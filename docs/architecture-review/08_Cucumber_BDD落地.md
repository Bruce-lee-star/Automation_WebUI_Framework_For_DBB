# 08 Cucumber BDD 落地：Runner、步骤、Hooks 与标签

> 评审范围：`test-automation/src/test/java/**`（155 java）、`src/test/resources/features/**`（16 feature）
> 评审基线：`e11a847`

---

## 一、模块职责

BDD 落地面：把 Gherkin 业务语义映射到框架能力，包含 4 个 Runner、步骤定义库、Hooks、feature 组织与标签策略。

---

## 二、现状评估

### 2.1 Runner 与标签（严重问题）

4 个 `@Suite` Runner（JUnit 5 迁移已完成，无 JUnit 4 残留）：

| Runner | 标签 |
|---|---|
| `CucumberTestRunnerIT` | `@test1`（`:12,17`） |
| `CucumberParallelLogonRunnerIT` | 并发登录（`:29`） |
| `CucumberE2ESandboxRunnerIT` | `@e2e-sandbox`（`:30`） |
| `CucumberConcurrentLogonRunnerIT` | 并发（`:49`） |

glue 固定为 `tests.glue,tests.api.steps`，features 用 `@SelectClasspathResource("features")`（`:14-16`）。

**问题 1：默认标签只跑 1 个场景。**
根 POM `<tags>@test1</tags>`（`pom.xml:19`），而**全仓只有 `login_dbb1.feature:12` 一个场景打了 `@test1`**。也就是说默认执行集 = 1 个场景。其余 15 个 feature 文件在默认配置下完全不执行。

**问题 2：标签配置双源冲突。**
Runner 里用 `@ConfigurationParameter` 硬编码 `@test1`（`:17`），failsafe 又通过 `systemPropertyVariables` 传 `-Dcucumber.filter.tags=${tags}`（`test-automation/pom.xml:195`）。两者谁生效取决于 Cucumber 的优先级规则，**在当前配置下是一处隐性地雷**——改 `-Dtags` 可能不生效，也可能覆盖，行为不确定。

**问题 3：文档与实现不一致。**
`api` 包的 `package-info.java:40` 写着 runner tags = `@0test`，与实际 `@test1` / `@api` 都不一致。

### 2.2 步骤定义

- **API 域**：11 个类，均 `extends UIInteractionSteps`（`ResponseStatusSteps` / `ResponseBodySteps` / `PayloadSteps` / `HeaderSteps` 等）——按"响应状态/头/体/请求/查询参数/路径参数/实体/配置"维度拆分，**粒度合理**。
- **Web / Route 域**：`RouteDemoServiceSteps`、`RouteDemoCoverageSteps`、`RouteDemoCompositeSteps`、`BaiduSteps`、`PickerSmokeSteps`、`LoginSteps`。

**问题 4：存在两个同名 `LoginSteps`。**
`tests/steps/LoginSteps.java:52` 与 `tests/LoginSteps.java:24`——分属不同包但**同名同类**。glue 里两个包是否都在扫描路径内需确认；若都在，Cucumber 会因"重复步骤定义"抛 `DuplicateStepDefinitionException`；若只有一个在，另一个是死代码。无论哪种情况都是隐患。

**问题 5：步骤里内联 JUnit 断言。**
`RouteDemoServiceSteps.java:20-24` 引入 `assertEquals/assertTrue`，并在 `:184-466` 大量内联断言（如 `:185 assertEquals(200, ...)`、`:506 assertTrue(elapsed >= 700, ...)`）。

这带来两个后果：
1. 步骤定义与断言混杂，业务语义被技术细节淹没；
2. `:506 assertTrue(elapsed >= 700, ...)` 这类**耗时下界断言**是典型的 flaky 源——在 CI 慢机器上容易过，在快机器上容易挂（或反之）。

**问题 6：步骤里出现 `Thread.sleep`。**
`RouteDemoServiceSteps.java:147`、`RouteDemoCoverageSteps.java:116`、`RouteDemoCompositeSteps.java:93`。框架层已提供 `PageWaits` 与 Playwright 自动等待，步骤层却回退到硬等待——**说明等待封装在易用性上有缺口，使用者选择了更"快"的写法**。

### 2.3 Hooks

| Hook | 位置 | 作用 |
|---|---|---|
| `@Before` / `@After` 全局 | `ApiScenarioHooks.java:19,24` | 对所有场景生效 |
| `@After` | `LogonGlue.java:88` | 释放并发信号量 |
| `@Before(order=0)` | `ProfilePreScanHook.java:66` | 配置预扫描 |
| `@Before` / `@After` | `RouteDemoCompositeGlue.java:30,36` | `resetDemoData()` / `cleanup()` |
| `@Before` / `@After` | `RouteDemoCoverageGlue.java:28,33` | 预清理 / 清理 |
| `@After` | `RouteDemoServiceGlue.java:23` | 清理 |

**正面**：没有用 `@BeforeAll/@AfterAll` 启停浏览器（由 `@AutoBrowser` 注解托管），且**每个 route demo glue 都做了 `@Before` 重置 + `@After` 清理**，说明团队意识到了用例隔离。

**问题 7：这些隔离清理是"每个 glue 各写一遍"的**，而不是框架统一提供。新增一个 glue 时如果忘记写 `@Before resetDemoData()`，就会踩到上一个用例留下的脏数据。**隔离依赖复制粘贴，而非机制保证。**

### 2.4 Feature 组织

```
features/
├── api/route_demo_api_restassured.feature      @api
├── web/*.feature                                @route / @e2e-sandbox / @test1 ...
└── role_element_*.feature（根目录 3 个）
```

标签体系：`@route`、`@api`、`@e2e-sandbox`、`@route-composite`、`@test1`。

**问题 8：标签命名不成体系**。`@test1` 是无语义标签（"测试1"是什么？），`@route-composite` 用连字符而其它用单词，`@0test` 出现在文档里但代码中不存在。缺少 `@smoke` / `@regression` / `@p0` 这类**执行编排语义**标签。

---

## 三、优势

1. **JUnit 5 迁移干净**：4 个 Runner 全部 `@Suite`，无 `@RunWith(CucumberWithSerenity)` 残留。
2. **API 步骤按响应维度拆分**（状态/头/体/参数），粒度合理、复用性好。
3. **route demo 的 glue 成对做了 `@Before` 重置 + `@After` 清理**，隔离意识到位。
4. **`@AutoBrowser` 注解托管浏览器**，用例侧零样板代码。
5. **步骤复用 `UIInteractionSteps`**，与 Serenity Screenplay 生态对齐。

---

## 四、风险与问题

| 编号 | 级别 | 问题 | 证据 | 影响 |
|---|---|---|---|---|
| B-1 | **P0** | 默认标签 `@test1` 只覆盖 **1 个场景**，其余 15 个 feature 默认不执行 | 根 `pom.xml:19`、`login_dbb1.feature:12` | 回归套件名存实亡 |
| B-2 | **P0** | 标签双源配置冲突（Runner `@ConfigurationParameter` vs failsafe `-Dcucumber.filter.tags`） | `CucumberTestRunnerIT:17` vs `pom.xml:195` | `-Dtags` 行为不确定，CI 编排不可靠 |
| B-3 | **P1** | 两个同名 `LoginSteps`（`tests` 与 `tests.steps` 包） **已修复**：`tests.LoginSteps` → `tests.RoleElementLoginSteps`（更新 `RoleElementLoginGlue` 引用、删旧类），消除同名同类隐患，行为零变更；**未强行合并**（两者实现不同、各被不同 Glue 引用，合并会改变 RoleElement 场景登录行为）| `tests/steps/LoginSteps.java:52`、`tests/LoginSteps.java:24` | 重复步骤定义风险或死代码 |
| B-4 | **P1** | 步骤内 `Thread.sleep` **已修复**：新增 `tests.utils.AsyncWaits`（有界轮询：`awaitTrue`/`awaitResult`，超时上界 + 固定间隔 + 中断安全），`RouteDemoServiceSteps`/`RouteDemoCoverageSteps`/`RouteDemoCompositeSteps`/`RouteDemoParallelSmokeSteps` 4 个步骤类全部改用它；ArchUnit `LayeringArchTest#stepsMustNotCallThreadSleep` 禁 `*Steps` 调 `Thread.sleep` | `RouteDemoServiceSteps:147`、`RouteDemoCoverageSteps:116`、`RouteDemoCompositeSteps:93` | flaky + 执行变慢 |
| B-5 | **P1** | 步骤内联断言 + 耗时下界断言 **已修复**：① 移除 `RouteDemoServiceSteps` 的 flaky 计时下界，改「观测日志 + 上界 SLA」（delay 能力由 `RouteDemoCompositeSteps` 的 DELAY 标记断言确定性覆盖）；② 新增 `tests.verify.RouteDemoVerifications` 步骤层断言收口，`RouteDemo*Steps` 不再直连 `org.junit.jupiter.api.Assertions`；③ ArchUnit `LayeringArchTest#stepsMustNotUseJunitAssertionsDirectly`（负向探针验证）| `RouteDemoServiceSteps:185,506` | 业务语义被淹没；`elapsed>=700` 必 flaky |
| B-6 | **P1** | 用例隔离靠**每个 glue 手写 `@Before` 重置**，非框架机制 **已修复**：core 新增 `StateResolver`/`CleanStateRegistry`/`@RequiresCleanState`/`StateResidueException`；test-automation 新增 `CleanStateHooks`（`@Before` 统一复位 + `@After` 复位并**断言无残留**，残留即抛异常当场失败）；4 个 `RouteDemo*Glue` 移除手写 `@Before`/`@After`，改注册 `StateResolver`；`CleanStateRegistryTest` 6 例 | 各 `RouteDemo*Glue` | 新 glue 漏写即脏数据 |
| B-7 | **P2** | 标签命名不成体系（`@test1` / `@route-composite` / 文档里的 `@0test`） **已修复**：建立三维标签体系（执行层/子系统/域/状态）并落为门禁 `FeatureTagTaxonomyTest`（未注册标签或 feature 级缺维度即失败，负向探针验证）；17 个 feature 补齐 feature 级标签，删除无语义 `@test1`/`@test`、`@baidu1`→`@baidu` | — | 无法做执行编排 |
| B-8 | **P2** | 文档与实现不一致（`package-info.java:40` 称 `@0test`） | `api/package-info.java:40` | 误导维护者 |

---

## 五、优化方案

### 5.1 消灭标签双源，建立单一事实来源（P0）

**原则：Runner 里不写死标签，全部由外部属性驱动。**

```java
@Suite
@IncludeEngines("cucumber")
@SelectClasspathResource("features")
@ConfigurationParameter(key = GLUE_PROPERTY_NAME, value = "tests.glue,tests.api.steps")
@ConfigurationParameter(key = PLUGIN_PROPERTY_NAME,
    value = "io.cucumber.core.plugin.SerenityReporterParallel,json:target/cucumber.json")
public class CucumberTestRunnerIT {
    // 不再用 @ConfigurationParameter 写死 filter.tags
}
```

```xml
<!-- test-automation/pom.xml：唯一来源 -->
<systemPropertyVariables>
  <cucumber.filter.tags>${tags}</cucumber.filter.tags>
</systemPropertyVariables>
```

```xml
<!-- 根 pom.xml：默认改为有执行语义的标签 -->
<tags>not @skip</tags>
```

并在 Runner 上加一条启动自检，防止再次写死：

```java
@BeforeSuite
static void assertNoHardcodedTags() {
    if (System.getProperty("cucumber.filter.tags") == null) {
        throw new IllegalStateException(
            "未设置 cucumber.filter.tags。请通过 -Dtags= 指定，禁止在 Runner 里硬编码标签。");
    }
}
```

### 5.2 重建标签体系与执行分层（P0）

```gherkin
# 分层：执行频率 + 子系统 + 稳定性
@smoke @web @login
Scenario: 用户登录成功

@regression @api @route
Scenario: 接口监控捕获异常状态码

@flaky @skip           # 已知不稳定，暂时排除
Scenario: 待修复的用例
```

配套执行档位（根 POM 用 profile 表达）：

```xml
<profiles>
  <profile><id>smoke</id><properties><tags>@smoke and not @skip</tags></properties></profile>
  <profile><id>regression</id><properties><tags>@regression and not @skip</tags></properties></profile>
  <profile><id>all</id><properties><tags>not @skip</tags></properties></profile>
</profiles>
```

> **落地记录（B-7，2026-09-17）**：三维标签体系已落地，并由门禁**可执行校验**。
> - **注册表**：执行层 `@smoke`/`@regression`；子系统 `@web`/`@api`/`@route`；域 `@login`/`@baidu`/`@scan`/`@scan-record`/`@picker`/`@e2e-sandbox`/`@parallel-logon`/`@concurrent-logon`/`@route-composite`/`@route-coverage`/`@route-capability-stop`/`@route-parallel-smoke`/`@delay`/`@click`/`@hover`/`@type`/`@check`/`@select`/`@popup`/`@download`/`@dialog`；状态 `@skip`/`@flaky`。
> - **落地范围**：17 个 feature 全部在 **feature 级**声明「1 个执行层 + 1 个子系统」标签（scenario 级可自由叠加域标签，Cucumber 自动继承）；删除无语义 `@test1`/`@test`，`@baidu1` → `@baidu`。
> - **编排示例**：`-Dtags="@smoke and not @skip"`（冒烟档）、`-Dtags="@api"`（接口层）、`-Dtags="@regression and @route-coverage"`（能力域）。
> - **门禁**：`FeatureTagTaxonomyTest` —— ① 任何标签必须在注册表内；② 每个 feature 恰好 1 个执行层 + 1 个子系统标签。两条规则均经**负向探针**验证可命中（临时植入未注册标签 / 移除子系统标签 → 双双失败）。标签行识别要求「整行皆为标签」，故描述文本与注释里的 `@Xxx`（如 `@RoleElement`、`-Dtags=@route`）不会被误判。
> - **兼容性**：不改名 Runner 已依赖的标签（`@route-parallel-smoke`/`@parallel-logon`/`@concurrent-logon`/`@e2e-sandbox`），既有过滤行为零变更；新增标签为纯叠加，默认 `not @skip` 执行集不变。

CI 编排：`PR → smoke`，`nightly → regression`。

### 5.3 合并同名步骤类（P1）

```bash
# 用 IDE 或脚本合并 tests.LoginSteps 与 tests.steps.LoginSteps
# 统一为 tests.steps.LoginSteps，删除另一个
```

并加一条 **Cucumber 重复步骤检查**：在 `mvn test` 阶段跑一次 `--dry-run`，任何 `DuplicateStepDefinitionException` 直接失败。

### 5.4 用 ArchUnit 禁止步骤层硬等待与内联断言（P1）

```java
@ArchTest
static final ArchRule noThreadSleepInSteps =
    noClasses().that().resideInAPackage("..tests..")
        .should().callMethod(Thread.class, "sleep", long.class)
        .because("步骤层禁止硬等待，请使用 Playwright 自动等待或框架 PageWaits");

@ArchTest
static final ArchRule noJunitAssertInSteps =
    noClasses().that().resideInAPackage("..tests..")
        .and().haveSimpleNameEndingWith("Steps")
        .should().dependOnClassesThat().haveFullyQualifiedName("org.junit.jupiter.api.Assertions")
        .because("断言应下沉到 Page/Service 层，步骤层只做业务语义编排");
```

> **落地记录（B-4，2026-09-17）**：`Thread.sleep` 规则已实现于 `LayeringArchTest#stepsMustNotCallThreadSleep`，
> 采用更精确的可达谓词 `resideInAPackage("..automation.tests..").and(simpleNameEndingWith("Steps"))`——
> 仅禁**步骤类**硬等待，允许受控等待集中在 `tests.utils.AsyncWaits`（与主代码规则
> `frameworkCodeMustNotCallThreadSleep` 的「测试代码可保留受控等待」原则一致）。
> 注意：`callMethod(Thread.class,"sleep")` 省略参数类型会退化为匹配无参 `sleep()`（不存在）而**空转**，
> 必须写全 `sleep(long)`；该 bug 亦存在于既有主代码规则，已一并修正（修正后暴露 `AbstractRestJob` 退避
> 使用 `Thread.sleep`，已改用 `LockSupport.parkNanos`）。

### 5.5 断言下沉与耗时断言改造（P1）

```java
// 反例（现状 RouteDemoServiceSteps:506）
assertTrue(elapsed >= 700, "响应耗时应 >= 700ms");

// 改法 1：性能断言只作为"观测指标"记录，不作为通过条件
Metrics.record("api.latency", endpoint, elapsed);
// 如需断言，只断言上界（SLA），不断言下界
assertThat(elapsed).isLessThan(SLA_MAX_MS);

// 改法 2：断言下沉到 Service 层，步骤层只做语义表达
@Then("接口 {string} 应在 {int} 毫秒内响应")
public void thenApiRespondsWithin(String endpoint, int maxMs) {
    apiVerifier.verifyLatencyWithin(endpoint, maxMs);   // 断言在 Verifier 内
}
```

### 5.6 隔离机制化（P1）

把"每个 glue 手写 `@Before` 重置"改为**框架统一钩子 + 注解声明**：

```java
@Target(ElementType.TYPE)
@Retention(RUNTIME)
public @interface RequiresCleanState {
    String value();   // 如 "demo-users" / "route-rules"
}
```

```java
public class FrameworkHooks {

    @Before
    public void resetDeclaredState(Scenario scenario) {
        // 从 glue 类上读注解，或从 feature 标签推导
        stateResolvers.forEach(r -> r.reset());
    }

    @After
    public void assertNoResidue(Scenario scenario) {
        // 关键：断言式清理 —— 用例结束后若仍有残留，直接失败
        List<String> residue = stateResolvers.stream()
            .filter(StateResolver::isDirty).map(StateResolver::name).toList();
        if (!residue.isEmpty()) {
            throw new FrameworkException("用例结束后状态残留: " + residue);
        }
    }
}
```

**"断言式清理"是关键**——它把"忘记清理"从"以后某个用例莫名失败"变成"当场失败并指明原因"。

---

## 六、结论

BDD 这一层的**迁移质量不错**（JUnit 5 干净、API 步骤拆分合理、有 `@AutoBrowser` 消除样板），但**执行入口是断的**：默认标签只跑 1 个场景（B-1），且标签双源配置让 `-Dtags` 行为不确定（B-2）。这意味着无论框架能力多强，**CI 上实际跑的东西可能远少于预期**。

这两项修复成本都在半小时以内，却直接决定"这套自动化到底覆盖了多少"。同时建议把隔离从"复制粘贴"升级为"机制 + 断言式校验"（B-6），这是让套件能长期健康扩张的前提。
