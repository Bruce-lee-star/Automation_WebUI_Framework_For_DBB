package com.hsbc.cmb.hk.dbb.automation.framework.web.enums;

/**
 * 浏览器切换策略
 * 
 * 定义不同的浏览器切换时机和行为
 * 
 * @author Automation Framework
 * @version 2.0
 */
public enum BrowserSwitchStrategy {
    /**
     * 延迟切换策略（推荐）
     * 
     * 策略：
     * - 在第一次调用PlaywrightManager.getBrowser()时才检测并切换
     * - 如果浏览器类型相同，不重启（性能优化）
     * 
     * 优点：
     * - 性能最好，避免不必要的浏览器重启
     * - 按需初始化，节省资源
     * - 适合大部分场景
     * 
     * 适用场景：
     * - 需要在不同浏览器上运行测试
     * - 需要性能优化
     * - 大部分测试使用相同浏览器
     */
    LAZY("延迟切换", "按需初始化，性能最优"),
    
    /**
     * 立即切换策略
     * 
     * 策略：
     * - 在步骤开始时立即检测并切换浏览器
     * - 无论浏览器类型是否相同，都检查并处理
     * 
     * 优点：
     * - 浏览器类型明确，便于调试
     * - 确保每个scenario都使用正确的浏览器
     * 
     * 适用场景：
     * - 需要确保浏览器类型严格正确
     * - 调试模式
     * - 浏览器类型频繁变化
     */
    EAGER("立即切换", "立即初始化，明确可靠"),
    
    /**
     * 缓存策略
     * 
     * 策略：
     * - 缓存已启动的浏览器实例
     * - 只在真正需要时才切换
     * - 同类型的scenarios复用浏览器
     * 
     * 优点：
     * - 最大化浏览器复用
     * - 最少的重启次数
     * 
     * 适用场景：
     * - 测试suite较长
     * - 浏览器启动开销大
     */
    CACHED("缓存策略", "最大化复用，最少重启"),
    
    /**
     * 隔离策略
     * 
     * 策略：
     * - 每个scenario都使用独立的浏览器实例
     * - 不复用任何浏览器资源
     * 
     * 优点：
     * - 完全隔离，无状态污染
     * - 最可靠的测试环境
     * 
     * 适用场景：
     * - 需要完全隔离的测试环境
     * - 浏览器状态敏感的测试
     * - 并行测试
     */
    ISOLATED("隔离策略", "完全隔离，最可靠");
    
    private final String displayName;
    private final String description;
    
    BrowserSwitchStrategy(String displayName, String description) {
        this.displayName = displayName;
        this.description = description;
    }
    
    public String getDisplayName() {
        return displayName;
    }
    
    public String getDescription() {
        return description;
    }
}
