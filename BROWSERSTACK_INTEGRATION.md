# BrowserStack 云测试集成指南（企业级）

## 概述

本框架已集成 **BrowserStack** 企业级云测试平台，通过 **CDP (Chrome DevTools Protocol)** 远程连接模式工作，支持在云端执行自动化测试，无需本地安装多种浏览器。

### 架构设计

```
┌─────────────────────────────────────────────────────────────────┐
│                        测试执行流程                              │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  Jenkins Pipeline                                                │
│       │                                                          │
│       ▼                                                          │
│  PlaywrightManager.initialize()                                  │
│       │                                                          │
│       ├──► isBrowserStackEnabled()  ◄── 检查配置开关              │
│       │                                                          │
│       ▼                                                          │
│  BrowserStackManager.connect(playwright)                        │
│       │                                                          │
│       ├──► buildCdpUrl()          ◄── 构建 WSS 连接 URL           │
│       ├──► buildAuthHeader()      ◄── Basic Auth 认证            │
│       │         │                                             │
│       │         ▼                                              │
│       │   playwright.chromium().connectOverCDP(cdpUrl, options) │
│       │                                                          │
│       ▼                                                          │
│  返回远程 Browser 实例                                            │
│       │                                                          │
│       ▼                                                          │
│  所有测试操作在 BrowserStack 云端浏览器执行                         │
│  （视频/截图/网络日志自动录制）                                    │
│       │                                                          │
│       ▼                                                          │
│  测试完成                                                         │
│       │                                                          │
│       ├──► setTestStatus("passed"/"failed", "reason")            │
│       │     └──► REST API PUT → api.browserstack.com             │
│       │                                                          │
│       └──► 结果标记到 BrowserStack Dashboard                     │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

**连接模式**：`本地 Playwright ──WSS/CDP──▶ cdp.browserstack.com ──▶ 远程浏览器实例`

---

## 功能特性

| 特性 | 状态 | 说明 |
|------|------|------|
| 跨浏览器测试 | ✅ | Chrome、Firefox、Safari、Edge |
| 跨平台测试 | ✅ | Windows、macOS、iOS、Android |
| CDP 远程连接 | ✅ | 通过 Chrome DevTools Protocol 连接 |
| 并行测试支持 | ✅ | 多会话并发执行 |
| 视频录制 | ✅ | 自动录制测试过程 |
| 网络日志 | ✅ | 捕获网络请求/响应 |
| 控制台日志 | ✅ | 捕获 JS Console 输出 |
| 截图捕获 | ✅ | 失败时自动截图 |
| Local Testing | ✅ | 支持内网应用测试 |
| 会话状态标记 | ✅ | REST API 标记 passed/failed |
| 凭证安全脱敏 | ✅ | 日志自动隐藏 AccessKey |

---

## 快速开始

### 1. 获取 BrowserStack 凭证

1. 访问 [BrowserStack](https://www.browserstack.com/)
2. 注册企业账号或登录
3. 获取用户名和访问密钥：
   - URL: https://www.browserstack.com/accounts/settings
   - **Username**: 你的登录用户名
   - **Access Key**: API 密钥（32位字符串）

### 2. 配置 BrowserStack

#### 方式 A：serenity.properties 配置（推荐）

```properties
# ==================== BrowserStack 云测试配置 ====================
browserstack.enabled=true
browserstack.username=your_username
browserstack.access.key=your_access_key_abc123

# 浏览器与操作系统
browserstack.browser.version=latest
browserstack.os=Windows
browserstack.os.version=11

# 会话配置
browserstack.session.name=Automation Test Session
browserstack.debug=false
browserstack.video=true
browserstack.network.logs=false

# 超时设置（秒）
browserstack.timeout=300
```

#### 方式 B：环境变量（安全推荐）

```bash
# Linux/macOS (export)
export BROWSERSTACK_ENABLED=true
export BROWSERSTACK_USERNAME="your_username"
export BROWSERSTACK_ACCESS_KEY="your_access_key"

# Windows PowerShell
$env:BROWSERSTACK_ENABLED="true"
$env:BROWSERSTACK_USERNAME="your_username"
$env:BROWSERSTACK_ACCESS_KEY="your_access_key"
```

#### 方式 C：Jenkins Pipeline

```groovy
environment {
    BROWSERSTACK_ENABLED = 'true'
    BROWSERSTACK_USERNAME = credentials('BROWSERSTACK_USERNAME')
    BROWSERSTACK_ACCESS_KEY = credentials('BROWSERSTACK_ACCESS_KEY')
}
```

> **⚠️ 安全提示**：生产环境强烈建议使用环境变量或 Jenkins Credentials 存储 AccessKey，
> 避免明文写入配置文件。框架日志中会自动对凭证进行脱敏处理。

### 3. 运行测试

```bash
# 标准构建（BrowserStack 配置为 enabled 时自动连接）
mvn clean verify

# 强制使用 BrowserStack（忽略配置文件）
mvn clean verify -DBROWSERSTACK_ENABLED=true

# 本地开发模式（禁用 BrowserStack）
mvn clean verify -DBROWSERSTACK_ENABLED=false
```

---

## 配置说明

### 必需配置

| 配置项 | 环境变量 | serenity.properties | 说明 | 示例值 |
|--------|---------|---------------------|------|--------|
| `enabled` | `BROWSERSTACK_ENABLED` | `browserstack.enabled` | 是否启用云测试 | `true` / `false` |
| `username` | `BROWSERSTACK_USERNAME` | `browserstack.username` | BrowserStack 用户名 | `"john_doe"` |
| `accessKey` | `BROWSERSTACK_ACCESS_KEY` | `browserstack.access.key` | BrowserStack 访问密钥 | `"xAz9pLm..."` |

### 可选配置

| 配置项 | 环境变量 | serenity.properties | 默认值 | 说明 |
|--------|---------|---------------------|--------|------|
| **操作系统** |||||
| `os` | — | `browserstack.os` | `"Windows"` | 操作系统名称 |
| `osVersion` | — | `browserstack.os.version` | `"11"` | 操作系统版本 |
| **浏览器** |||||
| `browserVersion` | — | `browserstack.browser.version` | `"latest"` | 浏览器版本 |
| **会话信息** |||||
| `sessionName` | — | `browserstack.session.name` | `"Test Session"` | Dashboard 显示的会话名称 |
| `projectName` | — | `serenity.project.name` | `"Automation Project"` | 项目名称（复用 Serenity 配置） |
| **功能开关** |||||
| `debug` | — | `browserstack.debug` | `"false"` | 启用调试模式（步骤级截图） |
| `video` | — | `browserstack.video` | `"true"` | 录制测试视频 |
| `networkLogs` | — | `browserstack.network.logs` | `"false"` | 捕获网络请求日志 |
| **高级** |||||
| `timeout` | — | `browserstack.timeout` | `"300"` | 连接超时时间（秒） |
| `local` | `BROWSERSTACK_LOCAL` | — | `false` | 是否启用 Local Testing |

### 配置优先级（从高到低）

```
1. 环境变量 (BROWSERSTACK_*)          ← 最高优先级
2. JVM 系统属性 (-D 参数)               ← 中等优先级
3. serenity.properties 配置文件        ← 默认值
4. FrameworkConfig 枚举默认值           ← 兜底
```

示例：如果同时设置了环境变量和配置文件，**环境变量生效**。

### FrameworkConfig 枚举对照表

`FrameworkConfig.java` 中定义了所有 BrowserStack 配置枚举：

| 枚举名 | 配置键 | 环境变量 | 默认值 | 说明 |
|--------|--------|---------|--------|------|
| `BROWSERSTACK_ENABLED` | `browserstack.enabled` | `BROWSERSTACK_ENABLED` | `false` | 是否启用云测试 |
| `BROWSERSTACK_USERNAME` | `browserstack.username` | `BROWSERSTACK_USERNAME` | `""` | BrowserStack 用户名 |
| `BROWSERSTACK_ACCESS_KEY` | `browserstack.accessKey` | `BROWSERSTACK_ACCESS_KEY` | `""` | BrowserStack 访问密钥 |
| `BROWSERSTACK_SESSION_NAME` | `browserstack.sessionName` | — | `""` | Dashboard 会话名称 |
| `BROWSERSTACK_OS` | `browserstack.os` | — | `"Windows"` | 操作系统名称 |
| `BROWSERSTACK_OS_VERSION` | `browserstack.osVersion` | — | `"11"` | 操作系统版本 |
| `BROWSERSTACK_BROWSER_VERSION` | `browserstack.browserVersion` | — | `"latest"` | 浏览器版本 |
| `BROWSERSTACK_TIMEOUT` | `browserstack.timeout` | — | `"300"` | 连接超时（秒） |
| `BROWSERSTACK_DEBUG` | `browserstack.debug` | — | `"true"` | 调试模式（步骤级截图） |
| `BROWSERSTACK_NETWORK_LOGS` | `browserstack.networkLogs` | — | `"true"` | 捕获网络请求日志 |
| `BROWSERSTACK_VIDEO` | `browserstack.video` | — | `"true"` | 录制测试视频 |

> **代码访问**：在 Java 中通过 `FrameworkConfigManager.getString(FrameworkConfig.BROWSERSTACK_ENABLED)` 读取配置。

---

## 常用操作系统和浏览器组合

### Windows + Chrome（最常用）

```properties
browserstack.os=Windows
browserstack.os.version=11
browserstack.browser.version=latest
```

### macOS + Safari

```properties
browserstack.os=OS X
browserstack.os.version=Ventura
browserstack.browser.version=16.0
playwright.browser.type=webkit
```

### iOS 移动端

```properties
browserstack.os=iOS
browserstack.os.version=17
browserstack.device=iPhone 15 Pro Max
playwright.browser.type=webkit
```

### Android 移动端

```properties
browserstack.os=Android
browserstack.os.version=14
browserstack.device=Samsung Galaxy S24 Ultra
playwright.browser.type=chromium
```

### 多浏览器矩阵测试

```bash
# Chrome
mvn verify -Dbrowserstack.os="Windows" -Dbrowserstack.browser.version="latest"

# Firefox
mvn verify -Dbrowserstack.os="Windows" -Dbrowserstack.browser.version="latest" \
          -Dplaywright.browser.type="firefox"

# Safari
mvn verify -Dbrowserstack.os="OS X" -Dbrowserstack.os.version="Ventura" \
          -Dplaywright.browser.type="webkit"
```

---

## 框架集成详解

### 自动触发机制

**无需修改测试代码！** 当 `browserstack.enabled=true` 时：

```java
// PlaywrightManager.java（框架内部自动调用）
public static void initialize() {
    Playwright playwright = Playwright.create();
    
    if (BrowserStackManager.isBrowserStackEnabled()) {
        // 自动创建 BrowserStack 远程连接
        browser = BrowserStackManager.connect(playwright);
    } else {
        // 使用本地浏览器
        browser = createLocalBrowser(playwright);
    }
}
```

### 手动控制（可选）

在 Step Definition 中可以手动调用：

```java
import com.hsbc.cmb.hk.dbb.automation.framework.web.cloud.BrowserStackManager;

// 获取当前会话 ID
String sessionId = BrowserStackManager.getCurrentSessionId();

// 获取 Dashboard URL（用于报告链接）
String dashboardUrl = BrowserStackManager.getCurrentSessionUrl();

// 设置自定义会话名称
System.setProperty("browserstack.session.name", scenario.getName());

// 手动标记测试状态
if (testFailed) {
    BrowserStackManager.setTestStatus("failed", "AssertionError: element not found");
} else {
    BrowserStackManager.setTestStatus("passed", "All steps completed");
}
```

### Hook 集成示例（Cucumber）

```java
import com.hsbc.cmb.hk.dbb.automation.framework.web.cloud.BrowserStackManager;
import io.cucumber.java.After;
import io.cucumber.java.Before;
import io.cucumber.java.Scenario;

public class BrowserStackHooks {

    @Before(order = 1)
    public void setupSession(Scenario scenario) {
        // 设置会话名称为场景名称
        System.setProperty("browserstack.session.name", 
            scenario.getName());
        
        // 记录会话 ID
        String sessionId = BrowserStackManager.getCurrentSessionId();
        if (sessionId != null) {
            scenario.log("[BrowserStack] Session: " + 
                BrowserStackManager.getCurrentSessionUrl());
        }
    }

    @After(order = 1)
    public void markTestStatus(Scenario scenario) {
        if (scenario.isFailed()) {
            BrowserStackManager.setTestStatus(
                "failed", 
                scenario.getName() + ": " + getFailureMessage(scenario)
            );
        } else {
            BrowserStackManager.setTestStatus(
                "passed", 
                "Scenario: " + scenario.getName()
            );
        }
    }
}
```

---

## 高级功能

### 1. Local Testing（内网应用测试）

当被测应用部署在内网或 localhost 时：

```bash
# 方式 1：环境变量
export BROWSERSTACK_LOCAL=true

# 方式 2：系统属性
mvn verify -DBROWSERSTACK_LOCAL=true
```

**工作原理**：
- 框架自动检测 `local=true` 配置
- 在 CDP URL 中添加 `local=true&localIdentifier=<timestamp>` 参数
- BrowserStack 建立加密隧道访问你的内网服务
- 测试完成后隧道自动关闭

**适用场景**：
- 测试 staging 环境（`https://staging.internal.company.com`）
- 测试本地开发服务器（`http://localhost:8080`）
- 测试 VPN 内部系统

### 2. 并行测试

在 `serenity.properties` 或命令行配置：

```properties
# 5 个并行链
serenity.concurrent.chains=5

# 每个链的最大线程数
serenity.concurrent.max threads=3
```

**并行架构**：
```
Chain 1: BrowserStack Session A (Windows+Chrome)
Chain 2: BrowserStack Session B (macOS+Safari)  
Chain 3: BrowserStack Session C (Windows+Firefox)
Chain 4: BrowserStack Session D (Android+Chrome)
Chain 5: BrowserStack Session E (iOS+Safari)
```

> **注意**：每个并行线程都会创建独立的 BrowserStack 会话。
> 请确保你的 BrowserStack 套餐支持足够多的并行会话数。

### 3. 自定义能力扩展

通过代码动态覆盖默认配置：

```java
Map<String, Object> customCaps = new HashMap<>();
customCaps.put("browserName", "chrome");
customCaps.put("browserVersion", "120");  // 固定版本
customCaps.put("resolution", "1920x1080");
customCaps.put("timezone", "Hong_Kong");

Browser browser = BrowserStackManager.connect(playwright, customCaps);
```

### 4. 调试模式

```properties
browserstack.debug=true
```

开启后：
- 每个 Playwright 步骤都截图
- 详细网络请求日志
- 更详细的 Console 日志
- DOM 变更追踪

**仅限调试使用！会产生大量数据，影响性能。**

---

## 测试报告

### BrowserStack Dashboard（实时查看）

测试进行中和完成后都可以查看：

**URL**: https://automate.browserstack.com/dashboard/v2/builds

| 报告内容 | 说明 |
|----------|------|
| 📹 视频录制 | 完整测试过程回放 |
| 📊 网络日志 | HTTP 请求/响应详情 |
| 📝 控制台日志 | JavaScript console/error 输出 |
| 📸 截图 | 失败时的页面快照 |
| ✅❌ 状态标记 | passed / failed（由 setTestStatus 设置） |
| 🔗 会话链接 | 可分享给团队成员 |

### Serenity BDD 报告

标准报告位置：`target/site/serenity/index.html`

**BrowserStack 信息会嵌入报告**：
- 每个测试用例旁显示 Session Dashboard 链接
- 截图自动关联到对应步骤
- 失败原因同步到 Serenity 报告

### Jenkins 集成

```groovy
// Jenkinsfile
pipeline {
    agent any
    
    environment {
        BROWSERSTACK_ENABLED = "${params.USE_BROWSERSTACK}"
        BROWSERSTACK_USERNAME = credentials('bs-username')
        BROWSERSTACK_ACCESS_KEY = credentials('bs-access-key')
    }
    
    stages {
        stage('UI Tests') {
            steps {
                bat "mvn clean verify -Dbrowserstack.session.name=${env.BUILD_NUMBER}"
            }
        }
    }
    
    post {
        always {
            // 发布 Serenity 报告
            publishHTML(target: [
                allowMissing: false,
                alwaysLinkToLastBuild: true,
                keepAll: true,
                reportDir: 'target/site/serenity',
                reportFiles: 'index.html',
                reportName: 'Serenity Report'
            ])
            
            // 发布 BrowserStack 链接
            script {
                println "BrowserStack Dashboard: https://automate.browserstack.com/dashboard/v2/builds"
            }
        }
    }
}
```

---

## 最佳实践

### 1. 安全凭证管理

| 场景 | 推荐方式 | ❌ 避免 |
|------|---------|-------|
| 本地开发 | 环境变量 `.env` 文件 | 明文写进 properties |
| CI/CD Pipeline | Jenkins Credentials 插件 | 写入 Git 仓库 |
| Docker 容器 | Docker Secrets / Env File | ENTRYPOINT 里硬编码 |
| K8s 部署 | Kubernetes Secret | ConfigMap 存密钥 |

### 2. 成本优化策略

```properties
# 开发环境：关闭 BrowserStack，用本地浏览器
browserstack.enabled=false

# CI 主分支：只跑最新 Chrome
browserstack.os=Windows
browserstack.os.version=11
browserstack.browser.version=latest

# Release 分支：多浏览器矩阵
# 通过 Maven Profile 切换
```

**并行优化**：
- 使用 `serenity.concurrent.chains=N` 控制并行度
- N 的最大值 = BrowserStack 套餐支持的并行会话数
- 通常 3-5 个并行链是性价比最优的选择

### 3. 失败重试策略

结合 RerunConfiguration：

```properties
# 失败重试 2 次
rerun.max.count=2
rerun.retry.failed.tests=true

# 重试时会创建新的 BrowserStack 会话（旧会话标记 failed）
```

### 4. 超时调优

根据网络环境和测试复杂度调整：

| 环境 | 推荐 timeout | 说明 |
|------|-------------|------|
| 本地网络 | 60s | 快速失败 |
| 企业内网 | 180s | 穿越防火墙延迟高 |
| 跨区域（HK→US Cloud） | 300s | 默认值，稳定可靠 |
| 大型 E2E 测试套件 | 600s | 包含等待页面加载等 |

---

## 故障排查

### 问题 1：连接失败

```
错误: [BrowserStack] Failed to connect to BrowserStack.
      Check credentials and network connectivity.
```

**诊断步骤**：

```bash
# 1. 验证凭证是否正确
curl -u "username:access_key" https://api.browserstack.com/automate/builds.json

# 2. 检查网络连通性
ping cdp.browserstack.com
telnet cdp.browserstack.com 443

# 3. 检查防火墙规则（需要允许 WSS 出站连接）
```

**常见原因及解决方案**：

| 原因 | 解决方案 |
|------|---------|
| 用户名/AccessKey 错误 | 登录 BrowserStack 后台重新复制 |
| 账号过期/额度耗尽 | 联系管理员续费或升级套餐 |
| 企业防火墙阻断 WSS | 开放 `cdp.browserstack.com:443` 白名单 |
| 代理服务器干扰 | 配置 JVM 代理参数 `-Dhttps.proxyHost=proxy:8080` |

### 问题 2：认证错误 (401 Unauthorized)

```
HTTP 401 from api.browserstack.com/automate/sessions
```

**检查项**：
1. AccessKey 是否包含特殊字符？→ 需要 URL 编码
2. 用户名是否有空格？→ 用引号包裹
3. 凭证是否过期？→ BrowserStack 后台重新生成

### 问题 3：超时错误

```
Timeout waiting for browser session after 60 seconds
```

**解决方案**：

```properties
# 增加超时时间
browserstack.timeout=600

# 或减少能力参数数量（简化连接请求）
# browserstack.network.logs=false  # 关闭网络日志
```

### 问题 4：浏览器版本不支持

```
Unsupported browser type or version for BrowserStack
```

**验证可用组合**：https://www.browserstack.com/list-of-browsers-and-platforms/automate

### 问题 5：Local Testing 无法连接内网

```
Local Testing connection failed
```

**解决方案**：
1. 确认目标地址可从本机访问（先 curl/ping 测试）
2. 检查本地防火墙是否阻止 BrowserStack Local 进程
3. 尝试指定端口：`-DBROWSERSTACK_LOCAL_IDENTIFIER=my-test-1234`
4. 如果使用 VPN，确保 VPN 在 BrowserStack Local 启动前已连接

### 问题 6：setTestStatus 不生效

```
[BrowserStack] Cannot set status: no active session
```

**原因**：`currentSessionId` 未被设置

**解决**：确保调用顺序正确：
```java
// 正确顺序
1. BrowserStackManager.connect(playwright);  // 返回 Browser
2. browser.newPage();                          // 创建页面
3. BrowserStackManager.setCurrentSessionId(sessionIdFromPage);
4. ... 执行测试 ...
5. BrowserStackManager.setTestStatus("passed", "OK");
```

---

## 日志输出示例

### 成功连接

```
[INFO] [BrowserStack] Connecting to remote browser...
[DEBUG] [BrowserStack] CDP URL: wss://john_doe:****@cdp.browserstack.com?browserName=chrome&os=Windows...
[INFO] [BrowserStack] Connected successfully!
[INFO] [BrowserStack] Configuration:
[INFO]   OS: Windows 11
[INFO]   Browser: chrome latest
[INFO]   Project: My Automation Project / Build: Build-1713326400000
[INFO]   Video: true, Debug: false
```

### 状态标记成功

```
[INFO] [BrowserStack] Setting session abc123def456 status to: passed
[INFO] [BrowserStack] Status updated successfully for session abc123def456
```

### 错误日志（凭证缺失）

```
Exception in thread "main" java.lang.IllegalStateException: 
[BrowserStack] Credentials not configured!
Set one of:
  1. Environment variables: BROWSERSTACK_USERNAME, BROWSERSTACK_ACCESS_KEY
  2. Config properties: browserstack.username, browserstack.access.key
  3. System properties: -Dbrowserstack.username=xxx -Dbrowserstack.access.key=yyy
```

---

## API 参考

### 公共方法一览

| 方法 | 返回类型 | 说明 |
|------|---------|------|
| `isBrowserStackEnabled()` | `boolean` | 检查是否启用云测试 |
| `isLocalEnabled()` | `boolean` | 检查是否开启 Local Testing |
| `connect(Playwright)` | `Browser` | 创建远程浏览器连接（**核心方法**） |
| `connect(Playwright, Map)` | `Browser` | 创建带自定义能力的连接 |
| `getCurrentSessionId()` | `String` | 获取当前会话 ID |
| `setCurrentSessionId(String)` | `void` | 设置当前会话 ID |
| `getCurrentSessionUrl()` | `String` | 获取 Dashboard URL |
| `setTestStatus(String, String)` | `boolean` | 标记测试结果 (passed/failed) |
| `getSessionUrl(String)` | `String` | 获取任意会话的 Dashboard URL |

### 已废弃方法（向后兼容）

| 方法 | 替代方案 |
|------|---------|
| `configureLaunchOptions(options, type)` | `connect(Playwright)` |
| `getConnectOptions(type)` | `connect(Playwright)` |
| `setTestStatus(sessionId, status, reason)` | `setTestStatus(status, reason)` |

---

## 参考资源

- **BrowserStack 官方文档**: https://www.browserstack.com/docs/
- **Playwright on BrowserStack**: https://www.browserstack.com/docs/automate/playwright
- **BrowserStack Automate REST API**: https://www.browserstack.com/docs/automate/api-reference/selenium/introduction
- **CDP 协议规范**: https://chromedevtools.github.io/devtools-protocol/
- **支持的平台/浏览器列表**: https://www.browserstack.com/list-of-browsers-and-platforms/automate

## 版本更新记录

| 版本 | 日期 | 变更内容 |
|------|------|---------|
| v2.0 (Enterprise) | 2026-04-17 | **全面重写**：<br>- 采用 CDP 连接方式<br>- 实现 REST API 状态标记<br>- 新增会话生命周期管理<br>- 凭证安全脱敏<br>- Local Testing 支持<br>- 配置优先级链 |
| v1.0 | 2025-xx-xx | 初始版本：基本 Selenium Grid 连接 |
