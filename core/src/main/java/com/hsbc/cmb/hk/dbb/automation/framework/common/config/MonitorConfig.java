package com.hsbc.cmb.hk.dbb.automation.framework.common.config;

/**
 * 监控 / 持久化配置键<b>门面</b> —— 定义已收敛至 {@link ConfigKeys}。
 *
 * <p>原从 web 的 {@code WebFrameworkConfig} 下沉，用于解耦 {@code route → web} 依赖；
 * 现键名与默认值的<b>唯一事实来源</b>是 {@link ConfigKeys}（{@code MONITOR_*} / {@code API_CAPTURE_*} 条目），
 * 本类仅保留 {@link Key} 形态与读取访问器，使既有调用方（route / persistence 域）零改动。
 *
 * <p>解析统一走 {@link ConfigSource#resolve(String, String)}（支持 serenity.properties / 系统属性 /
 * 环境变量 / {@code ENC()} 透明解密）；键名与默认值经 {@link ConfigKeys} 集中登记，对既有配置零侵入
 * （配置仅按 key 字符串解析，与定义位置无关）。
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
    public static final Key API_CAPTURE_MAX_RESPONSE_SIZE_MB = new Key(ConfigKeys.API_CAPTURE_MAX_RESPONSE_SIZE_MB.key(), ConfigKeys.API_CAPTURE_MAX_RESPONSE_SIZE_MB.defaultValue());

    // ==================== API Monitor 数据库存储配置 ====================
    public static final Key MONITOR_DB_STORE_ENABLED = new Key(ConfigKeys.MONITOR_DB_STORE_ENABLED.key(), ConfigKeys.MONITOR_DB_STORE_ENABLED.defaultValue());
    public static final Key MONITOR_DB_TYPE = new Key(ConfigKeys.MONITOR_DB_TYPE.key(), ConfigKeys.MONITOR_DB_TYPE.defaultValue());
    public static final Key MONITOR_DB_URL = new Key(ConfigKeys.MONITOR_DB_URL.key(), ConfigKeys.MONITOR_DB_URL.defaultValue());
    public static final Key MONITOR_DB_USER = new Key(ConfigKeys.MONITOR_DB_USER.key(), ConfigKeys.MONITOR_DB_USER.defaultValue());
    public static final Key MONITOR_DB_PASSWORD = new Key(ConfigKeys.MONITOR_DB_PASSWORD.key(), ConfigKeys.MONITOR_DB_PASSWORD.defaultValue());
    public static final Key MONITOR_DB_POOL_MAX_SIZE = new Key(ConfigKeys.MONITOR_DB_POOL_MAX_SIZE.key(), ConfigKeys.MONITOR_DB_POOL_MAX_SIZE.defaultValue());

    // ==================== Monitor 测试运行 ID ====================
    public static final Key MONITOR_TEST_RUN_ID = new Key(ConfigKeys.MONITOR_TEST_RUN_ID.key(), ConfigKeys.MONITOR_TEST_RUN_ID.defaultValue());

    // ==================== Monitor body 读取重试（route MonitorHandler，2026-09-17 评审）====================
    /** 重试调度线程数：原为单线程且被所有 context 共享（并行下跨 context 串行瓶颈），现可配。 */
    public static final Key MONITOR_BODY_READ_SCHEDULER_THREADS = new Key(ConfigKeys.MONITOR_BODY_READ_SCHEDULER_THREADS.key(), ConfigKeys.MONITOR_BODY_READ_SCHEDULER_THREADS.defaultValue());

    /**
     * Body 读取并发上限（默认 16）：{@code res.body()} 是 CDP 协议往返（Network.getResponseBody）。
     *
     * <p><b>为什么是 16 而非 2（历史教训）</b>：早期实现把 {@code res.body()} 收敛到 2 线程的读体池，意图
     * 「限制并发 CDP 读取、避免单连接饱和」。但在「即时读体」架构（{@link #MONITOR_BODY_CAPTURE_THREADS}
     * 协调池在 {@code handle()} 中<b>立即</b>提交读体任务）下，2 线程成为致命瓶颈——50 并发时 16 个协调线程
     * 全部排队等这 2 个读线程，读体延迟超出 Chromium 的响应对象回收窗口（约 300~400ms），后期读取时
     * {@code response@} 已被浏览器 GC 回收，抛 {@code Object doesn't exist} → 捕获全部降级（实测 monitor@50 捕获为 0）。
     *
     * <p><b>为什么 16 不会重新饱和连接</b>：原「8 并发打爆 CDP」的结论建立在<b>旧的延迟协调</b>架构上——
     * 观测线程被排队后<b>同时</b>空出、形成雷鸣式群发（thundering herd），且大量读体已超出回收窗口而抛错、
     * 触发<b>重试风暴</b>进一步放大负载。现改为<b>即时协调</b>后，每个协调线程趁<b>各自响应刚到达</b>时读取，
     * 读体时机被<b>摊平</b>到响应到达时刻（而非同时爆发），几乎不触发重试；并发上限同时由协调池（默认 16）
     * 严格收敛，故 16 并发稳态读取既能在回收窗口内完成、又不引入无界群发。
     *
     * <p>16 与 {@link #MONITOR_BODY_CAPTURE_THREADS}（协调池）同值：协调线程提交读体后，读体池总有空闲线程
     * 立即承接，读体任务零排队 → 读体时机完全由「响应到达」决定，不受读体池自身排队拖累。
     */
    public static final Key MONITOR_BODY_READ_CONCURRENCY = new Key(ConfigKeys.MONITOR_BODY_READ_CONCURRENCY.key(), ConfigKeys.MONITOR_BODY_READ_CONCURRENCY.defaultValue());

    /**
     * 即时读体协调池线程数（默认 16）：承载「响应到达即读 body」的协调任务
     * （{@code awaitExistingResponse} 轮询 + 等待 {@link #MONITOR_BODY_READ_CONCURRENCY} 池完成 CDP 读取）。
     *
     * <p><b>为什么需要独立池</b>：根因是高并发下 body 读取被推迟到<b>排队的观测任务</b>里执行，
     * 等观测线程空出时响应体已被浏览器回收（CDP {@code Object doesn't exist}），导致捕获全部降级。
     * 本池的任务在 {@code handle()} 中<b>立即</b>提交（不经观测队列），趁响应刚到达即刻发起
     * {@code res.body()}，把读取时机从「观测调度后」前移到「响应到达时」。这些线程绝大部分时间在
     * 阻塞等待（等响应到达 / 等 CDP 读取完成），故线程数可高于实际并发；真正的 CDP 读取并发仍由
     * {@link #MONITOR_BODY_READ_CONCURRENCY}（默认 2）严格收敛，本池只负责协调，不放大 CDP 压力。
     */
    public static final Key MONITOR_BODY_CAPTURE_THREADS = new Key(ConfigKeys.MONITOR_BODY_CAPTURE_THREADS.key(), ConfigKeys.MONITOR_BODY_CAPTURE_THREADS.defaultValue());
    /** 基础尝试次数（不含按 DELAY 推导的额外次数）。 */
    public static final Key MONITOR_BODY_READ_BASE_ATTEMPTS = new Key(ConfigKeys.MONITOR_BODY_READ_BASE_ATTEMPTS.key(), ConfigKeys.MONITOR_BODY_READ_BASE_ATTEMPTS.defaultValue());
    /** 重试间隔（毫秒）。 */
    public static final Key MONITOR_BODY_READ_RETRY_INTERVAL_MS = new Key(ConfigKeys.MONITOR_BODY_READ_RETRY_INTERVAL_MS.key(), ConfigKeys.MONITOR_BODY_READ_RETRY_INTERVAL_MS.defaultValue());
    /**
     * 等待预算上限（毫秒，默认 30s）：即 route 事件线程等待 body 的<b>最大</b>时长。
     * 必须有上限——长 DELAY 会把尝试次数放大到数百次（预算分钟级），一旦链断/调度器异常，
     * {@code future.get} 会把事件线程长期占住（"卡程序"）。{@code <=0} 表示不设上限（仅用尝试总时长）。
     */
    public static final Key MONITOR_BODY_READ_MAX_WAIT_MS = new Key(ConfigKeys.MONITOR_BODY_READ_MAX_WAIT_MS.key(), ConfigKeys.MONITOR_BODY_READ_MAX_WAIT_MS.defaultValue());

    /**
     * Monitor / Mock / Modify 超时上限（毫秒，默认 5min）：防御性 sanity cap。
     * <p>{@code RouteRule.setTimeoutMs} 在设置超时时会钳制到此上限并打 WARN。
     * 防止极端超时被长期挂在调度器上（持有 MonitorSession / context / rule 引用，延迟 GC；
     * 且若 context 清理遗漏，future 会一直挂到超时时刻才触发）。正常监控时长远低于此值。
     */
    public static final Key MONITOR_TIMEOUT_MAX_MS = new Key(ConfigKeys.MONITOR_TIMEOUT_MAX_MS.key(), ConfigKeys.MONITOR_TIMEOUT_MAX_MS.defaultValue());

    // ==================== MonitorHandler 观测线程池（P0-3 / RT-F1） ====================
    /**
     * MonitorHandler 观测执行器线程数（默认 8）。
     * <p>把 {@code page.waitForResponse} + body 读 + 断言整体移出 Playwright 事件线程后，
     * 由本池承载阻塞等待。线程数应 ≈ 期望的单 context 并发监控请求数上限。
     */
    public static final Key MONITOR_OBSERVE_THREADS = new Key(ConfigKeys.MONITOR_OBSERVE_THREADS.key(), ConfigKeys.MONITOR_OBSERVE_THREADS.defaultValue());

    /**
     * MonitorHandler 观测执行器有界队列容量（默认 4096）。队列满即拒绝并放行请求（绝不反压事件线程）。
     */
    public static final Key MONITOR_OBSERVE_QUEUE_CAPACITY = new Key(ConfigKeys.MONITOR_OBSERVE_QUEUE_CAPACITY.key(), ConfigKeys.MONITOR_OBSERVE_QUEUE_CAPACITY.defaultValue());

    // ==================== API Monitor 文件存储配置 ====================
    public static final Key MONITOR_FILE_STORE_ENABLED = new Key(ConfigKeys.MONITOR_FILE_STORE_ENABLED.key(), ConfigKeys.MONITOR_FILE_STORE_ENABLED.defaultValue());
    public static final Key MONITOR_FILE_STORE_DIR = new Key(ConfigKeys.MONITOR_FILE_STORE_DIR.key(), ConfigKeys.MONITOR_FILE_STORE_DIR.defaultValue());
    public static final Key MONITOR_FILE_STORE_PRETTY = new Key(ConfigKeys.MONITOR_FILE_STORE_PRETTY.key(), ConfigKeys.MONITOR_FILE_STORE_PRETTY.defaultValue());
    /**
     * 文件存储异步写盘队列容量（默认 4096，P0-4 / RT-F2）。队列满即丢弃并计数告警，绝不阻塞事件线程。
     */
    public static final Key MONITOR_FILE_STORE_WRITE_QUEUE_CAPACITY = new Key(ConfigKeys.MONITOR_FILE_STORE_WRITE_QUEUE_CAPACITY.key(), ConfigKeys.MONITOR_FILE_STORE_WRITE_QUEUE_CAPACITY.defaultValue());

    public static final Key MONITOR_FILE_STORE_GROUP_BY_SCENARIO = new Key(ConfigKeys.MONITOR_FILE_STORE_GROUP_BY_SCENARIO.key(), ConfigKeys.MONITOR_FILE_STORE_GROUP_BY_SCENARIO.defaultValue());

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
