package com.hsbc.cmb.hk.dbb.automation.framework.web.route.persistence;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.hsbc.cmb.hk.dbb.automation.framework.web.config.FrameworkConfig;
import com.hsbc.cmb.hk.dbb.automation.framework.web.config.FrameworkConfigManager;
import com.hsbc.cmb.hk.dbb.automation.framework.web.route.core.MonitorCallback;
import com.hsbc.cmb.hk.dbb.automation.framework.web.route.util.SensitiveDataSanitizer;
import net.thucydides.core.steps.StepEventBus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 框架内置的文件存储 Monitor 响应回调。
 *
 * <p><b>用户无需手动注册此回调</b>。框架在 {@link com.hsbc.cmb.hk.dbb.automation.framework.web.route.handler.MonitorHandler}
 * 中自动调用，根据配置决定是否将监控数据写入文件。
 *
 * <p><b>配置控制</b>（serenity.properties）：
 * <pre>{@code
 * # 是否启用文件存储（默认 false，不存储）
 * monitor.file.store.enabled=true
 *
 * # 文件输出目录（相对路径基于工作目录，也可填绝对路径）
 * monitor.file.store.dir=target/monitor-output
 *
 * # 是否美化 JSON 输出（默认 true）
 * monitor.file.store.pretty=true
 *
 * # 是否按 scenario 分组（默认 true）
 * monitor.file.store.group.by.scenario=true
 * }</pre>
 *
 * <p><b>文件命名规则</b>：
 * <ul>
 *   <li>以 endpoint（即 {@code api(urlPattern)} 配置的 urlPattern，缺省时取实际请求 URL）清洗后的安全名称命名</li>
 *   <li>同一 endpoint 在同一 scenario 内被多次捕获 → {@code <endpoint>.json}、{@code <endpoint>_1.json}、
 *       {@code <endpoint>_2.json} …… 依次递增；<b>只有一条时不会出现 _ 后缀</b></li>
 *   <li>{@code group.by.scenario=true}（默认）时，每个 Serenity scenario 写入独立子目录
 *       {@code <outputDir>/<scenario>/<endpoint>.json}，且序号在该 scenario 开始时<b>自动重置</b>。
 *       这样不同 case 之间即使监控同一 endpoint 也互不干扰，不会串号、不会相互覆盖</li>
 *   <li>输出为 JSON 格式，包含 endpoint、requestUrl、method、statusCode、headers、responseBody、capturedAt 等字段</li>
 * </ul>
 *
 * <p><b>跨平台</b>：文件名清洗按运行所在 OS 自适应。
 * 非法字符 / 超长截断在所有系统通用；尾部点空格与保留设备名（CON/PRN/…）仅在 Windows 规避，
 * 避免误伤 macOS / Linux 上合法且区分的字符。Windows 与 macOS（默认）文件系统大小写不敏感，
 * endpoint 文件名统一转小写以防 {@code api_Users} / {@code api_users} 互相覆盖；
 * scenario 子目录因附加原始串 hash 兜底，在任意系统（含大小写不敏感）均唯一。
 * 注意：JSON 内容（endpoint / scenario 字段）始终保留原始字符串大小写与内容。
 *
 * <p><b>安全降级</b>：
 * <ul>
 *   <li>配置 {@code monitor.file.store.enabled=false}（默认）→ 静默跳过，不存储</li>
 *   <li>目录不可写 / 写入失败 → 打 WARN 日志，不抛异常，不中断测试</li>
 *   <li>非 Serenity 上下文（取不到当前 scenario）→ 退化为平铺目录 + JVM 内累计序号（旧行为），不影响存储</li>
 * </ul>
 *
 * <p><b>线程安全</b>：每个 endpoint 的文件名序号使用 {@code ConcurrentHashMap<String, AtomicInteger>}
 * 保证并发安全；scenario 切换时的重置在同步块内完成，避免多线程竞态导致序号错乱。
 * 文件写入在调用方（RouteAsyncPool 异步线程）中同步完成。
 */
public final class FileStoreMonitorCallback implements MonitorCallback {

    private static final Logger LOGGER = LoggerFactory.getLogger(FileStoreMonitorCallback.class);

    /** 单例 */
    public static final FileStoreMonitorCallback INSTANCE = new FileStoreMonitorCallback();

    /** 是否已检查过配置（懒加载，仅检查一次） */
    private volatile boolean configChecked = false;

    /** 是否已启用 */
    private volatile boolean storeEnabled = false;

    /** 输出根目录（检查配置时解析） */
    private volatile File outputDir;

    /** 是否美化 JSON */
    private volatile boolean pretty = true;

    /** 是否按 scenario 分组（默认 true） */
    private volatile boolean groupByScenario = true;

    /** 每个 endpoint 已写入的文件序号（用于 _1、_2 命名），跨整个测试运行 / 当前 scenario 共享 */
    private final ConcurrentHashMap<String, AtomicInteger> counters = new ConcurrentHashMap<>();

    /** scenario 切换重置的同步锁 */
    private final Object resetLock = new Object();

    /** 当前 scenario 的标识（已清洗），用于检测 scenario 切换；null 表示尚无 scenario 上下文 */
    private volatile String currentScenarioKey = null;

    /** 当前 scenario 对应的输出子目录 */
    private volatile File currentScenarioDir = null;

    /** 运行所在操作系统（小写），用于按需应用平台相关规则，而非一刀切。 */
    private static final String OS_NAME =
            System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);

    /** 是否 Windows（NTFS/FAT 对保留名、尾部点/空格有特殊处理）。 */
    private static final boolean IS_WINDOWS = OS_NAME.contains("win");

    /** 是否 macOS（默认 APFS/HFS+ 文件系统大小写不敏感）。 */
    private static final boolean IS_MAC = OS_NAME.contains("mac");

    /**
     * 文件系统是否大小写不敏感：Windows 与 macOS（默认）均不区分大小写，
     * 此时同一个目录下的 {@code api_Users.json} 与 {@code api_users.json} 会相互覆盖，
     * 需将文件名统一转小写以保证跨平台安全；Linux 默认大小写敏感，保留原样。
     */
    private static final boolean CASE_INSENSITIVE_FS = IS_WINDOWS || IS_MAC;

    private FileStoreMonitorCallback() {}

    // ═══════════════════════════════════════════════════════════════
    // MonitorCallback 实现（业务侧注册的回调只传 url）
    // ═══════════════════════════════════════════════════════════════

    @Override
    public void onResponse(String url, int status, String body,
                           Map<String, String> responseHeaders, String method) {
        // 业务侧注册时拿不到 urlPattern，退化为使用 url 作为 endpoint
        onResponse(url, null, status, body, responseHeaders, method);
    }

    // ═══════════════════════════════════════════════════════════════
    // 框架内部调用（带 urlPattern，用于更稳定的 endpoint 命名）
    // ═══════════════════════════════════════════════════════════════

    /**
     * 框架内部入口：携带 urlPattern，命名更贴切。
     */
    public void onResponse(String url, String urlPattern, int status, String body,
                           Map<String, String> responseHeaders, String method) {
        if (!configChecked) {
            checkConfigAndInit();
            configChecked = true;
        }
        if (!storeEnabled) return;

        try {
            String baseName = sanitizeBaseName(urlPattern != null ? urlPattern : url);

            // 确定本次写入的目标目录：按 scenario 分组时每个 scenario 一个子目录
            File targetDir = resolveTargetDir();

            int index = counters.computeIfAbsent(baseName, k -> new AtomicInteger(0))
                    .getAndIncrement();
            String fileName = baseName + (index == 0 ? "" : "_" + index) + ".json";

            Map<String, Object> json = buildJson(urlPattern, url, status, body,
                    responseHeaders, method);

            Gson gson = pretty
                    ? new GsonBuilder().setPrettyPrinting().create()
                    : new Gson();
            String content = gson.toJson(json);

            if (!targetDir.exists() && !targetDir.mkdirs()) {
                LOGGER.warn("[FileStoreMonitorCallback] Cannot create dir '{}', skip write.",
                        targetDir.getAbsolutePath());
                return;
            }

            File target = new File(targetDir, fileName);
            Files.write(target.toPath(), content.getBytes(StandardCharsets.UTF_8));
            LOGGER.debug("[FileStoreMonitorCallback] Wrote monitor data -> {} (endpoint='{}', scenario='{}')",
                    target.getAbsolutePath(),
                    urlPattern != null ? urlPattern : url,
                    currentScenarioKey);

        } catch (Exception e) {
            LOGGER.warn("[FileStoreMonitorCallback] Failed to write monitor file for '{}': {}",
                    url, e.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // scenario 感知：解析当前 scenario，并在切换时重置序号 + 切换目录
    // ═══════════════════════════════════════════════════════════════

    /**
     * 解析当前应写入的目录：
     * <ul>
     *   <li>未启用分组 / 取不到 scenario → 返回输出根目录（平铺，序号 JVM 内累计）</li>
     *   <li>启用分组且取到 scenario：
     *       <ul>
     *         <li>scenario 与上次相同 → 复用之前的子目录</li>
     *         <li>scenario 发生切换 → <b>重置序号计数器</b> 并切换到新子目录（需要重置时即重置）</li>
     *       </ul>
     *   </li>
     * </ul>
     */
    private File resolveTargetDir() {
        if (!groupByScenario) {
            return outputDir;
        }
        String scenarioKey = resolveScenarioKey();
        if (scenarioKey == null) {
            // 取不到 scenario 上下文（例如非 Serenity 环境），退化为平铺根目录
            return outputDir;
        }
        if (!scenarioKey.equals(currentScenarioKey)) {
            synchronized (resetLock) {
                if (!scenarioKey.equals(currentScenarioKey)) {
                    // 需要重置的时候，就重置：新 scenario 序号从 0 开始，并写入独立子目录
                    counters.clear();
                    currentScenarioDir = new File(outputDir, scenarioKey);
                    currentScenarioKey = scenarioKey;
                    LOGGER.debug("[FileStoreMonitorCallback] Scenario switched -> '{}', "
                            + "counters reset, output subdir: {}",
                            scenarioKey, currentScenarioDir.getAbsolutePath());
                }
            }
        }
        return currentScenarioDir != null ? currentScenarioDir : outputDir;
    }

    /**
     * 通过 Serenity 的 StepEventBus 反射获取当前 scenario 的标识。
     * 复用框架 {@code AutoBrowserProcessor} 中已验证可用的反射路径，避免直接依赖
     * Serenity 版本差异导致的编译问题。
     *
     * @return 清洗后的 scenario 标识，取不到时返回 {@code null}
     */
    private String resolveScenarioKey() {
        try {
            StepEventBus eventBus = StepEventBus.getEventBus();
            if (eventBus == null) {
                return null;
            }
            Method m = StepEventBus.class.getDeclaredMethod("currentBaseStepListener");
            m.setAccessible(true);
            Object listener = m.invoke(eventBus);
            if (listener == null) {
                return null;
            }
            Method getOutcome = listener.getClass().getMethod("getCurrentTestOutcome");
            Object outcome = getOutcome.invoke(listener);
            if (outcome == null) {
                return null;
            }
            String name = (String) outcome.getClass().getMethod("getName").invoke(outcome);
            if (name == null || name.isEmpty()) {
                return null;
            }
            return "scenario-" + toSafeDirName(name);
        } catch (Exception e) {
            // 非 Serenity 上下文或早期初始化阶段，属于正常情况
            LOGGER.debug("[FileStoreMonitorCallback] Cannot resolve current scenario ({}), "
                    + "fallback to flat dir.", e.getMessage());
            return null;
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // JSON 构建
    // ═══════════════════════════════════════════════════════════════

    private Map<String, Object> buildJson(String urlPattern, String url, int status,
                                          String body, Map<String, String> responseHeaders,
                                          String method) {
        // ⭐ P0 安全修复：本文件是「落本地磁盘」这条数据出域路径，此前完全绕过脱敏，
        //    Cookie / Authorization / 令牌 / 账号等明文写入 JSON 文件，是系统性泄露点。
        //    脱敏是数据出域的强制收口 —— 与 ApiMonitoringRecord（落库）保持同一标准。
        String safeUrl = SensitiveDataSanitizer.sanitizeUrl(url);
        String safeBody = SensitiveDataSanitizer.sanitizeBody(body);
        Map<String, String> safeHeaders = SensitiveDataSanitizer.sanitizeHeaders(responseHeaders);

        Map<String, Object> json = new LinkedHashMap<>();
        json.put("endpoint", urlPattern != null ? urlPattern : safeUrl);
        json.put("requestUrl", safeUrl);
        json.put("method", method);
        json.put("statusCode", status);
        json.put("requestHeaders", null);
        json.put("responseHeaders", safeHeaders);
        json.put("responseBody", safeBody);
        // ⭐ bodyLength 取【原始】长度：脱敏会改变字符串长度，用脱敏后长度会让
        //    "响应体大小" 这一诊断维度失真（掩码串比真实令牌短或长）。
        json.put("bodyLength", body != null ? body.length() : 0);
        json.put("capturedAt", System.currentTimeMillis());
        json.put("testRunId",
                FrameworkConfigManager.getString(FrameworkConfig.MONITOR_TEST_RUN_ID));
        json.put("scenario", currentScenarioKey);
        json.put("assertionOk", status >= 200 && status < 300);
        return json;
    }

    // ═══════════════════════════════════════════════════════════════
    // endpoint → 文件名 清洗
    // ═══════════════════════════════════════════════════════════════

    /**
     * 保留设备名（仅 Windows / FAT 文件系统禁止作为文件名，macOS / Linux 上合法且无意义）。
     * 作为文件名在 Windows 上会导致创建失败或异常，故仅在该平台规避。
     */
    private static final java.util.Set<String> RESERVED_DEVICE_NAMES = new java.util.HashSet<>(
            java.util.Arrays.asList(
                    "CON", "PRN", "AUX", "NUL",
                    "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
                    "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9"));

    /**
     * 将 endpoint（urlPattern 或 url）清洗为<b>跨平台</b>安全的基础文件名。
     * <ul>
     *   <li>非法字符（非字母数字 / 点 / 下划线 / 连字符）、连续 {@code _}、首尾 {@code _}、
     *       超长截断：所有系统通用。</li>
     *   <li><b>尾部点 / 空格 / 保留设备名</b>：仅 Windows 需规避（macOS / Linux 上这些是合法
     *       且区分的字符，不应误剥，否则反而制造碰撞）。</li>
     *   <li><b>大小写</b>：Windows 与 macOS（默认）文件系统大小写不敏感，统一转小写以避免
     *       同一目录下 {@code api_Users} / {@code api_users} 互相覆盖；Linux 保留原样。</li>
     * </ul>
     */
    private String sanitizeBaseName(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "endpoint";
        }
        String name = raw.replaceAll("[^A-Za-z0-9._-]", "_")
                .replaceAll("_+", "_")
                .replaceAll("^_+|_+$", "");
        if (IS_WINDOWS) {
            // 仅 Windows 会静默丢弃尾部点与空格，造成 report. 与 report 撞名
            name = trimTrailingDotsAndSpaces(name);
        }
        if (name.isEmpty()) {
            name = "endpoint";
        }
        if (name.length() > 120) {
            name = name.substring(0, 120);
        }
        if (IS_WINDOWS) {
            name = trimTrailingDotsAndSpaces(name);
        }
        if (name.isEmpty()) {
            name = "endpoint";
        }
        if (CASE_INSENSITIVE_FS) {
            // 大小写不敏感文件系统：统一小写，避免 NamedX / namedx 互相覆盖
            name = name.toLowerCase(java.util.Locale.ROOT);
        }
        return avoidReservedName(name);
    }

    /**
     * 将 scenario description 清洗为文件系统安全、且<b>全局唯一</b>的目录名。
     *
     * <p>相比 {@link #sanitizeBaseName(String)}，此方法专门解决 scenario description
     * 含特殊字符导致的问题：
     * <ul>
     *   <li><b>中文 / 其它非 ASCII 字符</b>：清洗后可能整体变空或塌缩，若仅靠可读名，
     *       多个不同的中文 scenario 会撞成同一个目录、互相覆盖 —— 因此始终附加
     *       基于<b>原始字符串</b>的短 hash 后缀作为唯一性兜底。</li>
     *   <li><b>非法字符 / 尾部点 / 空格 / 保留设备名</b>：交由 {@link #sanitizeBaseName} 按当前平台处理。</li>
     *   <li><b>超长</b>：可读部分截断，但 hash 保证唯一，不会因截断而撞名。</li>
     * </ul>
     *
     * @param raw scenario 原始名称（getName()）
     * @return 形如 {@code <可读清洗名>-<8位hex hash>} 的安全唯一目录名
     */
    private String toSafeDirName(String raw) {
        String readable = sanitizeBaseName(raw);
        // 可读部分再收紧长度，为 hash 后缀留出空间
        if (readable.length() > 100) {
            readable = trimTrailingDotsAndSpaces(readable.substring(0, 100));
            if (readable.isEmpty()) {
                readable = "endpoint";
            }
        }
        // 基于原始字符串的稳定短 hash：无论清洗如何塌缩，都能唯一区分不同 scenario
        String hash = String.format("%08x", (raw != null ? raw : "").hashCode());
        return readable + "-" + hash;
    }

    /** 去除文件/目录名尾部的点与空格（仅 Windows 会静默丢弃，macOS / Linux 上合法）。 */
    private String trimTrailingDotsAndSpaces(String name) {
        if (name == null) {
            return "";
        }
        int end = name.length();
        while (end > 0) {
            char c = name.charAt(end - 1);
            if (c == '.' || c == ' ') {
                end--;
            } else {
                break;
            }
        }
        return name.substring(0, end);
    }

    /**
     * 若名称（忽略扩展名部分）命中保留设备名，追加下划线以规避。
     * 该规则<b>仅 Windows / FAT 有意义</b>（macOS / Linux 上这些名字合法），
     * 故非 Windows 直接原样返回。
     */
    private String avoidReservedName(String name) {
        if (!IS_WINDOWS) {
            return name;
        }
        String upper = name.toUpperCase(java.util.Locale.ROOT);
        int dot = upper.indexOf('.');
        String stem = dot >= 0 ? upper.substring(0, dot) : upper;
        if (RESERVED_DEVICE_NAMES.contains(stem)) {
            return name + "_";
        }
        return name;
    }

    // ═══════════════════════════════════════════════════════════════
    // 配置检查 & 目录准备
    // ═══════════════════════════════════════════════════════════════

    private void checkConfigAndInit() {
        boolean enabled = FrameworkConfigManager.getBoolean(FrameworkConfig.MONITOR_FILE_STORE_ENABLED);
        if (!enabled) {
            LOGGER.info("[FileStoreMonitorCallback] File store is DISABLED. "
                    + "Set 'monitor.file.store.enabled=true' in serenity.properties to enable.");
            storeEnabled = false;
            return;
        }

        pretty = FrameworkConfigManager.getBoolean(FrameworkConfig.MONITOR_FILE_STORE_PRETTY);
        groupByScenario = FrameworkConfigManager.getBoolean(
                FrameworkConfig.MONITOR_FILE_STORE_GROUP_BY_SCENARIO);

        String dir = FrameworkConfigManager.getString(FrameworkConfig.MONITOR_FILE_STORE_DIR);
        if (dir == null || dir.trim().isEmpty()) {
            dir = "target/monitor-output";
        }

        outputDir = new File(dir);
        if (!outputDir.exists() && !outputDir.mkdirs()) {
            LOGGER.warn("[FileStoreMonitorCallback] Cannot create output dir '{}'. "
                    + "File store will be disabled.", outputDir.getAbsolutePath());
            storeEnabled = false;
            return;
        }
        if (!outputDir.canWrite()) {
            LOGGER.warn("[FileStoreMonitorCallback] Output dir '{}' is not writable. "
                    + "File store will be disabled.", outputDir.getAbsolutePath());
            storeEnabled = false;
            return;
        }

        storeEnabled = true;
        LOGGER.info("[FileStoreMonitorCallback] File store is ENABLED. Output dir: {}, "
                + "groupByScenario: {}", outputDir.getAbsolutePath(), groupByScenario);
    }

    // ═══════════════════════════════════════════════════════════════
    // 生命周期
    // ═══════════════════════════════════════════════════════════════

    /**
     * 重置回调状态与文件序号计数（主要用于测试）。
     */
    void reset() {
        configChecked = false;
        storeEnabled = false;
        outputDir = null;
        groupByScenario = true;
        counters.clear();
        currentScenarioKey = null;
        currentScenarioDir = null;
    }
}
