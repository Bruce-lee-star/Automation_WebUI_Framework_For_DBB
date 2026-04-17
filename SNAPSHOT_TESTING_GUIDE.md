# 快照测试（视觉回归测试）使用指南

基于 Playwright 原生 API 的快照测试方案，支持视觉快照和 ARIA 快照两种模式。

---

## 核心类

| 类名 | 功能 |
|------|------|
| **PlaywrightSnapshotSupport** | 快照测试 API 封装，支持视觉快照和 ARIA 快照 |
| **NativeSnapshotResult** | 快照测试结果对象（含统计类） |
| **NativeSnapshotReportGenerator** | 独立 HTML 报告生成器 |

---

## 两种快照类型对比

| 特性 | 视觉快照 (Visual) | ARIA 快照 (ARIA) |
|------|-----------------|-----------------|
| 测试内容 | 像素级图像对比 | 可访问性树结构对比 |
| 跨平台一致性 | 需要为每个 OS 创建基线 | **一套基线，跨平台** |
| 字体/渲染差异 | 敏感 | **不受影响** |
| 动态内容检测 | 容易误报 | **可忽略动态元素** |
| 截图差异可见 | 直观对比 | 结构对比 |
| CI/CD 友好度 | 需要配置 CI 基线 | **高度可靠** |

> **推荐**：优先使用 ARIA 快照，视觉快照作为补充。

---

## 快速开始

### 视觉快照 (Visual)

像素级图像对比，用于检测 UI 变化。

```java
// 1. 创建基线（开发阶段，首次运行）
PlaywrightSnapshotSupport.of(page)
    .visual()
    .baselineName("login-page")
    .updateBaseline(true)      // true = 创建或更新基线
    .snapshot();

// 2. 验证模式（CI/CD 回归测试）
PlaywrightSnapshotSupport.of(page)
    .visual()
    .baselineName("login-page")
    .maxDiffPixels(100)
    .snapshot();

// 3. 整页截图
PlaywrightSnapshotSupport.of(page)
    .visual()
    .fullPage(true)
    .baselineName("full-homepage")
    .snapshot();

// 4. 元素截图
PlaywrightSnapshotSupport.of(page.locator(".header"))
    .visual()
    .baselineName("header-element")
    .snapshot();
```

### ARIA 快照 (ARIA)

可访问性树结构对比，**不受字体/渲染差异影响**，一套基线跨平台。

```java
// 1. 创建基线（首次运行）
PlaywrightSnapshotSupport.of(page.locator("main"))
    .aria()
    .baselineName("main-aria")
    .updateBaseline(true)      // true = 创建或更新基线
    .snapshot();

// 2. 验证模式（CI/CD）
PlaywrightSnapshotSupport.of(page.locator("main"))
    .aria()
    .baselineName("main-aria")
    .snapshot();

// 3. 使用正则匹配（忽略动态内容）
PlaywrightSnapshotSupport.of(page.locator("form"))
    .aria()
    .baselineName("form-aria")
    .useRegex(true)
    .snapshot();

// 4. 静默模式（减少日志输出）
PlaywrightSnapshotSupport.of(page.locator("nav"))
    .aria()
    .baselineName("nav-aria")
    .silent(true)
    .snapshot();
```

### ARIA 快照输出示例

```
- main "Welcome to Dashboard"
  - heading "User Profile"
  - button "Edit Profile"
  - text "Member since 2024"
  - navigation "Main Menu"
    - link "Home"
    - link "Settings"
```

---

## Builder 配置选项

### 视觉快照 (VisualBuilder)

| 方法 | 参数 | 默认值 | 说明 |
|------|------|--------|------|
| `baselineName(name)` | String | 必填 | 基线名称（自动转义为合法文件名） |
| `updateBaseline(update)` | boolean | false | true=创建/更新基线, false=仅对比 |
| `maxDiffPixels(max)` | int | 100 | 最大差异像素数 |
| `maxDiffPixelRatio(ratio)` | double | 0.01 | 最大差异比例（0.01=1%） |
| `fullPage(full)` | boolean | false | 是否整页截图 |
| `mask(locators...)` | Locator[] | - | 需要遮罩的元素 |
| `silent(silent)` | boolean | false | 静默模式 |

### ARIA 快照 (AriaBuilder)

| 方法 | 参数 | 默认值 | 说明 |
|------|------|--------|------|
| `baselineName(name)` | String | 必填 | 基线名称 |
| `updateBaseline(update)` | boolean | false | true=创建/更新基线, false=仅对比 |
| `useRegex(use)` | boolean | false | 使用正则表达式匹配 |
| `silent(silent)` | boolean | false | 静默模式 |

---

## 配置项 (serenity.properties)

```properties
# ==================== Playwright 原生快照测试配置 ====================

# 启用快照测试（全局开关）
native.snapshot.enabled=true

# 视觉快照基线目录（建议提交到版本控制）
native.snapshot.visual.dir=src/test/resources/snapshots/native/visual

# ARIA 快照基线目录（建议提交到版本控制）
native.snapshot.aria.dir=src/test/resources/snapshots/native/aria

# 视觉快照最大差异像素数
native.snapshot.visual.maxDiffPixels=100

# 视觉快照最大差异像素比例 (0.01 = 1%)
native.snapshot.visual.maxDiffPixelRatio=0.01

# 静默模式（减少日志输出）
native.snapshot.silent=false
```

---

## 文件结构

```
src/test/resources/snapshots/native/     # ★ 基线文件（提交到版本控制）
├── visual/                              # 视觉快照基线 (.png)
│   ├── login-page.png
│   ├── password-page.png
│   └── header-element.png
└── aria/                                # ARIA 快照基线 (.aria)
    ├── main-aria.aria
    └── form-aria.aria

target/snapshots/native/current/         # 运行时生成（不纳入版本控制）
└── ...

target/snapshot-reports/native/          # HTML 报告
└── native-snapshot-report-*.html
```

> **Git 管理**：`src/test/resources/snapshots/native/` 目录应提交到版本控制仓库，作为团队共享的 UI 参考标准。

---

## 报告生成

```java
// 获取统计信息
NativeSnapshotResult.Stats stats = PlaywrightSnapshotSupport.getStats();
// stats.getTotal(), stats.getPassed(), stats.getFailed(), stats.getError()
// stats.getVisual(), stats.getAria(), stats.getPassRate()

// 获取摘要文本
String summary = PlaywrightSnapshotSupport.getSummary();
// 输出: "Native Snapshot Tests: 5 total | 4 PASS | 1 FAIL | 0 ERROR | Pass Rate: 80.0%"

// 生成 HTML 报告（默认目录）
String reportPath = PlaywrightSnapshotSupport.generateReport();

// 生成 HTML 报告（自定义目录）
String reportPath = PlaywrightSnapshotSupport.generateReport("custom-report-dir");

// 清空结果（通常在 Scenario 开始前调用）
PlaywrightSnapshotSupport.clearResults();
```

### Cucumber Hook 中自动生成

```java
@Before
public void beforeScenario() {
    PlaywrightSnapshotSupport.clearResults();
}

@After(order = 1000)
public void generateSnapshotReport(Scenario scenario) {
    String path = PlaywrightSnapshotSupport.generateReport();
    if (path != null) {
        scenario.write("Snapshot Report: " + path);
    }
    PlaywrightSnapshotSupport.clearResults();
}
```

---

## 完整使用示例

### 登录流程快照验证

```java
@Step
public void performLogin() {
    loginPage.navigateTo(currentUrl);

    // 视觉快照 - 登录页
    PlaywrightSnapshotSupport.of(loginPage.getPage())
        .visual()
        .baselineName("login-page")
        .snapshot();

    loginPage.userNameIpt.type(username);
    loginPage.nextBtn.click();

    // 视觉快照 - 密码页
    PlaywrightSnapshotSupport.of(loginPage.getPage())
        .visual()
        .baselineName("password-page")
        .snapshot();

    loginPage.paswordIpt.type(password);
    loginPage.loginBtn.click();

    // ARIA 快照 - 首页结构验证（跨平台可靠）
    PlaywrightSnapshotSupport.of(page.locator("main"))
        .aria()
        .baselineName("home-main")
        .snapshot();
}
```

### 多步骤 UI 验证

```java
@Test
public void testMultiStepSnapshot() {
    // 步骤 1: 初始状态
    PlaywrightSnapshotSupport.of(page)
        .visual().baselineName("dashboard-initial").snapshot();

    // 步骤 2: 筛选后状态
    dashboard.FILTER_BTN.click();
    PlaywrightSnapshotSupport.of(page)
        .visual().baselineName("dashboard-filtered").snapshot();

    // 步骤 3: 侧边栏展开
    dashboard.SIDEBAR_TOGGLE.click();
    PlaywrightSnapshotSupport.of(page.locator(".sidebar"))
        .visual().baselineName("sidebar-expanded").snapshot();

    // 步骤 4: ARIA 结构验证
    PlaywrightSnapshotSupport.of(page.locator("nav"))
        .aria().baselineName("main-nav").snapshot();

    // 生成报告
    String report = PlaywrightSnapshotSupport.generateReport();
    logger.info("Snapshot report: {}", report);
}
```

---

## 基线管理最佳实践

### 何时更新基线？

| 场景 | 操作 |
|------|------|
| 全新功能 | `updateBaseline(true)` 首次创建基线 |
| UI 有意变更 | `updateBaseline(true)` 设计师确认后的 UI 改版 |
| 日常回归测试 | `updateBaseline(false)` CI/CD 标准流程 |
| 误报修复 | `updateBaseline(true)` 重新生成基线 |

### 团队协作工作流

```
开发者A: 开发新功能 → 本地 updateBaseline(true) → 提交 baseline 到 Git
                    ↓
代码审查: Reviewer 检查基线截图是否正确
                    ↓
合并到主分支: Jenkins CI 运行 updateBaseline(false) 回归检查
                    ↓
UI 改版时: 设计师确认 → updateBaseline(true) → 提交新 baseline
```

---

## 推荐使用场景

| 场景 | 推荐方案 | 原因 |
|------|----------|------|
| 跨平台 UI 测试 | `aria()` | 一套基线，不受字体/渲染影响 |
| 可访问性验证 | `aria()` | 直接测试 ARIA 树结构 |
| CI/CD 回归测试 | `aria()` | 高度可靠，低误报率 |
| 设计师确认 UI | `visual()` | 像素级对比，截图直观可见 |
| 像素级精确验证 | `visual()` | 配合遮罩，精确控制对比范围 |

---

## 相关文档

| 文档 | 说明 |
|------|------|
| [README.md](./README.md) | 框架总览 |
| [API_MONITOR_README.md](./API_MONITOR_README.md) | API 监控工具指南 |
| [BROWSERSTACK_INTEGRATION.md](./BROWSERSTACK_INTEGRATION.md) | BrowserStack 云测试配置 |
