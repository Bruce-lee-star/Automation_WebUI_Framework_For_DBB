package framework.webbridge;

import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.PlaywrightManager;
import com.microsoft.playwright.APIRequestContext;
import com.microsoft.playwright.Playwright;

/**
 * {@link RouteRuntimeBridge} 的 web 侧宿主实现：经 web 模块稳定的公开契约
 * {@link PlaywrightManager#getPlaywright()} 获取 {@code Playwright} 实例，并创建<b>不绑定
 * Page/Frame</b>的独立 {@link APIRequestContext}，供 route 模块在「页面跳转导致 Frame 销毁」的
 * 竞态窗口内仍能完成 interceptResponse 透传 fetch（T-挂死根因修复）。
 *
 * <p>本类驻中立桥接模块（非 pw-web-ui、非 test-automation），经 SPI 注册后被 route 模块在运行时
 * 自动发现，零编译期耦合。调用方（{@code RoutePassThroughFetcher}）负责关闭返回的 context。</p>
 */
public class WebRouteRuntimeBridge implements RouteRuntimeBridge {

    @Override
    public APIRequestContext createApiRequestContext() {
        Playwright pw = PlaywrightManager.getPlaywright();
        if (pw == null) {
            return null;
        }
        return pw.request().newContext();
    }
}
