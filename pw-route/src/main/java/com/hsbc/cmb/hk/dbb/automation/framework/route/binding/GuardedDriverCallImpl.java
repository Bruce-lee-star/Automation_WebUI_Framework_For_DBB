package com.hsbc.cmb.hk.dbb.automation.framework.route.binding;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * {@link GuardedDriverCall} 默认实现：<b>单一专用驱动线程</b> + 主线程有界等待（行为零回归）。
 *
 * <p>经 {@code META-INF/services} 注册为 {@link GuardedDriverCall} 的 SPI 默认实现；
 * classpath 无显式注册时由 {@link GuardedDriverCallRegistry} 回退到本类。</p>
 *
 * <p><b>单线程化（T6，2026-09-29）</b>：Playwright 客户端要求对连接<b>外部串行</b>，
 * {@code Object doesn't exist} 是并发协议调用的直接产物。故所有同步驱动协议调用
 * （{@code context.route} / {@code unroute} 等）收敛到<b>单一专用驱动线程</b>
 * （守护线程、全局复用、不随 case 泄漏），消除共享连接上的并发竞态。
 * 实现形态参照 {@code AsyncPool.MONITOR_CALLBACK_EXECUTOR}：{@code volatile} 执行器 + 工厂构造、可复位重建。</p>
 *
 * <p><b>毒化重置（T6，2026-09-29）</b>：某次调用在界内无回包（超时）→ 主线程按策略 fail-fast / 降级返回，
 * 同时<b>仅重置驱动线程</b>：{@code compareAndSet} 把执行器换成新建实例、旧执行器 {@code shutdownNow()}
 * （卡死线程为 native 不可中断，仅遗弃为守护线程，随 GC 回收）。<b>绝不关闭 / 重建 BrowserContext 与 Page</b>
 * （Context 生命周期由 sessionKey 判据唯一掌管，见 FIX_PLAN I-9 / T2 裁定：路由是辅助设施，不得绑架会话）。</p>
 *
 * <p><b>范围说明</b>：本单线程化仅覆盖<b>同步</b>驱动协议调用（guarded bind/unroute）。
 * 拦截期 {@code route.fetch} / {@code page.request} 经 {@code BoundedOps} / IO 池并发执行
 * （串行化会回归并发请求吞吐，且其 channel 竞态已由 T2 的 claim / closeConfirmed 收敛），不在 T6 范围内。</p>
 *
 * <p><b>2026-09-29 全新评审（P0）—— 上文"毒化重置"段落已被取代</b>：{@code compareAndSet} 换新建实例的前提是
 * <b>在途调用确实结束了</b>。Native 调用不可中断时，换线程只会在同一连接上留下<b>第二个消息泵</b>，回包互相错取，
 * 实测新线程同样拿不到 ack（{@code reset#=2} 之后仍卡满 30s、用例依旧失败）。现行处置见 {@link #handleTimeout}：
 * 先摘后判，<b>未确认收尾则绝不新建线程</b>，复位交由会话层换 {@code Playwright} 实例（对外 API 里唯一"换连接"手段）。
 */
public final class GuardedDriverCallImpl implements GuardedDriverCall {

    private static final Logger LOGGER = LoggerFactory.getLogger(GuardedDriverCallImpl.class);

    /**
     * 单一专用驱动线程执行器（T6）：所有 guarded 驱动协议调用收敛于此线程。
     *
     * <p>镜像 {@code AsyncPool} 的 {@code MONITOR_CALLBACK_EXECUTOR}：{@code volatile} + 工厂构造，
     * 毒化时 CAS 换新建实例、旧实例遗弃，支持复位重建。守护线程、全局复用、不随 case 泄漏。</p>
     */
    /** 驱动线程代际（毒化重建后递增，便于日志区分新旧信道）。 */
    private static final AtomicInteger DRIVER_EPOCH = new AtomicInteger(0);

    /** 毒化重置累计次数（可观测，对齐 AsyncPool 计数风格；供单测与指标消费）。 */
    private static final AtomicLong POISON_RESETS = new AtomicLong(0);
    /** 信道故障（超时）累计次数：单调递增，供会话层判断"本用例是否污染过信道"。 */
    private static final AtomicLong CHANNEL_FAILURES = new AtomicLong(0);

    /** 信道是否已被判定不可用（存在未收尾的在途调用）；成功调用后自动恢复。 */
    private static final AtomicBoolean UNUSABLE = new AtomicBoolean(false);

    /** 超时后等待在途调用收尾的窗口（毫秒）：超过即认定"真卡死"，不再新建线程（避免第二个消息泵）。 */
    private static final long POISON_DRAIN_MS = 1_000L;

    /**
     * 单一专用驱动线程执行器（T6）：所有 guarded 驱动协议调用收敛于此线程。
     *
     * <p>镜像 {@code AsyncPool} 的 {@code MONITOR_CALLBACK_EXECUTOR}：{@code volatile} + 工厂构造，
     * 毒化时 CAS 换新建实例、旧实例遗弃，支持复位重建。守护线程、全局复用、不随 case 泄漏。</p>
     */
    private static final AtomicReference<ExecutorService> DRIVER =
            new AtomicReference<>(newDriverExecutor());

    private static ExecutorService newDriverExecutor() {
        int epoch = DRIVER_EPOCH.incrementAndGet();
        ThreadFactory tf = r -> {
            Thread t = new Thread(r, "route-v2-driver-" + epoch);
            t.setDaemon(true);
            t.setPriority(Thread.NORM_PRIORITY - 1);
            return t;
        };
        return Executors.newSingleThreadExecutor(tf);
    }

    /** 当前毒化重置累计次数（framework-internal 可观测）。 */
    static long poisonResetCount() {
        return POISON_RESETS.get();
    }

    @Override
    public <T> T guarded(String opName, long boundMs, OnTimeout policy, Callable<T> action) {
        ExecutorService driver = DRIVER.get();
        Future<T> future;
        try {
            future = driver.submit(action);
        } catch (RejectedExecutionException rejected) {
            //  信道已被判定不可用（执行器已停）：绝不新起线程 —— 在途调用未收尾时另起线程会在同一连接上
            //  产生第二个消息泵，回包互相错取。按策略确定处置：不静默吞、也不把辅助设施的不确定性升级为
            //  Context 重建（复位由会话层换 Playwright 实例完成）。
            UNUSABLE.set(true);
            if (policy == OnTimeout.FAIL_FAST) {
                throw new IllegalStateException("[Route] driver channel unusable (an earlier call did not stop); "
                        + "call '" + opName + "' rejected — rebuild the session to reset the channel", rejected);
            }
            LOGGER.warn("[Route] driver channel unusable — call '{}' skipped (degrade, Context/Page untouched)",
                    opName);
            return null;
        }
        T result;
        try {
            result = future.get(boundMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException timedOut) {
            // 驱动对协议调用无响应：毒化重置（仅驱动线程，不波及 Context），再按策略处置。
            handleTimeout(opName, boundMs);
            if (policy == OnTimeout.FAIL_FAST) {
                throw new IllegalStateException("[Route] driver call '" + opName
                        + "' 超时 " + boundMs + "ms 未收到回包（驱动忙 / 客户端共享连接被并发占用）；"
                        + "排查协议层收发请开 DEBUG=pw:channel");
            }
            LOGGER.warn("[Route] driver call '{}' exceeded {}ms — abandoning wait; "
                    + "driver thread poisoned & reset (Context/Page untouched), "
                    + "driver-side handler released on context close", opName, boundMs);
            return null;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            if (policy == OnTimeout.FAIL_FAST) {
                throw new IllegalStateException("[Route] driver call '" + opName
                        + "' 被中断（驱动线程重置）", interrupted);
            }
            LOGGER.warn("[Route] driver call '{}' interrupted — abandoning wait (degrade)", opName);
            return null;
        } catch (java.util.concurrent.ExecutionException executed) {
            Throwable cause = executed.getCause();
            if (policy == OnTimeout.WARN_AND_ABANDON) {
                LOGGER.warn("[Route] driver call '{}' failed: {}", opName, cause.toString());
                return null;
            }
            throw new IllegalStateException("[Route] driver call '" + opName + "' failed", cause);
        }
        //  调用成功 ⇒ 信道可确证可用（超时标记自动恢复；信道故障计数保持单调，供会话层判断是否被污染）
        UNUSABLE.set(false);
        return result;
    }

    /**
     * 毒化重置：仅把卡死的驱动线程信道重建为新建执行器，绝不波及 Context / Page。
     *
     * <p>只有「我们提交的同一执行器仍是当前实例」时才换（首个超时者负责重建，避免并发超时级联毒化健康线程）；
     * 若已被其他调用者先行重建，则仅记录，复用既有新建执行器（丢弃本次多余新建实例）。</p>
     */
    /**
     * 超时处置：先把在途调用从线程上摘掉，再**按"它是否真的结束"决定是否重建执行器**。
     *
     * <p><b>为什么不能无脑换线程（2026-09-29 全新评审，P0）</b>：Playwright Java 的客户端<b>没有独立读线程</b>
     * —— 谁发起协议调用，谁就负责把消息读干净（{@code Connection.sendMessage → ChannelOwner.runUntil →
     * processOneMessage}）。因此若在途调用<b>没有真正结束</b>就换一个线程，同一连接上就会<b>同时存在两个消息泵</b>，
     * 回包互相错取 ⇒ 新线程同样拿不到 ack。实测 dbb-3：{@code reset#=2} 之后新线程仍卡满 30s、用例依旧失败 ——
     * 换线程的收益没有兑现，却把共享信道弄脏并通过它传染给后续用例。</p>
     *
     * <p><b>判定与处置</b>：
     * <ol>
     *   <li>{@code shutdownNow()} 先让<b>可中断</b>的在途调用结束（interrupt）；</li>
     *   <li>在有界窗口 {@link #POISON_DRAIN_MS} 内等待其收尾：
     *     <ul>
     *       <li><b>已收尾</b> ⇒ 信道无残留泵，重建干净执行器（保留既有"自愈"语义，计数 {@code POISON_RESETS}）；</li>
     *       <li><b>仍未收尾</b>（native 不可中断）⇒ <b>绝不新建线程</b>：标记信道
     *           {@link #isChannelUsable() 不可用}，复位交由会话语义（换 {@code Playwright} 实例 ——
     *           对外 API 里唯一"换连接"的手段，配 session 缓存恢复登录态）。</li>
     *     </ul>
     *   </li>
     * </ol>
     *
     * <p>该处置<b>不波及 Context / Page</b>：路由是辅助设施，其信道不确定性不得绑架会话生命周期。</p>
     */
    private static void handleTimeout(String opName, long boundMs) {
        CHANNEL_FAILURES.incrementAndGet();
        ExecutorService current = DRIVER.get();
        current.shutdownNow();
        boolean drained;
        try {
            drained = current.awaitTermination(POISON_DRAIN_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            drained = false;
        }
        if (!drained) {
            UNUSABLE.set(true);
            LOGGER.error("[Route] driver call '{}' timed out {}ms and the in-flight call did NOT stop within {}ms — "
                            + "channel marked UNUSABLE and NO new driver thread is created (a second message pump on the "
                            + "same connection would steal the acks). Rebuild the session (new Playwright instance; the "
                            + "session cache restores the login) to reset the channel. Context/Page untouched.",
                    opName, boundMs, POISON_DRAIN_MS);
            return;
        }
        ExecutorService fresh = newDriverExecutor();
        if (DRIVER.compareAndSet(current, fresh)) {
            long resets = POISON_RESETS.incrementAndGet();
            LOGGER.error("[Route] route-v2 driver thread RESET after '{}' timed out {}ms — in-flight call confirmed "
                            + "stopped within {}ms, so no orphan pump is left on the connection (reset#={}, epoch -> {}, "
                            + "Context/Page untouched)",
                    opName, boundMs, POISON_DRAIN_MS, resets, DRIVER_EPOCH.get());
        } else {
            fresh.shutdownNow();
            LOGGER.warn("[Route] driver call '{}' timed out {}ms but the channel was already reset by another caller",
                    opName, boundMs);
        }
    }

    /** 信道当前是否可确证可用（{@code false} ⇒ 存在未收尾的在途调用；成功调用后自动恢复）。 */
    @Override
    public boolean isChannelUsable() {
        return !UNUSABLE.get();
    }

    /** 信道故障（超时）累计次数（单调递增；跨用例比较差值即可判断"本用例是否污染过信道"）。 */
    public static long channelFailureCount() {
        return CHANNEL_FAILURES.get();
    }

    /** 当前驱动线程代际（测试用：断言"没有新建线程"）。 */
    static int driverEpochForTest() {
        return DRIVER_EPOCH.get();
    }

    /**
     * 显式复位信道：换一条干净驱动线程并清除"不可用"标记。
     *
     * <p><b>只在确认连接已换（或套件收尾）之后调用</b>：在途调用未收尾时单方面换线程正是本类要杜绝的
     * "第二个消息泵"。会话层换 {@code Playwright} 实例后调用它，即可让信道与新连接对齐。</p>
     */
    public static void resetChannel() {
        ExecutorService fresh = newDriverExecutor();
        ExecutorService previous = DRIVER.getAndSet(fresh);
        if (previous != null) {
            previous.shutdownNow();
        }
        UNUSABLE.set(false);
        LOGGER.error("[Route] driver channel explicitly RESET (epoch -> {}); caller must have rebuilt the connection first",
                DRIVER_EPOCH.get());
    }
}
