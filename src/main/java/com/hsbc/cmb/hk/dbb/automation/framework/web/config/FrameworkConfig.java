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
     * 
     * 注意：反后台节流 flags 已硬编码到 PlaywrightConfigManager，会自动追加，无需在此配置。
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

    /**
     * BrowserStack 代理主机（公司网络环境必填）
     */
    BROWSERSTACK_PROXY_HOST(
        "browserstack.proxy.host",
        "",
        "BrowserStack 代理主机地址"
    ),

    /**
     * BrowserStack 代理端口（公司网络环境必填）
     */
    BROWSERSTACK_PROXY_PORT(
        "browserstack.proxy.port",
        "",
        "BrowserStack 代理端口"
    ),

    /**
     * BrowserStack 代理认证用户名（可选）
     */
    BROWSERSTACK_PROXY_USERNAME(
        "browserstack.proxy.username",
        "",
        "BrowserStack 代理认证用户名"
    ),

    /**
     * BrowserStack 代理认证密码（可选）
     */
    BROWSERSTACK_PROXY_PASSWORD(
        "browserstack.proxy.password",
        "",
        "BrowserStack 代理认证密码"
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

    /**
     * Context 代理服务器
     * 用于公司网络环境下浏览器通过代理访问内网域名
     * 格式: http://proxy-host:port
     * 示例: http://proxy.example.com:8080
     * 留空则不使用代理
     */
    PLAYWRIGHT_CONTEXT_PROXY(
        "playwright.context.proxy",
        "",
        "Context 代理服务器"
    ),

    /**
     * Context 代理用户名（可选）
     * 代理需要认证时填写，与 proxy.password 配合使用
     */
    PLAYWRIGHT_CONTEXT_PROXY_USERNAME(
        "playwright.context.proxy.username",
        "",
        "Context 代理用户名"
    ),

    /**
     * Context 代理密码（可选）
     * 代理需要认证时填写，与 proxy.username 配合使用
     */
    PLAYWRIGHT_CONTEXT_PROXY_PASSWORD(
        "playwright.context.proxy.password",
        "",
        "Context 代理密码"
    ),

    /**
     * Context 代理启用开关
     * true=启用代理（需同时配置 playwright.context.proxy）
     * false=禁用代理（即使配置了 proxy URL 也不会生效）
     * 方便在不同环境（公司网络/家庭网络）之间切换，无需反复修改 proxy URL
     */
    PLAYWRIGHT_CONTEXT_PROXY_ENABLED(
        "playwright.context.proxy.enabled",
        "false",
        "Context 代理启用开关"
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

    /**
     * 框架超详细日志输出（Trace级）
     */
    FRAMEWORK_TRACE_LOGGING(
        "framework.trace.logging",
        "false",
        "框架超详细日志"
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

    /**
     * Playwright Driver 临时目录
     * 指定 Playwright Driver 的临时文件存储目录
     */
    PLAYWRIGHT_DRIVER_TMPDIR(
        "playwright.driver.tmpdir",
        ".playwright/driver",
        "Playwright Driver 临时目录"
    ),

    PLAYWRIGHT_BROWSERS_PATH(
            "playwright.browsers.path",
            ".playwright/browsers",
            "Playwright browser path"),

    /**
     * 浏览器下载 HTTP 代理地址
     * 用于公司网络环境下 Playwright CLI 下载浏览器时通过代理访问 CDN。
     * 格式: host:port（不含 scheme 和认证信息）
     * 
     * 示例: proxy.company.com:8080
     */
    PLAYWRIGHT_BROWSER_DOWNLOAD_HTTP_PROXY(
        "playwright.browser.download.http.proxy",
        "",
        "浏览器下载 HTTP 代理地址"
    ),

    /**
     * 浏览器下载 HTTPS 代理地址
     * 格式: host:port（不含 scheme 和认证信息）
     * 
     * 示例: proxy.company.com:8443
     */
    PLAYWRIGHT_BROWSER_DOWNLOAD_HTTPS_PROXY(
        "playwright.browser.download.https.proxy",
        "",
        "浏览器下载 HTTPS 代理地址"
    ),

    /**
     * 浏览器下载 HTTP 代理用户名（可选）
     * 注意：用户名中的特殊字符（如 @ % $ 等）会自动进行 URL 编码。
     * 
     * 示例: myuser@domain
     */
    PLAYWRIGHT_BROWSER_DOWNLOAD_HTTP_PROXY_USERNAME(
        "playwright.browser.download.http.proxy.username",
        "",
        "浏览器下载 HTTP 代理用户名"
    ),

    /**
     * 浏览器下载 HTTPS 代理用户名（可选）
     * 注意：用户名中的特殊字符（如 @ % $ 等）会自动进行 URL 编码。
     */
    PLAYWRIGHT_BROWSER_DOWNLOAD_HTTPS_PROXY_USERNAME(
        "playwright.browser.download.https.proxy.username",
        "",
        "浏览器下载 HTTPS 代理用户名"
    ),

    /**
     * 浏览器下载 HTTP 代理密码（可选）
     * 注意：密码中的特殊字符（如 @ % $ 等）会自动进行 URL 编码。
     */
    PLAYWRIGHT_BROWSER_DOWNLOAD_HTTP_PROXY_PASSWORD(
        "playwright.browser.download.http.proxy.password",
        "",
        "浏览器下载 HTTP 代理密码"
    ),

    /**
     * 浏览器下载 HTTPS 代理密码（可选）
     * 注意：密码中的特殊字符（如 @ % $ 等）会自动进行 URL 编码。
     */
    PLAYWRIGHT_BROWSER_DOWNLOAD_HTTPS_PROXY_PASSWORD(
        "playwright.browser.download.https.proxy.password",
        "",
        "浏览器下载 HTTPS 代理密码"
    ),

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

    // ==================== Playwright 原生快照测试配置 ====================

    /**
     * 是否启用 Playwright 原生快照测试
     * true=启用, false=跳过
     */
    NATIVE_SNAPSHOT_ENABLED(
        "native.snapshot.enabled",
        "true",
        "是否启用 Playwright 原生快照测试"
    ),

    /**
     * Playwright 原生视觉快照基线目录
     * 存储 .png 格式的视觉基线
     */
    NATIVE_SNAPSHOT_VISUAL_DIR(
        "native.snapshot.visual.dir",
        "src/test/resources/snapshots/native/visual",
        "原生视觉快照基线目录"
    ),

    /**
     * Playwright 原生 ARIA 快照基线目录
     * 存储 .aria 格式的可访问性树基线
     */
    NATIVE_SNAPSHOT_ARIA_DIR(
        "native.snapshot.aria.dir",
        "src/test/resources/snapshots/native/aria",
        "原生 ARIA 快照基线目录"
    ),

    /**
     * 原生视觉快照最大差异像素数
     * 超过此值判定为失败
     */
    NATIVE_SNAPSHOT_MAX_DIFF_PIXELS(
        "native.snapshot.visual.maxDiffPixels",
        "100",
        "原生视觉快照最大差异像素数"
    ),

    /**
     * 原生视觉快照最大差异像素比例
     * 0.01 = 1%，超过此比例判定为失败
     */
    NATIVE_SNAPSHOT_MAX_DIFF_RATIO(
        "native.snapshot.visual.maxDiffPixelRatio",
        "0.01",
        "原生视觉快照最大差异像素比例"
    ),

    /**
     * 原生快照测试静默模式
     * true=减少日志输出, false=正常日志
     */
    NATIVE_SNAPSHOT_SILENT(
        "native.snapshot.silent",
        "false",
        "原生快照测试静默模式"
    ),

    // ==================== Playwright 元素操作配置 ====================

    /**
     * 元素操作后等待时间（毫秒）
     * 用于 click/fill 等操作后等待 DOM 稳定
     */
    PLAYWRIGHT_ELEMENT_ACTION_POST_DELAY(
        "playwright.element.action.post.delay",
        "200",
        "元素操作后等待时间（毫秒）"
    ),

    /**
     * 元素操作最大重试次数
     * 元素操作失败时的最大重试次数
     */
    PLAYWRIGHT_ELEMENT_RETRY_MAX(
        "playwright.element.retry.max",
        "2",
        "元素操作最大重试次数"
    ),

    /**
     * 元素操作重试间隔时间（毫秒）
     * 两次重试之间的等待时间
     */
    PLAYWRIGHT_ELEMENT_RETRY_DELAY_MS(
        "playwright.element.retry.delay.ms",
        "300",
        "元素操作重试间隔时间（毫秒）"
    ),

    /**
     * 元素操作总超时时间（毫秒）
     * 元素操作的总时间预算（含重试）
     */
    PLAYWRIGHT_ELEMENT_OPERATION_TIMEOUT(
        "playwright.element.operation.timeout",
        "30000",
        "元素操作总超时时间（毫秒）"
    ),

    /**
     * 失败时是否自动截图
     * true=自动截图, false=不截图
     */
    PLAYWRIGHT_ELEMENT_SCREENSHOT_ON_FAILURE(
        "playwright.element.screenshot.on.failure",
        "true",
        "失败时是否自动截图"
    ),

    /**
     * 是否收集详细诊断信息
     * true=收集完整诊断信息, false=收集基本信息
     */
    PLAYWRIGHT_ELEMENT_DIAGNOSTICS_DETAILED(
        "playwright.element.diagnostics.detailed",
        "true",
        "是否收集详细诊断信息"
    ),

    /**
     * 失败截图保存路径
     */
    PLAYWRIGHT_ELEMENT_SCREENSHOT_PATH(
        "playwright.element.screenshot.path",
        "target/screenshots",
        "失败截图保存路径"
    ),

    // ==================== Route Engine / API 捕获配置 ====================

    /**
     * API 捕获响应体总字节数上限（MB）
     * 防止大响应（如文件下载）导致 OOM。
     * 可通过 serenity.properties 或 JVM 参数覆盖：
     * {@code -Dapi.capture.max.response.size.mb=100}
     */
    API_CAPTURE_MAX_RESPONSE_SIZE_MB(
        "api.capture.max.response.size.mb",
        "50",
        "API 捕获响应体总字节数上限（MB）"
    ),

    /**
     * API 断言等待超时时间（毫秒）
     * 用于等待所有异步 API 请求完成
     */
    API_ASSERTION_WAIT_TIMEOUT(
        "api.assertion.wait.timeout.ms",
        "15000",
        "API 断言等待超时（毫秒）"
    ),

    // ==================== API Monitor 数据库存储配置 ====================

    /**
     * 是否启用 Monitor 数据库持久化存储
     * true=每次捕获 API 响应后自动写入数据库
     * false=不存储（默认）
     */
    MONITOR_DB_STORE_ENABLED(
        "monitor.db.store.enabled",
        "false",
        "是否启用 Monitor 数据库持久化存储"
    ),

    /**
     * 数据库类型
     * 支持: MYSQL, POSTGRESQL
     */
    MONITOR_DB_TYPE(
        "monitor.db.type",
        "MYSQL",
        "数据库类型 (MYSQL, POSTGRESQL)"
    ),

    /**
     * 数据库连接 URL
     */
    MONITOR_DB_URL(
        "monitor.db.url",
        "",
        "数据库连接 URL"
    ),

    /**
     * 数据库用户名
     */
    MONITOR_DB_USER(
        "monitor.db.user",
        "",
        "数据库用户名"
    ),

    /**
     * 数据库密码
     */
    MONITOR_DB_PASSWORD(
        "monitor.db.password",
        "",
        "数据库密码"
    ),

    /**
     * 数据库连接池最大连接数
     */
    MONITOR_DB_POOL_MAX_SIZE(
        "monitor.db.pool.max.size",
        "5",
        "数据库连接池最大连接数"
    ),

    /**
     * Monitor 测试运行 ID
     * 用于标记一次测试运行中的监控数据
     */
    MONITOR_TEST_RUN_ID(
        "monitor.test.run.id",
        "",
        "Monitor 测试运行 ID"
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
