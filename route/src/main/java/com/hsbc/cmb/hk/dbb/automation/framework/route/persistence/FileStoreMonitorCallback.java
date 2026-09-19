package com.hsbc.cmb.hk.dbb.automation.framework.route.persistence;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.hsbc.cmb.hk.dbb.automation.framework.common.config.MonitorConfig;
import com.hsbc.cmb.hk.dbb.automation.framework.route.core.spi.MonitorCallback;
import com.hsbc.cmb.hk.dbb.automation.framework.common.security.SensitiveDataSanitizer;
import net.thucydides.core.steps.StepEventBus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 框架内置的文件存储 Monitor 响应回调。
 *
 * <p><b>用户无需手动注册此回调</b>。框架在 {@link com.hsbc.cmb.hk.dbb.automation.framework.route.handler.MonitorHandler}
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
 * <b>落盘（mkdirs + {@code Files.write} + 权限收紧）在专用单线程 executor 中异步执行</b>（P0-4 / RT-F2），
 * 调用方（Playwright 事件线程）不再做同步磁盘 IO；队列饱和时计数丢弃并告警。
 */
public final class FileStoreMonitorCallback implements MonitorCallback {

    private static final Logger LOGGER = LoggerFactory.getLogger(FileStoreMonitorCallback.class);

    /**  P2-17：Gson 可复用、线程安全，静态化避免每次写文件 new Gson（参照 ApiMonitoringRepository.GSON） */
    private static final Gson GSON_PRETTY = new GsonBuilder().setPrettyPrinting().create();
    private static final Gson GSON_COMPACT = new Gson();

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

    /**
     * 平铺模式（未按 scenario 分组 / 取不到 scenario 上下文）下的 JVM 内累计序号计数器。
     * 仅此模式使用；按 scenario 分组时走 {@link #scenarioStates} 内各自的计数器，互不串号（RT-C3 / P1-9）。
     */
    private final ConcurrentHashMap<String, AtomicInteger> flatCounters = new ConcurrentHashMap<>();

    /**
     * 按 scenario 隔离的写入状态（RT-C3 / P1-9 收口）：去掉全局 {@code currentScenarioKey} 与全局共享
     * {@code counters} —— 原实现在 scenario 切换时 {@code counters.clear()} 会清空<b>正在运行</b>的其它
     * scenario 的序号，导致跨场景串号 / 串目录。现每个 scenario 持有独立计数器与独立子目录。
     */
    private final ConcurrentHashMap<String, ScenarioState> scenarioStates = new ConcurrentHashMap<>();

    /** 单 scenario 的写入状态（目录 + 序号计数器）。 */
    private static final class ScenarioState {
        final File dir;
        final ConcurrentHashMap<String, AtomicInteger> counters = new ConcurrentHashMap<>();
        ScenarioState(File dir) {
            this.dir = dir;
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // 异步写盘执行器（P0-4 / RT-F2）
    // ═══════════════════════════════════════════════════════════════

    /**
     * 异步写盘执行器：把 {@code Files.write} 移出调用方线程（原实现在 Playwright 事件线程同步写盘，
     * 叠加 MonitorHandler 的阻塞进一步拖垮吞吐）。
     *
     * <p>单线程：保证同一 endpoint 的落盘顺序与提交顺序一致（文件名已在提交侧按序分配）。
     * 有界队列 + 拒绝即计数丢弃：绝不反压调用方（事件线程），与 RT-C2 的「丢弃可观测」策略一致。
     */
    private static final ThreadPoolExecutor WRITE_EXECUTOR = newWriteExecutor();

    /** 队列饱和被丢弃的写次数（观测用）。 */
    private static final java.util.concurrent.atomic.AtomicLong droppedWrites =
            new java.util.concurrent.atomic.AtomicLong();

    private static ThreadPoolExecutor newWriteExecutor() {
        int queue = Math.max(16, MonitorConfig.getInt(MonitorConfig.MONITOR_FILE_STORE_WRITE_QUEUE_CAPACITY, 4096));
        ThreadPoolExecutor ex = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queue),
                r -> {
                    Thread t = new Thread(r, "monitor-file-write");
                    t.setDaemon(true);
                    t.setPriority(Thread.NORM_PRIORITY - 1);
                    return t;
                },
                new ThreadPoolExecutor.AbortPolicy());
        com.hsbc.cmb.hk.dbb.automation.framework.core.lifecycle.ShutdownCoordinator.register(
                com.hsbc.cmb.hk.dbb.automation.framework.core.lifecycle.ShutdownCoordinator.ORDER_MONITOR_HANDLER,
                "monitor-file-write", () -> {
                    ex.shutdown();
                    try {
                        if (!ex.awaitTermination(5, TimeUnit.SECONDS)) {
                            ex.shutdownNow();
                        }
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        ex.shutdownNow();
                    }
                });
        return ex;
    }

    /**
     * 套件收尾：等待在途写盘完成（最多 5s），避免套件结束后仍有写盘任务持有文件句柄/队列。
     *
     * <p>实现：单线程 FIFO 队列中投一个哨兵任务，其执行即代表此前所有写任务已完成（不丢数据）。
     * <b>不关闭</b>线程池本体（同 JVM 内可再次运行；JVM 退出由 {@code ShutdownCoordinator} 关闭）。
     */
    public static void flushForSuiteTeardown() {
        try {
            int pending = WRITE_EXECUTOR.getQueue().size();
            if (pending == 0) {
                return;
            }
            LOGGER.info("[FileStoreMonitorCallback] suite teardown: awaiting {} pending write(s)", pending);
            java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
            WRITE_EXECUTOR.execute(latch::countDown);
            if (!latch.await(5, TimeUnit.SECONDS)) {
                LOGGER.warn("[FileStoreMonitorCallback] suite teardown flush timed out (pending={})",
                        WRITE_EXECUTOR.getQueue().size());
            }
        } catch (Exception e) {
            LOGGER.warn("[FileStoreMonitorCallback] suite teardown flush failed: {}", e.getMessage());
        }
    }

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
        // 业务侧注册时拿不到 urlPattern / requestHeaders，退化为使用 url 作为 endpoint（请求头置空）
        onResponse(url, null, status, body, null, responseHeaders, method);
    }

    // ═══════════════════════════════════════════════════════════════
    // 框架内部调用（带 urlPattern + requestHeaders，用于更稳定的 endpoint 命名与完整落盘）
    // ═══════════════════════════════════════════════════════════════

    /**
     * 框架内部入口：携带 urlPattern 与 requestHeaders，命名更贴切且请求头可落盘（P2-24 修复：补上请求头）。
     */
    public void onResponse(String url, String urlPattern, int status, String body,
                           Map<String, String> requestHeaders, Map<String, String> responseHeaders,
                           String method) {
        if (!configChecked) {
            checkConfigAndInit();
            configChecked = true;
        }
        if (!storeEnabled) return;
        String scenarioKey = groupByScenario ? resolveScenarioKey() : null;
        writeForScenario(scenarioKey, url, urlPattern, status, body, requestHeaders, responseHeaders, method);
    }

    /**
     * 按 scenario 隔离写入（RT-C3 / P1-9 收口）：目录与序号计数器均按 {@code scenarioKey} 隔离，
     * 不再持有全局 {@code currentScenarioKey} 与全局共享 {@code counters}，杜绝并行 scenario 串号 / 串目录。
     *
     * <p><b>package-private 测试 seam</b>：允许单测直接注入 {@code scenarioKey} 验证场景隔离，
     * 无需依赖 Serenity 上下文（生产路径由 {@link #onResponse} 经 {@link #resolveScenarioKey()} 解析）。
     *
     * @param scenarioKey 已解析的 scenario 标识；{@code null} 退化为平铺根目录 + JVM 内累计序号（旧行为）
     */
    void writeForScenario(String scenarioKey, String url, String urlPattern, int status, String body,
                           Map<String, String> requestHeaders, Map<String, String> responseHeaders,
                           String method) {
        if (!storeEnabled) return;
        try {
            String baseName = sanitizeBaseName(urlPattern != null ? urlPattern : url);

            File targetDir;
            ConcurrentHashMap<String, AtomicInteger> counterMap;
            if (scenarioKey == null) {
                //  平铺模式（未分组 / 取不到 scenario 上下文）：沿用 JVM 内累计序号（旧行为）。
                targetDir = outputDir;
                counterMap = flatCounters;
            } else {
                //  每个 scenario 独立子目录 + 独立计数器 → 并行 scenario 互不串号、互不串目录。
                ScenarioState st = scenarioStates.computeIfAbsent(
                        scenarioKey, k -> new ScenarioState(new File(outputDir, k)));
                targetDir = st.dir;
                counterMap = st.counters;
            }

            int index = counterMap.computeIfAbsent(baseName, k -> new AtomicInteger(0))
                    .getAndIncrement();
            String fileName = baseName + (index == 0 ? "" : "_" + index) + ".json";

            Map<String, Object> json = buildJson(urlPattern, url, status, body,
                    requestHeaders, responseHeaders, method, scenarioKey);

            String content = (pretty ? GSON_PRETTY : GSON_COMPACT).toJson(json);

            File target = new File(targetDir, fileName);
            byte[] payload = content.getBytes(StandardCharsets.UTF_8);
            //  P0-4：真正落盘（mkdirs + Files.write + 权限收紧）移出调用方线程。
            //    队列饱和（极端负载）→ 计数丢弃并告警，绝不反压调用方（Playwright 事件线程）。
            try {
                WRITE_EXECUTOR.execute(() -> writeFile(targetDir, target, payload));
            } catch (RejectedExecutionException rex) {
                long dropped = droppedWrites.incrementAndGet();
                LOGGER.error("[FileStoreMonitorCallback] Write queue saturated, dropping '{}' "
                                + "(dropped total: {}): {}",
                        target.getAbsolutePath(), dropped, rex.getMessage());
            }

        } catch (Exception e) {
            LOGGER.warn("[FileStoreMonitorCallback] Failed to build monitor file for '{}': {}",
                    url, e.getMessage());
        }
    }

    /**
     * 真正落盘（在 {@link #WRITE_EXECUTOR} 工作线程执行）：建目录 + 写文件 + 收紧权限。
     *
     * <p>失败仅 WARN，不抛异常（安全降级），不影响测试。
     */
    private static void writeFile(File targetDir, File target, byte[] payload) {
        try {
            if (!targetDir.exists() && !targetDir.mkdirs()) {
                LOGGER.warn("[FileStoreMonitorCallback] Cannot create dir '{}', skip write.",
                        targetDir.getAbsolutePath());
                return;
            }
            Files.write(target.toPath(), payload);
            //  修复 S4：落盘内容虽已脱敏，仍可能含业务数据（URL、响应结构、账号片段）。
            //    target/ 下文件按 umask 创建（常见 002 → 664），在多用户 CI 节点上
            //    同机其它账号可读。尽力收紧为 600（仅属主读写）；非 POSIX 文件系统静默跳过。
            restrictToOwnerOnly(target.toPath());
            LOGGER.debug("[FileStoreMonitorCallback] Wrote monitor data -> {}", target.getAbsolutePath());
        } catch (Exception e) {
            LOGGER.warn("[FileStoreMonitorCallback] Failed to write monitor file '{}': {}",
                    target.getAbsolutePath(), e.getMessage());
        }
    }

    /**
     *  修复 S4：尽力把文件权限收紧为「仅属主可读写」（600）。
     * <p>多用户 CI 节点上 {@code target/} 下的报告文件默认对同机其它账号可读；
     * 非 POSIX 文件系统（如 Windows NTFS）不支持 POSIX 权限，此时静默忽略 ——
     * 权限加固是best-effort，绝不能因设置失败而影响主流程。
     */
    private static void restrictToOwnerOnly(java.nio.file.Path path) {
        try {
            java.nio.file.Files.setPosixFilePermissions(path, java.util.EnumSet.of(
                    java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                    java.nio.file.attribute.PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException e) {
            // 非 POSIX 文件系统（如 Windows）：本就不支持该权限模型，属预期分支，但不得静默（D7-3）
            LOGGER.debug("[FileStoreMonitorCallback] POSIX permission model unsupported on this filesystem, "
                    + "skip restrictToOwnerOnly: {}", e.toString());
        } catch (Exception e) {
            LOGGER.debug("[FileStoreMonitorCallback] Could not restrict permissions on '{}': {}",
                    path, e.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // scenario 感知：解析当前 scenario，并在切换时重置序号 + 切换目录
    // ═══════════════════════════════════════════════════════════════

    //  resolveTargetDir() 已内联到 writeForScenario：目录与序号均按 scenarioKey 隔离（RT-C3 / P1-9），
    //  不再持有全局 scenario 状态，scenario 切换不再 clear 其它正在运行的 scenario 的计数器。

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
                                          String body, Map<String, String> requestHeaders,
                                          Map<String, String> responseHeaders, String method,
                                          String scenarioKey) {
        //  P0 安全修复：本文件是「落本地磁盘」这条数据出域路径，此前完全绕过脱敏，
        //    Cookie / Authorization / 令牌 / 账号等明文写入 JSON 文件，是系统性泄露点。
        //    脱敏是数据出域的强制收口 —— 与 ApiMonitoringRecord（落库）保持同一标准。
        String safeUrl = SensitiveDataSanitizer.sanitizeUrl(url);
        String safeBody = SensitiveDataSanitizer.sanitizeBody(body);
        Map<String, String> safeReqHeaders = SensitiveDataSanitizer.sanitizeHeaders(requestHeaders);
        Map<String, String> safeHeaders = SensitiveDataSanitizer.sanitizeHeaders(responseHeaders);

        Map<String, Object> json = new LinkedHashMap<>();
        json.put("endpoint", urlPattern != null ? urlPattern : safeUrl);
        json.put("requestUrl", safeUrl);
        json.put("method", method);
        json.put("statusCode", status);
        json.put("requestHeaders", safeReqHeaders);
        json.put("responseHeaders", safeHeaders);
        json.put("responseBody", safeBody);
        //  bodyLength 取【原始】长度：脱敏会改变字符串长度，用脱敏后长度会让
        //    "响应体大小" 这一诊断维度失真（掩码串比真实令牌短或长）。
        json.put("bodyLength", body != null ? body.length() : 0);
        json.put("capturedAt", System.currentTimeMillis());
        json.put("testRunId",
                MonitorConfig.getString(MonitorConfig.MONITOR_TEST_RUN_ID));
        json.put("scenario", scenarioKey);
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
        boolean enabled = MonitorConfig.getBoolean(MonitorConfig.MONITOR_FILE_STORE_ENABLED);
        if (!enabled) {
            LOGGER.info("[FileStoreMonitorCallback] File store is DISABLED. "
                    + "Set 'monitor.file.store.enabled=true' in serenity.properties to enable.");
            storeEnabled = false;
            return;
        }

        pretty = MonitorConfig.getBoolean(MonitorConfig.MONITOR_FILE_STORE_PRETTY);
        groupByScenario = MonitorConfig.getBoolean(
                MonitorConfig.MONITOR_FILE_STORE_GROUP_BY_SCENARIO);

        String dir = MonitorConfig.getString(MonitorConfig.MONITOR_FILE_STORE_DIR);
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
        flatCounters.clear();
        scenarioStates.clear();
    }
}
