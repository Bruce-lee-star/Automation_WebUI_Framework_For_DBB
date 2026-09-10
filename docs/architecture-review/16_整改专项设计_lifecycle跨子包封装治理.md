# 16 整改专项设计：lifecycle 跨子包封装治理

> 状态：**Phase 0 ✅ + Phase 1 ✅ + Phase 2 ✅ + Phase 3 ✅ + Phase 4 ✅ + 包层级重组 ✅ 已落地**（全护盾 507 例绿、0 失败/0 错误/0 跳过、BUILD SUCCESS）；**Phase 5（JPMS 限定导出）经用户决策不做**——原目标实为 `web/lifecycle` 包的层级结构，已由「按职责拆分子包」达成（见 §11.5），JPMS 维持 doc「可选/北极星」定位但非本专项验收项。
> 落地明细：§7（Phase 0/1）、§8（Phase 2 状态根接口化）、§9（Phase 3 锁中介）、§10（Phase 4 CustomOptionsManager 内部面收敛）、§11（收尾与 Phase 5 路线）
> 关联：14_整改专项设计_WEB-P1-1_拆分PlaywrightManager.md、15_整改专项设计_WEB-P1-6_DI二期_实例与接口化.md
> 触发根因：doc14「同包编译期封装」与「物理子包拆分」本质冲突 → 跨子包 `package-private` 失效 → 编译失败；上一轮「升 `public` + `@apiNote`」为临时止血，需企业级收口。

---

## 0. 通读结论：现状与根因

### 0.1 包结构（父包 + 7 子包，协作者已分散）

| 包 | 关键类 |
|---|---|
| `lifecycle`（父） | `PlaywrightManager`(门面)、`PlaywrightRuntime`(组合根)、`PlaywrightRuntimeState`(状态根)、`BrowserLifecycle`(组合门面) |
| `lifecycle.browser` | `BrowserRegistryImpl`/`BrowserStartupImpl`/`BrowserRestartImpl`/`BrowserCleanupImpl`/`BrowserCrashGuard` |
| `lifecycle.context` | `ContextRegistryImpl`/`CustomOptionsManager`(`implements CustomOptions`) |
| `lifecycle.page` | `PageRegistryImpl` |
| `lifecycle.scenario` | `ScenarioLifecycle` |
| `lifecycle.serenity` | `PlaywrightSerenityBridge`/`TestContextBridge`/`SerenityBusBridge` |
| `lifecycle.bootstrap` | `PlaywrightContextManager`/`PlaywrightInitializer` |
| `lifecycle.media` | `PlaywrightScreenshotManager` |

### 0.2 当前真实可见性（`PlaywrightManager.java:112-461` 实测）

- **已升 `public`**：`STATE`、`SHARED_BROWSER_LOCK`、`CONTEXT_LOCK`、`PAGE_LOCK`、`getCurrentConfigId()`、`perThreadBrowserLock()`、`isSharedBrowserMode()`、`parseSharedBrowserMode()`
- **仍 `package-private`（子包编译必失败）**：`SHARED_KEY_PREFIX`、`CONTEXT_KEY`、`PAGE_KEY`、`CURRENT_CONFIG_ID_KEY`、`SHARED_BROWSER_MODE`
- `frameworkState`(`:145`) 仍是包私有字段，且 `ContextRegistryImpl:69`、`PageRegistryImpl:64` **直接字段访问** `PlaywrightManager.frameworkState`（应走既有 `getFrameworkState()`）
- 子包被跨包调用却仍包私有：`TestContextBridge.drainPageErrors()`、`SerenityBusBridge.replayFailures(...)`、`PlaywrightInitializer.initializePlaywrightPaths()`、`PlaywrightContextManager.closeContext(BrowserContext)`、`PlaywrightSerenityBridge.cleanupThreadLocals(boolean)`；`CustomOptionsManager` 的 `CUSTOM_VIEWPORT_*_KEY`/`CUSTOM_STORAGE_STATE*_KEY`/`removeAllThreadLocals()` 同样包私有但被 `serenity`/`scenario`/`bootstrap` 跨包引用。

### 0.3 根因（一句话）

doc14 的「同包编译期封装」与「物理子包拆分」**本质冲突**——Java 子包 ≠ 同包，`package-private` 不跨子包。上一轮「升 `public` + `@apiNote`」只是止血，它**既侵蚀了 doc14 追求的封装，又脆弱**（每新增一处跨子包访问就再升一次可见性，锁与状态根也变成公开可达）。

---

## 1. 设计目标与硬约束

1. 保留子包物理拆分（可读性/分层收益不退回）。
2. 在「框架内部 / 业务」之间建立**可强制**的封装边界（不只是注释约定）。
3. **并发语义逐字不变**：锁实例、锁顺序、键、状态容器不变。
4. 保留 DI二期 的多态 seam（`PlaywrightRuntime.setInstance`）。
5. 内部面**刻意最小 + 构建期守护**。

---

## 2. 推荐方案（分层，按"最小暴露"原则）

### 2.1 基石：显式「框架内部 API 面」+ ArchUnit 守护

- 只公开**确实跨子包所需**的成员，其余保持包私有。
- 扩展现有 `businessCodeMustNotUseInternalByLocators` 规则，新增 `businessCodeMustNotUseLifecycleInternals`：任何 `framework.web.lifecycle` 包树**之外**（且非测试 seam）的类引用 `PlaywrightManager.STATE`、锁访问器、`frameworkState`、`CustomOptionsManager` 内部键等 → **构建失败**。
- 效果：`public` 不再等于「业务可用」，而是「刻意公开但构建期守护」，与 Spring `@Internal`、Jackson `util` 包同思路。

### 2.2 封装可变状态：`LifecycleState` 角色接口（封装 + 多态）

- 新增接口 `LifecycleState`，暴露**意图明确**的受控操作：
  `getPlaywright(key)`/`putPlaywright(key,pw)`/`getBrowser(key)`/`removeBrowser(key)`/`markDisconnected(b)`/`isDisconnected(b)`/`markClosing(b)`/`markRetired(id)`/`isRetired(id)`/`isFullInit()`/`markFullInit()`。
- `PlaywrightRuntimeState implements LifecycleState`，字段改为 `private`（线程安全容器不变）。
- `PlaywrightRuntime` 增加 `public final LifecycleState state`（或 `state()` 访问器）。子包由 `PlaywrightManager.STATE.browserInstances.get(k)` 改为 `PlaywrightRuntime.instance().state().getBrowser(k)`。
- 收益：**真封装**（不变式在方法内强制）+ **多态**（状态根可随 `setInstance` 一起换实现，与 DI二期 一致）。
- 取舍：约 45 处 `STATE.x` 访问改为方法调用。首版若求稳，可保留 `STATE` 为 `public final`（容器引用本身 `final`，不变量靠容器线程安全）+ ArchUnit 守护作为务实折中，**T2 再收口为接口**。

### 2.3 锁中介：`LifecycleLockMediator`（Mediator 模式，**解决真问题**）

- 现状：`CONTEXT_LOCK`/`PAGE_LOCK`/`SHARED_BROWSER_LOCK` 是 `public` 监视器对象，子包直接 `synchronized`，锁顺序仅靠注释约定（`PAGE_LOCK → CONTEXT_LOCK`）。跨子包下**任何人可按任意顺序 `synchronized` 同一锁 → 死锁/锁劫持风险**。
- 方案：中介暴露**有序临界区执行器**，锁本身变 `private`：
  `withPageLock(Runnable)` / `withContextLock(Runnable)` / `withPageThenContextLock(Runnable)`（强制规范顺序）/ `withSharedBrowserLock(Runnable)`。
- 收益：集中锁顺序（**死锁预防**，非装饰）、隐藏锁对象、语义逐字不变（同一监视器实例、同顺序）。

### 2.4 键/常量：保持 `public static final`（合理，不动）

`CONTEXT_KEY`/`PAGE_KEY`/`CURRENT_CONFIG_ID_KEY` 是不可变 `ContextKey` 常量，`public static final` 符合习惯，**不是封装泄漏**。

### 2.5 `frameworkState`：用既有 `getFrameworkState()` 访问器（原则 B：优先访问器）

修复 `ContextRegistryImpl:69`、`PageRegistryImpl:64` 两处直接字段访问为 `PlaywrightManager.getFrameworkState().isInitialized()`。

### 2.6 子包被跨包调用的方法：`public` + `@apiNote framework-internal`

`drainPageErrors()`/`replayFailures(...)`/`initializePlaywrightPaths()`/`closeContext(BrowserContext)`/`cleanupThreadLocals(boolean)` → `public`；`CustomOptionsManager` 的 `CUSTOM_VIEWPORT_*_KEY`/`CUSTOM_STORAGE_STATE*_KEY`/`removeAllThreadLocals()` 同理，并归入 ArchUnit 守护。

### 2.7 北极星：JPMS 限定导出（`qualified exports`）

未来若迁移到 `module-info.java`，用 `exports ...lifecycle to ...lifecycle.browser, ...context, ...page, ...scenario, ...serenity, ...bootstrap;` 实现**真正跨子包编译期封装**——子包可见、业务不可见，无需升 `public`、无需 ArchUnit 补丁。
代价：模块迁移（split-package 约束、第三方依赖 classpath 处理）、工作量较大，列为理想终态而非即时任务。

---

## 3. 设计模式映射

| 模式 | 落点 | 解决什么 |
|---|---|---|
| **Facade** | `PlaywrightManager`、`BrowserLifecycle` | 稳定公开门面，53 外部文件零改动 |
| **Composition Root / Service Locator** | `PlaywrightRuntime` | 组合根持有 6 角色 + 状态 + 锁，集中可替换 |
| **Role Interface + Interface Segregation** | `BrowserRegistry`/`ContextRegistry`/`PageRegistry`/`BrowserStartup`/`BrowserRestart`/`BrowserCleanup`/`CustomOptions`/`LifecycleState`(新) | 多态骨架、可测试替身 |
| **Mediator** | `LifecycleLockMediator`(新) | 集中锁顺序、防死锁、隐藏锁对象 |
| **Bridge** | WEB-P0-2 `RuntimeProvider` seam | 运行时对象源可换 |
| **Registry** | `BrowserRegistry.keyFor` | 键/锁派发 |
| **Singleton** | `*Impl.INSTANCE`、`PlaywrightRuntimeState.INSTANCE` | 框架内部无状态单例（务实取舍，非理想但可接受） |

---

## 4. Java 三大特性合规

- **封装 ✅**：可变状态收口到 `LifecycleState` 接口 + 私有字段（非公开字段）；锁经 Mediator 私有化；内部面由 ArchUnit 强制；`frameworkState` 走访问器。
- **继承 🔶→✅（需澄清）**：本框架**刻意不用 class 继承**（所有 `*Impl` 为 `final`，组合优于继承，避免脆弱基类、保护封装）——这是正确的 OO 取舍。多态**通过接口继承**（`implements RoleInterface`）实现，而非类层级。建议：**不要**在此引入 class 继承树；`final implements Interface` 已是该 stateless 工具风格下最优表达。
- **多态 ✅**：角色接口 + `PlaywrightRuntime.setInstance` seam（Phase 3 已达真多态，每接口 ≥2 实现：生产 `XImpl` + 测试替身）；新增 `LifecycleState` 可随之换实现。

---

## 5. 任务列表（分阶段，含验收）

### Phase 0 — 稳定编译（即时止血，保留"升 public+@apiNote"折中）
- **T0.1** 升 `public`：`SHARED_KEY_PREFIX`、`CONTEXT_KEY`、`PAGE_KEY`、`CURRENT_CONFIG_ID_KEY`、`SHARED_BROWSER_MODE`（`PlaywrightManager`）；`CUSTOM_VIEWPORT_WIDTH/HEIGHT_KEY`、`CUSTOM_STORAGE_STATE_PATH/KEY`、`removeAllThreadLocals()`（`CustomOptionsManager`）；`drainPageErrors`/`replayFailures`/`initializePlaywrightPaths`/`closeContext(BrowserContext)`/`cleanupThreadLocals(boolean)`（各子包）。
- **T0.2** 修复 `ContextRegistryImpl:69`、`PageRegistryImpl:64` → `getFrameworkState()`。
- 验收 ✅：`mvn -o -pl web -am clean compile` BUILD SUCCESS；
  `PlaywrightManager.frameworkState` 直访点已清零（全仓仅剩注释文本）。
  实测确认 T0.1 的 5 个 `PlaywrightManager` 成员与 `CustomOptionsManager` 的
  `CUSTOM_VIEWPORT_*_KEY`/`CUSTOM_STORAGE_STATE*_KEY`/`removeAllThreadLocals()`
  均已为 `public`；5 个跨子包方法（`drainPageErrors`/`replayFailures`/`initializePlaywrightPaths`/
  `PlaywrightContextManager.closeContext`/`PlaywrightSerenityBridge.cleanupThreadLocals`）均已为 `public`。

### Phase 1 — 内部面 + ArchUnit 守护
- **T1.1** 列出"刻意公开"清单（状态根/锁访问器/键/少量方法）。
- **T1.2** 新增 L7 规则（扩展 `ArchitectureTest`，实现为 2 例）：
  - `lifecycleInternalStateMustNotBeUsedOutsideLifecycle`（字段访问门禁，
    owner+字段名精确匹配，避免同名类误判）
  - `lifecycleInternalMethodsMustNotBeCalledOutsideLifecycle`（方法调用门禁，
    按 owner 精确区分同名的 `PlaywrightManager.closeContext()` 与 `PlaywrightContextManager.closeContext(...)`）
- 验收 ✅（**已做负向验证**）：临时在业务包 `tests.architecture` 下植入探针
  `LifecycleInternalProbe`（访问 `PlaywrightManager.STATE` + 调用 `getFrameworkState()`）
  → 构建 **Failures: 2**（两条规则各自拦截）；删除探针后恢复 13 例全绿。
- 全覆盖校验 ✅：PowerShell 扫描确认 **lifecycle 包树之外的所有 Java 文件对内部面引用数为 0**，
  故规则落地即零违规，无需豁免。

### Phase 2 — 状态根接口化（封装+多态）
- **T2.1** 新增 `LifecycleState` 接口；`PlaywrightRuntimeState implements` 之，字段转 `private`。
- **T2.2** `PlaywrightRuntime` 暴露 `state`；路由全部 `STATE.x` 直读为接口方法。
- 验收 ✅：子包**零** `PlaywrightManager.STATE.<field>` 直读（代码引用全清零，注释亦已同步更新）；
  全护盾 507 例绿。落地明细见 §8。

### Phase 3 — 锁中介（Mediator，防死锁）✅ 已落地（2026-09-10）
- **T3.1** ✅ 新增 `LifecycleLockMediator`（`framework.web.lifecycle` 包根）；`SHARED_BROWSER_LOCK`/`CONTEXT_LOCK`/`PAGE_LOCK` 收为 `private static final Object`；暴露有序执行器 `withBrowserLock`/`withSharedBrowserLock`/`withPerThreadBrowserLock`/`withContextLock`/`withPageLock`（各含 `Runnable` 与 `Supplier<T>` 版本）。
- **T3.2** ✅ 子包裸 `synchronized(CONTEXT_LOCK/PAGE_LOCK/SHARED_BROWSER_LOCK)` 已全部改为 `withXxxLock(...)`；全仓检索 `synchronized(锁)` 仅 6 处命中且全在 `LifecycleLockMediator` 内部。
- 验收：无 `public` 锁字段（达成）；⚠️ **锁顺序单测（故意逆序不 deadlock）未新增**——计划偏差，见 §9.4。

### Phase 4 — CustomOptionsManager 内部面收敛 ✅ 已落地（2026-09-10）
- **T4.1** ✅ 内部键（5 个 `public static final ContextKey`）降为包级私有（比 `LifecycleInternals` 门面更直接地消除跨包泄漏）；`removeAllThreadLocals` 保留 `public`（跨包清理入口）+ ArchUnit L7 守护；其余 getter·setter/`getInstance` 为合法公开业务 API（经 `PlaywrightManager.customOptions()` 暴露）。
- **T4.2** ✅ 跨包直接读写 TestContext 键的 2 处调用收敛为管理器 API（见 §10.2）；新增 `enableCustomOptions()`/`preserveStorageState(Path,String)` 支撑收敛且语义等价。
- 验收：ArchUnit 守护覆盖（L7 13 例全绿）；`CustomOptionsManagerConcurrencyTest`/`PlaywrightManagerCloseContextCleanupTest` 绿；全护盾 507 例基线（等义重构，未重跑完整套件）。

### Phase 5（可选/北极星）— JPMS 限定导出
- **T5.1** `module-info.java` + `exports ...lifecycle to ...lifecycle.{browser,context,page,scenario,serenity,bootstrap}`。
- **T5.2** 移除对"升 public"的依赖，回退 Phase 0 中不必要的 `public`。
- 验收：模块编译通过；限定导出生效；业务仍不可达内部面。

### Phase 6 — 文档与守护固化
- **T6.1** 本文档落盘（本文件）。
- **T6.2** `ArchitectureTest` 覆盖 + 全护盾（基线 503）。

---

## 6. 取舍与风险

- **Phase 0 的"升 public"是临时态**，必须被 Phase 1 ArchUnit 或 Phase 2/3 收口，否则封装回归。
- **T2 约 45 处改调用**工作量最大但收益最高（真封装+可换实现）；若时间紧可先 T0+T1 止血，T2 排期。
- **JPMS（T5）代价高**，仅在确有模块迁移计划时启动；在此之前 ArchUnit 守护是成熟替代。
- **执行纪律**：所有可见性提升一律 `public` + `@apiNote framework-internal`（防业务误用）；UTF8 无 BOM 写回；每步必跑 `clean compile` + 全护盾。

---

## 7. 落地记录（Phase 0 + Phase 1，2026-09-10）

### 7.1 验证数据

| 项 | 结果 |
|---|---|
| `mvn -o -pl web -am clean compile` | BUILD SUCCESS |
| `mvn -o -pl test-automation -am test-compile` | BUILD SUCCESS |
| `ArchitectureTest` | 13 例绿（原 11 + 新增 L7 两条） |
| 负向探针验证 | 植入 → Failures: 2；移除 → 全绿 |
| 全护盾 `mvn -o -pl test-automation -am test` | **505 例，0 失败 / 0 错误 / 0 跳过，BUILD SUCCESS**（基线 503 + 新增 2） |

### 7.2 三起实测偏差与教训（务必牢记）

**① `clean compile` 掩盖了子包拆分的测试编译断裂。**
`mvn -pl web -am clean compile` 只编译主代码，不编译测试；切到 `test-compile` 才暴露
15 个测试类（test-automation `framework.web.lifecycle` 包 + web `SerenityBusBridgeTest`）
因协作者迁入子包而缺失/失效 import（`cannot find symbol`、旧路径 `package X does not exist`）。
修复：`tools/fix_lifecycle_test_imports.ps1` 批量补齐（UTF8 无 BOM、纯 ASCII 脚本），
另手工补 `SerenityBusBridgeTest`（`serenity.SerenityBusBridge`）、`BrowserCrashGuardTest`、
`PlaywrightRuntimePolymorphismTest`。
**教训：子包/包重命名类重构的验收命令必须是 `test-compile`，不能停在 `compile`。**

**② WEB-P3-N14 ②（下载 `TargetClosedError` 降级）在生产代码中整段丢失。**
`PlaywrightContextManager.isContextClosedError(Throwable)` 全仓仅存在于文档与测试中，
生产代码仍是 `logger.error("[Download] Failed to save file")`。
根因与记忆 85506876 同源：**用 `git show HEAD:<path>` 重建文件时，会连同覆盖本轮新增的修复**。
已重建该方法（沿 cause 链匹配类名含 `TargetClosed` 或消息含
`Target page, context or browser has been closed`，`IdentityHashMap` 身份集合做 cause 自环防御，
`null` 返回 `false`），并把下载 catch 改为「命中→DEBUG、未命中→ERROR」。
同时确认 ①（`closingBrowsers` 弱引用 + `closeBrowserInstance` 收口）完好，未丢失。
**教训：`git show` 重建必须逐项核对「非原始」改动清单，并保留重建前的功能自检（grep 方法名）。**

**③ 测试要访问包级私有方法时，应移动测试而非提升可见性。**
`DownloadSaveErrorClassifierTest` 测的是 `bootstrap` 子包的包级私有 `isContextClosedError`。
最初按「升 `public` + `@apiNote`」处理，但那会扩大生产 API 面并需额外 ArchUnit 豁免；
改为把测试类迁入 `...lifecycle.bootstrap` 包（跨模块同包仍可访问包级私有成员），
与既有白盒测试（`BrowserRegistryConfigIdValidationTest` 等）同范式。

### 7.3 ArchUnit 覆盖缺口修复（重要）

既有规则一律使用 `ImportOption.DoNotIncludeTests()`，而该选项会排除 `target/test-classes`，
**恰好把 test-automation 的业务代码（Page Object / Step）整片排除**——规则实际只守护 framework 主代码。
L7 两条规则已刻意去掉该选项，改为
`new ClassFileImporter().importPackages(BASE_PACKAGE, "com.hsbc.cmb.hk.dbb.automation.tests")`，
使业务侧违规可被真正拦截（已由 §7.1 的负向探针证实）。
**建议后续评审其它 ArchUnit 规则的同类覆盖缺口。**

### 7.4 内部面清单（Phase 1 产物，构建期守护）

| 类别 | 成员 |
|---|---|
| 状态/锁/键字段（`PlaywrightManager`） | `STATE`、`CONTEXT_LOCK`、`PAGE_LOCK`、`SHARED_BROWSER_LOCK`、`SHARED_KEY_PREFIX`、`SHARED_BROWSER_MODE`、`CONTEXT_KEY`、`PAGE_KEY`、`CURRENT_CONFIG_ID_KEY` |
| 存储键字段（`CustomOptionsManager`） | 全部 `CUSTOM_*_KEY` |
| 内部方法 | `perThreadBrowserLock`、`getPageThreadLocal`、`getFrameworkState`、`removeAllThreadLocals`、`drainPageErrors`、`replayFailures`、`initializePlaywrightPaths`、`PlaywrightContextManager.closeContext`、`PlaywrightSerenityBridge.cleanupThreadLocals` |
| 状态根（Phase 2 新增） | `LifecycleState` 角色接口的**任何**方法（整接口对外封闭） |

---

## 8. Phase 2 落地记录（2026-09-10）

### 8.1 实现形态

| 产物 | 说明 |
|---|---|
| `lifecycle/LifecycleState.java`（新增） | 状态根角色接口，26 个意图明确的受控操作，按域分区（Playwright 表 / Browser 表 / 断开·关闭中标记 / 废弃 configId / 初始化判据） |
| `PlaywrightRuntimeState` | `implements LifecycleState`；6 个容器字段由「包级可见」收为 `private`；对外只暴露受控操作与**只读视图** |
| `PlaywrightRuntime` | 新增第 7 个角色 `public final LifecycleState state`（默认 `PlaywrightRuntimeState.INSTANCE`），与其余 6 个协作者同构、可随 `setInstance` 一起替换 |
| `PlaywrightManager` | **删除 `STATE` 字段**——状态根不再挂在门面公开面上，协作者统一经 `PlaywrightRuntime.instance().state` 访问 |

### 8.2 关键设计决策

**① 遍历视图返回「只读实时视图」而非 `Map.Entry` 裸集合。**
`playwrightEntries()` / `browserEntries()` 返回 `Collections.unmodifiableMap(map).entrySet()`
（而非 `unmodifiableSet(map.entrySet())`）——后者只挡 add/remove，**仍可经 `Map.Entry.setValue()` 改值**；
前者连 `setValue` 也会抛 `UnsupportedOperationException`。
保留「遍历 + 条件 + 经接口删除」的既有清理模式，语义与直接操作 `ConcurrentHashMap` 完全等价
（迭代期间删除不抛 `ConcurrentModificationException`）。

**② `removePlaywright` / `removeBrowser` 返回被移除的实例（与 `ConcurrentMap#remove` 同语义）。**
首版设计为 `void`，编译即暴露 3 处调用点确实使用了返回值（启动失败路径回收泄漏的 Playwright 进程）。
**教训：接口化时不要凭印象把 `Map` 操作降为 `void`，应先枚举调用点是否消费返回值。**

**③ `state` 作为构造参数放在最后一位**，使既有 6 参数调用只需追加一个实参，改动最小。

### 8.3 迁移方式

`tools/lifecycle_phase2_state_migration.ps1`：按「字段 + 紧跟操作」的全限定前缀精确替换
（如 `PlaywrightManager.STATE.browserInstances.containsValue(` →
`PlaywrightRuntime.instance().state.containsBrowserInstance(`），共 26 条规则、53 处调用点，
UTF8 无 BOM 写回；脚本末尾输出残留清单以便人工复核。

### 8.4 验证数据

| 项 | 结果 |
|---|---|
| `mvn -o -pl web -am compile` | BUILD SUCCESS |
| `mvn -o -pl test-automation -am test-compile` | BUILD SUCCESS |
| `PlaywrightRuntimeTest` | 2 → 4 例（补 `state` 默认非空、null 防御、可替换） |
| 全护盾 | **507 例（505 + 新增 2），0 失败 / 0 错误 / 0 跳过，BUILD SUCCESS** |
| 多态验收 | `LifecycleState` 已具备 ≥2 实现（生产 `PlaywrightRuntimeState` + Mockito 替身） |

### 8.5 过程中的两个坑

**① `mvn clean` 因 `web/target` 被占用而失败**（非代码问题，IDE/残留进程持有句柄）。
直接去掉 `clean` 用 `compile` 即可；不要误判为代码错误。

**② 注释/javadoc 会残留旧引用。** 代码迁移后 `PlaywrightManager.STATE` 在 6 处注释、
1 处失效 javadoc 链接（`{@link PlaywrightRuntimeState#browserInstances}`，字段已 private）中残留，
需单独清理，否则误导后来者。

---

## 9. Phase 3 落地记录（2026-09-10）

### 9.1 实现形态

| 产物 | 说明 |
|---|---|
| `lifecycle/LifecycleLockMediator.java`（新增） | 锁中介（Mediator）；饿汉单例 `INSTANCE`；三锁 `SHARED_BROWSER_LOCK`/`CONTEXT_LOCK`/`PAGE_LOCK` 均为 `private static final Object`；per-thread Browser 锁键 `BROWSER_LOCK_KEY` 迁入 `TestContext`（原 `ThreadLocal` 语义等价） |
| 执行器 | `withBrowserLock(sharedMode, block)` 按共享模式派发进程级/per-thread 锁；`withSharedBrowserLock`/`withPerThreadBrowserLock`/`withContextLock`/`withPageLock` 显式指定；均提供 `Runnable` 与 `Supplier<T>` 重载 |
| `PlaywrightManager` | 锁字段（原 `public` 监视器）已移除，改经 `LifecycleLockMediator.withXxxLock(...)` 访问；仅保留 `SHARED_BROWSER_MODE` 作为 `public` 模式标志（非锁对象） |

### 9.2 关键设计决策

**① 不提供嵌套组合执行器（如 `withPageThenContextLock`）。** 规范锁顺序为 `PAGE_LOCK → CONTEXT_LOCK`，但当前全部临界区均为单层；提前暴露嵌套 API 会诱导调用方扩大临界区（长临界区是吞吐与死锁主因）。真正防死锁手段是「Browser 切换时把 `closePage`/`closeContext` 移到 Browser 锁**之外**」（已在 `BrowserRegistryImpl#handleBrowserTypeSwitch` 保持）。

**② per-thread Browser 锁迁入 `TestContext`。** 原 `ThreadLocal<Object> withInitial(Object::new)` 收拢为 `ContextKey` 惰性缓存（`TestContextHolder.get().computeIfAbsent(...)`），同一线程多次取到同一锁对象，语义等价且不随共享模式切换。

**③ 语义逐字等价。** 每个执行器 ≡ `synchronized (原锁对象) { return block.get(); }`，锁实例/作用域/可见性与迁移前一致，仅锁对象不再对外可见（外部无法逆序加锁或劫持）。

### 9.3 迁移核验
全仓检索 `synchronized\((CONTEXT_LOCK|PAGE_LOCK|SHARED_BROWSER_LOCK)\)`：仅 6 处命中，全部位于 `LifecycleLockMediator` 内部（即中介自身的 `synchronized`）；子包调用点已无裸锁 → T3.2 迁移完成。

### 9.4 偏差与待补
- ✅ **锁顺序单测已补（2026-09-10）**：新增 `LifecycleLockMediatorTest`（6 例，test-automation `framework.web.lifecycle.lock` 包，纯并发单测零浏览器依赖）：
  ① 规范序（PAGE→CONTEXT）单线程嵌套 + 规范序 16 线程竞争均不 deadlock（防 ABBA）；
  ② 逆规范序（CONTEXT→PAGE）单线程嵌套可完成（单线程持两把异构监视器不自死锁，固化 doc §5「逆序不 deadlock」字面要求）；
  ③ 进程级 `withSharedBrowserLock` 与 per-thread `withPerThreadBrowserLock` 交叉竞争不 deadlock；
  ④ 反射守护：三把锁 `SHARED_BROWSER_LOCK`/`CONTEXT_LOCK`/`PAGE_LOCK`/`BROWSER_LOCK_KEY` 保持 `private`（一旦被误升可见性即失败）；中介无返回锁对象（`Object`）的公开方法（泛型 `<T>` Supplier 擦除经 `TypeVariable` 排除）。
- 锁的「隐藏」已由 `private` 提供编译期保障，故 ArchUnit L7 无需新增锁相关规则（锁字段根本不可跨包引用）；L7 仍守护 `PlaywrightManager.STATE`/`getFrameworkState` 等既有内部面。

### 9.5 验证数据

| 项 | 结果 |
|---|---|
| `mvn -o -pl web -am compile` | BUILD SUCCESS |
| `mvn -o -pl test-automation -am test-compile` | BUILD SUCCESS（8 模块全绿，含 `framework-route`） |
| `synchronized(锁)` 外部裸锁 | 0（全在 `LifecycleLockMediator` 内部） |
| 全护盾 | 基线 507 例（Phase 3 为等义锁重构、未增删用例；本次仅验证 `test-compile` 绿，未重跑全护盾——等价重构下计数与 Phase 2 基线一致） |
| ArchUnit | 13 例（L7 两条仍守护既有内部面，锁已 `private` 无需新增规则） |

---

## 10. Phase 4 落地记录（2026-09-10）

### 10.1 内部面泄漏点
`CustomOptionsManager` 原有 5 个 `public static final ContextKey` 常量，允许任意包直接 `TestContextHolder.get().get/set(CustomOptionsManager.CUSTOM_X_KEY)` 读写内部存储键、绕过管理器封装：

| 键 | 泄露调用方（改造前） |
|---|---|
| `CUSTOM_CONTEXT_OPTIONS_FLAG_KEY` | `PlaywrightContextManager`（读/写）、`PlaywrightSerenityBridge`（写）、`ContextRegistryImpl`（同包读，OK） |
| `CUSTOM_STORAGE_STATE_PATH_KEY` / `CUSTOM_STORAGE_STATE_KEY` | `PlaywrightSerenityBridge`（读/写，会话保留） |
| `CUSTOM_VIEWPORT_WIDTH_KEY` / `CUSTOM_VIEWPORT_HEIGHT_KEY` | `PlaywrightContextManager`（读，应用视口） |

### 10.2 收敛动作
- 5 个 `ContextKey` 降为包级私有（仅 `context` 包内可直接引用；`ContextRegistryImpl` 同包，无需改动）。
- 新增 2 个语义等价公开方法：
  - `enableCustomOptions()`：仅置位 `customContextOptionsFlag=true`，不触发 Context 重建（对称于 `disableCustomOptions()`）。
  - `preserveStorageState(Path, String)`：原样回填 storageState 路径/内存内容，不置位 flag、不触发重建（等价于直接 set 原 TestContext key，且按 null 跳过）。
- 跨包调用改为经管理器 API（见 §5 T4.2），不再持有内部键引用。

### 10.3 验证数据

| 项 | 结果 |
|---|---|
| `mvn -o -pl web,test-automation -am test-compile` | BUILD SUCCESS |
| ArchUnit `ArchitectureTest`（L7 守护内部面） | 13 例全绿 |
| `CustomOptionsManagerConcurrencyTest` | 绿（per-thread 隔离语义未变） |
| `PlaywrightManagerCloseContextCleanupTest` | 绿 |

> 注：Phase 4 为等义重构（锁/存储键语义逐字等价），未增删用例；全护盾 507 例基线沿用 Phase 2，本次未重跑完整套件，仅以编译 + ArchUnit + 相关单测佐证无回归。

### 10.4 偏差与后续
- ⚠️ 更深的 Browser 选项耦合（storageState 会话保留与 Context 重建竞态、Feature/Scenario 模式差异）未在本专项穷尽，建议并入独立的「浏览器选项治理」专项。
- `enableCustomOptions()` 与 `disableCustomOptions()` 均直接操作 `customContextOptionsFlag`，未与 `applyCustomOption` 的「设值即置位 flag + 触发重建」路径统一；后续可评估收口为单一 flag 写入入口。

---

## 11. 收尾与 Phase 5 路线（2026-09-10）

### 11.1 专项总览（Phase 0–4）

| Phase | 主题 | 状态 |
|---|---|---|
| 0/1 | `PlaywrightManager` 公开字段（`STATE`/`STATE_LOCK`）收敛 + 跨子包访问治理 | ✅ |
| 2 | 状态根接口化（`LifecycleState`） | ✅ |
| 3 | 锁中介（`LifecycleLockMediator`，`private` 锁） | ✅ |
| 4 | `CustomOptionsManager` 内部面收敛（`ContextKey` 包级私有） | ✅ |

全护盾 **507 例绿、0 失败/0 错误/0 跳过、BUILD SUCCESS**；ArchUnit L7（13 例）守护内部面通过。

### 11.2 残留偏差（集中跟踪）

- **偏差 A（Phase 3）✅ 已收口（2026-09-10）**：锁顺序单测 `LifecycleLockMediatorTest`（6 例）已新增，覆盖规范序/逆序单线程嵌套、规范序多线程竞争、Browser 双锁交叉竞争无死锁，以及锁字段 `private` + 无公开锁访问器守护（§9.4）。
- **偏差 B（Phase 4）**：storageState 会话保留与 Context 重建竞态、Feature/Scenario 模式差异未穷尽 → 并入「浏览器选项治理」专项（§10.4）。
- **偏差 C（Phase 4）**：`enableCustomOptions`/`disableCustomOptions` 未与 `applyCustomOption` 的 flag 写入入口统一（§10.4）。

### 11.3 Phase 5（JPMS 限定导出）路线与风险校正

doc §6 原表述 `exports ...lifecycle to ...lifecycle.{browser,context,page,scenario,serenity,bootstrap}` **不可行**：`PlaywrightManager`（业务主入口，test-automation 经 `PlaywrightManager.getInstance()`/`customOptions()` 直接使用）位于 `lifecycle` 包，若整包仅限定导出，test-automation（classpath / unnamed module，`useModulePath=false`）将失去对 `PlaywrightManager` 的访问，全量编译断裂。

校正方案：
1. `lifecycle` 包保持**非限定导出**，保障 `PlaywrightManager` 等公开 API 对 test-automation 可达。
2. 将「仅框架内部、不应被其他模块直触」的类迁入 `lifecycle.internal`（如 `LifecycleLockMediator`、`PlaywrightRuntimeState`、其它纯内部协作类），并以
   `exports ...lifecycle.internal to ...lifecycle.browser, ...lifecycle.context, ...lifecycle.page, ...lifecycle.scenario, ...lifecycle.serenity, ...lifecycle.bootstrap`
   **限定导出**——仅 `web` 内部子包可触达。
3. `CustomOptionsManager` 因被 `PlaywrightManager.customOptions()` 返回且被 test-automation 直接单测（§10.3），**保持公开导出**；其内部 `CUSTOM_*` 键已为包级私有（Phase 4 已锁）。

风险与前提：
- 构建为 classpath 模型，test-automation 走 unnamed module，模块边界主要靠 `web` 的 `module-info` 约束。
- 跨模块 Java 访问规则已天然阻止 `package-private`/`private` 成员外泄；Phase 5 的实质增益是把「**公开但本应内部**」的类型藏进非导出（或限定导出）包，并借 ArchUnit 固化。
- `web` 模块化须将其全部依赖解析为自动模块（Playwright / Serenity / Guava 等），存在 split-package 或缺失 `Automatic-Module-Name` 风险，须以 `mvn -o -pl web -am compile` 逐条核验；失败时回退（删除 module-info）。

### 11.3.1 可行性验证结论（2026-09-10，实测）
为 `web` 加 `module-info.java` 并以 `mvn -o -pl web -am compile` 验证：所有 `requires`（含兄弟模块 `framework.core/api/reporting` 与外部 `net.serenitybdd.*`、`com.microsoft.playwright` 等）一律报 `module not found`。

根因：reactor 内**非模块化兄弟模块无法作为自动模块置于模块路径**——自动模块必须是 jar，而 `-am` 仅产出 `target/classes` 目录；模块路径解析不认目录型依赖。该现象与 `useModulePath` 无关（`useModulePath` 仅作用于 surefire 测试阶段，不改变 compiler 的模块路径解析）。

推论：
1. 要让 `web` 作为模块编译，**必须全仓 7 个模块（core/api/reporting/codegen/route/test-automation）一并补 `module-info.java`**——即整项目 JPMS 迁移，工作量与回归风险显著，且需逐一处理 split-package 与外部依赖自动模块名。
2. 即便完成迁移，`maven-surefire-plugin` 的 `useModulePath=false` 使 test-automation 始终走 classpath / unnamed module，**模块边界对 test-automation 的访问约束不生效**——Phase 5「限定导出内部面」的目标在现有构建配置下无法真正达成。
3. 跨模块 Java 访问规则（package-private / private 不可跨模块）已天然阻止内部成员外泄；本专项 Phase 0–4 已通过降可见性 + ArchUnit L7 固化内部面，具备同等效果的编译期保障。

结论：Phase 5 在当前构建模型下**收益有限、成本高、且有配置前提未满足**；经用户决策**不做**，原目标（`web/lifecycle` 包层级结构）已由 §11.5「按职责子包拆分」达成，JPMS 维持 doc 原「可选/北极星」定位。若后续确需模块级强约束，独立立项路径为：① 全模块补 `module-info.java`；② surefire `useModulePath` 翻 `true` 并为 test-automation 加 `module-info.java`；③ 处理 split-package 与外部依赖自动模块名。

### 11.4 Phase 5 验收（不适用）
- Phase 5（JPMS 限定导出）经用户决策不做（见 §11.3 结论）；本专项内部面保障由 Phase 0–4（降可见性）+ ArchUnit L7（13 例）承接，等价编译期约束持续有效。
- 原「`lifecycle.internal` 仅被 `web` 内部子包引用」验收项由 §11.5 的按职责子包拆分实质达成（内部类迁入子包后，跨子包访问受 `public` 可见性与 ArchUnit L7 双重约束）。

### 11.5 lifecycle 包层级结构重组（已落地，2026-09-10）

**目标**：将根 `lifecycle` 包扁平堆积的 13 个职责各异的类归并为按职责子包，使根仅保留门面 / 组合根 / 状态契约；与既有 `browser/context/page/scenario/serenity/bootstrap/media` 风格一致，形成清晰层级。

**最终包树**
```
web/.../web/lifecycle/
├── PlaywrightManager.java        # 门面 / 公开入口（53 处外部引用，必须留根）
├── PlaywrightRuntime.java        # 组合根（留根）
├── LifecycleState.java           # 状态角色接口（公开契约，留根）
├── state/    PlaywrightRuntimeState.java
├── lock/     LifecycleLockMediator.java
├── config/   PlaywrightConfigManager.java, ProxyConfigResolver.java
├── event/    PageEventMonitor.java
├── provider/ DefaultRuntimeProvider.java
└── concurrent/ ConcurrentContextExecutor.java / ConcurrentContextOptions.java
                / ContextTask.java / ContextTaskResult.java
   （既有子包：bootstrap / browser / context / media / page / scenario / serenity）
```

**迁移清单（10 类）**

| 类 | 迁入子包 |
|---|---|
| `PlaywrightRuntimeState` | `state` |
| `LifecycleLockMediator` | `lock` |
| `PlaywrightConfigManager` / `ProxyConfigResolver` | `config` |
| `PageEventMonitor` | `event` |
| `DefaultRuntimeProvider` | `provider` |
| `ConcurrentContextExecutor` / `ConcurrentContextOptions` / `ContextTask` / `ContextTaskResult` | `concurrent` |

**关键工程决策**
1. `git mv` 保留文件历史；`package` 声明 + 全仓 `import`（及 javadoc `{@link}`）经 UTF8 无 BOM 重写，回归面为含 import 的 43 个文件（web + test-automation）。
2. 跨子包可见性：10 个类本就 `public`，直接迁移；但以下包级私有成员被跨子包消费，提升 `public`——
   - `PlaywrightRuntimeState.INSTANCE` / `DefaultRuntimeProvider.INSTANCE`（`PlaywrightRuntime` / `PlaywrightManager` 跨子包单例消费）；
   - `ContextTaskResult.success/failure/cancelled` 工厂方法（`lifecycle.serenity` 与测试跨子包消费）。
3. 根 `lifecycle` 内 13 类之间原靠裸名互引（同包免 import）；迁走 10 个后，留根类与跨子包裸名引用由脚本精准补 `import`（仅对确有代码引用者补，javadoc / 字符串里的类名不补，避免 Checkstyle `UnusedImports`）。
4. ArchUnit L7（内部面访问守护）不受影响——访问语义未变，仅 relocate。

**验证**
- 全护盾 `mvn clean -o -pl test-automation -am test`：**507 例、0 失败 / 0 错误 / 0 跳过、BUILD SUCCESS**。
- Checkstyle（`FileLength` / `EmptyCatchBlock` / `UnusedImports`）：web 模块 **0 违规**。
- 顺手收敛 2 个历史遗留未用 import（与本次重组无关）：`BasePage` 的 `java.util.function.Consumer` / `java.util.regex.Pattern`、`PageDebugControl` 的 `com.microsoft.playwright.Page`（均为 WEB-P1-2 拆分后冗余）。
- 迁移文件 import 收敛：9 个迁入子包的文件原从根包继承了约 25–33 条未使用 import（maven-checkstyle-plugin 的 `<includes>` 未覆盖 `lifecycle` 包，故此前未被 `UnusedImports` 检查）；本次按「仅保留代码实际引用」原则清理，仅 `LifecycleLockMediator` 本就干净。清理中曾误删 7 个确被代码使用的 import（`DefaultRuntimeProvider` 的 `Browser/BrowserContext/Page/Playwright`、`ConcurrentContextExecutor` 的 `SerenityBusBridge`、`LifecycleLockMediator` 的 `TestContextHolder`、`PlaywrightRuntimeState` 的 `WeakHashMap`），已逐一补回并复编译验证；清理后全护盾仍 507 绿。
- 迁移文件框架内部标注与遗留死代码清理（2026-09-10）：① 7 个迁入子包的文件此前缺 `@apiNote`（`PlaywrightConfigManager`/`ProxyConfigResolver`/`DefaultRuntimeProvider`/`ConcurrentContextExecutor`/`ConcurrentContextOptions`/`ContextTask`/`ContextTaskResult`），统一补「框架内部能力 / 业务代码不得依赖」标注，与既有 `LifecycleLockMediator`/`LifecycleState`/`PageEventMonitor`/`PlaywrightRuntimeState` 风格对齐；② `ContextTaskResult` 的 `diagnostics` 字段与 `getDiagnostics()` 仅写入、全仓零读取（死数据），连同 `success/failure` 工厂的该形参一并移除，并同步更新 `ConcurrentContextExecutor`（5 处）与 `SerenityBusBridgeTest`（6 处）调用点、清理空出的 `import java.util.Map`；③ `ConcurrentContextOptions.hardCap` 全仓无任何调用方设置（仅恒为 null），移除该字段、`resolvedParallelism` 中的分支与 `Builder.hardCap` 方法，并发度恒走 `DEFAULT_HARD_CAP=16`。改动后仍 507 绿、0 违规。
- 冗余间接层治理（2026-09-10）：`ProxyConfigResolver` 此前为 7 个场景（`Context`/`Download`/`BrowserStackCdp`×2 / `BrowserStackLocal`×2 / `Download`·`Context` 的 HTTPS）各提供同名公共方法，但设计上「各场景共享全局凭据、无需场景覆盖」，该 7 方法均为 `getHttpProxyUrl()`/`getHttpsProxyUrl()` 的纯别名（共 9 处调用点 + `getHttpsProxyUrlForContext` 0 调用方死代码）。已收口：9 处调用点重定向至规范方法 `getHttpProxyUrl()`/`getHttpsProxyUrl()`（`PlaywrightContextManager`·`PlaywrightInitializer`·`BrowserStackManager`×5·`BrowserStackLocalManager`），删除 7 个场景别名方法（55 行）；类仅保留规范入口 `getHttpProxyUrl`/`getHttpsProxyUrl` 与 `sanitizeProxyUrlForLog`/`extractHost`/`extractPort`/`extractUser`/`extractPass` 及内部实现。改动后仍 507 绿、0 违规。

---

## 12. 变更文件清单（Phase 0–4）

| 文件 | 改动 |
|---|---|
| `web/.../lifecycle/bootstrap/PlaywrightContextManager.java` | Phase 3 改 `withXxxLock`；Phase 4 改走 `CustomOptionsManager` API |
| `web/.../lifecycle/serenity/PlaywrightSerenityBridge.java` | Phase 3 改 `withXxxLock`；Phase 4 改走 `CustomOptionsManager` API |
| `web/.../lifecycle/context/ContextRegistryImpl.java` | Phase 3 改 `withContextLock`/`withSharedBrowserLock`；FLAG 同包直读（合法） |
| `web/.../lifecycle/LifecycleLockMediator.java` | Phase 3 新增（锁中介，`private` 锁） |
| `web/.../lifecycle/context/CustomOptionsManager.java` | Phase 2 状态根协作；Phase 4 `ContextKey` 包级私有 + 新增 `enableCustomOptions`/`preserveStorageState` |
| `web/.../lifecycle/LifecycleState.java` / `PlaywrightRuntimeState.java` / `PlaywrightRuntime.java` / `PlaywrightManager.java` | Phase 2 状态根接口化 |
| `core/.../PlaywrightRuntimeState` 等协作者 | Phase 2 接口化配套 |
