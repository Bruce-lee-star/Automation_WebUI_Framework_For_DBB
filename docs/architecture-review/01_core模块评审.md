# 模块评审 01｜`framework-core`

> 定位：通用底座（上下文 / 异步池 / 安全 / 关闭编排）
> 规模：19 Java 文件 / 3,373 行 / 自有单元测试 0 个
> 依赖声明：`core/pom.xml` 显式依赖 **0 个**（全部继承自父 POM）
> **模块总评：3.1 / 5.0（基本可用，地基被污染）**

---

## 一、模块职责盘点

| 包 | 类 | 行数 | 职责 |
|---|---|---:|---|
| `core.context` | `TestContext` / `ThreadLocalTestContext` / `TestContextHolder` / `ContextKey` | 171 | per-scenario 上下文 |
| `common.async` | `AsyncPool` | 489 | 全局异步任务池 + 监控 |
| `common` | `ShutdownCoordinator` | 95 | JVM 关闭编排 |
| `common.config` | `ConfigSource` / `VerboseLogging` | 212 | 配置读取 + 日志详细度 |
| `common.security` | `ConfigCipher` / `SecretValue` / `SensitiveDataSanitizer` / `BuiltinValueRecognizers` / `SensitiveValueRecognizer` | 1,554 | **加密 + 脱敏（占模块 46%）** |
| `common.route` | `RouteLifecycle` / `RouteLifecycleRegistry` / `CaptureContext` | 128 | 路由生命周期 SPI 契约 |
| `common.reporting` | `MonitorFailureReportSink` | 29 | 失败上报 SPI |
| `utils` | `JsonFileReader` / `RandomStringUtil` | 694 | 工具类 |

**结构观察**：安全能力独占近半代码量，说明团队对金融级数据保护的投入是真实的——这是本模块最大的资产。

---

## 二、八维度逐项分析

### D1 模块边界与依赖治理 —— 3.0 / 5 ⚠️

**问题 C-1（严重）：底层模块编译期强耦合 Serenity，地基被污染**

`ConfigSource.java:4` 直接 import Serenity 内部类：

```java
import net.thucydides.model.environment.SystemEnvironmentVariables;
...
String raw = SystemEnvironmentVariables.currentEnvironmentVariables().getProperty(key, defaultValue);
```

`core/pom.xml` 未声明任何依赖却能通过编译，**唯一原因是父 POM 的 `<dependencies>` 被无条件继承**。这意味着：

- `framework-core` 名义上是"零依赖底座"，实际携带 Playwright + Serenity + Selenium + HikariCP + axe-core 全量 classpath；
- 任何模块（含 core）都可以误用上层依赖而不被编译器/工具链拦截；
- `framework-core` **无法脱离 Serenity 被复用**（例如给纯 Playwright 或纯 API 项目）。

这与根 POM 注释声明的分层意图（`core <- reporting / api <- web <- route`）**直接冲突**：依赖方向看似正确，但依赖**内容**完全失控。

**问题 C-2（中）：包名 `core.context` 与 `common.*` 并存，语义重叠**

同一模块内同时存在 `framework.core.context` 与 `framework.common.*` 两个命名空间，且 `common` 下又分 `config / security / route / reporting / async`。`common` 是典型的"垃圾桶包"命名，承载了 5 个互不相关的关注点。

---

### D2 抽象设计与扩展性 —— 3.5 / 5 ✅

**优点 C-3：类型安全上下文键（ContextKey）—— 设计优秀**

```java
public <T> T get(ContextKey<T> key);
public <T> void set(ContextKey<T> key, T value);
```
`ContextKey<T>` 携带类型信息，`get` 返回 `T` 而非 `Object`，**编译期杜绝强转错误**。这比"字符串 key + 强转"的通行做法高出一个层次。

**优点 C-4：SPI 契约下沉正确**

`RouteLifecycle` / `RouteLifecycleRegistry` / `MonitorFailureReportSink` 三个接口放在 core，由 route/reporting 实现，通过 `RouteLifecycleRegistry.get()` 反向查找——**依赖倒置（DIP）落地正确**，使 core 无需知道 route 的存在。

**问题 C-5（中）：`TestContextHolder` 是全局静态，仅为"过渡方案"但无收敛计划**

`TestContext.java:12-13` 自述：*"先收拢后删除……待全部收拢完成并验证后，再考虑构造注入以消灭 Holder"*。当前状态：
- 全局 `static ThreadLocal`（`TestContextHolder.java:11`）
- 无任何机制阻止新代码继续新增 `static ThreadLocal`
- 无 ArchUnit/静态检查规则保障"收拢"方向不被逆转

**问题 C-6（中）：`Utils` 包定位模糊**

`RandomStringUtil`（394 行）与 `JsonFileReader`（300 行）放在 `framework.utils`（注意：不在 `core.*` 也不在 `common.*`，是第三套顶层命名空间）。三个顶层包（`core` / `common` / `utils`）并存，命名体系不统一。

---

### D3 并发与线程安全 —— 2.5 / 5 ⚠️

**问题 C-7（严重）：`ThreadLocalTestContext` 明确声明"不需要并发保护"，但假设脆弱**

`ThreadLocalTestContext.java:8-9` 注释：*"内部以普通 Map 存储，实例由 per-thread ThreadLocal 持有，故无需并发安全"*。

该假设在以下场景**立即失效**：
1. `TestContext` 引用被传递到 `AsyncPool` 的工作线程（core 自身就提供异步池）；
2. 使用 `InheritableThreadLocal` 或虚拟线程（JDK 21 已启用，`maven.compiler.source=21`）时子线程继承同一实例；
3. Playwright 事件回调线程与测试主线程共享引用。

**当前无 `InheritableThreadLocal` 支持** → 子线程实际上**拿不到**上下文，这是功能缺口；而若有人手工传递引用 → 立刻变成数据竞争。**两个方向都有问题，却没有防御措施**。

**问题 C-8（严重）：`ShutdownCoordinator.runAll()` 一次性，不可复位**

```java
private static final AtomicBoolean running = new AtomicBoolean(false);
public static void runAll() {
    if (!running.compareAndSet(false, true)) { return; }   // ← 一旦为 true，永不复位
    ...
}
```
类注释（第 19 行）声称"同名任务自动去重（应对 reset 后重 init 的测试场景）"，但 `runAll()` 执行过一次后，后续**所有**调用静默返回空。测试环境 reset 后重新初始化框架，关闭编排**永久失效**——注释承诺的能力与实现不符。

**问题 C-9（中）：`AsyncPool` 全静态单例，无法隔离/重启**

- `POOL` / `SCHEDULER` / `MONITOR_CALLBACK_EXECUTOR` 均为 `static final`；
- `shutdownGracefully()` 执行后（如测试套件结束），这些执行器**无法重建**；
- `MONITOR_CALLBACK_EXECUTOR` 关闭后所有后续 Monitor 回调**永久丢弃**（虽有计数告警，但功能已失）。

**问题 C-10（中）：`CONTEXT_SCHEDULERS` 无生命周期兜底**

`newContextScheduler()` 注册 per-context 调度器，文档要求调用方在 context 关闭时 `removeContextScheduler()`。但：
- 无 WeakHashMap/弱引用兜底（对比 route 模块已用 `WeakHashMap`——同项目内标准不一致）；
- 若调用方异常未清理 → **线程与内存泄漏**；
- 仅在 JVM 关闭时统一 `clear()`，运行期泄漏无感知（无泄漏检测告警）。

**问题 C-11（中）：`runWithTimeout` 语义可疑**

```java
SCHEDULER.schedule(() -> {
    try { f.get(0, TimeUnit.MILLISECONDS); }      // ← 到期时只探测一次
    catch (TimeoutException e) { f.cancel(true); ... }
}, timeoutMs, ...);
```
在超时时刻**仅做一次**非阻塞探测。若任务恰好在探测后、cancel 前完成，行为正确；但若任务在 `timeoutMs` 前完成，调度任务仍会占用 SCHEDULER 线程直到 `timeoutMs` 到期才执行探测——**SCHEDULER 只有 2 个线程**，大量带超时任务会耗尽调度线程，构成隐蔽瓶颈。

**问题 C-12（轻）：`SensitiveDataSanitizer` 静态可变状态 + 手写双检锁**

`activeValueRecognizers`（volatile List）、`EXTRA_*_KEYS`、`extraLoaded` / `rulesLoaded` 标志位，配合 `synchronized` 方法——可变全局状态 + 手写双检锁，无 SPI 隔离，多套件并行时规则互相干扰。

---

### D4 生命周期与资源治理 —— 3.0 / 5

**优点 C-13：`ShutdownCoordinator` 统一收口 —— 方向正确**

将散落各处的 `Runtime.addShutdownHook` 收敛为单一有序钩子，`ORDER_API_MONITOR_FLUSH(100) → ORDER_FRAMEWORK_CORE(900)` 七档顺序，单任务异常不影响其余。**这是企业级做法**，明显优于各模块自行注册钩子。

**问题 C-14（中）：只注册不注销，无 `unregister()`**
测试 reset 场景无法摘除任务；`register` 靠 name 去重，但同名不同 order 的任务会被静默忽略（`register` 第 57-60 行直接 `return`），**配置错误被静默吞掉**。

**问题 C-15（中）：`AsyncPool` 静态初始化块注册关闭钩子 —— 类加载即副作用**

`AsyncPool` 的 `static {}` 块中调用 `ShutdownCoordinator.register(...)`。任何触碰 `AsyncPool` 的代码路径都会**隐式注册 JVM 钩子**，包括纯单测场景。测试隔离性受损，且初始化顺序不可控。

---

### D5 配置与多环境 —— 2.0 / 5 ❌

**问题 C-16（严重）：core 内部存在第三套配置机制**

项目中并行的配置来源已达三套：
1. **Serenity 体系** —— `ConfigSource.resolve()`：System 属性 → 环境变量 → `SystemEnvironmentVariables`
2. **Typesafe Config** —— api 模块的 `application.conf`
3. **`ASYNC_*` 独立环境变量** —— `AsyncPool` 的 `getEnvInt/getEnvLong/getEnvDouble`（451-488 行）

`AsyncPool` 绕开一切既有配置体系，直接 `System.getenv("ASYNC_CORE_THREADS")`。后果：
- 线程池参数**无法写进配置文件**，只能靠环境变量；
- CI 中不可审计（不在任何配置文件里，无法 code review）；
- 与 `ConfigSource` 的解密能力脱节（`ASYNC_*` 不支持 `ENC(...)`）。

**优点 C-17：`ConfigSource` 的分层解析顺序正确**

System 属性（最高）→ 环境变量 → Serenity 合并源 → 默认值，且每层都过 `SecretValue.decryptIfNeeded()`，**解密横切、不漏层**。环境变量映射 `toEnvKey()` 与 Serenity 规范对齐，考虑周到。

---

### D6 错误处理与可观测性 —— 3.5 / 5 ✅

**优点 C-18：`AsyncPool` 可观测性达到生产级雏形**

- 双级阈值告警（80% 告警 / 70% 预警）；
- 拒绝、超时、丢弃**分别计数**（`getRejectedCount` / `getTimeoutCount` / `getMonitorCallbackDroppedCount`）；
- `getStatusSnapshot()` 一行输出 13 项指标；
- `MONITOR_CALLBACK_EXECUTOR` 采用**有界队列（10,000）+ 丢弃计数**，注释明确论证了为何不用 `CallerRunsPolicy`（会阻塞 Playwright 事件线程导致整轮卡死）——**这是经过深思熟虑的设计决策，不是巧合**。

**问题 C-19（中）：指标无统一出口**

全部指标只能通过 `getStatusSnapshot()` 返回**字符串**，无 Micrometer / JMX / OpenTelemetry 接入，无法对接企业监控系统。

**问题 C-20（中）：告警用 `LOGGER.error` 表达**

队列超阈值、任务被丢弃等"系统健康度事件"与"真实错误"混用 `error` 级别，在日志告警系统（如 Splunk/ELK 按 ERROR 触发告警）中会造成**告警疲劳**，真正的问题被淹没。

---

### D7 安全与合规 —— 4.0 / 5 ✅ **本模块最强项**

**优点 C-21：`ConfigCipher` 采用 AES-256-GCM，未自造算法**

```java
private static final String TRANSFORMATION = "AES/GCM/NoPadding";
```
- GCM 模式提供**认证加密**（防篡改），优于常见的 CBC；
- 主密钥按序解析：环境变量 `CONFIG_MASTER_KEY` → 系统属性 → `user.home/.dbb_automation_master_key`；
- 文档明确警告"切勿把仅经 Base64 编码的明文当密文"——**安全认知到位**。

**优点 C-22：`SensitiveDataSanitizer`（927 行）脱敏覆盖面完整**

支持 header / body / query 三维，内置敏感键集合 + 可扩展的 `SensitiveValueRecognizer` SPI（`registerValueRecognizer`），配置可外部扩展（`sensitive.data.extra.*.keys`）。在 route 的 DB、File、Record、日志、reporting 的报告 5 处统一收口。

**问题 C-23（中）：主密钥文件无权限校验、无轮换机制**

`user.home/.dbb_automation_master_key` 固定路径，读取时**未校验文件权限**（如要求 600）。无密钥轮换（rotation）设计，无多环境隔离（dev/uat/prod 共用同一 `CONFIG_MASTER_KEY` 环境变量名）。

**问题 C-24（轻）：`.dbb_automation_master_key` 未加入 `.gitignore`**

虽实际路径在 `user.home`（不在仓库内，风险有限），但作为安全基线应在 `.gitignore` 中显式声明，防止开发误置于项目根。

---

### D8 可测试性与质量门禁 —— 3.0 / 5

**问题 C-25（严重）：`core/src/test` 完全为空**

19 个生产类、3,373 行代码，**零单元测试**。安全类（`ConfigCipher` / `SensitiveDataSanitizer`）这类"错了就是事故"的代码**没有任何测试保护**。

**问题 C-26（中）：全静态设计阻断可测试性**

`AsyncPool`（私有构造 + 全 static）、`ShutdownCoordinator`（私有构造 + 全 static）、`SensitiveDataSanitizer`（全 static + 静态可变状态）、`TestContextHolder`（static ThreadLocal）——**无任何注入 seam**。测试只能靠全局副作用与时序 hack，这也解释了为何测试全被推到 `test-automation` 模块。

---

## 三、问题清单

| ID | 级别 | 问题 | 证据 |
|---|---|---|---|
| C-1 | **P0** | core 编译期强耦合 Serenity，地基污染 | `ConfigSource.java:4`；`core/pom.xml` 0 依赖 |
| C-7 | **P0** | `ThreadLocalTestContext` 非线程安全假设脆弱，且不支持子线程继承 | `ThreadLocalTestContext.java:13`；`TestContextHolder.java:11` |
| C-8 | **P0** | `ShutdownCoordinator.runAll()` 一次性，reset 后关闭编排永久失效 | `ShutdownCoordinator.java:36,81` |
| C-16 | **P1** | `ASYNC_*` 第三套配置机制，不可审计 | `AsyncPool.java:106-112,451-488` |
| C-9 | **P1** | `AsyncPool` 静态单例关闭后不可重建 | `AsyncPool.java:54-55,76` |
| C-10 | **P1** | `CONTEXT_SCHEDULERS` 无弱引用兜底，泄漏无检测 | `AsyncPool.java:52,251-274` |
| C-25 | **P1** | `core/src/test` 为空，安全类零测试 | 目录实证 |
| C-14 | **P1** | `ShutdownCoordinator` 无 unregister，同名冲突静默忽略 | `ShutdownCoordinator.java:56-65` |
| C-11 | **P2** | `runWithTimeout` 占用调度线程至超时到期 | `AsyncPool.java:187-203` |
| C-5 | **P2** | `TestContextHolder` 过渡方案无收敛机制 | `TestContext.java:12-13` |
| C-2 | **P2** | `common` 垃圾桶包 + 三套顶层命名空间 | 包结构 |
| C-23 | **P2** | 主密钥文件无权限校验 / 无轮换 | `ConfigCipher.java:158-160` |
| C-19 | **P2** | 指标无标准监控出口 | `AsyncPool.java:399-408` |
| C-20 | **P2** | 健康度事件误用 ERROR 级别 | `AsyncPool.java:290,300` |
| C-24 | **P2** | 密钥文件名未进 `.gitignore` | `.gitignore` |

---

## 四、整改任务列表（core 模块）

### P0 —— 阻断级

| 任务 ID | 任务 | 验收标准 | 工时 |
|---|---|---|---:|
| **CORE-P0-1** | **切断 core 对 Serenity 的编译期依赖**：将 `ConfigSource` 的 Serenity 解析抽为 `ConfigResolver` 接口，core 仅保留接口 + 默认值实现；Serenity 实现下沉至 web 模块，通过 `ServiceLoader` 注入 | ① `core` 模块 `mvn dependency:tree` 中无 `serenity-*`；② `core` 可在无 Serenity classpath 下编译通过；③ 现有配置读取行为不变（回归测试通过） | 3d |
| **CORE-P0-2** | **修复上下文线程模型**：`ThreadLocalTestContext` 内部改用 `ConcurrentHashMap`；`TestContextHolder` 提供 `InheritableThreadLocal` 开关 + `capture()`/`restore()` 跨线程传递 API；补充"禁止新增 static ThreadLocal"的 ArchUnit 规则 | ① 子线程可继承/显式传递上下文；② 并发读写上下文的单测通过；③ ArchUnit 规则检出违规即失败 | 2d |
| **CORE-P0-3** | **修复 `ShutdownCoordinator` 不可复位**：`runAll()` 结束后复位 `running`；新增 `unregister(String name)`；同名不同 order 时抛异常而非静默忽略 | ① 同一 JVM 内 `runAll()` 可重复执行；② reset 后重新注册生效；③ 同名不同 order 触发明确报错 | 1d |

### P1 —— 重要

| 任务 ID | 任务 | 验收标准 | 工时 |
|---|---|---|---:|
| **CORE-P1-1** | **统一配置机制**：废弃 `ASYNC_*` 环境变量直读，线程池参数改由 `ConfigSource` 解析（支持配置文件 / 环境变量 / `ENC()` 解密） | ① `AsyncPool` 无 `System.getenv` 直读；② 参数可在 `serenity.conf` 配置；③ 向后兼容：既有 `ASYNC_*` 环境变量仍生效（作为 fallback） | 2d |
| **CORE-P1-2** | **`AsyncPool` 可重建**：改造为受管实例（保留 static 门面兼容），提供 `reset()` 用于测试环境；`MONITOR_CALLBACK_EXECUTOR` 支持惰性重建 | ① shutdown 后 `reset()` 可恢复功能；② 现有调用方零改动（门面签名不变） | 3d |
| **CORE-P1-3** | **防调度器泄漏**：`CONTEXT_SCHEDULERS` 改用 `WeakHashMap` 或引入引用计数；新增"活跃调度器数 > 阈值且持续增长"告警 | ① context 未显式 remove 时能被 GC 回收或触发告警；② 有泄漏检测单测 | 1d |
| **CORE-P1-4** | **补齐 core 单元测试**：优先覆盖 `ConfigCipher`（加解密往返、错误密钥、损坏密文）、`SensitiveDataSanitizer`（各维度脱敏、自定义识别器）、`ContextKey`/`TestContext`；目标行覆盖 ≥ 60% | ① `core/src/test` 存在 ≥ 15 个测试类；② JaCoCo 报告 core 行覆盖 ≥ 60%；③ 安全类覆盖 ≥ 85% | 5d |
| **CORE-P1-5** | **`ShutdownCoordinator` 补齐注销与诊断**：支持 `unregister`；提供 `getRegisteredTasks()` 供诊断；重复注册改为显式异常 | 见 CORE-P0-3 验收项 ②③ | 0.5d |

### P2 —— 优化

| 任务 ID | 任务 | 验收标准 | 工时 |
|---|---|---|---:|
| **CORE-P2-1** | 修复 `runWithTimeout`：任务完成时主动取消调度，避免占用 SCHEDULER 线程 | 调度任务数随任务完成即时下降 | 1d |
| **CORE-P2-2** | 包结构治理：`common` 拆为 `config / security / async / lifecycle`；`utils` 并入 `common.support` | 包重命名完成，无跨包循环 | 1d |
| **CORE-P2-3** | 主密钥增强：读取时校验文件权限（非 600 告警）；支持按环境隔离的密钥别名（`CONFIG_MASTER_KEY_<ENV>`）；文档化轮换流程 | 权限校验生效；轮换 SOP 入文档 | 1d |
| **CORE-P2-4** | 可观测性升级：健康度事件改用 `WARN` + 独立 logger category；指标接入 Micrometer（至少 `Gauge` 暴露队列/线程/丢弃计数） | 指标可被 Prometheus 抓取 | 2d |
| **CORE-P2-5** | `.gitignore` 增加 `.dbb_automation_master_key`；`SensitiveDataSanitizer` 全局状态改为实例 + 单例持有 | 无法误提交；状态可 reset | 1d |

---

## 五、给架构决策者的建议

`core` 的问题**不是设计能力不足，而是"过渡方案被长期固化"**：

- `TestContextHolder` 自述是"先收拢后删除"的过渡态 → 但没有删除的时间表与强制机制；
- 依赖全量继承自述是"拆分首版，后续收敛" → 但没有任何跟踪项；
- `ShutdownCoordinator` 注释承诺"应对 reset 场景" → 实现与之矛盾。

**建议**：为每一处"临时/过渡"设计建立**可追踪的收敛项**并纳入 CI 门禁，否则技术债务会以"文档里写清楚了"的方式永久沉淀。当前 core 的技术债务形态，正是三个"本该临时"的设计叠加的结果。
