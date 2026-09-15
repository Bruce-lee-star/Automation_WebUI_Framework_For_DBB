package com.hsbc.cmb.hk.dbb.automation.framework.web.page.base;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.options.BoundingBox;
import com.microsoft.playwright.Frame;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.Cookie;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.config.PlaywrightConfigManager;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.PageElement;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.PageElementList;

import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * 组合式 Page Object 的 Layer B 能力面（G1，doc15 §1 G1）。
 *
 * <p>命名沿用 Serenity 录制体系语义：{@code SerenityBasePage} 即"经 Serenity 录制的页面能力契约"，
 * 默认实现 {@link SerenityBasePageImpl} 以委托 {@code BasePage} 承载录制；业务页经组合/继承获得。
 *
 * <p>业务 Page 退化为纯 POJO（{@code extends AbstractManagedPage}，即
 * {@code implements ManagedPageAware, SerenityBasePage}），不再继承 {@code BasePage} 这部 110 方法的
 * "上帝基类"，从而彻底消解 Layer B 的继承面。Layer B 实现收敛于 {@link SerenityBasePageImpl}
 * （持有 {@code BasePage} 委托 + {@code SerenityPageRecorder}），业务页经组合/继承获得。
 *
 * <p><b>面向接口的多态契约：</b>框架内部与业务 Step 层应面向本接口编程——即可将任一页面对象
 * 赋给 {@link SerenityBasePage} 引用、作为方法参数/返回值传递，从而与具体页面实现解耦（亦便于测试替身）。
 * {@link SerenityBasePageImpl} 为默认实现，{@code AbstractManagedPage} 为业务页基类（其自身即是一个
 * {@code SerenityBasePage}）。
 *
 * <p>本接口逐字对齐原 {@code BasePage} 的对外方法签名（去掉 framework-internal 的包级 seam：
 * {@code activateFrame/deactivateFrame/pushShadow/popShadow/clearShadows/getShadowDepth/peekShadow/
 * ensurePageValid/isPageClosed/onPageSwitched/setPageReference/safeBringToFront/findLastAvailablePage/
 * pageSwitchLockFor/shadowPrefix/getPageRaw/getContextRaw/locatorInternal/resetFrameContextAfterNavigation/
 * isDebugEnvironment}），使业务页从 {@code extends BasePage} 迁移到 {@code SerenityBasePage} 时调用点零改动。
 *
 * <p>原生操作（Layer A）不走本接口——POJO 经 {@code ManagedPageAware} 持有的装饰 {@code Page}
 * 直接调用（{@code click/fill/navigate/...}）即由 {@code RecordingPageProxy} 自动录制。
 *
 * @apiNote 业务 Page 应使用本接口能力；{@code by*} 定位器工厂仍属 framework-internal（受
 *       {@code businessCodeMustNotUseInternalByLocators} 守护），业务应改走 {@code @RoleElement} 或
 *       {@link #element(String)}/{@link #locator(String)}。
 */
public interface SerenityBasePage {

    // ===================== 元素定位（Tier-1 用户公开 API） =====================

    PageElement element(String selector);

    PageElement locator(String selector);

    PageElementList elements(String selector);

    // ===================== 文本 / 属性 =====================

    String normalizeText(String raw);

    String getAttributeValue(String selector, String attr, String defaultValue);

    void append(String selector, String text);

    // ===================== 页面生命周期 / 上下文 =====================

    Page getPage();

    BrowserContext getContext();

    String getCurrentUrl();

    String getTitle();

    void navigateTo(String url);

    void refresh();

    void back();

    void forward();

    void resetFrameContextAfterNavigation();

    PlaywrightConfigManager getConfig();

    // ===================== 等待 =====================

    void waitForNetworkIdle(int timeout);

    void waitForPageFullyLoaded(int timeout);

    void waitForDOMContentLoaded(int timeout);

    void waitForTimeout(int milliseconds);

    // ===================== 可见性 / 验证 =====================

    void shouldBeVisible(String selector);

    void shouldBeNotVisible(String selector);

    // 注：verifyPageTitleContains / verifyPageTitleEquals / verifyUrlContains / getPageSourceContains
    // 属断言/验证语义，应落在 Step 层（经 Serenity 断言 + 报告），不放 Page Object 能力面，故不纳入本接口。

    // ===================== 重试 =====================

    boolean retryWithValidation(Runnable operation, BooleanSupplier validation, int maxRetries, String desc);

    void retry(Runnable runnable, String desc);

    void retry(Runnable runnable, int retries, int intervalMs, String desc);

    boolean retryWithValidation(Runnable operation, BooleanSupplier validation,
                                int maxRetries, int retryIntervalMs, String desc);

    void navigateToWithRetry(String url, int retries);

    // ===================== 导航 / 页面切换 =====================

    void switchToPage(int index);

    Page switchToPage(Page page);

    Page waitForNewPage(Runnable trigger, int timeoutSecs);

    Page waitForNewPage(int timeoutSecs);

    void waitForDownload(Runnable trigger, int timeoutSecs);

    void closeCurrentPage();

    void closeOtherPages();

    // ===================== 交互 =====================

    void keyDown(String selector, String key);

    void keyUp(String selector, String key);

    void press(String selector, String key);

    void acceptAlert();

    void dismissAlert();

    void acceptAlert(Runnable trigger);

    void dismissAlert(Runnable trigger);

    // 注：bringToFront（标签页/窗口激活）刻意不纳入本能力面，也不对外暴露 seam——
    // 切页入口 switchToPage / waitForNewPage 的收尾已由内部 safeBringToFront() 自动激活目标页。

    void setContent(String html);

    void setViewportSize(int width, int height);

    void executeInFrame(String frameName, Consumer<Frame> action);

    // ===================== 状态 / 截图 =====================

    boolean isClosed();

    byte[] takeScreenshot();

    byte[] takeElementScreenshot(String selector);

    BoundingBox getElementBoundingBox(String selector);

    void pause();

    // ===================== frame / shadow =====================

    Frame getFrame(String name);

    Frame switchToFrame(String nameOrSelector);

    void switchToShadow(String hostSelector);

    String switchToDefaultShadow();

    void switchToDefaultShadowAll();

    Frame switchToFrameAndWait(Runnable trigger, String nameOrSelector, int timeoutSecs);

    Frame switchToFrameAndWait(Runnable trigger, String nameOrSelector);

    Frame switchToFrameAndWait(String nameOrSelector, int timeoutSecs);

    Frame switchToFrameAndWait(String nameOrSelector);

    void switchToDefaultContent();

    List<Frame> getAllFrames();

    void dumpAccessibilityRoles();

    // ===================== 脚本 / 源码 =====================

    Object executeJavaScript(String script, Object... args);

    String getPageSource();

    int getPageSize();

    // ===================== Cookie =====================

    List<Cookie> getCookies();

    List<Cookie> getCookies(String url);

    List<Cookie> getCookies(List<String> urls);

    Cookie getCookie(String name);

    boolean hasCookie(String name);

    void addCookie(Cookie cookie);

    void addCookies(List<Cookie> cookies);

    void deleteCookie(String name);

    void clearCookies();

    List<Cookie> getCookiesForCurrentPage();

    // 注：by* 定位器工厂（byRole/byText/byLabel/...）刻意不纳入本接口——它们属 framework-internal
    // （受 businessCodeMustNotUseInternalByLocators 守护），业务方应经 @RoleElement 或
    // element(String)/locator(String) 获取框架原生 PageElement，而非裸 Locator。
}
