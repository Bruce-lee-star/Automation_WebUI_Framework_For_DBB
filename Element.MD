# WebUI 自动化框架 - 元素定位方法手册
**仅包含：元素定位 + 选择器 + @Element + PageElement / BasePage 定位相关API**
无多余内容，简洁可直接归档

---

## 一、@Element 注解（元素声明核心）
### 作用
在 Page 类中**自动声明元素**，无需手动 `new`

### 语法
```java
@Element("选择器")
private PageElement 元素名;

@Element(value = "选择器", description = "描述")
private PageElement 元素名;
```

### 支持类型
- `PageElement`：单个元素
- `PageElementList`：元素列表

### 示例
```java
@Element("#username")
private PageElement usernameInput;

@Element(value = ".submit-btn", description = "登录按钮")
private PageElement loginButton;

@Element(".list-item")
private PageElementList itemList;
```

---

## 二、支持的元素定位语法（全部）
### 1. CSS 选择器（最常用）
```java
#id
.class
tag
[attribute]
[attribute='value']
parent > child
tag.class
```

### 2. 属性定位
```java
[type='text']
[role='button']
[data-testid='submit']
```

### 3. 文本定位（Playwright 内置）
```java
:has-text('登录')
button:has-text('确定')
```

### 4. 层级/父子
```java
div.container > button
ul li:nth-child(1)
```

### 5. XPath 定位
```java
//div[@id='login']
//button[text()='登录']
```

---

## 三、BasePage 中 元素定位 相关方法
### 1. 基础定位器
```java
Locator locator(String selector)
```

### 2. 内置定位方法
```java
Locator byAltText(String altText)
Locator byRole(AriaRole role)
Locator byTitle(String title)
Locator byTestId(String testId)
```

### 3. 元素数量
```java
int getElementCount(String selector)
```

---

## 四、PageElement 元素定位相关方法
### 1. 获取当前元素定位器
```java
Locator locator()
```

### 2. 子元素/内部定位
```java
PageElement find(String childSelector)
```

示例：
```java
table.find("tr:nth-child(1)").click();
```

### 3. 列表元素定位
```java
PageElement get(int index)
```

---

## 五、元素等待（与定位强相关）
```java
waitForElementVisibleWithinTime(selector, 秒)
waitForElementHiddenWithinTime(selector, 秒)
waitForElementClickableWithinTime(selector, 秒)
waitForElementExists(selector, 秒)
waitForElementNotExists(selector, 秒)
```

---

## 六、最简使用示例
```java
public class LoginPage extends SerenityBasePage {

    @Element("#loginForm")
    private PageElement loginForm;

    public void inputUsername(String name) {
        // 子元素定位
        loginForm.find("#username").type(name);
    }
}
```

---

## 七、定位相关速查（极简版）
- `@Element(selector)` → 声明元素
- `locator(selector)` → 获取定位器
- `find(childSelector)` → 子元素定位
- `get(index)` → 列表元素定位
- `byTestId/byTitle/byAltText/byRole` → 快捷定位