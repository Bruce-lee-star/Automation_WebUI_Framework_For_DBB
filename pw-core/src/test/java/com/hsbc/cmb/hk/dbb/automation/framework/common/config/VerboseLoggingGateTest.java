package com.hsbc.cmb.hk.dbb.automation.framework.common.config;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.LoggerFactory;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 日志门控契约（2026-09-28 对齐 master 的 {@code LoggingConfigUtil} 语义）。
 *
 * <p>三条不可回退的约束：
 * <ol>
 *   <li>详细日志由 {@code framework.verbose.logging} / {@code framework.trace.logging} 决定，
 *       <b>不再</b>由 {@code serenity.logging} 决定；</li>
 *   <li>提级只作用于<b>本项目包</b> logger，<b>绝不动 root</b>（旧实现抬 root ⇒ 第三方 DEBUG 全量刷屏）；</li>
 *   <li>录制开关（{@code isRecordingEnabled}）与日志门控<b>解耦</b>，仍按 {@code serenity.logging} 判定
 *       —— 否则关日志会连带停掉 Serenity 报告的动作录制。</li>
 * </ol>
 */
public class VerboseLoggingGateTest {

    private static final String SERENITY_LOGGING = ConfigKeys.WEB_SERENITY_LOGGING.key();
    private static final String FRAMEWORK_VERBOSE = ConfigKeys.WEB_FRAMEWORK_VERBOSE_LOGGING.key();
    private static final String FRAMEWORK_TRACE = ConfigKeys.WEB_FRAMEWORK_TRACE_LOGGING.key();
    private static final String PROJECT_LOGGER = "com.hsbc.cmb.hk.dbb.automation";

    private String prevSerenity;
    private String prevVerbose;
    private String prevTrace;
    private Level projectLevelBefore;
    private Level rootLevelBefore;

    @Before
    public void setUp() {
        prevSerenity = System.getProperty(SERENITY_LOGGING);
        prevVerbose = System.getProperty(FRAMEWORK_VERBOSE);
        prevTrace = System.getProperty(FRAMEWORK_TRACE);
        VerboseLogging.resetForTests();
        projectLevelBefore = projectLogger().getLevel();
        rootLevelBefore = rootLogger().getLevel();
        // 模拟 logback.xml 显式配了本项目包级别（真实环境常见），使"还原"断言可判定
        projectLogger().setLevel(Level.INFO);
    }

    @After
    public void tearDown() {
        restore(SERENITY_LOGGING, prevSerenity, ConfigKeys.WEB_SERENITY_LOGGING.defaultValue());
        restore(FRAMEWORK_VERBOSE, prevVerbose, ConfigKeys.WEB_FRAMEWORK_VERBOSE_LOGGING.defaultValue());
        restore(FRAMEWORK_TRACE, prevTrace, ConfigKeys.WEB_FRAMEWORK_TRACE_LOGGING.defaultValue());
        VerboseLogging.isVerboseEnabled(); // 让级别按恢复后的配置重新同步
        projectLogger().setLevel(projectLevelBefore);
        rootLogger().setLevel(rootLevelBefore);
        VerboseLogging.resetForTests();
    }

    /** 刻意用 setProperty 而非 clearProperty：清除会落到 Serenity 启动期快照，值不确定（既有教训）。 */
    private static void restore(String key, String value, String fallback) {
        System.setProperty(key, value != null ? value : fallback);
    }

    private static Logger projectLogger() {
        return (Logger) LoggerFactory.getLogger(PROJECT_LOGGER);
    }

    private static Logger rootLogger() {
        return (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
    }

    @Test
    // @DisplayName: "门控键：framework.verbose.logging 决定日志；serenity.logging 只决定录制（两者解耦）"
    public void gate_followsFrameworkKeys_notSerenity() {
        System.setProperty(SERENITY_LOGGING, "VERBOSE");
        System.setProperty(FRAMEWORK_VERBOSE, "false");
        System.setProperty(FRAMEWORK_TRACE, "false");
        assertFalse("serenity.logging=VERBOSE 不得再开启详细日志", VerboseLogging.isVerboseEnabled());
        assertTrue("录制语义必须保持：serenity.logging=VERBOSE ⇒ 录制开", VerboseLogging.isRecordingEnabled());

        System.setProperty(FRAMEWORK_VERBOSE, "true");
        assertTrue("framework.verbose.logging=true ⇒ 详细日志开", VerboseLogging.isVerboseEnabled());
        assertFalse("verbose 不等于 trace", VerboseLogging.isTraceEnabled());

        System.setProperty(FRAMEWORK_VERBOSE, "false");
        System.setProperty(FRAMEWORK_TRACE, "true");
        assertTrue("framework.trace.logging=true ⇒ trace 开", VerboseLogging.isTraceEnabled());
        assertTrue("trace 蕴含 verbose", VerboseLogging.isVerboseEnabled());

        System.setProperty(SERENITY_LOGGING, "QUIET");
        assertFalse("serenity.logging=QUIET ⇒ 录制关（既有语义）", VerboseLogging.isRecordingEnabled());
        assertTrue("录制开关不得反过来影响日志门控（已解耦）", VerboseLogging.isVerboseEnabled());
    }

    @Test
    // @DisplayName: "提级只作用于本项目包，绝不动 root"
    public void levelSync_touchesProjectLoggerOnly() {
        Level rootBefore = rootLogger().getLevel();

        System.setProperty(FRAMEWORK_VERBOSE, "true");
        System.setProperty(FRAMEWORK_TRACE, "false");
        assertTrue(VerboseLogging.isVerboseEnabled());
        assertEquals("verbose ⇒ 本项目包 DEBUG", Level.DEBUG, projectLogger().getLevel());
        assertEquals("root 级别绝不能被改动", rootBefore, rootLogger().getLevel());

        System.setProperty(FRAMEWORK_TRACE, "true");
        assertTrue(VerboseLogging.isTraceEnabled());
        assertEquals("trace ⇒ 本项目包 TRACE", Level.TRACE, projectLogger().getLevel());
        assertEquals("root 级别绝不能被改动", rootBefore, rootLogger().getLevel());

        System.setProperty(FRAMEWORK_VERBOSE, "false");
        System.setProperty(FRAMEWORK_TRACE, "false");
        assertFalse(VerboseLogging.isVerboseEnabled());
        assertEquals("关闭后必须还原为原始级别（此处 INFO）", Level.INFO, projectLogger().getLevel());
    }

    @Test
    // @DisplayName: "真实输出：关闭时 *IfVerbose 不输出，开启后输出"
    public void output_isGated() {
        Logger logger = (Logger) LoggerFactory.getLogger(PROJECT_LOGGER + ".gate.test");
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.DEBUG);
        try {
            System.setProperty(FRAMEWORK_VERBOSE, "false");
            System.setProperty(FRAMEWORK_TRACE, "false");
            VerboseLogging.logDebugIfVerbose(logger, "hidden");
            assertEquals("门控关闭 ⇒ 详细日志不得输出", 0, appender.list.size());

            System.setProperty(FRAMEWORK_VERBOSE, "true");
            VerboseLogging.logDebugIfVerbose(logger, "visible");
            assertEquals("门控开启 ⇒ 详细日志必须输出", 1, appender.list.size());
            assertEquals("visible", appender.list.get(0).getFormattedMessage());
        } finally {
            logger.detachAppender(appender);
        }
    }
}
