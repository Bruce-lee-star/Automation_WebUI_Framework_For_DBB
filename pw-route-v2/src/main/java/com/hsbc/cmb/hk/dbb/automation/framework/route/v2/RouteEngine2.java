package com.hsbc.cmb.hk.dbb.automation.framework.route.v2;

import com.hsbc.cmb.hk.dbb.automation.framework.common.route.RouteV2AssertionFailure;
import com.hsbc.cmb.hk.dbb.automation.framework.route.v2.dsl.RouteCapability;
import com.hsbc.cmb.hk.dbb.automation.framework.route.v2.binding.GuardedDriverCallImpl;
import com.hsbc.cmb.hk.dbb.automation.framework.route.v2.binding.GuardedDriverCallRegistry;
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
        shutdown(context, false);
    }

    /**
     * 关闭指定 context 的 runtime（T8-5）。
     *
     * @param context            目标 BrowserContext
     * @param contextBeingClosed true = 调用方紧接着就会 {@code context.close()} ⇒ 标记 runtime
     *                           「Context 正在关闭」，{@code close()} 跳过逐条 unroute（原生释放）。
     *                           <b>仅当该 Context 确实会被关闭时才可传 true</b>：若 Context 仍需存活，
     *                           跳过 unroute 会把规则残留在驱动侧。
     */
    public static void shutdown(BrowserContext context, boolean contextBeingClosed) {
        RouteRuntime runtime = RUNTIMES.remove(context);
        if (runtime != null) {
            if (contextBeingClosed) {
                runtime.markContextClosing();
            }
            runtime.close();
        }
    }
    /**
     * 驱动信道当前是否可确证可用（{@code false} ⇒ 存在未收尾的在途协议调用）。
     *
     * <p>对外 API 里没有"复位连接 / 取消在途调用"的手段，唯一的"换连接"是关闭并重建 {@code Playwright} 实例。
     * 故本方法只暴露**可查询状态**，供会话层决定是否重建会话；重建后请调用 {@link #resetDriverChannel()}
     * 让信道与新连接对齐。</p>
     */
    public static boolean isDriverChannelUsable() {
        return GuardedDriverCallRegistry.instance().isChannelUsable();
    }

    /** 驱动信道故障（超时）累计次数（单调递增；跨用例比较差值即可判断"本用例是否污染过信道"）。 */
    public static long driverChannelFailures() {
        return GuardedDriverCallImpl.channelFailureCount();
    }

    /**
     * 复位驱动信道（换一条干净驱动线程）；<b>不触碰任何 Context / Page / runtime</b>。
     *
     * <p>仅应在<b>已换连接</b>（重建 {@code Playwright} 实例、或套件收尾）之后调用：在途调用未收尾时
     * 单方面换线程会制造第二个消息泵。</p>
     */
    public static void resetDriverChannel() {
        GuardedDriverCallImpl.resetChannel();
    }

    /**
     * 新用例起点的信道判定结论。
     */
    public enum ChannelVerdict {
        /** 信道可确证可用 ⇒ 直接进入下一个用例，无需任何重建。 */
        REUSABLE,
        /**
         * 信道被确证不可继续（存在未收尾的在途协议调用）⇒ 调用方必须重建会话
         * （换 {@code Playwright} 实例，session 缓存负责恢复登录态），随后调用
         * {@link RouteEngine2#resetDriverChannel()} 让框架信道与新连接对齐。
         */
        REBUILD_SESSION_REQUIRED
    }

    /**
     * 用例边界的信道体检：告诉调用方<b>能否直接开始下一个用例</b>；本方法只读状态，
     * <b>不重建任何东西</b>（重建由会话层执行，见返回值说明）。
     *
     * <p><b>为什么必须有显式入口</b>：Playwright 对外 API 里没有"只复位连接"的手段
     * （{@code unrouteAll()} 同走 NO_TIMEOUT 全量下发；{@code close(context/browser)} 无 timeout），
     * 唯一的"换连接"是关闭并重建 {@code Playwright} 实例；而框架自身的 JVM 级静态状态
     * （驱动信道、调度器）<b>又不会</b>随实例重建而复位。因此"何时重建、重建到哪一层"必须显式判定 ——
     * "每用例都重建实例"既不必要（规则与业务态天然按 Context 隔离）也不充分（JVM 级残留照样在）。</p>
     *
     * <p><b>粒度对照（决策依据）</b>：
     * <ul>
     *   <li><b>规则 / 拦截</b>：Context 粒度（{@code BrowserContextImpl.routes} 是实例字段）
     *       ⇒ 用 {@link #clearRules(BrowserContext)}，<b>不需要</b>换实例；</li>
     *   <li><b>业务态</b>（cookies / 登录态）：Context 粒度 ⇒ 新 Context 或 session 缓存恢复；</li>
     *   <li><b>协议信道</b>：Connection = <b>Playwright 实例</b>粒度 ⇒ 只有换实例能复位（本方法判定）；</li>
     *   <li><b>框架 JVM 级静态态</b>：换实例也复位不了 ⇒ 由 {@link #resetDriverChannel()} 与
     *       {@link #shutdownAll()} 负责。</li>
     * </ul>
     *
     * @return {@link ChannelVerdict#REUSABLE} ⇒ 无需重建；{@link ChannelVerdict#REBUILD_SESSION_REQUIRED}
     *         ⇒ 由会话层重建 {@code Playwright} 实例后回调 {@link #resetDriverChannel()}
     */
    public static ChannelVerdict channelVerdictForNewCase() {
        return isDriverChannelUsable() ? ChannelVerdict.REUSABLE : ChannelVerdict.REBUILD_SESSION_REQUIRED;
    }

    /**
     * 只清指定 context 的全部 V2 规则，<b>保留</b> runtime 与 context（V2-2）。
     *
     * <p>与 {@link #shutdown(BrowserContext)} 的区别：{@code shutdown} 关闭整个 runtime
     * （下一次注册会重建）；本方法只退役规则 —— runtime 及其线程池、Context 及其登录态都保留，
     * 适合 feature 模式"跨 scenario 复用 Context、但要求规则确定性清空"的场景。</p>
     *
     * <p>幂等；该 context 从未注册过规则（无 runtime）时返回 0 且<b>不创建</b> runtime。
     * 驱动侧撤销为异步可确证路径，结论见 {@link RouteRuntime#isClean()}。</p>
     *
     * @return 本次提交退役的规则数
     */
    public static int clearRules(BrowserContext context) {
        Objects.requireNonNull(context, "context");
        RouteRuntime runtime = RUNTIMES.get(context);
        return runtime == null ? 0 : runtime.clearRules();
    }

    /**
     * 【档 B】纯内存解绑：只清规则表，保留驱动侧绑定与 runtime（<b>零协议调用</b>）。
     *
     * <p>用于「Context 仍存活」的用例 / feature 边界清理 —— 取代原先"拆 runtime + 逐条 unroute"的
     * 收尾，消除 FIX_PLAN §4.1 定位的失败引信。Context 即将关闭请用
     * {@link #shutdown(BrowserContext, boolean)}（{@code true}）。
     *
     * @return 清空的规则数；无 runtime 时为 0（且不为其创建 runtime）
     */
    public static int detachRules(BrowserContext context) {
        Objects.requireNonNull(context, "context");
        RouteRuntime runtime = RUNTIMES.get(context);
        return runtime == null ? 0 : runtime.detachRules();
    }

    /**
     * 只清<b>全部</b> runtime 的规则，保留各 runtime 与 context（V2-2；feature / 套件之间的规则清理）。
     *
     * <p>单 runtime 失败不阻断其它（fail-safe 隔离）。</p>
     *
     * @return 全部 runtime 提交退役的规则数之和
     */
    public static int clearRulesAll() {
        int total = 0;
        for (RouteRuntime runtime : RUNTIMES.values()) {
            try {
                total += runtime.clearRules();
            } catch (Exception e) {
                // 单 runtime 清理失败不阻断其它（fail-safe 隔离）
                LOGGER.warn("[RouteV2] clearRules failed: {}", e.toString());
            }
        }
        return total;
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
        //  套件收尾：连同驱动信道一起复位 —— 若此前有在途调用未收尾，信道会一直被标记不可用；
        //  不复位会让同一 JVM 内的后续套件全部失去路由能力。
        resetDriverChannel();
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
