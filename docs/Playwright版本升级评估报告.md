# Playwright Java 版本升级评估报告

评估对象：`Automation_WebUI_Framework_For_DBB` 的 web 模块所依赖的 `com.microsoft.playwright:playwright`
当前版本：**1.58.0**（2026-01-28 发布）
评估目标版本：**1.62.0**（2026-08-03 发布，目前最新稳定版）
评估日期：2026-09-11
结论：**建议升级，直接到 1.62.0**

---

## 1. 结论概要（TL;DR）

- 当前落后 **4 个版本 / 约 7 个月**，最新稳定版为 **1.62.0**。
- 已对 **1.60 的破坏性变更**全量核查，**本项目零命中**，升级无源码级破坏性改动。
- 与本框架直接对口的收益有三项：**WebP 截图**、**BrowserContext 级生命周期事件（可删除自研 `PageEventMonitor`）**、**`scroll: none`（治 flaky）**。
- 唯一真实风险：**axe-core 无障碍库与 Playwright 的版本耦合**，升级后必须实测 AxeCoreScanner。
- 落地成本极低：改根 POM 的 `playwright.version` 一处即可，其余模块不动。

---

## 2. 版本演进与本项目的关联性

| 版本 | 发布日期 | 关键变更 | 本项目相关性 |
|---|---|---|---|
| 1.58.0 | 2026-01-28 | 当前版本 | — |
| 1.59.0 | 2026-Q1 | `BrowserContext.setStorageState()` 新方法；WebAuthn 增强 | 中（可简化会话恢复逻辑） |
| 1.60.0 | 2026-Q2 | **BrowserContext 生命周期事件**（onPageClose/onPageLoad/onFrameNavigated/onDownload）；移除多项废弃 API | **高（可删自研代码）** |
| 1.61.0 | 2026-Q3 | WebAuthn passkeys（虚拟密码钥匙）；Firefox/WebKit 改进 | 低（银行 passkey 登录有价值） |
| 1.62.0 | 2026-08-03 | **WebP 截图**；**`scroll: none` 选项**；headless 剪贴板与 OS 隔离；放弃 Debian 11 | **高（截图瘦身 + 治 flaky）** |

跨版本浏览器内核跨度：**Chromium 145 → 151**，CI 首次构建需重下三套浏览器二进制（Chromium / Firefox / WebKit），需预留时间或加缓存。

---

## 3. 与本项目直接相关的三项核心收益

### 3.1 WebP 截图（1.62）— 收益最实在

- 现状：`serenity.properties` 配置 `AFTER_EACH_STEP` + `fullpage=true`，银行 E2E 用例多、步骤长，PNG 全页截图常见 **500KB–2MB**。
- 收益：WebP 可降 **60–80%** 体积，CI 制品与报告目录整体瘦身接近一个数量级。
- ⚠️ **使用约束**：不要用在 Serenity 报告截图上。Serenity 4.2.0 对截图后缀/格式可能有内部假设，风险不值得冒。**仅用于自建归档目录**（如 `PlaywrightScreenshotManager` 落盘的合规证据目录）。

### 3.2 BrowserContext 生命周期事件（1.60）— 可删除自研代码

- 现状：`PageEventMonitor` 维护 `REGISTERED` 并发集合做幂等去重、靠 `onClose` 清理防泄漏；`ApiCaptureLifecycle` 曾因重复注册 `onResponse` 导致计数漂移（同类问题）。
- 收益：1.60 起可在 **BrowserContext 级**注册一次 `onPageClose` / `onPageLoad` / `onFrameNavigated` / `onDownload`，自动覆盖该 Context 下所有页面（含新标签页、弹窗）。
- 升级后可**直接删除** `PageEventMonitor` 的幂等去重与泄漏防护逻辑，同时堵住"新标签页/弹窗漏注册"这个洞。

### 3.3 `scroll: none` 选项（1.62）— 治 flaky

- 现状：银行长表单多，Playwright 自动 `scroll-into-view` 遇 sticky header 会造成点击偏移，是 E2E 经典抖动源。
- 收益：1.62 可显式关闭自动滚动（`scroll: none`），在已知布局处消除偏移类 flaky。

---

## 4. 次要收益（按需采用）

- **`context.setStorageState()`（1.59）**：免去当前"改会话即重建 Context"的绕路设计，`SessionManager` 会话恢复可更轻量。
- **WebAuthn passkeys（1.61）**：让 passkey 登录成为可测路径，对银行系统有潜在价值。

---

## 5. 唯一真实风险：axe-core 版本耦合

`com.deque.html.axe-core:playwright:4.9.1` 的 POM **编译期绑定 `playwright:1.42.0`**。当前靠根 POM 显式声明 `1.58.0` 覆盖，等于已跨越 16 个版本运行。

- 现状判断：它目前跑得好，说明 axe 用到的核心 API 在这一大段版本里稳定，再加 4 个版本风险有限。
- 先例警示：同类事故有先例 —— Evinced 的 Playwright SDK 曾在 1.59 上因 `NoSuchMethodError` 直接崩溃。
- **建议**：升级 Playwright 同时，将 axe-core 升到开源最新 **4.10.2**（绑定 `playwright:1.49.0`），把版本差距从 1.42 拉近到 1.49，风险敞口显著缩小。

---

## 6. 破坏性变更核查结果（1.60 移除项）

逐项扫描全仓库（`core/web/api/route/codegen/test-automation` 源码）：

| 1.60 移除的 API | 命中情况 | 结论 |
|---|---|---|
| `ariaRef` | 零命中 | 无影响 |
| `videosPath` / `videoSize` | 零命中 | 无影响 |
| `exposeBinding` 旧 `handle` 选项 | 仅 codegen 的 `RolePickerBridgeRegistry` 使用，且已是新版双参 lambda | 无影响 |
| `connectOverCDP()` | BrowserStack 走 `browserType.connect()` 而非 `connectOverCDP()` | 无影响 |

**环境核查**：CI 为 `ubuntu-latest`；1.62 仅放弃 Debian 11，不影响本流水线。

---

## 7. 升级落地方案与验证清单

### 7.1 改动范围

- 仅改根 `pom.xml` 的 `playwright.version` 属性（一处），其余模块无需改动。
- 同步建议：`axe-core:playwright` 升至 `4.10.2`。

### 7.2 升级后必验清单（按风险排序）

1. **AxeCoreScanner 无障碍扫描** —— 唯一可能崩溃点，重点验证。
2. **route 模块 mock / modify / monitor 全链路** —— 确认流量改写与抓取未受影响。
3. **共享 Browser 并发模式**（`SHARED_BROWSER_MODE=true`）—— 验证多场景并发隔离。
4. **BrowserStack 云测连通性** —— 确认 `browserType.connect()` 握手。
5. **SessionManager 会话恢复** —— 确认 `setStorageState` 兼容。
6. **截图在 Serenity 报告中的渲染** —— 确认未被 WebP 改动波及。
7. **用到 clipboard 的用例** —— 1.62 起 headless 剪贴板已与操作系统隔离，行为可能变化。

### 7.3 分阶段建议

- **阶段一（本次）**：只升版本 + 跑通 `test-automation` 的 route 与 web 套件看结果。
- **阶段二（确认无误后）**：用新 API 重构 `PageEventMonitor`（删自研去重/泄漏防护），引入 `scroll: none` 治理已知 flaky。

---

## 8. 建议行动

1. 根 POM `playwright.version` → `1.62.0`。
2. `axe-core:playwright` → `4.10.2`。
3. 触发 CI，按 §7.2 清单逐项验；AxeCoreScanner 不过则回退 axe 版本重验。
4. 验证通过后进入 §7.3 阶段二重构。

> 备注：本文档基于公开发布说明与本项目源码静态核查生成，1.60/1.62 行为收益需以实际运行验证为准。
