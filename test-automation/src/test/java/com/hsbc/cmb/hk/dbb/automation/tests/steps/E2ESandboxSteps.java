package com.hsbc.cmb.hk.dbb.automation.tests.steps;

import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.PlaywrightManager;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.factory.PageObjectFactory;
import com.hsbc.cmb.hk.dbb.automation.tests.pages.E2ESandboxPage;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import net.serenitybdd.annotations.Step;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

/**
 * E2E 沙箱流程（Steps 层，负责 Serenity 报告步骤与断言）。
 *
 * <p>页面通过 {@code setContent} 注入 classpath 上的静态 HTML，避免 file:// 与本地服务依赖，
 * 保证每次执行环境一致、结果可复现。</p>
 */
public class E2ESandboxSteps {

    private static final Logger logger = LoggerFactory.getLogger(E2ESandboxSteps.class);

    /** 沙箱页面在 test classpath 上的位置（对应 src/test/resources/pages/e2e-sandbox.html）。 */
    private static final String SANDBOX_HTML = "/pages/e2e-sandbox.html";

    private final E2ESandboxPage sandbox = PageObjectFactory.getPage(E2ESandboxPage.class);

    /**
     * 打开沙箱页：注入 HTML 并等待主标题渲染完成。
     */
    @Step
    public void openSandbox() {
        // ⚠ 必须先跳 about:blank：setContent() 只替换 DOM 而不改变页面 URL，
        // 新建页面会被框架按 routedemo.conf 的基础 URL（默认 http://localhost:8888/demo）自动导航，
        // 不重置会让沙箱隐式依赖该地址可达，破坏「自包含、结果可复现」的目标。
        sandbox.navigateTo("about:blank");
        sandbox.setContent(loadSandboxHtml());
        sandbox.title.waitForVisible(10);
        logger.info("[E2E sandbox] opened, title = {}", sandbox.getTitle());
        // 每个场景都打一次线程/浏览器快照：并行跑时据此核对并发模型（是否共享 Browser、Context 是否独立）
        logBrowserSnapshot();
    }

    /**
     * 填入账号密码并提交。
     */
    @Step
    public void signIn(String user, String password) {
        sandbox.username.fill(user);
        sandbox.password.fill(password);
        sandbox.signInBtn.click();
    }

    /**
     * 断言消息区包含期望文本（同时覆盖成功问候与校验失败两种分支）。
     */
    @Step
    public void verifyMessageText(String expected) {
        sandbox.message.waitForVisible(10);
        String actual = sandbox.message.getText();
        logger.info("[E2E sandbox] message = {}", actual);
        assertThat(actual, containsString(expected));
    }

    /**
     * 触发异步加载（JS 延迟 1.2s 后显示面板）。
     */
    @Step
    public void loadAsyncData() {
        sandbox.loadDataBtn.click();
    }

    /**
     * 断言异步面板出现且内容正确——验证框架的显式等待能力。
     */
    @Step
    public void verifyAsyncPanelText(String expected) {
        sandbox.asyncPanel.waitForVisible(10);
        String actual = sandbox.asyncPanel.getText();
        logger.info("[E2E sandbox] async panel = {}", actual);
        assertThat(actual, containsString(expected));
    }

    /**
     * 连续点击自增按钮若干次。
     */
    @Step
    public void incrementCounter(int times) {
        for (int i = 0; i < times; i++) {
            sandbox.incrementBtn.click();
        }
    }

    /**
     * 断言计数器精确等于期望值。
     * <p>若并发 scenario 之间浏览器上下文发生串扰，计数会被其它 scenario 污染而断言失败，
     * 因此该断言同时是「并行隔离」的校验点。</p>
     */
    @Step
    public void verifyCounter(String expected) {
        String actual = sandbox.counter.getText();
        logger.info("[E2E sandbox] counter = {} (expected {})", actual, expected);
        assertThat(actual, equalTo(expected));
    }

    /**
     * 记录当前 scenario 的线程与浏览器上下文身份，用于在报告中核对并行隔离。
     */
    @Step
    public void verifyBrowserIsolation() {
        logBrowserSnapshot();
    }

    /**
     * 打印当前 scenario 的线程与浏览器上下文快照，用于核对并发模型：
     * <ul>
     *   <li><b>共享 Browser 模式</b>：不同 threadId 应对应<b>相同</b>的 browserIdentity，
     *       且 openContexts 随并发场景数上升（一个 Browser 上挂多个 Context）。</li>
     *   <li><b>每线程独立 Browser 模式</b>（默认）：不同 threadId 对应<b>不同</b>的 browserIdentity。</li>
     * </ul>
     */
    private void logBrowserSnapshot() {
        Thread current = Thread.currentThread();
        BrowserContext context = PlaywrightManager.getContext();
        Browser browser = context != null ? context.browser() : null;
        int openContexts = (browser != null && browser.isConnected()) ? browser.contexts().size() : -1;

        logger.info("[E2E isolation] threadId={}, threadName={}, browserIdentity={}, openContexts={}, url={}",
                current.getId(),
                current.getName(),
                browser != null ? Integer.toHexString(System.identityHashCode(browser)) : "n/a",
                openContexts,
                PlaywrightManager.getPage().url());
    }

    /**
     * 从 test classpath 读取沙箱页面 HTML。
     *
     * @return 页面 HTML（UTF-8）
     * @throws IllegalStateException 资源缺失或读取失败时抛出（fail-fast，避免后续以空页面静默通过）
     */
    private static String loadSandboxHtml() {
        try (InputStream in = E2ESandboxSteps.class.getResourceAsStream(SANDBOX_HTML)) {
            if (in == null) {
                throw new IllegalStateException(
                        "Sandbox page not found on test classpath: " + SANDBOX_HTML);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read sandbox page: " + SANDBOX_HTML, e);
        }
    }
}
