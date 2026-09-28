package com.hsbc.cmb.hk.dbb.automation.framework.route.v2.diag;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 卡住诊断看门狗（route2 移植版，<b>临时诊断设施</b>）。
 *
 * <p>与 web 模块 {@code com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.event.HangWatchdog}
 * 同源设计，但<b>自包含</b>：route2 不依赖 web / common 模块，故独立实现、不共享类。职责一致 ——
 * 当 runtime 生命周期（≈ 一个 scenario 的 context 存活期）运行超阈值时，打印线程现场 + 硬超时
 * {@code interrupt} + 暴露 {@link HangEvent}，用于定位"卡在 route2 哪一步"。
 *
 * <p><b>为什么 route2 也需要</b>：route2 已在根因层用 {@code GuardedDriverCall} / {@code BoundedOps}
 * 设界（原生驱动调用不会无限挂起），但业务线程调 route2 API（如 {@code register} 等待 IO 池 /
 * pending 满）仍可能长时间阻塞；本看门狗提供最后兜底诊断，与根因防护正交。
 *
 * <p><b>临时</b>：与 web {@code HangWatchdog} 一并去除（去除清单见设计文档 §5）。放在 {@code diag}
 * 子包，便于整包移除，且不影响运行时依赖（仅诊断期使用，复用进程级 daemon 调度池）。</p>
 *
 * @apiNote framework-internal：诊断设施，非公开 API。
 */
public final class HangWatchdog {

    private static final Logger LOGGER = LoggerFactory.getLogger(HangWatchdog.class);

    /** 进程级共享 daemon 调度池（诊断设施，单线程足够；避免每 runtime 建池）。 */
    private static final ScheduledExecutorService SCHED = Executors.newSingleThreadScheduledExecutor(
            new ThreadFactory() {
                @Override
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "route-v2-hang-watchdog");
                    t.setDaemon(true);
                    return t;
                }
            });

    /** 概览中刻意跳过的 JVM 内务 / 自身线程（对定位卡住无价值，只增加噪音）。 */
    private static final String[] NOISE_THREAD_PREFIXES = {
            "Reference Handler", "Finalizer", "Signal Dispatcher", "Attach Listener",
            "Common-Cleaner", "Notification Thread", "process reaper", "Cleaner-",
            "route-v2-hang-watchdog"
    };

    private final String id;
    private volatile ScheduledFuture<?> sampler;
    private volatile Thread ownerThread;
    private volatile long startMs;
    private volatile boolean hardFired;
    private volatile HangEvent lastHang;
    /** "代"令牌：disarm 会自增，使在途采样立即作废，避免关闭后仍打印。 */
    private final AtomicLong token = new AtomicLong();

    public HangWatchdog(String id) {
        this.id = id;
    }

    /**
     * 武装：开始采样（幂等 —— 重复 arm 会先取消旧采样，故遗漏 disarm 也不会重复打印）。
     * 采样目标 = 调用 {@code arm()} 时的当前线程（即安装本 runtime 的业务线程）。
     */
    public void arm() {
        long interval = intervalMs();
        if (interval <= 0) {
            return; // 显式关闭
        }
        disarm();
        hardFired = false;
        lastHang = null;
        ownerThread = Thread.currentThread();
        startMs = System.currentTimeMillis();
        long t = token.incrementAndGet();
        try {
            sampler = SCHED.scheduleWithFixedDelay(() -> sample(t, interval), interval, interval, TimeUnit.MILLISECONDS);
        } catch (Throwable ignored) {
            // 诊断设施绝不影响主流程：调度失败即"无采样"，仅记 debug
            LOGGER.debug("[RouteV2-HangWatchdog] arm failed (sampling disabled): {}", ignored.getMessage());
        }
    }

    /** 解除武装：取消在途采样并使之后采样作废。 */
    public void disarm() {
        token.incrementAndGet();
        ScheduledFuture<?> f = sampler;
        sampler = null;
        if (f != null) {
            try {
                f.cancel(false);
            } catch (Throwable ignored) {
                LOGGER.debug("[RouteV2-HangWatchdog] cancel failed (non-fatal): {}", ignored.getMessage());
            }
        }
        ownerThread = null;
    }

    /** 是否已武装（供单测 / 诊断查询）。 */
    public boolean isArmed() {
        return sampler != null && ownerThread != null;
    }

    private void sample(long t, long interval) {
        try {
            if (t != token.get()) {
                return; // 已 disarm：本次采样作废
            }
            Thread owner = ownerThread;
            if (owner == null || !owner.isAlive()) {
                disarm(); // 业务线程已终止而 close 未到：自动解除武装，避免无意义刷屏
                return;
            }
            long elapsed = System.currentTimeMillis() - startMs;
            long hard = hardTimeoutMs();
            if (hard > 0 && !hardFired && elapsed >= hard) {
                fireHardTimeout(owner, elapsed);
            }
            LOGGER.warn(renderSample(owner, elapsed, interval));
        } catch (Throwable ignored) {
            LOGGER.debug("[RouteV2-HangWatchdog] sample failed (non-fatal): {}", ignored.getMessage());
        }
    }

    /** 渲染一次采样文本（package-private 以便单测断言格式）。 */
    static String renderSample(Thread owner, long elapsedMs, long interval) {
        StringBuilder sb = new StringBuilder(4096);
        sb.append("==== [RouteV2-HangWatchdog] runtime '").append(owner.getName())
                .append("' 已运行 ").append(elapsedMs).append("ms（采样间隔 ").append(interval)
                .append("ms）—— 疑似卡住，以下为线程现场；")
                .append("关闭：-Droute.v2.hang.watchdog.interval.ms=0 ====\n");
        sb.append("--- 业务线程 『").append(owner.getName()).append("』（state=")
                .append(owner.getState()).append("）完整栈 ---\n");
        for (StackTraceElement frame : owner.getStackTrace()) {
            sb.append("    at ").append(frame).append('\n');
        }
        Map<Thread, StackTraceElement[]> all = Thread.getAllStackTraces();
        sb.append("--- 其它线程概览（共 ").append(all.size()).append(" 个，各取首个非 JDK 帧）---\n");
        for (Map.Entry<Thread, StackTraceElement[]> entry : all.entrySet()) {
            Thread th = entry.getKey();
            if (th == owner || isNoiseThread(th.getName())) {
                continue;
            }
            sb.append("    [").append(th.getName()).append("] ").append(th.getState()).append(" | ")
                    .append(firstInterestingFrame(entry.getValue())).append('\n');
        }
        sb.append("==== [RouteV2-HangWatchdog] 采样结束（若停在 GuardedDriverCall/RouteIoExecutor 则是 route2 阻塞；"
                + "停在 Playwright sendMessage 则是协议层等待）====");
        return sb.toString();
    }

    /** 挂死事件（供报告 / 测试 hook 消费以标记 scenario 失败）。 */
    public static final class HangEvent {
        /** 用例标识（可能为 null）。 */
        public final String scenarioId;
        /** 触发时用例已运行毫秒数。 */
        public final long elapsedMs;
        /** 检测到挂死的时刻（epoch millis）。 */
        public final long detectedAtMs;

        HangEvent(String scenarioId, long elapsedMs, long detectedAtMs) {
            this.scenarioId = scenarioId;
            this.elapsedMs = elapsedMs;
            this.detectedAtMs = detectedAtMs;
        }

        @Override
        public String toString() {
            return "HangEvent{scenarioId='" + scenarioId + "', elapsedMs=" + elapsedMs
                    + ", detectedAtMs=" + detectedAtMs + '}';
        }
    }

    /** 硬超时处置：记录 ERROR + 尝试中断业务线程 + 留存挂死事件。 */
    private void fireHardTimeout(Thread owner, long elapsed) {
        hardFired = true;
        LOGGER.error("[RouteV2-HangWatchdog] HARD TIMEOUT: runtime '{}' ran {}ms exceeding hard limit — "
                + "interrupting owner thread (effective only for interruptible blocks); "
                + "consume via consumeHardHang() to fail the scenario", id, elapsed);
        try {
            owner.interrupt();
        } catch (Throwable ignored) {
            // interrupt 失败不影响诊断
            LOGGER.debug("[RouteV2-HangWatchdog] interrupt failed (non-fatal): {}", ignored.getMessage());
        }
        lastHang = new HangEvent(id, elapsed, System.currentTimeMillis());
    }

    /** 采样间隔（≤0 = 关闭）；系统属性不可读时按关闭处理，绝不让诊断设施影响主流程。 */
    private static long intervalMs() {
        try {
            return Long.getLong("route.v2.hang.watchdog.interval.ms", 60_000L);
        } catch (Throwable t) {
            return 0L;
        }
    }

    /** 硬超时阈值（≤0 = 禁用）；默认关闭以保持向后兼容。 */
    private static long hardTimeoutMs() {
        try {
            return Long.getLong("route.v2.hang.watchdog.hard.timeout.ms", 0L);
        } catch (Throwable t) {
            return 0L;
        }
    }

    /**
     * 取出并清除最近挂死事件（幂等）；测试 / 报告 hook 调用以标记 scenario 失败。
     *
     * @return 挂死事件，无则返回 {@code null}
     */
    public HangEvent consumeHardHang() {
        HangEvent e = lastHang;
        lastHang = null;
        return e;
    }

    private static boolean isNoiseThread(String name) {
        if (name == null) {
            return false;
        }
        for (String prefix : NOISE_THREAD_PREFIXES) {
            if (name.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /** 取首个"有信息量"的帧：跳过 JDK 反射 / 线程池样板帧，优先框架与 Playwright 帧。 */
    private static String firstInterestingFrame(StackTraceElement[] stack) {
        if (stack == null || stack.length == 0) {
            return "(无栈帧)";
        }
        for (StackTraceElement frame : stack) {
            String cls = frame.getClassName();
            if (cls.startsWith("com.hsbc.") || cls.startsWith("com.microsoft.playwright")) {
                return frame.toString();
            }
        }
        for (StackTraceElement frame : stack) {
            String cls = frame.getClassName();
            if (!cls.startsWith("java.") && !cls.startsWith("jdk.") && !cls.startsWith("sun.")) {
                return frame.toString();
            }
        }
        return stack[0].toString();
    }
}
