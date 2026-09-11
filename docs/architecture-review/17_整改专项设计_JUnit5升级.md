# 整改专项设计：JUnit 5（Jupiter）升级

> 状态：✅ 已完成（2026-09-11，全护盾 reporting 8 + web 194 + route 2 + test-automation 600 = 804 例，0 失败/0 错误/0 跳过，BUILD SUCCESS；enforcer RequireUpperBoundDeps 全模块通过）。依赖层 + 代码迁移（142 普通测试文件 + 4 个 Cucumber `@Suite` runner）+ 断言参数序修正 + junit-platform 版本对齐均已落地，详见 §12。
> 日期：2026-09-11
> 策略（用户拍板）：仅出方案 + 依赖层先行 + 保持 Serenity 4.2.0（最小变动）

---

## 1. 背景与动机

当前测试框架为 **JUnit 4.13.2 + Serenity JUnit4 集成（`serenity-junit`）**。139+ 测试文件使用 `org.junit.*` API，4 个 Cucumber runner 使用 `@RunWith(CucumberWithSerenity.class)`。JUnit 4 已停止演进，JUnit 5（Jupiter）是行业主流，需平滑迁移以解锁现代断言、`@Nested`、参数化测试、更好的并发模型与 Serenity 的 JUnit5 原生扩展。

**约束**：保持 `serenity.version=4.2.0`（不连带升级 Serenity，避免扩大变更面）。

---

## 2. 现状盘点（数据）

| 维度 | 数据 |
|---|---|
| JUnit 版本 | `junit:junit 4.13.2`（JUnit4） |
| Serenity 测试集成 | `serenity-junit`（JUnit4 runner） |
| `org.junit.*` 测试文件 | 139+（全仓）；0 文件使用 `org.junit.jupiter` |
| 含 JUnit4 测试的模块 | `web`（~30）、`test-automation`（含 4 个 Cucumber IT）、`reporting`（3）、`route`（1 个 `HikariConfigFactoryTest`） |
| 不含 JUnit4 测试的模块 | `core`、`codegen`、`api`（无需改动） |
| Cucumber runner | `CucumberTestRunnerIT`(tags=@test1)、`CucumberE2ESandboxRunnerIT`(tags=@e2e-sandbox)、`CucumberParallelLogonRunnerIT`(tags=@parallel-logon)、`CucumberConcurrentLogonRunnerIT`(tags=@concurrent-logon) |
| 本地 `.m2` 是否已具备 JUnit5 依赖 | **否**（离线环境缺 `junit-jupiter` / `junit-platform` / `serenity-junit5` → 当前不可构建，需在线下载） |

---

## 3. 可行性结论

- `serenity-junit5` 是 Serenity BDD 自 **2.6.0** 起内置的模块（`net.serenitybdd.junit5.SerenityJUnit5Extension`），**4.2.0 必然提供该 artifact**（groupId `net.serenity-bdd`，version 与 `serenity.version` 对齐）。"保持 4.2.0 + 追加 serenity-junit5" 成立。
- 官方文档（5.x）示例用 `junit-jupiter 6.0.3`，但 **4.2.0 须用 5.x**。默认钉 `5.11.4`（与 `cucumber-junit-platform-engine:7.31` 的 `junit-platform` 上界兼容），**在线用 `mvn dependency:tree` 校准至 `serenity-junit5:4.2.0` 传递引入的 `junit-jupiter` 版本**，确保 `requireUpperBoundDeps` 门禁通过。
- Cucumber 集成：JUnit4 的 `@RunWith(CucumberWithSerenity)` → JUnit5 的 `@Suite` + `@IncludeEngines("cucumber")` + `cucumber-junit-platform-engine` 引擎（Cucumber 7 原生 JUnit Platform 引擎）。

---

## 4. 依赖改造（已实施）

### 4.1 根 POM `pom.xml`
- 属性：`junit.version 4.13.2` → `junit.jupiter.version 5.11.4`。
- `<dependencyManagement>`：`serenity-junit` → `serenity-junit5`；新增 `junit-jupiter-api` / `junit-jupiter-engine` / `junit-platform-suite`（均 `${junit.jupiter.version}`）、`cucumber-junit-platform-engine`（`${cucumber.version}=7.31.0`）；移除 `junit:junit`（JUnit4）DM 项（保留 `hamcrest-core 2.2` 钉版本，仍由 Cucumber/AssertJ 传递消费）。

### 4.2 测试模块 POM
| 模块 | 改动 |
|---|---|
| `test-automation` | `serenity-junit`→`serenity-junit5`；`junit:junit`→`junit-jupiter-api`+`junit-jupiter-engine`；新增 `cucumber-junit-platform-engine`+`junit-platform-suite`（Cucumber runner 用） |
| `web` | `serenity-junit`→`serenity-junit5`；`junit:junit`→`junit-jupiter-api`+`junit-jupiter-engine` |
| `reporting` | 同上（`web` 模式） |
| `route` | `junit:junit`→`junit-jupiter-api`+`junit-jupiter-engine`（route 纯 JUnit 测试，不依赖 Serenity，故无 `serenity-junit5`） |

> 注：surefire 3.2.5 原生支持 JUnit Platform，无需改 surefire/failsafe 配置。

---

## 5. 代码迁移规则（JUnit4 → JUnit5 API 映射）

| JUnit4 | JUnit5（Jupiter） |
|---|---|
| `import org.junit.Test;` | `import org.junit.jupiter.api.Test;` |
| `import org.junit.Before;` | `import org.junit.jupiter.api.BeforeEach;` |
| `import org.junit.After;` | `import org.junit.jupiter.api.AfterEach;` |
| `import org.junit.BeforeClass;` | `import org.junit.jupiter.api.BeforeAll;` |
| `import org.junit.AfterClass;` | `import org.junit.jupiter.api.AfterAll;` |
| `import org.junit.Ignore;` | `import org.junit.jupiter.api.Disabled;` |
| `import org.junit.Assert;` | `import org.junit.jupiter.api.Assertions;` |
| `import org.junit.Assume;` | `import org.junit.jupiter.api.Assumptions;` |
| `@Before` / `@After` | `@BeforeEach` / `@AfterEach` |
| `@BeforeClass` / `@AfterClass` | `@BeforeAll` / `@AfterAll`（**须为 `static`**） |
| `@Ignore` | `@Disabled` |
| `@Test(timeout=…)` | `@Test` + `Assertions.assertTimeout(…)`（JUnit5 无 timeout 属性） |
| `@Test(expected=…)` | `assertThrows(Ex.class, () -> …)` |
| `Assert.assertEquals/assertTrue/…` | `Assertions.assertEquals/assertTrue/…` |
| `Assume.assumeTrue/…` | `Assumptions.assumeTrue/…` |

**生命周期可见性**：`@BeforeAll`/`@AfterAll` 方法必须是 `static`（JUnit5 强制）；若原 `@BeforeClass` 方法依赖实例字段，需改为静态字段或注入（`@RegisterExtension`）。

**规则常量**：`org.junit.rules.*`（如 `TemporaryFolder`、`ExpectedException`）→ JUnit5 `org.junit.jupiter.api.io.TempDir` / `assertThrows`；本仓若用到需手工替换（迁移脚本不覆盖，会列出待办）。

---

## 6. Cucumber runner 迁移（4 个 IT）

JUnit4：`@RunWith(CucumberWithSerenity.class)` + `@CucumberOptions(...)`（空类体）。
JUnit5：`@Suite` + `@IncludeEngines("cucumber")` + `@SelectClasspathResource` + `@ConfigurationParameter(...)`。

**通用模板**（以 `CucumberE2ESandboxRunnerIT` 为例）：

```java
package com.hsbc.cmb.hk.dbb.automation.tests.web;

import org.junit.platform.suite.api.ConfigurationParameter;
import org.junit.platform.suite.api.IncludeEngines;
import org.junit.platform.suite.api.SelectClasspathResource;
import org.junit.platform.suite.api.Suite;

import static org.junit.platform.suite.api.ConfigurationParameterKey.FILTER_TAGS_PROPERTY_NAME;
import static org.junit.platform.suite.api.ConfigurationParameterKey.GLUE_PROPERTY_NAME;
import static org.junit.platform.suite.api.ConfigurationParameterKey.PLUGIN_PROPERTY_NAME;

@Suite
@IncludeEngines("cucumber")
@SelectClasspathResource("features/web/e2e_sandbox.feature")
@ConfigurationParameter(key = PLUGIN_PROPERTY_NAME,
        value = "net.serenitybdd.cucumber.core.plugin.SerenityReporterParallel") // ⚠ 4.2.0 的 FQN 需在线核实（详见 §9）
@ConfigurationParameter(key = GLUE_PROPERTY_NAME,
        value = "com.hsbc.cmb.hk.dbb.automation.tests.glue")
@ConfigurationParameter(key = FILTER_TAGS_PROPERTY_NAME, value = "@e2e-sandbox")
public class CucumberE2ESandboxRunnerIT {
}
```

**4 个 runner 映射表**：

| Runner | features | glue | tags |
|---|---|---|---|
| `CucumberTestRunnerIT` | `features`（默认目录） | `tests.glue` + `framework.web.concurrent` | `@test1` |
| `CucumberE2ESandboxRunnerIT` | `features/web/e2e_sandbox.feature` | `tests.glue` | `@e2e-sandbox` |
| `CucumberParallelLogonRunnerIT` | `features/web/parallel_logon_dbb.feature` | `tests.glue` | `@parallel-logon` |
| `CucumberConcurrentLogonRunnerIT` | `features/web/concurrent_logon_dbb.feature` | `tests.glue` + `framework.web.concurrent` | `@concurrent-logon` |

> 原 `plugin` 中的 `pretty/html/json` 报告由 Serenity reporter（`SerenityReporterParallel`）统一接管，不再需要在 `@ConfigurationParameter` 中声明；`dryRun=false` 是默认值，省略。

**迁移脚本**：`tools/migrate_junit4_to_junit5.ps1` 会识别这 4 个文件并标记为 `CUCUMBER_RUNNER`，不直接重写类结构（避免破坏 package/import 语义），输出骨架提示；建议按上表**手工改 4 个 runner**（改动量极小，每文件约 10 行）。

---

## 7. 自动化迁移脚本

`tools/migrate_junit4_to_junit5.ps1`（已落地，UTF8 无 BOM 字节级替换，符合本机历史约束）：
- 默认 **DryRun** 预览；加 `-Apply` 落盘。
- 覆盖 §5 的 import / 注解 / 断言 / 假设映射。
- Cucumber runner 单独列出（见 §6 手工迁移）。
- 运行：`powershell -ExecutionPolicy Bypass -File tools/migrate_junit4_to_junit5.ps1` 预览；确认后加 `-Apply`。

---

## 8. 分步执行计划（在线环境落地）

1. **联网下载依赖**：去掉 `-o`，`mvn -pl test-automation -am dependency:resolve -DincludeScope=test` 拉取 `serenity-junit5` / `junit-jupiter-*` / `junit-platform-suite` / `cucumber-junit-platform-engine`。
2. **校准 junit-jupiter 版本**：`mvn dependency:tree -pl test-automation` 查看 `serenity-junit5:4.2.0` 传递的 `junit-jupiter` 版本，回填根 POM `junit.jupiter.version`（确保 `requireUpperBoundDeps` 通过）。
3. **迁移普通测试代码**：运行脚本 DryRun → 复核 → `-Apply`。
4. **迁移 4 个 Cucumber runner**：按 §6 手工改（每文件约 10 行）。
5. **编译验证**：`mvn -o -pl test-automation -am test-compile`（先编测试，暴露 import 残留）。
6. **全护盾**：`mvn -o -pl test-automation -am test`（普通单测）→ `mvn -o -pl test-automation -am verify -Dit.test=CucumberTestRunnerIT`（Cucumber IT，逐个 tag 验证并行/并发 runner）。
7. **其余模块**：`mvn -o -pl web,reporting,route -am test`。

---

## 9. 风险评估与缓解

| 风险 | 等级 | 缓解 |
|---|---|---|
| `SerenityReporterParallel` FQN：官方文档称 5.0.0 起由 `io.cucumber.core.plugin.*` 改为 `net.serenitybdd.cucumber.core.plugin.*`；**4.2.0 路径未知** | 高 | 在线 `mvn dependency:tree` / 解压 `serenity-cucumber:4.2.0` jar 确认实际 FQN，再回填 4 个 runner 的 `PLUGIN_PROPERTY_NAME` |
| `@BeforeAll`/`@AfterAll` 必须 `static`：原 `@BeforeClass` 方法若依赖实例字段 | 中 | 脚本不自动改可见性；迁移后编译期暴露，逐个改静态字段或 `@RegisterExtension` |
| `serenity-junit5:4.2.0` 与 `serenity-core:4.2.0` 版本须严格一致 | 中 | 根 POM 统一 `${serenity.version}`，勿分开钉 |
| `requireUpperBoundDeps` 失败（junit-jupiter 与 cucumber-junit-platform-engine 的 junit-platform 上界不一致） | 中 | 步骤 2 校准版本 |
| Cucumber 场景级并行语义变化：`CucumberWithSerenity`（JUnit4）下 `serenity.parallel.for.tests` 是历史空操作；JUnit5 + cucumber-junit-platform-engine 并行行为可能不同 | 中 | 先在 `@test1`/`@e2e-sandbox` 单 tag 验证，再开并行 runner；对比运行日志 |
| Serenity 场景生命周期（StepEventBus / scenario 清理）在 JUnit5 extension 下行为 | 中 | 全护盾 + 4 个 runner 逐一验证，关注 scenario 级清理/截图/报告 |
| 规则类 `org.junit.rules.*` 残留 | 低 | 脚本列出待办；搜索 `org.junit.rules` 手工替换 |

---

## 10. 在线验证命令

```powershell
# 1) 依赖解析（在线）
mvn -pl test-automation -am dependency:resolve -DincludeScope=test
# 2) 版本校准
mvn dependency:tree -pl test-automation | Select-String junit-jupiter
# 3) 测试编译（暴露 import 残留）
mvn -o -pl test-automation -am test-compile
# 4) 普通单测
mvn -o -pl test-automation -am test
# 5) Cucumber IT（逐个）
mvn -o -pl test-automation -am verify -Dit.test=CucumberTestRunnerIT
mvn -o -pl test-automation -am verify -Dit.test=CucumberE2ESandboxRunnerIT -Dtags=@e2e-sandbox
# 6) 其余模块
mvn -o -pl web,reporting,route -am test
```

---

## 11. 回滚方案

- 依赖层：git revert 根 POM + 4 模块 POM 的 JUnit5 改动即可回到 JUnit4。
- 代码层：迁移脚本为字节级替换，回滚 = `git checkout -- <test files>`（未 commit 前）；或基于脚本备份 `.bak`（如需）。
- 因当前离线未构建、未 commit，所有改动均可在在线验证失败前 `git checkout` 撤销。

---

## 12. 落地记录（2026-09-11 完成）

**结果**：全护盾 804 例（reporting 8 + web 194 + route 2 + test-automation 600）0 失败/0 错误/0 跳过，`mvn -o -pl test-automation -am test` BUILD SUCCESS，enforcer `RequireUpperBoundDeps` 全模块通过。

**关键落地项**：

1. **依赖层**（根 POM）：
   - `junit.jupiter.version=5.11.4`、`junit.platform.version=1.14.0`、`cucumber.version=7.31.0`、`serenity.version=4.2.0`。
   - **`junit-platform` 必须 ≥ 1.14.0**：`cucumber-junit-platform-engine:7.31.0`（junit-bom 5.14.0）的 `CucumberTestEngine.discover` 引用
     `org.junit.platform.engine.support.discovery.DiscoveryIssueReporter`（1.13+ 才引入）；若钉 1.11.4 则运行期
     `NoClassDefFoundError` → surefire 报 “TestEngine with ID 'cucumber' failed to discover tests”。
     故 `junit-platform-launcher` / `junit-platform-suite` / `junit-platform-engine` / `junit-platform-commons` 统一 1.14.0。

2. **4 个 Cucumber runner → `@Suite`**（`@IncludeEngines("cucumber")` + `@SelectClasspathResource` + `@ConfigurationParameter`）：
   - `CucumberTestRunnerIT`：`features` + glue `tests.glue,tests.api.steps`，tags `@test1`。
   - `CucumberE2ESandboxRunnerIT`：`features/web/e2e_sandbox.feature`，tags `@e2e-sandbox`。
   - `CucumberParallelLogonRunnerIT`：`features/web/parallel_logon_dbb.feature`，tags `@parallel-logon`。
   - `CucumberConcurrentLogonRunnerIT`：`features/web/concurrent_logon_dbb.feature`，glue 追加 `framework.web.concurrent`，tags `@concurrent-logon`。
   - `PLUGIN_PROPERTY_NAME = "io.cucumber.core.plugin.SerenityReporterParallel"`（Serenity 4.2.0 路径；5.0.0 起才迁 `net.serenitybdd.cucumber.core.plugin.*`）。

3. **断言参数序修正**：JUnit4 `assertX(message, ...)` → JUnit5 `assertX(..., message)`（message 置末位），
   由 `tools/fix_assert_order_v3.ps1`（括号/字符串感知解析器，非正则）对干净 JUnit4 源批量旋转；
   `-BoolOnly` 仅处理 `assertTrue/False/Null/NotNull`（首参不可能为字符串，绝对安全）。

4. **Cucumber glue 注解**：`@Before`/`@After` 是 `io.cucumber.java.*`（非 JUnit5 `@BeforeEach/@AfterEach`），
   迁移脚本会把二者误改，已按 `import io.cucumber.java.Before` 归类精准回退（7 个 glue 文件）。

5. **验证命令**：`mvn -o -pl test-automation -am test`（全护盾）；`verify` 阶段逐个跑 4 个 `@Suite` runner IT（需真实浏览器/DBB 环境）。

**教训**：批量正则迁移断言参数序对「含嵌套括号 / 逗号字符串 / 未加引号 message」会产出不可逆损坏；
必须用括号平衡解析器，且对含特殊语法的文件（`@Test(expected)/@Test(timeout)/@Rule`）与手改文件
（Cucumber runner / glue）逐一核对，不能只依赖脚本预览。

---

## 12. 验收标准

1. `mvn -o -pl test-automation -am test-compile` 全绿（0 个 `org.junit` 残留编译错误）。
2. 4 个 Cucumber runner 均能在 JUnit5 下跑通（`@Suite` + `cucumber-junit-platform-engine`），Serenity 报告正常生成。
3. 全护盾测试计数与迁移前基线（约 528）一致或经评审说明差异，0 失败/0 错误。
4. `requireUpperBoundDeps` + `banDuplicatePomDependencyVersions` 通过。
5. 全仓 `import org.junit.` 残留 = 0（`import org.junit.jupiter.` 接管）。
