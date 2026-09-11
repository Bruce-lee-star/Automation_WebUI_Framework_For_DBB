package com.hsbc.cmb.hk.dbb.automation.framework.common.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.FileAppender;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 测试专用日志捕获器：为指定 logger 挂一个<b>独立的临时文件 appender</b>。
 *
 * <p><b>为什么需要它</b>：原先依赖框架共享的 {@code target/logs/framework.log} 做端到端断言，
 * 但该文件的写入受全局吞吐、日志滚动（rollover）与其它用例并发影响，
 * 断言在全量运行时会<b>偶发失败</b>（实测多次），属于典型的"测试依赖共享可变状态"反模式。
 *
 * <p>本类把捕获目标隔离到一个临时文件并随用例结束移除，
 * 断言只与本次调用相关，因此<b>确定、可重复</b>。
 *
 * <p>用于验证"日志出口"相关的两件事：
 * MDC（{@code %X{scenarioId}}）与消息脱敏（{@code %msg} 走注册的 Converter）。
 */
public final class TestLogCapture implements AutoCloseable {

    private final ch.qos.logback.classic.Logger logger;
    private final FileAppender<ILoggingEvent> appender;
    private final Path file;
    private final Level originalLevel;

    private TestLogCapture(Class<?> clazz, String pattern) throws Exception {
        this.file = Files.createTempFile("logcapture-", ".log");
        LoggerContext ctx = (LoggerContext) LoggerFactory.getILoggerFactory();

        PatternLayoutEncoder encoder = new PatternLayoutEncoder();
        encoder.setContext(ctx);
        encoder.setPattern(pattern);
        encoder.setCharset(StandardCharsets.UTF_8);
        encoder.start();

        appender = new FileAppender<>();
        appender.setContext(ctx);
        appender.setFile(file.toAbsolutePath().toString());
        appender.setEncoder(encoder);
        appender.start();

        logger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(clazz);
        originalLevel = logger.getLevel();
        logger.addAppender(appender);
        logger.setLevel(Level.INFO);
    }

    /** 捕获指定类的日志，pattern 自定（如 {@code "%X{scenarioId} %msg%n"}）。 */
    public static TestLogCapture of(Class<?> clazz, String pattern) throws Exception {
        return new TestLogCapture(clazz, pattern);
    }

    /** 通过该 logger 输出一条消息。 */
    public void info(String message) {
        logger.info(message);
    }

    /** 停止捕获并返回落盘内容（已强制 flush）。 */
    public String content() throws Exception {
        appender.stop();
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
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
            try {
                Files.deleteIfExists(file);
            } catch (Exception ignored) {
                // 临时文件清理失败不影响断言
            }
        }
    }
}
