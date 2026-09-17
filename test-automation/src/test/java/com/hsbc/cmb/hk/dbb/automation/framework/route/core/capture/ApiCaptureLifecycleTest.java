package com.hsbc.cmb.hk.dbb.automation.framework.route.core.capture;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import com.hsbc.cmb.hk.dbb.automation.framework.route.core.capture.ApiCaptureContext;
import com.hsbc.cmb.hk.dbb.automation.framework.route.core.capture.ApiCaptureLifecycle;

/**
 * 固化 Phase 5 抽离出的 {@link ApiCaptureLifecycle} 契约（与拆分前语义严格一致）。
 *
 * <p>覆盖可纯单测的核心不变量（不依赖 Playwright 运行时对象）：
 * <ul>
 *   <li>当前线程绑定的 ThreadLocal 在 {@code unbind} / {@code bind(null)} 后正确解除；</li>
 *   <li>空会话状态下活动 Page / Context 计数为 0；</li>
 *   <li>{@link ApiCaptureContext} 公开转发壳签名不变（null 入参仍按原语义抛 IllegalArgumentException）。</li>
 * </ul>
 * 与 {@link ApiCaptureLifecycle} 同包，可访问其 package-private 静态方法。
 * 真实的 Page/Context 采集编排由 CI 集成护盾（需 Playwright 运行时）覆盖。
 */
public class ApiCaptureLifecycleTest {

    @Test
    public void unbindCurrentContext_thenCurrentContextOrNullIsNull() {
        ApiCaptureLifecycle.unbindCurrentContext();
        assertNull(ApiCaptureLifecycle.currentContextOrNull());
    }

    @Test
    public void bindNull_unbindsCurrentContext() {
        ApiCaptureLifecycle.bindCurrentContext(null);
        assertNull(ApiCaptureLifecycle.currentContextOrNull(), "bind(null) 应解绑当前线程");
    }

    @Test
    public void activePageCount_empty_returns0() {
        assertEquals(0, ApiCaptureLifecycle.activePageCount());
    }

    @Test
    public void activeContextCount_empty_returns0() {
        assertEquals(0, ApiCaptureLifecycle.activeContextCount());
    }

    @Test
    public void apiCaptureContext_start_nullContext_throws() {
        assertThrows(IllegalArgumentException.class, () -> ApiCaptureContext.start((BrowserContext) null));
    }

    @Test
    public void apiCaptureContext_start_nullPage_throws() {
        assertThrows(IllegalArgumentException.class, () -> ApiCaptureContext.start((Page) null));
    }
}
