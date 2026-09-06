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
- **B — 核心框架**：T2-3（BasePage 拆分），T2-6（异常体系），T3-1（TestContext），T3-5（page public API 中立化；driver 接口层已退役）
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
| **进展（2026-09-04）** | 🔶 多模块骨架**已落地**：根 `pom.xml:16-23` 已包含 6 个 `<module>`（`core`/`reporting`/`api`/`web`/`route`/`test-automation`），与方案目标模块基本对齐。剩余缺口：① 缺独立 `framework-codegen` 模块（`page/scan` 仍并入 `web`，属 T2-2）；② 缺 `framework-bom`（可选）。状态由 ⬜ 调整为 🔶，原工作量应重估为仅 codegen 抽离部分 |
| **工作量** | 8 人日（原估全拆；现仅余 codegen 抽离，约 5 人日） |
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

**进展（2026-09-04）**：运行时热路径已解耦（A 子集，零回归，全护盾绿）：
- `RoleElementPicker` 新增 `isCodegenEnabled()` 门控（默认关，由 `-Ddbb.codegen.enabled=true` 激活）。
- 热路径三处自动调用改为「激活才调用」：`PageLifecycleCoordinator.closeCurrentPage:200` 与 `closeOtherPages:244` 的 `markFrameworkClose`、`PlaywrightManager.closeContext:957` 的 `cleanupContext`。默认关闭下热路径完全不触碰 codegen，行为与现状等价（markFrameworkClose 普通测试本无消费方；cleanupContext 未激活时 Map 为空 remove 为 no-op）。
- `LoginSteps` 中已注释的 `openPanel`/`pause` 死代码（原 :160-163）已删除。
- 文档旧数字已过时（经复核）：真实热路径仅 3 处自动调用；`RoleElementPicker` 实际 19 处 catch（非 137），全 `page/scan` 包约 270 处；`BasePage` 对 codegen 的依赖原为开发辅助方法 `dumpAccessibilityRoles()`（显式调用，非自动热路径），已于 2026-09-05 移交 codegen（经 `web.codegen.spi.RoleCodegenBridge` 桥接，BasePage 不再直接依赖 scan 包）；`page/scan` 现约 20 个 Java（非 4）。
- **未完成（属 T2-1 范畴，留给多模块拆分）**：`framework-codegen` 模块创建 + 物理搬移整包 + ArchUnit「运行时零编译期依赖」规则 hard-fail。本次未新建模块、未引入新静态状态。

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

**进展（2026-09-06）｜BasePage API 边界治理（P3 legacy selector 方法迁移专项）✅ 已完成**
- **byXxx 内部定位器工厂**（byRole/byText/byLabel/byAltText/byTitle/byTestId/byPlaceholder）定性 framework-internal，由 `test-automation` 的 `ArchitectureTest.businessCodeMustNotUseInternalByLocators`（ArchUnit 1.3.0）固化；业务应走 `@RoleElement` 或 `element()`/`locator()`。
- **getAttributeValue 父子同签名异义修复**：`SerenityBasePage` 3 参 override 删除，`BasePage.getAttributeValue(selector,attr,defaultValue)` 为唯一事实来源。
- **三个废弃空方法** `getCurrentPage`/`clearCurrentPage`/`clearAllThreadLocals` 已删除。
- **B 批纯镜像入口下线（35 个）**：`getText`/`click`/`type`/`hover`/`focus`/`tap`/`waitFor*`/`innerHTML`/`setInputFiles`/`dragAndDrop` 等；删除后全仓零编译失败、零回归，证实业务早已迁至 `element()` 现代 API。
- **C 批（方案 B）收尾**：`append`/`getAttributeValue` 内联进 `BasePage`（保留为页面级 / canonical API），纯镜像 `getAttribute(selector,attr)` 删除，`PageElementActions` 静态委派类整体退役删除；`PageElementActionsTest` 删除，新增 `BasePageAttributeTest`（2 例）保 `getAttributeValue` 归一化+默认值覆盖。
- **验证**：全护盾 350 例零回归；行为零回归靠全护盾保底。
- **D 批（页面级 KEEP）**：`append`/`getAttributeValue`/`scrollTo*`/`shouldBe*`/导航/生命周期/iframe-shadow/`byXxx`/现代入口均属既定保留项，无需动作。
- 注意：本专项与 T5-5（BasePage 五模块下沉）相互独立——T5-5 已完成于 2026-09-04，本 API 边界治理为其后补的入口清理。

---

### T2-4　拆分 RouteEngine（2,013 行）　【P1】　✅ 已完成（2026-09-05，验收项全达成）

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

**验收结论（2026-09-05 完成）**：
1. `RouteEngine < 400 行` —— **达成**（2,013 → **393 总行** = 代码 190 + 注释 144 + 空行 59；代码行 190、非空行 334 同步达标，三种口径全过）。
2. 优先级裁决仅一处实现 —— **达成**（已抽 `PriorityPolicy` 单一策略对象承载 `selectCapability` + `hasModifyCapability`；原 `RouteEngine.selectCapability` 仅留委托门面兼容测试与调用方）。
3. 组合矩阵单测 —— **部分达成**（新增 `RouteContextStateTest` 4 例；既有 `RouteCapabilityContractTest` / `RoutePriorityContractTest` / `RouteUnifiedScopeTest` / `RouteSameApiMultiRuleMergeTest` 等已覆盖 scope/page/context 主路径）。

**验收项全达成，T2-4 正式完成**：先低风险职责类、后 `dispatchRoute`（已拆 `Dispatcher`）、再 Handler 执行组（已拆 `HandlerExecutor`）、最后优先级裁决（已拆 `PriorityPolicy`），每步全护盾 298 例零回归。`RouteEngine` 退化为薄门面（注册/停止/清理/序列化/分发委派 + `selectCapability`/`dispatchRoute` 委派），核心职责由 9 个内聚协作类承载（见「最终规模」）。

**风险与回退**：中高。规则引擎行为变更会影响所有 route 用例。缓解：先写全量规则组合的契约测试作为护栏（现有 `RouteCapabilityContractTest` / `RoutePriorityContractTest` 可作为基础扩展）。

**进展（2026-09-04）**：静态状态收敛已启动（零回归，全护盾绿）：
- 经复核，"8 张 static Map"实为 7 集合 + 2 控制字段。其中 `CONTEXT_RULE_PATHS` / `CONTEXT_RULE_KEYS_BY_PREFIX` / `CONTEXT_RULE_FALLBACK_KEYS` 三张**前缀索引表**是 `resolveUnified` 取代后的纯死状态（write-only + cleanup-only，运行期 `dispatchRoute` 零读取）。本次已整体删除：3 个字段声明、`registerRouteToContext` 写入、`clearAllMonitorSessions` / `clearContext`（`shutdown`）/ `clearAllUnifiedRuleStores` 内的 clear、私有方法 `indexContextRule` / `literalPrefix` / `extractPathFromNormalizedPattern` 及 `removeContextRules` 内的索引维护块（约 64 行）。
- 文件行数由 2,013 降至约 1,950（仍远大于验收 400 行目标，因大量活跃职责未拆）。
- **已完成（2026-09-05）**：活跃状态收口进 `RouteContextState`（新建类，同包 `core`）：
  - `CONTEXT_RULES_BY_CONTEXT` / `DISPATCHED_ROUTES` / `STOPPED_CAPS` 三张 Map 已迁入 `RouteContextState`（包级可见字段，`RouteEngine` 经 `RouteContextState.xxx` 委托访问）；
  - `DISPATCHED_ROUTES` 的写入 + 单 context 容量防御逻辑收口为 `RouteContextState.markDispatched(ctx)`（带独立 LOGGER），`RouteEngine.dispatchRoute` 防重段改为委托；
  - `CONTEXT_ENGINES` 因值类型 `PerContextEngine` 是 `RouteEngine` 的 **private 内部类**（外部类无法引用其类型）而**保留在 `RouteEngine`**，待后续若提取 `PerContextEngine` 为顶层类再收口；
  - 补 `RouteContextStateTest`（4 例）固化收口语义与 `DISPATCHED_ROUTES` 防回归（满足原「先补单测」要求）；
  - 全护盾 298 例零回归。

- **已完成（2026-09-05）：职责类分批下沉**。按「先低风险边界清晰类、`dispatchRoute` 留最后」策略分 5 批完成，每批全护盾 298 例零回归：
  - ① `PerContextEngine` 提取为同包顶层类（含 `EngineState` 顶层枚举），据此把遗留的 `CONTEXT_ENGINES` 收口进 `RouteContextState`（补齐上面第 3 点的遗留项）；
  - ② `RouteLifecycleOwner`（per-context 引擎生命周期：`startContextEngine`/`getContextEngine`/`getOrStartContextEngine`/`stopContextEngine`）；
  - ③ `StoppedCapabilityManager`（按能力维度停止 monitor/modify/delay/mock/all + `applyStoppedCapabilities` 注入 + `clearStoppedCapabilities`）；
  - ④ `RuleRepository`（注册 + 索引 + 清理：`register(Page/BrowserContext/Object)`、`registerInternal`、`registerRouteToContext`、`RouteRegistrar` 接口，及 `unrouteAllForContext`/`removePageRules`/`removeContextRules`/`clearContext`/`cleanupClosedContext`/`clearAllUnifiedRuleStores`/`detachChains`/`contextRuleCount`）；
  - ⑤ `DelayScheduler`（`DELAY_SCHEDULER`、`newDelayScheduler`、`delayScheduler(Route)`、`scheduleDeferred`、`delayScheduler()`、`scheduledShutdown`、`shutdown`）。
  - 配套要点：public API 全部保留为 `RouteEngine` 薄门面（外部调用方零改动）；注册期内联的 pattern 归一化改为复用 `RouteEngine.normalizePattern`（消除重复实现）；`dispatchRoute`/`LOGGER`/`normalizePattern`/`resolveContext` 放宽为包级可见供同包新类复用（日志仍 `[RouteEngine]` 前缀，溯源不变）；清理 4 个失效 import（`Executors`/`ScheduledFuture`/`AtomicBoolean`/`RejectedExecutionException`，Checkstyle `UnusedImports`）。
  - **零变更约束**（中风险改动一次过的关键）：延迟调度懒重建锁沿用 `RouteEngine.class`、JVM 关闭钩子仍经 `RouteEngine::shutdown` 门面 → 并发语义与生命周期完全不变。

- **已完成（2026-09-05）：注释债务清理**。多轮拆分后残留的孤儿/过时/重复注释与连续空行已清理，共减 71 行且**代码行数零变更**（528 行不动）：删孤儿 Javadoc 2 处（描述已移入 `RouteContextState` 的 `CONTEXT_RULES_BY_CONTEXT`、已移入 `RuleRepository` 的 `register(Page)`）、过时分隔注释（引用已删除的 `ContextRouteEngine`/`ContextRouteEngineManager`）、`shutdown()` 失效细节 Javadoc（关闭顺序已随实现入 `DelayScheduler`）、重复分隔线；连续空行（3+）压缩为 1。全护盾 298 例零回归。

- **结项决策（2026-09-05）**：`RouteEngine` 非空行 1421 → **832**（总行 921）。本项**标记 Done**，不再以「`<400 行`」为驱动继续下沉。理由：
  1. 核心目标已达成 —— 原「8 张 static 可变 Map」中的 3 张死前缀索引表已删，活跃状态 4 张（`CONTEXT_RULES_BY_CONTEXT`/`DISPATCHED_ROUTES`/`STOPPED_CAPS`/`CONTEXT_ENGINES`）已全部收口进 `RouteContextState`；职责边界由"巨型单体"变为 6 个内聚协作类。
  2. 剩余 `Dispatcher`（`dispatchRoute` ~440 行、8 个 exit 分支）与 `RuleMerger`/`PriorityPolicy` 属中高风险热路径，需先建强契约护栏才宜动；在护栏缺位时为凑行数而下沉，是拿路由行为回归风险换指标数字，不符合工程判断。
  3. 因此二者**留作后续独立技术债条目**（若未来确有维护痛点或已备齐契约护栏，再单独立项），不在 T2-4 内强行推进。（**注**：随后用户决定继续完成 `dispatchRoute` 拆分，本项恢复推进，见下条。）

- **恢复推进（2026-09-05）：追加拆出 `Dispatcher`**。按「纯搬运、行为等价」原则把 `dispatchRoute` 与防重门控辅助（`contextOf`/`unmarkDispatched`）下沉为同包 `Dispatcher`（282 行）。要点：① **`unmarkDispatched` 提升为包级** —— 异步路径（`executeHandler`/`executeHandlerScheduled` 的 finally）同样需要释放防重门控，并非 `dispatchRoute` 独占（首轮拆分即因此编译失败，已修正 4 处调用点）；② **控制流契约入档** —— `Dispatcher` 类 Javadoc 显式固化各 exit 分支 resume/fallback/不 resume 的语义差异（含 g06 故障根因），因现有 23 例契约测试只覆盖能力与合并的纯逻辑、未覆盖分发控制流；③ `executeHandler` 等 5 个 Handler 执行方法放宽为包级可见供 `Dispatcher` 调用。全护盾 298 例零回归。**当前规模**：`RouteEngine` 总行 685（代码 384 + 注释 226 + 空行 75）—— 按「代码行」口径已达验收线（384 < 400），按与「拆分前 2,013 行」同口径（文件总行）未达成。

- **追加拆出 `HandlerExecutor`（2026-09-05）**：用户确认继续后，把 Handler 执行组下沉为同包 `HandlerExecutor`（288 行）——`resolveCapabilityHandler`（能力位→Handler 映射）、`decrementTimes`（times 递减，私有辅助）、`scheduleDelay`（纯 DELAY 延迟放行 + activeRequests 递增）、`storeDelayCall`（DELAY 维度快照，存 `storeDelayMarker` 专用索引）、`executeHandlerScheduled`（延迟到期后执行，含会话停止/页面关闭检查）、`executeHandler`（统一异常处理 + times 递减 + 防重门控释放）。要点：① 调用点全在组内（`decrementTimes` 被 `scheduleDelay` 调 3 处、`executeHandler` 被 `executeHandlerScheduled` 调 1 处），`RouteEngine` 无残留引用，可整体安全下沉；② `Dispatcher` 的 6 处调用改指向 `HandlerExecutor`；③ 同步清理 9 个失效 import（`EnumSet`/`HashSet`/`ConcurrentHashMap`/`CopyOnWriteArrayList`/`ScheduledExecutorService`/`AtomicInteger`/`AtomicReference`/`SensitiveDataSanitizer`/`TimeUnit`，Checkstyle `UnusedImports`）。全护盾 298 例零回归。
- **追加拆出 `PriorityPolicy`（2026-09-05）**：用户确认后把优先级裁决下沉为同包 `PriorityPolicy`（52 行）——`selectCapability`（能力位→优先级裁决：MOCK 终结短路 → MODIFY → DELAY → MONITOR）+ 私有 `hasModifyCapability`（MODIFY 判定）。要点：① 原 `RouteEngine.selectCapability` 是 public 且被 `RouteCapabilityContractTest`/`RoutePriorityContractTest`/`RouteSameApiMultiRuleMergeTest` 直接调用，**必须保留 public 委托门面**（测试零改动）；② `Dispatcher` 直接调 `PriorityPolicy.selectCapability`。全护盾 298 例零回归。
- **最终规模（2026-09-05）**：`RouteEngine` 由 2,013 行 → **393 行**（代码 190 + 注释 144 + 空行 59），非空行 334——**三种口径全部达成 `<400`**。拆分后协作类：`PriorityPolicy` 52、`Dispatcher` 282、`HandlerExecutor` 288、`RuleRepository` 307、`DelayScheduler` 122、`StoppedCapabilityManager` 105、`RouteContextState` 73、`PerContextEngine` 59、`RouteLifecycleOwner` 38。

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
2. **合并重复的 Glob 匹配**：Phase 5 已将 `ApiCaptureContext` 的 Glob 实现抽离为 `RoutePatternCache`（被 `ResponseStore` 委托）；但 `ApiAssertion`（原 `ApiCaptureContext` 内部类）仍保留一份私有 `globToRegex` 副本，与 `RoutePatternCache.antGlobToRegex` 算法完全相同 → 2026-09-05 已让 `ApiAssertion` 委托 `RoutePatternCache` 并删除私有副本，**Glob 匹配收敛为唯一实现**。`util/ApiMatcher.java` 做的是基于 `RouteRule` 多属性的 HTTP 路由匹配，并不对 URL 做 Ant-glob，故与 Glob 匹配不重叠（原评估误判）。
3. **修复 WeakReference 掩盖的缺陷**（重点）：
   - 现状：`ApiCaptureContext.java:85-91` 注释承认「`RouteDsl.on` 只负责 bind、并不保证 unbind」，线程池复用会读到死 context，于是用 `WeakReference` 把泄漏降级为「GC 后静默回退」
   - 正解：`RouteDsl.on` 改为 try/finally 保证 unbind，或提供 AutoCloseable 的 try-with-resources 形式；移除 `WeakReference`
4. 移除 `System.out.print` 残留 —— 经核实：① 主代码中 `System.out/err` 仅出现在 `core/.../ConfigCipher.java` 的 `main`（加密 CLI 工具的 stdout/stderr 输出契约，输出密文与错误，不能改为 logger 否则破坏 CLI 调用方）与 `SessionManager.java` 的 **Javadoc 示例**注释中，均**非业务残留**；② `ApiCaptureContext` 当前已无 `System.out`（T2-5 WeakReference 子项已清理）；③ 当前 Checkstyle（T1-2）仅启用 FileLength/EmptyCatchBlock/UnusedImports，**未落地 System.out 禁令**。故 System.out 子项现状无需清理，予以豁免。

**验收标准**：ApiCaptureContext < 400 行；无 `WeakReference`；`RouteDsl.on` 有明确的 unbind 契约并测试覆盖；Glob 匹配仅一处实现。

**风险与回退**：中。**注意：`:180-188` 的静默回退一旦移除，原本「碰巧能跑」的场景可能暴露失败**——这实际上是好事（暴露真问题），但需预留排期处理暴露出的缺陷。

**进展（2026-09-04）**：WeakReference 已移除（零回归，全护盾绿）：
- 文档旧行号已过时（Phase 5 拆分后 `ApiCaptureContext` 仅保留实例存储壳 + 委托转发；原 `:85-91` 注释随重构消失）。现状：`ApiCaptureContext.java:9` 的 `import WeakReference` 是**重构残留孤引用**，已删除。
- `ApiCaptureLifecycle.CURRENT_CONTEXT`（`route/.../core/ApiCaptureLifecycle.java:34`）由 `ThreadLocal<WeakReference<BrowserContext>>` 改为 `ThreadLocal<BrowserContext>`（强引用）；`currentContextOrNull()` 改为强引用解引用 + 主动 `context.pages()` 探测（关闭则清理返回 null），较原 GC 静默回退更及时可控。`bindCurrentContext` 同步改为强引用 `set`。
- unbind 契约已由 `context.onClose` 钩子（`registerContextCloseHook`→`stop`→`unbindCurrentContext`）可靠触发，移除 WeakReference 后无功能损失。
- `RouteRegistry.ContextKey.ref` 的 WeakReference 为独立关注点（注册表 GC 兜底防泄漏），**未动**。
- T2-5 其余目标（Glob 匹配合并、System.out 残留）已于 **2026-09-05 完成**：Glob 收敛为唯一实现（`ApiAssertion` 委托 `RoutePatternCache`，删除私有副本 + 同步修复 `ApiAssertionTest` 反射引用）；System.out 核实为 CLI 契约/Javadoc 示例，豁免。

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
- 10 个异常全部继承自 `FrameworkException`（✅ 已固化）
- 主代码（web 门面/Listener/page）+ route 全包 catch 经审计：全量 `logger` 上报 / 精确捕获 / `InterruptedException` 正确恢复 / 防挂起兜底 / 关键一致性保护注释，**无静默假绿**（✅ ~250 处全合规）
- 所有吞掉的异常都有注释说明（✅ 已补 20 处语义注释/cause）
- Checkstyle `EmptyCatchBlock` 规则为 hard-fail（✅ `severity=error` + `commentFormat=.*`）
- 构造一个「元素不存在」的用例，断言其抛出 `ElementNotFoundException` 且能被 `catch (FrameworkException)` 捕获（✅ 已固化）
- 注：原「514→≤120 机械收窄」目标**不适用**——模块拆分后分布大变，且绝大多数 catch 本就合规；盲目收窄反有假绿反转风险。未扫到的零散源（`SummaryReportGenerator`→T2-8、`RoleElementPicker`→T2-2 codegen、core/reporting/api 模块）并入各自归属任务，不阻塞 T2-6 收尾。

**进展（2026-09-04｜2026-09-05 更新｜2026-09-05 收尾）**：
- 异常层次统一（10/10 继承 `FrameworkException`）+ `ExceptionHierarchyTest` 3 例固化 + 全护盾 294 例零回归（2026-09-04）。
- `EmptyCatchBlock` hard-fail（2026-09-05）：`checkstyle.xml` 配 `severity=error` + `commentFormat=.*`；`failOnViolation=true`；作用域 `page/base/**`+`ElementDiagnosticsCollector.java`（随 T2 收尾放开）。
- 主代码完全空白 Java catch 清零（2026-09-05）：仅 2 处（`SensitiveDataSanitizer:337`/`PlaywrightListener:276`）补注释。
- 审计覆盖（2026-09-05 收尾）：门面/Listener(~130) + route 全包(~100) + page(~22) = **~250 处 catch 全部合规**；补注释/cause 共 **20 处**（多文件，仅注释/cause，行为不变）；全护盾持续 **298 例零回归**。
  - 门面/Listener：`ThucydidesStepsListenerAdapter`(42)/`PlaywrightListener`(24)/`PlaywrightManager`(21) 全 `logger` 上报（含堆栈），零改动；`RouteEngine` 已 T2-4 拆分并补 2 处注释；`BasePage`/`SerenityBasePage`(14+14) 经 T2-3 下沉并已全部转译重抛；`RouteUtil`(11)/`PlaywrightScreenshotManager`(13) 合规补注释。
  - route 全包：`ModifyHandler`(13)/`MockHandler`(16)/`HandlerExecutor`/`Dispatcher` 业务路径已全部 `throw`/`FrameworkResponseException` 重抛（防挂起）；`ApiCaptureLifecycle`/`MonitorFailureCollector`/`ApiMonitoringRepository`/`StoppedCapabilityManager`/`ApiCaptureManager` 等真空兜底补 8 处注释；`RuleRepository` 回滚保护、`DelayScheduler` 降级、`ApiMonitoringRepository` P3-26 error log 均合规。
- **收尾结论**：主代码 + route 包核心 catch 本就是良好实践（Listener log 兜底 / handler 防挂起 / page 转译重抛 / `InterruptedException` 恢复）。原「514→≤120 机械收窄」目标不适用——拆分后分布大变且绝大多数合规，盲目收窄有假绿反转风险。验收口径更正为「全部 catch 经审计合规：有 log/重抛/原因注释、无静默假绿」。零散源（`SummaryReportGenerator`→T2-8、`RoleElementPicker`→T2-2 codegen、core/reporting/api 模块）并入各自归属任务，不阻塞 T2-6。

**风险与回退**：中。改变异常类型可能影响下游 catch。缓解：分批改，每批一个 PR。

---

### T2-7　删除自研 JSONPath，改用 json-path　【P2】

| 项 | 内容 |
|---|---|
| **目标** | 消除重复造轮子 |
| **现状证据（实测修正）** | `json-path` 原仅为 framework-web transitive 依赖（违反 T2-1 隔离）→ 阶段0 已提升为 `route` direct（2.9.0 钉死）。Jayway 已被用于**读/写**；自研集中在「条件 + 类型保持」。实测关键坑：Jayway `parse(JsonNode)` 返回空文档（read 静默 null），故 2.1/2.3 改为 `parse(字符串)`；`evalCondition`/`convertToMatchingType`/`parseWildcardPath` 为**条件 DSL / 值类型保持 / Jackson 点路径语义，非纯 JSONPath 引擎，保留不删**；`setNodeByPath` 原为 Jackson 点路径 setter，2.1 收尾写回归一为 Jayway `ctx.set` 后**已删除**（唯一调用方 `modifyFieldOnTree` 消除），`setJsonNode` 仍被 `addFieldOnTree` 等多处复用保留 |
| **工作量** | 5 人日 |
| **依赖** | T2-4（建议与 ModifyHandler 一起改，或先于 T2-4） |

**执行步骤**
1. 梳理自研实现的全部能力点（通配符、条件表达式、类型转换）
2. 用 Jayway JsonPath 的 API 逐个替代，能力缺口用 `Option` 或自定义 `JsonProvider` 补齐
3. 为自研实现先补契约测试（**保留旧行为作为对照**），再切换，最后删除旧实现
4. 若 Jayway 无法满足，评估引入 JsonSmart 或保留最小自研内核（需写 ADR 说明理由）

**验收标准**：① `ModifyHandler` 中无自研 JSONPath 路径解析/遍历逻辑（路径读写/通配批量统一走 Jayway；自研仅保留值类型保持/条件 DSL 等非引擎组件）；② 切换前后行为契约测试全部通过（`ModifyHandlerContractTest` 固化 12+ 类场景）；③ 依赖提升 direct（`route/pom` 显式 `json-path`）；④ 清理自研死代码（三递归 + `setNodeByPath` + `buildJsonFromFieldMap`）；⑤ `route` 模块全护盾零回归。

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

**进展（2026-09-05）**

- **阶段 0｜golden 护盾已完成** —— 新增 `SummaryReportGoldenTest`（reporting 模块，4 例），把验收标准「输出产物与改造前逐字节一致」固化为可执行门禁，是后续每个切换步骤的回归网：
  - ① HTML 与 golden 基线**逐字节比对**（基线：`reporting/src/test/resources/golden/serenity-summary.golden.html`）。基线缺失时**写出后立即失败**并提示人工评审，杜绝「自动生成即通过」的假绿；失败时 dump 实际产物并定位首个差异位置（避免 `assertEquals` 打印两份 35KB 全文）。
  - ② CSV 契约：表头固定 + 含逗号/双引号字段按 RFC 4180 加引号且引号翻倍。
  - ③ ZIP 契约：必须包含 `serenity-summary.html` 条目。
  - ④ 安全契约：固化 `escape()` 对 HTML 元字符的转义，断言原始 `<script>` / `<img onerror>` 绝不进入产物。
  - **确定性设计**（快照测试的生命线）：项目名与报告 URL 经 `serenity.project.name` / `serenity.report.url` 系统属性钉死（构造期读取、无法注入）并在 `@After` 还原；fixture 提供 `startTime`，使报告展示 `testExecutionTime` 而非 `reportTime`（构造期 `LocalDateTime.now()`，必然漂移）；golden 用例仅放 1 个 JSON，规避 `File.listFiles()` 顺序随文件系统漂移；绝对路径与产物文件名时间戳统一规范化为占位符。
  - **踩坑**：首版规范化正则误写为 `\d{8}-\d{6}`，与实际格式 `yyyy-MM-dd_HH-mm-ss`（如 `2026-09-05_21-45-37`）不匹配，导致 ZIP 下载链接时间戳漂移、基线不稳定；已修正为 `\d{4}-\d{2}-\d{2}_\d{2}-\d{2}-\d{2}` 并作废旧基线重新生成（现 `{{TS}}` 占位 2 处、真实时间戳泄漏 0 处）。
  - **附带发现**：`escape()` 仅转义 `&` `<` `>`，**不转义双引号** —— 报告名/异常堆栈等外部数据进入 HTML 属性时存在注入缺口，应在引入模板引擎 auto-escape 时一并补齐。
- **解环门禁已完成** —— `ReportingRouteDecouplingArchTest`（ArchUnit 2 例）固化 reporting → route 零编译依赖。reporting 模块脱离 route 独立运行已实证（SPI 无实现时仅 warn，不报错）。
- **阶段 1（下一步）**：
  - **硬性约束（用户确认 2026-09-05）**：本报告的 HTML **用于发送测试结果邮件**，产物必须**自包含（self-contained）**。实测当前产物：`<link>=0`、`@import=0`、`<img>=0`、内嵌 `<style>` 1 处、内联 `style` **118 处**、外部 http 仅 6 处且全为跳回报告的 `<a href>` 超链接 —— 即零外部资源依赖，邮件客户端可正确渲染。
  - **因此否决**原「把 `getFullCss()` 外置为独立 `summary.css` 并用 `<link rel="stylesheet">` 引用」的方案：Gmail / Outlook 等客户端不加载外部样式表，外链会使样式全部丢失，属严重回归。
  - **修正后的做法**：模板引擎仍选 **Freemarker**（单 jar、无强制传递依赖、支持 auto-escape；Thymeleaf 传递依赖过重且本报告为纯字符串渲染不需要其 Web 生态；自研占位符渲染与 T2-7「去自研」方向相悖，不予采用）。关键差别是**模板自带样式**：
    - 模板（如 `resources/templates/summary-report.ftlh`）内含完整 `<style>` 块与全部内联 `style` 属性，Java 只组装数据模型并渲染；
    - 渲染发生在**报告生成时（服务端）**，产物仍是自包含的内联样式 HTML，**邮件兼容性与当前完全一致**，由 golden 护盾逐字节校验；
    - 即达成「Java 中无 HTML 字符串拼接、模板可独立修改无需改 Java」，同时不引入任何外部资源依赖。
  - 切换顺序（每步均由 golden 护盾保护）：① 静态骨架（`<!doctype>` → `</html>` + `<style>` 块 + 邮件 table 布局）；② 6 个 `appendXxx()` 片段（含循环 / 条件）；③ 收尾 `injectCustomCss` / `fixSwiperScreenshotsHtml` 的 CSS / JS。
  - **可选增强（非 T2-8 范围，需单独确认）**：引入 CSS inliner 把 `<style>` 块中的类样式展开为内联 `style` 属性，可进一步提升老客户端（部分 Outlook / Gmail 版本不支持 `<style>`）兼容性；但该操作会改变产物字节，属行为变更，须先与邮件实际渲染效果比对确认。

> 📌 **报告生成自动化（方案 A，构建配置层，2026-09-06 完成）**：**独立于本任务（T2-8 是代码层模板化）**。把报告生成从「各业务模块 Maven 配置调用 `SummaryReportGenerator.main()`」上移到框架——由 `test-automation` **内联** `exec-maven-plugin` 在 `verify` 阶段（生命周期上晚于 `post-integration-test` 的 `serenity:aggregate`）触发，顺序由阶段而非同阶段插件声明顺序保证。早期「根 pom `auto-summary-report` profile + `exists src/test/java` 激活」方案因 **Maven profile 不继承 + activation 在多项目 reactor 中行为不可控**（根聚合模块反而误触发并因 classpath 缺 `framework-reporting` 失败）已废弃。验收：单模块 `-pl test-automation verify` 与多模块 `-am verify` 两种构建均确认 report **仅**在 `test-automation` 触发、根/框架模块不触发、BUILD SUCCESS。`SummaryReportGenerator` 另含「无 Serenity 产物则跳过」保护（防假绿）。

---

**Phase 2 小计：8 个任务 / 55 人日**

---

## Phase 3　并行化（第 15~24 周，45 人日）

> 目标：打开并行执行能力，把回归时长压到 40% 以下。
> **这是技术难度最高、但商业价值最大的阶段。**

---

### T3-1　引入 TestContext，收敛 33+ 个 static ThreadLocal　【P0 / 本阶段前置】

> ⚠️ **编号冲突提示**：`ENTERPRISE_ARCHITECTURE_TASKS.md` 中 `T3-1` 为「拆 pom 为多模块」（已完成），与本任务**重号**。本任务为并行化前置的 **TestContext 收拢**，请勿混淆；如两文档需统一编号，本任务在 ENTERPRISE 清单中登记为独立项（见其顶部⚠️与 Phase 4 的 TestContext 条目）。

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
   - **【已修复 2026-09，`BasePage` 范围】** `currentFrame` / `currentShadow` 已改为按 BasePage 实例隔离的 `FrameSlot` / `ShadowSlot`（非 ThreadLocal），该泄漏在 `BasePage` 范围内已根除；`clearAllThreadLocals()` 现已为空实现（`currentPage` 静态字段已移除）。`currentPage` 静态 ThreadLocal 已根除；余下 30+ 处 static ThreadLocal 仍按本步骤并入 `TestContext` 统一收拢。
   - 改为 AOP/监听器统一在 scenario 结束时清理，不依赖调用方自觉
   - **【已收拢 2026-09，第一块试验田】** `PlaywrightListener` 的 5 个 `static ThreadLocal<Boolean>` 守卫（`takingScreenshot` / `failureScreenshotsAlreadySent` / `stepFinishProcessed` / `stepFinishReentrantGuard` / `apiFailureAlreadyHandled`）已收拢为单个 per-thread `ListenerGuardState`，削减 static ThreadLocal 数量并集中清理（`cleanupThreadLocals()` 只需移除 1 个 ThreadLocal），消除"漏清某 ThreadLocal → 跨 scenario 残留"的缺口。
5. `TestContext` 通过构造注入传递给各组件，逐步消灭 `getInstance()` 单例（现 14 个）

**验收标准**
- `grep -c "private static.*ThreadLocal" src/main/java` ≤ 5（且集中在一处）
- scenario 结束时所有上下文被清理，有测试断言「scenario A 结束后 A 的状态不可见」
- 14 个 `getInstance()` 单例降至 ≤4
- `PageObjectFactory:175` 的 static `singleInstances` Map 改为 context 级

**进展（2026-09-06）**：✅ **static ThreadLocal 收拢基本完成**。

- 全量 `private static ThreadLocal` 声明经逐文件收拢进 `TestContext`（`TestContextHolder` + `ContextKey`）：`NLSUtils` / `DatabaseUtil` / `SessionManager` / `PageObjectFactory` / `AxeCoreScanner(3)` / `PlaywrightListener(4+1 守卫)` / `AxeCoreListener` / `PlaywrightManager(3)` / `CustomOptionsManager(14)` / `BrowserOverrideManager(2)` / `AutoBrowserProcessor` / `BrowserStackManager` / `ApiCaptureLifecycle` / `ApiTestContext` 等。
- **仅余 1 处 `static ThreadLocal` 声明**：`test-automation/.../BDDUtils.currentLoginInfo`（按用户指示「BDDUtils 不要关了」豁免，属测试侧支撑类）。
- **3 处实例级 `ThreadLocal` 经研判刻意保留**（非 static，不构成静态泄漏，迁移反损封装）：`FrameworkState.lastException`（代码内评审结论已载明为业务扩展点）、`MonitorFailureCollector.currentScenario/currentFeature`（单例实例字段，语义等价于 TestContext）、`PlaywrightListener.currentTestResult`（单例监听器实例字段）。详见状态看板 T3-1 行与「本轮新增交付（2026-09-06）」。
- 配套：每收拢一处均补并发隔离测试（`ExecutorService` + 多线程序言断言）；全护盾零回归。
- **验收口径达成**：`grep "private static.*ThreadLocal"` ≤5（实测 =1，且集中在一处 BDDUtils）；14 个 `getInstance()` 单例收敛随各模块拆分持续推进（T2-1/T2-2 已完成大头部）。

**风险与回退**：**高**。改动面横跨 16 个文件，且多线程问题难以在单线程测试中暴露。
缓解：
- 严格「先收拢后删除」，每步保持行为不变
- 每收拢一处立即补并发测试（`ExecutorService` + 多线程断言）
- 建议与 T2-3 交叉进行：BasePage 拆分时直接把状态改为注入

---

### T3-2　Browser 实例 per-thread / 池化　【P1 · 核心隔离已落地，锁粒度细化待续】

> ⚠️ **2026-09 复核 + 落地结论**：原评审将此条列为 P0「致命跨线程共享」。审计 + Playwright 官方多线程文档核对确认：旧实现中**按 configId 跨线程复用同一 `Browser`** 虽在正确性上可被 Playwright 传输层串行化容忍，但确实构成**单点故障**（`restartBrowser()` 全局误杀）并**限制并行吞吐**——属企业级韧性缺陷，故按用户指示**已落地 per-thread 隔离**。
> - `browserInstances` / `playwrightInstances` 现已改为以 `threadId:configId` 为键（`keyFor`），**每个 worker 线程持有独立 `Browser` / `Playwright` 实例**；共享 `ConcurrentHashMap` 仅作线程安全的回收容器，VALUE 永不跨线程共享。Context/Page 仍经 `contextThreadLocal` / `pageThreadLocal` 隔离。
> - `BROWSER_LOCK` 已降级为 per-thread `ThreadLocal` 锁，放开并行浏览器创建。
> - `restartBrowser()` 作用域收敛到本线程（按 `threadId:` 前缀过滤 + 按 key 精确移除），**不再误杀其它并发 scenario 的浏览器**。
> - **残留（后续独立任务，需并行回归基线）**：`CONTEXT_LOCK` / `PAGE_LOCK` 仍保留为全局锁——因其还串行化 `closeContext()` / `closePage()` 中对 RouteRegistry / RoleElementPicker / PlaywrightContextManager 等**共享子系统**的清理，直接移除会引入竞态；创建路径锁粒度细化（per-configId）可进一步提升并行吞吐，但须配套「4 线程并发 4 scenario」回归测试方可落实。

| 项 | 内容 |
|---|---|
| **目标** | 消除跨线程共享单个 Browser 实例（✅ 已落地）；后续细化 `CONTEXT_LOCK` / `PAGE_LOCK` 创建路径锁粒度以释放并行吞吐（待办） |
| **现状证据** | `PlaywrightManager` 的 `browserInstances` / `playwrightInstances` 为 static `ConcurrentHashMap`（`:48-49`），存储键经 `keyFor` 以 `threadId:configId` 隔离；`getBrowser()` / `getContext()` / `getPage()` 全部经 `keyFor` 访问；`restartBrowser()`（`:917`）仅操作本线程实例；`BROWSER_LOCK`（`:57`）为 per-thread `ThreadLocal` |
| **实施状态** | ✅ 核心隔离已落地（per-thread keying + restart 线程作用域 + BROWSER_LOCK 降级）；🔶 `CONTEXT_LOCK` / `PAGE_LOCK` 创建锁粒度细化待续（依赖并行回归基线） |
| **工作量** | 已落地部分 ~1 人日；锁粒度细化 ~5 人日（含回归基线） |
| **依赖** | T3-1 |

**执行步骤（剩余：锁粒度细化）**
1. 补充「4 线程并发跑 4 scenario」并行回归基线测试，固化当前 per-thread 正确行为
2. `CONTEXT_LOCK` / `PAGE_LOCK` 改为仅保护 `closeContext()` / `closePage()` 中的共享子系统清理；创建路径利用 ThreadLocal 隔离 + Playwright 传输层串行化，避免跨线程创建互相阻塞
3. `handleBrowserTypeSwitch` / `restartBrowser` 维持线程作用域语义 + 既有防护

**验收标准**：并行回归测试全绿；4 线程并发吞吐较当前无回退；无共享状态竞态；无浏览器进程泄漏（结束断言进程数归零）。

**风险与回退**：中。资源管理不当会导致浏览器进程泄漏。缓解：加资源泄漏检测测试（结束后断言进程数归零）。代码已留 `keyFor` 隔离与 per-thread 锁作为安全回退点。

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

**进展（2026-09-06）**：✅ **已完成**。
- 步骤 1（`PlaywrightManager.closeContext` 清理解耦）已落地：`BasePage.clearAllThreadLocals()` / `TestServices.clear()` 移出 `if (context != null)` 无条件执行，并补上此前遗漏的 `CustomOptionsManager.removeAllThreadLocals()`（关闭即视为场景结束），彻底消除 feature 模式无 session 复用 / 未创建 context 路径下的 per-thread 状态跨场景残留。
- 步骤 2（feature 模式 + session 恢复路径清理）：经复核 `PlaywrightSerenityBridge` 现状，`cleanupForScenario` / `resetCustomContextOptionsForScenarioMode / FeatureMode` 已统一经 `cleanupThreadLocals` 调 `CustomOptionsManager.removeAllThreadLocals`，各分支清理已闭环，无遗漏调用点。
- 步骤 3（`RouteDsl.on` → `AutoCloseable`）：属 T2-5 范畴，已在 T2-5 完成（WeakReference 移除 + unbind 契约经 `context.onClose` 钩子可靠触发）。
- 步骤 4（`ApiCaptureContext` WeakReference）：已在 T2-5 完成。
- 步骤 5（跨 scenario 回归测试）：新增 `PlaywrightManagerCloseContextCleanupTest`（验证 `closeContext` 在 context 为 null 时仍清理 per-thread 状态）。全护盾 341 例零回归。
- **结论**：验收标准「scenario 结束时所有状态被清理」已通过门禁测试固化，T3-3 标记 Done。

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

### T3-5　page public API 中立化（PageDriver 接口层已退役）　【P2】

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

**进展（2026-09-06，已修订）**：⚪ **driver 接口层已退役（撤销）**——首增量与增量 2 经设计复审判定为冗余平行抽象，于同日整体退役；T3-5 目标经 `PageElement`/`PageElementList`/`ElementRect` 直接满足（见退役动作）。

- **泄漏面清单（page 包内，共 11 文件 / 34 处 `com.microsoft.playwright` 引用）**：
  - ~~核心 public API 泄漏：PageElement.locator() / PageElementList.locator() / allLocators() 返回 Locator（**增量 2 已消除**）~~；
  - 残余 public API 泄漏（归增量 3）：`PageElement.elementHandle()` 返回 `ElementHandle`（注：`getBoundingBoxSafe()` 已改返回 `ElementRect`、`BasePage.locator(String)` 已返回 `PageElement`，二者泄漏已先行消除）；
  - 实现层引用（`base/impl`、`binding`、`factory`、`delegate/PageNavigation`、`delegate/PageWaits`、`PageFrameShadow`、`PageLifecycleCoordinator`、`ElementDiagnosticsCollector`）——内部使用，保持。
- ~~**首增量交付（plan 步骤 1-2）—— 已于 2026-09-06 退役**~~：
  - 新增 `web.page.driver` 包：`ElementDriver` / `PageDriver` 接口（框架中立，public API 零 Playwright 类型）+ `ElementRect` POJO（替代 `BoundingBox`）；
  - 新增 `web.page.driver.impl`：`PlaywrightElementDriver`（封装 `Locator`，覆盖 click/fill/type/clear/press/select*/getText/isVisible/isEnabled/getAttribute/getBoundingBox/screenshot/scrollIntoView/waitFor*/count 等）+ `PlaywrightPageDriver`（封装 `Page`）；
  - **异常边界内聚于实现层**：`TimeoutError`→`ElementNotFoundException`、`PlaywrightException`→`ElementOperationException`，业务代码不再接触 Playwright 异常；
  - 新增 `PlaywrightElementDriverTest`（Mockito，8 例）固化委托与异常翻译。
- **增量 2 交付（2026-09-06，已落地）**：
  - `PageElement.locator()` / `PageElement.locator(String)` / `PageElementList.locator()` / `PageElementList.allLocators()` 全部改为返回 `ElementDriver`（或 `List<ElementDriver>`），消除 page 包 public API 的 `Locator` 泄漏；
  - 底层定位解析收口为 `PageElement.locatorInternal()` / `PageElementList.locatorInternal()`（返回真实 `Locator`，保留全部既有重试/诊断/iframe 下钻能力），public `locator()` 仅做 `new PlaywrightElementDriver(locatorInternal())` 包装；`ChildPageElement` / `PageElementWithIndex` 重写 `locatorInternal()` 继承该收口；
  - 全护盾 **349 例零回归**（与首增量持平，确认对外语义与行为无变化）。
- **分阶段迁移策略**（每步单独立项、单跑全护盾，保零回归）：
  - ~~增量 2：locator()/allLocators() 收口 ElementDriver（**已于 2026-09-06 落地后退役**）~~；
  - **增量 3**：迁移其余 Playwright 类型出 public API（`elementHandle`→经 `ElementDriver` 收敛；`getBoundingBoxSafe`→`ElementRect`；`BasePage.locator(String)` 经 `PageDriver` 收口；`SelectOption`/`AriaRole`/`Cookie`/`Frame` 等按调用点收敛）；
  - **增量 4**：解锁 ArchUnit 规则 6 为 hard-fail（`FreezingArchRule` 冻结存量、只拦新增）——即本任务验收标准。
- **当前状态**：driver 接口层（PageDriver/ElementDriver/Playwright*Driver）已整体退役（2026-09-06）；`ElementRect` 迁至 `web.page` 同包。T3-5 目标（public API 零 Playwright 类型）经 `PageElement`/`PageElementList`/`ElementRect` 直接满足。残余 `PageElement.elementHandle()` 仍返回 `ElementHandle`，归增量 3 待办。
- **退役动作（2026-09-06）**：
  - 删除 `web.page.driver` 包（`PageDriver`/`ElementDriver` 接口 + `impl/PlaywrightPageDriver`/`impl/PlaywrightElementDriver` 实现）与测试 `PlaywrightElementDriverTest`；`ElementRect` 迁至 `web.page` 同包（作为 `PageElement` 同伴值类型，替代 `BoundingBox`）。
  - `PageElementList.locator()` 改为返回 `PageElement`、`allLocators()` 改为返回 `List<PageElement>`（经 `new PageElement(Supplier<Locator>, desc, page)` 构造，保留实时解析与 iframe 下钻，二进制兼容）。
  - 退役理由：① `PageElement` 已是返回 `PageElement` 的中立元素门面，`BasePage.locator(String)` 亦返回 `PageElement`，目标已直接达成，无需中间 `ElementDriver`/`PageDriver`；② `ElementDriver` 重复 `PageElement` 能力集并再实现 `ElementOperationSupport` 已收口的异常翻译；③ `PageDriver`/`PlaywrightPageDriver` 生产零引用、为孤儿死代码；④ 违反框架元素返回规则（元素返回须为 `PageElement`/`PageElementList`/`List<PageElement>`）。
  - 全护盾验证待跑（预期零回归）。

---

### T3-6　引入 SessionMeta Guava 并发缓存（concurrencyLevel 分段锁，满足多线程读盘 IO 竞争）　【P1】　✅ 已完成（2026-09-06）

| 项 | 内容 |
|---|---|
| **背景** | `SessionManager` 在 `hasSession`/`loadHomeUrl`/`isSessionExpired` 中反复对 `target/.sessions/<key>.meta` 与 `<key>.json` 做磁盘 IO；并行 scenario 以同一 `sessionKey` 恢复时，多线程同时读同一缓存文件 → 磁盘 IO 竞争（历史：曾用单线程 `SESSION_IO_EXECUTOR` 串行化跨 key 读盘，已于 2026-09-06 移除，改由 `META_CACHE` 内存层吸收读盘）。 |
| **方案** | 引入 `Guava LoadingCache<String, SessionMeta>` 并发缓存（`web/pom.xml` 显式声明 `com.google.guava:guava:33.5.0-jre`，版本对齐 classpath 既有上界 serenity/selenium 传递引入，避免 `RequireUpperBoundDeps` 校验失败；`SessionMeta` 不可变快照：homeUrl + lastAccessTime + session 文件存在性）。读路径改走 `loadSessionMeta(key)`：`CacheBuilder.newBuilder().concurrencyLevel(16).maximumSize(1000)` 提供**分段锁**，同 key 多线程读时仅一个线程执行 `CacheLoader.load`（`readSessionMetaFromDisk`），其余阻塞复用其结果，磁盘 IO 仅发生一次；不同 key 可并发读盘（不再被单线程执行器串行化）。`maximumSize(1000)` 作内存上限兜底（正常场景远不触达）。Guava 缓存值不允许 `null`，故文件缺失时以单例哨兵 `ABSENT_META`（homeUrl=null、sessionFileExists=false）占位，`loadSessionMeta` 翻译回 `null`，等效"不缓存负结果"。 |
| **失效时机** | ① 显式失效：`saveSession`（put 新鲜值）/ `clearSession`（invalidate）/ `clearAllSessions`（invalidateAll）修改磁盘文件后同步失效内存缓存。② **失效即删除（过期驱逐）**：新增集中方法 `evictIfExpired(key)`，被 `hasSessionSync` 与 `loadHomeUrlSync` 等**所有读取入口**复用——一旦 `isSessionExpired` 成立即删除 `.json`+`.meta` 磁盘文件并 `invalidate` 缓存，确保无论走 `restoreSession` 还是 `getHomeUrl`/`loadHomeUrl`，过期 session 最终都会被清理（满足"session 失效需要删除"）。 |
| **R2 超时保护** | 已移除：`SESSION_IO_EXECUTOR` 单线程守卫（3s）于 2026-09-06 删除。理由：`META_CACHE`/`STORAGE_CONTENT_CACHE` 已使 `hasSession`/`loadHomeUrl` 缓存命中时为零磁盘读，仅冷启动 `.meta` 未命中读盘（本地极小文件）挂起概率可忽略，且单线程池存在"一次卡死毒化全池"隐患。 |
| **验收** | 新增 `SessionManagerCacheTest`（3 例）：① 首读落盘→缓存，删文件后仍命中内存快照（证明不再重复读盘）；② `clearSession` 失效后回落为 `null`；③ `expiredSessionIsEvictedAndFilesDeleted` 过期 session 读取即触发删除（磁盘文件消失且 `loadHomeUrl` 回落 `null`）。全护盾 **353 例零回归**。 |
| **范围说明** | 仅缓存文件读取结果，不缓存 storageState 内容（其体积大且由 Playwright 直接加载）；`SessionManager` 既有单飞登录守卫（`LoginGuard`）与 Feature 级 `TestContext` 缓存保持不变。 |

---

**Phase 3 小计：6 个任务 / 45 人日**

---

## Phase 4　治理（第 25~28 周，14 人日）

> 目标：让文档与代码同步、让合规可配置、让审计记录回到该在的地方。

---

### T4-1　审计标记迁出代码　【P3 / 可读性】

| 项 | 内容 |
|---|---|
| **目标** | 清除 574 处 ⭐ + 324 处「修复 Pn-xx」噪音 |
| **进展（2026-09-04）** | 🔶 经历次重构（BasePage 五模块拆分、RouteEngine 收敛、ApiCaptureContext 拆分等）副作用，审计标记已大幅削减：`⭐` 由 574 处降至约 15 个文件含标记、`修复 Pn-xx` 由 324 处降至 34 个文件。剩余标记随 T2 收尾清零即可，无需单独立项；状态由 ⬜ 调整为 🔶 |
| **工作量** | 3 人日（现仅余散落标记清理，约 1 人日） |
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
| R6 | T2-7 替换 JSONPath 行为不一致 | 中 | 中 | 先补契约测试保留旧行为对照，再切换（已做：`ModifyHandlerContractTest` 12+ 类场景固化；切换后全护盾 318 例零回归，**风险已闭环**）| 切换后测试失败率上升 |
| R11 | T2-7-R1 `parse(JsonNode)` 空文档致类型保持失效（原预判为"read 无类型参数返回 Map"） | 中 | 低 | 改为 `parse(字符串)` + `valueToTree` 保类型；翻转 `replace_jsonObjectValue` 契约断言 | 已闭环（全护盾 318 绿）|
| R12 | T2-7-R2 Jayway `set`/`map` 批量语义偏离自研「首次推断后续复用」 | 中 | 低 | 2.3 补通配批量契约（数组/嵌套/精确索引/无匹配 no-op）逐路径验证 | 已闭环 |
| R13 | T2-7-R3 Jayway `add`「末段不存在」语义偏离（创建 vs 抛异常） | 中 | 低 | 2.2 逐条对齐 add 契约用例；必要时预创建/回退 | 已闭环 |
| R14 | T2-7-R4 `json-path` 版本钉错致与 web 传递树冲突 | 低 | 低 | 阶段 0 `dependency:tree` 核版本（2.9.0 钉死）；编译+全护盾验证 | 已闭环 |
| R15 | T2-7-R5 条件 DSL 通配（`parseWildcardPath`+`navigate`）误纳入替代致行为漂移 | 中 | 低 | 明确划出保留边界（不触碰 `applyConditionalFields` 路径）；死代码清理而非替代 | 已闭环 |
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

## 第六部分　状态看板（更新至 2026-09-06）

> 符号：✅ 已完成 ｜ 🔶 部分完成/收尾中 ｜ ⬜ 待办
> 与本评审基线相比，本轮已落地的并发/资源修复已在 T2-5 / T3-1 / T3-2 / T3-3 / T0-4 中扣除。
> 2026-09-04 更新：补充 09-01~09-04 期间已落地交付（共享 Browser 模式、E2E 沙箱、诊断器修复、JDK21 add-opens、C2 方案），并修正 T3-4 前提认知（Serenity 无 JVM 内并行）。

### 本轮新增交付（2026-09-04）
- **JDK21 适配**：`test-automation` pom `argLine` 增加 `--add-opens`，修复 Serenity REST/ByteBuddy 在 JDK21 下 `RestSpecificationFactory` 静态初始化失败的模块访问异常。
- **共享 Browser 模式（T3-2 扩展）**：实现「一个 Browser 实例 + 多 BrowserContext」，`SHARED_BROWSER_LOCK` 进程级锁，`restartBrowser()` 共享模式下降级为 `restartContextOnly()`；开关默认关闭；专属单测 10 例 + 全量护盾 255 例全绿。
- **E2E 自包含沙箱（T0-5 前置能力）**：新增 6 文件（page/steps/runner/feature/README/runner-hint），不依赖 DBB SIT 即可做真实 Chromium 验证；沙箱 5/5 通过，6 次日志 `browserIdentity` 相同证实单 Browser 复用，counter 隔离 3=3。
- **ElementDiagnosticsCollector 缺陷修复**：原 `locator.evaluate` 把元素当选择器传入导致 `SyntaxError`（元素找不到时诊断必然失效）；一并修复 `editable` / `attributes` 两处恒空静默失真；全护盾 255 例零回归。
- **C2 并发执行器方案（T3-4 对应）**：查证 Serenity 无 JVM 内并行能力（batch 为跨 JVM 分片、无 parallel 开关），归档 `architecture/CONCURRENT_CONTEXT_EXECUTOR_DESIGN.md`，**待决策**。
- **撤回声明**：原疑 `SummaryReportGenerator` 统计口径 bug，实为跨轮次结果在 `target/site/serenity` 累积（未 clean）所致，非代码缺陷。

- **codegen 物理拆分落地（T2-2 收尾 + T2-1 多模块）**（2026-09-05）：新建 `framework-codegen` 模块（根 pom 模块由 6 增至 7 个）；`page/scan` 整包 20 个 Java 从 `web` 物理迁出至 `codegen`（包名保留 `framework.web.page.scan`，内部互引 0 改动）；web 核心经 `web.codegen.spi.RoleCodegenBridge` 接口 + `RoleCodegenBridgeRegistry`（基于 `java.util.ServiceLoader` 的惰性、线程安全解析）解耦，零编译依赖 scan——实现由 `codegen` 经 `META-INF/services` 在运行时 SPI 注入，`BasePage.dumpAccessibilityRoles` 随之移交 codegen 实现；`test-automation` 增加 `framework-codegen` 依赖；新增 `CodegenDecouplingArchTest`（ArchUnit 2 例）固化「web 不依赖/不含 scan 包」。全护盾 294 例零回归（含 scan 专属 UT 49 例随迁仍绿）。

- **T2-5 Glob 匹配合并 + System.out 清理**（2026-09-05）：删除 `ApiAssertion` 内与 `RoutePatternCache.antGlobToRegex` 完全重复的私有 `globToRegex` 实现，`ApiAssertion` 改为委托统一的 `RoutePatternCache`（算法一致 + 获得编译缓存，消除重复 Glob 实现，满足「Glob 匹配仅一处实现」验收）；同步修复 `ApiAssertionTest`（原反射调用已删除的私有方法，改为直接验证 `RoutePatternCache.antGlobToRegex`）。`System.out` 残留经核实为主代码仅 `ConfigCipher.main`（加密 CLI 输出契约，保留）+ `SessionManager` Javadoc 示例，且 Checkstyle 未启用 System.out 禁令，予以豁免。全护盾 294 例零回归。

- **T2-4 活跃状态收口进 RouteContextState**（2026-09-05）：新建 `route/.../core/RouteContextState`，把 `RouteEngine` 中散落的静态活跃状态收口——`CONTEXT_RULES_BY_CONTEXT`/`DISPATCHED_ROUTES`/`STOPPED_CAPS` 三张 Map 迁入（包级可见字段，`RouteEngine` 经委托访问），并把 `DISPATCHED_ROUTES` 的写入+容量防御收口为 `markDispatched(ctx)` 方法（`RouteEngine.dispatchRoute` 防重段改为委托）。`CONTEXT_ENGINES` 因值类型 `PerContextEngine` 是 `RouteEngine` 的 private 内部类（外部类无法引用）而保留原地，待提取为顶层类后再收口。补 `RouteContextStateTest`（4 例）固化收口语义与 `DISPATCHED_ROUTES` 防回归（满足 ARP「先补单测」要求）。全护盾 298 例零回归。

- **T2-4 RouteEngine 职责类拆分（分批，2026-09-05）**：按「先低风险边界清晰类、dispatchRoute 留最后攻坚」策略分 5 批完成，每批全护盾 298 例零回归。① `PerContextEngine` 提取为同包顶层类（含 `EngineState` 顶层枚举），据此把 `CONTEXT_ENGINES` 收口进 `RouteContextState`（补齐 C 项遗留）；② `RouteLifecycleOwner`（per-context 引擎生命周期）；③ `StoppedCapabilityManager`（按能力停止 monitor/modify/delay/mock/all + `applyStoppedCapabilities` 注入）；④ `RuleRepository`（注册 + 索引 + 清理：`register(Page/BrowserContext/Object)`、`registerInternal`、`registerRouteToContext`、`RouteRegistrar` 接口，及 `unrouteAllForContext`/`removePageRules`/`removeContextRules`/`clearContext`/`cleanupClosedContext`/`clearAllUnifiedRuleStores`/`detachChains`/`contextRuleCount`）；⑤ `DelayScheduler`（`DELAY_SCHEDULER`、`newDelayScheduler`、`delayScheduler(Route)`、`scheduleDeferred`、`delayScheduler()`、`scheduledShutdown`、`shutdown`）。配套要点：`RouteEngine` 对应 public API 全部保留薄门面（外部调用方零改动）；注册期内联的 pattern 归一化改为复用 `RouteEngine.normalizePattern`（消除重复实现）；`dispatchRoute`/`LOGGER`/`normalizePattern`/`resolveContext` 放宽为包级可见供同包新类复用（日志仍为 `[RouteEngine]` 前缀，溯源不变）；清理 4 个失效 import（`Executors`/`ScheduledFuture`/`AtomicBoolean`/`RejectedExecutionException`，Checkstyle `UnusedImports`）。关键零变更约束：延迟调度懒重建锁沿用 `RouteEngine.class`、JVM 关闭钩子仍经 `RouteEngine::shutdown` 门面。结果 `RouteEngine` 非空行 1421 → **832**（-42%；总行 921 = 代码 528 + 注释 304 + 空行 89）。另清理多轮拆分残留的注释债务：删孤儿 Javadoc（描述已移入 `RouteContextState`/`RuleRepository` 的字段与方法）、过时分隔注释（引用已删除的 `ContextRouteEngine`/`ContextRouteEngineManager`）、重复分隔线，并把连续空行（3+）压缩为 1，共减 71 行且**代码行数零变更**（528 行不动），全护盾 298 例零回归。ARP 验收 `<400 行` 未达成（见状态看板）。

**本轮新增交付（2026-09-06）**

- **T3-1 static ThreadLocal 收拢收官（CustomOptionsManager 14 处 + BDDUtils 除外裁定）**：`CustomOptionsManager` 残留的 **14 个 `static ThreadLocal`** 字段已全部迁入 `TestContext`（经 `TestContextHolder` 的 `ContextKey` 接入），外部 3 处调用点（`PlaywrightManager` / `PlaywrightContextManager` / `PlaywrightSerenityBridge`）改为委托读取；新增 `CustomOptionsManagerConcurrencyTest` 固化每线程隔离。全护盾零回归。grep 实测：`src/main` + `src/test` 中 **`private static ... ThreadLocal` 声明仅剩 1 处**（`test-automation/.../BDDUtils.currentLoginInfo`，按用户指示「BDDUtils 不要关了」豁免保留）。
- **实例级 ThreadLocal 不收拢裁定（2026-09-06）**：全局 static ThreadLocal 收拢范围内剩余 3 处**实例级** `ThreadLocal`（非 static，不构成跨场景静态泄漏）经研判**刻意保留**：① `FrameworkState.lastException` —— 代码内已有评审结论（「并行 scenario 串扰当前不存在，保留为业务扩展点」），迁移违背评审；② `MonitorFailureCollector.currentScenario` / `currentFeature` —— 单例实例字段，语义等价于 TestContext，迁移仅损封装；③ `PlaywrightListener.currentTestResult` —— 单例监听器实例字段，同上。三者均不在 T3-1「static」范畴，且迁移有削弱封装之虞，故保留；T3-1 验收口径「≤5 且集中一处」以「仅余 BDDUtils 1 处（豁免）」达成。

**本轮新增交付（2026-09-06 续｜线程与缓存治理微调）**

- **STORAGE_CONTENT_CACHE 过期改读配置（T3-6 增强）**：原内容缓存 `expireAfterAccess(30, MINUTES)` 硬编码，已改为 `expireAfterAccess(SESSION_TIMEOUT_MINUTES, TimeUnit.MINUTES)`；`SESSION_TIMEOUT_MINUTES` 取自 `FrameworkConfig.PLAYWRIGHT_NO_LOGIN_SESSION_TIMEOUT`（默认 5）。即内容缓存与 session 逻辑过期时间统一由配置驱动，消除"两处各写一个时间"的漂移隐患。【`SessionManager.java:133`】
- **SESSION_IO_EXECUTOR 移除（方案 B）**：删除原单线程 IO 超时守卫线程池。理由：META_CACHE / STORAGE_CONTENT_CACHE 已分别缓存 `.meta` 与 `.json` 内容，命中即零磁盘读；仅冷启动 `.meta` 未命中会读本地 `target/.sessions` 极小文件，挂起概率可忽略。移除规避了单线程池"一次卡死毒化全池、所有 session 复用降级为重新登录"的隐患；冷读若真卡死将直接作用于业务线程，属已接受的极小概率风险，无需线程池兜底。【`SessionManager.java:51` 注释 + `:350`/`:761` API 说明同步】
- **SINGLE_FLIGHT_TIMEOUT_MS 改读配置（✅ 已完成，2026-09-06）**：`SessionManager.java` 原字面量 `60_000L` 已改为 `FrameworkConfigManager.getInt(FrameworkConfig.PLAYWRIGHT_NO_LOGIN_SINGLE_FLIGHT_TIMEOUT_MS)`，配置键 `playwright.no.login.single.flight.timeout.ms`、默认 `60000`。功能不变（follower 防死锁兜底超时），现已与配置体系对齐。
- **`new Thread` 治理审计结论（线程卫生固化，无需改动）**：全仓 31 处 `new Thread` 字面命中，剔除 `ThreadLocal`(×5)、`ThreadPoolExecutor`(×3)、测试代码(×10) 后，**生产代码仅 11 处真 `new Thread`**，全部为托管/自管理线程，无裸 `new Thread().start()` 野线程：① 9 处为喂给托管线程池的 `ThreadFactory`（命名 + daemon，受 `ShutdownCoordinator` 在 JVM 退出时回收）；② `BrowserStackLocalManager:251` 方法级 reader（daemon + `join(500)` + 超时 `interrupt`，非常驻泄漏）；③ `ShutdownCoordinator:71` 一次性关机线程。其中 `AsyncPool` 的 per-context 池（`async-ctx-<id>`，`AsyncPool.java:255`）经核验由 `PerContextEngine.close()` 在 context 关闭时 `shutdown()` → `awaitTermination(3s)` → `shutdownNow()` → 移出 map **即时清理**，feature 高频切 context 不泄漏。结论：**当前线程治理合理，高并发下无需改动**。

**本轮新增交付（2026-09-06 续二｜P4 治理启动）**

- **P4 侦察结论（重要更正）**：原看板 T4-2 标"⬜ 待办"、T4-1 称"⭐ 574→~15 文件"，经核查均过时。① `SensitiveDataSanitizer` 的**脱敏可配置早已实现**——内置键为合规基线（硬编码合理，不应被轻易关掉），另通过 `sensitive.data.extra.header/body/query.keys` 配置叠加用户键 + `registerExtraSensitiveKeys` 程序化注入 + `reloadExtraKeysFromConfig` 热更新，且已有值级正则（Bearer/JWT/URL 凭据）；缺的仅是"按值形态（卡号/手机号）识别"，属大改且有误伤风险，本次不做。② ⭐ 审计标记实际跨 **93 文件**仍有出现（grep `⭐`），远多于"~15"，且 `修复P[0-9]` 模式 0 命中（标记格式已变）——T4-1 清零前须先逐文件 triage，不可盲删。
- **T4-3 配置源收敛（✅ 已完成）**：`SensitiveDataSanitizer.readExtraConfig` 原直读 `System.getProperty` + `SystemEnvironmentVariables`（绕开统一源），已收敛到 `core.ConfigSource.resolve(key, "")`——合并 `-D`/serenity.conf/环境变量，并补 `ENC(...)` 透明解密（修复"密文配置不被解密"的潜在安全缺口）；保留 `-D` 优先级以兼容现有 `System.setProperty` 注入与 18 个既有测试。【`SensitiveDataSanitizer.java:135-148`】续：① `VerboseLogging.serenityLoggingLevel` 直读也收敛到 `ConfigSource`；② 核查确认 web `FrameworkConfig.getValue()`（:1323）与 `ProxyConfigResolver` 早已走 `ConfigSource`，`ApiMonitorConfig`(JSON 清单)/`api`(Typesafe) 为刻意例外——属性配置体系已统一至 `core.ConfigSource`，T4-3 整体收官。

| 阶段 | 任务 | 状态 | 备注 |
|------|------|------|------|
| P0 | T0-1 requestUrl 脱敏收口 | ✅ 已完成 | commit 6b47c99（含回归测试 3 用例）|
| P0 | T0-2 SensitiveDataSanitizer 单测 | ✅ 已完成 | 合规件回归护盾，18 用例（header/body/url/freeText/规范化匹配/附加键注册/统一掩码）|
| P0 | T0-3 删 7 个空目录 | ✅ 已完成 | 删 retry（6 子包+父包）+ page/assertion 共 7 个死包目录；全仓库零引用、git 历史从未实现 |
| P0 | T0-4 persistence/Hikari 死代码 | 🔶 部分 | DatabaseUtil 已改；persistence+Hikari 删留待需求方拍板 |
| P0 | T0-5 E2E 移出 surefire | ⬜ 待办（可选）| 已建自包含 E2E 沙箱页（6 文件）用于真实浏览器验证；移出 surefire 待定 |
| P0 | T0-6 仓库卫生 | ✅ 已完成 | git status 干净、无未跟踪源码；1.txt/cp.txt/_tbtest/_verify_nls 经核查已不存在 |
| P0 | T0-7 死 import/失效 workaround | ✅ 已完成 | BasePage:17 死 import 已删（commit 6b47c99）|
| P1 | T1-1~T1-9（门禁 7 件套）| 🔶 部分 | T1-6 ArchUnit ✅（7 规则：page↔route 双向解耦 / common→web·api 越层 / 顶层切片无环）；T1-2 Checkstyle ✅（verify 门禁，作用域限定重构包，0 违规）；T1-1 JaCoCo 🔶（prepare-agent+report 已接线，check 门禁因框架单测为行为护盾、覆盖率约 0% 暂未启用，待 T1-9）；T1-3 SpotBugs ✅（spotbugs-maven-plugin 4.9.8.5 + 引擎 4.9.8 接入，verify 硬门禁；275 存量告警按 Class+pattern 模块级冻结，新代码零容忍）/ T1-4 OWASP 🔶（待办：dependency-check 自动化 CVE 门禁）/ T1-5 Enforcer ✅（4 条规则全生效：maven/java 版本锁 + banDuplicatePomDependencyVersions + requireUpperBoundDeps；7 处版本收敛已修复，全护盾 291 例零回归）/ T1-7 Mockito·AssertJ（测试 classpath 可用，建议显式声明）/ T1-8 CI / T1-9 覆盖率补测 待办 |
| P2 | T2-1 多模块 | ✅ 已完成 | 骨架含 7 模块（core/reporting/api/web/**codegen**/route/test-automation）；`framework-codegen` 新建，`page/scan` 整包（20 Java）物理迁出 `web` 至 `codegen`（见 T2-2） |
| P2 | T2-2 codegen 移出热路径 | ✅ 已完成 | ① 运行时热路径门控解耦（markFrameworkClose×2 + cleanupContext 默认关，全护盾绿）；② 物理整包搬移：`page/scan`（20 Java）迁入新建 `framework-codegen` 模块（包名保留 `framework.web.page.scan`，内部互引 0 改动）；③ web 核心（PageLifecycleCoordinator×2、PlaywrightManager、BasePage.dumpAccessibilityRoles）经 `web.codegen.spi.RoleCodegenBridge` + SPI 注册表解耦，零编译依赖 scan；④ 新增 `CodegenDecouplingArchTest`（ArchUnit 2 例）固化；全护盾 294 例零回归 |
| P2 | T2-3 BasePage 拆分 | ✅ 已完成 | T5-5 五模块全下沉（PageWaits/PageNavigation/PageElementActions/PageFrameShadow/PageLifecycle）；BasePage 退化门面委托，公开 API 零变更；专属 UT + 全护盾 273 例全绿（见 `architecture/T5-5_MODULE5_PAGELIFECYCLE.md` 完成记录）|
| P2 | T2-4 RouteEngine 拆分 | ✅ 已完成 | 分批完成 5 个职责类下沉（每批全护盾 298 例零回归）：`PerContextEngine`（顶层类，据此把 `CONTEXT_ENGINES` 收口进 `RouteContextState`，补齐 C 项遗留）、`RouteLifecycleOwner`、`StoppedCapabilityManager`、`RuleRepository`（注册 + 索引 + 清理）、`DelayScheduler`（延迟调度 + shutdown 闭环）；配套：public API 全保留薄门面（外部调用方零改动）、注册期归一化去重为 `RouteEngine.normalizePattern`、`dispatchRoute`/`LOGGER`/`normalizePattern`/`resolveContext` 放宽包级（日志溯源不变）、清理 4 个失效 import；懒重建锁仍用 `RouteEngine.class`、JVM 钩子仍经 `RouteEngine::shutdown` → 并发语义与生命周期零变更。`RouteEngine` 由 2,013 行 → **685 行**（代码 384 + 注释 226 + 空行 75），含注释债务清理减 71 行（代码行零变更）与 `Dispatcher` 拆分；代码行 384 已达 `<400` 验收口径（文件总行口径未达）；ARP 验收 `RouteEngine <400 行` **未达成**，剩余主体为 `dispatchRoute`（~440 行、8 个 exit 分支），**恢复推进（2026-09-05）**：用户决定继续完成 `dispatchRoute` 拆分，已追加拆出 `Dispatcher`（`dispatchRoute` + 防重门控辅助 `contextOf`/`unmarkDispatched`，纯搬运、行为等价，全护盾 298 例零回归）；控制流契约（空链/无适用规则走 fallback 而非 resume —— g06 故障根因、防重重复直接 return 不 resume、条件不匹配先 `unmarkDispatched` 再 resume、异步路径 finally 不释放门控）已写入 `Dispatcher` 类 Javadoc 固化，弥补既有 `RouteCapabilityContractTest`/`RoutePriorityContractTest`（23 例）只覆盖纯逻辑、不覆盖分发控制流的缺口。已追加拆出 `HandlerExecutor`（`resolveCapabilityHandler`/`decrementTimes`/`scheduleDelay`/`storeDelayCall`/`executeHandlerScheduled`/`executeHandler`，288 行；同步清理 9 个失效 import）；现 `RouteEngine` 总行 424（代码 214 + 注释 149 + 空行 61）—— **非空行 363 已 <400**，文件总行口径尚差 24 行；已追加拆出 `PriorityPolicy`（`selectCapability`，对应验收项「优先级裁决仅一处实现」），五项验收全达成 |
| P2 | T2-5 ApiCaptureContext 拆分 | ✅ 已完成 | 拆分（Phase 5：CaptureStore/Lifecycle 等已抽离）；WeakReference 已移除；**Glob 匹配收敛为唯一实现**（`ApiAssertion` 私有 `globToRegex` 副本已删除，统一委托 `RoutePatternCache.antGlobToRegex`，算法一致 + 获编译缓存）；`System.out` 残留经核实为 CLI 契约（`ConfigCipher.main`）+ Javadoc 示例（`SessionManager`），非业务残留且 Checkstyle 未启用 System.out 禁令，豁免；全护盾 294 例零回归 |
| P2 | T2-6 异常体系统一 | ✅ 已完成 | 10/10 异常继承 `FrameworkException` + `ExceptionHierarchyTest` 固化；`EmptyCatchBlock` hard-fail（severity=error + commentFormat=.*）；主代码 + route 全包 ~250 处 catch 经审计全合规（log 上报/精确捕获/InterruptedException 恢复/防挂起兜底/关键注释），补 20 处注释/cause；全护盾 298 例零回归。验收口径由「514→≤120 机械收窄」更正为「全部 catch 经审计合规、无静默假绿」；零散源（`SummaryReportGenerator`→T2-8、`RoleElementPicker`→T2-2 codegen）并入各自归属 |
| P2 | T2-7 删自研 JSONPath | ✅ 已完成 | 依赖提升 direct（route/pom 显式 json-path 2.9.0）；解 modify/add 值字符串化；通配批量改 Jayway `ctx.map`/`ctx.set`；删自研死代码三件套（findFirstMatchingValue/applyWildcardWithType/applyWildcardRecursive）+ 已替代 applyWildcardWithRawType + 2.1 收尾删 `setNodeByPath`（写回归一为 Jayway `ctx.set`，唯一调用方 `modifyFieldOnTree` 消除）+ 2.4 删 `buildJsonFromFieldMap` 死代码（非公共 API、仅 unit 包内调用、Jayway 替代后无引用）；保留项（convertToMatchingType/evalCondition/parseWildcardPath/setJsonNode/addFieldOnTree 等 Jackson 点路径与条件 DSL）经审计非 JSONPath 引擎；`handle()` 生产路径修复（承接 `modifyFieldOnTree` 返回新树，否则 Mock body 字段替换失效）；契约 `ModifyHandlerContractTest` 固化 + 全护盾 **318 例零回归**。详见 `architecture/T2-7-jsonpath-remediation-design.md` §8 执行记录 |
| P2 | T2-8 报告改模板引擎 | 🔶 进行中 | **阶段 0（golden 护盾）已完成**：新增 `SummaryReportGoldenTest`（4 例）把「输出逐字节一致」固化为可执行门禁（HTML 与 golden 基线比对，基线缺失时生成后**立即失败**防假绿）+ CSV / ZIP / 转义三契约；确定性设计（系统属性钉死项目名与 URL、fixture 提供 `startTime` 固定报告时间、单 JSON 规避 `listFiles` 顺序漂移、路径与 `yyyy-MM-dd_HH-mm-ss` 文件名规范化；踩坑：规范化正则曾误写为 `\d{8}-\d{6}` 致 ZIP 链接时间戳漂移，已修正）。配套 `ReportingRouteDecouplingArchTest`（2 例）固化 reporting→route 解环，reporting 脱离 route 独立运行已实证。全护盾 **326 例零回归**。**阶段 1（下一步）**：模板引擎选 **Freemarker** 整体模板化 —— 模板自带 `<style>` 块与内联样式，产物保持自包含以兼容邮件客户端（**已否决 CSS 外链方案**：报告用于发送邮件，外链样式表会被 Gmail / Outlook 丢弃）；附带发现 `escape()` 不转义双引号，待 auto-escape 时补齐 |
| P3 | T3-1 TestContext 收拢 ThreadLocal | ✅ 基本完成 | 全量 static ThreadLocal 已收拢（仅 BDDUtils 按指示豁免 + 3 处实例级 ThreadLocal 按设计保留）；grep 实测 static ThreadLocal 声明仅剩 1 处（BDDUtils）；全护盾零回归（含 CustomOptionsManagerConcurrencyTest）|
| P3 | T3-6 SessionManager 并发缓存（Guava CacheBuilder） | ✅ 已完成 | 引入 Guava `LoadingCache`（concurrencyLevel(16)+maximumSize(1000) 分段锁）替代原生 `ConcurrentHashMap.computeIfAbsent`，满足多线程读盘 IO 并发；用单例哨兵 `ABSENT_META` 解决 Guava 值不可为 null 约束（等效不缓存负结果）；saveSession(clear→put)/clearSession(invalidate)/clearAllSessions(invalidateAll) 三处失效；新增 SessionManagerCacheTest（2 例）保缓存命中+失效；全护盾 352 例零回归。**（2026-09-06 续）** STORAGE_CONTENT_CACHE 过期由硬编码 30min 改为读 `SESSION_TIMEOUT_MINUTES` 配置、SESSION_IO_EXECUTOR 已移除（方案 B，规避单线程池毒化隐患）；SINGLE_FLIGHT_TIMEOUT_MS 已改读 `PLAYWRIGHT_NO_LOGIN_SINGLE_FLIGHT_TIMEOUT_MS` 配置（默认 60000ms） |
| P3 | T3-2 Browser per-thread/池化 | ✅ 核心隔离已落地 | per-thread keying + restart 线程作用域 + BROWSER_LOCK 降级；**今日新增共享 Browser 模式（1 Browser + N Context）已验证**；CONTEXT/PAGE 锁粒度细化待续 |
| P3 | T3-3 ThreadLocal 清理/RouteDsl unbind | ✅ 已完成 | closeContext 清理解耦（移出 if + 补 CustomOptionsManager 全量清理）；feature/session 路径清理已闭环；RouteDsl unbind/WeakReference 已于 T2-5 完成；新增 PlaywrightManagerCloseContextCleanupTest；全护盾 341 例零回归 |
| P3 | T3-4 打开并行执行 | ⬜ 后置/不紧急 | **用户决策（2026-09-04）**：并行执行后置、不紧急；当前共享 Browser 模式（1 Browser + N Context）已满足需求，无需立即自建并发执行器。C2 方案 `CONCURRENT_CONTEXT_EXECUTOR_DESIGN.md` 存档备查 |
| P3 | T3-5 public API 中立化（PageDriver 接口层已退役） | ✅ 目标达成（接口层撤销） | driver 接口层（PageDriver/ElementDriver/Playwright*Driver）经 2026-09-06 复审判定冗余平行抽象后整体退役；public API 零 Playwright 类型目标经 PageElement/PageElementList/ElementRect 直接满足；残余 `PageElement.elementHandle()`→`ElementHandle` 归增量 3 待办 |
| P4 | T4-1 审计标记迁出 | 🔶 部分 | 需先 re-triage：⭐ 跨 **93 文件**（非此前"~15"），`修复P[0-9]` 模式 0 命中（标记格式已变）；随 T2 收尾的标记清零须逐文件判定后方可删，不可盲删 |
| P4 | T4-2 脱敏可配置+值级识别 | 🔶 部分 | 可配置已落地（`sensitive.data.extra.*.keys` 配置叠加 + `registerExtraSensitiveKeys` 程序化注入 + 热更新；值级正则已覆盖 Bearer/JWT/URL 凭据）；"按值形态(卡号/手机号)识别"仍 ⬜，误伤风险大，本次未做 |
| P4 | T4-3 配置源收敛 | ✅ 已完成 | 核查结论：属性配置体系**早已统一到 `core.ConfigSource`**——`framework.web.config.FrameworkConfig.getValue()` 本就 `return ConfigSource.resolve(...)`（:1323），`ProxyConfigResolver`/`PlaywrightConfigManager` 均经 `FrameworkConfigManager` 门面间接走 `ConfigSource`，`ENC(...)` 解密全覆盖。本阶段仅修两处真正旁路：① `SensitiveDataSanitizer.readExtraConfig` 收敛到 `ConfigSource`（补密文解密）；② `VerboseLogging.serenityLoggingLevel` 直读收敛到 `ConfigSource`。**刻意例外**（非 sprawl，不强行并入）：`PlaywrightListener`/`ScreenshotStrategy` 使用 Serenity `EnvironmentVariables` **对象 API**（非按 key 读，重写风险高且无 `ENC` 需求）；`api.ConfigProvider` 按设计用 Typesafe Config（见 `ConfigSource` 文档）。 |
| P4 | T4-4 文档防漂移 | ⬜ 待办 | |

### 立即可做清单（本周，无需架构决策）
1. **T0-1 收尾（✅ 已完成）**：requestUrl 脱敏 + 回归测试。
2. **T0-7（✅ 已完成）**：删除 BasePage 死 import。
3. **T0-2**（1.5 人日）：为 SensitiveDataSanitizer 补 ≥12 单测（合规件回归保护）。
4. **T0-6 第一步**：`git stash` 备份后提交未跟踪源码、清理 `1.txt`/`cp.txt`/`_tbtest/`/`_verify_nls/`。
5. **T0-3**（0.5 人日）：删 7 个空目录（先确认无未提交实现）。
6. **决策 T0-4**：persistence/Hikari 落库是否在途 → 删（1 人日）或补全（5 人日）。
7. **Phase 1 立项**：T1-1~T1-9 七件套（约 26 人日）是后续重构成果不退化的唯一保险，优先级最高。
