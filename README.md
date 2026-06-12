# Automation WebUI Framework For DBB

> **企业级 Web UI + API 自动化测试框架** — 基于 Playwright + Serenity BDD + Cucumber，构建的高效、稳定、可扩展的自动化测试解决方案。

---

## 框架概览

| 属性 | 说明 |
|------|------|
| **技术栈** | Java 21 + Playwright 1.58.0 + Serenity BDD 4.3.4 + Cucumber 7.14.0 |
| **架构模式** | BDD 行为驱动开发（Cucumber Gherkin）+ Page Object Model |
| **构建工具** | Maven（surefire 3.2.5 / failsafe 3.2.5 / serenity-maven-plugin 4.3.4） |
| **测试框架** | JUnit 4.13.2 + Serenity JUnit 集成 |
| **浏览器驱动** | Playwright（Chromium / Firefox / WebKit） |
| **云测试** | BrowserStack CDP 云浏览器 |
| **报告** | Serenity HTML Report + SummaryReportGenerator（HTML / CSV / ZIP） |
| **配置管理** | typesafe.config 1.4.2（HOCON 格式） |
| **依赖注入** | Spring Context 6.1.6 |
| **日志** | SLF4J + Logback Classic 1.5.6 |

### 环境准备

| 工具 | 版本 | 安装/配置说明 | 用途 |
|------|------|--------------|------|
| **JDK** | 21+ | 1. 下载 [OpenJDK 21](https://adoptium.net/download/) 或 [Oracle JDK 21](https://www.oracle.com/java/technologies/downloads/#java21)<br>2. 安装后设置环境变量 `JAVA_HOME=<安装路径>`<br>3. 将 `%JAVA_HOME%\bin` 加入 `PATH`<br>4. 验证：`java -version` | Java 21 编译运行环境，框架使用虚拟线程、Record 类等新特性 |
| **Maven** | 3.9+ | 1. 下载 [Maven](https://maven.apache.org/download.cgi)<br>2. 解压后设置 `MAVEN_HOME=<解压路径>`<br>3. 将 `%MAVEN_HOME%\bin` 加入 `PATH`<br>4. 验证：`mvn -version` | 项目构建、依赖管理、测试执行（surefire/failsafe/serenity-maven-plugin） |
| **Git** | 2.40+ | 1. 下载 [Git for Windows](https://git-scm.com/download/win) 或 `brew install git` (macOS)<br>2. 配置用户信息：`git config --global user.name "你的姓名"`<br>`git config --global user.email "你的邮箱"`<br>3. 验证：`git --version` | 版本控制、分支管理、协作开发 |
| **GitHub** | — | 1. 在 [GitHub](https://github.com) 注册账号<br>2. 配置 SSH Key：`ssh-keygen -t rsa -b 4096 -C "你的邮箱"`<br>→ 将公钥 `~/.ssh/id_rsa.pub` 添加到 GitHub Settings → SSH and GPG keys<br>3. 验证：`ssh -T git@github.com` | 代码托管、Pull Request、CI/CD 集成（GitHub Actions / Jenkins） |
| **Nexus** | — | 1. 联系团队管理员申请 Nexus 账号（获取用户名/密码）<br>2. 在 `~/.m2/settings.xml` 中配置 Nexus 仓库认证：<br>```xml<br><server><br>  <id>nexus-releases</id><br>  <username>your-username</username><br>  <password>your-password</password><br></server><br>```<br>3. 验证：`mvn compile`（看私有仓库依赖是否正常下载） | 私有 Maven 仓库，托管内部依赖和插件（如 HSBC 内部 jar） |

---

## Playwright 核心概念

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

| 层级 | 职责 | 类比 | 管理方式 |
|------|------|------|---------|
| **Playwright** | 管理浏览器驱动，提供 `chromium()`/`firefox()`/`webkit()` 工厂方法 | 浏览器引擎管理器 | `PlaywrightManager` 静态 Map 管理，同一配置复用 |
| **Browser** | 一个浏览器进程实例，可包含多个 Context | 浏览器应用程序 | `browserInstances` Map，按 configId 隔离 |
| **BrowserContext** | 独立的浏览器会话，Cookie/LocalStorage/登录态完全隔离 | 隐身窗口 | `contextThreadLocal` ThreadLocal；Context 实例复用 + 状态深度清理 |
| **Page** | 一个标签页，所有页面操作的载体 | 浏览器标签页 | `pageThreadLocal` ThreadLocal，每个线程一个 Page |

**关键设计：**
- **Browser 复用 + Context 隔离**：频繁创建/销毁 Browser 开销大，框架采用 Browser 共享、Context 隔离的策略。`scenario` 模式通过 `cleanupContextState()` 深度清理（清除 Cookies/Permissions）后复用同一 Context 实例，**整个测试生命周期只保持 1 个浏览器窗口**。
- **Context 复用机制**：两种重启策略（`scenario` / `feature`）均复用 Context 实例，避免 `browser.newContext()` 弹出多个 Chrome 窗口；隔离性通过状态清理保证。
- **Session 跨 Scenario 复用**：通过 `SessionManager` 将 Cookie/LocalStorage 持久化到 `target/.sessions/` 目录。**缓存命中**时 `setStorageStatePath()` 预加载 Cookie，下一个 Scenario 跳过登录直达 homeUrl；**缓存未命中**时走完整登录流程，登录后自动 `saveSession()` 持久化，后续 Scenario 即可命中缓存。

---

## 架构设计

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
│  │  Element → PageElement / PageElementList → BasePage │    │
│  │  → SerenityBasePage → YourPage                      │    │
│  └─────────────────────────────────────────────────────┘    │
│  ┌──────────────┐ ┌──────────────┐ ┌──────────────────┐    │
│  │ Route Engine │ │ Session 管理  │ │ 无障碍扫描        │    │
│  │ (Monitor/    │ │ SessionMgr   │ │ AxeCore          │    │
│  │  Mock/Modify/│ │              │ │                  │    │
│  │  Delay)      │ │              │ │                  │    │
│  └──────────────┘ └──────────────┘ └──────────────────┘    │
│  ┌──────────────┐ ┌──────────────┐ ┌──────────────────┐    │
│  │ 原生快照测试  │ │ 截图管理      │ │ 监听器体系        │    │
│  │ Snapshot     │ │ Screenshot   │ │ Listener         │    │
│  └──────────────┘ └──────────────┘ └──────────────────┘    │
│  ┌──────────────┐ ┌──────────────┐ ┌──────────────────┐    │
│  │ 云测试支持    │ │ AutoBrowser  │ │ 配置中心          │    │
│  │ BrowserStack │ │ 注解驱动     │ │ FrameworkConfig  │    │
│  └──────────────┘ └──────────────┘ └──────────────────┘    │
├─────────────────────────────────────────────────────────────┤
│                     基础设施层 (Infrastructure)                │
│  ┌──────────────────────────────────────────────────────┐   │
│  │  FrameworkCore / PlaywrightManager                    │   │
│  │  Playwright / Browser / BrowserContext / Page 生命周期  │   │
│  │  JVM Shutdown Hook 资源清理                            │   │
│  └──────────────────────────────────────────────────────┘   │
├─────────────────────────────────────────────────────────────┤
│                       报告层 (Reporting)                      │
│  ┌──────────────────────────┐  ┌──────────────────────────┐  │
│  │ Serenity HTML Report     │  │ Summary Report           │  │
│  │ (详细步骤级)              │  │ (HTML/CSV/ZIP 邮件摘要)   │  │
│  └──────────────────────────┘  └──────────────────────────┘  │
└─────────────────────────────────────────────────────────────┘
```

---

## 项目结构

```
Automation_WebUI_Framework_For_DBB/
├── pom.xml                              # Maven 构建配置（全部依赖与插件）
├── serenity.properties                  # 框架运行时配置（超时、截图、视口、浏览器等）
├── browserstack.conf                    # BrowserStack 云测试配置示例
│
├── src/main/java/.../automation/
│   ├── framework/web/
│   │   ├── page/                        # ★ Page Object Model 核心（7 个类）
│   │   │   ├── PageElement.java         # 元素操作（企业级重试 + 失败诊断）
│   │   │   ├── PageElementList.java     # 元素列表（动态查询 + 多阶段等待）
│   │   │   ├── Element.java             # @Element 注解（仅 CSS / XPath）
│   │   │   ├── ElementDiagnosticsCollector.java  # 失败诊断收集器（批量 JS 单次 IPC）
│   │   │   ├── base/BasePage.java       # 基础页面类（99+ Playwright 操作 + element() 统一门面）
│   │   │   ├── base/impl/SerenityBasePage.java  # Serenity 集成（双拦截器 + element() 覆盖统一报告注入）
│   │   │   └── factory/PageObjectFactory.java   # 页面对象工厂（单例/原型/线程隔离）
│   │   ├── route/                       # ★ Route Engine（17 个类）
│   │   │   ├── core/RouteEngine.java    # 路由引擎
│   │   │   ├── core/RouteRegistry.java  # WeakReference 隔离注册表
│   │   │   ├── core/RouteRule.java      # 路由规则模型 + 校验
│   │   │   ├── core/RouteHandleType.java # Monitor/Mock/Modify/Delay 枚举
│   │   │   ├── core/RouteHandler.java   # Handler 函数式接口
│   │   │   ├── core/ApiCaptureContext.java  # ThreadLocal 捕获上下文
│   │   │   ├── core/CapturedApiCall.java    # API 调用快照
│   │   │   ├── core/RouteMonitor.java       # 监控门面
│   │   │   ├── core/RouteException.java     # 三层异常体系
│   │   │   ├── dsl/RouteDsl.java        # 流式 DSL（外部唯一入口）
│   │   │   ├── handler/MonitorHandler.java  # 监控处理器
│   │   │   ├── handler/MockHandler.java     # 模拟处理器（含 mockReplaceField 批量替换）
│   │   │   ├── handler/ModifyHandler.java   # 修改处理器
│   │   │   ├── handler/DelayHandler.java    # 延迟处理器
│   │   │   └── util/                    # RouteAsyncPool / SerenityReporter / RouteUtil
│   │   ├── lifecycle/                   # ★ Playwright 生命周期管理（6 个类）
│   │   │   ├── PlaywrightManager.java   # Playwright/Browser/Context/Page 管理层
│   │   │   ├── PlaywrightContextManager.java  # Context 创建与 Page 稳定化
│   │   │   ├── PlaywrightConfigManager.java   # 浏览器类型判断与配置
│   │   │   ├── PlaywrightInitializer.java     # Playwright 实例初始化
│   │   │   ├── CustomOptionsManager.java      # 自定义 Context 选项（链式 API）
│   │   │   └── ContextLifecycleHookManager.java # Context 生命周期钩子
│   │   ├── config/                     # 配置中心
│   │   │   ├── FrameworkConfig.java    # 枚举式集中配置（含全部默认值和说明）
│   │   │   ├── AutoBrowserProcessor.java  # @AutoBrowser 注解处理器
│   │   │   └── BrowserOverrideManager.java # 浏览器覆盖管理器
│   │   ├── listener/                   # 监听器体系
│   │   │   ├── PlaywrightListener.java # Serenity 生命周期监听
│   │   │   ├── AxeCoreListener.java    # 无障碍扫描监听
│   │   │   ├── ListenerRegistry.java   # 监听器注册
│   │   │   └── ThucydidesStepsListenerAdapter.java  # SPI 自动注册适配器
│   │   ├── accessibility/AxeCoreScanner.java  # WCAG 无障碍扫描
│   │   ├── screenshot/ScreenshotStrategy.java # 截图策略
│   │   ├── snapshot/                   # 原生快照测试
│   │   │   └── PlaywrightSnapshotSupport.java # 视觉/ARIA 快照
│   │   ├── cloud/BrowserStackManager.java  # BrowserStack CDP 云测试
│   │   ├── session/SessionManager.java     # Cookie/LocalStorage 跨场景复用
│   │   ├── core/                       # 框架核心
│   │   │   ├── FrameworkCore.java     # 初始化/运行/停止/清理 + JVM Shutdown Hook
│   │   │   └── FrameworkState.java    # 框架状态管理
│   │   ├── annotations/AutoBrowser.java  # 浏览器自动选择注解
│   │   ├── enums/BrowserSwitchStrategy.java  # 浏览器切换策略枚举
│   │   ├── exceptions/                # 10 层异常体系
│   │   │   ├── BrowserException / ConfigurationException / ElementException
│   │   │   ├── ElementNotFoundException / ElementOperationException
│   │   │   ├── FrameworkException / InitializationException
│   │   │   ├── NavigationException / ScreenshotException / TimeoutException
│   │   └── utils/                     # 工具类
│   │       ├── TextNormalizer.java     # 统一文本标准化管道
│   │       ├── LoggingConfigUtil.java  # 受 verbose 开关控制的日志工具
│   │       ├── DatabaseUtil.java       # 数据库工具
│   │       └── FileUtils.java          # 文件工具
│   │
│   ├── framework/api/                   # API 测试框架（基于 Rest Assured，26 个类）
│   │   ├── client/                     # REST 客户端（GET/POST/PUT/PATCH/DELETE）
│   │   ├── core/                       # Endpoint / Entity / Step / Services
│   │   └── assembler/                  # 请求组装
│   │
│   └── report/SummaryReportGenerator.java  # HTML/CSV/ZIP 摘要报告
│
├── route-demo-service/                   # Route Engine 演示服务
│   ├── pom.xml                          # Spring Boot 2.7.18
│   └── src/main/java/com/example/demo/
│       ├── DemoApplication.java
│       ├── controller/
│       │   ├── AuthController.java      # POST /demo/api/auth/login
│       │   ├── SlowController.java      # GET /demo/api/slow/*
│       │   └── UserController.java      # CRUD /demo/api/users/**
│       └── model/                       # User / Order / LoginRequest / LoginResponse
│
├── src/test/java/.../tests/
│   ├── CucumberTestRunnerIT.java         # Maven Failsafe 测试运行入口
│   ├── steps/                            # BDD 步骤定义（@Step 编排业务逻辑）
│   ├── pages/                            # 业务页面对象（@Element 声明元素）
│   ├── glue/                             # Cucumber Glue 桥接层（@AutoBrowser 注解）
│   ├── route/                            # Route Engine 测试步骤
│   └── api/steps/                        # API 测试 BDD 步骤定义
│
├── src/test/resources/
│   ├── features/
│   │   ├── web/                          # Web UI 测试 Feature 文件
│   │   └── route/                        # Route Engine 测试 Feature 文件
│   ├── config/application.conf           # API 框架配置
│   └── payload/                          # API 测试 Payload 模板
│
└── 文档/
    ├── README.md                         # ★ 框架总览（本文件）
    ├── ROUTE_PACKAGE_README.md           # Route Engine 完整 API 文档
    ├── API_README.md                     # API 测试框架文档（Rest Assured）
    ├── PLAYWRIGHT_VS_SELENIUM.md         # Playwright vs Selenium 技术选型
    ├── PLAYWRIGHT_LISTENERS.md           # Playwright 监听器全景指南
    └── Element.MD                        # 元素定位方法手册
```

---

## 核心能力

### 1. 智能 Page Object Model

```java
// 声明式元素 — @Element 注解（仅支持 CSS / XPath）
public class LoginPage extends SerenityBasePage {
    @Element("#username")       public PageElement usernameInput;
    @Element("#password")       public PageElement passwordInput;
    @Element("#login-btn")      public PageElement loginButton;
    @Element(".welcome-msg")    public PageElement welcomeMessage;
    @Element(".item-row")       public PageElementList itemRows;
}

// 链式调用 + 智能等待（自动处理元素状态）
loginPage.usernameInput.type("user").waitForVisible(10).click();

// element() 统一门面 — 内联选择器，无需声明字段
loginPage.element("#login-btn").click();
String text = loginPage.element(".welcome-msg").getText();

// 轮询等待 — isEnabled()/isSelected() 自动轮询直到条件满足
boolean enabled = loginPage.loginButton.isEnabled();

// 文本内容等待 — 等待元素包含指定文本
loginPage.welcomeMessage.waitForContainsText("Welcome", 15);

// 子元素链式定位 — Locator.locator() 避免选择器无限拼接
PageElement cell = tableRow.child("td:nth-child(2)");

// 元素列表 — 动态查询 + 多阶段智能等待
itemRows.waitForCount(5).get(2).click();
itemRows.forEachSafe(el -> logger.info(el.getText()));
```

**核心特性：**

| 特性 | 说明 |
|------|------|
| **智能等待** | Playwright 原生 `waitFor()` 处理元素可见性、存在、隐藏、可点击等全部状态 |
| **轮询等待** | `isEnabled()`、`isSelected()` 等属性检查，自动轮询直到条件满足 |
| **链式调用** | 所有操作返回 `this`，支持流畅的链式编程；提供 `element(selector)` 统一门面 |
| **双超时机制** | 全局默认超时（`playwright.element.wait.timeout=15000ms`）+ 单次指定超时 |
| **企业级重试** | `executeWithRetry()` 自动诊断 DOM 状态 + 失败截图，`executeSafely()` 统一异常转换 |
| **文本标准化** | `TextNormalizer` 统一管道：NFKC 规范化 → 去控制字符 → 合并空白 → 去标点前空格 → trim |
| **失败诊断** | `ElementDiagnosticsCollector` 批量 JS 单次 IPC 收集 DOM 状态，不拖慢正常流程 |
| **智能 Pause** | `BasePage.pause()` 安全调试点：本地 IDE 自动打开 Playwright Inspector；Jenkins/BrowserStack 环境自动跳过 |

---

### 2. Route Engine — API 拦截引擎

通过流式 DSL 统一管理网络层拦截：**Monitor（监控断言）**、**Mock（模拟响应）**、**Modify（修改请求）**、**Delay（高延迟模拟）**。支持**优先级覆盖机制**——高优先级规则可自动覆盖同 pattern 的低优先级规则。支持 **Page 与 BrowserContext 双层级注册**，跨层规则自动合并延迟配置。

```java
// Monitor — 监控 API 并断言（放行请求 → 异步读取响应 → 验证）
RouteDsl.on(page)
    .api("/api/users/**")
    .monitor()
    .expectStatus(200)
    .expectJsonPath("$.data.count", 10)
    .matchMethod("GET")
    .done()
    .start();

// Mock — 纯 Mock 模式：拦截并返回自定义响应（不访问真实服务器）
RouteDsl.on(page)
    .api("/api/login")
    .mock()
    .mockBody("{\"token\":\"mock-token-123\"}")
    .mockStatus(200)
    .done()
    .start();

// Mock 拦截真实响应 — fetch 真实 API 响应后替换指定字段
RouteDsl.on(page)
    .api("profile/list")
    .mock()
    .interceptResponse()
    .mockReplaceField("updateContctOverlayFlag", false)
    .mockReplaceField("isOverBlockedDate", false)
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
    .api("/api/slow-endpoint")
    .delay(3)
    .randomDelay(1, 5)              // 每次请求 1-5 秒随机延迟
    .matchMethod("POST")
    .done()
    .start();
```

**核心特性：**

| 模式 | 行为 | 优先级 | 适用场景 |
|------|------|--------|---------|
| **Monitor** | 放行请求 → 异步读响应 → 断言状态码/JSONPath | 1 | 验证 API 调用行为 |
| **Delay** | 拦截请求 → 延迟后原样放行 | 2 | 测试超时/loading/重试 |
| **Modify** | 拦截请求 → 修改请求体/头 → 继续发送 | 3 | 篡改请求测试异常场景 |
| **Mock** | 拦截请求 → 直接返回自定义响应 | 4 | 后端未就绪时前端独立测试 |

- **优先级覆盖**：MOCK(4) > MODIFY(3) > DELAY(2) > MONITOR(1)
- **Page/Context 双层级注册**：跨层自动合并延迟配置
- **MonitorSession 自动停止**：支持超时、最小匹配次数 + `autoStopOnMatch` 双重机制
- **线程安全**：ConcurrentHashMap + AtomicLong + byte[] 拷贝跨线程
- **内存安全**：WeakReference 防泄漏、双重上限防 OOM
- **Serenity 报告集成**：Handler 数据通过 `SerenityReporter` 自动刷入 Serenity 报告
- **三层清理保障**：Scenario 结束 → JVM 退出 → 手动 `RouteDsl.clear()`

👉 详细文档：[ROUTE_PACKAGE_README.md](./ROUTE_PACKAGE_README.md)

---

### 3. 自定义 Context 选项（CustomOptionsManager）

通过链式 API 在运行时动态覆盖 Playwright Context 配置，立即生效：

```java
// 单行链式设置多个选项，立即触发 Context 重建
PlaywrightManager.customOptions()
    .setViewportSize(1920, 1080)
    .setLocale("zh-CN")
    .setTimezone("Asia/Shanghai")
    .setProxyEnabled(false)
    .setColorScheme(ColorScheme.LIGHT)
    .setIsMobile(false)
    .setDeviceScaleFactor(1.0);

// 覆盖 Geolocation
PlaywrightManager.customOptions()
    .setGeolocation(22.3, 114.17)
    .setProxyEnabled(true);

// 清除所有自定义选项，恢复默认
PlaywrightManager.customOptions().clearAll();
```

支持的可选项：`StorageState`、`Locale`、`Timezone`、`UserAgent`、`Permissions`、`Geolocation`、`DeviceScaleFactor`、`IsMobile`、`HasTouch`、`ColorScheme`、`ViewportSize`、`ProxyEnabled`。

---

### 4. AutoBrowser — 注解驱动浏览器选择

通过 `@AutoBrowser` 注解零配置实现浏览器自动选择和切换，不依赖 Hook 机制：

```java
@AutoBrowser
public class LoginGlue {
    @Steps
    private LoginSteps loginSteps;

    @Given("I login")
    public void login() {
        loginSteps.performLogin();  // 浏览器已自动设置好
    }
}
```

**工作原理：**
1. `PlaywrightManager.getBrowserType()` 被调用时自动触发 `AutoBrowserProcessor`
2. 通过堆栈跟踪找到标注 `@AutoBrowser` 的 Glue 类
3. 从 Serenity 上下文获取当前 Scenario 的标签
4. 自动匹配浏览器类型（`@firefox` / `@webkit` / `@chromium` 等标签）
5. ThreadLocal 缓存避免同一 Scenario 重复扫描

---

### 5. AxeCore Scanner — 无障碍合规测试

内置 Deque axe-core 集成，每个 Scenario 结束后自动扫描 WCAG 合规性：

```java
// 手动扫描
AxeCoreScanner scanner = new AxeCoreScanner(page);
AccessibilityResult result = scanner.scan();

// 自动监听 — AxeCoreListener 在每个 Scenario 结束后自动执行扫描
// 报告输出: target/accessibility-axe/
```

**配置：**
```properties
axe.scan.enabled=true
axe.scan.tags=wcag2a,wcag2aa,wcag21aa,wcag22aa
axe.scan.outputDir=target/accessibility-axe
```

---

### 6. 原生快照测试（PlaywrightSnapshotSupport）

支持视觉快照和 ARIA 结构快照，跨平台一致：

```java
// 视觉快照对比
PlaywrightSnapshotSupport.of(page.locator("main"))
    .visual()
    .baselineName("main-page")
    .snapshot();

// ARIA 结构快照
PlaywrightSnapshotSupport.of(page.locator("nav"))
    .aria()
    .baselineName("nav-aria")
    .snapshot();
```

---

### 7. BrowserStack — 云浏览器测试

通过 CDP 连接 BrowserStack 云端浏览器，无需修改测试代码：

```properties
browserstack.enabled=true
browserstack.username=${BROWSERSTACK_USERNAME}
browserstack.accessKey=${BROWSERSTACK_ACCESS_KEY}
browserstack.os=Windows
browserstack.osVersion=11
browserstack.browserVersion=latest
browserstack.debug=true
browserstack.networkLogs=true
browserstack.video=true
browserstack.timeout=300
```

---

### 8. SummaryReportGenerator — 摘要报告

在 `post-integration-test` 阶段自动生成精美的 HTML 摘要报告：

- **测试统计面板**：通过率、失败数、跳过数（彩色进度条）
- **功能覆盖率**：按 Feature 分组的通过率可视化
- **失败分析**：高频错误分类饼图 + 最不稳定功能排名
- **完整测试列表**：每个 Scenario 结果和错误详情
- **导出功能**：CSV 数据表 + ZIP 打包下载

内置 12 类错误自动分类（支持 `report.error.types` 自定义扩展）。

---

## 快速开始

### 环境要求

| 组件 | 版本 | 说明 |
|------|------|------|
| JDK | 21+ | Java 编译与运行 |
| Maven | 3.8+ | 构建工具 |
| Node.js | 18+ | Playwright 浏览器驱动运行环境 |

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

# 失败重试（CI 环境）
mvn clean verify -DrerunFailingTestsCount=2

# 指定浏览器类型
mvn clean verify -Dplaywright.browser.type=firefox
```

### Maven 构建生命周期

```
mvn clean verify
  ├── clean                          ← 清理 target/ + serenity-reports/
  ├── compile / test-compile         ← Java 21 编译
  ├── test                           ← surefire（*Test.java）
  ├── integration-test               ← failsafe（CucumberTestRunner*.java, *IT.java）
  ├── post-integration-test
  │   ├── serenity-reports           ← serenity-maven-plugin（aggregate）
  │   └── generate-summary-report    ← exec-maven-plugin（SummaryReportGenerator）
  └── verify                         ← failsafe verify（失败时构建终止）
```

### 编写新测试 — 四层架构

```
Feature File (.feature)      → Gherkin 自然语言描述业务场景
    ↓
Glue Layer (*Glue.java)      → @AutoBrowser + @Steps 注入，Cucumber 与 Steps 的桥接
    ↓
Steps Layer (*Steps.java)    → @Step 标记报告步骤，编排业务流程，Session 管理
    ↓
Page Layer (*Page.java)      → @Element 声明元素，继承 SerenityBasePage
    ↓
SerenityBasePage / BasePage  → 99+ 操作 + 智能等待
```

**1. Feature 文件** (`features/Login.feature`)：

```gherkin
Feature: User Login

  Scenario: Login with valid credentials
    Given user navigates to login page
    When user enters username "testuser" and password "Password123"
    And clicks the login button
    Then the welcome message "Welcome, Test User" should be displayed
```

**2. Page Object** (`pages/LoginPage.java`)：

```java
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

**3. Glue 层** (`glue/LoginGlue.java`)：

```java
@AutoBrowser
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

**4. Steps 层** (`steps/LoginSteps.java`)：

```java
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

| 层 | 文件 | 关键注解 | 职责 |
|---|------|---------|------|
| **Feature** | `.feature` | Gherkin | 用自然语言描述业务场景 |
| **Glue** | `*Glue.java` | `@AutoBrowser` `@Steps` | 桥接 Cucumber 与 Steps，参数传递 |
| **Steps** | `*Steps.java` | `@Step` | 编排业务流程，管理 Session |
| **Page** | `*Page.java` | `@Element` | 声明页面元素，继承 SerenityBasePage |

---

## 关键配置项 (serenity.properties)

### Serenity 基础

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `serenity.project.name` | Serenity Playwright Demo | 项目名称 |
| `serenity.test.root` | com.hsbc.cmb.hk.dbb.automation.tests | 测试代码根包 |
| `serenity.logging` | VERBOSE | 日志级别（QUIET / NORMAL / VERBOSE） |
| `framework.verbose.logging` | false | 框架详细日志 |
| `cucumber.execution.strict` | true | Cucumber 严格模式 |
| `serenity.outputDirectory` | target/site/serenity | Serenity 报告目录 |

### Playwright 浏览器

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `playwright.browser.type` | chromium | 浏览器类型（chromium / firefox / webkit） |
| `playwright.browser.headless` | false | 无头模式 |
| `playwright.browser.channel` | (空) | 浏览器渠道（chrome / msedge / chromium） |
| `playwright.skip.browser.download` | true | 跳过浏览器下载（使用本地浏览器） |
| `playwright.browser.slowMo` | 0 | 操作延迟（毫秒，调试用） |
| `playwright.browser.args.maximized` | true | 最大化窗口 |
| `playwright.browser.chrome.args` | 反节流 flags | Chrome 启动参数 |
| `playwright.browser.edge.args` | 反节流 flags | Edge 启动参数 |
| `playwright.browser.chromium.args` | 反节流 flags | Chromium 启动参数 |

### 超时配置

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `playwright.page.timeout` | 30000ms | 页面操作超时 |
| `playwright.page.navigationTimeout` | 30000ms | 页面导航超时 |
| `playwright.element.wait.timeout` | 15000ms | 元素等待超时 |
| `playwright.stabilize.wait.timeout` | 30000ms | Page 稳定化等待超时 |
| `playwright.polling.interval` | 500ms | 轮询检查间隔 |

### 重试机制

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `playwright.action.retry.max` | 3 | 元素操作最大重试次数 |
| `playwright.action.retry.delay` | 500ms | 重试间隔 |
| `playwright.action.operation.timeout` | 15000ms | 单次操作超时 |
| `playwright.action.screenshot.on.failure` | true | 操作失败时自动截图 |
| `rerunFailingTestsCount` | 0 | Maven Failsafe 测试级重试 |

### 截图与录屏

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `serenity.screenshot.strategy` | AFTER_EACH_STEP | 截图策略 |
| `playwright.screenshot.fullpage` | true | 全页截图 |
| `playwright.context.recordVideo.enabled` | false | 视频录制 |
| `playwright.context.trace.enabled` | false | Playwright Trace 记录 |

### 浏览器重启

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `serenity.playwright.restart.browser.for.each` | feature | scenario = 每场景深度清理；feature = Feature 内复用 |

### Session 管理

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `playwright.no.login.session.timeout.minutes` | 20 | 无登录 Session 超时时间 |

### 代理配置

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `playwright.context.proxy` | (空) | 代理服务器地址 |
| `playwright.context.proxy.enabled` | false | 代理启用开关 |

### 视口与上下文

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `playwright.context.viewport.width` | 1366 | 视口宽度 |
| `playwright.context.viewport.height` | 768 | 视口高度 |
| `playwright.context.locale` | (空) | 区域设置 |
| `playwright.context.timezone` | (空) | 时区 |
| `playwright.context.userAgent` | (空) | 自定义 User-Agent |
| `playwright.context.hasTouch` | false | 触摸屏模式 |
| `playwright.context.isMobile` | false | 移动端模式 |
| `playwright.context.colorScheme` | (空) | 颜色模式 |
| `playwright.context.deviceScaleFactor` | 1.0 | 设备像素比 |
| `playwright.context.permissions` | (空) | 授予的权限 |
| `playwright.context.geolocation` | (空) | 地理位置 |

### 自定义 Context 选项

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `playwright.custom.context.colorScheme` | DARK | 自定义颜色模式 |
| `playwright.custom.context.userAgent` | Mozilla/5.0... | 自定义 UA |
| `playwright.custom.context.viewport.width` | 600 | 自定义视口宽度 |
| `playwright.custom.context.viewport.height` | 840 | 自定义视口高度 |

### Axe-core 无障碍

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `axe.scan.enabled` | true | 无障碍扫描开关 |
| `axe.scan.tags` | (空) | WCAG 标准标签 |
| `axe.scan.outputDir` | target/accessibility-axe | 报告输出目录 |

### BrowserStack 云测试

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `browserstack.enabled` | false | 云测试开关 |
| `browserstack.username` | (空) | 用户名（推荐环境变量） |
| `browserstack.accessKey` | (空) | 访问密钥（推荐环境变量） |
| `browserstack.os` | Windows | 操作系统 |
| `browserstack.osVersion` | 11 | 操作系统版本 |
| `browserstack.browserVersion` | latest | 浏览器版本 |
| `browserstack.timeout` | 300s | 连接超时 |
| `browserstack.debug` | true | 调试模式 |
| `browserstack.networkLogs` | true | 网络日志 |
| `browserstack.video` | true | 视频录制 |

### 报告配置

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `serenity.report.url` | ${JENKINS_URL}job/${JOB_NAME}/${BUILD_NUMBER}/... | 报告 URL（Jenkins 环境变量自动解析） |
| `report.error.types` | JSON 数组 | 自定义错误分类规则 |

---

## 异常体系

框架定义 10 层异常类，统一继承关系：

| 异常类 | 父类 | 触发场景 |
|--------|------|---------|
| `FrameworkException` | RuntimeException | 顶层框架异常基类 |
| `BrowserException` | FrameworkException | 浏览器启动/关闭/连接失败 |
| `ConfigurationException` | FrameworkException | 配置项缺失或格式错误 |
| `ElementException` | FrameworkException | 元素操作异常基类 |
| `ElementNotFoundException` | ElementException | 元素未找到 |
| `ElementOperationException` | ElementException | 元素操作执行失败 |
| `InitializationException` | FrameworkException | 框架初始化失败 |
| `NavigationException` | FrameworkException | 页面导航超时或失败 |
| `ScreenshotException` | FrameworkException | 截图保存失败 |
| `TimeoutException` | FrameworkException | 等待操作超时 |

---

## Playwright vs Selenium — 关键行为差异

> **核心区别**：Selenium 是**同步指令式**模型，Playwright 是**事件驱动式**模型。这种范式差异导致 Alert 和新 Tab 场景的**时序要求完全不同**。

### 时序敏感的典型场景

| 场景 | Selenium 方式 | Playwright 方式 | 时序风险 |
|------|--------------|----------------|---------|
| **Alert** | 事后 `switchTo().alert().accept()` | 事前 `onceDialog(Dialog::accept)` | ⚠️ 高 — 必须先注册再触发 |
| **新 Tab** | 事后 `getWindowHandles()` 轮询 | 事前 `waitForPage(action)` | ⚠️ 高 — 必须事件级捕获 |
| **iframe** | `switchTo().frame()` | `page.frame()` + ThreadLocal 管理 | ⚠️ 中 — 导航后 Frame detached |
| **点击** | 手动 `WebDriverWait` | 自动等待 5 个阶段 | ✅ 低 — Playwright 更可靠 |
| **下载** | 配置 Profile + 轮询 | `onDownload()` 事件 | ✅ 低 — 框架自动处理 |
| **隔离** | 多 Driver 实例 | BrowserContext | ✅ 低 — Context 零开销 |
| **全页截图** | aShot 第三方库 | 原生 `setFullPage(true)` | ✅ 低 |
| **网络拦截** | BrowserMob-Proxy | 原生 `route()` API | ✅ 低 — CDP 层面 |
| **文件上传** | `sendKeys(path)` | `setInputFiles(paths)` | ✅ 低 |
| **资源关闭** | `driver.quit()` | Page → Context → Browser → Playwright | ⚠️ 中 — 顺序不可颠倒 |

**框架适配：**
- `BasePage.acceptAlert()` / `dismissAlert()` — 事前注册模式，面向 Selenium 用户习惯
- `BasePage.switchToNewPage(action, timeout)` — 原子操作，消除竞态
- JVM Shutdown Hook 自动按正确顺序关闭资源

👉 详细对比：[PLAYWRIGHT_VS_SELENIUM.md](./PLAYWRIGHT_VS_SELENIUM.md)

---

## IDEA 断点调试指南

> **根因**：IDEA 断点默认 Suspend All 会挂起 Java 主线程，导致 Playwright WebSocket 无法向浏览器下发指令。

### 推荐调试方案

| 方案 | 适用场景 | 操作 |
|------|----------|------|
| **`pause()` 方法** | 需要人机交互暂停检查页面 | 代码中插入 `pause()`，自动打开 Playwright Inspector |
| **非挂起日志断点** | 只需观察变量值 | 右键断点 → 取消 Suspend → 勾选 Evaluate and log |
| **反后台节流 flags** | 降低断点挂起时 Chrome 冻结概率 | 框架默认启用 `--disable-background-timer-throttling` 等 4 个 flag |

### 框架内置反节流 Flags（默认生效）

| Flag | 作用 |
|------|------|
| `--disable-background-timer-throttling` | 禁止后台定时器节流 |
| `--disable-backgrounding-occluded-windows` | 禁止对被遮挡窗口降级后台化 |
| `--disable-renderer-backgrounding` | 禁止渲染进程后台化挂起 |
| `--disable-features=CalculateNativeWinOcclusion` | 禁用 Windows 原生窗口遮挡检测 |

---

## 技术亮点

1. **Playwright 替代 Selenium** — CDP 协议比 WebDriver 快 30-50%，内置自动等待机制
2. **三层架构清晰** — Playwright → Browser → BrowserContext → Page，分层管理；Context 实例复用 + 状态深度清理
3. **双等待策略** — 智能等待（Playwright 原生 waitFor）+ 轮询等待（isEnabled/isSelected 等属性检查）
4. **Route Engine 统一网络拦截** — Monitor/Mock/Modify/Delay 四种模式，流式 DSL，零阻塞 UI；类型保持的字段替换；Page/Context 双层级注册 + 跨层延迟合并
5. **企业级报告体系** — Serenity 详细报告 + SummaryReportGenerator 摘要报告（HTML/CSV/ZIP），12 类错误自动分类
6. **CI/CD 原生集成** — Jenkins Pipeline，环境变量自动解析，邮件链接自动构建
7. **无障碍合规** — 内置 Deque axe-core WCAG 自动扫描
8. **Session 跨场景复用** — 缓存命中时跳过登录直达 homeUrl，未命中时自动保存登录态
9. **BrowserStack 云测试** — CDP 协议连接远程浏览器，无需修改测试代码
10. **线程安全 + 内存安全** — ConcurrentHashMap + WeakReference 防泄漏 + 双重上限防 OOM
11. **CustomOptionsManager 链式 API** — 运行时动态覆盖 Context 配置，立即生效，支持 12 种选项
12. **@AutoBrowser 零配置浏览器切换** — 注解驱动，堆栈跟踪自动发现，Scenario 标签匹配浏览器
13. **JVM Shutdown Hook 资源清理** — 确保 JVM 异常退出时 Playwright 资源被正确释放
14. **原生快照测试** — 视觉快照 + ARIA 结构快照，跨平台一致基线

---

## API 测试框架

框架同时内置了基于 Rest Assured + Serenity 的 API 测试能力（26 个类）：

- **配置驱动**：HOCON 配置文件管理 Headers、Endpoint、Query Params 等
- **多层级配置合并**：default → entity-specific → environment-specific
- **动态 Payload**：JSON 模板文件 + JsonPath 字段动态修改
- **BDD + TDD 双模式**：支持 Cucumber Gherkin 和 JUnit 编程式测试
- **全 URL 模式**：自动解析 baseUri + endpoint + query params

```java
// 快速示例
BaseStep baseStep = TestServices.initialize()
    .withEntity("petstore")
    .withEnv("dev")
    .baseStep();

baseStep.loadEndpointConfig("pet", "GET");
baseStep.getResource();
baseStep.verifyResponseStatusCode(200);
baseStep.verifyResponseJsonPath("name", "doggie");
```

👉 详细文档：[API_README.md](./API_README.md)

---

## 文档索引

| 文档 | 内容 |
|------|------|
| **[README.md](./README.md)** | 框架总览、架构设计、Playwright vs Selenium 关键差异、快速开始、全部配置项 |
| [ROUTE_PACKAGE_README.md](./ROUTE_PACKAGE_README.md) | Route Engine 完整 API 文档（Monitor/Mock/Modify/Delay） |
| [API_README.md](./API_README.md) | API 测试框架文档（Rest Assured + Serenity + HOCON 配置） |
| [PLAYWRIGHT_VS_SELENIUM.md](./PLAYWRIGHT_VS_SELENIUM.md) | Playwright vs Selenium 技术选型对比 |
| [PLAYWRIGHT_LISTENERS.md](./PLAYWRIGHT_LISTENERS.md) | Playwright 监听器全景指南（事件驱动模型、全部监听器用途与最佳实践） |
| [Element.MD](./Element.MD) | 元素定位方法手册（CSS/XPath 优先级、PageElement/PageElementList/BasePage 完整速查表） |
| `route-demo-service/` | Spring Boot 演示服务（端口 8888），用于 Route Engine 测试 |

---

## 依赖速查

| 组件 | GroupId | Version | 说明 |
|------|---------|---------|------|
| Serenity BDD | net.serenity-bdd | 4.3.4 | serenity-model / serenity-core / serenity-cucumber / serenity-junit |
| Playwright | com.microsoft.playwright | 1.58.0 | 浏览器自动化驱动 |
| Cucumber | io.cucumber | 7.14.0 | BDD 框架 |
| JUnit | junit:junit | 4.13.2 | 测试框架 |
| Spring Context | org.springframework | 6.1.6 | DI 容器 |
| Logback | ch.qos.logback | 1.5.6 | SLF4J 日志实现 |
| typesafe.config | com.typesafe | 1.4.2 | HOCON 配置解析 |
| Gson | com.google.code.gson | 2.10.1 | JSON 序列化 |
| JsonPath | com.jayway.jsonpath | 2.9.0 | JSON 路径表达式 |
| org.json | org.json | 20240303 | JSON 对象模型 |
| Axe-core Playwright | com.deque.html.axe-core:playwright | 4.9.1 | 无障碍扫描 |
| Reflections | org.reflections | 0.10.2 | 类路径扫描 |
| Hamcrest | org.hamcrest | 2.2 | 断言匹配器 |
| Selenium Support | org.seleniumhq.selenium | 4.15.0 | Serenity 内部依赖 |
| HikariCP | com.zaxxer | 5.0.1 | JDBC 连接池 |
