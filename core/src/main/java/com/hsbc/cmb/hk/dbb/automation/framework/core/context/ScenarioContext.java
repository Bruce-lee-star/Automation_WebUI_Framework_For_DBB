package com.hsbc.cmb.hk.dbb.automation.framework.core.context;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 用例级上下文绑定（CORE-P0-2 演进 / 评审08类-5 / C-1 / X-2）。
 *
 * <p>现状 {@link ThreadContextRegistry} 以 {@code WeakHashMap<Thread, TestContext>} 线程绑定持有上下文，
 * 隔离依赖调用方显式 {@code resetForCurrentThread()} 的纪律，在并行执行下存在跨线程串扰风险。
 * 本类提供"用例 id -> TestContext"的<b>用例级主键</b> + 线程指针，配合 Cucumber
 * {@code @Before}/{@code @After} 的 {@link #begin(String)}/{@link #end(String)} 强制生命周期，
 * 将隔离从"线程纪律"升级为"机器强制"（{@link #assertUnbound()} 在 {@code @After} 后校验）。
 *
 * <p><b>接线策略</b>：本类为框架落地骨架。C-1 闭环批次一已将生命周期强制接入两条边界——
 * Cucumber 场景经 {@code FrameworkHooks} 的 {@code @Before}/{@code @After} 调
 * {@link #begin(String)}/{@link #end(String)}；并发子用例经
 * {@code ConcurrentContextExecutor.runOnce} 在同一 {@link #begin(String)}/{@link #end(String)} 边界绑定
 * （用例 id 用 {@code ctx:<seq>} 全局唯一，防同 name 碰撞）。读取路径切到本类（替换
 * {@link ThreadContextRegistry} 线程键）为批次二，切换后隔离从"线程级"升级为"用例级"；
 * 线程键保留为无用例绑定的兜底（异步线程 / {@code @BeforeClass} / 纯单测）。
 */
public final class ScenarioContext {

    private static final Logger LOGGER = LoggerFactory.getLogger(ScenarioContext.class);

    private static final Map<String, TestContext> SCENARIO_CONTEXTS = new ConcurrentHashMap<>();
    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    private ScenarioContext() {
    }

    /** 用例开始：登记用例级上下文并置当前线程指针。 */
    public static void begin(String scenarioId) {
        if (scenarioId == null || scenarioId.isEmpty()) {
            throw new IllegalArgumentException("scenarioId must not be empty");
        }
        CURRENT.set(scenarioId);
        SCENARIO_CONTEXTS.computeIfAbsent(scenarioId, k -> new ThreadLocalTestContext());
    }

    /** 当前线程绑定的用例上下文（无则 null）。 */
    public static TestContext current() {
        String id = CURRENT.get();
        return id == null ? null : SCENARIO_CONTEXTS.get(id);
    }

    /** 当前线程绑定的用例 id（无绑定则 null），供数据隔离域派生主键。 */
    public static String currentScenarioId() {
        return CURRENT.get();
    }

    /** 用例结束：清理上下文并解除线程指针；与当前线程不符时仅解除指针。 */
    public static void end(String scenarioId) {
        // D-6 / PAR-4：用例级数据隔离清理钩子（命名空间清理）机器强制执行，
        // 防脏数据泄漏到并行邻居（语义同下方 assertUnbound 的清理保险丝）。
        ScenarioDataNamespace.runCleanup(scenarioId);
        // F-13 兜底：同时执行「当前线程键域」的钩子 —— 覆盖登记时未绑定用例者
        // （@BeforeClass / 异步线程），它们不归属于任何用例 id，永远不会被 runCleanup(id) 命中。
        ScenarioDataNamespace.runCleanupForCurrentThread();
        TestContext ctx = SCENARIO_CONTEXTS.remove(scenarioId);
        if (ctx != null) {
            ctx.clear();
        }
        if (scenarioId.equals(CURRENT.get())) {
            CURRENT.remove();
        }
    }

    /** 当前线程是否仍绑定用例（用于 @After 后断言式清理）。 */
    public static boolean isBound() {
        return CURRENT.get() != null;
    }

    /** @After 后若仍绑定则抛错（机器强制清理，防脏上下文泄漏到下一用例）。 */
    public static void assertUnbound() {
        if (CURRENT.get() != null) {
            throw new IllegalStateException(
                    "ScenarioContext still bound after @After (leak risk): " + CURRENT.get());
        }
    }

    /**
     * 解除当前线程绑定的用例上下文（若已绑定）。供 {@code TestContextHolder.resetForCurrentThread()}
     * 在 scenario / 并发任务结束时统一解绑；幂等，重复调用安全（与 {@link #end(String)} 等价且不会抛错）。
     */
    public static void endCurrent() {
        String id = CURRENT.get();
        if (id != null) {
            end(id);
        } else {
            // F-13 兜底：未绑定用例的线程（异步任务 / @BeforeClass）结束时应执行其线程键域清理钩子，
            // 否则这类钩子既不属于任何用例 id、也无处触发，会静默泄漏到后续用例。
            ScenarioDataNamespace.runCleanupForCurrentThread();
        }
    }

    /** 清理全部用例上下文（套件结束兜底）。 */
    public static void resetAll() {
        SCENARIO_CONTEXTS.values().forEach(ctx -> {
            try {
                ctx.clear();
            } catch (Throwable ignored) {
                // 单个清理异常不影响整体兜底（留痕，不得静默，D7-3）
                LOGGER.debug("[ScenarioContext] clear failed for one context, continue: {}",
                        ignored.toString());
            }
        });
        SCENARIO_CONTEXTS.clear();
        CURRENT.remove();
        // D-6 / PAR-4：套件结束兜底清空全部数据隔离清理钩子
        ScenarioDataNamespace.resetAll();
    }
}
