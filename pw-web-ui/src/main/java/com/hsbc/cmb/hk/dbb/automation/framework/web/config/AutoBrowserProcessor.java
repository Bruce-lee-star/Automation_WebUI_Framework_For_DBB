package com.hsbc.cmb.hk.dbb.automation.framework.web.config;


import com.hsbc.cmb.hk.dbb.automation.framework.web.annotations.AutoBrowser;
import com.hsbc.cmb.hk.dbb.automation.framework.common.config.VerboseLogging;
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
import com.hsbc.cmb.hk.dbb.automation.framework.core.context.ContextKey;
import com.hsbc.cmb.hk.dbb.automation.framework.core.context.TestContextHolder;

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
    
    // 缓存已检查过的类，避免重复扫描（ T3-1 收拢：由 static ThreadLocal 迁入 TestContext，per-thread 等价）
    private static final ContextKey<Boolean> PROCESSED_KEY = ContextKey.of("autobrowser.processed", Boolean.class);
    // 缓存已解析的 glue 类名，避免每次 getPage() 都扫描 123 帧调用栈（日志噪声修复）
    private static final ContextKey<String> GLUE_NAME_KEY = ContextKey.of("autobrowser.glueName", String.class);
    // 标记本场景是否已打印过 "Processing" 头部日志，避免重复刷屏（日志噪声修复）
    private static final ContextKey<Boolean> LOGGED_KEY = ContextKey.of("autobrowser.logged", Boolean.class);
    
    /**
     * 处理 @AutoBrowser 注解
     *
     * 在 PlaywrightManager.getBrowserType() 中调用此方法
     * 自动检测并设置浏览器覆盖配置
     */
    public static void processAutoBrowserAnnotation() {
        // 避免在同一个 Scenario 中重复处理
        if (Boolean.TRUE.equals(TestContextHolder.get().get(PROCESSED_KEY))) {
            return;
        }

        // 仅首次打印头部日志，避免每次 getPage() 都刷 "Processing @AutoBrowser annotation..."（日志噪声修复）
        if (!Boolean.TRUE.equals(TestContextHolder.get().get(LOGGED_KEY))) {
            VerboseLogging.logInfoIfVerbose(logger, "Processing @AutoBrowser annotation...");
            TestContextHolder.get().set(LOGGED_KEY, true);
        }

        try {
            // 0. 检查 StepEventBus 是否已准备好
            if (!isStepEventBusReady()) {
                return; // Serenity 未就绪，稍后重试（静默，不重复刷日志）
            }

            // 1. 从堆栈跟踪找到 Glue 类（结果按类名缓存，避免每次调用扫描 123 帧栈）
            Class<?> glueClass = resolveGlueClass();

            if (glueClass == null) {
                return; // 栈中暂无 @AutoBrowser 类，静默重试
            }

            // 2. 检查 @AutoBrowser 注解
            AutoBrowser autoBrowser = glueClass.getAnnotation(AutoBrowser.class);

            if (autoBrowser == null || !autoBrowser.enabled()) {
                TestContextHolder.get().set(PROCESSED_KEY, true); // 终态：本场景无需处理，停止重试
                return;
            }

            // 3. 从 Serenity 上下文获取 Scenario 标签
            String[] tags = getTagsFromSerenityContext();

            if (tags == null || tags.length == 0) {
                return; // 非终态：tags 就绪后重试（glue 已缓存，无需再扫栈、不再刷日志）
            }

            // 4. 设置 Scenario 标签
            BrowserOverrideManager.setScenarioTags(tags);

            String effectiveBrowser = BrowserOverrideManager.getEffectiveBrowserType();
            VerboseLogging.logInfoIfVerbose(logger, "Effective browser type set to: {}", effectiveBrowser);

            // 标记为已处理
            TestContextHolder.get().set(PROCESSED_KEY, true);

        } catch (Exception e) {
            VerboseLogging.logErrorIfVerbose(logger, "ERROR processing @AutoBrowser annotation: {}", e.getMessage(), e);
        }
    }

    /**
     * 解析 @AutoBrowser glue 类，结果按类名缓存到 TestContext，避免每次 getPage() 重扫调用栈（日志噪声修复）。
     * 仅当真正找到时才缓存；未找到则不缓存，允许后续调用重试（glue 类可能稍后入栈）。
     */
    private static Class<?> resolveGlueClass() {
        String cachedName = TestContextHolder.get().get(GLUE_NAME_KEY);
        if (cachedName != null) {
            try {
                return Class.forName(cachedName);
            } catch (Throwable t) {
                TestContextHolder.get().remove(GLUE_NAME_KEY); // 缓存类失效，重置后重扫
            }
        }
        Class<?> found = findGlueClass();
        if (found != null) {
            TestContextHolder.get().set(GLUE_NAME_KEY, found.getName());
        }
        return found;
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
                    VerboseLogging.logInfoIfVerbose(logger, "BaseStepListener not registered, auto-registering...");
                    return registerBaseStepListener(eventBus);
                }

                return true;
            } catch (Exception e) {
                VerboseLogging.logTraceIfVerbose(logger, "Could not check BaseStepListener status: {}", e.getMessage());
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

            VerboseLogging.logInfoIfVerbose(logger, "BaseStepListener registered successfully");
            return true;

        } catch (Exception e) {
            VerboseLogging.logWarnIfVerbose(logger, "Failed to register BaseStepListener: {}", e.getMessage());
            return false;
        }
    }
    
    /**
     * 清除处理状态（在 Scenario 结束时调用）
     */
    public static void clearProcessingState() {
        TestContextHolder.get().remove(PROCESSED_KEY);
        TestContextHolder.get().remove(GLUE_NAME_KEY);
        TestContextHolder.get().remove(LOGGED_KEY);

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
        
        VerboseLogging.logDebugIfVerbose(logger, "Scanning {} stack trace elements for @AutoBrowser annotation", stackTrace.length);
        
        for (int i = 0; i < stackTrace.length; i++) {
            StackTraceElement element = stackTrace[i];
            String className = element.getClassName();
            
            VerboseLogging.logTraceIfVerbose(logger, "[{}] Checking class: {}", i, className);
            
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
                    VerboseLogging.logDebugIfVerbose(logger, "[{}] Found @AutoBrowser annotation on class: {} (enabled={})", 
                        i, className, autoBrowser.enabled());
                    
                    if (autoBrowser.enabled()) {
                        return clazz;
                    }
                }
            } catch (ClassNotFoundException e) {
                VerboseLogging.logTraceIfVerbose(logger, "[{}] Could not load class: {}", i, className);
            } catch (NoClassDefFoundError e) {
                VerboseLogging.logTraceIfVerbose(logger, "[{}] Could not load class (dependency issue): {}", i, className);
            } catch (Throwable e) {
                VerboseLogging.logTraceIfVerbose(logger, "[{}] Error loading class {}: {}", i, className, e.getMessage());
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
            VerboseLogging.logDebugIfVerbose(logger, "Attempting to get tags from StepEventBus...");

            // 从 StepEventBus 获取当前 TestOutcome
            StepEventBus eventBus = StepEventBus.getEventBus();
            if (eventBus == null) {
                VerboseLogging.logDebugIfVerbose(logger, "StepEventBus.getEventBus() returned null - tests may not be running with Serenity runners");
                return new String[0];
            }
            VerboseLogging.logTraceIfVerbose(logger, "StepEventBus instance: {}", eventBus.getClass().getName());

            // 使用反射安全地获取 BaseStepListener
            Method method = StepEventBus.class.getDeclaredMethod("currentBaseStepListener");
            method.setAccessible(true);
            Object listener = method.invoke(eventBus);

            if (listener == null) {
                VerboseLogging.logDebugIfVerbose(logger, "BaseStepListener not registered yet");
                return new String[0];
            }

            VerboseLogging.logTraceIfVerbose(logger, "BaseStepListener instance: {}", listener.getClass().getName());

            // 获取 TestOutcome
            Method getTestOutcomeMethod = listener.getClass().getMethod("getCurrentTestOutcome");
            TestOutcome testOutcome = (TestOutcome) getTestOutcomeMethod.invoke(listener);

            if (testOutcome == null) {
                VerboseLogging.logDebugIfVerbose(logger, "getCurrentTestOutcome() returned null");
                return new String[0];
            }
            VerboseLogging.logDebugIfVerbose(logger, "TestOutcome found: {}", testOutcome.getName());

            Set<TestTag> testTags = testOutcome.getTags();
            if (testTags == null) {
                VerboseLogging.logDebugIfVerbose(logger, "testOutcome.getTags() returned null");
                return new String[0];
            }

            VerboseLogging.logDebugIfVerbose(logger, "Found {} tags in TestOutcome", testTags.size());

            if (!testTags.isEmpty()) {
                String[] tags = testTags.stream()
                    .map(TestTag::getName)
                    .toArray(String[]::new);

                VerboseLogging.logDebugIfVerbose(logger, "Converted tags: {}", Arrays.toString(tags));
                return tags;
            }

        } catch (Exception e) {
            VerboseLogging.logDebugIfVerbose(logger, "Exception getting tags from Serenity context: {} - this is normal during early test initialization", e.getMessage());
        }

        return new String[0];
    }
    
    /**
     * 检查当前是否有 @AutoBrowser 注解生效
     * 
     * @return true 如果有注解生效
     */
    public static boolean hasAutoBrowserActive() {
        return Boolean.TRUE.equals(TestContextHolder.get().get(PROCESSED_KEY));
    }
}