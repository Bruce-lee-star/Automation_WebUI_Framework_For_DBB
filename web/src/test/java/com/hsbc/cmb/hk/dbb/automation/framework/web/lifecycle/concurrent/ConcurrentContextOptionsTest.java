package com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.concurrent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * WEB-P1-5 种子测试：并发执行器选项（不可变、Builder 构造，无浏览器）。
 * 覆盖默认/显式取值、Builder 入参守卫、以及 resolvedParallelism 的「任务数 / 并行度 / 硬上限」三者取小。
 */
public class ConcurrentContextOptionsTest {

    @Test
    public void builder_defaultsAreSafe() {
        ConcurrentContextOptions options = ConcurrentContextOptions.builder().build();

        assertEquals(4, options.parallelism());
        assertFalse(options.failFast());
        assertEquals(0L, options.perTaskTimeoutMillis());
        assertFalse(options.useVirtualThreads());
    }

    @Test
    public void builder_appliesExplicitValues() {
        ConcurrentContextOptions options = ConcurrentContextOptions.builder()
                .parallelism(8)
                .failFast(true)
                .perTaskTimeoutMillis(5000L)
                .useVirtualThreads(true)
                .build();

        assertEquals(8, options.parallelism());
        assertEquals(true, options.failFast());
        assertEquals(5000L, options.perTaskTimeoutMillis());
        assertEquals(true, options.useVirtualThreads());
    }

    @Test
    public void build_rejectsNonPositiveParallelism() {
        assertThrows(IllegalArgumentException.class,
                () -> ConcurrentContextOptions.builder().parallelism(0).build());
    }

    @Test
    public void build_rejectsNegativeTimeout() {
        assertThrows(IllegalArgumentException.class,
                () -> ConcurrentContextOptions.builder().perTaskTimeoutMillis(-1L).build());
    }

    @Test
    public void resolvedParallelism_isBoundedByTaskCount() {
        ConcurrentContextOptions options = ConcurrentContextOptions.builder().parallelism(8).build();

        assertEquals( 3,  options.resolvedParallelism(3), "任务数更少时以任务数为准");
    }

    @Test
    public void resolvedParallelism_isBoundedByConfiguredParallelism() {
        ConcurrentContextOptions options = ConcurrentContextOptions.builder().parallelism(8).build();

        assertEquals(8, options.resolvedParallelism(100));
    }

    @Test
    public void resolvedParallelism_isBoundedByHardCapOfSixteen() {
        ConcurrentContextOptions options = ConcurrentContextOptions.builder().parallelism(32).build();

        assertEquals( 16,  options.resolvedParallelism(100), "硬上限 16，防止过度并发压垮浏览器");
    }
}
