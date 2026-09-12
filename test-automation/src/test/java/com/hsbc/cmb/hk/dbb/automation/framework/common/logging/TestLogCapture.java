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
