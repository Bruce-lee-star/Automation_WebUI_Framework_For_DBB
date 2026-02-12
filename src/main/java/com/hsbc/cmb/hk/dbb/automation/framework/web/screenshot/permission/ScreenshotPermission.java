package com.hsbc.cmb.hk.dbb.automation.framework.web.screenshot.permission;

import net.thucydides.model.environment.SystemEnvironmentVariables;
import net.thucydides.model.util.EnvironmentVariables;
import net.thucydides.model.domain.TestResult;
import net.thucydides.model.domain.TestOutcome;
import net.thucydides.model.steps.ExecutedStepDescription;
import com.hsbc.cmb.hk.dbb.automation.framework.web.screenshot.strategy.ScreenshotStrategy;

public class ScreenshotPermission {
    
    private final ScreenshotStrategy strategy;
    private final EnvironmentVariables environmentVariables;
    
    public ScreenshotPermission() {
        this.environmentVariables = SystemEnvironmentVariables.currentEnvironmentVariables();
        this.strategy = ScreenshotStrategy.from(environmentVariables);
    }
    
    public ScreenshotPermission(EnvironmentVariables environmentVariables) {
        this.environmentVariables = environmentVariables;
        this.strategy = ScreenshotStrategy.from(environmentVariables);
    }
    
    /**
     * 判断是否应该为特定的测试结果截图
     */
    public boolean screenshotForThis(TestOutcome testOutcome) {
        // 检查是否启用了截图功能
        if (!isScreenshotsEnabled()) {
            return false;
        }
        
        return strategy.shouldTakeScreenshotFor(testOutcome);
    }
    
    /**
     * 判断是否应该为特定的测试步骤截图
     */
    public boolean screenshotForThis(ExecutedStepDescription step) {
        // 检查是否启用了截图功能
        if (!isScreenshotsEnabled()) {
            return false;
        }
        return strategy.shouldTakeScreenshotFor(step);
    }
    
    /**
     * 判断是否应该为特定的测试结果类型截图
     */
    public boolean screenshotForThis(TestResult result) {
        // 检查是否启用了截图功能
        if (!isScreenshotsEnabled()) {
            return false;
        }
        
        return strategy.shouldTakeScreenshotFor(result);
    }
    
    /**
     * 检查是否启用了截图功能
     */
    private boolean isScreenshotsEnabled() {
        return environmentVariables.getPropertyAsBoolean("serenity.screenshots.enabled", true);
    }
    
    /**
     * 检查是否启用了关键操作词过滤
     */
    private boolean isKeyActionFilterEnabled() {
        return environmentVariables.getPropertyAsBoolean("serenity.screenshots.filter.key.actions", false);
    }
    
    public ScreenshotStrategy getCurrentStrategy() {
        return strategy;
    }
    
    /**
     * 检查是否应该在测试开始时截图
     */
    public boolean screenshotAtTestStart() {
        return environmentVariables.getPropertyAsBoolean("serenity.screenshots.at.test.start", true);
    }
    
    /**
     * 检查是否应该在测试结束时截图
     */
    public boolean screenshotAtTestEnd() {
        return environmentVariables.getPropertyAsBoolean("serenity.screenshots.at.test.end", true);
    }
    
    /**
     * 检查是否应该在验证开始时截图
     */
    public boolean screenshotAtVerificationStart() {
        return environmentVariables.getPropertyAsBoolean("serenity.screenshots.at.verification.start", false);
    }
    
    /**
     * 检查是否应该在验证结束时截图
     */
    public boolean screenshotAtVerificationEnd() {
        return environmentVariables.getPropertyAsBoolean("serenity.screenshots.at.verification.end", false);
    }
}