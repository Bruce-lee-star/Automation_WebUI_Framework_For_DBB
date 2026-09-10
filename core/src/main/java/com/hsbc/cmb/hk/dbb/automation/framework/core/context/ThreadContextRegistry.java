package com.hsbc.cmb.hk.dbb.automation.framework.core.context;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * 上下文集中托管容器（CORE-P0-2）。
 *
 * <p>以 {@code WeakHashMap<Thread, TestContext>}（同步包装）持有 per-thread 上下文，替代裸
 * {@code ThreadLocal}：key 为弱引用，线程被 GC 后条目自动清除（防内存泄漏）；同时支持显式
 * 集中清理（{@link #remove(Thread)} / {@link #resetAll()}），杜绝线程池复用线程残留上一 scenario
 * 上下文导致的跨 scenario 串扰（core 地基级修复）。
 *
 * <p><b>不注册 ShutdownCoordinator</b>：core→common 反向依赖会破坏 G1（framework 切片无循环）架构门禁
 * （common→core 已由 {@code LanguageState} 存在）。JVM 关闭兜底清理非必需——线程结束由 WeakHashMap 弱 key
 * 自动清除，scenario 级清理由 {@link TestContextHolder#resetForCurrentThread()} 负责（CORE-P0-2 设计权衡）。
 *
 * <p>可观测：{@link #activeThreadCount()} 暴露当前活跃线程上下文数，供 {@link TestContextHolder}
 * 与监控（如 AsyncPool / MonitorFailureCollector）观测泄漏。
 */
final class ThreadContextRegistry {

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
                } catch (Throwable ignored) {
                    // 单个上下文清理异常不影响整体兜底
                }
            }
            CONTEXTS.clear();
        }
    }

    /** 当前活跃线程上下文数（可观测 / 泄漏排查）。 */
    static int activeThreadCount() {
        return CONTEXTS.size();
    }
}
