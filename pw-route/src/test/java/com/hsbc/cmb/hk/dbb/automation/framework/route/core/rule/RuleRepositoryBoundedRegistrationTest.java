package com.hsbc.cmb.hk.dbb.automation.framework.route.core.rule;

import com.hsbc.cmb.hk.dbb.automation.framework.route.core.engine.RouteContextState;
import com.hsbc.cmb.hk.dbb.automation.framework.route.core.engine.RouteException;
import com.microsoft.playwright.BrowserContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 原生路由注册<b>有界性</b>契约守卫（2026-09-26 卡死根因修复）。
 *
 * <p><b>要守住的缺陷（内网实测）</b>：Playwright 的 {@code context.route(...)} 底层是
 * {@code setNetworkInterceptionPatterns} 且<b>无客户端超时</b>（playwright 1.62
 * {@code BrowserContextImpl.updateInterceptionPatterns} 传 {@code NO_TIMEOUT}）。浏览器不回 ACK 时调用方
 * <b>永久阻塞</b>：{@code main} 线程停在 {@code RuleRepository.registerRouteToContext → BrowserContext.route}，
 * 两次看门狗采样（240s / 300s）停在<b>同一帧</b>，整轮用例挂死。
 *
 * <p><b>守住的契约</b>：
 * <ol>
 *   <li><b>有界</b>：预算内未完成即失败，绝不无限等待；</li>
 *   <li><b>快速失败语义化</b>：抛 {@code RouteConnectionUnresponsiveException}，并指出无超时的底层命令；</li>
 *   <li><b>标记传播</b>：超时即标记该 Context「连接无响应」⇒ 后续注册<b>立即</b>失败（不各付一次预算），
 *       且不再发起原生往返；web 侧据此在下一个用例重建浏览器；</li>
 *   <li><b>零回归</b>：正常路径返回原生句柄；注册自身抛错时原样上抛（保留调用方回滚逻辑）。</li>
 * </ol>
 */
class RuleRepositoryBoundedRegistrationTest {

    @Test
    @DisplayName("浏览器不回 ACK：在预算内有界失败 + 标记连接无响应 + 信息指出无超时命令")
    void registerFailsFastWhenBrowserDoesNotAck() throws Exception {
        BrowserContext ctx = mock(BrowserContext.class);
        CountDownLatch release = new CountDownLatch(1);
        when(ctx.isClosed()).thenReturn(false);
        when(ctx.route(anyString(), any())).thenAnswer(invocation -> {
            assertTrue(release.await(30, TimeUnit.SECONDS), "release not fired within 30s"); // 模拟"浏览器无响应"（底层无客户端超时）
            return (AutoCloseable) () -> { };
        });
        try {
            long t0 = System.currentTimeMillis();
            RouteException thrown = assertThrows(RouteException.class,
                    () -> RuleRepository.registerNativeRouteBounded(ctx, "**/never-acks/**", List.of(), 200L));
            long elapsed = System.currentTimeMillis() - t0;

            assertTrue(thrown instanceof RouteException.RouteConnectionUnresponsiveException,
                    "必须是「连接无响应」语义化异常，便于上层区分于可降级的注册失败");
            assertTrue(elapsed >= 200L && elapsed < 3_000L,
                    "必须有界失败（预算=200ms，实测 " + elapsed + "ms）");
            assertTrue(RouteContextState.isUnresponsive(ctx), "超时后必须标记该 Context 无响应");
            assertTrue(thrown.getMessage().contains("setNetworkInterceptionPatterns"),
                    "异常信息应指出『无客户端超时』的底层命令，便于现场定位");
        } finally {
            release.countDown();
            RouteContextState.removeContextFromAllRegistries(ctx);
        }
    }

    @Test
    @DisplayName("已标记无响应的 Context：后续注册立即失败，且不再发起原生往返（不重复付预算）")
    void registerFailsImmediatelyOnceFlagged() {
        BrowserContext ctx = mock(BrowserContext.class);
        when(ctx.isClosed()).thenReturn(false);
        RouteContextState.markUnresponsive(ctx);
        try {
            long t0 = System.currentTimeMillis();
            assertThrows(RouteException.RouteConnectionUnresponsiveException.class,
                    () -> RuleRepository.registerNativeRouteBounded(ctx, "**/flagged/**", List.of(), 5_000L));
            assertTrue(System.currentTimeMillis() - t0 < 200L,
                    "已标记无响应时必须立即失败，不再等待（否则每条规则各付一次预算）");
            verify(ctx, never()).route(anyString(), any());
        } finally {
            RouteContextState.removeContextFromAllRegistries(ctx);
        }
    }

    @Test
    @DisplayName("正常路径：返回原生句柄（供确定性注销），且不标记无响应")
    void registerReturnsHandleOnHappyPath() throws Exception {
        BrowserContext ctx = mock(BrowserContext.class);
        when(ctx.isClosed()).thenReturn(false);
        AutoCloseable handle = mock(AutoCloseable.class);
        when(ctx.route(anyString(), any())).thenReturn(handle);

        long t0 = System.currentTimeMillis();
        AutoCloseable result = RuleRepository.registerNativeRouteBounded(ctx, "**/ok/**", List.of(), 5_000L);

        assertSame(handle, result, "必须返回 context.route(...) 句柄（跨用例栅栏依赖它做确定性注销）");
        assertTrue(System.currentTimeMillis() - t0 < 2_000L, "正常路径不得引入可观测等待");
        assertFalse(RouteContextState.isUnresponsive(ctx), "正常路径不得标记无响应");
    }

    @Test
    @DisplayName("注册自身抛错：原样上抛（保留类型与实例，供调用方回滚内存状态）")
    void registerPropagatesUnderlyingFailure() {
        BrowserContext ctx = mock(BrowserContext.class);
        when(ctx.isClosed()).thenReturn(false);
        IllegalStateException boom = new IllegalStateException("invalid pattern");
        when(ctx.route(anyString(), any())).thenThrow(boom);

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> RuleRepository.registerNativeRouteBounded(ctx, "**/bad/**", List.of(), 5_000L));

        assertSame(boom, thrown, "可降级失败必须原样上抛，不得被包装成'连接无响应'");
        assertFalse(RouteContextState.isUnresponsive(ctx), "非超时失败不得误标无响应");
    }
}
