package com.hsbc.cmb.dbb.hk.automation.framework.config;

import net.thucydides.model.environment.SystemEnvironmentVariables;

import java.util.function.Function;

/**
 * 框架配置枚举 - 集中管理所有配置项
 * 
 * 使用方式：
 * FrameworkConfig.SCREENSHOT_STRATEGY.getValue()
 * FrameworkConfig.BROWSER_RESTART_STRATEGY.getValue()
 */
public enum FrameworkConfig {

    // ==================== Serenity 核心配置 ====================

    /**
     * 项目名称
     */
    SERENITY_PROJECT_NAME(
        "serenity.project.name",
        "Serenity Playwright Demo",
        "项目名称"
    ),


    /**
     * 编码设置
     */
    SERENITY_ENCODING(
        "serenity.encoding",
        "UTF-8",
        "编码设置"
    ),

    /**
     * 报告编码设置
     */
    SERENITY_REPORT_ENCODING(
        "serenity.report.encoding",
        "UTF-8",
        "报告编码设置"
    ),

    // ==================== Playwright 浏览器配置 ====================

    /**
     * 浏览器类型
     * chromium - Chromium 浏览器
     * firefox - Firefox 浏览器
     * webkit - WebKit 浏览器（Safari）
     */
    PLAYWRIGHT_BROWSER_TYPE(
        "playwright.browser.type",
        "chromium",
        "浏览器类型"
    ),

    /**
     * 浏览器模式
     * true - 无头模式（后台运行）
     * false - 有头模式（显示浏览器窗口）
     */
    PLAYWRIGHT_BROWSER_HEADLESS(
        "playwright.browser.headless",
        "false",
        "浏览器模式"
    ),

    /**
     * 浏览器 channel - 使用本地安装的浏览器
     * chrome - 使用本地 Chrome 浏览器
     * msedge - 使用本地 Edge 浏览器
     * 不设置或设置为空 - 使用 Playwright 下载的浏览器
     */
    PLAYWRIGHT_BROWSER_CHANNEL(
        "playwright.browser.channel",
        "",
        "浏览器 channel"
    ),

    /**
     * 跳过 Playwright 浏览器下载
     * true - 跳过浏览器下载（使用 channel=chrome/msedge 时推荐）
     * false - 允许 Playwright 下载浏览器（默认）
     */
    PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD(
        "playwright.skip.browser.download",
        "false",
        "跳过 Playwright 浏览器下载"
    ),


    /**
     * 浏览器启动参数（逗号分隔）
     */
    PLAYWRIGHT_BROWSER_ARGS(
        "playwright.browser.args",
        "--disable-blink-features=AutomationControlled,--disable-pinch",
        "浏览器启动参数"
    ),

    /**
     * 浏览器操作慢动作延迟（毫秒），用于调试
     */
    PLAYWRIGHT_BROWSER_SLOWMO(
        "playwright.browser.slowMo",
        "500",
        "浏览器慢动作延迟"
    ),

    /**
     * 浏览器超时设置（毫秒）
     */
    PLAYWRIGHT_BROWSER_TIMEOUT(
        "playwright.browser.timeout",
        "30000",
        "浏览器超时设置"
    ),

    /**
     * 下载文件保存路径
     */
    PLAYWRIGHT_BROWSER_DOWNLOADS_PATH(
        "playwright.browser.downloadsPath",
        "target/downloads",
        "下载文件保存路径"
    ),

    // ==================== Playwright 窗口配置 ====================

    /**
     * 窗口最大化
     */
    PLAYWRIGHT_WINDOW_MAXIMIZE(
        "serenity.playwright.window.maximize",
        "true",
        "窗口最大化"
    ),

    /**
     * 窗口最大化参数
     */
    PLAYWRIGHT_WINDOW_MAXIMIZE_ARGS(
        "serenity.playwright.window.maximize.args",
        "--disable-infobars,--no-first-run,--no-default-browser-check",
        "窗口最大化参数"
    ),

    // ==================== Playwright 上下文配置 ====================

    /**
     * 禁用触摸功能
     */
    PLAYWRIGHT_CONTEXT_HAS_TOUCH(
        "playwright.context.hasTouch",
        "false",
        "禁用触摸功能"
    ),

    /**
     * 移动设备模拟
     */
    PLAYWRIGHT_CONTEXT_IS_MOBILE(
        "playwright.context.isMobile",
        "false",
        "移动设备模拟"
    ),

    /**
     * Viewport 宽度
     */
    PLAYWRIGHT_CONTEXT_VIEWPORT_WIDTH(
        "playwright.context.viewport.width",
        "1366",
        "Viewport 宽度"
    ),

    /**
     * Viewport 高度
     */
    PLAYWRIGHT_CONTEXT_VIEWPORT_HEIGHT(
        "playwright.context.viewport.height",
        "768",
        "Viewport 高度"
    ),

    /**
     * 截图保存路径
     */
    PLAYWRIGHT_CONTEXT_SCREENSHOT_PATH(
        "playwright.context.screenshotPath",
        "target/screenshots",
        "截图保存路径"
    ),

    /**
     * 录屏功能
     */
    PLAYWRIGHT_CONTEXT_RECORD_VIDEO_ENABLED(
        "playwright.context.recordVideo.enabled",
        "false",
        "录屏功能"
    ),

    /**
     * 录屏保存目录
     */
    PLAYWRIGHT_CONTEXT_RECORD_VIDEO_DIR(
        "playwright.context.recordVideo.dir",
        "target/videos",
        "录屏保存目录"
    ),

    /**
     * Trace 功能
     */
    PLAYWRIGHT_CONTEXT_TRACE_ENABLED(
        "playwright.context.trace.enabled",
        "true",
        "Trace 功能"
    ),

    /**
     * Trace 时截图
     */
    PLAYWRIGHT_CONTEXT_TRACE_SCREENSHOTS(
        "playwright.context.trace.screenshots",
        "true",
        "Trace 时截图"
    ),

    /**
     * Trace 时快照
     */
    PLAYWRIGHT_CONTEXT_TRACE_SNAPSHOTS(
        "playwright.context.trace.snapshots",
        "true",
        "Trace 时快照"
    ),

    /**
     * Trace 时源码
     */
    PLAYWRIGHT_CONTEXT_TRACE_SOURCES(
        "playwright.context.trace.sources",
        "true",
        "Trace 时源码"
    ),

    /**
     * Context locale 设置
     */
    PLAYWRIGHT_CONTEXT_LOCALE(
        "playwright.context.locale",
        "en-US",
        "Context locale 设置"
    ),

    /**
     * Context timezone 设置
     */
    PLAYWRIGHT_CONTEXT_TIMEZONE_ID(
        "playwright.context.timezoneId",
        "",
        "Context timezone 设置"
    ),

    /**
     * Context User-Agent 设置
     */
    PLAYWRIGHT_CONTEXT_USER_AGENT(
        "playwright.context.userAgent",
        "",
        "Context User-Agent 设置"
    ),

    /**
     * Context 权限设置（逗号分隔）
     */
    PLAYWRIGHT_CONTEXT_PERMISSIONS(
        "playwright.context.permissions",
        "",
        "Context 权限设置（逗号分隔）"
    ),

    /**
     * ColorScheme (light, dark, no-preference)
     */
    PLAYWRIGHT_CONTEXT_COLOR_SCHEME(
        "playwright.context.colorScheme",
        "light",
        "ColorScheme (light, dark, no-preference)"
    ),

    /**
     * Geolocation 纬度
     */
    PLAYWRIGHT_CONTEXT_GEOLOCATION_LATITUDE(
        "playwright.context.geolocation.latitude",
        "",
        "Geolocation 纬度"
    ),

    /**
     * Geolocation 经度
     */
    PLAYWRIGHT_CONTEXT_GEOLOCATION_LONGITUDE(
        "playwright.context.geolocation.longitude",
        "",
        "Geolocation 经度"
    ),

    /**
     * 设备缩放因子（留空则自动检测）
     */
    PLAYWRIGHT_CONTEXT_DEVICE_SCALE_FACTOR(
        "playwright.context.deviceScaleFactor",
        "",
        "设备缩放因子（留空则自动检测）"
    ),

    // ==================== Playwright 页面配置 ====================

    /**
     * 页面操作超时（毫秒）
     */
    PLAYWRIGHT_PAGE_TIMEOUT(
        "playwright.page.timeout",
        "15000",
        "页面操作超时"
    ),

    /**
     * 页面导航超时（毫秒）
     */
    PLAYWRIGHT_PAGE_NAVIGATION_TIMEOUT(
        "playwright.page.navigationTimeout",
        "15000",
        "页面导航超时"
    ),

    /**
     * 页面加载状态
     * LOAD - 页面开始加载时触发（最快）
     * DOMCONTENTLOADED - DOM 完全加载时触发（推荐）
     * NETWORKIDLE - 所有网络请求完成时触发（最稳定）
     */
    PLAYWRIGHT_PAGE_LOAD_STATE(
        "playwright.page.load.state",
        "NETWORKIDLE",
        "页面加载状态"
    ),

    /**
     * 页面稳定化等待超时（毫秒）
     */
    PLAYWRIGHT_STABILIZE_WAIT_TIMEOUT(
        "playwright.stabilize.wait.timeout",
        "8000",
        "页面稳定化等待超时"
    ),

    // ==================== 截图配置 ====================

    /**
     * 截图策略
     * BEFORE_AND_AFTER_EACH_STEP - 每个步骤前后截图
     * AFTER_EACH_STEP - 每个步骤后截图（推荐）
     * FOR_FAILURES - 仅失败时截图
     * DISABLED - 禁用截图
     */
    SERENITY_SCREENSHOT_STRATEGY(
        "serenity.screenshot.strategy",
        "AFTER_EACH_STEP",
        "截图策略"
    ),

    /**
     * 全页截图配置
     * true - 截图整个页面（包括滚动区域，较慢）
     * false - 仅截图可见区域 viewport（快，推荐）
     */
    PLAYWRIGHT_SCREENSHOT_FULLPAGE(
        "playwright.screenshot.fullpage",
        "true",
        "全页截图配置"
    ),


    /**
     * 截图等待超时（毫秒）
     */
    PLAYWRIGHT_SCREENSHOT_WAIT_TIMEOUT(
        "playwright.screenshot.wait.timeout",
        "5000",
        "截图等待超时"
    ),

    /**
     * Serenity 报告截图目录
     */
    SERENITY_REPORTS_SCREENSHOTS_DIRECTORY(
        "serenity.reports.screenshots.directory",
        "target/site/serenity",
        "Serenity 报告截图目录"
    ),

    /**
     * Serenity 截图目录
     */
    SERENITY_SCREENSHOTS_DIRECTORY(
        "serenity.screenshots.directory",
        "target/site/serenity",
        "Serenity 截图目录"
    ),

    // ==================== 日志配置 ====================

    /**
     * 日志级别
     * QUIET - 最少日志
     * NORMAL - 正常日志
     * VERBOSE - 详细日志
     */
    SERENITY_LOGGING(
        "serenity.logging",
        "VERBOSE",
        "日志级别"
    ),

    /**
     * 框架详细日志输出
     */
    FRAMEWORK_VERBOSE_LOGGING(
        "framework.verbose.logging",
        "false",
        "框架详细日志"
    ),

    // ==================== 报告配置 ====================

    /**
     * 报告输出目录
     */
    SERENITY_OUTPUT_DIRECTORY(
        "serenity.outputDirectory",
        "target/site/serenity",
        "报告输出目录"
    ),


    // ==================== 测试执行配置 ====================

    /**
     * 特性文件根目录
     */
    SERENITY_FEATURES_ROOT(
        "serenity.features.root",
        "src/test/resources/features",
        "特性文件根目录"
    ),

    /**
     * 需求根目录
     */
    SERENITY_REQUIREMENTS_BASE(
        "serenity.requirements.base",
        "src/test/resources/features",
        "需求根目录"
    ),

    /**
     * 浏览器配置（Playwright 原生配置）
     */
    SERENITY_BROWSER(
        "serenity.browser",
        "playwright",
        "浏览器配置"
    ),


    /**
     * 浏览器重启策略
     * FEATURE - 每个 feature 文件重启浏览器一次（更快，但可能有状态污染）
     * SCENARIO - 每个 scenario 重启浏览器一次（推荐）
     */
    SERENITY_PLAYWRIGHT_RESTART_BROWSER_FOR_EACH(
        "serenity.playwright.restart.browser.for.each",
        "scenario",
        "浏览器重启策略"
    ),



    // ==================== 重试配置 ====================
    /**
     * 失败测试重跑等待时间（毫秒）
     */
    SERENITY_RERUN_FAILURES_WAIT_TIME(
        "serenity.rerun.failures.wait.time",
        "1000",
        "失败测试重跑等待时间（毫秒）"
    ),

    /**
     * 重试延迟策略（fixed, exponential）
     */
    SERENITY_RETRY_DELAY_STRATEGY(
        "serenity.retry.delay.strategy",
        "fixed",
        "重试延迟策略（fixed, exponential）"
    ),

    /**
     * 指数退避基础延迟（毫秒）
     */
    SERENITY_RETRY_DELAY_BASE(
        "serenity.retry.delay.base",
        "1000",
        "指数退避基础延迟（毫秒）"
    ),

    /**
     * 指数退避最大延迟（毫秒）
     */
    SERENITY_RETRY_DELAY_MAX(
        "serenity.retry.delay.max",
        "30000",
        "指数退避最大延迟（毫秒）"
    ),

    /**
     * 指数退避乘数
     */
    SERENITY_RETRY_DELAY_MULTIPLIER(
        "serenity.retry.delay.multiplier",
        "2.0",
        "指数退避乘数"
    ),



    // ==================== API Mock 配置 ====================

    /**
     * 全局启用 API Mock
     */
    API_MOCK_ENABLED(
        "api.mock.enabled",
        "false",
        "全局启用 API Mock"
    ),

    /**
     * Mock 规则配置目录
     */
    API_MOCK_RULES_DIRECTORY(
        "api.mock.rules.directory",
        "src/test/resources/mocks",
        "Mock 规则配置目录"
    ),

    /**
     * Mock 日志级别
     * DEBUG, INFO, WARN, ERROR
     */
    API_MOCK_LOG_LEVEL(
        "api.mock.log.level",
        "INFO",
        "Mock 日志级别"
    ),

    // ==================== Playwright SDK 配置 ====================

    /**
     * Playwright SDK 目录
     * 指定 Playwright SDK 的存储目录
     */
    PLAYWRIGHT_SDK_DIR(
        "playwright.sdk.dir",
        ".playwright/sdk",
        "Playwright SDK 目录"
    );

    private final String key;
    private final String defaultValue;
    private final String description;

    FrameworkConfig(String key, String defaultValue, String description) {
        this.key = key;
        this.defaultValue = defaultValue;
        this.description = description;
    }

    /**
     * 获取配置值
     * @return 配置值（字符串）
     */
    public String getValue() {
        return SystemEnvironmentVariables.currentEnvironmentVariables().getProperty(key, defaultValue);
    }

    /**
     * 获取配置值（布尔）
     * @return 配置值（布尔）
     */
    public boolean getBooleanValue() {
        String value = getValue();
        return value != null && (value.equalsIgnoreCase("true") || value.equalsIgnoreCase("yes") || value.equalsIgnoreCase("1"));
    }

    /**
     * 获取配置值（整数）
     * @return 配置值（整数）
     */
    public int getIntValue() {
        try {
            return Integer.parseInt(getValue());
        } catch (NumberFormatException e) {
            return Integer.parseInt(defaultValue);
        }
    }

    /**
     * 获取配置值（长整数）
     * @return 配置值（长整数）
     */
    public long getLongValue() {
        try {
            return Long.parseLong(getValue());
        } catch (NumberFormatException e) {
            return Long.parseLong(defaultValue);
        }
    }

    /**
     * 设置配置值（用于运行时动态配置）
     * @param value 新的配置值
     */
    public void setValue(String value) {
        System.setProperty(key, value);
    }

    /**
     * 获取配置键名
     * @return 配置键名
     */
    public String getKey() {
        return key;
    }

    /**
     * 获取默认值
     * @return 默认值
     */
    public String getDefaultValue() {
        return defaultValue;
    }

    /**
     * 获取配置描述
     * @return 配置描述
     */
    public String getDescription() {
        return description;
    }

    /**
     * 获取枚举值的便捷方法（用于自定义转换）
     * @param mapper 转换函数
     * @return 转换后的值
     */
    public <T> T mapValue(Function<String, T> mapper) {
        return mapper.apply(getValue());
    }

    /**
     * 检查配置值是否匹配
     * @param expectedValue 期望值
     * @return 是否匹配
     */
    public boolean isValue(String expectedValue) {
        return getValue().equalsIgnoreCase(expectedValue);
    }
}
