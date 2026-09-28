package com.hsbc.cmb.hk.dbb.automation.framework.web.page.engine;

import com.hsbc.cmb.hk.dbb.automation.framework.web.config.FrameworkConfigManager;
import com.hsbc.cmb.hk.dbb.automation.framework.web.config.WebFrameworkConfig;
import com.hsbc.cmb.hk.dbb.automation.framework.web.exceptions.NavigationException;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.DriverRaceErrors;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.config.PlaywrightConfigManager;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.TimeoutError;
import com.microsoft.playwright.options.LoadState;
import com.microsoft.playwright.options.WaitUntilState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 导航子模块（T5-5 拆分）。
 * <p>原 {@link BasePage} 的 {@code navigateTo} / {@code navigateToWithRetry} / {@code getCurrentUrl} /
 * {@code getTitle} / {@code refresh} / {@code back} / {@code forward} / {@code setContent} 方法体下沉至此。
 * <p>仅依赖 {@link BasePage} 公开 API（{@code getPage()} / {@code getConfig()} /
 * {@code resetFrameContextAfterNavigation()}），其中 {@code getPage()} 内部已触发 {@code ensurePageValid()}，
 * 故行为与原实现零差异；公开 API 不变。
 */
public final class PageNavigation {

    private static final Logger logger = LoggerFactory.getLogger(PageNavigation.class);

    /**
     * 导航超时下限兜底：配置项 {@code navigationTimeout} 为 0/负时 Playwright 语义为「无限等待」，
     * 弱网下会永久挂起导航。回落到与 {@code ConfigKeys} 导航超时默认一致的正数，避免死等。
     */
    private static final long DEFAULT_NAVIGATION_TIMEOUT_MS = 15_000;

    /**
     * 「驱动竞态自愈」时等待当前文档收敛的上限（毫秒）。
     * <p>取值只需覆盖"上一次导航的服务端重定向收尾 / 在途事件分发收尾"，实测该窗口 &lt; 1s；
     * 5s 是安全上界，且失败即忽略（不把收敛等待变成新的失败点）。
     */
    private static final long NAVIGATION_SETTLE_TIMEOUT_MS = 5_000;

    private PageNavigation() {
        // 纯静态工具类，禁止实例化
    }

    public static void navigateTo(BasePage bp, String url) {
        PlaywrightConfigManager config = bp.getConfig();
        String pageLoadState = config.getPageLoadState();
        Page.NavigateOptions options = new Page.NavigateOptions();
        long timeout = config.getNavigationTimeout();
        if (timeout <= 0) {
            timeout = DEFAULT_NAVIGATION_TIMEOUT_MS;
        }
        options.setTimeout(timeout);
        // 根据配置设置等待策略
        switch (pageLoadState.toLowerCase()) {
            case "networkidle":
                options.setWaitUntil(WaitUntilState.NETWORKIDLE);
                break;
            case "domcontentloaded":
                options.setWaitUntil(WaitUntilState.DOMCONTENTLOADED);
                break;
            case "commit":
                options.setWaitUntil(WaitUntilState.COMMIT);
                break;
            default:
                options.setWaitUntil(WaitUntilState.LOAD);
        }
        try {
            // navigate 已经根据 options 中的 waitUntil 等待页面加载
            // 不需要再额外 waitForLoadState，避免重复等待
            bp.getPage().navigate(url, options);
            logger.debug("Navigation completed (waitUntil={}): {}", pageLoadState, url);
            bp.resetFrameContextAfterNavigation();
        } catch (TimeoutError e) {
            // TimeoutError 必须放在 PlaywrightException 前面（因为 TimeoutError 继承 PlaywrightException）
            throw new NavigationException(url, config.getNavigationTimeout(), e);
        } catch (PlaywrightException e) {
            //  ═══ 驱动竞态自愈（2026-09-26；判据与设计见 DriverRaceErrors）═══
            //  Playwright Java 的 Connection 是【一条共享连接】：消息在 Connection.processOneMessage 内分发，
            //  而分发线程就是"此刻恰好在等这条连接结果的任意调用"。故驱动内部的解析失败会【以本调用为宿主】
            //  抛出，与导航语义无关。实测（playwright 1.62，内网）：
            //    · Object doesn't exist: response@125692…（BrowserContextImpl.handleEvent:853 → getExistingObject:210）
            //      —— 浏览器侧【其实已完成】导航并 302 到 /logon，仅 Java 侧调用被炸；
            //    · Navigation to "…/logon" is interrupted by another navigation to "…/home"
            //      —— 上一次导航的重定向链尚未收尾。
            //  两者都会让业务把"驱动噪声"误判为"会话失效"→ 删缓存 → 全量重登（实测正是如此）。
            //  该异常发生在业务回调【之前】，业务侧 try/catch 无法拦截，只能在最外层收口。
            //
            //  策略：有界（至多一次）、可关（playwright.navigation.selfheal.enabled）、可观测（WARN+INFO+耗时）、
            //        不掩盖失败（两次都败仍抛 NavigationException，原异常 addSuppressed 保留）。
            DriverRaceErrors.Kind race = DriverRaceErrors.Kind.NONE;
            if (selfHealEnabled()) {
                race = DriverRaceErrors.classify(e);
            }
            if (race == DriverRaceErrors.Kind.NONE) {
                throw new NavigationException(url, "Navigation failed: " + e.getMessage(), e);
            }
            PlaywrightException retryError = selfHealOnceAfterSettle(bp, url, options, race, e);
            if (retryError == null) {
                return; // 自愈成功
            }
            NavigationException failure = new NavigationException(url,
                    "Navigation failed after self-heal [" + race + "]: " + retryError.getMessage(), retryError);
            //  两条链路都留：cause = 重试失败（更接近现状），suppressed = 原始竞态异常（根因）。
            failure.addSuppressed(e);
            throw failure;
        }
    }

    /**
     * 导航「驱动竞态自愈」开关（默认开启，配置键 {@code playwright.navigation.selfheal.enabled}）。
     *
     * <p>读取失败（框架未初始化 / 配置层异常 / classpath 缺失）时按<b>开启</b>处理：自愈是加固手段，
     * 不应因配置读取异常而失效；且它只作用于"可分类的驱动竞态"，不存在误伤语义失败的风险。
     */
    private static boolean selfHealEnabled() {
        try {
            return Boolean.TRUE.equals(
                    FrameworkConfigManager.getBoolean(WebFrameworkConfig.PLAYWRIGHT_NAVIGATION_SELFHEAL_ENABLED));
        } catch (Exception | LinkageError e) {
            logger.debug("Navigation self-heal switch unavailable, defaulting to ENABLED: {}", e.toString());
            return true;
        }
    }

    /**
     * 驱动竞态后的有界自愈：先等当前文档收敛，再<b>原样重试一次</b>导航。
     *
     * <p><b>为何必须先收敛</b>：不等待就重试，在途的那次导航/事件分发仍在，
     * 重试同样可能被判定为"被打断"或再次读到已回收句柄。收敛失败（页面关闭 / 仍在导航 / 超时）
     * 不构成放弃理由 —— 重试本身仍是最优动作。
     *
     * @param race     已分类的竞态类别（用于日志与失败信息）
     * @param original 原始竞态异常（仅用于日志，最终由调用方 addSuppressed 保留）
     * @return {@code null} 表示自愈成功；否则返回重试失败原因（调用方据此抛出更准确的异常）
     */
    private static PlaywrightException selfHealOnceAfterSettle(BasePage bp, String url, Page.NavigateOptions options,
                                                               DriverRaceErrors.Kind race, PlaywrightException original) {
        logger.warn("[Navigation] driver race detected [{}]: {} — settling (≤{}ms) then retrying once: {}",
                race, original.getMessage(), NAVIGATION_SETTLE_TIMEOUT_MS, url);
        long startMs = System.currentTimeMillis();
        try {
            bp.getPage().waitForLoadState(LoadState.DOMCONTENTLOADED,
                    new Page.WaitForLoadStateOptions().setTimeout(NAVIGATION_SETTLE_TIMEOUT_MS));
        } catch (Exception settleError) {
            logger.debug("[Navigation] settle wait before self-heal did not complete (ignored): {}",
                    settleError.getMessage());
        }
        long settleMs = System.currentTimeMillis() - startMs;
        try {
            bp.getPage().navigate(url, options);
            bp.resetFrameContextAfterNavigation();
            logger.info("[Navigation] navigation self-healed [{}] in {}ms (settle {}ms): {}",
                    race, System.currentTimeMillis() - startMs, settleMs, url);
            return null;
        } catch (PlaywrightException retryError) {
            logger.warn("[Navigation] navigation self-heal failed [{}] after {}ms: {}",
                    race, System.currentTimeMillis() - startMs, retryError.getMessage());
            return retryError;
        }
    }

    public static String getCurrentUrl(BasePage bp) {
        return bp.getPage().url();
    }

    public static String getTitle(BasePage bp) {
        return bp.getPage().title();
    }

    public static void refresh(BasePage bp) {
        bp.getPage().reload();
        bp.resetFrameContextAfterNavigation();
    }

    public static void back(BasePage bp) {
        bp.getPage().goBack();
        bp.resetFrameContextAfterNavigation();
    }

    public static void forward(BasePage bp) {
        bp.getPage().goForward();
        bp.resetFrameContextAfterNavigation();
    }

    public static void setContent(BasePage bp, String html) {
        bp.getPage().setContent(html);
        // 替换页面内容后，所有 iframe 均被销毁，必须重置 iframe 上下文
        bp.resetFrameContextAfterNavigation();
    }

    public static void navigateToWithRetry(BasePage bp, String url, int retries) {
        bp.retry(() -> bp.navigateTo(url), retries, 1000, "navigate to: " + url);
    }
}
