package com.hsbc.cmb.hk.dbb.automation.retry.executor;

import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.PlaywrightManager;
import com.hsbc.cmb.hk.dbb.automation.retry.configuration.RerunConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class RerunProcessExecutor {
    private static final Logger logger = LoggerFactory.getLogger(RerunProcessExecutor.class);
    private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final RerunConfiguration configuration;
    private final AtomicInteger totalRerunCount = new AtomicInteger(0);
    private final AtomicInteger successfulRerunCount = new AtomicInteger(0);
    private final AtomicInteger failedRerunCount = new AtomicInteger(0);
    private final AtomicBoolean isRunning = new AtomicBoolean(false);
    private final List<RerunExecutionListener> executionListeners = new CopyOnWriteArrayList<>();

    private Process currentProcess;
    private Thread outputThread;
    private final Object processLock = new Object();
    private long sessionStartTime;

    public interface RerunExecutionListener {
        void onRerunStarted(int round, int maxRounds);
        void onRerunCompleted(int round, int exitCode, long durationMs);
        void onRerunFailed(int round, Throwable error);
        void onAllRerunsCompleted(int totalRounds, int successCount, int failureCount);
    }

    public RerunProcessExecutor() {
        this.configuration = RerunConfiguration.getInstance();
    }

    public RerunProcessExecutor(RerunConfiguration config) {
        this.configuration = config;
    }

    public void startSession() {
        this.sessionStartTime = System.currentTimeMillis();
        totalRerunCount.set(0);
        successfulRerunCount.set(0);
        failedRerunCount.set(0);
        logger.info("Rerun executor session started");
    }

    public long getSessionDuration() {
        return System.currentTimeMillis() - sessionStartTime;
    }

    public RerunExecutionResult executeInitialRun() {
        logger.info("Executing initial test run...");

        try {
            List<String> command = configuration.buildInitialRunCommand();

            logger.debug("Executing initial run command: {}", String.join(" ", command));

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);

            currentProcess = pb.start();

            int exitCode = currentProcess.waitFor();
            long duration = System.currentTimeMillis() - sessionStartTime;

            boolean success = exitCode == 0;
            logger.info("Initial test run completed - exit code: {}, duration: {}ms", exitCode, duration);

            return new RerunExecutionResult(1, exitCode, duration, success, null);

        } catch (Exception e) {
            long duration = System.currentTimeMillis() - sessionStartTime;
            logger.error("Initial test run failed", e);
            return new RerunExecutionResult(1, -1, duration, false, e.getMessage());
        }
    }

    public RerunExecutionResult executeRerun(int currentRound, int maxRounds) {
        if (isRunning.get()) {
            logger.warn("Rerun process is already running, skipping duplicate request");
            return new RerunExecutionResult(currentRound, -1, 0, false, "Process already running");
        }

        synchronized (processLock) {
            if (isRunning.get()) {
                return new RerunExecutionResult(currentRound, -1, 0, false, "Process already running");
            }
            isRunning.set(true);
        }

        totalRerunCount.incrementAndGet();
        long startTime = System.currentTimeMillis();
        notifyRerunStarted(currentRound, maxRounds);

        logger.info("Starting round {} rerun (of {} rounds)...", currentRound, maxRounds);

        applyRetryDelay(currentRound);

        try {
            List<String> command = configuration.buildRerunCommand(currentRound, maxRounds);

            logger.debug("Executing command: {}", String.join(" ", command));

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);

            currentProcess = pb.start();

            startOutputStreaming(currentRound);

            int exitCode = currentProcess.waitFor();

            long duration = System.currentTimeMillis() - startTime;

            boolean success = exitCode == 0;
            if (success) {
                successfulRerunCount.incrementAndGet();
            } else {
                failedRerunCount.incrementAndGet();
            }

            logger.info("Round {} rerun completed - exit code: {}, duration: {}ms", currentRound, exitCode, duration);

            notifyRerunCompleted(currentRound, exitCode, duration);

            if (currentRound >= maxRounds) {
                notifyAllRerunsCompleted(maxRounds, successfulRerunCount.get(), failedRerunCount.get());
            }

            return new RerunExecutionResult(currentRound, exitCode, duration, success, null);

        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            failedRerunCount.incrementAndGet();

            logger.error("Rerun execution failed - Round {}", currentRound, e);
            notifyRerunFailed(currentRound, e);

            if (currentRound >= maxRounds) {
                notifyAllRerunsCompleted(maxRounds, successfulRerunCount.get(), failedRerunCount.get());
            }

            return new RerunExecutionResult(currentRound, -1, duration, false, e.getMessage());
        } finally {
            stopOutputStreaming();
            isRunning.set(false);
            // 在重试进程完成后关闭Playwright资源
            closePlaywrightResources();
        }
    }

    private void applyRetryDelay(int currentRound) {
        if (currentRound > 1) {
            long delay = configuration.calculateRetryDelay(currentRound);
            if (delay > 0) {
                logger.info("Round {} retry delay: {}ms (anti-avalanche mechanism)", currentRound, delay);
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    private void startOutputStreaming(int round) {
        if (currentProcess == null) return;

        outputThread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(currentProcess.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    System.out.println(line);
                    logToRerunLog(round, line);
                }
            } catch (IOException e) {
                logger.error("Error streaming process output", e);
            }
        });
        outputThread.setDaemon(true);
        outputThread.setName("RerunOutputThread-" + round);
        outputThread.start();
    }

    private void stopOutputStreaming() {
        if (outputThread != null && outputThread.isAlive()) {
            outputThread.interrupt();
            try {
                outputThread.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        outputThread = null;
    }

    private void closePlaywrightResources() {
        logger.info("Closing browser resources...");
        try {
            PlaywrightManager.closePage();
            PlaywrightManager.closeContext();
            logger.info("Browser resources closed");
        } catch (Exception e) {
            logger.warn("Error closing browser resources: {}", e.getMessage());
        }
    }

    private void logToRerunLog(int round, String line) {
        try {
            String logPath = configuration.getRerunLogFile();
            String timestamp = LocalDateTime.now().format(TIMESTAMP_FORMAT);
            String logEntry = String.format("[%s] [Round %d] %s%n", timestamp, round, line);

            Files.writeString(Paths.get(logPath), logEntry, 
                StandardOpenOption.CREATE, 
                StandardOpenOption.APPEND);
        } catch (IOException e) {
            logger.warn("Failed to write to rerun log", e);
        }
    }

    public boolean cancelRunningRerun() {
        synchronized (processLock) {
            if (currentProcess != null && currentProcess.isAlive()) {
                logger.info("Canceling ongoing rerun process...");
                currentProcess.destroyForcibly();
                stopOutputStreaming();
                isRunning.set(false);
                return true;
            }
        }
        return false;
    }

    public boolean isRerunRunning() {
        return isRunning.get();
    }

    public RerunStatistics getStatistics() {
        return new RerunStatistics(
            totalRerunCount.get(),
            successfulRerunCount.get(),
            failedRerunCount.get(),
            isRunning.get()
        );
    }

    public void addExecutionListener(RerunExecutionListener listener) {
        if (listener != null) {
            executionListeners.add(listener);
        }
    }

    public void removeExecutionListener(RerunExecutionListener listener) {
        executionListeners.remove(listener);
    }

    private void notifyRerunStarted(int round, int maxRounds) {
        for (RerunExecutionListener listener : executionListeners) {
            try {
                listener.onRerunStarted(round, maxRounds);
            } catch (Exception e) {
                logger.warn("Error notifying rerun started", e);
            }
        }
    }

    private void notifyRerunCompleted(int round, int exitCode, long durationMs) {
        for (RerunExecutionListener listener : executionListeners) {
            try {
                listener.onRerunCompleted(round, exitCode, durationMs);
            } catch (Exception e) {
                logger.warn("Error notifying rerun completed", e);
            }
        }
    }

    private void notifyRerunFailed(int round, Throwable error) {
        for (RerunExecutionListener listener : executionListeners) {
            try {
                listener.onRerunFailed(round, error);
            } catch (Exception e) {
                logger.warn("Error notifying rerun failed", e);
            }
        }
    }

    private void notifyAllRerunsCompleted(int totalRounds, int successCount, int failureCount) {
        for (RerunExecutionListener listener : executionListeners) {
            try {
                listener.onAllRerunsCompleted(totalRounds, successCount, failureCount);
            } catch (Exception e) {
                logger.warn("Error notifying all reruns completed", e);
            }
        }
    }

    public void resetStatistics() {
        totalRerunCount.set(0);
        successfulRerunCount.set(0);
        failedRerunCount.set(0);
        isRunning.set(false);
    }

    public static class RerunExecutionResult {
        private final int round;
        private final int exitCode;
        private final long durationMs;
        private final boolean success;
        private final String errorMessage;

        public RerunExecutionResult(int round, int exitCode, long durationMs, 
                                    boolean success, String errorMessage) {
            this.round = round;
            this.exitCode = exitCode;
            this.durationMs = durationMs;
            this.success = success;
            this.errorMessage = errorMessage;
        }

        public int getRound() { return round; }
        public int getExitCode() { return exitCode; }
        public long getDurationMs() { return durationMs; }
        public boolean isSuccess() { return success; }
        public String getErrorMessage() { return errorMessage; }

        @Override
        public String toString() {
            return String.format("RerunResult[round=%d, exitCode=%d, duration=%dms, success=%s]",
                round, exitCode, durationMs, success);
        }
    }

    public static class RerunStatistics {
        private final int totalCount;
        private final int successCount;
        private final int failureCount;
        private final boolean currentlyRunning;

        public RerunStatistics(int totalCount, int successCount, 
                              int failureCount, boolean currentlyRunning) {
            this.totalCount = totalCount;
            this.successCount = successCount;
            this.failureCount = failureCount;
            this.currentlyRunning = currentlyRunning;
        }

        public int getTotalCount() { return totalCount; }
        public int getSuccessCount() { return successCount; }
        public int getFailureCount() { return failureCount; }
        public boolean isCurrentlyRunning() { return currentlyRunning; }

        public double getSuccessRate() {
            return totalCount > 0 ? (successCount * 100.0 / totalCount) : 0.0;
        }

        public String getFormattedSuccessRate() {
            return String.format("%.2f%%", getSuccessRate());
        }

        @Override
        public String toString() {
            return String.format(
                "RerunStatistics[total=%d, success=%d, failure=%d, successRate=%s, running=%s]",
                totalCount, successCount, failureCount, 
                getFormattedSuccessRate(), currentlyRunning);
        }
    }
}
