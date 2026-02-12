package com.hsbc.cmb.hk.dbb.automation.framework.web.screenshot.strategy;

import com.hsbc.cmb.hk.dbb.automation.framework.web.config.FrameworkConfig;
import com.hsbc.cmb.hk.dbb.automation.framework.web.config.FrameworkConfigManager;
import net.thucydides.model.util.EnvironmentVariables;
import net.thucydides.model.domain.TestResult;
import net.thucydides.model.domain.TestOutcome;
import net.thucydides.model.steps.ExecutedStepDescription;

public enum ScreenshotStrategy {
    FOR_FAILURES {
        public boolean shouldTakeScreenshotFor(TestOutcome testOutcome) {
            return testOutcome != null && (testOutcome.isFailure() || testOutcome.isError());
        }
        
        public boolean shouldTakeScreenshotFor(ExecutedStepDescription step) {
            return false; // 仅在失败时截图，不考虑步骤
        }
        
        public boolean shouldTakeScreenshotFor(TestResult result) {
            return result == TestResult.FAILURE || result == TestResult.ERROR;
        }
    },
    
    DISABLED {
        public boolean shouldTakeScreenshotFor(TestOutcome testOutcome) {
            return false; // 禁用截图
        }
        
        public boolean shouldTakeScreenshotFor(ExecutedStepDescription step) {
            return false; // 禁用截图
        }
        
        public boolean shouldTakeScreenshotFor(TestResult result) {
            return false; // 禁用截图
        }
    },
    
    AFTER_EACH_STEP {
        public boolean shouldTakeScreenshotFor(TestOutcome testOutcome) {
            return true; // 对所有测试结果都截图
        }
        
        public boolean shouldTakeScreenshotFor(ExecutedStepDescription step) {
            return step != null; // 对每个步骤都截图
        }
        
        public boolean shouldTakeScreenshotFor(TestResult result) {
            return true; // 对所有结果都截图
        }
    },
    
    BEFORE_AND_AFTER_EACH_STEP {
        public boolean shouldTakeScreenshotFor(TestOutcome testOutcome) {
            return true; // 对所有测试结果都截图
        }
        
        public boolean shouldTakeScreenshotFor(ExecutedStepDescription step) {
            return step != null; // 对每个步骤都截图
        }
        
        public boolean shouldTakeScreenshotFor(TestResult result) {
            return true; // 对所有结果都截图
        }
    },
    
    MANUAL {
        public boolean shouldTakeScreenshotFor(TestOutcome testOutcome) {
            return false; // 仅手动截图
        }
        
        public boolean shouldTakeScreenshotFor(ExecutedStepDescription step) {
            return false; // 仅手动截图
        }
        
        public boolean shouldTakeScreenshotFor(TestResult result) {
            return false; // 仅手动截图
        }
    };
    
    // 抽象方法定义
    public abstract boolean shouldTakeScreenshotFor(TestOutcome testOutcome);
    public abstract boolean shouldTakeScreenshotFor(ExecutedStepDescription step);
    public abstract boolean shouldTakeScreenshotFor(TestResult result);
    
    // 从配置中获取策略
    public static ScreenshotStrategy from(EnvironmentVariables environmentVariables) {
        if (environmentVariables == null) {
            return AFTER_EACH_STEP; // 默认策略
        }

        String screenshotStrategy = FrameworkConfigManager.getString(FrameworkConfig.SERENITY_SCREENSHOT_STRATEGY);
        try {
            return valueOf(screenshotStrategy.toUpperCase());
        } catch (IllegalArgumentException e) {
            return AFTER_EACH_STEP; // 默认策略
        }
    }
}