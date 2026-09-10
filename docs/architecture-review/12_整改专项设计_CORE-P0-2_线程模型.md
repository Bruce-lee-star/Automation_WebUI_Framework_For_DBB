# CORE-P0-2 专项设计：core 上下文线程模型现代化

> 状态：📐 设计中（待评审后落地）｜依赖：CORE-P0-1（core 去 Serenity，已 ✅）｜owner：core
> 铁律：先出设计/seam 再动手；公共 API 不变 → 调用方零改动。

## 1. 背景与目标

依赖图链上 CORE-P0-1 之后的下一环：core 的 per-thread 测试上下文从「裸 `ThreadLocal` + 非并发 `HashMap`」
升级为可集中管控、可清理防泄漏、按需可继承的现代化并发上下文模型。

目标：
1. 用 `WeakHashMap<Thread, TestContext>`（同步包装）容器集中托管上下文，杜绝线程池复用导致的跨 scenario 串扰；key 为弱引用，线程 GC 后条目自动清除（防内存泄漏）；
2. 内部存储改为并发安全，移除对「线程隔离」的隐式依赖；
3. 提供 `InheritableThreadLocal` 风格的按需传播开关（派生线程可继承父上下文，无需每处显式 capture）；
4. 巡检并加固全局静态开关（`volatile`/`AtomicBoolean`）；
5. 保持 `TestContext` 接口与 `TestContextHolder` 公共方法签名不变（seam 已就绪，调用方零改）。

## 2. 现状诊断（证据）

| 项 | 位置 | 问题 |
|----|------|------|
| 上下文持有 | `core/.../core/context/TestContextHolder.java:18` | `private static final ThreadLocal<TestContext> CURRENT = ThreadLocal.withInitial(ThreadLocalTestContext::new)`——裸 ThreadLocal，线程池复用线程若未 `resetForCurrentThread()` 则残留 → 跨 scenario 污染；无集中清理/兜底 |
| 内部存储 | `core/.../core/context/ThreadLocalTestContext.java:13` | `private final Map<ContextKey<?>, Object> store = new HashMap<>()`——非并发安全；依赖线程隔离，防御性弱 |
| 跨线程桥（已有） | `TestContextHolder.java:47-99` + `CapturedContext.java` | `capture()/restore()/runWithContext()/callWithContext()` 已存在，快照为浅拷贝、工作线程写入不回灌提交线程（隔离保留，N-10 语义正确）；`AsyncPool.java:174-182` 已用 |
| 双轨范式（已有） | `core/.../common/context/LanguageState.java:30-37` | 全局 `AtomicReference` + 线程覆盖 + 单调序号 `LANG_WRITE_SEQ`——跨线程可见性良好范式，可复用 |
| 缺 Inheritable 传播 | 全局 | 新派生线程（如 Playwright 事件线程）默认不继承父 ctx；未显式 capture 的路径取不到上下文 |
| 静态开关（外延） | `web/.../listener/ListenerRegistry.java:27` | `private static boolean initialized`（非 volatile，非线程安全）。注：core/route 多数已 `volatile`（`JsonFileReader.java:28`、`SensitiveDataSanitizer.java:149,234`、`ApiMonitoringRepository.java:78-79`），仅个别遗漏 |

## 3. 整改设计

### 3.1 上下文容器：`ThreadContextRegistry`（internal，替代裸 ThreadLocal）
新增 `core/.../core/context/ThreadContextRegistry`：

```java
final class ThreadContextRegistry {
    private static final Map<Thread, TestContext> CONTEXTS =
            Collections.synchronizedMap(new WeakHashMap<>());
    static TestContext get() { return CONTEXTS.computeIfAbsent(Thread.currentThread(), k -> new ThreadLocalTestContext()); }
    static void remove(Thread t) { TestContext c = CONTEXTS.remove(t); if (c != null) c.clear(); }
    static void resetAll() { CONTEXTS.values().forEach(TestContext::clear); CONTEXTS.clear(); }
    static int activeThreadCount() { return CONTEXTS.size(); }
}
```

`TestContextHolder` 内部 `CURRENT` 改为委托 `ThreadContextRegistry`（公共方法 `get()/resetForCurrentThread()` 签名不变）。

### 3.2 `ThreadLocalTestContext` 内部并发安全
`store` 由 `HashMap` 改为 `ConcurrentHashMap<ContextKey<?>, Object>`；`snapshot()/loadSnapshot()` 保持浅拷贝语义（基于 ConcurrentHashMap 拷贝）。行为等价，移除线程隔离隐式依赖。

### 3.3 Inheritable 传播开关（按需 propagate）
`TestContextHolder` 增加可配置开关 `INHERIT_MODE`（默认 **false**，避免线程池误继承）：
- `true` + 真实 `new Thread` 派生：子线程自动继承父 ctx 浅拷贝；
- 线程池场景仍走显式 `capture()/runWithContext()`（推荐，已在 `AsyncPool` 使用）；
- 新增 `TestContextHolder.inherit()`：当前线程复制源线程（父/指定）ctx 快照，供 Playwright 事件线程等派生场景。

### 3.4 capture/restore 工具（保留 + 增强，签名不变）
保留 `capture()/restore()/runWithContext()/callWithContext()`（seam）。新增可选 `propagate(CapturedContext, Runnable)` 重载（监控用）。明确语义：工作线程写入不回灌提交线程（隔离保留），保持既有 N-10 行为。

### 3.5 全局静态开关治理（外延子任务）
巡检 `core/web/route` 全部 `private static [final] boolean` 开关，非 `volatile`/`AtomicBoolean` 者统一加固（如 `ListenerRegistry.initialized`）。单独 commit 便于 review。

### 3.6 清理防泄漏兜底
`ThreadContextRegistry.resetAll()` **不**注册 `ShutdownCoordinator`：core→common 反向依赖会破坏 G1（framework 切片无循环）架构门禁（common→core 已由 `LanguageState` 存在）；JVM 关闭兜底非必需——线程结束由 WeakHashMap 弱 key 自动清除，scenario 级清理由 `resetForCurrentThread()` 负责。

## 4. 迁移步骤（seam，调用方零改）

1. 新增 `ThreadContextRegistry`；`TestContextHolder` 内部 `CURRENT` 替换为委托（公共签名不变）。
2. `ThreadLocalTestContext.store` 改 `ConcurrentHashMap`（行为等价）。
3. 加 `INHERIT_MODE` 开关 + `inherit()` 工具（默认关）。
4. `resetAll()` 由套件结束显式调用兜底（**不**注册 `ShutdownCoordinator`，避免破坏 G1，见 §3.1）。
5. 静态开关巡检加固（清单见 §5，单独 commit）。
6. 全护盾 + 新增并发单测验证。

## 5. 全护盾验证点 + 新增单测

- **底线**：全护盾 470 例零回归（Failures 0 / Errors 0 / Skipped 0）。
- 增强 `TestContextConcurrencyTest`：线程池复用串扰（同一线程先后跑 2 scenario，验证 `resetForCurrentThread()` 后隔离）、并发 `computeIfAbsent`、capture/restore 隔离。
- 新增 `ThreadContextRegistryLeakTest`：scenario 结束后 `resetForCurrentThread()`/`resetAll()` 后 `CONTEXTS` size 回落（防泄漏）。
- 新增 `InheritablePropagationTest`：开关开时派生线程继承父 ctx；开关关时不继承。

## 6. 风险与回滚

- **风险**：线程池复用语义变化（`resetAll()` 兜底可能改变既有的「未清理残留」隐式行为）→ 以全护盾 + 单测覆盖。
- **回滚**：设计保持公共 API 不变，回滚仅替换内部实现，调用方无需回改。

## 7. 评审待确认点

- **Q1**：`INHERIT_MODE` 默认开 or 关？（建议默认关，显式 `capture` 优先，仅在派生线程场景按需开）
- **Q2**：静态开关治理（§3.5）是否纳入本次 CORE-P0-2 commit，还是单独 PR？
- **Q3**：`ThreadContextRegistry` 是否需暴露指标给 `AsyncPool`/`MonitorFailureCollector` 监控（如 `activeThreadCount`）？
