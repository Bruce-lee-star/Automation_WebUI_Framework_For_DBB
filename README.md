# Automation WebUI Framework BDD

> **企业级 Web UI 自动化测试框架** — 基于 Playwright + Serenity BDD + Cucumber，构建的高效、稳定、可扩展的自动化测试解决方案。

---

## 📋 框架概览

| 属性 | 说明 |
|------|------|
| **技术栈** | Java 21 + Playwright 1.58 + Serenity BDD 4.3.4 + Cucumber 7.14 |
| **架构模式** | BDD 行为驱动开发（Cucumber Gherkin）+ Page Object Model + Screenplay Pattern |
| **构建工具** | Maven |
| **CI/CD 集成** | Jenkins（Pipeline / Freestyle） |
| **云测试** | BrowserStack 支持 |

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
│  ┌──────────┐ ┌──────────┐ ┌───────────┐ ┌──────────────┐  │
│  │ API 监控  │ │ Mock 管理 │ │ 快照测试  │ │ 无障碍扫描   │  │
│  │ Monitor   │ │ Mock     │ │ Snapshot  │ │ AxeCore      │  │
│  └──────────┘ └──────────┘ └───────────┘ └──────────────┘  │
│  ┌──────────┐ ┌──────────┐ ┌───────────┐ ┌──────────────┐  │
│  │ 请求修改  │ │ Session  │ │ 截图管理  │ │ 配置中心     │  │
│  │ Modifier  │ │ Manager  │ │ Screenshot│ │ Config       │  │
│  └──────────┘ └──────────┘ └───────────┘ └──────────────┘  │
├─────────────────────────────────────────────────────────────┤
│                     基础设施层 (Infrastructure)                │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────────┐   │
│  │ Playwright    │  │ BrowserStack  │  │ Jenkins Pipeline │   │
│  │ Lifecycle     │  │ Cloud         │  │ Integration      │   │
│  └──────────────┘  └──────────────┘  └──────────────────┘   │
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
| `PageElement` | 页面元素包装类（696 行），支持所有元素操作、智能等待和链式调用 |
| `PageElementList` | 元素列表操作，动态查询 + 多阶段智能等待 |
| `Element` | `@Element` 注解，标记 Page 字段选择器，支持自动初始化 |
| `BasePage` | 基础页面类，封装 10+ 种元素定位方法和所有 Playwright 核心操作 |
| `SerenityBasePage` | Serenity 集成的页面实现 |
| `PageObjectFactory` | 页面对象工厂，支持单例/原型/线程隔离等生命周期策略 |

---

### 2. 📡 RealApiMonitor - API 监控（非阻塞 + 自动停止）

```java
// 启动监控 → 执行操作 → 自动停止（无需手动干预！）
RealApiMonitor.monitor(context)
    .api("/api/login", 200)       // 目标API + 期望状态码
    .api("/api/userInfo", 200)
    .timeout(60)                   // 兜底超时
    .start();                      // 异步启动

loginButton.click();
// ✅ 所有目标API捕获后自动停止，结果记录到 Serenity 报告

// 获取捕获的数据
ApiCallRecord record = RealApiMonitor.getLast("/api/login");
String responseBody = RealApiMonitor.getLastBody("/api/userInfo");
```

**核心特性：**
- ✅ **非阻塞异步**：不干扰测试流程
- ✅ **自动停止**：目标 API 全部捕获后立即停止（推荐方式）
- ✅ **超时保护**：无新 API 时超时自动停止（兜底）
- ✅ **实时验证**：支持对期望状态码进行实时校验
- ✅ **Host 过滤**：可限制只监控指定 Host 的 API
- ✅ **Serenity 集成**：自动记录到测试报告

👉 详细文档：[API_MONITOR_README.md](./API_MONITOR_README.md)

---

### 3. 🔧 ApiRequestModifier - 请求修改器

```java
// 修改请求参数、Headers、Body、URL...
ApiRequestModifier modification = ApiRequestModifier.create()
    .host("test-api.example.com")           // 切换环境
    .modifyBodyField("userId", "123")        // 修改 Body 字段
    .modifyHeader("Authorization", "Bearer xxx")
    .modifyQueryParam("debug", "false");

ApiRequestModifier.modifyRequest(context, "/api/users", modification);
```

**核心特性：**
- 完全控制 HTTP 请求（Body / Headers / QueryParams / Method / URL / Host）
- 支持字段级 JSON 修改（嵌套路径如 `items[0].id`）
- 支持回调获取请求/响应信息

---

### 4. 🎭 ApiMonitorAndMockManager - Mock 管理

```java
// Mock API 响应
MockBuilder mock = MockBuilder.create()
    .url("/api/auth/login")
    .method("POST")
    .status(200)
    .body("{\"token\":\"mock-jwt\"}");

ApiMonitorAndMockManager.mock(context, mock);

// 动态 Mock（根据请求生成响应）
MockBuilder dynamicMock = MockBuilder.create()
    .url("/api/users/(.*)")
    .method("GET")
    .dynamicResponse(req -> "...");
```

---

### 5. 📸 PlaywrightSnapshotSupport - 原生快照测试

```java
// 视觉快照（像素级对比）
PlaywrightSnapshotSupport.of(page)
    .visual()
    .baselineName("login-page")
    .updateBaseline(true)    // 创建/更新基线
    .snapshot();

// ARIA 快照（跨平台结构验证，推荐）
PlaywrightSnapshotSupport.of(page.locator("main"))
    .aria()
    .baselineName("main-aria")
    .snapshot();

// 测试套件结束自动生成报告（NativeSnapshotTestListener）
```

**核心特性：**
- **视觉快照**：Playwright 原生 `hasScreenshot()` API，像素级图像对比
- **ARIA 快照**：Playwright 原生 `matchesAriaSnapshot()` API，可访问性树结构对比（一套基线跨 Windows/Mac/Linux）
- 基线管理（创建 / 更新 / 对比），基线存储在 `src/test/resources/snapshots/native/`（纳入 Git 版本控制）
- **自动报告**：`NativeSnapshotTestListener` 自动在测试套件结束时生成 HTML 报告

👉 详细文档：[SNAPSHOT_TESTING_GUIDE.md](./SNAPSHOT_TESTING_GUIDE.md)

---

### 6. ♿ AxeCoreScanner - 无障碍测试

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

### 7. 📊 SummaryReportGenerator - 摘要报告

自动生成精美的 HTML 摘要报告，包含：

- **测试统计面板**：通过率、失败数、跳过数（彩色进度条）
- **功能覆盖率**：按 Feature 分组的通过率可视化
- **失败分析**：高频错误分类饼图 + 最不稳定功能排名
- **完整测试列表**：每个 Scenario 的结果和错误详情
- **导出功能**：CSV 数据表 + ZIP 打包下载

**内置 12 类错误自动分类：**
Timeout Error / Element Not Found / Navigation Failed / Assertion Failed / NPE / Code Issue / Browser Closed / Environment Issue / API Issue / Auth Failed / Browser Launch Error / Data Validation Error

**Jenkins 自动集成：**
```properties
serenity.report.url=${JENKINS_URL}job/${JOB_NAME}/${BUILD_NUMBER}/Serenity_20Summary_20Report/
```

---

### 8. ☁️ BrowserStack 云浏览器

```java
// 无需修改测试代码！设置 browserstack.enabled=true 后自动连接
// BrowserStackManager 通过 CDP (Chrome DevTools Protocol) 远程连接
// 框架自动管理会话生命周期和测试状态标记

// 可选：手动获取会话信息
String sessionId = BrowserStackManager.getCurrentSessionId();
String dashboardUrl = BrowserStackManager.getCurrentSessionUrl();

// 可选：手动标记测试状态
BrowserStackManager.setTestStatus("passed", "All steps completed");
```

支持：
- **跨浏览器 / 跨平台**：Chrome、Firefox、Safari、Edge + Windows、macOS、iOS、Android
- **CDP 远程连接**：通过 Chrome DevTools Protocol 连接 BrowserStack 云端
- **自动录制**：视频 / 截图 / 网络日志 / 控制台日志
- **Local Testing**：支持内网应用测试（加密隧道）
- **并行测试**：多会话并发执行
- **凭证脱敏**：日志自动隐藏 AccessKey

👉 详细文档：[BROWSERSTACK_INTEGRATION.md](./BROWSERSTACK_INTEGRATION.md)

---

## 📁 项目结构

```
Automation_WebUI_Framework_BDD/
├── pom.xml                              # Maven 构建配置
├── serenity.properties                  # 框架配置（超时、截图、视口等）
├── Jenkinsfile                          # Jenkins Pipeline 定义
│
├── src/main/java/.../automation/
│   ├── framework/web/                   # ★ 核心 Web 测试框架
│   │   ├── page/                       # Page Object Model
│   │   │   ├── PageElement.java        # 元素操作（智能等待/轮询等待）
│   │   │   ├── PageElementList.java    # 元素列表
│   │   │   ├── base/BasePage.java      # 基础页面类
│   │   │   └── factory/PageObjectFactory.java
│   │   ├── monitoring/                 # API 监控 & Mock
│   │   │   ├── RealApiMonitor.java     # 非阻塞 API 监控
│   │   │   ├── ApiRequestModifier.java # 请求修改器
│   │   │   └── ApiMonitorAndMockManager.java
│   │   ├── snapshot/                   # Playwright 原生快照测试
│   │   │   ├── PlaywrightSnapshotSupport.java  # 视觉快照 + ARIA 快照
│   │   │   ├── NativeSnapshotResult.java       # 快照结果
│   │   │   └── NativeSnapshotReportGenerator.java  # HTML 报告生成器
│   │   ├── accessibility/              # 无障碍测试
│   │   │   └── AxeCoreScanner.java
│   │   ├── cloud/                      # 云浏览器
│   │   │   └── BrowserStackManager.java
│   │   ├── screenshot/                 # 截图系统
│   │   ├── session/                    # Session 管理
│   │   ├── lifecycle/                  # Playwright 生命周期
│   │   ├── config/                     # 配置中心
│   │   ├── listener/                   # 监听器
│   │   └── utils/                      # 工具类
│   │
│   ├── framework/api/                   # API 测试框架
│   │   ├── client/                     # REST 客户端
│   │   ├── core/                       # Endpoint / Entity / Step
│   │   └── assembler/                  # 请求组装
│   │
│   ├── report/                         # 报告生成
│   │   └── SummaryReportGenerator.java # HTML/CSV/ZIP 摘要报告
│   └── retry/                          # 重试机制
│
├── src/test/java/.../tests/             # 测试代码
│   ├── CucumberTestRunnerIT.java        # 测试运行入口
│   ├── steps/                          # BDD 步骤定义
│   ├── pages/                          # 业务页面对象
│   ├── glue/                           # Cucumber Glue
│   ├── hooks/                          # Before/After Hook
│   └── api/steps/                      # API 测试步骤
│
├── src/test/resources/
│   └── features/                       # Cucumber Feature 文件
│
└── docs/                               # 文档
    ├── README.md                       # ★ 框架总览（本文件）
    ├── API_MONITOR_README.md           # API 监控文档
    ├── SNAPSHOT_TESTING_GUIDE.md       # 快照测试指南
    ├── BROWSERSTACK_INTEGRATION.md     # BrowserStack 集成
    ├── JENKINS_PIPELINE_GUIDE.md       # Jenkins 指南
    ├── EMAIL_CONFIGURATION.md          # 邮件通知配置
    ├── JENKINS_ENV_CONFIG.md           # Jenkins 环境配置
    ├── ENTERPRISE_NETWORK_CONFIG.md    # 企业网络配置
    ├── Serenity升级方案.md             # Serenity 升级方案
    └── JDK21_升级方案.md               # JDK 21 升级指南
```

---

## 🚀 快速开始

### 环境要求

| 组件 | 版本 |
|------|------|
| JDK | 21+ |
| Maven | 3.8+ |
| Node.js | 18+（Playwright 浏览器） |
| 操作系统 | Windows / Linux / macOS |

### 运行测试

```bash
# 运行全部 Cucumber 测试
mvn clean verify

# 运行特定 Feature
mvn clean verify -Dtags="@login"

# 运行并生成报告（自动触发）
mvn clean verify -Dserenity.report.url=http://your-server/reports/

# 只运行单元测试
mvn test

# 跳过测试只打包
mvn package -DskipTests
```

### 编写一个新 Feature

**1. 创建 Feature 文件** (`src/test/resources/features/Login.feature`)：
```gherkin
Feature: 用户登录功能

  Scenario: 使用有效凭证成功登录
    Given 用户导航到登录页面
    When 用户输入用户名 "testuser" 和密码 "Password123"
    And 点击登录按钮
    Then 页面应显示欢迎消息 "Welcome, Test User"
    And 登录接口 "/api/auth/login" 应返回状态码 200
```

**2. 创建 Page Object** (`src/test/java/.../pages/LoginPage.java`)：
```java
public class LoginPage extends BasePage {
    public static final PageElement USERNAME_INPUT = new PageElement("#username");
    public static final PageElement PASSWORD_INPUT = new PageElement("#password");
    public static final PageElement LOGIN_BUTTON = new PageElement("#login-btn");
    public static final PageElement WELCOME_MSG = new PageElement(".welcome-message");

    // 可选：自定义业务方法
    public void login(String username, String password) {
        USERNAME_INPUT.type(username);
        PASSWORD_INPUT.type(password);
        LOGIN_BUTTON.click();
    }
}
```

**3. 实现 Step Definitions**：
```java
public class LoginSteps {
    @Given("用户导航到登录页面")
    public void navigateToLogin() { loginPage.navigateTo(loginUrl); }

    @When("用户输入用户名 {string} 和密码 {string}")
    public void enterCredentials(String user, String pass) {
        loginPage.USERNAME_INPUT.type(user);
        loginPage.PASSWORD_INPUT.type(pass);
    }
}
```

---

## ⚙️ 关键配置项 (serenity.properties)

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `playwright.element.wait.timeout` | 15000ms | 元素等待超时 |
| `playwright.page.timeout` | 30000ms | 页面操作超时 |
| `playwright.polling.interval` | 500ms | 轮询间隔 |
| `playwright.context.viewport` | 1366x768 | 浏览器视口大小 |
| `serenity.screenshot.strategy` | AFTER_EACH_STEP | 截图策略 |
| `serenity.playwright.restart.browser.for.each` | scenario | 浏览器重启粒度 |
| `axe.scan.enabled` | true | 无障碍扫描开关 |
| `serenity.logging` | VERBOSE | 日志级别 |

---

## 📚 文档索引

| 文档 | 内容 |
|------|------|
| **[README.md](./README.md)** | 框架总览与快速开始 |
| [API_MONITOR_README.md](./API_MONITOR_README.md) | API 监控、请求修改、Mock 管理完整指南 |
| [SNAPSHOT_TESTING_GUIDE.md](./SNAPSHOT_TESTING_GUIDE.md) | 快照测试（视觉回归）完整使用指南 |
| [BROWSERSTACK_INTEGRATION.md](./BROWSERSTACK_INTEGRATION.md) | BrowserStack 云浏览器集成配置 |
| [EMAIL_CONFIGURATION.md](./EMAIL_CONFIGURATION.md) | 测试报告邮件通知配置 |
| [JENKINS_PIPELINE_GUIDE.md](./JENKINS_PIPELINE_GUIDE.md) | Jenkins Pipeline 配置指南 |
| [JENKINS_ENV_CONFIG.md](./JENKINS_ENV_CONFIG.md) | Jenkins 环境变量配置说明 |
| [ENTERPRISE_NETWORK_CONFIG.md](./ENTERPRISE_NETWORK_CONFIG.md) | 企业网络代理配置 |
| [Serenity升级方案.md](./Serenity升级方案.md) | Serenity 版本升级方案 |
| [JDK21_升级方案.md](./JDK21_升级方案.md) | JDK 21 升级迁移指南 |

---

## 🔧 技术亮点

1. **Java 21 全新语法支持** — Record、Pattern Matching、Switch Expression、Sealed Classes、Virtual Threads
2. **Playwright 替代 Selenium** — 更快的执行速度、更可靠的元素定位、自动等待机制
3. **双等待策略** — 智能等待（原生 waitFor）+ 轮询等待（属性检查），覆盖所有场景
4. **非阻塞 API 监控** — 异步监听 + 自动停止，零侵入测试流程
5. **企业级报告体系** — Serenity 详细报告 + 自定义摘要报告（HTML/CSV/ZIP），12 类错误自动分析
6. **CI/CD 原生集成** — Jenkins Pipeline、环境变量自动解析、相对/绝对路径自适应
7. **无障碍合规** — 内置 axe-core WCAG 2.0 AA 标准自动化扫描
8. **视觉回归** — Playwright 原生快照测试，支持视觉快照（像素级）+ ARIA 快照（跨平台结构验证）
9. **Session 复用** — 登录态跨 Scenario 复用，减少冗余登录操作
10. **云测试就绪** — BrowserStack 多浏览器/多设备并行测试

---


