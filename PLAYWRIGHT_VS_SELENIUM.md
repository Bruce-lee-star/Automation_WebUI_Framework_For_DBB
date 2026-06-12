# Playwright vs Selenium — 全面行为差异与迁移指南

> **基于本框架的实际实现经验**
>
> 📖 **互补阅读**：[README.md § Playwright vs Selenium — 关键行为差异与迁移指南](./README.md#playwright-vs-selenium--关键行为差异与迁移指南)
> — 侧重 API 层面的直接对比和代码示例。

---

> **核心区别**：Selenium 是**同步指令式**模型（send command → wait for response），Playwright 是**事件驱动式**模型（register listener → event triggers handler）。这种范式差异导致许多操作的**时序要求完全不同**，从 Selenium 迁移时最容易踩坑。

---

## 一、技术架构对比

| 维度 | Playwright | Selenium |
|------|-----------|----------|
| **语言绑定** | 原生多语言（Java/Python/JS/Go/C#） | WebDriver 协议（各语言独立实现） |
| **浏览器控制** | Chrome DevTools Protocol (CDP) | WebDriver JSON Wire Protocol |
| **通信方式** | WebSocket 持久连接，事件实时推送 | HTTP 请求-响应，轮询查询 |
| **执行速度** | ⚡ 快 30-50%（直接 CDP，无中间协议层） | 较慢（协议转换开销） |
| **稳定性** | ⚡ 高（自动等待机制，原生 DOM 快照） | 需手动 `Thread.sleep()` 或显式等待 |
| **网络拦截** | ⚡ 强大（原生 `route`/`onResponse` API） | ❌ 弱（依赖第三方库如 BrowserMob-Proxy） |
| **Mock 能力** | ⚡ 强大（`route.fulfill()`/`route.abort()`/`route.wait()`） | ❌ 需额外工具（Selenium 本身不支持） |
| **并行测试** | ⚡ 原生支持 `browserType.launch()` 并行 | 需配置 Grid 或云服务 |
| **拦截器性能** | ⚡ 零开销（CDP 层面拦截） | ❌ 有代理中间人开销（Proxy-based） |
| **编程范式** | **事件驱动**：先注册监听器，再执行动作 | **指令驱动**：先执行动作，再查询结果 |
| **元素定位** | Locator **延迟求值**，每次操作重新查询 DOM | By 选择器即时查找，需手动重定位 |

---

## 二、Playwright 工作原理

### 2.1 整体架构

Playwright 的架构可以理解成三层。最上层是你写的测试代码，中间是 Playwright 自带的驱动进程，最下层是真正的浏览器。

它和 Selenium 最大的架构区别在两点：一是它自带浏览器内核——Chromium、Firefox、WebKit 都内置在安装包里，不需要单独装浏览器驱动；二是它多了一个 Context 的概念，Context 是浏览器的独立会话单元，每个 Context 有自己的 Cookie、LocalStorage 和 Session，互相隔离。同一个浏览器进程里可以开多个 Context，这意味着模拟多个用户同时登录时，不需要像 Selenium 那样启动多个浏览器实例。

简单记：一个 Browser → 多个 Context → 每个 Context 下有多个 Page（标签页）。

```
┌─────────────────────────────────────────────────────────────────┐
│                   你的测试代码（Java/Python/JS...）               │
│  ┌─────────────────────────────────────────────────────────┐    │
│  │                 Driver（Playwright 自带的驱动进程）        │    │
│  │  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐      │    │
│  │  │ Chromium    │  │ Firefox     │  │ WebKit      │      │    │
│  │  └─────────────┘  └─────────────┘  └─────────────┘      │    │
│  └─────────────────────────────────────────────────────────┘    │
│                              ↕ WebSocket 长连接                  │
│  ┌─────────────────────────────────────────────────────────┐    │
│  │                  浏览器进程                               │    │
│  │  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐      │    │
│  │  │   浏览器    │  │   标签页    │  │  隔离会话   │      │    │
│  │  │  (主进程)   │  │  (N个Page)  │  │  (N个Context)│      │    │
│  │  └─────────────┘  └─────────────┘  └─────────────┘      │    │
│  └─────────────────────────────────────────────────────────┘    │
└─────────────────────────────────────────────────────────────────┘
```

| 组件 | 职责 |
|------|------|
| **Client** | 测试代码，调用 `page.click()` 等 API |
| **Driver** | 可执行程序，负责启动浏览器、建立通信。一套 Driver 兼容 Chromium / Firefox / WebKit |
| **Browser** | Chromium / Firefox / WebKit 实例 |
| **Context** | 独立会话单元，Cookie / LocalStorage / Session 完全隔离，同一进程可并存多个 |
| **Page** | 浏览器标签页，一个 Context 下可开多个 |

---

### 2.2 通信方式：CDP 直连 vs WebDriver 中转

Selenium 和 Playwright 跟浏览器说话的方式完全不同。

Selenium 走的是 WebDriver 协议，简单说就是中间多了一层翻译——你的代码发指令给 WebDriver 服务，WebDriver 再转发给浏览器，浏览器执行完再原路返回。而且每次交互都是 HTTP 请求-响应，一问一答。

Playwright 用的是 CDP——Chrome DevTools Protocol。这个协议就是 Chrome F12 开发者工具和浏览器之间聊天用的那套东西。Playwright 直接走这个协议跟浏览器通信，没有中间层，而且是 WebSocket 长连接，一直连着不断开。

这样做有三个好处。第一，速度快——因为绕过了 WebDriver 那一层协议转换。第二，能力强——开发者工具能干的事它都能干，比如网络拦截、Service Worker 控制、性能追踪。第三，实时——长连接意味着浏览器那边发生的事能立刻推过来，不需要反复轮询。

举一个具体例子：当你写 `page.click("#button")`，Playwright 内部实际发送的是 CDP 的 `Input.dispatchMouseEvent` 命令，直接告诉浏览器在哪个坐标按下鼠标。

```java
{
  "id": 12,
  "method": "Input.dispatchMouseEvent",
  "params": {
    "type": "mousePressed",
    "x": 100, "y": 200,
    "button": "left",
    "clickCount": 1
  }
}
```

---

### 2.3 网络拦截原理（路由机制）

Playwright 能拦截网络请求的核心在于：请求还没离开浏览器，就被它在 CDP 网络层拦住了。

具体流程是这样的：你先用 `context.route()` 注册一个 URL 匹配规则和一个回调函数，告诉 Playwright"遇到匹配的请求先别发出去"。然后当浏览器要发出这个请求时，CDP 把它暂停，通知 Playwright，Playwright 调用你的回调，你把请求的处理方式告诉 Playwright，Playwright 再通知浏览器照办。

你的回调有四种处理方式：`fulfill()` 直接返回假响应，请求根本不到服务器；`abort()` 直接中断；`resume()` 放行；`wait(ms)` 延迟指定毫秒后再放行，模拟慢网速。

对比 Selenium，它本身做不了网络拦截，必须借助 BrowserMob-Proxy 这类代理工具，相当于在浏览器和网络之间再加一层中转。开发调试都比较麻烦，而且有性能开销。

```
浏览器 ──请求──▶ │ Playwright 拦截点 │
                   │    (CDP 网络层)   │
                   │                  │
                   │ ① fulfill() ───▶ 返回Mock（不发到服务器）
                   │ ② abort()   ───▶ 拒绝请求
                   │ ③ resume()  ───▶ 放行到服务器
                   │ ④ wait(ms)  ───▶ 延迟后放行
```

```java
context.route("**/api/users", route -> {
    route.fulfill(new Route.FulfillOptions()
        .setStatus(200)
        .setBody("{\"name\":\"test\"}"));
});
// 底层：CDP Network.setRequestInterception() → 匹配请求暂停 → 调回调 → fulfill通知浏览器完成
```

---

### 2.4 Context 与 Page 生命周期

Context 是 Playwright 独有的一个概念，简单理解就是一个隔离的浏览器会话。每个 Context 有自己独立的 Cookie、LocalStorage 和 Session，互相完全不通。

这带来一个直接的好处：如果你想模拟用户 A 和用户 B 同时登录，在 Selenium 里需要开两个浏览器实例，但在 Playwright 里，你只需要在同一个浏览器下建两个 Context 就行，共享一个浏览器进程，零额外开销。

Context 还有一个重要的使用场景叫"重建"。比如登录后 Cookie 存在了 Context 里，当你需要切换用户或者清空缓存时，最干净的做法不是一条条删 Cookie，而是直接销毁旧 Context、建一个新的。本框架的 SessionManager 就是利用这个机制做登录态复用的——先检查有没有未过期的 session 文件，有就直接加载到新 Context，没有就走完整登录流程再保存。

```
Browser.launch() → 创建Context A（独立Cookie/Session）
                    ├── Page 1
                    ├── Page 2
                    └── Page 3

需要重建时：保存当前状态 → 关闭旧Context → 创建新Context
```

---

### 2.5 自动等待机制

用过 Selenium 的人应该都遇到过：页面还没加载完，代码就去查元素，然后报 NoSuchElementException。所以你必须自己加 Thread.sleep 或者 WebDriverWait。这是 Selenium 测试不稳定的一个主要来源。

Playwright 解决这个问题的方式是：在每次操作之前，自动检查元素是否真的准备好了。比如你调用 `page.click("#btn")`，Playwright 不会立刻去点，而是先反复验证五个条件：

1. Attached——元素在 DOM 里了吗？
2. Visible——元素可见吗？不是 display:none 或 hidden 吧？
3. Stable——元素在做动画吗？动画中定位会偏移
4. Enabled——元素有没有被 disabled？有没有被其他元素挡住？
5. Actionable——能接收点击事件吗？

这五个条件全部满足，它才真正去点击。而且不是只检查一次，是在超时时间内持续重试，直到全部满足。这样一来，绝大部分的等待逻辑你根本不用写，Playwright 替你做了。这直接减少了因等待不充分导致的 flaky test。

---

### 2.6 编程范式差异：事件驱动 vs 指令驱动

这是 Playwright 和 Selenium 之间最根本的差异，理解了它，其他所有行为差异都很好理解。

Selenium 是指令驱动模型——你每次想知道什么，必须主动发请求去问。比如 `getWindowHandles()`，发一个 HTTP 请求，浏览器回复当前有几个窗口。问题是：如果新窗口在你的两次查询之间打开了，你这次就漏掉了，只能下次再问。

Playwright 是事件驱动模型——你先告诉浏览器你关心什么事件，事件发生时浏览器主动通知你。比如用 `waitForPage()`，先注册一个 CDP 事件监听器，然后执行点击操作，浏览器创建新标签页的瞬间就把事件推给 Playwright，不会漏。

这个差异具体影响四个方面：

**第一，Alert 和 Dialog 处理。** Selenium 是事后处理，弹窗已经出来了再调 `switchTo().alert().accept()`。Playwright 必须在触发弹窗之前注册监听器，如果你没注册，Playwright 默认会把弹窗自动 dismiss 掉，你就拿不到了。

**第二，新标签页/新窗口的处理。** Selenium 用 `getWindowHandles()` 主动查列表，轮询等新窗口出现。Playwright 用 `waitForPage()` 等事件推送，新窗口创建瞬间就知道。

**第三，引用失效问题。** Selenium 里你拿到一个 WebElement 或 Frame 引用，只要页面不关就能一直用。Playwright 里页面一导航（跳转、刷新），旧的 Frame 引用就变成 detached 状态，必须重新获取。我们在框架里用 ThreadLocal 管 Frame，导航后自动重置，对业务代码透明。

**第四，等待逻辑。** Selenium 要自己写 WebDriverWait。Playwright 的 click、type 内置自动等待，不用额外写。

---

## 三、关键行为差异：时序陷阱与迁移要点

> ⚠️ 本章节聚焦从 Selenium 迁移到 Playwright 时最容易出错的行为差异场景。

---

### 3.1 Alert / Dialog 处理（⚠️ 高时序风险）

| 维度 | Selenium | Playwright |
|------|----------|------------|
| **触发方式** | `driver.switchTo().alert().accept()` — **事后处理** | `page.onceDialog(Dialog::accept)` — **必须事前注册** |
| **时序要求** | 可以在 alert 弹出来后随时随地调用 | **必须在触发 alert 的代码之前注册监听器** |
| **fail 模式** | 不注册也可以，到需要时再处理 | 没事前注册 → Dialog 被 Playwright 默认自动 dismiss → 拿不到 dialog |

**根本原因**：Playwright 在启动时默认注册了 Dialog 自动 dismiss 行为。如果用户代码未在弹框前覆盖此行为，弹框会在弹出的瞬间被 Playwright 自动关闭。

**Playwright 正确写法：**

```java
// ✅ 正确：先注册监听器，再触发 alert
page.onceDialog(Dialog::accept);          // 第1步：事前注册
page.click("#delete-btn");                // 第2步：触发 alert → Playwright 自动调用 accept()

// ✅ 获取 dialog 信息
page.onceDialog(dialog -> {
    logger.info("Dialog message: {}", dialog.message());
    dialog.accept("input text");           // prompt 输入
});

// ❌ 迁移陷阱：Selenium 思维 — 先点击再处理
page.click("#delete-btn");               // alert 弹出来了！
page.onceDialog(Dialog::accept);         // 太晚了！已自动 dismiss
```

**框架封装（面向 Selenium 用户习惯做了适配）：**

```java
// BasePage 封装了 before-click 注册模式
basePage.acceptAlert();       // 等价于 page.onceDialog(Dialog::accept);
basePage.dismissAlert();      // 等价于 page.onceDialog(Dialog::dismiss);

// 使用方式 — 先调用 acceptAlert()，再点击触发按钮
basePage.acceptAlert();
basePage.click("#delete-btn");
```

---

### 3.2 新页面 / 新 Tab 处理（⚠️ 高时序风险）

| 维度 | Selenium | Playwright |
|------|----------|------------|
| **获取窗口** | `driver.getWindowHandles()` — **事后查询**，随时获取句柄列表 | `context.waitForPage(action)` — **事前监听**，在 CDP 事件级捕获 |
| **切换方式** | `driver.switchTo().window(handle)` — 按句柄切换 | `switchToPage(index)` / `switchToPage(page)` / `switchToNewPage(trigger)` |
| **时序问题** | 通过 `getWindowHandles().size()` 轮询，可能与页面打开时机产生竞态 | `context.waitForPage()` 在 CDP 层面拦截新页面事件，无误判 |

**Playwright 正确写法：**

```java
// ✅ 方式1：原子操作 — 点击 + 等待新页面（推荐）
Page newPage = basePage.switchToNewPage(
    () -> basePage.click("#link-that-opens-new-tab"), 15);

// ✅ 方式2：手动 waitForPopup + switchToPage
Page popup = page.waitForPopup(() -> page.click("#link"));
basePage.switchToPage(popup);

// ✅ 方式3：已触发新 tab，只需等待 + 切换
basePage.switchToNewPage(15);  // 快速路径检查 → 事件监听兜底

// ❌ 迁移陷阱 — Selenium 思维：先点击，再 getWindowHandles 轮询
page.click("#link");                          // 新 tab 已打开
for (int i = 0; i < 10; i++) {               // 轮询等待
    if (context.pages().size() > 1) break;
    page.waitForTimeout(500);
}
// ⚠️ 问题：context.pages() 获取到的是点击前的 page 列表快照，不包括新页面
```

**CDP vs WebDriver 的事件捕获差异：**

```
Selenium (WebDriver 协议 — 主动轮询):
  getWindowHandles()
  └── HTTP POST /wd/hub/session/{id}/window/handles
      └── Browser 查询当前窗口列表
          └── 返回 ["CDwindow-1", "CDwindow-2"]

  竞态条件：新窗口在 HTTP 请求发出后、响应返回前打开 → 本次查询丢失

Playwright (CDP 协议 — 被动接收事件推送):
  context.waitForPage(action)
  └── 注册 CDP 事件监听器 Target.attachedToTarget
      └── 执行 action（如点击链接）
          └── Browser 创建新 Target → 立即推送事件 → Playwright 收到通知
  └── 返回新 Page 对象

  无竞态：事件在网络底层被同步拦截，不会丢失
```

---

### 3.3 iframe 切换

| 维度 | Selenium | Playwright |
|------|----------|------------|
| **切换方式** | `driver.switchTo().frame(index/name/WebElement)` | `page.frame(name)` / `switchToFrame(nameOrSelector)` / `switchToFrame(index)` |
| **导航后** | 自动回到 defaultContent（取决于实现） | **Frame 对象会变为 detached**，必须重置 |
| **回主页面** | `driver.switchTo().defaultContent()` | `switchToDefaultContent()` |

**Playwright 注意事项：**

```java
// ✅ 进入 iframe
basePage.switchToFrame("myFrame");         // name/id
basePage.switchToFrame("iframe.embedded"); // CSS selector
basePage.switchToFrame(1);                 // index

// 在 iframe 内操作 — locator() 自动适配当前 Frame
basePage.click("#button-inside-iframe");
basePage.type("#input-inside-iframe", "text");

// ✅ 退出 iframe
basePage.switchToDefaultContent();

// ⚠️ 页面导航后 Frame 自动 detached！
// 框架通过 ThreadLocal<Frame> + 导航后自动重置，对业务代码透明
```

**与 Selenium 的关键差异：**
- Playwright 的 `Frame` 对象是**引用**，页面导航后变为 detached 状态，需要重新获取
- 本框架通过 `ThreadLocal<Frame>` 存储当前 iframe 上下文，并在 `navigateTo/refresh/back/forward` 后自动重置
- 这意味着**跨导航的 iframe 操作**需要重新 `switchToFrame()`

---

### 3.4 下载文件处理

| 维度 | Selenium | Playwright |
|------|----------|------------|
| **下载方式** | 配置浏览器 Profile 禁止下载弹窗，设置默认下载目录 | `page.onDownload()` **事件监听** |
| **时序** | 通过轮询文件系统检测下载完成（不可靠） | Playwright 通过 CDP 事件通知下载完成 |
| **触发方式** | 配置一次，全局生效 | 每个 Page 实例创建时注册一次 |

**Playwright 自动下载（框架内置）：**

```java
// 框架在 createPage 时自动注册 onDownload 监听
// 下载文件自动保存到 playwright.browser.downloadsPath 目录（默认 target/downloads）

// 点击下载按钮
basePage.click("#download-btn");
// 文件自动保存，日志输出：Download completed: report.xlsx -> target/downloads/report.xlsx
```

**vs Selenium 的方式：**

```java
// Selenium：需配置 ChromeOptions + 轮询文件系统
ChromeOptions options = new ChromeOptions();
options.setExperimentalOption("prefs", Map.of(
    "download.default_directory", "/path/to/downloads",
    "download.prompt_for_download", false,
    "download.directory_upgrade", true
));

// 还需手动轮询检测文件是否下载完成
File downloadedFile = new File("/path/to/downloads/myfile.xlsx");
for (int i = 0; i < 30; i++) {
    if (downloadedFile.exists() && !downloadedFile.getName().endsWith(".crdownload")) {
        break;
    }
    Thread.sleep(1000);
}
```

---

### 3.5 文件上传

| 维度 | Selenium | Playwright |
|------|----------|------------|
| **方式** | `element.sendKeys(filePath)` — 模拟键盘输入路径 | `locator.setInputFiles(paths)` — **直接调用 CDP 设置文件** |
| **行为** | 依赖浏览器原生文件选择对话框 | 绕过对话框，直接注入文件 |

```java
// Selenium
driver.findElement(By.id("upload")).sendKeys("/path/to/file.pdf");

// Playwright — 框架封装
basePage.setInputFiles("#upload", "/path/to/file.pdf");
// 支持多文件
basePage.setInputFiles("#upload", "/path/to/file1.pdf", "/path/to/file2.pdf");
```

---

### 3.6 全页截图（Full Page Screenshot）

| 维度 | Selenium | Playwright |
|------|----------|------------|
| **全页截图** | 需第三方库（aShot、Selenium 4 有 `getFullPageScreenshotAs()` 但不稳定） | **原生支持** `setFullPage(true)` |
| **懒加载** | 需手动滚动触底触发懒加载 | 框架内置截图前稳定化：滚动触底 → 等待渲染 → 滚回顶部 |

```java
// Playwright 全页截图（原生）
page.screenshot(new Page.ScreenshotOptions()
    .setFullPage(true)              // 自动滚动拼接
    .setAnimations(ScreenshotAnimations.DISABLED));
```

---

### 3.7 资源生命周期管理

| 维度 | Selenium | Playwright |
|------|----------|------------|
| **资源层级** | WebDriver → Window/Tab | Playwright → Browser → BrowserContext → Page |
| **关闭顺序** | `driver.quit()` 一步关闭 | **必须按层级关闭**：Page → Context → Browser → Playwright |
| **残留风险** | `driver.quit()` 超时/异常时可能残留浏览器进程 | JVM Shutdown Hook 兜底，确保进程被杀死 |

**Playwright 关闭顺序（不可颠倒！）：**

```java
// ✅ 正确顺序
page.close();                    // 1. 关闭页面
context.close();                 // 2. 关闭上下文
browser.close();                 // 3. 关闭浏览器
playwright.close();              // 4. 关闭 Playwright

// ❌ 错误：跨层关闭会导致异常
browser.close();                 // Context/Page 还在引用 Browser → 异常
```

**框架自动管理：**
- `FrameworkCore` 注册 JVM Shutdown Hook → `cleanupAll()` 按正确顺序关闭
- `cleanupForScenario()` / `cleanupForFeature()` 正确管理 Scenario/Feature 边界

---

### 3.8 键盘操作

| 维度 | Selenium | Playwright |
|------|----------|------------|
| **全局按键** | `Actions().keyDown()` 链式调用 | `page.keyboard().down(key)` + `page.keyboard().up(key)` |
| **元素级按键** | `element.sendKeys(Keys.ENTER)` | `locator.press("Enter")` |
| **逐字输入** | `sendKeys("text")` | `locator.pressSequentially("text")` — **逐个字符按下**，模拟真实用户 |

```java
// Selenium — Ctrl+A 全选
element.sendKeys(Keys.chord(Keys.CONTROL, "a"));

// Playwright — 等价操作
basePage.click("#input");              // 聚焦
basePage.getPage().keyboard().press("Control+a");  // Ctrl+A 全选

// 逐字输入（模拟真实用户输入，更接近人类行为）
basePage.element("#input").type("hello");  // 底层: pressSequentially("hello")
```

---

### 3.9 BrowserContext 隔离模型

| 维度 | Selenium | Playwright |
|------|----------|------------|
| **隔离级别** | 每个 `WebDriver` 实例一个浏览器窗口，Cookie 共享 | `BrowserContext` 完全隔离 Cookie/LocalStorage/Session |
| **多用户登录** | 需要启动多个浏览器实例 | 同一 Browser 创建多个 Context，**零开销** |
| **会话管理** | 需手动管理 Cookie 文件 | `context.storageState()` 保存 / `setStorageStatePath()` 恢复 |

```java
// Selenium 思维：两个登录用户需要两个 Driver → 两个浏览器窗口
WebDriver driver1 = new ChromeDriver();  // 窗口 1，进程 1
WebDriver driver2 = new ChromeDriver();  // 窗口 2，进程 2

// Playwright 方式：两个 Context → 共享一个浏览器进程
BrowserContext ctx1 = browser.newContext();  // 用户 A 的隔离会话
BrowserContext ctx2 = browser.newContext();  // 用户 B 的隔离会话
// ctx1 和 ctx2 的 Cookie/Storage 完全隔离，属于同一浏览器进程
```

**本框架的 Session 复用机制（区分缓存命中/未命中）：**

| 场景 | 行为 | 说明 |
|------|------|------|
| **缓存命中** | `setStorageStatePath(sessionPath)` → Context 创建时预载 Cookie/LocalStorage → **跳过登录**直达 homeUrl | session `.json` + `.meta` 文件存在且未过期（≤60min） |
| **缓存未命中** | `restoreSession()` 返回 false → 业务层执行**完整登录**流程 → 登录后 `saveSession()` 持久化 → 后续命中 | 文件缺失或过期，自动清理过期文件后等待登录 |

- **Scenario 模式** — 缓存命中时：新 Context 直接加载持久化 Cookie；未命中时：执行登录后自动保存
- **Feature 模式** — 同一 Feature 内首个 Scenario 恢复后，后续通过 `markFeatureSessionRestored()` 跳过重复恢复

---

### 3.10 行为差异总结速查表

| 场景 | Selenium 方式 | Playwright 方式 | 时序风险 | 本框架适配 |
|------|--------------|----------------|---------|-----------|
| **Alert** | 事后 `switchTo().alert().accept()` | 事前 `onceDialog(Dialog::accept)` | ⚠️ **高** — 必须先注册再触发 | `acceptAlert()` / `dismissAlert()` 事前调 |
| **新 Tab** | 事后 `getWindowHandles()` 轮询 | 事前 `waitForPage(action)` | ⚠️ **高** — 必须事件级 CDP 捕获 | `switchToNewPage(action, timeout)` |
| **iframe** | `switchTo().frame()` | `page.frame()` + ThreadLocal 管理 | ⚠️ **中** — 导航后 Frame detached | `switchToFrame()` + 导航后自动重置 |
| **点击** | 手动 `WebDriverWait` | 自动等待 5 个阶段 | ✅ 低 — Playwright 更可靠 | 无需适配，直接使用 |
| **下载** | 配置 Profile + 轮询文件系统 | `onDownload()` 事件 | ✅ 低 — 框架自动处理 | `createPage` 时自动注册监听 |
| **隔离** | 多 Driver 实例 | BrowserContext | ✅ 低 — Context 零开销 | `SessionManager` 透明管理 |
| **全页截图** | aShot 第三方库 | 原生 `setFullPage(true)` | ✅ 低 | 框架内置稳定化处理 |
| **网络拦截** | BrowserMob-Proxy | 原生 `route()` API | ✅ 低 — CDP 层面 | Route Engine 流式 DSL |
| **文件上传** | `sendKeys(path)` | `setInputFiles(paths)` | ✅ 低 | `setInputFiles()` 多文件支持 |
| **资源关闭** | `driver.quit()` | Page → Context → Browser → Playwright | ⚠️ **中** — 顺序不可颠倒 | Shutdown Hook 自动按序关闭 |
| **键盘操作** | `sendKeys()` 一次性输入 | `pressSequentially()` 逐字输入 | ✅ 低 | `type()` 底层自动适配 |

---

## 五、API Mock 能力对比

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

## 六、网络拦截对比

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

## 七、核心优势总结

**选择 Playwright 的关键理由：**

1. **零依赖 Mock** - 无需 BrowserMob-Proxy 等第三方工具，直接使用原生 API
2. **路由安全机制** - `applyRoutes()` 强制清理 + 双重异常兜底，避免白屏
3. **原生延迟支持** - `route.wait()` 在回调内同步执行，无异步线程风险
4. **高性能拦截** - CDP 层面拦截，无代理中间人开销
5. **自动等待机制** - Playwright 自动等待元素可点击/可见，减少 flaky tests
6. **多浏览器一致** - Chromium/Firefox/WebKit 使用统一 API

---

## 八、本框架的 Playwright 路由安全机制

### 8.1 路由重复注册防护

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

### 8.2 双重异常兜底

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

## 九、Selenium 的替代方案及局限性

如果必须使用 Selenium，网络拦截需要依赖以下工具：

| 工具 | 优点 | 缺点 |
|------|------|------|
| **BrowserMob-Proxy** | 功能丰富，支持请求/响应修改 | 需要额外进程，配置复杂，性能开销 |
| **Zap Proxy** | OWASP 项目，功能全面 | 学习曲线陡峭，集成复杂 |
| **Selenium Wire** | 纯 Java 实现 | 可能与新版 Selenium 冲突 |

> **结论**：对于需要强大 API Mock 和监控能力的测试框架，Playwright 是更优选择。
