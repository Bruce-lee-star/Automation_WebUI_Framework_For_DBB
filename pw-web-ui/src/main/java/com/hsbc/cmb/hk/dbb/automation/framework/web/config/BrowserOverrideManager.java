package com.hsbc.cmb.hk.dbb.automation.framework.web.config;


import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import com.hsbc.cmb.hk.dbb.automation.framework.core.context.ContextKey;
import com.hsbc.cmb.hk.dbb.automation.framework.core.context.TestContextHolder;

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
 * 注意（S2 注解门控）：浏览器覆盖最终是否生效，由 AutoBrowserProcessor 的注解门控决定——
 *   若 glue 类标注 @AutoBrowser(enabled=false)，即便 scenario 含 @firefox/@edge 标签，覆盖也会被压制（回落默认浏览器）。
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
    
    // 线程级别的浏览器覆盖配置（ T3-1 收拢：由 static ThreadLocal 迁入 TestContext，per-thread 等价）
    private static final ContextKey<String> OVERRIDE_BROWSER_TYPE_KEY =
            ContextKey.of("browserOverride.overrideBrowserType", String.class);
    
    //  评审移除（2026-09-17）：原 `globalOverrideMap`（threadId → browserType）与下面的 TestContext 键
    //  【双轨重复】，且是一条会造成跨用例污染的"复活轨道"：
    //  ① 查找始终以「当前线程 id」为键（getEffectiveBrowserType），因此永远读不到别的线程的条目 ——
    //     "用于并发测试"的初衷并未实现（跨线程可见性根本没建立）；
    //  ② per-thread 清理（ScenarioLifecycle / TestContextHolder.resetForCurrentThread）只清 TestContext，
    //     不碰本 Map；而 AutoBrowserProcessor 的清理入口以 hasOverride()（仅读 TestContext）为前置条件，
    //     一旦 TestContext 键已被清，Map 条目就被"跳过清理"而残留 —— 之后同一 worker 线程执行下一个
    //     scenario 时，getEffectiveBrowserType() 会把上一个 scenario 的浏览器覆盖【复活】回来（串扰）。
    //  收敛为单轨（TestContext）后，无覆盖时一律回落默认浏览器，语义更正确且不再有残留。
    
    // Scenario标签缓存（避免重复解析）（ T3-1 收拢：由 static ThreadLocal 迁入 TestContext，per-thread 等价）
    private static final ContextKey<String[]> SCENARIO_TAGS_KEY =
            ContextKey.of("browserOverride.scenarioTags", String[].class);
    
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

    // 多浏览器标签时的确定性优先级（数值越小优先级越高）。
    // 用于保证 scenario 同时带多个浏览器标签时，"只运行第一个"在任何运行下都得到一致结果，
    // 不依赖 Serenity TestOutcome.getTags()（本质为 Set）的遍历顺序。
    private static final List<String> BROWSER_PRIORITY = Arrays.asList("chromium", "firefox", "webkit");
    
    /**
     * 设置当前线程的浏览器覆盖配置
     * 
     * @param browserType 浏览器类型 (chromium, firefox, webkit)
     */
    public static void setOverrideBrowser(String browserType) {
        long threadId = Thread.currentThread().threadId();

        // 验证浏览器类型
        if (!isValidBrowserType(browserType)) {
            logger.warn("Invalid browser type: '{}'. Valid types are: chromium, firefox, webkit. Ignoring override.", 
                browserType);
            return;
        }
        
        TestContextHolder.get().set(OVERRIDE_BROWSER_TYPE_KEY, browserType);
        
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
     * 1. 线程级别的覆盖配置（TestContext，per-thread）
     * 2. 配置文件中的默认值
     *
     * @return 浏览器类型
     */
    public static String getEffectiveBrowserType() {
        // 1. 检查线程级别的覆盖配置
        String override = TestContextHolder.get().get(OVERRIDE_BROWSER_TYPE_KEY);
        if (override != null && !override.isEmpty()) {
            return override;
        }

        // 2. 返回配置文件中的默认值（原"全局覆盖 Map"已随双轨收敛移除）
        return getDefaultBrowserType();
    }
    
    /**
     * 获取默认浏览器类型（从配置文件）
     * 
     * @return 默认浏览器类型
     */
    public static String getDefaultBrowserType() {
        return FrameworkConfigManager.getString(WebFrameworkConfig.PLAYWRIGHT_BROWSER_TYPE);
    }
    
    /**
     * 清除当前线程的浏览器覆盖配置
     */
    public static void clearOverrideBrowser() {
        long threadId = Thread.currentThread().threadId();
        String oldType = TestContextHolder.get().get(OVERRIDE_BROWSER_TYPE_KEY);
        
        TestContextHolder.get().remove(OVERRIDE_BROWSER_TYPE_KEY);
        
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
        return TestContextHolder.get().get(OVERRIDE_BROWSER_TYPE_KEY) != null;
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
     * 支持两种格式的标签：
     * - 带 @ 前缀：@firefox, @chrome
     * - 不带 @ 前缀：firefox, chrome（Serenity 返回的格式）
     * 
     * @param tags Cucumber tags 数组
     * @return 浏览器类型，如果没有浏览器标签则返回 null
     */
    public static String extractBrowserFromTags(String[] tags) {
        if (tags == null || tags.length == 0) {
            return null;
        }

        // 收集所有浏览器标签（保留出现顺序），用于"只运行第一个"并告警其余被忽略项
        List<String> browserTags = new ArrayList<>();
        for (String tag : tags) {
            // 规范化标签：去除空格，转小写，确保有 @ 前缀
            String normalizedTag = tag.toLowerCase().trim();
            if (!normalizedTag.startsWith("@")) {
                normalizedTag = "@" + normalizedTag;
            }
            if (TAG_TO_BROWSER_TYPE.containsKey(normalizedTag)) {
                browserTags.add(normalizedTag);
            }
        }

        if (browserTags.isEmpty()) {
            return null;
        }

        // 按确定性优先级选取"第一个"：保证多浏览器标签场景下结果稳定，不随 Set 遍历顺序变化
        String selectedTag = browserTags.get(0);
        int selectedPriority = priorityOf(TAG_TO_BROWSER_TYPE.get(selectedTag));
        for (int i = 1; i < browserTags.size(); i++) {
            String candidate = browserTags.get(i);
            int candidatePriority = priorityOf(TAG_TO_BROWSER_TYPE.get(candidate));
            if (candidatePriority < selectedPriority) {
                selectedTag = candidate;
                selectedPriority = candidatePriority;
            }
        }
        String selectedBrowser = TAG_TO_BROWSER_TYPE.get(selectedTag);

        // 多浏览器标签：只运行第一个，明确告警并列出被忽略的标签
        if (browserTags.size() > 1) {
            List<String> ignored = new ArrayList<>(browserTags);
            ignored.remove(selectedTag);
            logger.warn(
                "Multiple browser tags detected in scenario: {}. Only the FIRST (by priority {}) will be used: {} -> {}. "
                    + "Ignored browser tags: {}.",
                String.join(", ", browserTags),
                BROWSER_PRIORITY,
                selectedTag, selectedBrowser,
                String.join(", ", ignored));
        }

        return selectedBrowser;
    }

    /**
     * 返回浏览器类型在 {@link #BROWSER_PRIORITY} 中的优先级（越小越优先）；
     * 未知类型给予最大优先级值，使其永不抢占已知类型。
     */
    private static int priorityOf(String browserType) {
        int idx = BROWSER_PRIORITY.indexOf(browserType);
        return idx < 0 ? Integer.MAX_VALUE : idx;
    }
    
    /**
     * 设置当前Scenario的标签（用于自动检测浏览器类型）
     * 这个方法不依赖Cucumber hooks，可以在任何地方调用
     * 
     * @param tags Scenario的所有标签
     */
    public static void setScenarioTags(String[] tags) {
        if (tags == null) {
            TestContextHolder.get().set(SCENARIO_TAGS_KEY, new String[0]);
            return;
        }
        TestContextHolder.get().set(SCENARIO_TAGS_KEY, tags);
        
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
        return TestContextHolder.get().get(SCENARIO_TAGS_KEY);
    }

    /**
     * 仅缓存 scenario tags（不在此时应用浏览器覆盖）。
     * <p>
     * 由 {@code AutoBrowserProcessor.onScenarioStart()} 在 scenario 开始时静默调用一次，
     * 使首个步骤里的 {@code @AutoBrowser} 门禁能立即拿到 tags 并终态化，避免"tags 未就绪→每次
     * getPage() 重试"的日志刷屏。浏览器覆盖的实际应用仍由 {@link #setScenarioTags(String[])} 完成。
     *
     * @param tags Scenario 的标签（可为 null）
     */
    public static void cacheScenarioTags(String[] tags) {
        if (tags == null) {
            TestContextHolder.get().set(SCENARIO_TAGS_KEY, new String[0]);
            return;
        }
        TestContextHolder.get().set(SCENARIO_TAGS_KEY, tags);
    }

    /**
     * 返回已缓存的 scenario tags（可能为 null 或空数组，表示尚未预取）。
     * 供 {@code AutoBrowserProcessor.onStepStarted(ExecutedStepDescription)} 在 @AutoBrowser 门禁确认前优先读取。
     *
     * @return 缓存的标签数组，未预取时为 null
     */
    public static String[] getCachedScenarioTags() {
        return TestContextHolder.get().get(SCENARIO_TAGS_KEY);
    }

    /**
     * 清除当前Scenario的标签
     */
    public static void clearScenarioTags() {
        TestContextHolder.get().remove(SCENARIO_TAGS_KEY);
        logger.debug("Scenario tags cleared");
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
        TestContextHolder.get().remove(OVERRIDE_BROWSER_TYPE_KEY);
        logger.info("All browser overrides cleared");
    }
}