package com.hsbc.cmb.hk.dbb.automation.framework.web.config;

import com.hsbc.cmb.hk.dbb.automation.framework.web.annotations.AutoBrowser;
import com.hsbc.cmb.hk.dbb.automation.framework.web.utils.LoggingConfigUtil;
import net.thucydides.core.steps.BaseStepListener;
import net.thucydides.core.steps.StepEventBus;
import net.thucydides.model.domain.TestOutcome;
import net.thucydides.model.domain.TestTag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Set;

/**
 * AutoBrowser Processor - 框架层自动处理 @AutoBrowser 注解
 * 
 * 工作原理：
 * 1. 在 PlaywrightManager.getBrowserType() 被调用时自动触发
 * 2. 通过堆栈跟踪找到 Glue 类
 * 3. 检查是否有 @AutoBrowser 注解
 * 4. 从 Serenity 上下文获取当前 Scenario 的标签
 * 5. 自动调用 BrowserOverrideManager.setScenarioTags()
 * 
 * 优势：
 * - 零配置：测试代码无需任何修改
 * - 自动化：框架自动处理浏览器切换
 * - 透明化：对测试代码完全透明
 * 
 * @author Automation Framework
 * @version 1.0
 */
public class AutoBrowserProcessor {
    
    private static final Logger logger = LoggerFactory.getLogger(AutoBrowserProcessor.class);
    
    // 缓存已检查过的类，避免重复扫描
    private static final ThreadLocal<Boolean> processedForCurrentScenario = new ThreadLocal<>();
    
    /**
     * 处理 @AutoBrowser 注解
     *
     * 在 PlaywrightManager.getBrowserType() 中调用此方法
     * 自动检测并设置浏览器覆盖配置
     */
    public static void processAutoBrowserAnnotation() {
        // 避免在同一个 Scenario 中重复处理
        if (Boolean.TRUE.equals(processedForCurrentScenario.get())) {
            LoggingConfigUtil.logDebugIfVerbose(logger, "Already processed for current scenario, skipping");
            return;
        }

        LoggingConfigUtil.logInfoIfVerbose(logger, "Processing @AutoBrowser annotation...");

        try {
            // 0. 检查 StepEventBus 是否已准备好
            if (!isStepEventBusReady()) {
                LoggingConfigUtil.logDebugIfVerbose(logger, "StepEventBus not ready yet, skipping @AutoBrowser processing");
                return;
            }

            // 1. 从堆栈跟踪找到 Glue 类
            Class<?> glueClass = findGlueClass();

            if (glueClass == null) {
                logger.warn("No class with @AutoBrowser found in call stack");
                LoggingConfigUtil.logDebugIfVerbose(logger, "Call stack trace for debugging:");
                StackTraceElement[] stackTrace = Thread.currentThread().getStackTrace();
                for (int i = 0; i < Math.min(15, stackTrace.length); i++) {
                    LoggingConfigUtil.logDebugIfVerbose(logger, "  [{}] {}", i, stackTrace[i].getClassName());
                }
                return;
            }

            LoggingConfigUtil.logDebugIfVerbose(logger, "Found class '{}' with @AutoBrowser", glueClass.getName());

            // 2. 检查 @AutoBrowser 注解
            AutoBrowser autoBrowser = glueClass.getAnnotation(AutoBrowser.class);

            if (autoBrowser == null || !autoBrowser.enabled()) {
                logger.warn("@AutoBrowser annotation not enabled on class '{}'",
                    glueClass.getSimpleName());
                return;
            }

            LoggingConfigUtil.logDebugIfVerbose(logger, "@AutoBrowser annotation is enabled (verbose={})",
                autoBrowser.verbose());

            // 3. 从 Serenity 上下文获取 Scenario 标签
            String[] tags = getTagsFromSerenityContext();

            if (tags == null || tags.length == 0) {
                LoggingConfigUtil.logDebugIfVerbose(logger, "No tags found in Serenity context - this is normal during early test initialization");
                return;
            }

            LoggingConfigUtil.logDebugIfVerbose(logger, "Found {} tags: {}", tags.length, Arrays.toString(tags));

            // 4. 设置 Scenario 标签
            BrowserOverrideManager.setScenarioTags(tags);

            String effectiveBrowser = BrowserOverrideManager.getEffectiveBrowserType();
            LoggingConfigUtil.logInfoIfVerbose(logger, "Effective browser type set to: {}", effectiveBrowser);

            // 标记为已处理
            processedForCurrentScenario.set(true);

        } catch (Exception e) {
            logger.error("ERROR processing @AutoBrowser annotation: {}", e.getMessage(), e);
        }
    }

    /**
     * 检查 StepEventBus 是否已准备好，如果未准备好则自动注册监听器
     *
     * @return true 如果 StepEventBus 已初始化且 BaseStepListener 已注册
     */
    private static boolean isStepEventBusReady() {
        try {
            StepEventBus eventBus = StepEventBus.getEventBus();
            if (eventBus == null) {
                return false;
            }

            // 使用反射访问 currentBaseStepListener() 方法，避免触发 ERROR 日志
            try {
                Method method = StepEventBus.class.getDeclaredMethod("currentBaseStepListener");
                method.setAccessible(true);
                Object listener = method.invoke(eventBus);

                if (listener == null) {
                    // 监听器未注册，自动注册
                    LoggingConfigUtil.logInfoIfVerbose(logger, "BaseStepListener not registered, auto-registering...");
                    return registerBaseStepListener(eventBus);
                }

                return true;
            } catch (Exception e) {
                LoggingConfigUtil.logTraceIfVerbose(logger, "Could not check BaseStepListener status: {}", e.getMessage());
                return false;
            }
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 注册 BaseStepListener
     *
     * @param eventBus StepEventBus 实例
     * @return true 如果注册成功
     */
    private static boolean registerBaseStepListener(StepEventBus eventBus) {
        try {
            // 创建输出目录（用于存储测试结果）
            File outputDirectory = new File("target/site/serenity");
            if (!outputDirectory.exists()) {
                outputDirectory.mkdirs();
            }

            // 创建 BaseStepListener（需要一个输出目录）
            BaseStepListener listener = new BaseStepListener(outputDirectory);

            // 注册到 StepEventBus
            eventBus.registerListener(listener);

            LoggingConfigUtil.logInfoIfVerbose(logger, "BaseStepListener registered successfully");
            return true;

        } catch (Exception e) {
            logger.warn("Failed to register BaseStepListener: {}", e.getMessage());
            return false;
        }
    }
    
    /**
     * 清除处理状态（在 Scenario 结束时调用）
     */
    public static void clearProcessingState() {
        processedForCurrentScenario.remove();
        
        // 同时清理 BrowserOverrideManager
        if (BrowserOverrideManager.hasOverride()) {
            BrowserOverrideManager.clearOverrideBrowser();
        }
        BrowserOverrideManager.clearScenarioTags();
    }
    
    /**
     * 从当前调用堆栈中找到带有 @AutoBrowser 注解的类
     * 
     * 只要类有 @AutoBrowser 注解就会被识别，不限包名
     * 
     * @return 带 @AutoBrowser 注解的类，如果没找到则返回 null
     */
    private static Class<?> findGlueClass() {
        StackTraceElement[] stackTrace = Thread.currentThread().getStackTrace();
        
        LoggingConfigUtil.logDebugIfVerbose(logger, "Scanning {} stack trace elements for @AutoBrowser annotation", stackTrace.length);
        
        for (int i = 0; i < stackTrace.length; i++) {
            StackTraceElement element = stackTrace[i];
            String className = element.getClassName();
            
            LoggingConfigUtil.logTraceIfVerbose(logger, "[{}] Checking class: {}", i, className);
            
            // 跳过框架自身的类
            if (className.startsWith("com.hsbc.cmb.hk.dbb.automation.framework.")) {
                continue;
            }
            
            // 跳过 JDK 和第三方库的类
            if (className.startsWith("java.") || 
                className.startsWith("sun.") || 
                className.startsWith("org.junit.") ||
                className.startsWith("io.cucumber.") ||
                className.startsWith("net.serenitybdd.") ||
                className.startsWith("net.thucydides.")) {
                continue;
            }
            
            try {
                Class<?> clazz = Class.forName(className);
                
                // 只要类有 @AutoBrowser 注解就处理
                if (clazz.isAnnotationPresent(AutoBrowser.class)) {
                    AutoBrowser autoBrowser = clazz.getAnnotation(AutoBrowser.class);
                    LoggingConfigUtil.logDebugIfVerbose(logger, "[{}] Found @AutoBrowser annotation on class: {} (enabled={})", 
                        i, className, autoBrowser.enabled());
                    
                    if (autoBrowser.enabled()) {
                        return clazz;
                    }
                }
            } catch (ClassNotFoundException e) {
                LoggingConfigUtil.logTraceIfVerbose(logger, "[{}] Could not load class: {}", i, className);
            } catch (NoClassDefFoundError e) {
                LoggingConfigUtil.logTraceIfVerbose(logger, "[{}] Could not load class (dependency issue): {}", i, className);
            } catch (Throwable e) {
                LoggingConfigUtil.logTraceIfVerbose(logger, "[{}] Error loading class {}: {}", i, className, e.getMessage());
            }
        }
        
        return null;
    }
    
    /**
     * 从 Serenity 上下文获取当前 Scenario 的标签
     *
     * @return 标签数组
     */
    private static String[] getTagsFromSerenityContext() {
        try {
            LoggingConfigUtil.logDebugIfVerbose(logger, "Attempting to get tags from StepEventBus...");

            // 从 StepEventBus 获取当前 TestOutcome
            StepEventBus eventBus = StepEventBus.getEventBus();
            if (eventBus == null) {
                logger.debug("StepEventBus.getEventBus() returned null - tests may not be running with Serenity runners");
                return new String[0];
            }
            LoggingConfigUtil.logTraceIfVerbose(logger, "StepEventBus instance: {}", eventBus.getClass().getName());

            // 使用反射安全地获取 BaseStepListener
            Method method = StepEventBus.class.getDeclaredMethod("currentBaseStepListener");
            method.setAccessible(true);
            Object listener = method.invoke(eventBus);

            if (listener == null) {
                logger.debug("BaseStepListener not registered yet");
                return new String[0];
            }

            LoggingConfigUtil.logTraceIfVerbose(logger, "BaseStepListener instance: {}", listener.getClass().getName());

            // 获取 TestOutcome
            Method getTestOutcomeMethod = listener.getClass().getMethod("getCurrentTestOutcome");
            TestOutcome testOutcome = (TestOutcome) getTestOutcomeMethod.invoke(listener);

            if (testOutcome == null) {
                logger.debug("getCurrentTestOutcome() returned null");
                return new String[0];
            }
            LoggingConfigUtil.logDebugIfVerbose(logger, "TestOutcome found: {}", testOutcome.getName());

            Set<TestTag> testTags = testOutcome.getTags();
            if (testTags == null) {
                logger.debug("testOutcome.getTags() returned null");
                return new String[0];
            }

            LoggingConfigUtil.logDebugIfVerbose(logger, "Found {} tags in TestOutcome", testTags.size());

            if (!testTags.isEmpty()) {
                String[] tags = testTags.stream()
                    .map(TestTag::getName)
                    .toArray(String[]::new);

                LoggingConfigUtil.logDebugIfVerbose(logger, "Converted tags: {}", Arrays.toString(tags));
                return tags;
            }

        } catch (Exception e) {
            logger.debug("Exception getting tags from Serenity context: {} - this is normal during early test initialization", e.getMessage());
        }

        return new String[0];
    }
    
    /**
     * 检查当前是否有 @AutoBrowser 注解生效
     * 
     * @return true 如果有注解生效
     */
    public static boolean hasAutoBrowserActive() {
        return Boolean.TRUE.equals(processedForCurrentScenario.get());
    }
}
