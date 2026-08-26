# Route 路由框架使用手册

`com.hsbc.cmb.hk.dbb.automation.framework.web.route` 包封装了 Playwright `page.route()` / `context.route()`，提供**请求拦截、Mock 响应、请求体修改、API 监控断言、高延迟模拟**能力，通过流式 DSL 构建规则，简化测试中的网络层控制。

---

## 目 录

- [一、四种处理类型](#一四种处理类型)
- [二、RouteDsl 方法速查](#二routedsl-方法速查)
- [三、Monitor 监控](#三monitor-监控)
- [四、Mock 模拟响应](#四mock-模拟响应)
- [五、Modify 修改请求](#五modify-修改请求)
- [六、Delay 高延迟模拟](#六delay-高延迟模拟)
- [七、请求条件匹配](#七请求条件匹配)
- [八、多规则组合](#八多规则组合)
- [九、API 流量采集（ApiCapture）](#九api-流量采集apicapture)
- [十、获取断言失败详情与捕获快照](#十获取断言失败详情与捕获快照)
- [十一、用户方法完整说明](#十一用户方法完整说明)
- [十二、清理与生命周期](#十二清理与生命周期)

---

## 一、四种处理类型

| 类型 | 行为 |
|------|------|
| **MONITOR** | 放行请求 → 异步读取响应体 → 执行状态码/JSONPath 断言 → 写入 Serenity 报告 |
| **MODIFY** | 拦截请求 → 修改请求头/请求体/HTTP 方法 → 继续发送 |
| **MOCK** | 拦截请求 → 直接返回自定义响应（状态码 + Body + Headers） |
| **DELAY** | 拦截请求 → 延迟 N 秒后原样放行（不修改内容，仅模拟高延迟） |

同一 `pattern` 的组合语义：`MOCK` 是终结动作；`MODIFY`、`DELAY` 可以叠加；`MONITOR` 是真实响应监控基线，除 `MOCK` 外不会被 `MODIFY` 或 `DELAY` 覆盖。跨 `Page` / `BrowserContext` 时，`MOCK` 仍会终止其它能力，`MONITOR` 对真实响应持续生效。

---

## 二、RouteDsl 方法速查

```java
// ── 入口 ──
RouteDsl.on(page)             // Page 级别
RouteDsl.on(browserContext)   // Context 级别

// ── 规则配置 ──
.api(urlPattern)              // 开始配置一个 API 规则

// ── 类型选择 ──
.monitor()                    // 声明为监控模式
.modifyRequest()              // 声明为修改模式
.mock()                       // 声明为 Mock 模式
.delay(3)                     // 声明为高延迟模式 + 延迟秒数

// ── 生命周期 ──
.done()                       // 完成当前规则 → 返回 RouteDsl（可继续链式）
.start()                      // 启动路由注册
.clear()                      // 注销所有 pattern + 清理上下文
RouteDsl.clearAllRules()      // 静态方法：全局清理所有上下文的所有路由规则
```

**Monitor 配置**：`.record(boolean)`、`.timeout(long seconds)`、`.minMatches(int)`、`.autoStopOnMatch(boolean)`、`.expectStatus(int)`、`.expectJsonPath(path, val)`、`.onResponse(callback)`

**Modify 配置**：`.setRequestHeader(key, val)`、`.setRequestHeaders(map)`、`.removeRequestHeader(key)`、`.modifyRequestBody(path, v)`、`.addRequestBodyField(p, v)`、`.removeRequestBodyField(p)`、`.modifyMethod(method)`

**Mock 配置**：`.mockBody(body)`、`.mockBodyFromFile(name)`、`.mockBodyFromFile(name, map)`、`.mockStatus(status)`、`.mockHeader(key, value)`、`.replaceField(path, val)`、`.replaceFields(map)`、`.mockReplaceField(path, val)`、`.interceptResponse()`

**请求条件匹配**：`.matchMethod(method)`、`.resourceType(types)`、`.onlyXhr()`、`.onlyFetch()`、`.onlyApi()`、`.matchHeader(key, value)`、`.matchQuery(key, value)`、`.matchBodyRegex(regex)`、`.matchContentType(type)`、`.matchReferrer(referrer)`、`.matchOrigin(origin)`、`.matchFrameUrl(url)`、`.onlyMainFrame(bool)`、`.allowAllFrames()`、`.onlyApiCall(bool)`、`.allowAllRequests()`

**一次性拦截（对齐 Playwright `Route.setTimes`）**：`.times(n)` — 0（默认）无限次；N>0 仅处理前 N 次，之后请求直接放行走真实网络。适用于 MOCK / MODIFY / DELAY；MONITOR 请用 `minMatches` + `autoStopOnMatch`。

**Delay 专用**：`.delay(secs)`、`randomDelay(minSecs, maxSecs)`

---

## 三、Monitor 监控

基本用法（默认匹配 1 次后自动停止）：

```java
RouteDsl.on(page)
    .api("/api/users/**")
    .monitor()
    .expectStatus(200)
    .expectJsonPath("$.data.count", 10)
    .done()
    .start();
```

等待同一 API 捕获 N 次（不自动停止时可配 `timeout` 兜底）：

```java
RouteDsl.on(page)
    .api("/api/config")
    .monitor()
    .minMatches(3)        // 等待该 API 被捕获 3 次才停止
    .timeout(60)          // 60 秒兜底超时
    .done()
    .start();
```

持续捕获不自动停止：

```java
RouteDsl.on(page)
    .api("/api/data/list")
    .monitor()
    .autoStopOnMatch(false)
    .timeout(120)         // 靠超时结束监听
    .done()
    .start();
```

响应回调（断言通过后异步执行，可提取字段）：

```java
RouteDsl.on(page)
    .api("/api/login")
    .monitor()
    .expectStatus(200)
    .onResponse((url, status, body, headers, method) -> {
        String token = JsonPath.read(body, "$.data.token");
        System.out.println("Login success, token=" + token);
    })
    .done()
    .start();
```

---

## 四、Mock 模拟响应

### 4.1 纯 Mock（默认，不访问真实服务器）

```java
RouteDsl.on(page)
    .api("/api/login")
    .mock()
    .mockBody("{\"token\":\"mock-token-123\"}")
    .mockStatus(200)
    .mockHeader("Content-Type", "application/json")
    .done()
    .start();
```

从 JSON 文件读取响应体（文件放 `src/test/resources/mocks/`）：

```java
RouteDsl.on(page)
    .api("/api/login")
    .mock()
    .mockBodyFromFile("login-response.json")   // 自动从 src/test/resources/mocks/ 查找
    .replaceField("$.data.token", "fake-token")
    .mockStatus(200)
    .done()
    .start();

// 等价且更高效：一次性读文件 + 批量改字段
RouteDsl.on(page)
    .api("/api/login")
    .mock()
    .mockBodyFromFile("login-response.json",
        Map.of("$.data.token", "fake-token", "$.users[*].active", true))
    .mockStatus(200)
    .done()
    .start();
```

### 4.2 Mock 批量字段替换

```java
// 通配符 [*] 批量替换 List 内字段
RouteDsl.on(page)
    .api("/api/users")
    .mock()
    .mockBody("[{\"name\":\"Alice\",\"email\":\"a@test.com\"},"
        + "{\"name\":\"Bob\",\"email\":\"b@test.com\"}]")
    .mockReplaceField("$[*].email", "redacted@hsbc.com")
    .mockStatus(200)
    .allowAllRequests()
    .done()
    .start();

// 数组/对象字段自动类型保持（不会变成字符串）
RouteDsl.on(page)
    .api("/api/items")
    .mock()
    .mockBody("{\"code\":200,\"data\":{\"items\":[],\"config\":{}}}")
    .mockReplaceField("$.data.items", "[{\"id\":1,\"name\":\"test\"}]")
    .mockReplaceField("$.data.config", "{\"timeout\":30,\"enable\":true}")
    .mockStatus(200)
    .done()
    .start();

// 数字/布尔类型保持
RouteDsl.on(page)
    .api("/api/profile")
    .mock()
    .mockBody("{\"name\":\"Alice\",\"age\":25,\"active\":true,\"score\":88.5}")
    .mockReplaceField("$.age", "30")        // → "age":30
    .mockReplaceField("$.active", "false")  // → "active":false
    .mockReplaceField("$.score", "99.9")    // → "score":99.9
    .done()
    .start();
```

### 4.3 拦截真实响应再替换（interceptResponse）

```java
RouteDsl.on(page)
    .api("/api/users")
    .mock()
    .interceptResponse()      // 先 route.fetch() 取真实响应，再替换部分字段
    .mockReplaceField("$.data.name", "Replaced")
    .mockStatus(200)
    .done()
    .start();
```

> **注意**：`mockReplaceField()` 仅在 `interceptResponse()` 模式下生效（作用于真实响应体）。纯 Mock 改字段请用 `replaceField()` / `replaceFields()` / `mockBodyFromFile(name, overrides)`。

### 4.4 优先级覆盖：Monitor 后 Mock 同一 API

```java
// 第一步：Monitor 监控
RouteDsl.on(page).api("/api/users/**").monitor().expectStatus(200).done().start();

// 第二步：Mock 同一 API —— MOCK 优先级高于 MONITOR，自动覆盖，无需手动 unroute
RouteDsl.on(page)
    .api("/api/users/**")
    .mock()
    .mockBody("{\"code\":0,\"data\":{\"items\":[]}}")
    .mockStatus(200)
    .done()
    .start();
```

---

## 五、Modify 修改请求

```java
RouteDsl.on(page)
    .api("/api/submit")
    .modifyRequest()
    .setRequestHeader("X-Custom-Header", "test-value")
    .modifyRequestBody("amount", "999")
    .modifyRequestBody("user.name", "test")
    .modifyMethod("POST")
    .done()
    .start();
```

`.modifyRequestBody()` 支持嵌套路径（`user.name`）和数组索引（`users[0].name`），并自动保持原始字段类型（int/boolean/数组/对象等）。

---

## 六、Delay 高延迟模拟

```java
// 固定延迟 3 秒
RouteDsl.on(page)
    .api("/api/**")
    .delay(3)
    .done()
    .start();

// 随机延迟 1-5 秒（仅对 POST 请求生效）
RouteDsl.on(page)
    .api("/api/slow-endpoint")
    .delay(5)
    .randomDelay(1, 5)
    .matchMethod("POST")
    .done()
    .start();
```

延迟通过调度器实现，不阻塞 UI 线程；最大延迟被钳制为 2 分钟，负值自动钳制为 0。

---

## 七、请求条件匹配

```java
RouteDsl.on(page)
    .api("/api/transfer")
    .matchMethod("POST")
    .matchHeader("X-Request-Source", "ios")
    .matchQuery("amount", "100000")
    .matchContentType("json")
    .matchBodyRegex(".*\"currency\":\"USD\".*")
    .matchOrigin("myapp.com")
    .mock()
    .mockBody("{\"code\":0,\"msg\":\"Mocked\"}")
    .mockStatus(200)
    .done()
    .start();
```

只匹配 API 请求（放过 image/font/media/document/navigation）：

```java
RouteDsl.on(page)
    .api("/api/**")
    .onlyApiCall(true)
    .monitor()
    .expectStatus(200)
    .done()
    .start();
```

同一 API 不同来源入口区分：

```java
RouteDsl.on(page)
    .api("/api/payment")
    .matchReferrer("checkout-page")
    .mock()
    .mockBody("{\"code\":0,\"msg\":\"success\"}")
    .done()
    .api("/api/payment")
    .mock()
    .mockBody("{\"code\":-1,\"msg\":\"unauthorized\"}")
    .mockStatus(403)
    .done()
    .start();
```

Frame 级别过滤：

```java
RouteDsl.on(page)
    .api("/api/checkout")
    .matchFrameUrl("payment-iframe")
    .mock()
    .mockBody("{\"status\":\"paid\"}")
    .done()
    .start();
```

---

## 八、多规则组合

```java
RouteDsl.on(page)
    .api("/api/users/**").monitor().expectStatus(200).done()
    .api("/api/login").mock().mockBody("{\"success\":true}").done()
    .api("/api/config").modifyRequest().modifyRequestBody("language", "en").done()
    .start();  // 一次调用注册所有规则
```

BrowserContext 级别注册：

```java
RouteDsl.on(browserContext)
    .api("/api/**")
    .monitor()
    .done()
    .start();
```

---

## 九、API 流量采集（ApiCapture）

`ApiCapture` 是旁路抓包门面：在跑 Web UI 自动化时自动抓取页面发出的 HTTP/API 请求，供测试代码直接断言、等待、查询。实际写入 `ApiCaptureContext` 的请求必须同时满足：资源类型属于 API 类、URL 命中已注册的 `endpoint`，以及可选的 `BASE_URL` / `BASE_PATH` 范围。未命中的请求（包括高频 health check 和动态新接口）不会写入上下文，避免内存持续增长。

```java
// 1) 测试开始时配置采集范围（可选）
// BASE_URL 会校验协议、host、端口；BASE_PATH 按完整路径段匹配
RouteEngine.setCaptureBaseUrl("http://localhost:8888");
RouteEngine.setCaptureBasePath("/demo/api");

// 2) 测试开始时启动采集（每个新页面都要 start）
ApiCapture.start(page);

// 3) 断言 API 结果（支持 Ant 通配 /api/** 、/api/*）
ApiCapture.assertThat("/api/user/list").statusIs(200);
ApiCapture.assertThat("/api/user/detail").jsonPath("$.code", 0);
ApiCapture.assertThat("/api/config").bodyContains("enabled");
ApiCapture.assertThat("/api/balance").isMock();          // 配合 RouteEngine MOCK 时

// 3) 等待 / 查询 / 关闭
CapturedApiCall login = ApiCapture.waitForApi(
        c -> "POST".equals(c.method()) && c.isOk(), 5000);
var all = ApiCapture.getAll();
CapturedApiCall last = ApiCapture.getLast("/api/user/list");

ApiCapture.stop();   // 测试收尾务必调用，释放 merger 线程池
RouteEngine.clearCaptureUrlScope(); // 清除 BASE_URL / BASE_PATH 范围
```

**按资源类型过滤断言**：

```java
// 类型安全：枚举过滤（推荐）
ApiCapture.assertThat("/api/user/list").ofType(ResourceType.XHR).statusIs(200);
ApiCapture.assertThat("/api/**").ofType(ResourceType.XHR, ResourceType.FETCH).statusIs(200);

// 字符串便捷重载（大小写不敏感，逗号分隔）
ApiCapture.assertThat("/api/**").ofType("xhr,fetch").statusIs(200);

// 直接查询调用类型
CapturedApiCall c = ApiCapture.getLast("/api/user/list");
ResourceType t = c.resourceType();   // 枚举，如 ResourceType.XHR
System.out.println(c.isXhr());       // true / false
System.out.println(c.isApiType());   // XHR/FETCH/API 投喂 → true
```

**注意点**：
- `start(page)` 必须传 Playwright `Page`；建议用 Chromium 跑测试以触发 CDP 策略（响应体最完整）。非 Chromium 自动降级。
- 新创建的 `Page` 也必须单独调用 `ApiCapture.start(newPage)`，否则该页面的请求不会被当前 CDP 会话采集。
- `BASE_URL`、`BASE_PATH` 是全局 capture 前置过滤条件；不配置表示不限制，但仍必须命中已注册 `endpoint` 才会入库。
- `BASE_URL` 示例：`http://localhost:8888`；`BASE_PATH` 示例：`/demo/api`。路径按 segment 匹配，`/api/v1` 不会误匹配 `/api/v10`。
- `ResourceType` 默认只允许 `XHR`、`FETCH`、`API`、`OTHER`；页面、脚本、图片、字体、WebSocket 等资源不会进入 API 上下文。
- 断言匹配限定在"当前测试步骤窗口"内，避免命中上一步遗留调用。
- `stop()` 在测试收尾调用，释放 merger 线程池；测试结束后调用 `RouteEngine.clearCaptureUrlScope()` 清除全局 URL 范围。

---

## 十、获取断言失败详情与捕获快照

```java
ApiCaptureContext ctx = ApiCaptureContext.getCurrent();

// 检查断言失败
if (ctx.hasAssertionFailures()) {
    String report = ctx.buildFailureReport();
    System.err.println(report);
}

// 获取 Monitor/Mock/Modify 的完整调用快照
List<CapturedApiCall> calls = ctx.getApiCalls("/api/track");
CapturedApiCall lastCall = ctx.getLastApiCall("/api/track");
int status = lastCall.statusCode();
String responseHeader = lastCall.responseHeader("Content-Type");
Object jsonValue = lastCall.json("$.data.id");
```

获取存储的响应 body：

```java
List<String> allBodies = ctx.getAllResponsesForUrl("/api/login");
String lastBody = ctx.getLastResponse("/api/login");
```

---

## 十一、用户方法完整说明

本节按用户实际使用顺序说明公开方法。除特别说明外，规则配置方法都返回当前 DSL 对象，因此可以继续链式调用；必须先调用 `done()` 完成当前 endpoint，再调用 `start()` 注册规则。

### 11.1 路由入口与生命周期

| 方法 | 说明 |
|---|---|
| `RouteDsl.on(page)` | 创建 Page 级 DSL。规则只作用于该 Page，适合单页面、单场景的精确控制。 |
| `RouteDsl.on(browserContext)` | 创建 Context 级 DSL。规则作用于 Context 下的页面，适合多个页面共享规则。 |
| `api(endpoint)` | 开始配置一个 endpoint。支持 Playwright URL pattern，例如 `/api/users/**`；`endpoint` 也是后续 `ApiCapture` 查询和存储使用的 key。 |
| `done()` | 完成当前 endpoint 配置并回到父 DSL；没有 `done()` 的规则不会被注册。 |
| `start()` | 注册当前 DSL 中已完成的全部规则。应在页面发起 API 前调用。重复注册相同 endpoint 会按能力位合并。 |
| `clear()` | 清理当前 DSL 的规则、Monitor 会话和相关上下文状态。独立 JUnit 测试应在 `@After` 调用。 |
| `RouteDsl.clearAllRules()` | 清理所有 Page/Context 的路由规则，适合测试套件结束或全局复位；不要在仍运行的并行测试中调用。 |

### 11.2 类型选择方法

| 方法 | 说明 |
|---|---|
| `monitor()` | 开启真实响应监控。请求会继续访问真实后端，并根据状态码、JSONPath 执行断言。默认匹配一次后自动停止。 |
| `mock()` | 开启 Mock。默认不访问真实后端，直接返回配置的状态码、响应头和响应体；同 endpoint 存在 Mock 时，Mock 终结其它能力。 |
| `modifyRequest()` | 开启真实请求修改。修改请求方法、Header 或 Body 后继续发送到后端。 |
| `delay(seconds)` | 开启延迟。等待指定秒数后原样放行；可与 Modify、Monitor 叠加，不能让真实响应监控失效。 |
| `randomDelay(minSeconds, maxSeconds)` | 配置随机延迟范围；每次匹配随机选择区间内的延迟。通常与 `delay()` 同一规则链使用。 |

### 11.3 通用匹配与控制方法

| 方法 | 说明 |
|---|---|
| `timeout(seconds)` | 设置 Monitor 等待超时时间，单位为秒；`0` 表示不设置超时。建议持续监控时配置兜底值。 |
| `minMatches(count)` | 设置至少匹配多少次才满足 Monitor 条件；默认是 `1`。 |
| `autoStopOnMatch(enabled)` | 设置满足条件后是否自动停止；Monitor 默认开启，持续 health check 监控应传 `false` 并配合 `timeout()`。 |
| `record(enabled)` | 是否记录 Monitor 捕获结果；关闭后仍可执行监控逻辑，但不建议依赖其查询存储结果。 |
| `matchMethod(method)` | 精确匹配 HTTP 方法，如 `GET`、`POST`。不配置表示不限制。 |
| `resourceType(types)` | 按资源类型过滤，传逗号分隔字符串，如 `xhr,fetch`。 |
| `onlyXhr()` | 只匹配 XHR。 |
| `onlyFetch()` | 只匹配 Fetch。 |
| `onlyApi()` | 只匹配 XHR 和 Fetch。 |
| `onlyApiCall(enabled)` | 设置是否只处理 API 请求；启用后跳过 document、script、image 等页面资源。 |
| `allowAllRequests()` | 取消 `onlyApiCall` 限制；仅在确实需要处理非 API 请求时使用。 |
| `matchHeader(key, value)` | 精确匹配请求 Header；多次调用时所有条件都必须满足。 |
| `matchQuery(key, value)` | 精确匹配 URL Query 参数；多次调用时所有条件都必须满足。 |
| `matchBodyRegex(regex)` | 用 Java 正则匹配请求 Body，适合按 JSON 字段区分相同 endpoint 的请求。 |
| `matchContentType(type)` | 按包含关系匹配 Content-Type，例如 `json` 可匹配 `application/json`。 |
| `matchReferrer(value)` | 按包含关系匹配 Referer。 |
| `matchOrigin(value)` | 按包含关系匹配 Origin。 |
| `matchFrameUrl(value)` | 按包含关系匹配发起请求的 Frame URL。 |
| `onlyMainFrame(enabled)` | 是否只处理主 Frame；默认开启。 |
| `allowAllFrames()` | 允许 iframe 等非主 Frame 请求。 |
| `times(count)` | 一次性拦截次数（对齐 Playwright `Route.setTimes`）：0=无限次（默认）；N>0=仅处理前 N 次，第 N+1 次起直接放行走真实网络。仅对独立注册的 MOCK / MODIFY / DELAY 生效；跨层合并场景安全降级为无限次。MONITOR 请用 `minMatches`+`autoStopOnMatch`。 |

### 11.4 Monitor 方法

| 方法 | 说明 |
|---|---|
| `expectStatus(status)` | 断言真实响应 HTTP 状态码。 |
| `expectJsonPath(path, expected)` | 读取响应 JSON 的 JSONPath 并与期望值比较，例如 `$.data.id`。 |
| `onResponse(callback)` | 断言通过后执行回调，可读取 URL、状态码、Body、Header 和 Method；回调异常不会改变请求放行结果。 |
| `assertThat(endpoint)` | 创建基于已捕获调用的查询断言对象，不发起新请求。 |
| `statusIs(status)` | 对查询到的最后一次匹配调用断言状态码。 |
| `jsonPath(path, expected)` | 对查询到的响应执行 JSONPath 断言。 |
| `bodyContains(text)` | 断言响应 Body 包含指定文本。 |
| `isMock()` | 断言调用是否来自 Mock 响应。 |
| `ofType(type...)` | 将查询断言限制到指定 `ResourceType`。 |

### 11.5 Mock 方法

| 方法 | 说明 |
|---|---|
| `mockBody(body)` | 设置纯 Mock 响应 Body；JSON 请传合法 JSON 字符串。 |
| `mockBodyFromFile(name)` | 从 `src/test/resources/mocks/` 读取响应文件。 |
| `mockBodyFromFile(name, replacements)` | 读取文件并批量替换 JSONPath 字段。 |
| `mockStatus(status)` | 设置 Mock HTTP 状态码，必须是 `100` 到 `599`。 |
| `mockHeader(key, value)` | 设置 Mock 响应 Header。 |
| `replaceField(path, value)` / `replaceFields(map)` | 对纯 Mock Body 的 JSON 字段执行替换。 |
| `mockReplaceField(path, value)` | 在 `interceptResponse()` 模式下替换真实响应字段。 |
| `interceptResponse()` | 先访问真实后端，再修改真实响应并返回；与纯 Mock 不同，不会短路真实请求。 |

### 11.6 Modify 方法

| 方法 | 说明 |
|---|---|
| `setRequestHeader(key, value)` | 新增或覆盖请求 Header。 |
| `setRequestHeaders(map)` | 批量新增或覆盖请求 Header。 |
| `removeRequestHeader(key)` | 删除请求 Header。 |
| `modifyRequestBody(path, value)` | 修改已有 JSON 字段，支持嵌套路径和数组索引。 |
| `addRequestBodyField(path, value)` | 新增 JSON 字段，不存在的中间对象会创建。 |
| `removeRequestBodyField(path)` | 删除 JSON 字段。 |
| `modifyMethod(method)` | 修改实际发送的 HTTP 方法。 |

### 11.7 ApiCapture 与范围过滤方法

| 方法 | 说明 |
|---|---|
| `ApiCapture.start(page)` | 为指定 Page 启动 CDP 旁路采集；新建 Page 后必须单独启动。 |
| `ApiCapture.stop()` | 停止采集并释放合并器资源；测试结束必须调用。 |
| `RouteEngine.setCaptureBaseUrl(url)` | 设置全局 `BASE_URL`，按协议、host、端口过滤。传 `null` 或空字符串取消限制。 |
| `RouteEngine.setCaptureBasePath(path)` | 设置全局 `BASE_PATH`，按完整 path segment 匹配。`/api/v1` 不会匹配 `/api/v10`。 |
| `RouteEngine.clearCaptureUrlScope()` | 清除 `BASE_URL` 和 `BASE_PATH`，避免污染后续测试。 |
| `ApiCapture.getAll()` | 获取当前上下文中所有已存储调用。只包含 API 类型、命中范围且命中 endpoint 的请求。 |
| `ApiCapture.getLast(endpoint)` | 获取指定 endpoint 最近一次调用；没有捕获结果时返回 `null`。 |
| `ApiCapture.waitForApi(predicate, timeoutMs)` | 等待满足条件的调用；超时后返回 `null`，应对结果做空值判断。 |
| `ApiCapture.assertThat(endpoint)` | 按 endpoint 查询已捕获数据并创建断言链。 |
| `ApiCaptureContext.getCurrent()` | 获取全局共享捕获上下文；Handler 和测试线程使用同一实例。 |
| `ctx.getApiCalls(endpoint)` | 获取指定 endpoint 的全部调用快照。 |
| `ctx.getLastApiCall(endpoint)` | 获取指定 endpoint 最近一次调用。 |
| `ctx.getAllResponsesForUrl(url)` | 获取指定 URL 的全部响应 Body。 |
| `ctx.getLastResponse(url)` | 获取指定 URL 最近一次响应 Body。 |
| `ctx.hasAssertionFailures()` | 判断是否有 Monitor 断言失败。 |
| `ctx.buildFailureReport()` | 生成可读的断言失败报告。 |

### 11.8 CapturedApiCall 查询方法

| 方法 | 说明 |
|---|---|
| `endpoint()` | 返回注册时使用的 endpoint。 |
| `requestUrl()` | 返回实际请求 URL。 |
| `method()` | 返回 HTTP 方法。 |
| `statusCode()` | 返回响应状态码；无响应时应先判断对象和状态是否有效。 |
| `requestHeaders()` / `responseHeaders()` | 获取请求/响应 Header 快照。 |
| `responseHeader(name)` | 获取指定响应 Header。 |
| `requestBody()` / `responseBody()` | 获取请求/响应 Body。 |
| `json(path)` | 使用 JSONPath 读取响应 JSON 字段。 |
| `resourceType()` | 获取资源类型枚举。 |
| `isXhr()` / `isApiType()` | 判断是否为 XHR 或 API 类型。 |
| `isMock()` | 判断是否为 Mock 调用。 |
| `timestamp()` | 获取捕获时间戳，用于步骤窗口和时序判断。 |

### 11.9 API 使用完整示例

```java
@Before
public void setUp() {
    RouteEngine.setCaptureBaseUrl("http://localhost:8888");
    RouteEngine.setCaptureBasePath("/demo/api");
    ApiCapture.start(page);

    RouteDsl.on(page)
        .api("/demo/api/users")
        .monitor()
        .expectStatus(200)
        .expectJsonPath("$.code", 0)
        .timeout(30)
        .done()
        .start();
}

@After
public void tearDown() {
    ApiCapture.stop();
    RouteDsl.clearAllRules();
    RouteEngine.clearCaptureUrlScope();
}
```

---

## 十二、清理与生命周期

```java
RouteDsl dsl = RouteDsl.on(page)
    .api("/api/**")
    .monitor()
    .done();

dsl.start();
// ... 测试执行 ...

dsl.clear();  // 注销所有 pattern，清理上下文 + MonitorSession
```

**清理保障**：

| 场景 | 是否需要 `dsl.clear()` | 说明 |
|------|------------------------|------|
| **Serenity BDD Scenario** | ❌ 不需要 | `PlaywrightListener.testFinished()` 自动清理 |
| **独立 JUnit `@Test`** | ✅ 需要 | 不走 Serenity 生命周期，必须在 `@After` 或测试末尾显式调用 |
| **测试中途提前停止路由** | ✅ 需要 | 例如提前解除 Mock |
| **Context Session 超时后残留** | ❌ 自动处理 | Scenario 结束时统一清理 |

`RouteDsl.clearAllRules()` 用于全局清理所有上下文的所有路由规则（如测试套件结束时）。

---

## 附录：与 Playwright Router 模型的对齐计划

> 本附录记录参照 `playwright-java` 1.58 源码（`Router.java` / `RouteImpl.java`）评估得出的演进路线。
> 已落地部分用 ✅ 标注；规划部分为后续重构方向，**不影响当前 DSL 与测试契约**。

### A. 已对齐（✅ 已落地）

| 项 | Playwright 设计 | 本框架对应实现 |
|---|---|---|
| ✅ 响应透传 | `route.fulfill(FulfillOptions.setResponse(apiResponse))` 保留全部真实头 + 协议层 `fetchResponseUid` 优化 | `MockHandler` 无字段替换/无自定义头时直接 `fulfill(setResponse(realResp))`；有替换时过滤 `content-encoding`/`content-length`/`transfer-encoding` 实体头（body 已解码，保留会导致前端按压缩格式解析纯文本） |
| ✅ 一次性拦截 | `RouteOptions.setTimes(n)` — handler 处理 n 次后自动移除 | `RuleRule.times(n)` + DSL `.times(n)`：递减计数，耗尽后仅放行走真实网络（语义同 session stopped，不 unroute 以避免 Playwright 线程竞态） |

### B. Handler 链替代能力位合并（对应 Playwright `Router.RouteInfo`）— ✅ 已落地（分发期合并方案）

**现状问题（B3 改造前）**：同 pattern 多规则通过 `RouteRule.mergeFrom()` 在**注册期**有损合并到共享可变对象，产生一串工程补偿：

- `equals/hashCode` 只按 `urlPattern + modifyMethod`（放弃值语义）→ 需要 `hashCodeCached` 每 setter 手动失效
- `copyForMerge()` 深拷贝 + 合并只开不关（DELAY 取 max、MODIFY putAll、MONITOR 只开不关）
- 被迫引入 `MonitorSessionKey`（因 mergeFrom 会改 hash）
- `CROSS_LAYER_HANDLED_URLS` / `DISPATCHED_ROUTES` 静态防重门控

**已落地实现（B3 链式模型）**：

```java
// 同 pattern 多次注册 → 不再就地 mergeFrom，而是追加为规则链（后注册优先）
Map<String, List<RouteRule>> ENGINE_RULE_STORE;   // 值类型 List<RouteRule>（规则链）
// 分发时对链执行「分发期合并」：copyForMerge + 依次 mergeFrom 生成一次性有效规则，
// 语义与就地合并一致，但无注册期共享可变状态（链上原始规则永不被修改）
RouteRule effective = rule.copyForMerge();
for (RouteRule next : chainTail) effective.mergeFrom(next);
// 会话 / times / 跨层 identity 归源链头 mergeSource，而非合并临时对象
```

**迁移要点落地情况**：

1. ✅ **注册追加链尾**：注册不再 `mergeFrom`，同 pattern 规则追加为链节点（后注册优先）
2. ✅ **分发期合并**：`dispatchRoute` 对链执行 `copyForMerge + mergeFrom` 生成一次性有效规则（用后即弃，无共享可变状态），等效替代逐节点能力位检查；MONITOR 事件链监听仍为规划（保留 route 内 fetch 兜底路径）
3. ✅ **`times` 归零放行**：对齐 Playwright `Route.setTimes`，递减计数耗尽后仅放行走真实网络（不 unroute 避免线程竞态）
4. ⏳ **保留 `mergeFrom`/`copyForMerge`**：在分发期合并方案下二者作为「合并算法」被复用（不再注册期落库），故不删除；`hashCodeCached`/`MonitorSessionKey`/`CROSS_LAYER_HANDLED_URLS` 仍在使用，待 C（Router 实例化）阶段收敛

**兼容约束**：DSL API（`RouteDsl` 全部方法）、行为语义（4.4 优先级覆盖、八多规则组合）、86+ 现有测试全部保持（`b17_mock_times_one_shot` 新增验证 times 一次性拦截）。

### C. 规划：Router 实例化收敛全局状态

- 现状：`RouteEngine` 为静态全局类（`ENGINE_RULE_STORE`/`SESSIONS`/`PAGE_RULES`/`CONTEXT_RULES`/`DISPATCHED_ROUTES` 等十余张静态表），依赖 `PageRef`/TTL/容量上限防御跨测试残留。
- 目标（对齐 Playwright：每个 `BrowserContext` 一个 `Router` 实例）：
  1. `ContextRouteEngine`（已有雏形）扩展为完整 Router：持有本 context 的规则链 + 会话表 + 防重状态
  2. 请求分发：page 层链优先，未处理/fallback 自动落到 context 层链（替代 `CROSS_LAYER_HANDLED_URLS` 手动跨层合并）
  3. 静态查询 API（`resolveEndpointForUrl` 等）委托给 context 实例；`clear`/`resetAll` 随 context 生命周期自然销毁
  4. 消除 `PageRef`、TTL 扫描、`MAX_*_ENTRIES` 容量上限等防御性复杂度
- 风险：改动面最大，需分步迁移（先实例化 session 表 → 再实例化规则链 → 最后收敛静态查询 API），每步保持全量测试通过。
