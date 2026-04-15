package com.hsbc.cmb.hk.dbb.automation.retry.controller;

import com.microsoft.playwright.PlaywrightException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.SocketException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

public class RetryController {
    private static final Logger logger = LoggerFactory.getLogger(RetryController.class);

    private final Map<String, AtomicInteger> retryCounters = new ConcurrentHashMap<>();
    private final Map<String, RetryHistory> retryHistories = new ConcurrentHashMap<>();

    private final Set<Class<? extends Throwable>> retriableExceptions = new HashSet<>();
    private final Set<String> retriablePatterns = new HashSet<>();
    private final Set<String> nonRetriablePatterns = new HashSet<>();

    private int maxRetries = 0;
    private long baseDelayMs = 1000L;
    private double backoffMultiplier = 2.0;
    private long maxDelayMs = 30000L;

    private boolean initialized = false;

    private RetryController() {
        initializeDefaultConfigurations();
    }

    public static synchronized RetryController getInstance() {
        return Holder.INSTANCE;
    }

    private static class Holder {
        static final RetryController INSTANCE = new RetryController();
    }

    private void initializeDefaultConfigurations() {
        retriableExceptions.add(PlaywrightException.class);
        retriableExceptions.add(TimeoutException.class);
        retriableExceptions.add(SocketException.class);
        retriableExceptions.add(IOException.class);

        retriablePatterns.add("timeout");
        retriablePatterns.add("Timeout");
        retriablePatterns.add("TIMEOUT");
        retriablePatterns.add("not found");
        retriablePatterns.add("connection refused");
        retriablePatterns.add("socket closed");
        retriablePatterns.add("stale element");
        retriablePatterns.add("unable to locate");
        retriablePatterns.add("no such element");
        retriablePatterns.add("frame detached");
        retriablePatterns.add("target closed");
        retriablePatterns.add("execution context was destroyed");
        retriablePatterns.add("node is detached");
        retriablePatterns.add("element is detached");
        retriablePatterns.add("navigation failed");
        retriablePatterns.add("net::");
        retriablePatterns.add("ERR_CONNECTION");
        retriablePatterns.add("WebSocket");
        retriablePatterns.add("dialog");
        retriablePatterns.add("evaluation failed");
        retriablePatterns.add("download error");

        nonRetriablePatterns.add("assertion failed");
        nonRetriablePatterns.add("verification failed");
        nonRetriablePatterns.add("test failed");
        nonRetriablePatterns.add("AssertionError");
        nonRetriablePatterns.add("expect(");
    }

    public void initialize() {
        if (initialized) {
            return;
        }

        this.maxRetries = getMaxRetriesFromConfig();
        this.baseDelayMs = getBaseDelayFromConfig();
        this.backoffMultiplier = getBackoffMultiplierFromConfig();
        this.maxDelayMs = getMaxDelayFromConfig();

        loadCustomExceptionConfigurations();

        initialized = true;
        logger.info("[RetryController] Initialized - MaxRetries: {}, BaseDelay: {}ms, Multiplier: {}, MaxDelay: {}ms",
                maxRetries, baseDelayMs, backoffMultiplier, maxDelayMs);
    }

    private int getMaxRetriesFromConfig() {
        String rerunCount = System.getProperty("rerunFailingTestsCount");
        if (rerunCount != null) {
            try {
                return Integer.parseInt(rerunCount);
            } catch (NumberFormatException e) {
                logger.warn("[RetryController] Invalid rerunFailingTestsCount: {}, using default: 2", rerunCount);
            }
        }

        String serenityRetries = System.getProperty("serenity.rerun.failures.max.retries");
        if (serenityRetries != null) {
            try {
                return Integer.parseInt(serenityRetries);
            } catch (NumberFormatException e) {
                logger.warn("[RetryController] Invalid serenity.rerun.failures.max.retries: {}, using default: 2", serenityRetries);
            }
        }

        int defaultRetries = 2;
        logger.info("[RetryController] No retry count configured, using default: {}", defaultRetries);
        return defaultRetries;
    }

    private long getBaseDelayFromConfig() {
        String delayStr = System.getProperty("serenity.rerun.failures.wait.time");
        if (delayStr != null) {
            try {
                return Long.parseLong(delayStr);
            } catch (NumberFormatException e) {
                logger.warn("[RetryController] Invalid serenity.rerun.failures.wait.time: {}", delayStr);
            }
        }
        return 1000L;
    }

    private double getBackoffMultiplierFromConfig() {
        String multiplierStr = System.getProperty("serenity.retry.delay.multiplier");
        if (multiplierStr != null) {
            try {
                return Double.parseDouble(multiplierStr);
            } catch (NumberFormatException e) {
                logger.warn("[RetryController] Invalid serenity.retry.delay.multiplier: {}", multiplierStr);
            }
        }
        return 2.0;
    }

    private long getMaxDelayFromConfig() {
        String maxDelayStr = System.getProperty("serenity.retry.delay.max");
        if (maxDelayStr != null) {
            try {
                return Long.parseLong(maxDelayStr);
            } catch (NumberFormatException e) {
                logger.warn("[RetryController] Invalid serenity.retry.delay.max: {}", maxDelayStr);
            }
        }
        return 30000L;
    }

    @SuppressWarnings("unchecked")
    private void loadCustomExceptionConfigurations() {
        String customExceptions = System.getProperty("serenity.retry.exceptions");
        if (customExceptions != null && !customExceptions.isEmpty()) {
            String[] exceptionClasses = customExceptions.split(",");
            for (String className : exceptionClasses) {
                try {
                    Class<?> exceptionClass = Class.forName(className.trim());
                    if (Throwable.class.isAssignableFrom(exceptionClass)) {
                        retriableExceptions.add((Class<? extends Throwable>) exceptionClass);
                        logger.info("[RetryController] Added custom retriable exception: {}", className);
                    }
                } catch (ClassNotFoundException e) {
                    logger.warn("[RetryController] Could not load custom exception class: {}", className);
                }
            }
        }

        String customPatterns = System.getProperty("serenity.retry.patterns");
        if (customPatterns != null && !customPatterns.isEmpty()) {
            String[] patterns = customPatterns.split(",");
            for (String pattern : patterns) {
                String trimmedPattern = pattern.trim();
                if (!trimmedPattern.isEmpty()) {
                    retriablePatterns.add(trimmedPattern.toLowerCase());
                    logger.info("[RetryController] Added custom retriable pattern: {}", trimmedPattern);
                }
            }
        }
    }

    public boolean shouldRetryTest(String testId, Throwable failureCause) {
        if (!isRetryEnabled()) {
            return false;
        }

        int currentRetryCount = getRetryCount(testId);
        if (currentRetryCount >= maxRetries) {
            logger.debug("[RetryController] Test {} has reached max retries ({})", testId, maxRetries);
            return false;
        }

        if (!isRetriableFailure(failureCause)) {
            logger.debug("[RetryController] Test {} failure is not retriable: {}",
                    testId, failureCause != null ? failureCause.getClass().getSimpleName() : "null");
            return false;
        }

        return true;
    }

    private boolean isRetriableFailure(Throwable failureCause) {
        if (failureCause == null) {
            return false;
        }

        for (Class<? extends Throwable> exceptionClass : retriableExceptions) {
            if (exceptionClass.isInstance(failureCause)) {
                logger.debug("[RetryController] Found retriable exception: {}",
                        failureCause.getClass().getSimpleName());
                return true;
            }
        }

        String errorMessage = failureCause.getMessage();
        if (errorMessage != null) {
            String lowerMessage = errorMessage.toLowerCase();

            for (String pattern : nonRetriablePatterns) {
                if (lowerMessage.contains(pattern)) {
                    logger.debug("[RetryController] Failure contains non-retriable pattern: {}", pattern);
                    return false;
                }
            }

            for (String pattern : retriablePatterns) {
                if (lowerMessage.contains(pattern)) {
                    logger.debug("[RetryController] Failure contains retriable pattern: {}", pattern);
                    return true;
                }
            }
        }

        return false;
    }

    public int getRetryCount(String testId) {
        AtomicInteger counter = retryCounters.get(testId);
        return counter != null ? counter.get() : 0;
    }

    public void recordRetry(String testId) {
        retryCounters.computeIfAbsent(testId, k -> new AtomicInteger(0)).incrementAndGet();
        logger.info("[RetryController] Recorded retry for test {} - Count: {}",
                testId, getRetryCount(testId));
    }

    public void recordRetryAttempt(String testId, int attemptNumber, boolean success, long durationMs) {
        RetryHistory history = retryHistories.computeIfAbsent(testId, k -> new RetryHistory());
        history.addAttempt(attemptNumber, success, durationMs);
    }

    public RetryHistory getRetryHistory(String testId) {
        return retryHistories.get(testId);
    }

    public boolean isRetryEnabled() {
        return maxRetries > 0;
    }

    public int getMaxRetries() {
        return maxRetries;
    }

    public long getBaseDelayMs() {
        return baseDelayMs;
    }

    public double getBackoffMultiplier() {
        return backoffMultiplier;
    }

    public long getMaxDelayMs() {
        return maxDelayMs;
    }

    public long calculateDelay(int attemptNumber) {
        if (attemptNumber <= 1) {
            return 0;
        }

        long delay = (long) (baseDelayMs * Math.pow(backoffMultiplier, attemptNumber - 1));
        return Math.min(delay, maxDelayMs);
    }

    public void reset() {
        retryCounters.clear();
        retryHistories.clear();
        initialized = false;
        logger.info("[RetryController] Reset - all retry counters and histories cleared");
    }

    public RetryStatistics getStatistics() {
        int totalRetries = 0;
        int successOnRetry = 0;
        int stillFailing = 0;

        for (RetryHistory history : retryHistories.values()) {
            if (history.getAttemptCount() > 1) {
                totalRetries += history.getAttemptCount() - 1;
                if (history.isSuccessOnLastAttempt()) {
                    successOnRetry++;
                } else {
                    stillFailing++;
                }
            }
        }

        return new RetryStatistics(
                retryCounters.size(),
                totalRetries,
                successOnRetry,
                stillFailing
        );
    }

    public static class RetryHistory {
        private final List<RetryAttempt> attempts = new ArrayList<>();

        public void addAttempt(int attemptNumber, boolean success, long durationMs) {
            attempts.add(new RetryAttempt(attemptNumber, success, durationMs));
        }

        public int getAttemptCount() {
            return attempts.size();
        }

        public boolean isSuccessOnLastAttempt() {
            if (attempts.isEmpty()) {
                return false;
            }
            return attempts.get(attempts.size() - 1).isSuccess();
        }

        public RetryAttempt getLastAttempt() {
            if (attempts.isEmpty()) {
                return null;
            }
            return attempts.get(attempts.size() - 1);
        }

        public List<RetryAttempt> getAttempts() {
            return Collections.unmodifiableList(attempts);
        }
    }

    public static class RetryAttempt {
        private final int attemptNumber;
        private final boolean success;
        private final long durationMs;

        public RetryAttempt(int attemptNumber, boolean success, long durationMs) {
            this.attemptNumber = attemptNumber;
            this.success = success;
            this.durationMs = durationMs;
        }

        public int getAttemptNumber() {
            return attemptNumber;
        }

        public boolean isSuccess() {
            return success;
        }

        public long getDurationMs() {
            return durationMs;
        }
    }

    public static class RetryStatistics {
        private final int uniqueTests;
        private final int totalRetryAttempts;
        private final int succeededOnRetry;
        private final int stillFailing;

        public RetryStatistics(int uniqueTests, int totalRetryAttempts,
                               int succeededOnRetry, int stillFailing) {
            this.uniqueTests = uniqueTests;
            this.totalRetryAttempts = totalRetryAttempts;
            this.succeededOnRetry = succeededOnRetry;
            this.stillFailing = stillFailing;
        }

        public int getUniqueTests() {
            return uniqueTests;
        }

        public int getTotalRetryAttempts() {
            return totalRetryAttempts;
        }

        public int getSucceededOnRetry() {
            return succeededOnRetry;
        }

        public int getStillFailing() {
            return stillFailing;
        }

        public double getRetrySuccessRate() {
            if (totalRetryAttempts == 0) {
                return 0.0;
            }
            return (succeededOnRetry * 100.0) / totalRetryAttempts;
        }

        @Override
        public String toString() {
            return String.format(
                    "RetryStatistics{uniqueTests=%d, totalRetryAttempts=%d, succeededOnRetry=%d, stillFailing=%d, retrySuccessRate=%.2f%%}",
                    uniqueTests, totalRetryAttempts, succeededOnRetry, stillFailing, getRetrySuccessRate()
            );
        }
    }
}

