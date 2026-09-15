package com.hsbc.cmb.hk.dbb.automation.framework.web.page.api;

import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.config.PlaywrightConfigManager;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.PageElement;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.PageElementList;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.base.BasePage;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.options.BoundingBox;
import com.microsoft.playwright.Frame;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.Cookie;

import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * {@link SerenityBasePage} 的默认实现（G1，doc15 §1 G1）。
 *
 * <p>组合式承载 Layer B 全部能力：业务 POJO 内部持有一个 {@code BasePage} 委托（页面上下文宿主），
 * 本实现把所有 {@link SerenityBasePage} 方法 1 行转发到该委托的对应方法。由于 {@code BasePage} 的对外方法
 * 本就是录制委托壳（内部经 {@code SerenityPageRecorder} 录制），转发即获得与「继承 BasePage」
 * 逐字一致的录制、iframe/shadow 路由、可观测性与错误处理语义——零新逻辑。
 *
 * <p>业务 Page 构造时继承本类即可获得等价 Layer B 能力，但不再继承 110 方法的基类
 * （彻底消解继承面）。原生 Layer A 操作由 POJO 经 {@code ManagedPageAware} 持有的装饰 {@code Page}
 * 直接调用、由 {@code RecordingPageProxy} 自动录制，不经本类。
 *
 * @see SerenityBasePage
 * @see AbstractManagedPage
 */
public class SerenityBasePageImpl implements SerenityBasePage {

    private final BasePage bp;

    public SerenityBasePageImpl(BasePage bp) {
        this.bp = bp;
    }

    // ===================== 元素定位 =====================

    @Override
    public PageElement element(String selector) {
        return bp.element(selector);
    }

    @Override
    public PageElement locator(String selector) {
        return bp.element(selector);
    }

    @Override
    public PageElementList elements(String selector) {
        return bp.elements(selector);
    }

    // ===================== 文本 / 属性 =====================

    @Override
    public String normalizeText(String raw) {
        return bp.normalizeText(raw);
    }

    @Override
    public String getAttributeValue(String selector, String attr, String defaultValue) {
        return bp.getAttributeValue(selector, attr, defaultValue);
    }

    @Override
    public void append(String selector, String text) {
        bp.append(selector, text);
    }

    // ===================== 页面生命周期 / 上下文 =====================

    @Override
    public Page getPage() {
        return bp.getPage();
    }

    @Override
    public BrowserContext getContext() {
        return bp.getContext();
    }

    @Override
    public String getCurrentUrl() {
        return bp.getCurrentUrl();
    }

    @Override
    public String getTitle() {
        return bp.getTitle();
    }

    @Override
    public void refresh() {
        bp.refresh();
    }

    @Override
    public void back() {
        bp.back();
    }

    @Override
    public void forward() {
        bp.forward();
    }

    @Override
    public void navigateTo(String url) {
        bp.navigateTo(url);
    }

    @Override
    public void resetFrameContextAfterNavigation() {
        bp.resetFrameContextAfterNavigation();
    }

    @Override
    public PlaywrightConfigManager getConfig() {
        return bp.getConfig();
    }

    // ===================== 等待 =====================

    @Override
    public void waitForNetworkIdle(int timeout) {
        bp.waitForNetworkIdle(timeout);
    }

    @Override
    public void waitForPageFullyLoaded(int timeout) {
        bp.waitForPageFullyLoaded(timeout);
    }

    @Override
    public void waitForDOMContentLoaded(int timeout) {
        bp.waitForDOMContentLoaded(timeout);
    }

    @Override
    public void waitForTimeout(int milliseconds) {
        bp.waitForTimeout(milliseconds);
    }

    // ===================== 可见性 / 验证 =====================

    @Override
    public void shouldBeVisible(String selector) {
        bp.shouldBeVisible(selector);
    }

    @Override
    public void shouldBeNotVisible(String selector) {
        bp.shouldBeNotVisible(selector);
    }

    // ===================== 重试 =====================

    @Override
    public boolean retryWithValidation(Runnable operation, BooleanSupplier validation,
                                        int maxRetries, String desc) {
        return bp.retryWithValidation(operation, validation, maxRetries, desc);
    }

    @Override
    public void retry(Runnable runnable, String desc) {
        bp.retry(runnable, desc);
    }

    @Override
    public void retry(Runnable runnable, int retries, int intervalMs, String desc) {
        bp.retry(runnable, retries, intervalMs, desc);
    }

    @Override
    public boolean retryWithValidation(Runnable operation, BooleanSupplier validation,
                                        int maxRetries, int retryIntervalMs, String desc) {
        return bp.retryWithValidation(operation, validation, maxRetries, retryIntervalMs, desc);
    }

    @Override
    public void navigateToWithRetry(String url, int retries) {
        bp.navigateToWithRetry(url, retries);
    }

    // ===================== 导航 / 页面切换 =====================

    @Override
    public void switchToPage(int index) {
        bp.switchToPage(index);
    }

    @Override
    public Page switchToPage(Page page) {
        return bp.switchToPage(page);
    }

    @Override
    public Page waitForNewPage(Runnable trigger, int timeoutSecs) {
        return bp.waitForNewPage(trigger, timeoutSecs);
    }

    @Override
    public Page waitForNewPage(int timeoutSecs) {
        return bp.waitForNewPage(timeoutSecs);
    }

    @Override
    public void waitForDownload(Runnable trigger, int timeoutSecs) {
        bp.waitForDownload(trigger, timeoutSecs);
    }

    @Override
    public void closeCurrentPage() {
        bp.closeCurrentPage();
    }

    @Override
    public void closeOtherPages() {
        bp.closeOtherPages();
    }

    // ===================== 交互 =====================

    @Override
    public void keyDown(String selector, String key) {
        bp.keyDown(selector, key);
    }

    @Override
    public void keyUp(String selector, String key) {
        bp.keyUp(selector, key);
    }

    @Override
    public void press(String selector, String key) {
        bp.press(selector, key);
    }

    @Override
    public void acceptAlert() {
        bp.acceptAlert();
    }

    @Override
    public void dismissAlert() {
        bp.dismissAlert();
    }

    @Override
    public void acceptAlert(Runnable trigger) {
        bp.acceptAlert(trigger);
    }

    @Override
    public void dismissAlert(Runnable trigger) {
        bp.dismissAlert(trigger);
    }

    @Override
    public void bringToFront() {
        bp.bringToFront();
    }

    @Override
    public void setContent(String html) {
        bp.setContent(html);
    }

    @Override
    public void setViewportSize(int width, int height) {
        bp.setViewportSize(width, height);
    }

    @Override
    public void executeInFrame(String frameName, Consumer<Frame> action) {
        bp.executeInFrame(frameName, action);
    }

    // ===================== 状态 / 截图 =====================

    @Override
    public boolean isClosed() {
        return bp.isClosed();
    }

    @Override
    public byte[] takeScreenshot() {
        return bp.takeScreenshot();
    }

    @Override
    public byte[] takeElementScreenshot(String selector) {
        return bp.takeElementScreenshot(selector);
    }

    @Override
    public BoundingBox getElementBoundingBox(String selector) {
        return bp.getElementBoundingBox(selector);
    }

    @Override
    public void pause() {
        bp.pause();
    }

    // ===================== frame / shadow =====================

    @Override
    public Frame getFrame(String name) {
        return bp.getFrame(name);
    }

    @Override
    public Frame switchToFrame(String nameOrSelector) {
        return bp.switchToFrame(nameOrSelector);
    }

    @Override
    public void switchToShadow(String hostSelector) {
        bp.switchToShadow(hostSelector);
    }

    @Override
    public String switchToDefaultShadow() {
        return bp.switchToDefaultShadow();
    }

    @Override
    public void switchToDefaultShadowAll() {
        bp.switchToDefaultShadowAll();
    }

    @Override
    public Frame switchToFrameAndWait(Runnable trigger, String nameOrSelector, int timeoutSecs) {
        return bp.switchToFrameAndWait(trigger, nameOrSelector, timeoutSecs);
    }

    @Override
    public Frame switchToFrameAndWait(Runnable trigger, String nameOrSelector) {
        return bp.switchToFrameAndWait(trigger, nameOrSelector);
    }

    @Override
    public Frame switchToFrameAndWait(String nameOrSelector, int timeoutSecs) {
        return bp.switchToFrameAndWait(nameOrSelector, timeoutSecs);
    }

    @Override
    public Frame switchToFrameAndWait(String nameOrSelector) {
        return bp.switchToFrameAndWait(nameOrSelector);
    }

    @Override
    public void switchToDefaultContent() {
        bp.switchToDefaultContent();
    }

    @Override
    public List<Frame> getAllFrames() {
        return bp.getAllFrames();
    }

    @Override
    public void dumpAccessibilityRoles() {
        bp.dumpAccessibilityRoles();
    }

    // ===================== 脚本 / 源码 =====================

    @Override
    public Object executeJavaScript(String script, Object... args) {
        return bp.executeJavaScript(script, args);
    }

    @Override
    public String getPageSource() {
        return bp.getPageSource();
    }

    @Override
    public int getPageSize() {
        return bp.getPageSize();
    }

    // ===================== Cookie =====================

    @Override
    public List<Cookie> getCookies() {
        return bp.getCookies();
    }

    @Override
    public List<Cookie> getCookies(String url) {
        return bp.getCookies(url);
    }

    @Override
    public List<Cookie> getCookies(List<String> urls) {
        return bp.getCookies(urls);
    }

    @Override
    public Cookie getCookie(String name) {
        return bp.getCookie(name);
    }

    @Override
    public boolean hasCookie(String name) {
        return bp.hasCookie(name);
    }

    @Override
    public void addCookie(Cookie cookie) {
        bp.addCookie(cookie);
    }

    @Override
    public void addCookies(List<Cookie> cookies) {
        bp.addCookies(cookies);
    }

    @Override
    public void deleteCookie(String name) {
        bp.deleteCookie(name);
    }

    @Override
    public void clearCookies() {
        bp.clearCookies();
    }

    @Override
    public List<Cookie> getCookiesForCurrentPage() {
        return bp.getCookiesForCurrentPage();
    }
}
