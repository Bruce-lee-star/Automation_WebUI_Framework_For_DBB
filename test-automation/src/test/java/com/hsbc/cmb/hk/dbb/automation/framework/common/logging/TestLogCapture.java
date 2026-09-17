package com.hsbc.cmb.hk.dbb.automation.framework.common.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.OutputStreamAppender;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * 测试专用日志捕获器：为指定 logger 挂一个<b>独立的、内存中的 appender</b>。
 *
 * <p><b>为什么需要它</b>：原先依赖框架共享的 {@code target/logs/framework.log} 做端到端断言，
 * 但该文件的写入受全局吞吐、日志滚动（rollover）与其它用例并发影响，
 * 断言在全量运行时会<b>偶发失败</b>（实测多次），属于典型的"测试依赖共享可变状态"反模式。
 *
 * <p>本类把捕获目标隔离到一个内存 {@link ByteArrayOutputStream}（经 logback 的
 * {@link OutputStreamAppender}），随用例结束读取并移除 appender，断言只与本次调用相关，
 * 因此<b>确定、可重复</b>——且不依赖磁盘 flush/读取时序，避免临时文件空读的偶发失败。
 *
 * <p>用于验证"日志出口"相关的两件事：
 * MDC（{@code %X{scenarioId}}）与消息脱敏（{@code %msg} 走注册的 Converter）。
 * 脱敏 converter 为全局 conversionRule 注册（见 logback-test.xml / core logback.xml），
 * 新建的 PatternLayout 同样识别 {@code %msg}，故出口脱敏在此捕获路径上依然生效。
 */
public final class TestLogCapture implements AutoCloseable {

    private final ch.qos.logback.classic.Logger logger;
    private final OutputStreamAppender<ILoggingEvent> appender;
    private final ByteArrayOutputStream sink;
    private final Level originalLevel;

    private TestLogCapture(Class<?> clazz, String pattern) {
        LoggerContext ctx = (LoggerContext) LoggerFactory.getILoggerFactory();

        sink = new ByteArrayOutputStream();

        PatternLayoutEncoder encoder = new PatternLayoutEncoder();
        encoder.setContext(ctx);
        encoder.setPattern(pattern);
        encoder.setCharset(StandardCharsets.UTF_8);
        encoder.start();

        appender = new OutputStreamAppender<>();
        appender.setContext(ctx);
        appender.setEncoder(encoder);
        appender.setOutputStream(sink);
        appender.start();

        logger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(clazz);
        originalLevel = logger.getLevel();
        logger.addAppender(appender);
        logger.setLevel(Level.INFO);
    }

    /** 捕获指定类的日志，pattern 自定（如 {@code "%X{scenarioId} %msg%n"}）。 */
    public static TestLogCapture of(Class<?> clazz, String pattern) {
        return new TestLogCapture(clazz, pattern);
    }

    /** 通过该 logger 输出一条消息。 */
    public void info(String message) {
        logger.info(message);
    }

    /**
     * 生成对「日志出口脱敏」免疫的唯一标记串。
     *
     * <p><b>为何不能直接用 {@code prefix + System.nanoTime()}</b>：{@code %msg} 出口会经
     * {@code SensitiveDataSanitizer} 的值级识别器，其中银行卡号（PAN）候选正则为
     * {@code \b\d(?:[ \-]?\d){12,18}\b}（13~19 位数字），命中后由 Luhn 校验裁定并<b>整体遮蔽</b>为
     * {@code ***[REDACTED]}。长 uptime 的 JVM 中 {@code System.nanoTime()} 恰为 19 位数字，约 1/10
     * 的概率通过 Luhn 校验 → 标记被误罩 → 端到端断言偶发「日志中找不到标记行」（已实测复现：
     * {@code throwable-e2e-1758096000123456789} → {@code throwable-e2e-***[REDACTED]}）。
     *
     * <p>末尾补一个字母，使数字串不再构成 {@code \b...\b} 词边界，从根本上免疫该误罩
     * （实测 {@code e2e-marker-1758096000123456789z} 原样通过）。
     *
     * @param prefix 业务前缀（如 {@code "sanitize-e2e-"}）
     * @return 唯一且不会被出口脱敏改写的标记串
     */
    public static String newMarker(String prefix) {
        return prefix + System.nanoTime() + "z";
    }

    /** 通过该 logger 输出一条带异常的 ERROR（用于验证 {@code %ex} 脱敏出口）。 */
    public void error(String message, Throwable throwable) {
        logger.error(message, throwable);
    }

    /** 停止捕获并返回落盘内容（内存缓冲，已随 stop 强制 flush）。 */
    public String content() {
        if (appender.isStarted()) {
            appender.stop();
        }
        return new String(sink.toByteArray(), StandardCharsets.UTF_8);
    }

    @Override
    public void close() {
        try {
            if (appender.isStarted()) {
                appender.stop();
            }
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(originalLevel);
        }
    }
}
