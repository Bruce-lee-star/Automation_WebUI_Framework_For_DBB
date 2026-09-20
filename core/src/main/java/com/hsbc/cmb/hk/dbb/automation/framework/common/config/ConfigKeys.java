package com.hsbc.cmb.hk.dbb.automation.framework.common.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 统一配置键注册表 —— 全框架配置键的<b>唯一事实来源（SSoT）</b>（评审08类-7 / C-6 / M-2）。
 *
 * <p>本枚举汇总全框架配置键元数据（{@code key / defaultValue / description}），是所有配置键的
 * <b>唯一定义处</b>：Web（{@code WebFrameworkConfig}）、API（{@code ApiFrameworkConfig}）、
 * Monitor（{@code MonitorConfig}）三侧均已退化为<b>经本注册表读取的门面</b>——它们不再各自持有
 * key / 默认值字面量，而是直接引用本枚举条目。
 *
 * <p><b>分域命名</b>（避免跨域同名冲突，并保留域归属可读性）：
 * <ul>
 *   <li>{@code WEB_*}：Web 域键（映射 {@code WebFrameworkConfig} 同去前缀常量名）；</li>
 *   <li>{@code API_*}：API/HTTP 域键（映射 {@code ApiFrameworkConfig} 同去前缀常量名）；</li>
 *   <li>其余：Monitor / 持久化域键（映射 {@code MonitorConfig} 同名常量）。</li>
 * </ul>
 *
 * <p><b>收敛收益</b>：消除「同键多处定义 / 默认值漂移」——此前 Web/API 侧各自持有字面量、
 * 本枚举另存一份镜像，两处默认值一旦不一致，实际生效值取决于读取路径（历史上
 * {@code serenity.screenshot.strategy} 的镜像默认值即与真实枚举不一致）。收敛后同一键只有一个定义，
 * 该风险在结构上被消除。
 *
 * <p><b>校验</b>：类加载时对所有键做<b>重复键 fail-fast 静态校验</b>（见 {@link #validateUniqueKeys()}），
 * 新增配置键必须且只能在此登记。默认值不漂移由 {@code ConfigKeysGoldenTest} 的 golden 快照守住。
 *
 * <p><b>运行期自适应默认值</b>：个别键的默认值依赖运行环境（如 {@link #WEB_PLAYWRIGHT_CONCURRENT_MAX}
 * 按 CPU 核数自适应），此类默认值在本枚举类加载时求值一次并固定，语义与收敛前一致
 * （收敛前同样在枚举类加载时求值）。该键在 golden 快照中以 {@code <adaptive>} 标记。
 */
public enum ConfigKeys {

    // ===================== Web（对应 WebFrameworkConfig）=====================

    WEB_SERENITY_PROJECT_NAME(
        "serenity.project.name",
        "Serenity Playwright Demo",
        "项目名称"
    ),
    WEB_SERENITY_ENCODING(
        "serenity.encoding",
        "UTF-8",
        "编码设置"
    ),
    WEB_SERENITY_REPORT_ENCODING(
        "serenity.report.encoding",
        "UTF-8",
        "报告编码设置"
    ),
    WEB_PLAYWRIGHT_BROWSER_TYPE(
        "playwright.browser.type",
        "chromium",
        "浏览器类型"
    ),
    WEB_PLAYWRIGHT_PAGE_ERROR_FAIL(
        "playwright.page.error.failOnError",
        "false",
        "页面未捕获 JS 异常是否触发测试失败"
    ),
    WEB_PLAYWRIGHT_BROWSER_HEADLESS(
        "playwright.browser.headless",
        "false",
        "浏览器模式"
    ),
    WEB_PLAYWRIGHT_BROWSER_CHANNEL(
        "playwright.browser.channel",
        "",
        "浏览器 channel"
    ),
    WEB_PLAYWRIGHT_BROWSER_CHROME_EXECUTABLE_PATH(
        "playwright.browser.chrome.executablePath",
        "",
        "Chrome 浏览器可执行文件路径"
    ),
    WEB_PLAYWRIGHT_BROWSER_EDGE_EXECUTABLE_PATH(
        "playwright.browser.edge.executablePath",
        "",
        "Edge 浏览器可执行文件路径"
    ),
    WEB_PLAYWRIGHT_BROWSER_FIREFOX_ARGS(
        "playwright.browser.firefox.args",
        "",
        "Firefox 浏览器启动参数"
    ),
    WEB_PLAYWRIGHT_BROWSER_CHROME_ARGS(
        "playwright.browser.chrome.args",
        "",
        "Chrome 浏览器启动参数"
    ),
    WEB_PLAYWRIGHT_BROWSER_EDGE_ARGS(
        "playwright.browser.edge.args",
        "",
        "Edge 浏览器启动参数"
    ),
    WEB_PLAYWRIGHT_BROWSER_CHROMIUM_ARGS(
        "playwright.browser.chromium.args",
        "",
        "Chromium 浏览器启动参数"
    ),
    WEB_PLAYWRIGHT_BROWSER_WEBKIT_ARGS(
        "playwright.browser.webkit.args",
        "",
        "WebKit 浏览器启动参数"
    ),
    WEB_PLAYWRIGHT_BROWSER_SLOWMO(
        "playwright.browser.slowMo",
        "500",
        "浏览器慢动作延迟"
    ),
    WEB_PLAYWRIGHT_BROWSER_TIMEOUT(
        "playwright.browser.timeout",
        "30000",
        "浏览器超时设置"
    ),
    WEB_PLAYWRIGHT_BROWSER_DOWNLOADS_PATH(
        "playwright.browser.downloadsPath",
        "target/downloads",
        "下载文件保存路径"
    ),
    WEB_BROWSERSTACK_ENABLED(
        "browserstack.enabled",
        "false",
        "是否启用 BrowserStack"
    ),
    WEB_BROWSERSTACK_USERNAME(
        "browserstack.username",
        "",
        "BrowserStack 用户名"
    ),
    WEB_BROWSERSTACK_ACCESS_KEY(
        "browserstack.accessKey",
        "",
        "BrowserStack 访问密钥"
    ),
    WEB_BROWSERSTACK_SESSION_NAME(
        "browserstack.sessionName",
        "",
        "BrowserStack 会话名称"
    ),
    WEB_BROWSERSTACK_BROWSER_NAME(
        "browserstack.browserName",
        "chrome",
        "BrowserStack 浏览器名称（chrome/firefox/webkit/edge）"
    ),
    WEB_BROWSERSTACK_OS(
        "browserstack.os",
        "Windows",
        "BrowserStack 操作系统"
    ),
    WEB_BROWSERSTACK_OS_VERSION(
        "browserstack.osVersion",
        "11",
        "BrowserStack 操作系统版本"
    ),
    WEB_BROWSERSTACK_BROWSER_VERSION(
        "browserstack.browserVersion",
        "latest",
        "BrowserStack 浏览器版本"
    ),
    WEB_BROWSERSTACK_TIMEOUT(
        "browserstack.timeout",
        "300",
        "BrowserStack 超时设置"
    ),
    WEB_BROWSERSTACK_DEBUG(
        "browserstack.debug",
        "true",
        "BrowserStack 调试模式"
    ),
    WEB_BROWSERSTACK_NETWORK_LOGS(
        "browserstack.networkLogs",
        "true",
        "BrowserStack 网络日志"
    ),
    WEB_BROWSERSTACK_VIDEO(
        "browserstack.video",
        "true",
        "BrowserStack 视频录制"
    ),
    WEB_BROWSERSTACK_LOCAL(
        "browserstack.local",
        "false",
        "BrowserStack Local Testing 开关"
    ),
    WEB_BROWSERSTACK_LOCAL_IDENTIFIER(
        "browserstack.local.identifier",
        "",
        "BrowserStack Local 隧道标识符"
    ),
    WEB_BROWSERSTACK_LOCAL_PATH(
        "browserstack.local.path",
        "",
        "BrowserStack Local 二进制文件路径"
    ),
    WEB_BROWSERSTACK_LOCAL_TIMEOUT(
        "browserstack.local.timeout",
        "30",
        "BrowserStack Local 启动超时（秒）"
    ),
    WEB_BROWSERSTACK_CDP_ENDPOINT(
        "browserstack.cdp.endpoint",
        "cdp.browserstack.com",
        "BrowserStack CDP WebSocket 端点域名"
    ),
    WEB_PLAYWRIGHT_WINDOW_MAXIMIZE(
        "serenity.playwright.window.maximize",
        "true",
        "窗口最大化"
    ),
    WEB_PLAYWRIGHT_WINDOW_MAXIMIZE_ARGS(
        "serenity.playwright.window.maximize.args",
        "--disable-infobars,--no-first-run,--no-default-browser-check",
        "窗口最大化参数"
    ),
    WEB_PLAYWRIGHT_CONCURRENT_PARALLELISM(
        "serenity.playwright.concurrent.parallelism",
        "4",
        "并发上下文执行器并行度（同时运行的独立 BrowserContext 任务数）"
    ),
    WEB_PLAYWRIGHT_CONCURRENT_TASK_TIMEOUT_SECONDS(
        "serenity.playwright.concurrent.task.timeout.seconds",
        "60",
        "并发执行器单任务超时（秒）"
    ),
    WEB_PLAYWRIGHT_CONCURRENT_USE_VIRTUAL_THREADS(
        "serenity.playwright.concurrent.use.virtual.threads",
        "false",
        "并发执行器启用虚拟线程（JDK 21+，启用前审计 BasePage pinning）"
    ),
    WEB_CONCURRENCY_PARTITION_ENABLED(
        "serenity.playwright.concurrent.partition.enabled",
        "auto",
        "SSO 感知并发：按身份分区互斥（相同身份串行、不同身份并行）。auto=引擎级并行为真时自动启用"
    ),
    WEB_CONCURRENCY_PARTITION_MAX_WAIT_MS(
        "serenity.playwright.concurrent.partition.max.wait.ms",
        "180000",
        "进入并发闸门的最大等待（毫秒）；0 表示无限等待（旧行为，不推荐）"
    ),
    WEB_CONCURRENCY_PARTITION_PER_KEY_PERMITS(
        "serenity.playwright.concurrent.partition.per.key.permits",
        "1",
        "每个并发分区键的并发许可数（>1 表示允许同身份 N 路并发）"
    ),
    WEB_CONCURRENCY_PARTITION_DIMENSIONS(
        "serenity.playwright.concurrent.partition.dimensions",
        "environment,username",
        "参与并发分区键的身份维度集合（environment,username,tenant,role,locale）"
    ),
    WEB_CONCURRENCY_BROWSER_CRASH_GUARD_ENABLED(
        "serenity.playwright.concurrent.browser.crash.guard.enabled",
        "true",
        "浏览器崩溃韧性守卫：共享 Browser 崩溃时单飞重建并重跑失败任务"
    ),
    WEB_PLAYWRIGHT_CONCURRENT_CRASH_CORROBORATION_ENABLED(
        "serenity.playwright.concurrent.crash.corroboration.enabled",
        "true",
        "崩溃识别启用「异常类型 + Playwright 断开事件」双重佐证（消息签名仍为主信号）"
    ),
    WEB_PLAYWRIGHT_WAITS_RETRY_INTERVAL_DEFAULT_MS(
        "playwright.waits.retry.interval.default.ms",
        "500",
        "条件驱动重试的默认重试间隔（毫秒）"
    ),
    WEB_PLAYWRIGHT_WAITS_RETRY_COUNT(
        "playwright.waits.retry.count",
        "3",
        "PageWaits.retry 默认重试次数"
    ),
    WEB_PLAYWRIGHT_WAITS_RETRY_INTERVAL_MS(
        "playwright.waits.retry.interval.ms",
        "1000",
        "PageWaits.retry 默认重试间隔（毫秒）"
    ),
    WEB_PLAYWRIGHT_WAITS_POLL_STEP_MS(
        "playwright.waits.poll.step.ms",
        "50",
        "PageWaits.waitUntil 固定小步长轮询间隔（毫秒）"
    ),
    WEB_PLAYWRIGHT_BROWSER_STARTUP_BACKOFF_MS(
        "playwright.browser.startup.backoff.ms",
        "2000",
        "浏览器启动重试退避基数（毫秒，随尝试次数线性放大）"
    ),
    WEB_PLAYWRIGHT_CONCURRENT_EXECUTOR_AWAIT_SECONDS(
        "playwright.concurrent.executor.await.seconds",
        "30",
        "并发上下文执行器线程池关闭等待超时（秒）"
    ),
    WEB_PLAYWRIGHT_BROWSERSTACK_CONNECT_TIMEOUT_SECONDS(
        "playwright.browserstack.connect.timeout.seconds",
        "60",
        "BrowserStack 会话连接超时（秒）"
    ),
    WEB_PLAYWRIGHT_BROWSERSTACK_REQUEST_TIMEOUT_MS(
        "playwright.browserstack.request.timeout.ms",
        "30000",
        "BrowserStack API 连接/读取超时（毫秒）"
    ),
    WEB_PLAYWRIGHT_CRASH_GUARD_MAX_REPLAY(
        "playwright.concurrent.crash.guard.max.replay",
        "1",
        "崩溃重跑严格有界次数"
    ),
    WEB_PLAYWRIGHT_CONTEXT_CLOSE_TRACE_TIMEOUT_SECONDS(
        "playwright.context.close.trace.timeout.seconds",
        "15",
        "关闭上下文时 tracing 写盘等待超时（秒）"
    ),
    WEB_PLAYWRIGHT_CONTEXT_STABILIZE_DELAY_MS(
        "playwright.context.stabilize.delay.ms",
        "300",
        "页面稳定化补偿延迟（毫秒）"
    ),
    WEB_PLAYWRIGHT_CONTEXT_HAS_TOUCH(
        "playwright.context.hasTouch",
        "false",
        "禁用触摸功能"
    ),
    WEB_PLAYWRIGHT_CONTEXT_IS_MOBILE(
        "playwright.context.isMobile",
        "false",
        "移动设备模拟"
    ),
    WEB_PLAYWRIGHT_CONTEXT_SCREENSHOT_PATH(
        "playwright.context.screenshotPath",
        "target/screenshots",
        "截图保存路径"
    ),
    WEB_PLAYWRIGHT_CONTEXT_RECORD_VIDEO_ENABLED(
        "playwright.context.recordVideo.enabled",
        "false",
        "录屏功能"
    ),
    WEB_PLAYWRIGHT_CONTEXT_RECORD_VIDEO_DIR(
        "playwright.context.recordVideo.dir",
        "target/videos",
        "录屏保存目录"
    ),
    WEB_PLAYWRIGHT_CONTEXT_TRACE_ENABLED(
        "playwright.context.trace.enabled",
        "true",
        "Trace 功能"
    ),
    WEB_PLAYWRIGHT_CONTEXT_TRACE_CHUNK_PER_SCENARIO(
        "playwright.context.trace.chunk.per.scenario",
        "true",
        "trace 按 scenario 分段（每用例一个文件）"
    ),
    WEB_PLAYWRIGHT_ARTIFACTS_RETENTION_ENABLED(
        "playwright.artifacts.retention.enabled",
        "true",
        "产物（trace/截图）保留治理开关"
    ),
    WEB_PLAYWRIGHT_ARTIFACTS_RETENTION_MAX_TOTAL_MB(
        "playwright.artifacts.retention.max.total.mb",
        "2048",
        "产物目录总上限（MB）"
    ),
    WEB_PLAYWRIGHT_ARTIFACTS_RETENTION_MAX_FILES(
        "playwright.artifacts.retention.max.files",
        "500",
        "产物目录文件数上限"
    ),
    WEB_PLAYWRIGHT_ARTIFACTS_RETENTION_MAX_AGE_DAYS(
        "playwright.artifacts.retention.max.age.days",
        "14",
        "产物保留期（天）"
    ),
    WEB_PLAYWRIGHT_ARTIFACTS_RETENTION_DIRS(
        "playwright.artifacts.retention.dirs",
        "target/site/serenity/traces,target/screenshots,target/screenshots-webp",
        "产物保留治理目录（逗号分隔）"
    ),
    WEB_PLAYWRIGHT_CONTEXT_TRACE_SCREENSHOTS(
        "playwright.context.trace.screenshots",
        "true",
        "Trace 时截图"
    ),
    WEB_PLAYWRIGHT_CONTEXT_TRACE_SNAPSHOTS(
        "playwright.context.trace.snapshots",
        "true",
        "Trace 时快照"
    ),
    WEB_PLAYWRIGHT_CONTEXT_TRACE_SOURCES(
        "playwright.context.trace.sources",
        "true",
        "Trace 时源码"
    ),
    WEB_PLAYWRIGHT_CONTEXT_LOCALE(
        "playwright.context.locale",
        "en-US",
        "Context locale 设置"
    ),
    WEB_PLAYWRIGHT_CONTEXT_TIMEZONE_ID(
        "playwright.context.timezoneId",
        "",
        "Context timezone 设置"
    ),
    WEB_PLAYWRIGHT_CONTEXT_USER_AGENT(
        "playwright.context.userAgent",
        "",
        "Context User-Agent 设置"
    ),
    WEB_PLAYWRIGHT_CONTEXT_PERMISSIONS(
        "playwright.context.permissions",
        "",
        "Context 权限设置（逗号分隔）"
    ),
    WEB_PLAYWRIGHT_CONTEXT_COLOR_SCHEME(
        "playwright.context.colorScheme",
        "light",
        "ColorScheme (light, dark, no-preference)"
    ),
    WEB_PLAYWRIGHT_CONTEXT_GEOLOCATION_LATITUDE(
        "playwright.context.geolocation.latitude",
        "",
        "Geolocation 纬度"
    ),
    WEB_PLAYWRIGHT_CONTEXT_GEOLOCATION_LONGITUDE(
        "playwright.context.geolocation.longitude",
        "",
        "Geolocation 经度"
    ),
    WEB_PLAYWRIGHT_CONTEXT_DEVICE_SCALE_FACTOR(
        "playwright.context.deviceScaleFactor",
        "",
        "设备缩放因子（留空则自动检测）"
    ),
    WEB_PLAYWRIGHT_CONTEXT_PROXY(
        "playwright.context.proxy",
        "",
        "Context 代理服务器"
    ),
    WEB_PLAYWRIGHT_CONTEXT_PROXY_ENABLED(
        "playwright.context.proxy.enabled",
        "false",
        "Context 代理启用开关"
    ),
    WEB_PLAYWRIGHT_PAGE_TIMEOUT(
        "playwright.page.timeout",
        "15000",
        "页面操作超时"
    ),
    WEB_PLAYWRIGHT_PAGE_NAVIGATION_TIMEOUT(
        "playwright.page.navigationTimeout",
        "15000",
        "页面导航超时"
    ),
    WEB_PLAYWRIGHT_PAGE_LOAD_STATE(
        "playwright.page.load.state",
        "DOMCONTENTLOADED",
        "页面加载状态"
    ),
    WEB_PLAYWRIGHT_STABILIZE_WAIT_TIMEOUT(
        "playwright.stabilize.wait.timeout",
        "15000",
        "页面稳定化等待超时"
    ),
    WEB_SERENITY_SCREENSHOT_STRATEGY(
        "serenity.screenshot.strategy",
        "AFTER_EACH_STEP",
        "截图策略"
    ),
    WEB_PLAYWRIGHT_SCREENSHOT_FULLPAGE(
        "playwright.screenshot.fullpage",
        "true",
        "全页截图配置"
    ),
    WEB_PLAYWRIGHT_SCREENSHOT_WAIT_TIMEOUT(
        "playwright.screenshot.wait.timeout",
        "5000",
        "截图等待超时"
    ),
    WEB_PLAYWRIGHT_SCREENSHOT_FULLPAGE_MAX_HEIGHT(
        "playwright.screenshot.fullpage.max.height",
        "12000",
        "全页截图最大滚动高度（像素），<=0 表示不限制"
    ),
    WEB_SERENITY_REPORTS_SCREENSHOTS_DIRECTORY(
        "serenity.reports.screenshots.directory",
        "target/site/serenity",
        "Serenity 报告截图目录"
    ),
    WEB_SERENITY_SCREENSHOTS_DIRECTORY(
        "serenity.screenshots.directory",
        "target/site/serenity",
        "Serenity 截图目录"
    ),
    WEB_PLAYWRIGHT_WEBP_SCREENSHOT_ENABLED(
        "playwright.screenshot.webp.enabled",
        "true",
        "失败截图是否额外落 WebP 到合规归档目录（不影响 Serenity 报告 PNG）"
    ),
    WEB_PLAYWRIGHT_WEBP_SCREENSHOT_QUALITY(
        "playwright.screenshot.webp.quality",
        "80",
        "WebP 合规截图质量 0-100"
    ),
    WEB_PLAYWRIGHT_WEBP_SCREENSHOT_ARCHIVE_DIR(
        "playwright.screenshot.webp.archiveDir",
        "target/screenshots-webp",
        "WebP 合规截图落盘目录（自建归档，与 Serenity 报告目录隔离）"
    ),
    WEB_SERENITY_LOGGING(
        "serenity.logging",
        "VERBOSE",
        "日志级别"
    ),
    WEB_SERENITY_OUTPUT_DIRECTORY(
        "serenity.outputDirectory",
        "target/site/serenity",
        "报告输出目录"
    ),
    WEB_SERENITY_FEATURES_ROOT(
        "serenity.features.root",
        "src/test/resources/features",
        "特性文件根目录"
    ),
    WEB_SERENITY_REQUIREMENTS_BASE(
        "serenity.requirements.base",
        "src/test/resources/features",
        "需求根目录"
    ),
    WEB_SERENITY_BROWSER(
        "serenity.browser",
        "playwright",
        "浏览器配置"
    ),
    WEB_SERENITY_PLAYWRIGHT_RESTART_BROWSER_FOR_EACH(
        "serenity.playwright.restart.browser.for.each",
        "scenario",
        "浏览器重启策略"
    ),
    WEB_SERENITY_PLAYWRIGHT_REUSE_CONTEXT_WITHIN_FEATURE(
        "serenity.playwright.reuse.context.within.feature",
        "false",
        "同一 feature 内复用同一 Context/Page（默认关；开启=1 窗口/feature，需场景间无状态依赖）"
    ),
    WEB_SERENITY_RERUN_FAILURES_WAIT_TIME(
        "serenity.rerun.failures.wait.time",
        "1000",
        "失败测试重跑等待时间（毫秒）"
    ),
    WEB_SERENITY_RETRY_DELAY_STRATEGY(
        "serenity.retry.delay.strategy",
        "fixed",
        "重试延迟策略（fixed, exponential）"
    ),
    WEB_SERENITY_RETRY_DELAY_BASE(
        "serenity.retry.delay.base",
        "1000",
        "指数退避基础延迟（毫秒）"
    ),
    WEB_SERENITY_RETRY_DELAY_MAX(
        "serenity.retry.delay.max",
        "30000",
        "指数退避最大延迟（毫秒）"
    ),
    WEB_SERENITY_RETRY_DELAY_MULTIPLIER(
        "serenity.retry.delay.multiplier",
        "2.0",
        "指数退避乘数"
    ),
    WEB_API_MOCK_ENABLED(
        "api.mock.enabled",
        "false",
        "全局启用 API Mock"
    ),
    WEB_API_MOCK_RULES_DIRECTORY(
        "api.mock.rules.directory",
        "src/test/resources/mocks",
        "Mock 规则配置目录"
    ),
    WEB_API_MOCK_LOG_LEVEL(
        "api.mock.log.level",
        "INFO",
        "Mock 日志级别"
    ),
    WEB_PLAYWRIGHT_DRIVER_PATH(
        "playwright.driver.path",
        ".playwright/driver",
        "Playwright Driver 路径"
    ),
    WEB_PLAYWRIGHT_SDK_PATH(
        "playwright.sdk.path",
        ".playwright/sdk",
        "Playwright SDK 目录"
    ),
    WEB_PLAYWRIGHT_DRIVER_TMPDIR(
        "playwright.driver.tmpdir",
        ".playwright/driver",
        "Playwright Driver 临时目录"
    ),
    WEB_PLAYWRIGHT_BROWSERS_PATH(
        "playwright.browsers.path",
        ".playwright/browsers",
        "Playwright browser path"
    ),
    WEB_PLAYWRIGHT_DOWNLOAD_PROXY_ENABLED(
        "playwright.download.proxy.enabled",
        "false",
        "浏览器下载代理启用开关"
    ),
    WEB_PLAYWRIGHT_BROWSER_DOWNLOAD_HTTP_PROXY(
        "playwright.browser.download.http.proxy",
        "",
        "浏览器下载 HTTP 代理地址"
    ),
    WEB_PLAYWRIGHT_BROWSER_DOWNLOAD_HTTPS_PROXY(
        "playwright.browser.download.https.proxy",
        "",
        "浏览器下载 HTTPS 代理地址"
    ),
    WEB_PLAYWRIGHT_NO_LOGIN_SESSION_TIMEOUT(
        "playwright.no.login.session.timeout.minutes",
        "5",
        "No login session timeout (minutes)"
    ),
    WEB_PLAYWRIGHT_NO_LOGIN_SINGLE_FLIGHT_TIMEOUT_MS(
        "playwright.no.login.single.flight.timeout.ms",
        "60000",
        "No login single-flight wait timeout (ms)"
    ),
    WEB_PLAYWRIGHT_ELEMENT_WAIT_TIMEOUT(
        "playwright.element.wait.timeout",
        "15000",
        "元素等待/操作超时时间（毫秒，用于查询和操作的统一超时）"
    ),
    WEB_PLAYWRIGHT_POLLING_INTERVAL(
        "playwright.polling.interval",
        "500",
        "轮询间隔（毫秒）"
    ),
    WEB_AXE_SCAN_ENABLED(
        "axe.scan.enabled",
        "false",
        "是否启用 axe-core 扫描"
    ),
    WEB_AXE_SCAN_TAGS(
        "axe.scan.tags",
        "",
        "Axe-core WCAG 标签"
    ),
    WEB_AXE_SCAN_OUTPUT_DIR(
        "axe.scan.outputDir",
        "target/accessibility-axe",
        "Axe-core 报告输出目录"
    ),
    WEB_PLAYWRIGHT_ELEMENT_ACTION_POST_DELAY(
        "playwright.element.action.post.delay",
        "200",
        "元素操作后等待时间（毫秒）"
    ),
    WEB_PLAYWRIGHT_ELEMENT_OPERATION_TIMEOUT(
        "playwright.element.operation.timeout",
        "30000",
        "元素操作总超时时间（毫秒）"
    ),
    WEB_PLAYWRIGHT_ELEMENT_SCREENSHOT_ON_FAILURE(
        "playwright.element.screenshot.on.failure",
        "true",
        "失败时是否自动截图"
    ),
    WEB_PLAYWRIGHT_ELEMENT_DIAGNOSTICS_DETAILED(
        "playwright.element.diagnostics.detailed",
        "true",
        "是否收集详细诊断信息"
    ),
    WEB_PLAYWRIGHT_ELEMENT_SCREENSHOT_PATH(
        "playwright.element.screenshot.path",
        "target/screenshots",
        "失败截图保存路径"
    ),
    WEB_API_ASSERTION_WAIT_TIMEOUT(
        "api.assertion.wait.timeout.ms",
        "15000",
        "API 断言等待超时（毫秒）"
    ),
    WEB_PLAYWRIGHT_PROXY_HTTP(
        "playwright.proxy.http",
        "",
        "HTTP 代理地址（host:port）"
    ),
    WEB_PLAYWRIGHT_PROXY_HTTPS(
        "playwright.proxy.https",
        "",
        "HTTPS 代理地址（host:port）"
    ),
    WEB_PLAYWRIGHT_PROXY_HTTP_USERNAME(
        "playwright.proxy.http.username",
        "",
        "HTTP 代理用户名"
    ),
    WEB_PLAYWRIGHT_PROXY_HTTP_PASSWORD(
        "playwright.proxy.http.password",
        "",
        "HTTP 代理密码"
    ),
    WEB_PLAYWRIGHT_PROXY_HTTPS_USERNAME(
        "playwright.proxy.https.username",
        "",
        "HTTPS 代理用户名"
    ),
    WEB_PLAYWRIGHT_PROXY_HTTPS_PASSWORD(
        "playwright.proxy.https.password",
        "",
        "HTTPS 代理密码"
    ),
    WEB_PLAYWRIGHT_BROWSER_DOWNLOAD_TIMEOUT_MINUTES(
        "playwright.browser.download.timeout.minutes",
        "5",
        "浏览器下载超时时间（分钟）"
    ),
    WEB_SENSITIVE_DATA_EXTRA_HEADER_KEYS(
        "sensitive.data.extra.header.keys",
        "",
        "附加敏感头名（逗号分隔），叠加在内置脱敏清单之上"
    ),
    WEB_SENSITIVE_DATA_EXTRA_BODY_KEYS(
        "sensitive.data.extra.body.keys",
        "",
        "附加敏感体字段名（逗号分隔），叠加在内置脱敏清单之上"
    ),
    WEB_SENSITIVE_DATA_EXTRA_QUERY_KEYS(
        "sensitive.data.extra.query.keys",
        "",
        "附加敏感 URL query 参数名（逗号分隔），叠加在内置脱敏清单之上"
    ),

    /**
     * 并发执行器硬上限（HARD CAP）：同时运行任务数的绝对上限。
     * <p>默认为<b>运行期自适应值</b>——CPU 核数 / 2，钳制于 [2, 32]（见 {@link #adaptiveConcurrentMaxValue()}），
     * 可经 {@code -Dserenity.playwright.concurrent.max} / serenity.properties 覆盖。
     */
    WEB_PLAYWRIGHT_CONCURRENT_MAX(
        "serenity.playwright.concurrent.max",
        adaptiveConcurrentMaxValue(),
        "并发执行器硬上限（HARD CAP）：同时运行任务数的绝对上限，按 CPU 核数自适应，可经 -D / serenity.properties 覆盖"
    ),

    /**
     * 崩溃型失败消息特征白名单（{@code |} 分隔，大小写不敏感）。
     * <p>仅列入明确的浏览器/进程崩溃信号，不含会被正常业务流程触发的内容，避免误判掩盖真实缺陷。
     */
    WEB_PLAYWRIGHT_CONCURRENT_CRASH_SIGNATURES(
        "serenity.playwright.concurrent.crash.signatures",
        "target crashed|browser has been closed|browser is closed|target page, context or browser has been closed"
            + "|target closed|connection closed|connection prematurely closed|playwright has been closed|browser disconnected|browser crashed",
        "崩溃型失败消息特征白名单（| 分隔，大小写不敏感），用于崩溃识别"
    ),

    // ===================== API / HTTP（对应 ApiFrameworkConfig）=====================

    API_HTTP_CONNECTION_TIMEOUT(
        "http.connection.timeout",
        "30000",
        "HTTP 连接超时（毫秒）"
    ),
    API_HTTP_SOCKET_TIMEOUT_FALLBACK(
        "http.socket.timeout.fallback",
        "15000",
        "HTTP socket 超时回退值（毫秒）"
    ),
    API_HTTP_SOCKET_TIMEOUT(
        "http.socket.timeout",
        "30000",
        "HTTP socket 超时（毫秒）"
    ),
    API_HTTP_MAX_CONNECTIONS_TOTAL(
        "http.connection.pool.max-total",
        "200",
        "HTTP 连接池最大总连接数"
    ),
    API_HTTP_MAX_CONNECTIONS_PER_ROUTE(
        "http.connection.pool.max-per-route",
        "20",
        "HTTP 连接池每路由最大连接数"
    ),
    API_HTTP_SSL_RELAX_VALIDATION(
        "http.ssl.relax-validation",
        "false",
        "是否放宽 SSL 校验（默认 false，安全优先）"
    ),
    API_JSON_FAIL_ON_UNKNOWN_PROPERTIES(
        "json.fail-on-unknown-properties",
        "false",
        "JSON 解析遇未知属性是否失败"
    ),
    API_JSON_ACCEPT_SINGLE_VALUE_AS_ARRAY(
        "json.accept-single-value-as-array",
        "true",
        "JSON 单值是否接受为数组"
    ),
    API_JSON_IGNORE_NULL_FOR_PRIMITIVES(
        "json.ignore-null-for-primitives",
        "true",
        "JSON 原始类型是否忽略 null"
    ),
    API_LOGGING_ROOT_LEVEL(
        "logging.root.level",
        "INFO",
        "根日志级别"
    ),
    API_LOGGING_CONSOLE_PATTERN(
        "logging.console.pattern",
        "%d{HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n",
        "控制台日志格式"
    ),
    API_LOGGING_FILE_ENABLED(
        "logging.file.enabled",
        "false",
        "是否启用文件日志"
    ),
    API_LOGGING_FILE_PATTERN(
        "logging.file.pattern",
        "%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n",
        "文件日志格式"
    ),
    API_API_BASE_URI_DEFAULT(
        "api.base.uri.default",
        "http://localhost",
        "API 默认 base URI"
    ),
    API_TEST_RETRY_COUNT(
        "test.retry.count",
        "3",
        "测试重试次数"
    ),
    API_TEST_RETRY_DELAY(
        "test.retry.delay",
        "1000",
        "测试重试延迟（毫秒）"
    ),
    API_SERENITY_OUTPUT_DIRECTORY(
        "serenity.output-directory",
        "target/site/serenity",
        "Serenity 输出目录"
    ),
    API_SERENITY_HISTORY_FOLDER(
        "serenity.history.folder",
        "target/site/serenity/history",
        "Serenity 历史目录"
    ),
    API_API_REQUEST_RESPONSE_LOGS_ENABLED(
        "api.request.response.logging.enabled",
        "false",
        "API 请求/响应日志是否启用（默认关闭，避免明文泄露）"
    ),

    /** 默认文件编码（原引用 API 模块 {@code Constants.UTF_EIGHT}，收敛后取其字面量值 {@code UTF-8}）。 */
    API_FILE_ENCODING_DEFAULT(
        "file.encoding.default",
        "UTF-8",
        "默认文件编码"
    ),

    /** payload 文件编码（原引用 API 模块 {@code Constants.UTF_EIGHT}，收敛后取其字面量值 {@code UTF-8}）。 */
    API_FILE_ENCODING_PAYLOAD(
        "file.encoding.payload",
        "UTF-8",
        "payload 文件编码"
    ),

    // ===================== Monitor / 持久化（对应 MonitorConfig）=====================

    /** API 捕获响应体最大体积（MB），超出即截断。 */
    API_CAPTURE_MAX_RESPONSE_SIZE_MB("api.capture.max.response.size.mb", "50", "API 捕获响应体最大体积（MB），超出即截断"),

    /** Monitor API 响应是否持久化到数据库。 */
    MONITOR_DB_STORE_ENABLED("monitor.db.store.enabled", "false", "Monitor API 响应数据库持久化"),

    /** Monitor 数据库方言类型（如 MYSQL / H2）。 */
    MONITOR_DB_TYPE("monitor.db.type", "", "Monitor 数据库方言类型（MYSQL / H2 等）"),

    /** Monitor 数据库 JDBC URL。 */
    MONITOR_DB_URL("monitor.db.url", "", "Monitor 数据库 JDBC URL"),

    /** Monitor 数据库用户名。 */
    MONITOR_DB_USER("monitor.db.user", "", "Monitor 数据库用户名"),

    /** Monitor 数据库密码（支持 {@code ENC()} 透明解密）。 */
    MONITOR_DB_PASSWORD("monitor.db.password", "", "Monitor 数据库密码（支持 ENC() 透明解密）"),

    /** Monitor 数据库连接池最大连接数。 */
    MONITOR_DB_POOL_MAX_SIZE("monitor.db.pool.max.size", "5", "Monitor 数据库连接池最大连接数"),

    /** Monitor 记录关联的测试运行 ID。 */
    MONITOR_TEST_RUN_ID("monitor.test.run.id", "", "Monitor 记录关联的测试运行 ID"),

    /** Body 读取重试调度线程数（原为单线程且被所有 context 共享，并行下为串行瓶颈）。 */
    MONITOR_BODY_READ_SCHEDULER_THREADS(
            "monitor.body.read.scheduler.threads", "4", "Body 读取重试调度线程数（跨 context 共享）"),

    /** Body 读取并发上限：{@code res.body()} 是 CDP 协议往返（Network.getResponseBody）。 */
    MONITOR_BODY_READ_CONCURRENCY(
            "monitor.body.read.concurrency", "16", "Body 读取并发上限（CDP 往返并发，过小会成为致命瓶颈）"),

    /** 即时读体协调池线程数：承载「响应到达即读 body」的协调任务。 */
    MONITOR_BODY_CAPTURE_THREADS(
            "monitor.body.capture.threads", "16", "即时读体协调池线程数（响应到达即读 body）"),

    /** Body 读取基础尝试次数（不含按重试间隔推导的额外次数）。 */
    MONITOR_BODY_READ_BASE_ATTEMPTS("monitor.body.read.base.attempts", "3", "Body 读取基础尝试次数"),

    /** Body 读取重试间隔（毫秒）。 */
    MONITOR_BODY_READ_RETRY_INTERVAL_MS("monitor.body.read.retry.interval.ms", "50", "Body 读取重试间隔（毫秒）"),

    /** Body 读取等待预算上限（毫秒）；{@code <=0} 表示不设上限。 */
    MONITOR_BODY_READ_MAX_WAIT_MS(
            "monitor.body.read.max.wait.ms", "30000", "Body 读取等待预算上限（毫秒，<=0 表示不设上限）"),

    /** Monitor / Mock / Modify 超时上限（毫秒）：防御性 sanity cap。 */
    MONITOR_TIMEOUT_MAX_MS(
            "monitor.timeout.max.ms", "300000", "Monitor/Mock/Modify 超时上限（毫秒，防御性 sanity cap）"),

    /** MonitorHandler 观测执行器线程数。 */
    MONITOR_OBSERVE_THREADS("monitor.observe.threads", "8", "MonitorHandler 观测执行器线程数"),

    /** MonitorHandler 观测执行器有界队列容量；队列满即拒绝并放行请求（绝不反压事件线程）。 */
    MONITOR_OBSERVE_QUEUE_CAPACITY(
            "monitor.observe.queue.capacity", "4096", "MonitorHandler 观测执行器有界队列容量"),

    /** Monitor API 响应是否持久化到文件。 */
    MONITOR_FILE_STORE_ENABLED("monitor.file.store.enabled", "false", "Monitor API 响应文件持久化"),

    /** Monitor 文件存储目录。 */
    MONITOR_FILE_STORE_DIR("monitor.file.store.dir", "target/monitor-output", "Monitor 文件存储目录"),

    /** Monitor 文件存储是否美化输出（pretty-print JSON）。 */
    MONITOR_FILE_STORE_PRETTY("monitor.file.store.pretty", "true", "Monitor 文件存储是否美化输出"),

    /** 文件存储异步写盘队列容量；队列满即丢弃并计数告警，绝不阻塞事件线程。 */
    MONITOR_FILE_STORE_WRITE_QUEUE_CAPACITY(
            "monitor.file.store.write.queue.capacity", "4096", "文件存储异步写盘队列容量"),

    /** 文件存储是否按场景分组。 */
    MONITOR_FILE_STORE_GROUP_BY_SCENARIO(
            "monitor.file.store.group.by.scenario", "true", "文件存储是否按场景分组");

    private final String key;
    private final String defaultValue;
    private final String description;

    ConfigKeys(String key, String defaultValue, String description) {
        this.key = key;
        this.defaultValue = defaultValue;
        this.description = description;
    }

    public String key() {
        return key;
    }

    public String defaultValue() {
        return defaultValue;
    }

    public String description() {
        return description;
    }

    /**
     * 转为共享 {@link ConfigKey} 元数据三元组（key / 默认 / 描述）。
     * <p>供 API 侧门面（{@code ApiFrameworkConfig}）以 {@code ConfigKey} 形态复用本注册表定义。
     */
    public ConfigKey toConfigKey() {
        return new ConfigKey(key, defaultValue, description);
    }

    /** 全框架配置键清单（供审计/文档/重复校验）。 */
    public static List<ConfigKeys> all() {
        List<ConfigKeys> list = new ArrayList<>();
        for (ConfigKeys c : values()) {
            list.add(c);
        }
        return list;
    }

    /**
     * 并发硬上限自适应默认值：CPU 核数 / 2，下限 2、上限 32。
     * <p>作为 {@link #WEB_PLAYWRIGHT_CONCURRENT_MAX} 的默认值，使「零配置」时按运行机核数自适应，
     * 同时保留经 -D / serenity.properties 显式覆盖的能力。类加载时求值一次。
     */
    private static String adaptiveConcurrentMaxValue() {
        int cores = Runtime.getRuntime().availableProcessors();
        return String.valueOf(Math.max(2, Math.min(32, cores / 2)));
    }

    // ===================== 静态校验（C-6 / M-2 核心痛点：键名无静态校验）=====================
    // 类加载即校验全部键唯一，重复键立即 fail-fast，避免新增配置键时键名冲突被静默吞掉。
    static {
        validateUniqueKeys();
    }

    private static void validateUniqueKeys() {
        Set<String> seen = new java.util.HashSet<>();
        for (ConfigKeys c : values()) {
            if (!seen.add(c.key)) {
                throw new IllegalStateException(
                        "ConfigKeys 注册表存在重复配置键: '" + c.key + "'"
                                + " —— 新增配置键前请先查重，避免键名冲突（C-6 / M-2）");
            }
        }
    }
}
