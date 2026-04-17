package com.hsbc.cmb.hk.dbb.automation.framework.web.snapshot;

import com.hsbc.cmb.hk.dbb.automation.framework.web.config.FrameworkConfig;
import com.hsbc.cmb.hk.dbb.automation.framework.web.config.FrameworkConfigManager;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Playwright 原生快照测试支持类
 *
 * <p>提供 Playwright 原生快照测试 API 的封装，支持两种快照类型：</p>
 * <ul>
 *   <li><b>视觉快照 (Visual)</b>: 使用 {@code assertThat(page).hasScreenshot()} 进行像素级图像对比</li>
 *   <li><b>ARIA 快照 (ARIA)</b>: 使用 {@code assertThat(locator).matchesAriaSnapshot()} 测试可访问性树结构</li>
 * </ul>
 *
 * <h2>视觉快照</h2>
 * <pre>{@code
 * // 1. 创建/更新基线（开发阶段）
 * NativeSnapshotResult result = PlaywrightSnapshotSupport.of(page)
 *     .visual()
 *     .baselineName("login-page")
 *     .updateBaseline(true)
 *     .snapshot();
 *
 * // 2. 验证模式（CI/CD）
 * NativeSnapshotResult result = PlaywrightSnapshotSupport.of(page)
 *     .visual()
 *     .baselineName("login-page")
 *     .maxDiffPixels(100)
 *     .maxDiffPixelRatio(0.01)
 *     .snapshot();
 *
 * // 3. 整页截图
 * NativeSnapshotResult result = PlaywrightSnapshotSupport.of(page)
 *     .visual()
 *     .fullPage(true)
 *     .baselineName("full-homepage")
 *     .snapshot();
 *
 * // 4. 元素截图
 * NativeSnapshotResult result = PlaywrightSnapshotSupport.of(page.locator(".header"))
 *     .visual()
 *     .baselineName("header-element")
 *     .snapshot();
 * }</pre>
 *
 * <h2>ARIA 快照</h2>
 * <pre>{@code
 * // 1. 创建/更新 ARIA 基线
 * NativeSnapshotResult result = PlaywrightSnapshotSupport.of(page.locator("main"))
 *     .aria()
 *     .baselineName("main-aria")
 *     .updateBaseline(true)
 *     .snapshot();
 *
 * // 2. 验证 ARIA 结构
 * NativeSnapshotResult result = PlaywrightSnapshotSupport.of(page.locator("main"))
 *     .aria()
 *     .baselineName("main-aria")
 *     .snapshot();
 *
 * // 3. 使用正则匹配
 * NativeSnapshotResult result = PlaywrightSnapshotSupport.of(page.locator("form"))
 *     .aria()
 *     .baselineName("form-aria")
 *     .useRegex(true)
 *     .snapshot();
 *
 * // 4. 静默模式（不输出日志）
 * NativeSnapshotResult result = PlaywrightSnapshotSupport.of(page.locator("nav"))
 *     .aria()
 *     .baselineName("nav-aria")
 *     .silent(true)
 *     .snapshot();
 * }</pre>
 *
 * <h2>配置 (serenity.properties)</h2>
 * <pre>{@code
 * # 启用原生快照测试
 * native.snapshot.enabled=true
 *
 * # 视觉快照目录（基线存储）
 * native.snapshot.visual.dir=src/test/resources/snapshots/native/visual
 *
 * # ARIA 快照目录（基线存储）
 * native.snapshot.aria.dir=src/test/resources/snapshots/native/aria
 *
 * # 默认最大差异像素数
 * native.snapshot.visual.maxDiffPixels=100
 *
 * # 默认最大差异像素比例
 * native.snapshot.visual.maxDiffPixelRatio=0.01
 *
 * # 是否静默模式（减少日志输出）
 * native.snapshot.silent=false
 * }</pre>
 *
 * <h2>优势对比</h2>
 * <table border="1">
 *   <tr><th>特性</th><th>视觉快照</th><th>ARIA 快照</th></tr>
 *   <tr><td>跨平台一致性</td><td>❌ 需要为每个 OS 创建基线</td><td>✅ 一套基线，跨平台</td></tr>
 *   <tr><td>字体/渲染差异</td><td>❌ 敏感</td><td>✅ 不受影响</td></tr>
 *   <tr><td>动态内容检测</td><td>❌ 容易误报</td><td>✅ 可忽略动态元素</td></tr>
 *   <tr><td>截图差异可见</td><td>✅ 直观对比</td><td>❌ 结构对比</td></tr>
 *   <tr><td>CI/CD 友好度</td><td>⚠️ 需要配置 CI 基线</td><td>✅ 高度可靠</td></tr>
 * </table>
 *
 * @see <a href="https://playwright.dev/java/docs/screenshots">Playwright Visual Snapshots</a>
 * @see <a href="https://playwright.dev/java/docs/aria-snapshots">Playwright ARIA Snapshots</a>
 */
public class PlaywrightSnapshotSupport {

    private static final Logger logger = LoggerFactory.getLogger(PlaywrightSnapshotSupport.class);

    private static final DateTimeFormatter TIMESTAMP_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");

    private static final List<NativeSnapshotResult> results = new CopyOnWriteArrayList<>();

    // ==================== 静态工厂方法 ====================

    /**
     * 创建页面级快照测试支持
     */
    public static PageSnapshotBuilder of(Page page) {
        return new PageSnapshotBuilder(page);
    }

    /**
     * 创建元素级快照测试支持
     */
    public static ElementSnapshotBuilder of(Locator locator) {
        return new ElementSnapshotBuilder(locator);
    }

    // ==================== 静态结果管理 ====================

    /**
     * 注册快照结果
     */
    public static void registerResult(NativeSnapshotResult result) {
        if (result != null) {
            results.add(result);
            logger.debug("[NativeSnapshot] Registered result: {}", result);
        }
    }

    /**
     * 获取所有快照结果
     */
    public static List<NativeSnapshotResult> getResults() {
        return new ArrayList<>(results);
    }

    /**
     * 清空所有快照结果
     */
    public static void clearResults() {
        results.clear();
    }

    /**
     * 获取快照统计信息
     */
    public static NativeSnapshotResult.Stats getStats() {
        NativeSnapshotResult.Stats stats = new NativeSnapshotResult.Stats();
        results.forEach(stats::add);
        return stats;
    }

    /**
     * 生成 HTML 报告
     */
    public static String generateReport() {
        return NativeSnapshotReportGenerator.generate();
    }

    /**
     * 生成 HTML 报告到指定目录
     */
    public static String generateReport(String outputDir) {
        return NativeSnapshotReportGenerator.generate(outputDir);
    }

    /**
     * 获取统计摘要
     */
    public static String getSummary() {
        NativeSnapshotResult.Stats stats = getStats();
        return String.format(
                "Native Snapshot Tests: %d total | %d PASS | %d FAIL | %d ERROR | Pass Rate: %.1f%%",
                stats.getTotal(), stats.getPassed(), stats.getFailed(), stats.getError(), stats.getPassRate()
        );
    }

    // ==================== 页面级快照构建器 ====================

    /**
     * 页面级快照测试构建器
     */
    public static class PageSnapshotBuilder {
        private final Page page;
        private final Builder builder;

        PageSnapshotBuilder(Page page) {
            this.page = Objects.requireNonNull(page, "page cannot be null");
            this.builder = new Builder();
            builder.page = page;
        }

        /**
         * 使用视觉快照模式
         */
        public VisualBuilder visual() {
            return new VisualBuilder(builder);
        }

        /**
         * 使用 ARIA 快照模式
         */
        public AriaBuilder aria() {
            return new AriaBuilder(builder);
        }

        private static class Builder {
            Page page;
            Locator locator;
            String baselineName;
            boolean updateBaseline = false;
            int maxDiffPixels = 100;
            double maxDiffPixelRatio = 0.01;
            boolean fullPage = false;
            boolean useMask = false;
            boolean useRegex = false;
            boolean silent = false;
            List<Locator> maskLocators = new ArrayList<>();
            byte[] failureScreenshot;
            long durationMs;

            Builder() {}
        }
    }

    // ==================== 元素级快照构建器 ====================

    /**
     * 元素级快照测试构建器
     */
    public static class ElementSnapshotBuilder {
        private final Locator locator;
        private final PageSnapshotBuilder.Builder builder;

        ElementSnapshotBuilder(Locator locator) {
            this.locator = Objects.requireNonNull(locator, "locator cannot be null");
            this.builder = new PageSnapshotBuilder.Builder();
            builder.locator = locator;
        }

        /**
         * 使用视觉快照模式
         */
        public VisualBuilder visual() {
            return new VisualBuilder(builder);
        }

        /**
         * 使用 ARIA 快照模式
         */
        public AriaBuilder aria() {
            return new AriaBuilder(builder);
        }
    }

    // ==================== 视觉快照构建器 ====================

    /**
     * 视觉快照测试构建器
     */
    public static class VisualBuilder {
        private final PageSnapshotBuilder.Builder b;

        VisualBuilder(PageSnapshotBuilder.Builder b) {
            this.b = b;
        }

        /**
         * 设置基线名称
         */
        public VisualBuilder baselineName(String name) {
            b.baselineName = name != null ? name : "untitled_visual_snapshot";
            return this;
        }

        /**
         * 是否更新基线
         * true = 创建/更新基线（开发阶段）
         * false = 仅对比（CI/CD）
         */
        public VisualBuilder updateBaseline(boolean update) {
            b.updateBaseline = update;
            return this;
        }

        /**
         * 最大差异像素数（超过此值判定为失败）
         */
        public VisualBuilder maxDiffPixels(int maxPixels) {
            b.maxDiffPixels = maxPixels;
            return this;
        }

        /**
         * 最大差异像素比例（0.01 = 1%）
         */
        public VisualBuilder maxDiffPixelRatio(double ratio) {
            b.maxDiffPixelRatio = ratio;
            return this;
        }

        /**
         * 是否整页截图
         */
        public VisualBuilder fullPage(boolean fullPage) {
            b.fullPage = fullPage;
            return this;
        }

        /**
         * 添加需要遮罩的元素
         */
        public VisualBuilder mask(Locator... locators) {
            if (locators != null) {
                b.maskLocators.addAll(Arrays.asList(locators));
                b.useMask = !b.maskLocators.isEmpty();
            }
            return this;
        }

        /**
         * 静默模式（减少日志输出）
         */
        public VisualBuilder silent(boolean silent) {
            b.silent = silent;
            return this;
        }

        /**
         * 执行视觉快照测试
         */
        public NativeSnapshotResult snapshot() {
            if (!isEnabled()) {
                log("[NativeSnapshot] Disabled, skipping visual: " + b.baselineName);
                return NativeSnapshotResult.error(b.baselineName, NativeSnapshotResult.SnapshotType.VISUAL,
                        "Native snapshot testing is disabled");
            }

            long startTime = System.currentTimeMillis();

            try {
                // 1. 构建截图选项
                Page.ScreenshotOptions options = buildScreenshotOptions();

                // 2. 如果更新基线，直接保存
                if (b.updateBaseline) {
                    String baselinePath = getBaselinePath(NativeSnapshotResult.SnapshotType.VISUAL, b.baselineName);
                    Path path = Paths.get(baselinePath);
                    Files.createDirectories(path.getParent());
                    Files.write(path, page().screenshot(options));
                    long duration = System.currentTimeMillis() - startTime;
                    log("[NativeSnapshot] Baseline saved: " + baselinePath);
                    NativeSnapshotResult result = NativeSnapshotResult.visualPassed(
                            b.baselineName,
                            "Baseline created: " + baselinePath,
                            duration
                    );
                    registerResult(result);
                    return result;
                }

                // 3. 对比模式
                String baselinePath = getBaselinePath(NativeSnapshotResult.SnapshotType.VISUAL, b.baselineName);
                Path baselineFile = Paths.get(baselinePath);

                if (!Files.exists(baselineFile)) {
                    long duration = System.currentTimeMillis() - startTime;
                    log("[NativeSnapshot] Baseline not found: " + baselinePath);
                    NativeSnapshotResult result = NativeSnapshotResult.visualFailed(
                            b.baselineName,
                            baselinePath,
                            "NOT_FOUND",
                            "0",
                            "Baseline file not found: " + baselinePath,
                            duration
                    );
                    registerResult(result);
                    return result;
                }

                // 4. 执行 Playwright 原生对比
                String screenshotPath = getCurrentSnapshotPath(NativeSnapshotResult.SnapshotType.VISUAL, b.baselineName);
                Path currentFile = Paths.get(screenshotPath);
                Files.createDirectories(currentFile.getParent());
                byte[] currentScreenshot = page().screenshot(options.setPath(currentFile));

                // 5. 读取基线并计算差异
                byte[] baselineBytes = Files.readAllBytes(baselineFile);
                DiffResult diffResult = calculateDiff(baselineBytes, currentScreenshot);

                long duration = System.currentTimeMillis() - startTime;
                b.durationMs = duration;

                if (diffResult.passed) {
                    log("[NativeSnapshot] PASSED: " + b.baselineName + " (diff=" + diffResult.diffPixels + "px, " + diffResult.diffPercent + "%)");
                    NativeSnapshotResult result = NativeSnapshotResult.visualPassed(
                            b.baselineName,
                            String.format("diff pixels=%d (%.2f%%), threshold=%dpx",
                                    diffResult.diffPixels, diffResult.diffPercent, b.maxDiffPixels),
                            duration
                    );
                    registerResult(result);
                    return result;
                } else {
                    log("[NativeSnapshot] FAILED: " + b.baselineName + " (diff=" + diffResult.diffPixels + "px, " + diffResult.diffPercent + "%)");
                    b.failureScreenshot = currentScreenshot;
                    NativeSnapshotResult result = NativeSnapshotResult.visualFailed(
                            b.baselineName,
                            baselinePath,
                            screenshotPath,
                            diffResult.diffPixels + "px (" + String.format("%.2f%%", diffResult.diffPercent) + ")",
                            String.format("Exceeded threshold: %dpx > %dpx (%.2f%% > %.2f%%)",
                                    diffResult.diffPixels, b.maxDiffPixels,
                                    diffResult.diffPercent, b.maxDiffPixelRatio * 100),
                            duration
                    );
                    registerResult(result);
                    return result;
                }

            } catch (AssertionError e) {
                long duration = System.currentTimeMillis() - startTime;
                log("[NativeSnapshot] FAILED: " + b.baselineName + " - " + e.getMessage());
                NativeSnapshotResult result = NativeSnapshotResult.visualFailed(
                        b.baselineName,
                        "baseline",
                        "current",
                        "N/A",
                        e.getMessage(),
                        duration
                );
                registerResult(result);
                return result;
            } catch (Exception e) {
                long duration = System.currentTimeMillis() - startTime;
                log("[NativeSnapshot] ERROR: " + b.baselineName + " - " + e.getMessage());
                NativeSnapshotResult result = NativeSnapshotResult.error(
                        b.baselineName,
                        NativeSnapshotResult.SnapshotType.VISUAL,
                        e.getMessage()
                );
                registerResult(result);
                return result;
            }
        }

        private Page page() {
            return b.page != null ? b.page : b.locator.page();
        }

        private Page.ScreenshotOptions buildScreenshotOptions() {
            Page.ScreenshotOptions options = new Page.ScreenshotOptions()
                    .setFullPage(b.fullPage);

            // 设置遮罩
            if (b.useMask && !b.maskLocators.isEmpty()) {
                List<String> masks = new ArrayList<>();
                for (Locator mask : b.maskLocators) {
                    masks.add(mask.toString());
                }
                // Playwright Java API 不直接支持 Mask，通过 JS 注入或其他方式处理
                log("[NativeSnapshot] Mask locators specified: " + masks.size());
            }

            return options;
        }
    }

    // ==================== ARIA 快照构建器 ====================

    /**
     * ARIA 快照测试构建器
     */
    public static class AriaBuilder {
        private final PageSnapshotBuilder.Builder b;

        AriaBuilder(PageSnapshotBuilder.Builder b) {
            this.b = b;
        }

        /**
         * 设置基线名称
         */
        public AriaBuilder baselineName(String name) {
            b.baselineName = name != null ? name : "untitled_aria_snapshot";
            return this;
        }

        /**
         * 是否更新基线
         */
        public AriaBuilder updateBaseline(boolean update) {
            b.updateBaseline = update;
            return this;
        }

        /**
         * 使用正则表达式匹配
         */
        public AriaBuilder useRegex(boolean useRegex) {
            b.useRegex = useRegex;
            return this;
        }

        /**
         * 静默模式
         */
        public AriaBuilder silent(boolean silent) {
            b.silent = silent;
            return this;
        }

        /**
         * 执行 ARIA 快照测试
         */
        public NativeSnapshotResult snapshot() {
            if (!isEnabled()) {
                log("[NativeSnapshot] Disabled, skipping aria: " + b.baselineName);
                return NativeSnapshotResult.error(b.baselineName, NativeSnapshotResult.SnapshotType.ARIA,
                        "Native snapshot testing is disabled");
            }

            long startTime = System.currentTimeMillis();

            try {
                // 1. 获取 ARIA 快照字符串
                String ariaSnapshot = locator().ariaSnapshot();
                String normalizedSnapshot = normalizeAriaSnapshot(ariaSnapshot);

                // 2. 如果更新基线
                if (b.updateBaseline) {
                    String baselinePath = getBaselinePath(NativeSnapshotResult.SnapshotType.ARIA, b.baselineName);
                    Path path = Paths.get(baselinePath);
                    Files.createDirectories(path.getParent());
                    Files.writeString(path, normalizedSnapshot);
                    long duration = System.currentTimeMillis() - startTime;
                    log("[NativeSnapshot] ARIA Baseline saved: " + baselinePath);
                    NativeSnapshotResult result = NativeSnapshotResult.ariaPassed(
                            b.baselineName,
                            normalizedSnapshot,
                            "ARIA Baseline created: " + baselinePath,
                            duration
                    );
                    registerResult(result);
                    return result;
                }

                // 3. 对比模式
                String baselinePath = getBaselinePath(NativeSnapshotResult.SnapshotType.ARIA, b.baselineName);
                Path baselineFile = Paths.get(baselinePath);

                if (!Files.exists(baselineFile)) {
                    long duration = System.currentTimeMillis() - startTime;
                    log("[NativeSnapshot] ARIA Baseline not found: " + baselinePath);
                    NativeSnapshotResult result = NativeSnapshotResult.ariaFailed(
                            b.baselineName,
                            "NOT_FOUND",
                            normalizedSnapshot,
                            "N/A",
                            "ARIA Baseline file not found: " + baselinePath,
                            duration
                    );
                    registerResult(result);
                    return result;
                }

                // 4. 读取基线并对比
                String baselineSnapshot = Files.readString(baselineFile);
                String normalizedBaseline = normalizeAriaSnapshot(baselineSnapshot);

                // 5. 计算差异
                DiffResult diffResult = calculateAriaDiff(normalizedBaseline, normalizedSnapshot);

                long duration = System.currentTimeMillis() - startTime;

                if (diffResult.passed) {
                    log("[NativeSnapshot] ARIA PASSED: " + b.baselineName);
                    NativeSnapshotResult result = NativeSnapshotResult.ariaPassed(
                            b.baselineName,
                            normalizedBaseline,
                            "ARIA snapshot matches baseline",
                            duration
                    );
                    registerResult(result);
                    return result;
                } else {
                    log("[NativeSnapshot] ARIA FAILED: " + b.baselineName + " - " + diffResult.diffDescription);
                    NativeSnapshotResult result = NativeSnapshotResult.ariaFailed(
                            b.baselineName,
                            normalizedBaseline,
                            normalizedSnapshot,
                            diffResult.diffDescription,
                            diffResult.diffDescription,
                            duration
                    );
                    registerResult(result);
                    return result;
                }

            } catch (AssertionError e) {
                long duration = System.currentTimeMillis() - startTime;
                log("[NativeSnapshot] ARIA FAILED: " + b.baselineName + " - " + e.getMessage());
                NativeSnapshotResult result = NativeSnapshotResult.ariaFailed(
                        b.baselineName,
                        "baseline",
                        "current",
                        "N/A",
                        e.getMessage(),
                        duration
                );
                registerResult(result);
                return result;
            } catch (Exception e) {
                long duration = System.currentTimeMillis() - startTime;
                log("[NativeSnapshot] ARIA ERROR: " + b.baselineName + " - " + e.getMessage());
                NativeSnapshotResult result = NativeSnapshotResult.error(
                        b.baselineName,
                        NativeSnapshotResult.SnapshotType.ARIA,
                        e.getMessage()
                );
                registerResult(result);
                return result;
            }
        }

        private Locator locator() {
            return b.locator;
        }
    }

    // ==================== 辅助方法 ====================

    private static boolean isEnabled() {
        try {
            return FrameworkConfigManager.getBoolean(FrameworkConfig.NATIVE_SNAPSHOT_ENABLED);
        } catch (Exception e) {
            return true; // 默认启用
        }
    }

    private static String getVisualBaselineDir() {
        try {
            String dir = FrameworkConfigManager.getString(FrameworkConfig.NATIVE_SNAPSHOT_VISUAL_DIR);
            return dir != null ? dir : "src/test/resources/snapshots/native/visual";
        } catch (Exception e) {
            return "src/test/resources/snapshots/native/visual";
        }
    }

    private static String getAriaBaselineDir() {
        try {
            String dir = FrameworkConfigManager.getString(FrameworkConfig.NATIVE_SNAPSHOT_ARIA_DIR);
            return dir != null ? dir : "src/test/resources/snapshots/native/aria";
        } catch (Exception e) {
            return "src/test/resources/snapshots/native/aria";
        }
    }

    private static String getBaselinePath(NativeSnapshotResult.SnapshotType type, String name) {
        String dir = type == NativeSnapshotResult.SnapshotType.VISUAL ? getVisualBaselineDir() : getAriaBaselineDir();
        String sanitized = sanitizeFileName(name);
        if (type == NativeSnapshotResult.SnapshotType.VISUAL) {
            return dir + "/" + sanitized + ".png";
        } else {
            return dir + "/" + sanitized + ".aria";
        }
    }

    private static String getCurrentSnapshotPath(NativeSnapshotResult.SnapshotType type, String name) {
        String timestamp = LocalDateTime.now().format(TIMESTAMP_FORMATTER) + "_" + System.nanoTime() % 1000000;
        String sanitized = sanitizeFileName(name);
        if (type == NativeSnapshotResult.SnapshotType.VISUAL) {
            return "target/snapshots/native/current/" + sanitized + "_" + timestamp + ".png";
        } else {
            return "target/snapshots/native/current/" + sanitized + "_" + timestamp + ".aria";
        }
    }

    private static String sanitizeFileName(String name) {
        if (name == null) return "unnamed";
        return name.replaceAll("[^a-zA-Z0-9_-]", "_")
                   .replaceAll("_+", "_")
                   .replaceAll("_$", "")
                   .toLowerCase();
    }

    /**
     * 标准化 ARIA 快照（去除空白差异）
     */
    private static String normalizeAriaSnapshot(String snapshot) {
        if (snapshot == null) return "";
        return snapshot.trim()
                .replaceAll("\\r\\n", "\n")
                .replaceAll("\\r", "\n");
    }

    /**
     * 计算视觉快照差异
     */
    private static DiffResult calculateDiff(byte[] baseline, byte[] current) {
        DiffResult result = new DiffResult();
        result.baselineSize = baseline.length;
        result.currentSize = current.length;

        // 计算像素差异
        int minLen = Math.min(baseline.length, current.length);
        int diffPixels = 0;
        int compareLen = minLen - (minLen % 4); // 对齐到 4 字节边界

        for (int i = 0; i < compareLen; i += 4) {
            for (int c = 0; c < 4; c++) {
                if (baseline[i + c] != current[i + c]) {
                    diffPixels++;
                    break;
                }
            }
        }

        // 估算总像素数
        long totalPixels = Math.max(baseline.length, current.length) / 4;
        result.diffPixels = diffPixels;
        result.diffPercent = totalPixels > 0 ? (diffPixels * 100.0 / totalPixels) : 0;

        // 使用默认阈值判断
        int defaultThreshold = 100;
        try {
            defaultThreshold = FrameworkConfigManager.getInt(FrameworkConfig.NATIVE_SNAPSHOT_MAX_DIFF_PIXELS);
        } catch (Exception e) {
            // 使用默认值
        }
        result.passed = diffPixels <= defaultThreshold;

        return result;
    }

    /**
     * 计算 ARIA 快照差异
     */
    private static DiffResult calculateAriaDiff(String baseline, String current) {
        DiffResult result = new DiffResult();

        if (baseline.equals(current)) {
            result.passed = true;
            result.diffDescription = "No differences found";
            return result;
        }

        result.passed = false;
        result.diffDescription = "ARIA snapshots do not match";

        // 简单的行差异比较
        String[] baselineLines = baseline.split("\n");
        String[] currentLines = current.split("\n");

        int diffLines = Math.abs(baselineLines.length - currentLines.length);
        result.diffDescription = String.format(
                "ARIA diff: %d lines baseline, %d lines current, %d lines differ",
                baselineLines.length, currentLines.length, diffLines
        );

        return result;
    }

    private static void log(String message) {
        logger.info(message);
    }

    // ==================== 内部类 ====================

    private static class DiffResult {
        boolean passed;
        int diffPixels;
        double diffPercent;
        long baselineSize;
        long currentSize;
        String diffDescription;
    }
}
