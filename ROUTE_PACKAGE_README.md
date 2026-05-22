# Route 路由框架说明文档

> **最后更新：** 2026-05-22 | **版本：** v4.3（ScheduledThreadPoolExecutor 防积压 + DispatchedRoutes 自动清理 + ApiMonitorContext 日志标准化）

`com.hsbc.cmb.hk.dbb.automation.framework.web.route` 包是对 Playwright `page.route()` / `context.route()` 的封装，提供**请求拦截、Mock 响应、请求体修改、API 监控断言**一体化能力，通过流式 DSL 构建规则，简化测试中的网络层控制。

---

## 目 录

- [一、包结构](#一包结构)
- [二、核心架构](#二核心架构)
- [三、RouteHandleType — 三种处理类型](#三routehandletype--三种处理类型)
- [四、核心类详解](#四核心类详解)
  - [4.1 RouteEngine — 路由引擎](#41-routeengine--路由引擎)
  - [4.2 RouteRegistry — 路由注册表](#42-routeregistry--路由注册表)
  - [4.3 RouteRule — 路由规则模型](#43-routerule--路由规则模型)
  - [4.4 RouteDsl — 流式 DSL 构建器](#44-routedsl--流式-dsl-构建器)
  - [4.5 MonitorHandler — 监控处理器](#45-monitorhandler--监控处理器)
  - [4.6 ModifyHandler — 修改处理器](#46-modifyhandler--修改处理器)
  - [4.7 MockHandler — Mock 处理器](#47-mockhandler--mock-处理器)
  - [4.8 ApiMonitorContext — 监控上下文](#48-apimonitorcontext--监控上下文)
  - [4.9 RouteAsyncPool — 异步任务池](#49-routeasyncpool--异步任务池)
  - [4.10 RouteException — 异常体系](#410-routeexception--异常体系)
  - [4.11 SerenityReporter — 报告工具](#411-serenityreporter--报告工具)
  - [🆕 4.12 RouteUtil — 请求条件匹配工具](#412-routeutil--请求条件匹配工具)
- [五、DSL 使用示例](#五dsl-使用示例)
- [六、完整 API 参考](#六完整-api-参考)
- [七、架构优势与设计亮点](#七架构优势与设计亮点)

---

## 一、包结构

```
route/
├── core/                              ← 核心引擎与数据模型
│   ├── RouteEngine.java               # 路由引擎（注册入口，按类型分发到 Handler）
│   ├── RouteHandler.java              # Handler 接口（@FunctionalInterface，解耦引擎与处理器）
│   ├── RouteHandleType.java           # 处理类型枚举（MONITOR / MODIFY / MOCK）
│   ├── RouteRule.java                 # 路由规则数据模型（含参数校验）
│   ├── RouteRegistry.java             # 路由注册表（WeakReference 防泄漏，按上下文隔离）
│   ├── RouteException.java            # 异常体系（配置异常 / 运行时异常 / 断言异常）
│   └── ApiMonitorContext.java         # API 监控上下文（线程隔离断言 + 双重上限响应存储）
├── dsl/
│   └── RouteDsl.java                  # 流式 DSL 构建器（外部调用唯一入口）
├── handler/                           ← 具体处理器
│   ├── MonitorHandler.java            # 监控处理器（放行请求 → 异步断言 + 报告）
│   ├── ModifyHandler.java             # 修改处理器（拦截 → JsonPath 精准替换 → 继续）
│   └── MockHandler.java               # Mock 处理器（拦截 → 直接返回自定义响应）
└── util/
    ├── RouteAsyncPool.java            # 异步任务线程池（守护线程 + 超时监控 + 告警）
    ├── SerenityReporter.java          # Serenity 报告工具（主线程安全）
    └── RouteUtil.java                 # 请求条件匹配工具（ResourceType/Header/Query/Body 等）
```

**共 4 个子包，14 个类文件。**

---

## 二、核心架构

### 请求处理链路

```
RouteDsl.start()
    │
    ▼
RouteEngine.register(context, rules)
    │
    ├── RouteRegistry.register()  → 去重检查（防重复注册同一 URL pattern）
    │
    ├── page.route(pattern, handler)  → 注册 Playwright 路由
    │
    └── dispatchRoute(route, rule)   → 请求到达时
            │
            ├── DISPATCHED_ROUTES 防重门控（同一请求只处理一次）
            │
            ├── 🆕 RouteUtil.requestMatches()  → 请求条件匹配
            │       ├── Resource Type（xhr/fetch/script/image/...）
            │       ├── HTTP Method（GET/POST/...）
            │       ├── Request Headers（精确匹配）
            │       ├── Query Parameters（精确匹配）
            │       ├── Content-Type（包含匹配）
            │       ├── Request Body Regex（正则匹配）
            │       ├── Referrer / Origin（包含匹配）
            │       └── Frame / Navigation（主 Frame + API 限定）
            │
            └── Handler.handle(route, rule)
                    │
                    ├── MonitorHandler: resume() → 异步获取 body → 断言 → 报告
                    ├── ModifyHandler:  修改请求 → resume()
                    └── MockHandler:    fulfill(mockOptions)
```

### 关键设计原则

| 原则 | 实现方式 |
|------|----------|
| **零阻塞 UI** | MonitorHandler 先 `resume()` 放行页面，后异步断言；线程池拒绝策略为 `DiscardOldestPolicy` |
| **异常隔离** | 单规则/单请求失败不影响其他规则和其他请求，所有 Handler 包裹 try-catch |
| **线程安全** | 所有全局状态用 `ConcurrentHashMap`/`AtomicLong` 保护；跨线程传递用 `byte[]` 拷贝 |
| **内存安全** | `RouteRegistry` 使用 `WeakReference` 防泄漏；`ApiMonitorContext` 双重上限防 OOM；`RouteEngine` 防重集合有容量上限 |

---

## 三、RouteHandleType — 三种处理类型

| 类型 | 枚举值 | 行为描述 |
|------|--------|----------|
| **MONITOR** | `MONITOR` | 放行请求 → 异步读取响应体 → 执行状态码/JSONPath 断言 → 写入 Serenity 报告（零阻塞 UI） |
| **MODIFY** | `MODIFY` | 拦截请求 → 修改请求头/请求体/HTTP 方法 → 继续发送（`route.resume()`） |
| **MOCK** | `MOCK` | 拦截请求 → 直接 `route.fulfill()` 返回自定义响应（状态码 + Body + Headers） |

---

## 四、核心类详解

### 4.1 RouteEngine — 路由引擎

**职责**：统一注册入口 + 类型分发 + 防重门控 + MonitorSession 生命周期管理。

**核心数据结构**：

```java
// Handler 映射表（EnumMap 分发，新增 Handler 无需改 switch）
private static final Map<RouteHandleType, RouteHandler> HANDLERS;

// 防重门控 — 同一请求匹配多个重叠 pattern 时只处理一次
private static final Map<Route, Boolean> DISPATCHED_ROUTES
        = new ConcurrentHashMap<>();
private static final int MAX_DISPATCHED_ROUTES = 500;  // 容量上限

// MonitorSession 管理（自动停止）
private static final Map<String, MonitorSession> SESSIONS
        = new ConcurrentHashMap<>();
```

**防重门控机制**：

同一请求匹配多个重叠注册的 URL pattern（如 `/api/**` + `/api/user`），`DISPATCHED_ROUTES.putIfAbsent()` 保证仅首个 handler 执行，后续 handler 静默跳过，彻底解决 Playwright 的 `"Route is already handled"` 异常。

**容量上限保护**：`DISPATCHED_ROUTES` 超过 500 条目时自动 `clear()`，防止异常情况下（`clearDispatchedRoutes()` 未调用）无限增长。

**MonitorSession**：用于管理 MONITOR 类型的自动停止：
- 超时调度（`ScheduledExecutorService` 单线程）
- 匹配计数追踪（`AtomicInteger` + `onMonitorMatch()`）
- 支持 `minMatches` 最小匹配次数 + `autoStopOnMatch` 开关

**关键方法**：

| 方法 | 说明 |
|------|------|
| `register(Object, List<RouteRule>)` | 统一注册入口，支持 Page/BrowserContext |
| `dispatchRoute(Route, RouteRule)` | 防重门控分发 |
| `onMonitorMatch(RouteRule)` | 递增匹配计数，检查 auto-stop 条件 |
| `clearDispatchedRoutes()` | 清空防重门控集合（每次测试结束调用） |
| `clearMonitorSessions(Object)` / `clearAllMonitorSessions()` | 清理 MonitorSession |
| `unrouteAllForContext(Object, Set<String>)` | 批量注销 Playwright 路由 |

---

### 4.2 RouteRegistry — 路由注册表

**职责**：按上下文（Page/BrowserContext）隔离存储已注册的 URL pattern，防止跨上下文路由冲突和内存泄漏。

**核心数据结构**：

```java
// Key: ContextKey（WeakReference 包装 Page/BrowserContext）
// Value: 该上下文已注册的 pattern 集合
private static final ConcurrentHashMap<ContextKey, Set<String>> CONTEXT_PATTERNS;

// 触发死条目清理的阈值
private static final int PURGE_THRESHOLD = 50;
```

**ContextKey 内部类** — 使用 `WeakReference` 防止静态 Map 阻止 Page/Context 被 GC：

```java
private static final class ContextKey {
    private final int identityHash;                  // System.identityHashCode()
    private final WeakReference<Object> ref;          // WeakReference 包裹
    // equals() 基于身份相等 (a == b)
    // hashCode() 返回 identityHash（不因 ref 释放而改变）
    // isDead()  → ref.get() == null 判定 GC 回收
}
```

**内存泄漏防护链路**：
```
Page 被外部释放 → ContextKey.ref.get() 返回 null
  → registerInternal() 触发 purgeDeadEntries()（Map size > 50 时）
  → 死条目被 Iterator.remove() 移除
  → Map 不阻止 Page GC ✓
```

**关键方法**：

| 方法 | 返回值语义 | 说明 |
|------|-----------|------|
| `register(Page/String)` | `true`=首次 / `false`=已存在 | Page 级别注册 pattern |
| `register(BrowserContext/String)` | 同上 | Context 级别注册 pattern |
| `unregister(Object, String)` | void | 注销单个 pattern |
| `clearContext(Object)` | void | 三步清理：移除注册表 → Playwright unroute → 清理 MonitorSession |
| `clearAll()` | void | 全局清理所有上下文 + JSONPath 缓存（测试套件结束时调用） |
| `purgeDeadEntries()` | void | 清理 GC 回收的死条目 |
| `getPatternCount(Object)` | int | 指定上下文已注册 pattern 数 |
| `getContextCount()` | int | 全局上下文数量 |

---

### 4.3 RouteRule — 路由规则模型

**职责**：统一承载 MONITOR / MODIFY / MOCK 三种类型的配置数据，内置参数校验。

**配置字段一览**：

| 分类 | 字段 | 类型 | 默认值 | 说明 |
|------|------|------|--------|------|
| **通用** | `urlPattern` | String | —（必填） | URL 匹配模式 |
| | `type` | RouteHandleType | `MONITOR` | 处理类型 |
| **Mock** | `mockBody` | String | null | 响应体内容 |
| | `mockStatus` | int | 200 | HTTP 状态码 |
| | `mockHeaders` | Map | null | 响应头 |
| **Modify** | `addHeaders` | Map | null | 添加/覆盖的请求头 |
| | `replaceBodyKey` | String | null | JSONPath 路径 |
| | `replaceBodyValue` | String | null | 替换值 |
| | `method` | String | null | 修改 HTTP 方法 |
| **Monitor** | `record` | boolean | true | 是否记录到报告 |
| | `expectedStatus` | Integer | null | 期望状态码断言 |
| | `jsonPathAssertions` | Map | null | JSONPath 断言 |
| **自动停止** | `timeoutMs` | long | 0（永不超时） | 超时毫秒数 |
| | `minMatches` | int | 1 | 最小匹配次数 |
| | `autoStopOnMatch` | boolean | true | 达标后是否自动停止 |
| **🆕 请求匹配** | `resourceTypes` | String | null | 允许的资源类型（逗号分隔：xhr,fetch,script...） |
| | `matchMethod` | String | null | HTTP 方法匹配（GET/POST/...） |
| | `matchHeaders` | Map | null | 请求头精确匹配 |
| | `matchQuery` | Map | null | Query 参数精确匹配 |
| | `matchBodyRegex` | String | null | 请求体正则匹配 |
| | `matchContentType` | String | null | Content-Type 包含匹配（如 "json"） |
| | `matchReferrer` | String | null | Referrer 包含匹配 |
| | `matchOrigin` | String | null | Origin 包含匹配 |
| | `matchFrameUrl` | String | null | Frame URL 包含匹配 |
| | `onlyMainFrame` | boolean | true | 是否只匹配主 Frame |
| | `onlyApiCall` | boolean | true | 是否仅匹配 API（跳过 navigation + 默认只匹配 xhr/fetch） |

**输入校验**：
- `urlPattern` 拒绝 blank 值
- `mockStatus` / `expectedStatus` 必须在 `[100, 600)` 范围内
- `timeoutMs` 必须 ≥ 0；`minMatches` 必须 ≥ 1

---

### 4.4 RouteDsl — 流式 DSL 构建器

**职责**：面向测试用例的唯一外部入口。持有上下文引用（Page/BrowserContext），`start()` 无需重复传入。

**核心设计**：
- `RouteDsl` 持有上下文引用 + 规则列表（`CopyOnWriteArrayList`）
- `ApiDsl` 内部类提供链式配置方法
- `done()` 完成当前 API 配置后返回父级 `RouteDsl`，支持链式多规则

**DSL 方法全览**：

```java
// ── 入口 ──
RouteDsl.on(page)           // Page 级别
RouteDsl.on(browserContext)  // Context 级别

// ── 规则配置 ──
.api(urlPattern)            // 开始配置一个 API 规则

// ── 类型选择 ──
.monitor()                  // 声明为监控模式
.modify()                   // 声明为修改模式
.mock(body)                 // 声明为 Mock 模式 + 响应体

// ── Monitor 配置 ──
.record(boolean)            // 是否写入 Serenity 报告（默认 true）
.timeout(long ms)           // 超时毫秒（0=永不超时）
.minMatches(int)            // 最小匹配次数（默认 1）
.autoStopOnMatch(boolean)   // 达标后自动停止（默认 true）
.expectStatus(int)          // 断言 HTTP 状态码
.expectJsonPath(path, val)  // 断言 JSONPath 字段值

// ── Modify 配置 ──
.addHeader(key, value)      // 添加/覆盖请求头
.replaceBody(key, value)    // JSONPath 精准替换请求体字段
.method(method)             // 修改 HTTP 方法

// ── Mock 配置 ──
.mock(body)                 // 设置响应体
.mockStatus(status)         // 设置 HTTP 状态码
.mockHeader(key, value)     // 设置响应头

// 🆕 ── 请求条件匹配 ──
.matchMethod(method)        // HTTP 方法过滤（GET/POST/...）
.resourceType(types)        // 资源类型过滤（逗号分隔）
.onlyXhr()                  // 仅匹配 XHR 请求
.onlyFetch()                // 仅匹配 Fetch 请求
.matchHeader(key, value)    // 请求头精确匹配
.matchQuery(key, value)     // Query 参数精确匹配
.matchBodyRegex(regex)      // 请求体正则匹配
.matchContentType(type)     // Content-Type 包含匹配
.matchReferrer(referrer)    // Referrer 包含匹配
.matchOrigin(origin)        // Origin 包含匹配
.matchFrameUrl(url)         // Frame URL 包含匹配
.onlyMainFrame(bool)        // 是否只匹配主 Frame
.allowAllFrames()           // 匹配所有 Frame（含 iframe）
.onlyApiCall(bool)          // 是否仅匹配 API 调用
.allowAllRequests()         // 匹配所有类型请求（含 navigation/静态资源）
.mockStatus(int)            // 设置状态码（默认 200）
.mockHeader(key, value)     // 设置响应头

// ── 生命周期 ──
.done()                     // 完成当前规则 → 返回 RouteDsl（可继续链式）
.start()                    // 启动路由注册
.clear()                    // 注销所有 pattern + 清理上下文
```

---

### 4.5 MonitorHandler — 监控处理器

**职责**：放行请求 → 异步断言 → Serenity 报告。零阻塞 UI 线程。

**处理流程**：

```
1. route.resume() 放行请求（异常安全，失败不影响路由）
         ↓
2. response.body() 在 Playwright 事件线程同步读取 → byte[]
         ↓
3. byte[] 拷贝后提交到 RouteAsyncPool.run() 异步执行
         ↓
4. 异步线程：byte[] → UTF-8 String → 存储到 ApiMonitorContext
         ↓
5. 执行断言：状态码断言 + JSONPath 断言
         ↓
6. 失败时通过 ApiMonitorContext.recordAssertionFailure() 记录详情
         ↓
7. 成功/失败都写入 Serenity 报告（通过 SerenityReporter）
         ↓
8. RouteEngine.onMonitorMatch() 触发 auto-stop 检查
```

**断言类型**：
- **状态码断言**：`expectedStatus != null` 时比较 `response.status() == expectedStatus`
- **JSONPath 断言**：通过 `JsonPath.read()` 获取实际值，`compareValues()` 支持 Number 类型松散比较

---

### 4.6 ModifyHandler — 修改处理器

**职责**：拦截请求 → 修改请求 → 继续发送（`route.resume()`）。

**支持的修改操作**：

| 操作 | API | 说明 |
|------|-----|------|
| 添加请求头 | `.addHeader(key, value)` | 合并到原请求头（不覆盖已有） |
| 请求体替换 | `.replaceBody(key, value)` | JSONPath 精准替换 + 类型保持 |
| 修改 HTTP 方法 | `.method("POST")` | 覆盖原方法 |

**JSONPath 精准替换特性**：

```java
// 支持嵌套路径
user.name                  → { "user": { "name": "newValue" } }

// 支持数组索引
users[0].name              → { "users": [{ "name": "newValue" }] }

// 类型保持 — 原字段是 int，替换值自动转换为 IntNode
"age": 25  → replaceBody("age", "30") → "age": 30  (int 保持)

// 安全降级 — JSON 解析失败时可退化为字符串替换
ModifyHandler.setAllowFallbackStringReplace(true);  // 默认关闭
```

**JSONPath 编译缓存**：

```java
// 缓存容量上限 200，超过后自动清空重建
private static final Map<String, JsonPath> JSONPATH_CACHE;
private static final int JSONPATH_CACHE_MAX_SIZE = 200;
```

**缓存生命周期**：
- `replaceByJsonPath()` 中自动检测 → 超过 200 条目时 `clear()` 重建
- `RouteRegistry.clearAll()` 中调用 `ModifyHandler.clearJsonPathCache()`（套件级清理）
- 提供 `getJsonPathCacheSize()` 监控方法

---

### 4.7 MockHandler — Mock 处理器

**职责**：拦截请求 → 直接 `route.fulfill()` 返回自定义响应。

**处理流程**：
```
1. 状态码校验 → 非法状态码 fallback 到 200
         ↓
2. 构建 Route.FulfillOptions（状态码 + 响应体 + 自定义 Headers）
         ↓
3. route.fulfill() 包裹 try-catch，单请求失败不影响路由
```

**配置字段**：`mockBody`（响应体）、`mockStatus`（HTTP 状态码，默认 200）、`mockHeaders`（自定义响应头）

---

### 4.8 ApiMonitorContext — 监控上下文

**职责**：提供线程隔离的断言失败标记、详细失败信息记录和带容量保护的 Response 存储。

**核心设计**：

```java
// 线程隔离（每个测试线程独立上下文，并行测试互不干扰）
// 通过 PlaywrightListener.getCurrentApiMonitorContext() 获取

// Response 双重上限保护
private static final int MAX_RESPONSE_STORAGE = 1000;        // 数量上限
private static final long MAX_RESPONSE_TOTAL_SIZE = 10MB;   // 体积上限（10MB）

// Response 多值存储（同一 endpoint 多次调用全部保留）
private final ConcurrentHashMap<String, List<String>> responseStorage;
private volatile long totalResponseSize = 0L;  // 当前累计字节数
```

**双重上限保护流程**：

```
storeResponse(url, body)
  ├── 数量检查: getTotalResponseCount() >= 1000 → 拒绝存储，返回
  ├── 体积检查: totalResponseSize >= 10MB → 拒绝存储（formatBytes 输出）
  └── 写入: responseStorage.add(body) + totalResponseSize += body.length()
```

**其他关键特性**：

| 特性 | 实现 |
|------|------|
| 活动请求计数 | `AtomicInteger` — `incrementActiveRequests()` / `decrementActiveRequests()` |
| 完成等待 | `awaitCompletion(timeoutMs)` — 使用 `synchronized + wait/notifyAll`，不轮询 CPU |
| 断言失败详情 | `AssertionFailureDetail` DTO — URL + 断言类型 + 预期值 + 实际值 + 时间戳 |
| 断言失败报告 | `buildFailureReport()` — 生成人类可读的多行文本报告 |
| 重置 | `reset()` — 清空所有状态（包括 `totalResponseSize = 0L`） |

---

### 4.9 RouteAsyncPool — 异步任务池

**职责**：为 MonitorHandler 的异步断言/报告写入提供生产级线程池。

**核心配置**（环境变量）：

| 环境变量 | 默认值 | 说明 |
|----------|--------|------|
| `ROUTE_CORE_THREADS` | 2 | 核心线程数 |
| `ROUTE_MAX_THREADS` | 6 | 最大线程数（弹性扩容） |
| `ROUTE_QUEUE_CAPACITY` | 200 | 有界队列容量 |
| `ROUTE_TASK_TIMEOUT_MS` | 30000 | 单任务超时毫秒数 |
| `ROUTE_MAX_PENDING_TIMEOUTS` | 500 | 待处理超时检查任务上限 |
| `ROUTE_QUEUE_USAGE_ALERT_THRESHOLD` | 0.8 | 队列使用率告警阈值 |
| `ROUTE_THREAD_USAGE_ALERT_THRESHOLD` | 0.9 | 线程使用率告警阈值 |

**架构安全特性**：

| 特性 | 实现 |
|------|------|
| **拒绝策略** | `DiscardOldestPolicy`（队列满时丢弃最旧任务，**绝不阻塞 UI 线程**） |
| **线程命名** | `pw-route` — 守护线程，优先级 `NORM_PRIORITY - 1` |
| **弹性扩容** | 核心线程满 → 入队 → 队列也满 → 扩容到最大线程数 |
| **空闲回收** | 非核心线程 30s 空闲后回收 |
| **超时机制** | `runWithTimeout()` — `ScheduledExecutorService` 单线程调度 + `future.cancel(true)` |
| **超时监控** | `pendingTimeoutCount`（AtomicLong）追踪挂起的超时任务数，超过 `MAX_PENDING_TIMEOUTS` 触发 ERROR 告警 |
| **优雅关闭** | JVM 关闭钩子 → `shutdown()` 等待 30 秒 → `shutdownNow()` 强制关闭 |
| **阈值告警** | 队列使用率 ≥80% ERROR、线程使用率 ≥90% ERROR、超时挂起数超限 ERROR |

**🆕 v4.3 改进 — ScheduledThreadPoolExecutor 防积压**：
- 将 `Executors.newSingleThreadScheduledExecutor()` 替换为 `new ScheduledThreadPoolExecutor(1)`
- 启用 `setRemoveOnCancelPolicy(true)`：已取消的 Future 立即从队列移除，防止超时检查任务积压
- 超时回调中 `f.get(timeoutMs, ...)` → `f.get(0, ...)`：调度器已等待 timeoutMs 才触发，无需二次等待

**监控指标暴露**：

```java
// 核心指标
RouteAsyncPool.getActiveCount()         // 活跃线程数
RouteAsyncPool.getQueueSize()           // 队列中等待的任务数
RouteAsyncPool.getCompletedTaskCount()  // 已完成任务数
RouteAsyncPool.getRejectedCount()       // 被拒绝的任务数
RouteAsyncPool.getTimeoutCount()        // 超时任务数
RouteAsyncPool.getPendingTimeoutCount() // 待处理超时检查任务数

// 使用率
RouteAsyncPool.getQueueUsage()          // 队列使用率 (0.0 ~ 1.0)
RouteAsyncPool.getThreadUsage()         // 线程使用率 (0.0 ~ 1.0)

// 快照
RouteAsyncPool.getStatusSnapshot()
// 输出示例:
// [RouteAsyncPool] active=3, pool=4/6, queue=12/200 (6%), threads=75%,
//   completed=1542, rejected=0, timeouts=3, pendingTimeouts=18/500
```

---

### 4.10 RouteException — 异常体系

**三层异常结构**：

```
RouteException (extends RuntimeException)
├── RouteConfigException    — 配置错误
│   场景：URL pattern 无效、状态码越界、minMatches < 1
│
├── RouteRuntimeException   — 运行时异常
│   场景：路由注册/注销失败、Handler 执行异常
│
└── ApiAssertionException   — API 断言失败
     场景：状态码/JSONPath 不匹配
```

所有异常均携带 `urlPattern` 和 `contextId` 上下文信息。`ApiAssertionException` 额外包含 `assertionType`、`expectedValue`、`actualValue`。

---

### 4.11 SerenityReporter — 报告工具

**职责**：封装 `Serenity.recordReportData()` 调用，统一在主线程写入 API 监控数据。

**特性**：
- URL 超过 80 字符自动截断加 `...`
- 异常静默捕获（不影响主流程）
- 单一静态方法：`recordApiOperation(operation, url, detail)`

### 🆕 4.12 RouteUtil — 请求条件匹配工具

**职责**：根据 Playwright `Request` 对象判断是否匹配 `RouteRule` 中定义的请求条件。

**核心方法**：

```java
// 入口方法 — 检查 10 个维度的匹配条件
public static boolean requestMatches(Route route, RouteRule rule)

// Query 参数解析工具
public static Map<String, String> parseQueryParams(String url)
```

**🆕 v4.2 新增 — Resource Type 常量枚举**：

```java
// 替代魔法字符串，与 Playwright request.resourceType() 返回值对应
RouteUtil.RT_XHR        // "xhr"
RouteUtil.RT_FETCH      // "fetch"
RouteUtil.RT_SCRIPT     // "script"
RouteUtil.RT_STYLESHEET // "stylesheet"
RouteUtil.RT_IMAGE      // "image"
RouteUtil.RT_FONT       // "font"
RouteUtil.RT_MEDIA      // "media"
RouteUtil.RT_DOCUMENT   // "document"
RouteUtil.RT_WEBSOCKET  // "websocket"
RouteUtil.RT_MANIFEST   // "manifest"
RouteUtil.RT_OTHER      // "other"
```

**🆕 v4.2 新增 — Regex Pattern 缓存**：
- 使用 `ConcurrentHashMap<String, Pattern>` 缓存编译后的正则
- 容量上限 200 → 超出后清空重建（防 OOM）
- `String.matches()` → `Pattern.matcher().matches()`（避免重复编译）

**🆕 v4.2 新增 — 资源类型合法性校验**：
- 配置 `resourceType()` 时自动检查是否属于 12 种合法 Playwright 资源类型
- 非法类型仅日志警告，不阻断流程

**匹配维度及优先级**：

| # | 维度 | 匹配方式 | 未配置时 |
|---|------|----------|----------|
| 1 | Resource Type | 集合包含（`req.resourceType()` in rule） | 默认 `onlyApiCall=true` → 仅 `xhr/fetch` |
| 2 | HTTP Method | 忽略大小写精确匹配 | 不限制 |
| 3 | Request Headers | 所有配置的 header 必须精确匹配 | 不检查 |
| 4 | Query Parameters | 所有配置的 query 必须精确匹配 | 不检查 |
| 5 | Content-Type | 包含匹配（`"json"` 匹配 `"application/json;charset=UTF-8"`） | 不检查 |
| 6 | Body Regex | Java Pattern 正则匹配 | 不检查 |
| 7 | Referrer | 包含匹配 | 不检查 |
| 8 | Origin | 包含匹配 | 不检查 |
| 9 | Frame | 主 Frame 限定 + Frame URL 包含匹配 | 默认仅主 Frame |
| 10 | Navigation | `isNavigationRequest()` 过滤 | 默认跳过 navigation |

**匹配失败处理**：从 `DISPATCHED_ROUTES` 移除标记 → `route.resume()` → Playwright 自动调用下一个匹配 pattern 的 handler。

**匹配异常处理**：任何匹配过程中的异常都会被捕获并返回 `false`（保守跳过，避免误匹配）。

---

## 五、DSL 使用示例

### 5.1 基本 Monitor（默认行为 — 匹配 1 次后自动停止）

```java
RouteDsl.on(page)
    .api("/api/users/**")
    .monitor()
    .expectStatus(200)
    .expectJsonPath("$.data.count", 10)
    .done()
    .start();  // 无需再传 page
```

### 5.2 Mock 模式

```java
RouteDsl.on(page)
    .api("/api/login")
    .mock("{\"token\":\"mock-token-123\"}")
    .mockStatus(200)
    .mockHeader("Content-Type", "application/json")
    .done()
    .start();
```

### 5.3 Modify 模式（请求体 JSONPath 精准替换）

```java
RouteDsl.on(page)
    .api("/api/submit")
    .modify()
    .addHeader("X-Custom-Header", "test-value")
    .replaceBody("amount", "999")     // 类型保持：原 number → number
    .replaceBody("user.name", "test") // 支持嵌套路径
    .method("POST")
    .done()
    .start();
```

### 5.4 多规则组合

```java
RouteDsl.on(page)
    .api("/api/users/**")
    .monitor()
    .expectStatus(200)
    .done()
    .api("/api/login")
    .mock("{\"success\":true}")
    .done()
    .api("/api/config")
    .modify()
    .replaceBody("language", "en")
    .done()
    .start();  // 一条调用注册所有规则
```

### 5.5 BrowserContext 级别注册

```java
RouteDsl.on(browserContext)
    .api("/api/**")
    .monitor()
    .done()
    .start();
```

### 5.6 等待同一 API N 次（minMatches）

```java
RouteDsl.on(page)
    .api("/api/config")
    .monitor()
    .minMatches(3)       // 等待该 API 被捕获 3 次才停止
    .timeout(60_000)     // 60 秒兜底超时
    .done()
    .start();
```

### 5.7 持续捕获不自动停止（autoStopOnMatch=false）

```java
RouteDsl.on(page)
    .api("/api/data/list")
    .monitor()
    .autoStopOnMatch(false)  // 不自动停止！持续捕获分页请求
    .timeout(120_000)        // 靠超时结束监听
    .done()
    .start();
```

### 5.8 手动清理

```java
RouteDsl dsl = RouteDsl.on(page)
    .api("/api/**")
    .monitor()
    .done();

dsl.start();
// ... 测试执行 ...

dsl.clear();  // 注销所有 pattern，清理上下文 + MonitorSession
```

### 🆕 5.9 全条件 Mock — 多维度精准匹配

```java
RouteDsl.on(page)
    .api("/api/transfer")
    .matchMethod("POST")                          // 仅拦截 POST
    .matchHeader("X-Request-Source", "ios")       // 请求头必须匹配
    .matchQuery("amount", "100000")               // Query 参数精确匹配
    .matchContentType("json")                     // 仅 JSON 请求
    .matchBodyRegex(".*\"currency\":\"USD\".*")   // body 必须包含 USD
    .matchOrigin("myapp.com")                     // 来源匹配
    .mock("{\"code\":0,\"msg\":\"Mocked\"}")
    .mockStatus(200)
    .done()
    .start();
```

### 🆕 5.10 资源类型过滤 — 只拦截 API，放过静态资源

```java
// 默认行为：onlyApiCall=true → 只拦截 xhr/fetch，自动跳过 image/font/media/document
RouteDsl.on(page)
    .api("/api/**")
    .monitor()
    .expectStatus(200)
    .done()
    .start();

// 显式只拦截 XHR
RouteDsl.on(page)
    .api("/api/**")
    .onlyXhr()          // 只匹配 XHR，不过滤 Fetch
    .mock("{}")
    .done()
    .start();

// 显式指定多种资源类型
RouteDsl.on(page)
    .api("/**/*.js")
    .resourceType("script")      // 只拦截 script 请求
    .mock("console.log('mocked')")
    .done()
    .start();

// 拦截所有类型（包括 image/font/navigation）
RouteDsl.on(page)
    .api("/**")
    .allowAllRequests()
    .monitor()
    .done()
    .start();
```

### 🆕 5.11 Frame 级别过滤

```java
// 只监控主 Frame，忽略 iframe 中的请求（默认行为）
RouteDsl.on(page)
    .api("/api/track")
    .onlyMainFrame(true)               // 默认值，可省略
    .monitor()
    .done()
    .start();

// 允许 iframe 请求也匹配
RouteDsl.on(page)
    .api("/ads/**")
    .allowAllFrames()                  // 不会跳过 iframe 发起的请求
    .mock("{\"blocked\":true}")
    .done()
    .start();

// 只匹配特定 Frame URL 发起的请求
RouteDsl.on(page)
    .api("/api/checkout")
    .matchFrameUrl("payment-iframe")    // 只在支付 iframe 中生效
    .mock("{\"status\":\"paid\"}")
    .done()
    .start();
```

### 🆕 5.12 Referrer/Origin 来源区分 — 同一 API 不同入口

```java
// 从支付页面发起的请求 Mock 为成功
RouteDsl.on(page)
    .api("/api/payment")
    .matchReferrer("checkout-page")
    .mock("{\"code\":0,\"msg\":\"success\"}")
    .done()
    // 从其他页面发起的请求 Mock 为失败
    .api("/api/payment")
    .mock("{\"code\":-1,\"msg\":\"unauthorized\"}")
    .mockStatus(403)
    .done()
    .start();
```

### 5.13 获取断言失败详情

```java
// 通过 PlaywrightListener 在异步线程自动收集
ApiMonitorContext ctx = PlaywrightListener.getCurrentApiMonitorContext();

// 判断是否有断言失败
if (ctx.hasAssertionFailures()) {
    // 获取人类可读的失败报告
    String report = ctx.buildFailureReport();
    System.err.println(report);
}

// 获取所有存储的响应（支持多记录查询）
List<String> allBodies = ctx.getAllResponsesForUrl("/api/login");
String lastBody = ctx.getLastResponse("/api/login");
Map<String, List<String>> all = ctx.getAllStoredResponses();
```

---

## 六、完整 API 参考

### RouteDsl — 入口 + 规则配置

| 方法 | 参数 | 返回 | 说明 |
|------|------|------|------|
| `on(page)` | Page | RouteDsl | Page 级别 DSL 入口 |
| `on(context)` | BrowserContext | RouteDsl | Context 级别 DSL 入口 |
| `api(urlPattern)` | String | ApiDsl | 开始配置 API 规则 |
| `start()` | — | void | 启动路由注册 |
| `clear()` | — | void | 注销 pattern + 清理上下文 |
| `getContext()` | — | Object | 获取绑定的上下文 |

### RouteDsl.ApiDsl — 链式配置

| 方法 | 参数 | 返回 | 说明 |
|------|------|------|------|
| `monitor()` | — | ApiDsl | 声明 Monitor 模式 |
| `modify()` | — | ApiDsl | 声明 Modify 模式 |
| `mock(body)` | String | ApiDsl | 声明 Mock 模式 + 设置响应体 |
| **— Monitor 配置 —** | | | |
| `record(boolean)` | boolean | ApiDsl | 是否写入报告（默认 true） |
| `timeout(ms)` | long | ApiDsl | 超时毫秒（0=永不超时） |
| `minMatches(n)` | int | ApiDsl | 最小匹配次数（默认 1） |
| `autoStopOnMatch(b)` | boolean | ApiDsl | 达标后自动停止（默认 true） |
| `expectStatus(s)` | int | ApiDsl | 期望 HTTP 状态码 |
| `expectJsonPath(p, v)` | String, Object | ApiDsl | JSONPath 断言 |
| **— Mock 配置 —** | | | |
| `mockStatus(s)` | int | ApiDsl | 状态码（默认 200） |
| `mockHeader(k, v)` | String, String | ApiDsl | 响应头 |
| **— Modify 配置 —** | | | |
| `addHeader(k, v)` | String, String | ApiDsl | 添加/覆盖请求头 |
| `replaceBody(k, v)` | String, String | ApiDsl | JSONPath 精准替换 |
| `method(m)` | String | ApiDsl | 修改 HTTP 方法 |
| **— 🆕 请求匹配 —** | | | |
| `matchMethod(m)` | String | ApiDsl | HTTP 方法过滤 |
| `resourceType(types)` | String | ApiDsl | 资源类型过滤（逗号分隔） |
| `onlyXhr()` | — | ApiDsl | 仅匹配 XHR |
| `onlyFetch()` | — | ApiDsl | 仅匹配 Fetch |
| `matchHeader(k,v)` | String,String | ApiDsl | 请求头精确匹配 |
| `matchQuery(k,v)` | String,String | ApiDsl | Query 参数精确匹配 |
| `matchBodyRegex(re)` | String | ApiDsl | 请求体正则匹配 |
| `matchContentType(ct)` | String | ApiDsl | Content-Type 包含匹配 |
| `matchReferrer(r)` | String | ApiDsl | Referrer 包含匹配 |
| `matchOrigin(o)` | String | ApiDsl | Origin 包含匹配 |
| `matchFrameUrl(u)` | String | ApiDsl | Frame URL 包含匹配 |
| `onlyMainFrame(b)` | boolean | ApiDsl | 是否只匹配主 Frame |
| `allowAllFrames()` | — | ApiDsl | 匹配所有 Frame（含 iframe） |
| `onlyApiCall(b)` | boolean | ApiDsl | 是否仅匹配 API 调用 |
| `allowAllRequests()` | — | ApiDsl | 匹配所有类型请求 |
| **— 生命周期 —** | | | |
| `done()` | — | RouteDsl | 完成规则 → 返回父级（可继续链式） |

### RouteRule — 字段

| 字段 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `urlPattern` | String | —（必填） | URL 匹配模式 |
| `type` | RouteHandleType | `MONITOR` | 处理类型 |
| `mockBody` | String | null | Mock 响应体 |
| `resourceTypes` | String | null | 🆕 资源类型过滤 |
| `matchMethod` | String | null | 🆕 HTTP 方法过滤 |
| `matchHeaders` | Map | null | 🆕 请求头匹配 |
| `matchQuery` | Map | null | 🆕 Query 参数匹配 |
| `matchBodyRegex` | String | null | 🆕 请求体正则 |
| `matchContentType` | String | null | 🆕 Content-Type 匹配 |
| `matchReferrer` | String | null | 🆕 Referrer 匹配 |
| `matchOrigin` | String | null | 🆕 Origin 匹配 |
| `matchFrameUrl` | String | null | 🆕 Frame URL 匹配 |
| `onlyMainFrame` | boolean | true | 🆕 仅主 Frame |
| `onlyApiCall` | boolean | true | 🆕 仅 API 调用 |
| `mockStatus` | int | 200 | Mock 状态码 |
| `mockHeaders` | Map\<String,String\> | null | Mock 响应头 |
| `addHeaders` | Map\<String,String\> | null | Modify 添加的请求头 |
| `replaceBodyKey` | String | null | Modify 替换字段的 JSONPath |
| `replaceBodyValue` | String | null | Modify 替换值 |
| `method` | String | null | Modify 修改后的 HTTP 方法 |
| `record` | boolean | true | Monitor 是否写入报告 |
| `expectedStatus` | Integer | null | Monitor 期望状态码 |
| `jsonPathAssertions` | Map\<String,Object\> | null | Monitor JSONPath 断言 |
| `timeoutMs` | long | 0 | 自动停止超时（0=永不超时） |
| `minMatches` | int | 1 | 最小匹配次数 |
| `autoStopOnMatch` | boolean | true | 达标后是否自动停止 |

### RouteRegistry — 静态方法

| 方法 | 说明 |
|------|------|
| `register(Page, pattern)` | Page 级别注册 pattern |
| `register(BrowserContext, pattern)` | Context 级别注册 pattern |
| `unregister(context, pattern)` | 注销单个 pattern |
| `clearContext(context)` | 清理单个上下文（unroute + 移除注册表 + 清理 Session） |
| `clearAll()` | 全局清理（含 JSONPath 缓存） |
| `close()` | 关闭 RouteAsyncPool 线程池 |
| `purgeDeadEntries()` | 清理 GC 回收的上下文条目 |
| `getPatternCount(context)` | 指定上下文已注册 pattern 数 |
| `getContextCount()` | 全局活跃上下文数量 |

### RouteAsyncPool — 静态方法

| 方法 | 返回 | 说明 |
|------|------|------|
| `run(Runnable)` | void | 提交异步任务（默认超时） |
| `runWithTimeout(Runnable, long)` | void | 提交异步任务（自定义超时 ms） |
| `shutdown()` | void | 优雅关闭线程池 |
| `getActiveCount()` | int | 活跃线程数 |
| `getQueueSize()` | int | 队列等待任务数 |
| `getCompletedTaskCount()` | long | 已完成任务数 |
| `getRejectedCount()` | long | 被拒绝任务数 |
| `getTimeoutCount()` | long | 超时任务数 |
| `getPendingTimeoutCount()` | long | 待处理超时检查任务数 |
| `getQueueUsage()` | double | 队列使用率 (0.0~1.0) |
| `getThreadUsage()` | double | 线程使用率 (0.0~1.0) |
| `getStatusSnapshot()` | String | 全状态快照字符串 |

### ApiMonitorContext — ThreadLocal 安全

| 方法 | 说明 |
|------|------|
| `incrementActiveRequests()` / `decrementActiveRequests()` | 活动请求计数 |
| `awaitCompletion(timeoutMs)` | 阻塞等待所有正在处理的请求完成 |
| `recordAssertionFailure(url, type, expected, actual, msg)` | 记录断言失败详情 |
| `hasAssertionFailures()` | 是否有断言失败 |
| `buildFailureReport()` | 生成人类可读失败报告 |
| `storeResponse(url, body)` | 存储响应（双重上限保护） |
| `getStoredResponse(url)` | 获取最后一条响应字符串 |
| `getAllResponsesForUrl(url)` | 获取某 URL 的所有响应 |
| `getAllStoredResponses()` | 获取全部存储的响应 Map |
| `getTotalResponseCount()` | 已存储响应总条数 |
| `getTotalResponseSize()` | 已存储响应总字节数 |
| `reset()` | 清空所有状态 |

### 🆕 RouteUtil — 静态工具方法

| 方法 | 参数 | 返回 | 说明 |
|------|------|------|------|
| `requestMatches(Route, RouteRule)` | route + rule | boolean | 检查请求是否满足规则中的所有匹配条件 |
| `parseQueryParams(String)` | URL | Map<String,String> | 从 URL 解析 query 参数 |

**🆕 v4.2 Resource Type 常量**：`RT_XHR`, `RT_FETCH`, `RT_SCRIPT`, `RT_STYLESHEET`, `RT_IMAGE`, `RT_FONT`, `RT_MEDIA`, `RT_DOCUMENT`, `RT_WEBSOCKET`, `RT_MANIFEST`, `RT_OTHER`

**🆕 v4.2 Regex 缓存**：`ConcurrentHashMap<String, Pattern>`，容量上限 200，高并发免重复编译

**匹配维度**（`requestMatches` 内部）：
ResourceType → Method → Headers → Query → ContentType → BodyRegex → Referrer → Origin → Frame → Navigation

### ModifyHandler — 静态方法

| 方法 | 说明 |
|------|------|
| `handle(Route, RouteRule)` | 处理入口 |
| `setAllowFallbackStringReplace(boolean)` | 开启/关闭 JSON 失败时的字符串替换降级 |
| `clearJsonPathCache()` | 清空 JSONPath 编译缓存 |
| `getJsonPathCacheSize()` | 获取缓存条目数 |

### RouteException

| 异常类 | 应用场景 |
|--------|----------|
| `RouteConfigException` | 配置错误（URL 无效、状态码越界） |
| `RouteRuntimeException` | 运行时异常（注册/注销失败） |
| `ApiAssertionException` | API 断言失败（状态码/JSONPath） |

---

## 七、架构优势与设计亮点

### 内存安全（v4.0 新建）

| 机制 | 说明 |
|------|------|
| **WeakReference 包装** | `RouteRegistry.ContextKey` 使用 `WeakReference` 包裹 Page/Context，静态 Map 不阻止 GC |
| **死条目自动清理** | `purgeDeadEntries()` 阈值触发（>50 条目），基于 `isDead()` 安全遍历移除 |
| **双重上限防 OOM** | `ApiMonitorContext` 同时限制响应存储数量（1000 条）和体积（10MB） |
| **防重集合上限** | `RouteEngine.DISPATCHED_ROUTES` 超过 500 条目自动清空 |
| **缓存淘汰** | `ModifyHandler.JSONPATH_CACHE` 超过 200 条目自动清空重建 |
| **线程池队列限流** | `DiscardOldestPolicy` 拒绝策略 + 有界队列，拒绝后不阻塞 UI，保护 JVM 堆 |

### 线程安全

| 机制 | 说明 |
|------|------|
| **防重门控** | `DISPATCHED_ROUTES.putIfAbsent()` — 同一请求匹配多个 pattern 时只处理一次 |
| **byte[] 拷贝跨线程** | MonitorHandler 在事件线程同步读取 body 后拷贝，传递给异步线程 |
| **CopyOnWriteArrayList** | RouteDsl.rules 支持并发修改 |
| **ConcurrentHashMap** | RouteRegistry / ApiMonitorContext / ModifyHandler 共享结构 |
| **AtomicLong/AtomicInteger** | RouteAsyncPool / ApiMonitorContext 计数器 |

### 可观测性

| 机制 | 说明 |
|------|------|
| **全链路日志** | 注册/分发/处理/异常都有 DEBUG/INFO/ERROR 级别日志 |
| **阈值告警** | 线程池队列/线程/超时挂起数超限 → ERROR 级别日志 |
| **指标暴露** | `getStatusSnapshot()` / `getQueueUsage()` / `getPendingTimeoutCount()` 等 |
| **Serenity 报告集成** | MonitorHandler 异步写入 + PlaywrightListener 主线程刷新 |
| **断言失败详情** | `ApiMonitorContext.buildFailureReport()` 人类可读多行报告 |

### 可扩展性

| 机制 | 说明 |
|------|------|
| **函数式接口** | `RouteHandler` @FunctionalInterface，新增 Handler 只需实现接口 |
| **EnumMap 分发** | `RouteEngine` 使用 `EnumMap<RouteHandleType, RouteHandler>`，新增类型无 switch |
| **DSL 链式** | `RouteDsl.ApiDsl` 内部类，`done()` 返回父级支持多规则链式组合 |
| **环境变量配置** | 线程池参数全部通过环境变量可调，无需改代码 |
