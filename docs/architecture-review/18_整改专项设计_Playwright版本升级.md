# 整改专项设计：Playwright Java 版本升级（1.58.0 → 1.62.0）

> 上游评估：docs/Playwright版本升级评估报告.md（评估日期 2026-09-11，结论：建议升级，直接到 1.62.0）。
> 任务登记：本专项为独立小专项，落地成本极低（根 POM 改版本属性 + 同步 axe-core），但需以全护盾验证兜底。

## 1. 目标与范围

- 目标版本：`com.microsoft.playwright:playwright` **1.58.0 → 1.62.0**（跨 1.59/1.60/1.61/1.62 共 4 个版本，浏览器内核 Chromium 145→151）。
- 同步建议：`com.deque.html.axe-core:playwright` **4.9.1 → 4.10.2**（缩小与 Playwright 的版本差距，降低 NoSuchMethodError 风险敞口）。
- 改动范围（阶段一，本次）：仅根 `pom.xml` 版本号，其余模块零改动（各模块经由根 `dependencyManagement` 统一钉版本）。
- 不改动：业务源码、阶段二重构（PageEventMonitor 去重/泄漏防护删除、`scroll: none` 治理 flaky）—— 留待升级验证通过后另行立项。

## 2. 任务清单

| 编号 | 任务 | 范围 | 验收 | 状态 |
|---|---|---|---|---|
| T1 | 根 POM `playwright.version` → `1.62.0` | pom.xml:35 | 依赖树显示 playwright 1.62.0 | ✅ |
| T2 | `axe-core:playwright` → `4.10.2` | pom.xml:324（DM 硬编码版本） | 依赖树显示 axe-core playwright 4.10.2 | ✅ |
| T3 | 依赖解析 + Enforcer 上界校验 | 全仓 | RequireUpperBoundDeps 全 8 模块通过 | ✅ |
| T4 | 编译校验（主 + 测试） | test-automation -am | `mvn -pl test-automation -am test-compile` BUILD SUCCESS | ✅ |
| T5 | 跑 route + web 套件（阶段一验证） | test-automation | route 单测（2例）绿 ✅；web 浏览器套件待 CI/浏览器二进制 | 🟡 部分 |
| T6 | AxeCoreScanner 无障碍扫描实测 | web 用例 | 无 NoSuchMethodError / 扫描正常出报告 | ⬜ 待 CI |
| T7 | 阶段二重构（确认无误后单列） | PageEventMonitor / scroll | 删除自研去重 + 引入 scroll:none | 🔒 待 T5/T6 通过 |

### 3.1 T3 实际暴露的传递依赖缺口（已钉）

bump axe-core 4.10.2 + playwright 1.62.0 触发 Enforcer `RequireUpperBoundDeps` 三项缺口，已在根 POM `dependencyManagement` 钉到上界：

| 依赖 | 原钉版本 | 新钉版本 | 触发来源 |
|---|---|---|---|
| `com.google.code.gson:gson` | 2.13.2 | **2.14.0** | playwright 1.62.0 强制（见 pw_compile3 之前日志 line 154：playwright→gson:2.14.0） |
| `commons-io:commons-io` | 未钉 | **2.18.0** | axe-core 4.10.2 传递（line 167） |
| `org.apache.commons:commons-compress` | 未钉（htmlunit 1.27.0） | **1.27.1** | axe-core 4.10.2 传递（line 186） |
| `com.google.errorprone:error_prone_annotations` | 2.43.0 | **2.48.0** | gson 2.14.0 传递（line 94：gson→error_prone:2.48.0） |

## 3. 约束与风险（来自评估报告）

- **唯一真实风险**：axe-core 与 Playwright 版本耦合（见 T2，已纳入同步升级）。
- **破坏性变更核查**：1.60 移除 API（ariaRef / videosPath / videoSize / exposeBinding 旧 handle / connectOverCDP）全仓零命中，升级无源码级破坏性改动。
- **环境**：CI `ubuntu-latest`，1.62 仅放弃 Debian 11，不影响流水线；但首次构建需重下 Chromium/Firefox/WebKit 三套二进制，需预留下载时间或加缓存。
- **WebP 截图**：仅用于自建归档目录，不用于 Serenity 报告截图（Serenity 4.2.0 对格式有内部假设，风险不值得冒）—— 属阶段二范畴。
- **headless 剪贴板**：1.62 起与操作系统隔离，用到 clipboard 的用例行为可能变化（§7.2 #7 验证项）。

## 4. 验证清单（评估报告 §7.2，按风险排序）

1. AxeCoreScanner 无障碍扫描（唯一可能崩溃点）— T6。
2. route 模块 mock/modify/monitor 全链路流量改写与抓取 — T5。
3. 共享 Browser 并发模式（`SHARED_BROWSER_MODE=true`）多场景隔离。
4. BrowserStack 云测 `browserType.connect()` 握手。
5. SessionManager 会话恢复（`setStorageState` 兼容）。
6. 截图在 Serenity 报告中的渲染未被波及。
7. 用到 clipboard 的用例行为变化。

## 5. 回退预案

- 若 T6 AxeCoreScanner 抛 `NoSuchMethodError`：先回退 axe-core 至 4.9.1 重验（保留 Playwright 1.62.0）；仍不行则整体回退 Playwright 至 1.58.0。
- 版本号改动可逆（仅两行），回退代价极低。

## 6. 落地记录

- 2026-09-12：创建任务清单；T1/T2 版本号改动落地；T3/T4/T5(部分) 验证完成。
  - T1/T2：根 POM `playwright.version` 1.58.0→1.62.0、`axe-core:playwright` 4.9.1→4.10.2。
  - T3：Enforcer `RequireUpperBoundDeps` 首报 gson/commons-io/commons-compress 三项缺口（来自 axe 4.10.2 与 playwright 1.62.0 传递），钉 gson 2.14.0 / commons-io 2.18.0 / commons-compress 1.27.1 后复报 error_prone_annotations 2.48.0（来自 gson 2.14.0 传递），钉后全 8 模块通过。
  - T4：`mvn -pl test-automation -am test-compile` BUILD SUCCESS。
  - T5（部分）：route 模块单测（2 例）+ reporting（8 例）BUILD SUCCESS，纯逻辑层零回归。
  - T6（AxeCoreScanner 真实浏览器扫描）、T5 完整 web 浏览器套件：需 Chromium 151 二进制（跨版本重下），按报告 §8.3 交由 CI / 带浏览器环境执行。
