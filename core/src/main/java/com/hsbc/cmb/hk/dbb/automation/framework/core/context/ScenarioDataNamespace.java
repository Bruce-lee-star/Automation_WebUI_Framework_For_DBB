package com.hsbc.cmb.hk.dbb.automation.framework.core.context;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 用例级测试数据隔离（D-6 / PAR-4 / 10-X-4）。
 *
 * <p>并行执行下，用例间对「共享可变后端」的隐式数据依赖会导致随机污染。
 * 本类提供两项能力，把隔离从"调用方纪律"升级为"机器强制"（与 {@link ScenarioContext} 的
 * {@code begin/end/assertUnbound} 同源）：
 * <ol>
 *   <li><b>每用例唯一数据</b>：{@link #uniqueId(String)} 基于当前用例域主键生成全局唯一、可读的标识，
 *       供测试构造不与其他并行用例碰撞的数据（用户名 / 订单号 / 命名空间键等）。</li>
 *   <li><b>命名空间清理</b>：{@link #registerCleanup(Runnable)} 登记当前用例域的清理钩子，
 *       在 {@link ScenarioContext#end(String)}（即 {@code @After} / 并发任务结束）时由框架强制执行，
 *       防脏数据泄漏到下一用例（语义同 {@link ScenarioContext#assertUnbound()} 的清理保险丝）。</li>
 * </ol>
 *
 * <p><b>隔离域主键</b>：优先用例级主键（{@link ScenarioContext#currentScenarioId()}），无绑定时回退线程标识，
 * 保证异步线程 / {@code @BeforeClass} / 纯单测下仍能产出确定且唯一的值。
 *
 * <p><b>线程键域的兜底执行（评审 F-13 / P1-5）</b>：回退到「线程键」的钩子不归属于任何用例 id，
 * 因而<b>永远不会</b>被 {@code end(scenarioId)} 命中 —— 原实现下这类钩子（典型
 * {@code @BeforeClass} 或异步线程登记）既不随用例结束触发、也不在套件结束兜底，<b>静默泄漏</b>。
 * 现在三处补兜底：
 * <ol>
 *   <li>{@link ScenarioContext#end(String)} 同时执行<b>当前线程键域</b>的钩子；</li>
 *   <li>{@link ScenarioContext#endCurrent()} 在未绑定用例时执行当前线程键域钩子；</li>
 *   <li>{@link #resetAll()}（套件结束）先<b>执行</b>再清空全部剩余钩子，并留痕告警
 *       （取代原先的「直接丢弃」）。</li>
 * </ol>
 *
 * <p><b>可观测 / 健壮</b>：清理钩子执行异常不中断其余钩子且记入日志（不静默，D7-3）；
 * 钩子按用例域隔离存储，串行 / 并行用例互不串扰。
 */
public final class ScenarioDataNamespace {

    private static final Logger LOGGER = LoggerFactory.getLogger(ScenarioDataNamespace.class);

    /** 无用例绑定时的域主键前缀（线程键域）。 */
    private static final String THREAD_KEY_PREFIX = "thread:";

    /** 用例域 -> 清理钩子列表（按域隔离，线程安全）。 */
    private static final Map<String, List<Runnable>> CLEANUPS = new ConcurrentHashMap<>();

    /** 全局自增序号，保证 {@link #uniqueId(String)} 跨域、同域内绝对唯一。 */
    private static final AtomicLong SEQ = new AtomicLong();

    private ScenarioDataNamespace() {
    }

    /** 当前线程键（无用例绑定时登记的钩子归属此域）。 */
    private static String currentThreadDomainKey() {
        return THREAD_KEY_PREFIX + Thread.currentThread().threadId();
    }

    /** 当前隔离域主键：用例绑定优先，否则线程标识兜底。 */
    private static String domainKey() {
        String scenarioId = ScenarioContext.currentScenarioId();
        return scenarioId != null ? scenarioId : currentThreadDomainKey();
    }

    /**
     * 生成当前用例域内的唯一标识（带可读前缀，便于后端 / 日志溯源）。
     *
     * <p>同一用例域内多次调用返回不同值；不同用例域永不碰撞（域主键 + 全局序号双重保证）。
     *
     * @param prefix 可读前缀（如 {@code "user"} / {@code "order"}）；空或 {@code null} 时退化为 {@code "ns"}
     */
    public static String uniqueId(String prefix) {
        String p = (prefix == null || prefix.isEmpty()) ? "ns" : prefix;
        return p + "-" + domainKey() + "-" + SEQ.incrementAndGet();
    }

    /**
     * 登记当前用例域的清理钩子（可重复登记；执行顺序不保证）。
     *
     * <p>未绑定用例时（{@code @BeforeClass} / 异步线程）钩子落在「线程键域」，其执行时机见类注释，
     * 此处以 verbose 日志留痕，便于排查"钩子为何没跑"。</p>
     *
     * @param cleanup 清理动作（如删除本用例创建的命名空间数据）；{@code null} 抛 {@link IllegalArgumentException}
     */
    public static void registerCleanup(Runnable cleanup) {
        if (cleanup == null) {
            throw new IllegalArgumentException("cleanup must not be null");
        }
        String key = domainKey();
        CLEANUPS.computeIfAbsent(key, k -> new CopyOnWriteArrayList<>()).add(cleanup);
        if (key.startsWith(THREAD_KEY_PREFIX) && LOGGER.isDebugEnabled()) {
            //  注意：此处刻意用本类 LOGGER 而非 common.config.VerboseLogging ——
            //  后者会让 `core` 反向依赖 `common`，与既有 `common -> core` 边构成切片循环
            //  （ArchUnit `frameworkSlicesMustBeFreeOfCycles` 已实测报红）。
            LOGGER.debug("[ScenarioDataNamespace] cleanup registered without a bound scenario (domain={}) — "
                    + "it will run at this thread's scenario end or at suite-end fallback", key);
        }
    }

    /**
     * 用例结束时由 {@link ScenarioContext#end(String)} 调用：执行并清空指定用例域的全部清理钩子。
     * 单个钩子异常不中断其余，且记入日志（不静默）。
     *
     * @param scenarioId 结束的用例 id（与 {@link ScenarioContext#currentScenarioId()} 同源）
     */
    static void runCleanup(String scenarioId) {
        List<Runnable> hooks = CLEANUPS.remove(scenarioId);
        runHooks(scenarioId, hooks);
    }

    /**
     * F-13 兜底：执行当前线程键域的清理钩子。
     *
     * <p>覆盖「登记时无用例绑定」的钩子（{@code @BeforeClass} / 异步线程）—— 它们不归属于任何用例 id，
     * 永远不会被 {@code end(scenarioId)} 命中。</p>
     */
    static void runCleanupForCurrentThread() {
        runCleanup(currentThreadDomainKey());
    }

    /**
     * F-13 兜底（套件结束）：<b>执行</b>并清空全部剩余域的清理钩子（含线程键域）。
     *
     * <p>由 {@link ScenarioContext#resetAll()} 调用。原实现直接 {@code clear()} —— 对于任何未被
     * {@code end} 命中过的域，钩子被<b>静默丢弃</b>；现在先执行再清空，并统计留痕（D7-3 不得静默）。</p>
     */
    static void resetAll() {
        int executed = 0;
        for (String domain : List.copyOf(CLEANUPS.keySet())) {
            List<Runnable> hooks = CLEANUPS.remove(domain);
            if (hooks != null && !hooks.isEmpty()) {
                executed += hooks.size();
                runHooks(domain, hooks);
            }
        }
        CLEANUPS.clear();
        if (executed > 0) {
            LOGGER.warn("[ScenarioDataNamespace] {} cleanup hook(s) executed at suite-end fallback "
                    + "(no scenario end reached them)", executed);
        }
    }

    /** 逐个执行钩子：单个异常不中断其余，且逐条留痕（D7-3）。 */
    private static void runHooks(String domain, List<Runnable> hooks) {
        if (hooks == null || hooks.isEmpty()) {
            return;
        }
        for (Runnable h : hooks) {
            try {
                h.run();
            } catch (Throwable t) {
                // 单个清理异常不影响其余钩子与用例收尾（D7-3：不得静默）
                LOGGER.warn("[ScenarioDataNamespace] cleanup hook failed for domain {}: {}",
                        domain, t.toString());
            }
        }
    }
}
