package com.hsbc.cmb.hk.dbb.automation.framework.web.page;

import com.hsbc.cmb.hk.dbb.automation.framework.web.exceptions.ElementNotFoundException;
import com.hsbc.cmb.hk.dbb.automation.framework.web.exceptions.ElementOperationException;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.PlaywrightManager;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.base.BasePage;
import com.hsbc.cmb.hk.dbb.automation.framework.web.utils.TextNormalizer;
import java.util.ArrayList;
import java.util.List;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.TimeoutError;
import com.microsoft.playwright.options.BoundingBox;
import com.microsoft.playwright.options.MouseButton;
import com.microsoft.playwright.options.SelectOption;
import com.microsoft.playwright.options.WaitForSelectorState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;

public class PageElement {
    private static final Logger logger = LoggerFactory.getLogger(PageElement.class);

    private final String selector;
    private final BasePage page;
    private Supplier<Locator> locatorSupplier;
    /** iframe 嵌套路径（自顶向下）；非空时在 locator() 中以 frameLocator 逐层下钻，对齐 page.pause 录制。 */
    private final List<String> frameSegs;

    // ==================== Constructor ====================
    public PageElement(String selector, BasePage page) {
        this(selector, page, null);
    }

    /**
     * 带 iframe 嵌套路径的构造（对齐 page.pause() 的 frameLocator 录制）。
     * frameSegs 非空时，底层 Locator 会以 {@code page.frameLocator(seg).locator(...)} 逐层包裹，
     * 使 css/xpath 选择器能命中 iframe 内的真实元素。
     */
    public PageElement(String selector, BasePage page, List<String> frameSegs) {
        if (selector == null || selector.isBlank()) {
            throw new IllegalArgumentException("Selector cannot be null or blank");
        }
        if (page == null) {
            throw new IllegalArgumentException("BasePage cannot be null");
        }
        this.selector = selector;
        this.page = page;
        this.frameSegs = (frameSegs == null || frameSegs.isEmpty()) ? null : new ArrayList<>(frameSegs);
    }

    /**
     * 基于动态 Locator 供应商构造（用于 NLS / 角色定位等运行时才能确定定位器的场景）。
     * supplier 每次调用都会重新解析定位器，因此语言切换 / Page 重建后自动生效，
     * 并复用本类的重试 / 诊断 / 截图全套能力。
     *
     * @param locatorSupplier 动态定位器供应商
     * @param description     描述（用于日志、诊断与截图命名），不可为空
     * @param page            所属页面
     */
    public PageElement(Supplier<Locator> locatorSupplier, String description, BasePage page) {
        if (locatorSupplier == null) {
            throw new IllegalArgumentException("Locator supplier cannot be null");
        }
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException("Description cannot be null or blank");
        }
        if (page == null) {
            throw new IllegalArgumentException("BasePage cannot be null");
        }
        this.locatorSupplier = locatorSupplier;
        this.selector = description;
        this.page = page;
        this.frameSegs = null;
    }

    public String getSelector() {
        return selector;
    }

    public BasePage getPage() {
        return page;
    }

    /**
     * 页面存活性保护 + Locator 重建。
     * 不再缓存 Locator——每次调用通过 {@code page.getPage()} 触发 ensurePageValid()，
     * 确保 Page 关闭重建后返回绑定到新 Page 实例的 Locator。
     * 若提供了动态定位器供应商（NLS / 角色定位），优先使用之，实现语言切换后自动重解析。
     */
    protected Locator locatorInternal() {
        // 触发 ensurePageValid() → 如 page 已关闭则重建 page
        page.getPage();
        Locator base;
        if (locatorSupplier != null) {
            base = locatorSupplier.get();
        } else {
            base = page.locatorInternal(selector);
        }
        // 对齐 page.pause() 的 frameLocator 录制：元素位于 iframe 内时逐层下钻到 iframe 中的真实元素。
        if (frameSegs != null) {
            for (String seg : frameSegs) {
                base = page.getPage().frameLocator(seg).locator(base);
            }
        }
        return base;
    }

    /**
     * 定位到「同一定位器匹配的一组元素」中的第 index 个（0-based），对齐 Playwright 的
     * {@code locator.nth(index)}。用于页面上存在多个同 role+name/文本的元素时（如多条
     * “Click here to download” 链接）精确选中其中之一。
     *
     * <p>返回的新 {@link PageElement} 复用本类全套重试 / 诊断 / 截图能力，且底层
     * 定位器动态解析——页面切换 / 语言切换后仍自动生效。
     *
     * @param index 0-based 序号
     * @return 指向第 index 个匹配元素的 PageElement
     */
    public PageElement nth(int index) {
        return new PageElement(() -> locatorInternal().nth(index),
                selector + " >> nth=" + index, page);
    }

    // ==================== 组合定位：filter / or（收口 Playwright Locator 组合能力） ====================
    /**
     * 按可见文本过滤（对齐 Playwright {@code Locator.filter(hasText)}）。
     * 返回的新 {@link PageElement} 复用本类全套重试 / 诊断 / 截图能力。
     *
     * @param text 过滤文本（子串匹配）
     * @return 过滤后的 PageElement
     */
    public PageElement filter(String text) {
        return new PageElement(() -> locatorInternal().filter(
                        new Locator.FilterOptions().setHasText(text)),
                selector + " >> filter(text=" + text + ")", page);
    }

    /** 无参过滤（保留原定位器语义，等价 {@code Locator.filter()}）。 */
    public PageElement filter() {
        return new PageElement(() -> locatorInternal().filter(),
                selector + " >> filter()", page);
    }

    /**
     * 过滤出包含指定子元素者（对齐 Playwright {@code Locator.filter(has)}）。
     *
     * @param has 作为“包含”条件的子元素定位器
     * @return 过滤后的 PageElement
     */
    public PageElement filterBy(PageElement has) {
        Objects.requireNonNull(has, "has locator cannot be null");
        return new PageElement(() -> locatorInternal().filter(
                        new Locator.FilterOptions().setHas(has.locatorInternal())),
                selector + " >> filter(has=" + has.getSelector() + ")", page);
    }

    /**
     * 过滤掉包含指定子元素者（对齐 Playwright {@code Locator.filter(hasNot)}）。
     *
     * @param hasNot 作为“排除”条件的子元素定位器
     * @return 过滤后的 PageElement
     */
    public PageElement filterNot(PageElement hasNot) {
        Objects.requireNonNull(hasNot, "hasNot locator cannot be null");
        return new PageElement(() -> locatorInternal().filter(
                        new Locator.FilterOptions().setHasNot(hasNot.locatorInternal())),
                selector + " >> filter(hasNot=" + hasNot.getSelector() + ")", page);
    }

    /**
     * 逻辑或：本元素或 other（对齐 Playwright {@code Locator.or}）。
     *
     * @param other 备选定位器
     * @return 两者并集的 PageElement
     */
    public PageElement or(PageElement other) {
        Objects.requireNonNull(other, "other locator cannot be null");
        return new PageElement(() -> locatorInternal().or(other.locatorInternal()),
                selector + " | " + other.getSelector(), page);
    }

    // ==================== Safe Execution Template（委托 ElementOperationSupport） ====================
    /**
     * 安全的 Locator 操作执行模板——统一异常翻译 + 自动诊断收集。
     * 实现下沉至 {@link ElementOperationSupport}，本方法仅做薄委托，公开行为零变更。
     */
    private <T> T executeSafely(Supplier<T> action, String operation) {
        return ElementOperationSupport.executeSafely(this::locatorInternal, selector, page, action, operation);
    }

    private void executeWithRetry(Supplier<Boolean> action, String operation) {
        ElementOperationSupport.executeWithRetry(this::locatorInternal, selector, page, action, operation);
    }

    private void executeWithRetry(Supplier<Boolean> action, String operation, String testName) {
        ElementOperationSupport.executeWithRetry(this::locatorInternal, selector, page, action, operation, testName);
    }

    // ==================== Execute with Test Name (for Screenshots) ====================
    /**
     * 执行操作并关联测试名称（用于失败截图命名）
     */
    public PageElement click(String testName) {
        executeWithRetry(() -> {
            locatorInternal().scrollIntoViewIfNeeded();
            locatorInternal().click(new Locator.ClickOptions().setDelay(100).setTimeout(opTimeout()));
            page.waitForTimeout((int) PlaywrightManager.config().getElementActionPostDelay());
            return true;
        }, "click", testName);
        return this;
    }

    public PageElement fill(String text, String testName) {
        executeWithRetry(() -> {
            locatorInternal().scrollIntoViewIfNeeded();
            locatorInternal().fill(text, new Locator.FillOptions().setTimeout(opTimeout()));
            return true;
        }, "fill", testName);
        return this;
    }

    // ==================== Click ====================
    public PageElement click() {
        return click(null);
    }

    public PageElement doubleClick() {
        executeWithRetry(() -> {
            locatorInternal().scrollIntoViewIfNeeded();
            locatorInternal().dblclick(new Locator.DblclickOptions().setTimeout(opTimeout()));
            page.waitForTimeout((int) PlaywrightManager.config().getElementActionPostDelay());
            return true;
        }, "doubleClick");
        return this;
    }

    public PageElement rightClick() {
        executeWithRetry(() -> {
            locatorInternal().scrollIntoViewIfNeeded();
            locatorInternal().click(new Locator.ClickOptions().setButton(MouseButton.RIGHT).setTimeout(opTimeout()));
            page.waitForTimeout((int) PlaywrightManager.config().getElementActionPostDelay());
            return true;
        }, "rightClick");
        return this;
    }

    /**
     * 通过 JavaScript 直接触发元素 {@code click()}（绕过 Playwright 的 actionability 检查）。
     *
     * <p>存在合理性：正常 {@link #click()} 在元素被遮罩/拦截或不可见时会被 Playwright 拒绝，
     * 此时需以 JS 方式强制触发点击。这是真实且常见的交互需求，故作为具名能力保留在元素门面，
     * 而非散落为 {@code BasePage.jsClick(String)} 这类字符串选择器入口。
     *
     * @return 当前元素（支持链式调用）
     */
    public PageElement jsClick() {
        executeWithRetry(() -> {
            locatorInternal().evaluate("el => el.click()");
            return true;
        }, "jsClick");
        return this;
    }

    // ==================== Input ====================
    public PageElement fill(String text) {
        return fill(text, null);
    }

    public PageElement type(String text) {
        executeWithRetry(() -> {
            locatorInternal().scrollIntoViewIfNeeded();
            locatorInternal().pressSequentially(text);
            return true;
        }, "type");
        return this;
    }

    public PageElement clear() {
        executeWithRetry(() -> {
            locatorInternal().scrollIntoViewIfNeeded();
            locatorInternal().clear();
            return true;
        }, "clear");
        return this;
    }

    public PageElement clearAndSetValue(String text) {
        executeWithRetry(() -> {
            locatorInternal().scrollIntoViewIfNeeded();
            locatorInternal().clear();
            locatorInternal().fill(text, new Locator.FillOptions().setTimeout(opTimeout()));
            return true;
        }, "clearAndSetValue");
        return this;
    }

    public PageElement clearAndTypeSequentially(String text) {
        executeWithRetry(() -> {
            locatorInternal().scrollIntoViewIfNeeded();
            locatorInternal().clear();
            locatorInternal().pressSequentially(text, new Locator.PressSequentiallyOptions().setTimeout(opTimeout()));
            return true;
        }, "clearAndTypeSequentially");
        return this;
    }

    // ==================== Keyboard ====================
    public PageElement press(String key) {
        executeWithRetry(() -> {
            locatorInternal().press(key, new Locator.PressOptions().setTimeout(opTimeout()));
            return true;
        }, "press");
        return this;
    }

    public PageElement selectText() {
        executeWithRetry(() -> {
            locatorInternal().selectText();
            return true;
        }, "selectText");
        return this;
    }

    // ==================== State Check ====================
    /**
     * 判断元素是否可见，带框架默认超时（页面加载友好）。
     *
     * <p>在 {@code getElementCheckTimeout()} 时间内等待元素变为可见；超时仍未可见返回 {@code false}。
     * 这是"页面加载中元素稍后会出现"场景的正确语义——不会因加载中途查询而误判为不可见。
     * 元素被永久隐藏（如 {@code display:none}）时，会等待至超时后返回 {@code false}。</p>
     */
    public boolean isVisible() {
        return isVisible(getDefaultTimeoutMs() / 1000);
    }

    /**
     * 在指定超时内等待元素变为可见。超时仍未可见则返回 {@code false}。
     */
    public boolean isVisible(int timeoutSec) {
        try {
            locatorInternal().waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.VISIBLE).setTimeout((long) timeoutSec * 1000));
            return true;
        } catch (PlaywrightException e) {
            return false;
        }
    }

    /** 判断元素是否不可见，带框架默认超时（页面加载友好）。 */
    public boolean isNotVisible() {
        return isNotVisible(getDefaultTimeoutMs() / 1000);
    }

    public boolean isNotVisible(int timeoutSec) {
        try {
            locatorInternal().waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.HIDDEN).setTimeout((long) timeoutSec * 1000));
            return true;
        } catch (PlaywrightException e) {
            return false;
        }
    }

    /**
     * 判断元素是否存在于 DOM，带框架默认超时（页面加载友好）。
     *
     * <p>在 {@code getElementCheckTimeout()} 时间内等待元素挂载；超时仍未挂载返回 {@code false}。
     * 页面加载中元素可能尚未解析到 DOM，等待语义可避免误判为不存在。</p>
     */
    public boolean exists() {
        return exists(getDefaultTimeoutMs() / 1000);
    }

    /**
     * 在指定超时内等待元素挂载到 DOM。超时仍未挂载则返回 {@code false}。
     */
    public boolean exists(int timeoutSec) {
        try {
            locatorInternal().waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.ATTACHED).setTimeout((long) timeoutSec * 1000));
            return true;
        } catch (PlaywrightException e) {
            return false;
        }
    }

    /**
     * 判断元素是否可用，带框架默认超时（页面加载友好）。
     *
     * <p>在 {@code getElementCheckTimeout()} 时间内等待元素变为可用；超时仍未可用返回 {@code false}。
     * 页面加载中元素可能尚未 enable，等待语义可避免误判。</p>
     */
    public boolean isEnabled() {
        return isEnabled(getDefaultTimeoutMs() / 1000);
    }

    /**
     * 在指定超时内等待元素变为可用；超时仍未可用则返回 {@code false}。
     */
    public boolean isEnabled(int timeoutSec) {
        try {
            return locatorInternal().isEnabled(new Locator.IsEnabledOptions()
                    .setTimeout((double) timeoutSec * 1000));
        } catch (PlaywrightException e) {
            return false;
        }
    }

    /** 判断元素是否禁用，带框架默认超时（页面加载友好）。 */
    public boolean isDisabled() {
        return !isEnabled();
    }

    public boolean isDisabled(int timeoutSec) {
        return !isEnabled(timeoutSec);
    }

    /**
     * 判断元素是否可编辑，带框架默认超时（页面加载友好）。
     *
     * <p>在 {@code getElementCheckTimeout()} 时间内等待元素变为可编辑；超时仍未可编辑返回 {@code false}。</p>
     */
    public boolean isEditable() {
        return isEditable(getDefaultTimeoutMs() / 1000);
    }

    /**
     * 在指定超时内等待元素变为可编辑；超时仍未可编辑则返回 {@code false}。
     */
    public boolean isEditable(int timeoutSec) {
        try {
            return locatorInternal().isEditable(new Locator.IsEditableOptions()
                    .setTimeout((double) timeoutSec * 1000));
        } catch (PlaywrightException e) {
            return false;
        }
    }

    /**
     * 判断勾选框/单选是否被选中，带框架默认超时（页面加载友好）。
     *
     * <p>在 {@code getElementCheckTimeout()} 时间内等待元素被选中；超时仍未选中返回 {@code false}。</p>
     */
    public boolean isChecked() {
        return isChecked(getDefaultTimeoutMs() / 1000);
    }

    /**
     * 在指定超时内等待元素被选中；超时仍未选中则返回 {@code false}。
     */
    public boolean isChecked(int timeoutSec) {
        try {
            return locatorInternal().isChecked(new Locator.IsCheckedOptions()
                    .setTimeout((double) timeoutSec * 1000));
        } catch (PlaywrightException e) {
            return false;
        }
    }

    // ==================== Text & Attribute ====================
    public String getText() {
        return executeSafely(() -> {
            String raw = locatorInternal().innerText();
            if (raw == null) {
                logger.warn("getText() returned null for selector: {}", selector);
                return "";
            }
            return TextNormalizer.normalize(raw);
        }, "getText");
    }

    /**
     * 获取原始文本，跳过标准化管道（性能优化：列表遍历场景避免 4 次正则）。
     */
    public String getTextRaw() {
        return executeSafely(() -> {
            String raw = locatorInternal().innerText();
            return raw != null ? raw : "";
        }, "getTextRaw");
    }

    public String getInnerHtml() {
        return executeSafely(() -> locatorInternal().innerHTML(), "getInnerHtml");
    }

    public List<String> getAllTextContents() {
        return executeSafely(() -> locatorInternal().allTextContents(), "getAllTextContents");
    }

    public String getAttribute(String attr) {
        return executeSafely(() -> locatorInternal().getAttribute(attr), "getAttribute");
    }

    public String getValue() {
        return executeSafely(() -> locatorInternal().inputValue(), "getValue");
    }

    // ==================== Select ====================
    public PageElement selectByValue(String value) {
        executeWithRetry(() -> {
            locatorInternal().selectOption(value, new Locator.SelectOptionOptions().setTimeout(opTimeout()));
            return true;
        }, "selectByValue");
        return this;
    }

    public PageElement selectByIndex(int index) {
        executeWithRetry(() -> {
            locatorInternal().selectOption(new SelectOption().setIndex(index), new Locator.SelectOptionOptions().setTimeout(opTimeout()));
            return true;
        }, "selectByIndex");
        return this;
    }

    public PageElement selectByVisibleText(String text) {
        executeWithRetry(() -> {
            locatorInternal().selectOption(new SelectOption().setLabel(text), new Locator.SelectOptionOptions().setTimeout(opTimeout()));
            return true;
        }, "selectByVisibleText");
        return this;
    }

    // ==================== WaitFor (Full Set) ====================
    private int getDefaultTimeoutMs() {
        return PlaywrightManager.config().getElementCheckTimeout();
    }

    /** 元素动作单次操作超时（毫秒），由 {@code playwright.element.operation.timeout} 驱动，注入 Playwright 原生 actionability 等待窗口。 */
    private double opTimeout() {
        return PlaywrightManager.config().getElementOperationTimeout();
    }

    public PageElement waitForVisible() {
        return waitForVisible(getDefaultTimeoutMs() / 1000);
    }

    public PageElement waitForVisible(int timeoutSec) {
        return waitForState(WaitForSelectorState.VISIBLE, timeoutSec, false,
                (e, t) -> new ElementOperationException("waitForVisible", selector,
                        "Element not visible within " + t + " seconds: " + selector, e),
                (e, t) -> new ElementOperationException("waitForVisible", selector,
                        "Failed to wait for element visible: " + selector, e));
    }

    public PageElement waitForNotVisible() {
        return waitForNotVisible(getDefaultTimeoutMs() / 1000);
    }

    public PageElement waitForNotVisible(int timeoutSec) {
        return waitForState(WaitForSelectorState.HIDDEN, timeoutSec, false,
                (e, t) -> new ElementOperationException("waitForNotVisible", selector,
                        "Element still visible after " + t + " seconds: " + selector, e),
                (e, t) -> new ElementOperationException("waitForNotVisible", selector,
                        "Failed to wait for element hidden: " + selector, e));
    }

    public PageElement waitForExists() {
        return waitForExists(getDefaultTimeoutMs() / 1000);
    }

    public PageElement waitForExists(int timeoutSec) {
        // 元素不存在用 ElementNotFoundException（语义更明确，便于上层区分"找不到"与"其它异常"）
        return waitForState(WaitForSelectorState.ATTACHED, timeoutSec, false,
                (e, t) -> new ElementNotFoundException(selector, e),
                (e, t) -> new ElementOperationException("waitForExists", selector,
                        "Failed to wait for element exists: " + selector, e));
    }

    public PageElement waitForNotExists() {
        return waitForNotExists(getDefaultTimeoutMs() / 1000);
    }

    public PageElement waitForNotExists(int timeoutSec) {
        return waitForState(WaitForSelectorState.DETACHED, timeoutSec, false,
                (e, t) -> new ElementOperationException("waitForNotExists", selector,
                        "Element still exists in DOM after " + t + " seconds: " + selector, e),
                (e, t) -> new ElementOperationException("waitForNotExists", selector,
                        "Failed to wait for element detached: " + selector, e));
    }

    public PageElement waitForClickable() {
        return waitForClickable(getDefaultTimeoutMs() / 1000);
    }

    public PageElement waitForClickable(int timeoutSec) {
        // Playwright click() 内置完整 actionability 检查（visible+stable+enabled+receives events）
        // waitForClickable 仅需确认元素已可见即可，实际可交互性由 click() 保证
        return waitForState(WaitForSelectorState.VISIBLE, timeoutSec, false,
                (e, t) -> new ElementOperationException("waitForClickable", selector,
                        "Element is not clickable within " + t + " seconds: " + selector, e),
                (e, t) -> new ElementOperationException("waitForClickable", selector,
                        "Failed to check element state: " + selector, e));
    }

    public PageElement waitForEditable() {
        return waitForEditable(getDefaultTimeoutMs() / 1000);
    }

    public PageElement waitForEditable(int timeoutSec) {
        return waitForPredicate(
                l -> l.isEditable(new Locator.IsEditableOptions().setTimeout((double) timeoutSec * 1000)),
                true, timeoutSec, "waitForEditable",
                "Element is not editable: " + selector);
    }

    public PageElement waitForEnabled() {
        return waitForEnabled(getDefaultTimeoutMs() / 1000);
    }

    public PageElement waitForEnabled(int timeoutSec) {
        return waitForPredicate(
                l -> l.isEnabled(new Locator.IsEnabledOptions().setTimeout((double) timeoutSec * 1000)),
                true, timeoutSec, "waitForEnabled",
                "Element is not enabled: " + selector);
    }

    public PageElement waitForDisabled() {
        return waitForDisabled(getDefaultTimeoutMs() / 1000);
    }

    public PageElement waitForDisabled(int timeoutSec) {
        return waitForPredicate(
                l -> l.isEnabled(new Locator.IsEnabledOptions().setTimeout((double) timeoutSec * 1000)),
                false, timeoutSec, "waitForDisabled",
                "Element is not disabled (still enabled): " + selector);
    }

    public PageElement waitForChecked() {
        return waitForChecked(getDefaultTimeoutMs() / 1000);
    }

    public PageElement waitForChecked(int timeoutSec) {
        return waitForPredicate(
                l -> l.isChecked(new Locator.IsCheckedOptions().setTimeout((double) timeoutSec * 1000)),
                true, timeoutSec, "waitForChecked",
                "Element is not checked: " + selector);
    }

    public PageElement waitForNotChecked() {
        return waitForNotChecked(getDefaultTimeoutMs() / 1000);
    }

    public PageElement waitForNotChecked(int timeoutSec) {
        return waitForPredicate(
                l -> l.isChecked(new Locator.IsCheckedOptions().setTimeout((double) timeoutSec * 1000)),
                false, timeoutSec, "waitForNotChecked",
                "Element is checked (expected not checked): " + selector);
    }

    // ---- waitFor* 公共模板 ----

    /**
     * 基于 waitFor(state) 的等待模板（waitForVisible/NotVisible/Exists/NotExists/Clickable 共用）。
     *
     * @param expect     期望的状态
     * @param timeoutSec 超时秒数
     * @param negate     是否取反（当前未使用，预留）
     * @param onTimeout  TimeoutError 时构造的异常（带 selector 与超时信息）
     * @param onOther    其它 PlaywrightException 时构造的异常
     */
    private PageElement waitForState(WaitForSelectorState expect, int timeoutSec, boolean negate,
            BiFunction<TimeoutError, Integer, RuntimeException> onTimeout,
            BiFunction<PlaywrightException, Integer, RuntimeException> onOther) {
        try {
            locatorInternal().waitFor(new Locator.WaitForOptions()
                    .setState(expect).setTimeout((long) timeoutSec * 1000));
            return this;
        } catch (TimeoutError e) {
            throw onTimeout.apply(e, timeoutSec);
        } catch (PlaywrightException e) {
            throw onOther.apply(e, timeoutSec);
        }
    }

    /**
     * 基于布尔谓词（isEnabled/isEditable/isChecked）的等待模板。
     * 先调谓词判断是否达成，未达成则显式抛出（而非依赖 TimeoutError）。
     *
     * @param check      带超时的判定函数（超时通过闭包传入，类型安全）
     * @param expectTrue 期望谓词返回 true 还是 false
     * @param timeoutSec 超时秒数
     * @param op         操作名（用于异常与日志）
     * @param failMsg    未达成时抛出的异常信息
     */
    private PageElement waitForPredicate(Function<Locator, Boolean> check,
            boolean expectTrue, int timeoutSec, String op, String failMsg) {
        try {
            boolean actual = Boolean.TRUE.equals(check.apply(locatorInternal()));
            if (actual != expectTrue) {
                throw new ElementOperationException(op, selector, failMsg, null);
            }
            return this;
        } catch (PlaywrightException e) {
            throw new ElementOperationException(op, selector,
                    "Failed to check element state: " + selector, e);
        }
    }

    // ==================== Event & JS ====================
    public PageElement dispatchEvent(String event) {
        executeWithRetry(() -> {
            locatorInternal().dispatchEvent(event);
            return true;
        }, "dispatchEvent");
        return this;
    }

    public PageElement dispatchEvent(String event, Object arg) {
        executeWithRetry(() -> {
            locatorInternal().dispatchEvent(event, arg);
            return true;
        }, "dispatchEventWithArg");
        return this;
    }

    // ==================== JS 求值 / 焦点 / 触摸（收口 Playwright Locator 能力） ====================
    /**
     * 在元素上执行任意 JavaScript 表达式（T3-5 补齐全量能力，供 jsClick / 自定义断言等场景使用）。
     * 等价于 Playwright {@code Locator.evaluate}，经框架异常翻译为 {@link ElementOperationException}。
     *
     * @param expression JS 表达式（函数体或表达式；若需参数，用 {@link #evaluate(String, Object)}）
     * @return 表达式返回值（JSON 反序列化对象）
     */
    public Object evaluate(String expression) {
        try {
            return locatorInternal().evaluate(expression);
        } catch (PlaywrightException e) {
            throw new ElementOperationException("evaluate", selector,
                    "Failed to evaluate expression: " + expression, e);
        }
    }

    /** 带参数版本的 {@link #evaluate(String)}，参数 {@code arg} 在表达式中以 {@code arg} 访问。 */
    public Object evaluate(String expression, Object arg) {
        try {
            return locatorInternal().evaluate(expression, arg);
        } catch (PlaywrightException e) {
            throw new ElementOperationException("evaluate", selector,
                    "Failed to evaluate expression: " + expression, e);
        }
    }

    /** 让元素失去焦点（对齐 Playwright {@code Locator.blur}）。 */
    public PageElement blur() {
        executeWithRetry(() -> {
            locatorInternal().blur();
            return true;
        }, "blur");
        return this;
    }

    /** 触摸点击（对齐 Playwright {@code Locator.tap}，适用于触摸设备）。 */
    public PageElement tap() {
        executeWithRetry(() -> {
            locatorInternal().tap(new Locator.TapOptions().setTimeout(opTimeout()));
            return true;
        }, "tap");
        return this;
    }

    // ==================== 无障碍 / ARIA 快照（T3-5 补全 ariaSnapshot 能力） ====================
    /**
     * 获取元素的 ARIA 快照（对齐 Playwright {@code Locator.ariaSnapshot()}）。
     * 用于无障碍合规断言与可访问性回归检测；检索失败经框架异常翻译为 {@link ElementOperationException}。
     *
     * @return ARIA 快照文本（默认仅含“有趣”节点）
     */
    public String ariaSnapshot() {
        try {
            return locatorInternal().ariaSnapshot();
        } catch (PlaywrightException e) {
            throw new ElementOperationException("ariaSnapshot", selector,
                    "Failed to capture ARIA snapshot", e);
        }
    }

    // ==================== Hover / Focus / Check / Scroll ====================
    /**
     * 将元素滚动到可视区域（使用 JS 实现）
     */
    public PageElement scrollIntoView() {
        executeWithRetry(() -> {
            locatorInternal().evaluate("el => el.scrollIntoView({ behavior: 'instant', block: 'center' })");
            return true;
        }, "scrollIntoView");
        return this;
    }

    public PageElement hover() {
        executeWithRetry(() -> {
            locatorInternal().hover(new Locator.HoverOptions().setTimeout(opTimeout()));
            return true;
        }, "hover");
        return this;
    }

    public PageElement focus() {
        executeWithRetry(() -> {
            locatorInternal().focus(new Locator.FocusOptions().setTimeout(opTimeout()));
            return true;
        }, "focus");
        return this;
    }

    public PageElement check() {
        executeWithRetry(() -> {
            locatorInternal().check(new Locator.CheckOptions().setTimeout(opTimeout()));
            return true;
        }, "check");
        return this;
    }

    public PageElement uncheck() {
        executeWithRetry(() -> {
            locatorInternal().uncheck(new Locator.UncheckOptions().setTimeout(opTimeout()));
            return true;
        }, "uncheck");
        return this;
    }

    /**
     * 按目标状态设置勾选（对齐 page.pause 的 setChecked 语义）：
     * 当前已满足目标状态时不做任何操作，避免对已勾选元素再次 check / 已未勾选再次 uncheck
     * 可能造成的误 toggle。封装层读取 isChecked() 做幂等保护后再调用 check/uncheck。
     *
     * @param target true=勾选 / false=取消勾选
     */
    public PageElement setChecked(boolean target) {
        Boolean current = isChecked();
        if (current != null && current == target) return this; // 已满足，无需操作
        executeWithRetry(() -> {
            locatorInternal().setChecked(target, new Locator.SetCheckedOptions().setTimeout(opTimeout()));
            return true;
        }, "setChecked");
        return this;
    }

    // ==================== Upload / Screenshot / Drag ====================
    public PageElement uploadFile(String... paths) {
        Path[] pathArray = Arrays.stream(paths).map(Paths::get).toArray(Path[]::new);
        executeWithRetry(() -> {
            locatorInternal().setInputFiles(pathArray, new Locator.SetInputFilesOptions().setTimeout(opTimeout()));
            return true;
        }, "uploadFile");
        return this;
    }

    public byte[] screenshot() {
        try {
            return locatorInternal().screenshot();
        } catch (PlaywrightException e) {
            logger.error("screenshot failed: {}", selector, e);
            return null;
        }
    }

    public PageElement dragTo(PageElement target) {
        executeWithRetry(() -> {
            locatorInternal().dragTo(target.locatorInternal(), new Locator.DragToOptions().setTimeout(opTimeout()));
            return true;
        }, "dragTo");
        return this;
    }

    // ==================== Utils ====================
    /**
     * 元素定位器健康度检查。
     * 快速判断 Locator 是否仍然有效（定位符对应的 DOM 未发生结构性变化）。
     * count() >= 0 表示定位符仍可正常解析（即使返回 0 个匹配也是"健康"的，只是元素不存在）。
     *
     * @return true 表示 Locator 健康可用，false 表示 Locator 已失效（如页面已关闭或选择器语法错误）
     */
    /**
     * 仅校验 Locator 语法/可解析性（不表示元素存在，count=0 也算 true）。
     * 与 exists() 区分：exists() 关注“是否有匹配元素”，本方法关注“定位器是否合法可用”。
     */
    public boolean isParsable() {
        try {
            locatorInternal().count();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 安全获取元素边界框。Playwright 的 boundingBox() 自带自动等待（元素可见+稳定），
     * 不需要额外调用 waitForVisible()。
     * 元素不可见/未挂载时返回 null 而非抛异常（与 "Safe" 语义一致，便于调用方优雅降级）。
     */
    public ElementRect getBoundingBoxSafe() {
        try {
            com.microsoft.playwright.options.BoundingBox box = locatorInternal().boundingBox();
            return box == null ? null : new ElementRect(box.x, box.y, box.width, box.height);
        } catch (PlaywrightException e) {
            logger.warn("getBoundingBoxSafe failed for {}: {}", selector, e.getMessage());
            return null;
        }
    }

    public int count() {
        return locatorInternal().count();
    }

    // ==================== Child Element ====================
    /**
     * 基于当前元素的 Locator 创建子元素定位器。
     * 使用 Playwright Locator.locator() 链式定位，而非字符串拼接 ">>"，
     * 避免无限嵌套导致的选择器过长/解析错误。
     */
    public PageElement child(String childSelector) {
        Objects.requireNonNull(childSelector, "childSelector must not be null");
        String clean = childSelector.trim();
        if (clean.isEmpty()) {
            throw new IllegalArgumentException("childSelector must not be blank");
        }
        // 关键ChildPageElement 必须继承父级 iframe 路径，
        // 否则位于 iframe 内的子元素会在父页面里找不到。
        List<String> inheritedFrames = (frameSegs == null) ? null : new ArrayList<>(frameSegs);
        return new ChildPageElement(selector, clean, page, inheritedFrames);
    }

    public PageElement child(String childSelector, int index) {
        // 委托 child(String) + nth(int)，单一事实来源，避免重复拼选择器字符串
        return child(childSelector).nth(index);
    }

    /**
     * 内部类：通过 Locator.locator() 实现嵌套定位，避免选择器字符串无限拼接。
     */
    private static final class ChildPageElement extends PageElement {
        private final String parentSelector;
        private final String childSelector;

        ChildPageElement(String parentSelector, String childSelector, BasePage page, List<String> frameSegs) {
            // 父类 selector 仅作为标识符使用，实际定位通过 locator() 的嵌套 Locator 实现
            super("parent[" + parentSelector + "] >> child[" + childSelector + "]", page, frameSegs);
            this.parentSelector = parentSelector;
            this.childSelector = childSelector;
        }

        @Override
        public String getSelector() {
            // 返回描述性字符串，与 locator() 行为一致（Locator.locator() 嵌套定位）
            return "parent[" + parentSelector + "] >> child[" + childSelector + "]";
        }

        @Override
        protected Locator locatorInternal() {
            // 关键先解析父级 locator（含父级 frameLocator 链），
            // 再用 Locator.locator() 在父级作用域下钻到子元素，确保 iframe 内子元素可定位。
            Locator parentLocator = super.locatorInternal();
            return parentLocator.locator(childSelector);
        }
    }

    @Override
    public String toString() {
        return "PageElement[" + selector + "]";
    }
}