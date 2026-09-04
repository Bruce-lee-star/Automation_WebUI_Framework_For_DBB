package com.hsbc.cmb.hk.dbb.automation.tests.pages;

import com.hsbc.cmb.hk.dbb.automation.framework.web.page.Element;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.PageElement;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.base.impl.SerenityBasePage;

/**
 * E2E 真实浏览器验证沙箱页（Page Object）。
 *
 * <p>页面 HTML 位于 {@code test-automation/src/test/resources/pages/e2e-sandbox.html}，
 * 由 {@code BasePage.setContent(html)} 直接注入，因此<b>不依赖外网、不依赖 DBB 环境、不依赖 REST</b>，
 * 但执行的仍是真实 Chromium 浏览器，可作为后续 E2E 真实浏览器回归与并行隔离验证的稳定靶子。</p>
 *
 * <p>覆盖的真实浏览器能力：</p>
 * <ul>
 *   <li>表单输入（fill）与点击（click）</li>
 *   <li>同步 DOM 结果断言（登录成功/校验失败）</li>
 *   <li>异步元素出现（JS setTimeout 1.2s 后显示面板）—— 验证显式等待</li>
 *   <li>页面内状态累加（计数器）—— 验证各 scenario 浏览器上下文互相隔离</li>
 * </ul>
 *
 * <p>链式调用示例：
 * <pre>{@code
 * sandbox.username.fill("alice");
 * sandbox.signInBtn.click();
 * sandbox.message.getText();
 * }</pre>
 */
public class E2ESandboxPage extends SerenityBasePage {

    /** 页面主标题（h1），同时用于判断页面是否渲染完成。 */
    @Element("#title")
    public PageElement title;

    @Element("#username")
    public PageElement username;

    @Element("#password")
    public PageElement password;

    @Element("#signin")
    public PageElement signInBtn;

    /** 登录结果/校验错误信息容器。 */
    @Element("#message")
    public PageElement message;

    /** 触发异步加载的按钮。 */
    @Element("#loadAsync")
    public PageElement loadDataBtn;

    /** 计数器自增按钮。 */
    @Element("#increment")
    public PageElement incrementBtn;

    /** 计数器数值展示。 */
    @Element("#counter")
    public PageElement counter;

    /** 异步面板（初始隐藏，点击 Load Data 后约 1.2s 出现）。 */
    @Element("#asyncPanel")
    public PageElement asyncPanel;
}
