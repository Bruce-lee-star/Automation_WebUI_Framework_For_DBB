package com.hsbc.cmb.hk.dbb.automation.framework.web.route.core;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 回归护盾：锁定 {@link ApiCaptureLifecycle#start(Page)} 的 Page 级监听器<b>幂等注册</b>。
 *
 * <p><b>背景</b>：修复前，重复调用 {@code start(page)} 会叠加多个 {@code page.onResponse} /
 * {@code page.onClose} 监听器，导致同一响应被兜底通道重复记录（破坏去重与计数），且监听器累积至
 * Page 关闭才解绑。修复后：同一 Page 无论 {@code start} 几次，{@code onResponse} /
 * {@code onClose} 仅注册一次；且 {@code stop(page)} 清理注册标记后，可再次 {@code start}
 * 重新挂上监听器（re-attach 场景）。
 *
 * <p>本测试用 Mockito 纯单测（mock Playwright {@code Page}/{@code BrowserContext}），
 * 不依赖浏览器运行时；与 {@link ApiCaptureLifecycle} 同包以访问其 package-private 静态方法。
 */
public class ApiCaptureLifecycleListenerTest {

    private Page page;
    private BrowserContext ctx;

    @Before
    public void setUp() {
        // 兜底采集默认开启，确保 onResponse 监听器会被注册
        ApiCaptureManager.setApiCaptureEnabled(true);
        page = mock(Page.class);
        ctx = mock(BrowserContext.class);
        when(page.context()).thenReturn(ctx);
    }

    @After
    public void tearDown() {
        // 清理静态会话集合，避免污染同包其它单测（如 activePageCount_empty_returns0）
        if (page != null) {
            ApiCaptureLifecycle.stop(page);
        }
        // 复位单例存储，避免按 url 隔离的断言跨测试残留
        ApiCaptureManager.getInstance().endApiCapture();
    }

    @Test
    public void start_samePageRepeatedly_registersListenersOnlyOnce() {
        //  关键不变量：同一 Page 多次 start，Page 级 onResponse / onClose 仅注册一次（幂等）
        ApiCaptureLifecycle.start(page);
        ApiCaptureLifecycle.start(page);
        ApiCaptureLifecycle.start(page);

        verify(page, times(1)).onResponse(any());
        verify(page, times(1)).onClose(any());
        // Context 级关闭钩子同样幂等（复用 CONTEXT_CLOSE_REGISTERED 的 putIfAbsent 保护）
        verify(ctx, times(1)).onClose(any());
    }

    @Test
    public void stopThenStart_reRegistersListeners() {
        ApiCaptureLifecycle.start(page);   // 注册 1 次
        ApiCaptureLifecycle.stop(page);    // 清理注册标记，允许重注册
        ApiCaptureLifecycle.start(page);   // re-attach：应再次挂上监听器

        //  关键不变量：stop 后 start 能重新注册，否则页面 re-attach 后采集静默失效
        verify(page, times(2)).onResponse(any());
        verify(page, times(2)).onClose(any());
    }

    @Test
    public void recordPassthrough_storesSingleCallWithOnResponseSource() {
        //  锁住兜底通道确实落库 + captureSource 残留值已从 PASSIVE 改为 ON_RESPONSE
        String url = "https://api.example.com/v1/accounts";
        ApiCaptureManager.getInstance().recordPassthrough(
                url, 200, "GET", Collections.emptyMap(), Collections.emptyMap());

        CapturedApiCall call = ApiCaptureManager.getInstance().getStore().getCallByUrl(url);
        assertNotNull("兜底通道应记录该 URL 的调用", call);
        assertEquals("兜底采集 captureSource 应为 ON_RESPONSE（去除 PASSIVE 残留）",
                "ON_RESPONSE", call.captureSource());
        assertEquals("兜底采集 handleType 应为 MONITOR",
                RouteHandleType.MONITOR, call.handleType());
    }
}
