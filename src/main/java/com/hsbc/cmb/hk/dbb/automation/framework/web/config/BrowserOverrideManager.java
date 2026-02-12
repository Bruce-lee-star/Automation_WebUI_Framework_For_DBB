package com.hsbc.cmb.hk.dbb.automation.framework.web.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Browser Override Manager - 管理测试用例级别的浏览器覆盖配置
 * 
 * 使用场景：
 * 1. 大部分测试用例使用 serenity.conf 中配置的默认浏览器
 * 2. 某些特定测试用例需要在特定浏览器上执行（如 Firefox、WebKit）
 * 3. 通过 Cucumber tags 动态指定浏览器类型
 * 
 * 新的设计理念：
 * - 不依赖 Cucumber @Before hooks，采用延迟初始化策略
 * - 在 PlaywrightManager 首次请求浏览器实例时，自动检测当前线程关联的标签
 * - 实现零侵入式的浏览器切换，无需在测试代码中显式调用
 * - 自动管理浏览器实例的生命周期，避免不必要的重启
 * 
 * 使用方式：
 * <pre>
 * // 方式1：通过 Cucumber tag（推荐）
 * @firefox
 * Scenario: Test in Firefox
 *   When I navigate to homepage
 *   Then I should see the title
 * 
 * // 方式2：手动设置（用于特殊场景）
 * BrowserOverrideManager.setOverrideBrowser("firefox");
 * // 执行测试...
 * BrowserOverrideManager.clearOverrideBrowser();
 * </pre>
 */
public class BrowserOverrideManager {
    
    private static final Logger logger = LoggerFactory.getLogger(BrowserOverrideManager.class);
    
    // 线程级别的浏览器覆盖配置
    private static final ThreadLocal<String> overrideBrowserType = new ThreadLocal<>();
    
    // 全局浏览器覆盖配置（用于并发测试）
    private static final Map<Long, String> globalOverrideMap = new ConcurrentHashMap<>();
    
    // Scenario标签缓存（避免重复解析）
    private static final ThreadLocal<String[]> scenarioTags = new ThreadLocal<>();
    
    // 标签到浏览器类型的映射
    private static final Map<String, String> TAG_TO_BROWSER_TYPE = new ConcurrentHashMap<>();
    
    static {
        // 初始化标签映射
        TAG_TO_BROWSER_TYPE.put("@chromium", "chromium");
        TAG_TO_BROWSER_TYPE.put("@chrome", "chromium");
        TAG_TO_BROWSER_TYPE.put("@firefox", "firefox");
        TAG_TO_BROWSER_TYPE.put("@webkit", "webkit");
        TAG_TO_BROWSER_TYPE.put("@edge", "chromium");
        TAG_TO_BROWSER_TYPE.put("@safari", "webkit");
    }
    
    /**
     * 设置当前线程的浏览器覆盖配置
     * 
     * @param browserType 浏览器类型 (chromium, firefox, webkit)
     */
    public static void setOverrideBrowser(String browserType) {
        long threadId = Thread.currentThread().getId();
        
        // 验证浏览器类型
        if (!isValidBrowserType(browserType)) {
            logger.warn("Invalid browser type: '{}'. Valid types are: chromium, firefox, webkit. Ignoring override.", 
                browserType);
            return;
        }
        
        overrideBrowserType.set(browserType);
        globalOverrideMap.put(threadId, browserType);
        
        logger.info("Browser override set for thread {}: {} -> {}", 
            threadId, getDefaultBrowserType(), browserType);
    }
    
    /**
     * 从 Cucumber tag 设置浏览器覆盖配置
     * 
     * @param tag Cucumber tag (e.g., @firefox, @chrome)
     */
    public static void setOverrideBrowserByTag(String tag) {
        if (tag == null || tag.trim().isEmpty()) {
            logger.warn("Empty tag provided to setOverrideBrowserByTag. Ignoring.");
            return;
        }
        
        // 确保标签以 @ 开头
        String normalizedTag = tag.startsWith("@") ? tag : "@" + tag;
        
        String browserType = TAG_TO_BROWSER_TYPE.get(normalizedTag.toLowerCase());
        
        if (browserType != null) {
            setOverrideBrowser(browserType);
            logger.info("Browser override set by tag '{}' to browser type: {}", normalizedTag, browserType);
        } else {
            logger.warn("No browser type mapping found for tag: '{}'. Valid tags are: {}", 
                normalizedTag, TAG_TO_BROWSER_TYPE.keySet());
        }
    }
    
    /**
     * 获取当前的浏览器类型（考虑覆盖配置）
     * 
     * 优先级：
     * 1. 线程级别的覆盖配置
     * 2. 全局覆盖配置
     * 3. 配置文件中的默认值
     * 
     * @return 浏览器类型
     */
    public static String getEffectiveBrowserType() {
        // 1. 检查线程级别的覆盖配置
        String override = overrideBrowserType.get();
        if (override != null && !override.isEmpty()) {
            return override;
        }
        
        // 2. 检查全局覆盖配置
        long threadId = Thread.currentThread().getId();
        override = globalOverrideMap.get(threadId);
        if (override != null && !override.isEmpty()) {
            return override;
        }
        
        // 3. 返回配置文件中的默认值
        return getDefaultBrowserType();
    }
    
    /**
     * 获取默认浏览器类型（从配置文件）
     * 
     * @return 默认浏览器类型
     */
    public static String getDefaultBrowserType() {
        return FrameworkConfigManager.getString(FrameworkConfig.PLAYWRIGHT_BROWSER_TYPE);
    }
    
    /**
     * 清除当前线程的浏览器覆盖配置
     */
    public static void clearOverrideBrowser() {
        long threadId = Thread.currentThread().getId();
        String oldType = overrideBrowserType.get();
        
        overrideBrowserType.remove();
        globalOverrideMap.remove(threadId);
        
        if (oldType != null) {
            logger.info("Browser override cleared for thread {}, reverting to: {}", 
                threadId, getDefaultBrowserType());
        }
    }
    
    /**
     * 检查当前是否有浏览器覆盖配置
     * 
     * @return true if override is active, false otherwise
     */
    public static boolean hasOverride() {
        return overrideBrowserType.get() != null;
    }
    
    /**
     * 检查指定的标签是否为浏览器标签
     * 
     * @param tag 标签字符串
     * @return true if tag is a browser tag, false otherwise
     */
    public static boolean isBrowserTag(String tag) {
        if (tag == null || tag.trim().isEmpty()) {
            return false;
        }
        
        String normalizedTag = tag.startsWith("@") ? tag : "@" + tag;
        return TAG_TO_BROWSER_TYPE.containsKey(normalizedTag.toLowerCase());
    }
    
    /**
     * 从标签数组中提取浏览器类型
     * 
     * @param tags Cucumber tags 数组
     * @return 浏览器类型，如果没有浏览器标签则返回 null
     */
    public static String extractBrowserFromTags(String[] tags) {
        if (tags == null || tags.length == 0) {
            return null;
        }
        
        for (String tag : tags) {
            String normalizedTag = tag.toLowerCase().trim();
            if (TAG_TO_BROWSER_TYPE.containsKey(normalizedTag)) {
                return TAG_TO_BROWSER_TYPE.get(normalizedTag);
            }
        }
        
        return null;
    }
    
    /**
     * 设置当前Scenario的标签（用于自动检测浏览器类型）
     * 这个方法不依赖Cucumber hooks，可以在任何地方调用
     * 
     * @param tags Scenario的所有标签
     */
    public static void setScenarioTags(String[] tags) {
        if (tags == null) {
            scenarioTags.set(new String[0]);
            return;
        }
        scenarioTags.set(tags);
        
        // 自动从标签中提取浏览器类型
        String browserType = extractBrowserFromTags(tags);
        if (browserType != null) {
            logger.info("Auto-detected browser type '{}' from scenario tags: {}", 
                browserType, String.join(", ", tags));
            setOverrideBrowser(browserType);
        }
    }
    
    /**
     * 获取当前Scenario的标签
     *
     * @return 标签数组
     */
    public static String[] getScenarioTags() {
        return scenarioTags.get();
    }

    /**
     * 清除当前Scenario的标签
     */
    public static void clearScenarioTags() {
        scenarioTags.remove();
        logger.debug("Scenario tags cleared");
    }

    /**
     * 检查是否需要切换浏览器
     * 通过比较当前浏览器类型和从标签中提取的类型来判断
     *
     * 注意：这个方法应该在getBrowser()之前调用，此时overrideBrowserType已经设置好了
     *
     * @return true 如果需要切换浏览器
     */
    public static boolean needsBrowserSwitch() {
        String[] tags = scenarioTags.get();
        if (tags == null || tags.length == 0) {
            return false;
        }

        String targetBrowserType = extractBrowserFromTags(tags);
        if (targetBrowserType == null) {
            return false;
        }

        // 获取期望的浏览器类型（从override或默认值）
        String expectedBrowserType = overrideBrowserType.get();
        if (expectedBrowserType == null) {
            expectedBrowserType = getDefaultBrowserType();
        }

        // 获取默认浏览器类型（用于判断是否真的需要切换）
        String defaultBrowserType = getDefaultBrowserType();

        // 如果期望的浏览器类型和默认类型相同，说明没有override，不需要切换
        if (targetBrowserType.equalsIgnoreCase(defaultBrowserType)) {
            if (logger.isDebugEnabled()) {
                logger.debug("needsBrowserSwitch: target browser '{}' matches default '{}', no switch needed",
                    targetBrowserType, defaultBrowserType);
            }
            return false;
        }

        // 如果期望的浏览器类型已经等于目标类型，说明已经设置过了
        if (targetBrowserType.equalsIgnoreCase(expectedBrowserType)) {
            if (logger.isDebugEnabled()) {
                logger.debug("needsBrowserSwitch: override already set to '{}', checking if restart needed",
                    expectedBrowserType);
            }
            // 这里需要通过其他方式判断是否真的需要重启
            // 简单起见，我们假设已经设置override就表示需要重启
            // 实际应该检查当前运行的浏览器类型
            return true;
        }

        logger.info("needsBrowserSwitch: needs to switch from '{}' to '{}'",
            expectedBrowserType, targetBrowserType);
        return true;
    }
    
    /**
     * 添加自定义标签到浏览器类型的映射
     * 
     * @param tag Cucumber tag (e.g., @mychrome)
     * @param browserType 浏览器类型 (chromium, firefox, webkit)
     */
    public static void addTagMapping(String tag, String browserType) {
        if (!isValidBrowserType(browserType)) {
            logger.warn("Cannot add tag mapping: invalid browser type '{}'. Valid types are: chromium, firefox, webkit", 
                browserType);
            return;
        }
        
        String normalizedTag = tag.startsWith("@") ? tag : "@" + tag;
        TAG_TO_BROWSER_TYPE.put(normalizedTag.toLowerCase(), browserType);
        logger.info("Added tag mapping: {} -> {}", normalizedTag, browserType);
    }
    
    /**
     * 验证浏览器类型是否有效
     * 
     * @param browserType 浏览器类型
     * @return true if valid, false otherwise
     */
    private static boolean isValidBrowserType(String browserType) {
        if (browserType == null || browserType.trim().isEmpty()) {
            return false;
        }
        
        String type = browserType.toLowerCase().trim();
        return type.equals("chromium") || 
               type.equals("firefox") || 
               type.equals("webkit");
    }
    
    /**
     * 获取所有支持的浏览器标签
     * 
     * @return 标签列表
     */
    public static String[] getSupportedTags() {
        return TAG_TO_BROWSER_TYPE.keySet().toArray(new String[0]);
    }
    
    /**
     * 清除所有覆盖配置（用于测试清理）
     */
    public static void clearAll() {
        overrideBrowserType.remove();
        globalOverrideMap.clear();
        logger.info("All browser overrides cleared");
    }
}
