package com.hsbc.cmb.hk.dbb.automation.framework.web.config;

import com.hsbc.cmb.hk.dbb.automation.framework.web.annotations.AutoBrowser;
import com.hsbc.cmb.hk.dbb.automation.framework.common.config.VerboseLogging;
import net.thucydides.core.steps.BaseStepListener;
import net.thucydides.core.steps.StepEventBus;
import net.thucydides.model.domain.TestOutcome;
import net.thucydides.model.domain.TestTag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
            VerboseLogging.logDebugIfVerbose(logger, "Already processed for current scenario, skipping");
            return;
        }

        VerboseLogging.logInfoIfVerbose(logger, "Processing @AutoBrowser annotation...");

        try {
            // 0. 检查 StepEventBus 是否已准备好（不再自动注册空 listener）
            if (!isStepEventBusReady()) {
                VerboseLogging.logDebugIfVerbose(logger, "StepEventBus not ready yet, skipping @AutoBrowser processing");
                return;
            }

            // 1. 从堆栈跟踪找到 Glue 类
            Class<?> glueClass = findGlueClass();

            if (glueClass == null) {
                VerboseLogging.logWarnIfVerbose(logger, "No class with @AutoBrowser found in call stack");
                VerboseLogging.logDebugIfVerbose(logger, "Call stack trace for debugging:");
                StackTraceElement[] stackTrace = Thread.currentThread().getStackTrace();
                for (int i = 0; i < Math.min(15, stackTrace.length); i++) {
                    VerboseLogging.logDebugIfVerbose(logger, "  [{}] {}", i, stackTrace[i].getClassName());
                }
                return;
            }

            VerboseLogging.logDebugIfVerbose(logger, "Found class '{}' with @AutoBrowser", glueClass.getName());

            // 2. 检查 @AutoBrowser 注解
            AutoBrowser autoBrowser = glueClass.getAnnotation(AutoBrowser.class);

            if (autoBrowser == null || !autoBrowser.enabled()) {
                VerboseLogging.logWarnIfVerbose(logger, "@AutoBrowser annotation not enabled on class '{}'",
                    glueClass.getSimpleName());
                return;
            }

            VerboseLogging.logDebugIfVerbose(logger, "@AutoBrowser annotation is enabled (verbose={})",
                autoBrowser.verbose());

            // 3. 从 Serenity 上下文获取 Scenario 标签（经 readScenarioTags 接缝，受控 bus；不再经反射 getter 探测）
            String[] tags = readScenarioTags(StepEventBus.getEventBus(), autoBrowser.verbose());

            if (tags == null || tags.length == 0) {
                VerboseLogging.logDebugIfVerbose(logger, "No tags found in Serenity context - this is normal during early test initialization");
                return;
            }

            VerboseLogging.logDebugIfVerbose(logger, "Found {} tags: {}", tags.length, Arrays.toString(tags));

            // 4. 设置 Scenario 标签
            BrowserOverrideManager.setScenarioTags(tags);

            String effectiveBrowser = BrowserOverrideManager.getEffectiveBrowserType();
            VerboseLogging.logInfoIfVerbose(logger, "Effective browser type set to: {}", effectiveBrowser);

            // 标记为已处理
            processedForCurrentScenario.set(true);

        } catch (Exception e) {
            VerboseLogging.logErrorIfVerbose(logger, "ERROR processing @AutoBrowser annotation: {}", e.getMessage(), e);
        }
    }

    /**
     * 检查 StepEventBus 是否已准备好。
     *
     * <p><b>注意</b>：仅探测就绪状态，<b>绝不</b>自动注册空 {@link BaseStepListener}。
     * 旧实现注册空 listener 会令 {@code getCurrentTestOutcome()} 恒为 null → tags 静默为空 →
     * 浏览器覆盖永久失效且无告警（见 {@code AutoBrowserProcessorTagBusTest} A1/A2 守卫）。
     *
     * @return true 如果 StepEventBus 已初始化且 BaseStepListener 已注册
     */
    private static boolean isStepEventBusReady() {
        try {
            StepEventBus eventBus = StepEventBus.getEventBus();
            return eventBus != null && eventBus.isBaseStepListenerRegistered();
        } catch (Exception e) {
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
     * 从给定 {@link StepEventBus} 读取当前 scenario 标签（A1/A2 回归接缝）。
     *
     * <p>直接从传入的 bus 读，<b>不</b>注入空 listener、<b>不</b>用会抛异常的反射 getter 探测；
     * bus 未就绪（null 或未注册 listener）即原样返回空数组，交由后续 step 自然重试。
     *
     * @param bus     受控 bus（prod 传 {@code StepEventBus.getEventBus()}，测试传 Mockito 替身）
     * @param verbose 是否输出 debug 日志
     * @return 标签名数组（无则空数组，绝不 null）
     */
    static String[] readScenarioTags(StepEventBus bus, boolean verbose) {
        if (bus == null || !bus.isBaseStepListenerRegistered()) {
            return new String[0];
        }
        try {
            BaseStepListener listener = bus.getBaseStepListener();
            TestOutcome outcome = listener.getCurrentTestOutcome();
            Set<TestTag> tags = outcome.getTags();
            if (tags == null || tags.isEmpty()) {
                return new String[0];
            }
            String[] result = tags.stream().map(TestTag::getName).toArray(String[]::new);
            if (verbose) {
                VerboseLogging.logDebugIfVerbose(logger, "readScenarioTags -> {}", Arrays.toString(result));
            }
            return result;
        } catch (Exception e) {
            VerboseLogging.logDebugIfVerbose(logger,
                    "readScenarioTags failed (bus not fully initialized): {} - normal during early test init", e.getMessage());
            return new String[0];
        }
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
     * 检查当前是否有 @AutoBrowser 注解生效
     * 
     * @return true 如果有注解生效
     */
    public static boolean hasAutoBrowserActive() {
        return Boolean.TRUE.equals(processedForCurrentScenario.get());
    }
}
