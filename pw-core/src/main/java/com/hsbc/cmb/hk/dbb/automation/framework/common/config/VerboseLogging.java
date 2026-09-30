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
 * <p><b>门控键（2026-09-28 对齐 master 的 {@code LoggingConfigUtil} 语义）</b>：
 * {@code framework.verbose.logging}（默认 {@code false}）与 {@code framework.trace.logging}
 * （默认 {@code false}）：
 * <ul>
 *   <li>两者皆未开（默认）→ 详细日志一律不输出，日志里只剩"<b>必要信息</b>"——
 *       直调 {@code logger.info/warn/error} 的关键事件（框架启停、浏览器初始化、会话读写、
 *       route 注册、用例开始/结束/结果、失败与告警）；</li>
 *   <li>{@code framework.verbose.logging=true} → 打开 {@code *IfVerbose} 的 info/warn/debug 详细日志；</li>
 *   <li>{@code framework.trace.logging=true} → 同 verbose，并打开 {@code *IfTraceVerbose} 的 trace 日志。</li>
 * </ul>
 *
 * <p><b>与旧实现的差异（重要，防止回退）</b>：旧实现以 {@code serenity.logging=VERBOSE|TRACE} 为门控，
 * 并把 <b>logback root</b> 级别抬到 DEBUG/TRACE —— 后者会把<b>所有</b>包（含第三方）的 DEBUG 一起放出来，
 * 让 {@code logback.xml} 里的抑制失效（实测导致日志刷屏）。现改为：
 * <ol>
 *   <li>门控只认 {@code framework.verbose.logging} / {@code framework.trace.logging}；</li>
 *   <li>提级只作用于<b>本项目包</b> {@code com.hsbc.cmb.hk.dbb.automation}（<b>不动 root</b>），
 *       既让 {@code logDebugIfVerbose} 真正可见，又不放第三方噪音进来。</li>
 * </ol>
 *
 * <p><b>与"录制开关"解耦</b>：{@code SerenityRecorder.isEnabled()} 等<b>行为开关</b>绝不能跟着日志门控
 * 一起关（否则只是关日志却把 Serenity 报告的动作录制也停了）。该历史语义
 * （{@code serenity.logging} 是否为 VERBOSE/TRACE）保留在 {@link #isRecordingEnabled()}。
 */
public final class VerboseLogging {

    private static final Logger LOGGER = LoggerFactory.getLogger(VerboseLogging.class);

    /** 本项目日志包前缀：提级只作用于它，绝不动 root（避免第三方 DEBUG 涌出）。 */
    private static final String PROJECT_LOGGER_NAME = "com.hsbc.cmb.hk.dbb.automation";

    /** 历史键：{@code serenity.logging}（仅 {@link #isRecordingEnabled()} 等历史语义继续使用）。 */
    private static final String SERENITY_LOGGING_KEY = "serenity.logging";

    /** 首次触碰时记录本项目包 logger 的原始级别，关闭 verbose 时还原（不抹掉 logback.xml 配置）。 */
    private static final AtomicReference<Level> ORIGINAL_PROJECT_LEVEL = new AtomicReference<>();
    private static final AtomicBoolean ORIGINAL_CAPTURED = new AtomicBoolean(false);

    private VerboseLogging() {
    }

    /** 是否开启详细日志：{@code framework.verbose.logging} 或 {@code framework.trace.logging} 为 true。 */
    public static boolean isVerboseEnabled() {
        syncProjectLoggerLevel();
        return isVerboseConfigured();
    }

    /** 是否开启 trace 档日志：仅 {@code framework.trace.logging}。 */
    public static boolean isTraceEnabled() {
        syncProjectLoggerLevel();
        return isTraceConfigured();
    }

    /**
     * <b>录制开关（历史语义，与日志门控解耦）</b>：{@code serenity.logging} 为 {@code VERBOSE}/
     * {@code TRACE} 时为 true。
     *
     * <p>为什么单独留一个方法：{@code SerenityRecorder.isEnabled()} 用它决定是否把原生操作写进
     * Serenity 报告（<b>行为</b>，不是日志）。若让它跟随 {@code framework.verbose.logging}，
     * 业务一旦把 verbose 关掉就会连带丢掉报告里的动作记录 —— 静默回归。
     * （既有 5 个录制相关单测正是用 {@code serenity.logging} 开/关录制的，语义必须保持。）</p>
     */
    public static boolean isRecordingEnabled() {
        String level = serenityLoggingLevel();
        return level.equalsIgnoreCase("VERBOSE") || level.equalsIgnoreCase("TRACE");
    }

    private static boolean isVerboseConfigured() {
        return isTraceConfigured() || isTrue(ConfigSource.resolve(
                ConfigKeys.WEB_FRAMEWORK_VERBOSE_LOGGING.key(),
                ConfigKeys.WEB_FRAMEWORK_VERBOSE_LOGGING.defaultValue()));
    }

    private static boolean isTraceConfigured() {
        return isTrue(ConfigSource.resolve(
                ConfigKeys.WEB_FRAMEWORK_TRACE_LOGGING.key(),
                ConfigKeys.WEB_FRAMEWORK_TRACE_LOGGING.defaultValue()));
    }

    /** 宽松布尔：{@code true}/{@code yes}/{@code 1}（忽略大小写）为真，其余（含 null）为假。 */
    private static boolean isTrue(String value) {
        if (value == null) {
            return false;
        }
        String trimmed = value.trim();
        return trimmed.equalsIgnoreCase("true") || trimmed.equalsIgnoreCase("yes") || trimmed.equalsIgnoreCase("1");
    }

    private static String serenityLoggingLevel() {
        // 经 core 统一配置源解析（与框架其它配置读取一致，收敛原先直读 Serenity 环境变量的逻辑）
        return ConfigSource.resolve(SERENITY_LOGGING_KEY, "").trim();
    }

    /**
     * 复位「原始级别」捕获状态（<b>仅供单测</b>）。
     *
     * <p>「原始级别」是<b>进程内一次捕获</b>（首次触碰本项目包 logger 前），生产语义正确但会让单测相互依赖；
     * 本钩子让每个用例都能在已知原始级别的前提下驱动 {@link #syncProjectLoggerLevel()}。</p>
     *
     * @apiNote 仅供同包单测使用，生产路径不得调用。
     */
    static void resetForTests() {
        ORIGINAL_CAPTURED.set(false);
        ORIGINAL_PROJECT_LEVEL.set(null);
    }

    /**
     * 按门控结果同步<b>本项目包</b> logger 的级别（幂等、支持热改与还原；包级可见以便单测直接驱动）。
     *
     * <p><b>为什么不改 root（2026-09-28 对齐 master 语义）</b>：旧实现把 root 抬到 DEBUG/TRACE，
     * 会把<b>所有</b>包（含第三方）的 DEBUG 一并放出来，等于让 {@code logback.xml} 的抑制失效
     * —— 实测导致日志刷屏。现只作用于 {@code com.hsbc.cmb.hk.dbb.automation}：verbose → DEBUG、
     * trace → TRACE、关闭 → 还原首次触碰前的原始级别（不硬编码 INFO，不抹掉 logback.xml 配置）；
     * 级别无变化时不写，避免每行日志都触发一次 setLevel。</p>
     */
    static void syncProjectLoggerLevel() {
        try {
            ch.qos.logback.classic.Logger projectLogger =
                    (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(PROJECT_LOGGER_NAME);
            if (ORIGINAL_CAPTURED.compareAndSet(false, true)) {
                ORIGINAL_PROJECT_LEVEL.set(projectLogger.getLevel());
            }
            Level desired = isTraceConfigured() ? Level.TRACE : (isVerboseConfigured() ? Level.DEBUG : null);
            Level current = projectLogger.getLevel();
            if (desired != null) {
                if (!desired.equals(current)) {
                    projectLogger.setLevel(desired);
                }
            } else if (!java.util.Objects.equals(ORIGINAL_PROJECT_LEVEL.get(), current)) {
                //  关闭 verbose：还原原始级别。原始级别可能为 null（沿用 logback.xml 的继承）——
                //  必须显式 setLevel(null) 才能还原继承，否则会永久卡在 DEBUG（旧实现即此隐患）。
                projectLogger.setLevel(ORIGINAL_PROJECT_LEVEL.get());
            }
        } catch (Throwable e) {
            // 日志级别调整失败不应影响业务；保持 logback.xml 的配置，但不得静默（D7-3）
            LOGGER.debug("[VerboseLogging] failed to sync project logger level, keep logback.xml config: {}",
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
