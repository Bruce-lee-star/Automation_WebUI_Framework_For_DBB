# Playwright 监听器全景指南

> **基于本框架实际使用的 Playwright `on*` / `once*` 事件监听系统整理**

---

## 核心概念：Playwright 的事件驱动模型

Playwright 的监听器（Listener）是整个自动化框架的神经中枢。它遵循**"先注册监听器，再执行动作"**的事件驱动范式：

```
注册监听器 → 执行触发动作 → 事件到达 → 回调处理
    ↑                                              |
    └────────  一次性监听器自动注销  ────────────────┘
```

**两种注册模式：**

| 方法 | 触发次数 | 适用场景 |
|------|---------|---------|
| `on*(handler)` | **无限次** — 每次事件都触发 | 全生命周期监听（下载、新页面、路由） |
| `once*(handler)` | **一次** — 触发后自动注销 | 预期发生一次的事件（Dialog、Popup） |

---

## 一、监听器全景分类

### 按归对象分类

```
Playwright
  ├── Browser.onDisconnected()      浏览器断开
  │
  ├── BrowserContext
  │     ├── context.onPage()        新 Page 创建
  │     ├── context.onClose()       上下文关闭
  │     ├── context.onRequest()     请求发送（⚠️ 与 route 互斥）
  │     ├── context.onResponse()    响应返回（⚠️ 与 route 互斥）
  │     └── context.route()         网络路由拦截
  │
  └── Page
        ├── page.onLoad()           页面加载完成
        ├── page.onClose()          页面关闭
        ├── page.onDialog()         Dialog 弹出（每次）
        ├── page.onceDialog()       Dialog 弹出（一次性）
        ├── page.onDownload()       文件下载
        ├── page.onPopup()          弹出窗口
        ├── page.onConsole()        控制台消息
        ├── page.onPageError()      页面未捕获异常
        ├── page.onRequest()        请求发送（⚠️ 与 route 互斥）
        ├── page.onResponse()       响应返回（⚠️ 与 route 互斥）
        ├── page.onRequestFailed()  请求失败
        ├── page.onRequestFinished()请求完成
        ├── page.onFrameNavigated() Frame 导航
        ├── page.onFrameDetached()  Frame 分离
        ├── page.onFileChooser()    文件选择器弹出
        ├── page.onWebSocket()      WebSocket 连接
        └── page.route()            页面级路由拦截
```

---

## 二、本框架实际使用的监听器

### 2.1 `context.onPage()` — 新窗口/Tab 探测

| 维度 | 说明 |
|------|------|
| **注册位置** | `PlaywrightContextManager.createContext()` |
| **注册时机** | 每次创建 BrowserContext 时 |
| **触发条件** | 页面通过 `window.open()` / `target="_blank"` 打开新页面 |
| **生命周期** | 跟随 BrowserContext，Context 关闭即注销 |
| **用途** | 调试日志、配合 `switchToNewPage()` 提供新页面就绪信号 |

```java
// PlaywrightContextManager.java:62-69
context.onPage(newPage -> {
    // 立即记录 URL（新页面可能尚未加载完成）
    LoggingConfigUtil.logInfoIfVerbose(logger,
            "New page detected via window.open(): url={}", newPage.url());

    // 在新页面上注册 onLoad，等待内容加载完成
    newPage.onLoad(pageLoad -> {
        LoggingConfigUtil.logDebugIfVerbose(logger,
                "New page loaded: url={}, title={}", newPage.url(), newPage.title());
    });
});
```

**为什么需要这个监听器？**

当测试中点击"在新窗口中打开"的链接后：
1. Browser 创建新 Target
2. CDP 触发 `Target.attachedToTarget` 事件
3. Playwright 收到事件 → 创建 Page 对象 → 调用 `context.onPage()` 回调
4. 框架记录新页面 URL，供后续 `switchToNewPage()` 匹配

**与 `switchToNewPage()` 的配合：**

```java
// BasePage.switchToNewPage() 内部逻辑：
// 1. 检查 context.pages() 是否已有新页面（快速路径）
// 2. 若无 → 注册一次性 context.waitForPage() 等待
// 3. context.onPage() 提供额外的日志可见性
```

---

### 2.2 `page.onLoad()` — 页面加载完成监听

| 维度 | 说明 |
|------|------|
| **注册位置** | `PlaywrightContextManager.createContext()` 中 `context.onPage()` 回调内 |
| **注册时机** | 嵌套注册：新 Page 创建时自动绑定 |
| **触发条件** | 页面 `load` 事件触发（所有资源加载完成） |
| **生命周期** | 跟随 Page，Page 关闭即注销 |
| **用途** | 日志记录新页面的 URL 和标题 |

```java
// 嵌套在 context.onPage() 中
newPage.onLoad(pageLoad -> {
    LoggingConfigUtil.logDebugIfVerbose(logger,
            "New page loaded: url={}, title={}", newPage.url(), newPage.title());
});
```

**注意事项：**
- 仅对 `window.open()` 产生的新页面生效
- 主 Page（`context.newPage()` 创建）不会自动绑定此监听器
- 如果需要监听主 Page 的 load 事件，需在 `createPage()` 中手动注册

---

### 2.3 `page.onDownload()` — 文件下载自动保存

| 维度 | 说明 |
|------|------|
| **注册位置** | `PlaywrightContextManager.createPage()` |
| **注册时机** | 每次创建 Page 时 |
| **触发条件** | 浏览器触发下载行为（点击下载链接、自动下载等） |
| **生命周期** | 跟随 Page |
| **用途** | 自动保存下载文件到指定目录 |

```java
// PlaywrightContextManager.java:96-110
page.onDownload(download -> {
    try {
        Path downloadDir = Paths.get(downloadsPath);
        if (!Files.exists(downloadDir)) {
            Files.createDirectories(downloadDir);
        }
        String suggestedFilename = download.suggestedFilename();
        Path savePath = downloadDir.resolve(suggestedFilename);
        download.saveAs(savePath);
        LoggingConfigUtil.logInfoIfVerbose(logger,
                "Download completed: {} -> {}", suggestedFilename, savePath.toAbsolutePath());
    } catch (Exception e) {
        logger.error("[Download] Failed to save file: {}", e.getMessage(), e);
    }
});
```

**vs Selenium 的文件下载：**

| 维度 | Selenium | Playwright (本框架) |
|------|----------|---------------------|
| **配置** | ChromeOptions `download.default_directory` | `playwright.browser.downloadsPath` 配置 |
| **检测** | 轮询文件系统 `.crdownload` 状态 | `onDownload` 事件通知，零延迟 |
| **保存** | 浏览器自动保存 | 回调中调用 `download.saveAs(path)` 精确控制 |

---

### 2.4 `page.onceDialog()` — Alert / Confirm / Prompt 处理

| 维度 | 说明 |
|------|------|
| **注册位置** | `BasePage.acceptAlert()` / `BasePage.dismissAlert()` |
| **注册时机** | 用户调用时（必须在触发 Dialog 的代码之前） |
| **触发条件** | 页面上弹出 `alert()` / `confirm()` / `prompt()` |
| **生命周期** | **一次性** — 触发后自动注销 |
| **用途** | 自动化处理 JavaScript 弹窗 |

```java
// BasePage.java:1103-1111
public void acceptAlert() {
    ensurePageValid();
    page.onceDialog(Dialog::accept);  // 弹框出现时自动点击"确定"
}

public void dismissAlert() {
    ensurePageValid();
    page.onceDialog(Dialog::dismiss); // 弹框出现时自动点击"取消"
}
```

**一次性 vs 持续性监听：**

```java
// onDialog（每次触发）— 适用于页面反复弹框的场景
page.onDialog(dialog -> {
    System.out.println("Dialog message: " + dialog.message());
    dialog.accept();
});

// onceDialog（只触发一次）— 适用于预期弹一次的场景（本框架默认）
page.onceDialog(Dialog::accept);
```

**⚠️ 时序陷阱：**

```java
// ✅ 正确：先注册，再触发
basePage.acceptAlert();          // 第1步：注册监听器
basePage.click("#delete-btn");   // 第2步：触发弹窗

// ❌ 错误：先触发，再注册 → Dialog 已被 Playwright 自动 dismiss
basePage.click("#delete-btn");   // 弹窗弹出 → 被 Playwright 默认处理 → dismiss
basePage.acceptAlert();          // 注册监听器 → 没人触发 → 永远等不到
```

---

### 2.5 `page.route()` / `context.route()` — 网络拦截路由

| 维度 | 说明 |
|------|------|
| **注册位置** | `RouteEngine.registerRouteToPage()` / `registerRouteToContext()` |
| **注册时机** | 测试代码通过 `RouteDsl` 流式 DSL 触发 |
| **触发条件** | 页面发起任意 HTTP 请求，URL 匹配注册的 pattern |
| **生命周期** | 手动取消（`context.unroute(pattern)`）或 Context 关闭 |
| **用途** | Monitor（监控）/ Mock（模拟）/ Modify（修改）/ Delay（延迟） |

```java
// RouteEngine.java:207-208 — Page 级路由
page.route(pattern, route -> dispatchRoute(route, rule));

// RouteEngine.java:253-254 — Context 级路由
context.route(pattern, route -> dispatchRoute(route, rule));
```

**路由 vs Request/Response 监听器的重要区别：**

| 能力 | `route()` | `onRequest()` / `onResponse()` |
|------|-----------|-------------------------------|
| **拦截并修改请求** | ✅ 可以 fulfill/abort/modify | ❌ 只读，无法修改 |
| **拦截并修改响应** | ✅ 可以替换响应体 | ❌ 只读 |
| **延迟请求** | ✅ `route.wait(ms)` | ❌ 不支持 |
| **性能开销** | 低（CDP 层面精准拦截） | 低 |
| **互斥性** | ⚠️ 与 onRequest/onResponse 互斥 | ⚠️ 与 route 互斥 |

**⚠️ 重要：`route()` 和 `onRequest()`/`onResponse()` 在 Playwright 中互斥！**
同一 Page/Context 上注册 `route()` 后会覆盖 `onRequest()`/`onResponse()` 的行为，
反之亦然。本框架默认使用 `route()`，因此 **未启用** `onRequest()` / `onResponse()`。

---

### 2.6 RouteDsl 自定义 `onResponse` 回调 — Monitor 模式回调

| 维度 | 说明 |
|------|------|
| **注册位置** | 测试代码中通过 `RouteDsl.monitor().onResponse(callback)` |
| **注册时机** | 测试运行时 |
| **触发条件** | route 拦截到匹配请求 → MonitorHandler 处理 → 断言通过后 |
| **生命周期** | 跟随 MockRule，测试结束清理 |
| **用途** | 业务侧自定义 API 响应处理逻辑 |

```java
// 测试代码示例
RouteDsl.on(page)
    .api("/api/users/**")
    .monitor()
    .expectStatus(200)
    .expectHeader("content-type", "application/json")
    .onResponse((url, status, body, headers, method) -> {
        // 自定义处理：校验响应体字段
        JSONObject json = new JSONObject(body);
        assertTrue(json.has("userId"));
        assertFalse(body.contains("ERROR"));
    });
```

**回调接口定义：**

```java
// MonitorCallback.java
@FunctionalInterface
public interface MonitorCallback {
    void onResponse(String url, int status, String body,
                    Map<String, String> responseHeaders, String method);
}
```

---

## 三、本框架未使用但重要的 Playwright 原生监听器

以下监听器在本框架中 **未直接使用**，但在特定场景下有重要价值：

### 3.1 `page.onConsole()` — 捕获浏览器控制台消息

```java
// 用于 CI 环境自动收集 JS 错误日志
page.onConsole(msg -> {
    if ("error".equals(msg.type())) {
        logger.error("[Browser Console ERROR] {}", msg.text());
    } else if ("warning".equals(msg.type())) {
        logger.warn("[Browser Console WARN] {}", msg.text());
    }
    // msg.type() 可能的值：log, debug, info, error, warning, dir, table, trace, clear, ...
});
```

**适用场景：** CI 中自动收集前端 JS 异常，避免人眼查看 DevTools。

---

### 3.2 `page.onPageError()` — 捕获页面未处理异常

```java
// 捕获 window.onerror 未处理的 JS 异常
page.onPageError(err -> {
    logger.error("[Page Unhandled Error] {}", err.message());
    // 可以截图保存现场
    page.screenshot(new Page.ScreenshotOptions()
        .setPath(Paths.get("target/errors/page-error-" + System.currentTimeMillis() + ".png")));
});
```

**适用场景：** 检测前端代码的未捕获异常，配合截图保存崩溃现场。

---

### 3.3 `page.onRequestFailed()` — 捕获网络请求失败

```java
// 监控所有失败的网络请求（4xx/5xx/network error）
page.onRequestFailed(request -> {
    logger.warn("[Request Failed] {} - {}", request.url(), request.failure());

    // request.failure() 返回 null 或失败原因文本
    // 例如："net::ERR_CONNECTION_REFUSED", "net::ERR_NAME_NOT_RESOLVED"
});
```

**适用场景：** 自动检测 404/500/网络超时，减少排查成本。

**⚠️ 限制：** 与 `page.route()` 互斥。如果同时注册了 route，`onRequestFailed` 不会触发。

---

### 3.4 `page.onRequestFinished()` — 捕获请求完成

```java
// 监控所有完成的请求
page.onRequestFinished(request -> {
    // 获取响应信息（注意：响应体不可直接获取，这是 CDP 限制）
    logger.debug("[Request Finished] {} {} — {}ms",
        request.method(), request.url(),
        request.timing().responseEnd() - request.timing().requestStart());
});
```

**适用场景：** 性能分析、网络耗时统计。

**⚠️ 限制：** 与 `page.route()` 互斥。

---

### 3.5 `page.onFileChooser()` — 文件选择器处理

```java
// 当页面触发 <input type="file"> 时自动设置文件
page.onFileChooser(fileChooser -> {
    fileChooser.setFiles(
        Paths.get("/path/to/file1.pdf"),
        Paths.get("/path/to/file2.pdf")
    );
});
// 然后点击"上传"按钮即可
page.click("#upload-btn");
```

**适用场景：** 文件上传场景的另一种处理方式（vs `locator.setInputFiles()`）。

---

### 3.6 `page.onPopup()` — 弹窗处理

```java
// popup 和 onPage 是同一个东西，onPopup 是 Page 级别的版本
Page popup = page.waitForPopup(() -> {
    page.click("#link-that-opens-popup");
});
popup.waitForLoadState();
```

**适用场景：** 等价于 `context.waitForPage()`，但语义上更直观。

---

### 3.7 `browser.onDisconnected()` — 浏览器断开检测

```java
// 监听浏览器进程退出（crash / OOM / 手动关闭）
browser.onDisconnected(b -> {
    logger.error("Browser disconnected unexpectedly! Forcing cleanup...");
    // 可以在此触发 fail-safe 清理逻辑
});
```

**适用场景：** 长时间运行测试时检测浏览器进程崩溃。

---

## 四、常见问题

### Q1: `route()` 和 `onRequest()`/`onResponse()` 为什么互斥？

Playwright 内部使用 CDP 的 `Network.setRequestInterception()` 实现 `route()`，而 `onRequest()`/`onResponse()` 使用 `Network.requestWillBeSent` / `Network.responseReceived` 事件。当 route 启用后，CDP 会切换为拦截模式，不再触发标准的 request/response 事件。

**结论：** 本框架选择了 `route()`（功能更强），因此不建议再注册 `onRequest()`/`onResponse()`。

### Q2: `onceDialog()` 触发后还能再次弹出 Dialog 吗？

可以。`onceDialog()` 只会处理**下一个** Dialog。如果业务代码中有多个 Dialog 连续弹出：

```java
// 每个 Dialog 都需要独立的 onceDialog
basePage.acceptAlert();    // 处理第1个 alert
basePage.click("#btn1");   // 触发第1个 alert → accept

basePage.acceptAlert();    // 处理第2个 alert
basePage.click("#btn2");   // 触发第2个 alert → accept
```

或者使用可持续监听的 `onDialog()`：

```java
// 一次性注册，处理所有后续 Dialog
page.onDialog(Dialog::accept);
page.click("#btn1");  // dialog 1 → accept
page.click("#btn2");  // dialog 2 → accept
page.click("#btn3");  // dialog 3 → accept
```

### Q3: 监听器会影响性能吗？

Playwright 原生监听器基于 CDP 事件，性能开销极小（微秒级）。**唯一有性能影响的场景是注册了大量 route 规则**，因为每个网络请求都需要逐一匹配 URL pattern。

### Q4: 如何验证监听器是否成功注册？

```java
// 方法1：通过日志确认（框架已内置日志）
// createContext() 后日志输出 "New page detected via window.open()"
// createPage() 后下载事件触发时输出 "Download completed: xxx -> xxx"

// 方法2：通过 listenerCount 检查（Playwright Java API 不直接暴露此方法）
// 但可以通过触发对应事件来间接验证
```

---

## 五、速查表

| 监听器 | 对象 | 类型 | 注册位置 | 用途 |
|--------|------|------|---------|------|
| `context.onPage()` | BrowserContext | `on`(持续) | `PlaywrightContextManager.createContext()` | 监听 window.open，日志记录 |
| `page.onLoad()` | Page | `on`(持续) | 嵌套在 `context.onPage()` 中 | 新页面加载完成日志 |
| `page.onDownload()` | Page | `on`(持续) | `PlaywrightContextManager.createPage()` | 自动保存下载文件 |
| `page.onceDialog(Dialog::accept)` | Page | `once`(一次性) | `BasePage.acceptAlert()` | 自动接受弹窗 |
| `page.onceDialog(Dialog::dismiss)` | Page | `once`(一次性) | `BasePage.dismissAlert()` | 自动关闭弹窗 |
| `page.route(pattern, handler)` | Page | `on`(持续) | `RouteEngine.registerRouteToPage()` | Page 级网络拦截 |
| `context.route(pattern, handler)` | BrowserContext | `on`(持续) | `RouteEngine.registerRouteToContext()` | Context 级网络拦截 |
| `MonitorCallback.onResponse()` | 自定义接口 | 回调 | `RouteDsl.onResponse()` 注册 | 监控模式自定义处理 |

---

> 📖 **相关文档：**
> - [PLAYWRIGHT_VS_SELENIUM.md](./PLAYWRIGHT_VS_SELENIUM.md) — Playwright vs Selenium 全面行为差异
> - [README.md](./README.md) — 框架总览与快速开始
> - [ROUTE_PACKAGE_README.md](./ROUTE_PACKAGE_README.md) — Route Engine 详细使用指南
