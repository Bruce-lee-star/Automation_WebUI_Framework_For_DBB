# 模块评审 07｜`test-automation` 与工程化体系

> 覆盖范围：业务测试工程组织 + 构建体系 + 依赖治理 + CI/CD + 质量门禁 + 仓库治理
> **综合总评：1.8 / 5.0 —— 全项目最大短板，企业级准入的一票否决项**

---

## 第一部分：`test-automation` 模块

### 1.1 规模与组织

- **115 个 Java 文件**，其中 **51 个是框架自测类**、64 个业务用例类、1 个根 Runner
- **32 个 `.feature` 文件**、**4 个 Cucumber Runner**
- 资源：`payload/`（4 个 API 报文）、`mocks/`、`nls/`（2 个语言包）、`scan/`（4 个 HTML 测试页）、`config/`（3 个配置文件）

### 1.2 问题分析

**问题 TA-1（P1/严重）：框架自测与业务用例混放**

```
test-automation/src/test/java/
├── com/hsbc/.../framework/     ← 51 个框架自测（应为各框架模块的 src/test）
│   ├── common/config/ConfigSourceTest.java
│   ├── web/lifecycle/*Test.java
│   ├── web/route/core/*Test.java        ← route 模块的测试
│   └── web/page/scan/RolePicker*Test.java  ← codegen 模块的测试
└── com/hsbc/.../tests/          ← 64 个业务用例
```

后果：
1. **框架模块自身 `src/test` 为空** → 各模块无法独立验证，CI 无法做模块级门禁；
2. 修改 `route` 模块后，必须跑整个 `test-automation` 才能验证——**反馈周期被无谓拉长**；
3. 业务用例与框架自测共享同一套 Runner 与配置（如 `tags=@test1`），互相干扰；
4. 新人无法从目录结构判断"这是框架代码还是业务代码"。

**问题 TA-2（P1/中）：Runner 碎片化，默认标签可疑**

4 个 Runner：`CucumberTestRunnerIT`（根包） + `CucumberConcurrentLogonRunnerIT` / `CucumberE2ESandboxRunnerIT` / `CucumberParallelLogonRunnerIT`（tests/web 包）。

根 POM 默认 `<tags>@route</tags>`，`test-automation/pom.xml:19` 覆盖为 `<tags>@test1</tags>`。`@test1` **高度疑似临时调试标签被固化为默认值**——这会导致"CI 跑的用例集"与"实际需要跑的用例集"长期偏离而不自知。

**问题 TA-3（P1/中）：无并行执行配置**

`maven-failsafe-plugin` 配置 `forkCount=1`，未配置 `parallel` / `threadCount` / `useUnlimitedThreads`。而框架本身投入了大量精力建设并发能力（`ConcurrencyGate` / `ConcurrentScenarioExecutor` / `ConcurrentContextExecutor` / `PerContextEngine`）——**能力建好了，但默认执行方式没有用上**。

**问题 TA-4（P2/中）：测试数据管理分散**
`payload/`（API 报文）、`mocks/`、feature 内 `Examples` 表格三处并存，无统一数据工厂或数据集版本管理。多环境（dev/uat）数据差异靠文件切换。

**问题 TA-5（P2/轻）：重试策略默认关闭**
`rerunFailingTestsCount=0`（根 POM），CI 可覆盖。**方向正确**——默认不重试可避免掩盖 flaky。但缺少 flaky 检测与标记机制（重试用例应在报告中标注）。

---

## 第二部分：依赖治理

### 2.1 致命问题：父 POM 全量继承

**证据链：**

```xml
<!-- 根 pom.xml（ENG-P0-2 整改后：2026-09-08） -->
<!-- 全部外部依赖已移入 <dependencyManagement> 统一钉版本；各模块在自身 <dependencies> 显式声明实际用到的依赖 -->

<!-- 根 pom.xml：dependencyManagement 内含全部外部依赖（原 22 项 + 7 项传递版本覆盖） -->
<dependencyManagement>
    <dependencies>
        jieba-analysis, pinyin4j,
        serenity-model, serenity-core, serenity-cucumber, serenity-junit,
        serenity-screenplay, serenity-rest-assured,
        commons-logging, commons-lang3, logback-classic, json-path, org.json,
        selenium-support, playwright, junit, archunit, hamcrest-core,
        typesafe-config, gson, axe-core(playwright), HikariCP
    </dependencies>
</dependencyManagement>
```

```xml
<!-- core/pom.xml（ENG-P0-2 整改后）—— 仅显式声明 2 个实际依赖：HikariCP + logback-classic -->
```

**实证后果**：`core/.../ConfigSource.java:4` 直接 import `net.thucydides.model.environment.SystemEnvironmentVariables` 并编译通过——最底层模块强耦合 Serenity。

### 2.2 影响评估

| 影响面 | 说明 |
|---|---|
| **模块边界失效** | 任何模块可误用任何依赖，编译器与 ArchUnit 均不拦截（ArchUnit 只管包，不管 classpath） |
| **无法依赖收敛** | 无法回答"route 模块真正需要哪些依赖" |
| **CVE 面放大** | 所有模块暴露全部依赖的漏洞面（这也是 osv-scanner 冻结 96 条的部分原因） |
| **制品膨胀** | 每个模块制品携带全量依赖 |
| **复用性归零** | `framework-core` 无法被非 Serenity 项目复用 |

### 2.3 相对亮点

- ✅ 使用 `dependencyManagement` 钉定 jackson / json-smart / slf4j / cucumber-core 等版本（根 POM 271-313 行）
- ✅ `maven-enforcer-plugin` 配置 `requireUpperBoundDeps` + `banDuplicatePomDependencyVersions` + `requireJavaVersion[21,)` + `requireMavenVersion 3.6.0`（根 POM 407-431 行）——**这是正确的依赖治理工具**
- ✅ 对 selenium-support 版本对齐、commons-logging 补回等有详细注释说明

**但**：Enforcer 管的是"版本不回退"，管不了"不该有的依赖"。**工具用对了，问题却在工具管辖范围之外。**

---

## 第三部分：CI/CD —— **一票否决项**

### 3.1 现状

```bash
.github/workflows/
├── doc-drift-check.yml        # 仅比对 README 与 POM 版本号
└── serenity-report-push.yml   # 手动触发，仅部署报告
```

**没有任何 workflow 执行 `mvn verify` 或 `mvn test`。**

### 3.2 连锁后果（这是本评审最重要的发现）

根 POM 中配置了**四道门禁**，全部绑定 `verify` 阶段：

| 门禁 | 插件 | 绑定阶段 | CI 中是否执行 |
|---|---|---|:--:|
| Checkstyle | maven-checkstyle-plugin | verify | ❌ 从不 |
| SpotBugs | spotbugs-maven-plugin | verify | ❌ 从不 |
| OSV CVE | exec-maven-plugin → cve-gate.ps1 | verify | ❌ 从不 |
| JaCoCo | jacoco-maven-plugin | （仅 report，无 check） | ❌ 从不 |

**结论：所有质量门禁都是"君子协定"——只在开发者本地手动执行 `mvn verify` 时才可能生效，且不执行也没有任何后果。**

这直接解释了为什么：
- `web` 模块 22,718 行代码可以只有 3 个测试而不被发现；
- `osv-scanner.toml` 可以冻结 96 条漏洞（含 8 CRITICAL）而无人质疑；
- codegen 的覆盖写问题可以一直存在。

**没有 CI，就没有质量约束——只有质量意愿。**

### 3.3 缺失的 CI 能力清单

| 能力 | 状态 |
|---|:--:|
| 触发（push / PR） | ❌ |
| 编译 + 单元测试 | ❌ |
| 集成测试 | ❌ |
| 静态检查门禁 | ❌ |
| 覆盖率门禁 | ❌ |
| CVE 门禁 | ❌ |
| 依赖缓存 | ❌ |
| 构建产物归档 | ❌ |
| 测试报告归档 / 发布 | ⚠️ 仅有手动触发的报告部署 |
| 并发控制（concurrency group） | ❌ |
| 失败通知 | ❌ |
| 状态检查（required status check） | ❌ |

---

## 第四部分：质量门禁有效性

### 4.1 Checkstyle —— 形同虚设

**规则集（`checkstyle.xml`）仅 3 条：**

| 规则 | 配置 | 实际效果 |
|---|---|---|
| `FileLength` | max=1000 | 仅约束文件行数 |
| `EmptyCatchBlock` | `commentFormat=".*"` | **任何带注释的空 catch 均合法** → 规则失效 |
| `UnusedImports` | — | 有效，但 IDE 自动完成 |

**作用域（根 POM:390）：**
```xml
<includes>**/framework/web/page/base/**,**/framework/web/page/ElementDiagnosticsCollector.java</includes>
```
仅覆盖 `web/page/base/` 包 + 1 个类。**codegen(21) / route(51) / api(30) / reporting(2) / core(19) 全部 123 个类不在检查范围。**

**雪上加霜**：`checkstyle-suppressions.xml` 豁免了 `BasePage` 的 FileLength——而 `BasePage`（1429 行）正是该规则最该约束的对象。

**净效果**：Checkstyle 实际只检查"除 BasePage 外的 `web/page/base` 包是否超过 1000 行、是否有未使用导入"。

### 4.2 SpotBugs —— 配置到位但基线为空

- `effort=Max` + `threshold=Medium` + `failOnError=true` + `includeTests=true`（根 POM 446-453 行）配置正确；
- **但根 `spotbugs-exclude.xml` 内容为空**（仅注释），仅 `test-automation/spotbugs-exclude.xml` 有 10 条抑制；
- 注释自陈"先产出存量基线，用 excludeFilter 冻结已知项……基线收敛后再绑定 verify 形成硬门禁"——**但 `check` 目标已经绑定 verify 了**（根 POM 455-463 行），与注释矛盾。

**风险**：空的排除文件 + 已绑定 verify = 一旦有人在本地跑 `mvn verify`，SpotBugs 会因存量问题直接失败。这很可能是**没人敢跑 `mvn verify`** 的原因之一。

### 4.3 JaCoCo —— 只出报告，无门禁

`test-automation/pom.xml:149-169` 仅配置 `report` 目标，注释自陈"check 待 T1-9 补充……此处仅出报告"。

**覆盖率不阻断构建** = 覆盖率只是个数字，不是约束。

### 4.4 CVE 门禁 —— 基线冻结 96 条，含 45 条高危

```
osv-scanner.toml: 96 [[IgnoredVulns]]
  8  CRITICAL
 37  HIGH
 36  MODERATE
 14  LOW
```

文件头部注释声明 **"never mask Critical/High"**，但**实际冻结了 8 CRITICAL + 37 HIGH = 45 条高危**。

**这不是"基线"，这是"免罪清单"。** 机制本身（新增漏洞阻断）是有效的，但当基线覆盖了 45 条高危漏洞时，"新增漏洞阻断"只剩下理论意义——因为**基线里的漏洞永远不会被修复**。

### 4.5 ArchUnit —— 有效但有盲区

**有效部分（7 条规则）**：L1(api↛web)、L2(common↛web)、L3(page↛route)、C1(route.core↛route.handler)、L4(common↛api)、L5(route↛page)、G1(切片无环)、+ API 边界门禁（禁止业务代码调用 `BasePage.by*`）。

**盲区**：
```java
slices().matching("..framework.(*)..")   // 按 framework 下第一层切片
```
route 的类原在 `framework.web.route`，被归入 `web` 切片内部 → **`route → web` 的反向依赖不被检出**（ROUTE-P1-1 已修复：route 不再依赖 web，并新增 ArchUnit L6 固化该约束）。

**另一个问题**：`ArchitectureTest` 位于 `test-automation` 模块（业务层），**框架模块自身的架构约束应由框架侧或独立 `architecture` 模块持有**。当前位置意味着：单独构建 `framework-route` 时，架构门禁不生效。

---

## 第五部分：仓库治理

### 5.1 亮点 ✅

- `.gitignore` 完善：`*.log`、`osv-report.json`、`target/` 均已忽略
- **已验证**：`git ls-files` 共 448 个跟踪文件，**无大二进制或日志文件入库**（`.git` 仅 12MB）
- 根目录的 `build_test.log`(39KB)、`compile_errors.log`(77KB)、`concurrent_logon_run.log`(3.2MB)、`osv-report.json`(8.6MB) 均为**本地产物，未入库**

> 评审澄清：初看根目录堆满日志像是严重仓库污染，实际核查后确认已被 `.gitignore` 正确覆盖。**这一项应当肯定。**

### 5.2 问题

**问题 ENG-1（P1）：文档膨胀与漂移**

根目录 12 个 `.md`：
```
README.md(57KB)  API_README.md(66KB)  Element.MD(28KB)
ENTERPRISE_ARCHITECTURE_TASKS.md(24KB)  ROUTE_FRAMEWORK_GUIDE.md(26KB)
ROUTE_SCOPE_AND_PRIORITY.md(18KB)  CONCURRENT_CONTEXT_EXECUTOR_README.md(21KB)
PLAYWRIGHT_LISTENERS.md(18KB)  PLAYWRIGHT_VS_SELENIUM.md(33KB)
API_MONITOR_DESIGN.md  ELEMENT_PICKER_AND_STEPS_USAGE.md
NEXUS_PUBLISH_GUIDE.md  CONTRIBUTING.md
```
- 总计约 320KB 文档，无目录、无索引、无所有权；
- **唯一的自动化校验是 `doc-drift-check.yml`**（仅比对 README 与 POM 的版本号）；
- `architecture/` 目录有 6 个 md + 3 个 html，与根目录文档职责重叠。

**问题 ENG-2（P1）：部分文档被 gitignore，团队 pull 不到**

`.gitignore:62-64` 忽略了：
- `ENTERPRISE_ARCHITECTURE_TASKS.md`（24KB，**整改任务清单**）
- `ROUTE_FRAMEWORK_GUIDE.md`（26KB）
- `ROUTE_SCOPE_AND_PRIORITY.md`（18KB）

这些文件**在工作区存在但不在仓库中**——新成员 clone 后看不到，团队知识不同步。

**问题 ENG-3（P2）：备份文件残留**
`reporting/src/main/java/.../SummaryReportGenerator.java.broken.bak`（28KB）位于 main 源码树。

**问题 ENG-4（P2）：版本常量硬编码**
`FrameworkCore.java:29`：`public static final String FRAMEWORK_VERSION = "1.0.0-FINANCIAL-GRADE";`
与 POM 的 `<version>1.0.0</version>` 重复维护，必然漂移。且 "FINANCIAL-GRADE" 这类**自评性营销词汇**出现在生产代码中，在企业评审中通常是减分项——**等级应由评审方认定，而非代码自封**。

**问题 ENG-5（P2）：孤儿目录**
- `route-demo-service/` / `route-demo-web/`：不在父 POM `<modules>` 中，游离于构建之外
- `src/`（空）、`architecture/`（仅文档）、`tools/`（脚本）

---

## 第六部分：问题清单

| ID | 级别 | 问题 | 证据 |
|---|---|---|---|
| ENG-CI | **P0** | **无任何 CI 执行 build/test，四道门禁从不触发** | `.github/workflows/` 仅 2 个非构建 workflow |
| ENG-DEP | **P0** | 父 POM `<dependencies>` 全量继承，模块边界失效 | 根 POM:82-269（已整改）；`core/pom.xml` 现显式声明 HikariCP+logback-classic | ✅ 已修复：依赖全入 `dependencyManagement`，各模块显式声明，模块边界在 classpath 层面真实存在 |
| ENG-CVE | **P1** | CVE 基线冻结 96 条（8 CRITICAL + 37 HIGH），自相矛盾 | `osv-scanner.toml` |
| ENG-CS | **P1** | Checkstyle 3 规则 + 限 2 包 + 空 catch 可豁免 + 豁免 BasePage | `checkstyle.xml`；根 POM:390 |
| ENG-JC | **P1** | JaCoCo 无阈值门禁 | `test-automation/pom.xml:149-169` |
| TA-1 | **P1** | 51 个框架自测混在业务模块，框架模块 src/test 为空 | 目录结构 |
| TA-2 | **P1** | 默认 tags=@test1（疑似临时标签固化）；4 个 Runner 碎片化 | `test-automation/pom.xml:19` |
| ENG-1 | **P1** | 12 个 md 文档膨胀，无索引无所有权，仅版本号校验 | 根目录 |
| ENG-2 | **P1** | 3 个重要文档被 gitignore，团队 pull 不到 | `.gitignore:62-64` |
| ENG-AR | **P1** | ArchUnit 切片盲区，无法检出 route→web；且位于业务模块 | `ArchitectureTest.java:106` |
| TA-3 | **P2** | 无并行执行配置，框架并发能力未被默认启用 | failsafe `forkCount=1` |
| ENG-SB | **P2** | SpotBugs 排除文件为空但已绑定 verify（注释与配置矛盾） | 根 POM:446-463 |
| ENG-4 | **P2** | 版本常量硬编码 + "FINANCIAL-GRADE" 自评词汇 | `FrameworkCore.java:29` |
| ENG-3 | **P2** | `.broken.bak` 残留 main 源码树 | reporting 目录 |
| ENG-5 | **P2** | 孤儿模块/目录（route-demo-*、src/、architecture/） | 目录结构 |
| TA-4 | **P2** | 测试数据管理分散，无统一数据工厂 | resources 结构 |

---

## 第七部分：整改任务列表（工程化）

### P0 —— 阻断级（**必须先做，否则所有其他门禁都不成立**）

| 任务 ID | 任务 | 验收标准 | 工时 |
|---|---|---|---:|
| **ENG-P0-1** | **建立 CI 主流水线**（`.github/workflows/build.yml`）：push/PR 触发 → JDK21 + Maven 缓存 → `mvn -B verify` → 归档 surefire/failsafe 报告、JaCoCo 报告、Serenity 报告 → 失败通知；配置 concurrency group 与 required status check | ① PR 自动跑构建；② 构建失败阻止合并；③ 报告可下载；④ 有缓存，二次构建 < 5 分钟 | 3d |
| **ENG-P0-2** ✅ | **依赖下沉**：根 POM `<dependencies>` 全部移入 `<dependencyManagement>`；各模块按实际需要显式声明依赖（2026-09-08 已完成：core 仅 HikariCP+logback-classic 2 个；Enforcer 通过；全护盾绿）；`core` 去除 Serenity/Playwright/axe 属 CORE-P0-1 接口下沉（独立待办） | ① `mvn dependency:tree` 各模块仅含必需依赖；② 各模块编译通过；③ `framework-core` 依赖数 ≤ 5（实际 2）；④ Enforcer 通过 | 8d |

### P1 —— 重要

| 任务 ID | 任务 | 验收标准 | 工时 |
|---|---|---|---:|
| **ENG-P1-1** | **CVE 基线治理**：审计 96 条冻结项，制定分级清理计划——**8 CRITICAL 限期全部修复或给出可接受理由并升级审批**；37 HIGH 按季度清理；基线文件增加"到期日"字段，超期未清理阻断构建 | ① CRITICAL 清零或有书面豁免；② 基线项含到期日；③ 新增漏洞仍阻断 | 10d |
| **ENG-P1-2** | **Checkstyle 规则扩充与全量覆盖**：规则集补充至 ≥ 20 条（MagicNumber、CyclomaticComplexity、MethodLength、ClassFanOutComplexity、ParameterNumber、IllegalCatch、MissingSwitchDefault 等）；`<includes>` 改为全量源码；`EmptyCatchBlock` 的 `commentFormat` 收紧为指定格式（如 `IGNORE:`）；移除 BasePage 豁免或提供整改计划 | ① 全量源码纳入检查；② 规则 ≥ 20 条；③ 存量违规要么修复要么进 suppressions 并带整改计划 | 5d |
| **ENG-P1-3** | **JaCoCo 阈值门禁**：配置 `check` 目标绑定 verify，按模块设定阈值（建议首期：core/reporting ≥ 60%，route ≥ 40%，web/api ≥ 20%），后续逐季提升 | ① 覆盖率低于阈值构建失败；② 阈值写入 POM 并有注释说明提升计划 | 2d |
| **ENG-P1-4** | **拆分 test-automation**：51 个框架自测按归属迁回 `core/src/test`、`web/src/test`、`api/src/test`、`route/src/test`、`codegen/src/test`；`test-automation` 仅保留业务用例 | ① 各框架模块 `src/test` 非空；② 可 `mvn -pl framework-route test` 独立验证；③ 业务用例不受影响 | 5d |
| **ENG-P1-5** | **ArchUnit 补盲区 + 迁移**：① 新增"route 不得依赖 web"显式规则；② slices 模式改为显式列举各模块包前缀；③ 将 `ArchitectureTest` 迁至框架侧（新建 `framework-architecture` 模块或置于 core） | ① 故意引入 route→web 依赖时构建失败；② 单独构建 route 时门禁生效 | 3d |
| **ENG-P1-6** | **文档治理**：建立 `docs/` 目录归拢全部文档；根目录仅保留 `README.md`（精简为索引 + 快速开始）与 `CONTRIBUTING.md`；为剩余文档标注 owner 与 last-reviewed 日期；**解 gitignore 3 个重要文档**（或明确其本地草稿定位） | ① 根目录 md ≤ 3 个；② 文档有 owner 标注；③ 重要文档入库 | 3d |
| **ENG-P1-7** | **默认标签与 Runner 治理**：审计 `@test1` / `@route` 标签语义，建立标签规范文档（如 `@smoke` / `@regression` / `@api` / `@web`）；合并或明确 4 个 Runner 的用途 | ① 默认 tags 语义明确且非临时值；② Runner 用途有文档 | 2d |

### P2 —— 优化

| 任务 ID | 任务 | 验收标准 | 工时 |
|---|---|---|---:|
| **ENG-P2-1** | 启用并行执行：failsafe 配置 `parallel` + `threadCount`（建议 4），验证框架并发能力在 CI 中稳定 | 构建时长下降 ≥ 40%；无新增 flaky | 5d |
| **ENG-P2-2** | SpotBugs 基线：生成真实基线（用 `updateExcludeFilter`），每条抑制加原因与整改计划；统一根与 test-automation 两份排除文件 | 排除文件非空且有说明；`mvn verify` 可通过 | 3d |
| **ENG-P2-3** | 版本常量统一：改为从 `MANIFEST.MF` 或 Maven 过滤的 properties 读取；移除 "FINANCIAL-GRADE" 自评词汇 | 版本号单一来源 | 1d |
| **ENG-P2-4** | 仓库清理：移除 `.brok.bak`；`.gitignore` 增 `*.bak`；孤儿目录（route-demo-*、空 `src/`）要么纳入构建要么删除 | 无孤儿与备份文件 | 1d |
| **ENG-P2-5** | 测试数据管理：引入统一数据工厂或数据集版本管理，支持多环境数据切换 | 数据集中管理；多环境可切 | 4d |
| **ENG-P2-6** | Flaky 检测：即使不重试，也应统计历史失败率并标记 flaky 用例，输出至报告 | 报告含 flaky 标记 | 3d |

---

## 第八部分：给架构决策者的建议

这一部分是整份评审中**最需要优先处理**的，理由很简单：

> **前六个模块的所有问题（测试缺失、上帝类、并发竞态、覆盖写），在没有 CI 的情况下，都只是"已知但未处理"；而一旦 CI 建立，它们会变成"每日可见的红"。**
>
> **没有 CI，整改任务列表永远只是列表。**

具体建议：

1. **先建 CI（ENG-P0-1），再谈其他。** 但要注意：直接开全量 `mvn verify` 会立刻失败（SpotBugs 空基线 + Checkstyle 存量违规 + 无覆盖率阈值）。建议**分两步**：第一步只跑 `mvn test`（保证编译与单测通过，立即获得"每次提交都验证过"的价值）；第二步在 SpotBugs/Checkstyle 基线就位后再开全量 verify。

2. **依赖下沉（ENG-P0-2）是"让架构真实存在"的前提。** 当前的多模块拆分在 Maven 层面是真的，在 classpath 层面是假的。只有当 `framework-core` 真的只依赖 5 个库时，"core 是底座"这句话才成立。

3. **CVE 基线需要一次管理决策，而非技术决策。** 8 个 CRITICAL 被冻结，技术上可以解释（传递依赖、无修复版本、不可达路径），但**必须由安全团队或架构委员会书面签字**，而不是由某个开发者写进 `osv-scanner.toml`。当前状态是"技术团队代替管理层做了风险接受决策"。

4. **文档不用重写，但要"收拢 + 归位"。** 320KB 文档本身是资产，问题是它们散在根目录、部分未入库、无 owner。建立 `docs/` 目录 + README 索引 + owner 标注，一天的工作量就能解决 80% 的问题。

5. **"FINANCIAL-GRADE" 这个常量建议删除。** 金融级不是自封的，是由审计方认定的。把这种自评写进生产代码，在正式评审中会被反复质疑——它给了评审者一个"这个团队对自己的认知不客观"的第一印象，而这个印象会影响后续所有结论的解读。
