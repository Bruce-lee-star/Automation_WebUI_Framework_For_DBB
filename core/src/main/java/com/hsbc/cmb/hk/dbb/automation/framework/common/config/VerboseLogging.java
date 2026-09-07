package com.hsbc.cmb.hk.dbb.automation.framework.common.config;

import ch.qos.logback.classic.Level;
import com.hsbc.cmb.hk.dbb.automation.framework.common.config.ConfigSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicBoolean;

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

    private static final String SERENITY_LOGGING_KEY = "serenity.logging";
    private static final AtomicBoolean LOG_LEVEL_APPLIED = new AtomicBoolean(false);

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
     * 按 {@code serenity.logging} 把 logback root 级别提升到 DEBUG/TRACE（仅一次、仅提升）。
     * 放在首次查询时触发，避免依赖任何启动钩子；任何异常都被吞掉，绝不破坏日志初始化。
     */
    private static void ensureLogLevelApplied() {
        if (LOG_LEVEL_APPLIED.compareAndSet(false, true)) {
            String level = serenityLoggingLevel();
            try {
                ch.qos.logback.classic.Logger root =
                        (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
                if (isTrace(level)) {
                    root.setLevel(Level.TRACE);
                } else if (isVerbose(level)) {
                    root.setLevel(Level.DEBUG);
                }
            } catch (Throwable ignored) {
                // 日志级别调整失败不应影响业务；保持 logback.xml 的配置
            }
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
