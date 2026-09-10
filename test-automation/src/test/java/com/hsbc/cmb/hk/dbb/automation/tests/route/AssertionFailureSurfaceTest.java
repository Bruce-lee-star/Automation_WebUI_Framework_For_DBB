package com.hsbc.cmb.hk.dbb.automation.tests.route;

import com.hsbc.cmb.hk.dbb.automation.framework.common.route.CaptureContext;
import com.hsbc.cmb.hk.dbb.automation.framework.common.route.RouteLifecycleRegistry;
import com.hsbc.cmb.hk.dbb.automation.framework.route.core.capture.ApiCaptureContext;
import com.hsbc.cmb.hk.dbb.automation.framework.route.core.lifecycle.RouteLifecycleImpl;
import com.microsoft.playwright.BrowserContext;
import org.junit.Test;

import java.util.Collections;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ROUTE-P0-1 回归：断言失败必须透出用例（不可静默漏测）。
 *
 * <p>复现机制（无需浏览器）：route 事件线程在页面 / 上下文失效时，
 * {@code RouteUtil.captureContext(route)} 无法解析 per-context 实例，回退 {@code ApiCaptureContext.getCurrent()}
 * 把失败标志落在全局 {@code SHARED} 实例；而步骤结束的失败聚合在<b>测试线程</b>（已绑定 per-context）经
 * 旧的 {@code getCurrentCapture()} 只读 per-context 实例 → 漏检 → 用例静默判 PASS。
 *
 * <p>本测试在独立未绑定线程上记录 {@code SHARED} 失败标志，断言：
 * <ul>
 *   <li>旧路径 {@code getCurrentCapture()} 在已绑定测试线程上读不到该失败（复现 bug）；</li>
 *   <li>新路径 {@code resolveFailureCapture()} 兜底命中 {@code SHARED} 失败（修复验证）。</li>
 * </ul>
 *
 * <p>并发安全：并发场景下各 scenario 均已绑定 per-context，{@code SHARED} 恒空，
 * 兜底解析不会产生跨 scenario 串扰（与 {@code RouteLifecycleImpl#resolveFailureCapture()} 语义一致）。
 */
public class AssertionFailureSurfaceTest {

    @Test
    public void sharedContextFailureIsSurfacedByListener() throws Exception {
        // 确保 RouteLifecycleImpl 静态自注册已触发（route 模块未在测试中显式启动）
        assertNotNull("RouteLifecycle 必须已自注册", RouteLifecycleImpl.class.getName());
        assertNotNull("RouteLifecycleRegistry.get() 必须非空", RouteLifecycleRegistry.get());

        BrowserContext ctx = mock(BrowserContext.class);
        // currentContextOrNull() 调用 pages() 探测关闭状态，返回空列表视为未关闭
        when(ctx.pages()).thenReturn(Collections.emptyList());

        ApiCaptureContext.bindCurrentContext(ctx);
        try {
            // 在独立未绑定线程上记录失败标志 —— 模拟 route 事件线程经 getCurrent() 回退到 SHARED
            Thread routeThread = new Thread(() ->
                    ApiCaptureContext.getCurrent().recordAssertionFailure(
                            "https://api.example.com/users", "STATUS", "200", "500",
                            "expected 200 but got 500"));
            routeThread.start();
            routeThread.join();

            // 旧路径：测试线程已绑定 per-context， getCurrentCapture() 读不到 SHARED 上的失败 → 复现漏检
            CaptureContext viaCurrent = RouteLifecycleRegistry.get().getCurrentCapture();
            assertFalse("复现 ROUTE-P0-1 旧 bug：getCurrentCapture() 漏检 SHARED 上的断言失败",
                    viaCurrent.hasAssertionFailures());

            // 新路径：resolveFailureCapture() 兜底命中 SHARED 失败 → 修复验证
            CaptureContext resolved = RouteLifecycleRegistry.get().resolveFailureCapture();
            assertNotNull("resolveFailureCapture() 不得返回 null", resolved);
            assertTrue("ROUTE-P0-1 修复：resolveFailureCapture() 应兜底命中 SHARED 上的断言失败",
                    resolved.hasAssertionFailures());
        } finally {
            ApiCaptureContext.resetCurrent();
            ApiCaptureContext.unbindCurrentContext();
        }
    }

    @Test
    public void noFailureResolvesToNonNullCurrent() {
        BrowserContext ctx = mock(BrowserContext.class);
        when(ctx.pages()).thenReturn(Collections.emptyList());

        ApiCaptureContext.bindCurrentContext(ctx);
        try {
            CaptureContext resolved = RouteLifecycleRegistry.get().resolveFailureCapture();
            assertNotNull("无失败时 resolveFailureCapture() 应返回非空当前上下文", resolved);
            assertFalse("无失败时不应报告断言失败", resolved.hasAssertionFailures());
        } finally {
            ApiCaptureContext.resetCurrent();
            ApiCaptureContext.unbindCurrentContext();
        }
    }
}
