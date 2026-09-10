# 模块评审 04｜`framework-route`

> 定位：Playwright 网络流量拦截 / Mock / 修改 / 延迟 / 监控 引擎
> 规模：51 Java 文件 / ~9,000 行 / 自有单元测试 0 个（测试在 test-automation）
> 依赖：`framework-core`、`framework-web`、`framework-reporting`、json-path
> **模块总评：3.4 / 5.0 —— 全项目技术密度最高、设计最成熟的模块**

---

## 一、模块能力地图

| 分层 | 类 | 说明 |
|---|---|---|
| **能力定义** | `RouteHandleType`（MONITOR/MODIFY/MOCK/DELAY） | 单一枚举定义优先级、执行序、终止性 |
| **规则模型** | `RouteRule` / `RouteRuleScope` / `ConditionalFieldRule` / `PriorityPolicy` | 规则、作用域、条件、优先级裁决 |
| **匹配** | `ApiMatcher` / `RoutePatternCache` / `RouteLiteralPathCache` / `RouteUnifiedResolution` | glob/正则匹配 + 缓存 + 统一裁决 |
| **注册** | `RouteRegistry` / `RouteHandlerRegistry` / `RuleRepository` | 注册表（弱键防泄漏） |
| **执行** | `RouteEngine` / `PerContextEngine` / `Dispatcher` / `HandlerExecutor` | 引擎、分发、处理器执行 |
| **处理器** | `MonitorHandler` / `MockHandler` / `ModifyHandler` / `DelayHandler` | 四类能力实现 |
| **生命周期** | `RouteLifecycleImpl` / `ApiCaptureLifecycle` / `RouteContextState` / `RouteMonitorSession` / `StoppedCapabilityManager` / `ApiCaptureManager` / `ApiCaptureStore` | 上下文隔离与状态 |
| **监控** | `ApiMonitorOrchestrator` / `MonitorFailureCollector` / `MonitorFailureReportWriter` / `MonitorCallback` | 监控编排与失败上报 |
| **持久化** | `ApiMonitoringRepository` / `ApiMonitoringRecord` / `DatabaseStoreMonitorCallback` / `FileStoreMonitorCallback` | DB/文件双通道 |
| **DSL** | `RouteDsl`（+ 能力分子类） | 用户 API |
| **工具** | `ApiAssertion` / `ApiCallAwaiter` / `DelayScheduler` / `RouteUtil` / `RouteException` / `AssertionFailureDetail` | 断言、等待、调度 |

---

## 二、八维度逐项分析

### D1 模块边界与依赖治理 —— 2.0 / 5 ❌ **本模块最主要短板**

**问题 R-1（P1/严重）：分层倒置 —— route 反向依赖 web**

```java
// route/src/main/java/.../persistence/DatabaseStoreMonitorCallback.java:3-4
import com.hsbc...framework.web.config.FrameworkConfig;
import com.hsbc...framework.web.config.FrameworkConfigManager;
// route/.../persistence/ApiMonitoringRepository.java:154
import com.hsbc...framework.web.utils.HikariConfigFactory;
```

Maven 依赖方向是 `route → web`（合法），但**语义上倒置**：一个网络拦截引擎本应是相对底层的设施，却向上依赖 Web UI 框架的配置体系与连接池工厂。后果：
- `framework-route` **无法自包含**，不能脱离 web 单独使用；
- route 的持久化配置走 web 的 `serenity.properties`，配置变更影响链路跨模块；
- 若未来 web 需要依赖 route 的某类，立刻形成**模块环**（当前仅靠"web 不 import route"的约定维持）。

**问题 R-2（P1/中）：包名与模块名错配，边界在包结构不可见**

Maven 模块叫 `framework-route`，包名原是 `framework.web.route.*`（ROUTE-P1-2 已修复为 `framework.route.*`）。读者从包路径看，会认为它是 web 模块的一部分。**模块边界在源码层面不可辨识**——这是多模块拆分不彻底的典型症状（现已修复）。

**问题 R-3（P1/中）：ArchUnit 门禁存在切片盲区**

```java
// ArchitectureTest.java:106
slices().matching("..framework.(*)..")
```
该模式按 `framework` 下**第一层**切片。原 route 的类在 `framework.web.route`，会被归入 `web` 切片**内部**（ROUTE-P1-2 已修复：现 `framework.route` 为独立切片）：
- `route → web` 的依赖属于"同一切片内"，**不被切片环路规则检出**；
- L1–L5 规则也未定义"route 不得依赖 web"。

**结论**：R-1 的分层倒置**在架构门禁下完全隐形**。门禁存在，但管的不是真正的风险。

---

### D2 抽象设计与扩展性 —— 4.0 / 5 ✅ **全项目最佳**

**优点 R-4：优先级与执行序语义单一收敛**

```java
// RouteHandleType.java:47-82
// 单一枚举定义：priority / executionOrder / terminal（是否终止后续处理器）
// PriorityPolicy.java:22 —— selectCapability 为唯一裁决点
```
所有"谁先执行、谁终止链路"的判断集中在两个点，没有散落 if-else。**新增能力只需加枚举值 + 一个 Handler**，符合开闭原则。

**优点 R-5：DSL 具备编译期类型安全**

`RouteDsl` 按能力分子类（`MonitorApiDsl` / `MockApiDsl` / `InterceptMockDsl` …），`done()` 时校验配置完整性并自动切换 collect-only 模式（`RouteDsl.java:632-645`）。用户无法写出"配了 monitor 却忘记 enable"的非法状态——**这是 API 设计的成熟表现**。

**优点 R-6：core/handler 解耦有门禁保障**
`RouteEngine` 不 import 任何具体 Handler，通过 `RouteHandlerRegistry` 反向注册（`ArchitectureTest.java:72-79` 的 C1 规则固化）。

**优点 R-7：模板方法消除重复**
与 api 模块一致，`AbstractRestJob` 式的上提思路在此也有体现。

**问题 R-8（中）：`RouteLiteralPathCache` 自陈无调用点**
`RouteLiteralPathCache.java:13-18` 标注 `@Deprecated` 且注释说明无调用点——死代码未清理。

**问题 R-9（中）：条件匹配层正则无预编译缓存**
`RoutePatternCache` 仅缓存 glob→正则的转换（`RoutePatternCache.java:19`），但 `matchBodyRegex` 等**条件层**匹配是否复用编译结果未确认。逐请求正则编译在高 QPS 拦截下是可观开销。

---

### D3 并发与线程安全 —— 4.0 / 5 ✅ **全项目最佳**

**优点 R-10：并发原语使用成熟**

| 机制 | 位置 | 作用 |
|---|---|---|
| `WeakHashMap` 弱键 | `RouteRegistry.java:61-62` | **防止 Context 关闭后注册表泄漏** |
| `AtomicInteger` times / `AtomicBoolean` stopped / `AtomicReference` timeoutFuture | 多处 | 无锁状态机 |
| `ConcurrentHashMap` | 多处 | 并发注册表 |
| per-Context 引擎 | `PerContextEngine.java:21` | 上下文隔离 |

**特别值得肯定的是 `WeakHashMap` 的使用**——这直接解决了 core 模块 `CONTEXT_SCHEDULERS`（`AsyncPool.java:52`）**没有解决**的同类问题。同项目内两个模块对同一问题的处理水平不一致，说明 route 的作者具备更强的并发素养。

**问题 R-11（中）：`DISPATCHED_ROUTES` 桶仅靠容量上限清理**
`RouteContextState.java:66-70` 仅在 `size >= 500` 时清空，context 关闭时未显式 remove 桶。长生命周期场景存在累积。

**问题 R-12（中）：Monitor 回调串行队列丢弃风险**
回调经 `AsyncPool.runOnMonitorCallbackThread` 投递到**单线程有界队列（10,000）**，满则丢弃。虽有计数告警，但**丢弃即意味着断言未执行**——在高流量页面上，监控断言可能被静默跳过。

---

### D4 生命周期与资源治理 —— 3.5 / 5

**优点 R-13：`StoppedCapabilityManager` 考虑周全**
能力停止（stop）后的状态管理独立成类，避免散落的 boolean 标志。

**优点 R-14：`ApiCaptureLifecycle` 与 core 的 `RouteLifecycle` SPI 对接正确**
通过 `RouteLifecycleRegistry` 注册，使 core 无需感知 route 实现。

**问题 R-15（中）：持久化表无清理策略**
`ApiMonitoringRepository.java:386-440` 仅建表 + 3 个索引，**无 purge / 归档 / 分区**。长跑套件的 `route_monitor_record` 表会无限增长，最终拖慢查询甚至撑爆磁盘。企业级必须有数据保留策略（如保留 30 天 + 定时归档）。

---

### D5 配置与多环境 —— 3.0 / 5 ⚠️

**问题 R-16（中）：持久化配置跨模块借道 web**
数据库连接配置读 web 的 `FrameworkConfig`（见 R-1），route 自身无配置入口。这是 R-1 的直接后果。

**优点 R-17：`ApiMonitorConfig` 独立可配**
`test-automation/src/test/resources/config/api-monitor-config.json` 提供监控专属配置，与全局配置分离。

---

### D6 错误处理与可观测性 —— 3.5 / 5 ⚠️

**问题 R-18（P0/严重）：断言失败可能不置用例失败**

```java
// HandlerExecutor.java:261-267
catch (ApiAssertionException e) {
    LOGGER.error(...);
    setFailFast(...);          // ← 仅置标志 + 落日志
    // 不 rethrow，未确认接入 Serenity 失败上报
}
```
若外部 fail-fast 信号未被 Serenity 消费，**接口断言失败只进日志和报告，用例仍判 PASS**。

在金融级测试体系中，这是**不可接受的风险**：测试通过了，但断言其实失败了。它比"测试挂了"危险得多——后者会阻止发布，前者会让缺陷流入生产。

**问题 R-19（中）：过量空 catch（~110+ 处）**
`Dispatcher.java:185,235,260`、`MockHandler.java:158,222` 等大量 `catch (Exception ignored)`。多数有注释说明是防御 page-closed 竞态（合理），但**数量过大且无分类标记**，会掩盖真实错误。建议引入"预期异常白名单"收口。

**优点 R-20：`AssertionFailureDetail` 结构化失败详情**
断言失败信息结构化（字段、期望值、实际值、路径），优于字符串拼接，便于报告渲染与定位。

---

### D7 安全与合规 —— 4.0 / 5 ✅

**优点 R-21：持久化全链路参数化 + 事务**
`ApiMonitoringRepository.java:371-375` 参数化 SQL（**无注入风险**）；`337-362` 行 `setAutoCommit(false) + commit/rollback` 显式事务控制；`234,307` 行批量失败重入队 + attempts ≤ 3 + `PENDING_HARD_CAP` 限流。**持久化健壮性达到生产级**。

**优点 R-22：脱敏收口覆盖全部出口**
`DatabaseStoreMonitorCallback.java:102-105`（DB）、`ApiMonitoringRecord.java:34`（记录）、`FileStoreMonitorCallback.java:301`（文件）——三处统一调用 `SensitiveDataSanitizer`。

**优点 R-23：报告文件权限 600**
`MonitorFailureReportWriter.java:99` 显式设置文件权限为仅属主可读，防止敏感失败详情泄露。这个细节在多数测试框架中被忽略。

---

### D8 可测试性与质量门禁 —— 3.0 / 5

**问题 R-24（中）：`route/src/test` 为空**
51 个类零自有测试，全部测试（约 15 个 `Route*Test`）堆在 `test-automation`。

**优点 R-25：测试质量高于其他模块**
`RoutePriorityContractTest`、`RouteCapabilityContractTest`、`RouteCrossLayerMergeTest`、`RouteSameApiMultiRuleMergeTest`、`RouteUnifiedScopeTest` 等——**采用契约测试（Contract Test）而非实现测试**，验证的是规则语义而非私有方法。这是正确的测试策略。

---

## 三、问题清单

| ID | 级别 | 问题 | 证据 |
|---|---|---|---|
| R-18 | **P0** | 断言失败仅落日志 + 置标志，不 rethrow，可能不置用例失败 | `HandlerExecutor.java:261-267` |
| R-1 | **P1** | 分层倒置：route 依赖 web 的 FrameworkConfig / HikariConfigFactory | `DatabaseStoreMonitorCallback.java:3-4`；`ApiMonitoringRepository.java:154` |
| R-3 | **P1** | ArchUnit 切片盲区，无法检出 route→web | `ArchitectureTest.java:106` |
| R-2 | **P1** | 包名 `web.route` 与模块名 route 错配 | 包结构 |
| R-15 | **P1** | 监控记录表无清理/归档策略，无限增长 | `ApiMonitoringRepository.java:386-440` |
| R-12 | **P1** | Monitor 回调队列满则丢弃，断言被静默跳过 | `AsyncPool.java:423-429` |
| R-24 | **P2** | `route/src/test` 为空 | 目录实证 |
| R-9 | **P2** | 条件层正则无预编译缓存 | `RoutePatternCache.java:19`（仅 pattern 层） |
| R-11 | **P2** | `DISPATCHED_ROUTES` 桶未在 context 关闭时清理 | `RouteContextState.java:66-70` |
| R-19 | **P2** | ~110+ 空 catch 未分类收口 | `Dispatcher.java:185,235,260` 等 |
| R-8 | **P2** | `RouteLiteralPathCache` 死代码 | `RouteLiteralPathCache.java:13-18` |

---

## 四、整改任务列表（route 模块）

### P0 —— 阻断级

| 任务 ID | 任务 | 验收标准 | 工时 |
|---|---|---|---:|
| **ROUTE-P0-1** | **断言失败必须置用例失败**：`HandlerExecutor` 捕获 `ApiAssertionException` 后，除落日志外，必须显式上报 Serenity（`StepEventBus` / `AssertionError` 抛出二选一，并确认端到端生效） | ① 新增端到端用例：故意配置一条必失败的 monitor 断言，运行后**用例必须 FAIL**；② 报告中出现该失败；③ 现有"预期失败"场景不被误伤 | 2d |

### P1 —— 重要

| 任务 ID | 任务 | 验收标准 | 工时 |
|---|---|---|---:|
| **ROUTE-P1-1** | **解除 route → web 依赖**：将 DB 持久化所需配置（JDBC URL/用户/池参数）与 `HikariConfigFactory` 下沉到 core（或新建 `framework-persistence`），route 只依赖 core | ① `route` 源码中无 `import ...framework.web.*`；② `route/pom.xml` 移除 `framework-web`；③ 持久化配置改从 route 自身配置源读取，行为不变 | 5d |
| **ROUTE-P1-2** | **包名与模块对齐**：`framework.web.route.*` → `framework.route.*`；同步更新 ArchUnit 规则 | ✅ ① 包重命名完成（全仓 86 文件）；② ArchUnit 新增 L6（route 不得依赖 web）并通过；③ 业务代码 import 路径已更新（全护盾 468 绿） | 2d |
| **ROUTE-P1-3** | **ArchUnit 增加模块级依赖规则**：新增"route 不得依赖 web"显式规则；slices 模式改为按 Maven 模块维度（或显式列出各层包前缀），消除切片盲区 | ① 规则在故意引入 route→web 依赖时失败；② 现有代码通过 | 1d |
| **ROUTE-P1-4** | **监控数据保留策略**：新增 purge 配置（保留天数 / 最大行数 / 归档目标），提供 `cleanup()` 与定时执行入口；建表脚本增加分区或归档表 | ① 可配置保留策略；② 有清理单测；③ 长跑 7 天数据量稳定 | 3d |
| **ROUTE-P1-5** | **回调丢弃可感知**：Monitor 回调被丢弃时，除计数外必须在**测试报告**中显式标记"本轮有 N 条断言未执行"，且可选配置为使构建失败 | ① 报告可见丢弃数；② 有丢弃时退出码可配置为非 0 | 2d |

### P2 —— 优化

| 任务 ID | 任务 | 验收标准 | 工时 |
|---|---|---|---:|
| **ROUTE-P2-1** | 条件层正则预编译缓存（扩展 `RoutePatternCache` 覆盖 `matchBodyRegex`） | 压测下正则编译次数为 0 增长 | 1d |
| **ROUTE-P2-2** | `DISPATCHED_ROUTES` 在 context 关闭时显式清理 | context 关闭后桶被移除 | 1d |
| **ROUTE-P2-3** | 空 catch 分类治理：引入 `@ExpectedException` 语义或集中 `RouteErrors.ignoreIfPageClosed(e)`，禁止裸 catch | 裸 catch 数量下降 ≥ 80% | 3d |
| **ROUTE-P2-4** | 移除 `RouteLiteralPathCache` 死代码 | 无 @Deprecated 无调用点类 | 0.5d |
| **ROUTE-P2-5** | 将 test-automation 中的 15 个 route 契约测试迁回 `route/src/test` | 测试位于模块内；CI 可独立跑 | 1d |

---

## 五、给架构决策者的建议

**`route` 是本项目的标杆模块，应当作为其他模块重构的参考范式。**

它在三件事上做对了，而这正是 core / web / api 做得不够的：

| route 做对的 | 其他模块的对照 |
|---|---|
| 优先级/执行序**单一收敛**于 `RouteHandleType` + `PriorityPolicy` | web 的浏览器策略散落在 `PlaywrightManager` 9 处静态状态 |
| 泄漏防护用 `WeakHashMap` 弱键 | core 的 `CONTEXT_SCHEDULERS` 无兜底，靠调用方自觉 |
| DSL **编译期**防非法状态 | api 的 `ConfigKeys` 靠 `toString()` 运行时约定 |

**因此，route 的整改重点不是"重做"，而是"解除外部束缚"**：
- R-1（依赖倒置）与 R-2（包名错配）是历史包袱，解开后这个模块几乎可以直接作为独立组件发布；
- R-18（断言不上报）是唯一的功能性 P0，必须优先修复——它直接影响测试结果的可信度。

**建议**：把 route 的 `WeakHashMap` 防泄漏、`RouteHandleType` 单一裁决点、`RouteDsl` 编译期校验三条实践，提炼为团队级的《框架设计约定》，反向输出到 core / web / api 的重构中。这比逐个模块拍脑袋修复更有效。
