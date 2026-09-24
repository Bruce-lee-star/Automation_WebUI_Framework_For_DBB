package com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle;

import com.hsbc.cmb.hk.dbb.automation.framework.core.lifecycle.ShutdownCoordinator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 关闭看门狗：Playwright 的 {@code close()} 无 timeout 选项（1.62.0 字节码已核实，
 * {@code Browser/BrowserContext.CloseOptions} 仅 {@code setReason}），故框架必须自行设限，
 * 防止 scenario/feature 收尾的 {@code close()} 阻塞 scenario 线程（IDE 直跑无 fork 超时兜底 → 永久卡死）。
 *
 * <p><b>语义</b>：用专属 daemon 线程执行关闭动作并 {@code join(limitMs)}；超限即放弃等待（动作仍在 daemon 上继续，
 * JVM 退出后由 Playwright driver 进程树回收兜底），并记 ERROR + 计入 {@link ShutdownCoordinator} 失败计数 ——
 * 关键是<b>绝不阻断</b>调用方线程（scenario 线程必须被释放）。</p>
 *
 * <p><b>不死后台线程（本次加固）</b>：超时后看门狗会<b>主动中断</b> worker（解阻塞任何可中断段）；worker 结束（正常或中断）
 * 时<b>自行从在途集合移除</b>，故后台线程<b>确定性回收、不残留</b>。对原生 CDP 调用（中断无效），worker 终将随
 * 调用方在超时路径<b>强收本线程 driver</b>（{@code BrowserCleanupImpl.forceReapThreadPlaywright}）使 {@code close()} 返回而死亡；
 * 另有 JVM 退出钩子兜底 drain，确保退出路径无泄漏。</p>
 */
public final class CloseGuard {

    private static final Logger logger = LoggerFactory.getLogger(CloseGuard.class);

    /** 单次 {@code BrowserContext.close()} 看门狗上限（毫秒）。context 关闭比 browser 轻。 */
    public static final long CONTEXT_CLOSE_LIMIT_MS = 1_500L;

    /** 单次 {@code Browser.close()} 看门狗上限（毫秒）。正常为毫秒级；超限即放弃等待 + 记失败。 */
    public static final long BROWSER_CLOSE_LIMIT_MS = 3_000L;

    /** 单次 {@code Page.close()} 看门狗上限（毫秒）。 */
    public static final long PAGE_CLOSE_LIMIT_MS = 1_000L;

    /** 收尾期轻量往返（cookies / newContext 探测）看门狗上限（毫秒）。 */
    public static final long ABSORB_OP_LIMIT_MS = 2_000L;

    /** 超时后强关本线程 Playwright driver 的兜底上限（毫秒）。 */
    public static final long FORCE_REAP_LIMIT_MS = 2_000L;

    /**
     * 在途 worker 线程跟踪：仅用于确定性回收与可观测性，杜绝"超时后后台线程放任不管"成为泄漏。
     * 手动 {@code add}（启动）/ {@code remove}（{@code finally} 正常结束或被中断后结束），故用强引用并发 Set
     * （非弱引用——我们需要确定性移除，弱引用会延迟回收、不利于断言无泄漏）。
     */
    private static final Set<Thread> IN_FLIGHT = ConcurrentHashMap.newKeySet();

    /** 关机钩子只注册一次。 */
    private static final AtomicBoolean HOOK_REGISTERED = new AtomicBoolean(false);

    private CloseGuard() {
    }

    static {
        if (HOOK_REGISTERED.compareAndSet(false, true)) {
            try {
                Runtime.getRuntime().addShutdownHook(
                        new Thread(CloseGuard::drainInFlight, "close-guard-shutdown-drain"));
            } catch (IllegalStateException e) {
                // JVM 已处于关闭序列，忽略
                logger.debug("[close-guard] shutdown hook registration skipped (JVM already shutting down)");
            }
        }
    }

    /**
     * 有界执行结果（不外露内部 Throwable 引用，仅暴露消息，规避 EI_EXPOSE_REP）。
     */
    public static final class Result {
        private final boolean completed;
        private final String errorMessage;

        Result(boolean completed, Throwable error) {
            this.completed = completed;
            this.errorMessage = error == null ? null : error.getMessage();
        }

        public boolean completed() {
            return completed;
        }

        public boolean hasError() {
            return errorMessage != null;
        }

        public String errorMessage() {
            return errorMessage;
        }
    }

    /**
     * 有界执行一次关闭动作，返回完成结果与异常。
     *
     * @param step     可定位的动作名（用于日志与失败计数）
     * @param action   关闭动作
     * @param limitMs  等待上限（毫秒）
     * @return 结果（completed=false 表示超时放弃；error 为动作内部抛出的异常）
     */
    public static Result runBoundedCapture(String step, Runnable action, long limitMs) {
        AtomicReference<Throwable> err = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try {
                action.run();
            } catch (Throwable t) {
                err.set(t);
                logger.warn("[close-bounded] Cleanup step '{}' failed (continuing): {}", step, t.getMessage());
            } finally {
                IN_FLIGHT.remove(Thread.currentThread());
            }
        }, "close-bounded-" + step);
        worker.setDaemon(true);
        IN_FLIGHT.add(worker);
        worker.start();
        try {
            worker.join(limitMs);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
        if (worker.isAlive()) {
            //  超时：主动中断 worker（尽力而为）。中断对原生 CDP 调用无效，但能解阻塞任何可中断段；
            //  原生挂死最终由调用方在超时路径强收本线程 driver 使 close() 返回、worker 自然死亡并自行移除。
            //  无论哪种，worker 终将结束、不会残留到 JVM 退出之后（另有 shutdown hook 兜底 drain）。
            worker.interrupt();
            ShutdownCoordinator.recordFailure(step + " exceeded " + limitMs + "ms and was abandoned "
                    + "(Playwright close() has no timeout option; worker interrupted and will be reclaimed)", null);
            return new Result(false, null);
        }
        return new Result(true, err.get());
    }

    /** 有界执行的布尔便捷方法（仅关心是否按时完成）。 */
    public static boolean runBounded(String step, Runnable action, long limitMs) {
        return runBoundedCapture(step, action, limitMs).completed();
    }

    /** 当前仍在后台执行的 worker 线程数（可观测性 + 单测断言"无线程泄漏"）。 */
    public static int inFlightCount() {
        return IN_FLIGHT.size();
    }

    /**
     * JVM 关闭前尽力中断并 join 所有仍存活的 worker（防御性兜底，保证退出路径无残留线程）。
     * 原生挂死且 driver 未被回收的 worker 此处只会等待 {@link #FORCE_REAP_LIMIT_MS} 后放弃，
     * 不无限阻塞 JVM 退出。
     */
    private static void drainInFlight() {
        for (Thread t : new ArrayList<>(IN_FLIGHT)) {
            if (t.isAlive()) {
                t.interrupt();
                try {
                    t.join(FORCE_REAP_LIMIT_MS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }
}
