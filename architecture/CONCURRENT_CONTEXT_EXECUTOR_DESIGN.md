# 并发上下文执行器（方案 C2）设计方案

> 依据：`ARCHITECTURE_REMEDIATION_PLAN.md` 第 1.1 节成功度量——Phase 3「并行执行 4 workers / 回归总时长 ≤ 0.4T」
> 版本：v1.0（待评审）　编制日期：2026-09-04
> 状态：**企业级升级设计中（2026-09-07，结合 serenity-core 原语调研）**：原 C2 + SSO 附录 A 已就绪；现补齐「Serenity 线程绑定桥接 / 执行引擎选型 / 共享 Browser 韧性兜底 / 可观测性」四大企业级维度，目标升级为可落地方案（见第九节）。前置能力「共享 Browser 模式」已实现并验证通过。
> 前置能力：共享 Browser 模式（「一个 Browser + 多 Context」）已实现并验证通过

---

## 一、背景与问题

### 1.1 目标

在「一个 Browser 实例 + 多个 BrowserContext」模型下，提供**框架级**并发执行能力，使多个任务真正并发地各自持有独立浏览器上下文，且互不污染。

成功判据（可度量）：

| 判据 | 验证方式 |
|---|---|
| 所有并发任务共享同一个 Browser 实例 | 各任务 `browserIdentity` 相同 |
| 每个任务持有独立 BrowserContext | 各任务 `contextIdentity` 互不相同 |
| 上下文之间状态隔离 | 各任务页面内计数器累加结果互不干扰 |
| 任务结束后无 ThreadLocal 残留 | 复用同一线程池跑两轮，第二轮结果与第一轮一致 |

### 1.2 现状调研结论：Serenity 无 JVM 内并行能力

调研对象：`D:\IdeaProject\serenity-core`（Serenity BDD 官方源码）。

| 调研项 | 结论 | 证据 |
|---|---|---|
| batch 机制语义 | **跨 JVM / CI 分片**，非 JVM 内多线程 | `SystemVariableBasedBatchManager.shouldExecuteThisTest()`：`testCaseCount % batchCount == batchNumber`，即「本批只执行属于本批序号的用例」 |
| 分片策略 | 仅 `DIVIDE_EQUALLY` / `DIVIDE_BY_TEST_COUNT` | `BatchStrategy` 枚举 |
| 并行开关 | **不存在** | `serenity-model` 的 SystemProperty 枚举中检索 `PARALLEL(` → 0 命中 |
| 并行执行器 | **不存在** | `serenity-core` 主源码检索 `parallel` → 仅 `AppiumDevicePool` 命中（与测试执行无关） |
| Cucumber 集成 | 不在本仓库 | 模块清单中无 `serenity-cucumber` |

**关键推论**：此前尝试的 `serenity.parallel.for.tests=4` 在 Serenity 中**并不存在**，因此 5 个场景全部串行执行于 `threadId=1, threadName=main`，与实测日志完全吻合。

> **结论：并发必须由框架自建，不能依赖 Serenity 驱动。**

### 1.3 已完成的前置能力

共享 Browser 模式已落地并验证（开关默认关闭）：

- `FrameworkConfig.PLAYWRIGHT_SHARED_BROWSER_ENABLED`（`serenity.playwright.shared.browser.enabled`，默认 `false`）
- `PlaywrightManager.keyFor()` 共享模式下去掉 threadId 维度，所有线程命中同一实例
- `SHARED_BROWSER_LOCK` 进程级锁（原 `BROWSER_LOCK` 为 ThreadLocal，在共享模式下无法跨线程互斥）
- `restartBrowser()` 在共享模式下降级为 `restartContextOnly()`，绝不关闭共享 Browser
- 专属单测 10 例 + 全量护盾 255 例通过

---

## 二、方案总览

### 2.1 并发模型

```
                    ┌─ 工作线程-1 → BrowserContext-A（独立 cookie / storage / 会话）
共享 Browser ───────┼─ 工作线程-2 → BrowserContext-B
  （单进程）        ├─ 工作线程-3 → BrowserContext-C
                    └─ 工作线程-N → BrowserContext-N
```

`PlaywrightManager.getContext()` 基于 `ThreadLocal<BrowserContext>`，因此每个工作线程天然获得独立 Context；隔离性由 Playwright 的 BrowserContext 保证（cookie / localStorage / session 彼此独立），与独立 Browser 等价。

### 2.2 与既有 T3-2 设计的关系

| 维度 | T3-2（每线程独立 Browser，默认） | 本方案（共享 Browser，可选） |
|---|---|---|
| 进程数 | N 个 Browser 进程 | 1 个 Browser 进程 |
| 隔离粒度 | Browser 级 | Context 级（等价隔离） |
| 故障爆炸半径 | 单 Browser 崩溃只影响本线程 | Browser 崩溃影响**全部**并发任务 |
| 启动开销 | 高（每线程一次进程启动） | 低（一次进程 + N 次 Context 创建） |
| 适用 | 稳定性优先 | 资源/启动速度优先 |

两者通过开关共存，**默认仍为 T3-2**，本方案不改变既有行为。

### 2.3 为何不选其它方案

| 方案 | 做法 | 否决原因 |
|---|---|---|
| A｜Serenity batch 分片 | `batch.count=N` 多 JVM | 每 JVM 一个 Browser → 变成 N 个 Browser，与目标相反 |
| B｜Cucumber 场景级并行 | `cucumber-jvm-parallel-plugin` 生成 per-scenario Runner | 构建期生成代码，与 Serenity 集成脆弱，且污染 `target/` |

---

## 三、详细设计

### 3.1 包与类的归属

**放置在 `com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle` 包内（与 `PlaywrightManager` 同包）**，而非新建 `concurrent` 子包。

理由（对应企业级「清晰的 API 边界」约束）：

1. 本执行器的本质是**编排 Browser / BrowserContext 的生命周期**，属生命周期关注点；
2. 需要访问包级私有的 `isSharedBrowserMode()`（带 `@apiNote` 标注为框架内部能力，不对外公开）。放入子包将迫使该能力重新提权为 `public`，破坏已建立的封装边界。

### 3.2 核心 API（示意）

```java
/** 并发上下文执行器：在共享 Browser 上为各任务分配独立 Context 并并发执行。 */
public final class ConcurrentContextExecutor {

    private ConcurrentContextExecutor() { /* 静态工具，禁止实例化 */ }

    /** 并发执行全部任务，返回与入参顺序一致的结果列表（不抛异常，失败体现在结果中）。 */
    public static <T> List<ContextTaskResult<T>> runAll(
            List<ContextTask<T>> tasks,
            ConcurrentContextOptions options);
}

/** 带名称的任务（名称用于日志与失败定位）。 */
public interface ContextTask<T> {
    String name();
    T call() throws Exception;
}

/** 单任务执行结果：成功值 / 失败原因 / 线程名 / 耗时 / 浏览器身份快照。 */
public final class ContextTaskResult<T> {
    public boolean isSuccess();
    public T value();                       // 失败时为 null
    public Optional<Throwable> failure();
    public String taskName();
    public String threadName();
    public long durationMs();
    public T valueOrThrow();                // 失败时抛 CompletionException，便于断言
}

/** 执行选项（Builder 构造，含入参校验）。 */
public final class ConcurrentContextOptions {
    int parallelism();          // 并发度
    long perTaskTimeoutMs();    // 单任务超时
    boolean failFast();         // true=首个失败即取消其余；false=跑完再统一断言（默认）
}
```

### 3.3 ThreadLocal 治理（本方案最关键的设计点）

框架用 `contextThreadLocal` / `pageThreadLocal` / `currentConfigId` 等 ThreadLocal 承载状态。**使用线程池时线程会被复用，若任务结束不清 ThreadLocal，下一个任务会继承上一个任务的 Context——这正是本方案要证明不存在的串扰。**

治理规则：

1. 清理必须在**工作线程自身**的 `finally` 中执行，不能由调用方线程代劳（ThreadLocal 是线程私有的）；
2. 复用框架既有收口 `PlaywrightManager.cleanupForScenario()`，而不是零散地 `remove()`：

```java
// 工作线程内
try {
    return task.call();
} finally {
    // 既有收口：关闭本线程 page/context + ContextLifecycleHookManager 快照
    //          + CustomOptionsManager.removeAllThreadLocals() + page/context ThreadLocal 清理
    // 该方法只关 Context，不关共享 Browser → 与共享模式天然兼容
    PlaywrightManager.cleanupForScenario();
}
```

3. 执行器**不得**关闭共享 Browser（Browser 生命周期归框架所有，由 `cleanupAll()` 收口）。

### 3.4 执行流程

```
runAll(tasks, options)
  ├─ 入参校验（null / 空列表 / parallelism<1 / 非法超时）→ IllegalArgumentException
  ├─ 计算有效并发度：min(tasks.size(), parallelism, 配置上限)
  ├─ 创建有界线程池（自定义 ThreadFactory，线程名含 executor 前缀便于日志追踪）
  ├─ 提交全部任务 → Future 列表
  ├─ 逐个 future.get(perTaskTimeout)：
  │     ├─ 正常返回    → 成功结果
  │     ├─ 执行异常    → 失败结果（记录 cause）
  │     └─ 超时        → cancel(true) + 失败结果（TimeoutException）
  ├─ failFast=true 时首个失败即取消剩余任务
  └─ finally：shutdownNow() + 等待终止（有界超时）+ 汇总日志
```

### 3.5 错误处理

| 场景 | 处理 |
|---|---|
| 入参非法 | 抛 `IllegalArgumentException`（语义化消息，非裸 NPE） |
| 单任务抛异常 | 捕获进 `ContextTaskResult.failure()`，**不影响其它任务** |
| 单任务超时 | `cancel(true)` 并记录 `TimeoutException`；清理仍由任务内 `finally` 保证 |
| 线程池被中断 | 传播为 `BrowserException`，保留中断标志 |

> **已知限制（须在 Javadoc 明示）**：`cancel(true)` 无法强制中断阻塞在 Playwright 原生调用中的线程，单任务超时为 **best-effort 兜底**；真正的耗时上限依赖框架既有的 navigation / element 超时配置。

### 3.6 配置

新增 `FrameworkConfig` 常量（沿用既有「key / 默认值 / 描述」三段式）：

| 常量 | key | 默认值 | 说明 |
|---|---|---|---|
| `PLAYWRIGHT_CONCURRENT_PARALLELISM` | `serenity.playwright.concurrent.parallelism` | `0`（=自动：`min(任务数, 可用核数)`） | 并发度，硬上限 16 |
| `PLAYWRIGHT_CONCURRENT_TASK_TIMEOUT_SECONDS` | `serenity.playwright.concurrent.task.timeout.seconds` | `300` | 单任务超时 |

解析沿用共享 Browser 模式的既有范式：**静态常量一次性解析 + 纯函数解析**（保证运行期不可变、且可单测）。

### 3.7 可观测性

- 每任务日志：`taskName / threadName / browserIdentity / openContexts / durationMs / 成功或失败`
- 汇总日志：总耗时、成功数、失败数、并发度、是否共享 Browser 模式
- 日志统一走 `PlaywrightManager` 自身 logger，保持生产溯源一致

---

## 四、企业级约束对照

| 约束 | 落实方式 |
|---|---|
| 线程安全 / 并发可见性 | 并发度静态解析、有界线程池、`ConcurrentHashMap` 结果收集、清理在工作线程内 |
| 清晰 API 边界 | 执行器置于 `lifecycle` 同包；内部能力保持包级私有 + `@apiNote`，不向业务 Page 外泄 |
| 可观测性 | 每任务 + 汇总两级日志，沿用框架既有 logger |
| 健壮错误处理 | 入参校验抛语义化异常；单任务失败隔离；超时兜底 |
| 单测 / 全护盾 | 专属单测 + 多线程 IT + 全量护盾（见第五节） |

---

## 五、测试策略

| 层级 | 内容 | 是否启真浏览器 |
|---|---|---|
| 单测 `ConcurrentContextExecutorTest` | 入参校验、并发度上限、结果聚合与顺序、异常捕获、超时、failFast 语义、ThreadLocal 清理（复用线程池跑两轮结果一致） | 否 |
| IT `ConcurrentContextIT` | N 个任务打开沙箱页各自累加计数器；断言：共享模式→`browserIdentity` 全相同且 Context 全不同；非共享模式→Browser 全不同；各任务计数互不污染 | 是 |
| 全护盾 | `mvn -o -pl test-automation -am test` | — |
| E2E 回归 | `CucumberE2ESandboxRunnerIT` | 是 |

> IT 断言按模式分支，使同一套用例在两种并发模型下都具备验证价值。

---

## 六、落地步骤（待启动）

1. `FrameworkConfig` 新增 2 个配置项（含纯函数解析）
2. `lifecycle` 包新增 `ConcurrentContextExecutor` / `ContextTask` / `ContextTaskResult` / `ConcurrentContextOptions`
3. 专属单测（预计 ≥ 12 例）
4. 多线程 IT 验收
5. 全量护盾 + E2E 沙箱回归
6. 补充 `README` / 配置示例

预估工作量：约 3～5 人日（不含第六节风险第 2 项的梯度压测）。

---

## 七、风险与对策

| # | 风险 | 对策 |
|---|---|---|
| 1 | **共享 Browser 是单点故障**：进程崩溃影响全部并发任务（T3-2 当初正是为此选的每线程独立 Browser） | 并发度硬上限；`getBrowser()` 已有 `isConnected` 双重检查；可选「崩溃自动重建 Browser 并重跑失败任务」 |
| 2 | 单 Browser 可承载的并发 Context 数未验证 | 实施时做 4 → 8 → 16 梯度压测，据此调整硬上限 |
| 3 | 超时无法强杀卡在原生调用中的任务 | 依赖框架既有 navigation / element 超时；单任务超时仅作兜底（Javadoc 明示） |
| 4 | 共享模式下 `handleBrowserTypeSwitch` 会关闭共享 Browser（切类型必然换 Browser），并发中发生会波及所有任务 | 共享模式下禁止运行期切换浏览器类型，fail-fast 抛 `BrowserException` |
| 5 | 线程池复用导致 ThreadLocal 泄漏 | 工作线程 `finally` 内调用 `PlaywrightManager.cleanupForScenario()`（见 3.3） |

---

## 八、待决策项

1. **是否按本方案实施**（用户决策 2026-09-07 更新：由「后置/不紧急」升级为「企业级升级设计中」；共享 Browser 模式已满足需求，但新增 Serenity 线程绑定桥接后可落地为正式能力，见第九节）
2. 并发度硬上限初值取多少（建议 8，待压测校准）
3. 风险 1 的「共享 Browser 单点」是否接受；是否需要「崩溃自动重建」兜底
4. 风险 4 的浏览器类型切换：fail-fast 还是维持现状
5. ~~是否需要同步修复 `SummaryReportGenerator` 的统计口径 bug~~ **已撤回**：实测为跨轮次结果在 `target/site/serenity` 累积所致，非代码缺陷

---

## 附录 A：SSO 感知并发（按身份分区互斥）

> 状态：**仅存档设计，本轮不落地代码**（用户决策 2026-09-07）。待后续集成 JUnit 5（`cucumber-junit-platform-engine`）时与本方案 C2 一并实施。
> 前置共识：方案 C2（第三节）已解决 *Browser / Context* 级隔离，但其假设"各并发任务的登录身份互不冲突"。本附录在更高一层补充**身份维度互斥**约束，与 C2 正交、可叠加。

### A.1 背景与动机

方案 C2 的并发隔离建立在"每个任务持有独立 BrowserContext"之上。但 SSO（单点登录）场景下，**身份**才是真正的共享资源：

- 同一 `(环境, 用户名)` 的两个 scenario 若并发登录，IdP 通常会**拒绝第二次并发登录**或使两者**互相覆盖 SSO 会话**；
- 即便登录被 `LoginGuard` 单飞收口（同 `sessionKey` 只登一次、复用会话），两 scenario 仍会**并发执行步骤、争用同一 SSO 会话** → 状态串扰。

因此并发判据需升级为：**相同身份 → 串行；不同身份 → 并行**。这是比 Context 隔离更高一层的约束，无法仅靠 Context 隔离解决。

### A.2 核心抽象

```
┌─ 同 (env,username) 的两 scenario ──┐  ConcurrencyGate 按 key 互斥（串行）
│  scenario-A ── acquire(key)        │
│  scenario-B ── acquire(key) 阻塞   │
└────────────────────────────────────┘
   不同 (env,username) 的 scenario ──→ 各自 key 独立 → 并行执行
   无登录的只读/API scenario ──→ key==null → 直接放行（不参与互斥）
```

| 类 / 接口 | 职责 | 包归属 |
|---|---|---|
| `ConcurrencyPartitionKey` | 不可变值对象，指纹化身份维度 `{environment, username, tenant?, role?, locale?}`；`equals/hashCode` 基于规范化后的维度元组 | `framework-core`（并发域顶层或 `common.concurrent`） |
| `ConcurrencyGate` | **runner 无关、串行安全**的互斥闸门：`ConcurrentHashMap<ConcurrencyPartitionKey, Semaphore> gates`；`acquire(key)` / `release(key)`；`key==null` 直接放行 | `framework-core` |
| `ConcurrencyKeyResolver` | 接口：返回 `Optional<ConcurrencyPartitionKey>`，供不同身份来源策略实现 | `framework-core` |

**`ConcurrencyGate` 语义要点（企业级约束）**：

```java
public final class ConcurrencyGate {
    private static final ConcurrentHashMap<ConcurrencyPartitionKey, Semaphore> GATES
            = new ConcurrentHashMap<>();

    /** 进入 scenario 时调用；key==null 直接返回（无身份场景不参与互斥）。 */
    public static void acquire(@Nullable ConcurrencyPartitionKey key) {
        if (key == null) return;                       // 非 SSO / 只读场景：零约束
        GATES.computeIfAbsent(key, k -> new Semaphore(perKeyPermits(k), true))
             .acquireUninterruptibly();                // per-key permits 默认 1（互斥）
    }

    /** scenario 结束（含失败）时必须配对调用；key==null 直接返回。 */
    public static void release(@Nullable ConcurrencyPartitionKey key) {
        if (key == null) return;
        Semaphore s = GATES.get(key);
        if (s != null) s.release();
    }
}
```

- `ConcurrentHashMap` 不允许 null key —— 已用 `if (key == null) return` 规避（无身份场景根本不进 Map）；
- `acquireUninterruptibly()` 避免 `InterruptedException` 被吞或打断测试线程的中断策略；release 必须放在框架既有清理收口的 `finally` 中，确保与 acquire 配对；
- `perKeyPermits(key)` 默认 1（互斥），可配置为 N（某些环境允许同用户 N 个并发会话）。

### A.3 身份来源（"如何告诉框架"）

业务 scenario **无需逐个声明**——登录已收口在 `SessionManager` / `LoginGuard`，框架可自动推导：

| 来源 | 机制 | 说明 |
|---|---|---|
| **自动推导（默认）** | `LoginIdentityKeyResolver` | 从当前 `FrameworkConfig` 解析出的 `environment` + 实际登录的 `username` 构成 key；基于 `LoginGuard`/`SessionManager` 的 `sessionKey` 归一化。业务零侵入 |
| **显式覆盖** | `TagOverrideKeyResolver` | 特殊 scenario 打 tag `@sso=UAT:alice` 或 `@concurrencyKey=env:user:tenant` 覆盖自动推导 |

- 解析器可**链式**：先跑 `TagOverrideKeyResolver`（命中即返回），未命中回退 `LoginIdentityKeyResolver`；
- 维度集合（`environment/username/tenant/role/locale`）可配置，决定"什么叫同一个身份"。

### A.4 与既有机制的分工（关键）

| 维度 | 由谁负责 | 说明 |
|---|---|---|
| 登录单飞 | `LoginGuard`（**已有**） | 同 `sessionKey` 只登一次、复用会话 |
| **整 scenario 按身份互斥** | `ConcurrencyGate`（本附录新增） | 把单飞升级为"整段执行互斥"，杜绝同身份并发抢 SSO 会话 |
| 跨环境并行 | Serenity 跨 JVM `batch` | 每 worker = 一环境，天然按环境隔离（方案 C2 第二节已确认 Serenity 无 JVM 内并行） |
| 同环境跨用户名并行 | `ConcurrencyGate`（JUnit 5 开通后） | 在 JVM 内按 key 各自独立 → 并行 |
| 串行模式（当前） | 闸门恒为 no-op | 全部 scenario 顺序执行，行为零回归 |

> 结论：`ConcurrencyGate` 与 `LoginGuard` 互补而非替代——前者约束"执行并发度"，后者约束"登录次数"。二者叠加完整覆盖 SSO 场景。

### A.5 接入点（runner 无关）

- **`acquire`**：在框架既有 `@Before`（建立登录的生命周期 hook，即 `LoginGuard` 单飞登录之后）调用，key 由 `ConcurrencyKeyResolver` 链解析；
- **`release`**：在 `@After` / 框架清理收口（`PlaywrightManager.cleanupForScenario()` 同款 seam）的 `finally` 中调用，确保 scenario 成功/失败都释放；
- 当前 key 经 `TestContext` seam 持有，使 `release` 与 `acquire` 严格配对（即使 scenario 抛异常也不泄漏信号量）。

### A.6 配置（沿用 FrameworkConfig 三段式）

| 常量 | key | 默认值 | 说明 |
|---|---|---|---|
| `CONCURRENCY_PARTITION_ENABLED` | `serenity.playwright.concurrent.partition.enabled` | `false`（JUnit 5 前恒 no-op） | 是否启用按身份互斥；未启用时 `acquire` 直接返回 |
| `CONCURRENCY_PARTITION_PER_KEY_PERMITS` | `serenity.playwright.concurrent.partition.per.key.permits` | `1` | 每身份并发许可；>1 表示允许同身份 N 路并发 |
| `CONCURRENCY_PARTITION_DIMENSIONS` | `serenity.playwright.concurrent.partition.dimensions` | `environment,username` | 参与分区键的维度集合 |

### A.7 企业级约束对照

| 约束 | 落实方式 |
|---|---|
| 线程安全 / 并发可见性 | `ConcurrentHashMap` 持有 per-key `Semaphore`；`acquireUninterruptibly`；release 在 `finally` 配对 |
| 清晰 API 边界 | 闸门置于 `framework-core` 并发域；`ConcurrencyGate` 为 `public final` 静态门面，`ConcurrencyKeyResolver` 实现为可被业务安全扩展的 SPI 式接口；**不向业务 Page 泄漏** 内部结构 |
| 可观测性 | acquire/release 记 `key` + 线程名 + 是否阻塞；汇总日志含"被互斥串行化的身份数" |
| 健壮错误处理 | `key==null` 安全跳过；resolver 解析失败抛语义化异常（非空 NPE）；`finally` 保证释放不泄漏 |
| 单测 / 全护盾 | `ConcurrencyGateTest` 固化（见 A.8）；实施后跑全护盾 |

### A.8 测试策略

| 层级 | 内容 |
|---|---|
| 单测 `ConcurrencyGateTest` | ① 同 key 两线程互斥（一持有时另一阻塞）；② release 后另一线程获得；③ `key==null` 直接放行、不进 Map；④ 不同 key 互不阻塞（独立并行）；⑤ key 等价性（`environment+username` 规范化后相等即同 key）；⑥ `TagOverrideKeyResolver` 覆盖自动推导；⑦ resolver 链回退语义；⑧ per-key permits=N 时允许 N 路并发 |
| 全护盾 | `mvn -o -pl test-automation -am test`（闸门默认关闭，行为零回归） |
| IT（随 JUnit 5） | 同 `(env,user)` 两 scenario 并发 → 断言其一阻塞至另一释放；不同身份 → 断言重叠执行 |

### A.9 落地步骤（待启动，随 JUnit 5 迁移一并实施）

1. `framework-core` 新增 `ConcurrencyPartitionKey` / `ConcurrencyGate` / `ConcurrencyKeyResolver` + `LoginIdentityKeyResolver` / `TagOverrideKeyResolver`；
2. 生命周期 hook（`@Before`/`@After` 收口）接入 `acquire` / `release`（经 `TestContext` 持有 key）；
3. `FrameworkConfig` 新增 3 个配置项（含纯函数解析）；
4. `ConcurrencyGateTest`（≥ 8 例）；
5. 全护盾 + 后续 JUnit 5 IT 验收。

### A.10 风险与对策

| # | 风险 | 对策 |
|---|---|---|
| 1 | 身份维度在 scenario 中途变更（env/username 运行期改写） | resolver 在 `@Before` 固定快照 key，禁止中途变更；变更需新开 scenario |
| 2 | 忘记 release → 信号量泄漏、后续同身份 scenario 永阻塞 | release 置于框架既有清理 `finally` 收口，与 acquire 严格配对；per-key 仅 1 permit 不会自我死锁 |
| 3 | 海量 key 导致 `GATES` Map 无界增长 | 加 size 上限 + LRU/弱引用淘汰；或按 key 维度上限仅缓存"活跃"信号量，空闲即清理 |
| 4 | 与 JUnit 5 集成耦合 | 闸门 runner 无关，仅在 JUnit 5（JVM 内并行）生效；Serenity 跨 JVM `batch` 按环境天然隔离，无需闸门 |
| 5 | `ConcurrentHashMap` 不允许 null key | 已用 `if (key == null) return` 规避（无身份场景根本不进 Map） |

---

## 九、企业级升级设计（结合 serenity-core 原语调研，2026-09-07）

> 本章把 C2 + SSO 附录从「存档设计」升级为「可落地企业级方案」。核心增量来自对 `D:\IdeaProject\serenity-core` 真实并发原语的调研，以及对我方框架 `StepEventBus` / `TestContextHolder` / `ApiCaptureContext` 实际依赖的核查。

### 9.1 调研结论：Serenity 的并发原语（源码实证）

| 原语 | 位置（serenity-core） | 语义 | 对本方案的含义 |
|---|---|---|---|
| `StepEventBus` | `net.thucydides.core.steps.StepEventBus.java` | `stepEventBusThreadLocal`（`ThreadLocal<StepEventBus>`）+ `STICKY_EVENT_BUSES`（`ConcurrentMap<Object,StepEventBus>`）；`getEventBus()` 按线程惰性新建；`setCurrentBusToEventBusFor(key)` / `eventBusFor(key)` 把任意线程切到 key 对应的粘性总线 | **报告 / 断言严格绑定到 runner 线程**；worker 线程若直接 `getEventBus()` 会拿到无监听器的全新总线 |
| `BatchManager` | `net.thucydides.core.batches.SystemVariableBasedBatchManager.java` | `shouldExecuteThisTest()`：`testCaseCount % batchCount == batchNumber` → **跨 JVM / CI 分片**，非 JVM 内多线程 | 跨环境并行只能靠多 JVM；JVM 内的同环境并行必须由本方案自建 |
| WebDriver 线程绑定 | `net.thucydides.core.webdriver.WebdriverProxyFactory.java` / `SerenityWebdriverManager.java` | WebDriver 经 `ThreadLocal` 按线程持有 | 与我们的 `PlaywrightManager` `contextThreadLocal` 同构，可作为 Context 隔离的参照实现 |

**关键推论（与 1.2 一致但更精确）**：Serenity 没有任何 JVM 内并行执行器；其 `StepEventBus` 与我们的 `TestContextHolder` 均为 `ThreadLocal`，因此**在自定义线程池中运行的任务若直接调用 `StepEventBus.getEventBus()` / `TestContextHolder.get()`，会落到 worker 线程私有的、与编排测试线程隔离的状态**——这正是 C2 当初未覆盖、却决定方案成败的缺口。

### 9.2 当前 C2 的关键缺口（企业级必须补齐）

| # | 缺口 | 实证（我方代码） | 后果 |
|---|---|---|---|
| G1 | `StepEventBus` 串扰 | `StepFailureAggregator.checkAndFailOnApiAssertions()` line 126、`checkAndFailOnPageErrors()` line 155 调 `StepEventBus.getEventBus().testFailed(...)`；`PlaywrightListener` line 355/436/478/689 调 `stepFinished(...)` | worker 线程拿到无监听器的全新总线 → 失败不标红、步骤不进报告 |
| G2 | `TestContextHolder` 串扰 | `core/.../context/TestContextHolder.java`（`ThreadLocal<TestContext>`）；`PageEventMonitor` 经它收集页面错误，`PlaywrightListener.checkAndFailOnPageErrors()` 在 test 线程 `drainPendingPageErrors()` | worker 线程的页面错误滞留在 worker `TestContext`，test 线程 drain 为空 → 页面错误漏报 |
| G3 | `ApiCaptureContext` 全局静态态 | `route/.../core/ApiCaptureContext` 以 `static` Map 收口 API 抓包；并发任务各自 `page.onResponse` 同时写全局态 | 并发写入需 `ConcurrentHashMap` / 同步保障，否则计数漂移或 `ConcurrentModificationException` |

### 9.3 桥接策略（核心决策）

**原则：Serenity 集成留在编排线程；worker 线程仅产出结构化结果，不直接触碰 `StepEventBus` / `TestContextHolder`。**

```
编排线程（JUnit/Cucumber runner 线程）
  ├─ 持有正确 StepEventBus（已注册 ThucydidesStepsListenerAdapter）+ TestContext
  ├─ 调 ConcurrentContextExecutor.runAll(tasks, options)
  │     └─ 工作线程-N：执行 ContextTask，仅与 BrowserContext/Page 交互
  │           ├─ 失败 / 页面错误 → 汇聚进 ContextTaskResult（failure / diagnostics）
  │           └─ finally：PlaywrightManager.cleanupForScenario() + TestContextHolder.resetForCurrentThread()
  └─ runAll 返回后：编排线程对每个失败结果回放既有失败路径
        （在 test 线程上经正确总线 testFailed）→ Serenity 报告 / IDE 标红零回归
```

- **失败回放**：编排线程在 `runAll` 后遍历 `ContextTaskResult`，对失败项调用既有 `StepFailureAggregator` 等价逻辑（在 test 线程 `getEventBus()` 上 `testFailed`）——复用现有 Serenity 标记通道，零新增报告代码。
- **页面错误汇聚**：并发任务内 `playwright.page.error.failOnError` 不自动标红；worker 线程 `drainPendingPageErrors()` 后随 `ContextTaskResult` 回传，由编排线程统一 `drain` + 标记。
- **`ApiCaptureContext` 加固（G3）**：将全局静态 Map 收敛为 `ConcurrentHashMap` 或加锁；若需按任务隔离，可在 `ContextTaskResult` 中携带 `ApiCall` 快照，避免跨任务共享全局态。

### 9.4 备选：per-task 粘性总线（任务级可观测性增强，默认不采用）

若未来需要"每个并发任务在 Serenity 报告中独立成段"，可模仿 serenity-core：

```java
// 编排线程：先把本测试总线注册进粘性表（key 唯一）
StepEventBus.setCurrentBusToEventBusFor(taskKey);   // 仅设置线程局部；不会携带已注册监听器
// 工作线程：切到同一粘性总线，并注册与 test 线程相同监听器集合
StepEventBus.setCurrentBusToEventBusFor(taskKey);
StepEventBus.getEventBus().registerListener(/* ThucydidesStepsListenerAdapter 等 */);
```

> 注意：`setCurrentBusToEventBusFor` 仅切换线程局部总线、**不迁移已注册监听器**；若采用此路径，必须在每个 task 的总线上重新注册与 test 线程一致的监听器集合，否则报告缺失。复杂度高，**默认不采用**，列为"任务级可观测性"增强候选项（待评估）。

### 9.5 执行引擎选型

| 引擎 | 适用 | 结论 |
|---|---|---|
| 有界固定线程池（`ThreadPoolExecutor` + 自定义 `ThreadFactory`，线程名 `dbb-ctx-N`） | 通用、可控 | **缺省**；并发度 `min(tasks, parallelism, 硬上限)`；`finally` 内 `shutdownNow()` + 有界 `awaitTermination` |
| 虚拟线程（`Executors.newVirtualThreadPerTaskExecutor()`，JDK 21+） | I/O 密集（Playwright 导航/等待阻塞 carrier 线程） | **增强备选**：轻量、无池上限焦虑；需先审计 `BasePage` 同步 API 与 `synchronized`/native 锁的 carrier 线程 pinning 风险，确认无 `Object.wait` 长持锁 |
| `ForkJoinPool.commonPool()` | — | **不采用**：被框架其它处共享，难以隔离、命名与超时控制 |

### 9.6 韧性兜底

- **共享 Browser 单点（风险 1 升级）**：`getBrowser()` 既有 `isConnected` 双重检查；新增 `BrowserCrashGuard`——检测到断开后在进程级锁内重建 Browser，并对失败任务做 **replay**（带身份亲和：同 `ConcurrencyPartitionKey` 任务优先复用同一重建后 Context）。
- **单任务超时**：`future.get(perTaskTimeout)` 超时 `cancel(true)` 兜底；真实耗时上限仍依赖框架既有 navigation / element 超时（G1 已在 3.5 明示为 best-effort）。

### 9.7 可观测性

- 每任务：`MDC.put("taskName" / "browserIdentity")` 便于日志追踪；记 `threadName / durationMs / openContexts / 成功或失败`。
- 汇总：总耗时、成功数、失败数、并发度、是否共享 Browser 模式、被 SSO 闸门串行化的身份数。
- 可选 `Micrometer` `Timer`/`Gauge`（SPI 式可选依赖，不强制引入）。

### 9.8 企业级约束对照（补充）

| 约束 | 落实方式（增量） |
|---|---|
| 线程安全 / 并发可见性 | G1/G2 桥接（Serenity 状态留在编排线程）；G3 `ApiCaptureContext` 静态态 `ConcurrentHashMap` 化；`ConcurrentHashMap` 结果收集 |
| 清晰 API 边界 | 桥接逻辑收口于 `ConcurrentContextExecutor`（lifecycle 同包）+ 新增 `SerenityBusBridge` / `TestContextBridge` 包级私有协作类；不向业务 Page 外泄 |
| 可观测性 | MDC 线程命名 + 每任务/汇总两级日志 + 可选 Micrometer |
| 健壮错误处理 | 入参校验抛 `IllegalArgumentException`；单任务失败隔离进 `ContextTaskResult`；超时兜底；`finally` 配对清理不泄漏 |
| 单测 / 全护盾 | 专属单测 + Serenity 桥接单测（断言 test 线程总线被正确标记）+ 多线程 IT + 全护盾 |

### 9.9 更新风险与对策

| # | 风险 | 对策 |
|---|---|---|
| G1 | `StepEventBus` 串扰（worker 总线无监听器） | 报告/断言只在编排线程回放（9.3）；per-task 粘性总线仅作备选（9.4） |
| G2 | `TestContextHolder` 串扰（页面错误滞留在 worker） | 并发任务内禁自动标红，错误随 `ContextTaskResult` 回传编排线程统一 drain（9.3） |
| G3 | `ApiCaptureContext` 全局静态态并发不安全 | 收敛为 `ConcurrentHashMap` 或按任务快照隔离（9.3） |
| R2 | 单 Browser 可承载并发 Context 数未验证 | 4→8→16 梯度压测校准硬上限 |
| R6 | 虚拟线程 pinning（若采用 9.5 增强） | 先审计 `BasePage` 同步 API 与长持锁，再启用开关 |
| R7 | 共享 Browser 崩溃波及全部任务 | `BrowserCrashGuard` 重建 + 失败任务 replay（9.6） |

### 9.10 落地步骤（企业级，待启动）

1. `FrameworkConfig` 新增配置项：`parallelism` / `task.timeout.seconds` / `virtual.thread.enabled` / `bridge.mode`（沿用三段式纯函数解析）。
2. `lifecycle` 包：`ConcurrentContextExecutor` + `ContextTask` / `ContextTaskResult` / `ConcurrentContextOptions`（第三节）。
3. 桥接协作类（包级私有）：`SerenityBusBridge`（编排线程→worker 结果回放）、`TestContextBridge`（worker→编排 页面错误汇聚）。
4. `ApiCaptureContext` 并发安全审计与加固（`ConcurrentHashMap` / 同步）。
5. 单测 ≥ 12 例 + Serenity 桥接单测（断言 test 线程 `StepEventBus` 被正确 `testFailed`、页面错误被汇聚）+ 多线程 IT（`api/core/web/route/reporting` 跨会话保行为）+ 全护盾 + E2E 沙箱回归。
6. 补充 `README` / 配置示例（含 SSO 闸门 `CONCURRENCY_PARTITION_*`）。
