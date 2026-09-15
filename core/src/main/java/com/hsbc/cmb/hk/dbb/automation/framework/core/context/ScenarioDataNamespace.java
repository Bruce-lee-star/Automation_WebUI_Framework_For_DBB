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
 * <p><b>可观测 / 健壮</b>：清理钩子执行异常不中断其余钩子且记入日志（不静默，D7-3）；
 * 钩子按用例域隔离存储，串行 / 并行用例互不串扰。
 */
public final class ScenarioDataNamespace {

    private static final Logger LOGGER = LoggerFactory.getLogger(ScenarioDataNamespace.class);

    /** 用例域 -> 清理钩子列表（按域隔离，线程安全）。 */
    private static final Map<String, List<Runnable>> CLEANUPS = new ConcurrentHashMap<>();

    /** 全局自增序号，保证 {@link #uniqueId(String)} 跨域、同域内绝对唯一。 */
    private static final AtomicLong SEQ = new AtomicLong();

    private ScenarioDataNamespace() {
    }

    /** 当前隔离域主键：用例绑定优先，否则线程标识兜底。 */
    private static String domainKey() {
        String scenarioId = ScenarioContext.currentScenarioId();
        return scenarioId != null ? scenarioId : "thread:" + Thread.currentThread().getId();
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
     * @param cleanup 清理动作（如删除本用例创建的命名空间数据）；{@code null} 抛 {@link IllegalArgumentException}
     */
    public static void registerCleanup(Runnable cleanup) {
        if (cleanup == null) {
            throw new IllegalArgumentException("cleanup must not be null");
        }
        CLEANUPS.computeIfAbsent(domainKey(), k -> new CopyOnWriteArrayList<>()).add(cleanup);
    }

    /**
     * 用例结束时由 {@link ScenarioContext#end(String)} 调用：执行并清空该用例域的全部清理钩子。
     * 单个钩子异常不中断其余，且记入日志（不静默）。
     *
     * @param scenarioId 结束的用例 id（与 {@link ScenarioContext#currentScenarioId()} 同源）
     */
    static void runCleanup(String scenarioId) {
        List<Runnable> hooks = CLEANUPS.remove(scenarioId);
        if (hooks == null || hooks.isEmpty()) {
            return;
        }
        for (Runnable h : hooks) {
            try {
                h.run();
            } catch (Throwable t) {
                // 单个清理异常不影响其余钩子与用例收尾（D7-3：不得静默）
                LOGGER.warn("[ScenarioDataNamespace] cleanup hook failed for scenario {}: {}",
                        scenarioId, t.toString());
            }
        }
    }

    /** 清空全部域清理钩子（套件结束兜底，由 {@link ScenarioContext#resetAll()} 调用）。 */
    static void resetAll() {
        CLEANUPS.clear();
    }
}
