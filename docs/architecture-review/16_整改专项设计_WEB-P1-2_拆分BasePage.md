# 16. 整改专项设计 · WEB-P1-2 拆分 `BasePage`

> 关联评审：02_web模块评审.md（WEB-P1-2）、08_整改任务总表.md（WEB-P1-2，8d）
> 状态：🔶 Phase 1 已完成（by* → `LocatorFactory`，BasePage 1477 → **1377**，全护盾 **487 绿**）；后续 Phase 待续；破坏性决策见 §3
> 前置：WEB-P0-2 ✅、WEB-P1-1 ✅（门面 1553 → 363）

## 1. 现状（2026-09-08 实测）

| 指标 | 数值 | 验收要求 | 差距 |
|---|---|---|---|
| `BasePage` 行数 | **1477** | ≤500 | −977 |
| 方法总数 | 115 | — | — |
| **公开方法数** | **107** | **≤40** | **−67** |

已下沉的协作者（`framework.web.page.base` 及 `base.delegate`）：
`PageFrameShadow`(201) / `PageLifecycleCoordinator`(294) / `PageWaits`(111) / `PageNavigation`(94) / `SerenityBasePage`(369，子类)。

## 2. 分域方案（按实测方法清单归类）

| 域 | 目标类 | 方法数 | 估算行数 | 说明 |
|---|---|---|---|---|
| 定位器工厂 | `LocatorFactory` | **22** | ~320 | `byRole`×6、`byText`×3、`byAltText`×3、`byTitle`×3、`byPlaceholder`×3、`byLabel`×3、`byTestId`×1（评审明确要求收拢 `byRole`×10） |
| Frame / Shadow | `PageFrameShadow`（扩） | **17** | ~230 | `switchToFrame`×2、`switchToFrameAndWait`×4、`switchToShadow`、`switchToDefaultShadow`×2、`getFrame`、`getCurrentFrame`、`getAllFrames`、`executeInFrame` 等 |
| Cookie | `PageCookies` | **10** | ~95 | `getCookies`×3、`getCookie`、`hasCookie`、`addCookie`、`addCookies`、`deleteCookie`、`clearCookies`、`getCookiesForCurrentPage` |
| 视口 / 脚本 / 源码 | `PageViewport`（暂定） | **11** | ~80 | `scrollTo`/`scrollBy`/`scrollToTopOf`/`scrollToBottomOf`、`executeJavaScript`、`getPageSource*`、`getPageSize`、`setViewportSize`、`setContent` |
| 交互 | `PageInteractions`（暂定） | **12** | ~90 | `keyDown`/`keyUp`/`press`、`waitForTimeout`、`acceptAlert`×2、`dismissAlert`×2、`takeScreenshot`、`takeElementScreenshot`、`pause` |
| 断言 | `PageAssertions` | **2** | ~15 | `shouldBeVisible`、`shouldBeNotVisible` |
| 无障碍 | `PageAccessibility` | **1** | ~10 | `dumpAccessibilityRoles`（委托 codegen SPI） |
| **门面保留** | `BasePage` | 核心 | — | `element`/`locator`/`elements`/`getPage`/`getContext`/`navigateTo*`/`getCurrentUrl`/`getTitle`/`refresh`/`back`/`forward`/`append`/`getAttributeValue`/`normalizeText` + 各域访问器 |

## 3. ⚠️ 关键矛盾与待决策略（需业务/架构确认）

**验收②「公开方法数 ≤40」与「公开 API 零变更」不可兼得。**

- 若 107 个公开方法全部保留委托壳 → 门面约 750 行、公开方法仍 107 → **验收①② 均不达标**。
- 要达标，**必须收窄公开 API**：业务调用从 `page.getCookies()` 迁移为 `page.cookies().getCookies()`，即由「扁平门面」改为「组合门面 + 域访问器」。

| 策略 | 做法 | 结果 | 代价 |
|---|---|---|---|
| **A 收窄（符合验收）** | 域方法迁出后**不在门面保留委托**，业务改用域访问器 | 门面 ~450 行、公开方法 ~35 ✅ | 破坏性变更，需改业务 Page Object |
| **B 兼容（不满足验收）** | 全部保留委托壳（`@Deprecated`） | 门面 ~750 行、公开方法 107 ❌ | 验收不达标 |
| **C 兼容期过渡（推荐）** | 先按 A 内聚，同时保留委托壳并标 `@Deprecated`，给业务一个迁移窗口；到期后删除委托 | 过渡期不达标，**迁移完成后达标** | 需约定删除时间点 |

**推荐 C**：以 A 为终态，用兼容期降低业务风险；验收在兼容层删除后复测达成。

## 4. 实施阶段

| Phase | 内容 | 验收 |
|---|---|---|
| **0** | 统计业务调用面：業務 Page Object 与测试步骤中使用了哪些 `BasePage` 方法、各多少次（决定迁移成本） | 输出调用面清单 |
| **1** | 建 `LocatorFactory`（22 个 `by*`），门面委托（兼容期） | 编译 + 全护盾 487 |
| **2** | 建 `PageCookies`（10 个） | 同上 |
| **3** | Frame/Shadow 继续下沉到 `PageFrameShadow`（17 个） | 同上 |
| **4** | 建 `PageViewport` / `PageInteractions` / `PageAssertions` / `PageAccessibility` | 同上 |
| **5** | 业务调用迁移到域访问器 + 委托壳标 `@Deprecated` | 全护盾 + 业务用例回归 |
| **6** | 删除委托壳，复测：门面 ≤500 行、公开方法 ≤40、ArchUnit `businessCodeMustNotUseInternalByLocators` 通过 | **三条验收全绿** |

## 5. 约束铁律

- `by*` 定位器工厂为 framework-internal（ArchUnit `businessCodeMustNotUseInternalByLocators` 已固化），迁移后仍不得被业务直接使用。
- `element()` / `locator()` 返回框架原生 `PageElement`（不得泄漏 Playwright 类型，T3-5 已治理）。
- 日志仍回 `LoggerFactory.getLogger(BasePage.class)` 保持生产溯源。
- 每步编译 + 全护盾 487 复验，不在未验证基线上叠加。

## 6. 风险

- **破坏性变更面**：需 Phase 0 量化；若业务调用面很大，C 的迁移窗口期需相应延长。
- **与 `SerenityBasePage`（369 行，子类）的耦合**：父类方法迁移会直接影响子类，需同步调整。
- **codegen 依赖**：`dumpAccessibilityRoles` 经 `RoleCodegenBridge` SPI，迁移时须保持 no-op 降级语义。
- **与 DI 二期（WEB-P1-6）串行**：二者都动 web 核心，避免并行。

## 7. 验证方式

- 行数：`[System.IO.File]::ReadAllLines(BasePage.java).Count`
- 公开方法数：匹配 `^    public\s` 且含 `) {`
- 全护盾：`mvn -o -pl test-automation -am test`（基线 **487 例绿**）
- ArchUnit：`businessCodeMustNotUseInternalByLocators` 规则仍通过
