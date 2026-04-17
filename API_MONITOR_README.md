# API 监控工具包使用指南

提供了三个核心工具类，用于 WebUI 自动化测试中的 API 监控、请求修改和 Mock 管理。

## 📦 核心类概览

| 类名 | 功能 | 使用场景 |
|------|------|----------|
| **RealApiMonitor** | API 监控（非阻塞） | 监控页面触发的 API 调用 |
| **ApiRequestModifier** | 请求修改 | 修改请求的 Body、Headers、URL 等 |
| **ApiMonitorAndMockManager** | Mock 管理 | Mock API 响应 |

---

## 1️⃣ RealApiMonitor - API 监控工具

### 核心特性
- ✅ **非阻塞**：所有方法都是异步的，不影响测试流程
- ✅ **辅助工具**：用于在 WebUI 测试过程中监控 API 调用
- ✅ **自动捕获**：启动监控后，自动捕获后续 API
- ✅ **自动停止**：捕获到所有目标 API 后自动停止监控，无需手动干预
- ✅ **超时保护**：无新 API 调用时超时自动停止
- ✅ **实时验证**：支持对期望状态码进行实时校验
- ✅ **Serenity 集成**：自动将结果记录到 Serenity 报告

### 停止机制说明

RealApiMonitor 提供**两种自动停止机制**：

#### 🎯 机制一：目标 API 全部捕获后自动停止（推荐）

当通过 `.api()` 方法指定了目标 API 时，监控系统会追踪每个目标 API 是否已被捕获。一旦**所有目标 API 都被捕获**，监控会立即自动停止，无需等待超时。

```
启动监控 → 捕获目标API #1 → 捕获目标API #2 → ... → 所有目标已捕获 → 自动停止 ✓
```

```java
// 示例：监控3个目标API，全部捕获后自动停止
RealApiMonitor.monitor(context)
    .api("/api/login", 200)       // 目标1: 登录接口
    .api("/api/userInfo", 200)     // 目标2: 用户信息
    .api("/api/permissions", 200)  // 目标3: 权限接口
    .timeout(60)                   // 最大等待时间（兜底）
    .start();                      // 启动监控

// 执行操作触发API...
loginPage.login("user", "pass");

// 无需手动停止！当3个API全部被捕获后，系统自动:
// 1. 标记 allTargetApisCaptured = true
// 2. 调用 stopMonitoring()
// 3. 将结果记录到 Serenity 报告
```

#### ⏱️ 机制二：超时自动停止（兜底）

如果在 `timeout()` 指定的时间内没有新的 API 调用产生，监控也会自动停止。这确保了即使某些 API 未被捕获，监控也不会永久运行。

```
启动监控 → 有API调用(重置计时器) → 无新API → 等待... → 超时到达 → 自动停止
```

### 核心方法

#### `monitor().start()` - 异步监控 API
```java
// 在执行操作前启动监控
RealApiMonitor.monitor(context)
    .api("/api/login", 200)      // 监控特定 API，期望状态码 200
    .api("/api/user", 200)        // 可同时监控多个 API
    .timeout(30)                  // 超过 timeout 时间无新API则自动停止（兜底）
    .start();                     // 异步启动，立即返回

// 执行操作... 所有目标API捕获后自动停止！
loginButton.click();

// 查询捕获的 API
ApiCallRecord record = RealApiMonitor.getLast("/api/login");
```

#### `getLast()` - 获取最后一条记录
```java
ApiCallRecord record = RealApiMonitor.getLast("/api/login");
if (record != null) {
    String url = record.getUrl();
    int status = record.getStatusCode();
    String body = String.valueOf(record.getResponseBody());
}
```

#### `getLastBody()` - 获取响应体
```java
String body = RealApiMonitor.getLastBody("/api/login");
```

#### `getHistory()` - 获取所有记录
```java
List<ApiCallRecord> history = RealApiMonitor.getHistory();
```

#### `clearHistory()` - 清空历史记录
```java
RealApiMonitor.clearHistory();
```

#### `setTargetHost()` - 设置目标 Host
```java
// 只监控指定 host 的 API
RealApiMonitor.setTargetHost("api.example.com");
```

### ApiCallRecord 字段说明

| 字段 | 类型 | 说明 |
|------|------|------|
| `url` | String | 请求 URL |
| `method` | String | HTTP 方法（GET/POST/PUT/DELETE等） |
| `statusCode` | int | 响应状态码 |
| `timestamp` | long | 时间戳 |
| `requestHeaders` | Map | 请求头 |
| `responseHeaders` | Map | 响应头 |
| `responseBody` | Object | 响应体（延迟读取） |
| `isMocked` | boolean | 是否为 Mock 数据 |

### 完整示例

#### 示例 1：基础监控（自动停止）
```java
// 1. 导航到页面
loginPage.navigateTo(currentUrl);

// 2. 启动 API 监控（在点击按钮前）- 捕获到目标API后自动停止！
BrowserContext context = loginPage.getContext();
RealApiMonitor.monitor(context)
    .api("lastLoginTime", 200)   // 目标API: 期望状态码200
    .timeout(30)                  // 兜底超时：30秒无新API则停止
    .start();                     // 异步启动，立即返回

// 3. 执行操作触发 API（无需手动停止监控）
loginPage.userNameIpt.type("user");
loginPage.passwordIpt.type("password");
loginPage.loginBtn.click();

// 4. 验证结果 - 目标API已被自动捕获并记录
ApiCallRecord record = RealApiMonitor.getLast("lastLoginTime");
assertNotNull("Should capture API", record);
assertEquals("Status should be 200", 200, record.getStatusCode());
```

#### 示例 2：多目标 API 监控（全部捕获后自动停止）
```java
// 场景：登录后需要等待多个接口返回
RealApiMonitor.monitor(context)
    .api("/api/auth/login", 200)      // 目标1: 登录认证
    .api("/api/user/profile", 200)    // 目标2: 用户资料
    .api("/api/user/permissions", 200)// 目标3: 权限数据
    .timeout(60)                      // 兜底超时60秒
    .start();

// 执行登录操作
loginPage.login("username", "password");

// 系统会在3个API全部捕获后自动停止，或60秒超时后停止

// 验证所有目标API都被捕获
assertNotNull(RealApiMonitor.getLast("/api/auth/login"));
assertNotNull(RealApiMonitor.getLast("/api/user/profile"));
assertNotNull(RealApiMonitor.getLast("/api/user/permissions"));
```

#### 示例 3：只监控不验证状态码
```java
// 只关注是否调用了某个API，不关心状态码
RealApiMonitor.monitor(context)
    .api("/api/analytics/track")     // 不指定状态码 = 不验证
    .timeout(15)
    .start();

page.click(".track-button");

ApiCallRecord record = RealApiMonitor.getLast("/api/analytics/track");
assertNotNull("Analytics should be tracked", record);
```

---

## 2️⃣ ApiRequestModifier - 请求修改器

### 核心特性
- ✅ **链式调用**：所有方法返回 `this`，支持链式操作
- ✅ **完全控制**：可修改 Body、Headers、QueryParams、Method、URL
- ✅ **灵活修改**：支持字段级修改和整体替换

### 核心方法

#### 创建实例
```java
ApiRequestModifier modification = ApiRequestModifier.create();
```

#### Body 操作
```java
// 完全替换 body
modification.body("{\"userId\":\"123\"}");

// 删除整个 body
modification.removeBody();

// 修改单个字段（支持嵌套路径）
modification.modifyBodyField("userId", "123");
modification.modifyBodyField("user.name", "John");
modification.modifyBodyField("items[0].id", "item1");

// 批量修改字段
Map<String, Object> fields = new HashMap<>();
fields.put("name", "John");
fields.put("age", 25);
modification.modifyBodyFields(fields);

// 删除单个字段
modification.removeBodyField("password");

// 批量删除字段
modification.removeBodyFields("password", "token", "secret");
```

#### Header 操作
```java
// 修改单个 header
modification.modifyHeader("Authorization", "Bearer token123");

// 批量修改 headers
Map<String, String> headers = new HashMap<>();
headers.put("Content-Type", "application/json");
headers.put("x-custom-header", "value");
modification.modifyHeaders(headers);

// 删除单个 header
modification.removeHeader("Cookie");

// 批量删除 headers
modification.removeHeaders("Cookie", "Set-Cookie");
```

#### QueryParam 操作
```java
// 修改单个参数
modification.modifyQueryParam("page", "1");

// 批量修改参数
Map<String, String> params = new HashMap<>();
params.put("page", "1");
params.put("size", "10");
modification.modifyQueryParams(params);

// 删除单个参数
modification.removeQueryParam("debug");

// 批量删除参数
modification.removeQueryParams("debug", "test");
```

#### Method 操作
```java
modification.method("POST");
modification.method("GET");
modification.method("PUT");
modification.method("DELETE");
```

#### URL 操作
```java
// 完全替换 URL
modification.url("https://new-api.example.com/path");

// 只替换 host（保留路径和查询参数）
modification.host("test-api.example.com");
```

#### 清理方法
```java
// 清空所有修改配置
modification.clear();
```

### 应用修改

```java
BrowserContext context = page.getContext();

ApiRequestModifier modification = ApiRequestModifier.create()
    .modifyBodyField("userId", "123")
    .modifyHeader("x-custom", "value")
    .host("test-api.example.com");

// 应用修改
RequestResponseStore store = ApiRequestModifier.modifyRequest(
    context, 
    "/api/users", 
    modification
);

// 执行操作
page.click("button");

// 获取最后一次请求/响应
RequestResponseInfo lastInfo = store.getLast();
System.out.println("Request: " + lastInfo.request.url);
System.out.println("Response Status: " + lastInfo.response.status);
```

### 使用回调

```java
RequestResponseStore store = ApiRequestModifier.modifyRequest(
    context, 
    "/api/login", 
    modification,
    info -> {
        System.out.println("Request URL: " + info.request.url);
        System.out.println("Response Status: " + info.response.status);
        System.out.println("Response Body: " + info.response.body);
    }
);
```

### 完整示例

#### 场景 1：切换环境（生产→测试）
```java
ApiRequestModifier modification = ApiRequestModifier.create()
    .host("test-api.example.com");

ApiRequestModifier.modifyRequest(context, "/api/users", modification);
```

#### 场景 2：修改请求参数 + 切换服务器
```java
ApiRequestModifier modification = ApiRequestModifier.create()
    .host("test-server.com")
    .modifyBodyField("environment", "test")
    .modifyHeader("x-test-mode", "true");

ApiRequestModifier.modifyRequest(context, "/api/login", modification);
```

#### 场景 3：完全重写请求
```java
ApiRequestModifier modification = ApiRequestModifier.create()
    .url("https://mock-server.com/api/v2/endpoint")
    .method("POST")
    .body("{\"mock\":true}")
    .modifyHeader("Content-Type", "application/json");

ApiRequestModifier.modifyRequest(context, "/api/old", modification);
```

---

## 3️⃣ ApiMonitorAndMockManager - Mock 管理

### 核心特性
- ✅ **灵活 Mock**：支持静态响应和动态响应
- ✅ **条件匹配**：支持 URL、Method、Body 条件匹配
- ✅ **链式规则**：支持多个 Mock 规则链式配置

### 核心方法

#### 创建 Mock 规则
```java
MockBuilder mock = MockBuilder.create()
    .url("/api/login")
    .method("POST")
    .status(200)
    .body("{\"token\":\"mock-token\",\"userId\":\"123\"}");
```

#### 应用 Mock
```java
BrowserContext context = page.getContext();
ApiMonitorAndMockManager.mock(context, mock);
```

### 完整示例

#### 场景 1：Mock 登录 API
```java
MockBuilder loginMock = MockBuilder.create()
    .url("/api/auth/login")
    .method("POST")
    .status(200)
    .body("{\"success\":true,\"token\":\"mock-jwt-token\"}")
    .header("Content-Type", "application/json");

ApiMonitorAndMockManager.mock(context, loginMock);

// 执行登录操作
loginPage.login("user", "password");
```

#### 场景 2：动态 Mock 响应
```java
MockBuilder userMock = MockBuilder.create()
    .url("/api/users/(.*)")  // 支持正则
    .method("GET")
    .dynamicResponse(request -> {
        String userId = extractUserIdFromUrl(request.url);
        return "{\"id\":\"" + userId + "\",\"name\":\"Test User\"}";
    });

ApiMonitorAndMockManager.mock(context, userMock);
```

---

## 🎯 最佳实践

### 1. RealApiMonitor 最佳实践

✅ **在操作前启动监控**
```java
// ✅ 正确：先启动监控
RealApiMonitor.monitor(context).api("/api/login", 200).start();
loginButton.click();

// ❌ 错误：操作后才启动监控
loginButton.click();
RealApiMonitor.monitor(context).api("/api/login", 200).start();  // 太晚了！
```

✅ **利用自动停止机制（无需手动 stopMonitoring）**
```java
// ✅ 推荐：配置目标API + 超时，系统自动管理生命周期
RealApiMonitor.monitor(context)
    .api("/api/login", 200)
    .api("/api/userInfo", 200)
    .timeout(30)       // 兜底超时
    .start();          // 所有目标捕获或超时后自动停止

loginPage.login("user", "pass");
// 无需调用 stopMonitoring()！
```

✅ **设置合理的超时时间**
```java
RealApiMonitor.monitor(context)
    .api("/api/login", 200)
    .timeout(30)  // 根据实际 API 响应时间调整（兜底保护）
    .start();
```

✅ **测试后清理状态**（同一测试类中多个 Scenario 时）
```java
@After
public void tearDown() {
    RealApiMonitor.stopMonitoring();   // 停止监控
    RealApiMonitor.clearHistory();     // 清空历史
    RealApiMonitor.resetForNextScenario();  // 重置Serenity记录标志
}
```

⚠️ **手动停止的场景**
```java
// 如果需要在特定时机提前停止（如验证中间状态）
RealApiMonitor.stopMonitoring();
List<ApiCallRecord> partial = RealApiMonitor.getHistory();
```

### 2. ApiRequestModifier 最佳实践

✅ **使用 Factory 方法**
```java
// ✅ 推荐
ApiRequestModifier modification = ApiRequestModifier.create()
    .modifyBodyField("userId", "123");

// ❌ 不推荐
ApiRequestModifier modification = new ApiRequestModifier()
    .modifyBodyField("userId", "123");
```

✅ **复用 Modifier**
```java
ApiRequestModifier baseModification = ApiRequestModifier.create()
    .modifyHeader("Authorization", "Bearer token");

// 复用并添加新配置
ApiRequestModifier.modifyRequest(context, "/api/1", baseModification);

baseModification.modifyBodyField("field", "value");
ApiRequestModifier.modifyRequest(context, "/api/2", baseModification);

// 清空后复用
baseModification.clear()
    .modifyBodyField("newField", "newValue");
ApiRequestModifier.modifyRequest(context, "/api/3", baseModification);
```

### 3. 组合使用

✅ **监控 + 修改**
```java
// 启动监控
RealApiMonitor.monitor(context)
    .api("/api/login", 200)
    .start();

// 修改请求
ApiRequestModifier modification = ApiRequestModifier.create()
    .modifyBodyField("username", "testuser");

ApiRequestModifier.modifyRequest(context, "/api/login", modification);

// 执行操作
loginButton.click();

// 验证结果
ApiCallRecord record = RealApiMonitor.getLast("/api/login");
assertNotNull("Should capture modified API", record);
```

---

## ⚠️ 注意事项

### 1. RealApiMonitor 注意事项
- ❌ **不要在阻塞方法中使用**：所有方法都是异步的
- ⚠️ **线程安全**：所有方法都是线程安全的
- ⚠️ **内存管理**：大量 API 调用可能占用内存，建议定期 `clearHistory()`

### 2. ApiRequestModifier 注意事项
- ⚠️ **JSON 格式**：`modifyBodyField()` 只适用于 JSON body
- ⚠️ **URL 编码**：QueryParams 会自动进行 URL 编码
- ⚠️ **Host 替换**：`host()` 会保留原有路径和查询参数

### 3. Mock 注意事项
- ⚠️ **Mock 优先级**：后添加的 Mock 规则优先级更高
- ⚠️ **清理 Mock**：测试后需要清理 Mock 规则，避免影响其他测试

---

## 📚 API 参考

### RealApiMonitor API

| 方法 | 参数 | 返回值 | 说明 |
|------|------|--------|------|
| `monitor(context)` | BrowserContext | MonitorBuilder | 创建监控构建器（Context版本） |
| `monitor(page)` | Page | MonitorBuilder | 创建监控构建器（Page版本） |
| `getLast(pattern)` | String | ApiCallRecord | 获取最后一条匹配的记录 |
| `getLastBody(pattern)` | String | String | 获取最后一条记录的响应体 |
| `getHistory()` | - | List\<ApiCallRecord\> | 获取所有记录 |
| `clearHistory()` | - | void | 清空历史记录和重置状态 |
| `setTargetHost(host)` | String | void | 设置目标 Host 过滤 |
| `clearTargetHost()` | - | void | 清除目标 Host 过滤 |
| `stopMonitoring()` | - | void | 手动停止监控并记录结果到 Serenity |
| `logResults()` | - | void | 将结果记录到 Serenity 报告 |
| `resetForNextScenario()` | - | void | 重置状态，支持同一测试多次 start/stop |

### MonitorBuilder API（链式配置）

| 方法 | 参数 | 返回值 | 说明 |
|------|------|--------|------|
| `api(pattern, status)` | String, int | MonitorBuilder | 添加目标API + 期望状态码 |
| `api(pattern)` | String | MonitorBuilder | 添加目标API（不验证状态码） |
| `timeout(seconds)` | int | MonitorBuilder | 设置兜底超时时间（秒） |
| `start()` | - | void | 异步启动监控，立即返回 |

**自动停止行为：**
- 当所有 `.api()` 指定的目标 API 全部被捕获时 → **立即自动停止**
- 当超过 `timeout()` 时间无新 API 调用时 → **超时自动停止**
- 调用 `stopMonitoring()` 时 → **手动停止**

### ApiRequestModifier API

| 方法 | 参数 | 返回值 | 说明 |
|------|------|--------|------|
| `create()` | - | ApiRequestModifier | 创建实例 |
| `body(body)` | String | ApiRequestModifier | 替换整个 body |
| `removeBody()` | - | ApiRequestModifier | 删除整个 body |
| `modifyBodyField(path, value)` | String, Object | ApiRequestModifier | 修改字段 |
| `removeBodyField(path)` | String | ApiRequestModifier | 删除字段 |
| `modifyHeader(name, value)` | String, String | ApiRequestModifier | 修改 header |
| `removeHeader(name)` | String | ApiRequestModifier | 删除 header |
| `modifyQueryParam(name, value)` | String, String | ApiRequestModifier | 修改参数 |
| `removeQueryParam(name)` | String | ApiRequestModifier | 删除参数 |
| `method(method)` | String | ApiRequestModifier | 修改 HTTP 方法 |
| `url(url)` | String | ApiRequestModifier | 替换整个 URL |
| `host(host)` | String | ApiRequestModifier | 替换 Host |
| `clear()` | - | ApiRequestModifier | 清空所有配置 |

---

