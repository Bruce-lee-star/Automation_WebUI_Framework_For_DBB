package com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.browser;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.PlaywrightManager;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.PlaywrightRuntime;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.state.PlaywrightRuntimeState;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.provider.DefaultRuntimeProvider;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.concurrent.ConcurrentContextExecutor;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.concurrent.ConcurrentContextOptions;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.concurrent.ContextTask;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.concurrent.ContextTaskResult;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.event.PageEventMonitor;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.config.PlaywrightConfigManager;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.config.ProxyConfigResolver;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.browser.BrowserRegistry;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.browser.BrowserRegistryImpl;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.browser.BrowserStartup;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.browser.BrowserStartupImpl;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.browser.BrowserRestart;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.browser.BrowserRestartImpl;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.browser.BrowserCleanup;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.browser.BrowserCleanupImpl;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.context.ContextRegistry;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.context.ContextRegistryImpl;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.context.CustomOptions;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.context.CustomOptionsManager;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.page.PageRegistry;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.page.PageRegistryImpl;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.scenario.ScenarioLifecycle;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.serenity.PlaywrightSerenityBridge;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.serenity.SerenityBusBridge;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.serenity.TestContextBridge;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.bootstrap.PlaywrightContextManager;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.bootstrap.PlaywrightInitializer;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.media.PlaywrightScreenshotManager;

import com.hsbc.cmb.hk.dbb.automation.framework.web.config.WebFrameworkConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 浏览器崩溃韧性守卫（设计文档第九节 9.6 / 风险 R7）。
 *
 * <p>共享 Browser 模式下一个 Browser 进程是全部并发任务的单点：进程崩溃会让所有任务失败。
 * 本守卫把"崩溃检测"与"失败后单次 replay"收口，与 {@link ConcurrentContextExecutor} 配合：
 * 当某任务因浏览器崩溃失败时，在<b>进程级单飞锁</b>内重建共享 Browser（{@link PlaywrightManager#rebuildSharedBrowserIfDisconnected}），
 * 再重跑该任务一次。非共享模式下每个 worker 线程持有独立 Browser，{@link PlaywrightManager#getBrowser()} 已能自动重建，
 * 故守卫仅做"崩溃检测 + 放行重跑"，不执行进程级操作。</p>
 *
 * <p><b>身份亲和</b>：replay 在<b>原 worker 线程</b>上重跑，上下文为 per-thread，重建后新建的 Context 自动继承该线程的
 * 身份维度（{@code TestContext} 在线程上持续存在），因此同身份任务自然复用同一重建后 Context，无需额外亲和逻辑。</p>
 *
 * <p><b>只重跑崩溃型失败</b>：{@link #isCrash} 仅基于明确的崩溃信号（消息特征）判定，
 * <b>不再按异常类名模糊匹配</b>（{@code PlaywrightException} 既承载崩溃也承载元素超时 / 断言失败，按类名一律判崩溃会掩盖真实缺陷）；
 * 正常业务失败（断言、超时等）不触发重跑。重跑严格有界为一次，避免崩溃持续时的无限循环。</p>
 *
 * <p>总开关 {@code CONCURRENCY_BROWSER_CRASH_GUARD_ENABLED} 默认 {@code true}（纯韧性增强）；关闭时退化为
 * "失败直接随 {@link com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.concurrent.ContextTaskResult} 返回"，行为不变。</p>
 */
public final class BrowserCrashGuard {

    private static final Logger LOGGER = LoggerFactory.getLogger(BrowserCrashGuard.class);

    /** 进程级单飞锁：保证并发崩溃的多个任务中只有一个真正执行重建。 */
    private static final Object REBUILD_LOCK = new Object();

    /** 重跑严格有界次数。 */
    private static final int MAX_REPLAY = 1;

    private static final AtomicLong REBUILD_COUNT = new AtomicLong();

    /**
     * 崩溃型失败的消息特征（小写子串匹配，遍历 cause 链）。
     *
     * <p><b>白名单收窄原则（WEB-P1-4）</b>：仅列入<b>明确的浏览器/进程崩溃信号</b>，
     * 不含任何会被正常业务流程触发的内容：
     * <ul>
     *   <li>移除 {@code "execution context was destroyed"}：正常 SPA 导航竞态也会抛此消息，误判会掩盖真实缺陷；</li>
     *   <li>移除 {@code "browser process"}：与启动期消息（"Waiting for the browser process to start"）子串重叠，误判；</li>
     *   <li>补 {@code "target crashed"}：Playwright 页面崩溃的真实消息形如 "Target crashed"。</li>
     * </ul>
     * 元素超时 / 断言失败（即使包在 {@code PlaywrightException} 中）不含以下任一信号，故不会触发重跑。
     */
    private static final List<String> CRASH_SIGNATURES = List.of(
            "target crashed",                                   // Playwright 页面/目标崩溃
            "browser has been closed",                            // 共享 Browser 被关闭
            "browser is closed",
            "target page, context or browser has been closed",
            "target closed",                                     // 目标关闭（崩溃常见消息）
            "connection closed",                                 // 浏览器/管道连接断开
            "connection prematurely closed",
            "playwright has been closed",
            "browser disconnected",                              // 进程断开
            "browser crashed"
    );

    /** 实际恢复动作（默认走 PlaywrightManager 真实共享浏览器重建；单测可注入桩）。 */
    private static volatile RecoveryAction recoveryAction = PlaywrightManager::rebuildSharedBrowserIfDisconnected;

    /** 强制恢复动作（句柄损坏场景）：默认走 PlaywrightManager 真实无条件重建；单测可注入桩。 */
    private static volatile RecoveryAction forcedRecoveryAction = PlaywrightManager::rebuildSharedBrowser;

    private BrowserCrashGuard() {
    }

    /** 浏览器恢复动作：返回是否执行了重建（或无需重建）。 */
    @FunctionalInterface
    public interface RecoveryAction {
        boolean rebuild() throws Exception;
    }

    /** 测试用：注入恢复动作（传 null 复位为默认真实重建）。 */
    public static void setRecoveryActionForTesting(RecoveryAction action) {
        recoveryAction = action == null ? PlaywrightManager::rebuildSharedBrowserIfDisconnected : action;
    }

    /** 测试用：注入强制恢复动作（传 null 复位为默认真实无条件重建）。 */
    public static void setForcedRecoveryActionForTesting(RecoveryAction action) {
        forcedRecoveryAction = action == null ? PlaywrightManager::rebuildSharedBrowser : action;
    }

    /**
     * 守卫是否启用。经 {@code WebFrameworkConfig} → {@code ConfigSource} 统一解析链
     * （实时 {@code System.getProperty} → 环境变量 → Serenity(serenity.conf/properties) → 默认值），
     * 故系统属性与环境变量均可覆盖，与框架其它配置一致。默认 {@code true}（纯韧性增强）。
     */
    public static boolean isEnabled() {
        return WebFrameworkConfig.CONCURRENCY_BROWSER_CRASH_GUARD_ENABLED.getBooleanValue();
    }

    /**
     * 判断异常是否由浏览器崩溃引起（仅基于消息特征，遍历 cause 链）。
     *
     * <p><b>不再按异常类名模糊匹配</b>：评审问题 W-15 指出，{@code PlaywrightException} 同时承载崩溃与
     * 元素超时 / 断言失败等正常业务失败，按类名一律判为崩溃会让"重跑一次就好"掩盖真实缺陷（假绿）。
     * 因此仅当异常消息包含 {@link #CRASH_SIGNATURES} 中<b>明确的崩溃信号</b>时才判为崩溃，触发自动重跑；
     * 元素超时 / 断言失败因不含任何崩溃信号而不会触发重跑。</p>
     *
     * @param t 任务抛出的异常（可为 null）
     * @return true 表示疑似浏览器/进程崩溃，应触发重跑
     */
    public static boolean isCrash(Throwable t) {
        Throwable cur = t;
        while (cur != null) {
            String msg = cur.getMessage();
            if (msg != null) {
                String m = msg.toLowerCase(Locale.ROOT);
                for (String sig : CRASH_SIGNATURES) {
                    if (m.contains(sig)) {
                        return true;
                    }
                }
            }
            cur = cur.getCause();
        }
        return false;
    }

    /**
     * 判断异常是否由 Chromium 句柄注册表损坏引起（{@code __adopt__} / {@code previewUpdated}）。
     *
     * <p>此类损坏下 {@code isConnected()} 恒为 true，断开型重建（{@link #recover()}）会落空，
     * 必须走强制重建（{@link #recoverForced()} → {@link PlaywrightManager#rebuildSharedBrowser()}）。
     * 仅做窄签名识别：普通崩溃（如 "browser has been closed"）不误判为句柄损坏。</p>
     *
     * @param t 任务抛出的异常（可为 null）
     * @return true 表示疑似句柄注册表损坏，应触发强制重建重跑
     */
    public static boolean isHandleCorruption(Throwable t) {
        Throwable cur = t;
        while (cur != null) {
            String msg = cur.getMessage();
            if (msg != null) {
                String m = msg.toLowerCase(Locale.ROOT);
                if (m.contains("__adopt__") || m.contains("previewupdated")) {
                    return true;
                }
            }
            cur = cur.getCause();
        }
        return false;
    }

    /**
     * 进程级单飞恢复：在 {@link #REBUILD_LOCK} 内执行恢复动作（共享模式重建 Browser，非共享模式为 no-op）。
     * 并发崩溃的多个任务中仅首个真正重建，其余等待后也返回（此时 Browser 已连接）。
     *
     * @return true 表示已重建或无需重建（调用方应继续重跑）；false 表示恢复动作抛错
     */
    public static boolean recover() {
        synchronized (REBUILD_LOCK) {
            try {
                boolean rebuilt = recoveryAction.rebuild();
                if (rebuilt) {
                    REBUILD_COUNT.incrementAndGet();
                }
                return rebuilt;
            } catch (Throwable e) {
                LOGGER.error("[browser-crash-guard] recovery action failed; task will not be replayed", e);
                return false;
            }
        }
    }

    /**
     * 进程级单飞<b>强制</b>恢复：在 {@link #REBUILD_LOCK} 内无条件执行强制恢复动作
     * （共享模式重建 Browser，非共享模式为 no-op）。与 {@link #recover()} 共享同一单飞锁，
     * 但使用独立的 {@link #forcedRecoveryAction}（指向 {@code rebuildSharedBrowser}），
     * 故句柄损坏（连接仍在）也能被真正重建。
     *
     * @return true 表示已重建或无需重建（调用方应继续重跑）；false 表示恢复动作抛错
     */
    public static boolean recoverForced() {
        synchronized (REBUILD_LOCK) {
            try {
                boolean rebuilt = forcedRecoveryAction.rebuild();
                if (rebuilt) {
                    REBUILD_COUNT.incrementAndGet();
                }
                return rebuilt;
            } catch (Throwable e) {
                LOGGER.error("[browser-crash-guard] forced recovery action failed; task will not be replayed", e);
                return false;
            }
        }
    }

    /** 重跑次数上限（调用方据此限制重跑，避免无限循环）。 */
    public static int maxReplay() {
        return MAX_REPLAY;
    }

    /** 观测：累计触发恢复次数。 */
    public static long rebuildCount() {
        return REBUILD_COUNT.get();
    }
}
