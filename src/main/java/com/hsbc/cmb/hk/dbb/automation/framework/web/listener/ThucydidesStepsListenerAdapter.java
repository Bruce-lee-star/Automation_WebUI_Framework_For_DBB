package com.hsbc.cmb.hk.dbb.automation.framework.web.listener;

import com.hsbc.cmb.hk.dbb.automation.framework.web.utils.LoggingConfigUtil;
import net.thucydides.model.steps.StepListener;
import net.thucydides.model.steps.StepFailure;
import net.thucydides.model.steps.ExecutedStepDescription;
import net.thucydides.model.domain.DataTable;
import net.thucydides.model.domain.Story;
import net.thucydides.model.domain.TestOutcome;
import net.thucydides.model.domain.TestResult;
import net.thucydides.model.screenshots.ScreenshotAndHtmlSource;
import net.thucydides.core.steps.StepEventBus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.hsbc.cmb.hk.dbb.automation.framework.web.exceptions.InitializationException;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Thucydides Steps 监听器适配器
 * 专门适配 net.thucydides.core.steps.listener 包的接口
 * <p>
 * 这个适配器提供了：
 * 1. 标准的 Serenity StepListener 实现
 * 2. Playwright 集成的增强功能
 * 3. 多监听器管理和协调
 * 4. 线程安全的事件处理
 * 5. 优化的日志输出（减少重复实例的日志噪音）
 */
public class ThucydidesStepsListenerAdapter implements StepListener {

    private static final Logger logger = LoggerFactory.getLogger(ThucydidesStepsListenerAdapter.class);

    // 单例实例
    private static volatile ThucydidesStepsListenerAdapter instance;
    private static final Object lock = new Object();
    private static volatile boolean instanceCreated = false;

    // 单例的 PlaywrightListener
    private static volatile PlaywrightListener singletonPlaywrightListener;

    // 管理多个子监听器 - 静态变量，所有实例共享
    private static final List<StepListener> delegateListeners = new CopyOnWriteArrayList<>();

    // 用于防止重复的testSuite日志
    private static volatile String lastTestSuiteName = null;

    // 实例ID,用于追踪多个实例
    private final long instanceId;
    private static final AtomicLong instanceCounter = new AtomicLong(0);

    public ThucydidesStepsListenerAdapter() {
        this.instanceId = instanceCounter.incrementAndGet();

        // 使用双重检查锁定来确保线程安全的单例模式
        synchronized (lock) {
            if (!instanceCreated) {
                // 首次创建：记录INFO日志
                LoggingConfigUtil.logInfoIfVerbose(logger, "🏗️ Created ThucydidesStepsListenerAdapter instance #{} (initializing singleton)", instanceId);
                LoggingConfigUtil.logDebugIfVerbose(logger, "🔧 Initializing Thucydides Steps Listener Adapter (first time)");
                instance = this;
                instanceCreated = true;

                // 只在首次初始化时创建 PlaywrightListener
                try {
                    singletonPlaywrightListener = new PlaywrightListener();
                    LoggingConfigUtil.logDebugIfVerbose(logger, "✅ Created singleton PlaywrightListener");
                    addDelegateListener(singletonPlaywrightListener);
                } catch (Exception e) {
                    logger.error("❌ Failed to add PlaywrightListener as delegate listener", e);
                }
            } else {
                // 后续实例只是"轻量级"包装器，不创建新的监听器
                // 只记录DEBUG级别日志，减少日志噪音
                LoggingConfigUtil.logDebugIfVerbose(
                    logger, "🔄 Reusing existing singleton instance #{} (instance #{})",
                    instance != null ? instance.instanceId : "UNKNOWN", instanceId);
            }
        }
    }

    /**
     * 获取单例实例
     * 注意：如果 SPI 机制已经创建了实例，则返回该实例
     */
    public static ThucydidesStepsListenerAdapter getInstance() {
        if (instance == null) {
            synchronized (lock) {
                if (instance == null) {
                    instance = new ThucydidesStepsListenerAdapter();
                    instanceCreated = true;
                }
            }
        }
        return instance;
    }

/**
     * 添加委托监听器
     */
    public void addDelegateListener(StepListener listener) {
        if (listener != null && !delegateListeners.contains(listener)) {
            delegateListeners.add(listener);
            
            LoggingConfigUtil.logInfoIfVerbose(
                logger, "📝 Added delegate listener: {} (total: {})", listener.getClass().getSimpleName(), delegateListeners.size());
        }
    }

    /**
     * 移除委托监听器
     */
    public void removeDelegateListener(StepListener listener) {
        if (delegateListeners.remove(listener)) {
            LoggingConfigUtil.logInfoIfVerbose(logger, "🗑️ Removed delegate listener: {} (total: {})", listener.getClass().getSimpleName(), delegateListeners.size());
        }
    }

    /**
     * 注册到 Serenity StepEventBus
     */
    public void registerWithStepEventBus() {
        try {
            StepEventBus.getEventBus().registerListener(this);
            LoggingConfigUtil.logInfoIfVerbose(logger, "✅ Thucydides Steps Listener Adapter registered with StepEventBus");
        } catch (Exception e) {
            logger.error("❌ Failed to register with StepEventBus", e);
            throw new InitializationException("Failed to register listener adapter", e);
        }
    }

    /**
     * 从 StepEventBus 注销
     */
    public void unregisterFromStepEventBus() {
        try {
            StepEventBus.getEventBus().dropListener(this);
            LoggingConfigUtil.logInfoIfVerbose(logger, "✅ Thucydides Steps Listener Adapter unregistered from StepEventBus");
        } catch (Exception e) {
            logger.error("❌ Failed to unregister from StepEventBus", e);
        }
    }

    @Override
    public void testStarted(String testName) {
        // 减少日志噪音：只在首次实例记录
        if (logger.isDebugEnabled() && instanceId == 1) {
            LoggingConfigUtil.logDebugIfVerbose(logger, "🚀 Test started: {}", testName);
        }

        for (StepListener listener : delegateListeners) {
            try {
                listener.testStarted(testName);
            } catch (Exception e) {
                logger.warn("Error in delegate listener testStarted: {}", listener.getClass().getSimpleName(), e);
            }
        }
    }

    // 内部实现方法，不直接作为接口方法
    private void testFinishedInternal() {
        LoggingConfigUtil.logDebugIfVerbose(logger, "🏁 Thucydides Adapter: Test finished");

        for (StepListener listener : delegateListeners) {
            try {
                // 调用有参数的testFinished方法
                listener.testFinished(null);
            } catch (Exception e) {
                logger.warn("Error in delegate listener testFinished: {}", listener.getClass().getSimpleName(), e);
            }
        }
    }

    @Override
    public void testSkipped() {
        LoggingConfigUtil.logDebugIfVerbose(logger, "⏭️ Thucydides Adapter: Test skipped");

        for (StepListener listener : delegateListeners) {
            try {
                listener.testSkipped();
            } catch (Exception e) {
                logger.warn("Error in delegate listener testSkipped: {}", listener.getClass().getSimpleName(), e);
            }
        }
    }

    @Override
    public void stepStarted(ExecutedStepDescription step) {
        if (step == null) return;

        // 使用LoggingConfigUtil控制日志输出
        LoggingConfigUtil.logDebugIfVerbose(logger, "📍 Step started: {} (delegates: {})", step.getTitle(), delegateListeners.size());

        for (StepListener listener : delegateListeners) {
            try {
                listener.stepStarted(step);
            } catch (Exception e) {
                logger.warn("Error in delegate listener stepStarted: {}", listener.getClass().getSimpleName(), e);
            }
        }
    }

    @Override
    public void stepFinished() {
        // 使用LoggingConfigUtil控制日志输出
        LoggingConfigUtil.logDebugIfVerbose(logger, "✅ Step finished (delegates: {})", delegateListeners.size());

        // 无参数版本不处理截图，Serenity 会调用带参数的版本
        for (StepListener listener : delegateListeners) {
            try {
                listener.stepFinished();
            } catch (Exception e) {
                logger.warn("Error in delegate listener stepFinished: {}", listener.getClass().getSimpleName(), e);
            }
        }
    }

    @Override
    public void stepFailed(StepFailure failure) {
        if (failure == null) return;

        LoggingConfigUtil.logDebugIfVerbose(logger, "💥 Thucydides Adapter: Step failed - {}", failure.getException().getMessage());

        for (StepListener listener : delegateListeners) {
            try {
                listener.stepFailed(failure);
            } catch (Exception e) {
                logger.warn("Error in delegate listener stepFailed: {}", listener.getClass().getSimpleName(), e);
            }
        }
    }

    @Override
    public void lastStepFailed(StepFailure failure) {
        LoggingConfigUtil.logDebugIfVerbose(logger, "💥 Thucydides Adapter: Last step failed - {}",
                failure != null ? failure.getException().getMessage() : "Unknown");

        for (StepListener listener : delegateListeners) {
            try {
                listener.lastStepFailed(failure);
            } catch (Exception e) {
                logger.warn("Error in delegate listener lastStepFailed: {}", listener.getClass().getSimpleName(), e);
            }
        }
    }

    @Override
    public void stepIgnored() {
        LoggingConfigUtil.logDebugIfVerbose(logger, "⏭️ Thucydides Adapter: Step ignored");

        for (StepListener listener : delegateListeners) {
            try {
                listener.stepIgnored();
            } catch (Exception e) {
                logger.warn("Error in delegate listener stepIgnored: {}", listener.getClass().getSimpleName(), e);
            }
        }
    }

    /**
     * 获取委托监听器数量
     */
    public int getDelegateListenerCount() {
        return delegateListeners.size();
    }

    /**
     * 清理所有委托监听器
     */
    public void clearDelegateListeners() {
        delegateListeners.clear();
        LoggingConfigUtil.logInfoIfVerbose(logger, "🧹 Cleared all delegate listeners");
    }

    /**
     * 获取适配器状态信息
     */
    public String getAdapterStatus() {
        StringBuilder status = new StringBuilder();
        status.append("📊 Thucydides Steps Listener Adapter Status:\n");
        status.append(String.format("Delegate Listeners: %d\n", delegateListeners.size()));
        status.append("Registered Listeners:\n");

        for (StepListener listener : delegateListeners) {
            status.append(String.format("  - %s\n", listener.getClass().getSimpleName()));
        }

        return status.toString();
    }

    @Override
    public void testSuiteStarted(Class<?> testClass) {
        LoggingConfigUtil.logDebugIfVerbose(logger, "Test suite started for class: {}", testClass);
        for (StepListener listener : delegateListeners) {
            try {
                listener.testSuiteStarted(testClass);
            } catch (Exception e) {
                logger.warn("Error in delegate listener testSuiteStarted: {}", listener.getClass().getSimpleName(), e);
            }
        }
    }

@Override
    public void testSuiteStarted(Story story) {
        String storyName = story != null ? story.getName() : "unknown";
        
        // 只记录第一次出现的测试套件，避免重复日志
        if (!storyName.equals(lastTestSuiteName)) {
            LoggingConfigUtil.logDebugIfVerbose(
                logger, "Test suite started for story: {}", storyName);
            lastTestSuiteName = storyName;
        }
        for (StepListener listener : delegateListeners) {
            try {
                listener.testSuiteStarted(story);
            } catch (Exception e) {
                logger.warn("Error in delegate listener testSuiteStarted: {}", listener.getClass().getSimpleName(), e);
            }
        }
    }

    @Override
    public void testSuiteFinished() {
        LoggingConfigUtil.logDebugIfVerbose(logger, "Test suite finished");
        for (StepListener listener : delegateListeners) {
            try {
                listener.testSuiteFinished();
            } catch (Exception e) {
                logger.warn("Error in delegate listener testSuiteFinished: {}", listener.getClass().getSimpleName(), e);
            }
        }
    }

    @Override
    public void testStarted(String testName, String testMethod) {
        LoggingConfigUtil.logDebugIfVerbose(logger, "Test started: {} with method: {}", testName, testMethod);
        for (StepListener listener : delegateListeners) {
            try {
                listener.testStarted(testName, testMethod);
            } catch (Exception e) {
                logger.warn("Error in delegate listener testStarted: {}", listener.getClass().getSimpleName(), e);
            }
        }
    }

    @Override
    public void testStarted(String testName, String testMethod, ZonedDateTime startTime) {
        LoggingConfigUtil.logDebugIfVerbose(logger, "Test started: {} with method: {} at: {}", testName, testMethod, startTime);
        for (StepListener listener : delegateListeners) {
            try {
                listener.testStarted(testName, testMethod, startTime);
            } catch (Exception e) {
                logger.warn("Error in delegate listener testStarted: {}", listener.getClass().getSimpleName(), e);
            }
        }
    }

    @Override
    public void testFinished(TestOutcome result) {
        // 防御性检查：确保result不为null
        if (result == null) {
            logger.warn("TestOutcome is null in testFinished, skipping delegate notification");
            return;
        }

        // 只在首次实例记录INFO日志
        if (instanceId == 1) {
            LoggingConfigUtil.logDebugIfVerbose(logger, "Test finished with result: {}", result);
        }
        for (StepListener listener : delegateListeners) {
            try {
                listener.testFinished(result);
            } catch (Exception e) {
                logger.warn("Error in delegate listener testFinished: {}", listener.getClass().getSimpleName(), e);
            }
        }
    }

    @Override
    public void testFinished(TestOutcome result, boolean isInDataDrivenTest, ZonedDateTime finishTime) {
        // 防御性检查：确保result和关键属性不为null，避免Serenity报告生成时的NPE
        if (result == null) {
            logger.warn("TestOutcome is null in testFinished, skipping delegate notification");
            return;
        }

        // 只在首次实例记录INFO日志
        if (instanceId == 1) {
            LoggingConfigUtil.logDebugIfVerbose(logger, "Test finished with result: {}, isDataDriven: {}, finishTime: {}", result, isInDataDrivenTest, finishTime);
        }

        for (StepListener listener : delegateListeners) {
            try {
                listener.testFinished(result, isInDataDrivenTest, finishTime);
            } catch (Exception e) {
                logger.warn("Error in delegate listener testFinished: {}", listener.getClass().getSimpleName(), e);
            }
        }
    }

    @Override
    public void testRetried() {
        LoggingConfigUtil.logDebugIfVerbose(logger, "Test retried");
        for (StepListener listener : delegateListeners) {
            try {
                listener.testRetried();
            } catch (Exception e) {
                logger.warn("Error in delegate listener testRetried: {}", listener.getClass().getSimpleName(), e);
            }
        }
    }

    @Override
    public void skippedStepStarted(ExecutedStepDescription step) {
        LoggingConfigUtil.logDebugIfVerbose(logger, "Skipped step started: {}", step);
        for (StepListener listener : delegateListeners) {
            try {
                listener.skippedStepStarted(step);
            } catch (Exception e) {
                logger.warn("Error in delegate listener skippedStepStarted: {}", listener.getClass().getSimpleName(), e);
            }
        }
    }

    @Override
    public void stepFailed(StepFailure failure, List<ScreenshotAndHtmlSource> screenshots, boolean takeScreenshotOnFailure, ZonedDateTime timestamp) {
        LoggingConfigUtil.logDebugIfVerbose(logger, "Step failed with {} screenshots", screenshots != null ? screenshots.size() : 0);
        for (StepListener listener : delegateListeners) {
            try {
                listener.stepFailed(failure, screenshots, takeScreenshotOnFailure, timestamp);
            } catch (Exception e) {
                logger.warn("Error in delegate listener stepFailed: {}", listener.getClass().getSimpleName(), e);
            }
        }
    }

    @Override
    public void stepPending() {
        LoggingConfigUtil.logDebugIfVerbose(logger, "Step pending");
        for (StepListener listener : delegateListeners) {
            try {
                listener.stepPending();
            } catch (Exception e) {
                logger.warn("Error in delegate listener stepPending: {}", listener.getClass().getSimpleName(), e);
            }
        }
    }

    @Override
    public void stepPending(String description) {
        LoggingConfigUtil.logDebugIfVerbose(logger, "Step pending: {}", description);
        for (StepListener listener : delegateListeners) {
            try {
                listener.stepPending(description);
            } catch (Exception e) {
                logger.warn("Error in delegate listener stepPending: {}", listener.getClass().getSimpleName(), e);
            }
        }
    }

    @Override
    public void stepFinished(List<ScreenshotAndHtmlSource> screenshots) {
        // 减少日志噪音：只在首次实例或有截图时记录
        if (logger.isDebugEnabled() && (instanceId == 1 || (screenshots != null && screenshots.size() > 0))) {
            LoggingConfigUtil.logDebugIfVerbose(logger, "Step finished with {} screenshots", screenshots != null ? screenshots.size() : 0);
        }
        for (StepListener listener : delegateListeners) {
            try {
                listener.stepFinished(screenshots);
            } catch (Exception e) {
                logger.warn("Error in delegate listener stepFinished: {}", listener.getClass().getSimpleName(), e);
            }
        }
    }

    @Override
    public void stepFinished(List<ScreenshotAndHtmlSource> screenshots, ZonedDateTime timestamp) {
        // 减少日志噪音：只在首次实例或有截图时记录
        if (logger.isDebugEnabled() && (instanceId == 1 || (screenshots != null && screenshots.size() > 0))) {
            LoggingConfigUtil.logDebugIfVerbose(logger, "Step finished with {} screenshots at {}",
                    screenshots != null ? screenshots.size() : 0, timestamp);
        }
        for (StepListener listener : delegateListeners) {
            try {
                listener.stepFinished(screenshots, timestamp);
            } catch (Exception e) {
                logger.warn("Error in delegate listener stepFinished: {}", listener.getClass().getSimpleName(), e);
            }
        }
    }

    @Override
    public void testFailed(TestOutcome result, Throwable throwable) {
        logger.error("Test failed: {}", result, throwable);
        for (StepListener listener : delegateListeners) {
            try {
                listener.testFailed(result, throwable);
            } catch (Exception e) {
                logger.warn("Error in delegate listener testFailed: {}", listener.getClass().getSimpleName(), e);
            }
        }
    }

    @Override
    public void testIgnored() {
        LoggingConfigUtil.logDebugIfVerbose(logger, "Test ignored");
        for (StepListener listener : delegateListeners) {
            try {
                listener.testIgnored();
            } catch (Exception e) {
                logger.warn("Error in delegate listener testIgnored: {}", listener.getClass().getSimpleName(), e);
            }
        }
    }

    @Override
    public void testPending() {
        logger.debug("Test pending");
        for (StepListener listener : delegateListeners) {
            try {
                listener.testPending();
            } catch (Exception e) {
                logger.warn("Error in delegate listener testPending: {}", listener.getClass().getSimpleName(), e);
            }
        }
    }

    @Override
    public void testIsManual() {
        logger.debug("Test is manual");
        for (StepListener listener : delegateListeners) {
            try {
                listener.testIsManual();
            } catch (Exception e) {
                logger.warn("Error in delegate listener testIsManual: {}", listener.getClass().getSimpleName(), e);
            }
        }
    }

    @Override
    public void notifyScreenChange() {
        logger.debug("Screen change notified");
        for (StepListener listener : delegateListeners) {
            try {
                listener.notifyScreenChange();
            } catch (Exception e) {
                logger.warn("Error in delegate listener notifyScreenChange: {}", listener.getClass().getSimpleName(), e);
            }
        }
    }

    @Override
    public void useExamplesFrom(DataTable dataTable) {
        logger.debug("Using examples from data table with {} rows", dataTable.getRows().size());
        for (StepListener listener : delegateListeners) {
            try {
                listener.useExamplesFrom(dataTable);
            } catch (Exception e) {
                logger.warn("Error in delegate listener useExamplesFrom: {}", listener.getClass().getSimpleName(), e);
            }
        }
    }

    @Override
    public void addNewExamplesFrom(DataTable dataTable) {
        logger.debug("Adding new examples from data table with {} rows", dataTable.getRows().size());
        for (StepListener listener : delegateListeners) {
            try {
                listener.addNewExamplesFrom(dataTable);
            } catch (Exception e) {
                logger.warn("Error in delegate listener addNewExamplesFrom: {}", listener.getClass().getSimpleName(), e);
            }
        }
    }

    @Override
    public void exampleStarted(Map<String, String> data) {
        logger.debug("Example started with data: {}", data);
        for (StepListener listener : delegateListeners) {
            try {
                listener.exampleStarted(data);
            } catch (Exception e) {
                logger.warn("Error in delegate listener exampleStarted: {}", listener.getClass().getSimpleName(), e);
            }
        }
    }

    @Override
    public void exampleFinished() {
        logger.debug("Example finished");
        for (StepListener listener : delegateListeners) {
            try {
                listener.exampleFinished();
            } catch (Exception e) {
                logger.warn("Error in delegate listener exampleFinished: {}", listener.getClass().getSimpleName(), e);
            }
        }
    }

    @Override
    public void assumptionViolated(String message) {
        logger.debug("Assumption violated: {}", message);
        for (StepListener listener : delegateListeners) {
            try {
                listener.assumptionViolated(message);
            } catch (Exception e) {
                logger.warn("Error in delegate listener assumptionViolated: {}", listener.getClass().getSimpleName(), e);
            }
        }
    }

    @Override
    public void testRunFinished() {
        // 只在首次实例记录INFO日志
        if (instanceId == 1) {
            logger.info("Test run finished");
            logger.info("Current delegate listeners count: {}", delegateListeners.size());
        }
        for (StepListener listener : delegateListeners) {
            try {
                if (instanceId == 1) {
                    logger.debug("Calling testRunFinished on listener: {}", listener.getClass().getSimpleName());
                }
                listener.testRunFinished();
            } catch (Exception e) {
                logger.warn("Error in delegate listener testRunFinished: {}", listener.getClass().getSimpleName(), e);
            }
        }
    }

    @Override
    public void takeScreenshots(List<ScreenshotAndHtmlSource> screenshots) {
        logger.debug("takeScreenshots called with {} screenshots", screenshots != null ? screenshots.size() : 0);
        for (StepListener listener : delegateListeners) {
            try {
                listener.takeScreenshots(screenshots);
            } catch (Exception e) {
                logger.warn("Error in delegate listener takeScreenshots: {}", listener.getClass().getSimpleName(), e);
            }
        }
    }

    @Override
    public void takeScreenshots(TestResult result, List<ScreenshotAndHtmlSource> screenshots) {
        logger.debug("takeScreenshots called with result {} and {} screenshots", result, screenshots != null ? screenshots.size() : 0);
        for (StepListener listener : delegateListeners) {
            try {
                listener.takeScreenshots(result, screenshots);
            } catch (Exception e) {
                logger.warn("Error in delegate listener takeScreenshots: {}", listener.getClass().getSimpleName(), e);
            }
        }
    }
}