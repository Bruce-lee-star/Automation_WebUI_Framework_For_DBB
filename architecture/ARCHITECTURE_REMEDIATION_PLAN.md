# 架构整改方案与任务排期

> 依据：`ARCHITECTURE_REVIEW.md`（评审结论：不符合企业级标准，综合 3.5/10）
> 版本：v1.0　编制日期：2026-08-31
> 范围：33 个任务 / 4 个阶段 / 28 周 / 约 153 人日

---

## 第一部分　方案总纲

### 1.1 目标与成功度量

| 度量项 | 现状（实测） | Phase 1 末 | Phase 2 末 | Phase 3 末 |
|---|---|---|---|---|
| 框架单元测试数 | 71 | 200 | 320 | 400+ |
| 行覆盖率 | ≈1%（71 测试 / 5 万行） | 20% | 40% | 60% |
| 质量门禁插件 | 0 | 6 | 7 | 7 |
| 循环依赖组数 | 3 | 0 | 0 | 0 |
| 超 1,000 行类 | 13 | 13 | 0 | 0 |
| `static ThreadLocal` | 33+ | 33+ | ≤20 | ≤5 |
| 宽泛 `catch` | 514 | 514 | ≤120 | ≤50 |
| 并行执行 | 不支持 | 不支持 | 不支持 | 4 workers |
| 回归总时长 | 基线 T | T | T | ≤0.4T |
| 编译期模块边界 | 无 | **有（ArchUnit）** | **有（Maven 模块）** | 有 |

### 1.2 核心策略：先立门禁，再改代码

这是本方案最重要的一条原则，请优先理解。

代码里现有 **574 处 ⭐ + 324 处「修复 P1-xx / P3-xx」**审计标记。这些标记说明团队过去一直在认真修问题——但问题仍在反复出现。原因不是不努力，而是**没有任何自动化机制阻止债务重新累积**：

- 修完循环依赖 → 下个迭代又有人 import 回来了
- 修完宽泛 catch → 新代码继续 `catch (Exception e)`
- 修完重复配置类 → 再写一个

**因此 Phase 1（约 4 周，26 人日）的全部意义，是在动手重构之前先把「守门员」装好。** 跳过 Phase 1 直接重构，几乎必然导致 6 个月后回到今天的状态，只是多了 600 处新的审计标记。

> 若因排期压力必须压缩，压缩 Phase 2/3 的内容，**不要压缩 Phase 1**。

### 1.3 阶段划分与依赖

```
Phase 0 止血 ──► Phase 1 立门禁 ──► Phase 2 拆解 ──► Phase 3 并行化 ──► Phase 4 治理
 (2周/13人日)     (4周/26人日)      (8周/55人日)     (10周/45人日)     (4周/14人日)
   低风险          关键路径          最大工作量        最高技术难度       持续性
```

**关键路径**：`T1-6 ArchUnit` → `T2-1 Maven 多模块` → `T3-1 TestContext` → `T3-4 并行验证`

**可并行**：Phase 0 全部任务相互独立；Phase 1 插件配置类任务相互独立；Phase 2/3 的各拆分任务在 `T2-1` 完成后可并行。

### 1.4 人力编排建议

| 配置 | 总工期 | 说明 |
|---|---|---|
| 1 人 | 28 周 | 只能串行，Phase 2/3 会拖得很长，不推荐 |
| **2 人** | **约 15 周** | 推荐最低配置：A 做门禁与工程侧，B 做拆分与重构侧 |
| 3 人 | 约 11 周 | 推荐：A 门禁+CI，B 拆 BasePage/异常体系，C 拆 route |
| 4 人 | 约 9 周 | 需精细协调，T2-1 多模块拆分是合并冲突高发点 |

**推荐角色分工（3 人配置）**
- **A — 工程效能**：T1-1~T1-8（门禁 + CI），T4-3（配置收敛），T4-4（文档防漂移）
- **B — 核心框架**：T2-3（BasePage 拆分），T2-6（异常体系），T3-1（TestContext），T3-5（PageDriver）
- **C — 能力层**：T2-2（codegen 拆出），T2-4（RouteEngine），T2-5（ApiCaptureContext），T2-7，T3-3

---

## 第二部分　任务分解（WBS）

任务编号规则：`T{阶段}-{序号}`。每个任务含：目标 / 现状证据 / 执行步骤 / 验收标准 / 工作量 / 依赖 / 风险与回退。

---

## Phase 0　止血（第 1~2 周，13 人日）

> 目标：消除合规风险与明显误导，不涉及架构改动，全部低风险可回退。
> 完成后状态：**可安全对外共享代码库，无合规红线暴露。**

---

### T0-1　修复 requestUrl 脱敏缺口　【P0 / 合规】

| 项 | 内容 |
|---|---|
| **目标** | 消除「URL query 中的 token 可出域」的数据泄漏路径 |
| **现状证据** | `CapturedApiCall.java:178-185` 只脱敏 body 与 headers，**`requestUrl` 未脱敏**；而 `ApiMonitoringRecord.java:35` 显式调用了 `sanitizeUrl`，说明**两处收口不一致**，是遗漏而非设计 |
| **工作量** | 0.5 人日 |
| **依赖** | 无 |

**执行步骤**
1. 在 `CapturedApiCall` 中定位 `getRequestUrl()` 与所有对外暴露 URL 的 getter（含 `toString()`、JSON 序列化入口、report 输出路径）
2. 统一改走 `SensitiveDataSanitizer.sanitizeUrl(...)`
3. 全局排查其它 URL 出口：`ApiCaptureContext.java` 报告生成路径（`:646`）、`MonitorHandler`、`FileStoreMonitorCallback`
4. 补一个回归测试：构造带 `?token=xxx&sessionId=yyy` 的 URL，断言输出中不含原始值

**验收标准**
- 全仓库 URL 出口统一经过 `sanitizeUrl`
- 新增测试 `SensitiveDataSanitizerTest#shouldMaskQueryParamsInUrl` 通过
- `grep -rn "getRequestUrl" src/main/java` 逐条确认已收口或已豁免（豁免需注释说明原因）

**风险与回退**：低。脱敏可能改变现有报告输出，需通知依赖报告的使用方；回退为 revert 单 commit。

**进展（2026-08-31）**：✅ 已完成。`CapturedApiCall.requestUrl` 构造期经 `SensitiveDataSanitizer.sanitizeUrl` 收口；`MonitorFailureReportWriter` 报告输出同步收口；新增 `src/test/java/.../tests/route/SensitiveDataSanitizerUrlTest.java`（3 用例通过，commit 6b47c99）。`sanitizeUrl` 行为：含敏感 query 参数（token/sessionId 等）时剥离整个 query（路径保留），无敏感参数则原样返回。

---

### T0-2　为 SensitiveDataSanitizer 补单元测试　【P0 / 合规】

| 项 | 内容 |
|---|---|
| **目标** | 让合规件获得回归保护（现状**零测试**） |
| **现状证据** | `SensitiveDataSanitizer.java` 559 行，`src/test/java/.../tests/route/` 下 9 个测试文件均**未覆盖**它 |
| **工作量** | 1.5 人日 |
| **依赖** | 无（但 T1-7 引入 assertj 后断言更好写，若已排到 Phase 1 可后置） |

**执行步骤**
1. 建 `src/test/java/.../framework/web/route/util/SensitiveDataSanitizerTest.java`
2. 必测用例（至少 12 个）：
   - 字段名命中：password / token / secret / authorization / sessionId / cookie / apiKey
   - 字段名未命中：普通业务字段不应被误伤（如 `userName`、`orderNo`）
   - Bearer token 与 JWT 三段结构识别
   - URL query 参数脱敏（依赖 T0-1）
   - 嵌套 JSON 递归脱敏
   - 数组内元素脱敏
   - 空值 / null / 空串 / 非字符串类型
   - 超长字符串的截断边界
   - headers 大小写不敏感（`Authorization` vs `authorization`）
   - 脱敏后不残留原始值的子串
3. 用参数化测试覆盖字段名集合

**验收标准**：新增 ≥12 个 `@Test`，全部通过；该文件行覆盖 ≥85%。

**风险与回退**：低。

---

### T0-3　删除 7 个空目录　【P1】

| 项 | 内容 |
|---|---|
| **目标** | 消除「功能已存在」的误导 |
| **现状证据** | `find src/main/java -type d -empty` 返回 7 个：`retry/{configuration,controller,executor,listener,metrics,strategy}` + `page/assertion` |
| **工作量** | 0.5 人日 |
| **依赖** | 无 |

**执行步骤**
1. 确认 `retry` 包在 git 历史中从未有实现（避免误删未提交工作）
2. `git rm -r --cached` + 删除物理目录
3. 若 `retry` 是规划中功能，改以 ADR 文档 + issue 记录设计意向，**不要用空目录占位**
4. 全局检查是否有代码引用 `retry` 包路径

**验收标准**：`find src/main/java -type d -empty` 返回空；`mvn compile` 通过。

**风险与回退**：低。若有未提交的本地实现会丢失——**执行前先 `git stash list` 与 `git status` 确认**。

---

### T0-4　处置 persistence 与 HikariCP（722 行永不生效代码）　【P1】

| 项 | 内容 |
|---|---|
| **目标** | 删除默认关闭、无驱动、无测试、无迁移的死代码 |
| **现状证据** | `pom.xml:236-240` HikariCP 5.0.1；**无 MySQL/PG 驱动**；`ApiMonitoringRepository.java:419` 硬编码 `com.mysql.cj.jdbc.Driver` 必抛 ClassNotFound；`:197-203` 仅 WARN 静默降级；`serenity.properties:255/277` 开关默认 `false` |
| **工作量** | 1 人日（删除）或 5 人日（保留并补全） |
| **依赖** | 需先与团队确认：**API 监控落库是否为在途需求？** |

**执行步骤（推荐路线：删除）**
1. 与需求方确认落库功能是否在途
2. 若**不在途**：删除 `route/persistence/` 全部 4 个文件 + `HikariCP` 依赖 + `HikariConfigFactory.java`（未提交）+ `DatabaseUtil.java`(1031 行，若仅被 persistence 使用一并评估) + `serenity.properties` 中相关配置项
3. 若**在途**：按 Phase 2 单项立项，需补全 JDBC 驱动、Flyway 迁移、连接池配置、集成测试——**这是 5 人日以上的独立任务，不应以现状留在主干**

**验收标准**（删除路线）：`grep -r "HikariCP\|jdbc" pom.xml` 无结果；`route/persistence/` 不存在；编译通过；surefire 测试全绿。

**风险与回退**：中。若删错会影响在途需求——**必须先做第 1 步确认**。回退为 revert。

---

### T0-5　E2E 测试移出 surefire 单元测试阶段　【P1】

| 项 | 内容 |
|---|---|
| **目标** | 恢复测试分层语义，缩短单元测试反馈时间 |
| **现状证据** | `RouteUnifiedBindingBrowserE2ETest.java:37-47` 真启动 chromium，却匹配 `pom.xml:288-290` 的默认 `**/*Test.java`；靠 `forkedProcessTimeoutInSeconds=1800`（`:304`）兜底；pom 注释自陈 4 个浏览器测试类合计 335s |
| **工作量** | 0.5 人日 |
| **依赖** | 无 |

**执行步骤**
1. 将 `RouteUnifiedBindingBrowserE2ETest` 重命名为 `RouteUnifiedBindingBrowserE2EIT`（自动落入 failsafe 的 `**/*IT.java`，`pom.xml:327`）
2. 或保留命名，加 JUnit `@Category`/`@Tag` 并在 surefire 中 `<excludedGroups>`
3. 同步调整 `forkedProcessTimeoutInSeconds`：failsafe 从 600 调到能满足 E2E 的值，surefire 的 1800 可大幅下调（单元测试集中后应远小于此）
4. 验证：`mvn test` 不再启动浏览器；`mvn verify` 仍执行 E2E

**验收标准**：`mvn test` 总耗时较基线下降 >80%；E2E 在 `mvn verify` 阶段执行且通过。

**风险与回退**：低。

---

### T0-6　仓库卫生治理　【P2 / 阻塞协作】

| 项 | 内容 |
|---|---|
| **目标** | 让协作者能拿到一份可运行的完整代码 |
| **现状证据** | 未跟踪：`ShutdownCoordinator.java`、`HikariConfigFactory.java`、整个 `tests/api/`（13 文件）、4 个 Glue、`RouteDemoPage.java`、`route-demo-service/`、`route-demo-web/`；根目录垃圾：`1.txt`、`cp.txt`、`_tbtest/`、`_verify_nls/`（1,407 文件）、13 个 `*.log` |
| **工作量** | 1 人日 |
| **依赖** | 无 |

**执行步骤**
1. **先备份**：`git add -A && git stash`（确保可回退）
2. 提交未跟踪源码：`ShutdownCoordinator.java`、`HikariConfigFactory.java`、`tests/api/**`、4 个 Glue、`RouteDemoPage.java`
3. 决策 `route-demo-service/` 和 `route-demo-web/`：若为演示工程，建议移至 `examples/` 目录或独立仓库；若保留，需提交
4. 清理根目录：`1.txt`、`cp.txt`、`build_test.log`、`install.log`、`demo_service*.log`、`web_*.log`、`verify_*.log`、`*_err.log`
5. 删除 `_tbtest/`、`_verify_nls/`（1407 文件，疑似 node_modules 残留）
6. 补 `.gitignore`：`_*/`、临时验证目录
7. 补充 CODEOWNERS 与 PR 模板（可选但推荐）

**验收标准**：`git status --short` 为空；新克隆 + `mvn verify` 可跑通；`.gitignore` 覆盖所有临时目录模式。

**风险与回退**：**中高——会误删未提交工作**。必须先 stash 备份，删除前逐个确认目录内容。

---

### T0-7　清理死 import 与失效 workaround　【P3】

| 项 | 内容 |
|---|---|
| **目标** | 消除误导性代码 |
| **现状证据** | `BasePage.java:17` import `route.core.RouteEngine` 但全文件未使用；`RoleElementPicker.java:977` 的 65535 常量池拆分 workaround 在改为运行时 `loadScript()` 后已失效 |
| **工作量** | 0.5 人日 |
| **依赖** | 无（但 RoleElementPicker 相关部分会在 T2-2 整体移除，可合并处理） |

**执行步骤**
1. IDE 或 `mvn` 全量扫描未使用 import 并清理
2. 清理 `RoleElementPicker.java:977-986` 的 `concat()` 拼接与相关注释（拆 a/b1/b2 已无必要）
3. 清理 `RouteEngine:59-77` 悬空 Javadoc、`:114-121` 连续空行

**验收标准**：无未使用 import；编译无 warning 增加。

**风险与回退**：低。

**进展（2026-08-31）**：✅ 已完成。`BasePage.java:17` 未使用的 `import ...route.core.RouteEngine` 已删除（commit 6b47c99）。

---

**Phase 0 小计：7 个任务 / 5.5 人日（含 T0-6 备份与确认）**

---

## Phase 1　建立门禁（第 3~6 周，26 人日）

> 目标：**在动手重构前装好守门员。** 这是全方案的关键路径。
> 完成后状态：**任何架构劣化都会被 CI 自动拦截，重构成果不会退化。**

---

### T1-1　引入 JaCoCo 覆盖率基线　【P0】

| 项 | 内容 |
|---|---|
| **目标** | 让覆盖率「只升不降」 |
| **工作量** | 1 人日 |
| **依赖** | 无 |

**执行步骤**
1. 加 `jacoco-maven-plugin`，绑定 `prepare-agent` 与 `report`
2. **首轮只生成报告，不加门禁**（现状 ≈1%，加门禁会立刻红）
3. 记录基线值写入 ADR
4. 设置渐进阈值：Phase 1 末 20% → Phase 2 末 40% → Phase 3 末 60%
5. 配置 `<excludes>` 排除 `page/scan/**`（T2-2 后整体移出）、`**/generated/**`

**验收标准**：`mvn verify` 后生成 `target/site/jacoco/index.html`；基线值已记录。

**风险与回退**：低。注意 Serenity 的 agent 与 JaCoCo agent 可能存在端口/类加载冲突，需验证。

---

### T1-2　引入 Checkstyle　【P1】

| 项 | 内容 |
|---|---|
| **目标** | 自动化拦截代码风格与结构性异味 |
| **工作量** | 1.5 人日 |
| **依赖** | 无 |

**执行步骤**
1. 引入 `maven-checkstyle-plugin` + 规则集（建议基于 Google/Sun 风格裁剪）
2. **首轮规则只开 3 条**（避免一上来几千个告警）：
   - `FileLength`：max=1000（先卡住新增千行类）
   - `EmptyCatchBlock`：拦截空 catch（对应 514 处宽泛 catch）
   - `UnusedImports`：拦截死 import（对应 T0-7）
3. 先用 `warn` 级别跑一轮，统计告警数并写入 ADR
4. 对存量代码生成 `suppressions.xml`（**必须限定有效期，到期清理**）
5. 转 `failOnViolation=true` 后只对新代码生效

**验收标准**：`mvn checkstyle:check` 可通过；新代码无法新增千行类、空 catch、死 import。

**风险与回退**：低。规则过严会阻塞开发——**务必渐进式**。

---

### T1-3　引入 SpotBugs　【P1】

| 项 | 内容 |
|---|---|
| **目标** | 静态发现真实缺陷 |
| **工作量** | 1.5 人日 |
| **依赖** | 无 |

**执行步骤**
1. 引入 `spotbugs-maven-plugin`
2. 重点关注 Bug 类别：
   - `EI_EXPOSE_REP` / `MS_EXPOSE_REP`：可变对象泄漏（对应 33 个 static 状态）
   - `STCAL_*`：静态 Calendar/DateFormat 线程不安全
   - `NP_*`：空指针
   - `RV_RETURN_VALUE_IGNORED`：忽略返回值
   - `SE_*`：序列化问题
3. 生成存量 suppressions，新代码零容忍

**验收标准**：`mvn spotbugs:check` 通过；基线缺陷数已记录并持续下降。

**风险与回退**：低。

---

### T1-4　引入 OWASP dependency-check　【P0 / 安全】

| 项 | 内容 |
|---|---|
| **目标** | 接续现有依赖治理成果，自动化 CVE 门禁 |
| **现状** | 项目已有良好依赖安全意识（`pom.xml:27-31` 显式压 jackson/json-smart、排除 commons-logging、`pom.xml:179-187` 精细对齐 selenium-support 4.38.0）——**本任务是把它自动化，不是从零开始** |
| **工作量** | 1 人日 |
| **依赖** | 无 |

**执行步骤**
1. 引入 `dependency-check-maven`
2. 配置 NVD API Key（避免匿名限流）
3. 设定 CVSS 阈值（建议 ≥7.0 阻断）
4. 首次跑生成基线报告，对已知项在 `suppressions.xml` 中登记并注明处置计划

**验收标准**：CI 中 CVE ≥7.0 直接失败；无高危 CVE 未处置。

**风险与回退**：低。首次全量扫描需下载 NVD 库，较慢（建议缓存数据库、非每次全量）。

---

### T1-5　引入 Maven Enforcer　【P1】

| 项 | 内容 |
|---|---|
| **目标** | 拦截依赖地狱与重复类 |
| **工作量** | 1 人日 |
| **依赖** | 无 |

**执行步骤**
1. 引入 `maven-enforcer-plugin`
2. 启用规则：
   - `requireUpperBoundDeps`：强制传递依赖取最高版本（防止 selenium 4.15 vs 4.38 这类问题复发）
   - `banDuplicateClasses`：**直接拦截两份同名 `FrameworkConfig` 这类问题**
   - `requireJavaVersion`、`requireMavenVersion`
   - `dependencyConvergence`（可选，可能告警过多）
3. 生成基线，逐项处置

**验收标准**：`mvn enforce:enforce` 通过；依赖树无版本冲突、无重复类。

**风险与回退**：低。

---

### T1-6　引入 ArchUnit 架构守护　【P0 / 本阶段最关键】

| 项 | 内容 |
|---|---|
| **目标** | **把「注释约定的模块边界」变成编译期强制规则** |
| **现状证据** | `api/config/FrameworkConfig.java:17-20` 注释：「api 与 web 是两个独立模块边界…两者刻意不合并」——但物理上同 jar 同 classpath，无强制；已产生 3 组循环依赖 |
| **工作量** | 3 人日 |
| **依赖** | T1-7（需要 JUnit 测试承载规则） |

**执行步骤**
1. 加 `archunit-junit4` 依赖（项目是 JUnit 4.13.2，注意选对 artifact）
2. 建 `src/test/java/.../arch/ArchitectureTest.java`，规则清单：

```java
// 规则 1：禁止正向循环 —— lifecycle 不得反向依赖 page
noClasses().that().resideInPackage("..web.lifecycle..")
    .should().dependOnClassesThat().resideInPackage("..web.page..")

// 规则 2：禁止 lifecycle 反向依赖 route（依赖倒置）
noClasses().that().resideInPackage("..web.lifecycle..")
    .should().dependOnClassesThat().resideInPackage("..web.route..")

// 规则 3：禁止 core 反向依赖 lifecycle
noClasses().that().resideInPackage("..web.core..")
    .should().dependOnClassesThat().resideInPackage("..web.lifecycle..")

// 规则 4：api 与 web 必须隔离
noClasses().that().resideInPackage("..framework.api..")
    .should().dependOnClassesThat().resideInPackage("..framework.web..")
// 反向同样

// 规则 5：禁止运行时代码依赖 codegen（为 T2-2 铺路）
noClasses().that().resideInPackage("..web.page.base..")
    .should().dependOnClassesThat().resideInPackage("..web.page.scan..")

// 规则 6：Playwright 类型不得出现在 page 包 public API（为 T3-5 铺路，先只统计不阻断）
// 规则 7：禁止 java.util.logging / commons-logging（统一 SLF4J）
// 规则 8：禁止 System.out / System.err（现状仅 2 处，可直接阻断）
```

3. **首批规则对存量违规项先用 `FreezingArchRule` 冻结**（ArchUnit 提供的 freeze 机制：记录存量、只拦截新增），避免一上来全红
4. 逐条解冻：Phase 2 每修完一组循环依赖，就解冻对应规则

**验收标准**
- `mvn test` 中执行架构规则
- 冻结文件中存量违规数**只减不增**
- 8 条规则全部生效，其中至少 3 条为 hard-fail

**风险与回退**：中。规则过严会阻塞开发；`FreezingArchRule` 的 `archunit_store` 目录需提交进 git 才能跨机生效。

> **为什么这条最重要**：Phase 2 的所有重构（多模块、拆上帝类、统一异常）在物理上做完之后，如果没有 ArchUnit，**下一个迭代就会被人 import 回去**。这条规则是 Phase 2/3 成果的唯一保险。

---

### T1-7　引入 Mockito + AssertJ　【P0】

| 项 | 内容 |
|---|---|
| **目标** | 解开单元测试的结构性封锁 |
| **现状** | 无 mockito / wiremock / assertj；14 个单例 + 33 个 static ThreadLocal 的系统，没有 mock 就在结构上无法单测 |
| **工作量** | 1 人日 |
| **依赖** | 无 |

**执行步骤**
1. 加 `mockito-core`（或 `mockito-inline`，用于 mock static——**当前架构下 mock static 是刚需，但也说明应尽快做 T3-1**）+ `assertj-core`
2. 注意 Java 21 需 Mockito 5.x
3. 加 `wiremock` 用于 T2-7 与 route 层测试（可选，建议加）
4. 写 1~2 个示范测试作为团队模板

**验收标准**：可在单测中 mock `PlaywrightManager`、`FrameworkConfig` 等静态/单例依赖。

**风险与回退**：低。**警示**：mock static 是权宜之计，T3-1 完成后应逐步减少 static mock 的使用。

---

### T1-8　CI 流水线升级为质量门禁　【P0】

| 项 | 内容 |
|---|---|
| **目标** | 让门禁真正执行 |
| **现状证据** | `.github/workflows/` 仅 `serenity-report-push.yml`，文件头注释自陈：「本 workflow 不跑测试，只做结果部署」 |
| **工作量** | 3 人日 |
| **依赖** | T1-1 ~ T1-7（以及 T0-5 的测试分层调整） |

**执行步骤**
1. 新建 `.github/workflows/build.yml`，触发条件 `pull_request` + `push` 到 main
2. Job 设计：
   - `build`：`mvn -B compile` + `mvn -B test`（单元，快）
   - `quality`：`mvn -B verify -DskipITs` 跑 checkstyle / spotbugs / enforcer / ArchUnit / jacoco 阈值
   - `security`：owasp dependency-check
   - `integration`：`mvn -B verify`（跑 Cucumber IT，可设为 manual 或 nightly）
3. PR 必须 `build` + `quality` 全绿才可合并（配置 branch protection）
4. 保留原 `serenity-report-push.yml`，改为由 `integration` 成功后调用
5. 缓存 `~/.m2` 加速

**验收标准**：PR 上能直接看到 4 个 job 的状态；人为引入一个违规（如新增未使用 import）能被引擎拦截并红。

**风险与回退**：中。CI 环境需具备浏览器（Playwright 需 `playwright install`）；建议 integration job 用容器化或自托管 runner。

---

### T1-9　补充核心单元测试（覆盖率 ≈1% → 20%）　【P0】

| 项 | 内容 |
|---|---|
| **目标** | 让框架具备基本回归保护 |
| **工作量** | 12 人日（本阶段投入 6，其余在 Phase 2/3 持续） |
| **依赖** | T1-7（mockito）、T1-1（jacoco 度量） |

**执行步骤（按价值排序）**
1. **优先补这些**（合规/核心/易测）：
   - `SensitiveDataSanitizer`（T0-2，必做）
   - `ApiMatcher`（531 行，纯函数，易测）
   - `RouteRule` / 优先级裁决逻辑（纯逻辑）
   - `JsonUtils` / `FileReader` / `NLSUtils` / `ConfigProvider`
   - `HttpStatus` / `EndpointConfig`
2. **次优先**：`RouteDsl` 的规则构造与参数校验（1,281 行，零测试）
3. **暂缓**：`RouteEngine`、`ApiCaptureContext`、`PlaywrightListener`（需 T2 拆分后才可测）

**验收标准**：单元测试数 71 → 200；行覆盖率 ≥20%；`SensitiveDataSanitizer` 覆盖 ≥85%。

**风险与回退**：低。这是一项持续投入，建议每个 sprint 固定 20% 容量。

---

**Phase 1 小计：9 个任务 / 26 人日**

---

## Phase 2　拆解（第 7~14 周，55 人日）

> 目标：把「注释约定的模块边界」变成物理边界，把上帝类拆成可维护单元。
> **前置条件：T1-6 ArchUnit 与 T1-8 CI 已生效**（否则拆完必退化）。

---

### T2-1　Maven 多模块拆分　【P0 / 本阶段前置】

| 项 | 内容 |
|---|---|
| **目标** | 用构建系统强制模块边界，同时解决两份同名 `FrameworkConfig` |
| **工作量** | 8 人日 |
| **依赖** | T1-6（ArchUnit 先行，防止拆分过程中边界继续劣化）、T1-8 |

**执行步骤**
1. 新建父 POM（packaging=pom），迁移公共属性与 `dependencyManagement`
2. 拆分为：
   ```
   framework-parent
   ├── framework-core        公共工具、异常体系、配置抽象、TestContext（T3-1）
   ├── framework-web         lifecycle / page / listener / session / accessibility / cloud
   ├── framework-api         api/**（现有 ~25 文件，零 web 依赖，最容易先拆）
   ├── framework-route       route/**（14,799 行，依赖 web）
   ├── framework-report      report/SummaryReportGenerator
   ├── framework-codegen     page/scan/**（provided scope，见 T2-2）
   └── framework-bom         依赖版本对齐（可选）
   ```
3. **建议拆分顺序（由易到难，降低风险）**：
   - 第 1 步：`framework-api`（零 web 依赖，已验证 `grep` 无跨引用）→ 最安全，先积累经验
   - 第 2 步：`framework-codegen`（provided，单向依赖）
   - 第 3 步：`framework-report`
   - 第 4 步：`framework-core`（需要先解 T2-6 异常体系 + 抽公共配置）
   - 第 5 步：`framework-web` / `framework-route`（**最难**，存在循环依赖，需先解 T2-3/T2-4/T2-5）
4. 每拆一个模块，同步：更新 `NEXUS_PUBLISH_GUIDE.md`、CI 路径、`<modules>`、Serenity 输出目录
5. 拆完后将 ArchUnit 规则升级为跨模块校验（或直接依赖 Maven 的编译期隔离）

**验收标准**
- `mvn clean install` 全部模块通过
- 两份 `FrameworkConfig` 分属不同模块，不再冲突
- `framework-api` 的 `mvn dependency:analyze` 无 `framework-web` 依赖
- Nexus 发布流程可用（更新 `NEXUS_PUBLISH_GUIDE.md`）

**风险与回退**：**高**。这是整个方案合并冲突与破坏面最大的任务。
缓解：
- 严格按上述 5 步顺序，每步一个独立 PR
- 每步前先打 tag，可整步回退
- 与团队约定拆分期间的 merge 冻结窗口
- **不要在拆分过程中同时做重构**——先物理搬移，跑通，再重构

---

### T2-2　codegen 独立为 provided artifact 并摘出运行时热路径　【P1】

| 项 | 内容 |
|---|---|
| **目标** | 让生产制品不再包含 7,854 行开发期工具代码 |
| **现状证据** | `page/scan/` 7,854 行 Java + 7,265 行 JS 打进主 jar；已侵入运行时：`BasePage.java:836/878`（closeCurrentPage/closeOtherPages 调 `RoleElementPicker.markFrameworkClose`）、`PlaywrightManager.java:893`（每次 closeContext 调 `cleanupContext`）、`LoginSteps.java:164`（真实登录步骤调 `openPanel`） |
| **工作量** | 5 人日 |
| **依赖** | T2-1 第 2 步 |

**执行步骤**
1. `page/scan/**`（4 个 Java + `resources/scan/js/*.js`）移入 `framework-codegen` 模块，父 POM 中声明 `<scope>provided</scope>`
2. **摘除运行时调用**（关键）：
   - `BasePage:836/878` 的 `markFrameworkClose` → 改为事件总线/可选 SPI 钩子，运行时默认空实现
   - `PlaywrightManager:893` 的 `cleanupContext` → 同上
   - `LoginSteps:164` 的 `openPanel` → **删除**，这是测试步骤，不应依赖开发工具
3. 若框架确实需要「面板状态标记」能力，抽象成一个可选接口 + SPI，由 codegen 在 provided 时注入
4. 合并 T0-7 中 RoleElementPicker 的失效 workaround 清理

**验收标准**
- 主 jar 中无 `page/scan/**`、无 `scan/js/**`
- `mvn dependency:tree` 中 codegen 为 provided
- 运行时代码对 codegen 零编译期依赖（ArchUnit 规则 5 解冻为 hard-fail）
- 原有 E2E 用例仍可跑通

**附带收益**：`RoleElementPicker` 单独占 **137 处宽泛 catch**（占全库 514 的 27%）。移出后 T2-6 的异常处理工作量直接减少四分之一。**建议 T2-2 排在 T2-6 之前。**

**风险与回退**：中。摘除运行时调用可能丢失功能 —— 需确认 `markFrameworkClose` / `cleanupContext` 是否真有必要；若无必要直接删。

---

### T2-3　拆分 BasePage（1,801 行 / 112 个 public 方法）　【P1 / 最大重构】

| 项 | 内容 |
|---|---|
| **目标** | 把 10 类职责拆成协作对象，BasePage 退化为门面（Facade） |
| **工作量** | 12 人日 |
| **依赖** | T2-1 第 4 步、T3-1（TestContext，建议先做以彻底解决状态问题，但可先做接口拆分） |

**方法分类（实测 112 个唯一 public 方法，已逐一归类）**

| # | 新组件 | 方法数 | 代表方法 |
|---|---|---|---|
| 1 | `LocatorFactory` | 8 | byRole, byText, byLabel, byPlaceholder, byAltText, byTitle, byTestId, locator |
| 2 | `ElementActions` | 23 | click, jsClick, tap, type, append, check, uncheck, clear, focus, hover, keyDown, keyUp, press, selectOption, selectByVisibleText, setInputFiles, dragAndDrop, scroll* |
| 3 | `ElementQueries` | 14 | getText, textContent, innerHTML, getAttribute*, getInputValue, getElementCount, getElementBoundingBox, isVisible/Hidden/Checked/Enabled/Disabled |
| 4 | `Waiter` | 16 | waitFor*（15 个）+ pause |
| 5 | `PageAssertions` | 2 | shouldBeVisible, shouldBeNotVisible |
| 6 | `FrameNavigator` | 10 | switchToFrame, switchToFrameAndWait, switchToDefaultContent, switchToShadow, switchToDefaultShadow*, executeInFrame, getFrame, getAllFrames |
| 7 | `PageNavigator` | 20 | navigateTo, refresh, back, forward, switchToPage, close*, bringToFront, setViewportSize, setContent, getPage*, getCurrentUrl, getPageSource*, getTitle |
| 8 | `CookieManager` | 8 | getCookie(s), addCookie(s), deleteCookie, clearCookies, hasCookie |
| 9 | `ScreenshotTaker` | 2 | takeScreenshot, takeElementScreenshot |
| 10 | `JsExecutor` | 3 | executeJavaScript, acceptAlert, dismissAlert |
| 11 | `RetryPolicy` | 2 | retry, retryWithValidation |
| 12 | *生命周期* | 4 | getContext, ensureContextValid, clearAllThreadLocals, element → **移交 T3-1 TestContext** |
| 13 | *a11y* | 1 | dumpAccessibilityRoles → **移交 codegen** |

**执行步骤**
1. **第 1 步（不改动行为）**：为每个新组件建类，方法体从 BasePage **原样搬移**，BasePage 保留委托方法（deprecated）
2. **第 2 步**：每搬完一个组件，跑全量测试确认行为不变（这是「搬移」而非「重写」，风险可控）
3. **第 3 步**：组件之间通过构造注入协作，全部从 TestContext（T3-1）获取依赖，不再用 static ThreadLocal
4. **第 4 步**：消除**双头 API**——`page.click("#x")` 与 `page.element("#x").click()` 二选一，团队评审决定
5. **第 5 步**：合并**两套重试实现**——`BasePage:323-366` 与 `PageElement:188-260`，统一到 `RetryPolicy`（现有策略差异：后者有 `isRetriable` 文案匹配，前者无，需评审哪个为准）
6. **第 6 步**：BasePage 退化为 Facade，只保留组合与委托，目标 <300 行
7. 同步处理 `base/impl/SerenityBasePage.java`：它是第二个抽象基类而非「impl」，改名或重新定位

**验收标准**
- BasePage < 300 行，public 方法 ≤ 30
- 无组件超过 400 行
- 全量 E2E 与单元测试通过，行为与重构前一致
- `PageElement` 与 `BasePage` 共用同一 `RetryPolicy`
- 无 `page/scan` 依赖（T2-2 已完成）

**风险与回退**：**高**。这是用户可见 API 的破坏性变更。
缓解：
- 全程保留委托方法并标 `@Deprecated`，给下游 1~2 个迭代迁移窗口
- 严格「先搬移、后清理」，每个组件一个 PR
- 建立重构前后的行为对比测试（golden test）

---

### T2-4　拆分 RouteEngine（2,013 行）　【P1】

| 项 | 内容 |
|---|---|
| **目标** | 收敛 8 张 static 可变 Map 与四处分散的优先级裁决 |
| **工作量** | 10 人日 |
| **依赖** | T2-1 第 5 步 |

**执行步骤**
1. 拆分目标：
   - `RuleRepository`：规则注册与索引（原 `:83-86` 缓存索引、`:347 register`）
   - `RuleMerger`：跨层合并（`:553 mergeCrossLayer`、`603 resolveUnified`、`820 selectCapability`、`802 applyStoppedCapabilities`）—— **优先级裁决收敛为单一策略对象**
   - `Dispatcher`：分发（`:671 dispatchRoute`，单方法 ~200 行 8 个 exit 分支，需按分支拆解）
   - `DelayScheduler`：延迟调度（`:137 newDelayScheduler`）
   - `RouteLifecycleOwner`：生命周期（`:153 PerContextEngine`、`:57 SESSIONS`）
2. **优先级语义统一**：现状散在 4 处（scope 仅 2 个枚举值却需要 `ROUTE_SCOPE_AND_PRIORITY.md` 两张组合表描述），抽出 `PriorityPolicy` 单一决策对象，用表驱动替代散落 if-else
3. 清理 `RouteEngine:59-77` 悬空 Javadoc、`:123` 与 `:126` 的注释/实现矛盾（称已委托异步池实际仍自建线程池）
4. 8 张 static 可变 Map 收敛进实例，生命周期绑定 TestContext

**验收标准**：RouteEngine < 400 行；优先级裁决仅一处实现；新增单元测试覆盖 scope/page/context 组合矩阵（对齐 `ROUTE_SCOPE_AND_PRIORITY.md` 的两张表）。

**风险与回退**：中高。规则引擎行为变更会影响所有 route 用例。缓解：先写全量规则组合的契约测试作为护栏（现有 `RouteCapabilityContractTest` / `RoutePriorityContractTest` 可作为基础扩展）。

---

### T2-5　拆分 ApiCaptureContext（2,245 行）　【P1】

| 项 | 内容 |
|---|---|
| **目标** | 拆掉混装 8 类职责的录制上下文，并移除靠 GC 兜底的 WeakReference |
| **工作量** | 10 人日 |
| **依赖** | T2-1 第 5 步 |

**执行步骤**
1. 拆分目标：
   - `CaptureStore`：存储与限流（`:755`、`:295 MAX_RECENT_CALLS`、`:1450` 响应体仓库）
   - `ApiAssertions`：断言 DSL（`:1962 ApiAssertion`）
   - `WaitGate`：等待门控（`:568`、`:1332`、`:1367`）
   - `CaptureReporter`：报告生成（`:646`）
   - `ApiCaptureLifecycle`：生命周期（`:1654-1789`）
2. **合并重复的 Glob 匹配**：`ApiCaptureContext:1052` 与 `util/ApiMatcher.java`(531 行) 功能重叠，二选一
3. **修复 WeakReference 掩盖的缺陷**（重点）：
   - 现状：`ApiCaptureContext.java:85-91` 注释承认「`RouteDsl.on` 只负责 bind、并不保证 unbind」，线程池复用会读到死 context，于是用 `WeakReference` 把泄漏降级为「GC 后静默回退」
   - 正解：`RouteDsl.on` 改为 try/finally 保证 unbind，或提供 AutoCloseable 的 try-with-resources 形式；移除 `WeakReference`
4. 移除 `System.out.print` 残留（2 处之一在此文件）

**验收标准**：ApiCaptureContext < 400 行；无 `WeakReference`；`RouteDsl.on` 有明确的 unbind 契约并测试覆盖；Glob 匹配仅一处实现。

**风险与回退**：中。**注意：`:180-188` 的静默回退一旦移除，原本「碰巧能跑」的场景可能暴露失败**——这实际上是好事（暴露真问题），但需预留排期处理暴露出的缺陷。

---

### T2-6　统一异常体系并审查 514 处宽泛 catch　【P1】

| 项 | 内容 |
|---|---|
| **目标** | 恢复框架异常的统一捕获能力，消除「假绿」风险 |
| **现状证据** | 10 个异常中仅 2 个继承 `FrameworkException`（`NavigationException`、`TimeoutException`）；5 个直接 `extends RuntimeException`：`BrowserException`、`ConfigurationException`、`ElementException`、`InitializationException`、`ScreenshotException`；514 处 `catch (Exception|Throwable)`，约 173 处只 log 不抛 |
| **工作量** | 8 人日 |
| **依赖** | **T2-2 先完成**（移出 RoleElementPicker 可立即消除 137 处，占 27%） |

**执行步骤**
1. 改 5 个异常的父类为 `FrameworkException`（`ElementNotFoundException`、`ElementOperationException` 因为继承 `ElementException`，会自动获得修正）
2. 审查 catch 分布 TOP10（合计 315 处，占 61%）：
   | 文件 | 处数 | 处置 |
   |---|---|---|
   | `RoleElementPicker` | 137 | **T2-2 移出，不计入** |
   | `ThucydidesStepsListenerAdapter` | 42 | 逐个审查 |
   | `PlaywrightListener` | 24 | 逐个审查 |
   | `PlaywrightManager` | 21 | 逐个审查 |
   | `RouteEngine` | 19 | T2-4 拆分时一并处理 |
   | `SummaryReportGenerator` | 18 | T2-8 一并处理 |
   | `SerenityBasePage` / `BasePage` | 14+14 | T2-3 拆分时一并处理 |
   | `RouteUtil` / `PlaywrightScreenshotManager` | 13+13 | 逐个审查 |
3. **分类处置原则**：
   - 顶层事件监听器（Listener）中吞异常可能合理（防止一个监听器的异常中断测试），但**必须记录并上报**，不能静默
   - 业务路径中的宽泛 catch 应改为精确捕获或重新抛出
   - 确需吞掉的，加注释说明原因与后果
4. **重点排查「只 log 不抛」会不会导致测试假绿**——这是测试框架最致命的问题

**验收标准**
- 10 个异常全部继承自 `FrameworkException`
- 宽泛 catch 从 514 降至 ≤120
- 所有吞掉的异常都有注释说明与日志级别合理性
- Checkstyle `EmptyCatchBlock` 规则为 hard-fail
- 构造一个「元素不存在」的用例，断言其抛出 `ElementNotFoundException` 且能被 `catch (FrameworkException)` 捕获

**风险与回退**：中。改变异常类型可能影响下游 catch。缓解：分批改，每批一个 PR。

---

### T2-7　删除自研 JSONPath，改用 json-path　【P2】

| 项 | 内容 |
|---|---|
| **目标** | 消除重复造轮子 |
| **现状证据** | 已引入 `serenity-rest-assured`（`pom.xml:134`）与 `com.jayway.jsonpath:json-path`（`:169-172`），却在 `ModifyHandler` 自研 JSONPath：`parseWildcardPath:1379`、`applyWildcardRecursive:1591`、`evalCondition:1165`、`convertToMatchingType:695` |
| **工作量** | 5 人日 |
| **依赖** | T2-4（建议与 ModifyHandler 一起改，或先于 T2-4） |

**执行步骤**
1. 梳理自研实现的全部能力点（通配符、条件表达式、类型转换）
2. 用 Jayway JsonPath 的 API 逐个替代，能力缺口用 `Option` 或自定义 `JsonProvider` 补齐
3. 为自研实现先补契约测试（**保留旧行为作为对照**），再切换，最后删除旧实现
4. 若 Jayway 无法满足，评估引入 JsonSmart 或保留最小自研内核（需写 ADR 说明理由）

**验收标准**：`ModifyHandler` 中无自研解析逻辑；切换前后行为测试全部通过；`ModifyHandler` 行数下降 >30%。

**风险与回退**：中。行为差异风险高——**必须先补契约测试再切换**。

---

### T2-8　报告生成改模板引擎　【P2】

| 项 | 内容 |
|---|---|
| **目标** | 消除 2,184 行中的 66 处 HTML/CSS 硬编码拼接 |
| **工作量** | 4 人日 |
| **依赖** | T2-1 第 3 步（report 模块独立） |

**执行步骤**
1. 引入 Freemarker 或 Thymeleaf
2. 把 `SummaryReportGenerator` 中的 HTML/CSS/JS 抽取为模板文件（放 `src/main/resources/templates/`）
3. Java 侧只负责数据模型组装
4. 保留现有输出格式契约（HTML / CSV / ZIP 三种产物、邮件/通知链接生成逻辑）

**验收标准**：Java 代码中无 HTML 字符串拼接；输出产物与改造前逐字节比对一致（或差异已确认可接受）；模板可独立修改无需改 Java 代码。

**风险与回退**：低。

---

**Phase 2 小计：8 个任务 / 55 人日**

---

## Phase 3　并行化（第 15~24 周，45 人日）

> 目标：打开并行执行能力，把回归时长压到 40% 以下。
> **这是技术难度最高、但商业价值最大的阶段。**

---

### T3-1　引入 TestContext，收敛 33+ 个 static ThreadLocal　【P0 / 本阶段前置】

| 项 | 内容 |
|---|---|
| **目标** | 把全局静态状态收敛进 per-scenario 上下文对象，为并行扫清障碍 |
| **现状证据** | 16 个文件 33+ 处 `private static ThreadLocal`：BasePage(3)、PlaywrightListener(10)、AxeCoreScanner(3)、ApiCaptureContext(1)、SessionManager(3)、DatabaseUtil(2)、NLSUtils(1)、BrowserStackManager(1)、AutoBrowserProcessor(1)、BrowserOverrideManager(2)、PageObjectFactory(1)、TestServices(1) |
| **工作量** | 12 人日 |
| **依赖** | T2-3（BasePage 拆分后接口更清晰）、T2-1（framework-core 模块已建立） |

**执行步骤**
1. 在 `framework-core` 定义 `TestContext` 接口：承载 browser / context / page / frame 栈 / shadow 栈 / NLS 语言 / 会话 key / 截图缓冲 / 监听器状态
2. 先做**收拢**而非删除：把所有 `static ThreadLocal` 的读写改走 `TestContext.get()` / `.set()`，行为不变，逐个 PR
3. 实现 `ThreadLocalTestContext`（默认），预留 `ScopedTestContext`（为将来虚拟线程/Structured Concurrency）
4. **修复清理缺口**（重点）：
   - `clearAllThreadLocals()` 唯一调用点在 `PlaywrightManager.java:894` 且被 `if (context != null)` 包裹（`:889`）
   - feature 模式 + session 恢复路径（`PlaywrightSerenityBridge.java:375-380`）**不走 `closeContext()`** → `currentFrame` / `currentShadow` 跨 scenario 残留，持有已关闭 Page 的 Frame，**这是真实泄漏**
   - 改为 AOP/监听器统一在 scenario 结束时清理，不依赖调用方自觉
5. `TestContext` 通过构造注入传递给各组件，逐步消灭 `getInstance()` 单例（现 14 个）

**验收标准**
- `grep -c "private static.*ThreadLocal" src/main/java` ≤ 5（且集中在一处）
- scenario 结束时所有上下文被清理，有测试断言「scenario A 结束后 A 的状态不可见」
- 14 个 `getInstance()` 单例降至 ≤4
- `PageObjectFactory:175` 的 static `singleInstances` Map 改为 context 级

**风险与回退**：**高**。改动面横跨 16 个文件，且多线程问题难以在单线程测试中暴露。
缓解：
- 严格「先收拢后删除」，每步保持行为不变
- 每收拢一处立即补并发测试（`ExecutorService` + 多线程断言）
- 建议与 T2-3 交叉进行：BasePage 拆分时直接把状态改为注入

---

### T3-2　Browser 实例 per-thread / 池化　【P0】

| 项 | 内容 |
|---|---|
| **目标** | 消除跨线程共享 Browser 与全局锁串行化 |
| **现状证据** | `PlaywrightManager` 的 `browserInstances` / `playwrightInstances` 是 static Map（`:47-48`），按 configId **跨线程共享同一 Browser**；`restartBrowser()`(`:952`) 与 `handleBrowserTypeSwitch()`(`:594`) 是全局操作，注释自认「会杀掉其它场景的浏览器」；`getContext()`(`:679`) / `getPage()`(`:801`) 用 static `CONTEXT_LOCK` / `PAGE_LOCK`，锁序仅靠注释维护（`:590-592`） |
| **工作量** | 10 人日 |
| **依赖** | T3-1 |

**执行步骤**
1. Browser 生命周期绑定到 `TestContext`（per-thread 或 per-worker）
2. 设计 Browser 池：区分「可共享 Browser + 独立 Context」与「完全独立 Browser」两种模式，按 configId 与 browserType 分池
3. 消除 `CONTEXT_LOCK` / `PAGE_LOCK` 全局锁，改为 per-context 锁或无锁（利用 ThreadLocal + 单线程 owner 约束）
4. `restartBrowser` / `handleBrowserTypeSwitch` 的语义从「全局杀」改为「当前上下文重启」
5. 补齐锁序文档（或干脆消除多锁）

**验收标准**：4 个线程并发跑 4 个 scenario，各自持有独立 Browser/Context，互不干扰；无全局锁竞争；无跨线程共享可变状态。

**风险与回退**：高。资源管理不当会导致浏览器进程泄漏。缓解：加资源泄漏检测测试（结束后断言进程数归零）。

---

### T3-3　修复 ThreadLocal 清理缺口与 RouteDsl unbind 契约　【P1】

| 项 | 内容 |
|---|---|
| **目标** | 堵住已确认的真实泄漏 |
| **工作量** | 5 人日 |
| **依赖** | T3-1、T2-5 |

**执行步骤**
1. `PlaywrightManager:889-894` 的清理逻辑从 `if (context != null)` 中解耦，改为无条件执行
2. feature 模式 + session 恢复路径（`PlaywrightSerenityBridge:375-380`）补上清理调用
3. `RouteDsl.on` 改为 `AutoCloseable`，配合 try-with-resources 保证 unbind（与 T2-5 第 3 步合并）
4. 移除 `ApiCaptureContext` 的 `WeakReference`（T2-5）
5. 补「跨 scenario 状态残留」的回归测试

**验收标准**：连续跑 2 个 scenario，第二个 scenario 开始时断言前一个的所有状态已清空；测试通过。

**风险与回退**：中。移除 WeakReference 会暴露原本被 GC 掩盖的失败（见 T2-5 风险）。

---

### T3-4　打开并行执行并验证　【P0 / 本阶段目标】

| 项 | 内容 |
|---|---|
| **目标** | 真正跑起来并量化收益 |
| **现状** | `serenity.properties`(289 行) 与 `serenity.conf` 中**无任何 parallel / thread 配置** |
| **工作量** | 8 人日 |
| **依赖** | T3-1、T3-2、T3-3（全部完成） |

**执行步骤**
1. 配置 Serenity 并行：`serenity.batch.count` / `serenity.batch.strategy` + `serenity.fork.number` 或使用 JUnit 4 `ParallelComputer`
2. **从 2 workers 起步**，逐步加到 4
3. 用 `route-demo-web` 构建并行回归基准集
4. 逐项验证并行安全性：
   - 截图归属（PlaywrightListener 的 `currentStepScreenshots` ThreadLocal）
   - 报告聚合（Serenity 报告是否串场景）
   - NLS 语言覆盖（现有 `NlsUtilsCrossThreadTest` 可作为起点扩展）
   - a11y 扫描结果收集（`AxeCoreScanner` 的 3 个 ThreadLocal）
5. 度量：回归总时长、失败率、资源占用

**验收标准**：4 workers 下全量回归通过；回归时长 ≤ 0.4 × 基线；无 flaky 用例增长；报告正确归属每个 scenario。

**风险与回退**：**高**。并行会暴露所有隐藏的状态共享问题。
缓解：
- 先灰度：只对 `@route` 标签用例开并行（现 `CucumberTestRunnerIT` 已设 `tags = "@route"`），稳定后再全量
- 保留串行开关，出问题可一键回退
- 预留 2 周缓冲处理暴露出的并行缺陷

---

### T3-5　引入 PageDriver 接口层　【P2】

| 项 | 内容 |
|---|---|
| **目标** | 收回泄漏到 public API 的 Playwright 类型 |
| **现状证据** | page 包 interface 数 = 0；Playwright 类型（`Page`/`Locator`/`Frame`/`BoundingBox`/`Cookie`/`AriaRole`）出现在 45+ 处 public 方法签名；`base/impl/SerenityBasePage.java` 是第二个抽象基类而非接口实现（命名误导） |
| **工作量** | 10 人日 |
| **依赖** | T2-3、T3-1 |

**执行步骤**
1. 定义 `PageDriver` / `ElementDriver` 接口族，抽象出 navigate / find / click / type / wait / screenshot 等能力
2. 提供 `PlaywrightPageDriver` 实现
3. Page Object 层的所有组件（T2-3 产出）依赖接口而非 Playwright 具体类型
4. `public` API 中不再出现 `com.microsoft.playwright.*` 类型（底层实现内部可用）
5. 修正 `base/impl/` 包命名
6. 解锁 ArchUnit 规则 6 为 hard-fail

**验收标准**：`grep -rn "com.microsoft.playwright" src/main/java/**/page/**` 仅出现在 impl 包；ArchUnit 规则 6 生效；全量测试通过。

**风险与回退**：中。接口设计不当会导致抽象泄漏或过度抽象。缓解：先只抽象最高频的 20 个方法，其余保持，渐进推进。

---

**Phase 3 小计：5 个任务 / 45 人日**

---

## Phase 4　治理（第 25~28 周，14 人日）

> 目标：让文档与代码同步、让合规可配置、让审计记录回到该在的地方。

---

### T4-1　审计标记迁出代码　【P3 / 可读性】

| 项 | 内容 |
|---|---|
| **目标** | 清除 574 处 ⭐ + 324 处「修复 Pn-xx」噪音 |
| **工作量** | 3 人日 |
| **依赖** | T2 完成（避免清理后又产生新的） |

**执行步骤**
1. 提取全部 ⭐ / 「修复 Pn-xx」注释，生成清单
2. 分类：
   - **已修复且信息已在代码中体现** → 直接删注释
   - **记录设计决策** → 迁入 ADR（`docs/adr/NNNN-*.md`）
   - **遗留 TODO** → 转为 issue/GitHub Issue
3. 修正矛盾注释：`RouteEngine:59-77` 悬空 Javadoc、`:123` vs `:126` 异步池矛盾、`FrameworkState:24-29`「当前无调用点所以不修」
4. 建立规范：**commit message 记录「为什么」，代码注释只记录「是什么」与「为什么这样写会产生 bug」**

**验收标准**：`grep -c "⭐" src/main/java` = 0；ADR 目录建立；矛盾注释已修正。

**风险与回退**：低。**注意**：清理前确保信息已迁移，否则丢失历史决策依据。

---

### T4-2　脱敏规则可配置 + 值级识别　【P0 / 合规】

| 项 | 内容 |
|---|---|
| **目标** | 满足金融行业多市场合规要求 |
| **现状** | `SensitiveDataSanitizer:52-95` 两个 `static final Set` 硬编码，改字段名要发版；仅字段名白名单，无 PAN(Luhn)/IBAN/HKID 值级识别；`:534` 掩码输出 `(len=N)` 泄漏长度 |
| **工作量** | 5 人日 |
| **依赖** | T0-2（已有测试护栏） |

**执行步骤**
1. 字段名规则外置到配置文件（支持 `application.conf` 或独立 `sanitize-rules.conf`），支持不同市场 profile
2. 增加值级识别器（策略模式）：
   - PAN（Luhn 校验，13-19 位）
   - IBAN（ISO 13616 校验位）
   - HKID（香港身份证校验位）
   - 信用卡轨道数据
3. 修 `maskValue:534`：不再输出 `(len=N)`，改为定长掩码
4. 增加 SPI 扩展点，允许业务方注册自定义识别器
5. 全部配套单元测试

**验收标准**：新增字段无需改代码；Luhn/IBAN/HKID 样例 100% 识别；掩码不泄漏长度；测试覆盖 ≥90%。

**风险与回退**：中。值级识别有误报风险（如订单号恰巧通过 Luhn）——需提供豁免名单机制。

---

### T4-3　配置源收敛　【P2】

| 项 | 内容 |
|---|---|
| **目标** | 8 个配置入口收敛为单一优先级权威 |
| **现状** | `serenity.properties`(68 键 / 8 组) + `browserstack.conf` + `serenity.conf` + `config/application.conf` + `config/routedemo.conf` + `api-monitor-config.json` + typesafe config + System properties |
| **工作量** | 4 人日 |
| **依赖** | T2-1（多模块后各模块配置归属清晰） |

**执行步骤**
1. 绘制配置源清单与优先级矩阵，写入 ADR
2. 建立单一 `ConfigurationResolver`，明确优先级（建议：System props > 环境变量 > 环境 profile > 默认文件）
3. 启动期 fail-fast：必填项缺失直接抛 `ConfigurationException`，带明确错误信息
4. 敏感配置（密码/token/DB 串）统一走环境变量或密钥管理，禁止入文件
5. 提供 `mvn exec:java` 或配置项 dump 工具，便于排障

**验收标准**：配置优先级有文档且有测试；缺失必填项时快速失败并给出可操作错误信息；无敏感配置明文入库。

**风险与回退**：中。配置变更影响面广，需灰度。

---

### T4-4　文档防漂移　【P3】

| 项 | 内容 |
|---|---|
| **目标** | 消除 README 与 pom 的版本漂移 |
| **现状** | README 声称 Spring 6.1.6 / Logback 1.5.6，pom 实际 6.2.19 / 1.5.34；pom 注释称「384 个用例」实际 71 个 |
| **工作量** | 2 人日 |
| **依赖** | 无 |

**执行步骤**
1. README 的技术栈表改为从 pom 属性自动生成（可用 `maven-resources-plugin` filtering 或 CI 脚本校验）
2. CI 加一个 job：校验 README 中的版本号与 pom 一致，不一致则失败
3. 修正 pom 中「384 个用例」注释
4. 补充 `CONTRIBUTING.md` 与架构守护说明（告诉贡献者 ArchUnit 规则是什么、为什么）

**验收标准**：CI 有版本一致性校验；README 表述与实际一致。

**风险与回退**：低。

---

**Phase 4 小计：4 个任务 / 14 人日**

---

## 第三部分　排期与甘特

### 3.1 里程碑

| 里程碑 | 时点 | 交付物 | 判定标准 |
|---|---|---|---|
| **M0 止血完成** | 第 2 周 | 可安全共享的代码库 | 合规缺口已修；空目录已清；`git status` 干净 |
| **M1 门禁就位** | 第 6 周 | CI 质量门禁 | 7 个插件生效；PR 需全绿；ArchUnit 冻结存量违规 |
| **M2 模块拆分完成** | 第 12 周 | 多模块工程 | 6 个 Maven 模块；`framework-api` 零 web 依赖 |
| **M3 上帝类拆分完成** | 第 14 周 | 可维护的核心层 | 无 >1000 行类；BasePage <300 行；异常体系统一 |
| **M4 并行能力上线** | 第 24 周 | 并行执行 | 4 workers 全绿；回归时长 ≤0.4T |
| **M5 治理完成** | 第 28 周 | 可演进的框架 | 审计标记归零；脱敏可配置；配置单一权威 |

### 3.2 关键路径与并行建议

```
关键路径（不可压缩）：
T1-6 ArchUnit ──► T2-1 多模块 ──► T3-1 TestContext ──► T3-4 并行验证
```

**可并行分组（3 人配置示例）**

| 人员 | 第 1-2 周 | 第 3-6 周 | 第 7-14 周 | 第 15-24 周 | 第 25-28 周 |
|---|---|---|---|---|---|
| **A 工程效能** | T0-3, T0-6, T0-7 | T1-1~T1-5, T1-8 | T2-1(协助), T2-8 | T1-9 持续 | T4-3, T4-4 |
| **B 核心框架** | T0-1, T0-2, T0-5 | T1-7, T1-9 | T2-1(主), T2-3, T2-6 | T3-1, T3-5 | T4-1 |
| **C 能力层** | T0-4 | T1-6（与 B 结对） | T2-2, T2-4, T2-5, T2-7 | T3-2, T3-3, T3-4 | T4-2 |

**依赖红线（违反会返工）**
1. `T1-6 ArchUnit` 必须早于 `T2-1` —— 否则拆分期间边界继续劣化
2. `T2-1` 必须早于所有 T2-x 重构 —— 先物理隔离再重构
3. **`T2-2 codegen 移出` 必须早于 `T2-6 异常审查`** —— 可直接省掉 137 处（27%）工作
4. `T2-3 BasePage 拆分` 与 `T3-1 TestContext` 应交叉进行 —— 拆分时直接改注入，避免二次返工
5. `T3-1 / T3-2 / T3-3` 必须全部完成才能做 `T3-4`
6. `T4-1 审计标记清理` 必须在 T2 之后 —— 否则清理完又产生新的

---

## 第四部分　风险登记册

| # | 风险 | 影响 | 概率 | 缓解措施 | 触发应急的条件 |
|---|---|---|---|---|---|
| R1 | T2-1 多模块拆分产生大量合并冲突 | 高 | 高 | 分 5 步、每步独立 PR、拆分期 merge 冻结窗口、每步前打 tag | 单步 revert 超过 2 次 |
| R2 | T3-4 并行暴露大量隐藏缺陷 | 高 | 高 | 先灰度 `@route` 标签；从 2 workers 起；保留串行开关；预留 2 周缓冲 | 并行失败率 > 串行 3 倍 |
| R3 | 门禁过严阻塞业务交付，团队绕过 | 高 | 中 | 渐进式启用；存量用 suppressions/freeze；新代码零容忍；定期评审规则 | 出现 `-Dcheckstyle.skip` 常态化 |
| R4 | 拆分 BasePage 破坏下游 Page Object | 高 | 中 | 保留 `@Deprecated` 委托方法 1~2 个迭代；golden test 对比行为 | 下游项目编译失败 |
| R5 | T2-5 移除 WeakReference 暴露隐藏失败 | 中 | 高 | 先补契约测试；预留排期处理暴露的缺陷 | 暴露缺陷 >10 个 |
| R6 | T2-7 替换 JSONPath 行为不一致 | 中 | 中 | 先补契约测试保留旧行为对照，再切换 | 切换后测试失败率上升 |
| R7 | T0-6 清理仓库误删未提交工作 | 高 | 中 | 执行前 `git add -A && git stash`；逐个确认目录内容 | — |
| R8 | 业务交付压力导致 Phase 1 被跳过 | **极高** | 中 | **向决策层明确：跳过 Phase 1 = Phase 2/3 成果会在 6 个月内退化** | 排期被压缩时 |
| R9 | Playwright/Serenity 版本升级引入不兼容 | 中 | 低 | 版本在 T2-1 后统一由 BOM 管理；升级单独立项 | — |
| R10 | 人力不足（<2 人）导致周期过长 | 中 | 中 | 优先保 Phase 0+1；Phase 2/3 按价值排序做 T2-1/T2-2/T3-1 | 实际投入 <1.5 人 |

---

## 第五部分　执行建议

### 5.1 立即可做（本周内，无需等待评审结论）

1. **T0-1 + T0-2**（2 人日）：合规缺口是当前唯一可能造成实际损害的问题，应最优先
2. **T0-6 的第一步**：先 `git stash` 备份，避免任何未提交工作丢失
3. **T0-3**（0.5 人日）：删空目录，零风险

### 5.2 需要决策层拍板的两件事

1. **Phase 1 是否保得住？** 4 周 / 26 人日的投入不产生任何业务可见功能。需要在立项时就与业务方达成共识：这不是「技术洁癖」，是防止后续 55+45 人日的重构成果在半年内归零。**代码里那 574 处 ⭐ 就是最好的论据。**
2. **T0-4 的 persistence 落在途与否？** 决定是删（1 人日）还是补全（5 人日）。需要需求方一句话确认。

### 5.3 过程中的度量节奏

| 频率 | 动作 |
|---|---|
| 每周 | 更新 jacoco 覆盖率趋势、ArchUnit 冻结违规数、未处理 P0/P1 数 |
| 每阶段末 | 重跑 `ARCHITECTURE_REVIEW.md` 的 12 维评分，对比基线 |
| 每里程碑 | 向干系人汇报：本阶段消除了什么、还剩什么、下阶段投入产出比 |

### 5.4 一句话总结

> **这个框架缺的不是能力，是止住能力流失的堤坝。先花 4 周建堤坝（Phase 1），再花 18 周疏浚河道（Phase 2/3），最后 4 周恢复生态（Phase 4）。顺序反了，28 周会白干。**

---

## 第六部分　状态看板（2026-08-31）

> 符号：✅ 已完成 ｜ 🔶 部分完成/收尾中 ｜ ⬜ 待办
> 与本评审基线相比，本轮已落地的并发/资源修复已在 T2-5 / T3-1 / T3-2 / T3-3 / T0-4 中扣除。

| 阶段 | 任务 | 状态 | 备注 |
|------|------|------|------|
| P0 | T0-1 requestUrl 脱敏收口 | ✅ 已完成 | commit 6b47c99（含回归测试 3 用例）|
| P0 | T0-2 SensitiveDataSanitizer 单测 | ⬜ 待办 | 合规件，≥12 用例 |
| P0 | T0-3 删 7 个空目录 | ⬜ 待办 | 先确认无未提交实现 |
| P0 | T0-4 persistence/Hikari 死代码 | 🔶 部分 | DatabaseUtil 已改；persistence+Hikari 删留待需求方拍板 |
| P0 | T0-5 E2E 移出 surefire | ⬜ 待办（可选）| 当前有意保留在 surefire（20s、有超时兜底）|
| P0 | T0-6 仓库卫生 | ⬜ 待办 | 提交未跟踪源码、清 1.txt/cp.txt/_tbtest/_verify_nls |
| P0 | T0-7 死 import/失效 workaround | ✅ 已完成 | BasePage:17 死 import 已删（commit 6b47c99）|
| P1 | T1-1~T1-9（门禁 7 件套）| ⬜ 待办 | **关键路径**，重构前必做 |
| P2 | T2-1 多模块 | ⬜ 待办 | 依赖 T1-6 |
| P2 | T2-2 codegen 移出热路径 | ⬜ 待办 | 先于 T2-6（省 137 处 catch）|
| P2 | T2-3 BasePage 拆分 | ⬜ 待办 | 已落地修复减轻部分风险 |
| P2 | T2-4 RouteEngine 拆分 | 🔶 部分 | 8 张 static Map 收敛已启动 |
| P2 | T2-5 ApiCaptureContext 拆分 | 🔶 部分 | 计数器/unbind 已做；WeakReference 移除待做 |
| P2 | T2-6 异常体系统一 | ⬜ 待办 | 依赖 T2-2 |
| P2 | T2-7 删自研 JSONPath | ⬜ 待办 | 先补契约测试 |
| P2 | T2-8 报告改模板引擎 | ⬜ 待办 | |
| P3 | T3-1 TestContext 收拢 ThreadLocal | 🔶 部分 | NLSUtils/AsyncPool/PageObjectFactory 已改；余 16 文件待收拢 |
| P3 | T3-2 Browser per-thread/池化 | 🔶 部分 | PlaywrightManager/BrowserStackManager 残留已修 |
| P3 | T3-3 ThreadLocal 清理/RouteDsl unbind | 🔶 部分 | unbind 契约已做；WeakReference/清理缺口待 T2-5 |
| P3 | T3-4 打开并行执行 | ⬜ 待办 | 依赖 T3-1/2/3 |
| P3 | T3-5 PageDriver 接口层 | ⬜ 待办 | |
| P4 | T4-1 审计标记迁出 | ⬜ 待办 | |
| P4 | T4-2 脱敏可配置+值级识别 | ⬜ 待办 | |
| P4 | T4-3 配置源收敛 | ⬜ 待办 | |
| P4 | T4-4 文档防漂移 | ⬜ 待办 | |

### 立即可做清单（本周，无需架构决策）
1. **T0-1 收尾（✅ 已完成）**：requestUrl 脱敏 + 回归测试。
2. **T0-7（✅ 已完成）**：删除 BasePage 死 import。
3. **T0-2**（1.5 人日）：为 SensitiveDataSanitizer 补 ≥12 单测（合规件回归保护）。
4. **T0-6 第一步**：`git stash` 备份后提交未跟踪源码、清理 `1.txt`/`cp.txt`/`_tbtest/`/`_verify_nls/`。
5. **T0-3**（0.5 人日）：删 7 个空目录（先确认无未提交实现）。
6. **决策 T0-4**：persistence/Hikari 落库是否在途 → 删（1 人日）或补全（5 人日）。
7. **Phase 1 立项**：T1-1~T1-9 七件套（约 26 人日）是后续重构成果不退化的唯一保险，优先级最高。
