package com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.concurrent;

import com.hsbc.cmb.hk.dbb.automation.framework.web.config.WebFrameworkConfig;

/**
 * 并发执行器选项（不可变，构造经 Builder）。
 *
 * @apiNote <b>框架内部能力</b>：仅供 {@code framework.web.lifecycle} 包树协作者与并发桥接使用；业务代码不得直接依赖。
 */
public final class ConcurrentContextOptions {

    private static final int DEFAULT_HARD_CAP = 16;

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

    /** 解析实际并发度：min(任务数, 并行度, 硬上限)。 */
    public int resolvedParallelism(int taskCount) {
        int cap = DEFAULT_HARD_CAP;
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
