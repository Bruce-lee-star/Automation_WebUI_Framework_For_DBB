package com.hsbc.cmb.hk.dbb.automation.framework.web.page.engine;

import com.hsbc.cmb.hk.dbb.automation.framework.web.config.FrameworkConfigManager;
import com.hsbc.cmb.hk.dbb.automation.framework.web.config.WebFrameworkConfig;
import com.hsbc.cmb.hk.dbb.automation.framework.web.exceptions.NavigationException;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.DriverRaceErrors;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.config.PlaywrightConfigManager;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.Response;
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
        long timeout = resolvedTimeout(config);
        options.setTimeout(timeout);
        //  两步导航（零配置加固）：把"取响应状态码"和"等加载状态"拆开。
        //   ① 第一步固定 COMMIT：只要收到响应头就返回 ⇒ 【状态码当场可知】，403（访问被拒）在这一刻
        //      就抛错，不再依赖"配置的加载状态能否达到"。原先把 waitUntil 直接配成 LOAD/NETWORKIDLE 时，
        //      403 页面可能等不到 load 事件（长连接/第三方资源）⇒ navigate 抛 TimeoutError ⇒
        //      真因从"403"退化成"导航超时"，又变成误导性失败。
        //   ② 第二步显式等业务配置的加载状态，语义与原来 navigate(waitUntil=配置值) 等价。
        //  导航"成功"≠页面可用：不在此收口就表现为"页面导航出现 403 就不动了"（干等元素超时）。
        //  两步共享同一个 navigationTimeout 预算（deadline 记账），故最坏耗时仍是 1×，不会翻倍。
        options.setWaitUntil(WaitUntilState.COMMIT);
        LoadState configuredState = configuredLoadState(pageLoadState);
        long deadlineNanos = System.nanoTime() + timeout * 1_000_000L;
        try {
            Response response = bp.getPage().navigate(url, options);
            //  先判拦截状态：403 在这里就抛，绝不等加载状态、更不等元素
            NavigationStatusGuard.enforce(response, url);
            waitForConfiguredState(bp, configuredState, remainingMs(deadlineNanos), url);
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
            PlaywrightException retryError = selfHealOnceAfterSettle(bp, url, options, configuredState, deadlineNanos, race, e);
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
                                                               LoadState configuredState, long deadlineNanos,
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
            Response response = bp.getPage().navigate(url, options);
            //  自愈成功同样要校验落点状态：驱动竞态掩盖下的 403 不能因为"重试成功"就放行
            NavigationStatusGuard.enforce(response, url);
            //  重试的加载状态等待同样只花剩余预算（navigate 仍沿用原 options 的完整超时：重试是异常路径，
            //  与改动前"复用同一 options"的行为一致）
            waitForConfiguredState(bp, configuredState, remainingMs(deadlineNanos), url);
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

    /**
     * 配置的加载状态 → {@link LoadState}，与 {@code WaitUntilState} 取值一一对应。
     * {@code commit} 返回 {@code null}：响应到达即算完成，第二步无需再等。
     */
    private static LoadState configuredLoadState(String pageLoadState) {
        if (pageLoadState == null || pageLoadState.isBlank()) {
            return LoadState.LOAD; // 配置缺失/为空：与"未知取值"同样回落 LOAD（原 switch 的 default 语义）
        }
        switch (pageLoadState.toLowerCase()) {
            case "networkidle":
                return LoadState.NETWORKIDLE;
            case "domcontentloaded":
                return LoadState.DOMCONTENTLOADED;
            case "commit":
                return null;
            default:
                return LoadState.LOAD;
        }
    }

    /**
     * 两步导航的第二步：等业务配置的加载状态，只花<b>剩余预算</b>；{@code null}（配置为 commit）直接返回。
     *
     * <p>超时抛 {@link TimeoutError}，由调用方按既有语义映射为 {@link NavigationException}。</p>
     *
     * @param budgetMs 剩余预算（毫秒）；{@code <= 0} 表示预算已在"提交"阶段耗尽 —— 此时按原
     *                 {@code navigate(waitUntil=配置值, timeout)} 的失败语义抛
     *                 {@link NavigationException}。<b>绝不把 0 传下去</b>：Playwright 里
     *                 {@code timeout=0} 是"永不超时"，会把用例挂死。
     */
    private static void waitForConfiguredState(BasePage bp, LoadState state, long budgetMs, String url) {
        if (state == null) {
            return;
        }
        if (budgetMs <= 0) {
            throw new NavigationException(url,
                    "load state not reached: navigation budget exhausted while committing the request");
        }
        bp.getPage().waitForLoadState(state, new Page.WaitForLoadStateOptions().setTimeout(budgetMs));
    }

    /** 导航超时解析：配置 0/负在 Playwright 语义里是"永不超时"，回落正数兜底避免死等。 */
    private static long resolvedTimeout(PlaywrightConfigManager config) {
        long timeout = config.getNavigationTimeout();
        return timeout <= 0 ? DEFAULT_NAVIGATION_TIMEOUT_MS : timeout;
    }

    /** 剩余预算（毫秒，下限 0）：让"提交 + 加载状态"两步共享同一个 navigationTimeout，最坏耗时仍是 1×。 */
    private static long remainingMs(long deadlineNanos) {
        return Math.max((deadlineNanos - System.nanoTime()) / 1_000_000L, 0);
    }

    public static String getCurrentUrl(BasePage bp) {
        return bp.getPage().url();
    }

    public static String getTitle(BasePage bp) {
        return bp.getPage().title();
    }

    /**
     * 刷新：与 {@link #navigateTo} 同款两步走（COMMIT 拿响应 → 判 403 → 等配置的加载状态）。
     *
     * <p><b>为什么要统一</b>：原先直调 {@code reload()} 且不传 options，于是走 Playwright 默认
     * {@code waitUntil=load} —— 既不读 {@code playwright.page.load.state}，又会在"load 事件不触发的门户"
     * 上稳定超时（本项目配置注释已明确该现象）。</p>
     */
    public static void refresh(BasePage bp) {
        historyNavigation(bp, "refresh", timeout -> {
            Page.ReloadOptions options = new Page.ReloadOptions();
            options.setTimeout(timeout);
            options.setWaitUntil(WaitUntilState.COMMIT);
            return bp.getPage().reload(options);
        });
    }

    /** 后退：两步走同 {@link #refresh}。 */
    public static void back(BasePage bp) {
        historyNavigation(bp, "back", timeout -> {
            Page.GoBackOptions options = new Page.GoBackOptions();
            options.setTimeout(timeout);
            options.setWaitUntil(WaitUntilState.COMMIT);
            return bp.getPage().goBack(options);
        });
    }

    /** 前进：两步走同 {@link #refresh}。 */
    public static void forward(BasePage bp) {
        historyNavigation(bp, "forward", timeout -> {
            Page.GoForwardOptions options = new Page.GoForwardOptions();
            options.setTimeout(timeout);
            options.setWaitUntil(WaitUntilState.COMMIT);
            return bp.getPage().goForward(options);
        });
    }

    /** 历史类导航（refresh / back / forward）的动作：三者 options 类型不共享基类，故由调用方构造。 */
    @FunctionalInterface
    private interface NavigationAction {
        Response apply(long timeoutMs);
    }

    /**
     * refresh / back / forward 的统一两步走：COMMIT 提交 → 判 403 → 等配置的加载状态 → 重置 iframe 上下文。
     * 与 {@link #navigateTo} 共用同一套判定与预算记账（两步共享一个 navigationTimeout，最坏耗时 1×）。
     *
     * @param operation 操作名（日志用）
     * @param action    实际导航动作（超时由本方法给出，返回值须为主文档响应）
     */
    private static void historyNavigation(BasePage bp, String operation, NavigationAction action) {
        PlaywrightConfigManager config = bp.getConfig();
        long timeout = resolvedTimeout(config);
        LoadState configuredState = configuredLoadState(config.getPageLoadState());
        long deadlineNanos = System.nanoTime() + timeout * 1_000_000L;
        // 历史类导航没有显式目标 URL：失败信息用操作发生时的当前地址
        String url = bp.getPage().url();
        try {
            Response response = action.apply(timeout);
            NavigationStatusGuard.enforce(response, url);
            waitForConfiguredState(bp, configuredState, remainingMs(deadlineNanos), url);
            logger.debug("{} completed (waitUntil={}): {}", operation, config.getPageLoadState(), url);
            bp.resetFrameContextAfterNavigation();
        } catch (TimeoutError e) {
            // TimeoutError 必须放在 PlaywrightException 前面（因为 TimeoutError 继承 PlaywrightException）
            throw new NavigationException(url, config.getNavigationTimeout(), e);
        }
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
