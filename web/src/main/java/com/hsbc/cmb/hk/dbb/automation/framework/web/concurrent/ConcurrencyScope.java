package com.hsbc.cmb.hk.dbb.automation.framework.web.concurrent;

/**
 * 并发闸门作用域（AutoCloseable）：严格保证 {@link ConcurrencyGate#acquire} 与
 * {@link ConcurrencyGate#release} 配对，即使 scenario 抛异常也不泄漏信号量。
 *
 * <p>runner 无关集成原语：在 scenario 建立登录后的生命周期 hook 中
 * {@code try (ConcurrencyScope s = ConcurrencyGate.enter(resolver)) { ... }}，
 * 退出 try 块（正常或异常）即自动 release。key==null 时为 no-op scope。</p>
 */
public final class ConcurrencyScope implements AutoCloseable {

    private final ConcurrencyPartitionKey key;
    private boolean closed = false;

    ConcurrencyScope(ConcurrencyPartitionKey key) {
        this.key = key;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        ConcurrencyGate.release(key);
    }
}
