package com.hsbc.cmb.hk.dbb.automation.framework.web.config;

import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.concurrent.ContextTaskResult;

import com.hsbc.cmb.hk.dbb.automation.framework.common.config.ConfigKey;
import com.hsbc.cmb.hk.dbb.automation.framework.common.config.ConfigKeys;
import com.hsbc.cmb.hk.dbb.automation.framework.common.config.ConfigSource;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Framework Configuration Enum - Centralized management of all configuration items
 * Usage:
 * WebFrameworkConfig.SCREENSHOT_STRATEGY.getValue()
 * WebFrameworkConfig.BROWSER_RESTART_STRATEGY.getValue()
 *
 * <p>⚠️ 同名类消歧（P3-31）：本类专管 <b>Web/Playwright</b> 侧配置（浏览器类型、headless、
 * Context/Page 策略、截图、BrowserStack、路由采集等），服务 {@code framework.web.*}。
 *
 * <p>另有一个同名类 {@code com.hsbc.cmb.hk.dbb.automation.framework.api.config.ApiFrameworkConfig}，
 * 专管 <b>API/HTTP</b> 侧配置（连接超时、socket 超时、SSL 校验等），服务 {@code framework.api.*}。
 *
 * <p>两者<b>刻意不合并</b>：api 与 web 是两个独立模块边界，合并会迫使其中一方依赖另一方
 * （当前 api 与 web 之间仅存在少量工具类引用，不应再引入配置层耦合）。
 * 若在某个类中同时用到两者，请使用全限定名或 static import 别名以规避同名冲突。
 */
public enum WebFrameworkConfig {

    // ==================== Serenity 核心配置 ====================

    /**
     * 项目名称
     */
    SERENITY_PROJECT_NAME(ConfigKeys.WEB_SERENITY_PROJECT_NAME),


    /**
     * 编码设置
     */
    SERENITY_ENCODING(ConfigKeys.WEB_SERENITY_ENCODING),

    /**
     * 报告编码设置
     */
    SERENITY_REPORT_ENCODING(ConfigKeys.WEB_SERENITY_REPORT_ENCODING),

    // ==================== Playwright 浏览器配置 ====================

    /**
     * 浏览器类型
     * chromium - Chromium 浏览器
     * firefox - Firefox 浏览器
     * webkit - WebKit 浏览器（Safari）
     */
    PLAYWRIGHT_BROWSER_TYPE(ConfigKeys.WEB_PLAYWRIGHT_BROWSER_TYPE),

    /**
     * 页面未捕获 JS 异常（page.onPageError）是否触发测试失败。
     * 默认 false：仅记录 error 级日志；设为 true 时，未捕获异常会在步骤结束时经
     * Serenity StepListener 标记测试失败并抛出，便于前端脚本错误即时暴露。
     */
    PLAYWRIGHT_PAGE_ERROR_FAIL(ConfigKeys.WEB_PLAYWRIGHT_PAGE_ERROR_FAIL),

    /**
     * 浏览器模式
     * true - 无头模式（后台运行）
     * false - 有头模式（显示浏览器窗口）
     */
    PLAYWRIGHT_BROWSER_HEADLESS(ConfigKeys.WEB_PLAYWRIGHT_BROWSER_HEADLESS),

    /**
     * 浏览器 channel - 使用本地安装的浏览器
     * chrome - 使用本地 Chrome 浏览器
     * msedge - 使用本地 Edge 浏览器
     * 不设置或设置为空 - 使用 Playwright 下载的浏览器
     * 
     * 注意：channel 仅适用于 Chromium 系列浏览器（Chrome、Edge）
     * 对于 Firefox，请使用 executablePath 指定本地浏览器路径
     */
    PLAYWRIGHT_BROWSER_CHANNEL(ConfigKeys.WEB_PLAYWRIGHT_BROWSER_CHANNEL),

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
    PLAYWRIGHT_BROWSER_CHROME_EXECUTABLE_PATH(ConfigKeys.WEB_PLAYWRIGHT_BROWSER_CHROME_EXECUTABLE_PATH),

    /**
     * Edge 浏览器可执行文件路径
     * 如果设置，当浏览器类型为 chromium 且 channel 为 msedge 时会使用此路径
     * 优先级高于 channel 配置
     * 
     * Windows 示例: "C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe"
     * macOS 示例: "/Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge"
     * Linux 示例: "/usr/bin/microsoft-edge"
     */
    PLAYWRIGHT_BROWSER_EDGE_EXECUTABLE_PATH(ConfigKeys.WEB_PLAYWRIGHT_BROWSER_EDGE_EXECUTABLE_PATH),

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
    PLAYWRIGHT_BROWSER_FIREFOX_ARGS(ConfigKeys.WEB_PLAYWRIGHT_BROWSER_FIREFOX_ARGS),

    /**
     * Chrome 浏览器启动参数（逗号分隔）
     * 如果设置，当浏览器类型为 chromium 且 channel 为 chrome 时会使用此参数
     * 
     * 示例: "--disable-blink-features=AutomationControlled,--disable-pinch,--start-maximized"
     * 
     * 注意：反后台节流 flags 已硬编码到 PlaywrightConfigManager，会自动追加，无需在此配置。
     */
    PLAYWRIGHT_BROWSER_CHROME_ARGS(ConfigKeys.WEB_PLAYWRIGHT_BROWSER_CHROME_ARGS),

    /**
     * Edge 浏览器启动参数（逗号分隔）
     * 如果设置，当浏览器类型为 chromium 且 channel 为 msedge 时会使用此参数
     * 
     * 示例: "--disable-blink-features=AutomationControlled,--disable-pinch,--start-maximized"
     */
    PLAYWRIGHT_BROWSER_EDGE_ARGS(ConfigKeys.WEB_PLAYWRIGHT_BROWSER_EDGE_ARGS),

    /**
     * Chromium 浏览器启动参数（逗号分隔）
     * 如果设置，当浏览器类型为 chromium 且未指定 channel 时会使用此参数
     * 
     * 示例: "--disable-blink-features=AutomationControlled,--disable-pinch"
     */
    PLAYWRIGHT_BROWSER_CHROMIUM_ARGS(ConfigKeys.WEB_PLAYWRIGHT_BROWSER_CHROMIUM_ARGS),

    /**
     * WebKit 浏览器启动参数（逗号分隔）
     * 如果设置，当浏览器类型为 webkit 时会使用此参数
     * 
     * 注意：WebKit 必须使用 Playwright 编译的版本
     * 
     * 示例: "--disable-web-security"
     */
    PLAYWRIGHT_BROWSER_WEBKIT_ARGS(ConfigKeys.WEB_PLAYWRIGHT_BROWSER_WEBKIT_ARGS),

    /**
     * 浏览器操作慢动作延迟（毫秒），用于调试
     */
    PLAYWRIGHT_BROWSER_SLOWMO(ConfigKeys.WEB_PLAYWRIGHT_BROWSER_SLOWMO),

    /**
     * 浏览器超时设置（毫秒）
     */
    PLAYWRIGHT_BROWSER_TIMEOUT(ConfigKeys.WEB_PLAYWRIGHT_BROWSER_TIMEOUT),

    /**
     * 下载文件保存<b>根</b>目录；实际文件按线程落在 {@code thread-<id>} 子目录
     * （见 {@code PlaywrightManager.downloadDirectoryForCurrentThread()}）——
     * 使每个用例收尾只清理自己线程的下载文件，并行下不干扰邻居用例。
     */
    PLAYWRIGHT_BROWSER_DOWNLOADS_PATH(ConfigKeys.WEB_PLAYWRIGHT_BROWSER_DOWNLOADS_PATH),

    // ==================== BrowserStack 云测试配置 ====================

    /**
     * 是否启用 BrowserStack
     */
    BROWSERSTACK_ENABLED(ConfigKeys.WEB_BROWSERSTACK_ENABLED),

    /**
     * BrowserStack 用户名
     */
    BROWSERSTACK_USERNAME(ConfigKeys.WEB_BROWSERSTACK_USERNAME),

    /**
     * BrowserStack 访问密钥
     */
    BROWSERSTACK_ACCESS_KEY(ConfigKeys.WEB_BROWSERSTACK_ACCESS_KEY),

    /**
     * BrowserStack 会话名称
     */
    BROWSERSTACK_SESSION_NAME(ConfigKeys.WEB_BROWSERSTACK_SESSION_NAME),

    /**
     * BrowserStack 操作系统
     */
    /**
     * BrowserStack 浏览器名称（chrome / firefox / webkit / edge）。
     * <p>BrowserStack 云端支持 Chromium (CDP)、Firefox 和 WebKit (Playwright 自有协议) 三种引擎。
     */
    BROWSERSTACK_BROWSER_NAME(ConfigKeys.WEB_BROWSERSTACK_BROWSER_NAME),

    BROWSERSTACK_OS(ConfigKeys.WEB_BROWSERSTACK_OS),

    /**
     * BrowserStack 操作系统版本
     */
    BROWSERSTACK_OS_VERSION(ConfigKeys.WEB_BROWSERSTACK_OS_VERSION),

    /**
     * BrowserStack 浏览器版本
     */
    BROWSERSTACK_BROWSER_VERSION(ConfigKeys.WEB_BROWSERSTACK_BROWSER_VERSION),

    /**
     * BrowserStack 超时设置（秒）
     */
    BROWSERSTACK_TIMEOUT(ConfigKeys.WEB_BROWSERSTACK_TIMEOUT),

    /**
     * BrowserStack 调试模式
     */
    BROWSERSTACK_DEBUG(ConfigKeys.WEB_BROWSERSTACK_DEBUG),

    /**
     * BrowserStack 网络日志
     */
    BROWSERSTACK_NETWORK_LOGS(ConfigKeys.WEB_BROWSERSTACK_NETWORK_LOGS),

    /**
     * BrowserStack 视频录制
     */
    BROWSERSTACK_VIDEO(ConfigKeys.WEB_BROWSERSTACK_VIDEO),

    // ==================== BrowserStack Local Testing ====================

    /**
     * BrowserStack Local Testing 开关。
     * <p>启用后，BrowserStack 云端浏览器可通过安全隧道访问内网/本地应用。
     * <p>需要 BrowserStack Local 二进制文件（见 {@link #BROWSERSTACK_LOCAL_PATH}）。
     * <p>启用后自动设置 {@code browserstack.local.force.local=true}，
     * CDP/Playwright WebSocket 流量强制走 Local 隧道（官方原生方案，零额外组件）。
     */
    BROWSERSTACK_LOCAL(ConfigKeys.WEB_BROWSERSTACK_LOCAL),

    /**
     * BrowserStack Local 隧道标识符（多构建并行时区分隧道）。
     * <p>留空则自动生成 {@code automation_<timestamp>}。
     */
    BROWSERSTACK_LOCAL_IDENTIFIER(ConfigKeys.WEB_BROWSERSTACK_LOCAL_IDENTIFIER),

    /**
     * BrowserStack Local 二进制文件路径。
     * <p>Windows: {@code BrowserStackLocal.exe}，Linux/Mac: {@code BrowserStackLocal}
     * <p>可从 https://www.browserstack.com/local-testing/automate 下载，放到项目工具目录或系统 PATH。
     * <p>留空则在 PATH 中查找。
     */
    BROWSERSTACK_LOCAL_PATH(ConfigKeys.WEB_BROWSERSTACK_LOCAL_PATH),

    /**
     * BrowserStack Local 启动超时（秒）。
     * <p>隧道建立完成前最多等待此时间。
     */
    BROWSERSTACK_LOCAL_TIMEOUT(ConfigKeys.WEB_BROWSERSTACK_LOCAL_TIMEOUT),

    /**
     * BrowserStack CDP WebSocket 端点域名。
     * <p>默认 {@code cdp.browserstack.com}。公司代理环境下如果该域名无法 DNS 解析，
     * 可尝试改为 {@code hub.browserstack.com}（部分企业网络允许），
     * 或在系统 hosts 文件中添加静态映射。
     * <p>仅影响 Playwright {@code browserType.connect()} 的 wss:// 连接地址。
     * 不影响 BrowserStack Local tunnel 的连接（tunnel 使用自己的控制通道）。
     */
    BROWSERSTACK_CDP_ENDPOINT(ConfigKeys.WEB_BROWSERSTACK_CDP_ENDPOINT),


    // ==================== Playwright 窗口配置 ====================

    /**
     * 窗口最大化
     */
    PLAYWRIGHT_WINDOW_MAXIMIZE(ConfigKeys.WEB_PLAYWRIGHT_WINDOW_MAXIMIZE),

    /**
     * 窗口最大化参数
     */
    PLAYWRIGHT_WINDOW_MAXIMIZE_ARGS(ConfigKeys.WEB_PLAYWRIGHT_WINDOW_MAXIMIZE_ARGS),

    /*
     *  （移除留痕，2026-09-17 评审）：此处原为「共享 Browser 模式（opt-in）」配置项的 javadoc ——
     *  该配置项（PLAYWRIGHT_SHARED_BROWSER_ENABLED）已随 W-7/DEV-V3 从框架彻底移除，此处只剩**无主注释**，
     *  却仍在文档层面暗示"该开关存在"，属误导性死文本，故删除（仅留此留痕）。
     *
     *  <p>权威结论：Playwright for Java 非线程安全（{@code Playwright}/{@code Browser}/{@code BrowserContext}/
     *  {@code Page} 必须在创建线程调用），跨线程共享单 Browser 会损坏客户端对象注册表
     *  （{@code __adopt__} / {@code pausedStateChanged}，见 playwright-java#1184）。因此框架<b>不提供</b>
     *  共享 Browser 开关：{@code BrowserRegistry.keyFor} 恒为 {@code "<threadId>:<configId>"}，
     *  并发统一为「N 并行 = N 线程 = N Browser」。详见 doc03 §2.2 与 doc10 §5.5。
     */

    /**
     * 并发上下文执行器并行度（{@code ConcurrentContextExecutor}）。
     * 控制同时运行的独立 BrowserContext 任务数；执行器内部再与任务数、硬上限（默认 16）取 min。
     */
    PLAYWRIGHT_CONCURRENT_PARALLELISM(ConfigKeys.WEB_PLAYWRIGHT_CONCURRENT_PARALLELISM),

    /**
     * 单任务超时（秒）。{@code ConcurrentContextExecutor} 对单个任务做 best-effort 超时兜底，
     * 超时后 cancel(true) 中断任务线程；真实耗时上限仍依赖框架既有 navigation / element 超时。
     */
    PLAYWRIGHT_CONCURRENT_TASK_TIMEOUT_SECONDS(ConfigKeys.WEB_PLAYWRIGHT_CONCURRENT_TASK_TIMEOUT_SECONDS),

    /**
     * 并发执行器是否启用虚拟线程（JDK 21+）。
     * 默认关闭：沿用平台线程池。启用前须先审计 {@code BasePage} 同步 API 与长持锁的
     * carrier 线程 pinning 风险（并发方案 R6），确认无 {@code Object.wait} 长持锁；
     * 另需保证运行环境为 JDK 21+，否则框架应安全降级为平台线程（见 {@code ConcurrentContextExecutor}）。
     */
    PLAYWRIGHT_CONCURRENT_USE_VIRTUAL_THREADS(ConfigKeys.WEB_PLAYWRIGHT_CONCURRENT_USE_VIRTUAL_THREADS),

    /**
     * 并发执行器硬上限（HARD CAP）。
     * 与 {@link #PLAYWRIGHT_CONCURRENT_PARALLELISM} 同为并发度闸门：
     * {@code ConcurrentContextOptions#resolvedParallelism} 取 min(taskCount, parallelism, max)。
     * <p>默认按 CPU 核数自适应（核/2，下限 2、上限 32），可经 serenity.properties 或
     * -Dserenity.playwright.concurrent.max 覆盖；CI 低核机应显式调小以防资源耗尽。
     */
    PLAYWRIGHT_CONCURRENT_MAX(ConfigKeys.WEB_PLAYWRIGHT_CONCURRENT_MAX),

    /**
     * SSO 感知并发（按身份分区互斥）总开关。
     *
     * <p><b>三态</b>：
     * <ul>
     *   <li>{@code auto}（默认）：当引擎级并行开启（{@code cucumber.execution.parallel.enabled}
     *       或 {@code junit.jupiter.execution.parallel.enabled} 为 true）时自动启用；串行运行时为 no-op；</li>
     *   <li>{@code true} / {@code false}：显式强制开 / 关（{@code false} 为逃生舱）。</li>
     * </ul>
     *
     * <p><b>为什么默认 auto</b>：并行下「同一身份（sessionKey）被两个 scenario 同时使用」会互相踩踏
     * （SSO 单会话互踢、storageState 覆写），且现象随机难查。默认在并行时启用，使
     * <b>「同一 sessionKey 串行、不同 sessionKey 并行」成为并行的默认语义</b>；串行下恒为 no-op、零回归。
     */
    CONCURRENCY_PARTITION_ENABLED(ConfigKeys.WEB_CONCURRENCY_PARTITION_ENABLED),

    /**
     * 每个并发分区键允许的并发许可数。
     * 默认 1（严格互斥）；某些环境允许同身份 N 路并发时可调大。
     */
    /**
     * 单次进入闸门的<b>最大等待</b>（毫秒，默认 3 分钟；{@code 0} = 无限等待/旧行为）。
     *
     * <p>取 3 分钟：正常环境登录远低于此值；而对「上游长时间无响应」的持有者，等待方最迟 3 分钟即
     * 降级放行，避免整套被一个卡住的身份拖到几十分钟（实测 10 分钟上限会让全量运行长达 8~9 分钟以上）。
     *
     * <p><b>为什么必须有界</b>：若某场景获取许可后未配对释放（许可泄漏），同身份的后续场景会
     * <b>永久</b> park 在该信号量上 —— 实测 4 个 worker 全部 park 在同一 {@code Semaphore$FairSync}
     * （栈：{@code ConcurrencyGate.acquire ← LogonGlue}），整个套件无任何进展。有界等待保证
     * 「闸门问题绝不使套件永久卡死」。</p>
     *
     * <p><b>超时后的行为</b>见 {@link #CONCURRENCY_PARTITION_FAIL_CLOSED}：默认<b>失败快</b>
     * （该场景判失败），可显式降级为 fail-open 放行。</p>
     */
    CONCURRENCY_PARTITION_MAX_WAIT_MS(ConfigKeys.WEB_CONCURRENCY_PARTITION_MAX_WAIT_MS),

    CONCURRENCY_PARTITION_PER_KEY_PERMITS(ConfigKeys.WEB_CONCURRENCY_PARTITION_PER_KEY_PERMITS),

    /**
     * 闸门等待超时后是否「失败快」（fail-closed）。默认 {@code true}。
     *
     * <p><b>为什么默认 fail-closed</b>：原实现超时即放行并仅打 ERROR —— 同一身份（sessionKey）的
     * 串行化<b>静默失效</b>，表现为 SSO 互踢 / 随机 401 / 断言漂移，而用例仍<b>可能通过</b>；
     * 这比「如实报红」危险得多（评审 F-11）。故默认把「闸门未能取得」如实判为该场景失败。</p>
     *
     * <p><b>逃生舱</b>：设 {@code false} 可退回「放行 + ERROR 日志」的旧行为 ——
     * 仅建议在确认串行化失败不会造成数据/会话破坏的场景下使用。</p>
     */
    CONCURRENCY_PARTITION_FAIL_CLOSED(ConfigKeys.WEB_CONCURRENCY_PARTITION_FAIL_CLOSED),

    /**
     * 浏览器崩溃韧性守卫（BrowserCrashGuard）总开关。
     * 开启后，并发任务因共享 Browser 进程崩溃而失败时，会自动经进程级单飞重建 Browser 并重跑该任务一次；
     * 关闭（默认仍 true，属纯韧性增强）时退化为「失败直接随 {@link ContextTaskResult} 返回」，行为不变。
     * 仅对崩溃型失败（见 {@code BrowserCrashGuard#isCrash}）触发，正常业务失败不重跑。
     */
    CONCURRENCY_BROWSER_CRASH_GUARD_ENABLED(ConfigKeys.WEB_CONCURRENCY_BROWSER_CRASH_GUARD_ENABLED),

    /**
     * 崩溃型失败的消息特征白名单（{@code |} 分隔，大小写不敏感）。
     * <p>仅列入<b>明确的浏览器/进程崩溃信号</b>，不含任何会被正常业务流程触发的内容
     * （如 {@code "execution context was destroyed"} / {@code "browser process"} 已剔除，避免误判掩盖真实缺陷）。
     * 经本键可运行时调窄/调宽签名集（W-10：签名可配置），默认与历史收窄白名单一致。</p>
     */
    PLAYWRIGHT_CONCURRENT_CRASH_SIGNATURES(ConfigKeys.WEB_PLAYWRIGHT_CONCURRENT_CRASH_SIGNATURES),

    /**
     * 崩溃识别是否启用「异常类型 + Playwright 事件」双重佐证（W-10 三重判定之一）。
     * <p>开启时，若异常类型为 Playwright/超时类且当前线程 Browser 已被 {@code onDisconnected} 标记为断开，
     * 即使异常消息未命中 {@link #PLAYWRIGHT_CONCURRENT_CRASH_SIGNATURES} 也判为崩溃（覆盖消息被包装吞掉的场景）。
     * 关闭时退化为「仅消息签名匹配」（与 W-15 去类名模糊匹配一致，最保守）。</p>
     */
    PLAYWRIGHT_CONCURRENT_CRASH_CORROBORATION_ENABLED(ConfigKeys.WEB_PLAYWRIGHT_CONCURRENT_CRASH_CORROBORATION_ENABLED),

    /**
     * 条件驱动重试的默认重试间隔（毫秒）。W-9：原散落字面量 {@code 500} 抽为可配置键。
     */
    PLAYWRIGHT_WAITS_RETRY_INTERVAL_DEFAULT_MS(ConfigKeys.WEB_PLAYWRIGHT_WAITS_RETRY_INTERVAL_DEFAULT_MS),

    /**
     * {@code PageWaits.retry} 默认重试次数。W-9：原散落字面量 {@code 3} 抽为可配置键。
     */
    PLAYWRIGHT_WAITS_RETRY_COUNT(ConfigKeys.WEB_PLAYWRIGHT_WAITS_RETRY_COUNT),

    /**
     * {@code PageWaits.retry} 默认重试间隔（毫秒）。W-9：原散落字面量 {@code 1000} 抽为可配置键。
     */
    PLAYWRIGHT_WAITS_RETRY_INTERVAL_MS(ConfigKeys.WEB_PLAYWRIGHT_WAITS_RETRY_INTERVAL_MS),

    /**
     * {@code PageWaits.waitUntil} 固定小步长轮询间隔（毫秒）。W-9：原散落字面量 {@code 50} 抽为可配置键。
     */
    PLAYWRIGHT_WAITS_POLL_STEP_MS(ConfigKeys.WEB_PLAYWRIGHT_WAITS_POLL_STEP_MS),

    /**
     * 浏览器启动重试的退避基数（毫秒，随尝试次数线性放大）。W-9：原散落字面量 {@code 2000} 抽为可配置键。
     */
    PLAYWRIGHT_BROWSER_STARTUP_BACKOFF_MS(ConfigKeys.WEB_PLAYWRIGHT_BROWSER_STARTUP_BACKOFF_MS),

    /**
     * 并发上下文执行器线程池关闭等待超时（秒）。W-9：原散落字面量 {@code 30} 抽为可配置键。
     */
    PLAYWRIGHT_CONCURRENT_EXECUTOR_AWAIT_SECONDS(ConfigKeys.WEB_PLAYWRIGHT_CONCURRENT_EXECUTOR_AWAIT_SECONDS),

    /**
     * BrowserStack 会话连接超时（秒）。W-9：原散落字面量 {@code 60} 抽为可配置键。
     */
    PLAYWRIGHT_BROWSERSTACK_CONNECT_TIMEOUT_SECONDS(ConfigKeys.WEB_PLAYWRIGHT_BROWSERSTACK_CONNECT_TIMEOUT_SECONDS),

    /**
     * BrowserStack API 连接/读取超时（毫秒）。W-9：原散落字面量 {@code 30000} 抽为可配置键。
     */
    PLAYWRIGHT_BROWSERSTACK_REQUEST_TIMEOUT_MS(ConfigKeys.WEB_PLAYWRIGHT_BROWSERSTACK_REQUEST_TIMEOUT_MS),

    /**
     * 崩溃重跑严格有界次数（防止崩溃持续时无限循环）。W-9：原散落字面量 {@code 1} 抽为可配置键。
     */
    PLAYWRIGHT_CRASH_GUARD_MAX_REPLAY(ConfigKeys.WEB_PLAYWRIGHT_CRASH_GUARD_MAX_REPLAY),

    /**
     * 关闭上下文时 tracing 写盘等待超时（秒）。W-9：原散落字面量 {@code 15} 抽为可配置键。
     */
    PLAYWRIGHT_CONTEXT_CLOSE_TRACE_TIMEOUT_SECONDS(ConfigKeys.WEB_PLAYWRIGHT_CONTEXT_CLOSE_TRACE_TIMEOUT_SECONDS),

    /**
     * 页面稳定化补偿延迟（毫秒，窗口定位异步重排）。W-9：原散落字面量 {@code 300} 抽为可配置键。
     */
    PLAYWRIGHT_CONTEXT_STABILIZE_DELAY_MS(ConfigKeys.WEB_PLAYWRIGHT_CONTEXT_STABILIZE_DELAY_MS),

    // ==================== Playwright 上下文配置 ====================

    /**
     * 禁用触摸功能
     */
    PLAYWRIGHT_CONTEXT_HAS_TOUCH(ConfigKeys.WEB_PLAYWRIGHT_CONTEXT_HAS_TOUCH),

    /**
     * 移动设备模拟
     */
    PLAYWRIGHT_CONTEXT_IS_MOBILE(ConfigKeys.WEB_PLAYWRIGHT_CONTEXT_IS_MOBILE),

    /**
     * 截图保存路径
     */
    PLAYWRIGHT_CONTEXT_SCREENSHOT_PATH(ConfigKeys.WEB_PLAYWRIGHT_CONTEXT_SCREENSHOT_PATH),

    /**
     * 录屏功能
     */
    PLAYWRIGHT_CONTEXT_RECORD_VIDEO_ENABLED(ConfigKeys.WEB_PLAYWRIGHT_CONTEXT_RECORD_VIDEO_ENABLED),

    /**
     * 录屏保存目录
     */
    PLAYWRIGHT_CONTEXT_RECORD_VIDEO_DIR(ConfigKeys.WEB_PLAYWRIGHT_CONTEXT_RECORD_VIDEO_DIR),

    /**
     * Trace 功能
     */
    PLAYWRIGHT_CONTEXT_TRACE_ENABLED(ConfigKeys.WEB_PLAYWRIGHT_CONTEXT_TRACE_ENABLED),

    /**
     * trace 是否按 <b>scenario 分段</b>（每个用例一个 trace 文件）——默认 true（方案 A，2026-09-17）。
     *
     * <p>{@code true}：用 Playwright 原生 {@code startChunk()}/{@code stopChunk(path)} 在用例边界切段，
     * 使「trace 的时间 == 用例执行时间」，文件名/报告带 scenarioId 与起止时间；feature 模式（context 复用）
     * 下同样正确。{@code false}：回退为「一个 context 一个整段 trace」（旧行为，逃生开关）。
     */
    PLAYWRIGHT_CONTEXT_TRACE_CHUNK_PER_SCENARIO(ConfigKeys.WEB_PLAYWRIGHT_CONTEXT_TRACE_CHUNK_PER_SCENARIO),

    // ==================== 产物保留治理（trace / 截图，企业级磁盘治理）====================

    /** 是否启用产物保留治理（磁盘总上限 / 文件数上限 / 保留期）。 */
    PLAYWRIGHT_ARTIFACTS_RETENTION_ENABLED(ConfigKeys.WEB_PLAYWRIGHT_ARTIFACTS_RETENTION_ENABLED),

    /** 目标目录总字节上限（MB）。 */
    PLAYWRIGHT_ARTIFACTS_RETENTION_MAX_TOTAL_MB(ConfigKeys.WEB_PLAYWRIGHT_ARTIFACTS_RETENTION_MAX_TOTAL_MB),

    /** 目标目录文件数上限。 */
    PLAYWRIGHT_ARTIFACTS_RETENTION_MAX_FILES(ConfigKeys.WEB_PLAYWRIGHT_ARTIFACTS_RETENTION_MAX_FILES),

    /** 保留期（天）：超过此龄的文件在下次治理时删除。 */
    PLAYWRIGHT_ARTIFACTS_RETENTION_MAX_AGE_DAYS(ConfigKeys.WEB_PLAYWRIGHT_ARTIFACTS_RETENTION_MAX_AGE_DAYS),

    /**
     * 需治理的目录列表（逗号分隔）。注意：<b>本次 run 产出的文件永不删除</b>
     * （只清理 mtime 早于本次 run 起点的遗留物），避免"证据凭空消失"。
     */
    PLAYWRIGHT_ARTIFACTS_RETENTION_DIRS(ConfigKeys.WEB_PLAYWRIGHT_ARTIFACTS_RETENTION_DIRS),

    /**
     * Trace 时截图
     */
    PLAYWRIGHT_CONTEXT_TRACE_SCREENSHOTS(ConfigKeys.WEB_PLAYWRIGHT_CONTEXT_TRACE_SCREENSHOTS),

    /**
     * Trace 时快照
     */
    PLAYWRIGHT_CONTEXT_TRACE_SNAPSHOTS(ConfigKeys.WEB_PLAYWRIGHT_CONTEXT_TRACE_SNAPSHOTS),

    /**
     * Trace 时源码
     */
    PLAYWRIGHT_CONTEXT_TRACE_SOURCES(ConfigKeys.WEB_PLAYWRIGHT_CONTEXT_TRACE_SOURCES),

    /**
     * Context locale 设置
     */
    PLAYWRIGHT_CONTEXT_LOCALE(ConfigKeys.WEB_PLAYWRIGHT_CONTEXT_LOCALE),

    /**
     * Context timezone 设置
     */
    PLAYWRIGHT_CONTEXT_TIMEZONE_ID(ConfigKeys.WEB_PLAYWRIGHT_CONTEXT_TIMEZONE_ID),

    /**
     * Context User-Agent 设置
     */
    PLAYWRIGHT_CONTEXT_USER_AGENT(ConfigKeys.WEB_PLAYWRIGHT_CONTEXT_USER_AGENT),

    /**
     * Context 权限设置（逗号分隔）
     */
    PLAYWRIGHT_CONTEXT_PERMISSIONS(ConfigKeys.WEB_PLAYWRIGHT_CONTEXT_PERMISSIONS),

    /**
     * ColorScheme (light, dark, no-preference)
     */
    PLAYWRIGHT_CONTEXT_COLOR_SCHEME(ConfigKeys.WEB_PLAYWRIGHT_CONTEXT_COLOR_SCHEME),

    /**
     * Geolocation 纬度
     */
    PLAYWRIGHT_CONTEXT_GEOLOCATION_LATITUDE(ConfigKeys.WEB_PLAYWRIGHT_CONTEXT_GEOLOCATION_LATITUDE),

    /**
     * Geolocation 经度
     */
    PLAYWRIGHT_CONTEXT_GEOLOCATION_LONGITUDE(ConfigKeys.WEB_PLAYWRIGHT_CONTEXT_GEOLOCATION_LONGITUDE),

    /**
     * 设备缩放因子（留空则自动检测）
     */
    PLAYWRIGHT_CONTEXT_DEVICE_SCALE_FACTOR(ConfigKeys.WEB_PLAYWRIGHT_CONTEXT_DEVICE_SCALE_FACTOR),

    /**
     * Context 代理服务器
     * 用于公司网络环境下浏览器通过代理访问内网域名
     * 格式: http://proxy-host:port
     * 示例: http://proxy.example.com:8080
     * 留空则不使用代理
     */
    PLAYWRIGHT_CONTEXT_PROXY(ConfigKeys.WEB_PLAYWRIGHT_CONTEXT_PROXY),

    /**
     * Context 代理启用开关。
     * <p>true=启用代理（需同时配置 playwright.context.proxy）
     * <p>false=禁用代理（即使配置了 proxy URL 也不会生效）
     * <p>方便在不同环境（公司网络/家庭网络）之间切换，无需反复修改 proxy URL
     */
    PLAYWRIGHT_CONTEXT_PROXY_ENABLED(ConfigKeys.WEB_PLAYWRIGHT_CONTEXT_PROXY_ENABLED),

    // ==================== Playwright 页面配置 ====================

    /**
     * 页面操作超时（毫秒）
     */
    PLAYWRIGHT_PAGE_TIMEOUT(ConfigKeys.WEB_PLAYWRIGHT_PAGE_TIMEOUT),

    /**
     * 页面导航超时（毫秒）
     */
    PLAYWRIGHT_PAGE_NAVIGATION_TIMEOUT(ConfigKeys.WEB_PLAYWRIGHT_PAGE_NAVIGATION_TIMEOUT),

    /**
     * 页面加载状态
     * LOAD - 页面开始加载时触发（最快）
     * DOMCONTENTLOADED - DOM 完全加载时触发（推荐）
     * NETWORKIDLE - 所有网络请求完成时触发（最稳定）
     */
    PLAYWRIGHT_PAGE_LOAD_STATE(ConfigKeys.WEB_PLAYWRIGHT_PAGE_LOAD_STATE),

    /**
     * 页面稳定化等待超时（毫秒）
     */
    PLAYWRIGHT_STABILIZE_WAIT_TIMEOUT(ConfigKeys.WEB_PLAYWRIGHT_STABILIZE_WAIT_TIMEOUT),

    // ==================== 截图配置 ====================

    /**
     * 截图策略
     * BEFORE_AND_AFTER_EACH_STEP - 每个步骤前后截图
     * AFTER_EACH_STEP - 每个步骤后截图（推荐）
     * FOR_FAILURES - 仅失败时截图
     * DISABLED - 禁用截图
     */
    SERENITY_SCREENSHOT_STRATEGY(ConfigKeys.WEB_SERENITY_SCREENSHOT_STRATEGY),

    /**
     * 全页截图配置
     * true - 截图整个页面（包括滚动区域，较慢）
     * false - 仅截图可见区域 viewport（快，推荐）
     */
    PLAYWRIGHT_SCREENSHOT_FULLPAGE(ConfigKeys.WEB_PLAYWRIGHT_SCREENSHOT_FULLPAGE),

    /**
     * 截图等待超时（毫秒）
     */
    PLAYWRIGHT_SCREENSHOT_WAIT_TIMEOUT(ConfigKeys.WEB_PLAYWRIGHT_SCREENSHOT_WAIT_TIMEOUT),

    /**
     * 全页截图时允许的最大滚动高度（像素）。
     * 用于防御无限滚动/懒加载页面：超过该高度的页面会被裁切到上限后再全页截图，
     * 避免 Playwright 全页拼图一直滚动导致卡死。
     * <= 0 表示不限制（沿用 Playwright 默认行为）。
     */
    PLAYWRIGHT_SCREENSHOT_FULLPAGE_MAX_HEIGHT(ConfigKeys.WEB_PLAYWRIGHT_SCREENSHOT_FULLPAGE_MAX_HEIGHT),

    /**
     * Serenity 报告截图目录
     */
    SERENITY_REPORTS_SCREENSHOTS_DIRECTORY(ConfigKeys.WEB_SERENITY_REPORTS_SCREENSHOTS_DIRECTORY),

    /**
     * Serenity 截图目录
     */
    SERENITY_SCREENSHOTS_DIRECTORY(ConfigKeys.WEB_SERENITY_SCREENSHOTS_DIRECTORY),

    /**
     * WebP 合规截图开关（对应 Playwright 1.62 升级评估报 §3.1）。
     * 默认 true：失败截图时<b>额外</b>落一张 WebP 到自建合规归档目录（不影响 Serenity 报告 PNG）。
     * 注意：WebP 仅用于自建归档目录，<b>绝不</b>写入 {@code target/site/serenity}（Serenity 4.2.0 对截图
     * 后缀/格式有内部假设，改用 WebP 会破坏报告渲染）。
     */
    PLAYWRIGHT_WEBP_SCREENSHOT_ENABLED(ConfigKeys.WEB_PLAYWRIGHT_WEBP_SCREENSHOT_ENABLED),

    /**
     * WebP 合规截图质量（0-100，越高体积越大）。仅对 WEBP/JPEG 有损格式有效。
     */
    PLAYWRIGHT_WEBP_SCREENSHOT_QUALITY(ConfigKeys.WEB_PLAYWRIGHT_WEBP_SCREENSHOT_QUALITY),

    /**
     * WebP 合规截图落盘目录（与 Serenity 报告目录隔离的自建归档目录）。
     */
    PLAYWRIGHT_WEBP_SCREENSHOT_ARCHIVE_DIR(ConfigKeys.WEB_PLAYWRIGHT_WEBP_SCREENSHOT_ARCHIVE_DIR),

    // ==================== 日志配置 ====================

    /**
     * 日志级别
     * QUIET - 最少日志
     * NORMAL - 正常日志
     * VERBOSE - 详细日志
     */
    SERENITY_LOGGING(ConfigKeys.WEB_SERENITY_LOGGING),

    // ==================== 报告配置 ====================

    /**
     * 报告输出目录
     */
    SERENITY_OUTPUT_DIRECTORY(ConfigKeys.WEB_SERENITY_OUTPUT_DIRECTORY),


    // ==================== 测试执行配置 ====================

    /**
     * 特性文件根目录
     */
    SERENITY_FEATURES_ROOT(ConfigKeys.WEB_SERENITY_FEATURES_ROOT),

    /**
     * 需求根目录
     */
    SERENITY_REQUIREMENTS_BASE(ConfigKeys.WEB_SERENITY_REQUIREMENTS_BASE),

    /**
     * 浏览器配置（Playwright 原生配置）
     */
    SERENITY_BROWSER(ConfigKeys.WEB_SERENITY_BROWSER),


    /**
     * 浏览器重启策略
     * FEATURE - 每个 feature 文件重启浏览器一次（更快，但可能有状态污染）
     * SCENARIO - 每个 scenario 重启浏览器一次（推荐）
     */
    SERENITY_PLAYWRIGHT_RESTART_BROWSER_FOR_EACH(ConfigKeys.WEB_SERENITY_PLAYWRIGHT_RESTART_BROWSER_FOR_EACH),

    /**
     * 同一 feature 内是否复用同一 Context/Page（默认 false）。
     *
     * <p><b>false（默认，保守）</b>：未使用 {@code SessionManager} 的场景在用例收尾即关闭 Context，
     * 杜绝同 feature 内场景间 Cookie / LocalStorage / 页面状态互相污染；代价是每用例各建 1 个
     * Context+Page（headed 下即每个用例开/关一次窗口）。
     *
     * <p><b>true</b>：同一 feature 内所有场景复用<b>同一个</b> Context+Page —— 配合
     * {@code serenity.playwright.restart.browser.for.each=feature} 即实现「1 个窗口 / 一个 feature」。
     * 适用于无状态场景（路由拦截 / 接口 / 只读页面）；<b>有登录态或页面状态依赖的场景请勿开启</b>，
     * 否则会引入跨场景串扰（正是本项默认关闭的原因）。
     *
     * <p>本项与 {@code restart.browser.for.each} 正交：后者决定「Context 何时关闭」（scenario/feature），
     * 本项决定「无 session 场景是否也保留 Context」。
     */
    SERENITY_PLAYWRIGHT_REUSE_CONTEXT_WITHIN_FEATURE(ConfigKeys.WEB_SERENITY_PLAYWRIGHT_REUSE_CONTEXT_WITHIN_FEATURE),



    // ==================== 重试配置 ====================
    /**
     * 失败测试重跑等待时间（毫秒）
     */
    SERENITY_RERUN_FAILURES_WAIT_TIME(ConfigKeys.WEB_SERENITY_RERUN_FAILURES_WAIT_TIME),

    /**
     * 重试延迟策略（fixed, exponential）
     */
    SERENITY_RETRY_DELAY_STRATEGY(ConfigKeys.WEB_SERENITY_RETRY_DELAY_STRATEGY),

    /**
     * 指数退避基础延迟（毫秒）
     */
    SERENITY_RETRY_DELAY_BASE(ConfigKeys.WEB_SERENITY_RETRY_DELAY_BASE),

    /**
     * 指数退避最大延迟（毫秒）
     */
    SERENITY_RETRY_DELAY_MAX(ConfigKeys.WEB_SERENITY_RETRY_DELAY_MAX),

    /**
     * 指数退避乘数
     */
    SERENITY_RETRY_DELAY_MULTIPLIER(ConfigKeys.WEB_SERENITY_RETRY_DELAY_MULTIPLIER),



    // ==================== API Mock 配置 ====================

    /**
     * 全局启用 API Mock
     */
    API_MOCK_ENABLED(ConfigKeys.WEB_API_MOCK_ENABLED),

    /**
     * Mock 规则配置目录
     */
    API_MOCK_RULES_DIRECTORY(ConfigKeys.WEB_API_MOCK_RULES_DIRECTORY),

    /**
     * Mock 日志级别
     * DEBUG, INFO, WARN, ERROR
     */
    API_MOCK_LOG_LEVEL(ConfigKeys.WEB_API_MOCK_LOG_LEVEL),

    // ==================== Playwright SDK 配置 ====================

    /**
     * Playwright Driver 路径
     * 指定 Playwright Driver 的存储目录
     */
    PLAYWRIGHT_DRIVER_PATH(ConfigKeys.WEB_PLAYWRIGHT_DRIVER_PATH),
    
    /**
     * Playwright SDK 目录
     * 指定 Playwright SDK 的存储目录
     */
    PLAYWRIGHT_SDK_PATH(ConfigKeys.WEB_PLAYWRIGHT_SDK_PATH),

    /**
     * Playwright Driver 临时目录
     * 指定 Playwright Driver 的临时文件存储目录
     */
    PLAYWRIGHT_DRIVER_TMPDIR(ConfigKeys.WEB_PLAYWRIGHT_DRIVER_TMPDIR),

    PLAYWRIGHT_BROWSERS_PATH(ConfigKeys.WEB_PLAYWRIGHT_BROWSERS_PATH),

    /**
     * 浏览器下载代理启用开关。
     * <p>true=启用下载代理（需同时配置下方下载代理地址/凭据，或统一代理地址）
     * <p>false=禁用下载代理（即使配置了代理地址也不会注入到 CLI 环境变量）
     */
    PLAYWRIGHT_DOWNLOAD_PROXY_ENABLED(ConfigKeys.WEB_PLAYWRIGHT_DOWNLOAD_PROXY_ENABLED),

    /**
     * 浏览器下载 HTTP 代理地址
     * 用于公司网络环境下 Playwright CLI 下载浏览器时通过代理访问 CDN。
     * 格式: host:port（不含 scheme 和认证信息）
     * 
     * 示例: proxy.company.com:8080
     */
    PLAYWRIGHT_BROWSER_DOWNLOAD_HTTP_PROXY(ConfigKeys.WEB_PLAYWRIGHT_BROWSER_DOWNLOAD_HTTP_PROXY),

    /**
     * 浏览器下载 HTTPS 代理地址
     * 格式: host:port（不含 scheme 和认证信息）
     * 
     * 示例: proxy.company.com:8443
     */
    PLAYWRIGHT_BROWSER_DOWNLOAD_HTTPS_PROXY(ConfigKeys.WEB_PLAYWRIGHT_BROWSER_DOWNLOAD_HTTPS_PROXY),

    /**
     * No login session timeout in minutes
     * After this period, saved sessions will be considered expired
     */
    PLAYWRIGHT_NO_LOGIN_SESSION_TIMEOUT(ConfigKeys.WEB_PLAYWRIGHT_NO_LOGIN_SESSION_TIMEOUT),

    /**
     * 同 user 登录单飞（single-flight）等待超时（毫秒）
     * follower 线程等待 leader 完成登录/落盘的最大时长；超时则摘除失效守卫、本线程接替为 leader，
     * 防止 leader 异常时 follower 永久阻塞。
     */
    PLAYWRIGHT_NO_LOGIN_SINGLE_FLIGHT_TIMEOUT_MS(ConfigKeys.WEB_PLAYWRIGHT_NO_LOGIN_SINGLE_FLIGHT_TIMEOUT_MS),

    /**
     * 元素等待时间（毫秒）
     * 用于 isVisible, exists, isChecked, isEnabled, isDisabled, isElementClickable 等立即执行方法的重试超时
     * 这些方法会重试检查，直到超时，提高测试稳定性
     */
    PLAYWRIGHT_ELEMENT_WAIT_TIMEOUT(ConfigKeys.WEB_PLAYWRIGHT_ELEMENT_WAIT_TIMEOUT),

    /**
     * 轮询间隔时间（毫秒）
     * 用于各种等待方法的轮询检查间隔
     */
    PLAYWRIGHT_POLLING_INTERVAL(ConfigKeys.WEB_PLAYWRIGHT_POLLING_INTERVAL),

    // ==================== Axe-core 配置 ====================

    /**
     * 是否启用 axe-core 扫描
     */
    AXE_SCAN_ENABLED(ConfigKeys.WEB_AXE_SCAN_ENABLED),

    /**
     * Axe-core WCAG 标签（逗号分隔）
     * 留空则运行所有规则
     * 例如: wcag2aa,wcag21aa
     */
    AXE_SCAN_TAGS(ConfigKeys.WEB_AXE_SCAN_TAGS),

    /**
     * Axe-core 报告输出目录
     */
    AXE_SCAN_OUTPUT_DIR(ConfigKeys.WEB_AXE_SCAN_OUTPUT_DIR),

    // ==================== Playwright 原生快照测试配置 ====================

    // ==================== Playwright 元素操作配置 ====================

    /**
     * 元素操作后等待时间（毫秒）
     * 用于 click/fill 等操作后等待 DOM 稳定
     */
    PLAYWRIGHT_ELEMENT_ACTION_POST_DELAY(ConfigKeys.WEB_PLAYWRIGHT_ELEMENT_ACTION_POST_DELAY),

    /**
     * 元素操作总超时时间（毫秒）
     * 元素操作（click / fill / check 等）的单次操作超时预算，
     * 取代原「sleep + 轮询」重试循环的截止时间，交由 Playwright 原生 actionability 自动等待。
     */
    PLAYWRIGHT_ELEMENT_OPERATION_TIMEOUT(ConfigKeys.WEB_PLAYWRIGHT_ELEMENT_OPERATION_TIMEOUT),

    /**
     * 失败时是否自动截图
     * true=自动截图, false=不截图
     */
    PLAYWRIGHT_ELEMENT_SCREENSHOT_ON_FAILURE(ConfigKeys.WEB_PLAYWRIGHT_ELEMENT_SCREENSHOT_ON_FAILURE),

    /**
     * 是否收集详细诊断信息
     * true=收集完整诊断信息, false=收集基本信息
     */
    PLAYWRIGHT_ELEMENT_DIAGNOSTICS_DETAILED(ConfigKeys.WEB_PLAYWRIGHT_ELEMENT_DIAGNOSTICS_DETAILED),

    /**
     * 失败截图保存路径
     */
    PLAYWRIGHT_ELEMENT_SCREENSHOT_PATH(ConfigKeys.WEB_PLAYWRIGHT_ELEMENT_SCREENSHOT_PATH),

    // ==================== API 断言配置 ====================

    /**
     * API 断言等待超时时间（毫秒）
     * 用于等待所有异步 API 请求完成
     */
    API_ASSERTION_WAIT_TIMEOUT(ConfigKeys.WEB_API_ASSERTION_WAIT_TIMEOUT),

    // ==================== API Monitor / 持久化配置（唯一事实来源已收敛至 core MonitorConfig）====================
    // 监控 / 持久化域配置键的单一事实来源为 core 的 MonitorConfig：
    //   api.capture.max.response.size.mb、monitor.db.store.enabled、monitor.db.type、monitor.db.url、
    //   monitor.db.user、monitor.db.password、monitor.db.pool.max.size、monitor.test.run.id、
    //   monitor.file.store.enabled、monitor.file.store.dir、monitor.file.store.pretty、
    //   monitor.file.store.group.by.scenario
    // 原因：route 需读取这些键，但不依赖 web（ArchUnit L6），故统一收敛至 core；此处不再重复定义。
    // 键名 / 默认值与 core 完全一致，经 ConfigSource 统一解析，对既有 serenity.properties 配置零影响；
    // 运行时读取入口：{@code com.hsbc.cmb.hk.dbb.automation.framework.common.config.MonitorConfig}。

    // ==================== 统一代理配置 ====================
    //
    // 所有代理场景（BrowserStack CDP / Context 浏览器流量 / Playwright 下载 / BS Local 隧道）
    // 统一从此处读取代理地址和凭据，无需为各子系统重复配置。
    //
    // 每个场景有独立的启用开关（见各场景对应配置项）。
    // port 已包含在 URL 中，无需单独配置。
    // HTTP 和 HTTPS 可设置不同的代理地址和凭据。

    /**
     * HTTP 代理地址（host:port，可含 http:// 或 https:// scheme）。
     * <p>示例: {@code http://proxy.company.com:8888} 或 {@code proxy.company.com:8888}
     */
    PLAYWRIGHT_PROXY_HTTP(ConfigKeys.WEB_PLAYWRIGHT_PROXY_HTTP),

    /**
     * HTTPS 代理地址（host:port，可含 http:// 或 https:// scheme）。
     * <p>HTTP 和 HTTPS 各自独立配置，互不回退。
     * <p>示例: {@code https://proxy.company.com:8443} 或 {@code proxy.company.com:8443}
     */
    PLAYWRIGHT_PROXY_HTTPS(ConfigKeys.WEB_PLAYWRIGHT_PROXY_HTTPS),

    /**
     * HTTP 代理用户名（可选）。
     */
    PLAYWRIGHT_PROXY_HTTP_USERNAME(ConfigKeys.WEB_PLAYWRIGHT_PROXY_HTTP_USERNAME),

    /**
     * HTTP 代理密码（可选）。
     */
    PLAYWRIGHT_PROXY_HTTP_PASSWORD(ConfigKeys.WEB_PLAYWRIGHT_PROXY_HTTP_PASSWORD),

    /**
     * HTTPS 代理用户名（可选）。
     */
    PLAYWRIGHT_PROXY_HTTPS_USERNAME(ConfigKeys.WEB_PLAYWRIGHT_PROXY_HTTPS_USERNAME),

    /**
     * HTTPS 代理密码（可选）。
     */
    PLAYWRIGHT_PROXY_HTTPS_PASSWORD(ConfigKeys.WEB_PLAYWRIGHT_PROXY_HTTPS_PASSWORD),

    /**
     * Playwright 浏览器下载超时时间（分钟）
     * 下载超过此时间未完成则强制终止进程
     */
    PLAYWRIGHT_BROWSER_DOWNLOAD_TIMEOUT_MINUTES(ConfigKeys.WEB_PLAYWRIGHT_BROWSER_DOWNLOAD_TIMEOUT_MINUTES),

    /**
     * 附加敏感请求/响应头名（逗号分隔），叠加在内置脱敏清单之上。
     * <p>由 {@code common.security.SensitiveDataSanitizer} 经 Serenity 配置体系读取，
     * 键名走规范化匹配（忽略大小写与 _ - . 空格）。
     */
    SENSITIVE_DATA_EXTRA_HEADER_KEYS(ConfigKeys.WEB_SENSITIVE_DATA_EXTRA_HEADER_KEYS),

    /**
     * 附加敏感体字段名（逗号分隔），叠加在内置脱敏清单之上。
     */
    SENSITIVE_DATA_EXTRA_BODY_KEYS(ConfigKeys.WEB_SENSITIVE_DATA_EXTRA_BODY_KEYS),

    /**
     * 附加敏感 URL query 参数名（逗号分隔），叠加在内置脱敏清单之上。
     */
    SENSITIVE_DATA_EXTRA_QUERY_KEYS(ConfigKeys.WEB_SENSITIVE_DATA_EXTRA_QUERY_KEYS);

    private final String key;
    private final String defaultValue;
    private final String description;

    WebFrameworkConfig(ConfigKeys definition) {
        this.key = definition.key();
        this.defaultValue = definition.defaultValue();
        this.description = definition.description();
    }

    /**
     * 获取配置值
     * @return 配置值（字符串）
     */
    public String getValue() {
        return ConfigSource.resolve(key, defaultValue);
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

    /**
     * 暴露为共享 {@link ConfigKey} 元数据（key / 默认 / 描述 三元组）。
     * <p>WEB-P1-7「配置体系收敛」：Web 与 API 两侧配置项统一以 {@link ConfigKey} 承载元数据，
     * 供跨域审计、文档生成与改名后的口径一致性校验使用。
     * @return 本配置项的 {@link ConfigKey}
     */
    public ConfigKey configKey() {
        return new ConfigKey(key, defaultValue, description);
    }

    /**
     * 导出全部配置项的 {@link ConfigKey} 元数据（供审计 / 文档 / 测试遍历）。
     * @return 所有配置项的 {@link ConfigKey} 列表
     */
    public static List<ConfigKey> allConfigKeys() {
        List<ConfigKey> keys = new ArrayList<>();
        for (WebFrameworkConfig c : values()) {
            keys.add(c.configKey());
        }
        return keys;
    }
}