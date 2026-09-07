package com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle;

import com.hsbc.cmb.hk.dbb.automation.framework.web.config.FrameworkConfig;
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
 * <p><b>只重跑崩溃型失败</b>：{@link #isCrash} 基于异常类 / 消息特征判定，正常业务失败（断言、超时等）不触发重跑，
 * 不会掩盖真实缺陷。重跑严格有界为一次，避免崩溃持续时的无限循环。</p>
 *
 * <p>总开关 {@code CONCURRENCY_BROWSER_CRASH_GUARD_ENABLED} 默认 {@code true}（纯韧性增强）；关闭时退化为
 * "失败直接随 {@link com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.ContextTaskResult} 返回"，行为不变。</p>
 */
public final class BrowserCrashGuard {

    private static final Logger LOGGER = LoggerFactory.getLogger(BrowserCrashGuard.class);

    /** 进程级单飞锁：保证并发崩溃的多个任务中只有一个真正执行重建。 */
    private static final Object REBUILD_LOCK = new Object();

    /** 重跑严格有界次数。 */
    private static final int MAX_REPLAY = 1;

    private static final AtomicLong REBUILD_COUNT = new AtomicLong();

    /** 崩溃型失败的消息特征（小写子串匹配，遍历 cause 链）。 */
    private static final List<String> CRASH_SIGNATURES = List.of(
            "browser has been closed",
            "browser is closed",
            "browser process",
            "target page, context or browser has been closed",
            "target closed",
            "connection closed",
            "connection prematurely closed",
            "playwright has been closed",
            "execution context was destroyed",
            "browser disconnected",
            "browser crashed"
    );

    /** 实际恢复动作（默认走 PlaywrightManager 真实共享浏览器重建；单测可注入桩）。 */
    private static volatile RecoveryAction recoveryAction = PlaywrightManager::rebuildSharedBrowserIfDisconnected;

    private BrowserCrashGuard() {
    }

    /** 浏览器恢复动作：返回是否执行了重建（或无需重建）。 */
    @FunctionalInterface
    public interface RecoveryAction {
        boolean rebuild() throws Exception;
    }

    /** 测试用：注入恢复动作（传 null 复位为默认真实重建）。 */
    static void setRecoveryActionForTesting(RecoveryAction action) {
        recoveryAction = action == null ? PlaywrightManager::rebuildSharedBrowserIfDisconnected : action;
    }

    /**
     * 守卫是否启用。经 {@code FrameworkConfig} → {@code ConfigSource} 统一解析链
     * （实时 {@code System.getProperty} → 环境变量 → Serenity(serenity.conf/properties) → 默认值），
     * 故系统属性与环境变量均可覆盖，与框架其它配置一致。默认 {@code true}（纯韧性增强）。
     */
    public static boolean isEnabled() {
        return FrameworkConfig.CONCURRENCY_BROWSER_CRASH_GUARD_ENABLED.getBooleanValue();
    }

    /**
     * 判断异常是否由浏览器崩溃引起（遍历 cause 链）。
     *
     * @param t 任务抛出的异常（可为 null）
     * @return true 表示疑似浏览器/进程崩溃，应触发重跑
     */
    public static boolean isCrash(Throwable t) {
        Throwable cur = t;
        while (cur != null) {
            String className = cur.getClass().getName().toLowerCase(Locale.ROOT);
            if (className.contains("playwrightexception")) {
                return true;
            }
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

    /** 重跑次数上限（调用方据此限制重跑，避免无限循环）。 */
    public static int maxReplay() {
        return MAX_REPLAY;
    }

    /** 观测：累计触发恢复次数。 */
    public static long rebuildCount() {
        return REBUILD_COUNT.get();
    }
}
