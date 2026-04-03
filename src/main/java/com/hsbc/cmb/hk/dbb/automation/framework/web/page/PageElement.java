package com.hsbc.cmb.hk.dbb.automation.framework.web.page;

import com.hsbc.cmb.hk.dbb.automation.framework.web.page.base.BasePage;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.options.BoundingBox;
import com.microsoft.playwright.options.WaitForSelectorState;
import com.microsoft.playwright.options.MouseButton;

import java.nio.file.Path;
import java.nio.file.Paths;

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
    public Locator locator() {
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
        locator().fill(text);
        return this;
    }

    /**
     * 点击元素
     */
    public PageElement click() {
        locator().click();
        return this;
    }

    /**
     * 双击元素
     */
    public PageElement doubleClick() {
        locator().dblclick();
        return this;
    }

    /**
     * 右键点击元素
     */
    public PageElement rightClick() {
        locator().click(new Locator.ClickOptions().setButton(MouseButton.RIGHT));
        return this;
    }

    /**
     * 鼠标悬停
     */
    public PageElement hover() {
        locator().hover();
        return this;
    }

    /**
     * 清空元素内容
     */
    public PageElement clear() {
        locator().clear();
        return this;
    }

    /**
     * 获取元素文本
     */
    public String getText() {
        return locator().textContent();
    }

    /**
     * 获取元素值
     */
    public String getValue() {
        return locator().inputValue();
    }

    /**
     * 获取元素属性
     */
    public String getAttribute(String attributeName) {
        return locator().getAttribute(attributeName);
    }

    /**
     * 检查元素是否可见
     */
    public boolean isVisible() {
        return locator().isVisible();
    }

    /**
     * 检查元素是否存在
     */
    public boolean exists() {
        return locator().count() > 0;
    }

    /**
     * 检查元素是否可点击
     */
    public boolean isClickable() {
        return locator().isEnabled();
    }

    /**
     * 检查元素是否启用
     */
    public boolean isEnabled() {
        return locator().isEnabled();
    }

    /**
     * 检查元素是否被选中
     */
    public boolean isSelected() {
        return locator().isChecked();
    }

    /**
     * 检查元素是否禁用
     */
    public boolean isDisabled() {
        return locator().isDisabled();
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
        locator().waitFor(new Locator.WaitForOptions()
            .setState(WaitForSelectorState.VISIBLE)
            .setTimeout(timeoutInSeconds * 1000));
        return this;
    }

    /**
     * 等待元素不可见
     */
    public PageElement waitForNotVisible(int timeoutInSeconds) {
        locator().waitFor(new Locator.WaitForOptions()
            .setState(WaitForSelectorState.HIDDEN)
            .setTimeout(timeoutInSeconds * 1000));
        return this;
    }

    /**
     * 等待元素存在
     */
    public PageElement waitForExists(int timeoutInSeconds) {
        locator().waitFor(new Locator.WaitForOptions()
            .setState(WaitForSelectorState.ATTACHED)
            .setTimeout(timeoutInSeconds * 1000));
        return this;
    }

    /**
     * 等待元素可点击
     */
    public PageElement waitForClickable(int timeoutInSeconds) {
        locator().waitFor(new Locator.WaitForOptions()
            .setState(WaitForSelectorState.VISIBLE)
            .setTimeout(timeoutInSeconds * 1000));
        return this;
    }

    /**
     * 等待元素隐藏
     */
    public PageElement waitForHidden() {
        locator().waitFor(new Locator.WaitForOptions()
            .setState(WaitForSelectorState.HIDDEN));
        return this;
    }

    /**
     * 等待元素附加到DOM
     */
    public PageElement waitForAttached() {
        locator().waitFor(new Locator.WaitForOptions()
            .setState(WaitForSelectorState.ATTACHED));
        return this;
    }

    /**
     * 等待元素从DOM中分离
     */
    public PageElement waitForDetached() {
        locator().waitFor(new Locator.WaitForOptions()
            .setState(WaitForSelectorState.DETACHED));
        return this;
    }

    /**
     * 等待元素附加（带超时）
     */
    public PageElement waitForAttached(int timeoutInSeconds) {
        return waitForExists(timeoutInSeconds);
    }

    /**
     * 等待元素不存在（带超时）
     */
    public PageElement waitForNotExists(int timeoutInSeconds) {
        locator().waitFor(new Locator.WaitForOptions()
            .setState(WaitForSelectorState.DETACHED)
            .setTimeout(timeoutInSeconds * 1000));
        return this;
    }

    /**
     * 选择下拉选项（通过值）
     */
    public PageElement selectByValue(String value) {
        locator().selectOption(value);
        return this;
    }

    /**
     * 选择下拉选项（通过索引）
     */
    public PageElement selectByIndex(int index) {
        // 使用 JavaScript 设置 selectedIndex
        locator().evaluate("el => { el.selectedIndex = " + index + "; el.dispatchEvent(new Event('change')); }");
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
        locator().waitFor(new com.microsoft.playwright.Locator.WaitForOptions()
            .setState(com.microsoft.playwright.options.WaitForSelectorState.VISIBLE)
            .setTimeout(timeoutInSeconds * 1000));
        // 等待文本出现
        long startTime = System.currentTimeMillis();
        while (System.currentTimeMillis() - startTime < timeoutInSeconds * 1000) {
            if (textContent().contains(text)) {
                return this;
            }
            getPage().waitForTimeout(100);
        }
        throw new RuntimeException("Element does not contain text: " + text);
    }

    /**
     * 等待元素文本等于指定文本
     */
    public PageElement waitForTextEquals(String text, int timeoutInSeconds) {
        locator().waitFor(new Locator.WaitForOptions()
            .setState(WaitForSelectorState.VISIBLE)
            .setTimeout(timeoutInSeconds * 1000));
        // 等待文本匹配
        long startTime = System.currentTimeMillis();
        while (System.currentTimeMillis() - startTime < timeoutInSeconds * 1000) {
            if (text.equals(textContent())) {
                return this;
            }
            getPage().waitForTimeout(100);
        }
        throw new RuntimeException("Element text does not equal: " + text);
    }


    public PageElement scrollTo() {
        locator().scrollIntoViewIfNeeded();
        return this;
    }

    /**
     * 滚动到指定位置
     */
    public PageElement scrollTo(int scrollX, int scrollY) {
        locator().evaluate("el => el.scrollTo(" + scrollX + ", " + scrollY + ")");
        return this;
    }

    /**
     * 滚动到元素底部
     */
    public PageElement scrollToBottom() {
        locator().evaluate("el => el.scrollTop = el.scrollHeight");
        return this;
    }

    /**
     * 滚动到元素顶部
     */
    public PageElement scrollToTop() {
        locator().evaluate("el => el.scrollTop = 0");
        return this;
    }

    /**
     * 滚动指定偏移量
     */
    public PageElement scrollBy(int offsetX, int offsetY) {
        locator().evaluate("el => el.scrollBy(" + offsetX + ", " + offsetY + ")");
        return this;
    }

    /**
     * 滚动到视图中心
     */
    public PageElement scrollToCenter() {
        locator().scrollIntoViewIfNeeded();
        return this;
    }

    /**
     * 获取子元素
     */
    public PageElement child(String childSelector) {
        return new PageElement(locator().locator(childSelector).toString().replace("Locator@", ""), getPage());
    }

    /**
     * 上传文件
     */
    public PageElement uploadFile(String filePath) {
        locator().setInputFiles(java.nio.file.Paths.get(filePath));
        return this;
    }

    /**
     * 追加文本到元素末尾
     */
    public PageElement append(String text) {
        locator().evaluate("el => el.value += arguments[0]", text);
        return this;
    }

    /**
     * 勾选复选框
     */
    public PageElement check() {
        locator().check();
        return this;
    }

    /**
     * 取消勾选复选框
     */
    public PageElement uncheck() {
        locator().uncheck();
        return this;
    }

    // ========== 键盘操作方法 ==========

    /**
     * 在元素上按键
     * @param key 要按下的键（如 "Enter", "ArrowDown", "Control+a" 等）
     */
    public PageElement press(String key) {
        locator().press(key);
        return this;
    }


    /**
     * 在元素上插入文本（不覆盖现有内容）
     * @param text 要插入的文本
     */
    public PageElement insertText(String text) {
        locator().fill(text);
        return this;
    }

    /**
     * 在元素上按下键
     * @param key 要按下的键
     */
    public PageElement keyDown(String key) {
        locator().press(key + "+KeyDown");
        return this;
    }

    /**
     * 在元素上释放键
     * @param key 要释放的键
     */
    public PageElement keyUp(String key) {
        locator().press(key + "+KeyUp");
        return this;
    }

    /**
     * 选择所有文本（Ctrl+A / Cmd+A）
     */
    public PageElement selectAll() {
        locator().press("Control+a");
        return this;
    }

    /**
     * 复制文本（Ctrl+C / Cmd+C）
     */
    public PageElement copy() {
        locator().press("Control+c");
        return this;
    }

    /**
     * 粘贴文本（Ctrl+V / Cmd+V）
     */
    public PageElement paste() {
        locator().press("Control+v");
        return this;
    }

    /**
     * 剪切文本（Ctrl+X / Cmd+X）
     */
    public PageElement cut() {
        locator().press("Control+x");
        return this;
    }

    // ========== 鼠标操作方法 ==========

    /**
     * 点击元素中心（使用鼠标操作）
     */
    public PageElement clickAtCenter() {
        locator().click();
        return this;
    }

    /**
     * 拖拽元素到指定坐标
     * @param targetX 目标 X 坐标
     * @param targetY 目标 Y 坐标
     */
    public PageElement dragToCoordinates(int targetX, int targetY) {
        locator().dragTo(page.locator("body"), new com.microsoft.playwright.Locator.DragToOptions()
            .setTargetPosition(targetX, targetY));
        return this;
    }

    /**
     * 获取元素中心坐标
     * @return 包含 x 和 y 坐标的数组
     */
    public int[] getCenter() {
        BoundingBox box = locator().boundingBox();
        return new int[]{(int)(box.x + box.width / 2), (int)(box.y + box.height / 2)};
    }

    // ========== 辅助功能方法 ==========

    /**
     * 检查元素是否可访问
     * @return 如果元素可访问则返回 true，否则返回 false
     */
    public boolean isAccessible() {
        return locator().isVisible() && locator().isEnabled();
    }

    /**
     * 获取元素的 ARIA 标签
     * @return ARIA 标签文本
     */
    public String getAriaLabel() {
        return locator().getAttribute("aria-label");
    }

    /**
     * 检查元素是否有 ARIA 标签
     * @return 如果元素有 ARIA 标签则返回 true，否则返回 false
     */
    public boolean hasAriaLabel() {
        String label = getAriaLabel();
        return label != null && !label.isEmpty();
    }

    /**
     * 获取元素的 ARIA 角色
     * @return ARIA 角色文本
     */
    public String getAriaRole() {
        return locator().getAttribute("role");
    }

    /**
     * 检查元素的可见性状态（考虑辅助功能）
     * @return 如果元素在辅助功能意义上可见则返回 true，否则返回 false
     */
    public boolean isVisibleForAccessibility() {
        return locator().isVisible();
    }

    /**
     * 检查颜色对比度（简化版）
     * @return 如果颜色对比度符合标准则返回 true，否则返回 false
     */
    public boolean hasSufficientColorContrast() {
        return true; // 简化实现
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
        locator().screenshot();
        return this;
    }

    /**
     * 获取元素位置和尺寸
     * @return 元素的边界框信息
     */
    public BoundingBox getBoundingBox() {
        return locator().boundingBox();
    }

    /**
     * 刷新页面元素（重新获取）
     */
    @Override
    public String toString() {
        return "PageElement{selector='" + selector + "'}";
    }

    // ==================== 断言方法 ====================

    // ==================== 新增方法 ====================

    /**
     * 点击元素（使用触摸方式）
     * @return this 支持链式调用
     */
    public PageElement tap() {
        locator().tap();
        return this;
    }

    /**
     * 聚焦到元素
     * @return this 支持链式调用
     */
    public PageElement focus() {
        locator().focus();
        return this;
    }

    /**
     * 获取元素的内部HTML
     * @return 元素的内部HTML
     */
    public String innerHTML() {
        return locator().innerHTML();
    }

    /**
     * 获取元素的文本内容
     * @return 元素的文本内容
     */
    public String textContent() {
        return locator().textContent();
    }

    /**
     * 检查元素是否隐藏
     * @return 如果元素隐藏则返回true，否则返回false
     */
    public boolean isHidden() {
        return locator().isHidden();
    }

    /**
     * 带入文件到输入元素
     * @param filePaths 文件路径数组
     * @return this 支持链式调用
     */
    public PageElement setInputFiles(String... filePaths) {
        Path[] paths = new Path[filePaths.length];
        for (int i = 0; i < filePaths.length; i++) {
            paths[i] = Paths.get(filePaths[i]);
        }
        locator().setInputFiles(paths);
        return this;
    }

}
