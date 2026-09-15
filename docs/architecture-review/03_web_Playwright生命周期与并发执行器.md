# 03 web 模块：Playwright 生命周期与并发执行器

> 评审范围：`web/.../lifecycle/**`、`web/.../concurrent/`、`web/.../screenshot/`、`web/.../listener/`
> 评审基线：`e11a847`

---

## 一、模块职责

这是整套框架的**心脏**，管理 Playwright 四层对象（Playwright → Browser → BrowserContext → Page）的创建、持有、复用、崩溃恢复与销毁，并把状态桥接进 Serenity 报告。

| 子包 | 职责 | 关键类 |
|---|---|---|
| `lifecycle/bootstrap` | 初始化编排 | `PlaywrightInitializer`、`PlaywrightContextManager` |
| `lifecycle/browser` | Browser 注册/启动/清理/崩溃守卫/重启 | `BrowserRegistry(Impl)`、`BrowserStartup(Impl)`、`BrowserCleanup(Impl)`、`BrowserCrashGuard`、`BrowserRestart(Impl)` |
| `lifecycle/context` | Context 注册与自定义选项 | `ContextRegistry(Impl)`、`CustomOptions(Manager)` |
| `lifecycle/page` | Page 注册 | `PageRegistry(Impl)` |
| `lifecycle/concurrent` | 并发执行器 | `ConcurrentContextExecutor`、`ConcurrentContextOptions`、`ContextTask` |
| `lifecycle/lock` | 锁中介 | `LifecycleLockMediator` |
| `lifecycle/state` | 运行时状态根 | `PlaywrightRuntimeState` |
| `lifecycle/serenity` | Serenity 桥接 | `PlaywrightSerenityBridge`、`SerenityBusBridge`、`TestContextBridge` |
| `lifecycle/media` | 截图 | `PlaywrightScreenshotManager` |
| `listener` | 事件监听（SPI 注册） | `PlaywrightListener`、`ThucydidesStepsListenerAdapter` |

---

## 二、现状评估

### 2.1 持有关系（实测）

```
PlaywrightRuntimeState (静态根)
  ├── playwrightInstances : ConcurrentHashMap      key = threadId:configId
  ├── browserInstances    : ConcurrentHashMap      key = threadId:configId | shared:configId
  └── (WeakHashMap 同步集)

TestContextHolder (per-thread)
  ├── CONTEXT_KEY → BrowserContext                 (PlaywrightManager.java:137)
  └── PAGE_KEY    → Page                           (PlaywrightManager.java:138)
```

**关键设计**：Playwright / Browser 由中心 Map 持有，Context / Page 挂在线程绑定的 `TestContextHolder` 上。`BrowserRegistry` / `ContextRegistry` / `PageRegistry` 是无状态门面单例。

这个分层是合理的——Browser 是重资源要复用，Context 是隔离单元要跟随用例。

### 2.2 作用域模式

- **每线程独立 Browser（唯一模型）**：`BrowserRegistryImpl.keyFor:88-93` 生成 `threadId:configId`，每个 worker 线程拿到各自独立的 Browser 实例（N 并行 = N 线程 = N Browser）。

> **变更（DEV-V3 收尾）**：原「共享 Browser 模式」（`shared:configId` 单 Browser + 每线程 Context，由 `serenity.playwright.shared.browser.enabled` 开启）**已从框架彻底移除**——含配置项 `PLAYWRIGHT_SHARED_BROWSER_ENABLED`、`PlaywrightManager.enableSharedBrowserMode` / `isSharedBrowserMode`、`keyFor` 的 `shared:` 分支。根因：Playwright for Java 非线程安全，共享单 Browser 跨线程并发会损坏客户端对象注册表（`__adopt__` / `pausedStateChanged`）。并发统一为每线程独立 Browser，隔离性由每线程各自的 `BrowserContext` 保证；资源开销较大但线程安全。

### 2.3 并发执行器

`ConcurrentContextExecutor.runAll:70-121` 支持 `ThreadPoolExecutor` 或虚拟线程：

| 参数 | 环境变量 | 默认值 / 上限 |
|---|---|---|
| 并发度 | `PLAYWRIGHT_CONCURRENT_PARALLELISM` | 默认 4，**硬上限 16**（`ConcurrentContextOptions.java:12,56-62`） |
| 任务超时 | `PLAYWRIGHT_CONCURRENT_TASK_TIMEOUT_SECONDS` | — |
| 虚拟线程 | `PLAYWRIGHT_CONCURRENT_USE_VIRTUAL_THREADS` | — |

`ContextTask` 每个任务独立线程 + 独立 Context。设计清晰。

**但这里是全框架最大的认知错位**：这个执行器只能被"显式调用它的用例"使用（`parallel_logon_dbb.feature`、`concurrent_logon_dbb.feature`），**Cucumber 引擎本身完全串行**（无 `junit-platform.properties`，failsafe `forkCount=1`）。也就是说——框架"具备并发能力"，但"默认不并发"。详见 10 号文档。

硬编码上限 16 写在代码里而非配置，跨机型（CI 机 2 核 vs 本地 16 核）无法自适应。

### 2.4 线程安全

实例表全部用并发容器（`ConcurrentHashMap` / `newKeySet` / `AtomicBoolean`），锁集中在 `LifecycleLockMediator`（`SHARED_BROWSER_LOCK` / `CONTEXT_LOCK` / `PAGE_LOCK` / per-thread `BROWSER_LOCK`）。

**锁顺序有明确纪律**：`PAGE_LOCK → CONTEXT_LOCK`（`PageRegistryImpl.java:89-97`），且 `BrowserRegistryImpl.handleBrowserTypeSwitch:212-219` 刻意在 Browser 锁之外先 closePage/closeContext 以避免嵌套。`BrowserCrashGuard` 另起独立的 `REBUILD_LOCK`（`BrowserCrashGuard.java:67`）。

**结论：锁设计是专业的，没发现死锁风险。** 这是很难得的——多数自研并发框架在这一层都有嵌套锁问题。

### 2.5 崩溃恢复

`BrowserCrashGuard` 按**异常消息签名**匹配（非类名，`CRASH_SIGNATURES:86-97`，`isCrash:144-159`），识别后在 `REBUILD_LOCK` 内单飞重建，`MAX_REPLAY=1:70` 最多重跑一次。共享模式走 `restartContextOnly:210-228`（只关 Page/Context 保 Browser），非共享走完整重启。

`PageEventMonitor` 监听 `onPageError` / `onConsoleMessage` / `onRequestFailed` / `onCrash`。

**缺口**：崩溃重建后**登录态 / Cookie / storageState 不会自动恢复**，除非 Feature 模式保留的 storageState 由 `CustomOptionsManager` 回填。对 DBB 这类强登录态系统，崩溃后重跑基本必然失败——恢复机制只恢复了浏览器，没恢复业务会话。

### 2.6 资源清理（明确缺陷）

- Scenario 级：`ScenarioLifecycle.cleanupForScenario:68-78` → `PlaywrightSerenityBridge.cleanupForScenario:382-410`，按 restart 策略关 Page/Context。
- 套件级：`PlaywrightListener.testSuiteFinished:921` → `cleanupForFeature`；`PlaywrightManager.cleanupAll:508-515`（并发模式下抛 `IllegalStateException` 防误关，用心很好）。
- **JVM 级：`cleanupAll` 未登记进 `ShutdownCoordinator`**。`ShutdownCoordinator` 确实有 JVM 钩子（`ShutdownCoordinator.java:88-98`），但只注册了 trace 线程池关闭（`PlaywrightContextManager.java:90`）与 `FrameworkCore:43`。

**后果**：CI 上 job 被 kill、本地调试强停、或 `System.exit` 会留下孤儿 Chromium/Firefox 进程。在 CI runner 反复执行场景下会累积到内存耗尽。

### 2.7 Trace 与截图

**Trace（好消息）**：已采集并归档。`enableTracing:485-492` 在 `isTraceEnabled` 时 `tracing.start()`，`closeContext:261-283` 在 `PLAYWRIGHT_CONTEXT_TRACE_ENABLED` 下 stop 并写 `target/traces/trace-<ts>.zip`，带 15 秒超时异步执行避免阻塞关闭。

**但 trace.zip 没有与 Serenity 报告关联**——报告里只有截图，排查时得自己去 `target/traces` 翻文件，且文件名只有时间戳，**没有 scenario 名，无法定位是哪个用例的 trace**。这在实际排障中几乎等于不可用。

**截图**：`PlaywrightScreenshotManager.takeScreenshot:252-280`，SHA-256 唯一名，PNG 落 `target/site/serenity`。时机由 `ScreenshotStrategy` 控制（`serenity.properties:25` 当前为 `AFTER_EACH_STEP`）。全页截图失败会降级重试视口、短超时再试，最终返回 null 不抛（`captureScreenshot:339-433`）——**兜底做得好**。

问题：
- `AFTER_EACH_STEP` 意味着**每个步骤都截全页图**。DBB 这种页面复杂的应用，单用例可能几十张全页 PNG，报告体积与生成耗时都会失控，且拖慢执行。
- **截图无脱敏、无掩码**。登录页、账户信息页的截图会把真实数据原样留在报告里，而报告会被推送到外部仓库（见 11 号文档）。这是**数据泄露面**。

### 2.8 监听器注册

全部走 SPI：`META-INF/services/net.thucydides.model.steps.StepListener`、`...FrameworkListener`、`...ConfigResolver`。`ThucydidesStepsListenerAdapter` 同时实现 `StepListener` 与 `FrameworkListener`，委派给 `PlaywrightListener` + `AxeCoreListener`。**没有用 JUnit5 的 `@BeforeAll/@AfterAll`**，语义完全由 Serenity 回调驱动。

`PlaywrightListener` 仍残留 4 个原始 ThreadLocal（`CURRENT_SCENARIO_NAME` 等，`:135-141`），已在 `fireAfterScenario` 的 finally 中清除（`:382-386`）——清理了，但没统一到 `TestContextHolder`，属于历史遗留。

### 2.9 硬编码清单

| 位置 | 硬编码值 |
|---|---|
| `ConcurrentContextOptions.java:12,56` | 并发硬上限 16 |
| `ConcurrentContextOptions.java:65` | 默认并发 4 |
| `PlaywrightContextManager.java:272` | trace stop 超时 15s |
| `PlaywrightContextManager.java:109` | 池等待 30s |
| `PlaywrightContextManager.java:226-232` | Firefox 超时 45000 / 1.5x |
| `PlaywrightContextManager.java:268` | 退避 2000ms |

---

## 三、优势

1. **Browser / Context 分层持有**：重资源复用、隔离单元随用例，模型正确。
2. **锁顺序有明确纪律并写进代码**，主动规避嵌套死锁——自研框架中的高水准。
3. **崩溃守卫用单飞 + 重放上限**，避免了崩溃风暴下的反复重建。
4. **trace 已采集**，且 stop 带超时不阻塞关闭，说明考虑过真实故障场景。
5. **截图三重兜底**（全页 → 视口 → null 不抛），不会因截图失败掩盖真实用例失败。
6. **SPI 注册监听器**，无侵入，可扩展。
7. `cleanupAll` 在并发模式下主动抛 `IllegalStateException` 防误关——**防御性设计意识强**。

---

## 四、风险与问题

| 编号 | 级别 | 问题 | 证据 | 影响 |
|---|---|---|---|---|
| W-1 | **P0** | `cleanupAll` 未登记进 `ShutdownCoordinator`，硬杀留下孤儿浏览器进程 | `ShutdownCoordinator` 仅注册 trace 池与 `FrameworkCore` | CI runner 进程堆积、内存耗尽 |
| W-2 | **P0** | 截图**无脱敏/无掩码**，且 `AFTER_EACH_STEP` 全量截图；报告被推送外部仓库 | `PlaywrightScreenshotManager:252-280`、`serenity.properties:25` | 敏感数据随报告外泄 + 报告体积与耗时失控 |
| W-3 | **P0** | trace.zip 只用时间戳命名、**未与 scenario 关联**、未挂进 Serenity 报告 | `PlaywrightContextManager:261-283` | trace 实际不可用，排障仍靠猜 |
| W-4 | **P1** | 崩溃重建后**不恢复登录态/storageState** | `BrowserRestartImpl:88-198`、`:210-228` | 强登录态系统崩溃重跑必然失败 |
| W-5 | **P1** | 框架具备并发能力但**引擎层完全串行**，并发仅 2 个专用 feature 可用 | 无 `junit-platform.properties`，failsafe `forkCount=1` | 并发能力名存实亡，套件总耗时无改善 |
| W-6 | **P1** | 并发度**硬编码上限 16** | `ConcurrentContextOptions.java:12,56` | 无法按机型自适应 |
| W-7 | **P1** | **已闭环：共享 Browser 模式已从框架彻底移除**（非仅默认 false）—— 含配置项 `PLAYWRIGHT_SHARED_BROWSER_ENABLED`、`enableSharedBrowserMode` / `isSharedBrowserMode`、`keyFor` 的 `shared:` 分支全部删除；并发统一为每线程独立 Browser（`keyFor` 恒为 `threadId:configId`）。根因：Playwright for Java 非线程安全，共享单 Browser 跨线程并发损坏对象注册表 | `BrowserRegistryImpl.keyFor:88-93`（恒 `threadId:configId`）、`ConcurrentScenarioExecutor.prepareConcurrentEnvironment`（原 `prepareSharedBrowser`） | 不再有共享模式 opt-in；`CucumberConcurrentLogonRunnerIT` 现按每线程独立 Browser 模型运行 |
| W-8 | **P2** | **已修复**：`PlaywrightListener` 裸 ThreadLocal 已全部收敛到 `TestContextHolder` | `PlaywrightListener.java`（原 :53,:135-141；现 5 个 ContextKey） | 与 core 上下文双轨、清理路径分裂问题已消除 |
| W-9 | **P2** | 大量硬编码超时/退避常量散落 | 见 2.9 表 | 调参需改代码重新打包 |
| W-10 | **P2** | 崩溃识别依赖**异常消息签名**匹配 | `BrowserCrashGuard:86-97,144-159` | Playwright 升级改文案即失效 |

---

## 五、优化方案

### 5.1 把 cleanupAll 注册进关闭编排（P0）

```java
// web 模块启动处（FrameworkCore 或 PlaywrightInitializer 静态块）
ShutdownCoordinator.register(ShutdownCoordinator.ORDER_BROWSER /* 400 */, () -> {
    try {
        PlaywrightManager.cleanupAll();   // 内部需容忍并发模式下的 IllegalStateException
    } catch (IllegalStateException concurrentModeGuard) {
        LOGGER.debug("并发模式跳过 cleanupAll：{}", concurrentModeGuard.getMessage());
    } catch (Throwable t) {
        LOGGER.warn("[LIFECYCLE] 关闭期清理浏览器失败", t);
    }
});
```

注意 `PlaywrightManager.cleanupAll:508-515` 在并发模式下会主动抛 `IllegalStateException`，注册时必须吞掉这个特例，否则关闭期会刷异常栈。

同时在 CI 上加一道兜底（见 11 号文档）：job 结束时 `pkill -f chrome|firefox` 或 taskkill。

### 5.2 截图策略与脱敏（P0）

两步走：

**第一步，改截图策略**。`AFTER_EACH_STEP` 改为按需：

```properties
# serenity.properties
# 由 AFTER_EACH_STEP 改为仅失败截图（BEFORE_AND_AFTER_EACH_STEP 也过重）
serenity.screenshot.strategy = AFTER_FAILING_STEP
# 全页截图改为视口截图，失败时才尝试全页
playwright.screenshot.fullpage.on.failure = true
playwright.screenshot.max.height = 4000
```

**第二步，截图脱敏**。在 `PlaywrightScreenshotManager` 写入前走一层遮罩——最稳妥的做法是在截图时对已知敏感区域（通过 `@RoleElement(sensitive = true)` 标记）加 CSS 遮罩，而不是事后做图像处理：

```java
// 截图前注入遮罩样式，截完移除
private byte[] captureWithMasking(Page page, Set<String> sensitiveSelectors) {
    if (sensitiveSelectors.isEmpty()) {
        return page.screenshot(new Page.ScreenshotOptions());
    }
    String css = sensitiveSelectors.stream()
        .map(s -> s + " { filter: blur(8px) !important; }")
        .collect(Collectors.joining("\n"));
    Locator style = page.locator("head");
    style.evaluate("(el, css) => { "
        + "const s = document.createElement('style'); s.id='__mask__'; s.textContent = css;"
        + "el.appendChild(s); }", css);
    try {
        return page.screenshot(new Page.ScreenshotOptions());
    } finally {
        page.evaluate("() => document.getElementById('__mask__')?.remove()");
    }
}
```

### 5.3 Trace 与 Scenario 关联并挂进报告（P0）

```java
// PlaywrightContextManager.closeContext 改造
private void stopTracing(BrowserContext context, String scenarioId) {
    if (!isTraceEnabled()) return;
    Path traceDir = Path.of("target", "site", "serenity", "traces");
    Files.createDirectories(traceDir);
    // 关键：文件名带 scenarioId，并在报告里可点击
    Path traceFile = traceDir.resolve(sanitize(scenarioId) + ".zip");
    try {
        context.tracing().stop(new Tracing.StopOptions().setPath(traceFile));
        // 挂进 Serenity：作为该 scenario 的附件/链接
        StepEventBus.getEventBus().addAttachmentToCurrentStep(
            "Playwright Trace", traceFile.toFile());
    } catch (Exception e) {
        LOGGER.warn("[TRACE] 停止 trace 失败 scenario={}", scenarioId, e);
    }
}
```

文件名用 `scenarioId` 而非时间戳；同时在汇总报告模板（见 07 号文档）里增加"Trace 下载"列。

### 5.4 崩溃后恢复登录态（P1）

关键是把 storageState 纳入"可恢复资源"：

```java
// 在每次成功登录后落盘 storageState（按 scenario + user 维度）
public final class SessionVault {
    public static void save(String scenarioId, BrowserContext ctx) {
        Path p = Path.of("target", "session", scenarioId + ".state.json");
        Files.writeString(p, ctx.storageState());
    }
    public static Optional<String> load(String scenarioId) { /* 读取 */ }
    public static void evict(String scenarioId) { /* 用例结束后删除 */ }
}

// BrowserRestartImpl 重建后：
public void restartAndRestore(String scenarioId) {
    restartBrowser();
    SessionVault.load(scenarioId).ifPresent(stateJson ->
        newContext.setStorageState(stateJson));
}
```

### 5.5 并发度自适应（P1，共享 Browser 模式已移除）

```java
// ConcurrentContextOptions.defaults()
int available = Runtime.getRuntime().availableProcessors();
int parallelism = Integer.getInteger("PLAYWRIGHT_CONCURRENT_PARALLELISM",
                      Math.max(2, Math.min(available / 2, 16)));   // 上限可配
int hardCap = Integer.getInteger("PLAYWRIGHT_CONCURRENT_MAX", 32);  // 不再硬编码
```

> **变更（DEV-V3 收尾）**：原「默认改为共享 Browser + 每线程 Context（`serenity.playwright.shared.browser.enabled=true`）」的建议**已作废**——共享 Browser 模式已从框架彻底移除（见 W-7 / 10 号文档 §5.5）。并发默认即**每线程独立 Browser**（N 进程），资源开销见 X-7；如需降低浏览器进程数，应通过单 worker 串行多用例或虚拟线程（9.4）而非共享 Browser。这需要在 10 号文档开启引擎级并行后同步做压测验证。

### 5.6 崩溃识别改为结构化信号（P2）

消息签名匹配脆弱，改为"消息签名 + 类型 + Playwright 事件"三重判定，并提供外部可配置的签名列表：

```hocon
playwright.crash.signatures = [
  "Target crashed", "Browser has been closed", "Target closed",
  "Protocol error", "__adopt__", "previewUpdated"
]
```

---

## 六、结论

这一层是**全框架工程水准最高的部分**：分层持有模型正确、锁顺序有纪律、崩溃守卫有单飞与重放上限、截图有三重兜底、并发模式下主动防御误关。能看出团队在真实故障里打磨过。

但有三个必须修的洞：**W-1 关闭不清理（孤儿进程）、W-2 截图不脱敏（数据外泄）、W-3 trace 不可定位（排障失效）**。其中 W-2 在金融场景下是合规问题，优先级应高于一切性能与体验优化。
