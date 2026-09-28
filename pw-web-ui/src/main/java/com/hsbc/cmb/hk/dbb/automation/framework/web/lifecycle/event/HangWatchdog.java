package com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.event;

import com.hsbc.cmb.hk.dbb.automation.framework.common.async.AsyncPool;
import com.hsbc.cmb.hk.dbb.automation.framework.common.config.VerboseLogging;
import com.hsbc.cmb.hk.dbb.automation.framework.web.config.WebFrameworkConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 卡住诊断看门狗（2026-09-26）。
 *
 * <p><b>要解决的问题</b>：实测存在"用例卡住、但日志长时间零输出"的现象
 * （实测 1.txt：14:26:09 之后 6.5 分钟无任何日志），此时<b>无法判断阻塞在哪一次调用上</b>，
 * 只能事后手工 {@code jstack}，而卡住瞬间往往已经错过。
 *
 * <p><b>做法</b>：用例运行时长一旦超过 {@code serenity.playwright.hang.watchdog.interval.ms}
 * （默认 60s），即由 {@link AsyncPool} 定时打印一次现场：
 * <ul>
 *   <li><b>场景线程</b>的完整栈（含 {@code at ...} 帧）—— 直接指出阻塞在哪个方法/哪一行；</li>
 *   <li><b>其它线程概览</b>（名称 + 状态 + 首个非 JDK 帧）—— 用于区分"业务线程卡在 Playwright 协议栈"
 *       还是"卡在自家锁上"，也是判定 {@code Playwright}'s {@code Object doesn't exist} 类竞态的关键；</li>
 * </ul>
 *
 * <p><b>为什么默认开启且几乎无噪音</b>：健康用例通常远短于 60s，故<b>正常情况下零输出</b>；
 * 出现输出即代表"该用例确实跑久了"（完整 SSO 登录约 3 分钟会产生数次采样，属预期且有信息量）。
 * 关闭：{@code -Dserenity.playwright.hang.watchdog.interval.ms=0}。
 *
 * <p><b>硬超时（2026-09-28 复盘 P2 升级）</b>：当用例运行超过
 * {@code serenity.playwright.hang.watchdog.hard.timeout.ms}（默认 0=禁用，保持向后兼容）即判定为挂死，
 * 除打印 ERROR 现场外，主动 {@code scenarioThread.interrupt()} 尝试打断（对可中断卡死有效；Playwright 原生
 * poll 不可中断，但 P1 已在协议调用层设界，二者互补），并通过 {@link #consumeHardHang()} 暴露挂死事件供
 * 收尾/报告 hook 标记 scenario 失败（测试侧消费，prod 仅提供原语）。</p>
 *
 * <p><b>线程模型</b>：不新建线程，复用 {@link AsyncPool} 的调度器；采样任务自带"代"令牌
 * （{@link #ARM_TOKEN}），换用例或收尾后陈旧采样立即失效；场景线程已终止时自动解除武装。
 *
 * @apiNote framework-internal：诊断设施，非公开 API。
 */
public final class HangWatchdog {

    private static final Logger logger = LoggerFactory.getLogger(HangWatchdog.class);

    /** 当前已武装的"代"。换用例/收尾都会自增，使在途采样自失效（避免打印上一个用例的现场）。 */
    private static final AtomicLong ARM_TOKEN = new AtomicLong();

    /** 采样定时器（重新武装会先取消旧的）。 */
    private static volatile ScheduledFuture<?> sampler;

    /** 被采样的场景线程（武装时捕获 = 执行该用例的线程）。 */
    private static volatile Thread scenarioThread;
    private static volatile long scenarioStartMs;
    private static volatile String scenarioId;
    /** 硬超时是否已触发（per 武装代；onScenarioStart 重置）。 */
    private static volatile boolean hardFired;
    /** 最近一次挂死事件（供收尾/报告 hook 查询并标记 scenario 失败）。 */
    private static volatile HangEvent lastHang;

    /** 概览中刻意跳过的 JVM 内务线程（对定位卡住无价值，只增加噪音）。 */
    private static final String[] NOISE_THREAD_PREFIXES = {
            "Reference Handler", "Finalizer", "Signal Dispatcher", "Attach Listener",
            "Common-Cleaner", "Notification Thread", "process reaper", "Cleaner-"
    };

    private HangWatchdog() {
    }

    /**
     * 用例开始：武装看门狗（幂等 —— 会先取消上一次采样，故遗漏收尾也不会重复打印）。
     *
     * @param id 用例标识（仅用于日志可读性，可为 null）
     */
    public static void onScenarioStart(String id) {
        long interval = intervalMs();
        if (interval <= 0) {
            return; // 显式关闭
        }
        cancelSampler();
        hardFired = false;
        lastHang = null;
        scenarioThread = Thread.currentThread();
        scenarioStartMs = System.currentTimeMillis();
        scenarioId = id;
        final long token = ARM_TOKEN.incrementAndGet();
        try {
            sampler = AsyncPool.scheduleWithFixedDelay(() -> sample(token, interval), interval, interval);
        } catch (Throwable t) {
            // 诊断设施绝不影响主流程：调度失败即"无采样"，仅记 debug
            VerboseLogging.logDebugIfVerbose(logger, "[HangWatchdog] arm failed (sampling disabled): {}",
                    t.getMessage());
        }
    }

    /**
     * 用例收尾：解除武装并使在途采样失效。
     *
     * <p>刻意在收尾<b>入口</b>调用（而非末尾）：这样即使收尾本身抛异常也不会留下长期武装的采样；
     * 代价是"收尾阶段自身卡住"不被采样（该阶段已有 {@code CloseGuard} 限时兜底）。
     */
    public static void onScenarioEnd() {
        ARM_TOKEN.incrementAndGet();
        cancelSampler();
        scenarioThread = null;
        scenarioId = null;
    }

    /** 是否已武装（供单测/诊断查询）。 */
    public static boolean isArmed() {
        return sampler != null && scenarioThread != null;
    }

    private static void cancelSampler() {
        ScheduledFuture<?> f = sampler;
        sampler = null;
        if (f != null) {
            try {
                f.cancel(false);
            } catch (Throwable ignored) {
                // 取消失败不影响正确性：令牌已自增，采样不再输出；保留可观测性（不影响主流程）
                logger.debug("Sampler cancellation failed (non-fatal): {}", ignored.getMessage());
            }
        }
    }

    /** 采样间隔（≤0 = 关闭）；配置不可读时按关闭处理，绝不让诊断设施影响主流程。 */
    private static long intervalMs() {
        try {
            return WebFrameworkConfig.SERENITY_PLAYWRIGHT_HANG_WATCHDOG_INTERVAL_MS.getLongValue();
        } catch (Throwable t) {
            return 0L;
        }
    }

    /** 一次采样。 */
    private static void sample(long token, long interval) {
        try {
            if (token != ARM_TOKEN.get()) {
                return; // 已换用例 / 已收尾：本次采样作废
            }
            Thread owner = scenarioThread;
            if (owner == null || !owner.isAlive()) {
                onScenarioEnd(); // 场景线程已终止而收尾未到：自动解除武装，避免无意义刷屏
                return;
            }
            long elapsed = System.currentTimeMillis() - scenarioStartMs;
            long hard = hardTimeoutMs();
            if (hard > 0 && !hardFired && elapsed >= hard) {
                fireHardTimeout(owner, scenarioId, elapsed);
            }
            logger.warn(renderSample(owner, scenarioId, elapsed, interval));
        } catch (Throwable t) {
            VerboseLogging.logDebugIfVerbose(logger, "[HangWatchdog] sample failed: {}", t.getMessage());
        }
    }

    /**
     * 渲染一次采样文本（package-private 以便单测断言格式）。
     *
     * @param owner     场景线程
     * @param id        用例标识（可 null）
     * @param elapsedMs 该用例已运行的毫秒数
     * @param interval  采样间隔（毫秒）
     * @return 可直接写日志的多行文本
     */
    static String renderSample(Thread owner, String id, long elapsedMs, long interval) {
        StringBuilder sb = new StringBuilder(4096);
        sb.append("==== [HangWatchdog] 用例 ").append(id == null ? "(未知)" : "'" + id + "'")
                .append(" 已运行 ").append(elapsedMs).append("ms（采样间隔 ").append(interval)
                .append("ms）—— 疑似卡住或长时间无日志，以下为线程现场；")
                .append("关闭采样：-Dserenity.playwright.hang.watchdog.interval.ms=0 ====\n");

        sb.append("--- 场景线程 『").append(owner.getName()).append("』（state=")
                .append(owner.getState()).append("）完整栈 ---\n");
        StackTraceElement[] ownStack = owner.getStackTrace();
        if (ownStack.length == 0) {
            sb.append("    (无栈帧)\n");
        } else {
            for (StackTraceElement frame : ownStack) {
                sb.append("    at ").append(frame).append('\n');
            }
        }

        Map<Thread, StackTraceElement[]> all = Thread.getAllStackTraces();
        sb.append("--- 其它线程概览（共 ").append(all.size()).append(" 个线程，各取首个非 JDK 帧）---\n");
        for (Map.Entry<Thread, StackTraceElement[]> entry : all.entrySet()) {
            Thread t = entry.getKey();
            if (t == owner || isNoiseThread(t.getName())) {
                continue;
            }
            sb.append("    [").append(t.getName()).append("] ").append(t.getState()).append(" | ")
                    .append(firstInterestingFrame(entry.getValue())).append('\n');
        }
        sb.append("==== [HangWatchdog] 采样结束（若上方场景线程停在 Playwright 的 sendMessage/waitForMessage，"
                + "则为协议层等待；停在自家锁对象上则是锁竞争）====");
        return sb.toString();
    }

    // ── 硬超时（P2：升级为可打断 + 标记）──

    /** 挂死事件（供报告/测试 hook 消费以标记 scenario 失败）。 */
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

    /** 硬超时处置：记录 ERROR + 尝试中断场景线程 + 留存挂死事件。 */
    private static void fireHardTimeout(Thread owner, String id, long elapsed) {
        hardFired = true;
        logger.error("[HangWatchdog] HARD TIMEOUT: scenario '{}' ran {}ms exceeding hard limit — "
                + "interrupting scenario thread (effective only for interruptible blocks); "
                + "consume via consumeHardHang() to fail the scenario", id, elapsed);
        try {
            owner.interrupt();
        } catch (Throwable ignored) {
            logger.debug("[HangWatchdog] thread interrupt ignored (non-fatal)", ignored);
        }
        lastHang = new HangEvent(id, elapsed, System.currentTimeMillis());
    }

    /** 硬超时阈值（≤0 = 禁用）；与 interval 同源系统属性，默认关闭以保持向后兼容。 */
    private static long hardTimeoutMs() {
        try {
            return Long.getLong("serenity.playwright.hang.watchdog.hard.timeout.ms", 0L);
        } catch (Throwable t) {
            return 0L;
        }
    }

    /**
     * 取出并清除最近挂死事件（幂等）；测试/报告 hook 调用以标记 scenario 失败。
     *
     * @return 挂死事件，无则返回 {@code null}
     */
    public static HangEvent consumeHardHang() {
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

    /** 取首个"有信息量"的帧：跳过 JDK 反射/线程池样板帧，优先框架与 Playwright 帧。 */
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
