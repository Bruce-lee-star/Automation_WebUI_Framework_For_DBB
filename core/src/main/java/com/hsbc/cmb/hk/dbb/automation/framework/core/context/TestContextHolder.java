package com.hsbc.cmb.hk.dbb.automation.framework.core.context;

/**
 * 持有当前线程的 {@link TestContext}（per-thread）。
 *
 * <p>这是 T3-1「先收拢」阶段的集中接入点：各模块把原 {@code static ThreadLocal} 改为
 * {@code TestContextHolder.get()...}。后续阶段可改为构造注入以彻底消灭本 Holder。
 */
public final class TestContextHolder {

    private static final ThreadLocal<TestContext> CURRENT =
            ThreadLocal.withInitial(ThreadLocalTestContext::new);

    private TestContextHolder() {
    }

    /** 获取当前线程的上下文（惰性创建）。 */
    public static TestContext get() {
        return CURRENT.get();
    }

    /**
     * scenario 结束时调用：清理本线程上下文并解除 ThreadLocal 绑定，
     * 避免线程池复用导致跨 scenario 串扰。
     */
    public static void resetForCurrentThread() {
        TestContext ctx = CURRENT.get();
        if (ctx != null) {
            ctx.clear();
        }
        CURRENT.remove();
    }
}
