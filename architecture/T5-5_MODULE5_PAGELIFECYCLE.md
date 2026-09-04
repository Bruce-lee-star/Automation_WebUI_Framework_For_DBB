# T5-5 Module 5 PageLifecycle 执行规格（✅ 已执行完成，2026-09-04）

> 目标：完成 BasePage 拆分收尾（T5-5 的 5/5 模块）。
> 当前进度：PageWaits / PageNavigation / PageElementActions / PageFrameShadow 已拆（4/5），Module 5 PageLifecycle **已完成（5/5）**。
> 执行结果：新建 `web/.../page/base/PageLifecycleCoordinator.java`（同包纯静态委派）；BasePage 5 个 private 辅助降为包级私有
> （isPageClosed / onPageSwitched / setPageReference / safeBringToFront / findLastAvailablePage），编排逻辑（含 acceptNewPage、
> logPageSwitchInfo）迁入协调器，BasePage 仅留薄门面转发。专属 UT `PageLifecycleCoordinatorTest` 18 例全绿；
> 全护盾 `mvn -o -pl test-automation -am test` **273 例全绿（BUILD SUCCESS）**，公开 API 零破坏性变更、零回归。

## 目标文件
`web/src/main/java/com/hsbc/cmb/hk/dbb/automation/framework/web/page/base/BasePage.java`（约 72.7 KB）

## 待下沉的私有辅助方法（本次 grep 行号，执行时务必重读确认当前签名/行号）
| 方法 | 行 | 可见性 |
|---|---|---|
| `isPageClosed(Page)` | 157 | private |
| `ensureContextValid()` | 167 | **public（不动）** |
| `onPageSwitched()` | 604 | private |
| `setPageReference(Page)` | 612 | private |
| `safeBringToFront()` | 621 | private |
| `findLastAvailablePage(List<Page>, int)` | 890 | private |

## 执行步骤（seam-first，保证对外行为不变）
1. **开 additive seam**：将 5 个 `private` 方法（`isPageClosed` / `onPageSwitched` / `setPageReference` / `safeBringToFront` / `findLastAvailablePage`）改为**包级私有**（去掉 `private` 修饰符）。`ensureContextValid()` 已 public，不动。此步仅改可见性，调用点不变，**零行为风险**。
2. **建同包 delegate**：在**同一个包** `com.hsbc.cmb.hk.dbb.automation.framework.web.page.base` 下新建包级私有类 `PageLifecycleCoordinator`。
   - ⚠️ **必须同包**：Java 子包**不继承**包级私有访问权，放 `...web.page.base.lifecycle` 等子包将无法访问 BasePage 的包级私有成员。
   - 将「页面切换 / 弹窗 / 下载 / 关闭」的编排逻辑迁入此类；构造时持有 BasePage 的 page/context ThreadLocal 句柄（通过构造注入或包级私有访问器）。
3. **BasePage 改为委托**：原编排逻辑转发给 `PageLifecycleCoordinator`，保持对外 `public` API 完全不变。
4. **专属单测**：为 `PageLifecycleCoordinator` 补 Mockito 单测（覆盖页面切换、关闭检测、置前、回退到最近可用页）。
5. **全护盾回归**：`mvn -o -pl test-automation -am test`，须维持 **255 绿**。

## 验收
- T5-5 → 5/5 完成；看板 T2-3 状态 → ✅ 已完成。
- 公开 API 零破坏性变更；全护盾零回归。

## 风险与约束
- `page` / `context` 为重度耦合的私有 ThreadLocal 字段，下沉时须通过构造注入或包级私有访问器传递，**不得改变生命周期语义**。
- 严守 seam-first：先可见性、再 delegate，每步可独立回滚、可独立跑测试。
- 与既有共享 Browser 模式（T3-2 扩展）兼容：编排逻辑不得关闭共享 Browser（由 `cleanupAll()` 收口）。
