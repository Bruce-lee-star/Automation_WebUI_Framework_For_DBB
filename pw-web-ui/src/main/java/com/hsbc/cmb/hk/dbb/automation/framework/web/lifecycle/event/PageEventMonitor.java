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
 * @apiNote 内部基础设施能力，业务 Page 不应直接调用；仅由 {@link PlaywrightContextManager} 在创建接缝处调用。
 */
public final class PageEventMonitor {

    private static final Logger logger = LoggerFactory.getLogger(PageEventMonitor.class);

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

    private PageEventMonitor() {
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
        if (context == null || !REGISTERED_CONTEXTS.add(context)) {
            return;
        }
        context.onPage(PageEventMonitor::register);
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
        page.onPageError(PageEventMonitor::handlePageError);
        page.onConsoleMessage(PageEventMonitor::handleConsoleMessage);
        page.onRequestFailed(PageEventMonitor::handleRequestFailed);
        page.onCrash(PageEventMonitor::handleCrash);
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
        } else  {if ("warning".equals(type)) {
            logger.warn("[console-warning] {}", message.text());
        }} 
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
