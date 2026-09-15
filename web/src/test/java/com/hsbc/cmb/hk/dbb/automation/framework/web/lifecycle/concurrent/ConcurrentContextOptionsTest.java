package com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.concurrent;

import com.hsbc.cmb.hk.dbb.automation.framework.web.config.WebFrameworkConfig;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WEB-P1-5 种子测试 + W-6 回归：并发执行器选项（不可变、Builder 构造，无浏览器）。
 * 覆盖默认/显式取值、Builder 入参守卫、以及 resolvedParallelism 的「任务数 / 并行度 / 硬上限」三者取小。
 * <p>硬上限（W-6）由 {@code WebFrameworkConfig.PLAYWRIGHT_CONCURRENT_MAX} 配置，默认按 CPU 核数自适应，
 * 故依赖默认值的用例需显式设键或清键以保证确定性，避免与运行机核数耦合。
 */
public class ConcurrentContextOptionsTest {

    private static final String MAX_KEY = "serenity.playwright.concurrent.max";
    private final String originalMax = System.getProperty(MAX_KEY);

    @AfterEach
    void restoreMax() {
        if (originalMax == null) {
            System.clearProperty(MAX_KEY);
        } else {
            System.setProperty(MAX_KEY, originalMax);
        }
    }

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

        assertEquals(3, options.resolvedParallelism(3), "任务数更少时以任务数为准");
    }

    @Test
    public void resolvedParallelism_isBoundedByConfiguredParallelism() {
        // 显式抬高硬上限，使并行度成为绑定因子，验证 parallelism 在 cap 内约束 taskCount
        WebFrameworkConfig.PLAYWRIGHT_CONCURRENT_MAX.setValue("100");
        ConcurrentContextOptions options = ConcurrentContextOptions.builder().parallelism(8).build();
        assertEquals(8, options.resolvedParallelism(100));
    }

    @Test
    public void resolvedParallelism_isBoundedByHardCap() {
        // W-6：硬上限可经配置覆盖（不再是硬编码 16），此处显式设为 16 验证其约束并行度
        WebFrameworkConfig.PLAYWRIGHT_CONCURRENT_MAX.setValue("16");
        ConcurrentContextOptions options = ConcurrentContextOptions.builder().parallelism(32).build();
        assertEquals(16, options.resolvedParallelism(100), "硬上限约束并行度，防止过度并发压垮浏览器");
    }

    @Test
    public void resolvedParallelism_negativeCapFallsBackToFallback() {
        WebFrameworkConfig.PLAYWRIGHT_CONCURRENT_MAX.setValue("-1");
        ConcurrentContextOptions options = ConcurrentContextOptions.builder().parallelism(100).build();
        assertEquals(16, options.resolvedParallelism(100), "负配置回退 FALLBACK_HARD_CAP=16");
    }

    @Test
    public void resolvedParallelism_defaultCapIsAdaptiveAndBounded() {
        System.clearProperty(MAX_KEY); // 回落枚举自适应默认（核/2，下限 2 上限 32）
        ConcurrentContextOptions options = ConcurrentContextOptions.builder().parallelism(1000).build();
        int resolved = options.resolvedParallelism(1000);
        assertTrue(resolved >= 2 && resolved <= 32, "默认硬上限按核数自适应，下限 2 上限 32");
    }
}
