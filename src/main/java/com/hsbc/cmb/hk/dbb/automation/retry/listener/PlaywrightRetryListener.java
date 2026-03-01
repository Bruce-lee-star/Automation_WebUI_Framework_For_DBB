package com.hsbc.cmb.hk.dbb.automation.retry.listener;


import net.thucydides.model.domain.TestResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

public class PlaywrightRetryListener {
    private static final Logger logger = LoggerFactory.getLogger(PlaywrightRetryListener.class);
    private static final PlaywrightRetryListener INSTANCE = new PlaywrightRetryListener();
    private static final String RETRY_DATA_FILE = "target/retry-data.properties";

    private final ConcurrentMap<String, List<RetryEvent>> retryEvents = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Integer> retryCounts = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, TestResult> finalResults = new ConcurrentHashMap<>();
    private final List<Consumer<RetryEvent>> eventListeners = new CopyOnWriteArrayList<>();

    private boolean enabled = true;
    private boolean reportRetries = true;

    private PlaywrightRetryListener() {
    }

    public static PlaywrightRetryListener getInstance() {
        return INSTANCE;
    }

    public void recordRetry(String testId, int attempt, Throwable cause) {
        if (!enabled) {
            return;
        }

        RetryEvent event = new RetryEvent(testId, attempt, cause);
        retryEvents.computeIfAbsent(testId, k -> new CopyOnWriteArrayList<>()).add(event);
        retryCounts.merge(testId, 1, Integer::sum);

        logger.info("📝 Recorded retry event for test '{}': attempt {}", testId, attempt);
        notifyListeners(event);
    }

    public void recordRetrySuccess(String testId, int finalAttempt) {
        if (!enabled) {
            return;
        }

        finalResults.put(testId, TestResult.SUCCESS);
        int retryCount = retryCounts.getOrDefault(testId, 0);
        logger.info(" Test '{}' passed after {} retries on attempt {}", testId, retryCount, finalAttempt);

        RetryEvent event = new RetryEvent(testId, finalAttempt, null, true);
        eventListeners.forEach(listener -> {
            try {
                listener.accept(event);
            } catch (Exception e) {
                logger.warn("Error notifying retry listener: {}", e.getMessage());
            }
        });
    }

    public void recordRetryFailure(String testId, Throwable finalFailure) {
        if (!enabled) {
            return;
        }

        finalResults.put(testId, TestResult.FAILURE);
        int retryCount = retryCounts.getOrDefault(testId, 0);
        logger.warn(" Test '{}' failed after {} retries", testId, retryCount);
    }

    public int getRetryCount(String testId) {
        return retryCounts.getOrDefault(testId, 0);
    }

    public List<RetryEvent> getRetryEvents(String testId) {
        return retryEvents.getOrDefault(testId, Collections.emptyList());
    }

    public Map<String, Integer> getAllRetryCounts() {
        return new HashMap<>(retryCounts);
    }

    public TestResult getFinalResult(String testId) {
        return finalResults.get(testId);
    }

    public boolean hasRetries(String testId) {
        return retryCounts.getOrDefault(testId, 0) > 0;
    }

    public int getTotalRetries() {
        return retryCounts.values().stream().mapToInt(Integer::intValue).sum();
    }

    public int getTotalTestsWithRetries() {
        return (int) retryCounts.entrySet().stream()
                .filter(e -> e.getValue() > 0)
                .count();
    }

    public void addEventListener(Consumer<RetryEvent> listener) {
        if (listener != null) {
            eventListeners.add(listener);
        }
    }

    public void removeEventListener(Consumer<RetryEvent> listener) {
        eventListeners.remove(listener);
    }

    private void notifyListeners(RetryEvent event) {
        eventListeners.forEach(listener -> {
            try {
                listener.accept(event);
            } catch (Exception e) {
                logger.warn("Error notifying retry listener: {}", e.getMessage());
            }
        });
    }

    public void clear() {
        retryEvents.clear();
        retryCounts.clear();
        finalResults.clear();
        logger.info("🧹 PlaywrightRetryListener cleared");
    }

    public void resetForRerun() {
        retryEvents.clear();
        retryCounts.clear();
        finalResults.clear();
        logger.info("🔄 PlaywrightRetryListener reset for rerun");
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public void setReportRetries(boolean reportRetries) {
        this.reportRetries = reportRetries;
    }

    public RetryStatistics getStatistics() {
        return new RetryStatistics(
                getTotalRetries(),
                getTotalTestsWithRetries(),
                getAllRetryCounts()
        );
    }

    /**
     * 保存重试数据到文件（用于跨进程共享）
     */
    public void saveToFile() {
        try {
            Properties props = new Properties();
            
            // 保存 retry counts
            for (Map.Entry<String, Integer> entry : retryCounts.entrySet()) {
                props.setProperty("retry.count." + entry.getKey(), String.valueOf(entry.getValue()));
            }
            
            // 保存 final results
            for (Map.Entry<String, TestResult> entry : finalResults.entrySet()) {
                props.setProperty("final.result." + entry.getKey(), entry.getValue().name());
            }
            
            // 保存统计数据
            props.setProperty("stats.total.retries", String.valueOf(getTotalRetries()));
            props.setProperty("stats.tests.with.retries", String.valueOf(getTotalTestsWithRetries()));
            
            File file = new File(RETRY_DATA_FILE);
            file.getParentFile().mkdirs();
            
            try (FileOutputStream fos = new FileOutputStream(file)) {
                props.store(fos, "Retry Statistics Data");
            }
            
            logger.info("💾 Saved retry data to file: {} (events: {}, tests: {})", 
                RETRY_DATA_FILE, getTotalRetries(), retryCounts.size());
        } catch (IOException e) {
            logger.warn("Failed to save retry data to file: {}", e.getMessage());
        }
    }

    /**
     * 从文件加载重试数据（用于跨进程共享）
     */
    public void loadFromFile() {
        try {
            File file = new File(RETRY_DATA_FILE);
            if (!file.exists()) {
                logger.debug("Retry data file does not exist: {}", RETRY_DATA_FILE);
                return;
            }

            Properties props = new Properties();
            try (FileInputStream fis = new FileInputStream(file)) {
                props.load(fis);
            }

            // 清空现有数据
            retryCounts.clear();
            finalResults.clear();

            // 加载 retry counts
            for (String key : props.stringPropertyNames()) {
                if (key.startsWith("retry.count.")) {
                    String testId = key.substring("retry.count.".length());
                    int count = Integer.parseInt(props.getProperty(key));
                    retryCounts.put(testId, count);
                } else if (key.startsWith("final.result.")) {
                    String testId = key.substring("final.result.".length());
                    String resultStr = props.getProperty(key);
                    try {
                        TestResult result = TestResult.valueOf(resultStr);
                        finalResults.put(testId, result);
                    } catch (IllegalArgumentException e) {
                        logger.warn("Unknown test result: {}", resultStr);
                    }
                }
            }

            logger.info("📂 Loaded retry data from file: {} (retries: {}, tests: {})", 
                RETRY_DATA_FILE, getTotalRetries(), retryCounts.size());
        } catch (IOException e) {
            logger.warn("Failed to load retry data from file: {}", e.getMessage());
        }
    }

    public static class RetryEvent {
        private final String testId;
        private final int attempt;
        private final Throwable cause;
        private final boolean success;

        public RetryEvent(String testId, int attempt, Throwable cause) {
            this(testId, attempt, cause, false);
        }

        public RetryEvent(String testId, int attempt, Throwable cause, boolean success) {
            this.testId = testId;
            this.attempt = attempt;
            this.cause = cause;
            this.success = success;
        }

        public String getTestId() {
            return testId;
        }

        public int getAttempt() {
            return attempt;
        }

        public Throwable getCause() {
            return cause;
        }

        public boolean isSuccess() {
            return success;
        }

        @Override
        public String toString() {
            return String.format("RetryEvent{testId='%s', attempt=%d, success=%s}",
                    testId, attempt, success);
        }
    }

    public static class RetryStatistics {
        private final int totalRetries;
        private final int testsWithRetries;
        private final Map<String, Integer> retryCounts;

        public RetryStatistics(int totalRetries, int testsWithRetries, Map<String, Integer> retryCounts) {
            this.totalRetries = totalRetries;
            this.testsWithRetries = testsWithRetries;
            this.retryCounts = retryCounts;
        }

        public int getTotalRetries() {
            return totalRetries;
        }

        public int getTestsWithRetries() {
            return testsWithRetries;
        }

        public Map<String, Integer> getRetryCounts() {
            return retryCounts;
        }

        public double getRetryRate() {
            if (retryCounts.isEmpty()) {
                return 0.0;
            }
            return (double) testsWithRetries / retryCounts.size();
        }

        @Override
        public String toString() {
            return String.format("RetryStatistics{totalRetries=%d, testsWithRetries=%d, retryRate=%.2f}",
                    totalRetries, testsWithRetries, getRetryRate());
        }
    }
}

