package com.hsbc.cmb.hk.dbb.automation.framework.common.config;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * P2-4 回归：{@code VerboseLogging} 的日志级别必须<b>支持热改与降级</b>。
 *
 * <p><b>修复前</b>：用一次性 {@code CAS} 实现「仅提升一次、绝不降级」—— 运行中改
 * {@code serenity.logging} 不生效；且只要有一次 verbose 查询把 root 抬到 DEBUG，
 * 之后即使配置改回 NORMAL 也<b>永久</b>停在 DEBUG。</p>
 *
 * <p><b>修复后</b>：每次查询比对「期望级别 vs 当前级别」，仅在有变化时写 root；
 * 期望为 NORMAL 时<b>恢复首次触碰前的原始级别</b>（而非硬编码 INFO，不抹掉 logback.xml 配置）。</p>
 *
 * <p>测试直接驱动包级切面 {@code VerboseLogging.syncRootLevel(String)} —— 它是「按配置值同步级别」的
 * 唯一决策点，且不依赖 ConfigSource 的缓存行为，断言确定、可重复。</p>
 *
 * <p>注意：本测试改动 logback root 级别（全局状态），{@code @AfterEach} 恢复原级别以隔离其它用例。</p>
 */
public class VerboseLoggingHotChangeTest {

    @BeforeEach
    void resetCapturedOriginalLevel() {
        //  「原始级别」是进程内一次捕获 —— 复位后每个用例都能在已知原始级别下驱动同步逻辑
        VerboseLogging.resetForTests();
    }

    @AfterEach
    void tearDown() {
        //  恢复为 logback-test.xml 的默认（INFO），避免抬高级别后给同 JVM 其它用例刷日志
        setRootLevel(Level.INFO);
        VerboseLogging.resetForTests();
    }

    @Test
    public void verboseRaisesRootToDebug() {
        VerboseLogging.syncRootLevel("VERBOSE");

        assertEquals(Level.DEBUG, rootLevel(), "VERBOSE 应把 root 提升到 DEBUG");
    }

    @Test
    public void hotChangeToNormalDowngradesBackToOriginalLevel() {
        setRootLevel(Level.WARN);          // 模拟 logback.xml 自定义的原始级别
        VerboseLogging.syncRootLevel("VERBOSE");
        assertEquals(Level.DEBUG, rootLevel(), "前置：verbose 已抬高级别");

        VerboseLogging.syncRootLevel("NORMAL");

        assertEquals(Level.WARN, rootLevel(),
                "P2-4：改回 NORMAL 必须降级回**原始级别**（修复前会永久停在 DEBUG；且不得硬编码 INFO）");
    }

    @Test
    public void traceIsAppliedAndDowngraded() {
        setRootLevel(Level.INFO);

        VerboseLogging.syncRootLevel("TRACE");
        assertEquals(Level.TRACE, rootLevel(), "TRACE 应把 root 提升到 TRACE");

        VerboseLogging.syncRootLevel("QUIET");
        assertEquals(Level.INFO, rootLevel(), "P2-4：非 verbose/trace 时应恢复原始级别（热改可降级）");
    }

    private static Level rootLevel() {
        return ((ch.qos.logback.classic.Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME)).getLevel();
    }

    private static void setRootLevel(Level level) {
        LoggerContext ctx = (LoggerContext) LoggerFactory.getILoggerFactory();
        ctx.getLogger(Logger.ROOT_LOGGER_NAME).setLevel(level);
    }
}
