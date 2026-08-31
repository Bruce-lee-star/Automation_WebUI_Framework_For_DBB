# 架构评审报告：Automation_WebUI_Framework_For_DBB

> 评审视角：资深测试架构师
> 评审问题：**本项目是否符合企业级架构标准？**
> 评审日期：2026-08-31
> 评审范围：全量主代码 117 文件 / 50,853 行 + 测试 47 文件 / 6,138 行 + 构建配置 + 文档 + CI

---

## 一、结论先行

**不符合企业级架构标准。**

综合成熟度评分 **3.5 / 10**。定性判断：这是一个**「能力先行、架构欠账」**的框架——功能完备度接近 L3（能力覆盖广、差异化能力强），但架构结构性保障停留在 L1~L2。

它更像是**高强度产出的高级内部工具**，而非**可供多团队长期共同演进的平台**。

我的判据不是「代码写得好不好」（很多局部实现相当扎实、注释里能看到真实的工程判断力），而是企业级框架必须能回答的四问：

| 四问 | 当前能否回答 | 阻塞原因 |
|---|---|---|
| **换人能不能接手？** | ✗ | 13 个千行以上上帝类；BasePage 142 个 public 方法；574 处 ⭐ 审计标记稀释可读性 |
| **加人能不能并行开发？** | ✗ | 单 Maven 模块 5 万行；模块边界仅靠注释约定；已存在循环依赖 |
| **出事能不能定位？** | ✗ | 514 处宽泛 `catch`，约 173 处只 log 不抛；异常基类形同虚设 |
| **需求变了能不能改？** | ✗ | page 包零接口抽象；Playwright 类型泄漏 45+ 处 public 签名 |

---

## 二、量化基线（实测数据）

| 指标 | 实测值 | 企业级参考 | 判定 |
|---|---|---|---|
| 主代码规模 | 117 文件 / 50,853 行 | — | 规模已达平台级 |
| 平均文件行数 | 435 行 | < 300 行 | ✗ |
| 超 1,000 行的类 | **13 个** | 0 个 | ✗ |
| 最大类 | RoleElementPicker **5,117 行** | < 500 行 | ✗ |
| BasePage | 1,801 行 / **142 个 public 方法** | < 30 个方法 | ✗ |
| 测试代码 | 47 文件 / 6,138 行 / **71 个 `@Test`** | 框架自测覆盖 ≥ 70% | ✗ |
| 测试代码占比 | 12%（行数比） | 80%~120% | ✗ |
| Maven 模块数 | **1**（无 `<modules>`） | 按边界拆分 | ✗ |
| 质量门禁插件 | **0 个** | checkstyle/spotbugs/jacoco/pmd/owasp/enforcer | ✗ |
| Mock 框架 | **0 个**（无 mockito/wiremock/assertj） | 必备 | ✗ |
| 架构守护（ArchUnit） | **0** | 必备 | ✗ |
| 并行执行配置 | **无任何 parallel/thread 配置** | 必备 | ✗ |
| `static ThreadLocal` | **33+ 处 / 16 文件** | 收敛进 Context 对象 | ✗ |
| 单例（`getInstance()`） | 14 个 | 依赖注入替代 | ✗ |
| `catch (Exception\|Throwable)` | **514 处**（每 99 行 1 处） | 精确捕获 | ✗ |
| 空包（骨架未实现） | **7 个** | 0 个 | ✗ |
| 注释中审计标记 | 574 处 ⭐ + 324 处「修复 Pn-xx」 | 0（应进 ADR/issue） | ✗ |
| 子系统占比失衡 | route 14,799 行 = **29%** | — | 关注 |

---

## 三、P0 阻断级问题

### P0-1　模块边界只是「注释约定」，无任何编译期强制

`framework/api/config/FrameworkConfig.java:17-20` 的注释原文：

> 「两者**刻意不合并**：api 与 web 是两个独立模块边界，合并会迫使其中一方依赖另一方。」

**问题在于：这个「模块边界」在物理上不存在。** `pom.xml` 中没有 `<modules>`，api 与 web 打进同一个 jar、共享同一 classpath，没有任何机制阻止越界引用。

**直接后果**：模块边界无任何编译期强制，使用者须靠全限定名小心消歧。评审初稿曾称「两份同名类 `FrameworkConfig`（`api/config/` 与 `web/config/`）」，经代码复核实为单一 `api/config/FrameworkConfig.java`，web 侧是 `web/config/FrameworkConfigManager.java`（**不同名，无同名冲突**）——该「同名类」论断有误；但「边界只能靠注释约定、无构建期强制」的结论不受影响。

> 企业级偏离点：**边界必须由构建系统强制，而非由文档约定。** 靠注释维护的边界，等价于没有边界。

### P0-2　循环依赖已成事实

| 循环 | 证据 |
|---|---|
| page ⇄ lifecycle | `BasePage.java:8` → `lifecycle.PlaywrightManager`；`PlaywrightManager.java:12,13` → `page.scan.RoleElementPicker`、`page.base.BasePage` |
| core ⇄ lifecycle | `FrameworkCore.java:4` → `lifecycle.PlaywrightManager`；`PlaywrightManager.java:7` → `core.FrameworkState` |
| lifecycle → route（反向越层） | `PlaywrightManager.java:10,11` → `route.core.RouteEngine`、`route.core.RouteRegistry` |

其中第三条最严重：**生命周期层（底层基础设施）反向依赖了能力层（业务功能）**，依赖方向完全倒置。

另有死依赖：`BasePage.java:17` import `route.core.RouteEngine`，但全文件无任何使用点。

> 企业级偏离点：循环依赖意味着**无法拆包、无法独立单测、无法分模块发布**。这是 P0-1（无守护）的必然结果。

### P0-3　并行执行能力结构性缺失

这是对一个企业级 UI 自动化框架**最致命**的缺口——不能并行 = 不能规模化 = 回归时长无法压缩。

而且它不是「还没配」，而是**架构上已经不允许**：

1. **33+ 个 `private static ThreadLocal`** 遍布 16 个文件。典型如：
   - `BasePage.java:39/47/57`：`currentPage` / `currentFrame` / `currentShadow` 是 **static** 的，同线程内两个 PageObject 共享状态，A 切 frame 会污染 B
   - `PlaywrightListener.java`：10 个布尔守卫（`takingScreenshot`、`stepFinishProcessed`、`stepFinishReentrantGuard`、`apiFailureAlreadyHandled`…）
2. **Browser 跨线程共享**：`PlaywrightManager` 的 `browserInstances` / `playwrightInstances` 是 static Map，按 configId 跨线程共享同一 Browser；`restartBrowser()` / `handleBrowserTypeSwitch()` 是全局操作，代码注释自认「会杀掉其它场景的浏览器」
3. **全局锁把并行吞吐串行化**：`getContext()` / `getPage()` 使用 static `CONTEXT_LOCK` / `PAGE_LOCK`，锁序仅靠注释维护
4. **ThreadLocal 清理有缺口**：`clearAllThreadLocals()` 唯一调用点在 `PlaywrightManager.java:894`，且被 `if (context != null)` 包裹；feature 模式 + session 恢复路径不走 `closeContext()` → `currentFrame` / `currentShadow` 跨 scenario 残留，持有已关闭 Page 的 Frame，**这是真实泄漏**
5. **配置层面零支持**：`serenity.properties`(289 行) 与 `serenity.conf` 中**没有任何 parallel / thread 配置**

> 企业级偏离点：静态状态是并行化的根本障碍。当前设计下「打开并行」不是配置动作，而是一次架构重写。

### P0-4　框架自身几乎没有测试，且结构上无法被测试

- 50,853 行框架代码，**仅 71 个 `@Test`**
- route 子系统 14,799 行，测试覆盖 **8.8%**
- **关键件零测试**：`SensitiveDataSanitizer`（合规件）、`ModifyHandler`(1,788 行)、`ApiCaptureContext`(2,245 行)、`RouteDsl`(1,281 行)、`ApiMonitoringRepository`
- **无任何 mock 框架**（mockito / wiremock / assertj 全无）

第 4 点是根因：一个由 14 个单例 + 33 个 static ThreadLocal 构成的系统，**没有 mock 就在结构上无法做单元测试**。测试稀少不是团队偷懒，而是静态设计的必然代价。

另有测试分层污染：`RouteUnifiedBindingBrowserE2ETest` 真启动 chromium，却匹配 `pom.xml:288-290` 的 surefire 默认 `**/*Test.java`——E2E 混进了单元测试阶段，靠 `forkedProcessTimeoutInSeconds=1800` 兜底。pom 注释自陈「实测 384 个用例…耗时集中在 4 个真实浏览器测试类（合计约 335s）」，而实际 `@Test` 只有 71 个，**文档与事实不符**。

### P0-5　合规缺口（金融行业红线）

`SensitiveDataSanitizer.java` 559 行，是脱敏收口件，但：

| 问题 | 证据 | 风险 |
|---|---|---|
| 脱敏规则硬编码、不可配置 | `:52-95` 两个 `static final Set`，无配置/扩展点 | HSBC 各市场本地字段名必须**改框架代码并重新发版** |
| 仅字段名白名单，无值级识别 | 除 Bearer/JWT/URL 参数（`:123-132`）外，无 PAN(Luhn) / IBAN / HKID 校验位识别 | `acctNo`、`beneAcct` 等变体直接漏网 |
| **requestUrl 未脱敏** | `CapturedApiCall.java:178-185` 只脱敏 body/headers | query 中的 token 可经 `getRequestUrl` 出域 |
| 掩码泄漏长度 | `:534` 输出 `(len=N)` | 对 4-6 位 PIN 缩小候选空间 |
| **该类零单测** | — | 合规件无回归保护 |

另：代码注释中留有真实 SIT 内网域名（`ApiCaptureContext.java:430`、`SerenityReporter.java:192`）。

> **2026-08-31 复核（P0-5）**：requestUrl 脱敏已部分落地——`ApiMonitoringRecord` 构造期 `sanitizeUrl`、`CapturedApiCall` 构造期已脱敏 body/headers；但 `CapturedApiCall.requestUrl` 仍原样存储、`MonitorFailureReportWriter` 直接 `getRequestUrl()` 输出 → 对象级 URL 出域缺口已由 **T0-1 收尾**闭合（见整改方案第六部分状态看板）。

> 企业级偏离点：金融行业的脱敏必须是**可配置、可审计、有测试覆盖**的独立组件。当前状态下无法通过合规审计。

---

## 四、P1 严重级问题

### P1-1　上帝类普遍化

| 类 | 行数 | 职责数 |
|---|---|---|
| `RoleElementPicker` | 5,117 | 元素拾取器 + JS 注入 + 面板 UI |
| `ApiCaptureContext` | 2,245 | 存储限流 + Glob 匹配 + 等待门控 + 断言 DSL + 报告 + 响应仓库 + 生命周期 |
| `SummaryReportGenerator` | 2,184 | HTML/CSS/JS 硬编码拼接（66 处） |
| `RouteEngine` | 2,013 | 注册 + 合并 + 分发 + 防重 + 延迟调度 + 生命周期 + 会话 + 缓存（8 张 static 可变 Map） |
| `BasePage` | 1,801 | **10 类职责 / 142 个 public 方法** |
| `ModifyHandler` | 1,788 | 大半是自研 JSONPath 引擎 |
| `PlaywrightListener` | 1,478 | 事件监听 + 10 个重入守卫标志 |

`BasePage` 的 10 类职责：元素定位、等待、断言、重试、截图、frame 切换、shadow DOM、窗口切换、Cookie、注解反射绑定、CI 环境判定、a11y 采集。严重违反 SRP。

附带问题：**两套并行 API + 两套重试实现**——`page.click("#x")` 与 `page.element("#x").click()` 并存；`BasePage:323-366` 与 `PageElement:188-260` 各有一套重试，策略不一致（后者有 `isRetriable` 文案匹配，前者无）。

### P1-2　零接口抽象，与 Playwright 硬绑定

- **page 包 interface 数量 = 0**
- `base/impl/` 下唯一文件 `SerenityBasePage.java` 是 `abstract class extends BasePage`——**「impl」命名误导**，实为第二种基类，非接口实现
- Playwright 类型（`Page` / `Locator` / `Frame` / `BoundingBox` / `Cookie` / `AriaRole`）出现在 **45+ 处 public 方法签名**

项目里有 `PLAYWRIGHT_VS_SELENIUM.md`(649 行) 认真讨论选型，但代码上已无退路——**替换驱动等于重写整个页面层**。

### P1-3　异常体系名存实亡

`FrameworkException` 作为基类存在，但 10 个异常中只有 2 个继承它：

```
FrameworkException          ← 基类
├── NavigationException     ✓
└── TimeoutException        ✓

RuntimeException            ← 5 个直接继承，绕过基类
├── BrowserException        ✗
├── ConfigurationException  ✗
├── ElementException        ✗（其下 ElementNotFound/ElementOperation 继承它）
├── InitializationException ✗
└── ScreenshotException     ✗
```

**后果：无法统一 `catch` 框架异常**，调用方只能 catch RuntimeException 或逐个列举。

配合 **514 处 `catch (Exception|Throwable)`**（约 173 处只 log 不抛），失败会被静默吞掉——**测试可能「假绿」**，这对测试框架是致命的（框架的唯一价值就是可信的红绿信号）。

### P1-4　死代码 / 未完成代码进主干

| 项 | 证据 |
|---|---|
| **7 个空目录** | `retry/` 全树 6 个子包（configuration/controller/executor/listener/metrics/strategy）+ `page/assertion` |
| **~722 行永不生效代码** | `pom.xml:236-240` 引入 HikariCP 5.0.1 但**无 MySQL/PG 驱动**；`ApiMonitoringRepository.java:419` 硬编码 `com.mysql.cj.jdbc.Driver` 必抛 ClassNotFound；`:197-203` 仅 WARN 静默降级；`serenity.properties:255/277` 开关默认 `false` |
| 无 schema 迁移 | `:379 buildDdl` 仅 `CREATE TABLE IF NOT EXISTS`，无 Flyway/Liquibase，加列在旧库永久静默失效 |
| 死 import | `BasePage.java:17` import RouteEngine 但未使用 |
| 失效的 workaround | `RoleElementPicker.java:977` 的 65535 常量池拆分（a/b1/b2）在改为运行时 `loadScript()` 后已无必要，属遗留投机复杂度 |

> 「骨架先行、实现缺失」还提交进主干，会让接手者误以为功能存在。

### P1-5　开发期工具混入生产制品且侵入运行时热路径

`page/scan/` = **7,854 行 Java + 7,265 行 JS**（打进主 jar），本质是交互式元素拾取器 + 代码生成器（`RoleElementStepGenerator` 注释自述「产物为草稿」）。

**它已经侵入运行时**：
- `BasePage.java:836/878`：`closeCurrentPage` / `closeOtherPages` 调用 `RoleElementPicker.markFrameworkClose(page)`
- `PlaywrightManager.java:893`：每次 `closeContext` 调 `RoleElementPicker.cleanupContext(context)`
- `LoginSteps.java:164`：真实登录步骤里调用 `RoleElementPicker.openPanel(...)`

> 企业级偏离点：开发期工具应为独立 artifact（`provided` scope），绝不能出现在生产制品的运行时热路径上。

### P1-6　route 子系统已膨胀为「被塞进 UI 框架的独立网络平台」

14,799 行（占主代码 29%），内含完整分层 `core/ + dsl/ + handler/ + monitor/ + persistence/`，覆盖 mock / 录制 / 断言 / DB 落库 / 报告 / 脱敏，项目根还带 `route-demo-service/`、`route-demo-web/` 两个独立演示工程。

附带**重复造轮子**：已引入 `serenity-rest-assured`（`pom.xml:134`），却在 `ModifyHandler` 自建 JSONPath 引擎（`:1379 parseWildcardPath`、`:1591 applyWildcardRecursive`、`:1165 evalCondition`）；`ApiCaptureContext:1052` 的 Glob 匹配与 `util/ApiMatcher.java`(531 行) 功能重叠。

**`WeakReference` 是缺陷自白**：`ApiCaptureContext.java:85-91` 注释承认「`RouteDsl.on` 只负责 bind、并不保证 unbind」，线程池复用会读到死 context。`WeakReference` 只是把泄漏降级为「GC 后静默回退」——**用 GC 掩盖缺失的 try/finally unbind**。

---

## 五、P2 中等级问题

| # | 问题 | 证据 |
|---|---|---|
| P2-1 | **配置源碎片化**：8 个配置入口无单一优先级权威，无 fail-fast 校验 | `serenity.properties`(289行) + `browserstack.conf` + `serenity.conf` + `config/application.conf` + `config/routedemo.conf` + `api-monitor-config.json` + typesafe config + System properties |
| P2-2 | **报告层硬编码 HTML**：2,184 行中 66 处 HTML/CSS 字符串拼接，无模板引擎 | `SummaryReportGenerator.java` |
| P2-3 | **CI 不是质量门禁**：仓库内无 build/test/scan 流水线，无 SAST/SCA/覆盖率门禁 | `.github/workflows/` 仅 `serenity-report-push.yml`，注释自陈「本 workflow 不跑测试，只做结果部署」 |
| P2-4 | **DSL 弱类型**：运行期才报错 | `RouteDsl:1065 when(String, String op, Object)` 的 `op` 是裸字符串；`:436 register(Object)` 靠 instanceof 分派 |
| P2-5 | **仓库卫生**：大量源码未提交，协作者拿不到可运行代码 | 未跟踪：`ShutdownCoordinator.java`、`HikariConfigFactory.java`、整个 `tests/api/`、4 个 Glue、`route-demo-service/`、`route-demo-web/`；根目录垃圾：`1.txt`、`cp.txt`、`_tbtest/`、`_verify_nls/`(1,407 文件)、13 个 `*.log` |
| P2-6 | `System.out.print` 残留 2 处 | `ApiCaptureContext.java`、`SessionManager.java` |

---

## 六、P3 轻微级问题

### P3-1　注释即审计台账（574 ⭐ + 324 「修复 Pn-xx」）

生产代码注释里留有 **574 处 ⭐** 和 **324 处「修复 P1-12 / P3-33 / A1 / D-1」**类审计标记。这些是审计**过程记录**，应进 commit message / ADR / issue tracker，而非代码注释。

现状是部分类的注释比代码长，可读性被审计噪音严重稀释，并已出现**注释与实现矛盾**：
- `RouteEngine:59-77` 悬空 Javadoc（对应字段已改名）
- `RouteEngine:123` 称「已委托通用异步池」，但 `:126` 仍自建线程池
- `FrameworkState:24-29` 以「当前无调用点」为由拒绝修复已知缺陷

### P3-2　文档漂移

| 项 | README 声称 | pom 实际 |
|---|---|---|
| Spring Context | 6.1.6 | **6.2.19** |
| Logback | 1.5.6 | **1.5.34** |
| 用例数 | pom 注释「384 个用例」 | **71 个 `@Test`** |

---

## 七、值得肯定的地方（评审须公允）

架构层面问题很多，但以下方面确实做得好，不应被否定：

1. **技术选型现代且合理**：Java 21 + Playwright 1.58.0 + Serenity BDD 4.3.4，方向正确
2. **依赖安全治理扎实**：显式 `dependencyManagement` 压 jackson 2.21.4 / json-smart 2.5.2 修 CVE；排除 `commons-logging`；显式声明 `commons-lang3 3.17.0` 替代脆弱的传递依赖
3. **有真实的工程判断力**：`pom.xml:179-187` 那段——发现原实现把 `selenium-support` 钉死在 4.15.0，而 Serenity 4.3.4 官方配套是 4.38.0，相隔 23 个 minor，会以 `NoSuchMethodError` 形式在运行时炸；于是对齐到 4.38.0 并保留瘦身意图。**这是高水平的依赖分析。**
4. **测试分层意识存在**：surefire（单元）/ failsafe（集成）分离，且都配了 `forkedProcessTimeoutInSeconds` 防死锁兜底；`pom.xml:295-303` 明确警告「这是防死锁最后防线，不是时间预算」，还记录了「300s 触发产生一批假失败被误判为代码回归」的真实教训
5. **SPI 注册方式正确**：通过 `META-INF/services` 注册 Cucumber EventListener 与 Thucydides StepListener，是标准做法
6. **分层意图方向正确**：`page / lifecycle / listener / config / route / session / accessibility / cloud` 的划分本身合理，问题在于没有强制手段
7. **文档量大且有设计文档**：5,356 行 9 篇，含 `API_MONITOR_DESIGN.md`、`ROUTE_SCOPE_AND_PRIORITY.md` 等设计级文档，不是只有 README
8. **差异化能力有价值**：route 子系统的拦截 / Mock / 录制 / 监控 / 断言能力、a11y 扫描、BrowserStack 云测、会话复用、NLS 多语言——这些是真实的竞争力，只是**装错了地方**

---

## 八、成熟度评分明细

| 维度 | 得分 | 关键依据 |
|---|---|---|
| 模块化与边界 | **2** / 10 | 单模块 5 万行，边界靠注释，3 组循环依赖 |
| 抽象与可替换性 | **2** / 10 | page 包零接口，driver 类型泄漏 45+ 处 |
| 并发与扩展性 | **2** / 10 | 33+ static ThreadLocal，Browser 跨线程共享，无并行配置 |
| 可测试性 | **2** / 10 | 71 测试 / 5 万行，无 mock 框架，关键件零覆盖 |
| 工程规范与门禁 | **2** / 10 | 0 个静态扫描插件，CI 不跑测试 |
| 错误处理 | **3** / 10 | 异常基类 2/10 生效，514 处宽泛捕获 |
| 安全与合规 | **4** / 10 | 依赖治理优秀，但脱敏硬编码 + URL 漏脱敏 + 零测试 |
| 配置管理 | **4** / 10 | 能力齐全，但 8 源碎片化、无 fail-fast |
| 可观测性与报告 | **6** / 10 | 报告能力强，但 2,184 行硬编码 HTML |
| 文档 | **6** / 10 | 量大有设计文档，但漂移 + 审计噪音 |
| 技术选型 | **8** / 10 | 现代、合理、有依据 |
| 功能完备度 | **8** / 10 | 能力覆盖广，差异化强 |
| **综合** | **≈ 3.5 / 10** | 功能像 L3，架构停在 L1~L2 |

---

## 九、整改路线图

### 关键原则：**先立门禁，再改代码**

如果先重构而不建门禁，改完必然退化——当前 574 处审计标记本身就证明了这一点：项目经历过多轮审计整改，但因为没有自动化守护，问题反复回归，最后审计记录堆积成了代码注释。

### Phase 0　止血（1~2 周，全部低风险）

| # | 动作 | 理由 |
|---|---|---|
| 1 | 修 `CapturedApiCall:178-185` 的 requestUrl 脱敏缺口 | **合规红线**，token 可出域 |
| 2 | 为 `SensitiveDataSanitizer` 补单元测试 | 合规件必须有回归保护 |
| 3 | 删除 7 个空目录（`retry/` 全树 + `page/assertion`） | 消除「功能存在」的误导 |
| 4 | 删除或独立 `persistence/` + HikariCP（722 行永不生效代码） | 默认关闭、无驱动、无迁移、无测试 |
| 5 | 将 `RouteUnifiedBindingBrowserE2ETest` 改名 `*IT` 或加 tag | 把 E2E 移出 surefire 单元测试阶段 |
| 6 | 提交未跟踪源码；清理 `1.txt`/`cp.txt`/`_tbtest/`/`_verify_nls/` | 协作者当前拿不到可运行代码 |
| 7 | 删除 `BasePage:17` 死 import | — |

### Phase 1　建立门禁（2~4 周）

| # | 动作 | 配置要点 |
|---|---|---|
| 8 | 引入 **jacoco** | 设当前覆盖率为基线，**只升不降** |
| 9 | 引入 **checkstyle + spotbugs** | 先设宽松规则（文件行数上限、空 catch 检测），逐步收紧 |
| 10 | 引入 **owasp dependency-check** | CVE 门禁，接续现有依赖治理成果 |
| 11 | 引入 **maven-enforcer** | 禁止依赖版本冲突、禁止重复类 |
| 12 | 引入 **ArchUnit** | 规则：禁 `page → lifecycle` 反向、禁 `lifecycle → route`、禁跨 `api ↔ web`、禁 `*/scan` 被运行时代码引用 |
| 13 | 引入 **mockito + assertj** | 解开单元测试的结构性封锁 |
| 14 | CI 增加 build + test + scan job | 现在只有报告部署，等于没有门禁 |

> Phase 1 第 12 条最关键：**用 ArchUnit 把 `api/config/FrameworkConfig.java:17-20` 那条「注释约定」变成编译期规则。**

### Phase 2　拆解（1~2 月）

| # | 动作 |
|---|---|
| 15 | **拆多模块**：`framework-core` / `framework-web` / `framework-api` / `framework-route` / `framework-codegen` / `framework-report`。用 Maven 模块把注释约定变成物理强制，同时解决两份 `FrameworkConfig` 的同名冲突 |
| 16 | **codegen 独立**：`page/scan`（7,854 行 Java + 7,265 行 JS）拆出为独立 artifact 改 `provided` scope，**并摘掉它在 `BasePage:836/878`、`PlaywrightManager:893` 运行时热路径的调用** |
| 17 | **拆 BasePage**：按 Locating / Waiting / Frame&Shadow / Window / Cookie / Screenshot 分离；`PageElement` 与 `BasePage` 的双头 API 二选一收敛，统一为一套重试实现 |
| 18 | **拆 RouteEngine** → `RuleRepository` + `RuleMerger` + `Dispatcher` + `LifecycleOwner`，优先级裁决收敛为单一策略对象（现散在 `resolveUnified:603`、`mergeCrossLayer:553`、`selectCapability:820`、`applyStoppedCapabilities:802` 四处） |
| 19 | **拆 ApiCaptureContext** → `CaptureStore` + `ApiAssertions` + `WaitGate` + `ReportBuilder` |
| 20 | **统一异常基类**：全部异常 `extends FrameworkException`；审查 514 处宽泛 catch，把静默吞异常的改为抛出或明确标注 |
| 21 | **删除自研 JSONPath**，改用已有的 `json-path` 依赖 |
| 22 | `SummaryReportGenerator` 改模板引擎（Freemarker/Thymeleaf） |

### Phase 3　并行化（2~3 月，最难但价值最高）

| # | 动作 |
|---|---|
| 23 | 引入 **per-scenario `TestContext` 对象**，把 33+ 个 static ThreadLocal 收敛进去，通过依赖注入传递而非静态访问 |
| 24 | Browser 实例改 per-thread 或池化，移除跨线程共享 static Map 与全局 `CONTEXT_LOCK`/`PAGE_LOCK` |
| 25 | 修 ThreadLocal 清理缺口：feature 模式 + session 恢复路径必须走清理；`RouteDsl.on` 补 try/finally unbind，移除靠 GC 兜底的 `WeakReference` |
| 26 | 打开 Cucumber/Serenity 并行执行，用 `route-demo-web` 做并行回归验证 |
| 27 | 引入 `PageDriver` 接口层，把 Playwright 类型从 public API 收回 |

### Phase 4　治理（持续）

| # | 动作 |
|---|---|
| 28 | 574 处 ⭐ / 324 处审计标记迁出代码 → ADR 文档 + issue tracker；清理悬空/矛盾 Javadoc |
| 29 | `SensitiveDataSanitizer` 改为可配置 + 补 PAN(Luhn)/IBAN/HKID 值级识别 |
| 30 | 配置源收敛，建立单一优先级权威 + 启动期 fail-fast 校验 |
| 31 | README 版本表改为从 pom 自动生成，消除漂移 |

---

## 十、给决策者的一句话总结

> 这个框架的**功能**已经达到企业级水准，但它的**结构**还不足以承载这些功能。
>
> 当前 50,853 行代码里，最大的技术债不是任何单个缺陷，而是**没有任何自动化机制阻止债务继续累积**——0 个质量门禁、0 个架构守护、71 个测试。574 处审计标记说明团队一直在努力修问题，但修的速度赶不上退化的速度。
>
> 因此我的建议是：**不要先重构，先建门禁。** Phase 1 的 7 个插件投入约 2~4 周，但它决定了后续所有重构是否有意义。

---

## 十一、评审后续修正与状态（2026-08-31 补）

本评审作为「整改前基线快照」仍成立；但经代码复核，需做以下修正，并扣除本轮已落地的修复。

### 修正 1：P0-1「两份同名类 FrameworkConfig」证据有误
实测主代码中仅存在 `api/config/FrameworkConfig.java` 一个 `FrameworkConfig`；web 侧为 `web/config/FrameworkConfigManager.java`（**不同名**），不存在同名冲突。评审据此「会迫使一方依赖另一方」的推导前提不成立，但「模块边界仅靠注释约定、无构建期强制（无 `<modules>`）」的核心结论不受影响，P0-1 维持。

### 修正 2：P0-5 requestUrl 脱敏已部分落地
- `ApiMonitoringRecord.java:35` 构造期已 `sanitizeUrl(...)`（含 ⭐ P0 修复 注释）；
- `CapturedApiCall` 构造期已脱敏 body/headers；
- **但** `CapturedApiCall.requestUrl` 仍原样存储、`MonitorFailureReportWriter` 经 `getRequestUrl()` 直出 → 对象级 URL 出域缺口。
- 2026-08-31 已通过 **T0-1 收尾**闭合（见整改方案第六部分）：`CapturedApiCall.requestUrl` 存前 `sanitizeUrl` + `MonitorFailureReportWriter` 收口 + 回归测试 `SensitiveDataSanitizerUrlTest`（3 用例通过，commit 6b47c99）。

### 扣除：本轮（H1–H19 / M 系）已落地、映射到整改任务的修复
以下修复在本评审之后已提交，对应任务状态需在整改方案中扣减（详见第六部分状态看板）：
- **T3-1 / T3-3**：NLSUtils 跨线程可见性、AsyncPool 许可对称释放、RouteDsl/`ApiCaptureContext` unbind 契约、ShutdownCoordinator 清理协调、PageObjectFactory `singleInstances`、BrowserStackManager 隧道清理、PlaywrightManager/PlaywrightContextManager 残留。
- **T2-5**：ApiCaptureContext 计数器只增不减修复、unbind 契约（WeakReference 移除待 T2-5 收尾）。
- **T0-4**：DatabaseUtil `closeResources` 对称释放、背压；`route/persistence` + HikariCP 死代码是否删除仍待需求方拍板。
- 其余并发/资源类修复（ApiMonitoringRepository 可见性/计数、MonitorHandler 丢弃可观测化等）亦已落地。

> 结论：本评审**合理、可信、可作整改依据**；仅需上述 2 处事实修正 + 1 处清单扣减，无需推翻。
