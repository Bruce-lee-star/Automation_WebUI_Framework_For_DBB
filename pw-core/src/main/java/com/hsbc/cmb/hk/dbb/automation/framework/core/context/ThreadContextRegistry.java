package com.hsbc.cmb.hk.dbb.automation.framework.core.context;

import com.hsbc.cmb.hk.dbb.automation.framework.core.lifecycle.ShutdownCoordinator;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 上下文集中托管容器（CORE-P0-2）。
 *
 * <p>以 {@code WeakHashMap<Thread, TestContext>}（同步包装）持有 per-thread 上下文，替代裸
 * {@code ThreadLocal}：key 为弱引用，线程被 GC 后条目自动清除（防内存泄漏）；同时支持显式
 * 集中清理（{@link #remove(Thread)} / {@link #resetAll()}），杜绝线程池复用线程残留上一 scenario
 * 上下文导致的跨 scenario 串扰（core 地基级修复）。
 *
 * <p><b>D5-1：现已登记 {@link ShutdownCoordinator}</b>。此前因 {@code ShutdownCoordinator} 位于
 * {@code common} 切片，core 引用它会形成 core↔common 循环、破坏 G1（framework 切片无循环）门禁，
 * 只能被迫放弃登记；下沉到 {@code core.lifecycle} 后方向收敛为单向，故此处可正常登记 JVM 退出兜底清理
 * （顺序 {@link ShutdownCoordinator#ORDER_TEST_CONTEXT}，最后执行，避免影响其它关闭任务）。
 * 常规清理仍由线程结束（WeakHashMap 弱 key）与
 * {@link TestContextHolder#resetForCurrentThread()} 负责，本登记只是兜底。
 *
 * <p>可观测：{@link #activeThreadCount()} 暴露当前活跃线程上下文数，供 {@link TestContextHolder}
 * 与监控（如 AsyncPool / MonitorFailureCollector）观测泄漏。
 */
final class ThreadContextRegistry {

    static {
        //  D5-1：资源登记契约回归 —— JVM 退出兜底清空全部线程上下文（顺序最后，不干扰其它关闭任务）
        ShutdownCoordinator.register(ShutdownCoordinator.ORDER_TEST_CONTEXT,
                "test-context", ThreadContextRegistry::resetAll);
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(ThreadContextRegistry.class);

    private static final Map<Thread, TestContext> CONTEXTS =
            Collections.synchronizedMap(new WeakHashMap<>());

    private ThreadContextRegistry() {
    }

    /** 取当前线程上下文（惰性创建并登记）。 */
    static TestContext get() {
        Thread t = Thread.currentThread();
        synchronized (CONTEXTS) {
            TestContext ctx = CONTEXTS.get(t);
            if (ctx == null) {
                ctx = new ThreadLocalTestContext();
                CONTEXTS.put(t, ctx);
            }
            return ctx;
        }
    }

    /** 移除并清理指定线程的上下文（线程池复用前复位，防串扰）。 */
    static void remove(Thread thread) {
        TestContext ctx;
        synchronized (CONTEXTS) {
            ctx = CONTEXTS.remove(thread);
        }
        if (ctx != null) {
            ctx.clear();
        }
    }

    /** 清理全部线程上下文（套件结束 / 显式兜底；单个清除异常不中断其余）。 */
    static void resetAll() {
        synchronized (CONTEXTS) {
            for (TestContext ctx : CONTEXTS.values()) {
                try {
                    ctx.clear();
                } catch (Throwable e) {
                    // 单个上下文清理异常不影响整体兜底，但不得静默（D7-3）
                    LOGGER.warn("[ThreadContextRegistry] resetAll: clear failed for one context: {}",
                            e.toString());
                }
            }
            CONTEXTS.clear();
        }
    }

    /** 当前活跃线程上下文数（可观测 / 泄漏排查）。 */
    static int activeThreadCount() {
        return CONTEXTS.size();
    }

    /**
     * CT2-08：对<b>所有</b>已登记线程上下文执行受控遍历（读取 / 清理）。
     *
     * <p>供套件级收尾使用：清理那种<b>按线程</b>存放的缓存时，若只清当前线程，其它 worker 线程
     * （线程池复用 / 并行 scenario）的缓存及其持有的 Page/Context 引用会跨用例、跨套件滞留。
     *
     * <p>遍历在 CONTEXTS 锁内取<b>快照</b>后于锁外执行，避免遍历期间并发登记/移除导致 CME，
     * 也避免持锁回调业务清理逻辑放大死锁面。单个上下文清理异常不影响其余（D7-3：不静默）。
     */
    static void forEachContext(Consumer<TestContext> visitor) {
        if (visitor == null) {
            return;
        }
        final List<TestContext> snapshot;
        synchronized (CONTEXTS) {
            snapshot = new ArrayList<>(CONTEXTS.values());
        }
        for (TestContext ctx : snapshot) {
            try {
                visitor.accept(ctx);
            } catch (Throwable e) {
                LOGGER.warn("[ThreadContextRegistry] forEachContext: visitor failed for one context: {}",
                        e.toString());
            }
        }
    }
}
