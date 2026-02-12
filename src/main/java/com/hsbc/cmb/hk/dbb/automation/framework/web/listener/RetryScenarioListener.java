package com.hsbc.cmb.hk.dbb.automation.framework.web.listener;

import com.hsbc.cmb.hk.dbb.automation.framework.web.core.FrameworkCore;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.PlaywrightManager;
import com.hsbc.cmb.hk.dbb.automation.framework.web.utils.LoggingConfigUtil;
import com.hsbc.cmb.hk.dbb.automation.retry.listener.RetryHealthChecker;
import com.hsbc.cmb.hk.dbb.automation.retry.listener.RetryHealthChecker.HealthStatus;
import com.hsbc.cmb.hk.dbb.automation.retry.metrics.RetryMetricsCollector;
import com.hsbc.cmb.hk.dbb.automation.retry.metrics.RetryProgressReporter;
import com.hsbc.cmb.hk.dbb.automation.retry.metrics.RetryProgressReporter.ProgressInfo;
import com.hsbc.cmb.hk.dbb.automation.retry.metrics.RetryProgressReporter.ProgressListener;
import com.hsbc.cmb.hk.dbb.automation.retry.metrics.RetryProgressReporter.RoundProgress;
import com.hsbc.cmb.hk.dbb.automation.retry.metrics.RetryProgressReporter.SessionProgress;
import com.hsbc.cmb.hk.dbb.automation.retry.configuration.RerunConfiguration;
import com.hsbc.cmb.hk.dbb.automation.retry.executor.RerunProcessExecutor;
import com.hsbc.cmb.hk.dbb.automation.retry.executor.RerunProcessExecutor.RerunExecutionListener;
import com.hsbc.cmb.hk.dbb.automation.retry.executor.RerunProcessExecutor.RerunStatistics;
import com.hsbc.cmb.hk.dbb.automation.retry.configuration.RoundResult;
import io.cucumber.plugin.EventListener;
import io.cucumber.plugin.event.EventPublisher;
import io.cucumber.plugin.event.Location;
import io.cucumber.plugin.event.Status;
import io.cucumber.plugin.event.TestCase;
import io.cucumber.plugin.event.TestCaseFinished;
import io.cucumber.plugin.event.TestCaseStarted;
import io.cucumber.plugin.event.TestRunFinished;
import net.thucydides.model.domain.TestResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;

public class RetryScenarioListener implements EventListener, RerunExecutionListener, ProgressListener {

    private static final Logger logger = LoggerFactory.getLogger(RetryScenarioListener.class);

    public static final String RERUN_FILE = "target/rerun.txt";
    public static final String RERUN_LOG_FILE = "target/rerun.log";
    public static final String RERUN_SUMMARY_FILE = "target/rerun-summary.json";

    private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final ConcurrentMap<String, ScenarioResult> scenarioResults = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Integer> attemptCounts = new ConcurrentHashMap<>();
    private final List<RetryEvent> retryEvents = new CopyOnWriteArrayList<>();
    private final List<RoundResult> roundResults = new CopyOnWriteArrayList<>();

    private final List<Location> failedTestLocations = new CopyOnWriteArrayList<>();

    private static volatile RetryScenarioListener instance;

    private RerunConfiguration configuration;
    private RerunProcessExecutor executor;
    private RetryMetricsCollector metricsCollector;
    private RetryProgressReporter progressReporter;
    private RetryHealthChecker healthChecker;

    private int maxRerunAttempts = 0; // 默认不启用重试
    private boolean isRerunMode = false;
    private int currentRerunRound = 0;
    private int totalScenarios = 0;
    private int passedScenarios = 0;
    private int failedScenarios = 0;
    private int retriedScenarios = 0;
    private long totalRerunDuration = 0;

    private volatile boolean initialRunCompleted = false;
    private volatile boolean rerunFilesCleared = false;
    private volatile boolean eventPublisherSet = false;
    private volatile boolean configurationInitialized = false;

    private final Object writeLock = new Object();
    private final Object rerunLock = new Object();
    private long sessionStartTime;

    public RetryScenarioListener() {
        synchronized (RetryScenarioListener.class) {
            if (instance == null) {
                instance = this;
                initialize();
            }
        }
    }

    public static RetryScenarioListener getInstance() {
        if (instance == null) {
            synchronized (RetryScenarioListener.class) {
                if (instance == null) {
                    instance = new RetryScenarioListener();
                }
            }
        }
        return instance;
    }

    private void initialize() {
        // 延迟初始化配置，只有需要重试时才加载
        initializeFromConfiguration();

        // 只有在确实启用重试功能时才清理旧的测试结果文件
        if (maxRerunAttempts > 0) {
            cleanOldTestResults();
            LoggingConfigUtil.logInfoIfVerbose(logger, "RetryScenarioListener initialized with max attempts: {}", maxRerunAttempts);
        } else {
            LoggingConfigUtil.logInfoIfVerbose(logger, "RetryScenarioListener initialized - retry mechanism disabled, skipping cleanup");
        }
    }

    /**
     * 清理旧的Serenity测试结果文件
     * 确保报告中只包含当前运行的测试结果，而不是历史数据
     */
    private void cleanOldTestResults() {
        try {
            File serenityReportDir = new File("target/site/serenity");
            if (serenityReportDir.exists()) {
                logger.info("Cleaning old test results from: {}", serenityReportDir.getAbsolutePath());
                cleanDirectory(serenityReportDir);
            }

            // 同时清理target目录下的其他测试结果文件
            cleanDirectoryRecursive(new File("target"), ".ser");
            cleanDirectoryRecursive(new File("target"), ".json");

        } catch (Exception e) {
            logger.warn("Failed to clean old test results", e);
        }
    }

    /**
     * 递归清理目录中的.ser和.json文件
     */
    private void cleanDirectory(File directory) {
        if (!directory.exists() || !directory.isDirectory()) {
            return;
        }

        File[] files = directory.listFiles();
        if (files == null) {
            return;
        }

        for (File file : files) {
            if (file.isDirectory()) {
                cleanDirectory(file);
            } else {
                String fileName = file.getName();
                // 删除所有.ser文件（Serenity测试结果）
                if (fileName.endsWith(".ser")) {
                    boolean deleted = file.delete();
                    if (deleted) {
                        logger.debug("Deleted old test result file: {}", fileName);
                    } else {
                        logger.warn("Failed to delete old test result file: {}", fileName);
                    }
                }
            }
        }
    }

    /**
     * 递归清理指定扩展名的文件
     */
    private void cleanDirectoryRecursive(File directory, String extension) {
        if (!directory.exists() || !directory.isDirectory()) {
            return;
        }

        File[] files = directory.listFiles();
        if (files == null) {
            return;
        }

        for (File file : files) {
            if (file.isDirectory()) {
                // 跳过某些特定目录
                if (!file.getName().equals("classes") && !file.getName().equals("test-classes")) {
                    cleanDirectoryRecursive(file, extension);
                }
            } else {
                String fileName = file.getName();
                if (fileName.endsWith(extension)) {
                    boolean deleted = file.delete();
                    if (deleted) {
                        logger.debug("Deleted old file with extension {}: {}", extension, fileName);
                    }
                }
            }
        }
    }

    private void initializeFromConfiguration() {
        // 只从系统属性读取（与 Serenity BDD 保持一致）
        String rerunCountStr = System.getProperty("rerunFailingTestsCount");
        if (rerunCountStr == null || rerunCountStr.trim().isEmpty()) {
            LoggingConfigUtil.logInfoIfVerbose(logger, "rerunFailingTestsCount not specified, retry mechanism disabled");
            maxRerunAttempts = 0;
        } else {
            try {
                maxRerunAttempts = Math.max(1, Integer.parseInt(rerunCountStr));
                LoggingConfigUtil.logInfoIfVerbose(logger, "Retry mechanism enabled with max attempts: {}", maxRerunAttempts);
            } catch (NumberFormatException e) {
                logger.warn("Invalid rerunFailingTestsCount value: {}, disabling retry", rerunCountStr);
                maxRerunAttempts = 0;
            }
        }

        String rerunMode = System.getProperty("rerun.mode", "false");
        this.isRerunMode = Boolean.parseBoolean(rerunMode);

        String rerunRoundStr = System.getProperty("rerun.round", "1");
        try {
            this.currentRerunRound = Math.max(1, Integer.parseInt(rerunRoundStr));
        } catch (NumberFormatException e) {
            this.currentRerunRound = 1;
        }
    }

    @Override
    public void setEventPublisher(EventPublisher publisher) {
        logger.info("[RetryScenarioListener] setEventPublisher called - maxRerunAttempts: {}, isRerunMode: {}",
            maxRerunAttempts, isRerunMode);

        // 在重试模式下，立即初始化重试组件
        if (isRerunMode && maxRerunAttempts > 0) {
            logger.info("[RetryScenarioListener] Initializing retry components for rerun mode");
            initializeRetryComponents();
        }

        // 首轮运行：如果设置了 rerunFailingTestsCount，注册监听器收集失败场景
        // 重试模式：如果设置了 maxRerunAttempts，注册监听器处理重试逻辑
        if (maxRerunAttempts > 0) {
            logger.info("[RetryScenarioListener] Registering event handlers (retry enabled)");
            publisher.registerHandlerFor(TestCaseFinished.class, this::handleTestCaseFinished);
            publisher.registerHandlerFor(TestRunFinished.class, this::handleTestRunFinished);
            publisher.registerHandlerFor(TestCaseStarted.class, this::handleTestRunStarted);

            eventPublisherSet = true;
            logger.info("[RetryScenarioListener] Event handlers registered successfully - eventPublisherSet: {}", eventPublisherSet);
        } else {
            logger.info("[RetryScenarioListener] Retry mechanism disabled (maxRerunAttempts=0), skipping event handler registration");
            eventPublisherSet = false;
        }
    }

    /**
     * 延迟初始化重试组件
     */
    private void initializeRetryComponents() {
        if (!configurationInitialized) {
            synchronized (this) {
                if (!configurationInitialized) {
                    logger.info("[RetryScenarioListener] Initializing retry components...");
                    this.configuration = RerunConfiguration.getInstance();
                    // 调用延迟初始化,避免在首轮运行时加载配置
                    this.configuration.lazyInitialize();
                    this.executor = new RerunProcessExecutor(configuration);
                    this.executor.addExecutionListener(this);
                    this.progressReporter = RetryProgressReporter.getInstance(executor);
                    this.progressReporter.setProgressListener(this);
                    configurationInitialized = true;
                    logger.debug(configuration.getPluginConfigurationSummary());
                }
            }
        }
    }

    private void handleTestRunStarted(TestCaseStarted event) {
        String roundInfo = isRerunMode ? String.format("[Retry Round %d/%d]", currentRerunRound, maxRerunAttempts) : "[Initial Run]";
        logger.info("{} Test started - rerun mode: {}, max attempts: {}", roundInfo, isRerunMode, maxRerunAttempts);

        // 只有在启用重试功能时才执行重试相关逻辑
        if (maxRerunAttempts > 0) {
            if (!isRerunMode && !rerunFilesCleared) {
                clearRerunFiles();
                initializeMonitoring();
                rerunFilesCleared = true;
                sessionStartTime = System.currentTimeMillis();
            }

            if (isRerunMode) {
                // 确保重试组件已经初始化
                initializeRetryComponents();
                progressReporter.startRound(currentRerunRound, maxRerunAttempts);
                PlaywrightManager.createNewContextAndPage();
                logger.info("Creating new browser context and page in rerun mode");
            }
        }

        try {
            FrameworkCore.getInstance().initialize();
        } catch (Exception e) {
            logger.error("Failed to initialize FrameworkCore", e);
        }
    }

    private void initializeMonitoring() {
        metricsCollector.startSession();
        progressReporter.startSession(maxRerunAttempts);
        healthChecker.startSession();
        healthChecker.checkConfigurationHealth(configuration);
        executor.startSession();

        logger.info("Monitoring components initialized");
    }

    @Override
    public void onProgressUpdate(ProgressInfo progress) {
        HealthStatus health = healthChecker.getCurrentStatus();
        logger.debug("[Progress Update] Round: {}/{} | Scenarios: {}/{} | Passed: {} | Failed: {} | Health: {}",
            progress.getCurrentRound(),
            progress.getMaxRounds(),
            progress.getCompletedScenarios(),
            progress.getTotalScenarios(),
            progress.getPassedScenarios(),
            progress.getFailedScenarios(),
            health.getDisplayName());
    }

    @Override
    public void onRoundComplete(RoundProgress roundProgress) {
        logger.info("[Round Complete] Round {} | Passed: {} | Failed: {} | Retried Passed: {} | Duration: {}ms",
            roundProgress.getRound(),
            roundProgress.getPassed(),
            roundProgress.getFailed(),
            roundProgress.getRetriedPassed(),
            roundProgress.getDurationMs());

        healthChecker.recordRoundEnd(roundProgress.getRound(), roundProgress.getPassed() > 0);
    }

    @Override
    public void onSessionComplete(SessionProgress sessionProgress) {
        logger.info("[Session Complete] Total Rounds: {} | Success: {} | Failed: {} | Success Rate: {} | Total Duration: {}ms",
            sessionProgress.getTotalRounds(),
            sessionProgress.getSuccessCount(),
            sessionProgress.getFailureCount(),
            sessionProgress.getFormattedSuccessRate(),
            sessionProgress.getTotalDurationMs());

        healthChecker.endSession();
        metricsCollector.endSession();
    }

    private void handleTestCaseFinished(TestCaseFinished event) {
        TestCase testCase = event.getTestCase();
        String scenarioId = generateScenarioId(testCase);
        io.cucumber.plugin.event.Result cucumberResult = event.getResult();
        TestResult result = convertToSerenityResult(cucumberResult.getStatus());

        totalScenarios++;

        int attempt = attemptCounts.merge(scenarioId, 1, Integer::sum);

        ScenarioResult scenarioResult = new ScenarioResult(scenarioId, cucumberResult.getStatus(), attempt);

        logger.info("[RetryScenarioListener] Test case finished - ID: {}, Status: {}, Attempt: {}/{}, isRerunMode: {}, initialRunCompleted: {}",
            scenarioId, cucumberResult.getStatus(), attempt, maxRerunAttempts, isRerunMode, initialRunCompleted);

        if (cucumberResult.getStatus() == Status.FAILED) {
            failedScenarios++;
            scenarioResults.put(scenarioId, scenarioResult);

            Throwable error = cucumberResult.getError();
            recordRetryEvent(scenarioId, attempt, error);

            if (maxRerunAttempts > 0 && attempt < maxRerunAttempts) {
                if (!isRerunMode && !initialRunCompleted) {
                    failedTestLocations.add(testCase.getLocation());
                    logger.warn("Test failed [Attempt {}/{}]: {} - Will add to rerun queue after initial run completes. failedTestLocations size: {}",
                        attempt, maxRerunAttempts, scenarioId, failedTestLocations.size());
                } else {
                    addToRerunFile(scenarioId, testCase.getLocation());
                    logger.warn("Test failed [Attempt {}/{}]: {} - Adding to rerun queue",
                        attempt, maxRerunAttempts, scenarioId);
                }
            } else if (maxRerunAttempts == 0) {
                logger.warn("Test failed [Attempt {}/{}]: {} - Retry mechanism disabled, will not retry",
                    attempt, maxRerunAttempts, scenarioId);
            } else if (isRerunMode) {
                logger.error("Rerun failed [Round {}/{}]: {} - Error: {}",
                    currentRerunRound, maxRerunAttempts, scenarioId,
                    error != null ? error.getClass().getSimpleName() : "Unknown");
            }
        } else if (cucumberResult.getStatus() == Status.PASSED) {
            passedScenarios++;
            if (attempt > 1) {
                retriedScenarios++;
                logger.info("Retry succeeded [Round {}]: {} - Passed after {} attempts",
                    currentRerunRound, scenarioId, attempt);
            } else {
                logger.info("Test passed: {}", scenarioId);
            }
        }
    }

    private TestResult convertToSerenityResult(Status status) {
        if (status == null) {
            return TestResult.PENDING;
        }
        if (status == Status.PASSED) {
            return TestResult.SUCCESS;
        } else if (status == Status.FAILED) {
            return TestResult.FAILURE;
        } else if (status == Status.SKIPPED || status == Status.PENDING) {
            return TestResult.SKIPPED;
        } else {
            return TestResult.UNDEFINED;
        }
    }

    private void handleTestRunFinished(TestRunFinished event) {
        String roundInfo = isRerunMode ? String.format("[Round %d/%d Complete]", currentRerunRound, maxRerunAttempts) : "[Initial Run Complete]";
        logger.info("{} Test complete - Total: {}, Passed: {}, Failed: {}, Retried Passed: {}", 
            roundInfo, totalScenarios, passedScenarios, failedScenarios, retriedScenarios);

        logger.info("[RetryScenarioListener] handleTestRunFinished - isRerunMode: {}, initialRunCompleted: {}, failedTestLocations size: {}, rerun file exists: {}, maxRerunAttempts: {}",
            isRerunMode, initialRunCompleted, failedTestLocations.size(), new File(RERUN_FILE).exists(), maxRerunAttempts);

        try {
            if (!isRerunMode) {
                initialRunCompleted = true;
                
                for (Location location : failedTestLocations) {
                    addToRerunFile(location);
                }
                
                logger.info("[RetryScenarioListener] Writing {} failed test locations to rerun file", failedTestLocations.size());

                boolean hasFailures = hasRemainingFailures();
                boolean canTriggerRetry = maxRerunAttempts > 0 && attemptCounts.values().stream().anyMatch(a -> a <= maxRerunAttempts);

                logger.info("[RetryScenarioListener] Retry decision - hasFailures: {}, canTriggerRetry: {}, maxRerunAttempts: {}",
                    hasFailures, canTriggerRetry, maxRerunAttempts);

                if (hasFailures && canTriggerRetry) {
                    logger.info("Initial run complete, failures detected, initializing retry components...");
                    // 只有在有失败用例时才初始化重试组件
                    initializeRetryComponents();
                    logger.info("Initializing retry components completed, triggering rerun...");
                    triggerRerun();
                } else {
                    if (maxRerunAttempts == 0) {
                        logger.info("Retry mechanism disabled (maxRerunAttempts=0), no rerun will be triggered");
                    } else {
                        logger.info("No rerun triggered - hasFailures: {}, canTriggerRetry: {}", hasFailures, canTriggerRetry);
                    }
                    generateFinalReport();
                    cleanup();
                }
            } else {
                boolean hasRemainingFailures = hasRemainingFailures();
                boolean canContinueRetry = currentRerunRound < maxRerunAttempts;

                logger.info("[RetryScenarioListener] Rerun mode - hasRemainingFailures: {}, currentRerunRound: {}, maxRerunAttempts: {}, canContinueRetry: {}",
                    hasRemainingFailures, currentRerunRound, maxRerunAttempts, canContinueRetry);

                if (hasRemainingFailures) {
                    if (canContinueRetry) {
                        logger.info("Continuing to next rerun round (Round {}/{})...",
                            currentRerunRound + 1, maxRerunAttempts);
                        triggerRerun();
                    } else {
                        logger.warn("Max retry attempts {} reached, stopping retry", maxRerunAttempts);
                        generateFinalReport();
                        cleanup();
                    }
                } else {
                    logger.info("All tests passed after round {} retry!", currentRerunRound);
                    generateFinalReport();
                    cleanup();
                }
            }
        } catch (Exception e) {
            logger.error("Error during test run cleanup", e);
        }
    }

    private String generateScenarioId(TestCase testCase) {
        String uri = testCase.getUri().toString();
        String scenarioName = testCase.getName();
        
        String featureName;
        if (uri.contains("/")) {
            featureName = uri.substring(uri.lastIndexOf('/') + 1);
            if (featureName.contains(".feature")) {
                featureName = featureName.replace(".feature", "");
            }
        } else {
            featureName = uri;
        }
        
        return featureName + ";" + scenarioName;
    }

    private void recordRetryEvent(String scenarioId, int attempt, Throwable error) {
        RetryEvent event = new RetryEvent(scenarioId, attempt, error);
        retryEvents.add(event);
        String errorType = error != null ? error.getClass().getSimpleName() : "UnknownError";
        String errorMessage = error != null ? error.getMessage() : "Unknown error";
        metricsCollector.recordScenarioFailure(scenarioId, attempt, errorType, errorMessage);
    }

    private void clearRerunFiles() {
        Path rerunPath = Paths.get(RERUN_FILE);
        try {
            if (Files.exists(rerunPath)) {
                boolean deleted = Files.deleteIfExists(rerunPath);
                if (deleted) {
                    logger.debug("Cleared rerun file");
                } else {
                    truncateFile(rerunPath);
                }
            }
        } catch (IOException e) {
            logger.debug("Could not clear rerun file, attempting to truncate: {}", e.getMessage());
            truncateFile(rerunPath);
        }
    }

    private void truncateFile(Path path) {
        try {
            Files.newBufferedWriter(path,
                java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.TRUNCATE_EXISTING,
                java.nio.file.StandardOpenOption.WRITE).close();
            logger.debug("Truncated rerun file successfully");
        } catch (IOException e) {
            logger.warn("Failed to truncate rerun file: {}", e.getMessage());
        }
    }

    private synchronized void addToRerunFile(String scenarioId, Location location) {
        synchronized (writeLock) {
            try (PrintWriter writer = new PrintWriter(new FileWriter(RERUN_FILE, true))) {
                String locationStr = location.toString();
                writer.println(locationStr);
            } catch (IOException e) {
                logger.error("Failed to write to rerun file", e);
            }
        }
    }
    
    private synchronized void addToRerunFile(Location location) {
        synchronized (writeLock) {
            try (PrintWriter writer = new PrintWriter(new FileWriter(RERUN_FILE, true))) {
                String locationStr = location.toString();
                writer.println(locationStr);
            } catch (IOException e) {
                logger.error("Failed to write to rerun file", e);
            }
        }
    }

    private boolean hasRemainingFailures() {
        File rerunFile = new File(RERUN_FILE);
        return rerunFile.exists() && rerunFile.length() > 0;
    }

    private void triggerRerun() {
        synchronized (rerunLock) {
            logger.info("[RetryScenarioListener] ENTERING triggerRerun - currentRerunRound: {}, maxRerunAttempts: {}",
                currentRerunRound, maxRerunAttempts);
            
            int nextRound = currentRerunRound + 1;

            if (nextRound > maxRerunAttempts) {
                logger.warn("[RetryScenarioListener] Max retry attempts {} reached, stopping retry", maxRerunAttempts);
                return;
            }

            logger.info("[RetryScenarioListener] Starting round {} rerun (of {} rounds)...", nextRound, maxRerunAttempts);

            RerunProcessExecutor.RerunExecutionResult result = executor.executeRerun(nextRound, maxRerunAttempts);

            if (result.isSuccess()) {
                logger.info("[RetryScenarioListener] Round {} rerun completed successfully", nextRound);
            } else {
                logger.warn("[RetryScenarioListener] Round {} rerun failed - exit code: {}", nextRound, result.getExitCode());
            }
        }
    }

    @Override
    public void onRerunStarted(int round, int maxRounds) {
        logger.info("Starting round {} rerun (of {} rounds)", round, maxRounds);
    }

    @Override
    public void onRerunCompleted(int round, int exitCode, long durationMs) {
        RerunStatistics stats = executor.getStatistics();
        logger.info("Round {} rerun completed - exit code: {}, duration: {}ms, stats: {}", 
            round, exitCode, durationMs, stats);
    }

    @Override
    public void onRerunFailed(int round, Throwable error) {
        logger.error("Round {} rerun execution failed: {}", round, error.getMessage());
    }

    @Override
    public void onAllRerunsCompleted(int totalRounds, int successCount, int failureCount) {
        long duration = System.currentTimeMillis() - sessionStartTime;
        totalRerunDuration = duration;
        roundResults.add(new RoundResult(totalRounds, successCount, failureCount, duration));
        
        logger.info("All reruns complete - Total rounds: {}, Success: {}, Failed: {}, Duration: {}ms", 
            totalRounds, successCount, failureCount, duration);
        
        determineFinalTestStatus();
        generateFinalReport();
        cleanup();
    }

    private void determineFinalTestStatus() {
        logger.info("Determining final test status...");
        
        int finalPassed = 0;
        int finalFailed = 0;
        int recoveredByRetry = 0;

        for (Map.Entry<String, ScenarioResult> entry : scenarioResults.entrySet()) {
            String scenarioId = entry.getKey();
            ScenarioResult result = entry.getValue();
            int attempts = attemptCounts.getOrDefault(scenarioId, 1);

            if (result.status == Status.PASSED) {
                if (attempts > 1) {
                    recoveredByRetry++;
                }
                finalPassed++;
            } else {
                finalFailed++;
                if (attempts > 1) {
                }
            }
        }

        logger.info("Final results - Passed: {}, Failed: {}, Recovered by retry: {}, Success rate: {}%", 
            finalPassed, finalFailed, recoveredByRetry, 
            String.format("%.2f%%", finalPassed * 100.0 / Math.max(1, finalPassed + finalFailed)));
    }

    private void generateFinalReport() {
        try {
            logger.info("Generating final retry report...");

            writeRerunLog();
            writeSummaryJson();
            logger.info("Final report generated successfully");
        } catch (Exception e) {
            logger.error("Failed to generate final report", e);
        }
    }

    private void writeRerunLog() {
        try (PrintWriter writer = new PrintWriter(new FileWriter(RERUN_LOG_FILE))) {
            writer.println("================================================================================");
            writer.println("RERUN EXECUTION LOG");
            writer.println("Timestamp: " + LocalDateTime.now().format(TIMESTAMP_FORMAT));
            writer.println("Max Retry Attempts: " + maxRerunAttempts);
            writer.println("================================================================================\n");

            writer.println("SUMMARY:");
            writer.println("  Total Scenarios: " + totalScenarios);
            writer.println("  Passed: " + passedScenarios);
            writer.println("  Failed: " + failedScenarios);
            writer.println("  Retried (passed after retry): " + retriedScenarios);
            writer.println("  Success Rate: " + String.format("%.2f%%", (passedScenarios * 100.0) / Math.max(1, totalScenarios)));
            writer.println();

            writer.println("RETRY EVENTS:");
            for (RetryEvent event : retryEvents) {
                writer.println("  - " + event);
            }

            logger.info("Rerun log written to: {}", RERUN_LOG_FILE);
        } catch (IOException e) {
            logger.error("Failed to write rerun log", e);
        }
    }

    private void writeSummaryJson() {
        try {
            StringBuilder json = new StringBuilder();
            json.append("{\n");
            json.append("  \"timestamp\": \"").append(LocalDateTime.now().format(TIMESTAMP_FORMAT)).append("\",\n");
            json.append("  \"maxRetryAttempts\": ").append(maxRerunAttempts).append(",\n");
            json.append("  \"summary\": {\n");
            json.append("    \"totalScenarios\": ").append(totalScenarios).append(",\n");
            json.append("    \"passed\": ").append(passedScenarios).append(",\n");
            json.append("    \"failed\": ").append(failedScenarios).append(",\n");
            json.append("    \"retriedPassed\": ").append(retriedScenarios).append(",\n");
            json.append("    \"successRate\": ").append(String.format("\"%.2f%%\"", (passedScenarios * 100.0) / Math.max(1, totalScenarios))).append("\n");
            json.append("  },\n");
            json.append("  \"retryEvents\": [\n");

            for (int i = 0; i < retryEvents.size(); i++) {
                RetryEvent event = retryEvents.get(i);
                json.append("    {\"scenario\": \"").append(event.scenarioId).append("\", ");
                json.append("\"attempt\": ").append(event.attempt).append("}");
                if (i < retryEvents.size() - 1) json.append(",");
                json.append("\n");
            }

            json.append("  ]\n");
            json.append("}\n");

            Files.writeString(Paths.get(RERUN_SUMMARY_FILE), json.toString());
            logger.info("Summary JSON written to: {}", RERUN_SUMMARY_FILE);
        } catch (IOException e) {
            logger.error("Failed to write summary JSON", e);
        }
    }

    private void cleanup() {
        try {
            FrameworkCore.getInstance().cleanup();
            progressReporter.endSession();
            healthChecker.endSession();
            logger.info("Cleanup completed");
        } catch (Exception e) {
            logger.error("Error during cleanup", e);
        }
    }

    private static class ScenarioResult {
        final String scenarioId;
        final Status status;
        final int attempt;

        ScenarioResult(String scenarioId, Status status, int attempt) {
            this.scenarioId = scenarioId;
            this.status = status;
            this.attempt = attempt;
        }
    }

    private static class RetryEvent {
        final String scenarioId;
        final int attempt;
        final Throwable error;
        final LocalDateTime timestamp;

        RetryEvent(String scenarioId, int attempt, Throwable error) {
            this.scenarioId = scenarioId;
            this.attempt = attempt;
            this.error = error;
            this.timestamp = LocalDateTime.now();
        }

        @Override
        public String toString() {
            return String.format("[%s] %s (attempt %d) - %s",
                timestamp.format(TIMESTAMP_FORMAT), scenarioId, attempt,
                error != null ? error.getClass().getSimpleName() : "unknown");
        }
    }
}
