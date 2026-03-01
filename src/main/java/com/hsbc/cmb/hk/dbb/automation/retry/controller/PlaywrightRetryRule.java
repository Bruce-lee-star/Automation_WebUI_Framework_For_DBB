package com.hsbc.cmb.hk.dbb.automation.retry.controller;

import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.PlaywrightManager;
import net.thucydides.core.steps.StepEventBus;
import org.junit.rules.TestRule;
import org.junit.runner.Description;
import org.junit.runners.model.Statement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class PlaywrightRetryRule implements TestRule {
    private static final Logger logger = LoggerFactory.getLogger(PlaywrightRetryRule.class);
    private static final ConcurrentMap<String, Integer> retryCounts = new ConcurrentHashMap<>();
    private static final ConcurrentMap<String, Boolean> shouldRetryCache = new ConcurrentHashMap<>();

    private final int maxRetries;
    private final long retryDelay;
    private final boolean restartBrowserForEachRetry;

    public PlaywrightRetryRule() {
        this.maxRetries = getMaxRetriesFromConfig();
        this.retryDelay = getRetryDelayFromConfig();
        this.restartBrowserForEachRetry = shouldRestartBrowserForEachRetry();
    }

    public PlaywrightRetryRule(int maxRetries) {
        this.maxRetries = maxRetries;
        this.retryDelay = getRetryDelayFromConfig();
        this.restartBrowserForEachRetry = shouldRestartBrowserForEachRetry();
    }

    public PlaywrightRetryRule(int maxRetries, long retryDelay) {
        this.maxRetries = maxRetries;
        this.retryDelay = retryDelay;
        this.restartBrowserForEachRetry = shouldRestartBrowserForEachRetry();
    }

    @Override
    public Statement apply(Statement base, Description description) {
        return new RetryableStatement(base, description, maxRetries, retryDelay, restartBrowserForEachRetry);
    }

    private int getMaxRetriesFromConfig() {
        String retryCountStr = System.getProperty("rerunFailingTestsCount",
                System.getProperty("serenity.retry.count",
                System.getProperty("cucumber.rerun.count", "0")));
        try {
            return Integer.parseInt(retryCountStr);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private long getRetryDelayFromConfig() {
        String delayStr = System.getProperty("serenity.retry.delay", "1000");
        try {
            return Long.parseLong(delayStr);
        } catch (NumberFormatException e) {
            return 1000;
        }
    }

    private boolean shouldRestartBrowserForEachRetry() {
        return Boolean.parseBoolean(System.getProperty("serenity.retry.restart.browser", "false"));
    }

    public static void clearRetryCounts() {
        retryCounts.clear();
        shouldRetryCache.clear();
    }

    public static int getRetryCount(String testId) {
        return retryCounts.getOrDefault(testId, 0);
    }

    private static class RetryableStatement extends Statement {
        private final Statement base;
        private final Description description;
        private final int maxRetries;
        private final long retryDelay;
        private final boolean restartBrowserForEachRetry;

        public RetryableStatement(Statement base, Description description,
                                  int maxRetries, long retryDelay, boolean restartBrowserForEachRetry) {
            this.base = base;
            this.description = description;
            this.maxRetries = maxRetries;
            this.retryDelay = retryDelay;
            this.restartBrowserForEachRetry = restartBrowserForEachRetry;
        }

        @Override
        public void evaluate() throws Throwable {
            Throwable lastThrowable = null;
            String testId = getTestId();

            for (int attempt = 1; attempt <= maxRetries + 1; attempt++) {
                logger.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
                logger.info("🧪 Attempt {}/{} for test: {}", attempt, maxRetries + 1, getTestName());
                logger.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");

                try {
                    if (attempt > 1) {
                        logger.info("🔄 Preparing for retry attempt {}...", attempt);
                        cleanupBeforeRetry();
                        if (restartBrowserForEachRetry) {
                            logger.info("🌐 Restarting browser for retry attempt {}...", attempt);
                            restartBrowser();
                        }
                    }

                    retryCounts.put(testId, attempt - 1);
                    base.evaluate();

                    if (attempt > 1) {
                        logger.info(" Test passed on attempt {}!", attempt);
                    }
                    return;

                } catch (Throwable t) {
                    lastThrowable = t;
                    logger.warn(" Attempt {} failed: {}", attempt, t.getMessage());

                    if (!shouldRetry(t, attempt)) {
                        logger.warn("🚫 Test will not be retried: {}", t.getClass().getSimpleName());
                        break;
                    }

                    if (attempt < maxRetries + 1) {
                        logger.info("⏳ Waiting {}ms before retry...", retryDelay);
                        sleep(retryDelay);
                        logger.info("🔄 Proceeding to retry attempt {}...", attempt + 1);
                    }
                }
            }

            logger.error("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
            logger.error("💥 Test failed after {} attempts", maxRetries + 1);
            logger.error("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
            throw lastThrowable;
        }

        private String getTestId() {
            return description.getClassName() + "." + description.getMethodName();
        }

        private String getTestName() {
            return description.getDisplayName();
        }

        private void cleanupBeforeRetry() {
            logger.info("🧹 Cleaning up before retry...");

            try {
                StepEventBus.getEventBus().clear();
                logger.info(" StepEventBus cleared");
            } catch (Exception e) {
                logger.warn(" Failed to clear StepEventBus: {}", e.getMessage());
            }

            try {
                clearPlaywrightContext();
                logger.info(" Playwright context cleared");
            } catch (Exception e) {
                logger.warn(" Failed to clear Playwright context: {}", e.getMessage());
            }
        }

        private void restartBrowser() {
            logger.info("🔄 Restarting browser for retry...");
            try {
                PlaywrightManager.restartBrowser();
                logger.info(" Browser restarted successfully");
            } catch (Exception e) {
                logger.warn(" Failed to restart browser: {}", e.getMessage());
            }
        }

        private void clearPlaywrightContext() {
            try {
                PlaywrightManager.closePage();
                PlaywrightManager.closeContext();
            } catch (Exception e) {
                logger.debug("Could not clear Playwright context: {}", e.getMessage());
            }
        }

        private boolean shouldRetry(Throwable t, int attempt) {
            if (attempt > maxRetries) {
                return false;
            }

            return isRetryableFailure(t);
        }

        private boolean isRetryableFailure(Throwable t) {
            String exceptionClassName = t.getClass().getName();

            String retryableExceptions = System.getProperty(
                    "serenity.retry.retryable-exceptions",
                    "com.microsoft.playwright.PlaywrightException," +
                    "java.net.ConnectException," +
                    "java.net.SocketTimeoutException," +
                    "java.utils.concurrent.TimeoutException," +
                    "java.io.IOException," +
                    "io.netty.channel.AbstractChannel$AnnotatedConnectException"
            );

            String[] exceptions = retryableExceptions.split(",");
            for (String exception : exceptions) {
                if (exceptionClassName.contains(exception.trim())) {
                    logger.info("🔄 Exception '{}' is retryable", exceptionClassName);
                    return true;
                }
            }

            String message = t.getMessage();
            if (message != null) {
                String[] patterns = {
                    "net::ERR",
                    "connection refused",
                    "timeout",
                    "Session not found",
                    "browser closed",
                    "Target page, context or browser closed"
                };
                for (String pattern : patterns) {
                    if (message.toLowerCase().contains(pattern.toLowerCase())) {
                        logger.info("🔄 Exception message contains retryable pattern: {}", pattern);
                        return true;
                    }
                }
            }

            return false;
        }

        private void sleep(long millis) {
            try {
                Thread.sleep(millis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
