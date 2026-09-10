# 模块评审 05｜`framework-reporting`

> 定位：测试报告生成（Serenity 集成 + Freemarker 汇总报告）
> 规模：**2 个主类** + 3 个测试 / ~2,500 行（含模板）
> 依赖：`framework-core`、Gson、serenity-model、Freemarker
> **模块总评：3.5 / 5.0 —— 全项目评分最高，小而美的典范**

---

## 一、模块结构

| 类别 | 文件 | 说明 |
|---|---|---|
| 主类 | `SerenityReporter.java` | Serenity 报告集成 + 数据收集 |
| 主类 | `SummaryReportGenerator.java` | Freemarker 汇总报告生成（**单文件 90KB**） |
| 模板 | `reporting/src/main/resources/**/*.ftlh` | Freemarker 模板（16 个 `.ftlh`） |
| 测试 | `SummaryReportGoldenTest.java` | **Golden 逐字节比对** |
| 测试 | `SummaryReportBranchTest.java` | 分支覆盖 |
| 测试 | `ReportingRouteDecouplingArchTest.java` | ArchUnit 解耦门禁 |

---

## 二、八维度逐项分析

### D1 模块边界与依赖治理 —— 3.5 / 5 ✅

**优点 RP-1：ArchUnit 显式固化"reporting 对 route 零依赖"**

`ReportingRouteDecouplingArchTest` 用 ArchUnit 断言 reporting 不依赖 route。这是**主动防御**——因为报告层天然有"想抓取更多运行时数据"的冲动，用门禁挡住比靠自觉可靠。

**优点 RP-2：依赖极简**
仅依赖 `framework-core` + Gson + serenity-model + Freemarker，是**全项目依赖最干净的模块**。

**问题 RP-3（中）：`SummaryReportGenerator` 单文件 90KB**
虽然模块整体小，但单个类达 90KB（约 2,000+ 行），render 逻辑与数据组装混在一起。

**问题 RP-4（轻）：`SummaryReportGenerator.java.broken.bak`（28KB）遗留在 main 源码树**
`reporting/src/main/java/.../SummaryReportGenerator.java.broken.bak` 是备份文件，位于 main 源码目录。虽非 `.java` 后缀不会被编译，但属于仓库污染，且容易被误恢复。

---

### D2 抽象设计与扩展性 —— 3.5 / 5 ✅

**优点 RP-5：模板化规范，XSS 防护到位**
使用 Freemarker 2.3.34 + `.ftlh` 扩展名（**默认开启 HTML auto-escape**），从模板引擎层面闭合 XSS 缺口——而非依赖手工 escape。Golden Test 中有专门用例 `htmlEscapesHtmlMetacharactersFromOutcomeData` 验证该行为。

**优点 RP-6：模板与代码分离**
16 个 `.ftlh` 模板独立管理，报告样式调整不需改 Java 代码。

**问题 RP-7（中）：多格式输出不足**
当前仅支持 HTML / CSV / ZIP，**无 JSON / PDF 原生输出**。企业级场景通常需要：
- JSON（供 CI 系统消费、做质量门禁数据）
- PDF（供合规存档、审计交付）

**问题 RP-8（中）：无报告数据模型层**
`SummaryReportGenerator` 直接组织数据并喂给模板，缺少独立的 `ReportModel` / `TestOutcomeSummary` 等领域模型。模板与数据契约隐式耦合——改模板字段名时编译器无法保护。

---

### D3 并发与线程安全 —— 3.0 / 5

**问题 RP-9（中）：报告聚合的并发假设未显式化**
汇总报告在 `verify` 阶段由 exec-maven-plugin 单线程生成（根 POM 注释说明绑定在 `verify`，晚于 `post-integration-test` 的 `serenity:aggregate`），**时序由 Maven 阶段保证而非代码保证**。若有人改为并行生成，无保护。

**优点**：`SerenityReporter` 入队即脱敏，多线程收集数据时的敏感信息处理在入队侧完成，避免渲染期才处理导致的竞态。

---

### D4 生命周期与资源治理 —— 3.0 / 5

**问题 RP-10（中）：`SummaryReportGenerator` 生成流程绑定在 Maven `verify` 阶段的 exec 插件**

根 POM 注释（44-49 行）说明：汇总报告由 `test-automation` 的 pom 内联 exec-maven-plugin 绑定 `verify` 生成，靠 **Maven 阶段顺序**保证晚于 `serenity:aggregate`。

这个设计的脆弱点：
- 时序依赖构建工具而非代码契约；
- 若改用 Gradle 或调整生命周期，报告会基于不完整数据生成；
- 生成失败时的降级路径不明确。

---

### D5 配置与多环境 —— 3.0 / 5

**问题 RP-11（中）：报告配置分散**
报告相关配置散落在 `serenity.properties`、exec 插件参数、Freemarker 模板内，无统一入口。多环境（dev/uat/prod 报告归档位置）差异靠外部传参。

---

### D6 错误处理与可观测性 —— 3.0 / 5

**问题 RP-12（中）：生成失败的降级路径不明确**
模板渲染失败、数据缺失时是否有兜底（生成简化报告 / 保留 Serenity 原生报告）未在代码中显式处理。企业级要求：**报告生成失败不应导致构建失败，但必须被感知**。

**优点 RP-13**：`SerenityReporter` 入队即脱敏，数据进入报告管道前已完成敏感信息处理（见 D7）。

---

### D7 安全与合规 —— 4.0 / 5 ✅

**优点 RP-14：安全 sink 设计——入队即脱敏**
`SerenityReporter` 在数据入队阶段就调用 `SensitiveDataSanitizer`，而非渲染时才过滤。这意味着：
- 任何下游消费者（HTML / CSV / 未来的 JSON）都自动继承脱敏；
- 新增输出格式不会意外泄露敏感数据。

**这是"安全默认值（secure by default）"的正确实现**，优于在每个渲染路径各做一次过滤。

**优点 RP-15：HTML auto-escape 防 XSS**
`.ftlh` 扩展名启用 auto-escape，且有用例验证。

**优点 RP-16：与 route 的 `MonitorFailureReportWriter`（600 权限）形成一致的安全基线**

---

### D8 可测试性与质量门禁 —— 4.0 / 5 ✅ **全项目最高分**

**优点 RP-17：Golden Test 策略是教科书级的**

`SummaryReportGoldenTest` 的做法：
1. **逐字节比对**生成的 HTML 与基线文件；
2. **确定性设计**——钉死 `project.name` / `report.url` / `startTime`、路径时间戳归一化，消除非确定性；
3. **基线缺失即 fail** —— 这一条最关键，它防止了"基线没建、测试自动通过"的**假绿**。

绝大多数报告类代码的测试都死在"输出不稳定"上，而这里通过"归一化 + 逐字节 + 缺基线即失败"三件套彻底解决。**这套模式应该推广到 codegen 模块**（codegen 同样生成文件，但无任何 Golden Test）。

**优点 RP-18：2 主类配 3 个测试**
测试/生产类比例 1.5:1，是全项目唯一"测试密度正常"的模块。

---

## 三、问题清单

| ID | 级别 | 问题 | 证据 |
|---|---|---|---|
| RP-3 | **P1** | `SummaryReportGenerator` 单文件 90KB，render 与数据组装混合 | 文件规模 |
| RP-7 | **P1** | 无 JSON / PDF 原生输出，无法对接 CI 质量门禁与合规存档 | 代码能力 |
| RP-12 | **P1** | 报告生成失败无明确降级路径 | 无 try-catch 兜底 |
| RP-8 | **P2** | 无独立报告数据模型层，模板与数据隐式耦合 | 代码结构 |
| RP-10 | **P2** | 生成时序依赖 Maven 阶段而非代码契约 | 根 POM 注释 44-49 行 |
| RP-9 | **P2** | 并发假设未显式化/无保护 | — |
| RP-11 | **P2** | 报告配置分散，无统一入口 | — |
| RP-4 | **P2** | `.broken.bak`（28KB）遗留在 main 源码树 | 目录实证 |

---

## 四、整改任务列表（reporting 模块）

### P1 —— 重要

| 任务 ID | 任务 | 验收标准 | 工时 |
|---|---|---|---:|
| **RPT-P1-1** | **拆分 `SummaryReportGenerator`（90KB）**：拆为 `ReportDataCollector` / `ReportModelAssembler` / `TemplateRenderer` / `ReportWriter`，单类 ≤ 400 行 | ① 单文件 ≤ 400 行；② Golden Test 全部仍通过（行为不变） | 5d |
| **RPT-P1-2** | **新增 JSON 输出**：输出结构化汇总 JSON（用例数、通过率、失败详情、耗时），供 CI 质量门禁与趋势分析消费 | ① 生成 `summary.json`；② schema 文档化；③ 有 Golden Test 覆盖 | 3d |
| **RPT-P1-3** | **生成失败降级**：模板渲染/数据缺失时，捕获异常 → 生成最小化报告 + 显式 ERROR 日志 + 构建警告（不阻断），保留 Serenity 原生报告 | ① 注入故障后构建不失败；② 有明确告警；③ 原生报告完整 | 2d |

### P2 —— 优化

| 任务 ID | 任务 | 验收标准 | 工时 |
|---|---|---|---:|
| **RPT-P2-1** | 引入 `ReportModel` 领域模型层，模板只消费模型 | 模板字段名变更可被编译器捕获 | 4d |
| **RPT-P2-2** | 新增 PDF 输出（用于合规存档） | 可生成 PDF 报告 | 3d |
| **RPT-P2-3** | 生成时序代码化：报告生成前显式校验 `serenity:aggregate` 产物存在且完整 | 数据不完整时明确报错而非静默生成 | 1d |
| **RPT-P2-4** | 报告配置统一入口（`ReportConfig`），支持多环境归档路径 | 配置集中 | 2d |
| **RPT-P2-5** | 清理 `SummaryReportGenerator.java.broken.bak`；`.gitignore` 增加 `*.bak` | 无备份文件入库 | 0.5d |

---

## 五、给架构决策者的建议

**`reporting` 是全项目唯一"小而美"的模块，它的价值不在于代码量，而在于它证明了一件事：这个团队具备写出企业级代码的能力。**

证据：
- 知道用 `.ftlh` 而非 `.ftl` 来默认开启 HTML 转义；
- 知道 Golden Test 必须"基线缺失即失败"才能防假绿；
- 知道脱敏要在**入队时**做而不是**渲染时**做；
- 知道用 ArchUnit 主动挡住"报告层想抓更多数据"的冲动。

**这说明当前项目的问题不是能力问题，而是工程量分配问题**——团队把精力投在了引擎（route）和报告（reporting）上，而基础设施（CI、依赖治理、门禁）被系统性忽略了。

**两条建议：**

1. **把 reporting 的 Golden Test 模式提炼为团队规范**，强制推广到 `codegen`（同样生成文件、同样需要幂等与防覆盖，但目前零 Golden Test）。这是投入产出比最高的一步。

2. **把 reporting 安全 sink 模式（入队即脱敏）写入《框架设计约定》**，作为新增任何数据出口（未来的 Kafka、ES、Prometheus exporter）的强制约束。

3. **优先补 JSON 输出（RPT-P1-2）**。它看似是 P1 优化，实则是**解锁后续所有质量门禁的前提**——覆盖率门禁、趋势分析、质量看板、发布卡点，全部依赖可机读的报告数据。没有 JSON 输出，CI 只能靠解析 HTML，脆弱且不可维护。
