package com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle;

import com.hsbc.cmb.hk.dbb.automation.framework.common.route.RouteLifecycleRegistry;
import com.hsbc.cmb.hk.dbb.automation.framework.core.lifecycle.ShutdownCoordinator;
import org.junit.platform.launcher.LauncherSession;
import org.junit.platform.launcher.LauncherSessionListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * JUnit Platform 会话级收尾监听器（ServiceLoader 自动注册：
 * {@code META-INF/services/org.junit.platform.launcher.LauncherSessionListener}）。
 *
 * <p><b>为什么需要它（2026-09-27 实测根因）</b>：1.txt 运行日志证实 Serenity 的
 * {@code testSuiteFinished()} 挂点在 Cucumber + JUnit Platform 编排下<b>并未触发</b>
 * （578 行日志无任何 "Test suite finished" 输出），唯一收尾是 {@code FrameworkCore} 的
 * JVM Shutdown Hook——而该路径在 IDEA/CI 手动停进程、或清理期抛 {@code IllegalStateException}
 * （并发窗口防御）时会被跳过，直接导致「运行完浏览器没关闭、Playwright/Node 驱动进程残留、
 * Route V2 引擎线程（route-v2-sweep-* / IO 线程）不退出」三类缺陷。</p>
 *
 * <p><b>修复定位</b>：JUnit Platform 的 {@link LauncherSessionListener} 由
 * {@code LauncherFactory.createSession()} 标准加载；@Suite(Cucumber) runner 跑完整个引擎后
 * 调用 {@link LauncherSession#close()}，JUnit 随即回调 {@link #launcherSessionClosed(LauncherSession)}
 * ——这是比 Serenity 挂点更早、更确定的「所有测试执行完毕」信号。
 * 收尾顺序（确定性、幂等）：</p>
 * <ol>
 *   <li>先关 Route 引擎（v1+v2：调度线程 / IO 线程 / sweep 线程）——与浏览器无关，任何时刻安全；
 *       即使下一步全局浏览器清理被并发窗口拒绝，线程也不会残留；</li>
 *   <li>再 {@link PlaywrightManager#cleanupAll()}——关闭全部 page/context/browser 与
 *       Playwright 实例（含 Node 驱动进程），内部已含 RouteLifecycleRegistry 的
 *       {@code stopAllContextEngines()/drainForSuiteTeardown()/clearAll()}（幂等，重复调用无害）；</li>
 *   <li>并发窗口拒绝（{@code ConcurrentContextExecutor} 防御性设计）时<b>降级</b>为本线程
 *       {@code cleanupForFeature()}，并打 WARN（非 verbose，可观测），绝不静默跳过。</li>
 * </ol>
 *
 * <p><b>幂等与线程安全</b>：{@code launcherSessionClosed} 可能被多次回调（多次 LauncherSession），
 * {@link #TEARDOWN_DONE} CAS 保证全局只执行一次。三路互补：SuiteTeardownListener（JUnit 会话级，
 * 最先触发）→ PlaywrightListener.testSuiteFinished（Serenity 挂点，若触发）→ FrameworkCore
 * JVM shutdown hook（最后兜底），均幂等、均经 RouteLifecycleRegistry / cleanupAll 收敛到同一收尾链。</p>
 */
public class SuiteTeardownListener implements LauncherSessionListener {

    private static final Logger LOGGER = LoggerFactory.getLogger(SuiteTeardownListener.class);

    /** 全局幂等闸：整个 JVM 生命周期内套件收尾只执行一次。 */
    private static final AtomicBoolean TEARDOWN_DONE = new AtomicBoolean(false);

    @Override
    public void launcherSessionClosed(LauncherSession session) {
        if (!TEARDOWN_DONE.compareAndSet(false, true)) {
            return; // 已收尾过（重跑/多次 session 场景），幂等返回
        }
        LOGGER.info("[SuiteTeardown] JUnit Platform session closed — deterministic global teardown starting");
        try {
            // 第 1 步：Route 引擎线程先行关闭（v1+v2，Composite 聚合；与浏览器无关，任何时刻安全）
            RouteLifecycleRegistry.get().stopAllContextEngines();
            RouteLifecycleRegistry.get().drainForSuiteTeardown();
        } catch (Exception e) {
            ShutdownCoordinator.recordFailure("web/suiteTeardown/routeEngines", e);
        }
        try {
            // 第 2 步：全局浏览器/Playwright 清理（含 v1+v2 route 兜底、Playwright 实例关闭）
            PlaywrightManager.cleanupAll();
            LOGGER.info("[SuiteTeardown] global cleanup completed (browsers/contexts/Playwright closed)");
        } catch (IllegalStateException e) {
            // 并发执行窗口内：不得全局关闭（防误关邻居线程浏览器）——降级为本线程清理，可观测
            LOGGER.warn("[SuiteTeardown] global cleanup skipped (concurrent mode active): {} "
                    + "— falling back to per-thread cleanup", e.getMessage());
            try {
                PlaywrightManager.cleanupForFeature();
            } catch (Exception ex) {
                ShutdownCoordinator.recordFailure("web/suiteTeardown/fallbackCleanupForFeature", ex);
            }
        } catch (Exception e) {
            // N-06 同源约定：收尾失败必须非 verbose 可观测 + 计入统一失败计数（可被 CI 断言）
            ShutdownCoordinator.recordFailure("web/suiteTeardown/cleanupAll", e);
        }
    }
}
