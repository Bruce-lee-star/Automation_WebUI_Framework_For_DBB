package com.hsbc.cmb.hk.dbb.automation.retry.strategy;

import com.hsbc.cmb.hk.dbb.automation.framework.web.config.FrameworkConfig;
import com.hsbc.cmb.hk.dbb.automation.framework.web.config.FrameworkConfigManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RetryDelayStrategyFactory {
    private static final Logger logger = LoggerFactory.getLogger(RetryDelayStrategyFactory.class);

    public static RetryDelayStrategy createStrategy() {
        String strategyType = FrameworkConfigManager.getString(FrameworkConfig.SERENITY_RETRY_DELAY_STRATEGY);

        switch (strategyType.toLowerCase()) {
            case "exponential":
                return createExponentialStrategy();
            case "fixed":
            default:
                return createFixedStrategy();
        }
    }

    public static RetryDelayStrategy createStrategy(String strategyType) {
        if (strategyType == null || strategyType.isEmpty()) {
            return createStrategy();
        }

        switch (strategyType.toLowerCase()) {
            case "exponential":
                return createExponentialStrategy();
            case "fixed":
            default:
                return createFixedStrategy();
        }
    }

    private static FixedDelayStrategy createFixedStrategy() {
        long delayMs = FrameworkConfigManager.getLong(FrameworkConfig.SERENITY_RERUN_FAILURES_WAIT_TIME);

        logger.info("[RetryDelayStrategyFactory] Created FixedDelayStrategy with delay: {}ms", delayMs);
        return new FixedDelayStrategy(delayMs);
    }

    private static ExponentialBackoffStrategy createExponentialStrategy() {
        long baseDelayMs = FrameworkConfigManager.getLong(FrameworkConfig.SERENITY_RETRY_DELAY_BASE);
        long maxDelayMs = FrameworkConfigManager.getLong(FrameworkConfig.SERENITY_RETRY_DELAY_MAX);
        double multiplier;

        try {
            String multiplierStr = FrameworkConfigManager.getString(FrameworkConfig.SERENITY_RETRY_DELAY_MULTIPLIER);
            multiplier = Double.parseDouble(multiplierStr);
        } catch (NumberFormatException e) {
            logger.warn("[RetryDelayStrategyFactory] Invalid multiplier, using default 2.0");
            multiplier = 2.0;
        }

        logger.info("[RetryDelayStrategyFactory] Created ExponentialBackoffStrategy with baseDelay: {}ms, maxDelay: {}ms, multiplier: {}",
                baseDelayMs, maxDelayMs, multiplier);

        return new ExponentialBackoffStrategy(baseDelayMs, maxDelayMs, multiplier);
    }
}

