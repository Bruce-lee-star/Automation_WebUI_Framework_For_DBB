package com.hsbc.cmb.hk.dbb.automation.framework.route.v2;

import com.hsbc.cmb.hk.dbb.automation.framework.common.route.RouteV2AssertionFailure;
import com.hsbc.cmb.hk.dbb.automation.framework.route.v2.dsl.RouteCapability;
import com.hsbc.cmb.hk.dbb.automation.framework.route.v2.monitor.CapturedApiCall;
import com.microsoft.playwright.BrowserContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Route V2 门面 —— 按 BrowserContext 获取/关闭运行时。
 *
 * <p>全局注册表是模块唯一的跨 context 共享结构，且仅做「context → runtime」的映射：
 * <ul>
 *   <li>key 为 Playwright BrowserContext 对象（对象标识），数量与浏览器会话同量级，无增长风险；</li>
 *   <li>entry 生命周期与 context 强绑定：context 关闭事件自动触发 {@link RouteRuntime#close()}，
 *       后者以 {@code remove(context, this)} 从本表移除（无泄漏）；</li>
 *   <li>显式 {@link #shutdown(BrowserContext)} 幂等。</li>
 * </ul>
 *
 * <p>生命周期 SPI：本类静态块触发 {@code RouteLifecycleV2Impl} 自注册（追加到核心层
 * {@code RouteLifecycleRegistry}），使 Serenity / web 侧统一收尾动作（stopContextEngine /
 * stopAllContextEngines / shutdownRouteEngine / drainForSuiteTeardown）同步驱动 V2 运行时关闭，
 * V2 runtime 不再"孤儿"。
 */
public final class RouteEngine2 {

    private static final Logger LOGGER = LoggerFactory.getLogger(RouteEngine2.class);

    private static final ConcurrentMap<BrowserContext, RouteRuntime> RUNTIMES = new ConcurrentHashMap<>();

    static {
        // 触发 RouteLifecycleV2Impl 静态块（registerAdditional）；业务首次使用 V2 即注册
        try {
            Class.forName("com.hsbc.cmb.hk.dbb.automation.framework.route.v2.lifecycle.RouteLifecycleV2Impl");
        } catch (ClassNotFoundException e) {
            // 同模块内不可达（仅防御类路径异常）
            LOGGER.warn("[RouteV2] RouteLifecycleV2Impl load failed: {}", e.toString());
        }
        // 触发 RouteV2AssertionProbeImpl 静态块（断言失败上报探针自注册）
        try {
            Class.forName("com.hsbc.cmb.hk.dbb.automation.framework.route.v2.lifecycle"
                    + ".RouteV2AssertionProbeImpl");
        } catch (ClassNotFoundException e) {
            // 同模块内不可达（仅防御类路径异常）
            LOGGER.warn("[RouteV2] RouteV2AssertionProbeImpl load failed: {}", e.toString());
        }
    }

    private RouteEngine2() {
    }

    /**
     * 获取（不存在则创建）指定 context 的 Route V2 运行时。
     */
    public static RouteRuntime runtimeOf(BrowserContext context) {
        Objects.requireNonNull(context, "context");
        return RUNTIMES.computeIfAbsent(context,
                c -> RouteRuntimeFactoryRegistry.instance().create(c, RouteV2Config.DEFAULTS, RUNTIMES));
    }

    /**
     * 获取（不存在则创建）指定 context 的 Route V2 运行时（自定义配置）。
     */
    public static RouteRuntime runtimeOf(BrowserContext context, RouteV2Config config) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(config, "config");
        return RUNTIMES.computeIfAbsent(context,
                c -> RouteRuntimeFactoryRegistry.instance().create(c, config, RUNTIMES));
    }

    /** 关闭指定 context 的运行时（幂等；context 关闭时也会自动触发）。 */
    public static void shutdown(BrowserContext context) {
        RouteRuntime runtime = RUNTIMES.remove(context);
        if (runtime != null) {
            runtime.close();
        }
    }

    // ── stop 系列（对齐现有 RouteDsl.stopX(context, pattern)）──
    // 语义：只停指定 pattern 的指定能力，路由仍注册（不 unroute）；未注册/已停返回 false。

    /** 停止某 pattern 的 MOCK 能力。 */
    public static boolean stopMock(BrowserContext context, String urlPattern) {
        return stopCapability(context, RouteCapability.MOCK, urlPattern);
    }

    /** 停止某 pattern 的 MONITOR 能力。 */
    public static boolean stopMonitor(BrowserContext context, String urlPattern) {
        return stopCapability(context, RouteCapability.MONITOR, urlPattern);
    }

    /** 停止某 pattern 的 MODIFY_REQUEST 能力。 */
    public static boolean stopModify(BrowserContext context, String urlPattern) {
        return stopCapability(context, RouteCapability.MODIFY_REQUEST, urlPattern);
    }

    /** 停止某 pattern 的 DELAY 能力。 */
    public static boolean stopDelay(BrowserContext context, String urlPattern) {
        return stopCapability(context, RouteCapability.DELAY, urlPattern);
    }

    /** 停止某 pattern 的当前全部能力（V2 一规则一能力）。 */
    public static boolean stopApi(BrowserContext context, String urlPattern) {
        RouteRuntime runtime = RUNTIMES.get(context);
        return runtime != null && runtime.stopApi(urlPattern);
    }

    private static boolean stopCapability(BrowserContext context, RouteCapability capability, String urlPattern) {
        RouteRuntime runtime = RUNTIMES.get(context);
        return runtime != null && runtime.stop(capability, urlPattern);
    }

    /** 关闭全部运行时（套件收尾 / stopAllContextEngines / shutdownRouteEngine；幂等）。 */
    public static void shutdownAll() {
        for (RouteRuntime runtime : RUNTIMES.values()) {
            try {
                runtime.close();
            } catch (Exception e) {
                // 单 runtime 关闭失败不阻断其它（fail-safe 隔离）
                LOGGER.warn("[RouteV2] runtime close failed: {}", e.toString());
            }
        }
    }

    /** 全部运行时数（测试/可观测性）。 */
    public static int activeRuntimes() {
        return RUNTIMES.size();
    }

    /** 该 context 的运行时是否已降级（注册曾失败）；context 复用方应据此丢弃该 Context。 */
    public static boolean isDegraded(BrowserContext context) {
        RouteRuntime rt = RUNTIMES.get(context);
        return rt != null && rt.isDegraded();
    }

    /**
     * 取走指定 context 已采集的 API 快照（消费式；幂等）。
     *
     * <p>CAPTURE 采集的业务消费出口（诊断 / 报告）；runtime 不存在时返回空列表。
     * 消费式语义保证多次调用不重复返回同一条快照，也不会跨场景串扰。
     */
    public static List<CapturedApiCall> dumpCapturedApis(BrowserContext context) {
        Objects.requireNonNull(context, "context");
        RouteRuntime runtime = RUNTIMES.get(context);
        return runtime == null ? List.of() : runtime.dumpCapturedApis();
    }

    /** 聚合全部活跃 runtime 的已定案断言失败（消费式；幂等——第二次调用返回空列表）。
     *
     * <p>MONITOR 断言失败上报的统一出口，由 {@code RouteV2AssertionProbeImpl} 委托；
     * web 侧（步骤结束 / 用例收尾）经 {@link RouteV2AssertionRegistry} 获取探针后调用。
     * 单 runtime 失败不阻断其它（fail-safe 隔离）。
     */
    public static List<RouteV2AssertionFailure> drainSettledAssertionFailures() {
        List<RouteV2AssertionFailure> all = new ArrayList<>();
        for (RouteRuntime runtime : RUNTIMES.values()) {
            try {
                all.addAll(runtime.drainSettledAssertionFailures());
            } catch (Exception e) {
                // 单 runtime 消费失败不阻断其它（fail-safe 隔离）
                LOGGER.warn("[RouteV2] drain assertion failures failed: {}", e.toString());
            }
        }
        return all;
    }
}
