package com.hsbc.cmb.hk.dbb.automation.framework.route.handler;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MonitorHandler body 读取重试策略守卫（2026-09-17 评审落地）。
 *
 * <p>背景：body 读取重试链在调度器上自我续投，等待方（route 事件线程）以
 * {@code future.get(budget)} 阻塞等待。原实现把「基础尝试次数 / 间隔」硬编码，且预算
 * 随 DELAY 线性放大<b>无上限</b>——长 DELAY（如 60s → 1200 次尝试）会把事件线程占住分钟级，
 * 一旦链断/调度器异常即表现为"程序卡死"。现将三者可配（{@code monitor.body.read.*}）并把
 * 预算收敛为 {@code min(尝试总时长 + 余量, 上限)}，由本测试固化。
 */
class MonitorHandlerRetryPolicyTest {

    /** 与实现一致的预算余量（超出尝试总时长后的调度抖动预留）。 */
    private static final long MARGIN = 5_000L;

    @Test
    void budgetIsCappedByMaxWait() {
        assertEquals(3 * 50L + MARGIN, MonitorHandler.computeBudgetMs(3, 50, 30_000),
                "短重试链的预算 = 尝试总时长 + 余量");
        assertEquals(30_000L, MonitorHandler.computeBudgetMs(1200, 50, 30_000),
                "长 DELAY 放大尝试数时，预算必须被上限截断（否则事件线程可能被占住分钟级）");
        assertEquals(1200 * 50L + MARGIN, MonitorHandler.computeBudgetMs(1200, 50, 0),
                "maxWait<=0 表示不设上限（仅用尝试总时长）");
    }

    @Test
    void maxAttemptsGrowsWithDelay() {
        assertEquals(3, MonitorHandler.computeMaxAttempts(0, 3, 50), "无 DELAY 时仅基础尝试次数");
        assertEquals(24, MonitorHandler.computeMaxAttempts(1_000, 3, 50),
                "DELAY=1000ms/间隔 50ms → 21 次额外 + 3 基础 = 24");
    }

    @Test
    void cancelForNullOrUnknownContextIsNoop() {
        assertEquals(0, MonitorHandler.cancelPendingBodyReadsFor(null), "null context 应为 no-op");
    }

    @Test
    void configuredPolicyDefaultsAreSane() {
        assertTrue(MonitorHandler.baseAttempts() >= 1, "基础尝试次数至少为 1");
        assertTrue(MonitorHandler.retryIntervalMs() >= 1, "重试间隔至少为 1ms");
        assertTrue(MonitorHandler.maxWaitMs() > 0, "默认必须有等待上限，保证事件线程等待有界");
    }
}
