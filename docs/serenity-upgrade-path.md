# Serenity BDD 升级路径与回归验证清单（D6-3）

> 维度：依赖解耦(9) ｜ 验收：制定 serenity 升级路径与回归验证清单，化「被动跟随上游降级」为「主动受控升级」
> 关联：评审报告 §L172/L174/L180（被动降级脆弱性）、根 `pom.xml` 版本钉版注释（CORE-P0-1）

---

## 1. 动机：为什么会被「被动降级」

Serenity BDD 的 `dependencyManagement` 父 POM 会钉一批传递依赖版本（byte-buddy / guava / jackson 等）。
当框架为修复 CVE 或对齐其它组件而把某传递依赖钉到**更高上界**时，`requireUpperBoundDeps` 门禁会要求统一到上限——
而 Serenity 自身传递的版本若低于该上限，就会**冲突**。历史上为消冲突，采取了「把 serenity 降到能兼容低上界的版本」的捷径：

- **CORE-P0-1**：为适配 `jackson 2.18.3` 上界，将 `serenity` 从更高版本**降到 4.2.0**（评审报告 L174 原文：
  「serenity 4.2.0 是为适配 jackson 2.18.3 而降级的结果，存在被动跟随上游的脆弱性」）。

后果：框架的 serenity 版本不是「主动选型」而是「被传递依赖上界逼出来的妥协」，每次上游漂移都要重走一遍降级。
本路径把决策显式化——未来冲突时**主动在「升框架上界 / 钉 serenity 到兼容版」之间权衡**，并按下方清单验证，而非盲目降级。

---

## 2. 当前钉版事实表（单一事实来源 = 根 `pom.xml` `<properties>` + `<dependencyManagement>`）

| 构件 | 当前版本 | 钉版根因 / 上界覆盖 |
|---|---|---|
| `serenity.version` | **4.2.0** | 属性集中（已分离 `serenity.cucumber.version`） |
| `serenity-cucumber` | 4.2.0 | 配 `cucumber 7.31.0` |
| `cucumber` | 7.31.0 | 属性集中，README 技术栈表据此 |
| `selenium-support` | 4.23.1 | 跟随 serenity 4.2.0 官方配套（CORE-P0-1 降 serenity 适配 jackson 时同步） |
| `playwright` | 1.58.0 | 独立，不受 serenity 升降影响 |
| `jackson`（core/annotations/databind） | 2.18.3 | 三件套统一钉（CORE-P0-1 / ENG-P0-2）；core `SensitiveDataSanitizer` 直接依赖 |
| `byte-buddy` | 1.17.8 | **覆盖 serenity 父 POM 的 1.14.18**（selenium-support 4.23.1 传递 1.17.8，requireUpperBoundDeps 取上限） |
| `guava` | 33.5.0-jre | **覆盖 serenity 父 POM 的 33.2.1**（web 显式使用，评审报告 L172 指出仍在漂移） |
| `freemarker` | 2.3.33 | serenity-model **直接依赖** 2.3.33（SummaryReportGenerator 用 `VERSION_2_3_33` 匹配） |
| `slf4j-api` | 2.0.17 | 传递覆盖 |
| `logback-classic` | 1.5.34 | CVE 修复 |

> 版本属性已集中（非散落各模块），升级只需改根 `<properties>` 一处 + 同步下方覆盖项。

---

## 3. 升级摩擦点矩阵（requireUpperBoundDeps 冲突的已知触发面）

| # | 冲突对 | 触发现象 | 解法 | 决策点 |
|---|---|---|---|---|
| F1 | serenity 传递 jackson **<** 框架钉的 2.18.3 | enforcer 失败 | 升 serenity 到兼容 2.18.3 的版本；**或** 同步升 jackson 上界（连带 core `SensitiveDataSanitizer` 等需复测） | 优先选「升 serenity」；除非目标 serenity 要求 jackson>2.18.3 才升 jackson |
| F2 | serenity 父钉 byte-buddy 1.14.18 vs selenium-support 传 1.17.8 | enforcer 失败 | DM 显式钉 `byte-buddy 1.17.8` 覆盖（已做） | 维持覆盖；升 selenium-support 后复核 |
| F3 | serenity 父钉 guava 33.2.1 vs web 用 33.5.0 | enforcer 失败 | DM 显式钉 `guava 33.5.0-jre` 覆盖（已做） | 维持覆盖 |
| F4 | serenity-model 直接依赖 freemarker 2.3.33 | SummaryReportGenerator 编译/渲染绑定 | DM 钉 `freemarker 2.3.33`；升级 serenity 后核对其 model 依赖的 freemarker 上界 | 若 serenity 升 freemarker 上界，SummaryReport 模板语法需回归 |
| F5 | serenity-cucumber ↔ cucumber 版本耦合 | cucumber runner 启动失败 / API 不匹配 | 查目标 serenity 的官方配套 cucumber 版本，同步 `cucumber.version` | 不可只升 serenity-cucumber 不动 cucumber |
| F6 | selenium-support 跟随 serenity 官方配套 | 反射/WebDriver API 漂移 | 升 serenity 时同步升 selenium-support 到其官方配套版 | 与 F2 联动 |
| F7 | surefire `argLine` 的 `--add-opens java.base/java.lang=ALL-UNNAMED` 等 | serenity REST 用 ByteBuddy 反射注入类，缺 add-opens 报 `InaccessibleObjectException` | 升级若改注入方式，补 `--add-opens`（根 pom surefire 配置已预留） | 跑 web/route E2E 验证 |

---

## 4. 升级 SOP（在线环境执行，offline 不可下载新 serenity）

1. **备份**：`git stash` 或另存根 `pom.xml` 版本属性块。
2. **提版本**：改根 `<properties>` 的 `serenity.version` / `serenity.cucumber.version` 到目标版本。
3. **同步配套**：按目标 serenity 官方 release notes 同步 `selenium-support`（F6）、核对 `cucumber`（F5）。
4. **编译门禁**：`mvn -o -pl test-automation -am test-compile`
   - 若 `requireUpperBoundDeps` 失败，按 §3 摩擦点矩阵逐个解（调 jackson/byte-buddy/guava/freemarker 上界或确认覆盖项仍有效）。
5. **强封装校验**：若报 `InaccessibleObjectException`，补 surefire `argLine` 的 `--add-opens`（F7）。
6. **回归验证**：跑 §5 清单全部绿。
7. **文档回写**：更新本文档 §2 事实表 + §3 矩阵（新冲突点补入）。
8. **提交**：单一 commit 含版本属性 + 覆盖项调整 + 文档。

---

## 5. 回归验证清单（升级后必跑，覆盖所有 Serenity 集成 seam）

> 集成面 = 全仓 `import net.thucydides.*` 的 19 个文件（listener 桥、result adapter、summary reporter、screenshot、axe、config resolver、route capture/monitor、BDD utils）。

### 5.1 编译与门禁（先跑，快速失败）
```
mvn -o -pl test-automation -am test-compile     # enforcer requireUpperBoundDeps 必须过
mvn -o -pl test-automation -am test -Dtest=ArchitectureTest "-Dsurefire.failIfNoSpecifiedTests=false"   # ArchUnit 无回退
```

### 5.2 web 模块（Serenity 事件桥 / 失败传播 / 截图 / BDD 场景）
```
mvn -o -pl web -am test
```
关键代表性测试（任一失败即阻断升级）：
- `FrameworkListenerBridgeTest` — 框架接口 ↔ Serenity 总线桥接 + 异常隔离
- `ThucydidesStepsListenerAdapterTest` — StepEventBus 适配层
- `RouteAssertionFailureSurfacesToScenarioTest` — 断言失败必置用例失败（R-18 验收）
- `PlaywrightListenerTest` — 生命周期接线
- `FailureScreenshotHandlerTest` — 截图捕获 + 文件名清洗
- `SerenityBusBridgeTest` — Serenity 事件 → 框架接口桥

### 5.3 reporting 模块（freemarker 模板 + 汇总报告，受 F4 影响）
```
mvn -o -pl reporting -am test
```
- `SummaryReportBranchTest` / `SummaryReportGoldenTest` — 模板渲染 + 汇总结构（升级后核对 freemarker 上界）

### 5.4 test-automation Serenity 集成点
- `SerenityResultAdapterTest` — `TestResult`/`StepResult` ↔ Serenity 方言映射（UNDEFINED→PENDING 等）
- `AxeCoreScannerConcurrencyTest` / `AxeCoreListenerConcurrencyTest` — AxeCore 经 Serenity 集成

### 5.5 route E2E 验收（经 serenity-cucumber BDD runner，受 F5/F7 影响）
```
mvn -o -pl test-automation -am test -Dtest=RouteUnifiedBindingBrowserE2ETest,ApiMonitoringRepositoryE2ETest "-Dsurefire.failIfNoSpecifiedTests=false"
```
- `RouteUnifiedBindingBrowserE2ETest` — 浏览器绑定 BDD 端到端
- `ApiMonitoringRepositoryE2ETest` — 监控落库（依赖 route 的 `HikariConfigFactory`，D6-1 已迁 route）

### 5.6 全护盾（最终闸）
```
mvn -o -pl test-automation -am test     # 0 失败 / 0 错误 / 0 跳过
```

---

## 6. 回滚预案
- 版本属性改动未跑通：直接 `git revert` 根 `pom.xml` 版本块；或降回 `serenity 4.2.0`（已知良好基线）+ 配套 `selenium-support 4.23.1`。
- 已跑通但生产环境出现 Serenity 行为异常：优先降 serenity 到 4.2.0 基线，再按 §4 重做（保留本文档记录的冲突点）。

---

## 7. 状态
- ✅ 升级路径 + 摩擦点矩阵 + 回归清单已建立（本文档）。
- ⬜ 实际版本跃迁（4.2.0 → 目标版）**待在线环境按 §4 SOP 执行**（本机 offline 不可下载新 serenity，且本次任务范围为「建立路径」而非「执行升级」）。
- 版本已集中为根 `<properties>`，升级改动面最小（一处属性 + 配套覆盖项）。
