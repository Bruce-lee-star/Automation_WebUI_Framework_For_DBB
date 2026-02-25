package com.hsbc.cmb.hk.dbb.automation.framework.web.page;

import com.hsbc.cmb.hk.dbb.automation.framework.web.page.base.BasePage;
import com.microsoft.playwright.options.BoundingBox;

/**
 * 页面元素包装类，支持链式调用
 * 用法示例：
 * LoginPage.USERNAME_INPUT.type(username);
 * LoginPage.NEXT_BUTTON.click();
 */
public class PageElement {
    private final String selector;
    private BasePage page;

    public PageElement(String selector, BasePage page) {
        if (selector == null || selector.trim().isEmpty()) {
            throw new IllegalArgumentException("Selector cannot be null or empty");
        }
        this.selector = selector;
        this.page = page;
    }

    /**
     * 创建仅包含选择器的PageElement（用于静态定义）
     * 使用时需要通过setPage()或从BasePage.getCurrentPage()获取page实例
     */
    public PageElement(String selector) {
        if (selector == null || selector.trim().isEmpty()) {
            throw new IllegalArgumentException("Selector cannot be null or empty");
        }
        this.selector = selector;
        this.page = BasePage.getCurrentPage();
    }

    /**
     * 设置Page实例
     */
    public void setPage(BasePage page) {
        this.page = page;
    }

    /**
     * 获取选择器
     */
    public String getSelector() {
        return selector;
    }

    /**
     * 获取Playwright Locator对象
     * @return Playwright Locator实例
     */
    public com.microsoft.playwright.Locator locator() {
        return getPage().locator(selector);
    }

    /**
     * 获取Page实例
     */
    private BasePage getPage() {
        if (page == null) {
            page = BasePage.getCurrentPage();
            if (page == null) {
                throw new IllegalStateException("No BasePage instance found. Please ensure the page is initialized.");
            }
        }
        return page;
    }

    /**
     * 在元素上输入文本
     */
    public PageElement type(String text) {
        getPage().type(selector, text);
        return this;
    }

    /**
     * 点击元素
     */
    public PageElement click() {
        getPage().click(selector);
        return this;
    }

    /**
     * 双击元素
     */
    public PageElement doubleClick() {
        getPage().doubleClick(selector);
        return this;
    }

    /**
     * 右键点击元素
     */
    public PageElement rightClick() {
        getPage().rightClick(selector);
        return this;
    }

    /**
     * 鼠标悬停
     */
    public PageElement hover() {
        getPage().hover(selector);
        return this;
    }

    /**
     * 清空元素内容
     */
    public PageElement clear() {
        getPage().clear(selector);
        return this;
    }

    /**
     * 获取元素文本
     */
    public String getText() {
        return getPage().getText(selector);
    }

    /**
     * 获取元素值
     */
    public String getValue() {
        return getPage().getValue(selector);
    }

    /**
     * 获取元素属性
     */
    public String getAttribute(String attributeName) {
        return getPage().getAttribute(selector, attributeName);
    }

    /**
     * 检查元素是否可见
     */
    public boolean isVisible() {
        return getPage().isVisible(selector);
    }

    /**
     * 检查元素是否存在
     */
    public boolean exists() {
        return getPage().exists(selector);
    }

    /**
     * 检查元素是否可点击
     */
    public boolean isClickable() {
        return getPage().isElementClickable(selector);
    }

    /**
     * 检查元素是否启用
     */
    public boolean isEnabled() {
        return getPage().isEnabled(selector);
    }

    /**
     * 检查元素是否被选中
     */
    public boolean isSelected() {
        return getPage().isChecked(selector);
    }

    /**
     * 检查元素是否禁用
     */
    public boolean isDisabled() {
        return getPage().isDisabled(selector);
    }

    /**
     * 检查元素是否可编辑
     */
    public boolean isEditable() {
        return isEnabled() && !isDisabled();
    }

    /**
     * 等待元素可见
     */
    public PageElement waitForVisible(int timeoutInSeconds) {
        getPage().waitForElementVisibleWithinTime(selector, timeoutInSeconds);
        return this;
    }

    /**
     * 等待元素不可见
     */
    public PageElement waitForNotVisible(int timeoutInSeconds) {
        getPage().waitForElementNotVisible(selector, timeoutInSeconds);
        return this;
    }

    /**
     * 等待元素存在
     */
    public PageElement waitForExists(int timeoutInSeconds) {
        getPage().waitForElementExists(selector, timeoutInSeconds);
        return this;
    }

    /**
     * 等待元素可点击
     */
    public PageElement waitForClickable(int timeoutInSeconds) {
        getPage().waitForElementClickable(selector, timeoutInSeconds);
        return this;
    }

    /**
     * 等待元素隐藏
     */
    public PageElement waitForHidden() {
        getPage().waitForHidden(selector);
        return this;
    }

    /**
     * 等待元素附加到DOM
     */
    public PageElement waitForAttached() {
        getPage().waitForAttached(selector);
        return this;
    }

    /**
     * 等待元素从DOM中分离
     */
    public PageElement waitForDetached() {
        getPage().waitForDetached(selector);
        return this;
    }

    /**
     * 等待元素附加（带超时）
     */
    public PageElement waitForAttached(int timeoutInSeconds) {
        getPage().waitForElementExists(selector, timeoutInSeconds);
        return this;
    }

    /**
     * 等待元素不存在（带超时）
     */
    public PageElement waitForNotExists(int timeoutInSeconds) {
        getPage().waitForElementNotExists(selector, timeoutInSeconds);
        return this;
    }

    /**
     * 选择下拉选项（通过值）
     */
    public PageElement selectByValue(String value) {
        getPage().selectOption(selector, value);
        return this;
    }

    /**
     * 选择下拉选项（通过索引）
     */
    public PageElement selectByIndex(int index) {
        getPage().selectOption(selector, index);
        return this;
    }

    /**
     * 检查元素是否包含指定文本
     */
    public boolean containsText(String text) {
        String elementText = getText();
        return elementText != null && elementText.contains(text);
    }

    /**
     * 等待元素包含指定文本
     */
    public PageElement waitForContainsText(String text, int timeoutInSeconds) {
        getPage().waitForElementTextContains(selector, text, timeoutInSeconds);
        return this;
    }

    /**
     * 等待元素文本等于指定文本
     */
    public PageElement waitForTextEquals(String text, int timeoutInSeconds) {
        getPage().waitForElementTextEquals(selector, text, timeoutInSeconds);
        return this;
    }

    /**
     * 文本验证方法
     */
    public boolean textEquals(String expectedText) {
        return getPage().textEquals(selector, expectedText);
    }

    /**
     * 文本匹配正则表达式
     */
    public boolean textMatches(String regex) {
        return getPage().textMatches(selector, regex);
    }

    /**
     * 滚动到元素
     */
    public PageElement scrollTo() {
        getPage().scrollToElement(selector);
        return this;
    }

    /**
     * 滚动到指定位置
     */
    public PageElement scrollTo(int scrollX, int scrollY) {
        getPage().scrollTo(selector, scrollX, scrollY);
        return this;
    }

    /**
     * 滚动到元素底部
     */
    public PageElement scrollToBottom() {
        getPage().scrollToBottomOf(selector);
        return this;
    }

    /**
     * 滚动到元素顶部
     */
    public PageElement scrollToTop() {
        getPage().scrollToTopOf(selector);
        return this;
    }

    /**
     * 滚动指定偏移量
     */
    public PageElement scrollBy(int offsetX, int offsetY) {
        getPage().scrollBy(selector, offsetX, offsetY);
        return this;
    }

    /**
     * 滚动到视图中心
     */
    public PageElement scrollToCenter() {
        getPage().scrollToElementCenter(selector);
        return this;
    }

    /**
     * 获取子元素
     */
    public PageElement child(String childSelector) {
        String combinedSelector = selector + " " + childSelector;
        return new PageElement(combinedSelector, getPage());
    }

    /**
     * 上传文件
     */
    public PageElement uploadFile(String filePath) {
        getPage().uploadFile(selector, filePath);
        return this;
    }

    /**
     * 追加文本到元素末尾
     */
    public PageElement append(String text) {
        getPage().append(selector, text);
        return this;
    }

    /**
     * 勾选复选框
     */
    public PageElement check() {
        getPage().check(selector);
        return this;
    }

    /**
     * 取消勾选复选框
     */
    public PageElement uncheck() {
        getPage().uncheck(selector);
        return this;
    }

    // ========== 键盘操作方法 ==========

    /**
     * 在元素上按键
     * @param key 要按下的键（如 "Enter", "ArrowDown", "Control+a" 等）
     */
    public PageElement press(String key) {
        getPage().press(selector, key);
        return this;
    }

    /**
     * 在元素上输入文本（逐个字符，带延迟）
     * @param text 要输入的文本
     */
    public PageElement typeSlowly(String text) {
        getPage().typeSlowly(selector, text);
        return this;
    }

    /**
     * 在元素上插入文本（不覆盖现有内容）
     * @param text 要插入的文本
     */
    public PageElement insertText(String text) {
        getPage().insertText(selector, text);
        return this;
    }

    /**
     * 在元素上按下键
     * @param key 要按下的键
     */
    public PageElement keyDown(String key) {
        getPage().keyDown(selector, key);
        return this;
    }

    /**
     * 在元素上释放键
     * @param key 要释放的键
     */
    public PageElement keyUp(String key) {
        getPage().keyUp(selector, key);
        return this;
    }

    /**
     * 选择所有文本（Ctrl+A / Cmd+A）
     */
    public PageElement selectAll() {
        getPage().selectAll(selector);
        return this;
    }

    /**
     * 复制文本（Ctrl+C / Cmd+C）
     */
    public PageElement copy() {
        getPage().copy(selector);
        return this;
    }

    /**
     * 粘贴文本（Ctrl+V / Cmd+V）
     */
    public PageElement paste() {
        getPage().paste(selector);
        return this;
    }

    /**
     * 剪切文本（Ctrl+X / Cmd+X）
     */
    public PageElement cut() {
        getPage().cut(selector);
        return this;
    }

    // ========== 鼠标操作方法 ==========

    /**
     * 点击元素中心（使用鼠标操作）
     */
    public PageElement clickAtCenter() {
        getPage().clickAtCenter(selector);
        return this;
    }

    /**
     * 拖拽元素到指定坐标
     * @param targetX 目标 X 坐标
     * @param targetY 目标 Y 坐标
     */
    public PageElement dragToCoordinates(int targetX, int targetY) {
        getPage().dragToCoordinates(selector, targetX, targetY);
        return this;
    }

    /**
     * 获取元素中心坐标
     * @return 包含 x 和 y 坐标的数组
     */
    public int[] getCenter() {
        return getPage().getElementCenter(selector);
    }

    // ========== 辅助功能方法 ==========

    /**
     * 检查元素是否可访问
     * @return 如果元素可访问则返回 true，否则返回 false
     */
    public boolean isAccessible() {
        return getPage().isAccessible(selector);
    }

    /**
     * 获取元素的 ARIA 标签
     * @return ARIA 标签文本
     */
    public String getAriaLabel() {
        return getPage().getAriaLabel(selector);
    }

    /**
     * 检查元素是否有 ARIA 标签
     * @return 如果元素有 ARIA 标签则返回 true，否则返回 false
     */
    public boolean hasAriaLabel() {
        return getPage().hasAriaLabel(selector);
    }

    /**
     * 获取元素的 ARIA 角色
     * @return ARIA 角色文本
     */
    public String getAriaRole() {
        return getPage().getAriaRole(selector);
    }

    /**
     * 检查元素的可见性状态（考虑辅助功能）
     * @return 如果元素在辅助功能意义上可见则返回 true，否则返回 false
     */
    public boolean isVisibleForAccessibility() {
        return getPage().isVisibleForAccessibility(selector);
    }

    /**
     * 检查颜色对比度（简化版）
     * @return 如果颜色对比度符合标准则返回 true，否则返回 false
     */
    public boolean hasSufficientColorContrast() {
        return getPage().hasSufficientColorContrast(selector);
    }

    // ========== 等待方法 ==========

    /**
     * 获取匹配选择器的元素数量
     * @return 元素数量
     */
    public int count() {
        return locator().count();
    }

    /**
     * 获取第一个匹配的元素
     * @return Playwright Locator实例
     */
    public com.microsoft.playwright.Locator first() {
        return locator().first();
    }

    /**
     * 获取最后一个匹配的元素
     * @return Playwright Locator实例
     */
    public com.microsoft.playwright.Locator last() {
        return locator().last();
    }

    /**
     * 获取第n个匹配的元素（从0开始索引）
     * @param index 元素索引
     * @return Playwright Locator实例
     */
    public com.microsoft.playwright.Locator nth(int index) {
        return locator().nth(index);
    }

    /**
     * 获取所有匹配的元素
     * @return Playwright Locator实例
     */
    public com.microsoft.playwright.Locator all() {
        return locator();
    }

    /**
     * 截取元素截图
     */
    public PageElement screenshot() {
        getPage().takeElementScreenshot(selector);
        return this;
    }

    /**
     * 获取元素位置和尺寸
     * @return 元素的边界框信息
     */
    public BoundingBox getBoundingBox() {
        return getPage().getElementBoundingBox(selector);
    }

    /**
     * 刷新页面元素（重新获取）
     */
    @Override
    public String toString() {
        return "PageElement{selector='" + selector + "'}";
    }
}
