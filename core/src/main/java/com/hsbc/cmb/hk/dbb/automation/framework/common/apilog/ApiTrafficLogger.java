package com.hsbc.cmb.hk.dbb.automation.framework.common.apilog;

import com.hsbc.cmb.hk.dbb.automation.framework.common.async.AsyncPool;
import com.hsbc.cmb.hk.dbb.automation.framework.common.config.ConfigSource;
import com.hsbc.cmb.hk.dbb.automation.framework.common.config.FrameworkFlags;
import com.hsbc.cmb.hk.dbb.automation.framework.common.security.SensitiveDataSanitizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * HTTP 请求 / 响应<b>脱敏落盘</b> —— D3-3。
 *
 * <p><b>要解决的问题</b>：接口层排障时最需要的是"这条请求到底发了什么、回了什么"，
 * 但直接把 HTTP 报文落盘等于把 token / 密码 / 证件号写进磁盘 —— 在金融级场景不可接受。
 * 本类把"落盘"与"脱敏"强制绑定：<b>任何字段写入前必须经
 * {@link SensitiveDataSanitizer}</b>，调用方无法绕过。
 *
 * <p><b>默认关闭（opt-in）</b>：由 {@code api.traffic.log.enabled} 控制（默认 false）。
 * 与框架其它开关（如 {@code playwright.page.error.failOnError}）保持一致的"默认不打扰"约定；
 * 只有明确需要排查接口问题时才打开。
 *
 * <p><b>不阻塞调用线程</b>：脱敏 + 落盘经 {@link AsyncPool} 异步执行，
 * 避免在 Playwright route 事件线程上做 IO 拖慢拦截链路（队列满时由 AsyncPool 丢弃并计数，
 * 绝不反压拦截线程）。
 *
 * <p><b>落盘格式</b>：按天分文件 {@code <dir>/api-traffic-yyyyMMdd.log}，
 * 单文件超过 {@value #MAX_FILE_SIZE_MB}MB 后停止写入并告警一次（防磁盘被撑爆）。
 *
 * @apiNote framework-internal：由 route 捕获链路调用；业务一般无需直接使用。
 */
public final class ApiTrafficLogger {

    private static final Logger LOGGER = LoggerFactory.getLogger(ApiTrafficLogger.class);

    /**
     * 开关配置键（默认 false）。
     *
     * <p><b>必须用 {@code framework.} 前缀</b>：实测裸键 {@code api.traffic.log.enabled} 会被
     * Serenity 合并配置源解析出非预期值（true），而 {@code framework.api.traffic.log.enabled}
     * 与 {@code api.something.else.enabled} 均正确回落默认值。
     * 框架自有开关统一加前缀，避免与业务 / 上游配置键撞车。
     */
    public static final String ENABLED_KEY = "framework.api.traffic.log.enabled";

    /** 落盘目录配置键（默认 {@code target/logs/api}）。 */
    public static final String DIR_KEY = "framework.api.traffic.log.dir";

    /** 默认落盘目录。 */
    public static final String DEFAULT_DIR = "target/logs/api";

    /** 单文件大小上限（MB）；超过后停止写入，防止磁盘被撑爆。 */
    public static final long MAX_FILE_SIZE_MB = 100L;

    /** 是否已达上限告警过（只告警一次，避免刷日志）。 */
    private static final AtomicBoolean SIZE_WARNED = new AtomicBoolean(false);

    private static final DateTimeFormatter FILE_DATE =
            DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter TS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    private ApiTrafficLogger() {
        // 纯静态门面，禁止实例化
    }

    /**
     * 当前是否启用（实时读取，便于测试与运行期开关）。
     *
     * <p><b>为什么直读系统属性 / 环境变量，而不走 {@link ConfigSource}</b>：
     * {@code ConfigSource} 的第 3 优先级是 SPI 合并源（web 提供的 Serenity 源），
     * 实测该源会<b>缓存</b>系统属性快照 —— 一旦某处 {@code setProperty(key,"true")}
     * 之后再 {@code clearProperty}，SPI 仍返回旧值 "true"，使开关行为不可预期
     * （对"默认必须关闭"的安全开关是不可接受的非确定性）。
     * 本开关属框架基础设施位（非业务配置），故只认系统属性与环境变量，行为确定。
     */
    public static boolean isEnabled() {
        String v = resolveFrameworkFlag(ENABLED_KEY);
        return v != null && Boolean.parseBoolean(v);
    }

    /** 当前落盘目录（同样直读系统属性 / 环境变量，理由见 {@link #isEnabled()}）。 */
    public static Path directory() {
        String dir = resolveFrameworkFlag(DIR_KEY);
        if (dir == null || dir.trim().isEmpty()) {
            dir = DEFAULT_DIR;
        }
        return Paths.get(dir.trim());
    }

    /** 解析框架自有开关：系统属性 → 环境变量 → null（由调用方决定默认值）。 */
    private static String resolveFrameworkFlag(String key) {
        // 统一直读系统属性 / 环境变量（理由见 isEnabled() Javadoc：SPI 合并源会缓存快照）
        return FrameworkFlags.resolve(key, null);
    }

    /** 当前应写入的文件（按天分文件）。 */
    public static Path currentFile() {
        return directory().resolve("api-traffic-" + LocalDateTime.now().format(FILE_DATE) + ".log");
    }

    /**
     * 记录一条 HTTP 报文（异步、脱敏后落盘）。
     *
     * <p>所有入参在进入本方法后<b>立即</b>经 {@link SensitiveDataSanitizer} 处理，
     * 且脱敏在调用线程完成 —— 这样即使后续异步任务被延迟调度，
     * 内存中也不会残留原始敏感值副本。
     *
     * @param handleType      路由处理类型（MOCK / MODIFY / MONITOR / DELAY），可为空
     * @param method          HTTP 方法
     * @param url             请求 URL
     * @param statusCode      响应状态码
     * @param requestHeaders  请求头（可空）
     * @param requestBody     请求体（可空）
     * @param responseHeaders 响应头（可空）
     * @param responseBody    响应体（可空）
     * @param timestampMs     发生时间戳（毫秒）
     */
    public static void record(String handleType, String method, String url, int statusCode,
                              Map<String, String> requestHeaders, String requestBody,
                              Map<String, String> responseHeaders, String responseBody,
                              long timestampMs) {
        if (!isEnabled()) {
            return;
        }

        //  脱敏必须在调用线程同步完成：确保异步任务里只有脱敏后的副本，原始敏感值不跨线程滞留
        final String safeUrl = SensitiveDataSanitizer.sanitizeUrl(url);
        final Map<String, String> safeReqHeaders =
                SensitiveDataSanitizer.sanitizeHeaders(requestHeaders == null
                        ? Collections.emptyMap() : requestHeaders);
        final String safeReqBody = SensitiveDataSanitizer.sanitizeBody(requestBody);
        final Map<String, String> safeRespHeaders =
                SensitiveDataSanitizer.sanitizeHeaders(responseHeaders == null
                        ? Collections.emptyMap() : responseHeaders);
        final String safeRespBody = SensitiveDataSanitizer.sanitizeBody(responseBody);
        final String block = render(handleType, method, safeUrl, statusCode,
                safeReqHeaders, safeReqBody, safeRespHeaders, safeRespBody, timestampMs);

        try {
            AsyncPool.run(() -> writeBlock(block));
        } catch (Exception e) {
            //  落盘失败绝不能影响主流程（排障设施不得成为故障源）
            LOGGER.debug("[ApiTrafficLogger] failed to enqueue traffic log: {}", e.toString());
        }
    }

    // ═══════════════════════════════════════════════════════════
    // 内部
    // ═══════════════════════════════════════════════════════════

    /** 渲染一条人类可读的报文块（入参均已脱敏）。 */
    private static String render(String handleType, String method, String url, int statusCode,
                                 Map<String, String> reqHeaders, String reqBody,
                                 Map<String, String> respHeaders, String respBody,
                                 long timestampMs) {
        StringBuilder sb = new StringBuilder(512);
        sb.append(TS.format(LocalDateTime.now()));
        sb.append(" [").append(handleType == null ? "-" : handleType).append("] ");
        sb.append(method == null ? "-" : method).append(' ').append(url);
        sb.append(" -> status=").append(statusCode);
        sb.append(" (at ").append(timestampMs).append(")\n");
        sb.append("  request.headers: ").append(reqHeaders).append('\n');
        sb.append("  request.body: ").append(reqBody).append('\n');
        sb.append("  response.headers: ").append(respHeaders).append('\n');
        sb.append("  response.body: ").append(respBody).append('\n');
        return sb.toString();
    }

    /** 追加写入（在异步线程执行）。 */
    private static void writeBlock(String block) {
        try {
            Path file = currentFile();
            Path dir = file.getParent();
            if (dir != null) {
                Files.createDirectories(dir);
            }
            if (Files.exists(file) && Files.size(file) > MAX_FILE_SIZE_MB * 1024 * 1024) {
                if (SIZE_WARNED.compareAndSet(false, true)) {
                    LOGGER.warn("[ApiTrafficLogger] traffic log '{}' exceeded {}MB, "
                            + "stop writing to protect disk", file, MAX_FILE_SIZE_MB);
                }
                return;
            }
            Files.write(file, block.getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            LOGGER.debug("[ApiTrafficLogger] failed to write traffic log: {}", e.toString());
        }
    }
}
