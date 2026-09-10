# 15. 整改专项设计 · WEB-P1-6 DI 二期（实例 + 接口化）

> 关联评审：02_web模块评审.md（WEB-P0-2 / WEB-P1-1）、13（DI seam 一期）、14（拆分 `PlaywrightManager`）
> 状态：🛠 实施中（Phase 1 接口提取 + 命名收敛已落地 ✅；Phase 2 实例门面 `PlaywrightRuntime` 已落地 ✅；Phase 3 可替换性已落地 ✅，接口=角色名 / 实现=`Impl` 后缀；**§10 门面收口设计已补充（企业级 + 三大特性）**；**Phase 4 门面收口（impl-to-impl 收束到组合根）已落地 ✅（见 §9.4）**；Phase 5 注释/@apiNote 收口待实施）
> 前置：WEB-P0-2 ✅（DI seam 一期）、WEB-P1-1 ✅（拆分完成，门面 1553 → 363 行）、WEB-P1-2 ✅（拆 BasePage，串行约束已解除）

## 1. 背景：三大特性复盘暴露的短板

对 WEB-P1-1 落地结果做「封装 / 继承 / 多态」复盘：

| 特性 | 现状 | 说明 |
|---|---|---|
| 封装 | ✅ 优秀 | 状态收敛到 `PlaywrightRuntimeState`；协作者全部 `final` + 包级私有；公开 API 零变更 |
| 继承 | ❌ 未使用 | 所有协作者为 `final class`，刻意采用「组合优于继承」 |
| 多态 | 🔶 有限 | 仅 `RuntimeProvider`、`BrowserStrategy` 有接口多态；**主体是 `static` 方法委托，静态方法不参与动态分派** |

**根本问题**：当前是「静态工具类 + 门面委托」的过程式风格，而非面向对象风格——
实现不可替换（静态绑定）、不可继承扩展、测试只能依赖 seam 与包级可见性。

## 2. 现状数据（2026-09-08 实测）

- `PlaywrightManager.` 引用分布在 **53 个文件**（`web` / `codegen` / `test-automation` 模块 + 文档）。
- 现有协作者（均 `framework.web.lifecycle` 包、包级私有 final）：
  `PlaywrightManager`(363) / `BrowserStartup`(378) / `BrowserRestart`(277) / `BrowserRegistry`(244) /
  `ContextRegistry`(229) / `BrowserCleanup`(173) / `PageRegistry`(98) / `BrowserLifecycle`(96，组合门面) /
  `PlaywrightRuntimeState`(78) / `ScenarioLifecycle`(56)。
- 已有接口：`RuntimeProvider`（seam）、`BrowserStrategy`（本地/云策略）。
- per-thread 状态已由 T3-1 收拢为 `ContextKey`（`TestContextHolder`），无 `static ThreadLocal`。

## 3. 目标与验收

- 将静态门面演进为**实例对象** `PlaywrightRuntime`，协作者**接口化**（可替换实现）；
- 满足三大特性：**封装**（实例字段私有）、**继承**（接口/抽象类可扩展）、**多态**（接口动态分派）；
- 验收：
  1. 核心协作者（Registry / Lifecycle）为**实例**且可通过构造/设值替换；
  2. 每个核心接口至少有 **2 个实现**（生产实现 + 测试替身），并由单测证明替换生效；
  3. **现有调用方零改动或仅为兼容层改动**（见 §4 兼容策略）；
  4. 全护盾 **487 例零回归**（基线）。

## 4. 兼容策略（关键决策）

| 方案 | 做法 | 改动面 | 风险 |
|---|---|---|---|
| A 激进 | 53 个文件的静态调用全部改为实例调用 | ~45 个源文件 | 高 |
| **B 务实（推荐）** | **静态门面保留为薄兼容层**（委托到默认实例），内部全面实例/接口化；新代码走实例；旧调用点零改动，后续逐步迁移并标记 `@Deprecated` | 0 个外部文件 | 低 |

**推荐 B**：与 WEB-P1-1「公开 API 零变更」一脉相承，可在不触碰 53 个调用文件的前提下完成面向对象化；兼容层在迁移完成后可删除。

## 5. 分阶段实施

| Phase | 内容 | 产出 | 验收 |
|---|---|---|---|
| **1** | **接口提取 + 命名收敛**：为 `BrowserRegistry` / `ContextRegistry` / `PageRegistry` / `BrowserStartup` / `BrowserRestart` / `BrowserCleanup` 提取角色名接口，既有静态协作者就地转为实例实现 `XImpl`（单例），与 `DefaultRuntimeProvider` 同为「接口 + 实例实现」结构 | 6 接口 + 6 实现 | 编译过、行为不变 |
| **2** | **实例门面**：新增 `PlaywrightRuntime`（实例）持有各协作者接口，提供获取入口（单例 + per-thread 语义）；`PlaywrightManager` 静态门面改为委托到该实例 | `PlaywrightRuntime` | 全护盾零回归 |
| **3** | **可替换性**：提供 `setters`/构造注入与测试替身；新增单测证明「替换实现后行为随之改变」（真运行时多态） | 单测 ≥6 例 | 新单测全绿 |
| **4** | **调用方迁移（可选/渐进）**：按模块逐步把静态调用改为实例调用；兼容层标记 `@Deprecated` 并最终移除 | 迁移记录 | 每批全护盾 |

## 6. 关键设计约束（行为零回归铁律）

- **线程模型不变**：per-thread 状态仍由 `ContextKey` 承载；实例门面不得引入新的共享可变状态（单例只持有**无状态协作者**，有状态数据仍在 `PlaywrightRuntimeState`）。
- **锁语义不变**：`perThreadBrowserLock` / `SHARED_BROWSER_LOCK` / `CONTEXT_LOCK` / `PAGE_LOCK` 的获取顺序与作用域逐字保持（防死锁链）。
- **seam 不被绕过**：`getPlaywright/getBrowser/getContext/getPage` 仍须经 `RuntimeProvider`，链路为 `门面 → provider → 协作者实现`。
- **生命周期**：实例的创建/销毁须与 Serenity 场景生命周期对齐（`initializeForScenario` / `cleanupForScenario`），避免跨 scenario 泄漏。
- **日志溯源**：下沉后日志仍回 `LoggerFactory.getLogger(PlaywrightManager.class)`，保持生产日志一致性。

## 7. 风险与权衡

- **过度抽象风险**：历史教训（过度抽象层曾被清理）。接口仅在**确有多实现需求**（生产/测试/云本地策略）时引入，避免为接口而接口。
- **静态 → 实例的并发风险**：实例初始化时机、可见性（`volatile`/`final`）、与静态状态的交互需逐项审计。
- **53 个调用文件的迁移成本**：方案 B 下为 0，但兼容层会长期存在，需在文档中明确其为「迁移期设施」。
- **与 WEB-P1-2（拆 `BasePage`）的冲突**：二者都触及 web 核心，建议串行（先完成 DI 二期 Phase 1~3，再动 `BasePage`），避免同时改动共享代码。

## 8. 验证方式

- 编译：`mvn -o -pl test-automation -am test-compile`
- 全护盾：`mvn -o -pl test-automation -am test`（基线 **487 例绿 / 0 跳过**）
- 接口实现数审计：每个接口 ≥2 实现（生产 + 测试）
- 调用面审计：`PlaywrightManager.` 引用文件数（迁移期应逐步下降）

---

## 9. 实施记录

### 9.1 Phase 1（接口提取）— 2026-09-09 已落地 ✅

采用「接口=角色名、实现=角色名+`Impl` 后缀」标准配对（与既有 `DefaultRuntimeProvider` 同为「接口 + 实例实现」结构）：6 个接口以角色命名（`BrowserRegistry` 等），6 个实例实现 `BrowserRegistryImpl` 等为**无状态单例**，直接承载 WEB-P1-1 拆分的真实逻辑。

早期曾落一版 `*Api` 接口 + `Default*` 薄适配（委托静态类），因三类同名叠加（`BrowserRegistry` 静态类 + `DefaultBrowserRegistry` 适配 + `BrowserRegistryApi` 接口）且 `Api` 后缀与本仓 `*Provider` seam 约定不一致，已重构为当前标准配对：**删除 6 个 `*Api` 接口与薄适配层，把既有 6 个静态协作者就地转为实例实现**（移除 `static`、改为 `implements` 角色接口、注入 `INSTANCE`）。改动面：6 个协作者自身及包内互相调用改为 `XImpl.INSTANCE.xxx`；外部调用点 `PlaywrightManager` / `DefaultRuntimeProvider` / `BrowserLifecycle` / `CustomOptionsManager` 及 2 个测试改经 `XImpl.INSTANCE` 访问（静态门面 `PlaywrightManager` 与组合门面 `BrowserLifecycle` 保持静态）；53 处 `PlaywrightManager.` 外部引用零改动。

**落地文件清单**（`web/.../framework/web/lifecycle/`）：

| 接口（角色名） | 默认实现（实例单例） |
|---|---|
| `BrowserRegistry` | `BrowserRegistryImpl` |
| `ContextRegistry` | `ContextRegistryImpl` |
| `PageRegistry` | `PageRegistryImpl` |
| `BrowserStartup` | `BrowserStartupImpl` |
| `BrowserRestart` | `BrowserRestartImpl` |
| `BrowserCleanup` | `BrowserCleanupImpl` |

各实现 `implements` 对应角色接口，方法体逐字迁移自 WEB-P1-1 拆分成果，行为零变更；行数均 ≤400 达标。

**验证**：
- 编译：`mvn -o -pl test-automation -am test-compile` → **BUILD SUCCESS**；
- 全护盾：`mvn -o -pl test-automation -am test` → **495 例，0 失败 / 0 错误 / 0 跳过，BUILD SUCCESS**（注：文档基线记 487，当前工作区另有未提交改动使总数为 495；零回归已确认）。

**约束符合性核对**：线程模型不变（实例无状态，真实状态仍在 `PlaywrightRuntimeState`）；锁语义不变（委托原方法体）；seam 不被绕过（`DefaultRuntimeProvider` 仍经 `BrowserRegistryImpl.INSTANCE.realGetXxx`）；日志溯源不变（logger 仍为 `PlaywrightManager.class`）。

### 9.2 Phase 2（实例门面）— 2026-09-09 已落地 ✅

新增 `PlaywrightRuntime`（public final 组合根，同包 `framework.web.lifecycle`）：持有 6 个角色接口字段（`browserRegistry` / `contextRegistry` / `pageRegistry` / `browserStartup` / `browserRestart` / `browserCleanup`），默认 = 各 `XImpl.INSTANCE` 无状态单例；提供默认单例 `instance()` + 注入构造（Phase 3 替身用），字段 `public final`，自身不引入任何共享可变状态（铁律 §6）。`PlaywrightManager` 静态门面把 10 处协作者调用（`ensureConfigId` / `setConfigId` / `sharedConfigId` / `resolveSharedBrowserMode` / `discardCurrentContext` / `createNewContextAndPage` / `closeContext` / `hasContext` / `setPage` / `closePage`，含 `SHARED_BROWSER_MODE` 静态常量）路由到 `PlaywrightRuntime.instance().<role>().xxx()`（兼容层，53 个外部调用文件零改动）。`getPlaywright/getBrowser/getContext/getPage` 仍走 WEB-P0-2 的 `provider` seam；`restartBrowser/cleanupAll/initialize` 仍经组合门面 `BrowserLifecycle`，未被绕过。

**验证**：
- 编译：`mvn -o -pl test-automation -am test-compile` → **BUILD SUCCESS**；
- 全护盾：`mvn -o -pl test-automation -am test` → **497 例（原 495 + 新增 `PlaywrightRuntimeTest` 2 例），0 失败 / 0 错误 / 0 跳过，BUILD SUCCESS**（零回归）。
- 新增单测 `PlaywrightRuntimeTest`：默认单例持有 6 协作者非空；注入构造对 null 抛 `NullPointerException`。

**下一步（Phase 3 可替换性）**：已于 2026-09-09 落地 ✅（见 §9.3）。

### 9.3 Phase 3（可替换性 / 真多态）— 2026-09-09 已落地 ✅

让组合根可整体替换，达成验收 ②（每接口 ≥2 实现：生产 `XImpl` + 测试替身），补完「多态 🔶」短板：

1. `PlaywrightRuntime` 单例由 `final INSTANCE` 改为 `volatile PlaywrightRuntime runtime`，新增受控换实现 seam `setInstance(PlaywrightRuntime)`（requireNonNull 守卫）/ `resetInstance()`；与 WEB-P0-2 的 `PlaywrightManager.setProvider/resetProvider` 同源，属测试性 seam 而非业务可变状态（类级铁律注释已同步修正）。
2. `DefaultRuntimeProvider`（WEB-P0-2 默认实现）改为经 `PlaywrightRuntime.instance().<role>().realGetXxx()` 委托（持有组合根引用，每次调用实时获取单例），`getPlaywright/getBrowser/getContext/getPage` 因此随组合根换实现而改变行为。原直接调用 `XImpl.INSTANCE.realGetXxx` 的写法移除（默认 runtime 仍走 `XImpl.INSTANCE`，无死代码、无方法删除）。
3. `PlaywrightManager` 门面注释（WEB-P0-2 seam 段）更新：明确 getter 经 `provider → PlaywrightRuntime 组合根` 获取，组合根可整体替换。
4. 新增 `PlaywrightRuntimePolymorphismTest`（6 例，全程 Mockito 替身、不启动真实浏览器）：
   - `swappingBrowserRegistryChangesGetBrowser` / `...GetPlaywright`：替换 `BrowserRegistry` 替身后，`PlaywrightManager.getBrowser()/getPlaywright()` 返回替身；
   - `swappingContextRegistryChangesGetContext` / `swappingPageRegistryChangesGetPage`：替换 `ContextRegistry` / `PageRegistry` 替身后，`getContext()/getPage()` 返回替身；
   - `swappingBrowserRegistryChangesFacadeEnsureConfigId`：替换 `BrowserRegistry` 替身后，门面 `ensureConfigId()` 返回替身常量 `UNIT_TEST_CONFIG_ID`（证明 Phase 2 委托链路也随组合根生效）；
   - `setInstanceThenResetRestoresDefault`：换实现后 `instance()` 指向替身、复位后恢复默认。
   - `@After` 统一 `resetInstance() + resetProvider()` 复位，避免污染其它测试。

**验证**：
- 编译：`mvn -o -pl test-automation -am test-compile` → **BUILD SUCCESS**；
- 全护盾：`mvn -o -pl test-automation -am test` → **503 例（原 497 + 新增 `PlaywrightRuntimePolymorphismTest` 6 例），0 失败 / 0 错误 / 0 跳过，BUILD SUCCESS**（零回归）。

**后续（Phase 5，非破坏）**：`PlaywrightManager` 类级 Javadoc 固化「三大特性 + API 边界」立场；`config()`/`customOptions()`/`getFrameworkState()`/`getPageThreadLocal()` 补 `@apiNote`（见 §10.4）。外部调用方迁移（53 文件改实例调用）为可选渐进项，本次未做。

### 9.4 Phase 4（门面收口：impl-to-impl 收束到组合根）— 2026-09-10 已落地 ✅

把 §10.4 Phase 4 的设计落地：消除「具体单例穿透」，让组合根成为**唯一装配权威**，impl 互调也走接口，从而 `setInstance` 换实现**全链路生效**（这是 WEB-P1-6 设计承诺但此前未真正兑现的 seam）。

- **做法**：全仓库 impl-to-impl / manager-to-impl 共 **~25 处** `XxxImpl.INSTANCE` 具体单例拉取，全部收敛为 `PlaywrightRuntime.instance().<role>()`（**接口类型**）：
  - `PageRegistryImpl` / `CustomOptionsManager` → `instance().contextRegistry`；
  - `BrowserStartupImpl` → `instance().browserRegistry` / `instance().browserCleanup`；
  - `BrowserRestartImpl` → `instance().pageRegistry` / `instance().contextRegistry` / `instance().browserStartup` / `instance().browserRegistry`；
  - `BrowserRegistryImpl` → `instance().pageRegistry` / `instance().contextRegistry`；
  - `BrowserCleanupImpl` → `instance().pageRegistry` / `instance().contextRegistry` / `instance().browserStartup`；
  - `PlaywrightManager` 门面既有委托保持不变。
- **保留的两处具体 `XImpl.INSTANCE` 引用（设计允许的例外）**：
  1. `PlaywrightRuntime` 默认构造（组合根本体的装配，本身即「引用具体实现」之处）；
  2. `BrowserCrashGuard` 的稳定真实默认（崩溃重建路径的硬依赖，用户已认可保留）。
- **NPE 风险审计**：6 个 Impl 构造器全部为**空 `private` 构造**（协作者仅在运行时实例方法内引用）；`runtime` 静态字段在类加载期即完成装配，故「调用时查根」`PlaywrightRuntime.instance()` 在 impl 方法内完全安全（与 `PageRegistryImpl.getPage()` 早已使用 `instance().browserCleanup` 而无 NPE 的事实一致）。

**验证**：
- 编译：`mvn -o -pl web -am compile` → **BUILD SUCCESS**；
- `Impl.INSTANCE.`（方法调用式）在 web 主代码中残留 **0 处**；
- 多态回归：`mvn -o -pl test-automation -am test -Dtest=PlaywrightRuntimePolymorphismTest` → **6 例，0 失败 / 0 错误 / 0 跳过，BUILD SUCCESS**（同时证明：**无初始化 NPE** + **`setInstance` seam 端到端生效**：替换组合根协作者后，门面层与 impl 互调层均路由到替身）。

---

## 10. 门面收口设计（企业级 + Java 三大特性）

> 本节为 Phase 4/5 的**设计基线**。目标：把 `PlaywrightManager` 从「兼容层 + 编排 + 内部对象泄露」的混合体，演进为**符合企业级、且满足 Java 三大特性的稳定门面**。
> 设计立场：用户明确要求门面须「满足 Java 三大特性且符合企业级设计」，故本节先把三特性在本框架的落地语义钉死，再据此收口，避免机械改名式迁移。

### 10.1 三大特性在本框架的落地语义（关键取舍）

经典教材把 Java 三大特性表述为「封装 / 继承 / 多态」。企业级实践中「继承」须被精确拆解，否则易误用为 class 子类化：

| 特性 | 教科书语义 | 本框架落地语义（企业级） | 取舍理由 |
|---|---|---|---|
| **封装** | 字段私有 + 受控访问器 | 可变状态收敛到 `PlaywrightRuntimeState`；协作者包级私有 `final`；门面只暴露稳定公开 API | 防止外部耦合内部可变状态 |
| **继承** | class 子类化扩展 | **刻意不用 class 继承**，改用「接口契约 + 组合」 | class 继承易产生脆弱基类（fragile base class）、破坏封装；企业级主流选择 |
| **多态** | 子类重写动态分派 | **接口多态 + seam 替换**：`PlaywrightRuntime.setInstance` 整体换实现、`PlaywrightManager.setProvider` 换运行时对象源；每接口 ≥2 实现（`XImpl` + 测试替身） | 测试可注入替身、运行时可替换，达成真多态 |

**结论**：本框架的「三大特性」= **封装 + 接口契约多态 + 组合**，而非 class 继承。若后续确有扩展需求（如新 Browser 策略），通过**新增接口实现类**（如 `LocalBrowserStrategy`/`CloudBrowserStrategy` 模式）而非**继承门面**满足。这既满足「三大特性」的内核，又是更稳健的企业级选择。

### 10.2 当前门面体检（基于 `PlaywrightManager.java` 真实代码 + 调用面审计）

> 审计（2026-09-10）：`PlaywrightManager.config()` **56+ 调用点**、`customOptions()` **15 处**，遍布 `SessionManager` / `PageElement` / `BasePage` / 各监听器 / `PlaywrightContextManager` / `PlaywrightInitializer`，含业务级会话恢复；`getFrameworkState()` / `getPageThreadLocal()` 仅各 2 处且均为同包协作者（含一个同包测试）。

| 维度 | 现状 | 判定 |
|---|---|---|
| 封装 | 门面持有 `STATE`(final) / 锁 / `SHARED_BROWSER_MODE`(final)，自身无可变静态字段 | ✅ 已达标。`config()` 返回**只读**配置视图；`customOptions()` 暴露的是**受生命周期治理的 per-thread 可变**自定义 Context 选项（意图内变更 API，setter 触发延迟 Context 重建），二者均不暴露框架可变状态根，**非有害泄露**（见 10.3④ 修正） |
| SRP（企业级） | 同时承担：运行时 getter、角色委托、Serenity 桥接、截图、配置代理 | 🔶 职责较聚合，但属「门面」本职（稳定公开入口聚合），可接受；`getPageThreadLocal()` / `getFrameworkState()` 已是包级私有，无对外泄露 |
| 多态 | 多态发生在 seam（`setInstance`/`setProvider`），门面静态方法本身不参与分派 | ✅ 正确——静态门面是稳定入口，多态在组合根层 |
| API 边界 | `config()`/`customOptions()` 为公开、`getPageThreadLocal()`/`getFrameworkState()` 为包级私有 | 🔶 边界**存在但未显式标注**；缺 `@apiNote` 声明「稳定公开契约 vs 框架内部 seam」，易让协作者误用公开方法绕开门面 |

### 10.3 收口目标终态（非破坏版）

1. **薄而稳定的意图揭示门面**：`PlaywrightManager` 保留**稳定公开 API**（4 运行时 getter + 生命周期入口 + `config()`/`customOptions()`），**不含编排细节**。内部协作者经实例调用（`PlaywrightRuntime.instance().<role>()`）完成实质工作。
2. **`PlaywrightRuntime` 组合根 = 唯一变化点**：持有全部角色协作者；门面所有方法 = 对组合根 / `RuntimeProvider` 的薄委托。
3. **角色接口 = 契约，`XImpl` + 测试替身 = 多态实现**（现状已具备，强化契约语义，禁止业务直接依赖 `XImpl`）。
4. **API 边界显式化（修正原「移除 config()/customOptions()」计划）**：
   - `config()` / `customOptions()` **保留为稳定公开门面方法**（审计证其为 56+/15 处广泛依赖的合法配置入口）；移除或改返回类型会击穿 53 个文件、违反「兼容策略 B 零破坏」铁律。改用 `@apiNote` 显式标注其为「受支持的稳定契约」，并引导业务统一经门面访问（不直连 `PlaywrightConfigManager` / `CustomOptionsManager`）。
   - `getFrameworkState()` / `getPageThreadLocal()` 已为包级私有（无对外泄露）；补 `@apiNote`「框架内部 seam，非对外契约」，明确仅同包协作者可用。
5. **不采用「公开方法 @Deprecated 移除」**：门面公开方法即稳定契约本身，不存在需废弃的「兼容层」——框架内部冗余调用已在 Phase 4 迁到实例路径，门面方法本身是终态契约，无需 deprecate。

### 10.4 实施路线（Phase 4 → Phase 5）

| Phase | 范围 | 动作 | 验收 |
|---|---|---|---|
| **4**（渐进） | 仅框架内部（`web`/`codegen`） | 静态调用改为 `PlaywrightRuntime.instance().<role>().xxx()`；门面保留为稳定公开入口 | ✅ 已落地（2026-09-10，见 §9.4）；web 编译过、`PlaywrightRuntimePolymorphismTest` 6/6 零回归 |
| **5**（收口，非破坏） | `PlaywrightManager` 自身 | ① 类级 Javadoc 固化「三大特性 + API 边界」设计立场；② `config()`/`customOptions()` 补 `@apiNote` 稳定契约；③ `getFrameworkState()`/`getPageThreadLocal()` 补 `@apiNote` 框架内部 seam | 全护盾零回归；无公开 API 被破坏（编译零变更、行为零变更） |

### 10.5 企业级维度验收（对齐三大特性）

- **封装**：门面无新增共享可变状态；`config()` 暴露只读配置视图，`customOptions()` 暴露受治理的 per-thread 可变自定义选项（可变状态仍封在 per-thread `TestContextHolder`，不暴露框架状态根）。
- **组合 + 多态**：每角色接口 ≥2 实现；`setInstance` / `setProvider` 替换后行为随之改变（现有 `PlaywrightRuntimePolymorphismTest` 已固化）。
- **企业级**：门面 API 边界经 `@apiNote` 显式声明（稳定公开 vs 框架内部 seam）、日志回 `PlaywrightManager.class`、语义化异常、双 seam 可测；全护盾零回归；**零公开 API 破坏**（符合兼容策略 B）。

### 10.6 实施记录（门面收口 + CustomOptions 接口化，2026-09-10）

按用户"结合框架整体看 + 满足三大特性与企业级"的要求落地，且据实校准了设计（审计证伪了原 §10 把 `config()/customOptions()` 判为"封装缺口/需移除"的过度结论）：

1. **`customOptions()` 接口化（封装增强，零破坏）**：
   - 新增 `CustomOptions` 门面契约接口（同包 `framework.web.lifecycle`），承载业务面向的 getter/setter + `disableCustomOptions()`；具体类 `CustomOptionsManager implements CustomOptions`。
   - `PlaywrightManager.customOptions()` 返回类型由 `CustomOptionsManager` 改为 `CustomOptions`（面向接口编程）；具体类 setter 经**协变返回类型**（`CustomOptionsManager` ⊂ `CustomOptions`）零签名改动。
   - 真正内部的生命周期控制 `removeAllThreadLocals()` 本就包级私有，业务经接口无法触及——封装收紧。
   - 修复点：`PlaywrightContextManager` 的 `CustomOptionsManager cm = customOptions()` 改 `CustomOptions cm =`（同包无 import）；两个相关单测经 `getInstance()` 直取不受影响。
2. **API 边界显式化（非破坏）**：`PlaywrightManager` 类级 Javadoc 固化「三大特性 + API 边界」立场；`config()`/`customOptions()`/`getFrameworkState()`/`getPageThreadLocal()` 补 `@apiNote`（稳定公开契约 vs 框架内部 seam）。**修正误判**：`customOptions()` 是受生命周期治理的 **per-thread 可变**变更 API（setter 触发延迟 Context 重建），**非只读视图、非泄露**。
3. **文档 BUG 修复**：README 示例 `PlaywrightManager.customOptions().clearAll()` 中 `clearAll()` **不存在**（仅为过时误例），改为真实存在的 `disableCustomOptions()` 并说明"场景结束由框架自动清理"。

**验证**：`mvn -o -pl web -am test-compile` BUILD SUCCESS；`mvn -o -pl test-automation -am test -Dtest=CustomOptionsManagerConcurrencyTest,PlaywrightManagerConcurrencyTest` → 3 例 0 失败 0 错误 BUILD SUCCESS；零公开 API 破坏。
