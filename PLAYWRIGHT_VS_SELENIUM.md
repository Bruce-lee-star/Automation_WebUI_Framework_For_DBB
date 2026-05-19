# Playwright vs Selenium 核心优势

> **基于本框架的实际实现经验**

---

## 一、核心术语表

| 术语 | 英文 | 说明 |
|------|------|------|
| **Browser** | Browser | 浏览器实例（Chromium/Firefox/WebKit），进程级别 |
| **Context** | BrowserContext | 浏览器上下文，隔离的会话环境，拥有独立的 cookies、storage、indexedDB |
| **Page** | Page | 浏览器 Tab 页面，一个 Context 可包含多个 Page |
| **Route** | Route | 请求路由拦截器，用于捕获、修改或中断网络请求 |
| **RouteHandler** | RouteHandler | 路由处理器函数，匹配请求时执行的回调 |
| **Request** | Playwright Request | Playwright 封装的 HTTP 请求对象 |
| **Response** | Playwright Response | Playwright 封装的 HTTP 响应对象 |
| **Frame** | Frame | 页面内的 iframe 框架 |
| **Locator** | Locator | 元素定位器，描述如何查找 DOM 元素 |
| **Selector** | Selector | CSS/XPath 选择器字符串 |
| **CDP** | Chrome DevTools Protocol | Chrome 开发者工具协议，Playwright 与 Chromium 通信的基础 |
| **Mock** | Mock | 模拟数据/响应，用于测试中替换真实接口 |
| **Intercept** | Intercept | 拦截，请求到达服务器前捕获并处理 |
| **Fulfill** | fulfill() | 用预定义响应满足被拦截的请求 |
| **Abort** | abort() | 中止/拒绝被拦截的请求 |
| **Resume** | resume() | 放行被拦截的请求，继续原始请求 |
| **Service Worker** | Service Worker | 运行在浏览器后台的脚本，可拦截/缓存网络请求 |

---

## 二、技术架构对比

| 维度 | Playwright | Selenium |
|------|-----------|----------|
| **语言绑定** | 原生多语言（Java/Python/JS/Go/C#） | WebDriver 协议（各语言独立实现） |
| **浏览器控制** | Chrome DevTools Protocol (CDP) | WebDriver JSON Wire Protocol |
| **执行速度** | ⚡ 快 30-50%（直接 CDP，无中间协议层） | 较慢（协议转换开销） |
| **稳定性** | ⚡ 高（自动等待机制，原生 DOM 快照） | 需手动 `Thread.sleep()` 或显式等待 |
| **网络拦截** | ⚡ 强大（原生 `route`/`onResponse` API） | ❌ 弱（依赖第三方库如 BrowserMob-Proxy） |
| **Mock 能力** | ⚡ 强大（`route.fulfill()`/`route.abort()`/`route.wait()`） | ❌ 需额外工具（Selenium 本身不支持） |
| **并行测试** | ⚡ 原生支持 `browserType.launch()` 并行 | 需配置 Grid 或云服务 |
| **移动端模拟** | ⚡ 原生支持（iOS/Android viewport） | 支持但配置复杂 |
| **拦截器性能** | ⚡ 零开销（CDP 层面拦截） | ❌ 有代理中间人开销（Proxy-based） |

---

## 三、Playwright 工作原理

### 2.1 整体架构

```
┌─────────────────────────────────────────────────────────────────┐
│                        Playwright Client                        │
│  ┌─────────────────────────────────────────────────────────┐    │
│  │                    Driver (浏览器可执行文件)               │    │
│  │  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐      │    │
│  │  │ Chromium    │  │ Firefox     │  │ WebKit      │      │    │
│  │  └─────────────┘  └─────────────┘  └─────────────┘      │    │
│  └─────────────────────────────────────────────────────────┘    │
│                              ↕ CDP / Firefox Driver / WebKit    │
│  ┌─────────────────────────────────────────────────────────┐    │
│  │                   Browser Instance (进程)                 │    │
│  │  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐      │    │
│  │  │   Browser   │  │  Page(s)    │  │   Context   │      │    │
│  │  │  (主进程)   │  │ (Tab 页面)  │  │ (隔离会话)  │      │    │
│  │  └─────────────┘  └─────────────┘  └─────────────┘      │    │
│  └─────────────────────────────────────────────────────────┘    │
└─────────────────────────────────────────────────────────────────┘
```

**核心组件：**

| 组件 | 作用 |
|------|------|
| **Client (API Layer)** | Java/Python/JS 等语言编写的客户端库，提供高层 API |
| **Driver** | 原生可执行文件，负责启动浏览器进程、建立通信 |
| **Browser** | Chromium/Firefox/WebKit 浏览器实例 |
| **Context** | 浏览器上下文，隔离 cookies/storage，多 Context 共享进程 |
| **Page** | 浏览器 Tab 页面，一个 Context 可有多个 Page |

### 2.2 通信协议：Chrome DevTools Protocol (CDP)

Playwright 与 Chromium 系列浏览器的核心通信基于 **CDP (Chrome DevTools Protocol)**：

```java
// Playwright 内部通过 CDP 发送命令
// 例如：page.click("#button") 实际发送的 CDP 命令：
{
  "id": 12,
  "method": "Input.dispatchMouseEvent",
  "params": {
    "type": "mousePressed",
    "x": 100,
    "y": 200,
    "button": "left",
    "clickCount": 1
  }
}
```

**CDP 优势：**
- **直接通信**：绕过 WebDriver 协议，无中间层
- **功能丰富**：支持网络拦截、Service Worker、性能追踪等
- **实时性强**：WebSocket 持久连接，事件实时推送

### 2.3 Playwright 路由机制原理

```
┌──────────────────────────────────────────────────────────────────┐
│                       请求拦截流程                                │
├──────────────────────────────────────────────────────────────────┤
│                                                                  │
│   Browser ──请求──▶ │  Playwright Route Handler │ ──处理──▶     │
│                     │                            │               │
│   拦截点:           │  ┌──────────────────────┐  │               │
│   CDP Network Layer │  │ 1. route.fulfill()   │──│──▶ 返回 Mock  │
│                     │  │    返回预定义响应     │  │               │
│                     │  ├──────────────────────┤  │               │
│                     │  │ 2. route.abort()     │──│──▶ 拒绝请求   │
│                     │  │    中断请求           │  │               │
│                     │  ├──────────────────────┤  │               │
│                     │  │ 3. route.resume()    │──│──▶ 放行请求   │
│                     │  │    继续原始请求       │  │               │
│                     │  ├──────────────────────┤  │               │
│                     │  │ 4. route.wait(ms)    │──│──▶ 延迟响应   │
│                     │  │    暂停后继续         │  │               │
│                     │  └──────────────────────┘  │               │
│                     └────────────────────────────┘               │
│                                                                  │
└──────────────────────────────────────────────────────────────────┘
```

**路由注册与触发：**

```java
// 1. 注册路由模式
context.route("**/api/users", route -> {
    // 2. 命中请求时，Playwright 调用此 Handler
    // 3. Handler 决定如何处理请求
    route.fulfill(new Route.FulfillOptions()
        .setStatus(200)
        .setBody("{\"name\":\"test\"}"));
});

// 底层原理：
// a) Playwright 通过 CDP 的 Network.setRequestInterception() 开启拦截
// b) 当请求匹配模式时，CDP 暂停请求并通知 Playwright
// c) Playwright 调用注册的 Java Handler
// d) Handler 调用 route.fulfill()/abort()/resume() 决定请求命运
// e) Playwright 通过 CDP 继续或终止请求
```

### 2.4 Context 生命周期管理

```
┌─────────────────────────────────────────────────────────────────┐
│                      Browser Lifecycle                          │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  Browser.launch()                                                │
│       │                                                         │
│       ▼                                                         │
│  ┌─────────────────┐                                            │
│  │  BrowserContext │ ◀── 创建隔离会话                            │
│  │  (指纹/cookie   │                                            │
│  │   完全隔离)      │                                            │
│  └────────┬────────┘                                            │
│           │                                                     │
│           ├──────────────┬──────────────┐                        │
│           ▼              ▼              ▼                        │
│      ┌────────┐     ┌────────┐     ┌────────┐                   │
│      │ Page 1 │     │ Page 2 │     │ Page 3 │  ...              │
│      └────────┘     └────────┘     └────────┘                   │
│                                                                  │
│  Context.aboutToRebuild() ──▶ 快照保存 ──▶ 重建 Context          │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

**为什么需要 Context 重建？**

| 场景 | 原因 |
|------|------|
| **认证状态变化** | 登录/登出后需要新的隔离 Context |
| **缓存清理** | 清除所有 cookies/storage |
| **测试隔离** | 每个测试用例独立 Context |

### 2.5 自动等待机制

```java
// Selenium 需要手动等待
WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(10));
wait.until(ExpectedConditions.elementToBeClickable(By.id("btn")));

// Playwright 自动等待
page.click("#btn");  // Playwright 自动等待元素可见、可点击
```

**Playwright 自动等待检查项：**
1. 元素是否 attached 到 DOM
2. 元素是否 visible（非 hidden）
3. 元素是否 stable（非动画中）
4. 元素是否 enabled（可点击）
5. 元素是否 actionable（可接收点击事件）

---

## 四、API Mock 能力对比

### Playwright 路由机制（原生支持）

```java
// ✅ Playwright 原生 Mock - 直接、强大、无依赖
context.route("**/api/users", route -> {
    route.fulfill(new Route.FulfillOptions()
        .setStatus(200)
        .setBody("{\"id\":1,\"name\":\"Mocked User\"}")));
});

// ✅ 动态 Mock - 根据请求内容返回不同响应
context.route("**/api/users/*", route -> {
    String userId = route.request().url().split("/api/users/")[1];
    route.fulfill(new Route.FulfillOptions()
        .setBody("{\"id\":" + userId + ",\"name\":\"User-" + userId + "\"}")));
});

// ✅ 延迟 Mock - 原生支持，无额外线程
context.route("**/api/slow", route -> {
    route.wait(2000);  // 等待 2 秒后响应
    route.fulfill(options);
});

// ✅ 拦截真实响应后修改
context.route("**/api/data", route -> {
    route.resume();  // 先放行到服务器
    // 获取响应后修改...（需配合 onResponse）
});
```

### Selenium 路由机制（依赖第三方工具）

```java
// ❌ Selenium 本身不支持 Mock，需要 BrowserMob-Proxy
// 问题：引入额外依赖，配置复杂，性能开销大

SeleniumProxy proxy = new SeleniumProxy(browsermobProxy);
DesiredCapabilities capabilities = new DesiredCapabilities();
capabilities.setCapability(CapabilityType.PROXY, proxy);

proxy.addRequestFilter((request, contents, messageInfo) -> {
    if (request.getUrl().contains("/api/users")) {
        return new Response(200, 
            Map.of("Content-Type", "application/json"),
            "{\"id\":1,\"name\":\"Mocked User\"}");
    }
    return null;  // 继续原始请求
});
```

---

## 五、网络拦截对比

| 能力 | Playwright | Selenium |
|------|-----------|----------|
| **拦截请求** | ✅ `context.route()` / `page.route()` | ❌ 需 BrowserMob-Proxy |
| **修改请求** | ✅ `route.fulfill()` / `route.abort()` / `route.resume()` | ❌ 需 Proxy |
| **拦截响应** | ✅ `context.onResponse()` / `page.onResponse()` | ⚠️ Proxy 的 filter |
| **修改响应** | ✅ 修改后 `route.fulfill()` | ⚠️ Proxy filter 复杂 |
| **延迟模拟** | ✅ `route.wait(ms)` 原生支持 | ❌ 需手动线程睡眠 |
| **并发控制** | ✅ 路由链式处理，无阻塞 | ⚠️ Proxy 线程池配置 |
| **内存泄漏防护** | ✅ 路由异常自动兜底（resume → abort） | ❌ 需手动处理 |
| **重复注册防护** | ✅ 路由去重机制 | ⚠️ Proxy 重复注册会叠加 |

---

## 六、核心优势总结

**选择 Playwright 的关键理由：**

1. **零依赖 Mock** - 无需 BrowserMob-Proxy 等第三方工具，直接使用原生 API
2. **路由安全机制** - `applyRoutes()` 强制清理 + 双重异常兜底，避免白屏
3. **原生延迟支持** - `route.wait()` 在回调内同步执行，无异步线程风险
4. **高性能拦截** - CDP 层面拦截，无代理中间人开销
5. **自动等待机制** - Playwright 自动等待元素可点击/可见，减少 flaky tests
6. **多浏览器一致** - Chromium/Firefox/WebKit 使用统一 API

---

## 七、本框架的 Playwright 路由安全机制

### 5.1 路由重复注册防护

```java
// ApiMonitorAndMockManager.applyRoutes() 核心逻辑
private void applyRoutes() {
    // 1. 强制清理所有已注册的路由，防止链式阻塞
    for (String pattern : registeredPatterns) {
        safeUnroute(targetPage, pattern);
        safeUnroute(targetContext, pattern);
    }
    registeredPatterns.clear();
    
    // 2. 额外防护：强制解绑该 endpoint 对应的所有旧路由
    for (MockRule rule : rulesSnapshot) {
        if (rule.isEnabled()) {
            unbindPattern(rule.getUrlPattern());
        }
    }
    
    // 3. 重新注册 Mock 规则
    for (MockRule rule : rulesSnapshot) {
        if (!rule.isEnabled()) continue;
        bindRouteIfNeeded(rule.getUrlPattern());
    }
}
```

### 5.2 双重异常兜底

```java
// handleUnifiedRoute() 确保每个请求 100% 被处理
private void handleUnifiedRoute(Route route) {
    try {
        MockRule matchedMock = findMatchingMock(route.request());
        if (matchedMock != null) {
            handleMock(route, matchedMock);
            return;
        }
        recordRealRequest(route);
        safeResume(route);

    } catch (Throwable e) {
        // 1. 先尝试 resume 放行请求
        try {
            safeResume(route);
        } catch (Throwable resumeEx) {
            // 2. resume 失败，强制 abort 避免请求永久挂起
            try {
                route.abort("failedhandler");
            } catch (Throwable abortEx) {
                // 3. abort 也失败，记录日志
            }
        }
    }
}
```

---

## 八、Selenium 的替代方案及局限性

如果必须使用 Selenium，网络拦截需要依赖以下工具：

| 工具 | 优点 | 缺点 |
|------|------|------|
| **BrowserMob-Proxy** | 功能丰富，支持请求/响应修改 | 需要额外进程，配置复杂，性能开销 |
| **Zap Proxy** | OWASP 项目，功能全面 | 学习曲线陡峭，集成复杂 |
| **Selenium Wire** | 纯 Java 实现 | 可能与新版 Selenium 冲突 |

> **结论**：对于需要强大 API Mock 和监控能力的测试框架，Playwright 是更优选择。
