package com.hsbc.cmb.hk.dbb.automation.framework.web.page.engine;

import com.hsbc.cmb.hk.dbb.automation.framework.web.exceptions.AccessDeniedException;
import com.microsoft.playwright.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 导航状态守卫单测（无浏览器，纯 Mockito；无任何配置开关 —— 判定是硬编码的）。
 *
 * <p>钉住的口径：401/403/407 直接抛错并携带状态码与 URL；其余状态码放行；
 * null 响应与"取不到状态"（驱动竞态）不得成为新的失败点。</p>
 */
@DisplayName("导航状态守卫：403 等访问被拒直接抛错（硬编码，不加配置）")
public class NavigationStatusGuardTest {

    private static final String URL = "https://sit.example.com/portalserver/logon";

    private static Response response(int status) {
        Response response = mock(Response.class);
        when(response.status()).thenReturn(status);
        when(response.url()).thenReturn(URL);
        when(response.headers()).thenReturn(Map.of("server", "AkamaiGHost"));
        return response;
    }

    @Test
    @DisplayName("403 ⇒ AccessDeniedException，携带状态码/请求与最终 URL/边缘线索")
    public void enforce_403_throwsAccessDeniedException() {
        AccessDeniedException ex = assertThrows(AccessDeniedException.class,
                () -> NavigationStatusGuard.enforce(response(403), URL));

        assertEquals(403, ex.getStatus());
        assertEquals(URL, ex.getRequestedUrl());
        assertEquals(URL, ex.getFinalUrl());
        assertTrue(ex.getMessage().contains("403"), "失败信息须含状态码");
        assertTrue(ex.getMessage().contains(URL), "失败信息须含最终 URL");
        assertTrue(ex.getMessage().contains("server=AkamaiGHost"), "失败信息须含边缘节点线索（帮判因）");
    }

    @Test
    @DisplayName("401 / 407 同属访问被拒，也直接抛错")
    public void enforce_401And407_alsoThrow() {
        assertEquals(401, assertThrows(AccessDeniedException.class,
                () -> NavigationStatusGuard.enforce(response(401), URL)).getStatus());
        assertEquals(407, assertThrows(AccessDeniedException.class,
                () -> NavigationStatusGuard.enforce(response(407), URL)).getStatus());
    }

    @Test
    @DisplayName("放行：2xx/3xx、404、429、5xx（需要拦再按需加，不做配置矩阵）")
    public void enforce_toleratesOtherStatuses() {
        for (int status : new int[]{200, 302, 404, 429, 500, 503}) {
            assertDoesNotThrow(() -> NavigationStatusGuard.enforce(response(status), URL),
                    "状态码 " + status + " 不属访问被拒一族，应放行");
        }
    }

    @Test
    @DisplayName("null 响应（about:blank/setContent）与句柄取不到状态都放行")
    public void enforce_toleratesNullAndUnavailable() {
        assertDoesNotThrow(() -> NavigationStatusGuard.enforce(null, URL));

        Response broken = mock(Response.class);
        when(broken.status()).thenThrow(new IllegalStateException("Object doesn't exist: response@x"));
        assertDoesNotThrow(() -> NavigationStatusGuard.enforce(broken, URL),
                "句柄不可用属驱动竞态，交回原导航异常链路，不在守卫里变成新失败点");
    }
}
