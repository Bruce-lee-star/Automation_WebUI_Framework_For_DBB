package com.hsbc.cmb.hk.dbb.automation.framework.common.logging;

import org.slf4j.MDC;

/**
 * 诊断上下文（SLF4J MDC）门面 —— D3-1「日志落盘 + MDC 可串联」。
 *
 * <p><b>要解决的问题</b>：并行 / 并发执行时，控制台与文件中的日志来自多个 scenario 与多个工作线程，
 * 仅凭 {@code [thread]} 无法判断某条日志属于哪个用例，排障只能靠猜。
 *
 * <p><b>本类提供的 MDC 键</b>（logback pattern 中以 {@code %X{key}} 引用）：
 * <ul>
 *   <li>{@link #KEY_SCENARIO_ID} —— 当前 scenario 标识（由监听器在 {@code testStarted} 绑定，
 *       {@code testFinished} 的 finally 解绑）；</li>
 *   <li>{@link #KEY_THREAD_ID}   —— 线程 ID（首次使用时惰性绑定，与 {@code %thread} 互补，
 *       便于按数字聚合）。</li>
 * </ul>
 *
 * <p><b>线程模型</b>：MDC 本身是线程本地存储，故本类无共享可变状态，天然线程安全；
 * 但正因如此，<b>线程池复用线程时必须清理</b> —— 否则上一个 scenario 的标识会被带到下一个用例
 * （与 {@code ThreadLocal} 串扰同源）。因此场景收尾必须调用 {@link #endScenario()}，
 * 而 {@link #clear()} 用于彻底重置当前线程（套件结束 / 线程归还前）。
 *
 * @apiNote framework-internal：由框架生命周期（Serenity 监听器）调用，业务代码一般无需直接使用；
 *          如需为自定义异步线程补齐上下文，可调用 {@link #beginScenario(String)} / {@link #clear()}。
 */
public final class LogContext {

    /** MDC 键：当前 scenario 标识。 */
    public static final String KEY_SCENARIO_ID = "scenarioId";

    /** MDC 键：当前线程 ID。 */
    public static final String KEY_THREAD_ID = "threadId";

    /** 未知 / 未绑定时的占位值。 */
    public static final String UNKNOWN = "unknown";

    private LogContext() {
        // 纯静态门面，禁止实例化
    }

    /**
     * 绑定当前 scenario 到 MDC（场景开始时调用）。
     * <p>顺带惰性补齐线程级默认值（threadId / env），保证首条日志即可串联。
     *
     * @param scenarioId scenario 标识；null 或空串时记为 {@link #UNKNOWN}
     */
    public static void beginScenario(String scenarioId) {
        ensureThreadDefaults();
        MDC.put(KEY_SCENARIO_ID, isBlank(scenarioId) ? UNKNOWN : scenarioId);
    }

    /**
     * 解绑当前 scenario（场景结束时调用，<b>必须放在 finally</b>）。
     * <p>仅移除 scenarioId，保留 threadId 等线程级默认值。
     */
    public static void endScenario() {
        MDC.remove(KEY_SCENARIO_ID);
    }

    /** 当前绑定的 scenario 标识；未绑定返回 null。 */
    public static String currentScenarioId() {
        return MDC.get(KEY_SCENARIO_ID);
    }

    /**
     * 清空当前线程的<b>全部</b>诊断上下文。
     * <p>用于套件结束或线程归还线程池前，杜绝跨用例 / 跨任务串扰。
     */
    public static void clear() {
        MDC.clear();
    }

    // ═══════════════════════════════════════════════════════════
    // 内部
    // ═══════════════════════════════════════════════════════════

    /** 补齐线程级默认值（threadId）；已存在时不重复写入。 */
    private static void ensureThreadDefaults() {
        if (MDC.get(KEY_THREAD_ID) == null) {
            MDC.put(KEY_THREAD_ID, String.valueOf(Thread.currentThread().threadId()));
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
}
