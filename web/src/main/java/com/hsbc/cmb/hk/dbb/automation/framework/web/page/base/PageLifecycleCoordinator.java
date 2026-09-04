package com.hsbc.cmb.hk.dbb.automation.framework.web.page.base;

import com.hsbc.cmb.hk.dbb.automation.framework.common.config.VerboseLogging;
import com.hsbc.cmb.hk.dbb.automation.framework.web.exceptions.TimeoutException;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.scan.RoleElementPicker;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.PlaywrightException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 页面生命周期编排子模块（T5-5 拆分，模块 5）。
 * <p>原 {@link BasePage} 的「页面切换 / 弹窗 / 下载 / 关闭」编排逻辑下沉至此。
 * <p>本类与 BasePage 同包（非 delegate 子包），以便直接调用其包级私有生命周期 seam
 * （{@code isPageClosed} / {@code onPageSwitched} / {@code setPageReference} /
 * {@code safeBringToFront} / {@code findLastAvailablePage}）；业务 Page 因处于不同包，
 * 编译期即无法访问这些 seam，杜绝误用。其余逻辑仅依赖 {@link BasePage} 公开 API
 * （{@code getContext} / {@code getPage} / {@code getPageRaw} / {@code ensureContextValid} 等），
 * 行为零回归；公开 API 不变（所有编排入口均为 additive 的静态委派）。
 * <p>与既有共享 Browser 模式（T3-2 扩展）兼容：本编排逻辑只切换/关闭 Page，不关闭共享 Browser
 * （由 {@code cleanupAll()} 收口），避免在共享 Browser 场景下误杀整个会话。
 */
public final class PageLifecycleCoordinator {

    // 路由回 BasePage 的日志类别，保证生产日志溯源与原实现一致（委派类跨包无法直访 BasePage.logger）。
    private static final Logger log = LoggerFactory.getLogger(BasePage.class);

    private PageLifecycleCoordinator() {
        // 纯静态工具类，禁止实例化
    }

    /**
     * 入参校验：委派方法均为公开静态 API，必须防止业务方误传 null 而抛裸 NPE。
     * 抛 {@link IllegalArgumentException} 以给出语义化错误，便于生产溯源与快速定位。
     */
    private static void requireNonNullPage(BasePage bp) {
        if (bp == null) {
            throw new IllegalArgumentException(
                    "BasePage instance must not be null when performing lifecycle operations");
        }
    }

    /**
     * 按索引切换到指定页面（Page），负数表示从末尾倒数（-1 = 最后一个）。
     * 对标 Selenium {@code switchTo().window()}。内置 isClosed 守卫：负数索引若目标已关闭，
     * 自动向前回退到第一个未关闭的页面。
     *
     * @param bp    BasePage 实例
     * @param index 页面索引，支持负数（-1 = 最后一个，-2 = 倒数第二个…）
     */
    public static void switchToPage(BasePage bp, int index) {
        requireNonNullPage(bp);
        bp.ensureContextValid();
        List<Page> pages = bp.context.pages();
        if (pages.isEmpty()) throw new TimeoutException("No pages available in context");

        int resolved = index >= 0 ? index : pages.size() + index;
        if (resolved < 0 || resolved >= pages.size())
            throw new IndexOutOfBoundsException("Invalid page index: " + index);

        Page target = pages.get(resolved);

        // 负数索引场景：目标可能已关闭，从该位置向前回退
        if (index < 0 && bp.isPageClosed(target)) {
            target = bp.findLastAvailablePage(pages, resolved);
        }
        if (bp.isPageClosed(target))
            throw new TimeoutException("Target page at index " + index + " is closed");

        bp.setPageReference(target);
        bp.safeBringToFront();
        bp.onPageSwitched();
        logPageSwitchInfo(bp);
    }

    /**
     * 切换到指定的 Page 实例（用于 waitForPopup 等 Playwright API 捕获到的外部 Page）。
     * 与 {@link #waitForNewPage(BasePage, Runnable, int)} 不同，本方法跳过事件监听，
     * 直接使用调用方已捕获的 Page 引用。
     *
     * @param bp   BasePage 实例
     * @param page 目标页面（Page 实例，不能为 null 或已关闭）
     * @return 切换后的 Page
     */
    public static Page switchToPage(BasePage bp, Page page) {
        requireNonNullPage(bp);
        if (page == null) {
            throw new IllegalArgumentException("page must not be null");
        }
        if (page.isClosed()) {
            throw new TimeoutException("Target page is already closed");
        }
        bp.ensureContextValid();
        bp.setPageReference(page);
        bp.safeBringToFront();
        bp.onPageSwitched();
        logPageSwitchInfo(bp);
        return page;
    }

    /**
     * 触发操作并等待新页面打开，对标 Selenium {@code switchTo().newWindow()}。
     * 基于 Playwright 原生 {@code context.waitForPage(action)} 在浏览器事件级捕获新 Tab。
     *
     * @param bp          BasePage 实例
     * @param trigger     触发新页面打开的操作（如点击链接）
     * @param timeoutSecs 等待超时秒数
     * @return 新打开的 Page 实例
     */
    public static Page waitForNewPage(BasePage bp, Runnable trigger, int timeoutSecs) {
        requireNonNullPage(bp);
        bp.ensureContextValid();
        try {
            return acceptNewPage(bp, bp.context.waitForPage(() -> trigger.run()));
        } catch (PlaywrightException e) {
            throw new TimeoutException("Waiting for new page timed out after " + timeoutSecs + " seconds", e);
        }
    }

    /**
     * 仅等待新页面（不触发操作），适用场景：前序步骤已触发新 Tab，本方法负责等待+切换。
     * 先检查是否已有新页面（快速路径），若无则通过 {@code context.waitForPage()} 注册事件监听。
     *
     * @param bp          BasePage 实例
     * @param timeoutSecs 等待超时秒数
     * @return 新打开的 Page 实例
     */
    public static Page waitForNewPage(BasePage bp, int timeoutSecs) {
        requireNonNullPage(bp);
        bp.ensureContextValid();
        // 快速路径：前序步骤可能已触发新页面，直接检查是否已存在
        for (int i = bp.context.pages().size() - 1; i >= 0; i--) {
            Page p = bp.context.pages().get(i);
            if (p != bp.getPageRaw() && !bp.isPageClosed(p)) {
                return acceptNewPage(bp, p);
            }
        }
        // 慢路径：注册 Playwright 原生 page 事件监听
        try {
            return acceptNewPage(bp, bp.context.waitForPage(() -> {}));
        } catch (PlaywrightException e) {
            throw new TimeoutException("Waiting for new page timed out after " + timeoutSecs + " seconds", e);
        }
    }

    /**
     * 等待下载：在 {@code trigger} 触发的一次下载完成前阻塞。
     * 框架已通过 {@code setAcceptDownloads(true)} 开启下载能力，下载文件自动保存到配置的下载目录。
     *
     * @param bp          BasePage 实例
     * @param trigger     触发下载的操作（如点击下载链接）
     * @param timeoutSecs 等待超时秒数
     */
    public static void waitForDownload(BasePage bp, Runnable trigger, int timeoutSecs) {
        requireNonNullPage(bp);
        Page page = bp.getPage();
        try {
            page.waitForDownload(new Page.WaitForDownloadOptions().setTimeout((long) timeoutSecs * 1000),
                    () -> trigger.run());
        } catch (PlaywrightException e) {
            throw new TimeoutException("Waiting for download timed out after " + timeoutSecs + " seconds", e);
        }
    }

    /**
     * 关闭当前页面并自动切换到前一个页面。
     * 若当前已是最前页面则切换到 index 0；不会关闭唯一页面。
     *
     * @param bp BasePage 实例
     */
    public static void closeCurrentPage(BasePage bp) {
        requireNonNullPage(bp);
        bp.ensureContextValid();
        List<Page> pages = bp.context.pages();

        if (pages.isEmpty()) {
            VerboseLogging.logWarnIfVerbose(log, "No pages available in context");
            bp.page = null;
            return;
        }

        if (pages.size() <= 1) {
            VerboseLogging.logWarnIfVerbose(log,
                    "Only one page available (size={}), skipping close to avoid losing the last page", pages.size());
            Page onlyPage = pages.get(0);
            if (bp.page != onlyPage) {
                bp.setPageReference(onlyPage);
                bp.onPageSwitched();
            }
            return;
        }

        int currentIndex = pages.indexOf(bp.page);
        try {
            if (bp.page != null && !bp.page.isClosed()) {
                // 标记本页为"框架主动关闭"：onClose 据此不再补登记 closeCurrentPage 步骤
                // （代码已显式调用 closeCurrentPage，重复登记会导致回放重复关闭）。
                RoleElementPicker.markFrameworkClose(bp.page);
                bp.page.close();
            } else {
                VerboseLogging.logDebugIfVerbose(log,
                        "Current page reference is null or already closed, skip close()");
            }
        } catch (Exception e) {
            VerboseLogging.logWarnIfVerbose(log,
                    "Exception while closing current page: {}", e.getMessage());
        }

        List<Page> updatedPages = bp.context.pages();
        if (updatedPages.isEmpty()) {
            VerboseLogging.logWarnIfVerbose(log,
                    "No pages available after closing current page, page reference will be null");
            bp.page = null;
            return;
        }
        int targetIndex = Math.max(0, Math.min(currentIndex, updatedPages.size()) - 1);
        bp.setPageReference(updatedPages.get(targetIndex));
        bp.onPageSwitched();
    }

    /**
     * 关闭除当前页面之外的所有其他页面，保持当前页面为 context 内唯一页面。
     * 若仅剩 1 个页面或 context 为空则不执行任何关闭操作。
     *
     * @param bp BasePage 实例
     */
    public static void closeOtherPages(BasePage bp) {
        requireNonNullPage(bp);
        bp.ensureContextValid();
        List<Page> pages = bp.context.pages();
        if (pages.size() <= 1) {
            VerboseLogging.logInfoIfVerbose(log,
                    "closeOtherPages skipped: page count={}, nothing to close", pages.size());
            return;
        }

        for (Page p : pages) {
            if (p == bp.page) continue;
            try {
                if (!p.isClosed()) {
                    // 标记为"框架主动关闭"，onClose 不再补登记 closeCurrentPage 步骤。
                    RoleElementPicker.markFrameworkClose(p);
                    p.close();
                }
            } catch (Exception e) {
                VerboseLogging.logWarnIfVerbose(log,
                        "Exception while closing other page: {}", e.getMessage());
            }
        }
        VerboseLogging.logInfoIfVerbose(log,
                "closeOtherPages done: closed {} other pages, current page retained",
                pages.size() - 1);
    }

    /** 新页面校验 + 切换 + 日志，供两个重载共用 */
    private static Page acceptNewPage(BasePage bp, Page newPage) {
        try {
            if (newPage.isClosed()) {
                throw new TimeoutException("New page was created but already closed");
            }
        } catch (Exception e) {
            if (e instanceof TimeoutException) throw (TimeoutException) e;
            VerboseLogging.logWarnIfVerbose(log,
                    "isClosed() check failed, page may already be gone: {}", e.getMessage());
            throw new TimeoutException("New page is no longer available (closed/destroyed)");
        }
        bp.setPageReference(newPage);
        bp.safeBringToFront();
        bp.onPageSwitched();
        logPageSwitchInfo(bp);
        return newPage;
    }

    /** 安全记录页面切换日志（url/title 可能在 page 已关闭时抛异常）。 */
    private static void logPageSwitchInfo(BasePage bp) {
        try {
            log.info("Switch to page: url={}, title={}", bp.getPageRaw().url(), bp.getPageRaw().title());
        } catch (Exception e) {
            VerboseLogging.logWarnIfVerbose(log, "Unable to log new page info (url/title): {}", e.getMessage());
        }
    }
}
