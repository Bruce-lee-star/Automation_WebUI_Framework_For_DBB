# 元素定位拾取器 & 步骤生成器 使用说明

> 适用对象：自动化测试工程师 / 框架维护者
> 配套代码：`framework/web/page/scan/RoleElementPicker`（拾取面板）、`RoleElementPageGenerator`（页面类生成）、`RoleElementStepGenerator`（步骤类生成）
> 状态：**已落地可运行**（区别于 `PAGEOBJECT_GENERATOR_DESIGN.md` 的设计草案，本文档描述当前实际实现）

---

## 1. 它解决什么问题

手工编写 PageObject 时，元素定位（selector）与操作步骤容易过时、且依赖人工读 DOM。本工具提供一套**浏览器内拾取器**：

- 在真实有头浏览器里打开目标页 → 点击元素 → 自动计算稳定的 ARIA/role 定位；
- 支持**跨页面、弹窗、关闭当前页**等复杂交互，一次「开始→停止」拾取 = 一个步骤；
- 一键生成**页面类（Page）** 与 **步骤类（Steps）** 草稿，`@RoleElement` 注解内置 NLS 文案反查，人工 review 后即可合入主干。

所有产物均为**草稿**，不直接提交：需人工确认 locator 与步骤语义。

---

## 2. 整体数据流

```
有头浏览器打开目标应用（可多页 / 弹窗）
        │  点开常驻面板：▶ 开始拾取 → 点击元素（实时/勾选）
        ▼
浏览器侧 window.__rolePicks / __steps   （role+name+_pageClass 等元信息）
        │  Java 侧 runPickerCommand 收口（内存态 javaPickBySig 抗导航/关闭清空）
        ▼
PickSnapshot { entries, steps, ops }
        │
        ├─ RoleElementPageGenerator.generate(entries, …)  →  <Xxx>Page.java
        └─ RoleElementStepGenerator.generate(generateMulti/generatePerPage) → <Xxx>Steps.java
        │
        ▼
  人工 review → 合入主干
```

> 关键解耦：生成器只吃 `RoleEntry` 列表与 `PickSnapshot`，不关心元素由谁拾取（同样是 CDP a11y 树算法，与 MCP 同源）。

---

## 3. 快速开始（面板模式，最常用）

在测试代码里，于「已导航到目标页、有头浏览器」的位置插入一行：

```java
Page page = loginPage.getPage();
RoleElementPicker.openPanel(
        "com.hsbc.cmb.hk.dbb.automation.tests.pages",  // ① 生成类的包名
        "LoginPage",                                    // ② 生成页面类名
        "LoginSteps",                                   // ③ 生成步骤类名（合法 Java 类名，非 nls 路径）
        "nls/NLS_footer.json", "nls/NLS_idv_logon.json" // ④ nls 文件（可变参数，用于文案反查）
);
```

面板打开后操作流程：

1. **▶ 开始拾取**：进入拾取态。此后点击页面元素即被记录（实时点选写入选择集，或到「页面元素」Tab 勾选）。
2. **点选元素**：每个点击生成一个 `RoleEntry`（含 role、可访问名、自动定位策略）。
3. **封装为步骤**（可选）：把当前勾选的元素打包成一个 step。Scan 路径与手动路径最终都走 `window.__packageStep`，**无论跨多少页，合并为一个 `StepRec`**。
4. **⏹ 停止生成代码**：把当前选择封装成 step 并生成 `Page` + `Steps` 源码，输出到面板「页面元素 / 步骤代码」两个 Tab。
5. **📋 复制 / ✕ 关闭**：复制源码，或关闭面板结束。

> CI 环境（`isCiRun()`）下 `openPanel` / `pick` 会自动跳过，不阻塞自动化；因此拾取面板是**本地开发工具**，不影响流水线。

---

## 4. 编程式 API（非交互 / 可脚本化）

| 方法 | 作用 | 备注 |
|---|---|---|
| `RoleElementPicker.pick(page, nlsFiles...)` | 打开面板交互拾取，返回 `List<RoleEntry>` | 阻塞直至关闭面板 |
| `RoleElementPicker.pickAndGenerate(page, pkg, pageClassName, nlsFiles...)` | 拾取并直接返回 Page 源码字符串 | 不落盘 |
| `RoleElementPicker.pickAndWrite(page, outputDir, pkg, pageClassName, nlsFiles...)` | 拾取并写入 `outputDir`（如 `src/test/java`） | 一步到位 |
| `RoleElementPicker.pickAndDump(page, pkg, pageClassName, nlsFiles...)` | 拾取并打印到日志 | 调试用 |
| `RoleElementPageGenerator.generate(entries, pkg, pageClassName, nlsFiles...)` | 由 `RoleEntry` 列表生成 Page 源码 | 可脱离面板调用 |
| `RoleElementPageGenerator.write(entries, outputDir, pkg, pageClassName, nlsFiles...)` | 同上并写文件 | — |
| `RoleElementStepGenerator.generateMulti(stepsByPage, entriesByPage, opsByPage, pkg, stepClassName)` | 生成合并版 Steps 类 | 多页同合并成一个类 |
| `RoleElementStepGenerator.generatePerPage(...)` | 按页各出一份「完整可编译」的 Steps 视图 | 面板分栏对照用 |

`openPanel` 的**正确参数顺序**是 `(page, packageName, pageClassName, stepClassName, nlsFiles...)`，不要把 nls 路径误传给 `stepClassName`（否则类名会变成 `nls/xxx.json` 这类非法名）。

---

## 5. 生成的页面类（Page）

```java
package com.hsbc.cmb.hk.dbb.automation.tests.pages;

import com.hsbc.cmb.hk.dbb.automation.framework.web.page.PageElement;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.RoleElement;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.base.BasePage;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.factory.PageObjectFactory;

public class LoginPage extends BasePage {

    // role=textbox name="Username"（拾取时反查到 nls key：login.username）
    @RoleElement(role = "textbox", name = "Username", nlsKey = "login.username")
    public final PageElement username = element("role=textbox[name=\"Username\"]");

    // role=button name="Sign in"
    @RoleElement(role = "button", name = "Sign in")
    public final PageElement signInBtn = element("role=button[name=\"Sign in\"]");
}
```

要点：

- 字段类型是 `PageElement`，由 `BasePage.element(String selector)` 创建（**只吃字符串 selector**）。
- selector 优先用 `role=...[name=...]` 或 `[data-testid=...]`，不依赖易变 DOM 层级。
- 拾取时若 a11y name 命中 nls 文件，则写入 `@RoleElement(nlsKey=...)`，运行时随语言切换自动重解析。
- `PageElement` 提供 `click() / fill() / type() / doubleClick() / rightClick() / clear() / press() / selectByValue() / selectByIndex() / hover() / isVisible() / isEnabled() / getText() / getValue() / getAttribute()` 等，均带重试与日志。

---

## 6. 生成的步骤类（Steps）

### 6.1 单步语义（重要）

一次「开始→停止」或一次「封装为步骤」= **一个 `@Step` 方法**，是步骤的唯一边界：

- 跨多个页面拾取 → 仍合并到**同一个 `@Step`**（每个 pick 自带 `_pageClass`，生成器据此引用对应页变量）；
- 中间打开/关闭弹窗、页面跳转 → 都是同一 step 内的交互，内联渲染；
- 仅含「关闭当前页」操作的 step 会被合并进**上一个 step**，不另成方法。

### 6.2 跨页 + 弹窗示例

```java
package com.hsbc.cmb.hk.dbb.automation.tests.pages.steps;

import net.serenitybdd.annotations.Step;
import com.microsoft.playwright.Page;
import com.hsbc.cmb.hk.dbb.automation.tests.pages.LoginPage;
import com.hsbc.cmb.hk.dbb.automation.tests.pages.PrivacyAndSecurityPage;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.factory.PageObjectFactory;

public class LoginSteps {

    private final LoginPage loginPage = PageObjectFactory.getPage(LoginPage.class);
    private final PrivacyAndSecurityPage privacyAndSecurityPage =
            PageObjectFactory.getPage(PrivacyAndSecurityPage.class);

    @Step("login and open privacy")
    public void loginAndOpenPrivacy() {
        loginPage.someLink.click();
        // 弹窗（新页面）触发：用 switchToNewPage 包裹触发动作，新页面交由目标页对象接管
        Page newPage = loginPage.switchToNewPage(() ->
                loginPage.privacyLink.click(), 15);
        privacyAndSecurityPage.switchToPage(newPage);
        privacyAndSecurityPage.someField.click();
        privacyAndSecurityPage.closeCurrentPage();   // 弹窗关闭落在目标页对象上
        loginPage.backOnLogin.click();                // 回到原页继续也属同一 step
    }
}
```

- `switchToNewPage(Runnable trigger, int timeoutSecs)`：执行触发动作并等待新页面；
- `switchToPage(Page)`：把新页面交给对应页对象（如 `privacyAndSecurityPage`）托管；
- `closeCurrentPage()`：关闭当前页（关闭操作内联在 step 内，不单独成方法）。
- 弹窗关闭的目标页对象由 `inferPopupTargetVar` 推断：弹窗之后的元素归属页若与打开页不同，即目标页。

### 6.3 校验 API

`BaseStep` 风格的断言由步骤体自行调用 `PageElement` 的状态方法（`isVisible()` 等）或框架断言；本生成器只产出交互代码，断言需人工补充。

---

## 7. NLS 集成

- 调用时传入 nls 文件路径（如 `"nls/NLS_footer.json"`），拾取器用 `buildNlsReverseJson` 构建「规范化文案 → key」反向表（覆盖多语言、跨文件合并、带缓存）。
- 点击元素时，把 a11y name 反查为对应 nls key，生成 `@RoleElement(nlsKey=...)`。
- 未命中则回退到 name 派生 slug，并提示需人工确认。

---

## 8. 元素定位策略（优先级）

| 优先级 | 条件 | 生成示例 | 类型 |
|---|---|---|---|
| 1 | 有 `data-testid` | `[data-testid="username"]` | TEST_ID |
| 2 | 有 role + 唯一 name | `role=button[name="Sign in"]` | ROLE |
| 3 | 短文本可唯一 | `text=Forgot password?` | TEXT |
| 4 | 有稳定 `id` | `#username` | CSS |
| 5 | 兜底 | `xpath=...`（标 `needsReview`） | XPATH |

首选 `role=` 与 `[data-testid]`：不依赖易变 DOM 层级，是最稳的持久 locator。

---

## 9. 企业级注意点

1. **草稿不自动提交**：生成文件需人工 review（确认 locator、补断言）后合入主干。
2. **a11y 树≠100% 覆盖**：无 ARIA 的 `<div onclick>` 拿不到 role；生成器会降级或标 `needsReview`，**绝不瞎编**。
3. **内存态抗清空**：导航/关闭导致浏览器端状态清空时，Java 侧 `javaPickBySig` 内存态会覆盖恢复，避免元素丢失。
4. **CI 安全**：`openPanel` / `pick` 在 CI 下自动跳过，不会阻塞无人值守流水线。
5. **stepClassName 必须是合法类名**：不要把 nls 路径传给它（见 §4）。

---

## 10. 典型工作流小结

```
1) 写一行 openPanel(...) 触发拾取面板（本地有头跑用例）
2) ▶ 开始 → 点击元素 / 勾选 →（封装为步骤）→ ⏹ 停止
3) 从「页面元素」「步骤代码」Tab 复制生成的 Page / Steps 草稿
4) 人工 review：确认 locator、补断言、调整步骤粒度
5) 落盘到 tests/pages 与 tests/steps，合入主干
```
