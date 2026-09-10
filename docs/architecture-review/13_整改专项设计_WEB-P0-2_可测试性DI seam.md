# 13. 整改专项设计 · WEB-P0-2 可测试性 DI seam

> 关联评审：02_web模块评审.md（WEB-P0-2 / W-5）、08_整改任务总表.md（WEB-P0-2）
> 状态：✅ 已落地（2026-09-08，全护盾 479 零回归，详见 §8 落地记录）
> 前置：CORE-P0-1（core 去 Serenity，ConfigResolver SPI）已 ✅；本项在 web 层建立 DI seam，是 WEB-P1-1/P1-2 拆上帝类的安全网前置

## 1. 背景与问题

- **W-5（阻断级质量风险）**：`PlaywrightManager` / `FrameworkCore` / `ConcurrencyGate` 全静态、零 DI，设计上不可测。`web` 模块是项目能力重心（~40% 代码）却是质量洼地（W-28：仅 3 个测试、零业务逻辑覆盖）。
- **BasePage 构造触发全局初始化**：`BasePage()`（BasePage.java:102-107）构造时强制 `FrameworkCore.getInstance().initialize()`（若未初始化），即 `new BasePage()` 会启动真实浏览器——无法在单测中构造 PageObject。
- **getter 入口硬依赖真实运行时**：`PlaywrightManager.getPlaywright()/getBrowser()/getContext()/getPage()`（647/661/793/929）是全局唯一获取入口，内部直接创建/持有真实 Playwright 对象，无任何替换点。
- **02 文档结论**：先做 WEB-P0-2（可测试性 seam）再谈任何拆分——没有 seam 的情况下拆上帝类等于空中换引擎，拆分引入的缺陷无测试可发现。

## 2. 目标与验收（引用 02 文档 WEB-P0-2）

- 抽出 `BrowserProvider` / `ContextProvider` / `PageProvider` 接口，`PlaywrightManager` 静态门面保留并委托给可替换的默认实现；
- `BasePage` 构造不再触发全局 `FrameworkCore.initialize()`；
- 验收：① 可通过 `setProvider()` 注入 mock；② 至少 `PageWaits` / `PageNavigation` / `PageLifecycleCoordinator` / `ConcurrencyGate` 可脱离真实浏览器单测；③ 现有 API 100% 向后兼容。

## 3. 设计

### 3.1 Provider 接口（public，`framework.web.core`）

聚合为单一 `RuntimeProvider`（便于单入口 `setProvider`），并拆分子接口以贴合评审命名：

```java
public interface BrowserProvider { Playwright getPlaywright(); Browser getBrowser(); }
public interface ContextProvider { BrowserContext getContext(); }
public interface PageProvider { Page getPage(); }
public interface RuntimeProvider extends BrowserProvider, ContextProvider, PageProvider {}
```

### 3.2 默认实现 `DefaultRuntimeProvider`（thin adapter，`framework.web.lifecycle`）

- 单例 `INSTANCE`（不可变），方法体委托 `PlaywrightManager` 的**包级私有真实方法** `realGetPlaywright()/realGetBrowser()/realGetContext()/realGetPage()`，**不搬运 77KB 真实逻辑**。
- 作用：把"真实浏览器逻辑"收敛到一个可替换的实现对象，使 `PlaywrightManager` 公开 getter 退化为纯委托门面。

### 3.3 `PlaywrightManager` 改造（最小侵入）

- 新增字段：`private static volatile RuntimeProvider provider = DefaultRuntimeProvider.INSTANCE;`
- 新增 API：`public static void setProvider(RuntimeProvider)` / `getProvider()` / `resetProvider()`（测试复位默认）。
- 4 个 getter 改为委托：

  ```java
  public static Page getPage() { return provider.getPage(); }
  ```

- 原 getter 主体下沉为包级私有 `realGetPage()` 等（DefaultRuntimeProvider 调用）；公开签名/行为不变。
- **线程安全**：`provider` 字段 `volatile` 保证跨线程可见；`setProvider` 仅原子换引用；默认 `INSTANCE` 不可变，生产路径零额外开销、无写竞争。

### 3.4 `BasePage` 构造改造

- 移除 `BasePage()` 中的 `if (!FrameworkCore.getInstance().isInitialized()) initialize();` 强制调用。
- 构造经 `PlaywrightManager.getPage()`（委托 provider）惰性获取 page；业务运行时由 Serenity listener（`beforeTest`→`FrameworkCore.beforeTest`）保证已初始化，单测经 `setProvider(mock)` 返回 mock page，从而**不触发 `FrameworkCore.initialize()`**。
- 向后兼容：业务路径 page 获取经默认 provider = 原真实逻辑，行为等价。

## 4. 线程安全 / 边界 / 错误处理

- `provider` 字段 `volatile` + 原子引用替换，满足可见性与原子性（memory model 同 `ListenerRegistry.initialized` 治理模式）。
- `provider` 永不为 null（默认 `INSTANCE` 常量），getter 无 NPE 分支。
- `setProvider(null)` 显式拒绝（抛 `IllegalArgumentException`），防误用。

## 5. 可观测 / 测试

- 新增 `WebRuntimeSeamTest`（test-automation，`framework.web.lifecycle` 同包或独立 seam 包）：
  - `setProvider(mockProvider)` → `PlaywrightManager.getPage()` 返回 mock `Page`（证明 seam 可注入）；
  - `resetProvider()` 复位默认，避免污染后续测试；
  - （扩展）`PageWaits`/`PageNavigation` 经 mock `Page`/`Locator` 脱离浏览器单测（二者若内部依赖 `PlaywrightManager.getPage()`，则 seam 直接解锁；若已接收 `Page` 参数则本就单测友好，seam 提供统一替换点）。
- 全护盾零回归：默认 `DefaultRuntimeProvider` 委托原逻辑，调用方零改动。

## 6. 风险与权衡

- **循环依赖**：`DefaultRuntimeProvider` → `PlaywrightManager.realGetX()`（包级私有），`realGetX` 不回调 `provider` → 无环；不触发 G1（web 内部 slice）。
- **向后兼容**：getter 签名不变；`setProvider/getProvider/resetProvider` 为新增测试 API，不影响业务。
- **Serenity 集成**：listener 仍 `FrameworkCore.beforeTest` 初始化，PlaywrightManager 真实路径不变；core 去 Serenity（CORE-P0-1）已落地，本 seam 不引入 Serenity 依赖。
- **范围控制**：本期仅建 seam（静态 getter 经 provider 可测），不拆上帝类（WEB-P1-1）；`PageWaits` 等单测改造留给 WEB-P1-5，本期用 `WebRuntimeSeamTest` 固化 seam 本身。

## 7. 迁移步骤

1. 新增 `RuntimeProvider` / `BrowserProvider` / `ContextProvider` / `PageProvider` 接口（`framework.web.core`）；
2. 新增 `DefaultRuntimeProvider`（`framework.web.lifecycle`，委托 `realGetX`）；
3. `PlaywrightManager`：4 getter 委托 + 包级私有 `realGetX` + `provider` 字段 + `setProvider/getProvider/resetProvider`；
4. `BasePage` 构造去 `FrameworkCore.initialize()`；
5. 新增 `WebRuntimeSeamTest`；
6. `mvn -o -pl test-automation -am test` 全护盾零回归。

## 8. 落地记录（2026-09-08）

状态：✅ **已落地**。全护盾 `mvn -o -pl test-automation -am test` **479 例零回归、BUILD SUCCESS**；`mvn -o -pl test-automation -am test-compile` 8 模块全绿；Enforcer（RequireUpperBoundDeps + BanDuplicatePomDependencyVersions）通过。

落地清单（对齐 §7 迁移步骤）：

| 步骤 | 产出 | 位置 |
|---|---|---|
| 1 | `BrowserProvider` / `ContextProvider` / `PageProvider` 三接口 + 组合接口 `RuntimeProvider`（`extends` 前三者） | `framework.web.core` |
| 2 | `DefaultRuntimeProvider`（包级私有 final、单例 `INSTANCE`，委托 `realGetXxx`，不搬运 77KB 逻辑） | `framework.web.lifecycle` |
| 3 | `PlaywrightManager`：`provider` volatile 字段 + `setProvider/getProvider/resetProvider` + 4 个 getter 退化为委托 + 包级私有 `realGetPlaywright/realGetBrowser/realGetContext/realGetPage` | `framework.web.lifecycle` |
| 4 | `BasePage()` 构造移除 `FrameworkCore.getInstance().initialize()` 强制初始化（及对应 import） | `framework.web.page.base` |
| 5 | `WebRuntimeSeamTest`（3 例：注入 mock 生效 / `resetProvider` 复位默认 / `setProvider(null)` 被拒）+ `BasePageSeamTest`（4 例：seam 打通 `BasePage` 真实构造路径，见下） | `test-automation` · `framework.web.lifecycle` / `framework.web.page.base` |
| 6 | 全护盾 483 绿、0 跳过（基线 476 + 新增 7） | — |

关键实现说明：

- **单一注入点 vs 三接口**：三接口已按 §3.1 全部抽出以满足验收；`setProvider` 仍只接受组合接口 `RuntimeProvider`，避免三个 setter 之间的状态协调（例如只替换 Page 却残留真实 Browser 导致半真半假的运行态）。
- **零行为变更**：4 个 getter 公开签名与返回语义不变；生产路径 `DefaultRuntimeProvider → realGetXxx` 即原逻辑，全部调用方零改动。
- **线程安全**：`provider` 为 `volatile`，`setProvider` 仅做原子引用替换；`INSTANCE` 不可变；`setProvider(null)` 抛 `IllegalArgumentException`（语义化校验，非裸 NPE）。
- **验收 ② 补齐（`BasePageSeamTest`，4 例）**：既有 `PageWaitsTest` / `PageNavigationTest` / `PageLifecycleCoordinatorTest` / `ConcurrencyGateTest` 虽然已能脱离浏览器运行，但它们用 `mock(BasePage.class)`（Objenesis 绕过构造）——改造前 `new BasePage()` 会强制 `FrameworkCore.initialize()` 启动浏览器，**这条路径此前无从单测**。新增 `BasePageSeamTest` 走**真实构造 + 真实 `getPage()`**链路：① 构造不获取任何 Playwright 运行时对象（`verify(provider, never()).getPlaywright/getBrowser/getContext/getPage()`，该断言不依赖全局初始化状态，故不会被 skip）；② `getPage()` 返回 seam 注入的 mock Page；③ `PageWaits.waitForNetworkIdle` 在 seam page 上真实执行并 `verify` 到 `waitForLoadState(NETWORKIDLE)`；④ `PageNavigation.getCurrentUrl` 读到 seam page 的 URL。至此验收 ② 四个类全部可脱离真实浏览器单测。
- **日志噪音（已知、非缺陷）**：`SerenityBusBridge.markFailure` 在单测（无 Serenity runner / 未注册 `BaseStepListener`）下调用 `StepEventBus.testFailed` 时，Serenity 内部会 `Thread.dumpStack()` 打印栈到 **stderr**（非异常、不影响结果）；该调用已被 `catch (Throwable)` 安全降级为 `LOGGER.debug`，故 Serenity 回放通道的异常在默认日志级别下不可见——属刻意设计，代价是通道故障时测试察觉不到。
- **未做（留给后续）**：Q2 的 ArchUnit 规则（禁止业务代码调用 `setProvider` / 依赖实现类）本期未加，暂以 Javadoc `@apiNote` 约束，建议随 ENG-P1-5（ArchUnit 补盲区）一并落地；Q3 的 `PageWaits`/`PageNavigation` 构造注入演进属 WEB-P1-5 范围，本期仅提供统一替换点。

## 9. 评审待确认（Q）

- **Q1**：Provider 接口公开粒度？建议 `public`（测试需注入 mock）；门面 `setProvider` 也 `public`（测试 seam）。若担心业务误用，在 Javadoc `@apiNote` 标注"仅测试注入，生产走默认"。
- **Q2**：是否新增 ArchUnit 规则固化"业务代码不得直接实例化 `DefaultRuntimeProvider` / 不得调用 `setProvider`"？建议加 `web 模块业务不得依赖 RuntimeProvider 实现类` 规则（可选，强化 seam 边界）。
- **Q3**：`PageWaits`/`PageNavigation` 等后续是否改为构造注入 `Page`（而非静态 getter）？本期仅 seam（静态 getter 经 provider 可测），WEB-P1 拆分时再演进为接收 `Page` 参数（更利于单测与解耦）。
