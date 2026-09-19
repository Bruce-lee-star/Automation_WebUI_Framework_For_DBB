# 06 route 模块：流量路由、录制回放与监控

> 评审范围：`route/src/main/java/.../framework/route/**`（57 java + 5 sql）
> 评审基线：`e11a847`

---

## 一、模块职责

**先澄清定位**：route 不是"测试用例路由"，而是**基于 Playwright `route` 拦截的 API 流量治理模块**——兼具 mock server、响应改写、流量录制与接口监控四种能力。

证据：`handler/MockHandler.java:29-31` 提供纯 Mock 与 `interceptRealResponse` 真实响应改写；`core/engine/RouteEngine.java:369` 注释明确 `page.route(pattern, route -> dispatchRoute(...))`。

| 包 | 职责 |
|---|---|
| `core/engine` | `RouteEngine` 注册 + `Dispatcher` 分发（防重门控、能力选择） |
| `core/rule` | `RouteRule` / `RuleRepository` / `PriorityPolicy`，pattern 匹配 |
| `core/capture` | `ApiCaptureManager` / `ApiCaptureLifecycle` / `ApiCaptureStore` 流量采集 |
| `core/lifecycle` | `RouteLifecycleImpl`，SPI 自注册 |
| `core/spi` | 回调倒置契约 |
| `dsl` | `RouteDsl` 流式 Java DSL（monitor / mock / modify / delay 四个分支） |
| `handler` | `MockHandler` / `ModifyHandler` / `MonitorHandler` / `DelayHandler` |
| `monitor` | `ApiMonitorOrchestrator` / `MonitorFailureCollector` |
| `persistence` | `ApiMonitoringRepository`（HikariCP 异步批量写库） |
| `body` / `util` | 请求响应体处理与工具 |

---

## 二、现状评估

### 2.1 架构纯度（最好的部分）

`route` 模块**完全不依赖 `web`**（全量 grep `framework.web` 零命中），只依赖 `core/common`。`web` 与 `route` 都依赖 `common.route.RouteLifecycle` 接口，`route` 通过 `RouteLifecycleImpl:18-22` 自注册实现 **SPI 依赖倒置**。

**没有循环依赖，方向干净。** 这是依赖倒置原则的教科书级应用——route 需要感知 web 的生命周期，但通过 core 里的接口完成，物理依赖不反向。

### 2.2 数据流

```
context.route(pattern, handler)          ← RuleRepository:171
   └─ Dispatcher.dispatchRoute(route)    ← Dispatcher:44-257
        ├─ 规则匹配（pattern + PriorityPolicy）
        ├─ 能力位管线：MOCK(终结) → MODIFY → MONITOR → DELAY
        └─ MockHandler.fulfill / ModifyHandler.fetch+改写 / MonitorHandler 断言
```

能力位管线用"MOCK 终结、其余可叠加"的语义，设计合理。

旁路采集：`ApiCaptureLifecycle:208` 用 `page.onResponse(...)` 做兜底采集，不干扰主链路。

### 2.3 录制数据存储

- **默认内存**：`ApiCaptureStore` / `ApiCaptureContext`（`core/capture/`）。
- **可选落库**：`ApiMonitoringRepository` 异步批量写 MySQL。
- 5 个 `.sql` 是 **Flyway DDL**（如 `mysql/V1__create_route_monitor_record.sql:5`，建 `route_monitor_record` 表），**不是录制内容**。

### 2.4 持久化实现（高质量）

这一段写得相当好：

- `ApiMonitoringRepository:199` 用 `HikariDataSource` 独立连接池；
- `:245-278` `save()` **只入队**，不阻塞用例；
- `:284-296` 单线程定时批量刷；
- `:356-394` `insertBatch` 用 **try-with-resources** 关连接；
- `:400-405` SQL 全部 `?` 占位，**无字符串拼接**；
- `HikariConfigFactory:90-109` 数据源登记进 `ShutdownCoordinator` 关闭。

**无连接泄漏、无 SQL 注入、无同步阻塞**——三点全做对了。

### 2.5 并发安全（主要风险）

两处进程级单例状态：

1. **`ApiCaptureManager`**（`:42,48,66`）：单例 + `volatile currentStore` + `WeakHashMap`；`:205-246` 用 200ms 节流的 `lastResolve` 做去抖。**问题**：全局 `currentStore` 与 per-context store 耦合，并行执行下多个 Context 同时采集时可能**跨 context 串扰**——节流是进程级的，不是 context 级的。
2. **`ApiMonitorOrchestrator.registeredPatterns`**（`:33`）：进程级单例 `Set`，代码注释自己承认 `clear()` **无人调用**，靠 `onClose` 兜底（`:97`）。**问题**：钩子未触发则状态跨 context 残留，下一个 context 会看到上一个的模式集合。

### 2.6 异常被吞

- `persistence/DatabaseStoreMonitorCallback.java:120-122`：写库失败仅 WARN；
- `handler/MonitorHandler.java:496-500`：记录异常仅 ERROR 不抛。

**后果**：监控数据丢失对测试完全不可见。更危险的是——如果 MonitorHandler 的断言本身抛异常被吞，一个"本应失败"的接口校验会静默通过，route 模块提供的**接口监控能力形同虚设**。

---

## 三、优势

1. **依赖倒置用得干净**：route 不依赖 web，通过 core 的 SPI 接口双向解耦，无环。
2. **能力位管线语义清晰**（MOCK 终结 → MODIFY → MONITOR → DELAY 可叠加）。
3. **持久化三点全对**：HikariCP 独立池 + 异步批量 + 全参数化 SQL + 统一关闭登记。
4. **自建 Java DSL**（`RouteDsl:65,307-391`）而非引入 Groovy，依赖更轻、类型更安全。
5. **旁路采集不干扰主链路**（`page.onResponse` 兜底）。
6. **Flyway 管理 DDL**，schema 演进可追溯。

---

## 四、风险与问题

| 编号 | 级别 | 问题 | 证据 | 影响 |
|---|---|---|---|---|
| R-1 | **P0** | `MonitorHandler` 断言异常被吞（仅 ERROR 不抛），接口监控失败静默通过 | `MonitorHandler.java:496-500` | 监控能力失效，缺陷被掩盖 |
| R-2 | **P1** | `ApiMonitorOrchestrator.registeredPatterns` 进程级单例，`clear()` 无人调用 | `:33,:97` | 并行/多 context 下模式串扰 |
| R-3 | **P1** | `ApiCaptureManager` 全局 `currentStore` + 200ms 进程级节流 | `:42,48,66,:205-246` | 并行采集跨 context 串扰 |
| R-4 | **P1（已修复 2026-09-17）** | 写库失败仅 WARN，监控数据丢失不可见 | `ApiMonitoringRepository` 丢弃点 + `MonitorFailureReportWriter` 报告尾部 | 数据完整性无保障 → 现经 `MonitorDataLossReporter` 计数并在汇总报告尾部红色提示，丢失可见 |
| R-5 | **P2** | 录制数据默认内存，无容量上限与淘汰策略 | `ApiCaptureStore` | 长跑场景 OOM 风险 |
| R-6 | **P2** | DSL 与 `RuleRepository` 的双入口（`context.route` vs DSL）关系未文档化 | — | 使用者易选错入口 |
| R-7 | **P2** | 模块命名 `route` 歧义大（易被误读为"用例路由"） **已决策：保留 `route`，不重命名**。① `route` 是 **Playwright 平台词汇**（`page.route(pattern, handler)` / `Route` / `unroute`），本模块即该能力的治理层，模块内 23+ 处 `import com.microsoft.playwright.Route` —— 改名 `traffic` 会与「每个类都在调用的平台 API」产生**新**错位；② 本仓**不存在**「用例路由 / Cucumber 路由」构造（该词只出现在本评审文档的假设描述里），词面联想没有实际混淆源；③ 改名成本被低估：模块目录 / `artifactId` / 61 个 java 的包名 / 依赖方 pom / ArchUnit 谓词 / `spotbugs-exclude.xml` / 示例应用 `route-demo-*` / Gherkin 标签 `@route*` / 7 篇评审文档，而收益是 0 功能价值；④ 陈述危害「新人理解成本高」改由 **`route/package-info.java` 术语定位说明**关闭 | ~~—~~ | ~~新人理解成本高~~ |

---

## 五、优化方案

### 5.1 监控断言失败必须冒泡（P0）

这是 route 模块的**核心价值**所在——如果监控结果不影响测试结果，整个模块就没有存在意义。

```java
// MonitorHandler.java 现状（:496-500）
try {
    assertion.check(response);
} catch (RuntimeException e) {
    LOGGER.error("监控断言异常", e);        // ← 吞掉了
}

// 改为：可配置严格模式，默认严格
public void check(Response response, MonitorAssertion assertion) {
    try {
        assertion.check(response);
    } catch (AssertionError | RuntimeException e) {
        // 1) 无论严格与否，都要进失败收集器（供报告展示）
        MonitorFailureCollector.record(assertion.ruleId(), response, e);
        // 2) 严格模式下必须冒泡，让用例失败
        if (MonitorConfig.isStrict()) {
            throw new FrameworkException(
                "接口监控断言失败: " + assertion.describe()
                + " | url=" + response.url() + " status=" + response.status(), e);
        }
        LOGGER.error("[ROUTE] 监控断言失败（非严格模式，已忽略）: {}",
                     assertion.describe(), e);
    }
}
```

同时在 Serenity 报告里体现（经 `StepEventBus`），保证即使非严格模式，失败也**可见**。

### 5.2 消除进程级单例状态（P1）

把 `registeredPatterns` 从进程级改为 context 级：

```java
// 现状：static Set
private static final Set<String> registeredPatterns = ConcurrentHashMap.newKeySet();

// 改法：挂在 Context 上，随 Context 生命周期自动回收
public final class RouteMonitorScope {
    private static final ClassValue<Map<String, RouteMonitorState>> BY_CONTEXT =
        new ClassValue<>() { protected Map<String, RouteMonitorState> computeValue(Class<?> t) {
            return new ConcurrentHashMap<>(); }};

    // 更直接：直接用 BrowserContext 的存储态或 TestContextHolder
    public static RouteMonitorState of(String contextId) {
        return TestContextHolder.current().computeIfAbsent(
            "route.monitor", k -> new RouteMonitorState());
    }
}
```

`ApiCaptureManager` 同理：把 `currentStore` 从全局 volatile 改为 per-context 查找，节流窗口也 per-context 独立。

```java
private ApiCaptureStore storeFor(String contextId) {
    return captureStores.computeIfAbsent(contextId, id -> new ApiCaptureStore(id));
}
private boolean throttled(String contextId, long nowMillis) {
    Long last = lastResolveByContext.get(contextId);   // 不再用单一 lastResolve
    if (last != null && nowMillis - last < THROTTLE_MS) return true;
    lastResolveByContext.put(contextId, nowMillis);
    return false;
}
```

### 5.3 写库失败可见化（P1）

```java
// DatabaseStoreMonitorCallback —— 加计数器与失败汇总
private static final AtomicLong DROPPED = new AtomicLong();

@Override public void onError(Throwable t, List<MonitorRecord> batch) {
    long dropped = DROPPED.addAndGet(batch.size());
    LOGGER.error("[ROUTE] 监控记录入持久化失败，丢弃 {} 条（累计 {}）。原因: {}",
                 batch.size(), dropped, t.getMessage());
    // 关键：写入一个"数据完整性摘要"，在报告尾部展示
    DataIntegrityReport.recordLoss("route_monitor_record", batch.size(), t);
}
```

并在汇总报告（07 号文档）里加一行"监控数据丢弃 N 条"的红色提示——**让丢失可见，比保证不丢失更现实**。

### 5.4 给内存录制加上限（P2）

```java
public final class ApiCaptureStore {
    private static final int MAX_ENTRIES =
        Integer.getInteger("route.capture.max.entries", 5000);
    private final Queue<CapturedExchange> buffer = new ArrayDeque<>();

    public synchronized void add(CapturedExchange e) {
        while (buffer.size() >= MAX_ENTRIES) {
            buffer.poll();      // FIFO 淘汰最旧
        }
        buffer.offer(e);
    }
}
```

### 5.5 ~~重命名以消除歧义（P2）~~ → 已决策：**不重命名**（2026-09-17）

原建议 `route` → `traffic`（或 `apitraffic`）。**复核后不采纳**：

1. **`route` 是 Playwright 的平台词汇**：`page.route(pattern, handler)` / `Route` / `unroute`。本模块正是该能力的治理层，模块内 23+ 个类直接 `import com.microsoft.playwright.Route` 并调用 `context.route(...)`；改名后会出现「模块叫 `traffic`、而每个类都在用 `Route`」的**新**错位——问题只是从一个词搬到另一个词，还丢掉了与所封装平台的一致性。
2. **「用例路由 / Cucumber 路由」在本仓不存在**：全仓检索只命中本评审文档自身的假设性描述，没有可混淆的真实构造——原论证的这一半是**推测性**的。
3. **成本被低估**：不止「IDE 全局重构 + 一次提交」——模块目录、`artifactId`、61 个 java 的包名、所有依赖方 pom、ArchUnit 包谓词、`spotbugs-exclude.xml`、示例应用 `route-demo-web/service`、Gherkin 标签 `@route*`、7 篇评审文档都需同步；而收益为 0 功能价值。
4. **同一词的两处用法是「抽象/实现」分层而非冲突**：`framework.common.route`（core）承载本能力的核心侧抽象与生命周期 SPI（`RouteLifecycle` / `RouteLifecycleRegistry`，为满足「core 不得反向依赖 route 模块」），与 `framework.route`（实现模块）是分层关系。

**替代落地**：新增 `route/package-info.java`，把定位与术语一次写死在**代码级文档**里（IDE 可见、随代码演进）：明确「`route` = Playwright 网络拦截」、与 `common.route` 抽象的关系，并显式排除「URL 路由 / 用例路由」两种误解。新人理解成本由**文档**解决，而不是靠重命名。

### 5.6 并发 / 线程 / 资源清理专项评审（2026-09-17）

针对「会不会竞态、线程会不会不释放、资源能不能即时清理」三问，逐条核对并发原语、线程池、注册表键强度与清理链路。

#### 结论一：竞态 —— 真修 3 处，另 1 类为误报

| # | 发现 | 性质 | 处置 |
|---|---|---|---|
| 1 | `PerContextEngine.contextId` 原为 `Integer.toHexString(System.identityHashCode(context))`，而它是 `AsyncPool.CONTEXT_SCHEDULERS` 的 key；该 Map 以「覆盖 put / 按 key remove」维护 | **真**：key 碰撞 → 旧池失去跟踪、`removeContextScheduler` 误删他池条目 → 「活跃 context 调度器数」这一**泄漏判据失真** | 改为「自增序号 + identityHashCode」（唯一性由序号保证，hash 保留供排查）；`AsyncPool.newContextScheduler` 增加碰撞 WARN，使未来回归显式暴露 |
| 2 | `CapturedApiCall.bodyTruncated / originalBodyBytes` 非 final 非 volatile，可经 `markBodyTruncated` 在**对象发布之后**修改；对象跨线程共享（route 事件线程写、主测试线程断言读） | **真（当前无写者）**：读方可能看到陈旧值（`bodyTruncated=false` / `originalBodyBytes=0`）；该 setter 全仓无调用方，故目前属**潜在**缺陷 | 两字段改 `volatile`；对应 SpotBugs 基线条目随之**真修并清理**（不再"长期冻结"） |
| 3 | `RouteRule.hashCodeCached / cachedHashCode` 非 volatile，而 RouteRule 是 `ConcurrentHashMap` 的 key | **真**：可见性竞态（本轮前一阶段已修） | 改 `volatile` |
| 4 | `NN_NAKED_NOTIFY`（`ApiCaptureContext` 的 increment/decrement/reset/signalFailFast） | **误报**：notify 与「检查 + wait」同在 `completionLock` 监视器内，不存在「检查后、wait 前」的漏唤醒窗口；计数本身是 `AtomicInteger`（其状态变更不在监视器内，故被静态分析判为 naked）。等待循环均带 deadline，`inWaitState` 在 `finally` 复位 | 保留原实现，记录判定理由（不抑制、不改动） |

#### 结论二：线程释放 —— 链路完整，但发现 1 处「延后释放且不可观测」

**释放链路（核对通过）**：`ShutdownCoordinator` 已登记 5 个关闭任务（API monitor flush 100 / route engine 200 / monitor handler 300 / Hikari 800 / framework core 900）；`AsyncPool.shutdown()` 统一关闭通用池、**所有 per-context 池**与 monitor 回调线程；context 清理链完整（`PlaywrightContextManager.closeContext` → `RouteLifecycleRegistry.clearContext` + `stopContextEngine(context)`；`BrowserCleanupImpl` → `stopAllContextEngines` + `clearAll` + `AsyncPool.shutdown`）。

**发现（真）**：per-context 的 DELAY 调度器池在关闭时**不会**把「尚未到期」的任务交给 `shutdownNow()` 取消或返回（`DelayedWorkQueue.drainTo` 只排空已到期任务，且默认 `executeExistingDelayedTasksAfterShutdownPolicy=true`），它们被**保留到原定延时后才执行** —— 池与线程要存活到那一刻；而 `close()` 已把池移出 `AsyncPool` 跟踪表，这段滞留**不可观测**（长 DELAY 下线程长期挂账）。全局 `DELAY_SCHEDULER` 同样如此。

**处置**：两处关闭路径统一改为「**趁池仍 RUNNING** 取出待发任务 → 立即执行 → 再关池」，把「池/线程存活期」从「原定延迟」压缩到「即刻」，并保证这些动作（`RouteUtil.safeResume`，即放行被拦截请求）绝不丢失。

> **踩坑记录（顺序不可调换）**：队列中的元素是 `ScheduledFutureTask`，其 `run()` 会先按「策略 + 池状态」自判：池处于 SHUTDOWN 且策略为 false、或已进入 STOP（`shutdownNow`）时，`run()` 会**自我取消**而不执行动作 —— 因此**不能**「先关池、后执行排水任务」（实测：请求永不 resume），必须「先取队列 → 立即执行 → 再关池」。另注：`scheduleDeferred` 的 catch 只覆盖「**提交时**被拒」（`RejectedExecutionException`），覆盖不到「**已入队**」的任务，二者互补而非重复。

**顺带修复**：`DelayScheduler.delayScheduler(Route)` 在全局拆除后仍会 `getOrStartContextEngine` 创建新的 per-context 引擎（在 `AsyncPool.shutdown()` 之后注册新池，任何扫描都不再覆盖它）→ 已按 `scheduledShutdown` 短路（与早前 C1 修复的「全局池拆除后复活」同源，当初漏了 per-context 路径）。

#### 结论三：资源即时清理 —— 上限齐备、弱键用在对处，另补一道守卫

- **上限（核对通过）**：`ResponseStore` 四组上限（总字节 50MB 可配 / `recentCalls` 500 / 每 endpoint 100 / 每 requestUrl 100，淘汰时**对称回减**字节计数）；`MonitorFailureCollector` 500 条 LRU + 单条 body 4096 字符；`DISPATCHED_ROUTES` 单 context 500；`ApiTrafficLogger` 单文件 100MB 后停止写入。
- **弱键（核对通过）**：`ApiCaptureContext.BY_CONTEXT`、`ApiCaptureManager.contextStores`、`ThreadContextRegistry` 均为弱键；`RouteRegistry` 用 `ContextKey` 弱引用包装；`RouteContextState` 四张表为**强键**（按设计，依赖清理链显式移除，正常路径已覆盖）。
- **新增守卫**：`RouteEngineResourceLifecycleTest`（4 例）把上述不变量变成可执行断言 —— ① 120 个引擎的 `contextId` 必须唯一且被 `AsyncPool` 全量跟踪，关闭后活跃数回落基线；② 关闭时待发的 DELAY 任务必须**立即执行**（10s 任务不得等满原定延迟）；③ 排水器对单任务异常做隔离；④ 空/null 入参安全。

**评审总评**：route 的并发/生命周期**底子是好的**（注册表收口、上限齐备、关闭编排统一、弱键用在对的地方）；本轮修掉的是**边界时序**问题（key 唯一性、发布后可变字段、关闭时的队列语义、拆除后复活），并把「线程/池是否即时释放」从"靠代码审查"变成"靠测试断言"。

#### 5.6.1 第二轮：MonitorHandler body 读取重试治理（2026-09-17，用户指示 #4）

`MonitorHandler` 的 body 读取重试链（`readResponseBodyWithRetry` + `retryBodyOnce`）原存在四个问题，全部处置：

| # | 问题 | 处置 |
|---|---|---|
| 1 | 调度器为 **单线程**（`newSingleThreadScheduledExecutor`）且被**所有** BrowserContext 共享 → 并行下各 context 的退避重试串行到同一线程（跨 context 串行瓶颈） | 改为线程数可配的 `ScheduledThreadPoolExecutor`（`monitor.body.read.scheduler.threads`，默认 4；守护线程、`removeOnCancelPolicy`） |
| 2 | 基础尝试次数 3 / 间隔 50ms **硬编码**，无法调参 | 全部可配：`monitor.body.read.base.attempts`（3）、`monitor.body.read.retry.interval.ms`（50）；策略抽为纯函数 `computeMaxAttempts` / `computeBudgetMs`，便于调参与单测 |
| 3 | 等待预算随 DELAY **线性放大且无上限**（DELAY 60s → 1200 次尝试 → 65s）→ 一旦重试链中断/调度器异常，`future.get` 会把 route 事件线程占住**分钟级**（"卡程序"） | 预算收敛为 `min(尝试总时长 + 5s 余量, 上限)`，上限由 `monitor.body.read.max.wait.ms`（默认 **30s**）配置；重试链本身亦受 `deadline` 约束，不再产生"用例已结束仍在续投"的残链 |
| 4 | 用例/上下文结束后，在途重试**无人取消** —— 等待方要耗尽整个预算才返回（"用例跑完还在等 timeout"） | 新增 **per-context 在途任务登记**（core 的 `RouteContextState`）：`RouteEngine.stopContextEngine` 在上下文收口时取消该 context 的全部在途重试 → 等待方立即抛 `CancellationException` 走兜底；JVM 收尾时统一取消全部 |

> **分层修正（被 ArchUnit 拦下，值得记录）**：首版把取消入口直接写在 `RouteEngine` 里调 `MonitorHandler`，触发 `routeCoreMustNotDependOnRouteHandler` 违规 —— `route.core.*` 不得依赖 `route.handler.*`。正解是**能力下沉 + 登记表在 core**：`RouteContextState` 提供 `registerPendingTask` / `cancelPendingTasksFor` / `cancelAllPendingTasks`（core 的 per-context 在途任务表），handler 侧只登记，core 侧统一取消。架构门禁在此次改动中**实际拦住了越层**，说明规则是活的。

**新增守卫**：`MonitorHandlerRetryPolicyTest`（4 例）—— 预算被上限截断 / `maxWait<=0` 表示不设上限 / 尝试数随 DELAY 推导 / `null` context 取消为 no-op / 默认策略合理。

#### 5.6.2 第三轮：强键注册表归零守卫（C-11）/ 旧包名清洗（C-12）/ web 弱键对齐（core 专项，2026-09-17）

> 承接 5.6 / 5.6.1 的「边界时序」修复，本轮收口三类收尾期强引用债。全部已落地、route 全护盾 29 例全绿（含新增守卫 2）。

**C-11 强键注册表归零守卫**

`cleanupClosedContext`（上下文收口末段）原只清路由层强键表（`CONTEXT_RULES_BY_CONTEXT` / `DISPATCHED_ROUTES` / `STOPPED_CAPS`）；引擎层 `CONTEXT_ENGINES` 与在途 `PENDING_TASKS` 仅靠 `stopContextEngine` 中 `RouteLifecycleOwner` + `cancelPendingTasksFor` 在其**之前**清理，`cleanupClosedContext` 自身未兜底 —— 若被独立调用则残留强引用。

| # | 发现 | 处置 |
|---|---|---|
| 1 | `cleanupClosedContext` 未统一兜底清零所有强键表 | `RouteContextState` 新增 `removeContextFromAllRegistries(BrowserContext)`：**幂等**，从全部强键表（`CONTEXT_RULES_BY_CONTEXT` / `DISPATCHED_ROUTES` / `STOPPED_CAPS` / `CONTEXT_ENGINES`）移除该 context 并取消其在途任务（`cancelPendingTasksFor`），在 `RuleRepository.cleanupClosedContext` 末段调用作「归零」权威兜底 —— 新增强键表时遗漏清理即由该守卫兜底，杜绝泄漏回归 |
| 2 | 守卫需可断言、且不能越界清零其它 context | 新增 `RouteContextStateZeroGuardTest`（2 例）：① 仅目标 context 在全部强键表归零、在途任务被取消，且其它 context 不受影响（无越界清零）；② `RouteEngine.cleanupClosedContext` 经该守卫在收口末段统一兜底清零 |

**C-12 旧包名 `framework.web.route` 清洗**

`test-automation` 下 `framework/web/route/**` 整树（15 文件：capture×10 / engine×2 / lifecycle×1 / monitor×1 / persistence×1）包声明早已改为 `framework.route.*`，但**磁盘目录仍残留旧 `web` 段**（javac 按包名出 class 故能编译，但目录结构与包不一致）。

| # | 发现 | 处置 |
|---|---|---|
| 1 | 目录 `framework/web/route/**` 与包 `framework.route.*` 不一致 | `git mv` 整树迁到 `framework/route/**`（15 文件，包名零改动、仅路径对齐）；`ApiMonitorOrchestratorTest` 包声明 `framework.web.route.monitor` → `framework.route.monitor`（随路径对齐修正） |
| 2 | 残留过期 `framework.web.route` FQCN 引用 | `MonitorFailureReportSinkSpiTest` javadoc、`route-demo-service/application.yml`（`com.hsbc...framework.web.route: TRACE` → `framework.route`）、`test-automation/spotbugs-exclude.xml`（过期 `framework.web.route.core.RouteMonitorSessionTest` FQCN → `framework.route.core.lifecycle.RouteMonitorSessionTest`）三处修正；全仓 `framework.web.route` 引用清零 |

**core 专项（web 弱键对齐）**

| # | 发现 | 处置 |
|---|---|---|
| 1 | `PlaywrightRuntimeState.disconnectedBrowsers` 用强引用 `ConcurrentHashMap.newKeySet()`，而其兄弟 `closingBrowsers` 已用弱键 | 改为弱引用 Set（`Collections.synchronizedSet(newSetFromMap(new WeakHashMap<>()))`，与 `closingBrowsers` 同口径：Browser 被 GC 后条目自失效、不延长生命周期、无引用泄漏；不主动移除条目以覆盖 `onDisconnected` 异步回调晚于 `close()` 返回） |
| 2 | `AsyncPool` / `ShutdownCoordinator` / 其余弱键类复核 | 经审健壮、无改动必要：`AsyncPool`（容量防御 + `identityHashCode` 碰撞 WARN + JVM 收尾 `cancelAllPendingTasks` 兜底）；`ShutdownCoordinator`（幂等 `runAll` + 安全 `register` + 顺序常量）；弱键类 `ThreadContextRegistry`（WeakHashMap<Thread>）、`ScenarioContext`（String 主键 + ThreadLocal，显式 begin/end 清理）、`ApiCaptureManager`（WeakHashMap<BrowserContext>）、`PlaywrightRuntimeState.closingBrowsers`（弱键）均正确 |

**本轮评审总评**：route 收尾期强键表现由 `removeContextFromAllRegistries` 单一守卫兜底清零（覆盖路由层 + 引擎层 + 在途任务），新增强键表无法再「遗漏清理」；web 侧崩溃 Browser 强引用滞留债已对齐弱键收口；test-automation 包路径与包声明彻底一致。

#### 5.6.3 第四轮：MonitorHandler 事件线程即时放行 + 二次 resume 幂等收敛（2026-09-19，P0-3 / RT-F1 收口）

P0-3 目标（RT-F1：事件线程同步阻塞）本轮收口，三处协同修复：

| # | 问题 | 处置 |
|---|---|---|
| 1 | `handle()` 对 `delayMs<=0` 把 resume 推迟到 `observationExecutor` 线程，高并发下大量被拦截请求同时挂起等待线程调度 → route@/request@ 竞态与连接失稳 | 像 `ModifyHandler` 一样在**事件线程**立即 `safeResume`（即时释放 route 对象）；观测/断言/记录异步后置，与 DELAY 分支共用 `observeAndRecord` |
| 2 | 观测任务（`observeAndRecordInternal`）在 `handle()` 已放行后**二次 resume** 同一 route → 对已处理 route 再发 CDP 命令，浏览器侧抛 `Cannot find parent object request@... to create route@`，并级联污染同 CDP 连接上的在途命令（`Cannot find command to respond`） | 引入 `resumed`（`AtomicBoolean`）幂等标记，每个 route 严格放行一次；`observeAndRecord` 各兜底路径（页面/context 关闭、无 context、读体降级、观测异常）全部移除二次 resume，改为直接 return；`delayMs>0` 延迟放行经 `RouteEngine.scheduleDeferred` 统一调度（B 方案），与队列饱和兜底共享 `compareAndSet` 保证至多放行一次 |
| 3 | 读体池 `newBodyReadExecutor` 兜底默认 2（与配置默认 `MONITOR_BODY_READ_CONCURRENCY=16` 不一致）→ 高并发下读体串行化打爆单 CDP 连接 | 兜底默认值由 2 对齐为 16；新增 `PlaywrightSafeOps` 工具类（route 包，承载死句柄零触碰 / 安全响应读取等复用逻辑） |

**配套不改行为**：即时读体协调任务（`captureBodyPromptly`）仍由 `handle()` 在响应到达时立即提交，把读取时机前移以规避响应体被浏览器回收（CDP `Object doesn't exist`），观测任务只 `get()` 该 future，与"放行"解耦。

**验证数据**：
- route 模块单测 **53 例**全绿（0 失败 / 0 错误 / 0 跳过，BUILD SUCCESS）
- `ApiCaptureLifecycleListenerTest` **3 例**全绿（listener 幂等注册护盾）
- 真浏览器压测 `RoutePerformanceStressTest` **4 例**全绿（50 并发 × 256KB / 4.5MB 大报文 + 守门场景，断言 `allPassed=true`、响应 `ok=14/nonOk=0`）—— 证明二次 resume 级联污染已根除；残余 `res.body()` attempt 1 偶发 `Object doesn't exist` 由既有 `readResponseBodyWithRetry` 重试 / 降级快照兜底（属预期降级，**非回归**）

#### 5.6.4 第五轮：FileStore 落盘按 scenario 隔离（2026-09-19，P1-9 / RT-C3 收口）

P1-9 目标（RT-C3：FileStore 跨场景串扰）本轮收口。原 `FileStoreMonitorCallback` 持有全局 `counters`（`ConcurrentHashMap<String,AtomicInteger>`）与全局 `currentScenarioKey`/`currentScenarioDir`（单例实例字段），在 Serenity 并行（`threadCount>1`）下：B 场景切换时 `resolveTargetDir()` 内的 `counters.clear()` 会清空<b>正在运行</b>的 A 场景序号，导致跨场景串号 / 串目录（A 的后续捕获被重置为 `endpoint_0` 覆盖自身、或与 B 共享序号）。

修复：去掉全局 scenario 状态，改为按 `scenarioKey` 隔离——
- 新增 `scenarioStates`（`ConcurrentHashMap<String, ScenarioState>`），`ScenarioState` 持有独立子目录与独立计数器；`computeIfAbsent` 保证每 scenario 唯一、不同 scenario 完全隔离。
- `onResponse` 解析 `scenarioKey` 后委托新增的 `writeForScenario`（package-private 测试 seam，允许单测直接注入 `scenarioKey` 验证隔离，无需 Serenity 上下文）；目录与序号均按 `scenarioKey` 分支，不再有全局 `counters.clear()`。
- 平铺模式（未分组 / 取不到 scenario 上下文）退化为 `flatCounters`（JVM 内累计），保留旧行为。
- `buildJson` 的 `scenario` 字段改用传入的 `scenarioKey` 参数，`reset()` 改为清理 `flatCounters` + `scenarioStates`。

**验证数据**：
- route 模块单测 **57 例**全绿（0 失败 / 0 错误 / 0 跳过，BUILD SUCCESS）
- 新增 `FileStoreMonitorCallbackScenarioIsolationTest` **3 例**：① 不同 scenario 写入独立目录、同一 endpoint 序号不跨场景串扰；② 晚到的 scenario 切换不再重置正在运行的 scenario 计数（第三条仍为 `_2`）；③ 平铺模式仍累计序号（旧行为兼容）

**关联**：P0-3、P1-7、P1-9 已完成（P1-7：`recordUnavailable` 落降级快照后 `signalFailFast`，杜绝 fail-open 假绿；P1-9：FileStore 落盘按 `scenarioKey` 隔离，去掉全局计数器与 scenario 切换 `counters.clear()`，根除并行 scenario 串号/串目录，新增 `FileStoreMonitorCallbackScenarioIsolationTest`）；**P0-4（RT-F2）经核查已在代码中落地**（`FileStoreMonitorCallback` 的 `WRITE_EXECUTOR` 单线程异步写盘，事件线程零同步磁盘 IO）。Wave 3 剩余项：P0-5（CG-F1 录制器 CME）、P1-8（RT-C2 落库丢失→失败信号，用户本地有 MySQL 可端到端验证）、P1-10（RT-C4 共享 Context reset 污染）、P1-11~13（CG）。详见 `13_致命缺陷评审` §2 / §4。

---

## 六、结论

route 模块在**架构纯度上是最优秀的**：零循环依赖、SPI 倒置干净、持久化实现（连接池/异步/参数化/统一关闭）三点全对、自建 DSL 而非引入 Groovy。

唯一必须立刻修的是 **R-1：监控断言异常被吞**。一个接口监控框架，如果监控失败不影响测试结果，那它就只是一个日志打印机。这个修复是语义级的一行改动（`throw` 代替 `LOGGER.error`），但决定了这个模块是否真的有价值。
