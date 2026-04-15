package com.hsbc.cmb.hk.dbb.automation.retry.metrics;

import com.hsbc.cmb.hk.dbb.automation.retry.executor.RerunProcessExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class RetryProgressReporter {
    private static final Logger logger = LoggerFactory.getLogger(RetryProgressReporter.class);
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final String ROUND_LOCK_FILE = "target/reporter-round.lock";

    private static class Holder {
        static final RetryProgressReporter INSTANCE = new RetryProgressReporter();
    }

    public static RetryProgressReporter getInstance() {
        return Holder.INSTANCE;
    }

    private final RerunProcessExecutor executor;
    private final AtomicInteger currentRound = new AtomicInteger(0);
    private final AtomicInteger maxRounds = new AtomicInteger(0);
    private final AtomicInteger totalScenarios = new AtomicInteger(0);
    private final AtomicInteger completedScenarios = new AtomicInteger(0);
    private final AtomicInteger passedScenarios = new AtomicInteger(0);
    private final AtomicInteger failedScenarios = new AtomicInteger(0);
    private final AtomicBoolean isReporting = new AtomicBoolean(false);
    private final AtomicLong currentRoundStartTime = new AtomicLong(0);

    private LocalDateTime sessionStartTime;
    private ScheduledExecutorService scheduler;
    private long processId;

    private ProgressListener progressListener;

    public interface ProgressListener {
        void onProgressUpdate(ProgressInfo progress);
        void onRoundComplete(RoundProgress roundProgress);
        void onSessionComplete(SessionProgress sessionProgress);
    }

    private RetryProgressReporter() {
        this.executor = new RerunProcessExecutor();
        this.processId = generateProcessId();
    }

    private long generateProcessId() {
        String name = ManagementFactory.getRuntimeMXBean().getName();
        String pid = name.split("@")[0];
        try {
            return Long.parseLong(pid);
        } catch (Exception e) {
            return Thread.currentThread().threadId();
        }
    }

    private RetryProgressReporter(RerunProcessExecutor executor) {
        this.executor = executor;
    }

    public static RetryProgressReporter getInstance(RerunProcessExecutor executor) {
        Holder.INSTANCE.setExecutor(executor);
        return Holder.INSTANCE;
    }

    private void setExecutor(RerunProcessExecutor executor) {
        if (executor != null) {
            Field field;
            try {
                field = RetryProgressReporter.class.getDeclaredField("executor");
                field.setAccessible(true);
                field.set(this, executor);
            } catch (Exception e) {
                logger.warn("Could not set executor", e);
            }
        }
    }

    public void startSession(int maxRounds) {
        shutdownScheduler();
        this.sessionStartTime = LocalDateTime.now();
        this.currentRound.set(0);
        this.maxRounds.set(maxRounds);
        this.totalScenarios.set(0);
        this.completedScenarios.set(0);
        this.passedScenarios.set(0);
        this.failedScenarios.set(0);
        this.isReporting.set(true);
        this.currentRoundStartTime.set(System.currentTimeMillis());
        writeRoundLock(0, currentRoundStartTime.get());

        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "RetryProgressReporter");
            t.setDaemon(true);
            return t;
        });

        startPeriodicReporting();

        logger.info("Starting retry progress reporting, max rounds: {}", maxRounds);
        notifyProgressUpdate(createProgressInfo());
    }

    public void endSession() {
        if (scheduler != null && !scheduler.isShutdown()) {
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(1, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                scheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }

        isReporting.set(false);

        Path lockPath = Paths.get(ROUND_LOCK_FILE);
        try {
            Files.deleteIfExists(lockPath);
        } catch (IOException e) {
            logger.warn("Failed to delete round lock file", e);
        }

        logger.info("Ending retry progress reporting");

        SessionProgress sessionProgress = createSessionProgress();
        notifySessionComplete(sessionProgress);
    }

    public void startRound(int round, int maxRounds) {
        if (sessionStartTime == null) {
            sessionStartTime = LocalDateTime.now();
        }

        currentRound.set(round);
        int oldMax = this.maxRounds.get();
        if (oldMax <= 0) {
            this.maxRounds.set(maxRounds);
        }

        completedScenarios.set(0);
        passedScenarios.set(0);
        failedScenarios.set(0);

        shutdownScheduler();

        isReporting.set(true);
        currentRoundStartTime.set(System.currentTimeMillis());
        writeRoundLock(round, currentRoundStartTime.get());

        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "RetryProgressReporter");
            t.setDaemon(true);
            return t;
        });
        startPeriodicReporting();

        logger.info("Starting retry round {}/{}", round, maxRounds);
        notifyProgressUpdate(createProgressInfo());
    }

    public void startRound(int round) {
        startRound(round, maxRounds.get());
    }

    public void endRound(int round, int passed, int failed, int retriedPassed, long durationMs) {
        passedScenarios.set(passed);
        failedScenarios.set(failed);

        RoundProgress roundProgress = new RoundProgress(
            round,
            maxRounds.get(),
            passed,
            failed,
            retriedPassed,
            durationMs,
            calculateRoundProgress()
        );

        notifyRoundComplete(roundProgress);

        logger.info("Round {} completed - passed: {}, failed: {}, retried passed: {}, duration: {}ms",
            round, passed, failed, retriedPassed, durationMs);
    }

    public void updateScenarioProgress(int completed, int total, int passed, int failed) {
        completedScenarios.set(completed);
        totalScenarios.set(total);
        passedScenarios.set(passed);
        failedScenarios.set(failed);

        notifyProgressUpdate(createProgressInfo());
    }

    public void recordScenarioResult(boolean passed) {
        if (passed) {
            passedScenarios.incrementAndGet();
        } else {
            failedScenarios.incrementAndGet();
        }
        completedScenarios.incrementAndGet();

        notifyProgressUpdate(createProgressInfo());
    }

    public void setProgressListener(ProgressListener listener) {
        this.progressListener = listener;
    }

    private void shutdownScheduler() {
        if (scheduler != null && !scheduler.isShutdown()) {
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(2, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                scheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    private void writeRoundLock(int round, long startTime) {
        Path lockPath = Paths.get(ROUND_LOCK_FILE);
        try {
            Files.createDirectories(lockPath.getParent());
            String content = processId + ":" + round + ":" + startTime;
            Files.writeString(lockPath, content);
        } catch (IOException e) {
            logger.warn("Failed to write round lock file", e);
        }
    }

    private long readRoundLockOwner() {
        Path lockPath = Paths.get(ROUND_LOCK_FILE);
        try {
            if (Files.exists(lockPath)) {
                String content = Files.readString(lockPath);
                String[] parts = content.split(":");
                if (parts.length >= 1) {
                    return Long.parseLong(parts[0]);
                }
            }
        } catch (Exception e) {
            logger.debug("Failed to read round lock file", e);
        }
        return -1;
    }

    private boolean isCurrentProcessOwner() {
        long ownerPid = readRoundLockOwner();
        return ownerPid == processId;
    }

    private void startPeriodicReporting() {
        scheduler.scheduleAtFixedRate(() -> {
            if (isReporting.get() && isCurrentProcessOwner()) {
                ProgressInfo progress = createProgressInfo();
                logProgress(progress);
                notifyProgressUpdate(progress);
            } else if (isReporting.get() && !isCurrentProcessOwner()) {
                logger.debug("Skipping progress report - not current process owner, pid={}, owner={}",
                    processId, readRoundLockOwner());
            }
        }, 10, 10, TimeUnit.SECONDS);
    }

    private ProgressInfo createProgressInfo() {
        Duration elapsed = Duration.between(sessionStartTime, LocalDateTime.now());
        int current = currentRound.get();
        int max = maxRounds.get();

        double overallProgress = calculateOverallProgress();

        return new ProgressInfo(
            current,
            max,
            totalScenarios.get(),
            completedScenarios.get(),
            passedScenarios.get(),
            failedScenarios.get(),
            elapsed.toMillis(),
            overallProgress,
            isRerunRunning()
        );
    }

    private SessionProgress createSessionProgress() {
        Duration totalDuration = Duration.between(sessionStartTime, LocalDateTime.now());

        RerunProcessExecutor.RerunStatistics stats = executor.getStatistics();

        return new SessionProgress(
            maxRounds.get(),
            stats.getTotalCount(),
            stats.getSuccessCount(),
            stats.getFailureCount(),
            stats.getSuccessRate(),
            totalDuration.toMillis(),
            totalScenarios.get(),
            passedScenarios.get(),
            failedScenarios.get()
        );
    }

    private double calculateOverallProgress() {
        int current = currentRound.get();
        int max = maxRounds.get();

        if (max <= 0) return 0.0;

        double roundProgress = current * 100.0 / max;

        int completed = completedScenarios.get();
        int total = totalScenarios.get();

        if (total > 0) {
            double scenarioProgress = completed * 100.0 / total;
            return (roundProgress + scenarioProgress) / 2;
        }

        return roundProgress;
    }

    private double calculateRoundProgress() {
        int completed = completedScenarios.get();
        int total = totalScenarios.get();

        if (total <= 0) return 0.0;
        return completed * 100.0 / total;
    }

    private boolean isRerunRunning() {
        return executor.isRerunRunning();
    }

    private void logProgress(ProgressInfo progress) {
        String status = progress.isRunning ? "RUNNING" : "PAUSED";
        String progressBar = createProgressBar(progress.getOverallProgress());

        logger.info("[Progress] {} {} | Round: {}/{} | Scenarios: {}/{} | Passed: {} | Failed: {} | Elapsed: {}",
            status,
            progressBar,
            progress.getCurrentRound(),
            progress.getMaxRounds(),
            progress.getCompletedScenarios(),
            progress.getTotalScenarios(),
            progress.getPassedScenarios(),
            progress.getFailedScenarios(),
            formatDuration(progress.getElapsedMs())
        );
    }

    private String createProgressBar(double progress) {
        int width = 20;
        int filled = (int) (progress * width / 100);
        int empty = width - filled;

        StringBuilder bar = new StringBuilder("[");
        for (int i = 0; i < filled; i++) {
            bar.append("█");
        }
        for (int i = 0; i < empty; i++) {
            bar.append("░");
        }
        bar.append("] ");
        bar.append(String.format("%.1f%%", progress));

        return bar.toString();
    }

    private String formatDuration(long ms) {
        Duration duration = Duration.ofMillis(ms);
        long seconds = duration.getSeconds();
        long minutes = seconds / 60;
        seconds = seconds % 60;

        if (minutes > 0) {
            return String.format("%d分%d秒", minutes, seconds);
        }
        return String.format("%d秒", seconds);
    }

    private void notifyProgressUpdate(ProgressInfo progress) {
        if (progressListener != null) {
            try {
                progressListener.onProgressUpdate(progress);
            } catch (Exception e) {
                logger.warn("Error notifying progress update", e);
            }
        }
    }

    private void notifyRoundComplete(RoundProgress roundProgress) {
        if (progressListener != null) {
            try {
                progressListener.onRoundComplete(roundProgress);
            } catch (Exception e) {
                logger.warn("Error notifying round complete", e);
            }
        }
    }

    private void notifySessionComplete(SessionProgress sessionProgress) {
        if (progressListener != null) {
            try {
                progressListener.onSessionComplete(sessionProgress);
            } catch (Exception e) {
                logger.warn("Error notifying session complete", e);
            }
        }
    }

    public static class ProgressInfo {
        private final int currentRound;
        private final int maxRounds;
        private final int totalScenarios;
        private final int completedScenarios;
        private final int passedScenarios;
        private final int failedScenarios;
        private final long elapsedMs;
        private final double overallProgress;
        private final boolean isRunning;

        public ProgressInfo(int currentRound, int maxRounds, int totalScenarios,
                          int completedScenarios, int passedScenarios, int failedScenarios,
                          long elapsedMs, double overallProgress, boolean isRunning) {
            this.currentRound = currentRound;
            this.maxRounds = maxRounds;
            this.totalScenarios = totalScenarios;
            this.completedScenarios = completedScenarios;
            this.passedScenarios = passedScenarios;
            this.failedScenarios = failedScenarios;
            this.elapsedMs = elapsedMs;
            this.overallProgress = overallProgress;
            this.isRunning = isRunning;
        }

        public int getCurrentRound() { return currentRound; }
        public int getMaxRounds() { return maxRounds; }
        public int getTotalScenarios() { return totalScenarios; }
        public int getCompletedScenarios() { return completedScenarios; }
        public int getPassedScenarios() { return passedScenarios; }
        public int getFailedScenarios() { return failedScenarios; }
        public long getElapsedMs() { return elapsedMs; }
        public double getOverallProgress() { return overallProgress; }
        public boolean isRunning() { return isRunning; }

        public String getFormattedProgress() {
            return String.format("%.2f%%", overallProgress);
        }

        public String getRoundDescription() {
            return String.format("%d/%d", currentRound, maxRounds);
        }
    }

    public static class RoundProgress {
        private final int round;
        private final int maxRounds;
        private final int passed;
        private final int failed;
        private final int retriedPassed;
        private final long durationMs;
        private final double progress;

        public RoundProgress(int round, int maxRounds, int passed, int failed,
                           int retriedPassed, long durationMs, double progress) {
            this.round = round;
            this.maxRounds = maxRounds;
            this.passed = passed;
            this.failed = failed;
            this.retriedPassed = retriedPassed;
            this.durationMs = durationMs;
            this.progress = progress;
        }

        public int getRound() { return round; }
        public int getMaxRounds() { return maxRounds; }
        public int getPassed() { return passed; }
        public int getFailed() { return failed; }
        public int getRetriedPassed() { return retriedPassed; }
        public long getDurationMs() { return durationMs; }
        public double getProgress() { return progress; }

        public String getFormattedProgress() {
            return String.format("%.2f%%", progress);
        }
    }

    public static class SessionProgress {
        private final int maxRounds;
        private final int totalRounds;
        private final int successCount;
        private final int failureCount;
        private final double successRate;
        private final long totalDurationMs;
        private final int totalScenarios;
        private final int passedScenarios;
        private final int failedScenarios;

        public SessionProgress(int maxRounds, int totalRounds, int successCount,
                              int failureCount, double successRate, long totalDurationMs,
                              int totalScenarios, int passedScenarios, int failedScenarios) {
            this.maxRounds = maxRounds;
            this.totalRounds = totalRounds;
            this.successCount = successCount;
            this.failureCount = failureCount;
            this.successRate = successRate;
            this.totalDurationMs = totalDurationMs;
            this.totalScenarios = totalScenarios;
            this.passedScenarios = passedScenarios;
            this.failedScenarios = failedScenarios;
        }

        public int getMaxRounds() { return maxRounds; }
        public int getTotalRounds() { return totalRounds; }
        public int getSuccessCount() { return successCount; }
        public int getFailureCount() { return failureCount; }
        public double getSuccessRate() { return successRate; }
        public long getTotalDurationMs() { return totalDurationMs; }
        public int getTotalScenarios() { return totalScenarios; }
        public int getPassedScenarios() { return passedScenarios; }
        public int getFailedScenarios() { return failedScenarios; }

        public String getFormattedSuccessRate() {
            return String.format("%.2f%%", successRate);
        }
    }
}
