package com.hsbc.cmb.hk.dbb.automation.framework.common.config;

import ch.qos.logback.classic.Level;
import com.hsbc.cmb.hk.dbb.automation.framework.common.config.ConfigSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 通用日志详细度开关 —— 下沉到 common，消除 common→web 的越层依赖（架构 L2 违规）。
 *
 * <p>日志级别直接复用 Serenity 自身的配置 {@code serenity.logging}
 * （取值参见 Serenity {@code LoggingLevel}：{@code QUIET} / {@code NORMAL} /
 * {@code VERBOSE} / {@code TRACE}），通过 Serenity 的
 * {@link SystemEnvironmentVariables} 统一读取，因此同时遵循
 * {@code serenity.conf} / {@code serenity.properties} / {@code -Dserenity.logging}。
 * 注意：{@link SystemEnvironmentVariables} 为第三方（Serenity）类，引用它不违反
 * common→web 的分层约束（仅禁止反向依赖项目自身的 {@code framework.web} 包）。
 *
 * <p>映射关系（与原 web 层日志工具语义一致，现已统一收口到本类）：
 * <ul>
 *   <li>{@code VERBOSE} → 开启 info/warn 级详细日志（verbose）</li>
 *   <li>{@code TRACE}   → 同时开启 verbose 与 trace 级详细日志</li>
 *   <li>其他（含未配置）→ 关闭</li>
 * </ul>
 *
 * <p>为让详细日志真正可见，首次查询时会按上述映射把 logback root 级别提升
 * （{@code VERBOSE→DEBUG}、{@code TRACE→TRACE}）；仅提升、不降级，且异常安全，
 * 因此 {@code logback.xml} 的 root 默认 {@code INFO} 即可。
 */
public final class VerboseLogging {

    private static final Logger LOGGER = LoggerFactory.getLogger(VerboseLogging.class);

    private static final String SERENITY_LOGGING_KEY = "serenity.logging";
    /** 首次触碰 root 时记录其原始级别，用于「降级回原状」（而非硬编码 INFO，避免抹掉 logback.xml 的配置）。 */
    private static final AtomicReference<Level> ORIGINAL_ROOT_LEVEL = new AtomicReference<>();
    private static final AtomicBoolean ORIGINAL_CAPTURED = new AtomicBoolean(false);

    private VerboseLogging() {
    }

    public static boolean isVerboseEnabled() {
        ensureLogLevelApplied();
        return isVerbose(serenityLoggingLevel());
    }

    public static boolean isTraceEnabled() {
        ensureLogLevelApplied();
        return isTrace(serenityLoggingLevel());
    }

    private static String serenityLoggingLevel() {
        // 经 core 统一配置源解析（与框架其它配置读取一致，收敛原先直读 Serenity 环境变量的逻辑）
        return ConfigSource.resolve(SERENITY_LOGGING_KEY, "").trim();
    }

    private static boolean isVerbose(String level) {
        return level.equalsIgnoreCase("VERBOSE") || isTrace(level);
    }

    private static boolean isTrace(String level) {
        return level.equalsIgnoreCase("TRACE");
    }

    /**
     * 按 {@code serenity.logging} 把 logback root 级别同步到期望值（<b>幂等、支持热改与降级</b>）。
     *
     * <p><b>P2-4 修复</b>：原实现用一次性 CAS「仅提升一次、绝不降级」——导致
     * ① 运行中改 {@code serenity.logging} 不生效（热改失效）；
     * ② 只要有一次 verbose 查询把 root 抬到 DEBUG，之后即使配置改回 NORMAL 也<b>永久</b>停在 DEBUG。
     * 现改为：每次查询时比对「期望级别 vs 当前级别」，仅在有变化时写一次；</p>
     * <ul>
     *   <li>期望级别 = {@code TRACE} → TRACE；{@code VERBOSE} → DEBUG；其余 → <b>恢复首次触碰前的原始级别</b>
     *       （而非硬编码 INFO —— 不抹掉 logback.xml 的配置）；</li>
     *   <li>幂等：级别无变化时不写 root，避免每行日志都触发一次 setLevel；</li>
     *   <li>异常安全：任何异常都只记 debug，绝不破坏日志初始化。</li>
     * </ul>
     */
    private static void ensureLogLevelApplied() {
        syncRootLevel(serenityLoggingLevel());
    }

    /**
     * 复位「原始级别」捕获状态（<b>仅供单测</b>）。
     *
     * <p>「原始级别」是<b>进程内一次捕获</b>（首次触碰 root 前），生产语义正确但会让单测相互依赖；
     * 本钩子让每个用例都能在已知原始级别的前提下驱动 {@link #syncRootLevel(String)}。</p>
     *
     * @apiNote 仅供同包单测使用，生产路径不得调用。
     */
    static void resetForTests() {
        ORIGINAL_CAPTURED.set(false);
        ORIGINAL_ROOT_LEVEL.set(null);
    }

    /**
     * 把 logback root 级别同步到 {@code level} 对应的期望值（P2-4 的核心，包级可见以便单测直接驱动）。
     *
     * @param level {@code serenity.logging} 的取值（{@code VERBOSE} / {@code TRACE} / 其它）
     */
    static void syncRootLevel(String level) {
        try {
            ch.qos.logback.classic.Logger root =
                    (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
            if (ORIGINAL_CAPTURED.compareAndSet(false, true)) {
                ORIGINAL_ROOT_LEVEL.set(root.getLevel());
            }
            Level desired = isTrace(level) ? Level.TRACE : (isVerbose(level) ? Level.DEBUG : null);
            Level target = desired != null ? desired : ORIGINAL_ROOT_LEVEL.get();
            if (target != null && !target.equals(root.getLevel())) {
                root.setLevel(target);
            }
        } catch (Throwable e) {
            // 日志级别调整失败不应影响业务；保持 logback.xml 的配置，但不得静默（D7-3）
            LOGGER.debug("[VerboseLogging] failed to sync root log level, keep logback.xml config: {}",
                    e.toString());
        }
    }

    public static void logInfoIfVerbose(Logger logger, String message) {
        if (isVerboseEnabled()) {
            logger.info(message);
        }
    }

    public static void logInfoIfVerbose(Logger logger, String format, Object... args) {
        if (isVerboseEnabled()) {
            logger.info(format, args);
        }
    }

    public static void logTraceIfVerbose(Logger logger, String message) {
        if (isTraceEnabled()) {
            logger.trace(message);
        }
    }

    public static void logTraceIfVerbose(Logger logger, String format, Object... args) {
        if (isTraceEnabled()) {
            logger.trace(format, args);
        }
    }

    public static void logWarnIfVerbose(Logger logger, String message) {
        if (isVerboseEnabled()) {
            logger.warn(message);
        }
    }

    public static void logWarnIfVerbose(Logger logger, String format, Object... args) {
        if (isVerboseEnabled()) {
            logger.warn(format, args);
        }
    }

    public static void logDebugIfVerbose(Logger logger, String message) {
        if (isVerboseEnabled()) {
            logger.debug(message);
        }
    }

    public static void logDebugIfVerbose(Logger logger, String format, Object... args) {
        if (isVerboseEnabled()) {
            logger.debug(format, args);
        }
    }

    public static void logErrorIfVerbose(Logger logger, String message) {
        if (isVerboseEnabled()) {
            logger.error(message);
        }
    }

    public static void logErrorIfVerbose(Logger logger, String format, Object... args) {
        if (isVerboseEnabled()) {
            logger.error(format, args);
        }
    }
}
