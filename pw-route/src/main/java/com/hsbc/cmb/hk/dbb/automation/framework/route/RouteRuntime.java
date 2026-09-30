package com.hsbc.cmb.hk.dbb.automation.framework.route;

import com.hsbc.cmb.hk.dbb.automation.framework.common.route.RouteAssertionFailure;
import com.hsbc.cmb.hk.dbb.automation.framework.route.binding.GenerationRegistry;
import com.hsbc.cmb.hk.dbb.automation.framework.route.claim.ClaimRegistry;
import com.hsbc.cmb.hk.dbb.automation.framework.route.claim.PendingGuard;
import com.hsbc.cmb.hk.dbb.automation.framework.route.dsl.ApiSpec;
import com.hsbc.cmb.hk.dbb.automation.framework.route.dsl.RouteCapability;
import com.hsbc.cmb.hk.dbb.automation.framework.route.exec.BoundedOps;
import com.hsbc.cmb.hk.dbb.automation.framework.route.exec.RouteIoExecutor;
import com.hsbc.cmb.hk.dbb.automation.framework.route.monitor.CapturedApiCall;
import com.microsoft.playwright.APIRequestContext;
import com.microsoft.playwright.Request;
import com.microsoft.playwright.Route;

import java.util.List;
import java.util.Map;

/**
 * Route V2 运行时角色契约 —— 每个 BrowserContext 一份，模块全部状态与线程的装配点。
 *
 * <p>设计对齐企业级 DI 范式（WEB-P1-6）：本接口是「运行时」角色，生产实现
 * {@link RouteRuntimeImpl} 经 {@link RouteRuntimeFactory} SPI 创建，
 * 可经 {@link RouteRuntimeFactoryRegistry#setInstance} 整体替换（真多态 + 测试可注入替身）。
 *
 * <p>API 边界（清晰分层，防业务误用）：
 * <ul>
 *   <li><b>稳定公开契约</b>（业务/框架消费面，语义稳定）：
 *       {@link #register}/{@link #stop}/{@link #stopApi}/{@link #dispatch}/{@link #close}/
 *       {@link #dumpCapturedApis}/{@link #drainSettledAssertionFailures}/{@link #metrics}；</li>
 *   <li><b>模块内部契约</b>（{@code @apiNote framework-internal}，仅 V2 子包协作者使用）：
 *       {@link #generations}/{@link #claims}/{@link #pending}/{@link #ops}/{@link #io}/
 *       {@link #recordObservation}/{@link #isDegraded}；</li>
 * </ul>
 *
 * <p>并发安全：实现须保证每 context 一份、生命周期与 context 绑定（context 关闭即整体关闭），
 * 天然消除跨 context 串扰。
 */
public interface RouteRuntime extends AutoCloseable {

    // ── 模块内部契约（framework-internal）──

    /**
     * 规则代际表（原子 CAS 发布）。
     * @apiNote framework-internal: 仅 RouteDispatcher / PatternBinder 使用
     */
    GenerationRegistry generations();

    /**
     * 终结所有权注册表（CAS 单所有者）。
     * @apiNote framework-internal
     */
    ClaimRegistry claims();

    /**
     * 挂起额度守卫。
     * @apiNote framework-internal
     */
    PendingGuard pending();

    /**
     * 预算执行器（无超时往返耗时/超时统计）。
     * @apiNote framework-internal
     */
    BoundedOps ops();

    /**
     * IO 线程池（阻塞操作唯一落点）。
     * @apiNote framework-internal
     */
    RouteIoExecutor io();

    /**
     * 本 runtime 所属 Context 的 {@link APIRequestContext}（共享该 Context 的 cookie 存储）。
     *
     * <p>供 MOCK intercept 取真实响应使用——与 {@code page.request()} 等价，但<b>不依赖</b>任何
     * {@code Page}/{@code Frame}/{@code Request} 句柄，从结构上杜绝"导航期句柄回收"竞态。
     * 取响应所需的 url/method/headers/body 由调用方在<b>事件线程拦截那一刻</b>以值快照提供。</p>
     *
     * @return 可用则返回；实现无法提供时返回 {@code null}（调用方必须 fail-open）
     * @apiNote framework-internal: 仅 RouteDispatcher 使用
     */
    default APIRequestContext request() {
        return null;
    }

    /** runtime 是否已关闭。 */
    boolean isClosed();

    /**
     * runtime 是否已降级（注册曾失败）；context 复用方据此丢弃该 Context。
     * @apiNote framework-internal: 由实现内部在注册失败时标记
     */
    boolean isDegraded();

    /**
     * 目的达成即撤销该 pattern 的绑定（T2+：<b>规则随目的生灭</b>取代"随用例生灭"）。
     *
     * <p>调用方（目的定案路径，如 MONITOR 断言结算）只需提交 pattern；实现必须<b>不在调用线程同步撤销</b>
     * （{@code unroute} 是同步协议调用，事件线程内做等于嵌套下发），并保证撤销<b>可确证</b>。</p>
     *
     * @param pattern 规则 pattern（{@link ApiSpec#pattern()}）
     * @return true=已提交撤销（或无需撤销 / runtime 收尾中）；false=IO 队列满（已归还给收尾兜底并降级）
     * @apiNote framework-internal：业务代码不得直接调用（由 dispatch / sink 定案路径驱动）。
     */
    boolean retireByPurpose(String pattern);

    /**
     * 本 runtime 的路由状态是否<b>已确证干净</b>（不变式 I-8 / I-9）。
     *
     * <p>{@code false} 含义：存在"未确证的撤销"（界内无回包等）⇒ 客户端与驱动的拦截状态可能不一致，
     * <b>不允许复用该 Context</b>，调用方必须丢弃重建（宁可重建，也不接受"假设干净"）。</p>
     *
     * @return true=所有撤销均已确证（可安全复用）；false=存在不可确证的撤销（必须丢弃重建）
     */
    boolean isClean();

    /** 未被确证的撤销次数（{@code > 0} ⇒ {@link #isClean()} 为 false）。观测/诊断用。 */
    int unconfirmedRetirements();

    /**
     * 统一观测入口（dispatch 对所有能力调用）：CAPTURE 对<b>所有能力</b>采集；MONITOR 记录只对
     * <b>带响应侧期望</b>的 MONITOR 规则生效 —— 非 MONITOR 能力（MOCK/MODIFY/DELAY）与无期望的
     * MONITOR 规则不进入观测队列，避免"记录后永不配对"在 drain 时被误判为监控超时并归因场景失败。
     * @apiNote framework-internal: 仅 RouteDispatcher 调用
     */
    void recordObservation(Request request, ApiSpec spec);

    // ── 稳定公开契约 ──

    /** 取走本 runtime 已采集的 API 快照（消费式；幂等——第二次调用返回空列表）。 */
    /**
     * 只清规则、保留运行时与 Context 的确定性入口（V2-2）。
     *
     * <p><b>与 {@link #close()} 的区别</b>：{@code close()} 拆掉整个 runtime（IO / 撤销线程池、
     * 巡检、看门狗、注册表条目）并随 Context 收尾；本方法只把当前<b>全部规则</b>退役
     *（走独立撤销执行器 —— 与"目的达成撤销"同一条可确证路径），<b>runtime 与 Context 都保留</b>，
     * 之后可继续 {@link #register(ApiSpec)}，既不需要重建 runtime，也不触发重新登录（不变式 I-1）。</p>
     *
     * <p><b>为什么需要</b>：feature 模式下 Context 跨 scenario 复用，而"清规则"与"拆引擎"本是两件事。
     * 缺本入口时业务只能以"拆 runtime"代替"清规则"，代价是每 scenario 重建线程池，且无法在
     * "确定性清空规则"之后继续复用同一 Context。</p>
     *
     * <p><b>语义</b>：调用返回时内存规则表<b>已为空</b>（不再有新请求命中）；驱动侧绑定以异步可确证
     * 路径撤销，结论计入 {@link #isClean()} / {@link #unconfirmedRetirements()}（未确证 ⇒ 重同步
     * 自愈 + 可见告警，<b>绝不重建 Context</b>）。本方法幂等。</p>
     *
     * <p><b>并发约定</b>：面向用例 / feature 边界的确定性清理，调用方须保证与
     * {@link #register(ApiSpec)} 不并发（边界清理本就是单线程语义）；与 {@link #dispatch}（事件线程）
     * 并发是安全的 —— 事件线程只会看到"清空前"或"清空后"的一致代际快照。</p>
     *
     * @return 本次提交退役的规则数（无规则时为 0）
     */
    int clearRules();

    /**
     * 【档 B】<b>纯内存</b>解绑：只清空规则表，<b>保留</b>驱动侧绑定（handler）与 runtime —— <b>零协议调用</b>。
     *
     * <p><b>与 {@link #clearRules()} 的本质区别</b>：{@code clearRules()} 会对每条规则走"目的达成撤销"
     * 路径 ⇒ 每条一次 {@code context.unroute()}（= 客户端 {@code setNetworkInterceptionPatterns}，
     * {@code NO_TIMEOUT} + 调用线程泵消息）。实测这条收尾链路正是 30s 卡死与"未确证 ⇒ 信道污染
     * ⇒ 下一用例 bind 挂死"的引信（FIX_PLAN §4 / §4.1）。本方法<b>一条协议调用都不发</b>。</p>
     *
     * <p><b>为什么安全（不会残留脏行为）</b>：驱动侧 handler 常驻，但每次命中都经
     * {@code RouteDispatcher.dispatch} 读当前代际表；规则表为空 ⇒ 走 fail-open 分支
     * （{@code fallback} ⇒ 驱动自动 resume），语义与"未注册"完全等价，请求永不悬挂。
     * 这正是 master 分支长期稳定所依赖的模型（其 {@code MonitorSession.stopped} 也是内存标记放行）。</p>
     *
     * <p><b>为什么反而更优</b>：pattern 注册一次后常驻 ⇒ 下一 scenario {@code register()} 命中既有
     * binder（{@code computeIfAbsent}）⇒ <b>连 bind 也省掉</b>，每用例协议调用数 ≈ 0。</p>
     *
     * <p><b>适用前提</b>：仅当 Context 仍存活（不再关闭）时使用；Context 即将关闭请走
     * {@code RouteEngine.shutdown(ctx, true)}（随 close 原生释放，同样不 unroute）。</p>
     *
     * @return 本次清空的规则数（规则表为空时为 0）
     */
    default int detachRules() {
        return 0;
    }

    List<CapturedApiCall> dumpCapturedApis();

    /** 取走本 runtime 已定案的断言失败（消费式；幂等）。 */
    List<RouteAssertionFailure> drainSettledAssertionFailures();

    /**
     * 注册一条规则并绑定驱动。
     *
     * <p>同 pattern 后注册覆盖先注册（代际原子发布，事件线程即刻可见）。
     * 返回句柄 close() 幂等；关闭后该 pattern 的绑定被注销，再次注册会重建绑定。
     */
    AutoCloseable register(ApiSpec spec);

    /**
     * 停止某 pattern 的指定能力（stop* 系列）。
     * @return true=本次调用生效（发布了新代）；false=未注册或已停止
     */
    boolean stop(RouteCapability capability, String pattern);

    /** 停止某 pattern 的当前能力（V2 一规则一能力：stopApi = 停其全部能力）。 */
    boolean stopApi(String pattern);

    /** 事件线程分发入口（由 PatternBinder 转发）。 */
    void dispatch(Route route, String pattern);

    /** 指标快照（可观测性/测试断言）。 */
    RouteMetrics metrics();

    @Override
    void close();

    /**
     * 标记「本 runtime 所属 Context 正被主动关闭」——随后的 {@link #close()} 只做内存收尾，
     * <b>不发起逐条 unroute</b>（规则随 {@code context.close()} 由驱动原生释放）（T8-5）。
     *
     * <p>为何必须显式传意图而不是"自己判断"：框架主动收尾时 <b>{@code stopContextEngine} 先于</b>真实的
     * {@code context.close()} 执行，此刻 Context 仍存活、{@code onClose} 事件尚未到达 —— 不传意图就会走
     * 逐条 unroute，把待关闭 Context 上的规则再撤销一遍（纯浪费 + 制造"未确证"）。
     *
     * <p>未实现时 no-op（退化为原行为：逐条 unroute），兼容测试替身。
     */
    default void markContextClosing() {
        // no-op by default
    }

    /** 只读指标（T1 度量基线：每用例统计下发次数 / 路由命中数 / 已按目的撤销数 / 未确证撤销数 / 每条规则 armed 时长）。 */
    record RouteMetrics(int generation, int inFlightClaims, int pendingIo,
                          long opsAccepted, long opsRejected, long ioRejected,
                          int monitorSize, int capturedSize, long captureDropped,
                          long dispatches, long hits, int retiredByPurpose,
                          int unconfirmedRetirements, Map<String, Long> ruleArmedDurationsMs) {
    }
}
