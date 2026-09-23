package com.hsbc.cmb.hk.dbb.automation.framework.common.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CT2-17 契约：{@link LazyInit} 的 {@code reset()} 语义。
 *
 * <p>原实现无 reset，组件一旦 {@code shutdown()} 便<b>永久不可恢复</b> —— 同一 JVM 内后续套件
 * 要么静默丢任务、要么抛 {@code RejectedExecutionException}（两种相反后果）。
 */
class LazyInitTest {

    @Test
    @DisplayName("CT2-17：reset() 后 ensure() 必须重新执行初始化（关闭后可恢复）")
    void resetAllowsReinitialization() {
        AtomicInteger runs = new AtomicInteger();
        LazyInit init = new LazyInit("TestComponent", () -> runs.incrementAndGet());

        init.ensure();
        init.ensure();
        assertEquals(1, runs.get(), "成功初始化后 ensure() 应幂等，不得重复执行");
        assertTrue(init.isInitialized());

        init.reset();
        assertFalse(init.isInitialized(), "reset 后应回到「未初始化」状态");

        init.ensure();
        assertEquals(2, runs.get(), "reset 后 ensure() 必须重新执行初始化（可恢复）");
        assertTrue(init.isInitialized());
    }

    @Test
    @DisplayName("CT2-17：reset() 是唯一的重试入口（默认 ensure 失败不重试）")
    void resetClearsCachedFailure() {
        AtomicInteger runs = new AtomicInteger();
        LazyInit init = new LazyInit("TestComponent", () -> {
            if (runs.incrementAndGet() == 1) {
                throw new IllegalStateException("boom");
            }
        });

        assertThrows(IllegalStateException.class, init::ensure, "首次初始化失败应抛出清晰异常");
        assertThrows(IllegalStateException.class, init::ensure, "默认策略：失败后不重试");
        assertEquals(1, runs.get(), "默认策略下初始化只尝试一次");

        init.reset();
        init.ensure();
        assertEquals(2, runs.get(), "reset 后应允许重试并成功");
        assertTrue(init.isInitialized(), "重试成功后应标记为已初始化");
    }
}
