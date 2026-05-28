# Route 路由框架说明文档

`com.hsbc.cmb.hk.dbb.automation.framework.web.route` 包是对 Playwright `page.route()` / `context.route()` 的封装，提供**请求拦截、Mock 响应、请求体修改、API 监控断言、高延迟模拟**一体化能力，通过流式 DSL 构建规则，简化测试中的网络层控制。

---

## 目 录

- [一、包结构](#一包结构)
- [二、核心架构](#二核心架构)
- [三、RouteHandleType — 四种处理类型](#三routehandletype--四种处理类型)
- [四、核心类详解](#四核心类详解)
  - [4.1 RouteEngine — 路由引擎](#41-routeengine--路由引擎)
  - [4.2 RouteRegistry — 路由注册表](#42-routeregistry--路由注册表)
  - [4.3 RouteRule — 路由规则模型](#43-routerule--路由规则模型)
  - [4.4 RouteDsl — 流式 DSL 构建器](#44-routedsl--流式-dsl-构建器)
  - [4.5 MonitorHandler — 监控处理器](#45-monitorhandler--监控处理器)
  - [4.6 ModifyHandler — 修改处理器](#46-modifyhandler--修改处理器)
  - [4.7 MockHandler — Mock 处理器](#47-mockhandler--mock-处理器)
  - [4.8 DelayHandler — 高延迟处理器](#48-delayhandler--高延迟处理器)
  - [4.9 ApiMonitorContext — 监控上下文](#49-apimonitorcontext--监控上下文)
  - [4.10 RouteMonitor — 监控入口门面](#410-routemonitor--监控入口门面)
  - [4.11 RouteAsyncPool — 异步任务池](#411-routeasyncpool--异步任务池)
  - [4.12 RouteException — 异常体系](#412-routeexception--异常体系)
  - [4.13 SerenityReporter — 报告工具](#413-serenityreporter--报告工具)
  - [4.14 RouteUtil — 请求条件匹配工具](#414-routeutil--请求条件匹配工具)
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
│   ├── RouteHandleType.java           # 处理类型枚举（MONITOR / MODIFY / MOCK / DELAY）
│   ├── RouteRule.java                 # 路由规则数据模型（含参数校验）
│   ├── RouteRegistry.java             # 路由注册表（WeakReference 防泄漏，按上下文隔离）
│   ├── RouteException.java            # 异常体系（配置异常 / 运行时异常 / 断言异常）
│   ├── ApiMonitorContext.java         # API 监控上下文（线程隔离断言 + 双重上限响应存储）
│   └── RouteMonitor.java              # 监控入口门面（测试代码唯一入口）
├── dsl/
│   └── RouteDsl.java                  # 流式 DSL 构建器（外部调用唯一入口）
├── handler/                           ← 具体处理器
│   ├── MonitorHandler.java            # 监控处理器（放行请求 → 异步断言 + 报告）
│   ├── ModifyHandler.java             # 修改处理器（拦截 → JsonPath 精准替换 → 继续）
│   ├── MockHandler.java               # Mock 处理器（拦截 → 直接返回自定义响应）
│   └── DelayHandler.java              # 高延迟处理器（拦截 → 延迟放行模拟高延迟网络）
└── util/
    ├── RouteAsyncPool.java            # 异步任务线程池（守护线程 + 超时监控 + 告警）
    ├── SerenityReporter.java          # Serenity 报告工具（主线程安全）
    └── RouteUtil.java                 # 请求条件匹配工具（ResourceType/Header/Query/Body 等）
```

**共 4 个子包，15 个类文件。**

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
            ├── RouteUtil.requestMatches()  → 请求条件匹配
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
                    ├── MockHandler:    fulfill(mockOptions)
                    └── DelayHandler:  ScheduledExecutorService.schedule() → resume()
```

### 关键设计原则

| 原则 | 实现方式 |
|------|----------|
| **零阻塞 UI** | MonitorHandler 先 `resume()` 放行页面，后异步断言；线程池拒绝策略为 `DiscardOldestPolicy` |
| **异常隔离** | 单规则/单请求失败不影响其他规则和其他请求，所有 Handler 包裹 try-catch |
| **线程安全** | 所有全局状态用 `ConcurrentHashMap`/`AtomicLong` 保护；跨线程传递用 `byte[]` 拷贝 |
| **内存安全** | `RouteRegistry` 使用 `WeakReference` 防泄漏；`ApiMonitorContext` 双重上限防 OOM；`RouteEngine` 防重集合有容量上限 |

---

## 三、RouteHandleType — 四种处理类型

| 类型 | 枚举值 | 行为描述 |
|------|--------|----------|
| **MONITOR** | `MONITOR` | 放行请求 → 异步读取响应体 → 执行状态码/JSONPath 断言 → 写入 Serenity 报告（零阻塞 UI） |
| **MODIFY** | `MODIFY` | 拦截请求 → 修改请求头/请求体/HTTP 方法 → 继续发送（`route.resume()`） |
| **MOCK** | `MOCK` | 拦截请求 → 直接 `route.fulfill()` 返回自定义响应（状态码 + Body + Headers） |
| **DELAY** | `DELAY` | 拦截请求 → `ScheduledExecutorService.schedule()` 等待 N 秒 → `route.resume()` 原样放行（不修改内容，仅模拟高延迟） |

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

// MonitorSession 管理（RouteRule 为 key，依赖 equals/hashCode）
private static final Map<RouteRule, MonitorSession> SESSIONS
        = new ConcurrentHashMap<>();
```

**防重门控机制**：

同一请求匹配多个重叠注册的 URL pattern（如 `/api/**` + `/api/user`），`DISPATCHED_ROUTES.putIfAbsent()` 保证仅首个 handler 执行，后续 handler 静默跳过，彻底解决 Playwright 的 `"Route is already handled"` 异常。

**容量上限保护**：`DISPATCHED_ROUTES` 超过 500 条目时自动 `clear()`，防止异常情况下无限增长。

**MonitorSession**：用于管理 MONITOR 类型的自动停止：
- 超时调度（`ScheduledExecutorService` 单线程）
- 匹配计数追踪（`AtomicInteger` + `onMonitorMatch()`）
- 支持 `minMatches` 最小匹配次数 + `autoStopOnMatch` 开关
- 使用归一化 pattern 存储和注销路由

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
| `clearContext(Object)` | void | 三阶段清理：① 移除注册表 + unroute → ② 清理 MonitorSession → ③ 清空防重门控 |
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
| | `mockReplaceFields` | Map\<String,String\> | null | Mock 批量字段替换（JSONPath → 值，支持 `[*]` 通配符） |
| **Modify** | `addHeaders` | Map | null | 添加/覆盖的请求头 |
| | `replaceBodyPairs` | Map\<String,String\> | null | JSONPath → 替换值（多对，LinkedHashMap 有序） |
| | `method` | String | null | 修改 HTTP 方法 |
| **Monitor** | `record` | boolean | true | 是否记录到报告 |
| | `expectedStatus` | Integer | null | 期望状态码断言 |
| | `jsonPathAssertions` | Map | null | JSONPath 断言 |
| **自动停止** | `timeoutMs` | long | 0（永不超时） | 超时秒数（DSL 中以秒为单位，内部存储为毫秒） |
| | `minMatches` | int | 1 | 最小匹配次数 |
| | `autoStopOnMatch` | boolean | true | 达标后是否自动停止 |
| **Delay** | `delayMs` | long | 0 | 固定延迟毫秒数（0 = 无延迟） |
| | `delayMinMs` | long | 0 | 随机延迟范围最小值（毫秒，0 = 不使用随机范围） |
| | `delayMaxMs` | long | 0 | 随机延迟范围最大值（毫秒） |
| **请求匹配** | `resourceTypes` | String | null | 允许的资源类型（逗号分隔：xhr,fetch,script...） |
| | `matchMethod` | String | null | HTTP 方法匹配（GET/POST/...） |
| | `matchHeaders` | Map | null | 请求头精确匹配 |
| | `matchQuery` | Map | null | Query 参数精确匹配 |
| | `matchBodyRegex` | String | null | 请求体正则匹配 |
| | `matchContentType` | String | null | Content-Type 包含匹配（如 "json"） |
| | `matchReferrer` | String | null | Referrer 包含匹配 |
| | `matchOrigin` | String | null | Origin 包含匹配 |
| | `matchFrameUrl` | String | null | Frame URL 包含匹配 |
| | `onlyMainFrame` | boolean | true | 是否只匹配主 Frame |
| | `onlyApiCall` | boolean | false | 是否仅匹配 API（false=匹配所有请求类型） |

**输入校验**：
- `urlPattern` 拒绝 blank 值
- `mockStatus` / `expectedStatus` 必须在 `[100, 600)` 范围内
- `timeoutMs` 必须 ≥ 0；`minMatches` 必须 ≥ 1
- `delayMs` / `delayMinMs` / `delayMaxMs` 必须 ≥ 0；`delayMaxMs` > `delayMinMs` 时才启用随机模式

**`equals()` / `hashCode()`**：

`RouteRule` 作为 `ConcurrentHashMap<RouteRule, MonitorSession>` 的 key，已重写基于业务标识的相等性判断：
- 相等判定：`urlPattern` + `type` + `method` 三者均相等
- 哈希计算：`Objects.hash(urlPattern, type, method)`

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
.delay(3)                   // 声明为高延迟模式 + 延迟秒数

// ── Monitor 配置 ──
.record(boolean)            // 是否写入 Serenity 报告（默认 true）
.timeout(long seconds)       // 超时秒数（0=永不超时）
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
.mockReplaceField(path, val) // 批量替换 Mock JSON body 字段（支持 [*] 通配符）

// ── 请求条件匹配 ──
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
| 添加请求头 | `.addHeader(key, value)` | 合并到原请求头 |
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
```

**JSONPath 编译缓存**：缓存容量上限 200，超过后自动清空重建。

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

**配置字段**：`mockBody`（响应体）、`mockStatus`（HTTP 状态码，默认 200）、`mockHeaders`（自定义响应头）。

---

### 4.8 DelayHandler — 高延迟处理器

**职责**：拦截请求 → 延迟 N 秒 → `route.resume()` 原样放行。模拟高延迟网络环境，不修改请求/响应内容。

**核心实现**：

```java
// 不使用 Thread.sleep() 阻塞线程，改用 ScheduledExecutorService.schedule()
DELAY_SCHEDULER.schedule(() -> {
    route.resume();  // 延迟结束后原样放行
}, delayMs, TimeUnit.MILLISECONDS);
```

**延迟模式**：

| 模式 | 方法 | 说明 |
|------|------|------|
| **固定延迟** | `delay(n)` | 每次请求延迟相同的秒数 |
| **随机延迟** | `delay(n).randomDelay(min, max)` | 每次请求在 [min, max] 秒范围内随机取值 |

**安全防护**：
- **最大延迟钳制**：`MAX_DELAY_MS = 120_000`（2 分钟），防止配置错误导致测试卡死
- **负值钳制**：负值延迟自动钳制为 0
- **线程隔离**：使用 `ScheduledExecutorService` 调度而非 `Thread.sleep()`，不占用请求处理线程

**与其他类型的区别**：

| 类型 | 是否修改内容 | 是否放行 | 放行方式 |
|------|-------------|----------|----------|
| **MOCK** | 是 | 否 | `fulfill()` 直接返回 |
| **MODIFY** | 是 | 是 | `resume()` 修改后放行 |
| **MONITOR** | 否 | 是 | `resume()` 立即放行 |
| **DELAY** | 否 | 是 | `schedule()` 延迟后 `resume()` |

**典型使用场景**：
- 模拟 3G/4G 高延迟网络
- 测试前端超时处理、loading 状态展示
- 验证请求失败重试机制
- 检测时间敏感型缺陷（如竞态条件）

---

### 4.9 ApiMonitorContext — 监控上下文

**职责**：提供线程隔离的断言失败标记、详细失败信息记录和带容量保护的 Response 存储。

**核心设计**：

```java
// 线程隔离（每个测试线程独立上下文，并行测试互不干扰）
private static final ThreadLocal<ApiMonitorContext> INSTANCE =
        ThreadLocal.withInitial(ApiMonitorContext::new);

// Response 双重上限保护
private static final int MAX_RESPONSE_STORAGE = 1000;        // 数量上限
private static final long MAX_RESPONSE_TOTAL_SIZE = 10MB;   // 体积上限（10MB）

// Response 多值存储（同一 endpoint 多次调用全部保留）
private final ConcurrentHashMap<String, List<String>> responseStorage;
private final AtomicLong totalResponseSize = new AtomicLong(0L);
```

**其他关键特性**：

| 特性 | 实现 |
|------|------|
| 活动请求计数 | `AtomicInteger` — `incrementActiveRequests()` / `decrementActiveRequests()` |
| 完成等待 | `awaitCompletion(timeoutMs)` — 使用 `synchronized + wait/notifyAll`，不轮询 CPU |
| 断言失败详情 | `AssertionFailureDetail` DTO — URL + 断言类型 + 预期值 + 实际值 + 时间戳 |
| 断言失败报告 | `buildFailureReport()` — 生成可读的多行文本报告 |
| 重置 | `reset()` — 清空所有状态 |

---

### 4.10 RouteMonitor — 监控入口门面

**职责**：面向测试代码的唯一 API 监控入口，封装 `ApiMonitorContext` 的获取逻辑。

```java
// 测试代码用法
ApiMonitorContext ctx = RouteMonitor.context();
List<CapturedApiCall> calls = ctx.getApiCalls("/api/track");
String body = ctx.getStoredResponse("/api/track");
Object id = ctx.getResponseJson("/api/track", "$.data.id");
```

---

### 4.11 RouteAsyncPool — 异步任务池

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
| **优雅关闭** | JVM 关闭钩子 → `shutdown()` 等待 30 秒 → `shutdownNow()` 强制关闭 |
| **阈值告警** | 队列使用率 ≥80% ERROR、线程使用率 ≥90% ERROR、超时挂起数超限 ERROR |

**监控指标暴露**：

```java
RouteAsyncPool.getActiveCount()         // 活跃线程数
RouteAsyncPool.getQueueSize()           // 队列中等待的任务数
RouteAsyncPool.getCompletedTaskCount()  // 已完成任务数
RouteAsyncPool.getRejectedCount()       // 被拒绝的任务数
RouteAsyncPool.getTimeoutCount()        // 超时任务数
RouteAsyncPool.getQueueUsage()          // 队列使用率 (0.0 ~ 1.0)
RouteAsyncPool.getThreadUsage()         // 线程使用率 (0.0 ~ 1.0)
RouteAsyncPool.getStatusSnapshot()      // 全状态快照字符串
```

---

### 4.12 RouteException — 异常体系

**三层异常结构**：

```
RouteException (extends RuntimeException)
├── RouteConfigException    — 配置错误（URL pattern 无效、状态码越界）
├── RouteRuntimeException   — 运行时异常（路由注册/注销失败、Handler 执行异常）
└── ApiAssertionException   — API 断言失败（状态码/JSONPath 不匹配）
```

所有异常均携带 `urlPattern` 和 `contextId` 上下文信息。

---

### 4.13 SerenityReporter — 报告工具

**职责**：封装 `Serenity.recordReportData()` 调用，统一在主线程写入 API 监控数据。

**特性**：
- URL 超过 80 字符自动截断加 `...`
- `StepEventBus.getBaseStepListener()` 非空检查，无 Serenity 监听器时静默跳过
- 异常静默捕获（不影响主流程）

### 4.14 RouteUtil — 请求条件匹配工具

**职责**：根据 Playwright `Request` 对象判断是否匹配 `RouteRule` 中定义的请求条件。

**Resource Type 常量**（与 Playwright `request.resourceType()` 对应）：

```
RT_XHR, RT_FETCH, RT_SCRIPT, RT_STYLESHEET, RT_IMAGE,
RT_FONT, RT_MEDIA, RT_DOCUMENT, RT_WEBSOCKET, RT_MANIFEST, RT_OTHER
```

**匹配维度及优先级**：

| # | 维度 | 匹配方式 | 未配置时 |
|---|------|----------|----------|
| 1 | Resource Type | 集合包含 | 默认不限制 |
| 2 | HTTP Method | 忽略大小写精确匹配 | 不限制 |
| 3 | Request Headers | 所有配置的 header 必须精确匹配 | 不检查 |
| 4 | Query Parameters | 所有配置的 query 必须精确匹配 | 不检查 |
| 5 | Content-Type | 包含匹配 | 不检查 |
| 6 | Body Regex | Java Pattern 正则匹配 | 不检查 |
| 7 | Referrer | 包含匹配 | 不检查 |
| 8 | Origin | 包含匹配 | 不检查 |
| 9 | Frame | 主 Frame 限定 + Frame URL 包含匹配 | 默认仅主 Frame |
| 10 | Navigation | `isNavigationRequest()` 过滤 | 默认跳过 navigation |

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
    .start();
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

### 5.2.1 Mock 批量字段替换（支持嵌套 List）

```java
RouteDsl.on(page)
    .api("/api/users")
    .mock("[{\"name\":\"Alice\",\"email\":\"a@test.com\",\"orders\":[{\"price\":10}]},"
        + "{\"name\":\"Bob\",\"email\":\"b@test.com\",\"orders\":[{\"price\":20}]}]")
    .mockReplaceField("$[*].email", "redacted@hsbc.com")
    .mockReplaceField("$[*].orders[*].price", "0")
    .mockStatus(200)
    .allowAllRequests()
    .done()
    .start();
```

### 5.3 Modify 模式（请求体 JSONPath 精准替换）

```java
RouteDsl.on(page)
    .api("/api/submit")
    .modify()
    .addHeader("X-Custom-Header", "test-value")
    .replaceBody("amount", "999")
    .replaceBody("user.name", "test")
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
    .start();  // 一次调用注册所有规则
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
    .timeout(60)          // 60 秒兜底超时
    .done()
    .start();
```

### 5.7 持续捕获不自动停止（autoStopOnMatch=false）

```java
RouteDsl.on(page)
    .api("/api/data/list")
    .monitor()
    .autoStopOnMatch(false)  // 不自动停止！持续捕获分页请求
    .timeout(120)        // 靠超时结束监听
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

> **自动清理 vs 手动清理**：
>
> | 场景 | 是否需要 `dsl.clear()` | 说明 |
> |------|------------------------|------|
> | **Serenity BDD Scenario** | ❌ 不需要 | `PlaywrightListener.testFinished()` 自动调用 `RouteRegistry.clearContext()` |
> | **独立 JUnit `@Test`** | ✅ 需要 | 不走 Serenity 生命周期，必须在 `@After` 或测试末尾显式调用 |
> | **测试中途提前停止路由** | ✅ 需要 | `autoStopOnMatch` 仅适用于 MONITOR 类型 |

### 5.9 多维度精准匹配

```java
RouteDsl.on(page)
    .api("/api/transfer")
    .matchMethod("POST")
    .matchHeader("X-Request-Source", "ios")
    .matchQuery("amount", "100000")
    .matchContentType("json")
    .matchBodyRegex(".*\"currency\":\"USD\".*")
    .matchOrigin("myapp.com")
    .mock("{\"code\":0,\"msg\":\"Mocked\"}")
    .mockStatus(200)
    .done()
    .start();
```

### 5.10 资源类型过滤 — 只拦截 API，放过静态资源

```java
RouteDsl.on(page)
    .api("/api/**")
    .onlyApiCall(true)   // 只匹配 xhr/fetch，跳过 image/font/media/document/navigation
    .monitor()
    .expectStatus(200)
    .done()
    .start();
```

### 5.11 Frame 级别过滤

```java
// 只匹配特定 Frame URL 发起的请求
RouteDsl.on(page)
    .api("/api/checkout")
    .matchFrameUrl("payment-iframe")
    .mock("{\"status\":\"paid\"}")
    .done()
    .start();
```

### 5.12 Referrer/Origin 来源区分 — 同一 API 不同入口

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

### 5.13 高延迟模拟（固定延迟）

```java
// 所有 API 固定延迟 3 秒后放行，用于测试前端 loading 状态
RouteDsl.on(page)
    .api("/api/**")
    .delay(3)                    // 拦截每个匹配请求，延迟 3 秒后 resume()
    .done()
    .start();
```

### 5.14 高延迟模拟（随机延迟）

```java
// 模拟不稳定高延迟，每次请求在 1-5 秒间随机延迟
RouteDsl.on(page)
    .api("/api/slow-endpoint")
    .delay(5)
    .randomDelay(1, 5)          // 覆盖为随机范围 1-5 秒
    .matchMethod("POST")        // 仅针对 POST 请求生效
    .done()
    .start();
```

> **注意**：Delay 仅模拟高延迟（latency），不模拟丢包、带宽限制等弱网特征。延迟通过 `ScheduledExecutorService.schedule()` 实现，不占用 Playwright 事件线程。

### 5.15 获取断言失败详情

```java
ApiMonitorContext ctx = RouteMonitor.context();

if (ctx.hasAssertionFailures()) {
    String report = ctx.buildFailureReport();
    System.err.println(report);
}

// 获取所有存储的响应
List<String> allBodies = ctx.getAllResponsesForUrl("/api/login");
String lastBody = ctx.getLastResponse("/api/login");
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
| `monitor()` | — | MonitorApiDsl | 声明 Monitor 模式 |
| `modify()` | — | ModifyApiDsl | 声明 Modify 模式 |
| `mock(body)` | String | MockApiDsl | 声明 Mock 模式 + 设置响应体 |
| `delay(secs)` | long | DelayApiDsl | 声明 Delay 模式（固定延迟秒数） |
| **— Monitor 配置 —** | | | |
| `record(boolean)` | boolean | ApiDsl | 是否写入报告（默认 true） |
| `timeout(seconds)` | long | ApiDsl | 超时秒数（0=永不超时） |
| `minMatches(n)` | int | ApiDsl | 最小匹配次数（默认 1） |
| `autoStopOnMatch(b)` | boolean | ApiDsl | 达标后自动停止（默认 true） |
| `expectStatus(s)` | int | ApiDsl | 期望 HTTP 状态码 |
| `expectJsonPath(p, v)` | String, Object | ApiDsl | JSONPath 断言 |
| **— Mock 配置 —** | | | |
| `mockStatus(s)` | int | ApiDsl | 状态码（默认 200） |
| `mockHeader(k, v)` | String, String | ApiDsl | 响应头 |
| `mockReplaceField(p, v)` | String, String | ApiDsl | 批量替换 JSON body 字段（支持 `[*]` 通配符） |
| **— Modify 配置 —** | | | |
| `addHeader(k, v)` | String, String | ApiDsl | 添加/覆盖请求头 |
| `replaceBody(k, v)` | String, String | ApiDsl | JSONPath 精准替换 |
| `method(m)` | String | ApiDsl | 修改 HTTP 方法 |
| **— 请求匹配 —** | | | |
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

### RouteDsl.DelayApiDsl — Delay 专用方法

| 方法 | 参数 | 返回 | 说明 |
|------|------|------|------|
| `delay(secs)` | long | DelayApiDsl | 更新延迟时长（秒） |
| `randomDelay(minSecs, maxSecs)` | long, long | DelayApiDsl | 启用随机延迟模式，每次在 [min, max] 秒间随机取值 |

> **注意**：`DelayApiDsl` 继承 `BaseApiDsl`，因此也拥有 `timeout()`、`minMatches()`、`matchMethod()` 等所有条件匹配和超时方法。

### RouteRegistry — 静态方法

| 方法 | 说明 |
|------|------|
| `register(Page, pattern)` | Page 级别注册 pattern |
| `register(BrowserContext, pattern)` | Context 级别注册 pattern |
| `unregister(context, pattern)` | 注销单个 pattern |
| `clearContext(context)` | 清理单个上下文（unroute + 移除注册表 + 清理 Session） |
| `clearAll()` | 全局清理（含 JSONPath 缓存） |
| `close()` | 关闭 RouteAsyncPool 线程池 |
| `getPatternCount(context)` | 指定上下文已注册 pattern 数 |
| `getContextCount()` | 全局活跃上下文数量 |

### ApiMonitorContext — ThreadLocal 安全

| 方法 | 说明 |
|------|------|
| **静态方法** | |
| `getCurrent()` | 获取当前线程的 API 监控上下文 |
| `resetCurrent()` | 重置当前线程的上下文（测试开始前调用） |
| `removeCurrent()` | 移除当前线程的上下文（测试结束后调用） |
| **实例方法** | |
| `incrementActiveRequests()` / `decrementActiveRequests()` | 活动请求计数 |
| `awaitCompletion(timeoutMs)` | 阻塞等待所有正在处理的请求完成 |
| `recordAssertionFailure(url, type, expected, actual, msg)` | 记录断言失败详情 |
| `hasAssertionFailures()` | 是否有断言失败 |
| `buildFailureReport()` | 生成可读失败报告 |
| `storeApiCall(call)` | 存储完整 API 调用快照 |
| `getApiCalls(endpoint)` | 获取指定端点的所有调用快照列表 |
| `getLastApiCall(endpoint)` | 获取指定端点最近一次调用快照 |
| `storeResponse(endpoint, body)` | 存储响应 body |
| `getStoredResponse(endpoint)` | 获取最后一条响应 body |
| `getAllResponsesForUrl(endpoint)` | 获取某端点的所有响应 body |
| `getAllStoredResponses()` | 获取全部存储的响应 Map |
| `getTotalResponseCount()` | 已存储响应总条数 |
| `reset()` | 清空所有状态 |

---

## 七、架构优势与设计亮点

### 内存安全

| 机制 | 说明 |
|------|------|
| **WeakReference 包装** | `RouteRegistry.ContextKey` 使用 `WeakReference` 包裹 Page/Context，静态 Map 不阻止 GC |
| **死条目自动清理** | `purgeDeadEntries()` 阈值触发（>50 条目），基于 `isDead()` 安全遍历移除 |
| **双重上限防 OOM** | `ApiMonitorContext` 同时限制响应存储数量（1000 条）和体积（10MB） |
| **防重集合上限** | `RouteEngine.DISPATCHED_ROUTES` 超过 500 条目自动清空 |
| **缓存淘汰** | `ModifyHandler.JSONPATH_CACHE` 超过 200 条目自动清空重建 |
| **线程池队列限流** | `DiscardOldestPolicy` 拒绝策略 + 有界队列，拒绝后不阻塞 UI |

### 线程安全

| 机制 | 说明 |
|------|------|
| **防重门控** | `DISPATCHED_ROUTES.putIfAbsent()` — 同一请求匹配多个 pattern 时只处理一次 |
| **byte[] 拷贝跨线程** | MonitorHandler 在事件线程同步读取 body 后拷贝，传递给异步线程 |
| **CopyOnWriteArrayList** | RouteDsl.rules 支持并发修改 |
| **ConcurrentHashMap** | RouteRegistry / ApiMonitorContext / ModifyHandler / SESSIONS 共享结构 |
| **AtomicLong/AtomicInteger** | RouteAsyncPool / ApiMonitorContext 计数器 |
| **RouteRule equals/hashCode** | 基于 `urlPattern + type + method` 的相等性判断 |

### 可观测性

| 机制 | 说明 |
|------|------|
| **全链路日志** | 注册/分发/处理/异常都有 DEBUG/INFO/ERROR 级别日志 |
| **阈值告警** | 线程池队列/线程/超时挂起数超限 → ERROR 级别日志 |
| **指标暴露** | `getStatusSnapshot()` / `getQueueUsage()` / `getPendingTimeoutCount()` 等 |
| **Serenity 报告集成** | MonitorHandler 异步写入 + PlaywrightListener 主线程刷新 |
| **断言失败详情** | `ApiMonitorContext.buildFailureReport()` 可读多行报告 |

### 可扩展性

| 机制 | 说明 |
|------|------|
| **函数式接口** | `RouteHandler` @FunctionalInterface，新增 Handler 只需实现接口 |
| **EnumMap 分发** | `RouteEngine` 使用 `EnumMap<RouteHandleType, RouteHandler>`，新增类型无 switch |
| **DSL 链式** | `RouteDsl.ApiDsl` 内部类，`done()` 返回父级支持多规则链式组合 |
| **环境变量配置** | 线程池参数全部通过环境变量可调，无需改代码 |
