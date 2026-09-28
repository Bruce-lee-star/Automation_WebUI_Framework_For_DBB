package com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.event;

import com.microsoft.playwright.ConsoleMessage;
import com.microsoft.playwright.Request;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.hsbc.cmb.hk.dbb.automation.framework.web.config.WebFrameworkConfig;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.bootstrap.PlaywrightContextManager;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;

import com.hsbc.cmb.hk.dbb.automation.framework.core.context.ContextKey;
import com.hsbc.cmb.hk.dbb.automation.framework.core.context.TestContextHolder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * 页面级可观测性事件监听注册器（单一职责）。
 *
 * <p><b>定位</b>：集中注册 Playwright {@link Page} / {@link BrowserContext} 的<b>诊断类</b>事件
 * （未捕获 JS 异常、控制台错误/警告、网络请求失败、页面崩溃），并将其路由到框架 logger，
 * 提升运行时可观测性。本类<b>不</b>处理 API 抓包（{@code route} 模块的 {@code ApiCaptureLifecycle} 负责）
 * 与测试报告（{@code PlaywrightListener} / Serenity 负责），职责隔离清晰。
 *
 * <p><b>为何不放进 {@code BasePage} / 页面能力契约接口</b>：页面对象是业务层，且多个 BasePage 实例共享同一底层
 * {@code Page}；把横切的可观测性塞进 page-object 既违反单一职责，又会让本就超大的基类进一步膨胀。
 * 注册点统一收敛在页面/上下文<b>创建接缝</b>（{@link PlaywrightContextManager}），与 {@code onDownload} /
 * {@code onPage} / {@code onLoad} 已有接线保持一致。
 *
 * <p><b>注册模型（Playwright 1.60+）</b>：经 {@link BrowserContext#onPage} 在上下文级注册一次，
 * 自动覆盖该上下文下所有页面（含 {@code window.open} 弹窗与 {@link BrowserContext#newPage()} 创建的页），
 * 1.60+ 保证每个页面仅触发一次；因此<b>无需</b>自研幂等去重与关闭清理（升级评估报 §3.2 已删除该逻辑）。
 * 诊断监听仅在页面创建接缝处注册一次，不叠加、不跨 scenario 残留。
 *
 * <p><b>订阅开关（全部配置驱动，见 {@code WebFrameworkConfig} / {@code ConfigKeys}）</b>：
 * {@code context.onPage} 扇出由 {@code playwright.page.events.page.enabled}（默认 true）控制；
 * 四个诊断订阅分别由 {@code playwright.page.events.<console|pageError|requestFailed|crash>.enabled}
 * 控制，<b>默认全部关闭</b>。关闭后服务端不再下发对应事件，同时消除"驱动按 payload guid 解析已回收
 * 句柄"造成的 {@code Object doesn't exist} / {@code Cannot find object to call} 类异常（污染在途调用）。
 *
 * @apiNote 内部基础设施能力，业务 Page 不应直接调用；仅由 {@link PlaywrightContextManager} 在创建接缝处调用。
 */
public final class PageEventMonitor {

    private static final Logger logger = LoggerFactory.getLogger(PageEventMonitor.class);

    //  逐事件订阅开关（已由硬编码迁至配置注册表，见 WebFrameworkConfig / ConfigKeys）：
    //    · 扇出总闸：PLAYWRIGHT_PAGE_EVENTS_PAGE_ENABLED（控制 context.onPage，默认 true）
    //      —— 关闭后本类与 PageInteractionMonitor 的每页监听都不会注册（挂载点即 onPage 回调）；
    //    · 本类四个诊断订阅默认全部【关闭】：console / pageError / requestFailed / crash。
    //
    //  为何配置驱动 + 默认关闭：这些订阅对应的事件分支会拿 payload 里的 guid 去客户端对象表解析
    //  （Connection.getExistingObject / Connection.dispatch），句柄一旦已被服务端回收即抛
    //  "Object doesn't exist: <type>@…" / "Cannot find object to call <event>: <type>@…"；异常发生在
    //  业务 lambda【之前】无法在回调内拦截，且因 Connection 为共享单连接，会以"此刻在等结果的任意
    //  调用线程"为宿主抛出（污染在途调用）。不订阅则服务端根本不下发该事件 ⇒ 敞口为零（实测零订阅时
    //  该 Context 收到的相关事件数为 0）。运行时改配置即可开关，无需改代码。

    /** "failOnError 已开但 pageError 订阅已关"的告警只打一次（避免每 context 刷屏）。 */
    private static final java.util.concurrent.atomic.AtomicBoolean FAIL_ON_ERROR_WARNED =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    /** 当前测试线程待上报的未捕获页面异常集合（步骤结束时经 Serenity 检查消费并清空）。 */
    @SuppressWarnings("unchecked")
    private static final ContextKey<List> PENDING_PAGE_ERRORS_KEY =
            ContextKey.of("pageEventMonitor.pendingPageErrors", List.class);

    /**
     * 已注册上下文/页面集合（弱引用键，不阻止 GC 堆积；与 {@code PlaywrightManager.CLOSING_BROWSERS} 同款模式）。
     * 用于<b>幂等护栏</b>：register 被重复调用（业务层、上下文重建、反射回归等任何路径）一律降级为 no-op，
     * 杜绝 handler 叠加导致诊断/抓取被双重处理。
     */
    private static final Set<BrowserContext> REGISTERED_CONTEXTS =
            Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));
    private static final Set<Page> REGISTERED_PAGES =
            Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));

    /**
     * 第三方噪音源域名白名单：指纹/监控/广告分析脚本（ThreatMetrix、AppDynamics、Tealium、Google/Yahoo 分析等）
     * 会持续刷 console-error/warning 与 request-failed，与业务断言无关且淹没真实诊断。命中则降级为 TRACE（默认静默），
     * 仅 {@code TRACE} 级别下可见，避免日志被淹。如需扩展/关闭，在此集合增删域名子串即可（后续可迁至 WebFrameworkConfig）。
     */
    private static final Set<String> NOISE_DOMAINS = Set.of(
            "online-metrix.net",        // ThreatMetrix 指纹
            "appdynamics.com",          // AppDynamics RUM (adrum)
            "tiqcdn.com",               // Tealium utag
            "googleadservices.com",     // Google 转化追踪
            "yimg.com",                 // Yahoo 资源
            "analytics.yahoo.com",
            "sp.analytics.yahoo.com",
            "doubleclick.net",
            "googletagmanager.com",
            "google-analytics.com",
            "scorecardresearch.com",
            "connect.facebook.net");

    /** 未捕获页面异常中属于上述第三方的文本片段（page-error 无 URL，只能按文本识别）。 */
    private static final List<String> THIRD_PARTY_ERROR_FRAGMENTS = List.of(
            "appdynamics", "adrum", "online-metrix", "tiqcdn");

    private PageEventMonitor() {
    }

    /** 来源 URL 是否命中第三方噪音白名单。 */
    private static boolean isNoise(String url) {
        if (url == null) {
            return false;
        }
        for (String domain : NOISE_DOMAINS) {
            if (url.contains(domain)) {
                return true;
            }
        }
        return false;
    }

    /** 未捕获异常文本是否来自第三方噪音脚本（page-error 不带 URL，按文本片段识别）。 */
    private static boolean isThirdPartyError(String error) {
        if (error == null) {
            return false;
        }
        for (String fragment : THIRD_PARTY_ERROR_FRAGMENTS) {
            if (error.contains(fragment)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 注册整个 BrowserContext 的页面级诊断监听。
     * 通过 {@code context.onPage} 覆盖所有新建页面（含 {@code window.open} 弹窗、{@code context.newPage()}），
     * 由 1.60+ {@code onPage} 保证每个页面仅触发一次。
     *
     * <p><b>幂等</b>：同一 {@link BrowserContext} 重复注册只生效一次（见 {@link #REGISTERED_CONTEXTS}）。
     *
     * @param context 浏览器上下文（null 安全：直接忽略）
     */
    public static void register(BrowserContext context) {
        if (!WebFrameworkConfig.PLAYWRIGHT_PAGE_EVENTS_PAGE_ENABLED.getBooleanValue()) {
            return;   // 扇出总闸关闭：连 onPage 都不订阅，本类所有页面级诊断均不生效
        }
        if (context == null || !REGISTERED_CONTEXTS.add(context)) {
            return;
        }
        warnIfFailOnPageErrorHasNoEffect();
        context.onPage(PageEventMonitor::register);
    }

    /**
     * 一致性告警（只打一次）：{@code playwright.page.error.failOnError=true} 依赖 {@code onPageError}
     * 订阅来收集异常；若该订阅已被配置关闭，则 fail-on-page-error 形同虚设——属"必须知情"的静默降级。
     */
    private static void warnIfFailOnPageErrorHasNoEffect() {
        if (WebFrameworkConfig.PLAYWRIGHT_PAGE_ERROR_FAIL.getBooleanValue()
                && !pageErrorEnabled()
                && FAIL_ON_ERROR_WARNED.compareAndSet(false, true)) {
            logger.warn("[page-error] '{}' is true but '{}' is false — page errors are NOT collected, "
                            + "so fail-on-page-error cannot take effect. Enable the subscription or disable the fail switch.",
                    WebFrameworkConfig.PLAYWRIGHT_PAGE_ERROR_FAIL.getKey(),
                    WebFrameworkConfig.PLAYWRIGHT_PAGE_EVENTS_PAGE_ERROR_ENABLED.getKey());
        }
    }

    private static boolean consoleEnabled() {
        return WebFrameworkConfig.PLAYWRIGHT_PAGE_EVENTS_CONSOLE_ENABLED.getBooleanValue();
    }

    private static boolean pageErrorEnabled() {
        return WebFrameworkConfig.PLAYWRIGHT_PAGE_EVENTS_PAGE_ERROR_ENABLED.getBooleanValue();
    }

    private static boolean requestFailedEnabled() {
        return WebFrameworkConfig.PLAYWRIGHT_PAGE_EVENTS_REQUEST_FAILED_ENABLED.getBooleanValue();
    }

    private static boolean crashEnabled() {
        return WebFrameworkConfig.PLAYWRIGHT_PAGE_EVENTS_CRASH_ENABLED.getBooleanValue();
    }

    /**
     * 注册单个 Page 的诊断监听（幂等）。
     *
     * <p><b>幂等</b>：同一 {@link Page} 重复注册只生效一次（见 {@link #REGISTERED_PAGES}），
     * 故本方法可安全地被任何层重复调用，handler 不会叠加。
     *
     * @param page 目标页面（null 安全：直接忽略）
     */
    public static void register(Page page) {
        if (page == null || !REGISTERED_PAGES.add(page)) {
            return;
        }
        //  逐事件按配置订阅（playwright.page.events.*）；默认四个诊断订阅全关 ⇒ 不注册任何回调。
        //  幂等标记照常记录，故重复 register 依旧 no-op（不会因配置差异而叠加 handler）。
        if (pageErrorEnabled()) {
            page.onPageError(PageEventMonitor::handlePageError);
        }
        if (consoleEnabled()) {
            page.onConsoleMessage(PageEventMonitor::handleConsoleMessage);
        }
        if (requestFailedEnabled()) {
            page.onRequestFailed(PageEventMonitor::handleRequestFailed);
        }
        if (crashEnabled()) {
            page.onCrash(PageEventMonitor::handleCrash);
        }
    }

    /** 未捕获 JS 异常：记录错误级日志；开启"页面异常即失败"开关时收集，待步骤结束上报 Serenity。 */
    private static void handlePageError(String error) {
        if (isThirdPartyError(error)) {
            logger.trace("[page-error-noise-suppressed] {}", error);
            return;
        }
        logger.error("[page-error] Uncaught page exception: {}", error);
        if (WebFrameworkConfig.PLAYWRIGHT_PAGE_ERROR_FAIL.getBooleanValue()) {
            pendingPageErrors().add(error);
        }
    }

    /** 当前测试线程的待上报页面异常列表（惰性创建）。 */
    @SuppressWarnings("unchecked")
    private static List<String> pendingPageErrors() {
        return (List<String>) TestContextHolder.get().computeIfAbsent(PENDING_PAGE_ERRORS_KEY, ArrayList::new);
    }

    /**
     * 取出并清空当前测试线程收集的未捕获页面异常（幂等消费，供 Serenity 失败传播）。
     *
     * @return 异常文本快照；无则为空列表（永不返回 null）
     */
    @SuppressWarnings("unchecked")
    public static List<String> drainPendingPageErrors() {
        List<String> errors = (List<String>) TestContextHolder.get().get(PENDING_PAGE_ERRORS_KEY);
        if (errors == null || errors.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> snapshot = new ArrayList<>(errors);
        errors.clear();
        return snapshot;
    }

    /** 控制台消息：仅关注 error / warning，避免 log 噪声；第三方噪音源降级为 TRACE（默认静默）。 */
    private static void handleConsoleMessage(ConsoleMessage message) {
        String url = message.location();
        if (isNoise(url)) {
            logger.trace("[console-noise-suppressed] {} from {}", message.type(), url);
            return;
        }
        String type = message.type();
        if ("error".equals(type)) {
            logger.error("[console-error] {}", message.text());
        } else if ("warning".equals(type)) {
            logger.warn("[console-warning] {}", message.text());
        }
    }

    /** 网络请求失败（超时/断网）：记录警告级日志；第三方噪音源降级为 TRACE（默认静默）。 */
    private static void handleRequestFailed(Request request) {
        if (isNoise(request.url())) {
            logger.trace("[request-noise-suppressed] {} {}", request.method(), request.url());
            return;
        }
        logger.warn("[request-failed] {} {}", request.method(), request.url());
    }

    /** 页面崩溃：记录警告级日志。 */
    private static void handleCrash(Page crashed) {
        logger.warn("[page-crash] Page crashed: {}", crashed.url());
    }
}
