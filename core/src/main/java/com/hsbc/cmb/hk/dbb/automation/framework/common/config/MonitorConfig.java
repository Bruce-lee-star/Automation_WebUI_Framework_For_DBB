package com.hsbc.cmb.hk.dbb.automation.framework.common.config;

/**
 * 监控 / 持久化配置键集中定义 —— 从 web 的 {@code WebFrameworkConfig} 下沉，用于解耦 {@code route → web} 依赖。
 *
 * <p>解析统一走 {@link ConfigSource#resolve(String, String)}（与 {@code FrameworkConfig} 同一解析器，
 * 支持 serenity.properties / 系统属性 / 环境变量 / {@code ENC()} 透明解密），行为零变更；
 * 配置键名与默认值与 {@code FrameworkConfig} 中对应枚举逐字一致，对既有配置零侵入。
 *
 * @apiNote 仅框架内部 persistence / monitor 域使用；业务代码不应直接依赖。
 */
public final class MonitorConfig {

    private MonitorConfig() {
    }

    /** 配置键（key + 默认值）。 */
    public record Key(String key, String defaultValue) {
    }

    // ==================== API 捕获配置 ====================
    public static final Key API_CAPTURE_MAX_RESPONSE_SIZE_MB =
            new Key("api.capture.max.response.size.mb", "50");

    // ==================== API Monitor 数据库存储配置 ====================
    public static final Key MONITOR_DB_STORE_ENABLED = new Key("monitor.db.store.enabled", "false");
    public static final Key MONITOR_DB_TYPE = new Key("monitor.db.type", "");
    public static final Key MONITOR_DB_URL = new Key("monitor.db.url", "");
    public static final Key MONITOR_DB_USER = new Key("monitor.db.user", "");
    public static final Key MONITOR_DB_PASSWORD = new Key("monitor.db.password", "");
    public static final Key MONITOR_DB_POOL_MAX_SIZE = new Key("monitor.db.pool.max.size", "5");

    // ==================== Monitor 测试运行 ID ====================
    public static final Key MONITOR_TEST_RUN_ID = new Key("monitor.test.run.id", "");

    // ==================== Monitor body 读取重试（route MonitorHandler，2026-09-17 评审）====================
    /** 重试调度线程数：原为单线程且被所有 context 共享（并行下跨 context 串行瓶颈），现可配。 */
    public static final Key MONITOR_BODY_READ_SCHEDULER_THREADS =
            new Key("monitor.body.read.scheduler.threads", "4");
    /** 基础尝试次数（不含按 DELAY 推导的额外次数）。 */
    public static final Key MONITOR_BODY_READ_BASE_ATTEMPTS =
            new Key("monitor.body.read.base.attempts", "3");
    /** 重试间隔（毫秒）。 */
    public static final Key MONITOR_BODY_READ_RETRY_INTERVAL_MS =
            new Key("monitor.body.read.retry.interval.ms", "50");
    /**
     * 等待预算上限（毫秒，默认 30s）：即 route 事件线程等待 body 的<b>最大</b>时长。
     * 必须有上限——长 DELAY 会把尝试次数放大到数百次（预算分钟级），一旦链断/调度器异常，
     * {@code future.get} 会把事件线程长期占住（"卡程序"）。{@code <=0} 表示不设上限（仅用尝试总时长）。
     */
    public static final Key MONITOR_BODY_READ_MAX_WAIT_MS =
            new Key("monitor.body.read.max.wait.ms", "30000");

    /**
     * Monitor / Mock / Modify 超时上限（毫秒，默认 5min）：防御性 sanity cap。
     * <p>{@code RouteRule.setTimeoutMs} 在设置超时时会钳制到此上限并打 WARN。
     * 防止极端超时被长期挂在调度器上（持有 MonitorSession / context / rule 引用，延迟 GC；
     * 且若 context 清理遗漏，future 会一直挂到超时时刻才触发）。正常监控时长远低于此值。
     */
    public static final Key MONITOR_TIMEOUT_MAX_MS =
            new Key("monitor.timeout.max.ms", "300000");

    // ==================== MonitorHandler 观测线程池（P0-3 / RT-F1） ====================
    /**
     * MonitorHandler 观测执行器线程数（默认 8）。
     * <p>把 {@code page.waitForResponse} + body 读 + 断言整体移出 Playwright 事件线程后，
     * 由本池承载阻塞等待。线程数应 ≈ 期望的单 context 并发监控请求数上限。
     */
    public static final Key MONITOR_OBSERVE_THREADS =
            new Key("monitor.observe.threads", "8");

    /**
     * MonitorHandler 观测执行器有界队列容量（默认 4096）。队列满即拒绝并放行请求（绝不反压事件线程）。
     */
    public static final Key MONITOR_OBSERVE_QUEUE_CAPACITY =
            new Key("monitor.observe.queue.capacity", "4096");

    // ==================== API Monitor 文件存储配置 ====================
    public static final Key MONITOR_FILE_STORE_ENABLED = new Key("monitor.file.store.enabled", "false");
    public static final Key MONITOR_FILE_STORE_DIR = new Key("monitor.file.store.dir", "target/monitor-output");
    public static final Key MONITOR_FILE_STORE_PRETTY = new Key("monitor.file.store.pretty", "true");
    /**
     * 文件存储异步写盘队列容量（默认 4096，P0-4 / RT-F2）。队列满即丢弃并计数告警，绝不阻塞事件线程。
     */
    public static final Key MONITOR_FILE_STORE_WRITE_QUEUE_CAPACITY =
            new Key("monitor.file.store.write.queue.capacity", "4096");

    public static final Key MONITOR_FILE_STORE_GROUP_BY_SCENARIO =
            new Key("monitor.file.store.group.by.scenario", "true");

    /** 字符串值（缺失则返回默认值）。 */
    public static String getString(Key k) {
        return ConfigSource.resolve(k.key(), k.defaultValue());
    }

    /** 布尔值：true/yes/1 视为真。 */
    public static boolean getBoolean(Key k) {
        String v = getString(k);
        return v != null && (v.equalsIgnoreCase("true") || v.equalsIgnoreCase("yes") || v.equalsIgnoreCase("1"));
    }

    /** 整数值：解析失败返回兜底值（不抛异常）。 */
    public static int getInt(Key k, int fallbackIfNaN) {
        String v = getString(k);
        try {
            return Integer.parseInt(v);
        } catch (NumberFormatException e) {
            return fallbackIfNaN;
        }
    }

    /** 长整数值：解析失败返回默认值（不抛异常）。 */
    public static long getLong(Key k) {
        String v = getString(k);
        try {
            return Long.parseLong(v);
        } catch (NumberFormatException e) {
            return Long.parseLong(k.defaultValue());
        }
    }
}
