# 并发上下文执行器（ConcurrentContextExecutor）使用指南

> 配套设计文档：[`architecture/CONCURRENT_CONTEXT_EXECUTOR_DESIGN.md`](architecture/CONCURRENT_CONTEXT_EXECUTOR_DESIGN.md)
> 模块归属：`web`（包 `framework.web.lifecycle` + `framework.web.concurrent`）
> 状态：企业级落地（G3 加固 + Serenity 桥接 + SSO 闸门 + 崩溃韧性守卫均已就绪）；跨会话多线程 IT / E2E 沙箱回归见文末「待补项」。

---

## 1. 这是什么

`ConcurrentContextExecutor` 是框架级并发原语：以**有界线程池（或 JDK 21 虚拟线程）**并发运行多个彼此独立的
`ContextTask`，每个任务在独立线程上获得独立 `BrowserContext`（共享 Browser 模式下由 per-thread Context 隔离），
任务结束后清理本线程 Context（**不关闭共享 Browser**）。失败与页面错误以结构化 `ContextTaskResult` 回传**编排线程**，
由既有 Serenity 通道统一标记报告。

设计目标（企业级约束，详见设计文档第九节）：

- **线程安全 / 并发可见性**：G1/G2 桥接（Serenity 状态留在编排线程）；G3 `ApiCaptureContext` 按 `BrowserContext` 隔离；结果收集用不可变 `List.copyOf`。
- **清晰 API 边界**：桥接逻辑收口于 `ConcurrentContextExecutor`，内部 `SerenityBusBridge` / `TestContextBridge` 为包级私有协作类；`ConcurrencyGate` / `BrowserCrashGuard` 为 `public` 但语义只读、异常安全。
- **可观测性**：每任务线程命名 `dbb-ctx-N` + MDC `concurrentTask`；结果含 `threadName` / `durationMillis` / `pageErrors`；`ConcurrencyGate.stats()` 暴露进入次数与被串行身份数。
- **健壮错误处理**：入参校验抛 `IllegalArgumentException`；单任务失败隔离进 `ContextTaskResult`；超时 `cancel(true)` 兜底；`finally` 配对清理不泄漏。

---

## 2. 何时使用

适用：

- 同一 Feature 内多个**互不依赖**的场景需要并行跑，以缩短总耗时（例如多个只读查询场景、多个独立用户的登录态预建）。
- 需要在单 JVM 内以「一个共享 Browser + N 个 Context」模型压测并发承载（见 `PLAYWRIGHT_SHARED_BROWSER_ENABLED`）。

**不适用 / 禁忌**：

- 任务之间共享可变全局状态（除受 `ConcurrencyGate` 保护的身份态外）。
- 在 `ContextTask.call()` 内直接调用 `StepEventBus.getEventBus().testFailed(...)` 等 Serenity 报告 API——工作线程拿到的是无监听器的总线（G1），标记无效。失败必须经 `ContextTaskResult` 回编排线程。

---

## 3. 快速上手

### 3.1 定义任务

```java
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.ContextTask;

// 方式 A：命名 lambda（推荐，便于结果追踪）
ContextTask<String> loginAsAlice = ContextTask.of("login-alice", () -> {
    // 任务内照常使用 BasePage / PlaywrightManager
    HomePage home = new HomePage();
    home.open();
    home.login("alice", secret());
    return home.currentUser();
});

// 方式 B：实现接口（默认 name() 取类名）
class LoginBobTask implements ContextTask<String> {
    @Override public String call() { /* ... */ return "bob"; }
}
```

> ⚠️ 每个任务在独立线程运行，框架保证该线程拥有独立 `BrowserContext`。任务内**不要缓存线程间共享的 Page/Context 引用**到外部静态字段。

### 3.2 运行 + 回放失败

```java
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.ConcurrentContextExecutor;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.ContextTask;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.ContextTaskResult;

import java.util.List;

List<ContextTask<String>> tasks = List.of(loginAsAlice, new LoginBobTask());

// runAll：并发度 = min(任务数, parallelism, 硬上限16)
List<ContextTaskResult<String>> results = ConcurrentContextExecutor.runAll(tasks);

// 编排线程遍历结果：任一失败 → 经 Serenity 既有通道标记 + 抛汇总 CompletionException
ConcurrentContextExecutor.assertAllSucceeded(results);
```

### 3.3 读取结构化结果（不抛异常时）

```java
for (ContextTaskResult<String> r : results) {
    if (r.isSuccess()) {
        String user = r.valueOrThrow();            // 成功时返回值
    } else {
        Throwable failure = r.getFailure();         // 失败原因
        List<String> pageErrors = r.getPageErrors(); // 任务线程累积的未捕获页面异常
        long ms = r.getDurationMillis();            // 耗时
        String thread = r.getThreadName();          // 执行线程名（dbb-ctx-N）
    }
}
```

### 3.4 自定义选项（并行度 / 超时 / 虚拟线程）

```java
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.ConcurrentContextOptions;

ConcurrentContextOptions options = ConcurrentContextOptions.builder()
        .parallelism(8)                       // 同时运行的独立 Context 数（实际再与 taskCount/硬上限16 取 min）
        .failFast(true)                       // 任一失败即取消其余未启动任务
        .perTaskTimeoutMillis(30_000L)        // 单任务超时后 cancel(true) 兜底（0 = 不限）
        // .useVirtualThreads(true)           // JDK 21+ 且已审计 BasePage pinning 后启用（见 §7）
        .build();

List<ContextTaskResult<String>> results = ConcurrentContextExecutor.runAll(tasks, options);
```

> `ConcurrentContextOptions.defaults()` 从 `FrameworkConfig` 读取并行度与超时（见 §4）。

---

## 4. 配置项

所有项经框架统一三段式解析链（`System.getProperty` → 环境变量 → Serenity(`serenity.conf`/`serenity.properties`) → 默认值），运行期可经 `-D` 或 `serenity.conf` 覆盖。

| 配置键 | 默认值 | 含义 |
|---|---|---|
| `serenity.playwright.shared.browser.enabled` | `false` | 共享 Browser 模式：一个 Browser 实例 + 多 Context 并行（Playwright 官方推荐并发模型）。`false` 时每线程独立 Browser。 |
| `serenity.playwright.concurrent.parallelism` | `4` | 并发执行器并行度（同时运行的独立 Context 任务数）；执行器内部再与 `taskCount` 及硬上限取 `min`。 |
| `serenity.playwright.concurrent.task.timeout.seconds` | `60` | 单任务超时（秒）；超时后 `cancel(true)` 中断任务线程（best-effort，真实上限仍依赖 navigation/element 超时）。 |
| `serenity.playwright.concurrent.partition.enabled` | `false` | **SSO 并发闸门总开关**。`false` 时 `ConcurrencyGate` 全为 no-op，行为零回归。 |
| `serenity.playwright.concurrent.partition.per.key.permits` | `1` | 每个并发分区键的许可数；`>1` 表示允许同身份 N 路并发。 |
| `serenity.playwright.concurrent.partition.dimensions` | `environment,username` | 参与分区键的身份维度集合（可选：`environment,username,tenant,role,locale`）。 |
| `serenity.playwright.concurrent.browser.crash.guard.enabled` | `true` | 崩溃韧性守卫：共享 Browser 崩溃时进程级单飞重建并重跑失败任务一次（仅崩溃型失败触发）。 |
| `playwright.page.error.failOnError` | `false` | 页面未捕获 JS 异常是否触发失败。并发下该开关不自动标红 worker 线程，错误随 `ContextTaskResult` 回传编排线程统一标记。 |

> **硬上限**：并发度硬上限固定为 `16`（`ConcurrentContextExecutor.DEFAULT_HARD_CAP` / `ConcurrentContextOptions.DEFAULT_HARD_CAP`），不可经配置覆盖，作为单 Browser 进程可承载 Context 数的安全护栏。实际并发度 = `min(taskCount, parallelism, 16)`。

### 4.1 配置示例

`serenity.conf`（或 `serenity.properties`）：

```properties
# 并发执行器
serenity.playwright.shared.browser.enabled=true
serenity.playwright.concurrent.parallelism=8
serenity.playwright.concurrent.task.timeout.seconds=120

# SSO 并发闸门：相同 identity 串行，不同 identity 并行
serenity.playwright.concurrent.partition.enabled=true
serenity.playwright.concurrent.partition.per.key.permits=1
serenity.playwright.concurrent.partition.dimensions=environment,username

# 崩溃韧性守卫（默认开启即可）
serenity.playwright.concurrent.browser.crash.guard.enabled=true
```

命令行即时覆盖（单测 / 调试友好）：

```bash
mvn test -Dserenity.playwright.concurrent.parallelism=12 \
         -Dserenity.playwright.concurrent.partition.enabled=true
```

---

## 5. SSO 并发闸门（ConcurrencyGate）

同一身份（相同 SSO 会话）的多个 scenario 并发登录会互相踩踏（token 互踢）。`ConcurrencyGate` 按身份分区做**互斥**：

- 相同身份（同 key）→ 串行；不同身份 → 并行；无身份（key == null，如只读场景）→ 直接放行，不进互斥 Map。
- 总开关 `serenity.playwright.concurrent.partition.enabled = false` 时 `acquire`/`release`/`enter` 全为 no-op，**行为零回归**。
- 每个 key 持有独立公平 `Semaphore`（许可数取自 `per.key.permits`）；`acquire` 用 `acquireUninterruptibly()` 避免吞/打测试线程中断策略。
- `ConcurrentHashMap` 不允许 null key，已用 `if (key == null) return` 规避。

### 5.1 定义身份解析器

```java
import com.hsbc.cmb.hk.dbb.automation.framework.web.concurrent.ConcurrencyKeyResolver;
import com.hsbc.cmb.hk.dbb.automation.framework.web.concurrent.ConcurrencyPartitionKey;

import java.util.Map;
import java.util.Optional;

public class ScenarioIdentityResolver implements ConcurrencyKeyResolver {
    @Override
    public Optional<ConcurrencyPartitionKey> resolve() {
        String env = TestContextHolder.getEnvironment();   // 业务取值
        String user = TestContextHolder.getLoggedInUser();  // 无登录/只读场景返回 empty
        if (user == null) {
            return Optional.empty();                        // 无身份 → 不参与互斥
        }
        return Optional.of(ConcurrencyPartitionKey.of(
                Map.of("environment", env, "username", user)));
    }
}
```

> `ConcurrencyPartitionKey.of(...)` 要求至少一个非空维度，否则抛 `IllegalArgumentException`。维度名自动小写、按字典序规范化，值保留大小写（兼容大小写敏感 IdP 用户名）。

### 5.2 在 scenario 生命周期中接入（try-with-resources）

```java
import com.hsbc.cmb.hk.dbb.automation.framework.web.concurrent.ConcurrencyGate;
import com.hsbc.cmb.hk.dbb.automation.framework.web.concurrent.ConcurrencyScope;

// 在 scenario 建立登录后的生命周期 hook 中：
try (ConcurrencyScope scope = ConcurrencyGate.enter(new ScenarioIdentityResolver())) {
    // scenario 主体；相同 identity 在此被串行，不同 identity 并行
    // 退出 try 块（正常或异常）即自动 release，信号量不泄漏
}
```

显式 `acquire` / `release`（须严格配对，release 放 finally）：

```java
ConcurrencyPartitionKey key = ConcurrencyPartitionKey.of(Map.of("environment","sit1","username","alice"));
try {
    ConcurrencyGate.acquire(key);
    // ... scenario 主体 ...
} finally {
    ConcurrencyGate.release(key);
}
```

### 5.3 观测

```java
ConcurrencyGate.ConcurrencyGateStats stats = ConcurrencyGate.stats();
// stats.enterCount()            累计进入次数
// stats.serializedIdentityCount() 被串行化的身份数（>0 表示闸门在生效）
// stats.activeGates()           当前活跃身份键数
```

---

### 5.4 Serenity 并发 E2E 实战（单 Browser + 多 Context，3 环境 × 3 username）

**并发驱动 = 框架自建**（设计文档 9.3），不依赖 Serenity 场景并行：`serenity.parallel.for.tests`
在 `CucumberWithSerenity`（JUnit 4）中是历史空操作，多个 scenario 实际串行于 `main` 线程（仅做到
「每 scenario 一个独立 Context」的隔离，并非并发）。

真实并发路径（见用户 Playwright 并发说明 —— 单 Browser、多 Context）：

1. `ConcurrentExecutionGlue`（包 `framework.web.concurrent`，**框架层随 web 分发**）在**编排线程**把 feature 中
   N 个 `(env, username)` 行构建为 N 个 `ContextTask`，提交 `ConcurrentScenarioExecutor.runCases` →
   框架 `ConcurrentContextExecutor.runAll` → **线程池真正并发**执行。业务层**零并发代码**，仅经 SPI 提供领域动作
   `ConcurrentLogonAction`（实现 `ConcurrentCaseAction`）。
2. 所有 worker 线程经 `PlaywrightManager.setConfigId(PlaywrightManager.sharedConfigId())` 设置**同一**
   configId；运行时开启 `serenity.playwright.shared.browser.enabled=true` 后 `keyFor` 返回
   `"shared:<configId>"`，**N 个并发登录复用同一个 Browser 实例**，各自持有隔离的 `BrowserContext`
   （Playwright 官方「单 Browser + 多 Context」并发模型，Cookie/LocalStorage 按 Context 隔离）。
3. 相同 `(env, username)` 在任务内 `ConcurrencyGate.acquire` 串行、不同身份并行（SSO 互踢防护 R7）。
4. 失败经 `ConcurrentContextExecutor.assertAllSucceeded` 在编排线程经 Serenity 既有通道回放
   （worker 线程不触碰 `StepEventBus`，桥接原则 G1）。

> ⚠️ 必须携带 `-Dserenity.playwright.shared.browser.enabled=true`：否则每个 worker 线程会各自启动
> **独立 Browser 进程**（多 Browser，资源开销高），退化为「多 Browser」而非「单 Browser 多 Context」。

配套交付（test-automation 模块）：

| 文件 | 作用 |
|---|---|
| `features/web/concurrent_logon_dbb.feature` | 单 scenario + DataTable：3 个不同身份（O63_SIT1/WP7UAT2_2、O38_SIT2/amhb2g0677_3、O88_SIT3/amhb2g0680_2）并发 + 3 个相同身份重复行 |
| `tests/web/CucumberConcurrentLogonRunnerIT.java` | 运行器（`@concurrent-logon` 标签，仅匹配单 scenario；glue 含框架包） |
| `framework/web/concurrent/ConcurrentExecutionGlue.java` | 框架层通用并发 glue（业务零并发代码，仅引用其步骤） |
| `tests/glue/ConcurrentLogonAction.java` | 业务领域动作（实现 `ConcurrentCaseAction`，经 SPI 发现） |

运行（推荐，单 Browser 多 Context；不同身份并行、相同身份也并行 → 仅验证跨环境隔离，闸门默认关闭）：

```bash
mvn -o -pl test-automation verify \
  -Dit.test=CucumberConcurrentLogonRunnerIT \
  -Dtags=@concurrent-logon \
  -Dserenity.playwright.shared.browser.enabled=true
```

开启 SSO 互斥（相同身份串行、不同身份并行，验证 R7/SSO 互踢防护）：

```bash
mvn -o -pl test-automation verify \
  -Dit.test=CucumberConcurrentLogonRunnerIT \
  -Dtags=@concurrent-logon \
  -Dserenity.playwright.shared.browser.enabled=true \
  -Dserenity.playwright.concurrent.partition.enabled=true
```

开启 `partition.enabled=true` 后，feature 中 4 个相同 `(O63_SIT1, WP7UAT2_2)` 行被串行化，
日志出现 `[concurrency-gate] identity ... serialized (blocked)`；其余不同身份仍并行。本验证依赖真实
DBB 环境与凭证（`serenity.conf` 的 `environment.*` / `userinfo_*`），需在可访问内网的运行机执行。

---

## 6. 浏览器崩溃韧性守卫（BrowserCrashGuard）

共享 Browser 模式下，Browser 进程是全部并发任务的单点，崩溃会让所有任务失败。`BrowserCrashGuard` 收口「崩溃检测 + 单次重跑」：

- 任务因崩溃失败时，在**进程级单飞锁**内重建共享 Browser（`PlaywrightManager.rebuildSharedBrowserIfDisconnected`），再于原 worker 线程重跑该任务一次（同身份经 per-thread `TestContext` 自然继承，无需额外亲和逻辑）。
- **句柄损坏 → 强制重建（2026-09-08 E2E 实测加固）**：并发导航偶发 Chromium 内部句柄错误（`Cannot find object to call __adopt__` / `previewUpdated`）时，连接仍在但驱动侧对象注册表已坏——断开型重建因 `isConnected` 恒 true 而 no-op，replay 落空。`isHandleCorruption`（窄签名识别）命中后改走 `recoverForced()` → `PlaywrightManager.rebuildSharedBrowser()` **无条件**重建共享 Browser（即使仍连接）再重跑；普通崩溃仍走断开型 `recover()`。两条路径共享同一进程级单飞锁，重跑仍严格有界 1 次。
- **事件监听器禁止同步 CDP 调用（2026-09-08 并发卡死根因修复）**：Playwright 事件（`onPage`/`onLoad`/`onDownload` 等）在<b>连接读线程</b>派发，监听器内若调用 `title()`/`evaluate()`/`saveAs()` 等同步传输方法会阻塞读线程自身，导致整条共享连接<b>自死锁</b>、所有并发导航挂起（页面停在 about:blank）。`createContext` 已移除 `onLoad→title()` 调试日志；`createPage` 的 `saveAs` 卸载到专属守护线程 `DOWNLOAD_EXECUTOR`。所有新增监听器须只做字段读取 / 日志，<b>禁止在监听器内发起同步 CDP 调用</b>。
- **只重跑崩溃型失败**：`isCrash` 基于异常类 / 消息特征（如 `browser has been closed`、`browser disconnected`、`connection closed` 等）判定；正常业务失败（断言/超时）不重跑，不掩盖缺陷。
- 重跑严格有界为 **1 次**（`MAX_REPLAY`），避免崩溃持续时无限循环。
- 总开关 `serenity.playwright.concurrent.browser.crash.guard.enabled = true`（默认开启，纯韧性增强）；关闭时退化为「失败直接随 `ContextTaskResult` 返回」。

```java
// 观测累计恢复次数（仅崩溃型触发）
long rebuilds = BrowserCrashGuard.rebuildCount();
```

---

## 7. 失败回放与页面错误汇聚（桥接原则）

并发任务的失败只发生在工作线程，结果以结构化 `ContextTaskResult` 回传**编排线程**。编排线程做两件事：

1. **失败回放**（`SerenityBusBridge.replayFailures`，由 `assertAllSucceeded` 委托）：对每个失败项经 `StepEventBus.testFailed(...)` 标记到 Serenity 报告（复用既有失败通道，零新增报告代码）；非 Serenity 环境（如离线单测）下安全降级，仅保留 `CompletionException` 抛出语义。
2. **页面错误汇聚**（`TestContextBridge.drainPageErrors`，由 `runOnce` 调用）：工作线程在任务结束时 drain 自身累积的未捕获页面异常，随 `ContextTaskResult` 回传编排线程统一汇聚标记（并发下不在 worker 线程自动标红）。

> 工作线程**从不触碰 `StepEventBus`**（设计文档第九节 G1）。失败必须经返回值/异常经 `ContextTaskResult#valueOrThrow()` 在编排线程回放。

---

## 8. 线程安全 / API 边界小结

| 维度 | 落实 |
|---|---|
| 并发可见性 | `ApiCaptureContext` 按 `BrowserContext` 隔离（`ApiCaptureManager.contextStores` 弱 key 同步 Map）；结果收集 `List.copyOf` |
| API 边界 | `SerenityBusBridge` / `TestContextBridge` 包级私有；`ConcurrencyGate` / `BrowserCrashGuard` 为 `public` 但语义只读、异常安全 |
| 入参校验 | `ConcurrentContextOptions` 校验 `parallelism>0` / `perTaskTimeoutMillis>=0`；`ConcurrencyPartitionKey.of` 校验非空维度 |
| 清理不泄漏 | `runOnce` 的 `finally` 配对 `PlaywrightManager.cleanupForScenario()` + `MDC.remove`；`ConcurrencyScope` 保证 `release` 配对 |

---

## 9. 待补项（诚实说明）

以下功能在设计文档中标注为「需真实 Browser/Server，本环境无法运行」，尚未以自动化 IT 形式固化：

- **R2 梯度压测**：单 Browser 可承载并发 Context 数（4→8→16）的硬上限校准——当前硬上限固定 16。
- **E2E 沙箱回归**：R7 共享 Browser 崩溃 replay 端到端验证、`api/core/web/route/reporting` 跨会话多线程 IT。
- **9.5 虚拟线程**：`ConcurrentContextOptions.useVirtualThreads(true)` 已可用，但启用前需先审计 `BasePage` 同步 API 与长持锁的 carrier 线程 pinning 风险（R6），确认无 `Object.wait` 长持锁；目前**无对应 `FrameworkConfig` 开关**，需代码显式开启。

---

## 10. 相关类索引

| 类 | 包 | 角色 |
|---|---|---|
| `ConcurrentContextExecutor` | `web.lifecycle` | 执行器门面（`runAll` / `assertAllSucceeded`） |
| `ContextTask<T>` | `web.lifecycle` | 任务接口（`call()` / `of(name, callable)`） |
| `ContextTaskResult<T>` | `web.lifecycle` | 结构化结果（`isSuccess` / `valueOrThrow` / `getPageErrors` …） |
| `ConcurrentContextOptions` | `web.lifecycle` | 不可变选项（Builder） |
| `SerenityBusBridge` | `web.lifecycle` | 包级私有：失败回放 |
| `TestContextBridge` | `web.lifecycle` | 包级私有：页面错误汇聚 |
| `BrowserCrashGuard` | `web.lifecycle` | 崩溃检测 + 单次重跑 |
| `ConcurrencyGate` | `web.concurrent` | SSO 并发闸门（acquire/release/enter/stats） |
| `ConcurrencyPartitionKey` | `web.concurrent` | 不可变分区键 |
| `ConcurrencyKeyResolver` | `web.concurrent` | 身份解析器接口 |
| `ConcurrencyScope` | `web.concurrent` | AutoCloseable 作用域（配对 release） |
