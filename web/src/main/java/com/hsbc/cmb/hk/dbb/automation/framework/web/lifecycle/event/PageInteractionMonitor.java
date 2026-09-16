package com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.event;

import com.hsbc.cmb.hk.dbb.automation.framework.common.config.VerboseLogging;
import com.hsbc.cmb.hk.dbb.automation.framework.core.context.ContextKey;
import com.hsbc.cmb.hk.dbb.automation.framework.core.context.TestContextHolder;
import com.hsbc.cmb.hk.dbb.automation.framework.web.config.WebFrameworkConfig;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Dialog;
import com.microsoft.playwright.FileChooser;
import com.microsoft.playwright.Frame;
import com.microsoft.playwright.Page;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * 页面交互事件监听注册器（与 {@link PageEventMonitor} 同族，单一职责）。
 *
 * <p><b>定位</b>：集中注册 Playwright {@link Page} 的<b>交互类</b>事件——
 * 导航轨迹（onFrameNavigated）、未受管弹窗（onPopup）、对话框自动处置（onDialog）、
 * 文件选择器自动上传（onFileChooser）——统一在页面/上下文创建接缝处注册一次，业务层零感知、零改动。
 *
 * <p><b>与既有诊断监听的关系</b>：{@link PageEventMonitor} 负责<b>诊断类</b>事件
 * （未捕获异常 / 控制台 / 网络 / 崩溃），本类负责<b>交互类</b>事件；二者均在
 * {@code PlaywrightContextManager.createContext()} 经 {@code context.onPage} 接线，覆盖所有新建页面
 * （含 {@code window.open} 弹窗与 {@code context.newPage()}），职责隔离清晰。
 *
 * <p><b>注册模型</b>：与 PageEventMonitor 一致，经 {@code BrowserContext#onPage} 上下文级注册一次，
 * 1.60+ 保证每个页面仅触发一次，无需自研幂等去重。Interactive 监听仅在页面创建接缝处注册一次。
 *
 * <h3>四项能力（默认全部零行为回归）</h3>
 * <ol>
 *   <li><b>onFrameNavigated（导航轨迹）</b>：始终记录（主框架 INFO，子框架 verbose）。仅在步骤失败时被回放，
 *       用于还原"失败前页面去过哪些地址"，不主动清理 iframe 上下文——失效 Frame 已由
 *       {@code PageContextState.clearStaleFrameContextIfNeeded()} 在每次 {@code ensurePageValid()} 惰性清理，
 *       主动清理会破坏"iframe 在 SPA 路由变化后仍然存活"的合法场景。</li>
 *   <li><b>onPopup（未受管弹窗）</b>：记录 app 自行弹出的新页（window.open / target=_blank）。框架经
 *       {@code switchToPage}/{@code waitForNewPage} 收尾认领的弹窗会被 {@link #markPopupClaimed(Page)} 剔除，
 *       残留者＝未受管弹窗，在步骤失败时告警。默认仅记录，不阻断。</li>
 *   <li><b>onDialog（对话框自动处置）</b>：受 {@code playwright.page.dialog.policy} 控制，默认 {@code dismiss}，
 *       语义等价于 Playwright 无监听时的<b>默认自动 dismiss</b>（零行为回归）；{@code accept}=自动接受；
 *       {@code ignore}=不注册监听，完全交回业务 {@code acceptAlert}/{@code dismissAlert}（默认行为）。
 *       业务显示调用 acceptAlert/dismissAlert 时，经 {@link #declareDialogAction(Page, boolean)} 声明意图，
 *       由本处理器优先按声明执行，避免与显式意图冲突或双重处置。</li>
 *   <li><b>onFileChooser（自动上传）</b>：受 {@code playwright.page.fileChooser.enabled} 控制，<b>默认关闭</b>
 *       （不注册监听，零行为回归）。开启后从配置目录自动 {@code setFiles}，目录为空 / 候选不唯一（无 glob）/
 *       多匹配（有 glob 则全部上传）时安全取消，避免静默传错文件或卡死页面。</li>
 * </ol>
 *
 * @apiNote 内部基础设施能力，业务 Page 不应直接调用；仅由 {@code PlaywrightContextManager} 创建接缝处与
 *           {@code PageContextState.setPageReference} 认领接缝调用。
 */
public final class PageInteractionMonitor {

    private static final Logger logger = LoggerFactory.getLogger(PageInteractionMonitor.class);

    /** 对话框处置策略（由 {@code playwright.page.dialog.policy} 解析）。 */
    public enum DialogPolicy {
        IGNORE, DISMISS, ACCEPT
    }

    /** 业务对对话框的显式意图（按 Page 维度登记，处理器消费后移除）。 */
    private static final Map<Page, DialogAction> DECLARED_DIALOG_ACTIONS =
            Collections.synchronizedMap(new WeakHashMap<>());

    /** 业务声明对话框处置动作。 */
    private enum DialogAction {
        ACCEPT, DISMISS
    }

    /** 每线程导航轨迹（环形缓冲，容量受 {@code playwright.page.navigation.trail.max} 约束）。 */
    @SuppressWarnings("unchecked")
    private static final ContextKey<List> NAV_TRAIL_KEY =
            ContextKey.of("pageInteractionMonitor.navTrail", List.class);

    /** 每线程未受管弹窗记录（被框架认领后移除）。 */
    @SuppressWarnings("unchecked")
    private static final ContextKey<List> UNMANAGED_POPUPS_KEY =
            ContextKey.of("pageInteractionMonitor.unmanagedPopups", List.class);

    private PageInteractionMonitor() {
    }

    // ===================== 注册接缝 =====================

    /**
     * 注册整个 BrowserContext 的页面级交互监听。
     * 通过 {@code context.onPage} 覆盖所有新建页面（含 window.open 弹窗、context.newPage()）。
     *
     * @param context 浏览器上下文（null 安全：直接忽略）
     */
    public static void register(BrowserContext context) {
        if (context == null) {
            return;
        }
        context.onPage(PageInteractionMonitor::register);
    }

    /**
     * 注册单个 Page 的交互监听（按配置条件化）。
     *
     * @param page 目标页面（null 安全：直接忽略）
     */
    public static void register(Page page) {
        if (page == null) {
            return;
        }
        page.onFrameNavigated(PageInteractionMonitor::handleFrameNavigated);
        page.onPopup(PageInteractionMonitor::handlePopup);
        if (isDialogAutoHandlingEnabled()) {
            page.onDialog(PageInteractionMonitor::handleDialog);
        }
        if (isFileChooserEnabled()) {
            page.onFileChooser(PageInteractionMonitor::handleFileChooser);
        }
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
            if (isMain) {
                logger.info("[nav] main frame navigated -> {}", url);
            } else {
                VerboseLogging.logDebugIfVerbose(logger, "[nav] frame '{}' navigated -> {}", frame.name(), url);
            }
            appendNavTrail(new NavEntry(isMain, url));
        } catch (Exception e) {
            logger.debug("[nav] frame navigation observe failed (detached?): {}", e.toString());
        }
    }

    private static void appendNavTrail(NavEntry entry) {
        List<NavEntry> trail = navTrail();
        trail.add(entry);
        int max = WebFrameworkConfig.PLAYWRIGHT_PAGE_NAV_TRAIL_MAX.getIntValue();
        if (max < 1) {
            max = 1;
        }
        while (trail.size() > max) {
            trail.remove(0);
        }
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

    // ===================== 4. 未受管弹窗 =====================

    /** 记录 app 自行弹出的新页（window.open / target=_blank）；框架认领后由 markPopupClaimed 剔除。 */
    private static void handlePopup(Page popup) {
        if (popup == null) {
            return;
        }
        try {
            String url = safeUrl(popup);
            String title = safeTitle(popup);
            logger.info("[popup] unmanaged popup detected: url={}, title={}", url, title);
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

    // ===================== 6. 对话框自动处置 =====================

    /** 对话框自动处置是否启用（policy != ignore）。 */
    public static boolean isDialogAutoHandlingEnabled() {
        return resolveDialogPolicy() != DialogPolicy.IGNORE;
    }

    private static DialogPolicy resolveDialogPolicy() {
        String v = WebFrameworkConfig.PLAYWRIGHT_PAGE_DIALOG_POLICY.getValue();
        if (v == null) {
            return DialogPolicy.DISMISS;
        }
        v = v.trim().toUpperCase();
        for (DialogPolicy p : DialogPolicy.values()) {
            if (p.name().equals(v)) {
                return p;
            }
        }
        return DialogPolicy.DISMISS;
    }

    /**
     * 业务显式声明对话框处置意图（accept/dismiss）。仅在自动处置启用时有效：
     * 成功登记返回 true，调用方（PageInteractions.acceptAlert/dismissAlert）应直接返回，由 onDialog 处理器执行；
     * 未启用返回 false，调用方走原生 onceDialog 路径。
     *
     * @param page  当前页面（null 安全：返回 false）
     * @param accept true=接受，false=拒绝
     * @return 是否已接管（true 表示处理器将处置，调用方无需再注册 onceDialog）
     */
    public static boolean declareDialogAction(Page page, boolean accept) {
        if (!isDialogAutoHandlingEnabled() || page == null) {
            return false;
        }
        DECLARED_DIALOG_ACTIONS.put(page, accept ? DialogAction.ACCEPT : DialogAction.DISMISS);
        return true;
    }

    private static void handleDialog(Dialog dialog) {
        if (dialog == null) {
            return;
        }
        try {
            Page page = safeDialogPage(dialog);
            DialogAction declared = page != null ? DECLARED_DIALOG_ACTIONS.remove(page) : null;
            if (declared != null) {
                applyDialog(declared, dialog, "explicit-business");
                return;
            }
            DialogPolicy policy = resolveDialogPolicy();
            if (policy == DialogPolicy.ACCEPT) {
                applyDialog(DialogAction.ACCEPT, dialog, "policy=accept");
            } else {
                applyDialog(DialogAction.DISMISS, dialog, "policy=dismiss");
            }
        } catch (Exception e) {
            logger.error("[dialog] handler failed (dialog left unhandled may hang the page): {}", e.getMessage(), e);
        }
    }

    private static void applyDialog(DialogAction action, Dialog dialog, String reason) {
        if (action == DialogAction.ACCEPT) {
            logger.info("[dialog] {} dialog accepted ({}): message={}", dialog.type(), reason, dialog.message());
            dialog.accept();
        } else {
            logger.info("[dialog] {} dialog dismissed ({}): message={}", dialog.type(), reason, dialog.message());
            dialog.dismiss();
        }
    }

    // ===================== 7. 文件选择器自动上传 =====================

    /** 自动上传是否启用（playwright.page.fileChooser.enabled）。 */
    public static boolean isFileChooserEnabled() {
        return WebFrameworkConfig.PLAYWRIGHT_PAGE_FILE_CHOOSER_ENABLED.getBooleanValue();
    }

    private static void handleFileChooser(FileChooser fc) {
        if (fc == null) {
            return;
        }
        try {
            String dir = WebFrameworkConfig.PLAYWRIGHT_PAGE_FILE_CHOOSER_DIR.getValue();
            String glob = WebFrameworkConfig.PLAYWRIGHT_PAGE_FILE_CHOOSER_GLOB.getValue();
            List<Path> candidates = resolveUploadCandidates(Paths.get(dir), glob);
            if (candidates.isEmpty()) {
                logger.warn("[upload] no candidate file in '{}' (glob='{}'); dismissing file chooser to avoid hang",
                        dir, glob);
                dismissFileChooser(fc);
                return;
            }
            if (glob == null || glob.isEmpty()) {
                if (candidates.size() > 1) {
                    logger.warn("[upload] {} candidates in '{}' but no glob set (ambiguous); dismissing to avoid wrong upload",
                            candidates.size(), dir);
                    dismissFileChooser(fc);
                    return;
                }
            }
            Path[] paths = candidates.toArray(new Path[0]);
            logger.info("[upload] auto setFiles ({} file(s)): {}", paths.length, java.util.Arrays.toString(paths));
            fc.setFiles(paths);
        } catch (Exception e) {
            logger.error("[upload] auto file-chooser failed; dismissing to avoid hang: {}", e.getMessage(), e);
            dismissFileChooser(fc);
        }
    }

    private static void dismissFileChooser(FileChooser fc) {
        try {
            fc.setFiles(new Path[0]);
        } catch (Exception ignore) {
            logger.debug("[upload] dismiss setFiles failed: {}", ignore.toString());
        }
    }

    /**
     * 解析上传候选文件（包级可见，便于单测）：目录不存在/非目录返回空。
     * 设 glob 时按文件名匹配（可多匹配，确定性排序后全部上传）；
     * 不设 glob 时取目录下全部常规文件（调用方需保证唯一，否则判歧义取消）。
     *
     * @param dir  上传目录
     * @param glob 文件名 glob（可为空）
     * @return 候选文件列表（按文件名排序；永不返回 null）
     */
    static List<Path> resolveUploadCandidates(Path dir, String glob) {
        if (dir == null || !Files.isDirectory(dir)) {
            return Collections.emptyList();
        }
        try {
            List<Path> files = new ArrayList<>();
            if (glob != null && !glob.isEmpty()) {
                PathMatcher matcher = dir.getFileSystem().getPathMatcher("glob:" + glob);
                try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
                    for (Path p : ds) {
                        if (Files.isRegularFile(p) && matcher.matches(p.getFileName())) {
                            files.add(p);
                        }
                    }
                }
            } else {
                try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
                    for (Path p : ds) {
                        if (Files.isRegularFile(p)) {
                            files.add(p);
                        }
                    }
                }
            }
            files.sort(Comparator.comparing(p -> p.getFileName().toString()));
            return files;
        } catch (IOException e) {
            return Collections.emptyList();
        }
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

    private static Page safeDialogPage(Dialog dialog) {
        try {
            return dialog.page();
        } catch (Exception e) {
            return null;
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
