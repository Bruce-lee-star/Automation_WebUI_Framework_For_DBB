package com.hsbc.cmb.hk.dbb.automation.framework.web.screenshot.strategy;

import com.hsbc.cmb.hk.dbb.automation.framework.web.config.WebFrameworkConfig;
import com.hsbc.cmb.hk.dbb.automation.framework.web.config.FrameworkConfigManager;
import net.thucydides.model.util.EnvironmentVariables;
import net.thucydides.model.domain.TestResult;
import net.thucydides.model.domain.TestOutcome;
import net.thucydides.model.steps.ExecutedStepDescription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;
import java.util.Map;

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

    private static final Logger LOGGER = LoggerFactory.getLogger(ScreenshotStrategy.class);

    /**
     * 配置名 → 策略（大小写不敏感）。
     *
     * <p><b>为什么要别名表而不是 {@code valueOf}</b>：本键同时被 Serenity 生态使用，
     * 官方策略名（{@code AFTER_FAILING_STEP} 等）与框架枚举名并不一致。原实现直接 {@code valueOf}
     * 并在 {@link IllegalArgumentException} 时静默退回 {@code AFTER_EACH_STEP}，后果是
     * 「配置写 {@code AFTER_FAILING_STEP}（只失败时截图）→ 实际每步都截图」——
     * 实测一轮 E2E 因此产出 85 张截图，且因为兜底值与 {@code ConfigKeys} 的默认值恰好相同而无人察觉。</p>
     */
    private static final Map<String, ScreenshotStrategy> NAME_ALIASES = Map.ofEntries(
            Map.entry("FOR_FAILURES", FOR_FAILURES),
            Map.entry("AFTER_FAILING_STEP", FOR_FAILURES),
            Map.entry("AFTER_EACH_STEP", AFTER_EACH_STEP),
            Map.entry("FOR_EACH_STEP", AFTER_EACH_STEP),
            Map.entry("BEFORE_AND_AFTER_EACH_STEP", BEFORE_AND_AFTER_EACH_STEP),
            Map.entry("DISABLED", DISABLED),
            Map.entry("NONE", DISABLED),
            Map.entry("MANUAL", MANUAL));

    // 从配置中获取策略
    public static ScreenshotStrategy from(EnvironmentVariables environmentVariables) {
        if (environmentVariables == null) {
            return AFTER_EACH_STEP; // 默认策略
        }
        return resolve(FrameworkConfigManager.getString(WebFrameworkConfig.SERENITY_SCREENSHOT_STRATEGY));
    }

    /**
     * 解析配置值（可单测的纯函数）。
     *
     * <p>未配置 → 维持既有默认 {@link #AFTER_EACH_STEP}；<b>配置了但无法识别 → 退回
     * {@link #FOR_FAILURES} 并告警</b>（而不是退回"每步都截"）：无法识别时应向"少截"这一侧失败，
     * 且必须留痕，否则拼错的策略名会变成查不出来的截图风暴。</p>
     */
    static ScreenshotStrategy resolve(String configured) {
        if (configured == null || configured.trim().isEmpty()) {
            return AFTER_EACH_STEP; // 未配置：保持既有默认（见 ConfigKeys 的默认值）
        }
        ScreenshotStrategy strategy = NAME_ALIASES.get(configured.trim().toUpperCase(Locale.ROOT));
        if (strategy != null) {
            return strategy;
        }
        LOGGER.warn("[Screenshot] unknown strategy '{}' -- falling back to {} (supported values: {})",
                configured, FOR_FAILURES, NAME_ALIASES.keySet());
        return FOR_FAILURES;
    }
}