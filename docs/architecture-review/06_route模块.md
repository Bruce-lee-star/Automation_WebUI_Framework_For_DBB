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
| R-4 | **P1** | 写库失败仅 WARN，监控数据丢失不可见 | `DatabaseStoreMonitorCallback.java:120-122` | 数据完整性无保障 |
| R-5 | **P2** | 录制数据默认内存，无容量上限与淘汰策略 | `ApiCaptureStore` | 长跑场景 OOM 风险 |
| R-6 | **P2** | DSL 与 `RuleRepository` 的双入口（`context.route` vs DSL）关系未文档化 | — | 使用者易选错入口 |
| R-7 | **P2** | 模块命名 `route` 歧义大（易被误读为"用例路由"） | — | 新人理解成本高 |

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

### 5.5 重命名以消除歧义（P2）

建议 `route` → `traffic`（或 `apitraffic`）。当前命名与"Cucumber 路由""用例路由"极易混淆，且 `web/.../common/route` 与 `framework/route` 同名不同义，日常沟通成本高。这是低成本高收益的改动（配合 IDE 全局重构 + 一次提交完成）。

---

## 六、结论

route 模块在**架构纯度上是最优秀的**：零循环依赖、SPI 倒置干净、持久化实现（连接池/异步/参数化/统一关闭）三点全对、自建 DSL 而非引入 Groovy。

唯一必须立刻修的是 **R-1：监控断言异常被吞**。一个接口监控框架，如果监控失败不影响测试结果，那它就只是一个日志打印机。这个修复是语义级的一行改动（`throw` 代替 `LOGGER.error`），但决定了这个模块是否真的有价值。
