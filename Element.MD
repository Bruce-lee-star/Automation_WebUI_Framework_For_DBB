# WebUI 自动化框架 — 元素定位方法手册（详细版 v3.0）

> **最后更新：** 2026-06-04
> **覆盖范围：** `@Element` 注解 / PageElement / PageElementList / BasePage / SerenityBasePage 全部定位相关 API

---

## 目录

- [一、类体系架构](#一类体系架构)
- [二、@Element 注解（元素声明）](#二element-注解元素声明)
- [三、选择器语法（全部支持）](#三选择器语法全部支持)
- [四、PageElement 元素操作](#四pageelement-元素操作)
  - [4.1 定位器](#41-定位器)
  - [4.2 点击操作](#42-点击操作)
  - [4.3 输入操作](#43-输入操作)
  - [4.4 选择框操作](#44-选择框操作)
  - [4.5 状态查询](#45-状态查询)
  - [4.6 文本与属性](#46-文本与属性)
  - [4.7 等待方法（元素级别）](#47-等待方法元素级别)
  - [4.8 其他操作](#48-其他操作)
- [五、PageElementList 列表元素](#五pageelementlist-列表元素)
- [六、BasePage 页面级定位方法](#六basepage-页面级定位方法)
  - [6.1 核心定位器](#61-核心定位器)
  - [6.2 内置语义定位器](#62-内置语义定位器)
  - [6.3 条件等待引擎](#63-条件等待引擎)
  - [6.4 元素状态等待](#64-元素状态等待)
  - [6.5 属性/文本/数量等待](#65属性文本数量等待)
  - [6.6 URL/标题/页面加载等待](#66url标题页面加载等待)
  - [6.7 操作委托方法](#67-操作委托方法)
- [七、SerenityBasePage 增强层](#七serenitybasepage-增强层)
- [八、完整速查表](#八完整速查表)

---

## 一、类体系架构

```
BasePage (abstract)                          ← 所有页面的基类
  │
  ├── SerenityBasePage (abstract)            ← Serenity BDD 测试专用（双拦截器精简 87 个 Override）
  │     │
  │     └── 你的 Page 类 (extends SerenityBasePage)
  │
  └── 使用:
      ├── PageElement                        ← 单个元素封装
      │     ├── ChildPageElement (内部)       ← Locator.locator() 链式嵌套定位
      │     └── PageElementWithIndex (内部)   ← 带 index 的缓存定位器
      │
      └── PageElementList extends AbstractList<PageElement>
            └── 内部使用 PageElementWithIndex

TextNormalizer ← 统一文本标准化管道（NFKC → 去控制字符 → 合并空白 → trim）
                    BasePage / PageElement 共享实现，避免重复 Pattern 定义

ElementDiagnosticsCollector ← 失败诊断信息收集器（批量 JS 单次 IPC）
@Element 注解 → 仅支持 CSS / XPath 选择器，自动初始化字段为 PageElement 或 PageElementList
```

**设计原则：**
- **@Element 声明 + 自动注入**：无需手动 new，构造函数中自动扫描并初始化；页面切换时仅刷新 Locator 缓存（不重建对象）
- **链式调用**：所有 PageElement 操作返回 this，支持 `.click().type("x").press("Enter")`
- **企业级重试**：`executeWithRetry` 自动诊断 DOM 状态 + 失败截图；`executeSafely` 模板统一读取操作异常转换
- **文本标准化**：`TextNormalizer` 统一管道（NFKC 规范化 → 去控制字符 → 合并空白 → 去标点前空格 → trim），BasePage/PageElement 共享

---

## 二、@Element 注解（元素声明）

### 作用

在 Page 类中**声明式定义元素**，框架自动注入实例。

> **选择器仅支持 CSS 和 XPath 两种类型。** CSS 直接书写，XPath 以 `//` 或 `xpath=` 开头。

### 语法

```java
@Element("CSS或XPath选择器")
private PageElement 元素名;

@Element(value = "CSS或XPath选择器", description = "描述文字")
private PageElement 元素名;
```

### 支持的类型

| 字段类型 | 含义 | 示例 |
|----------|------|------|
| `PageElement` | 单个元素 | 按钮、输入框、链接等 |
| `PageElementList` | 元素集合 | 表格行列表、选项列表等 |

### XPath 选择器示例

```java
@Element("//button[contains(@class, 'primary')]")
private PageElement primaryBtn;

@Element("xpath=//div[@data-testid='container']")
private PageElement container;
```

### 示例

```java
public class LoginPage extends SerenityBasePage {

    @Element("#username")
    private PageElement usernameInput;

    @Element(value = "#login-btn", description = "登录按钮")
    private PageElement loginButton;

    @Element(".error-message")
    private PageElement errorMsg;

    @Element(".table-row")
    private PageElementList tableRows;

    public void inputUsername(String name) {
        usernameInput.type(name);
        // 等价于：locator("#username").fill(name);
    }

    public void clickLogin() {
        loginButton.click("登录按钮点击");
    }

    public int getRowCount() {
        return tableRows.size();   // PageElementList 实现了 List 接口
    }
}
```

### 注解处理器工作流程

```
1. Page 构造函数执行 super()
2. 扫描当前类所有 @Element 字段
3. 对每个字段：
   a. 提取 selector 和 description
   b. 如果类型是 PageElement → new PageElement(selector, this)
   c. 如果类型是 PageElementList → new PageElementList(selector, this)
4. 通过反射设置字段值
```

---

## 三、选择器语法（全部支持）

### 1. CSS 选择器（最常用 / 推荐）

```java
"#id"                    // ID 选择器
".class"                 // 类选择器
"tag"                    // 标签选择器
"[attribute]"            // 属性存在选择器
"[attribute='value']"    // 属性值选择器
"parent > child"         // 直接子代
"ancestor descendant"    // 后代
"tag.class"              // 标签+类组合
".class1.class2"         // 多类
"tag[attr='val']"        // 标签+属性
```

### 2. Playwright 内置伪类

```java
":has-text('登录')"              // 包含指定文本
"button:has-text('确定')"       // 按钮包含文本
":visible"                       // 可见元素
":first-child"                   // 第一个子元素
":nth-child(2)"                  // 第 N 个子元素
"> > :text('Submit')"           // 精确匹配文本
```

### 3. 属性定位

```java
"[data-testid='submit-button']"   // data 属性（推荐用于测试！）
"[role='button']"                 // ARIA 角色
"[name='username']"               // name 属性
"[type='email']"                  // type 属性
"[placeholder='请输入...']"       // placeholder
```

### 4. 层级 / 父子关系

```java
"div.container > button"          // 直接子元素
"form.login-form input[type=text]" // 后代嵌套
"ul.menu li:nth-child(1)"        // 第一个 li
".table tr:nth-of-type(even)"     // 偶数行
```

### 5. XPath 定位（Playwright 支持）

```java
"//div[@id='login']"
"//button[text()='登录']"
"//input[@name='user' and @type='text']"
"//table//tr[td[contains(text(),'Order')]]"
```

### 推荐优先级

```
1. data-testid（最稳定）: "[data-testid='xxx']"
2. ID                    : "#element-id"
3. CSS Class             : ".primary-btn"
4. ARIA Role             : [role='button']
5. Text Content          : :has-text('确定')
6. XPath（复杂场景兜底） : //div[...]
```

---

## 四、PageElement 元素操作

### 4.1 定位器

```java
// 获取 Playwright Locator 对象（自动 waitForDOMContentLoaded）
Locator loc = element.locator();

// 相对定位（子元素）
Locator subLoc = element.locator(".child-class");

// 获取原始 CSS 选择器字符串
String sel = element.getSelector();
```

### 4.2 点击操作

```java
// 无参点击
loginButton.click();

// 带测试名点击（用于截图命名和日志）
loginButton.click("登录按钮");

// 双击
submitButton.doubleClick();

// 右键
contextMenuBtn.rightClick();
```

**返回值：** 所有方法都返回 `this`（`PageElement`），支持链式调用。

**内置能力：**
- 自动等待元素可见 + 可点击
- 企业级重试机制（可配置重试次数和间隔）
- 失败时自动截图 + 诊断信息采集
- 异常分类（超时 vs 元素不存在 vs 其他）

### 4.3 输入操作

```java
// fill — 清空后填充（推荐）
usernameInput.fill("admin");
usernameInput.fill("admin", "填写用户名");   // 带测试名

// type — 模拟逐字键盘输入
passwordInput.type("mypassword123");

// clear — 清空内容
searchBox.clear();

// 组合操作
searchBox.clearAndSetValue("new value");
searchBox.clearAndTypeSequentially("sequential");
```

**fill vs type 的区别：**
- `fill()` = 清空 + 设置 value（快速，不触发键盘事件）
- `type()` = 逐字符模拟键盘输入（慢但真实，会触发 keydown/input/keyup 事件）

### 4.4 键盘操作

```java
// 按下单个键/快捷键
searchInput.press("Enter");          // Enter
searchInput.press("Tab");            // Tab
searchInput.press("Control+a");      // Ctrl+A 全选

// 选中所有文本
searchInput.selectText();
```

### 4.5 选择框操作

```java
// Select 下拉框
countrySelect.selectByValue("CN");          // 按 value
countrySelect.selectByIndex(0);              // 按索引（从 0 开始）
countrySelect.selectByVisibleText("中国");   // 按显示文本
```

### 4.6 状态查询

```java
// 可见性
boolean visible = btn.isVisible();            // 默认超时
boolean visible = btn.isVisible(10);          // 自定义超时（秒）
boolean hidden = btn.isNotVisible();

// DOM 存在性
boolean exists = btn.exists();

// 启用/禁用
boolean enabled = btn.isEnabled();
boolean disabled = btn.isDisabled();

// 编辑态
boolean editable = btn.isEditable();

// 选中态（checkbox/radio）
boolean checked = btn.isChecked();
```

每个方法都有 **两个版本**：
- 无参版：使用默认超时
- 有 `(int timeoutSec)` 版本：自定义超时

### 4.7 文本与属性

```java
// 获取元素文本（innerText，经 TextNormalizer 标准化管道处理）
String text = element.getText();

// 原始文本（跳过标准化管道，性能优化：列表遍历场景避免 4 次正则）
String raw = element.getTextRaw();

// HTML 内容
String html = element.getInnerHtml();

// 所有文本节点内容
List<String> texts = element.getAllTextContents();

// 属性值
String href = link.getAttribute("href");
String placeholder = input.getAttribute("placeholder");

// 输入框的当前 value
String val = input.getValue();
```

**文本标准化管道（`TextNormalizer.normalize()`）：**
1. NFKC 规范化 — en-dash/em-dash→-、smart-quotes→"'、NBSP→空格、全角→半角
2. 删除零宽空格等格式控制字符（`\p{Cf}`）
3. 合并所有空白为单个空格
4. 去掉中英文标点前的空格
5. trim 首尾空白

> `getText()` 自动走完整管道，`getTextRaw()` 跳过管道直接返回原始文本（列表遍历时推荐使用以提升性能）。`TextNormalizer` 为 BasePage 和 PageElement 共享实现，避免重复 Pattern 定义。

### 4.8 等待方法（元素级别）

所有 `waitFor*` 方法返回 `this`（链式）：

```java
element.waitForVisible()                // 等待可见
element.waitForVisible(10);             // 最多等 10 秒
element.waitForNotVisible()            // 等待不可见
element.waitForExists()                // 等待存在于 DOM
element.waitForNotExists()            // 等待从 DOM 消失
element.waitForClickable()             // 等待可点击（visible + enabled + stable）
element.waitForEditable()              // 等待可编辑
element.waitForEnabled()               // 等待启用
element.waitForDisabled()              // 等待禁用
element.waitForChecked()               // 等待选中
element.waitForNotChecked()           // 等待未选中
```

### 4.9 其他操作

```java
// 滚动到可视区
element.scrollToView();

// 悬停 / 聚焦
element.hover();
element.focus();

// Checkbox / Radio
element.check();
element.uncheck();

// 触发事件
element.dispatchEvent("change");
element.dispatchEvent("focus");

// 文件上传
fileInput.uploadFile("/path/to/file.txt", "/path/to/file2.png");

// 截图（返回 byte[]）
byte[] screenshotBytes = element.screenshot();

// 拖拽
sourceElement.dragTo(targetElement);

// 子元素定位（使用 Locator.locator() 链式定位，非字符串拼接）
PageElement cell = table.child("tr:nth-child(1)").child("td:nth-child(2)");
PageElement cellWithIndex = row.child("td", 2);  // + nth=2

// 元素数量
int count = element.count();

// 边界框（确保可见后获取）
BoundingBox box = element.getBoundingBoxSafe();

// 底层 ElementHandle
ElementHandle handle = element.elementHandle();
```

---

## 五、PageElementList 列表元素

实现 `AbstractList<PageElement>`，完全兼容 Java 集合框架。

### 创建方式

```java
@Element(".menu-item")
private PageElementList menuItems;
```

### 核心操作

```java
// 大小（默认超时）
int count = menuItems.size();
int count = menuItems.size(10);          // 自定义超时

// 空判断
boolean empty = menuItems.isEmpty();
boolean hasElements = menuItems.hasElements();

// 获取单个元素（带缓存定位器）
PageElement first = menuItems.first();    // get(0)，空抛异常
PageElement last = menuItems.last();
PageElement item = menuItems.get(2);       // 第 3 个（索引从 0 开始）

// 迭代
for (PageElement item : menuItems) {      // for-each
    System.out.println(item.getText());
}

// 安全遍历（跳过异常元素继续）
menuItems.forEachSafe(item -> {
    try { item.click(); } catch (Exception e) { /* skip */ }
});

// 等待条件
menuItems.waitForExists(10);               // 等待至少 1 个存在
menuItems.waitForVisible(10);             // 等待至少 1 个可见
menuItems.waitForCount(5, 15);            // 等待元素数量 >= 5

// 获取所有 Locators（DOM 快照）
List<Locator> allLocs = menuItems.allLocators();
```

**性能说明：**
- `get(index)` 内部使用 `PageElementWithIndex` 缓存定位器，避免重复解析
- `size()` 默认使用配置的超时时间
- `iterator()` 返回快照迭代器（非实时视图）

---

## 六、BasePage 页面级定位方法

> BasePage 是所有页面的抽象基类。SerenityBasePage 继承它并添加 Serenity 日志集成。

### 6.1 核心定位器

```java
// ★★★ 最核心的方法：通过 CSS/XPath 创建 Locator ★★★
Locator loc = locator("#submit-button");
// 内部自动 ensurePageValid()

// 相对定位
Locator inner = locator("#form").locator("input[type=text]");
```

### 6.2 内置语义定位器

```java
// 通过 alt 文本定位图片/区域
Locator img = byAltText("公司Logo");

// 通过 ARIA 角色定位
Locator dialog = byRole(AriaRole.DIALOG);
Locator button = byRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("提交"));

// 通过 title 属性定位
Locator tip = byTitle("提示信息");

// 通过 data-testid 定位（推荐用于自动化测试！）
Locator submitBtn = byTestId("submit-order");
```

**对应 Playwright 原生方法：**
- `byAltText(text)` → `page.getByAltText(text)`
- `byRole(role)` → `page.getByRole(role)`
- `byTitle(title)` → `page.getByTitle(title)`
- `byTestId(id)` → `page.getByTestId(id)`

### 6.3 条件等待引擎

BasePage 的所有等待方法底层统一走 `waitForCondition` 引擎：

```java
// 自定义条件等待
waitForCustomCondition(() -> {
    return getPageSourceContains("加载完成");
}, 10, "等待页面加载完成");

// 带超时的操作执行
performActionWithTimeout(
    () -> click("#submit"),
    () -> isVisible("#success-msg"),
    10, "点击提交后等待成功消息"
);
```

### 6.4 元素状态等待（基于 Selector 字符串）

```java
// ★★★ 四大核心等待 ★★★
waitForElementVisibleWithinTime("#loading", 10);      // 等待可见
waitForElementHiddenWithinTime("#loading", 30);       // 等待隐藏
waitForElementExists("#dialog", 10);                  // 等待 DOM 存在
waitForElementNotExists("#spinner", 10);              // 等待 DOM 消失
waitForElementClickableWithinTime("#btn-submit", 10); // 等待可点击

// 扩展等待
waitForElementEditable("#input-field", 10);
waitForElementEnabled("#btn", 10);
waitForElementDisabled("#btn", 10);
waitForElementChecked("#checkbox", 10);
waitForElementNotChecked("#checkbox", 10);
```

### 6.5 属性 / 文本 / 数量等待

```java
// 属性等待
waitForElementAttributeEquals("#link", "href", "/home", 10);
waitForElementAttributeContains("#link", "href", "api", 10);

// 文本等待（已归一化比较）
waitForElementTextEquals("#status", "成功", 10);
waitForElementTextContains("#msg", "错误", 10);

// 数量等待
waitForElementCount(".list-item", 5, 10);           // 精确等于 5 个
waitForElementCountAtLeast(".list-item", 3, 10);    // 至少 3 个
```

### 6.6 URL / 标题 / 页面加载等待

```java
// URL
waitForUrlContainsWithinTime("dashboard", 10);
waitForUrlEquals("https://host/page", 10);
waitForUrlStartsWith("https://host/", 10);

// 标题
waitForTitleContainsWithinTime("首页", 10);

// 页面加载状态
waitForNetworkIdle(30);            // 等待网络空闲（无超过 500ms 的请求）
waitForPageFullyLoaded(30);        // 等待页面完全加载
waitForDOMContentLoaded(10);        // 等待 DOM 解析完成（最快）
```

### 6.7 操作委托方法

这些方法在 BasePage 中通过创建临时 `PageElement` 来执行，复用企业级重试机制。

```java
// ====== 点击 ======
click("#button");
jsClick("#button");                     // JS el.click()（绕过可点击检查）

// ====== 输入 ======
type("#input", "hello world");          // 逐字输入
append("#input", " more text");          // 追加文本
clear("#input");                         // 清空

// ====== 读取 ======
String text = getText("#label");          // 已归一化
String value = getInputValue("#field");
String attr = getAttribute("#link", "href");
String attrVal = getAttributeValue("#el", "data-x", "default");

// ====== 选择框 ======
selectOption("#country", 2);             // 按索引
selectByVisibleText("#role", "管理员");

// ====== 勾选框 ======
check("#agree");
uncheck("#remember");

// ====== 状态查询 ======
boolean isChecked = isChecked("#toggle");
boolean isEnabled = isEnabled("#btn");
boolean isDisabled = isDisabled("#btn");
boolean isVisible = isVisible("#msg");
boolean isHidden = isHidden("#msg");

// ====== 元素数量 ======
int count = getElementCount(".item");

// ====== 导航 ======
navigateTo("https://example.com");
String url = getCurrentUrl();
String title = getTitle();
refresh();
back();
forward();

// ====== 多页面 ======
switchToPage(1);
switchToLatestPage();
closeCurrentPageAndSwitchBack();
resetToMainPage();                      // 关闭多余页面，保留主页

// ====== 滚动 ======
scrollToElementCenter("#target");
scrollTo("#container", 0, 200);
scrollToBottomOf("#long-list");
scrollToTopOf("#long-list");

// ====== JS / HTML ======
Object result = executeJavaScript("return document.title");
String html = innerHTML("#content");
String text = textContent("#main");

// ====== 拖拽 / 聚焦 / 悬停 ======
dragAndDrop("#source", "#target");
focus("#input");
hover("#menu-item");

// ====== 弹窗 ======
acceptAlert();
dismissAlert();

// ====== 截图 ======
byte[] pageScreenshot = takeScreenshot();            // 整页
byte[] elemScreenshot = takeElementScreenshot("#card");

// ====== 键盘 ======
keyDown("#input", "Control");
keyUp("#input", "Control");
press("#input", "Enter");

// ====== 安全暂停 ======
pause();                              // CI 环境自动跳过，仅本地 Debug 模式有效
```

### 6.8 重试机制

```java
// 基础重试
retry(() -> click("#flaky-btn"), "点击不稳定按钮");

// 带参数的重试
retry(() -> click("#btn"), 3, 1000, "重试3次每次间隔1秒");

// 重试 + 验证
retryWithValidation(
    () -> click("#submit"),
    () -> isVisible("#success"),
    3, "提交后验证成功消息"
);

// 便捷重试方法
waitForVisibleWithRetry("#slow-element", 10, 3);
waitForHiddenWithRetry("#overlay", 10, 3);
clickWithRetry("#unstable-btn", 3);
typeWithRetry("#auto-fill", "text", 3);
navigateToWithRetry("https://example.com", 3);
```

---

## 七、SerenityBasePage 增强层

继承 `BasePage`，是 **Serenity BDD 测试专用基类**。

> **v3.0 重构**：通过 `record()` / `recordAndReturn()` 双拦截器消除 87 个冗余 `@Override super.xxx() + addSerenityTestData()` 模式；`VERBOSE_LOGGING` 标志按需记录，成功路径零开销。

### 与 BasePage 的区别

| 特性 | BasePage | SerenityBasePage |
|------|----------|------------------|
| 适用场景 | 通用 / 非 BDD | Serenity BDD 测试 |
| 日志输出 | DEBUG 级别 | INFO + `[Serenity]` 前缀（需开启 VERBOSE_LOGGING） |
| 数据记录 | 无 | 按需 `addSerenityTestData()`（VERBOSE_LOGGING 控制） |
| 验证记录 | 无 | `recordVerification(PASS/FAIL)` |
| 实现方式 | — | `record()` / `recordAndReturn()` 双拦截器消除所有冗余 Override |

### 新增功能

```java
// 验证方法（自动记录 PASS/FAIL 到 Serenity 数据）
verifyPageTitleContains("Dashboard");
verifyPageTitleEquals("Welcome");
verifyUrlContains("/dashboard");

// Serenity 数据管理
addSerenityTestData("loginTime", System.currentTimeMillis());
Object data = getSerenityTestData("loginTime");
Map<String, Object> allData = getSerenityTestDataMap();  // 返回副本
clearSerenityTestData();

// 记录验证结果
recordPageVerification("页面标题验证", true);   // true=PASS, false=FAIL
```

### 推荐使用

**所有 Cucumber / Serenity BDD 测试项目的 Page 类应继承 `SerenityBasePage`：**

```java
public class LoginPage extends SerenityBasePage {

    @Element("#username")
    private PageElement usernameInput;

    @Element(value = "#password", description = "密码输入框")
    private PageElement passwordInput;

    @Element("#login-btn")
    private PageElement loginButton;

    public void login(String user, String pass) {
        usernameInput.fill(user);
        passwordInput.fill(pass);
        loginButton.click("点击登录按钮");
        // 以上每个操作都会自动记录到 Serenity 测试数据
    }
}
```

---

## 八、完整速查表

### @Element 声明速查

| 写法 | 类型 | 说明 |
|------|------|------|
| `@Element("#id")` | PageElement | ID 定位 |
| `@Element(value=".cls", desc="x")` | PageElement | 类 + 描述 |
| `@Element("[data-testid='x']")` | PageElement | testId（推荐） |
| `@Element(".items")` | PageElementList | 列表 |

### PageElement 速查

| 方法 | 返回 | 说明 |
|------|------|------|
| `locator()` | Locator | Playwright Locator |
| `locator(sub)` | Locator | 相对子定位 |
| `child(sel)` | PageElement | 子元素 |
| `click([name])` | PageElement | 点击 |
| `doubleClick()` | PageElement | 双击 |
| `rightClick()` | PageElement | 右键 |
| `fill(text[, name])` | PageElement | 填充 |
| `type(text)` | PageElement | 逐字输入 |
| `clear()` | PageElement | 清空 |
| `selectByValue(val)` | PageElement | 下拉选 value |
| `selectByIndex(i)` | PageElement | 下拉选索引 |
| `selectByText(t)` | PageElement | 下拉选文本 |
| `press(key)` | PageElement | 键盘按键 |
| `getText()` | String | 标准化文本（TextNormalizer） |
| `getTextRaw()` | String | 原始文本（跳过标准化管道，性能优化） |
| `getAttribute(attr)` | String | 属性值 |
| `getValue()` | String | inputValue |
| `isVisible([t])` | boolean | 是否可见 |
| `exists([t])` | boolean | 是否存在 |
| `isEnabled([t])` | boolean | 是否启用 |
| `isChecked([t])` | boolean | 是否选中 |
| `waitForVisible([t])` | PageElement | 等可见 |
| `waitForClickable([t])` | PageElement | 等可点击 |
| `waitForExists([t])` | PageElement | 等存在 |
| `waitForNotExists([t])` | PageElement | 等消失 |
| `hover()` | PageElement | 悬停 |
| `focus()` | PageElement | 聚焦 |
| `check/uncheck()` | PageElement | 勾选/取消 |
| `scrollToView()` | PageElement | 滚动到视口 |
| `screenshot()` | byte[] | 截图 |
| `count()` | int | 匹配数量 |

### BasePage 速查

| 方法 | 说明 |
|------|------|
| `locator(sel)` | 核心：创建 Locator |
| `byAltText/byRole/byTitle/byTestId` | 语义定位器 |
| `waitForElementVisibleWithinTime(sel, t)` | 等可见 |
| `waitForElementHiddenWithinTime(sel, t)` | 等隐藏 |
| `waitForElementExists(sel, t)` | 等 DOM 存在 |
| `waitForElementNotExists(sel, t)` | 等 DOM 消失 |
| `waitForElementClickableWithinTime(sel, t)` | 等可点击 |
| `waitForElementCount(sel, expected, t)` | 等精确数量 |
| `waitForUrlContainsWithinTime(kw, t)` | 等 URL 包含 |
| `waitForTitleContainsWithinTime(kw, t)` | 等标题包含 |
| `waitForNetworkIdle(t)` | 等网络空闲 |
| `waitForPageFullyLoaded(t)` | 等页面完全加载 |
| `getElementCount(sel)` | 匹配元素数量 |
| `navigateTo(url)` | 导航 |
| `getCurrentUrl/getTitle` | 获取 URL/标题 |
| `executeJavaScript(script, args...)` | 执行 JS |
| `switchToPage(i)` | 切换页面标签 |
| `retry(action, desc)` | 重试 |
| `pause()` | 安全暂停（CI跳过） |

---
