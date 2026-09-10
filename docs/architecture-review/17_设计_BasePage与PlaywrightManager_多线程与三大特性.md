# 设计：BasePage 与 PlaywrightManager 的多线程与 Java 三大特性（对齐 playwright-java 1.58.0）

> 来源：基于 `playwright-java-1.58.0` 真实源码 + 本框架 WEB-P0-2 / WEB-P1-1 / WEB-P1-2 现状的复盘设计
> 配套任务：`08_整改任务总表.md` 第二轮复核增量 `WEB-P1-N13`
> 状态：✅ 设计已落地（线程归属守卫 + ArchUnit impl 闸门 + 并发隔离单测 + 共享 Browser 模式 Context 隔离实浏览器 E2E），验证全绿

---

## 0. 源码证据（playwright-java 1.58.0 事实，非臆测）

| 事实 | 源码位置 | 设计含义 |
|---|---|---|
| `Playwright` 是**接口**，`create()` 启动独立 driver 子进程 + 独立 `Connection`（单管道） | `Playwright.java:42` `PlaywrightImpl.java:52` | 一个 `Playwright` ≈ 一个 driver 进程；**非线程安全**（单连接串线，对象态无锁） |
| 官方并行范式 = **`ThreadLocal<Playwright/Browser/BrowserContext/Page>`** + `PlaywrightRegistry` 收尾 `close()` | `impl/junit/PlaywrightExtension.java:33` `BrowserExtension.java:30` `BrowserContextExtension.java:33` `PageExtension.java:26` | 官方认可：**每个线程一套 Playwright 对象**；并发隔离靠 ThreadLocal |
| `Browser/Page/BrowserContext/Frame/Locator` **全部是接口** | `Browser.java:44` `Page.java:70` `BrowserContext.java:46` `Frame.java:63` `Locator.java:31` | **多态天然存在**：`BrowserType` chromium/firefox/webkit 即多态；框架应面向接口编程，不造轮子 |

> 结论：Playwright 对象**不可跨线程并发访问**，但 `Browser`/`Playwright` 在 `launch` 之后配置即不可变，可安全**共享只读**；可变的 `Context`/`Page`/`Locator`/`Frame` 必须 **per-thread 隔离**。

---

## 1. 线程模型（核心设计）

```
                 ┌──────────────── 进程级（共享、launch 后只读）────────────────┐
                 │  Playwright(接口)  →  Browser(接口)  →  BrowserType 多态     │
                 │  PlaywrightManager 门面 + PlaywrightRuntimeState(单态)       │
                 └─────────────────────────────────────────────────────────────┘
                                      │ 仅 newContext() 需加锁（写共享 Browser）
                                      ▼
   Thread-A ── ContextKey(A) ──► BrowserContext_A ──► Page_A ──► BasePage实例A → PageContextState_A
   Thread-B ── ContextKey(B) ──► BrowserContext_B ──► Page_B ──► BasePage实例B → PageContextState_B
        （Page/Locator/Frame 绝不跨线程；BasePage 实例与 scenario 线程 1:1 绑定）
```

**两条不可违背的不变式（已写入 `PageContextState` + `BasePage` Javadoc + ArchUnit 闸门）：**

1. **单写者规则**：`page`/`context`/`frame 栈` 只由「拥有该 BasePage 的 scenario 线程」写入；只读的 `PageEventMonitor`/`RouteEngine` 监听器**绝不**回写 BasePage 状态（已通过 `PageEventMonitor` 与 BasePage 解耦实现；已核实 `PageEventMonitor` 不调用 `ensurePageValid/ensureContextValid`）。
2. **创建串行化**：跨线程 `browser.newContext()`/`newPage()` 须经 `pageSwitchLockFor(ContextKey)` 锁（已实现 per-context 隔离锁），否则 `BrowserImpl` 内部状态并发写会损坏。

> 与官方差异：官方用「每线程一个 Playwright（各自 driver）」，本框架用「共享一个 Playwright/Browser + per-thread Context」。**本框架方案更省资源且已落地**，仅需在 `PlaywrightManager` Javadoc 显式载明取舍与「Browser 仅做只读共享」前提。

### 1.1 新增：线程归属守卫（已落地）

`PageContextState` 记录首次使用状态机的线程（`ownerThread`），在 `ensurePageValid()`/`ensureContextValid()` 入口断言当前线程一致，否则抛语义化 `IllegalStateException`（把"跨线程共享 Page 导致 pipe closed"类诡异失败转为清晰异常）。

- 守卫是 **per-instance** 的（`ownerThread` 是 `PageContextState` 字段），两个独立 BasePage 实例分别由各自线程使用互不误伤。
- 该守卫已通过单测固化：`BasePageThreadOwnershipConcurrencyTest`（同线程通过 / 跨线程拒绝 / 不同实例并行通过）。

---

## 2. Java 三大特性映射

| 特性 | 判定 | 设计落点 |
|---|---|---|
| **封装 ✅** | 强 | `PlaywrightManager`=门面，全部可变状态收口 `PlaywrightRuntimeState`（单态 `final`）；`BasePage`=门面，per-instance 状态收口 `PageContextState`（`final` 引用）；跨线程容器用 `ConcurrentHashMap`+`AtomicBoolean`；日志路由回 `BasePage`/`PlaywrightManager` 保溯源 |
| **继承 ❌** | 刻意不用 | `BasePage` 不作文档继承根（无抽象子类层次）；协作者全部 `final`；**组合优于继承** |
| **多态 🔶** | 仅在确有必要时 | ① 复用 Playwright 自带接口（`BrowserType`/`Browser`/`Page`/`Locator`），不造轮子；② 唯一的自研接口 = DI seam `RuntimeProvider`/`BrowserProvider`/`ContextProvider`/`PageProvider`（确有「默认实现 vs 测试替身」双实现，已落地 WEB-P0-2）；③ 单实现的协作者（BrowserStartup 等）**不强行抽接口**，避免过度抽象（对齐 WEB-P1-6 复盘） |

---

## 3. `PlaywrightManager` 设计蓝图

**职责**：进程级生命周期门面 —— 持有唯一 `Playwright`/`Browser`，提供 per-thread `Context`/`Page` 的创建/回收，暴露 DI seam。

```
PlaywrightManager (门面, 363行)
 ├─ STATE : PlaywrightRuntimeState (单态, final)   // 唯一可变状态根
 ├─ RuntimeProvider seam (volatile, setProvider/getProvider)  // 多态测试入口
 └─ 委托协作者 (全部 final, 包级私有):
      BrowserStartup / BrowserRegistry / ContextRegistry / PageRegistry
      BrowserRestart / BrowserCleanup / ScenarioLifecycle
```

**线程安全要点：**
- `Playwright`/`Browser` 共享只读；`newContext()` 经 per-context 锁串行。
- 生命周期收口对齐官方 `PlaywrightRegistry`：在 Serenity `afterStory` 统一遍历 `STATE` 关闭；共享 Browser 模式下只关 Context、绝不关共享 Browser（重启降级为重建本线程 Context）。
- API 仅暴露接口（`getPage()`/`getContext()`/`getBrowser()` 返回 `Page`/`BrowserContext`/`Browser`），由 ArchUnit 闸门 `mustNotDependOnPlaywrightImpl` 固化（禁止 `*.impl.*` 依赖）。

---

## 4. `BasePage` 设计蓝图

**职责**：业务 Page 的**门面 + 状态持有者**。自身不含编排逻辑，全部经 `final` 协作者委托；per-instance 可变状态在 `PageContextState`。

```
BasePage (门面, 481行, abstract)
 ├─ protected volatile Page page;        // 仅 owning 线程写
 ├─ protected volatile BrowserContext context;
 ├─ final PageContextState pageContextState;  // 收口 iframe/shadow 栈、ensure 守卫、注解绑定
 ├─ 委托壳 → LocatorFactory / CookieManager / PageFrameShadow / PageViewport
 │           / PageAccessibility / PageInteractions / PageWaits / PageNavigation / PageLifecycleCoordinator
 └─ 调试 → PageDebugControl (final)
```

**线程安全要点：**
- `ensurePageValid`/`ensureContextValid` 入口加 `assertOwningThread()`（见 1.1）。
- 字段标注「仅 owning 线程写」文档（`@GuardedBy` 语义注释），固化单写者不变式。

---

## 5. 已落地增量（本轮实现）

| 增量 | 文件 | 内容 |
|---|---|---|
| ① 线程归属守卫 | `PageContextState.java` | 新增 `ownerThread` 字段 + `assertOwningThread()`，在 `ensurePageValid`/`ensureContextValid` 入口断言；跨线程访问抛 `IllegalStateException` |
| ② ArchUnit 接口闸门 | `ArchitectureTest.java` | 新增 `mustNotDependOnPlaywrightImpl()`：任何 framework 代码不得依赖 `com.microsoft.playwright.impl.*` |
| ③ 并发隔离单测 | `BasePageThreadOwnershipConcurrencyTest.java`（新增） | 同线程通过 / 跨线程拒绝 / 不同实例并行通过，无浏览器依赖（DI seam 注入 mock） |

### 5.1 验证结果

- 针对性测试（4 类 20 例）`BUILD SUCCESS`：`BasePageThreadOwnershipConcurrencyTest`(3) + `ArchitectureTest`(10，含 impl 闸门) + `BasePagePageSwitchLockConcurrencyTest`(5) + `PlaywrightManagerConcurrencyTest`(2)。
- 直接驱动 BasePage 的 mock 单测（9 类 69 例）`BUILD SUCCESS`：`BasePageSeamTest`/`BasePageAttributeTest`/`PageFrameShadowTest`/`PageLifecycleCoordinatorTest`/`PageNavigationTest`/`PageWaitsTest`/`WebRuntimeSeamTest`/`PageEventMonitorTest`/`PageElementA11yFilterTest`。
- 合计 **89 例全绿**，归属守卫未误伤正常单线程使用。
- 全框架编译通过（`ArchitectureTest` 会编译并扫描整个 framework 包，证明 impl 闸门通过、无编译回归）。

> 全护盾（基线 487 例，含浏览器 E2E）应在 CI（具备浏览器环境）跑通以确认零回归，符合"每次下沉后必跑全部测试类"铁律；本机已验证无浏览器依赖的子集。

---

## 6. 待强化 / 后续

### 6.1 已落地（本轮）

- ① **「Browser 只读共享」契约标注 + configId 防御**：在 `BrowserRegistry` 类级与 `realGetBrowser()` 入口以 Javadoc `@apiNote` 固化「共享 Browser 只读 + 可变操作持 `browserLock`」契约。
  **注：未引入 `@GuardedBy` 注解**——本仓 classpath 仅含 `error_prone_annotations`、无 jsr305/checkerframework，强引注解有编译风险，故用文档契约表达（零风险、等价可读）。
  并新增 `validateConfigIdShape(String)` 纯校验器，在 `realGetBrowser()` 入口对缺失 `'_'` 分隔符的非法 configId 抛 `IllegalArgumentException`（语义化异常，而非静默误判浏览器类型 / 裸 NPE）。
  配套单测 `BrowserRegistryConfigIdValidationTest`（3 例，无浏览器、同包白盒）。
- ③ **DI seam 防绕过 ArchUnit 闸门**：`ArchitectureTest.frameworkCodeMustNotMutateRuntimeSeam()` 禁止生产 framework 代码调用 `PlaywrightManager.setProvider`（扫描 framework 主代码、排除 tests），守住「门面 → provider → 协作者实现」替换链路（对齐 WEB-P1-6）。
- ② **并发隔离端到端（实浏览器 E2E）**：新增 `SharedBrowserContextIsolationE2E`（test-automation 同包白盒，仅 public API）。它经 `ConcurrentScenarioExecutor.prepareSharedBrowser()` 预热共享 Browser，两并发线程各取 `getPage()`，断言四条不变式：① 两线程拿到**不同** `BrowserContext` 实例；② 二者位于**同一个**共享 `Browser` 实例；③ 仅在 ctx1 写入的探测 Cookie 对 ctx2 不可见；④ 反向 ctx2→ctx1 同样隔离。自跳设计：`@BeforeClass` 经 `Assume.assumeTrue(PlaywrightManager.isSharedBrowserMode())` 优雅跳过，默认每线程独立 Browser 模型零侵入；CI 经 `shared-browser-e2e` Maven profile 在独立 fork 以 `serenity.playwright.shared.browser.enabled=true` 启动 JVM 真实跑通（已在本地 chromium 验证 `Tests run: 1, Failures: 0`，15.26s）。

### 6.2 待办清空

- 原「共享 Browser 模式下 Context 隔离」实浏览器 E2E 已于 2026-09-09 落地（见 6.1 ②），本小节待办已清零。
