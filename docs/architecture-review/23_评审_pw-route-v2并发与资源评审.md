# 23_评审_pw-route-v2（并发 / 资源 / 线程清理）

> 评审对象：`pw-route-v2` 模块（Route V2，并发安全重建版，与 `pw-route` 并存）。
> 评审视角：质查查 —— 时序竞态 / 资源泄漏 / 线程清理，聚焦用户核心诉求「route 在 feature 模式与 scenario 模式都不产生竞态」。
> 基线：工作区 `pw-route-v2/`（`git` 未提交部分按工作区现状），JDK21 + Playwright `1.62.0-dbb1`。
> 独立性说明：本次为针对 v2 的**首评**，未沿用任何历史评审结论；所有问题均回读源码原文确认（标注【亲验】）。

---

## 0 前置事实（拓扑 / 索引）

- **模块拓扑（亲验根 `pom.xml:23-32`）**：reactor 实际包含 8 个模块，`pw-route-v2` **已在 reactor 内**（`:31` `<module>pw-route-v2</module>`）。
  → 此前内存里「6 模块」的记录已失效；v2 是被测物，门禁能真正覆盖它（无「门禁脱钩」问题）。
- `pw-route-v2` 仅依赖 `pw-core`(SPI) + `playwright` + `slf4j` + `json-path`，不依赖 `pw-route`/`pw-web-ui`，可独立演进（pom 自述 + 源码印证）。
- 本仓库 `docs/architecture-review/00_文档索引.md` 当前**不存在于磁盘**（内存引用为陈旧指针）；本文为 23 号，索引需重建后再补条目。

---

## 1 摘要 + 评级

**评级：良好（Good）。** v2 对「route 竞态」的根因有正确认知，且用一套自洽的机制把它堵死；相比 v1 是**实质性进步**。

核心结论：

- **两模式都不产生竞态，机制成立**：
  - scenario 模式：每 scenario 独占 `BrowserContext` → 每 context 一份 `RouteRuntime`（`RouteEngine2.runtimeOf`：`RUNTIMES` 为 `ConcurrentHashMap<BrowserContext,RouteRuntime>`）→ 构造即隔离；context 关闭自动 `close()`，无残留。
  - feature 模式：context 跨 scenario 复用，但 web 侧在**每个 scenario 的 `testFinished`** 走 `RouteLifecycleRegistry.clearContext(context)`（`PlaywrightListener.cleanupRouteRegistryForCurrentThread` → `PlaywrightContextManager.closeContext:401/452`），**把 V2 runtime 整体拆掉、下个 scenario 再 `computeIfAbsent` 重建** ⇒ route 状态不会跨 scenario 串味。
  - 竞态的另一起源（v1 的 `Object doesn't exist: response@/request@`）被**结构性消除**：所有驱动协议调用（`context.route`/`unroute`）收敛到**单一全局 daemon 驱动线程**（`GuardedDriverCallImpl.DRIVER` 单线程执行器），共享连接上的并发协议调用被串行化；且**主动取消**了 `context.onResponse` 订阅（v1 竞态唯一触发源，见 `RouteRuntimeImpl:111-116` 注释）。
- **仍有 2 个 HIGH 需修**（一个真实线程池滥用、一个 feature 模式用法清晰度），其余为加固项。

---

## 2 逐模块/类结论表

| 类 / 文件 | 结论 | 关键证据 |
|---|---|---|
| `RouteEngine2` | 健康 | `ConcurrentHashMap` key 用 context 对象；`close()` idempotent；`shutdownAll` fail-safe 隔离 |
| `RouteRuntimeImpl` | 健康（1 处 HIGH） | `scheduleResponsePoll:155` 误用 `delayedExecutor` 两参重载 |
| `RouteDispatcher` | 健康（1 处 HIGH） | `dispatchDelay:314-316` 同上；CAS claim / `stop()` 重试环 / 令牌化 retire 均正确 |
| `RouteClaim` / `ClaimRegistry` | 健康 | `putIfAbsent` + `remove(route,claim)` CAS；`tryTerminal` 单方向 CAS；`wasIoAwait` 保证额度恰好释放一次 |
| `PendingGuard` | 健康 | `tryAcquire`/`release` AtomicInteger + 下限保护，无负溢出 |
| `PatternBinder` | 健康 | bind/unroute 全经 `GuardedDriverCall`；`closeConfirmed`/`retryUnroute` 重同步自愈；行为类 FAIL_FAST、观测类降级 |
| `GuardedDriverCallImpl` | 健康（1 处 LOW） | 单驱动线程串行化（核心修法）；毒化重置在超时窗口有并发残留风险 |
| `RouteIoExecutor` / `RouteRetireExecutor` | 健康 | 有界队列 + `AbortPolicy` + daemon；拒绝即 fail-open |
| `BoundedOps` | 健康 | Semaphore + 超时 → `Optional.empty()` fail-open |
| `RouteDsl2` | 健康 | `on(page/context)` 升级为 context 级；`clear()/resetAll` 收口到 `RouteEngine2`；`stopX` 按 context 隔离 |
| `RouteLifecycleV2Impl` | 健康（用法需文档化） | `clearContext`≡`stopContextEngine`（关 runtime）；无「只清规则不关 context」入口 |
| `RouteV2ArchitectureTest` | 健康（覆盖缺口） | 仅白名单 `PatternBinder`+`GuardedDriverCall*`；未禁止 `delayedExecutor` 两参 / 无界线程 |
| `RouteV2Config` | 健康 | 默认值保守；越界一律 fail-open |

---

## 3 问题总表（五维：RACE / BLOCK / LEAK / THRD / GOV）

| 编号 | 维度 | 严重度 | 位置 | 问题 | 修复 |
|---|---|---|---|---|---|
| V2-1 | THRD | **HIGH** | `RouteDispatcher:314-316`、`RouteRuntimeImpl:155` | `CompletableFuture.delayedExecutor(ms, unit)` **两参重载** → 落到 `ForkJoinPool.commonPool()`（JVM 级共享、并行度=核数-1）。DELAY 每条规则、响应轮询每 25ms 每在途请求都往公共池塞任务 ⇒ 多 case 并行时污染全 JVM 共享池、低核机退化为每任务新线程、延迟回归。注释 `:311`/`:139` 声称「daemon common pool」**与模块自身原则 #6「有界并发」自相矛盾**，且 ArchUnit 门禁未覆盖。 | 改用**三参重载** `delayedExecutor(ms, unit, customScheduler)`，scheduler 用模块已有的 daemon `ScheduledExecutorService`（仿 `sweeper` 再建一个专用 `delayScheduler`）；两处统一替换。 |
| V2-2 | LEAK/RACE | **HIGH** | `RouteLifecycleV2Impl:44-47`、`RouteEngine2:79-84`、`RouteDsl2`（无 `clearRules`） | **feature 模式没有「只清规则、保留 context」的入口**。`clearContext`/`shutdown` 关的是整个 runtime。当前靠 web 每层 scenario `clearContext` 拆 runtime 来防串味——这确实**不产生竞态**，但代价是：feature 内想在多个 scenario 间**共享同一条 MOCK** 做不到（每 scenario 被拆重建），且用户极易误以为「feature 模式 route 状态跨 scenario 保留」。 | 新增 `RouteEngine2.clearRules(context)`：经 `retireByPurpose` 把全部 `binders` 退役（走 `retire` 执行器，不关 runtime、不动 context），供「想跨 scenario 持久化 route 又想确定性清理」的用户。同时**在 DSL/用户文档写明**：默认每 scenario runtime 被拆，跨 scenario 共享需显式 `clearRules` 或注册返回的 `AutoCloseable` 句柄。 |
| V2-3 | LEAK | MEDIUM | `RouteDispatcher:52` `matcherCache` | `ConcurrentMap<ApiSpec,ApiMatcher>` 以**不可变 ApiSpec 实例**为 key。每次 `register`/`stop`/`withStopped` 都 new 新 `ApiSpec` ⇒ 长生命周期 feature 模式 runtime 下，旧 spec 永不驱逐 → 无界增长。 | key 改为 `pattern` 字符串（同 pattern 多代只留最新 matcher），或加有界/弱引用；`ApiSpec` 不可变性保留。 |
| V2-4 | RACE | LOW | `GuardedDriverCallImpl:123-139` | 毒化重置 `compareAndSet` 换新建执行器后，**旧执行器上仍可能有卡死的在途调用在跑**（native 不可中断，`shutdownNow` 仅遗弃）。此刻新调用在 fresh 线程提交，可能与旧在途调用**并发**占用同一 Connection，在超时后的窄窗口内**重新引入**「Object doesn't exist」类竞态（正是它要消灭的）。 | 毒化期间对 `DRIVER` 加「in-poison」闸门：新 guarded 调用在旧在途调用 ack/超时前排队，待连接静默后再放行；或记录 in-flight 并由下一个成功 ack 显式宣告信道可用。 |
| V2-5 | GOV | LOW | `RouteV2ArchitectureTest:45-72` | 门禁白名单只含 `PatternBinder`+`GuardedDriverCall*`，**未禁止** `CompletableFuture.delayedExecutor` 两参、`Executors.newCachedThreadPool`、`new Thread(` 等无界/共享池用法 → V2-1 这类能溜过编译期门禁。 | 增 ArchRule：`noClasses().that().resideInAPackage("..route.v2..").should().callMethod(CompletableFuture.class,"delayedExecutor")` 且排除三参重载；并禁止 `Executors.newCachedThreadPool`/`new Thread(`。 |

---

## 4 FATAL / CRITICAL 详述

**本次未确认任何 FATAL / CRITICAL。** v2 的竞态根因（共享连接并发协议调用、单 Route 事件多终结者、事件线程阻塞）均已被正确机制覆盖，且关键不变量经行为级测试固化（见 §6）。HIGH 级问题（V2-1/V2-2）影响的是**资源有界性 / feature 模式用法清晰度**，不破坏路由正确性，故不升 FATAL。

---

## 5 维度评分矩阵

| 维度 | 评分 | 说明 |
|---|---|---|
| 并发正确性（RACE） | A | 单所有权 CAS + 单驱动线程串行化 + 快照发布，正确 |
| 事件线程无阻塞（BLOCK） | A- | 仅 V2-1 把延迟/轮询错甩到公共池（非事件线程，但属无界线程） |
| 资源有界（LEAK） | B+ | 执行器均有界；V2-3 matcherCache 无界；V2-1 公共池滥用 |
| 线程清理（THRD） | B+ | 全部 daemon + 有界 + 拒绝即 fail-open；V2-4 毒化窗口残留 |
| 门禁（GOV） | B | 架构门禁存在且生效；V2-5 覆盖缺口 |

---

## 6 已确认健康清单（勿返工）

1. `RouteClaim` 状态机：NEW→IO_AWAIT→HANDLED/RELEASED 全 CAS 单方向，`tryTerminal` 防并发终结「Route is already handled」。【亲验】
2. `ClaimRegistry.markTerminal`：`wasIoAwait` → `pendingGuard.release()`，**额度恰好释放一次**（无泄漏/无负溢出）。【亲验】
3. `RouteRuntimeImpl.stop`：`while` CAS 重试环（`:314-328`），并发 stop/register 线性化。【亲验】
4. `retireByPurpose(ApiSpec)` 令牌判定（`:426-438`）：旧触发者（已替换规则）被**拒绝撤新规则** ⇒ 「旧触发者撤掉新规则」竞态已堵。【亲验】
5. `RouteDispatcher.dispatch` 单所有者：`tryClaim` `putIfAbsent` 仅首个匹配 pattern 命中，其余 `fallback` 链式裁决，请求永不悬挂。【亲验】
6. `GuardedDriverCallImpl` 单驱动线程：所有 `route`/`unroute` 串行 ⇒ v1 的 `Object doesn't exist` 并发根因消除。【亲验】
7. 主动取消 `context.onResponse` 订阅（`RouteRuntimeImpl:111-116` 注释 + `scheduleResponsePoll` 改走 route 通道 `request.existingResponse()`），消除 v1 竞态唯一触发源。【亲验】
8. `close()` 幂等（`AtomicBoolean closed`），`context.onClose` + 生命周期 SPI 双触发安全。【亲验】
9. `RouteIoExecutor`/`RouteRetireExecutor`：有界队列 + `AbortPolicy` + daemon，拒绝→fail-open 不阻塞。【亲验】
10. `BoundedOps`：Semaphore 超时 → `Optional.empty()` fail-open，不在事件线程死等。【亲验】
11. `capture` 横切观测与能力路径无共享可变状态（文档断言 + 代码印证），无竞态。【亲验】
12. 架构门禁 `RouteV2ArchitectureTest` 真实存在并白名单固化「驱动调用收口」，且配 `PatternBinderGuardedCallTest` 等行为级测试。【亲验】

---

## 7 上轮对账

不适用（v2 首评）。

---

## 8 P0 / P1 / P2

- **P0（真正的 P0 是「把已知好设计落到一致」，不是再修一个 bug）**：
  1. **V2-1**：把 `dispatchDelay` 与 `scheduleResponsePoll` 两处 `delayedExecutor` 两参改成三参 + 模块自带 daemon `ScheduledExecutorService`（与 `sweeper` 同源）。**可证伪验收**：在 `RouteDsl2` 单测里用 `Mockito.mock(...)BrowserContext` + 断言「DELAY 任务不跑在 `ForkJoinPool.commonPool()` 线程」（线程名前缀非 `ForkJoinPool.commonPool-worker`），或加 ArchRule（V2-5）让 CI 红。
  2. **V2-2**：在 DSL/用户文档与 `RouteDsl2` Javadoc 写明 feature 模式「每 scenario runtime 被拆、route 状态不跨 scenario 保留」；并提供可选的跨 scenario 持久化入口 `RouteEngine2.clearRules(context)`（如确需）。
- **P1**：V2-3（matcherCache 改按 pattern key 或加界）、V2-5（ArchUnit 补禁用规则）。
- **P2**：V2-4（毒化窗口并发残留，仅超时后窄窗口，低概率，可观察 `POISON_RESETS` 后酌情）。

---

## 9 证据索引（文件:行）

- 拓扑：`pom.xml:23-32`
- 运行时/注册表：`RouteEngine2.java:36,62-66,79-84`
- 竞态触发源取消：`RouteRuntimeImpl.java:111-116,142-157`
- **HIGH 线程池滥用**：`RouteRuntimeImpl.java:155`；`RouteDispatcher.java:314-316`（注释 `:311,:139` 称 daemon 公共池）
- 单所有权/挂起额度：`RouteClaim.java:82-104`；`ClaimRegistry.java:45-66`；`PendingGuard.java:38-61`
- 令牌化 retire：`RouteRuntimeImpl.java:426-438,457-485`
- 驱动单线程化：`GuardedDriverCallImpl.java:60-72,123-139`
- feature 模式逐 scenario 拆 runtime：`PlaywrightListener.java:687-703`；`PlaywrightContextManager.java:401,452`
- 生命周期语义：`RouteLifecycleV2Impl.java:44-47`；`RouteDsl2.java:97-118`（无 clearRules）
- 架构门禁：`RouteV2ArchitectureTest.java:45-72`
- 配置：`RouteV2Config.java:12-36`

---

## 10 一句话结论

**pw-route-v2 对「route 竞态」的根因判断正确、机制自洽，两种模式都不产生竞态（feature 模式靠 web 每 scenario 拆 V2 runtime、scenario 模式靠 context 隔离）；唯一必须修的是 V2-1 把 DELAY/轮询的 `delayedExecutor` 两参从 `ForkJoinPool.commonPool` 收回模块自有 daemon 调度器，以及 V2-2 把 feature 模式「route 不跨 scenario 保留」的语义写进文档并补可选的跨 scenario 持久化入口——其余皆为加固项，勿返工已确认健康的 12 处。**
