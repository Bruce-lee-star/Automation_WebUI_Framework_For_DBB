package com.hsbc.cmb.hk.dbb.automation.framework.web.config;

import net.thucydides.model.environment.SystemEnvironmentVariables;

import java.util.function.Function;

/**
 * Framework Configuration Enum - Centralized management of all configuration items
 * Usage:
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
     * 
     * 注意：channel 仅适用于 Chromium 系列浏览器（Chrome、Edge）
     * 对于 Firefox，请使用 executablePath 指定本地浏览器路径
     */
    PLAYWRIGHT_BROWSER_CHANNEL(
        "playwright.browser.channel",
        "",
        "浏览器 channel"
    ),

    /**
     * Chrome 浏览器可执行文件路径
     * 如果设置，当浏览器类型为 chromium 时会使用此路径
     * 优先级高于 channel 配置
     * 
     * Windows 示例: "C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe"
     * macOS 示例: "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
     * Linux 示例: "/usr/bin/google-chrome"
     * 
     * 注意：Firefox 和 WebKit 必须使用 Playwright 编译的版本，不支持 executablePath
     */
    PLAYWRIGHT_BROWSER_CHROME_EXECUTABLE_PATH(
        "playwright.browser.chrome.executablePath",
        "",
        "Chrome 浏览器可执行文件路径"
    ),

    /**
     * Edge 浏览器可执行文件路径
     * 如果设置，当浏览器类型为 chromium 且 channel 为 msedge 时会使用此路径
     * 优先级高于 channel 配置
     * 
     * Windows 示例: "C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe"
     * macOS 示例: "/Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge"
     * Linux 示例: "/usr/bin/microsoft-edge"
     */
    PLAYWRIGHT_BROWSER_EDGE_EXECUTABLE_PATH(
        "playwright.browser.edge.executablePath",
        "",
        "Edge 浏览器可执行文件路径"
    ),

    /**
     * 跳过 Playwright 浏览器下载
     * 
     * 自动判断逻辑：
     * - Firefox: false（必须下载 Playwright 版本）
     * - WebKit: false（必须下载 Playwright 版本）
     * - Chrome: 如果设置了 chrome.executablePath 或 channel="chrome" 则 true，否则 false
     * - Edge: 如果设置了 edge.executablePath 或 channel="msedge" 则 true，否则 false
     * - Chromium: 如果设置了 channel 则根据具体情况，否则 false
     * 
     * 注意：建议保持默认值 false，让 Playwright 按需下载
     * 只有在完全确定使用本地 Chrome/Edge 且已安装时才设置为 true
     */
    PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD(
        "playwright.skip.browser.download",
        "false",
        "跳过 Playwright 浏览器下载"
    ),


    /**
     * Firefox 浏览器启动参数（逗号分隔）
     * 如果设置，当浏览器类型为 firefox 时会使用此参数
     * 
     * 注意：Firefox 必须使用 Playwright 编译的版本
     * Firefox 不支持 Chromium 特定参数（如 --disable-blink-features）
     * 
     * 示例: "--disable-web-security"
     */
    PLAYWRIGHT_BROWSER_FIREFOX_ARGS(
        "playwright.browser.firefox.args",
        "",
        "Firefox 浏览器启动参数"
    ),

    /**
     * Chrome 浏览器启动参数（逗号分隔）
     * 如果设置，当浏览器类型为 chromium 且 channel 为 chrome 时会使用此参数
     * 
     * 示例: "--disable-blink-features=AutomationControlled,--disable-pinch,--start-maximized"
     */
    PLAYWRIGHT_BROWSER_CHROME_ARGS(
        "playwright.browser.chrome.args",
        "",
        "Chrome 浏览器启动参数"
    ),

    /**
     * Edge 浏览器启动参数（逗号分隔）
     * 如果设置，当浏览器类型为 chromium 且 channel 为 msedge 时会使用此参数
     * 
     * 示例: "--disable-blink-features=AutomationControlled,--disable-pinch,--start-maximized"
     */
    PLAYWRIGHT_BROWSER_EDGE_ARGS(
        "playwright.browser.edge.args",
        "",
        "Edge 浏览器启动参数"
    ),

    /**
     * Chromium 浏览器启动参数（逗号分隔）
     * 如果设置，当浏览器类型为 chromium 且未指定 channel 时会使用此参数
     * 
     * 示例: "--disable-blink-features=AutomationControlled,--disable-pinch"
     */
    PLAYWRIGHT_BROWSER_CHROMIUM_ARGS(
        "playwright.browser.chromium.args",
        "",
        "Chromium 浏览器启动参数"
    ),

    /**
     * WebKit 浏览器启动参数（逗号分隔）
     * 如果设置，当浏览器类型为 webkit 时会使用此参数
     * 
     * 注意：WebKit 必须使用 Playwright 编译的版本
     * 
     * 示例: "--disable-web-security"
     */
    PLAYWRIGHT_BROWSER_WEBKIT_ARGS(
        "playwright.browser.webkit.args",
        "",
        "WebKit 浏览器启动参数"
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

    // ==================== BrowserStack 云测试配置 ====================

    /**
     * 是否启用 BrowserStack
     */
    BROWSERSTACK_ENABLED(
        "browserstack.enabled",
        "false",
        "是否启用 BrowserStack"
    ),

    /**
     * BrowserStack 用户名
     */
    BROWSERSTACK_USERNAME(
        "browserstack.username",
        "",
        "BrowserStack 用户名"
    ),

    /**
     * BrowserStack 访问密钥
     */
    BROWSERSTACK_ACCESS_KEY(
        "browserstack.accessKey",
        "",
        "BrowserStack 访问密钥"
    ),

    /**
     * BrowserStack 会话名称
     */
    BROWSERSTACK_SESSION_NAME(
        "browserstack.sessionName",
        "",
        "BrowserStack 会话名称"
    ),

    /**
     * BrowserStack 操作系统
     */
    BROWSERSTACK_OS(
        "browserstack.os",
        "Windows",
        "BrowserStack 操作系统"
    ),

    /**
     * BrowserStack 操作系统版本
     */
    BROWSERSTACK_OS_VERSION(
        "browserstack.osVersion",
        "11",
        "BrowserStack 操作系统版本"
    ),

    /**
     * BrowserStack 浏览器版本
     */
    BROWSERSTACK_BROWSER_VERSION(
        "browserstack.browserVersion",
        "latest",
        "BrowserStack 浏览器版本"
    ),

    /**
     * BrowserStack 超时设置（秒）
     */
    BROWSERSTACK_TIMEOUT(
        "browserstack.timeout",
        "300",
        "BrowserStack 超时设置"
    ),

    /**
     * BrowserStack 调试模式
     */
    BROWSERSTACK_DEBUG(
        "browserstack.debug",
        "true",
        "BrowserStack 调试模式"
    ),

    /**
     * BrowserStack 网络日志
     */
    BROWSERSTACK_NETWORK_LOGS(
        "browserstack.networkLogs",
        "true",
        "BrowserStack 网络日志"
    ),

    /**
     * BrowserStack 视频录制
     */
    BROWSERSTACK_VIDEO(
        "browserstack.video",
        "true",
        "BrowserStack 视频录制"
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
        "DOMCONTENTLOADED",
        "页面加载状态"
    ),

    /**
     * 页面稳定化等待超时（毫秒）
     */
    PLAYWRIGHT_STABILIZE_WAIT_TIMEOUT(
        "playwright.stabilize.wait.timeout",
        "15000",
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
     * Playwright Driver 路径
     * 指定 Playwright Driver 的存储目录
     */
    PLAYWRIGHT_DRIVER_PATH(
        "playwright.driver.path",
        ".playwright/driver",
        "Playwright Driver 路径"
    ),
    
    /**
     * Playwright SDK 目录
     * 指定 Playwright SDK 的存储目录
     */
    PLAYWRIGHT_SDK_PATH(
        "playwright.sdk.path",
        ".playwright/sdk",
        "Playwright SDK 目录"
    ),

    PLAYWRIGHT_BROWSERS_PATH(
            "playwright.browsers.path",
            ".playwright/browsers",
            "Playwright browser path"),

    /**
     * No login session timeout in minutes
     * After this period, saved sessions will be considered expired
     */
    PLAYWRIGHT_NO_LOGIN_SESSION_TIMEOUT(
        "playwright.no.login.session.timeout.minutes",
        "5",
        "No login session timeout (minutes)"),

    /**
     * 元素等待时间（毫秒）
     * 用于 isVisible, exists, isChecked, isEnabled, isDisabled, isElementClickable 等立即执行方法的重试超时
     * 这些方法会重试检查，直到超时，提高测试稳定性
     */
    PLAYWRIGHT_ELEMENT_WAIT_TIMEOUT(
        "playwright.element.wait.timeout",
        "15000",
        "元素等待/操作超时时间（毫秒，用于查询和操作的统一超时）"),

    /**
     * 轮询间隔时间（毫秒）
     * 用于各种等待方法的轮询检查间隔
     */
    PLAYWRIGHT_POLLING_INTERVAL(
        "playwright.polling.interval",
        "500",
        "轮询间隔（毫秒）"),

    // ==================== Axe-core 配置 ====================

    /**
     * 是否启用 axe-core 扫描
     */
    AXE_SCAN_ENABLED(
        "axe.scan.enabled",
        "false",
        "是否启用 axe-core 扫描"),

    /**
     * Axe-core WCAG 标签（逗号分隔）
     * 留空则运行所有规则
     * 例如: wcag2aa,wcag21aa
     */
    AXE_SCAN_TAGS(
        "axe.scan.tags",
        "",
        "Axe-core WCAG 标签"),

    /**
     * Axe-core 报告输出目录
     */
    AXE_SCAN_OUTPUT_DIR(
        "axe.scan.outputDir",
        "target/accessibility-axe",
        "Axe-core 报告输出目录"),

    // ==================== 快照测试（视觉回归）配置 ====================

    /**
     * 是否启用快照测试
     * true=启用, false=跳过所有快照测试
     */
    SNAPSHOT_TESTING_ENABLED(
        "snapshot.testing.enabled",
        "true",
        "是否启用快照测试"
    ),

    /**
     * 基线文件存储目录
     * ⚠️ 不要使用 target/ 目录！target/ 是 Maven 构建输出，mvn clean 会删除基线。
     * 推荐: src/test/resources/snapshots/baselines
     * 也可通过系统属性 -Dsnapshot.baseline.dir 覆盖
     */
    SNAPSHOT_BASELINE_DIR(
        "snapshot.baseline.dir",
        "src/test/resources/snapshots/baselines",
        "基线文件存储目录"
    ),

    /**
     * 当前快照和失败截图存储目录
     * 运行时临时输出，可放 target/ 下
     */
    SNAPSHOT_DIR(
        "snapshot.dir",
        "target/snapshots",
        "当前快照存储目录"
    ),

    /**
     * 最大差异像素阈值
     * 超过此值视为测试失败
     */
    SNAPSHOT_DIFF_THRESHOLD(
        "snapshot.diff.threshold",
        "1000",
        "最大差异像素阈值"
    ),

    /**
     * 是否忽略抗锯齿等微小差异
     * 浏览器渲染的亚像素级噪声
     */
    SNAPSHOT_IGNORE_ANTIALIAS(
        "snapshot.ignore.antiAlias",
        "true",
        "是否忽略抗锯齿差异"
    ),

    /**
     * 失败时是否自动保存当前截图
     */
    SNAPSHOT_FAILURE_SCREENSHOT(
        "snapshot.failure.screenshot",
        "true",
        "失败时自动截图"
    ),

    /**
     * 全局默认的基线模式
     * true = 默认创建/更新基线（开发阶段 / UI 改版后）
     * false = 默认仅对比，不修改基线（CI/CD 回归测试）
     *
     * 注意：代码 Builder 的 .updateBaseline(true/false) 可覆盖此全局默认值
     */
    SNAPSHOT_UPDATE_BASELINE(
        "snapshot.update.baseline",
        "false",
        "全局基线模式（true=更新, false=对比）"
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
