package com.hsbc.cmb.hk.dbb.automation.framework.web.snapshot;

import java.util.ArrayList;
import java.util.List;

/**
 * Playwright 原生快照测试结果
 * <p>
 * 封装 Playwright 原生 API (hasScreenshot / matchesAriaSnapshot) 的测试结果。
 * 支持两种快照类型：
 * <ul>
 *   <li>视觉快照 (VISUAL) - 像素级图像对比</li>
 *   <li>ARIA 快照 (ARIA) - 可访问性树结构对比</li>
 * </ul>
 * </p>
 *
 * @see PlaywrightSnapshotSupport
 */
public class NativeSnapshotResult {

    // ==================== 快照类型 ====================
    public enum SnapshotType {
        /** 视觉快照 - 像素级图像对比 */
        VISUAL,
        /** ARIA 快照 - 可访问性树结构对比 */
        ARIA
    }

    // ==================== 状态常量 ====================
    public static final String PASSED = "PASSED";
    public static final String FAILED = "FAILED";
    public static final String ERROR = "ERROR";

    // ==================== 字段 ====================
    private final String name;
    private final SnapshotType type;
    private final String status;
    private final String expected;
    private final String actual;
    private final String diff;
    private final String details;
    private final long durationMs;

    private NativeSnapshotResult(Builder builder) {
        this.name = builder.name;
        this.type = builder.type;
        this.status = builder.status;
        this.expected = builder.expected;
        this.actual = builder.actual;
        this.diff = builder.diff;
        this.details = builder.details;
        this.durationMs = builder.durationMs;
    }

    // ==================== 工厂方法 ====================

    /** 创建视觉快照通过结果 */
    public static NativeSnapshotResult visualPassed(String name, String details, long durationMs) {
        return new Builder()
                .name(name)
                .type(SnapshotType.VISUAL)
                .status(PASSED)
                .details(details)
                .durationMs(durationMs)
                .build();
    }

    /** 创建视觉快照失败结果 */
    public static NativeSnapshotResult visualFailed(String name, String expected, String actual,
                                                     String diff, String details, long durationMs) {
        return new Builder()
                .name(name)
                .type(SnapshotType.VISUAL)
                .status(FAILED)
                .expected(expected)
                .actual(actual)
                .diff(diff)
                .details(details)
                .durationMs(durationMs)
                .build();
    }

    /** 创建 ARIA 快照通过结果 */
    public static NativeSnapshotResult ariaPassed(String name, String expected, String details, long durationMs) {
        return new Builder()
                .name(name)
                .type(SnapshotType.ARIA)
                .status(PASSED)
                .expected(expected)
                .details(details)
                .durationMs(durationMs)
                .build();
    }

    /** 创建 ARIA 快照失败结果 */
    public static NativeSnapshotResult ariaFailed(String name, String expected, String actual,
                                                  String diff, String details, long durationMs) {
        return new Builder()
                .name(name)
                .type(SnapshotType.ARIA)
                .status(FAILED)
                .expected(expected)
                .actual(actual)
                .diff(diff)
                .details(details)
                .durationMs(durationMs)
                .build();
    }

    /** 创建错误结果 */
    public static NativeSnapshotResult error(String name, SnapshotType type, String errorMessage) {
        return new Builder()
                .name(name)
                .type(type)
                .status(ERROR)
                .details(errorMessage)
                .durationMs(0)
                .build();
    }

    // ==================== Getter ====================

    public String getName() { return name; }
    public SnapshotType getType() { return type; }
    public String getStatus() { return status; }
    public String getExpected() { return expected; }
    public String getActual() { return actual; }
    public String getDiff() { return diff; }
    public String getDetails() { return details; }
    public long getDurationMs() { return durationMs; }

    /** 是否通过 */
    public boolean isPassed() {
        return PASSED.equals(status);
    }

    /** 是否失败 */
    public boolean isFailed() {
        return FAILED.equals(status);
    }

    /** 是否是视觉快照 */
    public boolean isVisual() {
        return SnapshotType.VISUAL.equals(type);
    }

    /** 是否是 ARIA 快照 */
    public boolean isAria() {
        return SnapshotType.ARIA.equals(type);
    }

    @Override
    public String toString() {
        return String.format("NativeSnapshotResult{name='%s', type=%s, status=%s, duration=%dms}",
                name, type, status, durationMs);
    }

    // ==================== Builder ====================

    public static class Builder {
        private String name;
        private SnapshotType type = SnapshotType.VISUAL;
        private String status = PASSED;
        private String expected;
        private String actual;
        private String diff;
        private String details;
        private long durationMs;

        public Builder name(String name) { this.name = name; return this; }
        public Builder type(SnapshotType type) { this.type = type; return this; }
        public Builder status(String status) { this.status = status; return this; }
        public Builder expected(String expected) { this.expected = expected; return this; }
        public Builder actual(String actual) { this.actual = actual; return this; }
        public Builder diff(String diff) { this.diff = diff; return this; }
        public Builder details(String details) { this.details = details; return this; }
        public Builder durationMs(long durationMs) { this.durationMs = durationMs; return this; }

        public NativeSnapshotResult build() {
            return new NativeSnapshotResult(this);
        }
    }

    // ==================== 统计辅助 ====================

    /** 统计快照结果集合 */
    public static class Stats {
        private int total = 0;
        private int passed = 0;
        private int failed = 0;
        private int error = 0;
        private int visual = 0;
        private int aria = 0;

        public void add(NativeSnapshotResult result) {
            total++;
            switch (result.getStatus()) {
                case PASSED -> passed++;
                case FAILED -> failed++;
                case ERROR -> error++;
            }
            switch (result.getType()) {
                case VISUAL -> visual++;
                case ARIA -> aria++;
            }
        }

        public int getTotal() { return total; }
        public int getPassed() { return passed; }
        public int getFailed() { return failed; }
        public int getError() { return error; }
        public int getVisual() { return visual; }
        public int getAria() { return aria; }

        public double getPassRate() {
            return total > 0 ? (passed * 100.0 / total) : 0;
        }

        @Override
        public String toString() {
            return String.format("Stats{total=%d, passed=%d, failed=%d, error=%d, visual=%d, aria=%d, passRate=%.1f%%}",
                    total, passed, failed, error, visual, aria, getPassRate());
        }
    }
}
