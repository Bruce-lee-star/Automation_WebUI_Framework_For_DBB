# 02 core 内核：配置 / 上下文 / 日志 / 脱敏

> 评审范围：`core/src/main/java/.../framework/common/{config,context,logging,security,assertion,async,apilog,result,route}`、`framework/core/{context,lifecycle}`
> 评审基线：`e11a847`

---

## 一、模块职责

core 是整个框架的内核层，向上提供 6 类横切能力：

| 包 | 职责 | 关键类 |
|---|---|---|
| `common/config` | 配置解析门面，四级优先级 + `ENC()` 透明解密 | `ConfigSource`、`ConfigKey`、`FrameworkFlags`、`MonitorConfig` |
| `core/context` | 测试上下文托管（线程绑定） | `ThreadContextRegistry`、`TestContextHolder`、`ThreadLocalTestContext` |
| `common/context` | 语言态等轻量上下文 | `LanguageState` |
| `common/logging` | MDC 打标 + 日志出口脱敏 | `LogContext`、`SanitizingMessageConverter` |
| `common/security` | AES 加解密 + 敏感数据识别脱敏 | `ConfigCipher`、`SecretValue`、`SensitiveDataSanitizer` |
| `common/assertion` | 硬断言 / 软断言 | `FrameworkAssertions`、`SoftAssertions` |
| `common/async` | 线程池托管 | `AsyncPool` |
| `common/apilog` | HTTP 流量脱敏落盘 | `ApiTrafficLogger` |
| `common/result` | 引擎无关结果模型与广播 | `ResultReporters`、`TestResult`、`StepResult` |
| `common/route` | 路由生命周期 SPI（依赖倒置） | `RouteLifecycleRegistry` |
| `core/lifecycle` | JVM 关闭编排 | `ShutdownCoordinator` |

---

## 二、现状评估

### 2.1 配置解析

`ConfigSource.resolve()` 实现四级优先级：**系统属性 > 环境变量 > SPI 合并源 > 默认值**，并对 `ENC(...)` 或裸 base64 密文经 `SecretValue` 透明解密（双格式）；兜底默认值为代码内字面量，仅显式 `ENC(...)` 才解密（C-9，已修复）。设计干净。

但存在两个问题：

1. **没有配置键注册表**。`ConfigKey` 只是一个 `record`（`ConfigKey.java:17`），用于描述键的元数据，而非集中常量表。真正的键散落在 `WebFrameworkConfig`（132 个）、`ApiFrameworkConfig`、`MonitorConfig` 三处枚举中。**新增配置项时无法在一个地方看到全貌，也无法做"键是否存在"的静态校验。**
2. **`FrameworkFlags` 另起炉灶**。它刻意绕过带缓存快照的 SPI，只认系统属性/环境变量（`FrameworkFlags.java:29-71`）。虽然注释记录了踩坑原因，但结果是**配置解析路径分裂成两套**，调用方必须知道"这个开关该走哪条路"，认知负担转嫁给了使用者。
3. ~~`ConfigSource.java:95` 对 `defaultValue` 也执行解密~~ **已修复（C-9）**：默认值路径跳过裸密文启发式，仅显式 `ENC(...)` 才解密，形似 base64 的默认值不再被误解密。

### 2.2 测试上下文（最关键的风险点）

`ThreadContextRegistry` 用 `WeakHashMap<Thread, TestContext>` 集中托管（`ThreadContextRegistry.java:30-90`），`TestContextHolder` 提供 `capture/restore/runWithContext` 跨线程传播桥，`ThreadLocalTestContext` 用 `ConcurrentHashMap` 存储。

**核心判断：上下文是线程绑定的，不是用例（Scenario）绑定的。**

这意味着：在 Cucumber 并行执行、且 worker 线程被复用的场景下，**必须由上层（web 监听器）显式调用 `resetForCurrentThread()` 才能保证用例间隔离**。core 内部不提供任何 scenario 级自动绑定。这是一个"正确性依赖于调用方纪律"的设计——只要有一处钩子漏掉，就会出现跨用例状态泄漏，而且这类故障极难复现。

`WeakHashMap` 只在线程对象被 GC 后回收，对常驻线程池线程无效。

另外 `LanguageState.globalLang` 是**静态全局可变字段**（`LanguageState.java:25-93`），不在 `TestContextHolder` 的清理范围内。

### 2.3 日志

`LogContext` 通过 SLF4J MDC 写入 `scenarioId` / `threadId`（`LogContext.java:27-88`），`SanitizingMessageConverter` 接管 logback 的 `%msg` 做出口脱敏（`SanitizingMessageConverter.java:36-60`）——**出口统一脱敏这个思路是对的**，比在每个调用点手工脱敏可靠得多。

短板：

- **只覆盖 `%msg`，不覆盖 `%ex`**。异常栈里的敏感值不被脱敏，而异常恰恰最容易携带完整请求体/URL。
- `sanitizeFreeText` 主要是正则识别（Bearer / JWT / URL / `key=value`）。普通日志里的 JSON 体 `"password":"x"` **不被字段级遮蔽**（只有 `ApiTrafficLogger` 做了全量脱敏）。
- **未桥接 Playwright 自身日志**，Playwright 输出的 URL/Cookie 不经过这套脱敏。
- `AsyncPool` 的异步线程**未传播 MDC**，异步任务日志丢失 `scenarioId`，排障时无法归因。

### 2.4 安全与脱敏

`ConfigCipher` 实现 AES-**256-GCM**，随机 12 字节 IV，强制 64 hex（32 字节）主密钥以杜绝 AES-128 降级（`ConfigCipher.java:43-220`）。主密钥三段来源：`CONFIG_MASTER_KEY` 环境变量 > `config.master.key` 系统属性 > `~/.dbb_automation_master_key`。**算法选型正确，没有 ECB，没有硬编码密钥。**

`SensitiveDataSanitizer` 能力相当完整：字段名白名单 + 值级识别器（Luhn / IBAN / HKID / 中国身份证 / 手机号 / 护照 / USCC / 轨道数据，**且带校验位校验**），掩码 `***[REDACTED]` 不泄露长度，覆盖 JSON / XML / form / URL。这是整套框架里完成度最高的组件之一。

> **测试约定（2026-09-17，验证期间发现并修复的偶发失败）**：PAN 候选正则 `\b\d(?:[ \-]?\d){12,18}\b`（13~19 位数字）
> 命中后由 Luhn 裁定并**整体遮蔽**——这是**正确行为**；但日志端到端测试若以 `"e2e-" + System.nanoTime()` 作标记，
> 长 uptime 的 JVM 中 `nanoTime()` 恰为 **19 位数字**，约 **1/10** 概率通过 Luhn 校验 → 标记被误罩为 `***[REDACTED]`
> → 断言偶发「日志中找不到标记行」。已实测复现：`throwable-e2e-1758096000123456789` → `throwable-e2e-***[REDACTED]`；
> 而 `...789z`（末尾补字母）原样通过。
> **约定**：日志捕获标记统一用 `TestLogCapture.newMarker(prefix)`（末尾补字母破除 `\b\d{13,19}\b` 词边界，从根本上免疫），
> 并由确定性回归用例 `SanitizingMessageConverterTest#markerIsImmuneToValueRecognizers`（含 Luhn 合法卡号样本 + 200 次采样）守住。
> （受影响并已修正的三处：`SanitizingThrowableConverterTest`、`SanitizingMessageConverterTest`、`LogContextTest`。）

两个问题：

- `SecretValue.java`（C-3 已加固）：`ENC(...)` 显式标记默认走**严格失败快**（`-Dsecurity.secret.strict=true`），解密失败即抛 `IllegalStateException`，逼出"主密钥缺失/密文损坏"；裸 `base64` **同样失败快**（key 不匹配/密文被篡改即抛异常，密文均绑定本地主密钥），仅**明显非密文形态**（非 base64 或长度不足以含 IV+GCM 标签）才原样返回。原"静默降级把密文当值用"问题已消除。
- `SensitiveDataSanitizer` 近 **936 行**的静态 god-class，且大量静态可变集合在 `loadRulesIfNeeded` 中被 `clear+addAll`（`:255-262`），是共享可变静态状态。

### 2.5 断言

`FrameworkAssertions` 硬断言（fail-fast）+ `SoftAssertions` 软断言（ThreadLocal 收集后 `assertAll()` 聚合），失败抛 `FrameworkAssertionError`（继承 `AssertionError`）——**继承 `AssertionError` 是关键决策**，保证了 JUnit / Cucumber / Serenity 三个引擎都能正确识别失败。这个设计是对的。

风险：`SoftAssertions.FAILURES` 是 ThreadLocal（`SoftAssertions.java:38`），同样依赖外部清理。若场景结束未调 `clearForCurrentThread()`，或 `assertAll()` 因场景跳过未被调用，**失败会残留并污染下一个用例**。

### 2.6 异步与关闭

`AsyncPool` 主池 core2/max6/**有界队列 200**，监控回调单线程有界 10000，调度器守护线程（`AsyncPool.java:108-155`），拒绝策略丢弃并告警——**不反压 Playwright 事件线程，这个取舍正确**。经 `ShutdownCoordinator` 优雅关闭。

`CONTEXT_SCHEDULERS`（`ConcurrentHashMap`, `:55`）仅靠显式 `removeContextScheduler` 清理，未调用即泄漏。

`ShutdownCoordinator` 单点管理关闭钩子，order 升序、幂等、`CopyOnWriteArrayList`（`ShutdownCoordinator.java:34-132`），实现干净。但注意：JVM 已进入 shutdown 后再注册不会触发。

---

## 三、优势

1. **出口统一脱敏**（`SanitizingMessageConverter` 接管 `%msg`）而非入口逐点脱敏，架构上更可靠。
2. **值级识别器带校验位**（Luhn/IBAN/HKID），误报率低；掩码不泄露长度，规避了长度推断攻击。
3. **AES-256-GCM + 强制 32 字节密钥**，无硬编码主密钥，算法选型无可挑剔。
4. **`FrameworkAssertionError` 继承 `AssertionError`**，一处决策同时兼容三个测试引擎。
5. **有界队列 + 守护线程 + 统一关闭编排**，资源治理意识清晰。

---

## 四、风险与问题

| 编号 | 级别 | 问题 | 证据 | 影响 |
|---|---|---|---|---|
| C-1 | **P0** | 测试上下文**线程绑定而非用例绑定**，隔离依赖调用方显式 reset | `ThreadContextRegistry.java:30-90`、`TestContextHolder.java:56-151` | 并行下跨用例状态泄漏，故障难复现 |
| C-2 | **P0** | 日志脱敏不覆盖 `%ex`（异常栈） | `SanitizingMessageConverter.java:36-60` 仅接管 `%msg` | 异常里携带的敏感值明文落盘 |
| C-3 | **P1** | `SecretValue` 解密失败静默降级为"密文当值用" | `SecretValue.java:65-72` | 凭据故障延迟暴露，排查成本高 |
| C-4 | **P1** | `SoftAssertions` ThreadLocal 残留风险 | `SoftAssertions.java:38` | 上一个用例的失败污染下一个用例 |
| C-5 | **P1** | 异步线程不传播 MDC | `AsyncPool` 未做 MDC 传递 | 异步日志无法归因到 scenario |
| C-6 | **P1** | 配置解析路径分裂（`ConfigSource` vs `FrameworkFlags`），无配置键统一注册表 **已修复**：core `ConfigKeys` 注册表已加重复键 fail-fast 静态校验（新增 `ConfigKeysRegistryTest` 3/3），web/api/monitor 配置按设计保持模块分离，三枚举（`WebFrameworkConfig`/`MonitorConfig`/`FrameworkFlags`）保留；另新增**键唯一性**（`configKeysAreGloballyUnique`，Web×API）+ **镜像默认值一致性**（`mirrorConfigKeysMustAgreeOnDefaults`，`MonitorConfig`/`ConfigKeys` 与 Web/API 枚举逐条比对）两道守卫，实测抓到并修复 2 处注册表默认值漂移 | `ConfigKey.java:17`、`FrameworkFlags.java:29-71` | 新配置无处可查，键名无静态校验 |
| C-7 | **P1** | `SensitiveDataSanitizer` 936 行静态 god-class + 共享可变静态集合 **已修复（核心）**：三个生效规则集（header/body/query）改为**不可变快照 + `volatile` 原子发布**，消除共享可变静态集与 reload 竞态；`SensitiveDataSanitizerReloadConcurrencyTest`（4 读 × 50 reload，0 违规）。整类策略链拆分待决策 | `:72-936`、`:255-262` | 无法扩展、并发下 reload 有竞态 |
| C-8 | **P2** | `LanguageState.globalLang` 静态全局可变，不在清理范围 **已修复**：`FrameworkHooks` 在每个 scenario 前后调用 `clearScenarioScopedState()` 复位语言态（`LanguageState.reset()`），使进程级全局语言值不跨用例残留（双轨设计保留全局轨道——跨线程可见性所必需）；`FrameworkHooksLanguageIsolationTest` 2/2 | `LanguageState.java:25-93` | 并行下语言态串扰 |
| C-9 | **P2** | ~~`ConfigSource` 对 defaultValue 也解密~~ **已修复**：默认值仅显式 `ENC(...)` 才解密 | `ConfigSource.java:95` | 形似 base64 的默认值不再被误解密 |
| C-10 | **P2** | `ApiTrafficLogger.writeBlock` IOException 仅 debug | `ApiTrafficLogger.java:198-199` | 流量日志静默丢失 |
| C-11 | **P2** | `CONTEXT_SCHEDULERS` 未清理即泄漏 | `AsyncPool.java:55` | 长跑场景调度器堆积 |
| C-12 | **P2** | `common/persistence` 为空目录，持久化实现在 route 侧 | core 目录 | 目录结构与实际能力不符 |

---

## 五、优化方案

### 5.1 上下文改为"用例绑定 + 线程绑定"双键（P0）

核心思路：引入 `ScenarioId` 作为一级键，ThreadLocal 只作为"当前线程绑定的 scenario 指针"。这样即使线程复用，只要 scenarioId 变了就必然拿到新上下文；同时保留 `runWithContext` 做跨线程传播。

```java
public final class ScenarioContext {

    private static final AtomicLong SEQ = new AtomicLong();
    private static final Map<String, TestContext> BY_SCENARIO = new ConcurrentHashMap<>();
    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    /** 用例开始时调用：绑定并返回一个全新的上下文 */
    public static String begin(String scenarioName) {
        String id = scenarioName + "#" + SEQ.incrementAndGet();
        BY_SCENARIO.put(id, new TestContext(id));
        CURRENT.set(id);
        LogContext.beginScenario(id);
        return id;
    }

    /** 用例结束时调用：彻底解绑 + 移除，杜绝线程复用导致的残留 */
    public static void end(String id) {
        try {
            TestContext ctx = BY_SCENARIO.remove(id);
            if (ctx != null) {
                ctx.close();              // 关闭其持有的资源
            }
            SoftAssertions.clearForScenario(id);
        } finally {
            CURRENT.remove();             // 关键：remove 而非 set(null)
            LogContext.endScenario();
        }
    }

    public static TestContext current() {
        String id = CURRENT.get();
        if (id == null) {
            throw new IllegalStateException(
                "No scenario bound to thread " + Thread.currentThread().getName()
                + " — 请确认 @Before 钩子调用了 ScenarioContext.begin()");
        }
        return Objects.requireNonNull(BY_SCENARIO.get(id), "context missing: " + id);
    }
}
```

配套：在 Cucumber `@Before`/`@After`（见 08 文档）强制调用 `begin/end`，并加一条 ArchUnit/运行时断言——**`@After` 结束后若 `CURRENT.get() != null` 直接抛错**，把"依赖纪律"变成"机器强制"。

### 5.2 脱敏覆盖异常栈（P0）

logback 的 `%ex` 走的是 `ThrowableProxyConverter`，需要单独接管：

```java
public class SanitizingThrowableConverter extends ThrowableProxyConverter {

    @Override
    protected String throwableProxyToString(IThrowableProxy tp) {
        String rendered = super.throwableProxyToString(tp);
        return SensitiveDataSanitizer.sanitize(rendered);
    }
}
```

```xml
<!-- logback-test.xml / logback.xml -->
<conversionRule conversionWord="ex"
                converterClass="...common.logging.SanitizingThrowableConverter"/>
<conversionRule conversionWord="msg"
                converterClass="...common.logging.SanitizingMessageConverter"/>
```

同时为 Playwright 日志加装脱敏桥（Playwright 的日志走 SLF4J 时自动生效；若走 stdout，需在 `BrowserStartupImpl` 里重定向）。

### 5.3 解密失败快速失败（P1）

```java
// SecretValue.java（已实现，C-3 + 双格式支持）：
// ENC(...) 与裸 base64 均严格失败快（key 不匹配/密文被篡改 → 抛异常）；仅非密文形态回退原串。
// security.secret.strict 默认 true；framework.secret.allow-bare-base64 默认 true（裸密文默认解密）。

public static String decrypt(String raw) {
    if (!isEncrypted(raw)) {
        return raw;
    }
    try {
        return ConfigCipher.decrypt(raw);
    } catch (Exception e) {
        if (STRICT) {
            throw new FrameworkException(
                "解密失败：主密钥缺失或不匹配。请检查环境变量 CONFIG_MASTER_KEY。"
                + " 如需临时放行，设置 -Dsecurity.secret.strict=false", e);
        }
        LOGGER.error("[SEC] 解密失败，已降级为密文原值：{}", raw, e);
        return raw;
    }
}
```

**关键改进：错误信息里直接告诉运维该查什么**，而不是让故障在"登录失败"处暴露。

### 5.4 异步线程传播 MDC（P1）

```java
public final class MdcAware {
    public static Runnable wrap(Runnable r) {
        Map<String, String> snapshot = MDC.getCopyOfContextMap();
        return () -> {
            if (snapshot != null) MDC.setContextMap(snapshot);
            try { r.run(); } finally { MDC.clear(); }
        };
    }
    public static <T> Callable<T> wrap(Callable<T> c) { /* 同构 */ }
}

// AsyncPool 提交处统一包一层
executor.submit(MdcAware.wrap(task));
```

### 5.5 拆分 god-class 与统一配置注册表（P1/P2）

`SensitiveDataSanitizer` 按"识别器"拆成策略链，消除静态可变集合：

```java
public interface ValueRecognizer {
    boolean matches(String fieldName, String value);
    String mask(String value);
}

public final class SanitizerEngine {
    // 构造后不可变，无 reload 竞态
    private final List<ValueRecognizer> recognizers;
    private final Set<String> sensitiveFieldNames;

    public SanitizerEngine(List<ValueRecognizer> recognizers, Set<String> fieldNames) {
        this.recognizers = List.copyOf(recognizers);
        this.sensitiveFieldNames = Set.copyOf(fieldNames);
    }
    public String sanitize(String input) { /* 管线执行 */ }
}
```

配置键统一注册表（消除"键散落三处"）：

```java
public enum ConfigKeys implements ConfigKeySpec {
    BROWSER_TYPE("playwright.browser.type", "chromium"),
    TRACE_ENABLED("playwright.context.trace.enabled", "false"),
    // ... 全框架唯一键表
    ;
    private final String key; private final String def;
    ConfigKeys(String key, String def) { this.key = key; this.def = def; }
    public String key() { return key; }
    public String get() { return ConfigSource.resolve(key, def); }
}
```

---

## 六、结论

core 的**安全与脱敏能力是这套框架的亮点**——AES-256-GCM、带校验位的值级识别、不泄露长度的掩码、出口统一脱敏，这几点达到了金融级要求。真正的系统性风险是 **C-1：上下文线程绑定而非用例绑定**。它把"用例隔离"这一正确性前提外包给了调用方纪律，在串行执行下无害，一旦开启并行就会变成随机故障。这必须与 10 号文档的并发改造合并处理。
