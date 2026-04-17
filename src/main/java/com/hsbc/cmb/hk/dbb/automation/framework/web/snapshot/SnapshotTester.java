package com.hsbc.cmb.hk.dbb.automation.framework.web.snapshot;

import com.hsbc.cmb.hk.dbb.automation.framework.web.config.FrameworkConfig;
import com.hsbc.cmb.hk.dbb.automation.framework.web.config.FrameworkConfigManager;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Objects;

/**
 * Playwright 快照测试（视觉回归测试）- 企业级解决方案
 * 
 * <p>核心功能：</p>
 * <ul>
 *   <li>页面/元素快照捕获与对比</li>
 *   <li>基线管理（创建/更新/删除基线）</li>
 *   <li>差异检测与报告生成</li>
 *   <li>Serenity 报告集成</li>
 *   <li>独立 HTML 对比报告（与 Serenity 完全分离）</li>
 * </ul>
 * 
 * <p>使用方式：</p>
 * <pre>
 * // 1. 创建/更新基线（首次运行时）
 * SnapshotTester.verify(page)
 *     .baselineName("login-page")
 *     .updateBaseline(true)    // 不存在则自动创建基线
 *     .snapshot();
 * 
 * // 2. 验证模式（CI/CD）
 * SnapshotTester.verify(page)
 *     .baselineName("login-page")
 *     .updateBaseline(false)   // 不更新，只对比
 *     .snapshot();
 * 
 * // 3. 元素级快照
 * SnapshotTester.verify(elementLocator)
 *     .baselineName("submit-button")
 *     .updateBaseline(false)
 *     .snapshot();
 * 
 * // 4. 所有快照完成后，生成独立 HTML 报告
 * String reportPath = SnapshotTester.generateReport();
 * </pre>
 * 
 * <p>配置（serenity.properties）：</p>
 * <pre>
 * # 启用快照测试
 * snapshot.testing.enabled=true
 * # 基线存储目录（应放在版本控制中，不要用 target/）
 * snapshot.baseline.dir=src/test/resources/snapshots/baselines
 * # 当前快照和失败截图存储目录
 * snapshot.dir=target/snapshots
 * # 失败时是否自动截图
 * snapshot.failure.screenshot=true
 * # 最大差异像素数阈值（超过此值视为失败）
 * snapshot.diff.threshold=1000
 * # 是否忽略抗锯齿等微小差异
 * snapshot.ignore.antiAlias=true
 * # 全局基线模式（代码 Builder 可覆盖此值）
 * snapshot.update.baseline=false   # false=对比(推荐)  true=创建/更新
 * </pre>
 *
 * <p>优先级：代码 Builder &gt; serenity.properties &gt; 框架默认值</p>
 */
public class SnapshotTester {

    private static final Logger logger = LoggerFactory.getLogger(SnapshotTester.class);
    
    private static final DateTimeFormatter TIMESTAMP_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");
    
    private final Page page;                    // Playwright 页面对象
    private final Locator locator;                // 元素定位器（可为 null，表示整页快照）
    private final String baselineName;           // 基线名称
    
    // 配置项
    private boolean updateBaseline = false;      // 是否更新基线（true=创建/更新，false=仅对比）
    private int maxDiffThreshold = 1000;         // 差异像素阈值（超过此值判定为失败）
    private boolean ignoreAntiAlias = true;        // 是否忽略抗锯齿差异
    private boolean takeFailureScreenshot = true;  // 失败时是否截图
    private String baselineDir;                  // 基线存储目录
    private boolean enabled;                     // 是否启用快照测试
    
    // 结果
    private SnapshotResult lastResult;

    /** Builder 是否显式设置了 updateBaseline（用于判断是否覆盖全局配置） */
    private boolean updateBaselineExplicitlySet = false;
    
    // ==================== 构建器 ====================
    
    /**
     * 私有构造：使用 Builder 创建实例
     */
    private SnapshotTester(Builder builder) {
        this.page = builder.page;
        this.locator = builder.locator;
        this.baselineName = builder.baselineName;
        this.updateBaseline = builder.updateBaseline;
        this.updateBaselineExplicitlySet = builder.updateBaselineExplicitlySet;
        this.maxDiffThreshold = builder.maxDiffThreshold;
        this.ignoreAntiAlias = builder.ignoreAntiAlias;
        this.takeFailureScreenshot = builder.takeFailureScreenshot;
        loadConfiguration();
    }
    
    /**
     * 获取页面的 SnapshotTester
     */
    public static SnapshotTester of(Page page) {
        return new Builder(page).build();
    }
    
    /**
     * 获取元素的 SnapshotTester
     */
    public static SnapshotTester of(Locator locator) {
        return new Builder(locator).build();
    }
    
    /**
     * 创建 Builder
     */
    public static Builder forPage(Page page) {
        return new Builder(page);
    }
    
    public static Builder forElement(Locator locator) {
        return new Builder(locator);
    }
    
    // ==================== 核心方法 ====================
    
    /**
     * 执行快照验证（主要入口方法）
     *
     * 每次调用都会自动将结果注册到 {@link SnapshotReportGenerator}，
     * 用于后续生成独立的 HTML 对比报告。
     *
     * @return 快照结果
     */
    public SnapshotResult snapshot() {
        if (!enabled) {
            logger.debug("Snapshot testing is disabled. Skipping: {}", baselineName);
            lastResult = SnapshotResult.skipped(baselineName, "Snapshot testing is disabled");
            SnapshotReportGenerator.registerResult(lastResult);
            return lastResult;
        }
        
        try {
            Path baselinePath = getBaselinePath(baselineName);
            Path currentPath = getCurrentPath(baselineName);
            
            // 1. 捕获当前快照
            byte[] currentData = captureSnapshot(currentPath);
            
            if (!updateBaseline) {
                // 2a. 对比模式：检查基线是否存在
                if (!Files.exists(baselinePath)) {
                    logger.warn("[Snapshot] Baseline not found: {} (use updateBaseline(true) to create)", baselinePath);
                    lastResult = SnapshotResult.noBaseline(
                        baselineName, "Baseline not found: " + baselinePath);
                    SnapshotReportGenerator.registerResult(lastResult);
                    return lastResult;
                }

                // 3. 执行对比
                byte[] baselineData = Files.readAllBytes(baselinePath);
                lastResult = compareSnapshots(baselineData, currentData, baselineName);
                
                if (!lastResult.isPassed() && takeFailureScreenshot) {
                    captureFailureScreenshot(lastResult);
                }

                SnapshotReportGenerator.registerResult(lastResult);
                return lastResult;
            } else {
                // 2b. 创建/更新基线模式
                ensureParentDirectoryExists(baselinePath);
                Files.write(baselinePath, currentData);
                
                logger.info("[Snapshot] Baseline created/updated: {} ({:} bytes)", 
                    baselinePath, currentData.length);
                    
                lastResult = SnapshotResult.created(
                    baselineName, "Baseline created/updated: " + baselinePath,
                    currentData.length);

                SnapshotReportGenerator.registerResult(lastResult);
                return lastResult;
            }
            
        } catch (Exception e) {
            logger.error("[Snapshot] Failed to execute snapshot test: {} - {}", baselineName, e.getMessage(), e);
            lastResult = SnapshotResult.error(baselineName, "Snapshot failed: " + e.getMessage());
            SnapshotReportGenerator.registerResult(lastResult);
            return lastResult;
        }
    }
    
    /**
     * 获取上一次快照结果
     */
    public SnapshotResult getLastResult() {
        return lastResult;
    }
    
    /**
     * 断言快照匹配（失败时抛出 AssertionError）
     */
    public void assertSnapshot() {
        SnapshotResult result = snapshot();
        if (!result.isPassed()) {
            throw new AssertionError(
                String.format("Snapshot mismatch for '%s': %s", baselineName, result.getDetails())
            );
        }
    }
    
    // ==================== 内部方法 ====================
    
    private byte[] captureSnapshot(Path outputPath) throws IOException {
        ensureParentDirectoryExists(outputPath);

        if (locator != null) {
            // 元素截图 - 使用 Locator.ScreenshotOptions
            locator.screenshot(new Locator.ScreenshotOptions().setPath(outputPath));
            logger.debug("[Snapshot] Element snapshot captured: {}", outputPath);
        } else {
            // 整页截图
            page.screenshot(new Page.ScreenshotOptions()
                    .setPath(outputPath)
                    .setFullPage(true));
            logger.debug("[Snapshot] Full page snapshot captured: {}", outputPath);
        }

        return Files.readAllBytes(outputPath);
    }
    
    private SnapshotResult compareSnapshots(byte[] baseline, byte[] current, String name) {
        long diffPixels = countDiffPixels(baseline, current);
        double totalPixels = baseline.length / 4; // RGBA = 4 bytes per pixel
        double diffPercent = totalPixels > 0 ? (diffPixels * 100.0 / totalPixels) : 0;
        
        boolean passed = diffPixels <= maxDiffThreshold;
        
        String details = String.format(
            "diff pixels=%d (%.2f%%), threshold=%d",
            diffPixels, diffPercent, maxDiffThreshold
        );
        
        return passed
            ? SnapshotResult.passed(name, details, (int) diffPixels, (int) Math.round(diffPercent))
            : SnapshotResult.failed(name, details, (int) diffPixels, (int) Math.round(diffPercent),
                (long) baseline.length, (long) current.length);
    }
    
    /**
     * 计算差异像素数（简单实现，逐字节对比 RGBA）
     * 注意：这是简化版，生产环境可替换为 ImageIO 的像素级对比
     */
    private int countDiffPixels(byte[] baseline, byte[] current) {
        int minLen = Math.min(baseline.length, current.length);
        int diffCount = 0;
        
        for (int i = 0; i < minLen; i++) {
            if (baseline[i] != current[i]) {
                diffCount++;
            }
        }
        
        // 如果长度不同，多出的部分也算差异
        diffCount += Math.abs(baseline.length - current.length);
        
        return ignoreAntiAlias ? Math.max(0, (diffCount / 4) - getAntiAliasTolerance(minLen)) : diffCount;
    }
    
    /**
     * 抗锯齿容忍度（基于图像大小估算）
     */
    private int getAntiAliasTolerance(int dataLength) {
        // 粗略估算：假设每 ~2700 字节对应 1 万像素，允许 0.5% 的抗锯齿噪声
        return Math.max(50, (int)(dataLength / 2700 * 50));
    }
    
    private void captureFailureScreenshot(SnapshotResult result) {
        try {
            String failurePath = getFailurePath(baselineName).toString();

            if (locator != null) {
                locator.screenshot(new Locator.ScreenshotOptions().setPath(Paths.get(failurePath)));
            } else {
                page.screenshot(new Page.ScreenshotOptions()
                        .setPath(Paths.get(failurePath))
                        .setFullPage(true));
            }
            
            result.setFailureScreenshotPath(failurePath);
            logger.info("[Snapshot] Failure screenshot saved: {}", failurePath);
        } catch (Exception e) {
            logger.warn("[Snapshot] Could not capture failure screenshot: {}", e.getMessage());
        }
    }
    
    private String buildJsonReport(SnapshotResult result) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        appendJson(sb, "name", result.getName());
        appendJson(sb, "status", result.getStatus());
        appendJson(sb, "passed", result.isPassed());
        appendJson(sb, "details", result.getDetails());
        appendJson(sb, "diffPixels", result.getDiffPixels());
        appendJson(sb, "diffPercent", result.getDiffPercent());
        appendJson(sb, "baselineSize", result.getBaselineSize());
        appendJson(sb, "currentSize", result.getCurrentSize());
        appendJson(sb, "failureScreenshot", result.getFailureScreenshotPath());
        sb.append("}\n");
        return sb.toString();
    }
    
    private void appendJson(StringBuilder sb, String key, Object value) {
        sb.append("  \"").append(key).append("\": ");
        if (value instanceof String) {
            sb.append("\"").append(escapeJson((String) value)).append("\"");
        } else {
            sb.append(value);
        }
        sb.append(",\n");
    }
    
    // ==================== 路径管理 ====================
    
    private Path getBaselinePath(String name) {
        return Paths.get(baselineDir, sanitizeFileName(name) + ".png");
    }
    
    private Path getCurrentPath(String name) {
        return Paths.get(getSnapshotsDir(), "current", 
            sanitizeFileName(name) + "_" + LocalDateTime.now().format(TIMESTAMP_FORMATTER) + ".png");
    }
    
    private Path getFailurePath(String name) {
        return Paths.get(getSnapshotsDir(), "failures",
            sanitizeFileName(name) + "_" + LocalDateTime.now().format(TIMESTAMP_FORMATTER) + ".png");
    }
    
    private void ensureParentDirectoryExists(Path filePath) throws IOException {
        Path parent = filePath.getParent();
        if (parent != null && !Files.exists(parent)) {
            Files.createDirectories(parent);
        }
    }
    
    // ==================== 配置加载 ====================
    
    private void loadConfiguration() {
        // 全局配置只在用户未通过 Builder 显式设置时才生效（Builder 优先）
        this.enabled = isEnabled();

        // 基线目录：始终从配置读取（Builder 不支持设置此属性）
        this.baselineDir = getBaselineDir();

        // 以下属性：Builder 显式设置则跳过全局配置，否则读取全局默认值
        if (!this.updateBaselineExplicitlySet) {
            this.updateBaseline = isUpdateBaselineGlobal();
        }
        // maxDiffThreshold: 同理（如果需要也可加 explicit flag）
        // ignoreAntiAlias: 同理
        // takeFailureScreenshot: 同理
    }
    
    private boolean isEnabled() {
        return FrameworkConfigManager.getBoolean(FrameworkConfig.SNAPSHOT_TESTING_ENABLED);
    }
    
    private String getBaselineDir() {
        String dir = FrameworkConfigManager.getString(FrameworkConfig.SNAPSHOT_BASELINE_DIR);
        if (dir == null || dir.trim().isEmpty()) {
            // 默认放在 src/test/resources 下，可纳入 Git 版本控制
            // target/ 是 Maven 构建输出目录，mvn clean 会删除，不适合存放基线
            dir = "src/test/resources/snapshots/baselines";
        }
        return dir;
    }
    
    private int getMaxDiffThreshold() {
        try {
            return FrameworkConfigManager.getInt(FrameworkConfig.SNAPSHOT_DIFF_THRESHOLD);
        } catch (Exception e) {
            return 1000;
        }
    }
    
    private boolean isIgnoreAntiAlias() {
        return FrameworkConfigManager.getBoolean(FrameworkConfig.SNAPSHOT_IGNORE_ANTIALIAS);
    }
    
    private boolean isTakeFailureScreenshot() {
        return FrameworkConfigManager.getBoolean(FrameworkConfig.SNAPSHOT_FAILURE_SCREENSHOT);
    }

    /**
     * 全局默认的 updateBaseline 模式（从 serenity.properties 读取）
     * true = 默认创建/更新基线（开发/改版时）
     * false = 默认仅对比基线（CI/CD 回归测试时）
     */
    private boolean isUpdateBaselineGlobal() {
        return FrameworkConfigManager.getBoolean(FrameworkConfig.SNAPSHOT_UPDATE_BASELINE);
    }
    
    private String getSnapshotsDir() {
        String dir = FrameworkConfigManager.getString(FrameworkConfig.SNAPSHOT_DIR);
        if (dir == null || dir.trim().isEmpty()) {
            dir = "target/snapshots";
        }
        return dir;
    }


    
    // ==================== 工具方法 ====================
    
    private static String sanitizeFileName(String name) {
        if (name == null) return "unnamed";
        return name.replaceAll("[^a-zA-Z0-9_-]", "_")
                      .replaceAll("_+", "_")
                      .replaceAll("_$", "")
                      .toLowerCase();
    }
    
    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "");
    }
    
    // ==================== 独立报告生成（与 Serenity 完全分离） ====================

    /**
     * 生成独立 HTML 快照对比报告
     * 
     * <p>收集所有已执行的快照测试结果，生成独立的 HTML 报告文件。
     * 报告包含：统计摘要、结果表格、差异可视化。</p>
     *
     * @return 报告文件绝对路径，无结果时返回 null
     */
    public static String generateReport() {
        return SnapshotReportGenerator.generate();
    }

    /**
     * 生成独立 HTML 快照对比报告到指定目录
     *
     * @param outputDir 输出目录，如 "target/snapshot-reports"
     * @return 报告文件绝对路径，无结果时返回 null
     */
    public static String generateReport(String outputDir) {
        return SnapshotReportGenerator.generate(outputDir);
    }

    /**
     * 获取快照测试统计摘要文本
     * 
     * @return 格式化的摘要字符串，如 "Snapshot Tests: 5 total | 4 PASS | 1 FAIL | ..."
     */
    public static String getSummary() {
        return SnapshotReportGenerator.getSummary();
    }

    /**
     * 清空已收集的快照结果（通常在 Scenario 开始前调用）
     */
    public static void clearResults() {
        SnapshotReportGenerator.clearResults();
    }

    // ==================== Builder ====================
    
    public static class Builder {
        private final Page page;
        private final Locator locator;
        private String baselineName;
        private boolean updateBaseline = false;
        private boolean updateBaselineExplicitlySet = false;
        private int maxDiffThreshold = 1000;
        private boolean ignoreAntiAlias = true;
        private boolean takeFailureScreenshot = true;
        
        Builder(Page page) {
            this.page = Objects.requireNonNull(page, "page cannot be null");
            this.locator = null;
            this.baselineName = "page-snapshot";
        }
        
        Builder(Locator locator) {
            this.locator = Objects.requireNonNull(locator, "locator cannot be null");
            this.page = null;
            this.baselineName = "element-snapshot";
        }
        
        /**
         * 设置基线名称（用于标识不同的快照基线）
         */
        public Builder baselineName(String name) {
            this.baselineName = name != null ? name : "snapshot";
            return this;
        }
        
        /**
         * 是否更新基线：
         * true = 基线不存在时自动创建，存在时更新
         * false = 仅做对比，不修改基线（CI/CD 模式推荐）
         *
         * 显式调用此方法会覆盖 serenity.properties 中的 snapshot.update.baseline 全局配置
         */
        public Builder updateBaseline(boolean update) {
            this.updateBaseline = update;
            this.updateBaselineExplicitlySet = true;  // 标记为用户显式设置
            return this;
        }
        
        /**
         * 设置差异像素阈值（超过此值视为失败），默认 1000 像素
         */
        public Builder maxDiffThreshold(int threshold) {
            this.maxDiffThreshold = Math.max(0, threshold);
            return this;
        }
        
        /**
         * 是否忽略抗锯齿等微小差异，默认 true
         */
        public Builder ignoreAntiAlias(boolean ignore) {
            this.ignoreAntiAlias = ignore;
            return this;
        }
        
        /**
         * 失败时是否自动截图，默认 true
         */
        public Builder failureScreenshot(boolean enable) {
            this.takeFailureScreenshot = enable;
            return this;
        }
        
        public SnapshotTester build() {
            return new SnapshotTester(this);
        }
    }
}
