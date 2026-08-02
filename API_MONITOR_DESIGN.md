# API 监控与失败通知设计说明

> 目标：在做 UI 自动化时，按「功能」监控该功能的 API endpoint，运行结束后将**失败的 endpoint（含完整 request / response）按 API owner 分组去重**，产出结构化文件，由 CI（Jenkins `emailext` 等发布插件）「谁的 API 发给谁」。

---

## 1. 整体架构

```
┌─────────────────────┐      load JSON        ┌──────────────────────────┐
│ api-monitor-        │ ───────────────────►  │ ApiMonitorConfig         │
│ config.json         │   (功能→endpoint→参数) │  (Gson 单例加载)          │
└─────────────────────┘                        └────────────┬─────────────┘
                                                            │ getFeatures()
                                                            ▼
┌─────────────────────┐      registerFeature                ┌──────────────────────────┐
│ 测试代码 (scenario) │ ──────────────────────────────────► │ ApiMonitorOrchestrator    │
│ registerFeature(    │   (传功能名，取 endpoint 注册监控)    │  - pattern 去重注册        │
│  "login", page)     │                                     │  - pattern→owner 映射      │
└───────────────────────┘                                     └────────────┬─────────────┘
                                                                          │ RouteDsl.on(page)
                                                                          │   .api(p).monitor()...
                                                                          ▼
┌─────────────────────┐   捕获 request/response + 断言失败            ┌──────────────────────────┐
│ MonitorHandler      │ ──────────────────────────────────────────► │ MonitorFailureCollector  │
│ (Playwright 路由拦截)│   转发失败调用                               │  - 指纹去重               │
└─────────────────────┘                                             │  - 按 owner 分组          │
                                                                     └────────────┬─────────────┘
                                                                               │ 整轮结束 write()
                                                                               ▼
┌─────────────────────┐   读取结构化文件循环发信                      ┌──────────────────────────┐
│ Jenkins (emailext/  │ ◄────────────────────────────────────────── │ MonitorFailureReportWriter│
│  publishHTML)       │   monitor-failures-by-owner.json             │  - 产出 json / md / html  │
└─────────────────────┘                                             └──────────────────────────┘
```

**核心原则**：框架只负责「加载清单 → 注册监控 → 捕获失败 → 去重归集 → 按 owner 分组产出文件」，**不发送邮件、不引入邮件依赖**。实际投递完全交给 CI 已有的发布插件。

---

## 2. 配置文件（JSON 监控清单）

文件：`src/test/resources/config/api-monitor-config.json`

结构为 **功能名 → (endpoint pattern → 监控参数)**。

```json
{
  "login": {
    "api/login": {
      "timeout": 30,
      "autoStopMonitor": true,
      "apiOwner": "zhangsan@company.com",
      "expectStatus": 200
    },
    "api/auth/assert": {
      "timeout": 30,
      "autoStopMonitor": false,
      "apiOwner": "lisi@company.com",
      "expectStatus": 200
    }
  },
  "transfer": {
    "api/transfer/**": {
      "timeout": 45,
      "autoStopMonitor": true,
      "apiOwner": "wangwu@company.com"
    }
  }
}
```

### 字段说明

| 层级 | 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|------|
| 一级 key | 功能名 | string | 是 | 如 `login` / `transfer`，调用 `registerFeature` 时传入 |
| 二级 key | endpoint pattern | string | 是 | 传给 `RouteDsl.api(pattern)` 的匹配模式，支持 `**` 通配 |
| endpoint | `timeout` | int | 否 | 监控超时秒数，默认 30 |
| endpoint | `autoStopMonitor` | boolean | 否 | 命中后是否自动停止监控，默认 `true` |
| endpoint | `apiOwner` | string | 否 | API owner 邮箱，**失败通知接收人**。同一人可在多个 endpoint 重复出现，归集后会合并到一封邮件 |
| endpoint | `expectStatus` | int | 否 | 期望 HTTP 状态码，不配置默认 200 |
| endpoint | `description` | string | 否 | 可选描述 |

> **一个人员负责多个 endpoint**：无需特殊语法，只要在多个 endpoint 下都写上同一 `apiOwner` 邮箱即可。归集时按 owner 分组，最终每人只收到一封汇总邮件（见第 5 节）。

---

## 3. 使用流程（先 load，再按功能名注册）

### 3.1 加载配置

```java
// 方式一：使用默认路径 config/api-monitor-config.json（懒加载单例）
ApiMonitorConfig.getInstance();

// 方式二：显式传入 JSON 文件名先加载（覆盖默认单例）
ApiMonitorConfig.loadFrom("src/test/resources/config/api-monitor-config-uat.json");

// 方式三：重置后重新加载（测试套件结束时或切换环境用）
ApiMonitorConfig.reset();
ApiMonitorConfig.loadFrom("config/api-monitor-config.json");
```

加载顺序：文件系统（允许外部覆盖）→ classpath。

### 3.2 注册监控（传入功能名）

在 scenario / case 开始时调用，**传入功能名**即可加载该功能下所有 endpoint 的监控：

```java
// 使用已加载的默认清单
ApiMonitorOrchestrator.getInstance().registerFeature("login", page);

// 或注册时显式指定清单文件
ApiMonitorOrchestrator.getInstance().registerFeature("login", "config/api-monitor-config.json", page);
```

`registerFeature` 内部：
1. 从已加载清单取 `features.get(featureKey)`。
2. 遍历该功能的 endpoint，逐个创建 `RouteDsl.on(page).api(pattern).monitor().expectStatus().timeout().autoStopOnMatch().done().start()` 规则。
3. **去重**：同一 endpoint pattern 在多 case 中只注册一次（见第 4 节）。

### 3.3 设置当前 scenario（可选但推荐）

为让报告里的「触发 Scenario」不为空，在测试框架的 `@Before` / `@BeforeScenario` hook 里设置：

```java
MonitorFailureCollector.getInstance().setCurrentScenario("Login.scn1");
// ... 测试执行 ...
MonitorFailureCollector.getInstance().clearCurrentScenario();
```

---

## 4. 两层去重机制

| 层级 | 机制 | 解决问题 |
|------|------|----------|
| **注册去重** | `ApiMonitorOrchestrator.registeredPatterns`（`Set<String>`） | 多个 case 监控同一 API 时，只建一次 `RouteDsl` 规则，避免重复注册、重复拦截 |
| **失败去重** | `MonitorFailureCollector` 指纹 `pattern\|statusCode\|响应体hash` | 同一 endpoint 的相同错误只记一条，并累计触发的 scenario 列表，避免 50 个 case 同一坏 endpoint 刷 50 条 |

失败去重后，每条失败记录带 `scenarios` 列表，标明「这个错误在哪些 scenario 出现过」。

---

## 5. 产出文件与按 owner 分组

整轮测试报告生成阶段（由 `SummaryReportGenerator.generateSummaryReport()` 末尾触发），`MonitorFailureReportWriter.write()` 产出：

| 文件 | 用途 |
|------|------|
| `target/monitor-failures-by-owner.json` | 按 `apiOwner` 分组的失败清单，供 Jenkins `emailext` 循环读取发送 |
| `target/monitor-failures-summary.md` | 人类可读摘要 |

### JSON 结构（按 owner 分组，一人一封）

```json
{
  "zhangsan@company.com": [
    {
      "feature": "login",
      "pattern": "api/login",
      "owner": "zhangsan@company.com",
      "status": "500",
      "method": "POST",
      "requestUrl": "https://app/api/login",
      "requestHeaders": { "Content-Type": "application/json" },
      "requestBody": "{ \"user\":\"...\" }",
      "responseHeaders": { ... },
      "responseBody": "{ \"error\":\"...\" }",
      "reason": "STATUS expected=200 actual=500",
      "scenarios": [ "Login.scn1", "Login.scn2" ]
    }
  ],
  "lisi@company.com": [ ... ]
}
```

> **方案 A（每人一封汇总）**：Jenkins 循环每个 owner 调一次 `emailext`，`to: owner`。同一 owner 负责的多个 endpoint 失败会全部出现在其邮件中，且同一条错误不会重复发送。

---

## 6. CI 集成（Jenkins）

框架不发送邮件，由 Jenkins pipeline 读取 `monitor-failures-by-owner.json` 循环发送。

### 6.1 Jenkinsfile 示例

```groovy
post {
    always {
        script {
            def failures = readJSON file: 'target/monitor-failures-by-owner.json'
            failures.each { owner, calls ->
                emailext(
                    to: owner,                              // 接收人 = apiOwner（谁的 API 发给谁）
                    subject: "【API 监控失败】${calls.size()} 个 endpoint - ${env.JOB_NAME} #${env.BUILD_NUMBER}",
                    body: """<p>以下 API endpoint 监控失败，请关注：</p>
                             <ul>${calls.collect { "<li>${it.pattern} (${it.status}) - ${it.reason}</li>" }.join('')}</ul>
                             <p>详情见附件。</p>""",
                    mimeType: 'text/html',
                    attachmentsPattern: 'target/monitor-failures-summary.md'
                )
            }
        }
    }
}
```

### 6.2 发件人（From）从哪来

发送人邮箱**不在框架 JSON 里**，由 Jenkins 环境提供：

- 若 Jenkins 系统已配置「Email Extension」默认发件人，`emailext` 不写 `from` 即用系统默认。
- 否则在 pipeline 注入 `from: env.SMTP_FROM ?: 'dbb-automation@company.com'`。

### 6.3 发布 HTML 报告（可选）

若主报告用 `publishHTML` 发布，监控 HTML（如后续产出 `monitor-failures-by-owner.html`）可同样发布：

```groovy
publishHTML([
    reportDir: 'target',
    reportFiles: 'monitor-failures-by-owner.html',
    reportName: 'API Monitor Failures'
])
```

---

## 7. 关键类一览

| 类 | 职责 |
|----|------|
| `ApiMonitorConfig` | 读取 JSON 监控清单（Gson），提供 `getInstance()` / `loadFrom(path)` / `reset()` |
| `ApiMonitorConfig.EndpointConfig` | 单个 endpoint 的监控参数 POJO（含 `apiOwner`） |
| `ApiMonitorOrchestrator` | 按功能名注册监控规则 + 跨 case pattern 去重 + pattern→owner 映射 |
| `MonitorFailureCollector` | 失败归集 + 指纹去重 + 按 owner 分组；`setCurrentScenario` 记录场景名 |
| `MonitorFailureReportWriter` | 整轮结束写出 `monitor-failures-by-owner.json` 与 `.md` |
| `CapturedApiCall` | 完整请求/响应快照（已含 `requestBody`，供失败通知） |
| `MonitorHandler` | Playwright 路由拦截，捕获 request/response，断言失败时转发给 collector |

---

## 8. 常见问题

**Q：配置文件路径在哪？**
默认 `config/api-monitor-config.json`（文件系统优先，classpath 回退）。可用 `ApiMonitorConfig.loadFrom("你的路径")` 指定。

**Q：本地跑会发邮件吗？**
不会。框架本身不发送邮件；只有 Jenkins 的 `emailext` 步骤才发，本地不会触发。

**Q：一个 owner 负责多个 endpoint 怎么配？**
多个 endpoint 下都写同一 `apiOwner` 即可，归集自动合并到一封邮件。

**Q：scenario 名为空？**
需在测试框架 hook 里调用 `MonitorFailureCollector.setCurrentScenario(name)`，否则报告里 scenario 列为空（不影响去重和 owner 分组）。
