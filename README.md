# Automation WebUI Framework BDD

> **企业级 Web UI 自动化测试框架** — 基于 Playwright + Serenity BDD + Cucumber，构建的高效、稳定、可扩展的自动化测试解决方案。
>
> 框架版本：`1.0.0-FINANCIAL-GRADE`

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
| **日志** | SLF4J + Logback Classic 1.5.6（System.err 绕过 IntelliJ 缓冲区） |


## Playwright 核心概念

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

| 层级 | 职责 | 类比 | 管理方式 |
|------|------|------|---------|
| **Playwright** | 管理浏览器驱动，提供 `chromium()`/`firefox()`/`webkit()` 工厂方法 | 浏览器引擎管理器 | `PlaywrightManager` 静态 Map 管理，同一配置复用 |
| **Browser** | 一个浏览器进程实例，可包含多个 Context | 浏览器应用程序 | `browserInstances` Map，按 configId 隔离 |
| **BrowserContext** | 独立的浏览器会话，Cookie/LocalStorage/登录态完全隔离 | 隐身窗口 | `contextThreadLocal` ThreadLocal；Context 实例复用 + 状态深度清理 |
| **Page** | 一个标签页，所有页面操作的载体 | 浏览器标签页 | `pageThreadLocal` ThreadLocal，每个线程一个 Page |

**关键设计：**
- **Browser 复用 + Context 隔离**：频繁创建/销毁 Browser 开销大，框架采用 Browser 共享、Context 隔离的策略。`scenario` 模式通过 `cleanupContextState()` 深度清理（清除 Cookies/Permissions）后复用同一 Context 实例，**整个测试生命周期只保持 1 个浏览器窗口**。
- **Context 复用机制**：两种重启策略（`scenario` / `feature`）均复用 Context 实例，避免 `browser.newContext()` 弹出多个 Chrome 窗口；隔离性通过状态清理保证。
- **Session 跨 Scenario 复用**：通过 `SessionManager` 将 Cookie/LocalStorage 持久化到 `target/.sessions/` 目录，下一个 Scenario 自动恢复，减少冗余登录。


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


## 项目结构

```
Automation_WebUI_Framework_BDD/
├── pom.xml                              # Maven 构建配置（全部依赖与插件）
├── serenity.properties                  # 框架运行时配置（超时、截图、视口、浏览器等）
├── browserstack.conf                    # BrowserStack 云测试配置示例
│
├── src/main/java/.../automation/
│   ├── framework/web/
│   │   ├── page/                        # ★ Page Object Model 核心
│   │   │   ├── PageElement.java         # 元素操作（942行，企业级重试 + 失败诊断）
│   │   │   ├── PageElementList.java     # 元素列表（274行，动态查询 + 多阶段等待）
│   │   │   ├── Element.java             # @Element 注解（仅 CSS / XPath）
│   │   │   ├── ElementDiagnosticsCollector.java  # 失败诊断收集器（批量 JS 单次 IPC）
│   │   │   ├── base/BasePage.java       # 基础页面类（99+ Playwright 操作）
│   │   │   ├── base/impl/SerenityBasePage.java  # Serenity 集成（双拦截器精简 87 个 Override）
│   │   │   └── factory/PageObjectFactory.java   # 页面对象工厂（单例/原型/线程隔离）
│   │   ├── route/                       # ★ Route Engine（18 个文件）
│   │   │   ├── core/RouteEngine.java    # 路由引擎
│   │   │   ├── core/RouteRegistry.java  # WeakReference 隔离注册表
│   │   │   ├── core/RouteRule.java      # 路由规则模型 + 校验
│   │   │   ├── core/RouteHandleType.java # Monitor/Mock/Modify/Delay 枚举
│   │   │   ├── core/ApiCaptureContext.java  # ThreadLocal 捕获上下文
│   │   │   ├── core/CapturedApiCall.java    # API 调用快照
│   │   │   ├── core/RouteMonitor.java       # 监控门面
│   │   │   ├── core/RouteException.java     # 三层异常体系
│   │   │   ├── dsl/RouteDsl.java        # 流式 DSL（外部唯一入口）
│   │   │   ├── handler/MonitorHandler.java  # 监控处理器
│   │   │   ├── handler/MockHandler.java     # 模拟处理器（含 mockReplaceField 批量替换）
│   │   │   ├── handler/ModifyHandler.java   # 修改处理器
│   │   │   ├── handler/DelayHandler.java    # 延迟处理器
│   │   │   ├── handler/RouteHandler.java    # 函数式接口
│   │   │   └── util/                    # RouteAsyncPool / SerenityReporter / RouteUtil
│   │   ├── lifecycle/                   # ★ Playwright 生命周期管理
│   │   │   ├── PlaywrightManager.java   # Playwright/Browser/Context/Page 管理层
│   │   │   ├── PlaywrightContextManager.java  # Context 创建与 Page 稳定化
│   │   │   ├── PlaywrightConfigManager.java   # 浏览器类型判断与配置
│   │   │   ├── PlaywrightInitializer.java     # Playwright 实例初始化
│   │   │   ├── CustomOptionsManager.java      # 自定义 Context 选项
│   │   │   └── ContextLifecycleHookManager.java # Context 生命周期钩子
│   │   ├── config/                     # 配置中心
│   │   │   ├── FrameworkConfig.java    # 枚举式集中配置（1089行，含全部默认值和说明）
│   │   │   └── AutoBrowserProcessor.java  # @AutoBrowser 注解处理器
│   │   ├── listener/                   # 监听器体系
│   │   │   ├── PlaywrightListener.java # Serenity 生命周期监听
│   │   │   ├── AxeCoreListener.java    # 无障碍扫描监听
│   │   │   ├── NativeSnapshotTestListener.java  # 快照测试监听
│   │   │   ├── ListenerRegistry.java   # 监听器注册
│   │   │   └── ThucydidesStepsListenerAdapter.java  # SPI 自动注册适配器
│   │   ├── accessibility/AxeCoreScanner.java  # WCAG 无障碍扫描
│   │   ├── screenshot/                 # 截图系统
│   │   │   ├── photographer/           # 截图摄影师
│   │   │   ├── processor/              # 截图处理器
│   │   │   ├── strategy/               # 截图策略
│   │   │   └── permission/             # 权限处理
│   │   ├── snapshot/                   # 原生快照测试
│   │   │   ├── PlaywrightSnapshotSupport.java # 快照核心
│   │   │   ├── NativeSnapshotResult.java      # 快照结果
│   │   │   └── NativeSnapshotReportGenerator.java # 快照报告生成
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
│   │   └── utils/
│   │       ├── TextNormalizer.java     # 统一文本标准化管道
│   │       └── LoggingConfigUtil.java  # 受 verbose 开关控制的日志工具
│   │
│   ├── framework/api/                   # API 测试框架（基于 Rest Assured）
│   │   ├── client/                     # REST 客户端
│   │   ├── core/                       # Endpoint / Entity / Step
│   │   └── assembler/                  # 请求组装
│   │
│   └── report/SummaryReportGenerator.java  # HTML/CSV/ZIP 摘要报告
│
├── route-demo-service/                   # Route Engine 演示服务
│   ├── pom.xml                          # Spring Boot 2.7.18
│   ├── src/main/java/com/example/demo/
│   │   ├── DemoApplication.java
│   │   ├── controller/
│   │   │   ├── AuthController.java      # POST /demo/api/auth/login
│   │   │   ├── SlowController.java      # GET /demo/api/slow/*
│   │   │   └── UserController.java      # CRUD /demo/api/users/**
│   │   └── model/                       # User / Order / LoginRequest / LoginResponse
│   └── src/main/resources/application.yml
│
├── src/test/java/.../tests/
│   ├── CucumberTestRunnerIT.java         # Maven Failsafe 测试运行入口
│   ├── steps/                            # BDD 步骤定义（@Step 编排业务逻辑）
│   ├── pages/                            # 业务页面对象（@Element 声明元素）
│   ├── glue/                             # Cucumber Glue 桥接层（@AutoBrowser 注解）
│   └── route/                            # Route Engine 测试步骤
│
├── src/test/resources/
│   ├── features/
│   │   ├── web/                          # Web UI 测试 Feature 文件
│   │   └── route/                        # Route Engine 测试 Feature 文件
│   └── snapshots/native/                 # 原生快照基线
│       ├── visual/                       # 视觉快照
│       └── aria/                         # ARIA 快照
│
└── 文档/
    ├── README.md                         # ★ 框架总览（本文件）
    ├── ROUTE_PACKAGE_README.md           # Route Engine 完整 API 文档
    ├── API_README.md                     # API 测试框架文档（Rest Assured）
    ├── PLAYWRIGHT_VS_SELENIUM.md         # Playwright vs Selenium 技术选型
    └── Element.MD                        # 元素定位方法手册
```


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
| **链式调用** | 所有操作返回 `this`，支持流畅的链式编程 |
| **双超时机制** | 全局默认超时（`playwright.element.wait.timeout=15000ms`）+ 单次指定超时 |
| **企业级重试** | `executeWithRetry()` 自动诊断 DOM 状态 + 失败截图，`executeSafely()` 统一异常转换 |
| **Locator 缓存** | Volatile + DCL 双重检查锁定，页面切换仅刷新缓存避免重建对象 |
| **文本标准化** | `TextNormalizer` 统一管道：NFKC 规范化 → 去控制字符 → 合并空白 → 去标点前空格 → trim |
| **失败诊断** | `ElementDiagnosticsCollector` 批量 JS 单次 IPC 收集 DOM 状态，不拖慢正常流程 |

**核心类：**

| 类 | 职责 |
|----|------|
| `Element` | `@Element` 注解，仅 CSS / XPath 选择器，自动注入 `PageElement` / `PageElementList` |
| `PageElement` | 942行，封装 Playwright Locator 完整操作；`executeSafely` + `executeWithRetry` 双模板；`ChildPageElement` 链式子定位；`getTextRaw()` 快速路径 |
| `PageElementList` | 274行，`AbstractList<PageElement>`；`waitForCount()` / `forEachSafe()` / 懒加载缓存 |
| `BasePage` | 基础页面类，99+ Playwright 核心操作；内部统一等待引擎 `waitForCondition`（private）驱动所有 `waitForXxx()` 公共方法；`retryWithValidation` 操作级重试 |
| `SerenityBasePage` | Serenity 集成，`record()` / `recordAndReturn()` 双拦截器消除 87 个冗余 Override；Route Engine 异步数据自动刷入 Serenity 报告 |
| `PageObjectFactory` | 页面对象工厂，支持单例/原型/线程隔离等生命周期策略 |


### 2. Route Engine — API 拦截引擎

通过流式 DSL 统一管理网络层拦截：**Monitor（监控断言）**、**Mock（模拟响应）**、**Modify（修改请求）**、**Delay（高延迟模拟）**。支持**优先级覆盖机制**——高优先级规则（MOCK > MODIFY > DELAY > MONITOR）可自动覆盖同 pattern 的低优先级规则。

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

// Mock — 拦截并返回自定义响应（支持通配符 [*] 批量字段替换 + 类型保持）
RouteDsl.on(page)
    .api("/api/login")
    .mock()
    .mockBody("{\"token\":\"mock-token-123\"}")
    .mockStatus(200)
    .done()
    .start();

// Mock 批量替换 — JSON 字段自动类型保持（Int/Long/Boolean/Array/Object 不会降级为字符串）
RouteDsl.on(page)
    .api("/api/items")
    .mock()
    .mockBody("{\"code\":200,\"data\":{\"items\":[]}}")
    .mockReplaceField("$.data.items[*].name", "\"MOCKED\"")
    .done()
    .start();

// Modify — 修改请求后继续发送（JSONPath 精准替换请求体/增删请求头）
RouteDsl.on(page)
    .api("/api/submit")
    .modifyRequest()
    .setRequestHeader("X-Custom-Header", "test-value")
    .modifyRequestBody("amount", "999")
    .done()
    .start();

// Delay — 模拟高延迟网络（固定延迟 + 随机延迟）
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

- **优先级覆盖**：MOCK(4) > MODIFY(3) > DELAY(2) > MONITOR(1)，高优先级规则自动覆盖同 pattern 低优先级规则
- **多维度匹配**：ResourceType、HTTP Method、Headers、Query、Body Regex、Referrer、Origin、Frame
- **线程安全**：ConcurrentHashMap + AtomicLong + byte[] 拷贝跨线程，零阻塞 UI
- **内存安全**：WeakReference 防泄漏、双重上限防 OOM、防重集合、缓存淘汰、线程池限流
- **Serenity 报告集成**：Handler 非主线程数据通过 `SerenityReporter` 队列机制自动刷入 Serenity 报告

**包结构（18个类，4个子包）：**

| 子包 | 类 | 职责 |
|------|-----|------|
| `core/` | `RouteEngine`、`RouteRegistry`、`RouteRule`、`RouteHandleType`、`RouteException`、`ApiCaptureContext`、`CapturedApiCall`、`RouteMonitor` | 路由引擎、注册表、数据模型、异常体系 |
| `dsl/` | `RouteDsl` | 流式 DSL 构建器（外部唯一入口） |
| `handler/` | `RouteHandler`（函数式接口）、`MonitorHandler`、`MockHandler`、`ModifyHandler`、`DelayHandler` | 四种处理器实现 |
| `util/` | `RouteAsyncPool`、`SerenityReporter`、`RouteUtil` | 异步线程池、报告刷入队列、匹配工具 |

👉 详细文档：[ROUTE_PACKAGE_README.md](./ROUTE_PACKAGE_README.md)


### 3. AutoBrowser — 注解驱动浏览器选择

通过 `@AutoBrowser` 注解零配置实现浏览器自动选择和切换，不依赖 Hook 机制，对测试代码完全透明。

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

浏览器标签映射：`@browser:firefox` → Firefox / `@browser:webkit` → WebKit / 默认 → Chromium


### 4. AxeCore Scanner — 无障碍合规测试

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
axe.scan.tags=wcag2a,wcag2aa,wcag21aa,wcag22aa   # 留空运行所有规则
axe.scan.outputDir=target/accessibility-axe
```

**依赖：** com.deque.html.axe-core:playwright 4.9.1


### 5. Native Snapshot — Playwright 原生快照测试

利用 Playwright 原生 `expect(page).toHaveScreenshot()` 和 `expect(locator).toMatchAriaSnapshot()` 实现视觉和 ARIA 快照比对：

| 快照类型 | 存储目录 | 说明 |
|---------|---------|------|
| **Visual** | `src/test/resources/snapshots/native/visual/` | 像素级视觉截图比对 |
| **ARIA** | `src/test/resources/snapshots/native/aria/` | ARIA 可访问性结构快照（一套基线跨平台） |

**配置：**
```properties
native.snapshot.enabled=true
native.snapshot.visual.maxDiffPixels=100
native.snapshot.visual.maxDiffPixelRatio=0.01
```

`NativeSnapshotTestListener` 在测试套件结束时自动调用 `NativeSnapshotReportGenerator` 生成快照比对报告。


### 6. BrowserStack — 云浏览器测试

通过 CDP（Chrome DevTools Protocol）连接 BrowserStack 云端浏览器，无需修改测试代码：

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

启用后，`BrowserStackManager` 在框架初始化时自动连接 BrowserStack CDP 端点，后续所有 Playwright 操作均通过远程浏览器执行。支持 Dashboard 会话名称按场景设置（`-Dbrowserstack.sessionName`）。

配置示例文件：`browserstack.conf`


### 7. SummaryReportGenerator — 摘要报告

自动生成精美的 HTML 摘要报告（在 `post-integration-test` 阶段通过 `exec-maven-plugin` 自动触发），包含：

- **测试统计面板**：通过率、失败数、跳过数（彩色进度条）
- **功能覆盖率**：按 Feature 分组的通过率可视化
- **失败分析**：高频错误分类饼图 + 最不稳定功能排名
- **完整测试列表**：每个 Scenario 结果和错误详情
- **导出功能**：CSV 数据表 + ZIP 打包下载

**内置 12 类错误自动分类**（支持用户自定义扩展，`report.error.types` 追加在默认规则之前优先匹配）：
Timeout Error / Element Not Found / Navigation Failed / Assertion Failed / NPE / Code Issue / Browser Closed / Environment Issue / API Issue / Auth Failed / Browser Launch Error / Data Validation Error

**Jenkins 集成：**
```properties
serenity.report.url=${JENKINS_URL}job/${JOB_NAME}/${BUILD_NUMBER}/Serenity_20Summary_20Report/
```
`SummaryReportGenerator` 自动检测 Jenkins 环境，解析环境变量并构建完整的报告 URL。邮件中所有链接（View Full Report、Download ZIP/CSV、Scenario 链接）自动指向 Jenkins workspace。


## 快速开始

### 环境要求

| 组件 | 版本 | 说明 |
|------|------|------|
| JDK | 21+ | Java 编译与运行 |
| Maven | 3.8+ | 构建工具 |
| Node.js | 18+ | Playwright 浏览器驱动（无需手动下载，`playwright.skip.browser.download=true` 使用本地浏览器） |

### 运行测试

```bash
# 运行全部 Cucumber 测试（Failsafe：verify goal 自动触发 serenity-maven-plugin aggregate）
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
  ├── clean                          ← maven-clean-plugin（清理 target/ + serenity-reports/）
  ├── compile / test-compile         ← maven-compiler-plugin（Java 21）
  ├── test                           ← maven-surefire-plugin（*Test.java）
  ├── integration-test               ← maven-failsafe-plugin（CucumberTestRunner*.java, *IT.java）
  ├── post-integration-test
  │   ├── serenity-reports           ← serenity-maven-plugin（aggregate → Serenity HTML Report）
  │   └── generate-summary-report    ← exec-maven-plugin（SummaryReportGenerator）
  └── verify                         ← failsafe verify（失败时构建终止）
```

### 编写新测试 — Feature → Glue → Steps → Page 四层架构

```
Feature File (.feature)      → Gherkin 自然语言描述业务场景
    ↓
Glue Layer (*Glue.java)      → @AutoBrowser + @Steps 注入，Cucumber 与 Steps 的桥接
    ↓
Steps Layer (*Steps.java)    → @Step 标记报告步骤，编排业务流程，Session 管理
    ↓
Page Layer (*Page.java)      → @Element 声明元素，继承 SerenityBasePage
    ↓
SerenityBasePage / BasePage  → 99+ 操作 + 智能等待（委托到 Playwright API）
```

**1. Feature 文件** (`src/test/resources/features/Login.feature`)：

```gherkin
Feature: User Login

  Scenario: Login with valid credentials
    Given user navigates to login page
    When user enters username "testuser" and password "Password123"
    And clicks the login button
    Then the welcome message "Welcome, Test User" should be displayed
```

**2. Page Object** (`src/test/java/.../pages/LoginPage.java`)：

```java
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.base.impl.SerenityBasePage;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.PageElement;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.Element;

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

**3. Glue 层** (`src/test/java/.../glue/LoginGlue.java`)：

```java
import com.hsbc.cmb.hk.dbb.automation.framework.web.annotations.AutoBrowser;
import net.serenitybdd.annotations.Steps;

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

**4. Steps 层** (`src/test/java/.../steps/LoginSteps.java`)：

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

| 层 | 文件 | 关键注解 | 职责 |
|---|------|---------|------|
| **Feature** | `.feature` | Gherkin | 用自然语言描述业务场景 |
| **Glue** | `*Glue.java` | `@AutoBrowser` `@Steps` | 桥接 Cucumber 与 Steps，参数传递，零业务逻辑 |
| **Steps** | `*Steps.java` | `@Step` | 编排业务流程，管理 Session，通过 PageObjectFactory 获取 Page |
| **Page** | `*Page.java` | `@Element` | 声明页面元素，继承 SerenityBasePage 获得全部操作能力 |


## 关键配置项 (serenity.properties)

### Serenity 基础

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `serenity.project.name` | Serenity Playwright Demo | 项目名称 |
| `serenity.test.root` | com.hsbc.cmb.hk.dbb.automation.tests | 测试代码根包 |
| `serenity.encoding` | UTF-8 | 编码 |
| `serenity.console.colors` | true | 控制台颜色 |
| `serenity.logging` | VERBOSE | 日志级别（QUIET / NORMAL / VERBOSE） |
| `framework.verbose.logging` | false | 框架详细日志（受 serenity.logging=VERBOSE 控制） |
| `cucumber.execution.strict` | true | Cucumber 严格模式（undefined/pending 视为失败） |
| `serenity.outputDirectory` | target/site/serenity | Serenity 报告目录 |

### Playwright 浏览器

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `playwright.browser.type` | chromium | 浏览器类型（chromium/firefox/webkit） |
| `playwright.browser.headless` | false | 无头模式 |
| `playwright.browser.channel` | (空) | 浏览器渠道（chrome/msedge/chromium） |
| `playwright.skip.browser.download` | true | 跳过浏览器下载（使用本地浏览器） |
| `playwright.browser.executablePath.chrome` | (空) | Chrome 可执行文件路径 |
| `playwright.browser.executablePath.firefox` | (空) | Firefox 可执行文件路径 |
| `playwright.browser.executablePath.webkit` | (空) | WebKit 可执行文件路径 |
| `playwright.browser.args.maximized` | true | 最大化窗口 |
| `playwright.browser.slowMo` | 0 | 操作延迟（毫秒，调试用） |

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
| `playwright.action.diagnostics.detailed` | true | 详细失败诊断信息 |
| `rerunFailingTestsCount` | 0 | Maven Failsafe 测试级重试（CI: `-DrerunFailingTestsCount=N`） |

### 页面加载与等待

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `playwright.page.load.state` | LOAD | 页面加载状态（LOAD/DOMCONTENTLOADED/NETWORKIDLE） |
| `playwright.action.post.delay` | 0ms | 元素操作后延迟（等待后续渲染） |

### 视口与上下文

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `playwright.context.viewport.width` | 1366 | 视口宽度（框架默认） |
| `playwright.context.viewport.height` | 768 | 视口高度（框架默认） |
| `playwright.context.locale` | (空) | 区域设置 |
| `playwright.context.timezone` | (空) | 时区 |
| `playwright.context.userAgent` | (空) | 自定义 User-Agent |
| `playwright.context.hasTouch` | false | 触摸屏模式 |
| `playwright.context.isMobile` | false | 移动端模式 |
| `playwright.context.colorScheme` | (空) | 颜色模式（dark/light/no-preference） |
| `playwright.context.deviceScaleFactor` | 1.0 | 设备像素比 |
| `playwright.context.permissions` | (空) | 授予的权限（逗号分隔） |
| `playwright.context.geolocation` | (空) | 地理位置 |

### 自定义 Context（条件注入）

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `playwright.custom.context.colorScheme` | DARK | 自定义颜色模式 |
| `playwright.custom.context.userAgent` | Mozilla/5.0...Chrome/60... | 自定义 UA |
| `playwright.custom.context.viewport.width` | 600 | 自定义视口宽度 |
| `playwright.custom.context.viewport.height` | 840 | 自定义视口高度 |

### 截图与录屏

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `serenity.screenshot.strategy` | AFTER_EACH_STEP | 截图策略（AFTER_EACH_STEP/FOR_FAILURES/BEFORE_AND_AFTER_EACH_STEP/DISABLED） |
| `playwright.screenshot.fullpage` | true | 全页截图（自动滚动拼接，触发懒加载内容） |
| `playwright.screenshot.wait.timeout` | 5000ms | 截图等待超时 |
| `playwright.context.recordVideo.enabled` | false | 视频录制 |
| `playwright.context.recordVideo.dir` | target/videos | 录制视频存储目录 |
| `playwright.context.trace.enabled` | false | Playwright Trace 记录 |

### 浏览器重启

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `serenity.playwright.restart.browser.for.each` | scenario | scencario=每场景深度清理；feature=Feature 内复用 |

### Session 管理

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `playwright.no.login.session.timeout.minutes` | 20 | 无登录 Session 超时时间（分钟） |

### Axe-core 无障碍

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `axe.scan.enabled` | true | 无障碍扫描开关 |
| `axe.scan.tags` | (空) | WCAG 标准标签（wcag2a/wcag2aa/wcag21aa/wcag22aa） |
| `axe.scan.outputDir` | target/accessibility-axe | 报告输出目录 |

### 原生快照

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `native.snapshot.enabled` | true | 快照测试开关 |
| `native.snapshot.visual.dir` | src/test/resources/snapshots/native/visual | 视觉快照基线 |
| `native.snapshot.aria.dir` | src/test/resources/snapshots/native/aria | ARIA 快照基线 |
| `native.snapshot.visual.maxDiffPixels` | 100 | 最大差异像素数 |
| `native.snapshot.visual.maxDiffPixelRatio` | 0.01 | 最大差异像素比例 |
| `native.snapshot.silent` | false | 静默模式 |

### BrowserStack 云测试

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `browserstack.enabled` | false | 云测试开关 |
| `browserstack.username` | (空) | 用户名（推荐环境变量 `BROWSERSTACK_USERNAME`） |
| `browserstack.accessKey` | (空) | 访问密钥（推荐环境变量 `BROWSERSTACK_ACCESS_KEY`） |
| `browserstack.sessionName` | (空) | Dashboard 会话名称（推荐 `-Dbrowserstack.sessionName`） |
| `browserstack.os` | Windows | 操作系统 |
| `browserstack.osVersion` | 11 | 操作系统版本 |
| `browserstack.browserVersion` | latest | 浏览器版本 |
| `browserstack.timeout` | 300s | 连接超时 |
| `browserstack.debug` | true | 调试模式（步骤级截图） |
| `browserstack.networkLogs` | true | 网络日志 |
| `browserstack.video` | true | 视频录制 |

### 报告与错误分类

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `serenity.report.url` | ${JENKINS_URL}job/${JOB_NAME}/${BUILD_NUMBER}/... | 报告 URL（环境变量自动解析） |
| `report.error.types` | JSON 数组 | 自定义错误分类规则（追加在 12 类默认规则之前优先匹配） |

### 测试目录

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `serenity.features.root` | src/test/resources/features | Feature 文件目录 |
| `serenity.requirements.base` | src/test/resources/features | 需求基础目录 |
| `serenity.requirements.types` | capability,feature | 需求层级类型 |


## 异常体系

框架定义 10 层异常类，统一继承关系，确保失败信息精准可追溯：

| 异常类 | 父类 | 触发场景 |
|--------|------|---------|
| `FrameworkException` | RuntimeException | 顶层框架异常基类 |
| `BrowserException` | FrameworkException | 浏览器启动/关闭/连接失败 |
| `ConfigurationException` | FrameworkException | 配置项缺失或格式错误 |
| `ElementException` | FrameworkException | 元素操作异常基类 |
| `ElementNotFoundException` | ElementException | 元素未找到（TimeoutError 自动转换） |
| `ElementOperationException` | ElementException | 元素操作执行失败（PlaywrightException 自动转换） |
| `InitializationException` | FrameworkException | 框架初始化失败 |
| `NavigationException` | FrameworkException | 页面导航超时或失败 |
| `ScreenshotException` | FrameworkException | 截图保存失败 |
| `TimeoutException` | FrameworkException | 等待操作超时 |


## 技术亮点

1. **Playwright 替代 Selenium** — CDP 协议比 WebDriver 快 30-50%，内置自动等待机制，无需显式等待
2. **三层架构清晰** — Playwright → Browser → BrowserContext → Page，分层管理；Context 实例复用 + 状态深度清理，整个测试生命周期只保持 1 个浏览器窗口
3. **双等待策略** — 智能等待（Playwright 原生 waitFor）+ 轮询等待（isEnabled/isSelected 等属性检查），覆盖全部场景
4. **Route Engine 统一网络拦截** — Monitor/Mock/Modify/Delay 四种模式，流式 DSL，零阻塞 UI；Mock/Modify 字段替换自动类型保持；非主线程数据通过 `SerenityReporter` 队列自动刷入 Serenity 报告；优先级覆盖（MOCK > MODIFY > DELAY > MONITOR）支持同 pattern 动态升级
5. **企业级报告体系** — Serenity 详细报告 + SummaryReportGenerator 摘要报告（HTML/CSV/ZIP），12 类错误自动分类 + 用户可自定义扩展
6. **CI/CD 原生集成** — Jenkins Pipeline，环境变量自动解析（`${JENKINS_URL}`/`${JOB_NAME}`/`${BUILD_NUMBER}`），邮件链接自动构建
7. **无障碍合规** — 内置 Deque axe-core WCAG 自动扫描，`AxeCoreListener` 在每个 Scenario 结束后自动执行
8. **Session 跨场景复用** — `SessionManager` 将 Cookie/LocalStorage 持久化到 `target/.sessions/`，减少冗余登录
9. **BrowserStack 云测试** — CDP 协议连接远程浏览器，无需修改测试代码，`-Dbrowserstack.sessionName` 按场景设置会话名
10. **原生快照测试** — Playwright 原生 Visual Screenshot + ARIA Snapshot 比对，基线纳入 Git 版本控制
11. **线程安全 + 内存安全** — ConcurrentHashMap + WeakReference 防泄漏 + 双重上限防 OOM + AtomicLong + CopyOnWriteArrayList + `ConcurrentLinkedQueue` 跨线程报告队列 + byte[] 拷贝跨线程
12. **Element 框架优化** — `TextNormalizer` 统一文本标准化管道；`executeSafely` + `executeWithRetry` 双模板消除重复 try-catch；`ChildPageElement` 使用 `Locator.locator()` 链式定位；`SerenityBasePage` 从 87 个冗余 Override 精简为 2 个拦截器；`waitForCondition`（private）作为内部统一等待引擎，所有 `waitForXxx()` 公共方法底层均委托于它
13. **IntelliJ 日志即时输出** — Logback ConsoleAppender 输出到 `System.err`（绕过 IntelliJ `idea.test.cyclic.buffer` 缓冲区），确保 Serenity discovery 阶段日志即时显示。正常日志颜色可通过 `Settings → Editor → Color Scheme → Console Colors → Console → Error output` 调整
14. **JVM Shutdown Hook 资源清理** — `FrameworkCore` 注册 JVM 关闭钩子，确保 JVM 异常退出时 Playwright 资源被正确释放
15. **@AutoBrowser 零配置浏览器切换** — 注解驱动，堆栈跟踪自动发现 Glue 类，Scenario 标签匹配浏览器类型，ThreadLocal 缓存避免重复扫描


## 文档索引

| 文档 | 内容 |
|------|------|
| **[README.md](./README.md)** | 框架总览、架构设计、快速开始、全部配置项 |
| [ROUTE_PACKAGE_README.md](./ROUTE_PACKAGE_README.md) | Route Engine 完整 API 文档（Monitor/Mock/Modify/Delay） |
| [API_README.md](./API_README.md) | API 测试框架文档（Rest Assured + Serenity + HOCON 配置） |
| [PLAYWRIGHT_VS_SELENIUM.md](./PLAYWRIGHT_VS_SELENIUM.md) | Playwright vs Selenium 技术选型对比 |
| [Element.MD](./Element.MD) | 元素定位方法手册（CSS/XPath 优先级、PageElement/PageElementList/BasePage 完整速查表） |
| `route-demo-service/` | Spring Boot 演示服务（端口 8888），用于 Route Engine 测试 |


## 依赖速查

| 组件 | GroupId | Version | 说明 |
|------|---------|---------|------|
| Serenity BDD | net.serenity-bdd | 4.3.4 | serenity-model / serenity-core / serenity-cucumber / serenity-junit / serenity-screenplay / serenity-rest-assured |
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
