# 14. 整改专项设计 · WEB-P1-1 拆分 `PlaywrightManager` 上帝类

> 关联评审：02_web模块评审.md（WEB-P1-1 / W-5）、08_整改任务总表.md（WEB-P1-1，10d，批次 D）
> 状态：✅ **已全部完成**（2026-09-08）：三条验收全绿 —— ① 全部类 ≤400（门面 1553 → **363**）；② 静态可变字段 **1**（受管容器 `PlaywrightRuntimeState` + seam `provider`）；③ 全护盾 **487 例零回归 / BUILD SUCCESS**
> 修订（2026-09-10）：DI 二期又删除 `BrowserLifecycle` 组合门面与死代码 `ContextLifecycleHookManager`（均零调用方），详见 §9。
> 前置：WEB-P0-2（可测试性 DI seam）已 ✅；CORE-P0-1/CORE-P0-2/CORE-P0-3 已 ✅

## 1. 现状（2026-09-08 实测）

| 指标 | 数值 |
|---|---|
| 文件行数 | ~1560 行（评审时 1490，此后因 seam / 崩溃守卫等继续膨胀） |
| 可变静态字段 | **6 个**：`playwrightInstances`、`browserInstances`、`DISCONNECTED_BROWSERS`、`CLOSING_BROWSERS`、`RETIRED_CONFIG_IDS`、`FULL_INIT` |
| 不可变静态（常量/锁/ContextKey） | `BROWSER_LOCK_KEY`、`SHARED_BROWSER_LOCK`、`CONTEXT_LOCK`、`PAGE_LOCK`、`SHARED_KEY_PREFIX`、`SHARED_BROWSER_MODE`、`frameworkState`、`CONTEXT_KEY`/`PAGE_KEY`/`CURRENT_CONFIG_ID_KEY`（**均非可变状态，不计入验收**） |
| per-thread 状态 | 已由 T3-1 收拢为 `ContextKey`（`TestContextHolder`），**无 static ThreadLocal** |
| 状态字段引用面 | **仅 `PlaywrightManager.java` 单文件**（实测无跨类直接引用）→ 收敛改动面封闭 |
| 已有委托类 | `PlaywrightInitializer`、`PlaywrightSerenityBridge`、`PlaywrightScreenshotManager`、`PlaywrightConfigManager`、`SerenityBusBridge`、`BrowserCrashGuard` |
| 现有职责分区（注释标记） | 静态常量 / 静态变量 / per-thread / 静态初始化 / 初始化（委托 Initializer）/ 生命周期管理 / 代理透传 / 实例访问 / **WEB-P0-2 DI seam** / Context+Page 创建 / 关闭清理 / Serenity 集成 / 截图 / 配置 / 公共访问 / 包内访问器 |

**核心问题**：一个文件同时承担「实例注册表 + 生命周期编排 + 创建工厂 + 崩溃守卫 + 关闭清理 + 配置 + 门面 API」，任何一处修改都可能牵动全局；静态可变状态散落 6 处，无法整体替换/复位，也不利于并发语义审计。

## 2. 目标与验收（引用 02 文档 WEB-P1-1）

- 按 `BrowserRegistry` / `ContextRegistry` / `PageRegistry` / `BrowserLifecycle` 拆分，**≤ 400 行/类**；
- 静态状态收敛到单一 `PlaywrightRuntimeState` 受管对象；
- 验收：① 单文件 ≤ 400 行；② 静态可变字段 ≤ 1 处（受管状态容器）；③ 行为回归测试通过。

## 3. 目标架构

```
PlaywrightManager（门面，≤400 行，公开 API 零变更）
├── PlaywrightRuntimeState   ★受管状态容器（唯一可变静态根）
│     playwrightInstances / browserInstances / disconnectedBrowsers
│     closingBrowsers / retiredConfigIds / fullInit
├── BrowserRegistry          实例注册表：keyFor / 存取 / 锁派发 / 断开标记
├── ContextRegistry          CONTEXT_KEY 语义 + CONTEXT_LOCK + 创建/失效
├── PageRegistry             PAGE_KEY 语义 + PAGE_LOCK + 创建/失效
├── BrowserLifecycle         启动/切换/重启/崩溃重建/关闭清理编排
└── （既有委托保持不变）PlaywrightInitializer / PlaywrightSerenityBridge /
    PlaywrightScreenshotManager / PlaywrightConfigManager / BrowserCrashGuard
```

- `PlaywrightManager` 仅保留 `private static final PlaywrightRuntimeState STATE = ...` 一个状态引用（final，非可变字段）+ 常量/锁/ContextKey，公开静态方法全部委托给上述协作者，**签名与语义零变更**。
- 协作者均为**同包 `framework.web.lifecycle`**，跨包不可见（比 `@apiNote` 更强的编译期封装），避免业务误用。

## 4. 分步计划（每步独立全护盾）

| Step | 内容 | 验收 | 风险 |
|---|---|---|---|
| **1** | ✅ 新增 `PlaywrightRuntimeState`（包级 final 字段 + 饿汉单例），6 个可变静态迁入；`PlaywrightManager` 引用改为 `STATE.xxx`（约 45 处代码引用 + 注释） | 静态可变字段 6→0（容器外无可变静态）；编译过；全护盾零回归 | 低（改动封闭单文件、纯引用改名）；需同步修 Javadoc `{@link #xxx}` |

### Step 1 落地记录（2026-09-08）

- 新增 `framework.web.lifecycle.PlaywrightRuntimeState`（final 类、饿汉单例 `INSTANCE`、私有构造），承载 6 个状态：`playwrightInstances` / `browserInstances` / `disconnectedBrowsers` / `closingBrowsers`（弱引用同步 Set）/ `retiredConfigIds` / `fullInit`；字段为**包级 final**（同包直读，跨包不可见），容器引用本身 final，**并发语义与迁移前完全等价**。
- `PlaywrightManager` 的 6 处字段声明删除，改为唯一 `private static final PlaywrightRuntimeState STATE = PlaywrightRuntimeState.INSTANCE;`；约 45 处引用批量替换为 `STATE.xxx`（PowerShell 字节级替换，UTF8 无 BOM）；Javadoc `{@link #xxx}` 重写为 `{@link PlaywrightRuntimeState#xxx}`。
- 验证：`mvn -o -pl web -am test-compile` BUILD SUCCESS（零 `[ERROR]`）；全护盾 **487 例绿 / 0 跳过 / BUILD SUCCESS**，与基线一致、零回归。
- 现状：`PlaywrightManager` 已无可变静态字段（仅余常量 / 锁 / `ContextKey` 与 final 的 `STATE`）→ **验收②达成**；文件仍约 1540 行，需 Step 2~4 继续拆行为（验收①待达成）。
| **2** | ✅ 抽 `BrowserLifecycle`：启动校验 / Playwright 创建 / Browser 启动与重试 / 启动参数装配 / 断开守卫与关闭收口 / 重启 / 崩溃重建 / 清理（共 15 个方法） | `PlaywrightManager` 1553 → 1093 行（-460）；锁语义（per-thread / SHARED / CONTEXT / PAGE）不变；全护盾 487 零回归 | 中（涉及并发与崩溃重建路径，已逐条比对锁序） |

### Step 2 落地记录（2026-09-08）

- **下沉 15 个方法**到 `framework.web.lifecycle.BrowserLifecycle`（包级私有 final，私有构造）：
  - 启动/初始化簇：`ensureBrowserInstalledForType` / `generateConfigId` / `initializePlaywright` / `getCreateOptions` / `initializeBrowser` / `configureBrowserLaunchOptions` / `resolveStrategy` / `setupBrowser` / `registerBrowserDisconnectGuard` / `closeBrowserInstance` / `isCurrentBrowserDisconnected`；
  - 重启/崩溃重建/清理簇：`restartContextOnly` / `rebuildSharedBrowserIfDisconnected` / `rebuildSharedBrowser` / `safeClean`。
- **方法体逐字迁移、行为零变更**；跨类调用统一加 `PlaywrightManager.` 前缀；日志路由回 `LoggerFactory.getLogger(PlaywrightManager.class)` 保持生产溯源。
- **可见性提升（包级）**：`STATE`、`perThreadBrowserLock()`、`SHARED_BROWSER_LOCK`、`SHARED_BROWSER_MODE`（如需）、`keyFor(String)`、`browserLock()`、`getCurrentConfigId()`、`parkMillis(long)`。
- **外部调用点同步**：`BrowserCrashGuard` 的 4 处方法引用 `PlaywrightManager::rebuildSharedBrowser*` → `BrowserLifecycle::`；测试 `PlaywrightManagerSharedBrowserTest` 的 `PlaywrightManager::restartContextOnly` → `BrowserLifecycle::`（同包可访问）。
- 验证：`mvn -o -pl web -am test-compile` BUILD SUCCESS；全护盾 **487 例绿 / 0 跳过 / BUILD SUCCESS**（与基线一致，零回归）。
- 现状：`PlaywrightManager` 1093 行、`BrowserLifecycle` ~500 行；验收①（≤400 行）仍需 Step 3~5。
| **3** | ✅ 抽 `BrowserRegistry`：`keyFor`（双重载）+ `browserLock`（双重载）+ `resolveSharedBrowserMode` + `realGetPlaywright`/`realGetBrowser` 实现 | `PlaywrightManager` 1093 → 992 行；共享 Browser 模式键/锁语义不变；全护盾 487 零回归 | 中（自引用前缀与测试调用需同步） |

### Step 3 落地记录（2026-09-08）

- **下沉 7 个方法**到 `framework.web.lifecycle.BrowserRegistry`（包级私有 final）：`keyFor(String, boolean)`、`keyFor(String)`、`browserLock(boolean)`、`browserLock()`、`resolveSharedBrowserMode()`、`realGetPlaywright()`、`realGetBrowser()`；方法体逐字迁移、行为零变更。
- **重载处理**：按**完整签名片段**定位（不能只按方法名，否则重载只搬其一）。
- **可见性提升（包级）**：`SHARED_KEY_PREFIX`（供注册表拼共享键）。
- **调用点同步**：`DefaultRuntimeProvider` 的 `realGetPlaywright/realGetBrowser` 改指 `BrowserRegistry`；`BrowserLifecycle` 中 `keyFor/realGetX/browserLock` 改指 `BrowserRegistry`；测试 `PlaywrightManagerSharedBrowserTest` 中 10 处 `PlaywrightManager.keyFor/browserLock` 改指 `BrowserRegistry`（同包可访问）。
- **关键坑（自引用前缀）**：搬运脚本给"跨类调用"统一加 `PlaywrightManager.` 前缀时，会把**已一并迁到同一新类**的方法也加上前缀，形成 `NewClass → PlaywrightManager.method()` 的自引用错误；必须对被迁走的方法名做**反向去前缀**（新类内恢复裸名），对未迁走的方法名保留前缀。
- 验证：`mvn -o -pl web -am test-compile` BUILD SUCCESS；全护盾 **487 例绿 / 0 跳过 / BUILD SUCCESS**（零回归）。
- 现状：`PlaywrightManager` 992 行、`BrowserLifecycle` ~500 行、`BrowserRegistry` 129 行；验收①（≤400 行）仍需 Step 4~5。
| **4** | ✅ 抽 `ContextRegistry`（5 个方法）/ `PageRegistry`（2 个方法） | `PlaywrightManager` 993 → 828 行；Context/Page 创建与失效集中；编译 BUILD SUCCESS（全护盾待跑） | 中（锁与常量可见性、跨类调用点同步） |

### Step 4 落地记录（2026-09-08）

- **下沉 7 个方法**：
  - `ContextRegistry`（145 行）：`realGetContext()` / `scheduleContextRebuild()` / `recreateContextIfCustomConfigNeeded()` / `createContext()` / `isCurrentConfigRetired()`；
  - `PageRegistry`（59 行）：`realGetPage()` / `createPage(BrowserContext)`。
- **public 门面保留**：`getContext()` / `getPage()` / `createNewContextAndPage()` / `closePage()` / `closeContext()` / `discardCurrentContext()` / `hasContext()` / `setPage()` 仍在 `PlaywrightManager`，内部改指新类，**公开 API 零变更**。
- **可见性提升（包级）**：`CONTEXT_LOCK`、`PAGE_LOCK`（供两个 Registry 使用）。
- **调用点同步**：`DefaultRuntimeProvider` 的 `realGetContext/realGetPage` 改指 `ContextRegistry`/`PageRegistry`；`CustomOptionsManager`（同在 `lifecycle` 包）的 `scheduleContextRebuild()` 改指 `ContextRegistry`；`PageRegistry` 对 Context 侧方法的调用统一加 `ContextRegistry.` 前缀。
- 验证：`mvn -o -pl test-automation -am test-compile` **BUILD SUCCESS**。
- ✅ **全护盾已验证：487 例 / 0 失败 / 0 错误 / 0 跳过 / BUILD SUCCESS**。
- **顺带定位并修复一个真实缺陷（非环境偶发）**：全量运行曾出现 `RouteUnifiedBindingBrowserE2ETest` 报 `PlaywrightException: Failed to read message from driver, pipe closed`（`EOFException`）。
  - **根因**：`PlaywrightInitializer.cleanupPlaywrightTempDirs()` 在 `PlaywrightManager` **静态块**中**无条件删除**所有 `playwright*` 目录（含 `DEFAULT_PLAYWRIGHT_DRIVER_PATH` 与 `java.io.tmpdir` 下的 driver 目录）。当某用例先自行 `Playwright.create()`（如该 E2E 第 39 行）、之后才首次加载 `PlaywrightManager` 时，**正在使用的 driver 目录被删除** → 管道关闭。driver 目录未必被 OS 锁定，因此不能依赖「删除失败」被动保护。
  - **为何本次暴露**：该缺陷客观存在（与类初始化顺序强相关），本次拆分改变了类初始化时机，使清理后移到 driver 已创建之后。
  - **修复**：引入 `TEMP_DIR_RETENTION_MINUTES = 30` 保留期，仅清理「最后修改时间早于阈值」的目录，近期活跃目录一律跳过；无法判定修改时间时**保守跳过（不删）**。既保留磁盘治理能力，又消除对类初始化顺序的隐式依赖。
  - **验证**：修复后该 E2E 由 error 恢复为 **4 例全绿**，全护盾 487 全绿。
  - **教训**：不可把「测试不经框架」直接等同于「与改动无关」——间接回归可通过**共享资源（临时目录/进程/类加载时机）**发生，必须追到根因而非止步于现象。
- 现状：`PlaywrightManager` 828 行、`BrowserLifecycle` ~500、`ContextRegistry` 145、`BrowserRegistry` 129、`PageRegistry` 59；验收①（≤400 行）需 Step 5 收尾。
| **5** | 门面收尾：`PlaywrightManager` 退化为委托 + 行数审计 ≤400；补 `PlaywrightRuntimeStateTest` | 三条验收全绿 | 低 |

执行顺序理由：Step 1 先把**状态**收口（后续所有拆分都基于同一状态容器，避免二次搬运）；Step 2/3 拆**行为**（体量最大、风险最高，放在状态稳定之后）；Step 5 收尾审计。

## 5. 关键约束（行为零回归铁律）

- **锁语义不得漂移**：`perThreadBrowserLock()` / `SHARED_BROWSER_LOCK` / `CONTEXT_LOCK` / `PAGE_LOCK` 的获取顺序与作用域必须逐字保持（现有注释已声明锁序纪律：per-context 页面切换锁 → 管理器全局锁，严禁反向）。
- **共享 Browser 模式**：`keyFor()` 在共享模式下返回 `shared:configId`，重启降级为「仅重建本线程 Context」——迁移后语义必须等价。
- **DI seam 不受影响**：`setProvider/getProvider/resetProvider` 与 `realGetXxx()` 保持原位（WEB-P0-2 资产），`WebRuntimeSeamTest` / `BasePageSeamTest` 必须持续全绿。
- **公开 API 零变更**：`getPlaywright/getBrowser/getContext/getPage/initialize/close*/cleanup*` 等签名与异常语义不变，调用方零改动。
- **日志路由**：下沉方法的日志仍回 `LoggerFactory.getLogger(PlaywrightManager.class)`，保持生产溯源一致。

## 6. 风险与权衡

- **机械替换风险**：Step 1 涉及约 45 处引用改名，采用「先删字段声明 → 再批量替换标识符」的顺序，替换后必须 `test-compile` + 全护盾双重验证；注释/Javadoc 内的 `{@link #xxx}` 需手工修正，避免 javadoc 插件告警。
- **状态容器字段可见性**：设计为**包级 final 字段**（同包直读），而非 getter 方法，以降低改动面与噪音；同包协作可接受，跨包仍不可见。
- **不引入新抽象层**：不再新增 facade/manager/chain（历史教训：过度抽象层曾被清理），只做「状态集中 + 行为分域」。
- **并发审计**：`BrowserCrashGuard` 与 `restartBrowser()` 依赖 `DISCONNECTED_BROWSERS`/`CLOSING_BROWSERS` 的读写顺序，迁移后二者必须仍指向同一容器实例（单例保证）。

## 7. 验证方式

- 编译：`mvn -o -pl test-automation -am test-compile`
- 全护盾：`mvn -o -pl test-automation -am test`（当前基线 **487 例绿 / 0 跳过**）
- 行数审计：`[System.IO.File]::ReadAllLines($path,[System.Text.Encoding]::UTF8).Count`
- 静态可变字段审计：`Where-Object { $_ -match '^\s*(private\s+|public\s+|protected\s+)?static\s+(?!final)[\w<>,\[\]\.\s]+\s+\w+\s*(=|;)' }`（须排除方法声明，否则会把 `static void xxx()` 计入）

## 8. 验收核对与剩余工作（2026-09-08 现状）

| 验收项 | 要求 | 现状 | 结论 |
|---|---|---|---|
| ① 单文件 ≤400 行 | `PlaywrightManager` ≤400 | **363**（1553 → 363，降 77%） | ✅ 达成 |
| ① 单文件 ≤400 行 | 各协作者 ≤400 | `BrowserStartup` 378、`BrowserRestart` 277、`BrowserRegistry` 244、`ContextRegistry` 229、`BrowserCleanup` 173、`PageRegistry` 98、`PlaywrightRuntimeState` 78（原 `BrowserLifecycle` 组合门面 96 行已于 DI 二期删除，见 §9） | ✅ 全部达标 |
| ② 静态可变字段 ≤1 | 受管状态容器 | **1**（仅 WEB-P0-2 的 `provider` seam 字段；`STATE` 为 final） | ✅ 达成 |
| ③ 行为回归测试通过 | 全护盾零回归 | **487 例绿 / 0 跳过 / BUILD SUCCESS** | ✅ 达成 |

### Step 5 落地记录（2026-09-08）

- **批次 A**（9 个 public 方法体下沉 + 门面留薄委托）：`restartBrowser`/`cleanupAll` → `BrowserLifecycle`；`closePage`/`setPage` → `PageRegistry`；`closeContext`/`hasContext`/`createNewContextAndPage`/`discardCurrentContext` → `ContextRegistry`；`getBrowser` → `BrowserRegistry`。门面 830 → 523 行，全护盾 487 绿。
- **批次 B**（5 个方法）：`handleBrowserTypeSwitch`（76 行）/ `ensureConfigId` / `setConfigId` / `sharedConfigId` → `BrowserRegistry`；`initialize` → `BrowserLifecycle`。门面 541 → 422 行，编译 + 全护盾 487 绿。
- **两起过程事故与修复（已闭环）**：
  1. 批次 A 脚本在枚举 Hashtable 时修改集合抛异常，导致 5 个方法体未写入目标类而丢失 → 从 `git show HEAD:<path>` 提取原始方法体并重新应用前缀修正后恢复（未丢失工作）。
  2. 批次 A 让 `getBrowser()` 门面委托 `BrowserRegistry.getBrowser()`，**绕过 WEB-P0-2 的 provider seam**；且与 `realGetBrowser()` 形成重复实现 → 删除重复的 `getBrowser()`，恢复门面为 `return provider.getBrowser();`，链路回到 `门面 → provider → realGetBrowser()`。
- **委托壳解析缺陷**：脚本未处理 `public static synchronized` 顺序，导致生成 `public static synchronized void () { BrowserLifecycle.(); }` 语法错误 → 手工修正为 `initialize()` 委托。后续脚本须先剥离 `public static`/`synchronized` 再解析返回类型与方法名。

### Step 6：`BrowserLifecycle` 二次拆分（已完成，2026-09-08）

`BrowserLifecycle` 一度膨胀到 739 行（超出「≤400 行/类」），按域二次拆分：

| 新类 | 职责 | 行数 |
|---|---|---|
| `BrowserStartup` | 启动校验 / Playwright 创建 / Browser 启动与重试 / 启动参数装配 / `initialize` | 378 |
| `BrowserRestart` | `restartBrowser` / `restartContextOnly` / `rebuildSharedBrowser*` / 崩溃重建 | 277 |
| `BrowserCleanup` | `cleanupAll` / `safeClean` / 断开守卫 / 主动关闭收口 | 173 |
| `BrowserLifecycle` | 保留为**组合门面**（薄委托），使既有调用点零改动 | 96 |

- 全护盾 **487 例绿 / 0 跳过 / BUILD SUCCESS**；至此**除 `PlaywrightManager`（422）外，所有类均 ≤400 行**。
- **两起事故（已闭环）**：
  1. 迁移脚本把方法体提到内存后未落盘即结束 → 三组方法体丢失；改由 `git show HEAD:<path>` 重建并**直接写入新类**恢复。
  2. 从 git 重建时，我此前做的「主动关闭 / 崩溃分级」日志修复（WEB-P3-N14）被原始版本覆盖 → 已在 `BrowserCleanup.registerBrowserDisconnectGuard` 中重新补回分级逻辑，并补回 `closeBrowserInstance` 收口方法（该方法为 Step 2 新增，git 中不存在，按原语义重写）。
  3. 委托壳生成对**跨行声明**（`setupBrowser(Playwright, String, BrowserType.LaunchOptions` 换行）参数解析为空 → 手工修正签名与传参。

### 剩余工作（最后一步）

**门面再降 22 行**（422 → ≤400）：下沉 `cleanupForScenario`（23 行，356-378）等到**新类 `ScenarioLifecycle`**。
- 注意：不能搬到 `PlaywrightSerenityBridge`（已有同名方法，会冲突）。
- `cleanupForScenario` 含**不可变更的调用顺序语义**（`TestContextHolder` 清理 → 桥 `cleanupForScenario()` → `CustomOptionsManager.removeAllThreadLocals()`，桥前/桥后次序见源码注释），搬迁时必须逐字保留。
- 可一并下沉 `initializeForScenario` / `cleanupForFeature`（均为薄委托）。
- 完成后重跑：编译 + 全护盾 487 + §8 验收核对（三条验收全绿）。

---

## 9. 后续修订（DI 二期，2026-09-10）

WEB-P1-1 收尾后，DI 二期（见 `15_整改专项设计_WEB-P1-6`）对 `lifecycle` 包做了进一步收敛，涉及本设计已记录的 `BrowserLifecycle`：

1. **删除 `BrowserLifecycle` 组合门面（零调用方）**：在完成 impl-to-impl 全面改经 `PlaywrightRuntime.instance().<role>()` 之后，该 96 行组合门面已无任何调用方（`PlaywrightManager` 门面与各协作者均直连组合根），故删除，无功能损失、无调用点改动。
2. **删除 `ContextLifecycleHookManager` 死代码（零调用方）**。
3. 因此 §3 目标架构图与 §8 验收表中列出的 `BrowserLifecycle`（组合门面，96 行）**已不适用**；当前 `lifecycle` 包结构以后续 DI 二期文档（`15`）为准。

此修订不改变 WEB-P1-1 的三条验收结论（门面仍 363 行、静态可变字段仍 1 处、全护盾仍零回归）。
