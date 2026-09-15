package com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.concurrent;

import com.hsbc.cmb.hk.dbb.automation.framework.web.config.WebFrameworkConfig;

/**
 * 并发执行器选项（不可变，构造经 Builder）。
 *
 * @apiNote <b>框架内部能力</b>：仅供 {@code framework.web.lifecycle} 包树协作者与并发桥接使用；业务代码不得直接依赖。
 */
public final class ConcurrentContextOptions {

    /** 配置误配为非正数时的兜底硬上限（历史默认 16），避免并发度退化为 0 使执行器无线程。 */
    private static final int FALLBACK_HARD_CAP = 16;

    private final int parallelism;
    private final boolean failFast;
    private final long perTaskTimeoutMillis;
    private final boolean useVirtualThreads;

    private ConcurrentContextOptions(Builder b) {
        this.parallelism = b.parallelism;
        this.failFast = b.failFast;
        this.perTaskTimeoutMillis = b.perTaskTimeoutMillis;
        this.useVirtualThreads = b.useVirtualThreads;
    }

    /** 从 WebFrameworkConfig 取默认并行度与超时，构建默认选项。 */
    public static ConcurrentContextOptions defaults() {
        return builder()
                .parallelism(WebFrameworkConfig.PLAYWRIGHT_CONCURRENT_PARALLELISM.getIntValue())
                .perTaskTimeoutMillis(WebFrameworkConfig.PLAYWRIGHT_CONCURRENT_TASK_TIMEOUT_SECONDS.getLongValue() * 1000L)
                .useVirtualThreads(WebFrameworkConfig.PLAYWRIGHT_CONCURRENT_USE_VIRTUAL_THREADS.getBooleanValue())
                .build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public int parallelism() {
        return parallelism;
    }

    public boolean failFast() {
        return failFast;
    }

    public long perTaskTimeoutMillis() {
        return perTaskTimeoutMillis;
    }

    public boolean useVirtualThreads() {
        return useVirtualThreads;
    }

    /**
     * 解析实际并发度：min(任务数, 并行度, 硬上限)。
     * <p>硬上限由 {@code WebFrameworkConfig.PLAYWRIGHT_CONCURRENT_MAX} 配置，可经
     * -Dserenity.playwright.concurrent.max / serenity.properties 覆盖，默认按 CPU 核数自适应
     * （见 {@code WebFrameworkConfig#adaptiveConcurrentMaxDefault}）；配置误配为非正数时回退
     * {@link #FALLBACK_HARD_CAP}，保证并发度恒为正。
     */
    public int resolvedParallelism(int taskCount) {
        int cap = WebFrameworkConfig.PLAYWRIGHT_CONCURRENT_MAX.getIntValue();
        if (cap <= 0) {
            cap = FALLBACK_HARD_CAP;
        }
        if (parallelism <= 0) {
            throw new IllegalArgumentException("parallelism must be > 0");
        }
        return Math.min(taskCount, Math.min(parallelism, cap));
    }

    public static final class Builder {
        private int parallelism = 4;
        private boolean failFast = false;
        private long perTaskTimeoutMillis = 0L; // 0 = 不限
        private boolean useVirtualThreads = false;

        public Builder parallelism(int v) {
            this.parallelism = v;
            return this;
        }

        public Builder failFast(boolean v) {
            this.failFast = v;
            return this;
        }

        public Builder perTaskTimeoutMillis(long v) {
            this.perTaskTimeoutMillis = v;
            return this;
        }

        public Builder useVirtualThreads(boolean v) {
            this.useVirtualThreads = v;
            return this;
        }

        public ConcurrentContextOptions build() {
            if (parallelism <= 0) {
                throw new IllegalArgumentException("parallelism must be > 0");
            }
            if (perTaskTimeoutMillis < 0) {
                throw new IllegalArgumentException("perTaskTimeoutMillis must be >= 0");
            }
            return new ConcurrentContextOptions(this);
        }
    }
}
