package com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.event;
import com.microsoft.playwright.ConsoleMessage;
import com.microsoft.playwright.Request;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.hsbc.cmb.hk.dbb.automation.framework.web.config.WebFrameworkConfig;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.bootstrap.PlaywrightContextManager;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;

import com.hsbc.cmb.hk.dbb.automation.framework.core.context.ContextKey;
import com.hsbc.cmb.hk.dbb.automation.framework.core.context.TestContextHolder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 页面级可观测性事件监听注册器（单一职责）。
 *
 * <p><b>定位</b>：集中注册 Playwright {@link Page} / {@link BrowserContext} 的<b>诊断类</b>事件
 * （未捕获 JS 异常、控制台错误/警告、网络请求失败、页面崩溃），并将其路由到框架 logger，
 * 提升运行时可观测性。本类<b>不</b>处理 API 抓包（{@code route} 模块的 {@code ApiCaptureLifecycle} 负责）
 * 与测试报告（{@code PlaywrightListener} / Serenity 负责），职责隔离清晰。
 *
 * <p><b>为何不放进 BasePage / SerenityBasePage</b>：页面对象是业务层，且多个 BasePage 实例共享同一底层
 * {@code Page}；把横切的可观测性塞进 page-object 既违反单一职责，又会让本就超大的基类进一步膨胀。
 * 注册点统一收敛在页面/上下文<b>创建接缝</b>（{@link PlaywrightContextManager}），与 {@code onDownload} /
 * {@code onPage} / {@code onLoad} 已有接线保持一致。
 *
 * <p><b>健壮性</b>：
 * <ul>
 *   <li>幂等：同一 {@link Page} 只会注册一次（{@link #REGISTERED} 并集去重），避免叠加监听器导致事件重复处理
 *       （历史上 {@code ApiCaptureLifecycle} 曾因重复注册 {@code onResponse} 引发计数漂移）；</li>
 *   <li>线程安全：{@code REGISTERED} 为并发安全集合；</li>
 *   <li>无泄漏：{@code page.onClose} 时从集合中移除，跨 scenario 不残留。</li>
 * </ul>
 *
 * @apiNote 内部基础设施能力，业务 Page 不应直接调用；仅由 {@link PlaywrightContextManager} 在创建接缝处调用。
 */
public final class PageEventMonitor {

    private static final Logger logger = LoggerFactory.getLogger(PageEventMonitor.class);

    /** 已注册页面的幂等去重集合（由 {@code page.onClose} 清理保证不泄漏）。 */
    private static final Set<Page> REGISTERED =
            Collections.newSetFromMap(new ConcurrentHashMap<>());

    /** 当前测试线程待上报的未捕获页面异常集合（步骤结束时经 Serenity 检查消费并清空）。 */
    @SuppressWarnings("unchecked")
    private static final ContextKey<List> PENDING_PAGE_ERRORS_KEY =
            ContextKey.of("pageEventMonitor.pendingPageErrors", List.class);

    private PageEventMonitor() {
    }

    /**
     * 注册整个 BrowserContext 的页面级诊断监听。
     * 通过 {@code context.onPage} 覆盖所有新建页面（含 {@code window.open} 弹窗、{@code context.newPage()}），
     * 每个页面仅注册一次（幂等）。
     *
     * @param context 浏览器上下文（null 安全：直接忽略）
     */
    public static void register(BrowserContext context) {
        if (context == null) {
            return;
        }
        context.onPage(PageEventMonitor::register);
    }

    /**
     * 注册单个 Page 的诊断监听（幂等）。
     *
     * @param page 目标页面（null 安全：直接忽略）
     */
    public static void register(Page page) {
        if (page == null || !REGISTERED.add(page)) {
            return;
        }
        page.onPageError(PageEventMonitor::handlePageError);
        page.onConsoleMessage(PageEventMonitor::handleConsoleMessage);
        page.onRequestFailed(PageEventMonitor::handleRequestFailed);
        page.onCrash(PageEventMonitor::handleCrash);
        // 页面关闭时移出集合，避免跨 scenario 残留
        page.onClose(REGISTERED::remove);
    }

    /** 未捕获 JS 异常：记录错误级日志；开启"页面异常即失败"开关时收集，待步骤结束上报 Serenity。 */
    private static void handlePageError(String error) {
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

    /** 控制台消息：仅关注 error / warning，避免 log 噪声。 */
    private static void handleConsoleMessage(ConsoleMessage message) {
        String type = message.type();
        if ("error".equals(type)) {
            logger.error("[console-error] {}", message.text());
        } else if ("warning".equals(type)) {
            logger.warn("[console-warning] {}", message.text());
        }
    }

    /** 网络请求失败（超时/断网）：记录警告级日志。 */
    private static void handleRequestFailed(Request request) {
        logger.warn("[request-failed] {} {}", request.method(), request.url());
    }

    /** 页面崩溃：记录警告级日志。 */
    private static void handleCrash(Page crashed) {
        logger.warn("[page-crash] Page crashed: {}", crashed.url());
    }
}
