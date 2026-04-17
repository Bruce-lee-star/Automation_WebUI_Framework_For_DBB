package com.hsbc.cmb.hk.dbb.automation.framework.web.snapshot;

/**
 * 快照测试结果 - 不可变数据对象（带 Builder 风格工厂方法）
 * <p>
 * 封装单次快照对比的完整结果，包括状态、差异统计、截图路径等信息。
 * 由 {@link SnapshotTester} 创建，注册到 {@link SnapshotReportGenerator} 用于生成报告。
 * </p>
 */
public class SnapshotResult {

    // ==================== 状态常量 ====================

    public static final String PASSED = "PASSED";
    public static final String FAILED = "FAILED";
    public static final String CREATED = "CREATED";
    public static final String NO_BASELINE = "NO_BASELINE";
    public static final String ERROR = "ERROR";
    public static final String SKIPPED = "SKIPPED";

    // ==================== 字段 ====================

    private final String name;
    private final String status;
    private final int diffPixels;
    private final double diffPercent;
    private final String details;
    private String failureScreenshotPath;  // 可变：失败后设置
    private final long baselineSize;
    private final long currentSize;

    // ==================== 私有构造器（通过工厂方法创建）====================

    private SnapshotResult(Builder builder) {
        this.name = builder.name;
        this.status = builder.status != null ? builder.status.toUpperCase() : ERROR;
        this.diffPixels = builder.diffPixels;
        this.diffPercent = builder.diffPercent;
        this.details = builder.details;
        this.failureScreenshotPath = builder.failureScreenshotPath;
        this.baselineSize = builder.baselineSize;
        this.currentSize = builder.currentSize;
    }

    // ==================== 工厂方法（SnapshotTester 调用）====================

    /** 测试通过 */
    public static SnapshotResult passed(String name, String details, int diffPixels, int diffPercent) {
        return new Builder().name(name).status(PASSED).diffPixels(diffPixels)
                .diffPercent(diffPercent).details(details).build();
    }

    /** 测试失败 */
    public static SnapshotResult failed(String name, String details, int diffPixels, int diffPercent,
                                        long baselineSize, long currentSize) {
        return new Builder().name(name).status(FAILED).diffPixels(diffPixels)
                .diffPercent(diffPercent).details(details)
                .baselineSize(baselineSize).currentSize(currentSize).build();
    }

    /** 基线已创建/更新 */
    public static SnapshotResult created(String name, String details, long sizeBytes) {
        return new Builder().name(name).status(CREATED).details(details).currentSize(sizeBytes).build();
    }

    /** 无基线 */
    public static SnapshotResult noBaseline(String name, String reason) {
        return new Builder().name(name).status(NO_BASELINE).details(reason).build();
    }

    /** 执行出错 */
    public static SnapshotResult error(String name, String errorMessage) {
        return new Builder().name(name).status(ERROR).details(errorMessage).build();
    }

    /** 跳过（未启用等） */
    public static SnapshotResult skipped(String name, String reason) {
        return new Builder().name(name).status(SKIPPED).details(reason).build();
    }

    // ==================== Getter ====================

    /** 基线名称 */
    public String getName() { return name; }

    /** 状态: PASSED/FAILED/CREATED/NO_BASELINE/ERROR/SKIPPED */
    public String getStatus() { return status; }

    /** 差异像素数 */
    public int getDiffPixels() { return diffPixels; }

    /** 差异百分比 (0.0 ~ 100.0) */
    public double getDiffPercent() { return diffPercent; }

    /** 详细描述信息 */
    public String getDetails() { return details; }

    /** 失败时的差异截图路径 */
    public String getFailureScreenshotPath() { return failureScreenshotPath; }

    /** 基线图片大小（字节） */
    public long getBaselineSize() { return baselineSize; }

    /** 当前截图大小（字节） */
    public long getCurrentSize() { return currentSize; }

    /**
     * 设置失败截图路径（仅此字段可变，因为捕获发生在构造之后）
     */
    public void setFailureScreenshotPath(String path) {
        this.failureScreenshotPath = path;
    }

    // ==================== 便捷方法 ====================

    /** 是否通过（PASSED 或 CREATED） */
    public boolean isPassed() {
        return PASSED.equals(status) || CREATED.equals(status);
    }

    /** 是否失败 */
    public boolean isFailed() {
        return FAILED.equals(status) || ERROR.equals(status);
    }

    @Override
    public String toString() {
        return String.format("SnapshotResult{name='%s', status=%s, diff=%.2f%%, %dpx}",
                name, status, diffPercent, diffPixels);
    }

    // ==================== 内部 Builder ====================

    private static class Builder {
        private String name;
        private String status;
        private int diffPixels;
        private double diffPercent;
        private String details;
        private String failureScreenshotPath;
        private long baselineSize;
        private long currentSize;

        Builder name(String v) { this.name = v; return this; }
        Builder status(String v) { this.status = v; return this; }
        Builder diffPixels(int v) { this.diffPixels = v; return this; }
        Builder diffPercent(double v) { this.diffPercent = v; return this; }
        Builder details(String v) { this.details = v; return this; }
        Builder failureScreenshotPath(String v) { this.failureScreenshotPath = v; return this; }
        Builder baselineSize(long v) { this.baselineSize = v; return this; }
        Builder currentSize(long v) { this.currentSize = v; return this; }
        SnapshotResult build() { return new SnapshotResult(this); }
    }
}
