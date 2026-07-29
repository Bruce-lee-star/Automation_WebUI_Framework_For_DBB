package com.hsbc.cmb.hk.dbb.automation.framework.api.playwright.client;

import com.hsbc.cmb.hk.dbb.automation.framework.api.config.FrameworkConfig;

/**
 * RetryStrategy - 可配置的重试策略（替代 {@link PlaywrightApiClient} 内硬编码的重试逻辑）。
 * <p>
 * 默认实现 {@link DefaultRetryStrategy}：依据 {@link FrameworkConfig} 的 retryCount/retryDelay，
 * 对传输异常（error != null）与 5xx 服务端错误重试，4xx 客户端错误不重试（避免掩盖真实缺陷）。
 */
public interface RetryStrategy {

    /** 最大尝试次数（含首次） */
    int maxAttempts();

    /** 重试间隔（毫秒） */
    long delayMillis();

    /**
     * 是否对已失败的一次请求进行重试。
     *
     * @param attempt 已执行的尝试次数（从 1 开始）
     * @param status  响应状态码（传输异常时为 0）
     * @param error   传输/解析异常（无异常为 null）
     * @return true 表示应重试
     */
    boolean shouldRetry(int attempt, int status, Throwable error);

    /** 基于 {@link FrameworkConfig} 的默认重试策略 */
    class DefaultRetryStrategy implements RetryStrategy {
        @Override
        public int maxAttempts() {
            return Math.max(1, FrameworkConfig.getRetryCount());
        }

        @Override
        public long delayMillis() {
            return FrameworkConfig.getRetryDelay();
        }

        @Override
        public boolean shouldRetry(int attempt, int status, Throwable error) {
            if (attempt >= maxAttempts()) {
                return false;
            }
            if (error != null) {
                return true; // 传输/IO/解析异常：可重试
            }
            return status >= 500; // 仅 5xx 重试；1xx/2xx/3xx/4xx 不重试
        }
    }
}
