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
| **日志** | SLF4J + Logback Classic 1.5.6 |


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
- **Session 跨 Scenario 复用**：通过 `SessionManager` 将 Cookie/LocalStorage 持久化到 `target/.sessions/` 目录。**缓存命中**时 `setStorageStatePath()` 预加载 Cookie，下一个 Scenario 跳过登录直达 homeUrl；**缓存未命中**时走完整登录流程，登录后自动 `saveSession()` 持久化，后续 Scenario 即可命中缓存。


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
│  │              │ │ 截图管理      │ │ 监听器体系        │    │
│  │              │ │ Screenshot   │ │ Listener         │    │
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
│   │   │   ├── PageElement.java         # 元素操作（企业级重试 + 失败诊断）
│   │   │   ├── PageElementList.java     # 元素列表（动态查询 + 多阶段等待）
│   │   │   ├── Element.java             # @Element 注解（仅 CSS / XPath）
│   │   │   ├── ElementDiagnosticsCollector.java  # 失败诊断收集器（批量 JS 单次 IPC）
│   │   │   ├── base/BasePage.java       # 基础页面类（99+ Playwright 操作 + element() 统一门面）
│   │   │   ├── base/impl/SerenityBasePage.java  # Serenity 集成（双拦截器 + element() 覆盖统一报告注入）
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
│   │   │   ├── ListenerRegistry.java   # 监听器注册
│   │   │   └── ThucydidesStepsListenerAdapter.java  # SPI 自动注册适配器
│   │   ├── accessibility/AxeCoreScanner.java  # WCAG 无障碍扫描
│   │   ├── screenshot/                 # 截图系统
│   │   │   ├── photographer/           # 截图摄影师
│   │   │   ├── processor/              # 截图处理器
│   │   │   ├── strategy/               # 截图策略
│   │   │   └── permission/             # 权限处理
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
│
└── 文档/
    ├── README.md                         # ★ 框架总览（本文件）
    ├── ROUTE_PACKAGE_README.md           # Route Engine 完整 API 文档
    ├── API_README.md                     # API 测试框架文档（Rest Assured）
    ├── PLAYWRIGHT_VS_SELENIUM.md         # Playwright vs Selenium 技术选型
    ├── PLAYWRIGHT_LISTENERS.md           # Playwright 监听器全景指南
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
| **统一重试** | 仅 `PageElement.executeWithRetry()` 一层重试，`clickWithRetry`/`typeWithRetry` 等不再额外包裹外层 retry |
| **智能 Pause** | `BasePage.pause()` 安全调试点：本地 IDE 自动打开 Playwright Inspector 暂停调试；Jenkins / BrowserStack 环境自动跳过，杜绝 CI/CD 流程阻塞 |

**核心类：**

| 类 | 职责 |
|----|------|
| `Element` | `@Element` 注解，仅 CSS / XPath 选择器，自动注入 `PageElement` / `PageElementList` |
| `PageElement` | 封装 Playwright Locator 完整操作；`executeSafely` + `executeWithRetry` 双模板；`ChildPageElement` 链式子定位；`getTextRaw()` 快速路径 |
| `PageElementList` | `AbstractList<PageElement>`；`waitForCount()` / `forEachSafe()` / 动态 size() 实时查询 DOM |
| `BasePage` | 99+ Playwright 操作 + `element(selector)` 统一门面；内部 `waitForCondition`（private）驱动所有 `waitForXxx()` 公共方法；`retryWithValidation` 操作级重试 |
| `SerenityBasePage` | Serenity 集成，覆盖 `element()` 统一注入报告记录；`record()` / `recordAndReturn()` 双拦截器简化代理方法 |
| `PageObjectFactory` | 页面对象工厂，支持单例/原型/线程隔离等生命周期策略 |


### 2. Route Engine — API 拦截引擎

通过流式 DSL 统一管理网络层拦截：**Monitor（监控断言）**、**Mock（模拟响应）**、**Modify（修改请求）**、**Delay（高延迟模拟）**。支持**优先级覆盖机制**——高优先级规则（MOCK > MODIFY > DELAY > MONITOR）可自动覆盖同 pattern 的低优先级规则。支持 **Page 与 BrowserContext 双层级注册**，跨层规则自动合并延迟配置。

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
- **Page/Context 双层级注册**：同一 API 可在 Page 和 BrowserContext 两层同时注册规则，跨层自动合并延迟配置（取两层最大延迟值）
- **跨层 Session 感知**：Context 层规则 Session 超时/auto-stop 后，跨层合并自动忽略已停止的 Context 规则，避免无效延迟被合并
- **同级规则更新**：同一层级对同一 API 重复注册同类型规则时（如监控超时后重新监控），旧 handler 自动 `unroute`、旧 session 停止、新配置生效
- **多维度匹配**：ResourceType、HTTP Method、Headers、Query、Body Regex、Referrer、Origin、Frame
- **MonitorSession 自动停止**：支持超时（`timeout`）、最小匹配次数（`minMatches`）+ `autoStopOnMatch` 双重自动停止机制
- **线程安全**：ConcurrentHashMap + AtomicLong + byte[] 拷贝跨线程，零阻塞 UI
- **内存安全**：WeakReference 防泄漏、双重上限防 OOM、防重集合、缓存淘汰、线程池限流
- **Serenity 报告集成**：Handler 非主线程数据通过 `SerenityReporter` 队列机制自动刷入 Serenity 报告
- **三层清理保障**：Scenario 结束自动 `clearContext()` → JVM 退出 `shutdown()` 关闭线程池 → 手动 `RouteDsl.clear()` 按需清理

**包结构（18个类，4个子包）：**

| 子包 | 类 | 职责 |
|------|-----|------|
| `core/` | `RouteEngine`、`RouteRegistry`、`RouteRule`、`RouteHandleType`、`RouteException`、`ApiCaptureContext`、`CapturedApiCall`、`RouteMonitor` | 路由引擎、注册表、数据模型、异常体系、MonitorSession 生命周期 |
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


### 5. BrowserStack — 云浏览器测试

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


### 6. SummaryReportGenerator — 摘要报告

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


## Playwright vs Selenium — 关键行为差异与迁移指南

> **核心区别**：Selenium 是**同步指令式**模型（send command → wait for response），Playwright 是**事件驱动式**模型（register listener → event triggers handler）。这种范式差异导致许多操作的**时序要求完全不同**，从 Selenium 迁移时最容易踩坑。

---

### 1. Alert / Dialog 处理（⚠️ 时序敏感）

| 维度 | Selenium | Playwright |
|------|----------|------------|
| **触发方式** | `driver.switchTo().alert().accept()` — **事后处理**，alert 弹出后再操作 | `page.onceDialog(Dialog::accept)` — **必须事前注册** |
| **时序要求** | 可以在 alert 弹出来后随时随地调用 | **必须在触发 alert 的代码之前注册监听器** |
| **fail 模式** | 不注册也可以，到需要时再处理 | 没有事前注册 → Dialog 自动被 Playwright 默认 dismiss → 后续代码拿不到 dialog |

**Playwright 正确写法：**

```java
// ✅ 正确：先注册监听器，再触发 alert
page.onceDialog(Dialog::accept);          // 第1步：事前注册
page.click("#delete-btn");                // 第2步：触发 alert → Playwright 自动调用 accept()

// ✅ 获取 dialog 信息
page.onceDialog(dialog -> {
    logger.info("Dialog message: {}", dialog.message());
    dialog.accept("input text");           // prompt 输入
});

// ❌ 迁移陷阱：Selenium 思维 — 先点击再处理
page.click("#delete-btn");               // alert 弹出来了！
page.onceDialog(Dialog::accept);         // 太晚了！已自动 dismiss
```

**框架封装（针对 Selenium 用户习惯做了适配）：**

```java
// BasePage 封装了 before-click 注册模式
basePage.acceptAlert();       // 等价于 page.onceDialog(Dialog::accept);
basePage.dismissAlert();      // 等价于 page.onceDialog(Dialog::dismiss);

// 使用方式 — 先调用 acceptAlert()，再点击触发按钮
basePage.acceptAlert();
basePage.click("#delete-btn");
```

---

### 2. 新页面/新 Tab 处理（⚠️ 时序敏感）

| 维度 | Selenium | Playwright |
|------|----------|------------|
| **获取窗口** | `driver.getWindowHandles()` — **事后查询**，随时获取句柄列表 | `context.waitForPage(action)` — **事前监听**，在浏览器事件级捕获 |
| **切换方式** | `driver.switchTo().window(handle)` — 按句柄切换 | `switchToPage(index)` / `switchToPage(page)` / `switchToNewPage(trigger)` |
| **时序问题** | 通过 `getWindowHandles().size()` 轮询，可能与页面打开时机产生竞态 | `context.waitForPage()` 在 CDP 层面拦截新页面事件，无误判 |

**Playwright 正确写法：**

```java
// ✅ 方式1：原子操作 — 点击 + 等待新页面（推荐）
Page newPage = basePage.switchToNewPage(
    () -> basePage.click("#link-that-opens-new-tab"), 15);

// ✅ 方式2：手动 waitForPopup + switchToPage
Page popup = page.waitForPopup(() -> page.click("#link"));
basePage.switchToPage(popup);

// ✅ 方式3：已触发新 tab，只需等待 + 切换
basePage.switchToNewPage(15);  // 快速路径检查 → 事件监听兜底

// ❌ 迁移陷阱 — Selenium 思维：先点击，再 getWindowHandles 轮询
page.click("#link");                          // 新 tab 已打开
for (int i = 0; i < 10; i++) {               // 轮询等待
    if (context.pages().size() > 1) break;
    page.waitForTimeout(500);
}
// ⚠️ 问题：如果在 click() 返回之前新页面已经打开，
//    context.pages().size() 在点击前保存的 snapshot 会永远是旧的
```

**关键原理：**

```
Selenium 模型（基于 WebDriver 协议，轮询查询）：
  getWindowHandles() → WebDriver 命令 → Browser → 返回句柄集合
  问题：页面在命令间隙打开时，下次查询才能发现

Playwright 模型（基于 CDP 事件，推送通知）：
  context.waitForPage() → 注册 CDP Target.targetCreated 监听 → 事件到达立即回调
  优势：Browser 创建新 Target 的瞬间 Playwright 就收到了
```

---

### 3. iframe 切换

| 维度 | Selenium | Playwright |
|------|----------|------------|
| **切换方式** | `driver.switchTo().frame(index/name/WebElement)` | `page.frame(name)` / `switchToFrame(nameOrSelector)` / `switchToFrame(index)` |
| **导航后** | 自动回到 defaultContent（取决于实现） | **Frame 对象会变为 detached**，必须重置 |
| **回主页面** | `driver.switchTo().defaultContent()` | `switchToDefaultContent()` |

**Playwright 注意事项：**

```java
// ✅ 进入 iframe
basePage.switchToFrame("myFrame");         // name/id
basePage.switchToFrame("iframe.embedded"); // CSS selector
basePage.switchToFrame(1);                 // index

// 在 iframe 内操作 — locator() 自动适配
basePage.click("#button-inside-iframe");
basePage.type("#input-inside-iframe", "text");

// ✅ 退出 iframe
basePage.switchToDefaultContent();

// ⚠️ 页面导航后 Frame 自动 detached
// navigateTo / refresh / back / forward 后框架自动重置 iframe 上下文
// 不需要手动 switchToDefaultContent，但如果有跨导航的 iframe 操作需注意
```

**与 Selenium 的关键差异：**
- Playwright 的 `Frame` 对象是**引用**，页面导航后变为 detached，需要重新获取
- 框架通过 `ThreadLocal<Frame>` + 导航后自动重置，对业务代码透明

---

### 4. 元素自动等待（Playwright 独有）

| 维度 | Selenium | Playwright |
|------|----------|------------|
| **点击前检查** | 需手动 `WebDriverWait` + `ExpectedConditions` | **自动检查**：attached → visible → stable → enabled → receives events |
| **输入前检查** | 需手动等待 | **自动等待**元素可编辑 |
| **选择器解析** | 即时（By.id/cssSelector/xpath 等） | Locator 延迟求值，每次操作重新查询 DOM |

**Selenium 需要这样写：**

```java
WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(10));
wait.until(ExpectedConditions.elementToBeClickable(By.id("#btn")));
wait.until(ExpectedConditions.visibilityOfElementLocated(By.id("#btn")));
driver.findElement(By.id("#btn")).click();
```

**Playwright 只需要：**

```java
page.click("#btn");  // 自动等待所有条件满足
```

**Playwright 自动等待的 5 个阶段：**
1. **Attached** — 元素是否在 DOM 中
2. **Visible** — 元素是否可见（非 `display:none` / `visibility:hidden`）
3. **Stable** — 元素是否稳定（非动画中）
4. **Enabled** — 元素是否可交互（非 `disabled`）
5. **Actionable** — 元素是否能接收事件（未被遮挡、在视口内）

---

### 5. 下载文件处理

| 维度 | Selenium | Playwright |
|------|----------|------------|
| **下载方式** | 配置浏览器 Profile 禁止下载弹窗，设置默认下载目录 | `page.onDownload()` **事件监听** |
| **时序** | 通过轮询文件系统检测下载完成 | Playwright 通过 CDP 事件通知下载完成 |

**Playwright 自动下载（框架内置）：**

```java
// 框架在 createPage 时自动注册 onDownload 监听
// 下载文件自动保存到 playwright.browser.downloadsPath 目录（默认 target/downloads）
// 无需手动处理

// 点击下载按钮
basePage.click("#download-btn");
// 文件自动保存，日志输出：Download completed: file.xlsx -> target/downloads/file.xlsx
```

---

### 6. 浏览器上下文隔离（BrowserContext）

| 维度 | Selenium | Playwright |
|------|----------|------------|
| **隔离级别** | 每个 `WebDriver` 实例一个浏览器窗口，Cookie 共享 | `BrowserContext` 完全隔离 Cookie/LocalStorage/Session |
| **多用户登录** | 需要启动多个浏览器实例 | 同一 Browser 创建多个 Context，**零开销** |
| **会话管理** | 需手动管理 Cookie 文件 | `context.storageState()` 保存 / `setStorageStatePath()` 恢复 |

**Playwright 的 Context 模型优势：**

```java
// Selenium 思维：两个登录用户需要两个 Driver → 两个浏览器窗口
WebDriver driver1 = new ChromeDriver();  // 窗口 1
WebDriver driver2 = new ChromeDriver();  // 窗口 2

// Playwright 方式：两个 Context → 共享一个浏览器进程
BrowserContext ctx1 = browser.newContext();  // 用户 A 的隔离会话
BrowserContext ctx2 = browser.newContext();  // 用户 B 的隔离会话
// ctx1 和 ctx2 的 Cookie/Storage 完全隔离，但属于同一浏览器进程
```

**本框架的 Session 复用机制（区分缓存命中/未命中）：**

| 场景 | 行为 | 说明 |
|------|------|------|
| **缓存命中** | `setStorageStatePath(sessionPath)` → Context 创建时预载 Cookie/LocalStorage → **跳过登录**直达 homeUrl | session `.json` + `.meta` 文件存在且未过期 |
| **缓存未命中** | `restoreSession()` 返回 false → 业务层执行**完整登录**流程 → 登录后 `saveSession()` 将 `context.storageState()` 序列化到 `target/.sessions/` | 文件缺失或超过 60 分钟过期 |

- **Scenario 模式** — 缓存命中时：新 Context 直接加载持久化 Cookie，无需 UI 交互；未命中时：执行登录后自动保存
- **Feature 模式** — 同一 Feature 内首个 Scenario 恢复 Session 后，后续 Scenario 通过 `markFeatureSessionRestored()` 标记跳过重复恢复

---

### 7. 文件上传

| 维度 | Selenium | Playwright |
|------|----------|------------|
| **方式** | `element.sendKeys(filePath)` — 模拟键盘输入路径 | `locator.setInputFiles(paths)` — **直接调用 CDP 设置文件** |
| **行为** | 依赖浏览器原生文件选择对话框 | 绕过对话框，直接注入文件 |

```java
// Selenium
driver.findElement(By.id("upload")).sendKeys("/path/to/file.pdf");

// Playwright
basePage.setInputFiles("#upload", "/path/to/file.pdf");
// 支持多文件
basePage.setInputFiles("#upload", "/path/to/file1.pdf", "/path/to/file2.pdf");
```

---

### 8. 全页截图（Full Page Screenshot）

| 维度 | Selenium | Playwright |
|------|----------|------------|
| **全页截图** | 需第三方库（aShot、Selenium 4 有 `getFullPageScreenshotAs()` 但不稳定） | **原生支持** `setFullPage(true)` |
| **懒加载** | 需手动滚动触底触发懒加载 | 框架内置截图前稳定化：滚动触底 → 等待渲染 → 滚回顶部 |

```java
// Playwright 全页截图
page.screenshot(new Page.ScreenshotOptions()
    .setFullPage(true)              // 自动滚动拼接
    .setAnimations(ScreenshotAnimations.DISABLED));
```

---

### 9. 资源生命周期管理

| 维度 | Selenium | Playwright |
|------|----------|------------|
| **资源层级** | WebDriver → Window/Tab | Playwright → Browser → BrowserContext → Page |
| **关闭顺序** | `driver.quit()` 一步关闭 | **必须按层级关闭**：Page → Context → Browser → Playwright |
| **残留风险** | `driver.quit()` 超时/异常时可能残留浏览器进程 | JVM Shutdown Hook 兜底，确保进程被杀死 |

**Playwright 关闭顺序（不可颠倒）：**

```java
// ✅ 正确顺序
page.close();                    // 1. 关闭页面
context.close();                 // 2. 关闭上下文
browser.close();                 // 3. 关闭浏览器
playwright.close();              // 4. 关闭 Playwright

// ❌ 错误：跨层关闭会导致异常
browser.close();                 // Context/Page 还在引用 Browser → 异常
```

**框架自动管理：**
- `FrameworkCore` 注册 JVM Shutdown Hook → `cleanupAll()` 按正确顺序关闭
- `cleanupForScenario()` / `cleanupForFeature()` 正确管理 Scenario/Feature 边界

---

### 10. 网络拦截（Route / Mock）

| 维度 | Selenium | Playwright |
|------|----------|------------|
| **拦截方式** | 需 BrowserMob-Proxy 或 Selenium Wire（代理层） | **原生 CDP 层面拦截**，零代理开销 |
| **请求修改** | 代理 filter，配置复杂 | `route.fulfill()` / `route.abort()` / `route.resume()` |
| **延迟模拟** | 需手动 Thread.sleep() | `route.wait(ms)` 原生支持 |
| **时序问题** | Proxy 在请求链路中，可能引入额外延迟 | CDP 层面同步拦截，无时序差异 |

```java
// Selenium + BrowserMob（复杂）
proxy.addRequestFilter((request, contents, messageInfo) -> {
    if (request.getUrl().contains("/api/users")) {
        return new Response(200, headers, "{\"data\":[]}");
    }
    return null;
});

// Playwright（原生，简单）
context.route("**/api/users", route -> {
    route.fulfill(new Route.FulfillOptions()
        .setStatus(200)
        .setBody("{\"data\":[]}"));
});
```

**本框架 Route Engine** 在原生 API 上进一步封装，提供了 Monitor/Mock/Modify/Delay 四种模式 + 流式 DSL + 优先级覆盖 + 跨层自动合并。

---

### 11. 键盘操作

| 维度 | Selenium | Playwright |
|------|----------|------------|
| **全局按键** | `Actions().keyDown()` 链式调用 | `page.keyboard().down(key)` + `page.keyboard().up(key)` |
| **元素级按键** | `element.sendKeys(Keys.ENTER)` | `locator.press("Enter")` |
| **逐字输入** | `sendKeys("text")` | `locator.pressSequentially("text")` — **逐个字符按下** |

```java
// Selenium
element.sendKeys(Keys.chord(Keys.CONTROL, "a"));

// Playwright
basePage.click("#input");              // 聚焦
basePage.getPage().keyboard().press("Control+a");  // Ctrl+A 全选

// 逐字输入（模拟真实用户输入）
basePage.element("#input").type("hello");  // 底层: pressSequentially("hello")
```

---

### 总结速查表

| 场景 | Selenium 方式 | Playwright 方式 | 时序风险 |
|------|--------------|----------------|---------|
| **Alert** | 事后 `switchTo().alert().accept()` | 事前 `onceDialog(Dialog::accept)` | ⚠️ 高 — 必须先注册再触发 |
| **新 Tab** | 事后 `getWindowHandles()` 轮询 | 事前 `waitForPage(action)` | ⚠️ 高 — 必须事件级捕获 |
| **iframe** | `switchTo().frame()` | `page.frame()` + ThreadLocal 管理 | ⚠️ 中 — 导航后 Frame detached |
| **点击** | 手动 `WebDriverWait` | 自动等待 5 个阶段 | ✅ 低 — Playwright 更可靠 |
| **下载** | 配置 Profile | `onDownload()` 事件 | ✅ 低 — 框架自动处理 |
| **隔离** | 多 Driver 实例 | BrowserContext | ✅ 低 — Context 零开销 |
| **全页截图** | aShot 第三方库 | 原生 `setFullPage(true)` | ✅ 低 |
| **网络拦截** | BrowserMob-Proxy | 原生 `route()` API | ✅ 低 — CDP 层面 |
| **文件上传** | `sendKeys(path)` | `setInputFiles(paths)` | ✅ 低 |
| **资源关闭** | `driver.quit()` | Page → Context → Browser → Playwright | ⚠️ 中 — 顺序不可颠倒 |

> **关键结论**：从 Selenium 迁移到 Playwright 时，最需要注意的是 Alert 和新 Tab 两个场景——它们从"事后处理"变成了"事前注册"，这是最容易出错的点。本框架的 `BasePage.acceptAlert()` / `dismissAlert()` 和 `switchToNewPage(action, timeout)` 已经对这两种场景做了面向 Selenium 用户习惯的封装。


## 技术亮点

1. **Playwright 替代 Selenium** — CDP 协议比 WebDriver 快 30-50%，内置自动等待机制，无需显式等待
2. **三层架构清晰** — Playwright → Browser → BrowserContext → Page，分层管理；Context 实例复用 + 状态深度清理，整个测试生命周期只保持 1 个浏览器窗口
3. **双等待策略** — 智能等待（Playwright 原生 waitFor）+ 轮询等待（isEnabled/isSelected 等属性检查），覆盖全部场景
4. **Route Engine 统一网络拦截** — Monitor/Mock/Modify/Delay 四种模式，流式 DSL，零阻塞 UI；Mock/Modify 字段替换自动类型保持；非主线程数据通过 `SerenityReporter` 队列自动刷入 Serenity 报告；优先级覆盖（MOCK > MODIFY > DELAY > MONITOR）支持同 pattern 动态升级；Page/Context 双层级注册 + 跨层延迟合并 + Session 状态感知；同级规则更新（unroute → 停旧 session → 注册新配置）；自动清理（Scenario 结束 / JVM 退出 / 手动 clear）
5. **企业级报告体系** — Serenity 详细报告 + SummaryReportGenerator 摘要报告（HTML/CSV/ZIP），12 类错误自动分类 + 用户可自定义扩展
6. **CI/CD 原生集成** — Jenkins Pipeline，环境变量自动解析（`${JENKINS_URL}`/`${JOB_NAME}`/`${BUILD_NUMBER}`），邮件链接自动构建
7. **无障碍合规** — 内置 Deque axe-core WCAG 自动扫描，`AxeCoreListener` 在每个 Scenario 结束后自动执行
8. **Session 跨场景复用** — `SessionManager` 缓存命中时跳过登录直达 homeUrl，未命中时自动保存登录态供后续使用
9. **BrowserStack 云测试** — CDP 协议连接远程浏览器，无需修改测试代码，`-Dbrowserstack.sessionName` 按场景设置会话名
10. **线程安全 + 内存安全** — ConcurrentHashMap + WeakReference 防泄漏 + 双重上限防 OOM + AtomicLong + CopyOnWriteArrayList + `ConcurrentLinkedQueue` 跨线程报告队列 + byte[] 拷贝跨线程
11. **Element 框架优化** — `TextNormalizer` 统一文本标准化管道；`executeSafely` + `executeWithRetry` 双模板消除重复 try-catch；`ChildPageElement` 使用 `Locator.locator()` 链式定位；`element(selector)` 统一门面替代分散的 `new PageElement(selector, this)`；统一重试策略，仅保留 `PageElement.executeWithRetry()` 一层
12. **IntelliJ 日志即时输出** — Logback ConsoleAppender 输出到 `System.err`（绕过 IntelliJ `idea.test.cyclic.buffer` 缓冲区），确保 Serenity discovery 阶段日志即时显示。正常日志颜色可通过 `Settings → Editor → Color Scheme → Console Colors → Console → Error output` 调整
13. **JVM Shutdown Hook 资源清理** — `FrameworkCore` 注册 JVM 关闭钩子，确保 JVM 异常退出时 Playwright 资源被正确释放
14. **@AutoBrowser 零配置浏览器切换** — 注解驱动，堆栈跟踪自动发现 Glue 类，Scenario 标签匹配浏览器类型，ThreadLocal 缓存避免重复扫描


## 文档索引

| 文档 | 内容 |
|------|------|
| **[README.md](./README.md)** | 框架总览、架构设计、Playwright vs Selenium 关键行为差异、快速开始、全部配置项 |
| [ROUTE_PACKAGE_README.md](./ROUTE_PACKAGE_README.md) | Route Engine 完整 API 文档（Monitor/Mock/Modify/Delay） |
| [API_README.md](./API_README.md) | API 测试框架文档（Rest Assured + Serenity + HOCON 配置） |
| [PLAYWRIGHT_VS_SELENIUM.md](./PLAYWRIGHT_VS_SELENIUM.md) | Playwright vs Selenium 技术选型对比 |
| [PLAYWRIGHT_LISTENERS.md](./PLAYWRIGHT_LISTENERS.md) | Playwright `on*`/`once*` 监听器全景指南（事件驱动模型、全部监听器用途与最佳实践） |
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
