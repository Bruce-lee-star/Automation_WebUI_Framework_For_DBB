package framework.webbridge;

import com.microsoft.playwright.APIRequestContext;

/**
 * 桥接契约：允许 route 模块在<b>不依赖 web 模块</b>的前提下，获取一个<b>不绑定 Page/Frame</b>的
 * 独立 {@link APIRequestContext}，用于「拦截真实响应（interceptResponse）」场景下的透传 fetch。
 *
 * <p><b>背景（T-挂死根因）</b>：{@link com.microsoft.playwright.Route#fetch} 绑定到发起请求的
 * Page/Frame；当页面在异步透传窗口内发生跳转（Frame 已销毁）时会抛
 * {@code Object doesn't exist: frame@...}，导致 mock 的字段改写失效、档案页被遮罩挡住。
 * 独立 {@link APIRequestContext} 不依赖 Frame，可安全完成透传，<b>根治该竞态</b>。</p>
 *
 * <p><b>解耦设计（依赖倒置）</b>：本接口驻中立模块 {@code pw-route-web-bridge}（包
 * {@code framework.webbridge}），由 route 依赖、由 web 侧实现，从而：
 * ① 不把 Playwright 引入 core（保持 core 纯净）；
 * ② 不让 pw-web-ui 依赖 route（保持 "Excludes route"）；
 * ③ 不让 route 依赖 framework.web（保持 ArchUnit L6）。
 * 实现经 SPI（{@code META-INF/services/framework.webbridge.RouteRuntimeBridge}）注册，
 * 无实现时调用方回退到 {@code route.fetch()}。</p>
 */
public interface RouteRuntimeBridge {

    /**
     * 创建一个独立的、不绑定任何 Page/Frame 的 {@link APIRequestContext}。
     * 调用方负责在使用后 {@code close()}（建议 try/finally）。
     *
     * @return 独立 APIRequestContext；若底层 Playwright 尚未初始化可返回 {@code null}
     *         （调用方须回退到 {@code route.fetch()}）
     */
    APIRequestContext createApiRequestContext();
}
