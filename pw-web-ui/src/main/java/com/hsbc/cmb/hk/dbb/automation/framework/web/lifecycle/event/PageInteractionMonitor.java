package com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.event;

import com.hsbc.cmb.hk.dbb.automation.framework.common.config.VerboseLogging;
import com.hsbc.cmb.hk.dbb.automation.framework.web.config.WebFrameworkConfig;
import com.hsbc.cmb.hk.dbb.automation.framework.core.context.ContextKey;
import com.hsbc.cmb.hk.dbb.automation.framework.core.context.TestContext;
import com.hsbc.cmb.hk.dbb.automation.framework.core.context.TestContextHolder;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Frame;
import com.microsoft.playwright.Page;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * 页面交互事件可观测性监控（与 {@link PageEventMonitor} 同族，单一职责）。
 *
 * <p><b>定位</b>：集中注册 Playwright {@link Page} 的<b>交互类</b>事件，仅做<b>可观测性诊断</b>，
 * 不自动处置任何交互（处置交回业务层既有方法）。当前覆盖两类：
 * <ul>
 *   <li><b>导航轨迹</b>（onFrameNavigated）：记录页面去过的地址，失败时回放，还原"失败前页面去过哪些"。</li>
 *   <li><b>未受管弹窗</b>（onPopup）：记录 app 自行弹出的新页（window.open / target=_blank）。框架经
 *       {@code switchToPage}/{@code waitForNewPage} 收尾认领的弹窗由 {@link #markPopupClaimed(Page)} 剔除，
 *       残留者＝未受管弹窗，在步骤失败时告警。</li>
 * </ul>
 *
 * <p><b>与既有诊断监听的关系</b>：{@link PageEventMonitor} 负责<b>诊断类</b>事件
 * （未捕获异常 / 控制台 / 网络 / 崩溃），本类负责<b>交互类</b>事件；二者均在
 * {@code PlaywrightContextManager.createContext()} 经 {@code context.onPage} 接线，覆盖所有新建页面
 * （含 {@code window.open} 弹窗与 {@code context.newPage()}），职责隔离清晰。</p>
 *
 * <p><b>不自动处置交互事件</b>：对话框（alert/confirm/prompt）与文件选择器（fileChooser）的处置
 * 一律交回业务层既有方法（如 {@code BasePage.acceptAlert/dismissAlert}、元素级 {@code setInputFiles}）。
 * 框架<b>不注册</b> onDialog / onFileChooser 监听，既保持 Playwright 默认语义
 * （无监听时对话框默认自动 dismiss；文件选择器由业务自行处理），也避免引入配置项或硬编码目录增加用户学习成本与耦合。</p>
 *
 * <p><b>注册模型</b>：与 PageEventMonitor 一致，经 {@code BrowserContext#onPage} 上下文级注册一次，
 * 1.60+ 保证每个页面仅触发一次，无需自研幂等去重。</p>
 *
 * <p><b>订阅开关（配置驱动）</b>：{@code context.onPage} 扇出由
 * {@code playwright.page.events.page.enabled}（默认 true）控制；{@code onFrameNavigated} /
 * {@code onPopup} 分别由 {@code playwright.page.events.frameNavigated.enabled} /
 * {@code playwright.page.events.popup.enabled}（默认 true）控制。关闭即不订阅对应事件，
 * 同时消除"驱动按 payload guid 解析已回收句柄"造成的对象回收类异常（污染在途调用）。</p>
 *
 * @apiNote 内部基础设施能力，业务 Page 不应直接调用；仅由 {@code PlaywrightContextManager} 创建接缝处与
 *           {@code PageContextState.setPageReference} 认领接缝调用。
 */
public final class PageInteractionMonitor {

    private static final Logger logger = LoggerFactory.getLogger(PageInteractionMonitor.class);

    /** 每线程导航轨迹。 */
    @SuppressWarnings("unchecked")
    private static final ContextKey<List> NAV_TRAIL_KEY =
            ContextKey.of("pageInteractionMonitor.navTrail", List.class);

    /** 每线程未受管弹窗记录（被框架认领后移除）。 */
    @SuppressWarnings("unchecked")
    private static final ContextKey<List> UNMANAGED_POPUPS_KEY =
            ContextKey.of("pageInteractionMonitor.unmanagedPopups", List.class);

    /**
     * 已注册上下文/页面集合（弱引用键，不阻止 GC 堆积；与 {@code PlaywrightManager.CLOSING_BROWSERS} 同款模式）。
     * 用于<b>幂等护栏</b>：register 被重复调用（业务层、上下文重建、反射回归等任何路径）一律降级为 no-op，
     * 杜绝 handler 叠加导致交互诊断被双重处理。
     */
    private static final Set<BrowserContext> REGISTERED_CONTEXTS =
            Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));
    private static final Set<Page> REGISTERED_PAGES =
            Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));

    private PageInteractionMonitor() {
    }

    /**
     * 已知第三方追踪/设备指纹 iframe 的 URL 特征（ThreatMetrix 等）。这类子框架只产生噪声与
     * 导航抖动，不计入诊断轨迹、也不打日志，避免污染失败回放与日志。
     */
    private static final java.util.Set<String> TRACKING_URL_MARKERS = java.util.Set.of(
            "online-metrix.net",             // ThreatMetrix 设备指纹 tag
            "/scripts/prod/crossdomain.html" // ThreatMetrix 跨域通信中继 iframe
    );

    /** 是否为第三方追踪/指纹 iframe 的导航（仅对子框架生效，绝不误伤主框架导航）。 */
    private static boolean isTrackingFrame(String url) {
        if (url == null) {
            return false;
        }
        for (String marker : TRACKING_URL_MARKERS) {
            if (url.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    // ===================== 注册接缝 =====================

    /**
     * 注册整个 BrowserContext 的页面级交互监听。
     * 通过 {@code context.onPage} 覆盖所有新建页面（含 window.open 弹窗、context.newPage()）。
     *
     * <p><b>幂等</b>：同一 {@link BrowserContext} 重复注册只生效一次（见 {@link #REGISTERED_CONTEXTS}）。
     *
     * @param context 浏览器上下文（null 安全：直接忽略）
     */
    public static void register(BrowserContext context) {
        if (!WebFrameworkConfig.PLAYWRIGHT_PAGE_EVENTS_PAGE_ENABLED.getBooleanValue()) {
            return;   // 扇出总闸关闭：连 onPage 都不订阅，本类所有页面级交互监听均不生效
        }
        if (context == null || !REGISTERED_CONTEXTS.add(context)) {
            return;
        }
        context.onPage(PageInteractionMonitor::register);
    }

    /**
     * 注册单个 Page 的交互监听（仅导航轨迹与未受管弹窗两类诊断）。
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
        //  逐事件按配置订阅（playwright.page.events.*，默认均开启）；幂等标记照常记录，重复 register 仍 no-op
        if (WebFrameworkConfig.PLAYWRIGHT_PAGE_EVENTS_FRAME_NAVIGATED_ENABLED.getBooleanValue()) {
            page.onFrameNavigated(PageInteractionMonitor::handleFrameNavigated);
        }
        if (WebFrameworkConfig.PLAYWRIGHT_PAGE_EVENTS_POPUP_ENABLED.getBooleanValue()) {
            page.onPopup(PageInteractionMonitor::handlePopup);
        }
    }

    /**
     * 清理当前线程（用例）的交互监控状态：导航轨迹与未受管弹窗清单。
     *
     * <p><b>用途</b>：scenario 结束时显式调用，防止 worker 线程被线程池复用、且 scenario 上下文未绑定/未解绑的
     * 边缘场景下，导航轨迹与未受管弹窗跨用例堆积。C-1 用例级隔离已覆盖主路径（scenario 上下文随
     * {@code TestContextHolder.resetForCurrentThread()} 解绑），本方法为<b>防御性兜底接缝</b>：
     * 额外保证<b>成功路径</b>也即时归零，且不依赖 {@code drainNavigationTrail/drainUnmanagedPopups}
     * （仅失败时由 {@code StepFailureAggregator} 触发）的回收时机。</p>
     *
     * <p>null 安全：当前无上下文时直接忽略，不抛异常。</p>
     */
    public static void resetForThread() {
        TestContext ctx = TestContextHolder.get();
        if (ctx == null) {
            return;
        }
        ctx.remove(NAV_TRAIL_KEY);
        ctx.remove(UNMANAGED_POPUPS_KEY);
    }

    // ===================== 1. 导航轨迹 =====================

    /** 每次导航（主框架或 iframe）记录一条轨迹；主框架记 INFO，子框架记 verbose。 */
    private static void handleFrameNavigated(Frame frame) {
        if (frame == null) {
            return;
        }
        try {
            Page page = frame.page();
            boolean isMain = page != null && frame == page.mainFrame();
            String url = safeUrl(frame);
            // 第三方追踪/指纹 iframe（ThreatMetrix 等）：只产生噪声与导航抖动，
            // 既不打日志也不计入诊断轨迹（仅对子框架生效，主框架导航照常记录）。
            if (!isMain && isTrackingFrame(url)) {
                return;
            }
            if (isMain) {
                // 主框架导航轨迹本身已记入 navTrail（失败时回放用）；此处日志默认关闭，
                // 仅在 serenity.logging=VERBOSE/TRACE 时输出，避免每次跳转刷屏。
                VerboseLogging.logInfoIfVerbose(logger, "[nav] main frame navigated -> {}", url);
            } else {
                VerboseLogging.logDebugIfVerbose(logger, "[nav] frame '{}' navigated -> {}", frame.name(), url);
            }
            appendNavTrail(new NavEntry(isMain, url));
        } catch (Exception e) {
            logger.debug("[nav] frame navigation observe failed (detached?): {}", e.toString());
        }
    }

    private static void appendNavTrail(NavEntry entry) {
        navTrail().add(entry);
    }

    @SuppressWarnings("unchecked")
    private static List<NavEntry> navTrail() {
        return (List<NavEntry>) TestContextHolder.get().computeIfAbsent(NAV_TRAIL_KEY, ArrayList::new);
    }

    /**
     * 取出并清空当前线程的导航轨迹，渲染为可读文本（失败时回放用，幂等消费）。
     *
     * @return 轨迹文本（每行一条，主框架标 [main]、iframe 标 [frame]）；无则为空串
     */
    @SuppressWarnings("unchecked")
    public static String drainNavigationTrail() {
        List<NavEntry> trail = (List<NavEntry>) TestContextHolder.get().get(NAV_TRAIL_KEY);
        if (trail == null || trail.isEmpty()) {
            return "";
        }
        List<NavEntry> snapshot = new ArrayList<>(trail);
        trail.clear();
        StringBuilder sb = new StringBuilder();
        for (NavEntry e : snapshot) {
            sb.append(e.mainFrame ? "[main] " : "[frame] ").append(e.url).append('\n');
        }
        return sb.toString().trim();
    }

    // ===================== 2. 未受管弹窗 =====================

    /** 记录 app 自行弹出的新页（window.open / target=_blank）；框架认领后由 markPopupClaimed 剔除。 */
    private static void handlePopup(Page popup) {
        if (popup == null) {
            return;
        }
        try {
            String url = safeUrl(popup);
            String title = safeTitle(popup);
            // 未受管弹窗的"逐次检测"日志默认关闭（verbose 才打印）；失败时的聚合告警仍由
            // StepFailureAggregator.drainUnmanagedPopups 负责，不因本行关闭而丢失。
            VerboseLogging.logInfoIfVerbose(logger, "[popup] unmanaged popup detected: url={}, title={}", url, title);
            unmanagedPopups().add(new PopupEntry(popup, url, title));
        } catch (Exception e) {
            logger.debug("[popup] observe failed: {}", e.toString());
        }
    }

    /**
     * 框架经 switchToPage / waitForNewPage / acceptNewPage 收尾认领某个弹窗页时调用，
     * 将其从"未受管"清单移除，避免误报。
     *
     * @param page 被框架认领的页面（null 安全）
     */
    public static void markPopupClaimed(Page page) {
        if (page == null) {
            return;
        }
        List<PopupEntry> popups = unmanagedPopupsRef();
        if (popups == null) {
            return;
        }
        popups.removeIf(p -> p.page == page);
    }

    @SuppressWarnings("unchecked")
    private static List<PopupEntry> unmanagedPopups() {
        return (List<PopupEntry>) TestContextHolder.get().computeIfAbsent(UNMANAGED_POPUPS_KEY, ArrayList::new);
    }

    @SuppressWarnings("unchecked")
    private static List<PopupEntry> unmanagedPopupsRef() {
        return (List<PopupEntry>) TestContextHolder.get().get(UNMANAGED_POPUPS_KEY);
    }

    /**
     * 取出并清空当前线程的未受管弹窗清单，渲染为可读文本（失败时告警用，幂等消费）。
     *
     * @return 弹窗文本（每行一条 url/title）；无则为空串
     */
    @SuppressWarnings("unchecked")
    public static String drainUnmanagedPopups() {
        List<PopupEntry> popups = (List<PopupEntry>) TestContextHolder.get().get(UNMANAGED_POPUPS_KEY);
        if (popups == null || popups.isEmpty()) {
            return "";
        }
        List<PopupEntry> snapshot = new ArrayList<>(popups);
        popups.clear();
        StringBuilder sb = new StringBuilder();
        for (PopupEntry p : snapshot) {
            sb.append("url=").append(p.url).append(", title=").append(p.title).append('\n');
        }
        return sb.toString().trim();
    }

    // ===================== 安全取值工具 =====================

    private static String safeUrl(Frame frame) {
        try {
            return frame.url();
        } catch (Exception e) {
            return "<unknown>";
        }
    }

    private static String safeUrl(Page page) {
        try {
            return page.url();
        } catch (Exception e) {
            return "<unknown>";
        }
    }

    private static String safeTitle(Page page) {
        try {
            return page.title();
        } catch (Exception e) {
            return "<unknown>";
        }
    }

    // ===================== 内部条目 =====================

    private static final class NavEntry {
        final boolean mainFrame;
        final String url;

        NavEntry(boolean mainFrame, String url) {
            this.mainFrame = mainFrame;
            this.url = url;
        }
    }

    private static final class PopupEntry {
        final Page page;
        final String url;
        final String title;

        PopupEntry(Page page, String url, String title) {
            this.page = page;
            this.url = url;
            this.title = title;
        }
    }
}
