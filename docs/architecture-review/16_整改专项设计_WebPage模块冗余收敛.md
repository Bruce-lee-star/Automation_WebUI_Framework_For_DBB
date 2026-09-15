# 16 整改专项设计：WebPage 模块冗余收敛

> 关联：WEB-P1-2 Phase 6 组合式 Page Object（G1）
> 范围：`web/src/main/java/.../framework/web/page` 模块
> 状态：设计稿；F1 / F2 已实现（见文末"命名与包变更说明"）
>
> **命名与包变更说明（实现后生效）**：本设计稿中的 `PageApi` 已更名为 **`SerenityBasePage`**（页面能力契约接口），
> `PageApiImpl` 已更名为 **`SerenityBasePageImpl`**（默认实现，委托 `BasePage` 并承载录制）；
> 二者与 `AbstractManagedPage` 一并从 `web.page.api` 包迁入 **`web.page.base`** 包，`api` 包已不存在。
> 下文"现状 / 目标设计 / 变更清单"代码块保留当时的原名，以还原设计演进过程，阅读时请按上述映射对照。

---

## 1. 背景与问题

上一轮评审（`web/page` 模块冗余评审）确认了两处可量化、行为安全的冗余：

### 1.1 F1 — `AbstractManagedPage` 与 `PageApiImpl` 双重纯转发

组合式页面的调用链当前是**四层纯转发**，中间两层零逻辑：

```
AbstractManagedPage.x()        // ~80 个 @Override，逐行 return api.x()
   └─ PageApiImpl.x()          // ~80 个 @Override，逐行 return bp.x()
        └─ BasePage.x()        // 引擎：状态 + 录制门面
             └─ PageNavigation / PageWaits / PageInteractions / LocatorFactory / ...（实现）
```

- `AbstractManagedPage`（`api/AbstractManagedPage.java`）把 `PageApi` 的约 80 个方法逐一转发给持有的
  `private final PageApi api = new PageApiImpl(page)`；
- `PageApiImpl`（`api/PageApiImpl.java`）**本身**已把同样的约 80 个方法逐一转发给 `private final BasePage bp`。
- 两层转发逐一对应、无任何附加逻辑 → **重复实现**，约 80 行机械代码。

### 1.2 F2 — `BasePage` / `SerenityPageRecorder` 中 verify 方法已成死代码

- `PageApi.java:87` 注释明确：4 个验证方法 `verifyPageTitleContains / verifyPageTitleEquals /
  verifyUrlContains / getPageSourceContains` **不纳入** `PageApi` 能力面（断言语义应落 Step 层）。
- `PageApiImpl` / `AbstractManagedPage` 因此也未暴露这 4 个方法；业务页不再继承 `BasePage`。
- 全仓 grep 确认这 4 个方法**无任何调用方**，仅经 `BasePage` 的公开包装（`:561–575`）与
  `SerenityPageRecorder`（`:104–134`、`:221–224`）的录制包装这一"死转发"链可达。
- 属"职责错位 + 无用"的死代码，应清除。

---

## 2. 目标

1. 收敛组合式页面对象的转发层级：消除 `AbstractManagedPage` 对 `PageApiImpl` 的重复转发。
2. 清除 `BasePage` / `SerenityPageRecorder` 中无调用方的 verify 死代码。
3. **行为零变化**；编译 + 架构/分层护栏零回归。

## 3. 非目标（本次不实施）

- **不**重构 `base/` 下的单一职责委托类（`PageNavigation` / `PageWaits` / `LocatorFactory` /
  `PageFrameShadow` / `PageLifecycleCoordinator` / `PageContextState` …）——属有意分解，仅代价是转发深度。
- **不**改动 `PageApi` 对外能力面（F3：`element` / `locator` 别名合并，向后兼容暂留）。
- **不**修订 `BasePage` 的 `≤40` 注释失准（F4：文档修正，递延）。

---

## 4. 当前设计（现状）

```
业务页  extends AbstractManagedPage
            ├─ implements ManagedPageAware   → setManagedPage(Supplier<Page>)
            ├─ implements PageApi            → 逐个 @Override 转发
            ├─ private final BasePage page = new BasePage(){};
            ├─ private final PageApi  api  = new PageApiImpl(page);
            └─ setManagedPage: page.attachManagedPage(...); BasePage.bindAnnotatedFields(this, page)

PageApiImpl implements PageApi
            ├─ private final BasePage bp;
            └─ 逐个 @Override 转发到 bp.*

BasePage（引擎，抽象类）
            ├─ 公开方法约 110 个（含已排除出 PageApi 的 4 个 verify 包装）
            └─ 委托到 SerenityPageRecorder / PageNavigation / PageWaits / PageInteractions / ...
```

---

## 5. 目标设计（实施后）

```
业务页  extends AbstractManagedPage
            ├─ extends PageApiImpl           → 直接继承全部 PageApi 转发（不再逐行重写）
            ├─ implements ManagedPageAware  → setManagedPage(Supplier<Page>)
            ├─ private final BasePage page   → super(page = new BasePage(){})
            ├─ setManagedPage: page.attachManagedPage(...); BasePage.bindAnnotatedFields(this, page)
            └─ getPage(): 保留 override 返回 managedPage.get()（与现状逐字一致）

PageApiImpl implements PageApi   （不变）
BasePage（引擎）                  → 删除 4 个 verify 公开包装（F2）
SerenityPageRecorder              → 删除 4 个 verify*（F2，实施前确认无调用方）
```

转发层级由 4 层降为 3 层（`AbstractManagedPage` 不再单独转发，经继承获得）：

```
业务页.getTitle()
   └─ PageApiImpl.getTitle()      // 继承自 PageApiImpl
        └─ BasePage.getTitle()
             └─ PageNavigation.getTitle() → bp.getPage().title()
```

---

## 6. 变更清单

### 6.1 F1 — `AbstractManagedPage` 合并进 `PageApiImpl`

**文件**：`web/src/main/java/.../framework/web/page/api/AbstractManagedPage.java`

**Before**（现状，约 200 行，含 ~80 行逐行转发）：

```java
public abstract class AbstractManagedPage implements ManagedPageAware, PageApi {
    private final BasePage page = new BasePage() {};
    private final PageApi api = new PageApiImpl(page);
    private Supplier<Page> managedPage;

    @Override
    public void setManagedPage(Supplier<Page> managedPage) {
        this.managedPage = managedPage;
        page.attachManagedPage(managedPage.get());
        BasePage.bindAnnotatedFields(this, page);
    }
    public Page getPage() { return managedPage.get(); }

    @Override public PageElement element(String s) { return api.element(s); }
    @Override public PageElement locator(String s) { return api.locator(s); }
    // ... 约 80 行逐行转发 ...
}
```

**After**（目标，约 25 行）：

```java
public abstract class AbstractManagedPage extends PageApiImpl implements ManagedPageAware {

    private final BasePage page;
    private Supplier<Page> managedPage;

    public AbstractManagedPage() {
        this(new BasePage() {});
    }

    // 承接委托实例的私有构造器：Java 不允许在 super() 实参中引用实例字段，
    // 故用本构造器把同一 BasePage 实例同时传给 super 并赋给字段。
    private AbstractManagedPage(BasePage delegate) {
        super(delegate);
        this.page = delegate;
    }

    @Override
    public void setManagedPage(Supplier<Page> managedPage) {
        this.managedPage = managedPage;
        page.attachManagedPage(managedPage.get());
        BasePage.bindAnnotatedFields(this, page);
    }

    /** 保留 override：返回惰性供应商当前解析的受管 Page，与原行为逐字一致（不经 ensurePageValid）。 */
    @Override
    public Page getPage() {
        return managedPage.get();
    }
}
```

**删除量**：约 80 行机械 `@Override` 转发。`PageApiImpl` 已完整实现 `PageApi`，继承即获得，无逻辑差异。

**要点**：
- `ManagedPageAware` 仅声明 `setManagedPage`；`getPage` 来自 `PageApi`（经 `PageApiImpl` 继承）。
  为严格保持现状语义（`getPage()` 返回 `managedPage.get()`、不触发 `ensurePageValid`），**保留 `getPage()` override**。
- 若后续希望统一走 `ensurePageValid` 守卫，可去掉该 override 改继承 `PageApiImpl.getPage()`；本次为"零行为变化"保留 override。

### 6.2 F2 — 删除 verify 死代码

**6.2.1 `BasePage.java`（`:559–575` 验证段）**

删除以下整段（4 个方法的公开包装，仅转发到 `serenity.*`，无调用方）：

```java
// ==================== 验证 ====================
public boolean verifyPageTitleContains(String expectedText) { return serenity.verifyPageTitleContains(this, expectedText); }
public boolean verifyPageTitleEquals(String expectedText)   { return serenity.verifyPageTitleEquals(this, expectedText); }
public boolean verifyUrlContains(String expectedText)       { return serenity.verifyUrlContains(this, expectedText); }
public boolean getPageSourceContains(String text)          { return serenity.getPageSourceContains(this, text); }
```

**6.2.2 `SerenityPageRecorder.java`（`:104–134` 三个 verify*、`221–224` `getPageSourceContains`）**

实施前全仓 grep 确认 `verifyPageTitleContains / verifyPageTitleEquals / verifyUrlContains /
getPageSourceContains` 除 `BasePage` 包装与 `SerenityPageRecorder` 自身外**无任何调用方**后，删除这 4 个方法及其 `recorder.record(...)` 录制管线。

> 注：`PageInteractions.getPageSourceContains` 仍被 `PageInteractionsTest` 覆盖且为 public 工具方法，
> 不属于本次删除范围（它不被 `SerenityPageRecorder` 删除后影响，因 `SerenityPageRecorder.getPageSourceContains`
> 本就是其唯一内部调用方）。

---

## 7. 行为等价性论证

- **F1**：`AbstractManagedPage.x()` 原转发到 `PageApiImpl.x()`，后者转发到 `BasePage.x()`；
  合并后 `AbstractManagedPage.x()` 直接继承 `PageApiImpl.x()`，转发目标与执行路径**完全一致**。
  `getPage()` 保留 override，返回值与原 `managedPage.get()` 逐字相同。`setManagedPage` 注入契约不变。
- **F2**：删除的方法经全仓确认无调用方，不出现在任何运行路径；删除不改变既有行为。

---

## 8. 风险与缓解

| 风险 | 缓解 |
|------|------|
| 合并后 `AbstractManagedPage` 变为 `PageApiImpl` 子类，若有代码依赖"它不是 `PageApiImpl`"（如 `instanceof PageApiImpl` 判否分支）会行为变化 | 实施前 grep `instanceof PageApiImpl` 与 `PageApiImpl` 引用，确认无依赖此关系的逻辑 |
| `getPage()` 改为继承会引入 `ensurePageValid` 副作用 | 已决定保留 `getPage()` override，返回 `managedPage.get()`，与原语义一致 |
| 误删仍有调用方的 verify 方法 | 实施前全仓 grep 四个方法名 + 检查反射/`PageObjectFactory` 路由；并跑 `BasePage*Test` / `PageInteractionsTest` / 架构护栏 |
| 字段初始化顺序导致 `page` 为 null 传入 `super` | 采用私有承接构造器（`private AbstractManagedPage(BasePage delegate)`）把同一实例同时传给 super 与字段，单测 `PageObjectFactoryConcurrencyTest` 验证 |

---

## 9. 验证计划

1. **全量编译**：`mvn -o -pl test-automation -am test-compile`
   （验证 6 个业务页 + 框架在合并/删减后仍编译通过）
2. **架构/分层护栏**（浏览器无关）：
   `mvn -o -pl test-automation -am test -Dtest=ArchitectureTest,LayeringArchTest -Dsurefire.failIfNoSpecifiedTests=false`
3. **既有单测回归**：
   `BasePageSeamTest` / `PageFrameShadowTest` / `PageObjectFactoryConcurrencyTest` /
   `PageInteractionsTest` / `BasePagePageSwitchLockConcurrencyTest`
4. **（可选）全量护盾**：需浏览器环境的 E2E（`CucumberE2ESandboxRunnerIT` 等），本地若有 Playwright 浏览器再补跑。

预期：所有步骤 BUILD SUCCESS、0 failure。

---

## 10. 回滚

纯删减 + 继承合并，无数据/配置变更。`git revert` 对应提交即可整体回退。

---

## 11. 后续可选（不在本次范围）

- **F3**：`PageApi.element` 与 `locator` 等价，长期合并或标注 `locator` 为别名。
- **F4**：修订 `BasePage.java:36` "公开方法收敛至 ≤40" 注释（实际指业务页继承面，非 `BasePage` 自身）。
