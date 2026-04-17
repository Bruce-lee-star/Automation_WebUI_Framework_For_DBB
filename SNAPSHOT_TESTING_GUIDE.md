# 快照测试（视觉回归测试）使用指南

基于 Playwright 的企业级视觉回归测试解决方案，用于检测页面/元素的 UI 变化。

---

## 📦 核心类概览

| 类名 | 功能 | 使用场景 |
|------|------|----------|
| **SnapshotTester** | 页面/元素快照捕获与对比 | UI 回归测试、视觉一致性验证 |
| **SnapshotResult** | 快照测试结果 | 获取测试结果、差异详情 |
| **SnapshotReportGenerator** | 独立 HTML 报告生成器 | 生成与 Serenity 分离的对比报告 |

---

## 🎯 核心特性

- ✅ **双模式支持**：页面级快照 + 元素级快照
- ✅ **基线管理**：自动创建 / 更新 / 对比
- ✅ **像素级对比**：逐字节 RGBA 差异检测
- ✅ **抗锯齿容差**：可配置容忍度，避免误报
- ✅ **失败截图**：自动保存失败时的当前截图
- ✅ **Serenity 集成**：结果自动记录到 Serenity 报告
- ✅ **独立 HTML 报告**：生成与 Serenity 完全分离的对比报告（统计摘要 + 结果表格）
- ✅ **灵活配置**：支持 `serenity.properties` 和代码两种配置方式

---

## 🚀 快速开始

### 场景 1：首次运行 — 创建基线

```java
// 首次执行时，创建基线图片（后续对比的参考标准）
SnapshotTester.forPage(page)
    .baselineName("login-page")
    .updateBaseline(true)       // true = 创建或更新基线
    .snapshot();
```

**输出：**
```
[Snapshot] Baseline created: target/snapshots/baselines/login-page.png (125432 bytes)
```

基线文件存储在 `target/snapshots/baselines/login-page.png`。

---

### 场景 2：CI/CD — 验证模式（推荐）

```java
// CI/CD 环境中使用：只做对比，不修改基线
SnapshotTester.forPage(page)
    .baselineName("login-page")
    .updateBaseline(false)      // false = 仅对比，不修改基线
    .assertSnapshot();          // 失败时抛出 AssertionError
```

**通过：** 无异常，测试继续  
**失败：** 抛出 `AssertionError: Snapshot mismatch for 'login-page': diff pixels=1523 (0.85%), threshold=1000`

---

### 场景 3：元素级快照

```java
// 对页面中的某个元素进行快照对比（如按钮、卡片、图表）
Locator submitButton = page.locator("#submit-btn");

SnapshotTester.forElement(submitButton)
    .baselineName("submit-button")
    .updateBaseline(false)
    .snapshot();                 // 或 .assertSnapshot()
```

---

## 📖 完整使用示例

### 示例 1：登录页面完整回归测试

```java
@Test
public void testLoginPageVisualRegression() {
    // 1. 导航到登录页面
    loginPage.navigateTo(loginUrl);

    // 2. 执行登录操作
    loginPage.USERNAME_INPUT.type("testuser");
    loginPage.PASSWORD_INPUT.type("Password123");
    loginPage.LOGIN_BUTTON.click();

    // 3. 等待页面稳定
    loginPage.WELCOME_MSG.waitForVisible(10);

    // 4. 整页快照验证
    SnapshotTester.forPage(page)
        .baselineName("home-page-after-login")
        .updateBaseline(false)           // CI 环境：不更新基线
        .maxDiffThreshold(2000)          // 允许最大 2000 像素差异
        .ignoreAntiAlias(true)           // 忽略抗锯齿噪声
        .assertSnapshot();

    // 5. 关键区域元素快照
    SnapshotTester.forElement(page.locator(".user-profile-card"))
        .baselineName("user-profile-card")
        .updateBaseline(false)
        .assertSnapshot();
}
```

### 示例 2：多步骤 UI 验证

```java
@Test
public void testMultiStepVisualCheck() {
    // 步骤 1: 初始状态
    SnapshotTester.forPage(page)
        .baselineName("dashboard-initial")
        .updateBaseline(false)
        .snapshot();

    // 步骤 2: 点击筛选器后的状态
    dashboard.FILTER_BTN.click();
    SnapshotTester.forPage(page)
        .baselineName("dashboard-filtered")
        .updateBaseline(false)
        .snapshot();

    // 步骤 3: 展开侧边栏后的状态
    dashboard.SIDEBAR_TOGGLE.click();
    SnapshotTester.forElement(page.locator(".sidebar"))
        .baselineName("sidebar-expanded")
        .updateBaseline(false)
        .assertSnapshot();
}
```

### 示例 3：获取详细结果（不断言）

```java
// 获取快照结果，自定义断言逻辑
SnapshotTester tester = SnapshotTester.forPage(page)
    .baselineName("checkout-page")
    .updateBaseline(false);

SnapshotResult result = tester.snapshot();

if (!result.isPassed()) {
    logger.warn("快照差异: {} 像素 ({:.2f}%)",
        result.getDiffPixels(), result.getDiffPercent());
    
    if (result.getDiffPercent() < 5.0) {
        // 小幅差异：记录警告但不算失败
        logger.warn("可接受的微小差异，继续测试");
    } else {
        // 大幅差异：判定为失败
        fail("UI 变化超过阈值: " + result.getDetails());
    }
}

// 访问结果字段
String status = result.getStatus();         // PASSED / FAILED / NO_BASELINE / ERROR
String details = result.getDetails();      // 详细描述
int diffPixels = result.getDiffPixels();   // 差异像素数
int diffPercent = result.getDiffPercent(); // 差异百分比
String failureScreenshot = result.getFailureScreenshotPath(); // 失败截图路径
```

---

## 🔧 Builder 配置选项

| 方法 | 参数 | 默认值 | 说明 |
|------|------|--------|------|
| `baselineName(name)` | String | 必填 | 基线标识名称（自动转义为合法文件名） |
| `updateBaseline(update)` | boolean | false | true=创建/更新基线, false=仅对比 |
| `maxDiffThreshold(threshold)` | int | 1000 | 最大允许差异像素数（超过则失败） |
| `ignoreAntiAlias(ignore)` | boolean | true | 是否忽略抗锯齿等微小差异 |
| `failureScreenshot(enable)` | boolean | true | 失败时是否自动保存截图 |

### 创建实例的三种方式

```java
// 方式 1: 页面快照
SnapshotTester tester = SnapshotTester.forPage(page).build();

// 方式 2: 元素快照
SnapshotTester tester = SnapshotTester.forElement(locator).build();

// 方式 3: 简化调用
SnapshotTester tester = SnapshotTester.of(page);
SnapshotTester tester = SnapshotTester.of(locator);
```

---

## ⚙️ 配置项（serenity.properties）

```properties
# ==================== 快照测试配置 ====================

# 是否启用快照测试（全局开关）
# true=启用, false=跳过所有快照测试
snapshot.testing.enabled=true

# 基线文件存储目录（⚠️ 必须放在 src/ 下，不要用 target/）
# target/ 是 Maven 构建输出目录，mvn clean 会删除基线！
# 推荐值: src/test/resources/snapshots/baselines
snapshot.baseline.dir=src/test/resources/snapshots/baselines

# 当前快照和失败截图存储目录（运行时临时目录，可放 target/）
snapshot.dir=target/snapshots

# 最大差异像素阈值（超过此值视为失败）
snapshot.diff.threshold=1000

# 是否忽略抗锯齿等微小差异（推荐开启）
snapshot.ignore.antiAlias=true

# 失败时是否自动截图
snapshot.failure.screenshot=true
```

### 配置优先级

```
代码 Builder 设置 > serenity.properties > 框架默认值
```

例如：
```java
// 代码设置会覆盖 properties 配置
SnapshotTester.forPage(page)
    .baselineName("test")
    .maxDiffThreshold(5000)     // 覆盖 snapshot.diff.threshold=1000
    .snapshot();
```

---

## 📄 独立 HTML 对比报告（与 Serenity 完全分离）

框架提供 **`SnapshotReportGenerator`**，生成独立的 HTML 快照对比报告，不依赖 Serenity。

### 为什么需要独立报告？

| Serenity 集成 | 独立报告 |
|---------------|---------|
| 嵌入在测试步骤报告中 | 单独的 HTML 文件 |
| 每个结果分散在各 Step 中 | 所有结果汇总在一个表格中 |
| 适合开发调试 | 适合发送给设计师/产品经理审查 |
| JSON 格式嵌入 | 精美可视化 UI + 统计面板 |

### 使用方式

#### 方式一：基本用法

```java
// 执行多个快照测试
SnapshotTester.forPage(page).baselineName("login-page").updateBaseline(false).snapshot();
SnapshotTester.forPage(page).baselineName("home-page").updateBaseline(false).snapshot();
SnapshotTester.forElement(page.locator(".header")).baselineName("header-bar").updateBaseline(false).snapshot();

// 所有快照完成后，一键生成报告
String reportPath = SnapshotTester.generateReport();
logger.info("Snapshot report: {}", reportPath);
// 输出: target/snapshots/reports/snapshot-report-2026-04-17_07-54-30.html
```

#### 方式二：自定义输出目录

```java
String reportPath = SnapshotTester.generateReport("custom-report-dir");
```

#### 方式三：获取摘要文本（用于日志/邮件/通知）

```java
// 获取统计摘要
String summary = SnapshotTester.getSummary();
// 输出: "Snapshot Tests: 5 total | 4 PASS | 1 FAIL | 0 CREATED | 0 NO_BASELINE | 0 ERROR | 0 SKIPPED"

// 在测试结束后输出到控制台
@After
public void tearDown() {
    System.out.println("\n" + SnapshotTester.getSummary());
}
```

#### 方式四：Cucumber Hook 中自动生成

```java
@After(order = 1000) // 最后执行
public void generateSnapshotReport(Scenario scenario) {
    String path = SnapshotTester.generateReport();
    if (path != null) {
        scenario.attach(path, "text/html", "snapshot-report");
        // 或写入 log
        scenario.write("Snapshot Report: " + path);
    }
    // 清空结果，准备下一个 Scenario
    SnapshotTester.clearResults();
}

@Before
public void beforeScenario() {
    SnapshotTester.clearResults();
}
```

### 报告内容

生成的 HTML 报告包含：

**1. 头部信息**
- 报告标题和生成时间
- 渐变色深色主题头部

**2. 统计摘要卡片**

| Total | PASS | FAIL | CREATED | Pass Rate |
|-------|------|------|---------|-----------|
| 数字 | 绿色数字 | 红色数字 | 蓝色数字 | 百分比 |

**3. 结果明细表格**

| # | Baseline Name | Status | Diff Pixels | Diff % | Details |
|---|---------------|--------|-------------|--------|---------|
| 1 | login-page | <span style="color:green">PASS</span> | 45 | 0.03% | diff pixels=45 (0.03%), threshold=1000 |
| 2 | header-bar | <span style="color:red">FAIL</span> | 3241 | 2.15% | diff pixels=3241 (2.15%), threshold=1000 |

每行特性：
- 左侧彩色边框指示状态（绿=通过 / 红=失败 / 蓝=新建 / 灰=跳过）
- 差异百分比带进度条可视化（低<5%=黄 / 中5-20%=橙 / 高>20%=红）
- 详情包含文件大小变化、失败截图路径

**4. 最新报告链接**

每次生成都会同时更新 `snapshot-report-latest.html`，方便固定 URL 访问。

### 报告输出位置

```
target/snapshots/reports/
├── snapshot-report-2026-04-17_07-54-30.html   ← 带时间戳的历史报告
├── snapshot-report-2026-04-17_08-10-15.html
└── snapshot-report-latest.html                  ← 始终最新的报告（覆盖更新）
```

### 报告预览效果

报告采用深色头部 + 白色卡片的现代化设计：

```
┌─────────────────────────────────────────────────────┐
│  📸 Snapshot Test Report                    2026-04 │
│     Visual Regression Test Results                 │
└─────────────────────────────────────────────────────┘

┌──────┐ ┌──────┐ ┌──────┐ ┌────────┐ ┌──────────┐
│ Total │ │ PASS │ │ FAIL │ │CREATED  │ │Pass Rate │
│   8   │ │  6   │ │  1   │ │   1    │ │  75.0%   │
└──────┘ └──────┘ └──────┘ └────────┘ └──────────┘

┌────────────────────────────────────────────────────────┐
│ #  │ Name          │Status │ DiffPx │ Diff% │Details │
├────┼───────────────┼───────┼────────┼───────┼─────────│
│ 1  │ login-page    │ PASS  │   45   │ 0.03% │ ...     │
│ 2  │ home-page    │ FAIL  │  3241  │ 2.15% │ ████░   │
│ 3  │ header-bar   │CREATED│   -    │   -   │ ...     │
└────────────────────────────────────────────────────────┘
```

---

## 📂 文件结构

快照测试生成的文件按以下结构组织：

```
src/test/resources/snapshots/           # ★ 基线文件（提交到版本控制）
└── baselines/                          # 基线图片（团队共享的 UI 参考标准）
    ├── login-page.png                 # 登录页面基线
    ├── submit-button.png              # 提交按钮基线
    └── user-profile-card.png          # 用户资料卡基线

target/snapshots/                       # 运行时生成（不纳入版本控制）
├── current/                            # 当前快照（每次运行生成）
│   ├── login-page_2026-04-17_07-30-00.png
│   └── submit-button_2026-04-17_07-30-01.png
└── failures/                           # 失败截图（仅对比失败时生成）
    └── login-page_2026-04-17_07-30-02.png
```

> **目录设计说明：**
> - `src/test/resources/snapshots/baselines/` — 基线持久化存储在源码中，受 Git 管理，`mvn clean` 不会删除
> - `target/snapshots/current/` 和 `failures/` — 运行时临时输出，无需版本控制，应加入 `.gitignore`

> ⚠️ **Git 管理**：`src/test/resources/snapshots/baselines/` 目录应提交到版本控制仓库，作为团队共享的 UI 参考标准。

---

## 🔍 差异检测原理

### 像素级对比算法

```
基线图片 (PNG)          当前截图 (PNG)
┌─────────────┐        ┌─────────────┐
│ R G B A ... │   VS   │ R G B A ... │
│ R G B A ... │ ----→  │ R G B A ... │
│ ...         │        │ ...         │
└─────────────┘        └─────────────┘
        ↓                       ↓
    逐字节对比             统计差异
        ↓                       ↓
    差异像素计数            与阈值比较
```

### 结果判定逻辑

```java
diffPixels = countDiffPixels(baselineData, currentData);
totalPixels = baseline.length / 4;  // RGBA = 4 bytes/pixel
diffPercent = (diffPixels * 100) / totalPixels;

// 抗锯齿容差调整
if (ignoreAntiAlias) {
    effectiveDiffPixels = max(0, diffPixels - antiAliasTolerance);
}

// 最终判定
passed = (effectiveDiffPixels <= maxDiffThreshold);
```

### 抗锯齿容差机制

浏览器渲染时会产生亚像素级别的抗锯齿差异，这些差异通常不是真正的 UI 变化。框架通过估算图像大小的比例来计算容差值：

```
容差值 ≈ 图像字节数 / 2700 * 50（最小 50 像素）
```

---

## 🎨 SnapshotResult 结果对象

```java
public class SnapshotResult {
    // === 基本信息 ===
    String getName();              // 基线名称
    String getStatus();            // PASSED / FAILED / NO_BASELINE / ERROR / SKIPPED / CREATED
    boolean isPassed();            // 是否通过
    
    // === 差异统计 ===
    int getDiffPixels();           // 差异像素数
    int getDiffPercent();          // 差异百分比（整数，如 3 表示 3%）
    
    // === 文件大小 ===
    long getBaselineSize();        // 基线文件大小（字节）
    long getCurrentSize();         // 当前文件大小（字节）
    
    // === 详情与路径 ===
    String getDetails();           // 人类可读的描述
    String getFailureScreenshotPath();  // 失败截图路径（如有）
    
    // === 静态工厂方法 ===
    static SnapshotResult passed(String name, String details, int diffPx, int diffPct);
    static SnapshotResult failed(String name, String details, int diffPx, int diffPct,
                                  long baselineSize, long currentSize);
    static SnapshotResult noBaseline(String name, String details);   // 基线不存在
    static SnapshotResult error(String name, String details);       // 执行出错
    static SnapshotResult skipped(String name, String reason);      // 已禁用
    static SnapshotResult created(String name, String details, long size); // 基线已创建
}
```

> **注意**：每次调用 `snapshot()` 后，结果会**自动注册**到 `SnapshotReportGenerator`，
> 无需手动操作，后续调用 `SnapshotTester.generateReport()` 即可生成包含所有结果的独立报告。

---

## 📊 报告集成

### 选项 A：Serenity 报告（可选）

每次执行 `snapshot()` 或 `assertSnapshot()` 后，结果自动记录到 Serenity 报告：

```json
{
  "name": "login-page",
  "status": "PASS",
  "passed": true,
  "details": "diff pixels=45 (0.03%), threshold=1000",
  "diffPixels": 45,
  "diffPercent": 0,
  "baselineSize": 125432,
  "currentSize": 124988,
  "failureScreenshot": null
}
```

在 Serenity HTML 报告中以 **"Snapshot Test: login-page [PASS]"** 标题展示。

### 选项 B：独立 HTML 报告（推荐，与 Serenity 完全分离）

👉 详见上方 [**独立 HTML 对比报告**](#-独立-html-对比报告与-serenity-完全分离) 章节。

> **两种报告可以同时使用**：Serenity 用于开发调试时的步骤级查看，独立报告用于发送给设计师/产品经理的 UI 审查。

---

## 🔄 基线管理最佳实践

### 何时更新基线？

| 场景 | 操作 | 说明 |
|------|------|------|
| **全新功能** | `updateBaseline(true)` | 首次创建基线 |
| **UI 有意变更** | `updateBaseline(true)` | 设计师确认后的 UI 改版 |
| **日常回归测试** | `updateBaseline(false)` | CI/CD 标准流程 |
| **误报修复** | 手动替换 baselines/ 下文件 | 确认是渲染波动而非真实变化 |

### 团队协作工作流

```
开发者A: 开发新功能 → 本地运行 updateBaseline(true) → 提交 baseline PNG 到 Git
                    ↓
代码审查: Reviewer 检查基线截图是否正确
                    ↓
合并到主分支: Jenkins CI 运行 updateBaseline(false) 进行回归检查
                    ↓
UI 改版时: 设计师确认 → 更新基线 → 提交新 baseline PNG
```

### 基线版本管理建议

```bash
# 为重要版本打标签，保留历史基线
git tag v1.0-baseline

# 如需回滚到旧版本 UI
git checkout v1.0-baseline -- src/test/resources/snapshots/baselines/
```

### 误报修复：手动替换基线

```bash
# 方式 1: 用 updateBaseline(true) 重新生成（推荐）
# 在测试代码中临时改为 true，运行一次后改回 false

# 方式 2: 手动将当前截图复制为基线
cp target/snapshots/current/login-page_xxx.png \
   src/test/resources/snapshots/baselines/login-page.png
git add src/test/resources/snapshots/baselines/login-page.png
git commit -m "update baseline: login page UI redesign"
```

---

## ⚠️ 注意事项

### 1. 动态内容处理

快照测试对动态内容（时间戳、随机数、广告）敏感，建议：
- 使用测试环境固定数据
- 在快照前隐藏动态元素：`page.evaluate("document.querySelector('.timestamp').style.display='none'")`
- 或对特定区域做元素级快照而非整页

### 2. 视口一致性

确保基线和测试使用相同的视口大小：
```properties
playwright.context.viewport.width=1366
playwright.context.viewport.height=768
```

### 3. 字体和渲染

不同操作系统/浏览器的字体渲染可能存在细微差异：
- CI 环境建议使用 Docker 固定容器镜像
- 或适当提高 `maxDiffThreshold` 和 `ignoreAntiAlias`

### 4. 性能影响

快照测试涉及图片 I/O 和字节比对，对性能有轻微影响：
- 单次快照耗时约 50-200ms（取决于图片大小）
- 大量快照场景建议选择性执行

---

## ❌ 常见问题排查

### Q1: "Baseline not found" 错误
```
原因: 基线文件不存在（首次运行或基线未提交到 Git）
解决: 先用 updateBaseline(true) 创建基线
```

### Q2: 每次运行都有少量差异（< 1%）
```
原因: 浏览器渲染的抗锯齿/子像素差异
解决: 确保 ignoreAntiAlias=true（默认已开启），或适当提高 maxDiffThreshold
```

### Q3: 大量差异但 UI 看起来没变
```
可能原因:
- 视口大小不一致
- 字体加载延迟（字体尚未完全渲染就截图）
- CSS 动画未完成

解决: 
- 检查视口配置一致
- 截图前等待字体加载: page.waitForLoadState("networkidle")
- 禁用动画: page.addStyleTag("*, *::before, *::after { animation-duration: 0s !important; }")
```

### Q4: 如何只对部分 Feature 运行快照？
```
方案 1: 通过 Cucumber Tag 过滤
@visual-regression
Scenario: 登录页面视觉检查
  ...

方案 2: 代码中条件判断
if (isSnapshotEnabled()) {
    SnapshotTester.forPage(page)...snapshot();
}
```

---

## 📚 相关文档

| 文档 | 说明 |
|------|------|
| [README.md](./README.md) | 框架总览 |
| [API_MONITOR_README.md](./API_MONITOR_README.md) | API 监控工具指南 |
| [BROWSERSTACK_INTEGRATION.md](./BROWSERSTACK_INTEGRATION.md) | BrowserStack 云测试配置 |

---

## 📋 SnapshotTester 静态 API 速查

### 实例化

| 方法 | 返回值 | 说明 |
|------|--------|------|
| `SnapshotTester.forPage(page)` | Builder | 创建页面快照测试 |
| `SnapshotTester.forElement(locator)` | Builder | 创建元素快照测试 |
| `SnapshotTester.of(page)` | SnapshotTester | 简化创建（页面） |
| `SnapshotTester.of(locator)` | SnapshotTester | 简化创建（元素） |

### 执行与报告

| 方法 | 参数 | 返回值 | 说明 |
|------|------|--------|------|
| `.snapshot()` | - | SnapshotResult | 执行快照验证，**结果自动注册到报告** |
| `.assertSnapshot()` | - | void | 执行并断言（失败抛异常） |
| **`SnapshotTester.generateReport()`** | - | String | 生成独立 HTML 报告（默认目录） |
| **`SnapshotTester.generateReport(dir)`** | String | String | 生成独立 HTML 报告到指定目录 |
| **`SnapshotTester.getSummary()`** | - | String | 获取统计摘要文本 |
| **`SnapshotTester.clearResults()`** | - | void | 清空已收集的结果 |

### SnapshotReportGenerator 直接调用（等价）

| 方法 | 参数 | 返回值 | 说明 |
|------|------|--------|------|
| `SnapshotReportGenerator.registerResult(result)` | SnapshotResult | void | 注册一个结果 |
| `SnapshotReportGenerator.clearResults()` | - | void | 清空所有结果 |
| `SnapshotReportGenerator.getResults()` | - | List\<SnapshotResult\> | 获取所有已注册结果 |
| `SnapshotReportGenerator.generate()` | - | String | 生成报告（默认目录） |
| `SnapshotReportGenerator.generate(dir)` | String | String | 生成报告（指定目录） |
| `SnapshotReportGenerator.getSummary()` | - | String | 获取统计摘要 |
