# API监控和Mock使用指南

## 概述

`ApiMonitorAndMockManager` 是一个强大的工具，用于在Web UI自动化测试中监测API响应、mock response和修改请求信息。它基于Playwright的请求拦截功能，提供了丰富的API mock和监控功能。

## 功能特性

- **API请求监测**：记录所有API调用的详细信息
- **Mock Response**：模拟API响应，包括成功、错误、超时等场景
- **请求修改**：修改请求头、请求体等
- **动态响应生成**：根据请求内容动态生成响应
- **API调用历史**：记录和查询API调用历史

## 基本用法

### 1. 初始化

在测试开始前初始化API监控管理器：

```java
ApiMonitorAndMockManager.initialize();
```

### 2. 启用全局Mock

```java
// 启用全局Mock
ApiMonitorAndMockManager.enableGlobalMock();

// 禁用全局Mock
ApiMonitorAndMockManager.disableGlobalMock();
```

### 3. 为Page或BrowserContext应用Mock规则

```java
// 为单个页面应用Mock规则
ApiMonitorAndMockManager.applyMocks(page);

// 为整个BrowserContext应用Mock规则
ApiMonitorAndMockManager.applyMocks(context);
```

## 注册Mock规则

### 1. 基本Mock

```java
// Mock成功的响应
ApiMonitorAndMockManager.mockSuccess("userInfo", "/api/user/info", "{\"id\": 123, \"name\": \"John Doe\"}");

// Mock错误响应
ApiMonitorAndMockManager.mockError("apiError", "/api/submit", "Internal Server Error");

// Mock超时
ApiMonitorAndMockManager.mockTimeout("slowApi", "/api/data", 5000);

// Mock 404 Not Found
ApiMonitorAndMockManager.mockNotFound("notFound", "/api/nonexistent");
```

### 2. 自定义Mock规则

```java
ApiMonitorAndMockManager.MockRule rule = new ApiMonitorAndMockManager.MockRule("customRule", "/api/data")
    .method("POST")  // 只匹配POST请求
    .mockDataJson("{\"status\": \"ok\", \"data\": \"test\"}")
    .statusCode(200)
    .header("Content-Type", "application/json")
    .delay(1000)  // 1秒延迟
    .enabled(true);
    
ApiMonitorAndMockManager.registerMockRule(rule);
```

### 3. 动态响应生成

```java
ApiMonitorAndMockManager.registerDynamicMock("dynamicUser", "/api/users/\\d+", (request, context) -> {
    // 从请求中获取用户ID
    String url = request.url();
    int userId = Integer.parseInt(url.replaceAll(".*/users/(\\d+).*", "$1"));
    
    // 根据用户ID生成不同的响应
    return String.format("{\"id\": %d, \"name\": \"User %d\", \"status\": \"active\"}", userId, userId);
});
```

### 4. 修改请求

```java
// 添加自定义header
ApiMonitorAndMockManager.modifyRequestAddHeader("addAuthHeader", ".*", "Authorization", "Bearer test-token");
```

## API调用历史

### 获取所有API调用记录

```java
List<ApiMonitorAndMockManager.ApiCallRecord> history = ApiMonitorAndMockManager.getApiCallHistory();
```

### 按URL过滤记录

```java
// 获取特定URL的调用记录
List<ApiMonitorAndMockManager.ApiCallRecord> userListCalls = 
    ApiMonitorAndMockManager.getApiCallHistoryByUrl("/api/users");
```

### 检查mock状态

```java
ApiMonitorAndMockManager.ApiCallRecord record = history.get(0);
boolean isMocked = record.isMocked();  // 检查是否是mock响应
```

## 高级用法

### 1. 使用RequestModifier修改请求

```java
ApiMonitorAndMockManager.MockRule rule = new ApiMonitorAndMockManager.MockRule("modifyRequest", "/api/submit")
    .requestModifier(request -> {
        // 修改请求头
        request.header("X-Test-Header", "test-value");
        
        // 修改请求体
        String originalBody = request.postData();
        String modifiedBody = originalBody.replace("old", "new");
        request.postData(modifiedBody);
    });
    
ApiMonitorAndMockManager.registerMockRule(rule);
```

### 2. 使用ResponseGenerator生成复杂响应

```java
ApiMonitorAndMockManager.registerDynamicMock("complexResponse", "/api/data", (request, context) -> {
    Map<String, Object> requestBody = (Map<String, Object>) context.get("postData");
    String userId = (String) requestBody.get("userId");
    
    // 根据用户ID和请求内容生成不同的响应
    return String.format("{\"userId\": \"%s\", \"data\": \"custom response for user %s\"}", 
        userId, userId);
});
```

## 配置

可以通过配置文件控制API监控的行为：

```properties
# serenity.properties 或其他配置文件
api.monitor.enabled=true  # 启用API监控
api.mock.rules=...       # Mock规则配置
```

## 最佳实践

1. **在测试开始前初始化**：在测试套件或测试类开始前调用 `initialize()` 方法
2. **按需启用Mock**：只在需要mock API时启用全局mock
3. **清理Mock规则**：在每次测试前清除之前的mock规则，避免干扰
4. **记录API调用**：利用API调用历史进行调试和验证
5. **使用动态mock**：对于需要根据请求内容生成响应的场景，使用动态响应生成器

## 常见用例

### 1. 测试不稳定的后端API

```java
// 模拟不稳定的API，确保测试稳定运行
ApiMonitorAndMockManager.mockSuccess("unstableApi", "/api/feature", "{\"data\": \"mocked data\"}");
```

### 2. 测试异常场景

```java
// 模拟服务器错误
ApiMonitorAndMockManager.mockError("serverError", "/api/critical", "Server is down");

// 模拟网络超时
ApiMonitorAndMockManager.mockTimeout("networkTimeout", "/api/external", 3000);
```

### 3. 测试认证和授权

```java
// 为所有请求添加认证header
ApiMonitorAndMockManager.modifyRequestAddHeader("addAuth", ".*", "Authorization", "Bearer test-token");
```

### 4. 测试API集成

```java
// 记录API调用历史，验证API是否按预期调用
List<ApiMonitorAndMockManager.ApiCallRecord> history = ApiMonitorAndMockManager.getApiCallHistory();
Assertions.assertEquals(2, history.size());  // 验证调用了2个API

// 检查特定API是否被调用
List<ApiMonitorAndMockManager.ApiCallRecord> userApiCalls = 
    ApiMonitorAndMockManager.getApiCallHistoryByUrl("/api/users");
Assertions.assertFalse(userApiCalls.isEmpty());
```

## 注意事项

1. **Mock规则优先级**：多个mock规则匹配同一个请求时，以最后注册的规则为准
2. **性能影响**：大量的mock规则可能会影响测试性能，建议按需使用
3. **调试信息**：可以通过API调用历史和日志信息调试mock规则
4. **清理资源**：测试完成后清理mock规则，避免影响其他测试

通过合理使用API监控和mock功能，可以大大提高Web UI自动化测试的稳定性和可维护性。