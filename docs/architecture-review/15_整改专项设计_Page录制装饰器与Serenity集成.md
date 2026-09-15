# 15 整改专项设计：Page 录制装饰器与 Serenity BDD 集成

> 关联：04 §2.8（目标模型）、13 G-9（进行中）、14 WEB-P1-2 后续演进
> 状态：设计评审稿（未实现）

---

## 0 背景与动机

`SerenityBasePage`（`web/.../page/base/impl/SerenityBasePage.java:53`）继承 `BasePage`，把 Serenity BDD 报告能力以"基类 `@Override` + `record`/`recordAndReturn` 拦截器"形式硬塞进页面层级：
- 约 40 处 `@Override`（`getPage`/`getContext`/`navigateTo`/`refresh`/`back`/`switchToPage`/`closeCurrentPage`/`pause` 等，`:146-294`）+ `byRole`/`byText`/`byAltText` 家族（`:578-677`）逐个包一层 `addSerenityTestData`；
- `verifyPageTitleContains`/`verifyUrlContains` 等重复"刷新待报告 API 数据 + 记录"模板（`:208-251`）；
- `record`/`recordAndReturn`/`recordVerification` 私有拦截器（`:95-126`）。

**根因**（用户洞察）：
1. `BasePage.page` 本身就是 `com.microsoft.playwright.Page` 接口实例（`BasePage.java:47`），当年实现却未把它当"Playwright `Page` 接口"利用，而是自己重造了一层 `@Override` 录制。
2. 封装的 Page 里有一大批方法是 **Playwright `Page` 接口根本没有的**（框架自有增值方法），不能简单用"实现 `Page` 接口的装饰器"一刀切替代——必须划清边界。

**目标**：Serenity 录制从"基类方法"收敛为"两层录制切面"——原生操作由 `Page` 动态代理装饰，框架自有方法由可复用录制助手在调用处完成；业务 Page 对象零继承、纯 POJO。

---

## 1 设计目标与原则

| 编号 | 目标 | 说明 |
|---|---|---|
| G1 | 业务 Page 零继承 | POJO + 组合，彻底移除 `extends BasePage` / `SerenityBasePage` 约束 |
| G2 | 录制对业务透明 | 业务不重写 `@Override`，不手写录制；原生操作经装饰 `Page` 自动录制 |
| G3 | 划清两层边界 | 原生操作（Layer A）走代理；框架自有方法（Layer B）走可复用助手 |
| G4 | 零开销开关 | reporting 关闭时返回裸 `Page`，不实例化代理、不写 HashMap |
| G5 | 线程安全 | 代理无状态；真实 `Page` 本身 per-thread（ContextKey）；录制写 ThreadLocal `StepEventBus` 仅测试线程 |
| G6 | 可测试性 | WEB-P0-2 provider seam 注入 mock `Page` 时不包装，测试零污染 |
| G7 | 企业级 API 边界 | 录制助手 `framework-internal` + `@apiNote` + ArchUnit 守护 |
| G8 | 行为零回归 | 专属 UT + 全护盾（600+ 用例 0 失败） |

---

## 2 范围边界（两层录制）

**Layer A — Playwright 原生操作（`Page`/`Locator`/`Frame`/`ElementHandle`/`Response`/`Request` 接口方法）**
由动态代理装饰录制：`navigate` / `click` / `fill` / `getTitle` / `url` / `waitForLoadState` / `evaluate` / `screenshot` / `getByRole` / `getByText` / `locator(...)` 等。

**Layer B — 框架自有方法（Playwright 无对应，组合式工具类承载 + 可复用助手录制）**
`SerenityBasePage` 比工具类**仅多做的"录制"**由 `SerenityRecorder.record(...)` 在调用处完成：
- `element()`（返回框架 `PageElement` 包装）、`byRole`/`byText`/`byAltText` 家族（含 `@RoleElement` 多语言 + `State` 三态）
- `shouldBeVisible` / `shouldBeNotVisible` / `verifyPageTitleContains` / `verifyUrlContains`
- `navigateToWithRetry` / `retry` / `retryWithValidation`
- `switchToShadow` / `switchToDefaultShadow` / `dumpAccessibilityRoles`
- `waitForNetworkIdle` / `waitForPageFullyLoaded` / `waitForDOMContentLoaded`
- Serenity 测试数据访问器（`addSerenityTestData` / `getSerenityTestData` 等）

> 上述 Layer B 实现体已下沉到工具类（`LocatorFactory`/`PageWaits`/`PageInteractions`/`PageFrameShadow`/`PageAccessibility`/`CookieManager`/`PageNavigation`，WEB-P1-2 成果），删除 `SerenityBasePage` 后无功能丢失。

---

## 3 架构

```
业务 Page POJO ──持有──> [装饰 Page]  (RecordingPageProxy 动态代理, 仅 enabled 时)
                              │ 原生操作 click/fill/navigate/getByRole...
                              ▼
                         [真实受管 Page]  (PlaywrightManager.getPage(), per-thread)
                              │
        Layer B 调用 ──> [工具类 LocatorFactory/PageWaits/...] ──调──> SerenityRecorder.record()
                              │                                            │
                              └──────────── 均 ─────────────────────────> SerenityReporter (sink)
                                                  flushPendingApiOperations + StepEventBus 录制
```

**组件**
- `SerenityRecorder`（final facade，`web/page/recording`，包级私有）：静态 `record` / `recordAndReturn` / `recordVerification` + `isEnabled()`；委托 `SerenityReporter.flushPendingApiOperations()` 与 Serenity 步骤写入。
- `RecordingPageProxy`（final，`web/page/recording`）：JDK 动态代理 `InvocationHandler`，包装 `Page`/`Locator`/`Frame`/`ElementHandle`/`Response`/`Request`（均为接口）。Handler：enabled 且非 `Object` 方法且非 `on*` 监听注册 → 录制后委托；可录制的返回值递归包装。
- `PageObjectFactory`（既有）：enabled 且非 mock 时注入装饰 `Page`，否则返回裸 `Page`。
- `SerenityReporter`（既有 sink，`framework.common.reporting`）：`flushPendingApiOperations()` + 报告写入。

---

## 4 关键决策与理由

- **D1 动态代理 > 手写 `implements Page`**：Playwright `Page` 接口方法约 100+，手写装饰器产生海量样板且随 Playwright 版本脆弱；JDK 动态代理单一 `InvocationHandler` 抗新增方法、DRY。依赖 `Page`/`Locator` 为接口（Playwright for Java 确为接口）——非接口返回类型按 R3 仅包装已知接口集合。
- **D2 仅包装接口类型**：`Page`/`Locator`/`Frame`/`ElementHandle`/`Response`/`Request`/`APIResponse` 包装；数据类（`BoundingBox`/`Cookie`/`ConsoleMessage` 等）不包装（无可录制的下钻方法）。
- **D3 `onXxx` 监听注册不包装**：`page.onLoad`/`onResponse`/`onPageError` 等直接委托真实 `Page` 并返回真实注册对象，避免干扰 Playwright 回调；事件级诊断由既有 `PageEventMonitor` 负责，不在此代理。
- **D4 零开销开关**：`SerenityRecorder.isEnabled()` 基于 reporting 开关（沿用 `VerboseLogging.isVerboseEnabled()` 现状语义）；关闭时 `PageObjectFactory` 直接返回裸 `Page`，代理类不实例化；`serenityTestData` HashMap 仅在 verbose 写入（沿用现状，不扩大）。
- **D5 线程安全**：代理无状态（仅持真实 `Page` 引用 + 委托），真实 `Page` 本身 per-thread（ContextKey 承载）；录制写 Serenity `StepEventBus`（ThreadLocal）仅在测试线程，安全；代理不引入任何共享可变状态。
- **D6 可测试性**：WEB-P0-2 `setProvider(mock)` 返回 mock `Page` 时，`PageObjectFactory` 识别 mock（`instanceof Proxy` 或显式标记）跳过包装 → 测试不污染，既有 `BasePageSeamTest` 等不受影响。新增 `RecordingPageProxyTest`（Mockito：验证 record 被调用 + 真委托）。
- **D7 API 边界**：`SerenityRecorder` 包级私有 + `@apiNote("framework-internal, 业务不得直接调用录制")`；建议新增 ArchUnit `businessCodeMustNotUseSerenityRecorder`。业务 Page 录制通过持有的装饰 `Page` 透明获得。
- **D8 错误处理**：录制失败不得影响业务——`record` 内 try/catch，异常降级 `logger.debug`（沿用 `SerenityBasePage` 现状）；业务语义异常（`ElementException`/`NavigationException` 等）原样透传。
- **D9 日志路由**：`SerenityRecorder` 用自身 `LoggerFactory.getLogger(SerenityRecorder.class)`；业务语义日志由被包装操作自身产生，保持溯源一致。

---

## 5 接口契约（草稿）

```java
package com.hsbc.cmb.hk.dbb.automation.framework.web.page.recording;

/**
 * framework-internal：业务 Page 不得直接调用，录制经装饰 Page 透明获得。
 * 实例（per-page 测试数据存储，供 SerenityBasePage 委托）+ 静态（Layer A 原生录制，供代理使用）。
 */
public final class SerenityRecorder {
    // 实例（per-page 测试数据存储）
    void record(String action, Object detail, Runnable op);
    <T> T recordAndReturn(String action, Object detail, Supplier<T> op);
    void recordVerification(String name, boolean passed);
    void addSerenityTestData(String key, Object value);
    Object getSerenityTestData(String key);
    Map<String, Object> getSerenityTestDataMap();
    void clearSerenityTestData();
    // 静态（Layer A 原生录制，零 per-page 状态）
    static boolean isEnabled();
    static void recordNative(String action, Object detail, Runnable op);
}

/** 动态代理：仅 enabled 且非 mock 时包装（mock 识别在 PageObjectFactory，Phase 2）。 */
public final class RecordingPageProxy {
    static Page wrap(Page real);                       // enabled? wrap : real
    static <T> T wrapIfRecordable(Object ret, Class<T> iface);
}
```

> **Phase 0/1 实现说明**：`SerenityRecorder` 采「实例门面（per-page 测试数据）+ 静态录制（Layer A）」混合形态，
> 而非设计稿原写的纯静态——因 `serenityTestData` 为 per-page 状态且需被跨包（{@code impl}）的
> `SerenityBasePage` 与（未来）`PageObjectFactory`/代理复用，故为 `public final`。录制语义与逐字迁移版一致。

`PageObjectFactory.getPage` 注入点（示意）：

```java
Page managed = PlaywrightManager.getPage();           // per-thread 受管 Page
Page injected = (SerenityRecorder.isEnabled() && !isMock(managed))
        ? RecordingPageProxy.wrap(managed) : managed;
roleElementBinder.bind(pageObject, injected);          // 业务 Page 持有装饰 Page
```

---

## 6 迁移路径（分阶段，保行为零回归）

- **Phase 0**：从 `SerenityBasePage` 提升 `record`/`recordAndReturn`/`recordVerification` 为 `SerenityRecorder` 包级私有静态（行为逐字迁移）；`SerenityBasePage` 暂仍调用之，零回归。
- **Phase 1**：实现 `RecordingPageProxy` 动态代理（Page/Locator + 递归包装）；专属 UT `RecordingPageProxyTest`（原生 click/fill/navigate 录制、递归包装 `locator.click`、mock 不包装、`onXxx` 不包装）。
- **Phase 2**：`PageObjectFactory` 注入装饰 `Page`（enabled 开关）；**试点 `RouteDemoPage`**（最简单、无 `@RoleElement`/UI 方法）走组合式 + 装饰 `Page`，验证录制链路与 Serenity 报告。
- **Phase 3**：Layer B 框架自有方法调用处改用 `SerenityRecorder.record(...)`（把 `SerenityBasePage` 的 `@Override` 方法体搬入对应工具类 / 业务 Page 调用处）。
- **Phase 4**：删除 `SerenityBasePage`；存量业务 Page（`LoginPage`/`HomePage`/`BaiduPage`/`PickerPage`/`E2ESandboxPage` 等）逐步去 `extends`，改组合式；新增 ArchUnit `businessCodeMustNotExtendBasePage`。
- **Phase 5**：闭环文档——04 §2.8 状态改"已落地"，13 G-9 关闭，14 WEB-P1-2 更新。

---

## 7 风险与缓解

| 风险 | 缓解 |
|---|---|
| R1 `equals`/`hashCode`/`toString` 被代理 | Handler 对 `Object` 方法直接委托真实对象、不做录制；`equals` 比较 underlying |
| R2 递归包装代理链过深 / 返回 null | null 直接返回；已是代理则跳过；仅包装已知接口集合 |
| R3 Playwright 内部类型非接口 | 只包装已知接口（`Page`/`Locator`/`Frame`/`ElementHandle`/`Response`/`Request`/`APIResponse`），其余直接返回 |
| R4 代理使 `instanceof PageImpl` 失败 | 核查框架无 `instanceof PageImpl` 依赖（page 公开 API 不泄漏 Playwright 实现类，内部仅用 `Page`/`Locator` 接口） |
| R5 Serenity `StepEventBus` 异步线程写入 | 沿用 `flushPendingApiOperations` 在主（测试）线程录制，事件回调层不在此代理 |
| R6 性能 | 关闭开关零开销；开启时仅一次方法调用 +（verbose）一次 HashMap put |

---

## 8 验收

- **单测**：`RecordingPageProxyTest`（原生操作录制 + 递归包装 + 不包装 mock/`onXxx`）、`SerenityRecorderTest`（enabled/disabled/verification）、`PageObjectFactoryRecordingTest`（注入装饰 vs 裸）。
- **全护盾**：现有 600+ 用例 0 失败 / 0 错误 / 0 跳过（行为零回归）。
- **ArchUnit**：新增 `businessCodeMustNotExtendBasePage` + `businessCodeMustNotUseSerenityRecorder`（framework-internal）。
- **文档**：04 §2.8 标注已落地、13 G-9 关闭、14 WEB-P1-2 状态更新。

## 9 对齐企业级铁律

- **线程安全/并发可见性**：代理无状态 + per-thread 受管 `Page`。
- **API 边界**：录制助手 `framework-internal` + `@apiNote` + ArchUnit。
- **可观测性**：Serenity 报告录制 + 日志路由。
- **错误处理**：语义化异常透传 + 录制失败降级 `debug`。
- **测试**：专属 UT + 全护盾。

## 10 实施进度

- **2026-09-15 Phase 0 落地**：新建 `web/page/recording/SerenityRecorder`（录制逻辑 `record` / `recordAndReturn` / `recordVerification` + per-page 测试数据存储从 `SerenityBasePage` 逐字迁移），`SerenityBasePage` 改为委托（约 10 处方法体下沉，调用点零改动），新增 `SerenityRecorderTest`（6 例）。编译零 lint 错误，行为零回归。
- **2026-09-15 Phase 1 落地**：新建 `web/page/recording/RecordingPageProxy`（JDK 动态代理，实现 `Page` 并递归包装 `Locator`/`Frame`/`ElementHandle`/`Response`/`Request`/`APIResponse`/`APIRequest`）；`SerenityRecorder` 补静态 `isEnabled()` + `recordNative()`（Layer A 原生录制，无 per-page 状态）；边界落实 D3（`onXxx` 不包装）/ R2（已是本代理则跳过递归，用 `RecordingHandler` 标识而非泛化 `Proxy.isProxyClass`，规避与 Mockito 冲突）/ R3（数据类不包装）/ R1（`Object` 方法直委）/ D4（关闭时返回裸 Page 零开销）。新增 `RecordingPageProxyTest`（6 例：click/navigate 委托、递归 `locator.click`、`onXxx` 不包装、`Object` 直委、关闭返回裸 Page）。编译零 lint 错误。
- **2026-09-15 Phase 2 落地**：`RuntimeProvider` 新增 `default boolean isTestDouble()`（D6 mock 识别，显式标记）；`RecordingPageProxy.wrap`/`wrapIfRecordable` 据此跳过测试替身包装。`PageObjectFactory.createInstance` 为组合式 Page Object（`ManagedPageAware`）注入受管 Page **惰性供应器**（避免创建期触碰浏览器）；新增标记接口 `web/page/base/ManagedPageAware`。**试点 `RouteDemoPage` 改为组合式 POJO**（实现 `ManagedPageAware`，`getPage()` 返回装饰受管 Page），调用方 `RouteDemoServiceSteps` 零改动。新增 `PageObjectFactoryRecordingTest`（2 例：enabled 且非替身→注入装饰 Page；替身→不包装）。编译零 lint 错误。
- **2026-09-15 Phase 3 落地**：抽取 `web/page/recording/SerenityPageRecorder`（framework-internal 薄门面，持有 per-page `SerenityRecorder` 实例）——把 `SerenityBasePage` 约 40 个 Layer B 录制方法体（element/getPage/getContext/verify*/navigate*/shouldBe*/by*/cookie*/frame/shadow/交互/脚本/等待/重试）逐字下沉，异常转换（`NavigationException`/`ElementException`/`ConfigurationException`）与数据键（`lastActionElement`/`navigateUrl`/`titleVerification`…）均不变。`SerenityBasePage` 退化为薄委托壳（每个方法一行 `serenity.xxx(this, ...)`，公开/受保护契约零变更）。**与"搬入对应工具类"的偏差**：改为抽独立门面而非污染 8 个工具类，避免改变 `BasePage` 传递行为、降低 Phase 4 前风险；`SerenityPageRecorder` 即为工具类/业务 Page 调用处的统一录制入口。新增 `SerenityPageRecorderTest`（2 例：element 录制+委托、recorder 为 per-instance）。编译零 lint 错误。
- 后续：Phase 4 删除 `SerenityBasePage`（业务 Page 改为继承 `BasePage` 并经 `SerenityPageRecorder`/装饰 Page 透明录制，或组合式 POJO + `ManagedPageAware`）+ ArchUnit 守护（`businessCodeMustNotExtendBasePage` / `businessCodeMustNotUseSerenityRecorder`）；Phase 5 文档闭环。
