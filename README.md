# Automation WebUI Framework BDD

> **企业级 Web UI 自动化测试框架** — 基于 Playwright + Serenity BDD + Cucumber，构建的高效、稳定、可扩展的自动化测试解决方案。

---

## 📋 框架概览

| 属性 | 说明 |
|------|------|
| **技术栈** | Java 21 + Playwright 1.58 + Serenity BDD 4.3.4 + Cucumber 7.14 |
| **架构模式** | BDD 行为驱动开发（Cucumber Gherkin）+ Page Object Model |
| **构建工具** | Maven |
| **测试范围** | Web UI 桌面端自动化测试 |
| **CI/CD 集成** | Jenkins（Pipeline / Freestyle） |

---

## 🧠 Playwright 核心概念

在深入框架之前，先理解 Playwright 的三层架构：

### Playwright → Browser → BrowserContext → Page

```
┌──────────────────────────────────────────┐
│              Playwright                  │  ← 顶层入口，管理浏览器下载、启动
│  ┌────────────────────────────────────┐  │
│  │           Browser                  │  │  ← 一个浏览器进程（Chromium/Firefox/WebKit）
│  │  ┌──────────────────────────────┐  │  │
│  │  │       BrowserContext         │  │  │  ← 独立的浏览器会话（隔离的 Cookie/LocalStorage）
│  │  │  ┌────────────────────────┐  │  │  │
│  │  │  │        Page            │  │  │  │  ← 一个标签页/窗口，执行页面操作的最小单元
│  │  │  └────────────────────────┘  │  │  │
│  │  │  ┌────────────────────────┐  │  │  │
│  │  │  │        Page            │  │  │  │  ← 同一个 Context 可有多个 Page
│  │  │  └────────────────────────┘  │  │  │
│  │  └──────────────────────────────┘  │  │
│  │  ┌──────────────────────────────┐  │  │
│  │  │       BrowserContext         │  │  │  ← 另一个 Context 完全隔离（如多用户登录）
│  │  │  ┌────────────────────────┐  │  │  │
│  │  │  │        Page            │  │  │  │
│  │  │  └────────────────────────┘  │  │  │
│  │  └──────────────────────────────┘  │  │
│  └────────────────────────────────────┘  │
└──────────────────────────────────────────┘
```

| 层级 | 职责 | 类比 | 本框架中的管理方式 |
|------|------|------|-------------------|
| **Playwright** | 管理浏览器驱动，提供 `chromium()`/`firefox()`/`webkit()` 工厂方法 | 浏览器引擎管理器 | `PlaywrightManager` 静态 Map 管理，同一配置复用 |
| **Browser** | 一个浏览器进程实例，可包含多个 Context | 浏览器应用程序 | `browserInstances` Map，按 configId 隔离，支持动态切换（Chromium/Firefox/WebKit） |
| **BrowserContext** | 独立的浏览器会话，Cookie/LocalStorage/登录态完全隔离 | 隐身窗口/用户配置文件夹 | `contextThreadLocal` ThreadLocal，每个 Scenario 独立 Context，可自定义 viewport/locale/timezone 等 |
| **Page** | 一个标签页，所有页面操作（导航/点击/输入/截图）的载体 | 浏览器标签页 | `pageThreadLocal` ThreadLocal，每个线程一个 Page |

**关键设计：**
- **Browser 与 Context 的关系**：Browser 是共享的进程，Context 是隔离的会话。频繁创建/销毁 Browser 开销大，所以采用 Browser 复用 + Context 隔离的策略。
- **Context 隔离**：不同 Context 之间 Cookie、LocalStorage、SessionStorage 完全隔离，模拟多用户/多会话场景。
- **重启策略**：`serenity.playwright.restart.browser.for.each` 控制 Context 重建粒度 — `scenario` 模式每场景重建 Context，`feature` 模式同 Feature 内复用 Context。**两种模式均支持登录态跨 Scenario 复用**：通过 `SessionManager` 将 Cookie/LocalStorage 持久化到 `target/.sessions/` 目录，下一个 Scenario 自动恢复。

---

## 🏗️ 架构设计

### 整体架构

```
┌─────────────────────────────────────────────────────────────┐
│                      测试执行层 (Test)                        │
│  ┌─────────────┐  ┌──────────────┐  ┌──────────────────┐   │
│  │ Cucumber    │  │ Feature      │  │ Step Definitions │   │
│  │ Features    │  │ Files        │  │ (Glue Code)      │   │
│  └─────────────┘  └──────────────┘  └──────────────────┘   │
├─────────────────────────────────────────────────────────────┤
│                     核心框架层 (Framework)                    │
│  ┌─────────────────────────────────────────────────────┐    │
│  │                  Page Object Model                   │    │
│  │  PageElement → BasePage → SerenityBasePage          │    │
│  └─────────────────────────────────────────────────────┘    │
│  ┌──────────────┐ ┌──────────────┐ ┌──────────────────┐    │
│  │ Route Engine │ │ Session 管理  │ │ 无障碍扫描        │    │
│  │ (Monitor/    │ │ SessionMgr   │ │ AxeCore          │    │
│  │  Mock/Modify)│ │              │ │                  │    │
│  └──────────────┘ └──────────────┘ └──────────────────┘    │
│  ┌──────────────┐ ┌──────────────┐ ┌──────────────────┐    │
│  │ 截图管理      │ │ 配置中心     │ │ 监听器体系        │    │
│  │ Screenshot   │ │ Config       │ │ Listener         │    │
│  └──────────────┘ └──────────────┘ └──────────────────┘    │
├─────────────────────────────────────────────────────────────┤
│                     基础设施层 (Infrastructure)                │
│  ┌──────────────────────────────────────────────────────┐   │
│  │  PlaywrightManager → Playwright / Browser /           │   │
│  │  BrowserContext / Page 生命周期管理                    │   │
│  └──────────────────────────────────────────────────────┘   │
├─────────────────────────────────────────────────────────────┤
│                       报告层 (Reporting)                      │
│  ┌──────────────────────────┐  ┌──────────────────────────┐  │
│  │ Serenity HTML Report     │  │ Summary Report (HTML)     │  │
│  │ (详细步骤级)              │  │ (邮件摘要 + CSV/ZIP下载)  │  │
│  └──────────────────────────┘  └──────────────────────────┘  │
└─────────────────────────────────────────────────────────────┘
```

---

## 🎯 核心能力

### 1. 🤖 智能 Page Object Model

```java
// 链式调用 + 智能等待（自动处理元素状态）
LoginPage.USERNAME_INPUT.type("user")
    .waitForVisible(10)
    .click();

// 轮询等待 - 用于属性检查（Playwright 不原生支持的状态）
boolean enabled = LoginPage.SUBMIT_BUTTON.isEnabled();  // 自动轮询直到启用/超时

// 文本内容等待
LoginPage.STATUS_MSG.waitForContainsText("登录成功", 15);
```

**核心特性：**
- **智能等待**：使用 Playwright 原生 `waitFor()` 处理元素可见性、存在性、隐藏等状态
- **轮询等待**：对于 `isEnabled()`、`isSelected()` 等属性检查，自动轮询直到条件满足
- **链式调用**：所有操作返回 `this`，支持流畅的链式编程
- **双超时机制**：支持全局默认超时和单次调用指定超时

**核心类：**
| 类 | 功能 |
|----|------|
| `PageElement` | 页面元素包装类，支持所有元素操作、智能等待和链式调用 |
| `PageElementList` | 元素列表操作，动态查询 + 多阶段智能等待 |
| `Element` | `@Element` 注解，标记 Page 字段选择器，支持自动初始化 |
| `BasePage` | 基础页面类，封装 10+ 种元素定位方法和所有 Playwright 核心操作 |
| `SerenityBasePage` | Serenity 集成的页面实现 |
| `PageObjectFactory` | 页面对象工厂，支持单例/原型/线程隔离等生命周期策略 |

---

### 2. 📡 Route Engine — API 拦截引擎

通过流式 DSL 统一管理网络层拦截：**Monitor（监控断言）**、**Mock（模拟响应）**、**Modify（修改请求）**、**Delay（高延迟模拟）**。

```java
// Monitor — 监控 API 并断言
RouteDsl.on(page)
    .api("/api/users/**")
    .monitor()
    .expectStatus(200)
    .expectJsonPath("$.data.count", 10)
    .done()
    .start();

// Mock — 拦截并返回自定义响应
RouteDsl.on(page)
    .api("/api/login")
    .mock()
    .mockBody("{\"token\":\"mock-token-123\"}")
    .mockStatus(200)
    .done()
    .start();

// Modify — 修改请求后继续发送
RouteDsl.on(page)
    .api("/api/submit")
    .modifyRequest()
    .setRequestHeader("X-Custom-Header", "test-value")
    .modifyRequestBody("amount", "999")
    .done()
    .start();

// Delay — 模拟高延迟网络
RouteDsl.on(page)
    .api("/api/**")
    .delay(3)                          // 所有 API 固定延迟 3 秒后放行
    .done()
    .start();

// Delay（随机延迟） — 模拟不稳定高延迟
RouteDsl.on(page)
    .api("/api/slow-endpoint")
    .delay(5)
    .randomDelay(1, 5)                 // 每次请求在 1-5 秒间随机延迟
    .matchMethod("POST")
    .done()
    .start();
```

**核心特性：**
- ✅ **Monitor（监控）**：放行请求 → 异步读取响应 → 状态码/JSONPath 断言 → 自动停止
- ✅ **Mock（模拟）**：拦截请求 → 直接返回自定义状态码+Body+Headers
- ✅ **Modify（修改）**：拦截请求 → JSONPath 精准替换请求体字段/增删请求头 → 继续发送
- ✅ **Delay（高延迟）**：拦截请求 → 延迟指定秒数后原样放行，支持固定和随机延迟，用于测试前端超时/loading/重试机制
- ✅ **条件匹配**：支持 ResourceType、HTTP Method、Headers、Query、Body Regex、Referrer、Origin 等多维度过滤
- ✅ **线程安全**：ConcurrentHashMap + AtomicLong + byte[] 拷贝跨线程，零阻塞 UI
- ✅ **内存安全**：WeakReference 防泄漏、双重上限防 OOM

👉 详细文档：[ROUTE_PACKAGE_README.md](./ROUTE_PACKAGE_README.md)

---

### 3. ♿ AxeCoreScanner — 无障碍测试

```java
// 自动 WCAG 合规性扫描
AxeCoreScanner scanner = new AxeCoreScanner(page);
AccessibilityResult result = scanner.scan();

// 内置 Listener 集成：每个 Scenario 结束后自动扫描
// 报告输出: target/accessibility-axe/
```

**配置（serenity.properties）：**
```properties
axe.scan.enabled=true
axe.scan.tags=wcag2a,wcag2aa
axe.scan.outputDir=target/accessibility-axe
```

---

### 4. 📊 SummaryReportGenerator — 摘要报告

自动生成精美的 HTML 摘要报告，包含：

- **测试统计面板**：通过率、失败数、跳过数（彩色进度条）
- **功能覆盖率**：按 Feature 分组的通过率可视化
- **失败分析**：高频错误分类饼图 + 最不稳定功能排名
- **完整测试列表**：每个 Scenario 的结果和错误详情
- **导出功能**：CSV 数据表 + ZIP 打包下载

**内置 12 类错误自动分类：**
Timeout Error / Element Not Found / Navigation Failed / Assertion Failed / NPE / Code Issue / Browser Closed / Environment Issue / API Issue / Auth Failed / Browser Launch Error / Data Validation Error

---

## 📁 项目结构

```
Automation_WebUI_Framework_BDD/
├── pom.xml                                  # Maven 构建配置
├── serenity.properties                      # 框架配置（超时、截图、视口等）
│
├── src/main/java/.../automation/
│   ├── framework/web/                       # ★ 核心 Web 测试框架
│   │   ├── page/                           # Page Object Model
│   │   │   ├── PageElement.java            # 元素操作（智能等待/轮询等待）
│   │   │   ├── PageElementList.java        # 元素列表
│   │   │   ├── base/BasePage.java          # 基础页面类
│   │   │   └── factory/PageObjectFactory.java
│   │   ├── route/                          # ★ Route Engine（API 拦截）
│   │   │   ├── core/                       # RouteEngine / RouteRegistry / RouteRule / ApiMonitorContext
│   │   │   ├── dsl/RouteDsl.java           # 流式 DSL 构建器
│   │   │   ├── handler/                    # MonitorHandler / MockHandler / ModifyHandler
│   │   │   └── util/                       # RouteAsyncPool / SerenityReporter / RouteUtil
│   │   ├── monitoring/                     # 旧版 API 监控（已由 Route Engine 替代）
│   │   ├── accessibility/                  # 无障碍测试
│   │   │   └── AxeCoreScanner.java
│   │   ├── screenshot/                     # 截图系统
│   │   ├── session/                        # Session 管理
│   │   ├── lifecycle/                      # Playwright 生命周期
│   │   │   ├── PlaywrightManager.java      # ★ Playwright/Browser/Context/Page 管理层
│   │   │   ├── PlaywrightContextManager.java
│   │   │   ├── PlaywrightConfigManager.java
│   │   │   └── PlaywrightInitializer.java
│   │   ├── config/                         # 配置中心
│   │   ├── listener/                       # 监听器
│   │   │   ├── PlaywrightListener.java     # Serenity 生命周期监听
│   │   │   ├── AxeCoreListener.java
│   │   │   └── ListenerRegistry.java
│   │   └── utils/                          # 工具类
│   │
│   ├── framework/api/                       # API 测试框架
│   │   ├── client/                         # REST 客户端
│   │   ├── core/                           # Endpoint / Entity / Step
│   │   └── assembler/                      # 请求组装
│   │
│   ├── report/                             # 报告生成
│   │   └── SummaryReportGenerator.java     # HTML/CSV/ZIP 摘要报告
│   └── retry/                              # 重试机制
│
├── src/test/java/.../tests/                 # 测试代码
│   ├── CucumberTestRunnerIT.java            # 测试运行入口
│   ├── steps/                              # BDD 步骤定义
│   ├── pages/                              # 业务页面对象
│   ├── glue/                               # Cucumber Glue
│   └── route/                              # Route API 测试步骤
│
├── src/test/resources/
│   └── features/                           # Cucumber Feature 文件
│       ├── web/                            # Web UI 测试
│       └── route/                          # Route Engine 测试
│
└── docs/
    ├── README.md                           # ★ 框架总览（本文件）
    ├── ROUTE_PACKAGE_README.md             # Route Engine 完整 API 文档
    ├── PLAYWRIGHT_VS_SELENIUM.md           # Playwright vs Selenium 对比
    └── Element.MD                          # 元素定位指南
```

---

## 🚀 快速开始

### 环境要求

| 组件 | 版本 |
|------|------|
| JDK | 21+ |
| Maven | 3.8+ |
| Node.js | 18+（Playwright 浏览器驱动） |
| 操作系统 | Windows / Linux / macOS |

### 运行测试

```bash
# 运行全部 Cucumber 测试
mvn clean verify

# 运行特定 Tag
mvn clean verify -Dtags="@login"

# 运行 Route Engine 测试
mvn clean verify -Dtags="@route-api"

# 跳过测试只打包
mvn package -DskipTests
```

### 编写一个新 Feature

本框架采用 **Feature → Glue → Steps → Page** 四层架构，每一层职责清晰：

```
Feature File (.feature)      → Cucumber Gherkin 描述业务场景
    ↓
Glue Layer (*Glue.java)      → @AutoBrowser + @Steps 注入，参数传递，无业务逻辑
    ↓
Steps Layer (*Steps.java)    → @Step 标记报告步骤，编排业务流程，Session 管理
    ↓
Page Layer (*Page.java)      → @Element 声明元素，继承 SerenityBasePage 获得全部操作能力
    ↓
SerenityBasePage / BasePage  → click/type/wait/navigateTo 等 50+ 操作 + 智能等待
    ↓
Playwright API               → Page / BrowserContext / Locator
```

**1. 创建 Feature 文件** (`src/test/resources/features/Login.feature`)：

```gherkin
Feature: User Login

  Scenario: Login with valid credentials
    Given user navigates to login page
    When user enters username "testuser" and password "Password123"
    And clicks the login button
    Then the welcome message "Welcome, Test User" should be displayed
```

**2. 创建 Page Object** (`src/test/java/.../pages/LoginPage.java`)：

```java
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.base.impl.SerenityBasePage;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.PageElement;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.annotations.Element;

public class LoginPage extends SerenityBasePage {
    @Element("#username")
    public PageElement usernameInput;

    @Element("#password")
    public PageElement passwordInput;

    @Element("#login-btn")
    public PageElement loginButton;

    @Element(".welcome-message")
    public PageElement welcomeMessage;
}
```

**3. 创建 Glue 层** (`src/test/java/.../glue/LoginGlue.java`)：

```java
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.AutoBrowser;
import net.serenitybdd.annotations.Steps;

@AutoBrowser(verbose = true)
public class LoginGlue {
    @Steps
    private LoginSteps loginSteps;

    @Given("user navigates to login page")
    public void navigateToLogin() {
        loginSteps.navigateToLogin();
    }

    @When("user enters username {string} and password {string}")
    public void enterCredentials(String username, String password) {
        loginSteps.enterCredentials(username, password);
    }

    @When("clicks the login button")
    public void clickLogin() {
        loginSteps.clickLogin();
    }

    @Then("the welcome message {string} should be displayed")
    public void verifyWelcomeMessage(String expectedMessage) {
        loginSteps.verifyWelcomeMessage(expectedMessage);
    }
}
```

**4. 实现 Steps 层** (`src/test/java/.../steps/LoginSteps.java`)：

```java
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.factory.PageObjectFactory;
import net.serenitybdd.annotations.Step;

public class LoginSteps {
    private LoginPage loginPage = PageObjectFactory.getPage(LoginPage.class);

    @Step
    public void navigateToLogin() {
        loginPage.navigateTo("https://example.com/login");
    }

    @Step
    public void enterCredentials(String username, String password) {
        loginPage.usernameInput.type(username);
        loginPage.passwordInput.type(password);
    }

    @Step
    public void clickLogin() {
        loginPage.loginButton.click();
    }

    @Step
    public void verifyWelcomeMessage(String expectedMessage) {
        loginPage.welcomeMessage.shouldContainText(expectedMessage);
    }
}
```

**各层职责对比：**

| 层 | 类 | 注解 | 职责 |
|---|----|------|------|
| **Feature** | `.feature` 文件 | Gherkin | 用自然语言描述业务场景 |
| **Glue** | `*Glue.java` | `@AutoBrowser` `@Steps` | 桥接 Cucumber 与 Steps，参数传递 |
| **Steps** | `*Steps.java` | `@Step` | 编排业务逻辑，管理 Session，调用 PageObjectFactory |
| **Page** | `*Page.java` | `@Element` | 声明页面元素，继承 SerenityBasePage 获得操作能力 |

---

## ⚙️ 关键配置项 (serenity.properties)

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `playwright.element.wait.timeout` | 15000ms | 元素等待超时 |
| `playwright.page.timeout` | 30000ms | 页面操作超时 |
| `playwright.polling.interval` | 500ms | 轮询间隔 |
| `playwright.context.viewport.width/height` | 1366x768 | 浏览器视口大小 |
| `serenity.screenshot.strategy` | AFTER_EACH_STEP | 截图策略 |
| `serenity.playwright.restart.browser.for.each` | scenario | 浏览器重启粒度（scenario/feature） |
| `axe.scan.enabled` | true | 无障碍扫描开关 |
| `serenity.logging` | VERBOSE | 日志级别 |
| `framework.verbose.logging` | true | 框架详细日志开关 |
| `playwright.page.load.state` | DOMCONTENTLOADED | 页面加载等待状态 |

---

## 📚 文档索引

| 文档 | 内容 |
|------|------|
| **[README.md](./README.md)** | 框架总览与快速开始 |
| [ROUTE_PACKAGE_README.md](./ROUTE_PACKAGE_README.md) | Route Engine 完整 API 文档（Monitor/Mock/Modify） |
| [PLAYWRIGHT_VS_SELENIUM.md](./PLAYWRIGHT_VS_SELENIUM.md) | Playwright vs Selenium 技术选型对比 |
| [Element.MD](./Element.MD) | 元素定位指南 |

---

## 🔧 技术亮点

1. **Playwright 替代 Selenium** — 更快的执行速度、更可靠的元素定位、自动等待机制
2. **三层架构清晰** — Playwright → Browser → BrowserContext → Page，分层管理，职责明确
3. **双等待策略** — 智能等待（原生 waitFor）+ 轮询等待（属性检查），覆盖所有场景
4. **Route Engine 统一网络拦截** — Monitor/Mock/Modify/Delay 四种模式，流式 DSL，零阻塞 UI
5. **企业级报告体系** — Serenity 详细报告 + 自定义摘要报告（HTML/CSV/ZIP），12 类错误自动分析
6. **CI/CD 原生集成** — Jenkins Pipeline、环境变量自动解析
7. **无障碍合规** — 内置 axe-core WCAG 自动扫描
8. **Session 复用** — 登录态跨 Scenario 自动复用（两种模式均支持），通过 storageState 持久化 + SessionManager 两层缓存，减少冗余登录
9. **线程安全 + 内存安全** — ConcurrentHashMap + WeakReference 防泄漏 + 双重上限防 OOM
