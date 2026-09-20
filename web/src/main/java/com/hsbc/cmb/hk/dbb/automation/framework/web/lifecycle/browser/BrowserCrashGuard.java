package com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.browser;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.PlaywrightManager;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.PlaywrightRuntime;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.concurrent.ConcurrentContextExecutor;

import com.hsbc.cmb.hk.dbb.automation.framework.web.config.WebFrameworkConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.microsoft.playwright.PlaywrightException;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 浏览器崩溃韧性守卫（设计文档第九节 9.6 / 风险 R7）。
 *
 * <p>每个 worker 线程持有<b>独立 Browser</b>实例（并发模型为每线程独立 Browser），进程崩溃仅影响所在线程。
 * 本守卫把"崩溃检测"与"失败后单次 replay"收口，与 {@link ConcurrentContextExecutor} 配合：
 * 当某任务因浏览器崩溃失败时，在<b>进程级单飞锁</b>内重建该线程 Browser（{@link PlaywrightManager#rebuildBrowserIfDisconnected}），
 * 再重跑该任务一次。{@link PlaywrightManager#getBrowser()} 在断连时已能自动重建，
 * 故守卫兼做"崩溃检测 + 放行重跑"，必要时触发进程级单飞重建。</p>
 *
 * <p><b>身份亲和</b>：replay 在<b>原 worker 线程</b>上重跑，上下文为 per-thread，重建后新建的 Context 自动继承该线程的
 * 身份维度（{@code TestContext} 在线程上持续存在），因此同身份任务自然复用同一重建后 Context，无需额外亲和逻辑。</p>
 *
 * <p><b>只重跑崩溃型失败</b>：{@link #isCrash} 采用 W-10 三重判定——
 * ① 主信号（消息签名）：异常消息命中<b>可配置的崩溃白名单</b>（{@code PLAYWRIGHT_CONCURRENT_CRASH_SIGNATURES}，默认与历史收窄白名单一致）；
 * ② ③ 佐证信号（默认开启，受 {@code PLAYWRIGHT_CONCURRENT_CRASH_CORROBORATION_ENABLED} 控制）：异常类型为 Playwright/超时类
 * <b>且</b>当前线程 Browser 已被 {@code onDisconnected} 标记为断开（Playwright 事件）。
 * 仅 ② 或仅 ③ 均不判崩溃，保留了 W-15「不按异常类名模糊匹配」的防误判铁律；正常业务失败（断言、超时等）不触发重跑。
 * 重跑严格有界（{@code PLAYWRIGHT_CRASH_GUARD_MAX_REPLAY}），避免崩溃持续时的无限循环。</p>
 *
 * <p>总开关 {@code CONCURRENCY_BROWSER_CRASH_GUARD_ENABLED} 默认 {@code true}（纯韧性增强）；关闭时退化为
 * "失败直接随 {@link com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.concurrent.ContextTaskResult} 返回"，行为不变。</p>
 */
public final class BrowserCrashGuard {

    private static final Logger LOGGER = LoggerFactory.getLogger(BrowserCrashGuard.class);

    /** 进程级单飞锁：保证并发崩溃的多个任务中只有一个真正执行重建。 */
    private static final Object REBUILD_LOCK = new Object();

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
    /**
     * 崩溃消息特征白名单：由 {@code WebFrameworkConfig.PLAYWRIGHT_CONCURRENT_CRASH_SIGNATURES} 配置，
     * 可经 -D / serenity.properties 运行时覆盖（W-10：签名可配置）。
     * <p>缓存于 volatile 字段，解析廉价；配置变更后下次读取自动刷新（{@link #refreshCrashSignatures()} 供测试/动态刷新）。
     * 列表元素已统一小写、去空白，匹配时直接 {@code contains}。</p>
     */
    private static volatile List<String> crashSignatures =
            parseCrashSignatures(WebFrameworkConfig.PLAYWRIGHT_CONCURRENT_CRASH_SIGNATURES.getValue());

    /** 解析配置签名串（{@code |} 分隔，大小写不敏感、去空白）。 */
    private static List<String> parseCrashSignatures(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String s : raw.split("\\|")) {
            String t = s.trim().toLowerCase(Locale.ROOT);
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
        return List.copyOf(out);
    }

    /** 测试/动态刷新：按当前配置重新解析签名白名单。 */
    public static void refreshCrashSignatures() {
        crashSignatures = parseCrashSignatures(WebFrameworkConfig.PLAYWRIGHT_CONCURRENT_CRASH_SIGNATURES.getValue());
    }

    /** 实际恢复动作（默认走 PlaywrightManager 真实共享浏览器重建；单测可注入桩）。 */
    private static volatile RecoveryAction recoveryAction = PlaywrightManager::rebuildBrowserIfDisconnected;

    /** 强制恢复动作（句柄损坏场景）：默认走 PlaywrightManager 真实无条件重建；单测可注入桩。 */
    private static volatile RecoveryAction forcedRecoveryAction = PlaywrightManager::rebuildBrowser;

    private BrowserCrashGuard() {
    }

    /** 浏览器恢复动作：返回是否执行了重建（或无需重建）。 */
    @FunctionalInterface
    public interface RecoveryAction {
        boolean rebuild() throws Exception;
    }

    /** 测试用：注入恢复动作（传 null 复位为默认真实重建）。 */
    public static void setRecoveryActionForTesting(RecoveryAction action) {
        recoveryAction = action == null ? PlaywrightManager::rebuildBrowserIfDisconnected : action;
    }

    /** 测试用：注入强制恢复动作（传 null 复位为默认真实无条件重建）。 */
    public static void setForcedRecoveryActionForTesting(RecoveryAction action) {
        forcedRecoveryAction = action == null ? PlaywrightManager::rebuildBrowser : action;
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
     * 判断异常是否由浏览器崩溃引起（W-10 三重判定）。
     *
     * <p>第一信号（主，权威）：异常消息遍历 cause 链包含<b>可配置的崩溃签名</b>
     * （{@link WebFrameworkConfig#PLAYWRIGHT_CONCURRENT_CRASH_SIGNATURES}）——详见 {@link #messageSignatureMatches}。</p>
     *
     * <p>第二、三信号（佐证，默认开启，受 {@code PLAYWRIGHT_CONCURRENT_CRASH_CORROBORATION_ENABLED} 控制）：
     * 异常类型为 Playwright/超时类 <b>且</b> 当前线程 Browser 已被 {@code onDisconnected} 标记为断开（Playwright 事件）。
     * 二者<b>同时</b>成立才判崩溃，单独任一不成立——这保留了 W-15「不按异常类名模糊匹配」的防误判铁律：
     * 纯业务失败（断言/元素超时）类型虽属 Playwright 类，但因无断开事件而不误判；仅事件而无类型也不判崩溃。</p>
     *
     * <p>综上：消息签名命中 <b>或</b>（类型匹配 且 断开事件观察到）即判为崩溃，触发自动重跑；
     * 正常业务失败因不含任何崩溃信号且不伴随断开事件而不会触发重跑（真缺陷不遮掩）。</p>
     *
     * @param t 任务抛出的异常（可为 null）
     * @return true 表示疑似浏览器/进程崩溃，应触发重跑
     */
    public static boolean isCrash(Throwable t) {
        if (messageSignatureMatches(t)) {
            return true;
        }
        if (WebFrameworkConfig.PLAYWRIGHT_CONCURRENT_CRASH_CORROBORATION_ENABLED.getBooleanValue()
                && isPlaywrightRelatedType(t)
                && playwrightEventObserved()) {
            return true;
        }
        return false;
    }

    /**
     * 第一信号：遍历 cause 链，消息小写后是否包含任一可配置崩溃签名。
     */
    private static boolean messageSignatureMatches(Throwable t) {
        Throwable cur = t;
        while (cur != null) {
            String msg = cur.getMessage();
            if (msg != null) {
                String m = msg.toLowerCase(Locale.ROOT);
                for (String sig : crashSignatures) {
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
     * 第二信号：异常（遍历 cause 链）是否为 Playwright 崩溃/超时类。
     * 仅作佐证，<b>不单独</b>判崩溃（防 W-15 类名模糊匹配误判）。
     */
    private static boolean isPlaywrightRelatedType(Throwable t) {
        Throwable cur = t;
        while (cur != null) {
            if (cur instanceof PlaywrightException || cur instanceof TimeoutException) {
                return true;
            }
            cur = cur.getCause();
        }
        return false;
    }

    /**
     * 第三信号：当前测试线程关联的 Browser 是否已被 Playwright {@code onDisconnected} 事件标记为断开。
     * 即真实的「Playwright 浏览器断开事件」已被观测到；探测异常时降级为 false，不影响主信号判定。
     */
    private static boolean playwrightEventObserved() {
        try {
            return PlaywrightRuntime.instance().browserCleanup.isCurrentBrowserDisconnected();
        } catch (Throwable ignore) {
            return false;
        }
    }

    /**
     * 判断异常是否由 Chromium 句柄注册表损坏引起（{@code __adopt__} / {@code previewUpdated}）。
     *
     * <p>此类损坏下 {@code isConnected()} 恒为 true，断开型重建（{@link #recover()}）会落空，
     * 必须走强制重建（{@link #recoverForced()} → {@link PlaywrightManager#rebuildBrowser()}）。
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
     * 进程级单飞恢复：在 {@link #REBUILD_LOCK} 内执行恢复动作（重建当前 worker 线程的 Browser）。
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
     * （重建当前 worker 线程的 Browser）。与 {@link #recover()} 共享同一单飞锁，
     * 但使用独立的 {@link #forcedRecoveryAction}（指向 {@code rebuildBrowser}），
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

    /** 重跑次数上限（调用方据此限制重跑，避免无限循环）。由配置键 {@code PLAYWRIGHT_CRASH_GUARD_MAX_REPLAY} 驱动（W-9）。 */
    public static int maxReplay() {
        int v = WebFrameworkConfig.PLAYWRIGHT_CRASH_GUARD_MAX_REPLAY.getIntValue();
        return v > 0 ? v : 1;
    }

    /** 观测：累计触发恢复次数。 */
    public static long rebuildCount() {
        return REBUILD_COUNT.get();
    }
}