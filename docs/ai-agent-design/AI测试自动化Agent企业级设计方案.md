# AI 测试自动化 Agent —— 企业级稳定解决方案设计

> 目标：在你现有框架（JDK21 + Java Playwright + Serenity BDD + Cucumber）之上，叠加 **Playwright MCP** 与 LLM Agent，让 AI 承担"需求分析 → 用例设计 → 自动化脚本开发 → 执行与自愈"的重复性工程劳动。
> 核心诉求不是"AI 能点页面"，而是**稳定、可复用、可审计、人在环内**的企业级产出。
> **多语言（NLS）是一等维度**：你的框架已原生支持 `@RoleFile` + `@RoleElement(key=...)` 多语言定位，AI 必须"绑定 NLS key"而非"翻译文案"。

---

## 0. 设计哲学（为什么能稳定）

| 原则 | 含义 | 反模式 |
|---|---|---|
| **约束优于自由** | AI 不允许自由写 XPath / 硬编码等待，只能产出 `@RoleElement` / `getByRole` / `getByTestId` | "AI 随便写脚本" → 一重构就崩 |
| **复用优于生成** | 每生成一个步骤先查 `step-map`，能复用已有步骤定义绝不新建 | 重复步骤泛滥（你框架的现存风险之一） |
| **验证优于假设** | 生成即执行：未通过 `dry-run` 的脚本**不允许**进仓库 | 生成一堆跑不通的死代码 |
| **人在环内** | Agent 不能直接 push `main`，必须 PR + 人工审批 | Agent 越权改主干 |
| **可观测** | 每个 Agent 动作带 `scenario`/`thread` 标记写入 Serenity + 审计日志（复用 core 的 MDC/脱敏） | 黑盒、不可追溯 |
| **NLS key 绑定优先** | 多语言页面，AI 一律生成 `key=` 绑定，绝不写死可见文案 | 写死 "登录" → 切成英文全崩 |

---

## 1. 总体架构

见内联架构图。分层：

- **编排层**：`Orchestrator Agent`（Planner/LLM）做需求拆解、任务路由、质量门、重试决策。
- **能力层（5 个子 Agent）**：需求分析、用例设计、脚本生成、执行&自愈、报告聚合。
- **工具层（两类工具）**：
  - *Playwright MCP Browser Tools*：accessibility snapshot / click / fill / navigate / screenshot / trace——AI 的"运行时手眼"。
  - *Framework Tools*：复用你框架的 `codegen(@RoleElement)`、`step-map` 去重、`nls_resolve` 反查、Serenity 报告、ENC 凭据。
- **执行环境**：沙箱浏览器（MCP 驱动，隔离可重建）+ 稳定 SIT 环境 + Serenity 报告（按 locale 分桶）。
- **治理层**：审计(MDC) / 最小特权 / 模型成本路由 / 回滚灰度 / 黄金评估集。

---

## 2. 工具清单（Tool Inventory）

每个工具都是 Agent 可调用、可单元测试、可审计的 MCP 风格工具。

| 工具 | 输入 | 输出 | 底层能力 |
|---|---|---|---|
| `requirement_ingest` | Jira key / PRD 文本 / Confluence URL | 结构化需求 JSON（用户故事、验收标准、实体、依赖） | Jira REST / 文档解析 |
| `requirement_decompose` | 需求 JSON | 可测业务流清单 + 可测性缺口标记 | LLM 规划 |
| `requirement_classify` | 需求文本 + 现有 case 索引 | 分类 `NEW` / `MODIFY` / `ENHANCE` + 置信度 + 命中的 NLS key / Page / Step / Feature 证据 | 复用 `map_existing_steps` 索引 + `nls_resolve` + LLM 语义匹配 |
| `impact_analyze` | 需求文本（MODIFY/ENHANCE 时） | 受影响清单：NLS key、引用该 key 的 Page 类、Step 定义、Feature/Scenario、当前绑定风格（key= vs name=） | grep `@RoleElement` + 索引反查 + tag 匹配 |
| `case_diff` | 现有 scenario + 新需求 | 结构化增量：定位器变更 / 新增步骤 / 断言变更 / 新增变体 | LLM diff（基于 Gherkin 语义） |
| `regression_guard` | 现有 cases（改前快照） | 不变行为的非回归断言集；修改后必须仍绿 | 复用 `dry_run_execute` 对未改 scenario |
| `design_testcases` | 业务流清单 | Gherkin feature 草稿 + 需求→用例追踪矩阵 | LLM + 模板 |
| `map_existing_steps` | Gherkin 步骤文本 | 已存在步骤定义（类/方法）或"无复用"标记 | 扫描 `test-automation` step defs（AST/注解） |
| `page_model` | 页面 URL / 已录制上下文 | 结构化页面模型：`regions` / `elements[]{role,name,nlsKey,space,interactions}` / `nlsKeys[]` / `spaces[]` / `a11yIssues[]` | 复用 `RoleElementPageGenerator.collectFromPage`（RoleEntry 列表）+ `RolePickerNlsCache` 反查 + `AxeCoreScanner`；MCP accessibility snapshot 作补充 |
| `req_trace_init` | 现有 feature 目录 | 为存量 case 打 `@req(<id>)` / `@story(<id>)` 追溯标签（一次性治理脚本） | grep + 批量注解写入 |
| `generate_page_object` | 页面 URL / DOM + nls 文件 | `@RoleFile` + `@RoleElement(key=)` Page 类（继承 `BasePage`） | 复用 `codegen` + `RolePickerNlsCache` 反查 |
| `generate_step_def` | Gherkin 步骤 + Page | Java 步骤定义（优先复用、仅补缺口） | LLM + 框架断言/软断言 |
| `nls_resolve` | 录制到的 a11y name（含 hover 揭示后的 tooltip 文本）+ nls 文件路径 | NLS key（如 `user_name`、`password_hint`） | 复用 `RolePickerNlsCache` 反向查表（a11y→key），**不**走拼音转写 |
| `tooltip_probe` | 触发器定位器 + 目标 nls 文件 | tooltip 节点的 NLS key + hover-reveal 后的可访问名 | MCP 先 hover/focus → 二次 accessibility snapshot 抓 tooltip 节点 → `nls_resolve` 反查 key |
| `nls_gap_check` | 一组 nls 文件 | 跨语言缺失的 key 清单（如 `zh-HK` 缺 `button_logon`） | 遍历各语言表比对 key 集合 |
| `nls_maintain` | 缺失 key + 目标语言 | 建议翻译（草稿，待人工审） | LLM 翻译 + 复用 `NlsNameTranslator` 命名 |
| `dry_run_execute` | 生成脚本 + 环境标签 + locale 列表 | 各语言下通过/失败 + Serenity 片段 + trace | Playwright MCP 沙箱执行（逐 locale） |
| `self_heal_locator` | 失败步骤 + 当前页面 + nls 文件 | 重映射 NLS key + 修正后脚本 | MCP accessibility snapshot 重新反查 |
| `submit_pr` | 验证通过的脚本 | 分支 + 提交 + PR（草稿态，等审批） | Git / 平台 API |
| `report_aggregate` | 执行结果集（按 locale） | Serenity 汇总 + 趋势 + 覆盖率缺口 | 复用 `reporting` 模块 |

---

## 3. 三条核心流水线

### 3.0 变更感知的需求分析（Modify vs Enhance）—— 一等能力

> 这是整套方案里**最容易被忽略、却最决定稳定性**的一环。新需求落到已有功能上时，Agent 第一步不是"写脚本"，而是**判断这是改现有还是增强**，再决定改存量还是加增量。做错这一步，就会把你仓库里已经存在的 `login_dbb1.feature` / `baidu1.feature` 这种"复制即改"的反模式规模化。

**为什么必须单独设计这一层（来自你仓库的真实证据）：**

- **没有需求追溯标签**：feature 文件只有 `@smoke @web`、`@regression @web @login` 这类*执行*标签，全仓没有任何 `@story` / `@jira` / `@req` 把 case 绑到需求（已 grep 验证）。→ Agent **无法自动知道**"新需求 = 改哪个旧 case"，必须靠 NLS key + 语义反查去**推断影响面**。
- **"复制即改"已在发生**：`login_dbb.feature`（3 个 scenario）与 `login_dbb1.feature`（同名 scenario + 多几步 `switch profile`、一个被注释掉）是近重复；`baidu1.feature` 注释明写 *"for comparison"*。这正是手工改需求时的典型凑合写法。
- **绑定风格不统一**：`LoginPage.java:43` 用 `@RoleElement(name="Troubleshooting login issues")` 写死英文文案，而非 `key=`。仓库处于"部分迁移"态，改现有路径必须能识别 `key=` 与 `name=` 两种写法。
- **NLS 是稳定的改/增信号**：`NLS_idv_logon.json` 结构是 `{ "en-US":{key:text}, "zh-HK":{...} }`。**改标签 = key 改名或值变更（跨所有语言）**；**增强 = 新增 key**。NLS 天然区分"改"与"增"。

**分类逻辑（`requirement_classify`）：**

| 类别 | 判定 | Agent 动作 | 红线 |
|---|---|---|---|
| `NEW` | 现有索引零命中（无 NLS key / Page / Step / tag 重叠） | 全新生成 Page + Step + Feature，无存量触碰 | — |
| `MODIFY`（改现有） | 命中存量 case，且需求改变了该功能的*既有*行为/流程 | `impact_analyze` → `case_diff` → **原地修改**命中 case | **禁止新增重复文件**；改完旧断言须仍绿（`regression_guard`） |
| `ENHANCE`（增强） | 命中存量 case，需求是在既有功能上*新增变体/字段/分支* | 旧 case 保留，**新增** scenario/步骤；引用同一 Page | **旧 case 不允许被破坏**；旧断言须仍绿 |

**关键工具链：**

1. `requirement_classify(req)` → `NEW/MODIFY/ENHANCE` + 置信度 + 命中证据（命中的 NLS key、Page 类、Step、Feature/tag）。
2. `impact_analyze(req)`（MODIFY/ENHANCE 时）→ 受影响清单：
   - NLS key（用 `nls_resolve` 把需求涉及的文案反查成 key）
   - 引用该 key 的 Page 类（grep `@RoleElement(key=...)`）
   - 引用这些 Page/Step 的步骤定义
   - 相关 Feature/Scenario（用 `map_existing_steps` 反向索引 + tag）
   - **当前绑定风格**：`key=` 还是 `name=`（决定要不要顺手迁移到 NLS key，呼应第 9 节铁律）
3. `case_diff(existing_scenario, req)` → 结构化增量：定位器变更 / 新增步骤 / 断言变更 / 新增变体。
4. `regression_guard(现有 cases)` → 改前对未变 scenario 做快照；改后必须仍绿，否则产物判失败。

**两条硬约束（写进稳定性机制）：**

- **MODIFY 改存量、禁复制**：产物若是"新建了一个 `xxx1.feature`"而存量 `xxx.feature` 未动，直接判为失败。这是把 `login_dbb1` 教训固化成规则。
- **ENHANCE 保旧绿**：旧 case 不允许因增强而红；增强只追加，不入侵。

**对仓库的配套建议（让推断变精确）：** 用一次性 `req_trace_init` 脚本为存量 case 补 `@req(<id>)` / `@story(<id>)` 标签。目前只能"语义推断影响面"，加上追溯标签后，`impact_analyze` 从"猜"变"查"，准确率与可审计性都上一个台阶。

### 3.1 需求 → 用例（低风险，纯生成）
`requirement_ingest` → `requirement_decompose` → `design_testcases` → 产出 Gherkin + 追踪矩阵。**人工确认用例**后才进入脚本阶段。

### 3.2 用例 → 脚本（MCP 录制 → 框架代码转译，**NLS 感知**，见内联图）
1. `dry_run_execute` 或 MCP 直接**录制**真实交互（accessibility snapshot 携带 `role` + 可访问名，无脆弱 XPath）。
2. `map_existing_steps` 先查复用，避免重复。
3. `nls_resolve` 把录制的 a11y name **反查成 NLS key**（复用 `RolePickerNlsCache`，**不是**拼音转写）。
4. `generate_page_object` 产出 `@RoleFile` + `@RoleElement(key=)` Page；`generate_step_def` 产出步骤定义（仅补缺口）。
5. `dry_run_execute` **逐语言**验证（en-US / zh-HK / …）；失败则 `self_heal_locator` 循环（重反查 key）。
6. 全部语言通过 → `submit_pr`（草稿，等审批）。**任一语言缺失 key 即整体失败**，不允许退化成字面量。

### 3.3 执行与自愈（运行时）
CI 跑生成的套件；失败时 Agent 拉起 `self_heal_locator` 自动修复定位器漂移（仍走 NLS key 反查），修复 PR 同样走人工门。**自愈只改定位器，不改业务逻辑。**

---

## 4. 稳定性机制（最关键）

1. **定位器策略强制**：工具层对生成物做 lint——只允许 `@RoleElement` / `getByRole` / `getByTestId`；绝对 XPath、裸 `Thread.sleep` 一律拒绝（这恰好补上你框架"测试代码零 Checkstyle 门禁"的缺口）。
2. **复用优先 + 去重**：`map_existing_steps` 是生成前置门，直接抑制重复步骤膨胀。
3. **生成即验证（dry-run gate）**：未通过 `dry_run_execute` 的脚本物理上无法进仓库。
4. **确定性**：代码生成用 `temperature=0` + 固定模型 + 固定 system prompt 版本，结果可复现。
5. **沙箱隔离**：执行在独立可重建环境，避免污染 SIT 与彼此状态（呼应你框架"用例隔离"风险）。
6. **黄金评估集（eval）**：维护一组"标杆用例"，每次 Agent 升级都回归，量化"Agent 生成质量"而非拍脑袋。
7. **NLS key 绑定优先**：生成的定位器一律 `@RoleElement(key=...)` + 类级 `@RoleFile`，绝不写死可见文案（`name=`/`text=` 仅当 nls 无对应 key 才降级，且须人工确认）。换语言零维护。
8. **跨语言 dry-run 门**：脚本必须在所有声明语言下通过，且每个 NLS key 在所有语言表中都存在；缺失 key 即失败，禁止退化成字面量。
9. **MODIFY 改存量、禁复制**：判定为"改现有"的需求，产物必须是*原地修改*命中 case；若 Agent 新建了 `xxx1.feature` 而存量 `xxx.feature` 未动，直接判失败（把 `login_dbb1` / `baidu1` 的教训固化成规则，见 §3.0）。
10. **ENHANCE 保旧绿**：判定为"增强"的需求，旧 case 不允许被破坏；增强只追加 scenario/步骤，旧断言改后仍须绿（`regression_guard` 兜底）。
11. **影响面人工确认**：仓库当前无 `@req` 追溯标签，MODIFY/ENHANCE 的影响面靠 NLS key + 语义推断得出，必须人工确认后才动手改存量，避免误伤无关 case。

---

## 5. 与现有框架的集成点（具体）

- **codegen 桥接**：`generate_page_object` 直接调用 `codegen` 的 `RoleElementPageGenerator`，保证生成规范一致。
- **NLS 反查复用**：录制到的 a11y name 经 `RolePickerNlsCache.buildNlsReverseJson`（`:42-108`）反查成 NLS key；字段命名走 `NlsNameTranslator`（仅代码生成期，见 `NlsNameTranslator.java:16-35`）。**AI 不重新发明翻译**。
- **`@RoleElement` 规范**：生成的 Page 继承 `BasePage`，注解与你手写 Page 完全一致（类级 `@RoleFile` + 字段 `key=`），混合维护无摩擦。
- **Cucumber 步骤 glue 映射**：`map_existing_steps` 扫描 `test-automation/src/test/java` 步骤定义，复用 glue；新步骤严格对齐现有命名。
- **Serenity 回写**：`report_aggregate` 复用 `reporting` 模块模板与 `serenity.conf` 环境切换；trace/截图按 locale 分桶进入报告。
- **凭据走 ENC**：Agent 运行时**只读密文**，经 `core` 的 `ConfigCipher` 解密到内存，绝不落日志（复用你已建好的 AES-256-GCM + 脱敏体系，正好堵住 `SEC` 类 P0）。

---

## 6. 企业级治理

- **权限/最小特权**：Agent 用独立技术账号，只能对 `feature/ai-generated/*` 分支写，禁止直推 `main`。
- **审计**：每次 LLM 调用、每次工具执行、每次提交都带 `actor=agent` + `run_id` 入审计日志（复用 core MDC）。
- **成本/模型路由**：分类/映射用便宜小模型，代码生成用强模型，结果缓存复用。
- **回滚/灰度**：评估集未过 → 自动回滚上一版 Agent prompt/工具；新能力先对 1 个模块灰度。
- **数据合规**：需求文本、截图、trace 按敏感级别脱敏（复用 `SensitiveDataSanitizer`）。

---

## 7. 分阶段落地 MVP（建议顺序）

| Wave | 范围 | 风险 | 产出 |
|---|---|---|---|
| A | 需求→用例 + `map_existing_steps` | 低（纯生成，不碰执行） | 用例草稿 + 复用报告 |
| B | 脚本生成 + `dry_run_execute` 验证（含 NLS 反查 + 单语言） | 中 | 可跑过的 `@RoleFile`+`key` Page/Step + PR |
| C | `self_heal_locator` + 自愈流水线 + **跨语言 dry-run** | 中 | 多语言定位器自愈 PR |
| D | 全流程 + 治理层 + 评估集 + **NLS 维护（gap/maintain）** | 中 | 企业级闭环 |

**先 A 后 B**：A 阶段不产生任何可执行代码，安全验证 Agent 的"理解力"；B 阶段才接通 Playwright MCP 执行。

---

## 8. 一个具体的转译示例（多语言版）

**MCP 录制时的 NLS 反向绑定（AI 复用 `RolePickerNlsCache` 已有的 a11y→key 反查）**
```text
录制快照: [textbox accessible-name="Username"]  →  nls_resolve(NLS_idv_logon.json)  →  key="user_name"
          [button accessible-name="Log on"]     →  nls_resolve(...)                  →  key="button_logon"
```
> AI 拿到的 a11y name 先反查 NLS key，再写 `key=`。**绝不**用 `NlsNameTranslator` 把"用户名"转成拼音当定位器——那是代码生成期的字段命名，不是运行期定位。

**生成的 Page Object（继承 BasePage，类级声明 @RoleFile，字段绑定 NLS key — 换语言零维护）**
```java
@RoleFile("nls/NLS_idv_logon.json")
public class LoginPage extends BasePage {
    @RoleElement(role = AriaRole.TEXTBOX, key = "user_name")
    private PageElement USER_NAME;

    @RoleElement(role = AriaRole.TEXTBOX, key = "password")
    private PageElement PASSWORD;

    @RoleElement(role = AriaRole.BUTTON, key = "button_logon")
    private PageElement LOGON;

    public void login(String u, String p) {
        USER_NAME.fill(u);
        PASSWORD.fill(p);
        LOGON.click();
    }
}
```
> 关键点：`key="user_name"` 直接指向 NLS 文件里的 key。运行时 `NLSUtils.setLanguage("zh-HK")` 后，同一字段自动解析为「用户名」；切到 `en-US` 解析为「Username」。**AI 生成的是 key，不是文案**——这正是多语言稳定的根基。

**生成的 Step Definition（先经 step-map，仅补缺口；locale 由场景钩子设置）**
```java
@When("user logs in with {string} and {string}")
public void userLogsIn(String u, String p) {
    // 场景级 @Before 已 NLSUtils.setLanguage(scenarioLocale)
    softAssert.that(loginPage.login(u, p)).isTrue();
}
```

**模板变量必须保留语义（不可写死真实值）**
```java
// nls 值: "if you don't want to log on as {{current_username}}"
// 生成时输出 key，运行时经 templatePattern 编译为正则匹配注入后的文本：
@RoleElement(role = AriaRole.LINK, key = "not_this_user")
private PageElement NOT_THIS_USER;   // getByText(Pattern)，跨语言 + 动态值兼得
```

---

## 9. 多语言 / NLS 策略（关键增强，一等维度）

你的框架已原生支持多语言定位，AI 的任务是**消费而非重造**这套能力。

### 9.1 机制事实（已落地的底座）
- **NLS 文件结构**：`{ "en-US": { key: text }, "zh-HK": { key: text }, ... }`（`NLS_idv_logon.json:2`）。新增语言 = 新增一个顶层语言表。
- **运行期多语言定位**：类级 `@RoleFile("nls/xxx.json")` + 字段 `@RoleElement(role, key="user_name")`；运行时 `NLSUtils.setLanguage("xx")` 后同一字段解析为对应语言的可访问名（`RoleElement.java:22,72-85`）。`NLSUtils` 支持多文件 `bind` 跨文件查 key，并自动剥离 HTML/实体后匹配可见文本。
- **语言状态并发安全**：`LanguageState` 用"全局值 + 线程级覆盖 + 全局单调序号"双轨（`LanguageState.java:50-77`），`getLanguage()` 取较新写入，既保留并发隔离又不被陈旧线程副本遮蔽——这正是多语言 + 并发场景隔离的正确底座（呼应评审 PAR-1）。
- **a11y→key 反查已存在**：`RolePickerNlsCache.buildNlsReverseJson`（`:42-108`）把 nls 编译成 `{exact, templates}` 反向查表注入浏览器，拾取时自动把可见名匹配回 key。AI 录制流程直接复用它。
- **模板变量跨语言**：`{{var}}` 经 `NLSUtils.templateRegexSource`（`:274-285`）编译为跨语言正则（`(.*?)`），`getByText(Pattern)` 匹配注入后文本；变量名在各语言保持一致。
- **`NlsNameTranslator` 是代码生成期权宜**：仅把中文元素名转成 Java 字段标识符（如 `登录`→`Login`），运行期不消费（`NlsNameTranslator.java:16-35`）。AI 的字段名可走它，但**定位器必须走 key**。

### 9.2 AI 的多语言铁律
1. **只生成 key，不生成文案**：任何 `@RoleElement` 优先 `key=`，配类级 `@RoleFile`。`name=`/`text=` 仅在 nls 确实无该 key 时降级，且必须人工确认（评分扣分）。
2. **录制即反查**：MCP 录到的 a11y name → `nls_resolve` → key；禁止让 LLM 凭记忆写 key。
3. **跨语言验证**：`dry_run_execute` 逐 locale 跑；任一语言失败或任一 key 缺失即整体打回。
4. **模板变量保语义**：含 `{{var}}` 的 nls 一律输出 `key=`，运行时正则匹配，绝不把真实值写进定位器。
5. **`data-i18n` 走属性选择器**：多语言 `data-i18n="header_business"` 直接用 `@Element("[data-i18n=\"header_business\"]")`，无需注解新增字段。

### 9.3 AI 顺手承接的 NLS 维护（你提到的"重复性脑力活"）
NLS 文件本身会随着需求增长持续膨胀，跨语言 key 缺失/不一致是长期痛点——这正是 AI 的重复性价值：
- **`nls_gap_check`**：比对一组 nls 文件各语言表的 key 集合，产出"en-US 有、zh-HK 缺"的清单（含评估缺漏严重度）。
- **`nls_maintain`**：对缺失 key 生成建议翻译草稿（保留 `{{var}}` 占位符、HTML 标签结构），**仅出 PR 草稿，由母语者审批**。
- 这两个工具与脚本生成共用同一套 nls 加载逻辑（`NLSUtils.rawTables`），零额外基础设施。

### 9.4 多语言下的并发与隔离
- 每个 Scenario 通过 `@Before` 调 `NLSUtils.setLanguage(locale)`；因 `LanguageState` 的单调序号机制，并发多场景各自设置语言互不遮蔽（评审 PAR-1 的现成答案）。
- 沙箱执行按 `(scenario, locale)` 二维隔离；Serenity 报告按 locale 分桶，便于横向对比"同一用例在各语言的通过率"。

### 9.5 无文案元素与 Tooltip 的定位策略（你提到的"tooltip 没有任何文案"）

> 这是"绑定 NLS key"策略最容易翻车的地方：tooltip 常常**触发器无可见文案**、且 tooltip 本身**默认不在 accessibility tree 里**（要 hover/focus 才出现）。若照 §9.2 铁律直接 `getByTitle("Do not enter your 6-digit...")`，会跨语言直接崩——和写死 `name=` 是同一病症。

**来自你仓库的事实：**
- **tooltip/hint 文案确实在 NLS 里**：`NLS_idv_logon.json` 含 `password_hint`、`title_*`、`forgot_username_title` 等，且 `en-US` / `zh-HK` 各一份。即"无文案"指的是**触发器**（图标按钮）没文字，而 tooltip 弹出的文本本身有 NLS key。
- **框架只支持 `getByTitle` 绑字面量**：`RoleElement.java:29,120` + `LocatorFactory.java:181,240` 提供 title 语义定位，但绑定的是 `title` 属性**字面量**，无 NLS key 变体。
- **框架缺口**：无 hover 才现身的封装、无 `aria-describedby` 关联、无专门 Tooltip wrapper。

**分级定位策略（写进宪章与治理）：**

| 情形 | 绑定目标 | 做法 |
|---|---|---|
| tooltip 文本在 NLS（最常见） | tooltip 节点的 NLS key | 先 hover 揭示 → 二次 snapshot 抓到 tooltip 节点 → `nls_resolve` 把其文本反查成 key → `@RoleElement(key=password_hint)` 绑在 tooltip 节点上 |
| 触发器有 `title`/`aria-label`（其可访问名） | 触发器的可访问名 | 可绑 `title`/`aria-label`；但若是写死英文，优先推动改 NLS 或 `data-testid`，仍有跨语言风险 |
| 触发器**完全无名**（纯图标、无 title/aria-label） | **禁止** XPath / 索引定位 | Agent 产出 `@a11y-gap` 标注 + 提缺陷单，要求开发加 `data-testid`/`aria-label`；**不生成脆弱定位器** |

**录制流程必须改写（二级可访问性节点）：** 不是一次 snapshot，而是 `触发 hover/focus → 抓第二次 snapshot → 捕获 tooltip 节点`。MCP 录制步骤需内建"hover 后重抓"。

**断言策略：** 用 `aria-describedby` 关联（触发器 → tooltip 节点），或 hover 后断言 tooltip 节点**按 key 的文案**可见；**绝不**断言触发器本身的文本（它本就无文本）。

**对框架的配套建议（缺口）：** 现状 `getByTitle` 绑字面量在跨语言下脆。建议加 `@RoleElement(titleKey=...)` 或 `TooltipElement` wrapper，把 hover-reveal + `aria-describedby` + NLS key 三件事封装成一个稳定 API，让 AI 和人工统一消费。

### 9.6 难测元素：iframe / Shadow DOM / 文件上传 / OTP / 动态表格行

你确认这五类在项目中都存在。逐类核实后结论很关键：**前四类你的 `RolePicker` 录制器已经原生解决，AI 只须"消费录制输出"；只有"动态表格行"是框架真空白，需 Agent 带新策略。** 这直接决定了 Agent 的"复用 vs 新增"分工。

| 元素 | 框架现状（已核实） | Agent 策略 | 复用/新增 |
|---|---|---|---|
| **iframe** | `RolePickerScriptInjector` 向所有 frame（含嵌套）注入拾取脚本，`onFrameAttached`/`onFrameNavigated` 覆盖动态附加帧；`RoleEntry.space` 记录 `frame:login>...` | 消费 `framePath`，生成 `page.frame(...)` / `frameLocator(...)` 切换 step | **复用录制器** |
| **Shadow DOM** | `RoleEntry.shadowPath` 记录 open shadowRoot 宿主链；`RoleElementStepGenerator` 据此显式生成 `switchToShadow` step（`:224-229`） | 消费 `shadowPath`，无需 AI 自行 pierce | **复用录制器** |
| **文件上传** | `PageElement.uploadFile()` → `setInputFiles`，支持 classpath 资源解析、多文件、路径归一化；`RolePickerScripts.MARK_LAST_PICK_UPLOAD_JS` 捕获文件选择框 | 消费 upload 标记，用 **test resource** 路径（非随机临时路径），多文件走数组 | **复用录制器** |
| **OTP / 验证码** | `BDDUtils` 读 `token.security.url`（按 env+user 的后门取数端点）；`LoginPage.otpRadioLabelRadio` 选 OTP 渠道 | **路由到后门**，绝不"破解"；纯 captcha 标 `@manual-blocked` | **复用 + 红线** |
| **动态表格行** | 全仓无 table/grid/row 首类抽象，行靠通用 role 定位——**框架空白** | 新增 `anchor_row` 锚定策略（按稳定单元格值/相对定位，禁绝对 index） | **Agent 新增** |

#### 9.6.1 iframe 与 Shadow DOM — 直接消费录制器输出
- 录制期 `RolePicker` 已把每个元素的 `space`（主文档 / 某 frame / 某 shadow / frame 内 shadow）和 `framePath`/`shadowPath` 算好并写入 `RoleEntry`。AI 转译时**原样读取**这两个字段生成切换 step，自己不重新推断"元素在哪个 frame"。
- 这样即使嵌套三层 frame + 两层 shadow（金融后台常见），生成的脚本也是"进 frame → 进 shadow → 操作 → 退出"，与人工手写一致，且天然稳定。
- **红线**：AI 生成的 frame/shadow 切换 step 必须与录制 `space` 完全一致；不得用绝对 CSS 选择器穿透 shadow（那是框架明确不做的写法）。

#### 9.6.2 文件上传 — 用受控测试资源，禁随机路径
- 消费录制器的 upload 标记后，文件路径一律指向 `src/test/resources` 下的**受控测试文件**（如 `test-upload/sample.txt`），与 `PageElement.uploadFile` 的 classpath 解析对齐。
- 多文件走 `uploadFile(a, b)` 数组形式，不要循环调用。
- 隐藏的 `<input type=file>`（被自定义按钮遮挡）靠 Playwright `fileChooser`/`setInputFiles` 直击，录制器已捕获该事件，AI 不必模拟点击遮罩按钮——这正好绕开"无文案触发器"问题（见 §9.5）。

#### 9.6.3 OTP / 验证码 — 只走后门，绝不破解（强红线）
- OTP 在你的框架里不是"AI 去收短信"，而是 `token.security.url` 这个**按 env+user 的后门端点**取出一次性 token（已核实 `BDDUtils.java:100-108`）。Agent 生成登录流程时，**必须**复用 `otpRadioLabelRadio` + 后门取数，不得尝试 OCR/读短信/猜验证码。
- **纯图形验证码（captcha）在无后门时**：Agent 一律标 `@manual-blocked` 并在 PR 描述里写明"此处需人工/测试桩"，绝不生成"识别验证码"的伪脚本——那会污染用例且不可维护。
- 与治理呼应：`token.security.url` 属凭据，Agent 只引用配置键，**绝不在生成物里写明文**（呼应架构评审 SEC 类 P0 + 宪章"只走 ENC"）。

#### 9.6.4 动态表格行 — 唯一的框架空白，Agent 带锚定策略（新增）
框架没有首类 table/grid 抽象，这是五类里唯一要 Agent 真正动脑的。策略：
- **按稳定单元格值锚定行，而非绝对行号**：`page.getByRole(ROW).filter(hasText=NLS.resolve(headerKey))` 或 `row = getByRole(CELL, {name: anchorValue}).locator("xpath=ancestor::tr")`。`anchorValue` 优先用 NLS key 解析（跨语言），禁 `tbody tr:nth-child(3)` 这类脆弱写法。
- **相对定位**：目标单元格用 `(row, headerKey)` 二维定位——先锚行、再在行内按 NLS key 找列，彻底摆脱列序号漂移。
- **与 MODIFY/ENHANCE 咬合**：表格列若因需求"改现有/增强"而增删，AI 改的是"headerKey 集合"与"行锚条件"，不是重写 index；旧 case 保绿（宪章 #10）。
- **空表/多页/懒加载**：AI 生成时须带"等待行出现/翻页/滚动加载"的显式等待，禁 `Thread.sleep`；建议框架补一个 `@RowLocator` 注解把上述二维锚定封装成稳定 API（缺口建议）。

### 9.7 页面结构理解（Page Model）：让"怎么做"从猜变成读

前面所有策略（NLS 绑定、tooltip 反查、iframe/shadow 切换、动态表格锚定）都依赖一个前提：**Agent 必须先读懂页面结构，才能决定怎么自动化**。你框架已经把这件事的发动机造好了——`RoleElementPageGenerator.collectFromPage`（`:449-470`）把整页 DOM 扫成 `RoleEntry` 列表，每个元素带 `role/name/tag/level/key` + `space/framePath/shadowPath`。这本身就是一份**结构化、语义化、NLS 感知的页面模型**，比裸 HTML 或扁平 a11y dump 清晰一个数量级。Agent 应直接消费它，而不是重新解析 DOM。

#### 9.7.1 Page Model 是什么
给定"URL 或已录制的上下文"，Agent 产出一份结构化页面模型（`page_model` 工具的输出）：

| 字段 | 来源 | 用途 |
|---|---|---|
| `regions` | 扫描 landmark/heading 层级 | 给出页面骨架（登录区/交易区/导航区），需求→用例时定位"操作落在哪个区" |
| `elements[]` | `RoleEntry` 列表 | 每个可交互元素：`{role, name, nlsKey, space, interactions}` |
| `nlsKeys[]` | 元素 key + NLS 文件解析 | 列出本页用到的 NLS key → 自动判断需引入哪些 NLS 文件、做 gap 检测（§9.2 `nls_gap_check`） |
| `spaces[]` | `framePath`/`shadowPath` | 列出本页涉及的 frame/shadow 空间 → 决定要不要 frame/shadow 切换 step（§9.6.1） |
| `a11yIssues[]` | 复用 `AxeCoreScanner` | 标记无 name 触发器、缺 label 控件 → 直接产出 `@a11y-gap`（§9.5 情形 C） |

#### 9.7.2 它让"怎么做"变清晰（四类决策）
- **定位决策**：模型里元素已有 `nlsKey` 与 `space`，Agent 直接决定"绑 key 还是要 hover 反查"（§9.2/§9.5），不再盲猜。
- **路由决策**：`spaces[]` 非空 → 自动加 frame/shadow 切换 step；为空 → 主文档直操作。与 §9.6 完全咬合。
- **复用决策**：把 `elements[]` 的语义（role+key）与 `map_existing_steps` 索引比对，**命中即复用存量 Step**，不重复造（呼应"复用优于生成"）。
- **影响面决策**：改现有/增强时（§3.0），把待改需求涉及的语义（如 `otp_radio_label`）去 `page_model` 里查"这个 key 在当前页面模型里存在吗、在哪个 space"，输出精确的命中清单，比全仓 grep 准。

#### 9.7.3 三层结构输入（不重造轮子）
Page Model 由三层拼成，全部复用你框架现有能力：
1. **语义层**——`collectFromPage` 的 `RoleEntry` 列表（已有）；
2. **NLS 层**——`RolePickerNlsCache` 反查 + NLS 文件（已有）；
3. **可访问性层**——`AxeCoreScanner` 扫描（已有，登录页已在用 `LoginSteps:159`）。

Playwright MCP 的 `accessibility snapshot` 作为**补充输入**（尤其对付"录制时未展开的折叠区/弹层"），但**主模型以 `RoleEntry` 为准**——因为它带 `key` 和 `space`，MCP 快照不带。

#### 9.7.4 红线
- Page Model 必须是 **NLS 解析后的**（key 已映射到 en-US/zh-HK 文本），不得基于未解析的 raw name 生成定位器；
- 模型必须包含 `space` 字段，缺 `space` 的 frame/shadow 元素一律判定为"结构不完整"→ 重试采集，不得降级成 XPath；
- 模型只用于"理解与生成"，不得把页面结构（哪怕脱敏）落盘到报告/日志之外的地方（呼应治理"最小数据"）。

### 9.8 Common Chrome 排除（TopBar / Left Menu 作用域）

登录落地页通常带 **TopBar（banner）+ Left Menu（navigation）+ 业务内容（main）** 三类区域。若 Agent 把 TopBar/Left Menu 当成"每个功能页面的一部分"去生成，会直接制造「复制整页来改一个菜单项」的灾难（与 §3.0 改现有的反模式同源：菜单一变，N 个功能页的 Step 全崩）。

**好消息：框架已内建两套排除机制，Agent 直接消费即可，不必重造。**

| 现成能力 | 证据 | 用于排除什么 |
|---|---|---|
| `RolePicker` 区域点选 `scanRegion`/`start-region-select-js.js`/`cmdScanRegion`/`cmdRegionScanned` | `RolePickerCommandEngine.java:298-453` | 用户/AI 点选业务区，仅该 DOM 子树（iframe 边界内）被采集，TopBar/Left Menu 在区域外 → 根本不进模型 |
| `pageClass`/`window.__rolePageName` 页面级隔离 | `RolePickerPickParser.java:80-100`（方案 B 按页去重，删一页不波及其它页） | 不同页面的同名元素各自成键；Shell 元素归 `AppShellPage`，Feature 元素归 `FeatureXPage`，互不污染 |
| `HomePage extends AbstractManagedPage` | `test-automation/.../pages/HomePage.java:17`（已含 `quickLink`/`profileSwitcher`） | Shell 已有首类建模雏形，应升级为统一 `AppShellPage` |
| `SessionManager.getFeatureHomeUrl` / `getHomeUrl` | `SessionManager.java:288-315` | "进入某功能"已概念化为"到达 feature home url"，是**可复用旅程**而非功能步骤 |
| `permissionLeftMenuConfig`（API 驱动菜单） | `LoginSteps.java:92,182` | 左菜单是**权限数据**而非静态标记 → 菜单项应建模为 `menuKey → featureHomeUrl` 映射，不逐个重拾链接 |

#### 9.8.1 三层排除策略（从临时到耐久）

**L1 — 区域采集（最简单，默认开启）**
`page_model` 工具优先用 `scanRegion` 语义：Agent 通过 Playwright MCP 点击业务区（或直接以 `main` landmark 为区域边界），仅业务区元素进入模型。TopBar/Left Menu 在区域外，物理上不进 `RoleEntry` 列表。

**L2 — Landmark 过滤（全扫时的兜底）**
若做了整页扫描，`page_model` 按 ARIA landmark 给每个元素打 `region` 标签：
- `banner` → TopBar；`navigation` → Left Menu / 侧导航；`main` → 业务内容；`contentinfo` → 页脚。
- 默认**只为 `main` 区域生成定位器与步骤**；`banner`/`navigation` 标 `common: shell`，进入排除清单，不生成功能级 Locator/Step。
- 这层也解决"万一区域点选没覆盖到嵌套弹层"的兜底。

**L3 — Shell 抽取（耐久修复，根因治理）**
TopBar + Left Menu 收敛为**单一的 `AppShellPage`**（升级自现有 `HomePage`），全框架只建模一次：
- Shell 元素用 `@RoleElement(key=)` 重做（现有 `HomePage` 用的是 `#quick_link_section`、`[id='topnav.profileswitcher.dropdown']` 这类 CSS，跨语言/改版会脆——顺手修）。
- Left Menu 的每一项建模为 `menuItem(menuKey)` → `navigateTo(featureHomeUrl)`，**数据来源是 `permissionLeftMenuConfig` 权限接口**，不是逐个重拾菜单链接。菜单 key 稳定即导航稳定，UI 文案/排序变了也不崩。
- "进入目标功能"封装成**可复用 Step 定义**：`Given I open menu "<menuKey>"` / `Given I am on feature "<menuKey>" home`。任何功能 Scenario 的 `Background` 只写这一句，**绝不在功能场景里重复描写 TopBar/Left Menu 的点击链**。

#### 9.8.2 登录 → 功能的流转（Agent 的默认剧本）

```
登录成功（LoginSteps/HomeSteps，复用现有）
   │
   ├─[SHELL 作用域] page_model 跑一次 → 产出 AppShellPage 模型（TopBar+LeftMenu+其 NLS key）
   │                 存入「可复用 Shell」，不视为某个功能页
   ▼
open_menu "<menuKey>"（复用 Step，从 Shell 出发，到达 feature home）
   │
   ├─[FEATURE 作用域] page_model 跑区域扫描 → 产出 FeatureXPage 模型（仅 main 业务区）
   ▼
功能 Scenario 只描述 main 区内的业务交互
```

#### 9.8.3 与变更感知（§3.0）的咬合

- **新需求改的是 TopBar/Left Menu 本身**（如顶栏加通知铃铛、菜单加一项）→ 判定为 **SHELL 变更**，`impact_analyze` 只标记 `AppShellPage` + 导航类 Step 定义，**不**波及每个功能 Scenario；Agent 切到 SHELL 作用域原地改 Shell，旧功能场景保绿（呼应 ENHANCE 保旧绿）。
- 这正好掐死"复制整页来加一个菜单项"——菜单是 Shell 的数据映射，加项只需在 `permissionLeftMenuConfig` 派生表里补一条 `menuKey → url`，零功能页改动。

#### 9.8.4 红线
- 功能级生成的 Locator/Step **不得**包含 `banner`/`navigation` 区域元素（除非本需求显式声明为 SHELL 变更）；
- `AppShellPage` 全框架唯一，禁止在功能 Page 类里重复声明 TopBar/Left Menu 元素；
- 导航到功能的点击链必须走复用 Step，禁止在各功能 Scenario 里内联"点菜单→点子项"；
- Shell 元素一律 `@RoleElement(key=)`，禁止 `@Element("#id")`/`[id=...]` 写死 CSS（现有 `HomePage` 的两处须在 SHELL 抽取时改写）。

---

## 10. 风险与边界（先讲清，避免误用）

- AI **不能替代探索性测试**：它做的是"已知需求 → 已知脚本"的重复劳动，异常场景仍需人设计（呼应你框架"异常场景设计"维度）。
- **需求质量决定上限**：垃圾 PRD 进，垃圾用例出；`requirement_decompose` 会标出可测性缺口，但需人确认。
- **NLS 不是银弹**：AI 只能保证"定位器绑定 key"，若产品本身在某语言漏翻某个 key，AI 会**如实报缺**（9.3 的 gap 工具），而不会偷偷用字面量掩盖——这是特性不是 bug。
- **成本**：LLM 调用有费用，靠缓存 + 模型路由 + 评估集控量。
- **误提交防护**：所有产出走 PR + 人工门，`dry_run` 只是门槛不是保险。

---

## 11. 下一步建议

1. 先落地 **Wave A**（需求→用例 + 复用映射），不需要 Playwright MCP 即可验证 Agent 理解力，1 周内可见效。
2. 我可以直接**原型化 `map_existing_steps` + `nls_resolve` 两个工具**（扫描你 `test-automation` 步骤定义建索引、调用 `RolePickerNlsCache` 反查），它们不依赖 Playwright MCP、不碰执行、风险最低，却最能立刻体现"复用优于生成"与"NLS 绑定优先"。
3. 评估集：从你现有 16 个 feature 里挑 3 个稳定用例（覆盖至少 2 种语言）作为 Agent 回归基准。

> 与前面架构评审的衔接：本方案复用 `@RoleElement`/codegen/Serenity/ENC 优势资产，并针对性修复 P0/P1（测试代码门禁缺口、用例隔离、明文凭据、并行未通）；NLS 维度进一步把"用例隔离"升级为"按 (scenario, locale) 二维隔离"，用框架已有的 `LanguageState` 单调序号机制兜底。AI 工具是"把评审结论变成持续生产力"的杠杆。
