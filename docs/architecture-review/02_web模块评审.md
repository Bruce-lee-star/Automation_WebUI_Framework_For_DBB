# 模块评审 02｜`framework-web`

> 定位：Playwright 生命周期 + 页面对象模型 + 监听器 + 云测集成
> 规模：90 Java 文件 / **22,718 行**（占全项目 40%）/ 自有单元测试 **3 个**
> 依赖：`framework-core`、`framework-api`、`framework-reporting`、Guava
> **模块总评：2.6 / 5.0（体量最大，质量负担最重）**

---

## 一、模块规模分布

| 分层 | 主要类 | 行数 | 占比 |
|---|---|---:|---:|
| lifecycle | `PlaywrightManager`(1490) `PlaywrightContextManager`(502) `PlaywrightInitializer`(513) `PlaywrightConfigManager`(514) `PlaywrightScreenshotManager`(332) `PlaywrightSerenityBridge`(429) `BrowserCrashGuard`(195) `ContextLifecycleHookManager`(384) `ProxyConfigResolver`(384) `ConcurrentContextExecutor`(193) | 4,936 | 22% |
| page | `BasePage`(1429) `SerenityBasePage`(369) `PageElement`(966) `PageObjectFactory`(569) `ElementDiagnosticsCollector`(394) `RoleElementBinder`(276) `PageElementList`(279) `PageLifecycleCoordinator`(294) `PageFrameShadow`(201) `Element`/`RoleElement`/`ElementRect`/`ElementOperationSupport` | 4,922 | 22% |
| config | `FrameworkConfig`(1473) `FrameworkConfigManager`(290) `BrowserOverrideManager`(369) `AutoBrowserProcessor`(322) | 2,454 | 11% |
| listener | `PlaywrightListener`(1232) `ThucydidesStepsListenerAdapter`(760) `ListenerRegistry`(569) `AxeCoreListener`(277) `StepFailureAggregator`(164) `FailureScreenshotHandler` `ListenerGuard` `ListenerPerfStats` | 3,100+ | 14% |
| cloud | `BrowserStackManager`(819) `BrowserStackLocalManager`(314) `BrowserStrategy`/`LocalBrowserStrategy`/`CloudBrowserStrategy` | 1,200+ | 5% |
| session / utils / concurrent / accessibility / exceptions / annotations | `SessionManager`(845) `AxeCoreScanner`(525) `NLSUtils`(408) `ConcurrencyGate`(173) 等 | 4,500+ | 20% |

**规模诊断**：22,718 行 / 90 类的模块，单元测试 3 个。这是**全项目质量风险最集中的地方**——任何一次重构都缺乏安全网。

---

## 二、八维度逐项分析

### D1 模块边界与依赖治理 —— 3.0 / 5 ⚠️

**优点 W-1：依赖方向正确**
web → api → core，web → reporting → core，无环。ArchUnit 的 L1/L2 规则保障 api 与 common 不反向依赖 web。

**问题 W-2（中）：被 route 反向依赖，形成"逻辑上层依赖物理下层"的错配**
`route` 模块（Maven 上层）import 了 web 的 `FrameworkConfig` / `FrameworkConfigManager` / `HikariConfigFactory`（`route/persistence/DatabaseStoreMonitorCallback.java:3-4`、`ApiMonitoringRepository.java:154`）。这意味着 web 实际承担了"全局配置提供者"角色，其内部配置改动会波及 route 的持久化行为。

**问题 W-3（中）：web 依赖 api 的合理性存疑**
`web/pom.xml` 声明依赖 `framework-api`。Web UI 测试框架依赖 HTTP 测试框架，名义上是为了复用 `SensitiveDataSanitizer` 等工具类，但**该工具类已在 T1-1 上提至 core 的 `common.security`**（见 `ArchitectureTest.java:19` 注释）。**依赖理由可能已经消失，但依赖仍在**——属于未清理的历史遗留。

---

### D2 抽象设计与扩展性 —— 2.5 / 5 ❌

**问题 W-4（严重）：四个千行级上帝类**

| 类 | 行数 | 承担职责数 |
|---|---:|---|
| `PlaywrightManager` | 1,490 | Playwright 实例 / Browser / Context / Page 生命周期 + 浏览器类型切换 + 崩溃重启 + 截图 + 自定义选项 + 配置门面 + 共享模式判定 + 锁管理 |
| `FrameworkConfig` | 1,473 | 128 个配置项枚举 + 解析 + 类型转换 + 校验 + 系统属性写入 |
| `BasePage` | 1,429 | 等待 / 断言 / 重试 / 导航 / iframe / shadow DOM / cookie / 截图 / `byRole`×10 重载 / dumpAccessibility |
| `PlaywrightListener` | 1,232 | Serenity 事件适配 + 步骤生命周期 + 截图触发 + 失败聚合 + 性能统计 |

**判定**：`BasePage` 虽已把实现委派给 `PageWaits` / `PageNavigation` / `PageFrameShadow` / `PageLifecycleCoordinator`（`BasePage.java:279-297, 423-457, 505-594`），但**全部职责仍在单一公开 API 面暴露**（约 100+ 公开方法）。委派只是减少了单文件行数，**没有减少认知负担与误用面**——这不是真正的职责分离。

**问题 W-5（严重）：全静态工具类，零依赖注入**

`PlaywrightManager`（全 static + 静态 `ConcurrentHashMap`）、`FrameworkCore`（饿汉单例，私有构造）、`FrameworkState`（单例）、`SessionManager`、`ConcurrencyGate`（私有构造）、`BrowserCrashGuard`（私有构造）——**没有任何 seam 可供替换或 mock**。

这直接导致 `web/src/test` 只能写 3 个"架构门禁"测试，无法对 `BasePage` / `PlaywrightManager` / 监听器做任何行为验证。**不是团队不想写测试，是设计上无法写**。

**问题 W-6（中）：PageObject 初始化依赖运行时反射**

```java
// PageObjectFactory.java:337
pageClass.getDeclaredConstructor().newInstance();
// BasePage.java:189-238 反射扫描 @RoleElement / @Element 字段
```
- 无注解处理器（APT）、无编译期生成；
- 初始化失败信息晦涩、调试栈深；
- `BasePage` 构造即触发 `FrameworkCore.initialize()`（`BasePage.java:99-104`）——**POJO 构造产生全局副作用**，违反最小惊讶原则。

**问题 W-7（中）：`executeWithRetry` 名不副实**

`ElementOperationSupport.executeWithRetry`（82-116 行）实际是**单次执行 + 诊断采集**，**无重试循环**。真正的重试在 `PageWaits.retryWithValidation`，但轮询间隔、次数上限等策略散落多处，无集中配置。命名与行为不符是维护陷阱。

---

### D3 并发与线程安全 —— 2.0 / 5 ❌ **本模块最严重问题**

**问题 W-8（P0/严重）：共享 Browser 模式锁使用错误 —— 已识别未修复**

```java
// PlaywrightManager.java:82（类注释，正确方案）
// 共享模式下必须使用进程级 SHARED_BROWSER_LOCK，per-thread 锁无法跨线程互斥

// PlaywrightManager.java:632-634（已提供 mode-aware 实现）
static Object browserLock(boolean sharedMode) { ... }

// PlaywrightManager.java:690  ← 实际调用
synchronized (perThreadBrowserLock()) { ... }     // ✗ 用了 per-thread 锁
// PlaywrightManager.java:742  ← 实际调用
synchronized (perThreadBrowserLock()) { ... }     // ✗ 同样错误
```

共享模式下 Browser 以 `"shared:<configId>"` 为键被**所有线程共用**（59-60、85 行），但加锁却用每线程各自独立的锁对象。后果：
- 两线程**同时**通过锁检查 → 双发射 `initializeBrowser()`；
- 旧 Browser 实例泄漏、Playwright 子进程未回收；
- 浏览器类型切换（`handleBrowserTypeSwitch`）同样受影响。

**这是全项目最危险的缺陷**：注释里写明了正确方案、代码里也提供了正确实现，但调用点未采用。属于"知道答案却没改"的遗留缺陷。

**问题 W-9（中）：全局 `CONTEXT_LOCK` 串行化所有 Context 创建**

```java
// PlaywrightManager.java:88-89
private static final Object CONTEXT_LOCK = new Object();
// :812
synchronized (CONTEXT_LOCK) { ... }
```
即便 `BrowserContext` 本身是 per-thread 的，所有 worker 线程仍在此**类级锁上串行**。并发扩展的直接瓶颈，与框架宣称的并发能力矛盾。

**问题 W-10（中）：`ListenerRegistry` 非线程安全的共享可变状态**

- `listenerClasses` 为裸 `HashSet`（第 26 行）；
- `initialized` 标志非 `volatile`（第 27 行）；
- 虽被 `synchronized` 方法保护，但 `getListenerClasses()` 返回视图在并发调用边界仍脆弱。

**问题 W-11（中）：静态可变状态清单（重构风险源）**

`PlaywrightManager.java` 第 59（`playwrightInstances`）、60（`browserInstances`）、64（`DISCONNECTED_BROWSERS`）、82（`SHARED_BROWSER_LOCK`）、88（`CONTEXT_LOCK`）、89（`PAGE_LOCK`）、94（`RETIRED_CONFIG_IDS`）、97（`frameworkState`）、100（`FULL_INIT`）行——**9 处静态可变状态集中在单个 1490 行类中**。任何并发问题的排查都要在这 9 个状态间推理。

**优点 W-12：`ConcurrencyGate` 设计合理**

`ConcurrencyGate`（173 行）与 `ConcurrentScenarioExecutor` 的闸门/分区机制经核查**无死锁与饥饿风险**（`ConcurrencyGate.java:38,87-117`），`MAX_GATES=4096` 有上界。这是本模块并发设计中亮点。

---

### D4 生命周期与资源治理 —— 3.0 / 5

**优点 W-13：`BrowserCrashGuard` 恢复有界**
重跑严格限 1 次 + 进程级单飞锁（`BrowserCrashGuard.java:34-37, 172`），不会无限循环重启。

**问题 W-14（中）：`isCrash()` 判定过宽，可能掩盖真实缺陷**

```java
// BrowserCrashGuard.java:108-118
// 类名含 "playwrightexception" 即判为崩溃 → 触发 replay
```
正常的 Playwright 超时、元素未找到等**业务性异常**也会被判定为"浏览器崩溃"并重跑，导致：
- 真实的产品缺陷被"重跑一次就好了"掩盖；
- 测试结果的**假绿**（flaky 重试掩盖失败）。

在金融级测试体系中，"自动重试"必须有严格的**白名单**（仅限明确的崩溃信号，如 `Target crashed` / `Browser closed`），不能用类名模糊匹配。

**问题 W-15（中）：`cleanupAll()` 的危险性已被识别但设计未根本改善**

`FrameworkCore.handleException` 注释（300-310 行）警告：`cleanupAll()` 会关闭**所有线程**的 Browser，并行场景下单个 scenario 异常会关停其余并发场景的浏览器。当前靠"约定不调用"规避——**靠注释约束而非类型/设计约束**，风险仍在。

**问题 W-16（中）：监听器发现机制脆弱（非 SPI）**

`ListenerRegistry` 对包下**每一个** `.class` 执行 `Class.forName`（128、156、418、445 行），而非 `ServiceLoader` / `META-INF/services`。任一不可加载类即抛异常 → `initialize()` 整体失败 → **所有监听器注册失败**（63-66 行），无优雅降级。

对比 `codegen` 模块已正确使用 `ServiceLoader` SPI——**同项目内标准不一致**。

---

### D5 配置与多环境 —— 3.0 / 5 ⚠️

**优点 W-17：`FrameworkConfig` 枚举自文档化——设计优秀（但被规模拖累）**

```java
SERENITY_PROJECT_NAME("serenity.project.name", "Serenity Playwright Demo", "项目名称")
```
key + 默认值 + 中文描述三元组，**128 个配置项集中在单一枚举**。这是典型的企业级配置治理手法，优于散落的 `getProperty("...")` 魔法字符串。

**问题 W-18（严重）：与 api 模块同名 `FrameworkConfig` 双份并存**

| | web | api |
|---|---|---|
| 类型 | `enum`（128 项） | `class`（静态 getter） |
| 配置源 | Serenity + System 属性 + 环境变量 | Typesafe Config `application.conf` |
| 全限定名 | `...framework.**web**.config.FrameworkConfig` | `...framework.**api**.config.FrameworkConfig` |

两处 Javadoc 均标注"⚠️ 同名类消歧（P3-31）……刻意不合并"。**决策本身可以理解**（避免 api↔web 配置层耦合），但代价是：
- 使用者需靠全限定名或 static import 别名区分，**可读性损失**；
- 配置治理口径分裂（一边是枚举自文档，一边是 `hasPath` + 魔法 key）；
- 新增配置时"该放哪边"无明确规则。

**问题 W-19（中）：全局可变配置写入**

`FrameworkConfig.setValue()` 本质是 `System.setProperty`（`FrameworkConfig.java:1429,1139`）——运行期可修改全局配置。测试间会相互影响，且 `ConfigSource` 的实时 System 属性优先策略使该写入**立即全局生效**。

**问题 W-20（中）：硬编码魔法值散落**

`setDelay(100)`（`PageElement.java:215`）、`MAX_GATES=4096`（`ConcurrencyGate.java:36`）、`backoff 2000*attempt`（`PlaywrightManager.java:342`）等，未纳入 `FrameworkConfig` 统一治理。

---

### D6 错误处理与可观测性 —— 3.0 / 5

**优点 W-21：诊断能力强**
`ElementDiagnosticsCollector`（394 行）专门采集元素失败上下文；`PageFrameShadow` 支持 `>>>` 穿透与事件驱动的 `switchToFrameAndWait`；`VerboseLogging` 提供分级详细日志。

**问题 W-22（中）：异常层次齐全但使用不一致**
`exceptions` 包定义了 10 个异常类（`BrowserException` / `ConfigurationException` / `ElementNotFoundException` / `ElementOperationException` / `FrameworkException` / `InitializationException` / `NavigationException` / `ScreenshotException` / `TimeoutException` / `ElementException`），层次设计合理。但 `TimeoutException` 与 `java.util.concurrent.TimeoutException` 同名——**易误导入**。

**问题 W-23（中）：诊断路径吞异常**
`ElementOperationSupport.captureDiagnosticsAndLog` 的 `catch (Exception ignored)`（第 68 行）静默吞掉诊断失败。诊断系统自身的失败不可见，等于"黑匣子里的黑匣子"。

**问题 W-24（轻）：`@SuppressWarnings` 20 处 / 11 文件**
多为反射与泛型强转抑制，掩盖潜在类型安全问题。

---

### D7 安全与合规 —— 3.5 / 5 ✅

**优点 W-25：零硬编码凭据（已全量 grep 验证）**
全模块 grep `password|secret|token|apiKey|accessKey = "..."` 无命中。`BROWSERSTACK_ACCESS_KEY` 默认值为空串（`FrameworkConfig.java:255-262`），仅从环境变量/系统属性读取（`BrowserStackManager.java:589-597`）。

**优点 W-26：云测日志脱敏到位**
`BrowserStackManager.java:688` 对 `accessKey / apiKey / password / token / secret` 做正则脱敏后再输出日志。

**优点 W-27：无障碍测试企业级集成**
`AxeCoreScanner`（525 行）直接使用 Deque 官方 `axe-core` 库（`AxeCoreScanner.java:3-6`），非自造轮子；配合 `AxeCoreListener` 融入 Serenity 生命周期。

---

### D8 可测试性与质量门禁 —— 1.5 / 5 ❌ **全项目最低分**

**问题 W-28（P0/严重）：22,718 行代码，3 个测试文件**

`web/src/test` 仅有：
- `CodegenDecouplingArchTest`（架构门禁）
- `SerenityBusBridgeTest`
- `TestContextBridgeTest`

**没有任何**覆盖 `BasePage` / `PageElement` / `PlaywrightManager` / 监听器业务逻辑的单元测试。而 `test-automation` 中虽有约 30 个 web 相关测试类，但它们位于**独立模块**，无法验证 web 模块内部实现的正确性。

**根因**：W-5（全静态、零 DI）+ W-6（构造即触发全局初始化）。**这是设计缺陷的直接后果，不是测试投入不足**。

**问题 W-29（中）：Checkstyle 门禁对本模块几乎无效**
根 POM 的 checkstyle `<includes>` 限定到 `**/framework/web/page/base/**` + `ElementDiagnosticsCollector.java`，且 `checkstyle-suppressions.xml` 还**豁免了 BasePage 的 FileLength**（该规则唯一真正想约束的对象）。

---

## 三、问题清单

| ID | 级别 | 问题 | 证据 |
|---|---|---|---|
| W-8 | **P0** | 共享 Browser 模式用错锁（per-thread 而非进程级），并发双发射 + 泄漏 | `PlaywrightManager.java:690,742` vs `:82,632-634` |
| W-28 | **P0** | 22,718 行 / 3 个测试，零业务逻辑覆盖 | `web/src/test` 目录 |
| W-4 | **P1** | 四个千行上帝类（1490/1473/1429/1232） | 见规模分布表 |
| W-5 | **P1** | 全静态、零 DI，设计上不可测 | `PlaywrightManager` / `FrameworkCore` / `ConcurrencyGate` |
| W-14 | **P1** | `isCrash()` 模糊匹配致误重试，掩盖真实缺陷 | `BrowserCrashGuard.java:108-118` |
| W-16 | **P1** | 监听器发现靠全量 `Class.forName`，单点失败全盘失败 | `ListenerRegistry.java:128,156,418,445,63-66` |
| W-18 | **P1** | 与 api 模块同名 `FrameworkConfig` 双份 | 两模块 Javadoc P3-31 |
| W-9 | **P1** | 全局 `CONTEXT_LOCK` 串行化所有 Context 创建 | `PlaywrightManager.java:88,812` |
| W-2 | **P1** | 被 route 反向依赖，承担全局配置提供者角色 | `route/.../DatabaseStoreMonitorCallback.java:3-4` |
| W-6 | **P2** | 反射初始化 PageObject，无 APT | `PageObjectFactory.java:337` |
| W-7 | **P2** | `executeWithRetry` 名不副实（无重试） | `ElementOperationSupport.java:82-116` |
| W-19 | **P2** | `setValue()` 全局 System 属性写入 | `FrameworkConfig.java:1429,1139` |
| W-23 | **P2** | 诊断路径吞异常 | `ElementOperationSupport.java:68` |
| W-20 | **P2** | 魔法值未纳入配置治理 | `PageElement.java:215` 等 |
| W-3 | **P2** | 依赖 api 的历史理由可能已消失 | `web/pom.xml` |
| W-22 | **P2** | `TimeoutException` 与 JDK 同名易误导入 | `exceptions/TimeoutException.java` |

---

## 四、整改任务列表（web 模块）

### P0 —— 阻断级

| 任务 ID | 任务 | 验收标准 | 工时 |
|---|---|---|---:|
| **WEB-P0-1** | **修复共享 Browser 锁**：`getBrowser()`(:690) 与 `handleBrowserTypeSwitch()`(:742) 改用已存在的 `browserLock(boolean)` mode-aware 实现 | ① 两个调用点不再使用 `perThreadBrowserLock()`；② 新增并发单测：N 线程并发获取共享 Browser，断言 `browserInstances` 中该 key 只被创建一次；③ BrowserStack 共享模式跑通 | 2d |
| **WEB-P0-2** | **建立可测试性 seam（DI 改造一期）**：抽出 `BrowserProvider` / `ContextProvider` / `PageProvider` 接口，`PlaywrightManager` 静态门面保留并委托给可替换的默认实现；`BasePage` 构造不再触发全局 `FrameworkCore.initialize()` | ① 可通过 `setProvider()` 注入 mock；② 至少 `PageWaits`、`PageNavigation`、`PageLifecycleCoordinator`、`ConcurrencyGate` 可脱离真实浏览器单测；③ 现有 API 100% 向后兼容 | 8d |

### P1 —— 重要

| 任务 ID | 任务 | 验收标准 | 工时 |
|---|---|---|---:|
| **WEB-P1-1** | **拆分 `PlaywrightManager`（1490 行）**：按 `BrowserRegistry` / `ContextRegistry` / `PageRegistry` / `BrowserLifecycle` 拆分为 ≤ 400 行/类；静态状态收敛到单一 `PlaywrightRuntimeState` 受管对象 | ① 单文件 ≤ 400 行；② 静态可变字段 ≤ 1 处（受管状态容器）；③ 行为回归测试通过 | 10d |
| **WEB-P1-2** | **拆分 `BasePage`（1429 行）**：按能力面拆出 `PageAssertions` / `PageCookies` / `PageAccessibility` / `LocatorFactory`（收拢 `byRole`×10），`BasePage` 保留组合门面 | ① `BasePage` ≤ 500 行；② 公开方法数 ≤ 40；③ ArchUnit 的 `businessCodeMustNotUseInternalByLocators` 规则仍通过 | 8d |
| **WEB-P1-3** | **监听器注册改 SPI**：改用 `ServiceLoader` + `META-INF/services`；`Class.forName` 扫描降级为可选回退且单类失败仅跳过该类 | ① 单个坏类不影响其他监听器；② 有失败告警日志；③ 对齐 codegen 模块的 SPI 做法 | 3d |
| **WEB-P1-4** | **收窄崩溃重跑白名单**：`isCrash()` 改为匹配明确崩溃信号（`Target crashed` / `Browser has been closed` / `Connection closed`），异常类名模糊匹配移除；重跑事件必须在报告中显式标注 | ① 元素超时/断言失败**不触发**重跑；② 重跑事件在 Serenity 报告可见 | 2d |
| **WEB-P1-5** | **补充 web 单元测试（一期）**：优先覆盖 `PageWaits` / `PageNavigation` / `PageFrameShadow` / `ConcurrencyGate` / `NLSUtils` / `SensitiveDataSanitizer` 集成；目标行覆盖 ≥ 25% | ① `web/src/test` ≥ 25 个测试类；② JaCoCo 行覆盖 ≥ 25% | 10d |
| **WEB-P1-6** | **解除全局 `CONTEXT_LOCK` 串行**：改为按 `configId` 分段锁（`ConcurrentHashMap.computeIfAbsent` + per-key lock），不同 configId 的 Context 创建可并行 | ① 并发创建不同 configId 的 Context 无互斥；② 压测 TPS 提升可量化 | 3d |
| **WEB-P1-7** | **配置体系收敛**：`FrameworkConfig`(web) 与 `FrameworkConfig`(api) 至少完成"命名与口径统一"——统一为 `WebFrameworkConfig` / `ApiFrameworkConfig`，并抽出共享的 `ConfigKey` 元数据规范 | ① 无同名类；② 两侧配置项均有 key/默认/描述三元组 | 3d | ✅ 已落地（2026-09-10，详见 22_整改专项设计_WEB-P1-7_配置体系收敛.md） |

### P2 —— 优化

| 任务 ID | 任务 | 验收标准 | 工时 |
|---|---|---|---:|
| **WEB-P2-1** | 引入 APT 或编译期生成替代运行时反射初始化 PageObject | 初始化失败在编译期或启动期明确报错 | 5d |
| **WEB-P2-2** | 修复 `executeWithRetry` 命名；集中轮询/重试策略为可配 `RetryPolicy` | 命名与行为一致；策略可配 | 2d |
| **WEB-P2-3** | 魔法值收敛：所有硬编码超时/上限纳入 `FrameworkConfig` | grep 无散落魔法数字 | 2d |
| **WEB-P2-4** | 诊断系统自愈：诊断失败的异常不再被吞，降级输出到日志 | 有诊断失败告警 | 1d |
| **WEB-P2-5** | 评估并移除 `web → api` 依赖（若仅剩工具类引用，相关类已在 core） | `web/pom.xml` 无 `framework-api`；构建通过 | 2d |
| **WEB-P2-6** | `TimeoutException` 重命名为 `FrameworkTimeoutException` 避免与 JDK 混淆 | 重命名完成，无编译错误 | 1d |

---

## 五、给架构决策者的建议

`web` 模块的核心矛盾是：**它是全项目能力的重心（40% 代码），却是质量保障的洼地（3 个测试）**。

这个组合意味着：每一次框架改动都是在**没有安全网的情况下高空作业**。团队显然意识到了问题——从 `BasePage` 拆分、`TestContext` 收拢、`VerboseLogging` 统一等痕迹看，重构一直在推进。但当前的**债务增速仍高于偿还速度**：

- 拆出了 `PageWaits` / `PageNavigation`，但 `BasePage` 仍有 1429 行和 100+ 公开方法；
- Checklist 只覆盖 2 个包，而新增的 21 个 codegen 类、51 个 route 类完全不在门禁内；
- `PlaywrightManager` 从"管理 Playwright"膨胀为"管理一切"。

**建议优先级**：先做 **WEB-P0-2（可测试性 seam）**，再谈任何拆分。没有 seam 的情况下拆上帝类，等于在空中换引擎——拆分本身会引入新缺陷，而没有测试能发现它们。
