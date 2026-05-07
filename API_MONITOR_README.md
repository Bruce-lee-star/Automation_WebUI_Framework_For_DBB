# API 监控工具包使用指南（详细版 v3.1）

> **最后更新：** 2026-05-08 | **基于代码版本：** v3.0 基础上新增 非阻塞条件检查 + 多记录查询（分页/重复请求支持）

---

## 目录

- [一、核心类概览](#一核心类概览)
- [二、RealApiMonitor - API 监控工具](#二realapimonitor---api-监控工具)
  - [2.1 核心特性与架构设计](#21-核心特性与架构设计)
  - [2.2 线程安全机制](#22-线程安全机制)
  - [2.3 停止机制（两种自动 + 手动）](#23-停止机制)
  - [2.4 MonitorBuilder 链式配置](#24-monitorbuilder-链式配置)
  - [2.5 核心查询方法（单条 + 多记录）](#25-核心查询单条--多记录)
  - [2.6 JSON 快速取值（企业级便捷操作）](#26-json-快速取值企业级便捷操作)
  - [2.7 阻塞等待 vs 非阻塞条件检查（何时用哪种）](#27-阻塞等待-vs-非阻塞条件检查何时用哪种)
  - [2.8 多记录 JSON 提取（分页/重复请求场景）](#28-多记录-json-提取分页重复请求场景)
  - [2.9 生命周期管理](#29-生命周期管理)
  - [2.10 完整示例（10 个场景）](#210-完整示例10-个场景)
- [三、ApiRequestModifier - 请求修改器](#三apirequestmodifier---请求修改器)
- [四、ApiMonitorAndMockManager - Mock 管理](#四api monitorandmockmanager---mock-管理)
- [五、最佳实践与注意事项](#五最佳实践与注意事项)
- [六、完整 API 参考](#六完整-api-参考)

---

## 一、核心类概览

| 类名 | 功能 | 使用场景 |
|------|------|----------|
| **RealApiMonitor** | API 监控（非阻塞、线程安全） | 监控页面触发的 API 调用，支持 JSON 字段提取、阻塞等待、多记录查询 |
| **ApiRequestModifier** | 请求修改 | 修改请求的 Body、Headers、URL 等 |
| **ApiMonitorAndMockManager** | Mock 管理 | Mock API 响应 |

### 类关系

```
PlaywrightListener (测试生命周期钩子)
    ├── testFinished() → RealApiMonitor.resetForNextScenario()   // 每个场景结束自动清理
    └── testSuiteFinished() → RealApiMonitor.forceCleanAll()      // 整个 Suite 结束终极清理

RealApiMonitor
    ├── monitor(context/page) → MonitorBuilder                    // 入口：创建监控构建器
    │       ├── .api(pattern, status)                             // 配置目标 API
    │       ├── .timeout(seconds)                                 // 超时
    │       ├── .then(callback)                                   // 匹配回调
    │       ├── .stopOnFirstMatch()                               // 首次匹配即停
    │       └── .start()                                          // 异步启动
    │
    ├── 【非阻塞 - 单条查询】
    │   ├── getLast(pattern) / getLastBody(pattern)               // 最后一条记录/响应体
    │   ├── hasApiCaptured(pattern)                               // 是否已捕获（条件判断）
    │   └── getJsonValue / getJsonString / ...                    // 最后一条记录的 JSON 字段
    │
    ├── 【非阻塞 - 多记录查询（分页/重复请求）】
    │   ├── getAll(pattern)                                       // 所有匹配记录列表
    │   ├── getMatchCount(pattern)                                // 匹配数量
    │   ├── getAllBodies(pattern)                                 // 所有响应体列表
    │   ├── getAllJsonValues(pattern, jsonPath)                   // 每条记录的某字段值
    │   └── getAllJsonStrings / getAllJsonInts / ...              // 类型安全多记录提取
    │
    ├── 【阻塞等待 - 确定性场景】
    │   ├── waitForApi / waitForApiBody                           // 等待 API 被捕获
    │   ├── waitForJsonValue / waitForJsonString / ...            // 等待字段有值
    │   └── waitForJsonEquals / waitForJsonValueEquals            // 等待字段等于期望值
    │
    └── stopMonitoring / resetForNextScenario / forceCleanAll     // 生命周期管理

ApiCallRecord (数据类)
    ├── getUrl / getMethod / getStatusCode                       // 基本信息
    ├── getResponseBody (synchronized, 防竞态)                    // 响应体（延迟读取）
    ├── getRequestHeaders / getResponseHeaders                   // 头信息
    └── isMocked                                                 // Mock 标记
```

---

## 二、RealApiMonitor - API 监控工具

### 2.1 核心特性与架构设计

- **非阻塞异步**：`start()` 立即返回，API 捕获在后台线程进行，不阻塞测试流程
- **自动捕获**：通过 Playwright `onResponse()` 监听器自动捕获所有网络请求
- **自动停止**：两种触发条件（目标全捕获 / 超时无新调用），无需手动干预
- **实时验证**：捕获到目标 API 时立即校验状态码（Hamcrest assertThat）
- **Serenity 集成**：自动将监控结果写入 Serenity BDD 报告（跨线程缓存机制）
- **JSON 提取**：内置 JsonPath 支持，提供类型安全便捷方法（String/Integer/Long/Double/Boolean/List/Map）
- **阻塞等待**：`waitForApi()` / `waitForJsonValue()` 解决 `getLast()` 返回 null 的竞态问题
- **非阻塞条件检查**：`hasApiCaptured()` 适用于"可能触发、也可能不触发"的条件性 API 场景（v3.1 新增）
- **多记录查询**：`getAll()` / `getAllJsonValues()` 支持分页、重复请求等多次调用场景（v3.1 新增）
- **超时增强诊断**：超时时输出已捕获的 API 列表，快速定位"为什么没抓到"
- **响应体同步读取**：在 `onResponse` 回调中立即读取 body，避免流关闭后丢失

### 2.2 线程安全机制（并行测试支持）

> 这是 v3.0 的核心重构。所有核心存储从 static 全局变量改为 **ThreadLocal**。

| 数据 | 存储类型 | 说明 |
|------|----------|------|
| `apiCallHistory` | `ThreadLocal<CopyOnWriteArrayList>` | 每线程独立的 API 调用历史 |
| `apiExpectations` | `ThreadLocal<ConcurrentHashMap>` | 每线程独立的状态码期望 |
| `hasLoggedToSerenty` | `ThreadLocal<Boolean>` | 每线程独立的报告写入标志 |
| `matchedTargetApiCount` | `ThreadLocal<AtomicInteger>` | 每线程独立的目标匹配计数 |
| `allTargetApisCaptured` | `ThreadLocal<Boolean>` | 每线程独立的全量捕获标志 |
| `targetApiPatterns` | `ThreadLocal<List>` | 每线程独立的目标 URL 模式列表 |
| `monitoringFailure` | `ThreadLocal<AssertionError>` | 每线程独立的失败异常 |
| `contextListeners` / `pageListeners` | `static ConcurrentHashMap` | 以 BrowserContext/Page 实例为 key，天然隔离不同线程 |
| `reportPending` / `pendingReport*` | `static volatile` | 跨线程报告缓存桥（异步线程→主线程传递） |

**关键设计决策：**
- `contextListeners` / `pageListeners` 保持 static —— 因为 BrowserContext/Page 实例本身就是线程绑定的
- `reportPending` 保持 volatile —— 用于跨线程通信（AsyncStop 线程写、主线程读）
- 所有 `resetForNextScenario()` 调用使用 `ThreadLocal.remove()` 而非 `set(null)` —— 防止线程池复用时旧数据污染

### 2.3 停止机制

#### 机制一：目标 API 全部捕获后自动停止（推荐）

当通过 `.api()` 指定了目标 API 后，系统追踪每个目标是否被捕获。**所有目标都命中后立即停止**。

```
启动监控 → 捕获目标API #1 ✅ → 捕获目标API #2 ✅ → ... → 全部捕获 → stopMonitoring() ✓
```

```java
RealApiMonitor.monitor(context)
    .api("/api/login", 200)        // 目标1: 登录接口，期望状态码 200
    .api("/api/userInfo", 200)      // 目标2: 用户信息
    .api("/api/permissions", 200)   // 目标3: 权限接口
    .timeout(60)                     // 兜底超时
    .start();                        // 异步启动，立即返回

// 执行操作触发 API...
loginPage.login("user", "pass");

// 无需手动停止！3 个 API 全部捕获后系统自动：
// 1. 标记 allTargetApisCaptured = true
// 2. 调用 stopMonitoring()
// 3. 将结果记录到 Serenity 报告（或缓存待主线程 flush）
```

#### 机制二：超时自动停止（兜底）

后台定时器每秒检查是否有新 API 调用。超过 `timeout()` 时间无新调用则自动停止。

```
启动监控 → 有API(重置计时器) → 有API(重置计时器) → 无新API... → 超时到达 → 自动停止
```

#### 机制三：首次匹配即停（`.stopOnFirstMatch()`）

配合 `.then(callback)` 使用，适合"等一个 API 就够了"的场景：

```java
RealApiMonitor.monitor(context)
    .api("/api/payment")
    .then(record -> {
        System.out.println("Payment: " + record.getResponseBody());
    })
    .stopOnFirstMatch()   // 捕获到第一个匹配就停止
    .timeout(30)
    .start();
```

### 2.4 MonitorBuilder 链式配置

```java
// 创建监控构建器
MonitorBuilder builder = RealApiMonitor.monitor(context);  // 或 monitor(page)

builder
    .api("/api/login", 200)           // 目标API + 期望状态码（0=不验证）
    .api("/api/user")                  // 只监控不验证状态码
    .timeout(30)                        // 兜底超时秒数（0=无限）
    .then(record -> { ... })            // 匹配时的回调
    .stopOnFirstMatch()                 // 首次匹配即停
    .start();                           // 异步启动

// start() 返回后可继续用 waitForResponse() 阻塞等待
ApiCallRecord result = builder.waitForResponse(30);  // 最多等30秒
```

**MonitorBuilder API：**

| 方法 | 参数 | 返回值 | 说明 |
|------|------|--------|------|
| `api(pattern)` | String | Builder | 添加目标 API（不限状态码） |
| `api(pattern, status)` | String, int | Builder | 添加目标 API + 期望状态码 |
| `timeout(seconds)` | int | Builder | 兜底超时时间（秒），0=无限 |
| `then(callback)` | Consumer\<ApiCallRecord\> | Builder | 匹配到目标 API 时执行回调 |
| `stopOnFirstMatch()` | - | Builder | 首次匹配任一目标后自动停止 |
| `start()` | - | void | 异步启动监控（立即返回） |
| `waitForResponse(timeoutSec)` | int | ApiCallRecord | 启动后阻塞等待首条匹配（超时返回 null） |

### 2.5 核心查询方法（单条 + 多记录）

#### `getLast(urlPattern)` — 获取最后一条匹配记录（非阻塞）

```java
ApiCallRecord record = RealApiMonitor.getLast("/api/login");
if (record != null) {
    String url = record.getUrl();          // 完整 URL
    String method = record.getMethod();     // GET / POST / PUT ...
    int status = record.getStatusCode();    // 200, 404, 500 ...
    Object body = record.getResponseBody(); // 响应体（Object 类型）
}
```

**URL 匹配规则：**
- 支持子串匹配：`"/api/login"` 会匹配 `https://host/api/login?token=xxx`
- 支持正则表达式：如果 pattern 包含 `.*` `\d` `?` `+` 等正则语法，自动按正则匹配
- 否则自动转换为正则：`".*pattern.*"`（特殊字符会被 `Pattern.quote` 正确转义）

#### `getAll(urlPattern)` — 获取所有匹配记录（非阻塞，v3.1 新增）

> **核心使用场景：分页请求、重复调用**

```java
// 分页场景：获取所有 /api/list?page=* 的调用
List<ApiCallRecord> pages = RealApiMonitor.getAll("/api/data/list");
for (ApiCallRecord pageRecord : pages) {
    String body = String.valueOf(pageRecord.getResponseBody());
    // 处理每一页的数据...
}

// 查看某 API 被调用了几次
int callCount = RealApiMonitor.getAll("/api/auth/refresh").size();
```

#### `hasApiCaptured(urlPattern)` — 条件检查（非阻塞，v3.1 新增）

> **核心使用场景：条件性 API —— 某些操作后可能触发、也可能不触发的 API**

```java
// 条件性检查：操作后判断是否触发了某 API
page.click("#maybe-trigger-api-btn");

if (RealApiMonitor.hasApiCaptured("/api/conditional")) {
    // ✅ 触发了，处理响应
    String body = RealApiMonitor.getLastBody("/api/conditional");
    System.out.println("Conditional API was called: " + body);
} else {
    // ❌ 没触发，走其他逻辑（不会浪费时间阻塞等待！）
    System.out.println("Conditional API was NOT called - skipping");
}
```

**对比 `waitForApi()`：**
| 方法 | 阻塞？ | 适用场景 | 没捕获到时 |
|------|--------|----------|-----------|
| `hasApiCaptured(pattern)` | **非阻塞** | 条件性API（可能不触发） | 立即返回 false |
| `waitForApi(pattern, timeout)` | **阻塞** | 确定性API（一定会触发） | 等到超时返回 null |
| `getLast(pattern)` | **非阻塞** | 已确认已触发，只需取值 | 返回 null |

#### `getMatchCount(urlPattern)` / `getAllBodies(urlPattern)` — 便捷方法

```java
// 快速查看调用次数
int refreshCount = RealApiMonitor.getMatchCount("/api/auth/refresh");

// 获取所有响应体
List<String> bodies = RealApiMonitor.getAllBodies("/api/data/list");
bodies.forEach(System.out::println);
```

#### `getLastBody(urlPattern)` — 获取最后一条的响应体字符串

```java
String body = RealApiMonitor.getLastBody("/api/login");
// 返回 null 如果没有匹配记录或响应体为空
```

#### `getHistory()` — 获取所有记录

```java
List<ApiCallRecord> history = RealApiMonitor.getHistory();
// 返回不可修改列表快照（UnmodifiableList）
for (ApiCallRecord r : history) {
    System.out.printf("%s %s -> %d%n", r.getMethod(), simplifyUrl(r.getUrl()), r.getStatusCode());
}
```

#### `clearHistory()` / `clearExpectations()` — 清理历史和期望

```java
RealApiMonitor.clearHistory();      // 清空调用历史 + 重置统计标志
RealApiMonitor.clearExpectations(); // 清除状态码期望
// 注意：不清理监听器引用（由 stopMonitoring / resetForNextScenario 负责）
```

#### `setTargetHost(host)` / `clearTargetHost()` — Host 过滤

```java
// 只监控特定 host 的 API（其他 host 的请求直接跳过）
RealApiMonitor.setTargetHost("api.example.com");
// ...
RealApiMonitor.clearTargetHost();  // 清除过滤
```

### 2.6 JSON 快速取值（企业级便捷操作）

> 基于 **json-path:2.9.0** 实现。从已捕获的 API 响应中提取 JSON 字段值。

#### 泛型基础版 `getJsonValue()`

```java
// 返回 Object（实际类型取决于 JSON 内容）
Object token = RealApiMonitor.getJsonValue("/api/login", "$.data.token");
Object userId = RealApiMonitor.getJsonValue("/api/user", "$.data.id");

// ⚠️ JsonPath 返回类型取决于 JSON 内容：
//   "string"  → String
//   123        → Integer
//   3.14       → Double
//   true/false → Boolean
//   [...]      → List<...>
//   {...}      → LinkedHashMap
```

**常用 JsonPath 表达式：**

| 表达式 | 含义 | 示例 |
|--------|------|------|
| `$.data.token` | 取 data 对象的 token 字段 | `"eyJhbGciOi..."` |
| `$.data.user.name` | 嵌套对象 | `"John Doe"` |
| `$[0].id` | 数组第一个元素的 id | `"order-001"` |
| `$.items[*].price` | 数组所有元素的价格 | `[99.9, 199.9]` |
| `$.data.list.length()` | 数组长度（json-path 内置函数） | `5` |

#### 类型安全便捷方法（推荐使用）

```java
String token  = RealApiMonitor.getJsonString("/api/login", "$.data.token");     // → String
Integer id    = RealApiMonitor.getJsonInt("/api/user", "$.data.id");              // → Integer
Long ts       = RealApiMonitor.getJsonLong("/api/order", "$.data.timestamp");     // → Long
Double amount = RealApiMonitor.getJsonDouble("/api/pay", "$.data.amount");         // → Double
Boolean ok    = RealApiMonitor.getJsonBoolean("/api/submit", "$.data.success");    // → Boolean

List<Object> items  = RealApiMonitor.getJsonList("/api/items", "$.data.items");    // → List<E>
Map<String, Object> user = RealApiMonitor.getJsonObject("/api/user", "$.data.user"); // → Map<String,Object>
```

**类型转换规则（`safeCast` 内部逻辑）：**
- `Number` 子类自动转换：Integer → Long / Double 无损转换
- 非 Number 类型尝试 `parseInt` / `parseLong` / `parseDouble`
- Boolean 尝试 `parseBoolean`
- List 兼容 Iterable 接口（处理 json-path 内部 JSONArray 等非标准实现）
- 转换失败返回 null 并记录 warn 日志

**空值判断（`isJsonValueEmpty`）：**
以下情况判定为"空"，不会误判合法值：
- Java `null`
- JSON `null`（字符串 "null"）
- 空 `""` 字符串
- 空 `Collection`
- 注意：`Integer(0)` / `Boolean(false)` / `Double(0.0)` **不是空值**！

### 2.7 阻塞等待 vs 非阻塞条件检查（v3.1 新增章节）

> **核心问题：什么时候该用阻塞等待？什么时候不该用？**

#### 决策树

```
这个 API 是否一定会被触发？
│
├─ 是 → 确定性场景 → 用 waitFor* 阻塞等待
│   例：点击支付按钮 → 支付API一定会来
│
└─ 否 → 条件性场景 → 用 hasApiCaptured / getAll 非阻塞检查
    例：某些条件下才请求的 API、可能不触发的可选接口
```

#### 场景对照表

| 场景类型 | 示例 | 推荐方法 | 原因 |
|----------|------|----------|------|
| **确定性**：操作后一定触发 | 点击支付→支付API | `waitForApi()` / `waitForJsonValue()` | 阻塞等到返回，避免竞态null |
| **条件性**：特定条件才触发 | 勾选选项→才请求某接口 | `hasApiCaptured()` + `if/else` | 不阻塞，根据是否触发走不同逻辑 |
| **多次请求**：分页/轮询 | 列表翻页→每页一次请求 | `getAll()` / `getAllJsonValues()` | 获取全部记录，逐条处理 |
| **不确定次数**：可能0-N次 | 自动刷新token | `getMatchCount()` + `getAllBodies()` | 先查次数，再决定处理方式 |

#### 非阻塞模式示例：条件性 API

```java
// ❌ 不好的做法：对条件性 API 用阻塞等待 —— 可能白等30秒！
ApiCallRecord r = RealApiMonitor.waitForApi("/api/maybe-called", 30); // 浪费时间

// ✅ 正确做法：非阻塞检查
page.click("#conditional-action");

if (RealApiMonitor.hasApiCaptured("/api/maybe-called")) {
    // 触发了，正常处理
    String body = RealApiMonitor.getLastBody("/api/maybe-called");
    // ...
} else {
    // 没触发，走其他逻辑
    // 没有浪费任何等待时间！
}
```

#### 阻塞等待方法参考（仅适用于确定性场景）

> 以下方法仅在 **确定 API 一定会触发** 时使用。如果 API 可能不触发，请用上方的非阻塞模式。

#### `waitForApi(urlPattern, timeoutSeconds)` — 等待 API 被捕获

```java
// 阻塞等待直到 /api/payment 被捕获（最多 30 秒）
ApiCallRecord record = RealApiMonitor.waitForApi("/api/payment", 30);
if (record != null) {
    System.out.println("Payment API captured: " + record.getStatusCode());
} else {
    throw new AssertionError("Payment API not received within 30s!");
}
```

**超时诊断输出（帮助排查）：**
```
WARN  waitForApi TIMEOUT after 30s for pattern='/api/payment'. Captured 12/12 APIs:
  [1] POST https://api.example.com/api/auth/refresh (status=200)
  [2] GET  https://cdn.example.com/static/app.js (status=200)
  [3] POST https://api.example.com/api/user/info (status=200)
  ...
```

#### `waitForApiBody(urlPattern, timeoutSeconds)` — 等待并返回响应体

```java
String body = RealApiMonitor.waitForApiBody("/api/payment", 30);
// body = {"orderId":"ORD-001","amount":99.9,"status":"SUCCESS"}
```

#### `waitForJsonValue()` 系列 — 等待 JSON 字段有值

```java
// 等待支付接口返回 orderId（泛型版）
Object orderId = RealApiMonitor.waitForJsonValue("/api/payment", "$.data.orderId", 30);

// 类型安全版（推荐）
String name = RealApiMonitor.waitForJsonString("/api/user", "$.data.name", 30);
Integer count = RealApiMonitor.waitForJsonInt("/api/list", "$.totalCount", 15);
Long ts = RealApiMonitor.waitForJsonLong("/api/order", "$.timestamp", 10);
Double price = RealApiMonitor.waitForJsonDouble("/api/item", "$.data.price", 20);
Boolean success = RealApiMonitor.waitForJsonBoolean("/api/submit", "$.success", 10);
```

#### `waitForJsonEquals()` — 等待字段等于期望值（字符串比较）

```java
// 等待状态变为 SUCCESS
boolean matched = RealApiMonitor.waitForJsonEquals(
    "/api/order", "$.data.status", "SUCCESS", 30);
assertThat(matched, is(true));
```

#### `waitForJsonValueEquals()` — 等待字段等于期望值（泛型 equals 比较）

```java
// 数字精确匹配（不需要转字符串！）
boolean matched = RealApiMonitor.waitForJsonValueEquals(
    "/api/status", "$.code", 200, 10);

// 布尔匹配
matched = RealApiMonitor.waitForJsonValueEquals(
    "/api/submit", "$.success", true, 10);

// 字符串匹配
matched = RealApiMonitor.waitForJsonValueEquals(
    "/api/user", "$.name", "John", 10);
```

### 2.8 多记录 JSON 提取（分页/重复请求场景，v3.1 新增）

> 当同一 API 被调用多次（分页、轮询、重复请求），需要从**每次响应**中提取字段时使用。

#### `getAllJsonValues(pattern, jsonPath)` — 从所有匹配记录中提取同一字段

```java
// 分页：获取每页返回的 totalCount
List<Object> totalCounts = RealApiMonitor.getAllJsonValues("/api/data/list", "$.data.totalCount");
int sum = 0;
for (Object val : totalCounts) {
    if (val instanceof Number) sum += ((Number) val).intValue();
}
System.out.println("Total records across all pages: " + sum);

// 收集每次刷新 token 后的新 token 值
List<String> tokens = RealApiMonitor.getAllJsonStrings("/api/auth/refresh", "$.data.token");
tokens.forEach(t -> System.out.println("Refreshed token: " + t));
```

#### 类型安全多记录方法

```java
// 每页的数据量
List<Integer> pageSizes = RealApiMonitor.getAllJsonInts("/api/data/list", "$.data.pageSize");

// 每次请求的耗时
List<Long> durations = RealApiMonitor.getAllJsonLongs("/api/report", "$.data.durationMs");

// 每次操作的成功状态
List<Boolean> successes = RealApiMonitor.getAllJsonBooleans("/api/batch", "$.success");

// 每次请求的金额
List<Double> amounts = RealApiMonitor.getAllJsonDoubles("/api/payment", "$.data.amount");
```

#### 多记录方法完整列表

| 方法 | 返回值 | 说明 |
|------|--------|------|
| `getAllJsonValues(pattern, jsonPath)` | `List\<T\>` | 泛型：每条记录的某字段原始值（失败为 null） |
| `getAllJsonStrings(pattern, jsonPath)` | `List\<String\>` | 类型安全 String 列表 |
| `getAllJsonInts(pattern, jsonPath)` | `List\<Integer\>` | 类型安全 Integer 列表 |
| `getAllJsonLongs(pattern, jsonPath)` | `List\<Long\>` | 类型安全 Long 列表 |
| `getAllJsonDoubles(pattern, jsonPath)` | `List\<Double\>` | 类型安全 Double 列表 |
| `getAllJsonBooleans(pattern, jsonPath)` | `List\<Boolean\>` | 类型安全 Boolean 列表 |

> **注意：** 这些方法都是 **非阻塞** 的，立即返回当前已捕获的所有匹配记录。如需等待新记录到达，请配合 `waitForApi()` 使用。

### 2.9 生命周期管理

#### 自动生命周期（PlaywrightListener 集成）

```
testStarted()          → （无需手动操作）
    ↓
[用户代码] monitor().start() → 执行操作
    ↓
stepFailed/testFinished → PlaywrightListener.flushPendingApiMonitorReport()
                          → 异步线程缓存的报告刷新到 Serenity
    ↓
testFinished(TestOutcome) → RealApiMonitor.resetForNextScenario()
                            → offResponse 移除监听器
                            → ThreadLocal.remove() 清理
                            → 清理报告缓存
    ↓
testSuiteFinished()     → RealApiMonitor.forceCleanAll()
                            → stopMonitoring() + resetForNextScenario() + clearTargetHost()
```

#### 手动生命周期方法

| 方法 | 用途 | 何时调用 |
|------|------|----------|
| `stopMonitoring()` | 停止当前监控并写报告 | 需要提前停止时 |
| `logResults()` | 将结果写入 Serenity 报告 | 通常不需要（stopMonitoring 已包含） |
| `resetForNextScenario()` | 重置场景状态（offResponse + remove） | 场景切换时（PlaywrightListener 自动调用） |
| `forceCleanAll()` | 终极清场（stop + reset + clearHost） | Suite 结束或异常恢复 |
| `flushPendingReport()` | 刷新异步缓存报告到 Serenity | 主线程回调点（PlaywrightListener 自动调用） |

**`resetForNextScenario()` 详细行为：**
1. 遍历所有已注册的 `contextListeners` / `pageListeners`，逐个调用 `offResponse(handler)`
2. 清空 `reportPending` 缓存
3. 清空 `contextMonitoringStopped` / `pageMonitoringStopped`
4. 清空 `contextListeners` / `pageListeners` 引用
5. 对所有 ThreadLocal 调用 `remove()`（不是 `set(null)`！）

**`forceCleanAll()` 详细行为：**
1. `stopMonitoring()` — 停止监控
2. `resetForNextScenario()` — 重置场景
3. `clearTargetHost()` — 清除 Host 过滤

### 2.10 完整示例（10 个场景）

#### 示例 1：基础监控（自动停止）

```java
@Test
public void testLoginCapturesApi() {
    loginPage.navigateTo(currentUrl);

    BrowserContext ctx = loginPage.getContext();
    RealApiMonitor.monitor(ctx)
        .api("lastLoginTime", 200)
        .timeout(30)
        .start();

    loginPage.userNameIpt.type("user");
    loginPage.passwordIpt.type("password");
    loginPage.loginBtn.click();

    ApiCallRecord record = RealApiMonitor.getLast("lastLoginTime");
    assertNotNull("Should capture API", record);
    assertEquals(200, record.getStatusCode());
}
```

#### 示例 2：多目标 API 监控

```java
@Test
public void testMultiApiCapture() {
    RealApiMonitor.monitor(context)
        .api("/api/auth/login", 200)
        .api("/api/user/profile", 200)
        .api("/api/user/permissions", 200)
        .timeout(60)
        .start();

    loginPage.login("username", "password");

    assertNotNull(RealApiMonitor.getLast("/api/auth/login"));
    assertNotNull(RealApiMonitor.getLast("/api/user/profile"));
    assertNotNull(RealApiMonitor.getLast("/api/user/permissions"));
}
```

#### 示例 3：只监控不验证状态码

```java
RealApiMonitor.monitor(page)
    .api("/api/analytics/track")    // 不指定状态码 = 不校验
    .timeout(15)
    .start();

page.click(".track-button");

ApiCallRecord record = RealApiMonitor.getLast("/api/analytics/track");
assertNotNull(record);
```

#### 示例 4：JSON 字段提取

```java
@Test
public void testExtractTokenFromLoginResponse() {
    RealApiMonitor.monitor(context)
        .api("/api/auth/token", 200)
        .timeout(15)
        .start();

    loginPage.login("admin", "password123");

    // 方式1：类型安全获取 String
    String token = RealApiMonitor.getJsonString("/api/auth/token", "$.data.accessToken");
    assertNotNull(token);
    assertTrue(token.startsWith("ey"));  // JWT 格式检查

    // 方式2：嵌套对象
    Map<String, Object> userData = RealApiMonitor.getJsonObject("/api/auth/token", "$.data.user");
    assertEquals("admin", userData.get("username"));

    // 方式3：数字字段
    Integer expiresIn = RealApiMonitor.getJsonInt("/api/auth/token", "$.data.expiresIn");
    assertTrue(expiresIn > 0);

    // 方式4：布尔字段
    Boolean mfaEnabled = RealApiMonitor.getJsonBoolean("/api/auth/token", "$.data.mfaEnabled");
}
```

#### 示例 5：阻塞等待 API + JSON 字段

```java
@Test
public void testWaitForPaymentOrder() {
    RealApiMonitor.monitor(context)
        .api("/api/payment/create")
        .timeout(60)
        .start();

    paymentPage.enterAmount("100.00");
    paymentPage.confirmBtn.click();

    // 等待支付 API 返回，然后提取 orderId
    String orderId = RealApiMonitor.waitForJsonString(
        "/api/payment/create", "$.data.orderId", 30);
    assertNotNull("Should get order ID within 30s", orderId);

    // 等待金额字段等于期望值
    boolean amountOk = RealApiMonitor.waitForJsonValueEquals(
        "/api/payment/create", "$.data.amount", 100.00, 10);
    assertThat(amountOk, is(true));
}
```

#### 示例 6：回调 + 首次匹配即停

```java
@Test
public void testCallbackOnFirstMatch() {
    final AtomicReference<String> paymentRef = new AtomicReference<>();

    RealApiMonitor.monitor(context)
        .api("/api/payment/process")
        .then(record -> {
            // 匹配到支付 API 时立即执行
            paymentRef.set(String.valueOf(record.getResponseBody()));
            logger.info("Payment callback fired! Status={}", record.getStatusCode());
        })
        .stopOnFirstMatch()  // 收到一个就停
        .timeout(30)
        .start();

    page.click("#pay-now-button");

    // 也可以用 waitForResponse 阻塞等
    ApiCallRecord result = RealApiMonitor.monitor(context)
        .api("/api/payment/process").timeout(30).start()
        .waitForResponse(30);
}
```

#### 示例 7：组合使用（监控 + 修改 + 验证）

```java
@Test
public void testMonitorModifyAndVerify() {
    // 1. 启动监控
    RealApiMonitor.monitor(context)
        .api("/api/orders", 200)
        .timeout(30)
        .start();

    // 2. 修改请求参数
    ApiRequestModifier modification = ApiRequestModifier.create()
        .modifyBodyField("userId", "TEST-USER-001")
        .modifyHeader("x-test-mode", "true");

    ApiRequestModifier.modifyRequest(context, "/api/orders", modification);

    // 3. 执行操作
    orderPage.submitOrder();

    // 4. 验证结果
    ApiCallRecord record = RealApiMonitor.waitForApi("/api/orders", 30);
    assertNotNull(record);
    assertEquals(200, record.getStatusCode());

    // 5. 从响应中提取订单号
    String orderNo = RealApiMonitor.getJsonString("/api/orders", "$.data.orderNo");
    assertThat(orderNo, startsWith("ORD"));
}
```

#### 示例 8：Page 级别监控（替代 Context）

```java
// Page 版本适用于单页面场景
RealApiMonitor.monitor(page)
    .api("/api/data/fetch")
    .timeout(20)
    .start();

page.click("#fetch-data-btn");

// 同样可以使用所有查询/JSON 方法
List<String> items = RealApiMonitor.getJsonList("/api/data/fetch", "$.data.items");
assertFalse(items.isEmpty());
```

#### 示例 9：条件性 API（非阻塞检查，v3.1 新增）

```java
@Test
public void testConditionalApi() {
    RealApiMonitor.monitor(context)
        .api("/api/data/fetch")
        .timeout(30)
        .start();

    // 执行一个可能触发、也可能不触发 API 的操作
    page.click("#optional-feature-toggle");

    // ✅ 非阻塞条件判断 —— 不用傻等！
    if (RealApiMonitor.hasApiCaptured("/api/data/fetch")) {
        // API 被触发了，处理数据
        String body = RealApiMonitor.getLastBody("/api/data/fetch");
        assertNotNull(body);
        System.out.println("Feature was ON, API returned data");
    } else {
        // API 没触发，走其他分支
        System.out.println("Feature was OFF, no API call expected");
    }

    // 对比：如果用 waitForApi，当 feature OFF 时会白等30秒才超时
}
```

#### 示例 10：分页请求（多记录查询 + 多记录 JSON 提取，v3.1 新增）

```java
@Test
public void testPaginationDataCollection() {
    RealApiMonitor.monitor(context)
        .timeout(60)       // 分页可能需要较长时间
        .start();

    // 触发分页加载（比如滚动到底部自动加载更多）
    page.scrollToBottom();   // 触发 page=1
    page.waitFor(1000);
    page.scrollToBottom();   // 触发 page=2
    page.waitFor(1000);

    // 获取所有分页请求的记录
    List<ApiCallRecord> pageRecords = RealApiMonitor.getAll("/api/data/list?page=");
    assertEquals("Should have captured 2 page requests", 2, pageRecords.size());

    // 方式1：遍历每条记录的响应体
    for (int i = 0; i < pageRecords.size(); i++) {
        String body = String.valueOf(pageRecords.get(i).getResponseBody());
        System.out.println("Page " + (i+1) + " response: " + body);
    }

    // 方式2：用 getAllJsonValues 批量提取某字段（更简洁）
    // 假设每页返回 {"data":{"items":[...],"totalCount":N}}
    List<Integer> counts = RealApiMonitor.getAllJsonInts(
        "/api/data/list?page=", "$.data.totalCount");

    int totalItems = 0;
    for (Integer count : counts) {
        if (count != null) totalItems += count;
    }
    System.out.println("Total items across all pages: " + totalItems);

    // 方式3：获取所有响应体列表
    List<String> bodies = RealApiMonitor.getAllBodies("/api/data/list?page=");
    assertEquals(2, bodies.size());
}
```

---

## 三、ApiRequestModifier - 请求修改器

### 核心特性
- **链式调用**：所有方法返回 this
- **完全控制**：Body / Headers / QueryParams / Method / URL / Host
- **灵活修改**：字段级修改和整体替换

### 创建实例

```java
ApiRequestModifier mod = ApiRequestModifier.create();
```

### Body 操作

```java
mod.body("{\"userId\":\"123\"}");              // 替换整个 body
mod.removeBody();                              // 删除整个 body
mod.modifyBodyField("userId", "123");           // 修改单个字段
mod.modifyBodyField("user.name", "John");       // 嵌套路径
mod.modifyBodyField("items[0].id", "item1");    // 数组元素路径
mod.removeBodyField("password");               // 删除单个字段
```

### Header 操作

```java
mod.modifyHeader("Authorization", "Bearer token");
mod.removeHeader("Cookie");
```

### 其他操作

```java
mod.method("POST");
mod.url("https://new-api.example.com/path");   // 完全替换 URL
mod.host("test-api.example.com");              // 只替换 Host（保留路径）
mod.modifyQueryParam("page", "1");             // Query 参数
mod.clear();                                    // 清空所有配置
```

### 应用修改

```java
RequestResponseStore store = ApiRequestModifier.modifyRequest(
    context,
    "/api/users",
    modification,
    info -> System.out.println("Status: " + info.response.status)  // 可选回调
);
```

---

## 四、ApiMonitorAndMockManager - Mock 管理

```java
MockBuilder mock = MockBuilder.create()
    .url("/api/auth/login")
    .method("POST")
    .status(200)
    .body("{\"success\":true,\"token\":\"mock-jwt\"}")
    .header("Content-Type", "application/json");

ApiMonitorAndMockManager.mock(context, mock);

// 动态 Mock
MockBuilder dynamicMock = MockBuilder.create()
    .url("/api/users/(.*)")    // 正则匹配
    .method("GET")
    .dynamicResponse(request -> {
        return "{\"id\":\"" + extractUserId(request.url) + "\"}";
    });

ApiMonitorAndMockManager.mock(context, dynamicMock);
```

---

## 五、最佳实践与注意事项

### RealApiMonitor 最佳实践

**1. 在操作前启动监控**
```java
// ✅ 先启动
RealApiMonitor.monitor(ctx).api("/api/x", 200).start();
button.click();

// ❌ 太晚
button.click();
RealApiMonitor.monitor(ctx).api("/api/x", 200).start();
```

**2. 利用自动停止机制（无需手动 stop）**
```java
RealApiMonitor.monitor(ctx).api(...).timeout(30).start();
doSomething();
// 不需要调用 stopMonitoring()
```

**3. 使用 waitFor* 解决竞态问题（仅限确定性场景）**
```java
// ❌ getLast 可能返回 null（API 还没返回）
ApiCallRecord r = RealApiMonitor.getLast("/api/slow");
assertNotNull(r);  // 可能失败！

// ✅ 阻塞等待 —— 适用于确定会触发的 API
ApiCallRecord r = RealApiMonitor.waitForApi("/api/slow", 30);
assertNotNull(r);  // 要么有值，要么超时明确
```

**3b. 使用非阻塞检查处理条件性API（v3.1）**
```java
// ❌ 对可能不触发的 API 用 waitFor → 白等超时
ApiCallRecord r = RealApiMonitor.waitForApi("/api/maybe-called", 30);

// ✅ 非阻塞条件判断
if (RealApiMonitor.hasApiCaptured("/api/maybe-called")) {
    // 触发了，正常处理
} else {
    // 没触发，走其他逻辑
}
```

**3c. 使用 getAll 处理分页/多次请求（v3.1）**
```java
// ✅ 获取所有分页记录
List<ApiCallRecord> pages = RealApiMonitor.getAll("/api/list?page=");
for (ApiCallRecord page : pages) {
    // 逐条处理每页数据...
}
```

**4. 使用类型安全的 JSON 方法**
```java
// ✅ 推荐：直接拿到 Integer
Integer count = RealApiMonitor.getJsonInt("/api/list", "$.totalCount");

// ❌ 不推荐：需要手动强转
Object raw = RealApiMonitor.getJsonValue("/api/list", "$.totalCount");
Integer count = ((Number) raw).intValue();
```

**5. 测试间清理（多 Scenario 时）**
```java
@After
public void tearDown() {
    RealApiMonitor.resetForNextScenario();  // PlaywrightListener 已自动调用，但手动加也无害
}
```

### 注意事项

| 项目 | 说明 |
|------|------|
| 线程安全 | 所有 public 方法都是线程安全的（ThreadLocal 隔离） |
| 内存管理 | 大量 API 调用会占用内存，建议定期 `clearHistory()` 或依赖场景自动清理 |
| 静态资源过滤 | `.js/.css/.png/.gif/.woff` 等静态资源和 `/api/` 以外的请求默认被过滤 |
| Host 过滤 | 设置 targetHost 后只监控该 host |
| Body 读取 | 响应体在 onResponse 回调中**同步一次性读取**，之后通过 `getResponseBody()` 直接返回（带 synchronized 锁防并发） |
| 二进制响应 | 非 UTF-8 文本响应自动标记为 `[BINARY_DATA base64=...]`，JSON 方法对其返回 null |
| 报告幂等 | `flushPendingReport()` 多次调用是幂等的（reportPending 标志保证只写一次） |
| 阻塞 vs 非阻塞 | `waitFor*` 仅用于确定性API（一定会触发）；条件性 API 用 `hasApiCaptured()` + `getAll()` （v3.1） |
| 多记录安全 | `getAll()` 返回新 ArrayList（非原列表视图），可安全修改；`getAllJsonValues()` 提取失败的位置返回 null |

---

## 六、完整 API 参考

### RealApiMonitor — 静态方法

| 方法 | 参数 | 返回值 | 说明 |
|------|------|--------|------|
| `monitor(context)` | BrowserContext | MonitorBuilder | Context 版监控入口 |
| `monitor(page)` | Page | MonitorBuilder | Page 版监控入口 |
| `getLast(pattern)` | String | ApiCallRecord | **最后**一条匹配记录（非阻塞，子串/正则） |
| `getAll(pattern)` | String | List\<ApiCallRecord\> | **所有**匹配记录（非阻塞，v3.1 新增，分页场景） |
| `getMatchCount(pattern)` | String | int | 匹配记录数量（非阻塞便捷方法，v3.1 新增） |
| `hasApiCaptured(pattern)` | String | boolean | 是否已捕获（非阻塞条件检查，v3.1 新增） |
| `getLastBody(pattern)` | String | String | 最后一条的响应体文本 |
| `getAllBodies(pattern)` | String | List\<String\> | 所有匹配响应体（v3.1 新增） |
| `getHistory()` | - | List\<ApiCallRecord\> | 所有记录（不可修改快照） |
| `clearHistory()` | - | void | 清空历史 + 重置标志 |
| `clearExpectations()` | - | void | 清除状态码期望 |
| `setTargetHost(host)` | String | void | 设置 Host 过滤 |
| `clearTargetHost()` | - | void | 清除 Host 过滤 |
| `stopMonitoring()` | - | void | 停止监控 + 写报告（主线程）/ 缓存报告（异步线程） |
| `logResults()` | - | void | 写入 Serenity 报告 |
| `resetForNextScenario()` | - | void | 场景级重置（offResponse + remove） |
| `forceCleanAll()` | - | void | 终极清场（stop + reset + clearHost） |
| `flushPendingReport()` | - | void | 刷新缓存报告到 Serenity（幂等） |
| `waitForApi(pattern, timeout)` | String, int | ApiCallRecord | 阻塞等待 API 被捕获 |
| `waitForApiBody(pattern, timeout)` | String, int | String | 等待并返回响应体 |
| `getJsonValue(pattern, jsonPath)` | String, String | T | 泛型 JSON 字段提取 |
| `getJsonString(pattern, jsonPath)` | String, String | String | → String 类型 |
| `getJsonInt(pattern, jsonPath)` | String, String | Integer | → Integer 类型 |
| `getJsonLong(pattern, jsonPath)` | String, String | Long | → Long 类型 |
| `getJsonDouble(pattern, jsonPath)` | String, String | Double | → Double 类型 |
| `getJsonBoolean(pattern, jsonPath)` | String, String | Boolean | → Boolean 类型 |
| `getJsonList(pattern, jsonPath)` | String, String | List\<E\> | → List 类型 |
| `getJsonObject(pattern, jsonPath)` | String, String | Map\<String,Object\> | → Map 类型 |
| `getAllJsonValues(pattern, jsonPath)` | String, String | List\<T\> | 所有记录的某字段值（v3.1 新增） |
| `getAllJsonStrings(pattern, jsonPath)` | String, String | List\<String\> | 所有记录的 String 字段（v3.1 新增） |
| `getAllJsonInts(pattern, jsonPath)` | String, String | List\<Integer\> | 所有记录的 Integer 字段（v3.1 新增） |
| `getAllJsonLongs(pattern, jsonPath)` | String, String | List\<Long\> | 所有记录的 Long 字段（v3.1 新增） |
| `getAllJsonDoubles(pattern, jsonPath)` | String, String | List\<Double\> | 所有记录的 Double 字段（v3.1 新增） |
| `getAllJsonBooleans(pattern, jsonPath)` | String, String | List\<Boolean\> | 所有记录的 Boolean 字段（v3.1 新增） |
| `waitForJsonValue(pattern, path, timeout)` | String,String,int | T | 阻塞等待 JSON 字段有值 |
| `waitForJsonString(pattern, path, timeout)` | String,String,int | String | → 等待 String |
| `waitForJsonInt(pattern, path, timeout)` | String,String,int | Integer | → 等待 Integer |
| `waitForJsonLong(pattern, path, timeout)` | String,String,int | Long | → 等待 Long |
| `waitForJsonDouble(pattern, path, timeout)` | String,String,int | Double | → 等待 Double |
| `waitForJsonBoolean(pattern, path, timeout)` | String,String,int | Boolean | → 等待 Boolean |
| `waitForJsonEquals(pattern, path, expected, timeout)` | String,String,String,int | boolean | 等待字段 == expected（串比较） |
| `waitForJsonValueEquals(pattern, path, value, timeout)` | String,String,T,int | boolean | 等待字段 equals value（泛型） |

### MonitorBuilder API

| 方法 | 参数 | 返回值 | 说明 |
|------|------|--------|------|
| `api(pattern)` | String | Builder | 添加目标（不限状态码） |
| `api(pattern, status)` | String, int | Builder | 添加目标 + 期望状态码 |
| `timeout(seconds)` | int | Builder | 兜底超时（0=无限） |
| `then(callback)` | Consumer\<ApiCallRecord\> | Builder | 匹配回调 |
| `stopOnFirstMatch()` | - | Builder | 首次匹配即停 |
| `start()` | - | void | 异步启动 |
| `waitForResponse(timeoutSec)` | int | ApiCallRecord | 阻塞等待首条匹配 |

### ApiCallRecord 字段

| 字段 | 类型 | 说明 |
|------|------|------|
| `requestId` | String | UUID，唯一标识本次调用 |
| `url` | String | 完整请求 URL |
| `method` | String | HTTP Method |
| `timestamp` | long | 捫获时间戳 |
| `statusCode` | int | HTTP 状态码 |
| `responseBody` | Object | 响应体（延迟读取 + synchronized） |
| `requestHeaders` | Map\<String,String\> | 请求头 |
| `responseHeaders` | Map\<String,String\> | 响应头 |
| `isMocked` | boolean | 是否为 Mock 数据 |

### ApiRequestModifier API

| 方法 | 参数 | 返回值 | 说明 |
|------|------|--------|------|
| `create()` | - | ApiRequestModifier | 工厂方法 |
| `body(text)` | String | ApiRequestModifier | 替换整个 body |
| `removeBody()` | - | ApiRequestModifier | 删除 body |
| `modifyBodyField(path, val)` | String, Object | ApiRequestModifier | 修改字段 |
| `removeBodyField(path)` | String | ApiRequestModifier | 删除字段 |
| `modifyHeader(name, val)` | String, String | ApiRequestModifier | 修改 header |
| `removeHeader(name)` | String | ApiRequestModifier | 删除 header |
| `modifyQueryParam(name, val)` | String, String | ApiRequestModifier | 修改参数 |
| `removeQueryParam(name)` | String | ApiRequestModifier | 删除参数 |
| `method(m)` | String | ApiRequestModifier | 修改 Method |
| `url(u)` | String | ApiRequestModifier | 替换 URL |
| `host(h)` | String | ApiRequestModifier | 替换 Host |
| `clear()` | - | ApiRequestModifier | 清空所有配置 |
| `modifyRequest(ctx, pattern, mod)` | Context, String, Mod | RequestResponseStore | 应用修改（静态方法） |

---
