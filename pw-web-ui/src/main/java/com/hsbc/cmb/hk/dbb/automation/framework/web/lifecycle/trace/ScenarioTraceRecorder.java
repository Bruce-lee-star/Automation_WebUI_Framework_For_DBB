package com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.trace;

import com.hsbc.cmb.hk.dbb.automation.framework.common.config.VerboseLogging;
import com.hsbc.cmb.hk.dbb.automation.framework.core.context.ContextKey;
import com.hsbc.cmb.hk.dbb.automation.framework.core.context.TestContextHolder;
import com.hsbc.cmb.hk.dbb.automation.framework.web.config.FrameworkConfigManager;
import com.hsbc.cmb.hk.dbb.automation.framework.web.config.WebFrameworkConfig;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.PlaywrightManager;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Tracing;
import net.serenitybdd.core.Serenity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;

/**
 * Scenario 级 trace 分段录制器（方案 A，2026-09-17 评审落地）。
 *
 * <h2>为什么需要它</h2>
 * <p>原实现把 `tracing().start()` 放在 <b>context 创建</b>、`stop()` 放在 <b>context 关闭</b>，
 * 于是 trace 的时间区间 = <b>Context 存活期</b>，而不是 case 执行期：
 * <ul>
 *   <li>{@code restart.browser.for.each=scenario}（默认）：区间≈该用例，但起点早于用例（含 context 建立、
 *       会话恢复、登录）；</li>
 *   <li>{@code =feature}：Context 跨 scenario 复用 → <b>一个 trace 覆盖整个 feature 的多个 case</b>，
 *       而文件名/报告标注取的是"关闭时刻"的 scenario（即该 feature 的最后一个用例）→ <b>名实不符</b>，
 *       「点开 trace 看单个失败用例」根本做不到。</li>
 * </ul>
 *
 * <h2>做法（Playwright 原生分段录制）</h2>
 * <p>官方协议（本仓 Playwright 1.62.0 {@code Tracing} 的 javadoc）：每个 context 调一次
 * {@code start(opts)}，随后反复 {@code startChunk()} / {@code stopChunk(path)}，每次得到一个独立 trace 文件。
 * 本类据此在 <b>scenario 边界</b>切段：
 * <pre>
 *   context 创建 → start(opts)             // chunk #0 开始（含 context 建立/会话恢复）
 *   scenario 结束 → stopChunk(trace-&lt;id&gt;-&lt;start&gt;-&lt;end&gt;-PASS|FAIL.zip)
 *   下一个 scenario 开始 → startChunk()    // context 复用（feature 模式）时续段
 *   context 关闭 → 若仍有活动 chunk 则兜底导出（ONCLOSE），随后 stop()
 * </pre>
 * 于是 <b>「trace 的时间 == case 的执行时间」成为结构保证</b>，文件名/报告同时带 scenarioId 与起止时间。
 *
 * <h2>健壮性</h2>
 * <ul>
 *   <li>导出走【每调用独立守护线程 + 有界 join】（无固定线程池、无硬编码池大小、线程按导出需求动态创建）；
 *       任一次 stopChunk 卡死只放弃该次导出，不影响后续导出、也不阻塞 JVM 退出；</li>
 *   <li><b>超时/异常一律删除残缺文件</b> —— 坏 zip 挂进报告比没有更糟；</li>
 *   <li>scenarioId 由调用方显式传入（不依赖 MDC），规避"套件收尾/失败清理路径上 MDC 已解绑 →
 *       文件名退化为 unknown 或错挂到别的用例"；</li>
 *   <li>可通过 {@code playwright.context.trace.chunk.per.scenario=false} 回退为整段录制（逃生开关）。</li>
 * </ul>
 *
 * @apiNote framework-internal：由 Serenity 监听器 / context 生命周期调用，业务代码不得依赖。
 */
public final class ScenarioTraceRecorder {

    private static final Logger logger = LoggerFactory.getLogger(ScenarioTraceRecorder.class);

    /** trace 落盘目录（与 Serenity 报告同目录，报告内可直接下载）。 */
    private static final String TRACE_DIR = "target/site/serenity/traces";

    /** 文件名片段最大长度（防超长名，文件系统上限 255）。 */
    private static final int MAX_NAME_FRAGMENT = 120;

    private static final ContextKey<Boolean> CHUNK_ACTIVE_KEY = ContextKey.of("trace.chunkActive", Boolean.class);
    private static final ContextKey<String> SCENARIO_KEY = ContextKey.of("trace.scenarioId", String.class);
    private static final ContextKey<Long> CHUNK_START_KEY = ContextKey.of("trace.chunkStartMs", Long.class);

    private ScenarioTraceRecorder() {
    }

    private static boolean traceEnabled() {
        return FrameworkConfigManager.getBoolean(WebFrameworkConfig.PLAYWRIGHT_CONTEXT_TRACE_ENABLED);
    }

    private static boolean chunkPerScenario() {
        return FrameworkConfigManager.getBoolean(WebFrameworkConfig.PLAYWRIGHT_CONTEXT_TRACE_CHUNK_PER_SCENARIO);
    }

    private static long exportTimeoutSeconds() {
        return Math.max(1, WebFrameworkConfig.PLAYWRIGHT_CONTEXT_CLOSE_TRACE_TIMEOUT_SECONDS.getIntValue());
    }

    /** context 创建：开启 tracing（chunk #0 同步开始，覆盖 context 建立/会话恢复/首个用例）。 */
    public static void onContextCreated(BrowserContext context) {
        if (context == null || !traceEnabled()) {
            return;
        }
        try {
            context.tracing().start(new Tracing.StartOptions()
                    .setScreenshots(PlaywrightManager.config().isTraceScreenshots())
                    .setSnapshots(PlaywrightManager.config().isTraceSnapshots())
                    .setSources(PlaywrightManager.config().isTraceSources()));
            setChunkActive(true);
            VerboseLogging.logDebugIfVerbose(logger,
                    "[trace] chunk #0 started with context (chunkPerScenario={})", chunkPerScenario());
        } catch (Exception e) {
            // tracing 未启用/已停止时 start 可能抛异常：降级，绝不影响 context 创建
            VerboseLogging.logDebugIfVerbose(logger, "[trace] start skipped: {}", e.getMessage());
        }
    }

    /**
     * scenario 开始：记录用例标识与起点；若当前 chunk 已被上一个用例导出（feature 模式复用 context），
     * 则显式开新 chunk。
     *
     * @param scenarioId 用例标识（由监听器显式传入，如 {@code uniqueTestName}）
     */
    public static void onScenarioStart(String scenarioId) {
        TestContextHolder.get().set(SCENARIO_KEY, scenarioId);
        TestContextHolder.get().set(CHUNK_START_KEY, System.currentTimeMillis());
        if (!traceEnabled() || !chunkPerScenario()) {
            return;
        }
        BrowserContext context = currentContext();
        if (context == null || Boolean.TRUE.equals(chunkActive())) {
            // context 尚未懒创建（创建时会 start → chunk #0 覆盖本用例）；或 chunk #0 仍活动（首个用例）
            return;
        }
        try {
            context.tracing().startChunk();
            setChunkActive(true);
            VerboseLogging.logDebugIfVerbose(logger, "[trace] chunk started for scenario '{}'", scenarioId);
        } catch (Exception e) {
            VerboseLogging.logDebugIfVerbose(logger, "[trace] startChunk skipped: {}", e.getMessage());
        }
    }

    /**
     * scenario 结束：把本用例的 chunk 导出为独立 trace 文件并挂进报告（含起止时间）。
     *
     * @param scenarioId 用例标识（显式传入；{@code null} 时回退到 {@link #onScenarioStart} 记录的值）
     * @param failed     该用例是否失败：{@code null}=未知（收尾兜底路径），写入文件名便于保留策略优先保留失败证据
     */
    public static void onScenarioEnd(String scenarioId, Boolean failed) {
        String id = scenarioId != null ? scenarioId : TestContextHolder.get().get(SCENARIO_KEY);
        Long startMs = TestContextHolder.get().get(CHUNK_START_KEY);
        long endMs = System.currentTimeMillis();
        String outcome = failed == null ? "UNKNOWN" : (failed ? "FAIL" : "PASS");
        try {
            BrowserContext context = currentContext();
            if (!traceEnabled() || context == null || !Boolean.TRUE.equals(chunkActive())) {
                return;
            }
            Path file = Paths.get(TRACE_DIR,
                    traceFileName(id, startMs == null ? endMs : startMs, endMs, outcome));
            exportChunk(context, file, id, startMs, endMs);
        } finally {
            setChunkActive(false);
            clearScenarioState();
        }
    }

    /**
     * context 关闭：若仍有活动 chunk（该用例未走到 {@link #onScenarioEnd}，如收尾/异常路径），
     * 兜底导出为 {@code ONCLOSE} 文件；随后结束 tracing。
     */
    public static void onContextClosing(BrowserContext context) {
        if (context == null || !traceEnabled()) {
            return;
        }
        try {
            if (Boolean.TRUE.equals(chunkActive())) {
                String id = TestContextHolder.get().get(SCENARIO_KEY);
                Long startMs = TestContextHolder.get().get(CHUNK_START_KEY);
                long endMs = System.currentTimeMillis();
                Path file = Paths.get(TRACE_DIR,
                        traceFileName(id, startMs == null ? endMs : startMs, endMs, "ONCLOSE"));
                exportChunk(context, file, id, startMs, endMs);
            } else {
                // 无待导出 chunk：仅结束 tracing（chunk #0 若从未导出，说明本 context 未绑定过用例）
                context.tracing().stop();
            }
        } catch (Exception e) {
            VerboseLogging.logDebugIfVerbose(logger, "[trace] stop skipped on context close: {}", e.getMessage());
        } finally {
            setChunkActive(false);
        }
    }

    /** 导出当前 chunk 到指定文件（每导出独立守护线程 + 有界 join + 超时放弃 + 失败删残片 + 挂报告）。 */
    private static void exportChunk(BrowserContext context, Path file, String scenarioId,
                                    Long startMs, long endMs) {
        try {
            //  Path.getParent() 对无父路径返回 null：显式判空（本仓既有写法，SpotBugs NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE）
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            //  A-10（doc 22）：原实现用【单一共享线程】跑 stopChunk（CompletableFuture.runAsync(..., 该线程池)）。
            //    stopChunk 是阻塞式 native 调用、不可中断；一旦卡住该线程被永久占住 → 此后【所有】导出排队超时、
            //    删除残片 → 后续场景 trace 全丢。改为「每导出独立守护线程 + 有界 join」：卡住的线程是 daemon，
            //    JVM 退出后由 Playwright driver 进程树回收，不阻塞后续导出、也不阻塞 JVM 退出
            //    （线程动态调整：按需创建、无硬编码池大小）。
            Thread exporter = new Thread(() -> {
                try {
                    context.tracing().stopChunk(new Tracing.StopChunkOptions().setPath(file));
                } catch (Throwable t) {
                    logger.warn("[trace] stopChunk failed for {}: {}", file.getFileName(), t.getMessage());
                }
            }, "pw-context-trace-" + (scenarioId == null ? "unknown" : sanitizeForFileName(scenarioId)));
            exporter.setDaemon(true);
            exporter.start();
            try {
                exporter.join(TimeUnit.SECONDS.toMillis(exportTimeoutSeconds()));
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
            if (exporter.isAlive()) {
                //  超时：放弃该次导出（线程 daemon，随 JVM 退出被 driver 回收），删残片，记可观测信号；
                //    注意：不要在此再 join 等待 —— 那会重新把调用线程拖死。
                logger.warn("[trace] export of {} exceeded {}s and was abandoned (trace capability degraded for "
                                + "this scenario; subsequent exports are unaffected). The exporter thread is a daemon "
                                + "and will be reclaimed by the Playwright driver on JVM exit.",
                        file.getFileName(), exportTimeoutSeconds());
                deletePartialFile(file, "timeout(" + exportTimeoutSeconds() + "s)");
                return;
            }
            attachToReport(file, scenarioId, startMs, endMs);
            ArtifactRetention.pruneIfDue();
        } catch (Exception e) {
            // tracing 未启动 / 已停止 / 写盘失败 / 目标被占用：一律删残片，绝不把坏 zip 挂进报告
            deletePartialFile(file, e.getMessage());
        }
    }

    /** 删除不完整/失败的 trace 文件（不存在则 no-op）。 */
    private static void deletePartialFile(Path file, String reason) {
        try {
            if (Files.exists(file)) {
                Files.delete(file);
                logger.warn("[trace] discarded incomplete trace file {} ({})", file.getFileName(), reason);
            } else {
                VerboseLogging.logDebugIfVerbose(logger, "[trace] export failed ({}): {}", reason, file.getFileName());
            }
        } catch (Exception ignored) {
            logger.warn("[trace] failed to delete incomplete trace file {}: {}", file.getFileName(), ignored.getMessage());
        }
    }

    /**
     * 把已落盘的 trace 登记进当前 Serenity 报告（W-3：改用 {@link Serenity#recordReportData()}，
     * 因本仓 Serenity 4.2.0 的 {@code StepEventBus} 无 {@code addAttachmentToCurrentStep}）。
     * 任何失败（非 Serenity 线程 / 步骤上下文已结束）均降级为 debug，绝不抛出。
     */
    private static void attachToReport(Path tracePath, String scenarioId, Long startMs, long endMs) {
        try {
            if (tracePath == null || !Files.exists(tracePath)) {
                return;
            }
            String title = "TRACE" + (scenarioId != null ? ": " + scenarioId : "");
            StringBuilder contents = new StringBuilder();
            contents.append("trace=").append(tracePath.getFileName())
                    .append("\npath=").append(tracePath.toAbsolutePath());
            if (startMs != null) {
                contents.append("\nwindow=")
                        .append(new java.text.SimpleDateFormat("HH:mm:ss.SSS").format(new java.util.Date(startMs)))
                        .append(" → ")
                        .append(new java.text.SimpleDateFormat("HH:mm:ss.SSS").format(new java.util.Date(endMs)))
                        .append(" (").append(Math.max(0, endMs - startMs)).append("ms)")
                        .append("  [覆盖本用例执行窗口]");
            }
            Serenity.recordReportData().withTitle(title).andContents(contents.toString());
            VerboseLogging.logDebugIfVerbose(logger, "[trace] recorded {} to Serenity report", tracePath.getFileName());
        } catch (Exception e) {
            VerboseLogging.logDebugIfVerbose(logger, "[trace] record to report skipped: {}", e.getMessage());
        }
    }

    /**
     * 构造 trace 文件名：{@code trace-<safe(scenarioId)>-<startMs>-<endMs>-<outcome>.zip}。
     *
     * <p>带上 <b>起止时间</b>（原实现只有结束时间戳），既可按时间窗定位，也让"文件名即证据区间"。
     *
     * @param scenarioId 用例标识（可为 null → {@code unknown}）
     * @param startMs    chunk 起点（epoch ms）
     * @param endMs      chunk 终点（epoch ms）
     * @param outcome    {@code PASS} / {@code FAIL} / {@code ONCLOSE}（保留策略据此优先保留失败证据）
     * @return 文件名（不含目录）
     */
    static String traceFileName(String scenarioId, long startMs, long endMs, String outcome) {
        return "trace-" + sanitizeForFileName(scenarioId) + "-" + startMs + "-" + endMs
                + "-" + (outcome == null ? "UNKNOWN" : outcome) + ".zip";
    }

    /**
     * 将任意字符串转为文件系统安全片段：非 {@code [A-Za-z0-9._-]} 字符替换为 {@code _}，截断至 120 字符。
     *
     * @param raw 原始字符串（允许 null）
     * @return 安全片段，绝不为 null/空
     */
    static String sanitizeForFileName(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "unknown";
        }
        StringBuilder sb = new StringBuilder(Math.min(raw.length(), MAX_NAME_FRAGMENT));
        for (int i = 0; i < raw.length() && sb.length() < MAX_NAME_FRAGMENT; i++) {
            char c = raw.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '.' || c == '_' || c == '-') {
                sb.append(c);
            } else {
                sb.append('_');
            }
        }
        return sb.length() == 0 ? "unknown" : sb.toString();
    }

    private static BrowserContext currentContext() {
        try {
            return TestContextHolder.get().get(PlaywrightManager.CONTEXT_KEY);
        } catch (Exception e) {
            return null;
        }
    }

    private static Boolean chunkActive() {
        return TestContextHolder.get().get(CHUNK_ACTIVE_KEY);
    }

    private static void setChunkActive(boolean active) {
        TestContextHolder.get().set(CHUNK_ACTIVE_KEY, active);
    }

    private static void clearScenarioState() {
        TestContextHolder.get().remove(SCENARIO_KEY);
        TestContextHolder.get().remove(CHUNK_START_KEY);
    }
}
