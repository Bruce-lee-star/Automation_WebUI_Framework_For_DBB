package com.hsbc.cmb.hk.dbb.automation.framework.route.v2;

import com.hsbc.cmb.hk.dbb.automation.framework.common.route.RouteV2AssertionFailure;
import com.hsbc.cmb.hk.dbb.automation.framework.route.v2.binding.GenerationRegistry;
import com.hsbc.cmb.hk.dbb.automation.framework.route.v2.claim.ClaimRegistry;
import com.hsbc.cmb.hk.dbb.automation.framework.route.v2.claim.PendingGuard;
import com.hsbc.cmb.hk.dbb.automation.framework.route.v2.dsl.ApiSpec;
import com.hsbc.cmb.hk.dbb.automation.framework.route.v2.dsl.RouteCapability;
import com.hsbc.cmb.hk.dbb.automation.framework.route.v2.exec.BoundedOps;
import com.hsbc.cmb.hk.dbb.automation.framework.route.v2.exec.RouteIoExecutor;
import com.hsbc.cmb.hk.dbb.automation.framework.route.v2.monitor.CapturedApiCall;
import com.microsoft.playwright.Request;
import com.microsoft.playwright.Route;

import java.util.List;

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

    /** runtime 是否已关闭。 */
    boolean isClosed();

    /**
     * runtime 是否已降级（注册曾失败）；context 复用方据此丢弃该 Context。
     * @apiNote framework-internal: 由实现内部在注册失败时标记
     */
    boolean isDegraded();

    /**
     * 统一观测入口（dispatch 对所有能力调用）：CAPTURE 对<b>所有能力</b>采集；MONITOR 记录只对
     * <b>带响应侧期望</b>的 MONITOR 规则生效 —— 非 MONITOR 能力（MOCK/MODIFY/DELAY）与无期望的
     * MONITOR 规则不进入观测队列，避免"记录后永不配对"在 drain 时被误判为监控超时并归因场景失败。
     * @apiNote framework-internal: 仅 RouteDispatcher 调用
     */
    void recordObservation(Request request, ApiSpec spec);

    // ── 稳定公开契约 ──

    /** 取走本 runtime 已采集的 API 快照（消费式；幂等——第二次调用返回空列表）。 */
    List<CapturedApiCall> dumpCapturedApis();

    /** 取走本 runtime 已定案的断言失败（消费式；幂等）。 */
    List<RouteV2AssertionFailure> drainSettledAssertionFailures();

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
    RouteV2Metrics metrics();

    @Override
    void close();

    /** 只读指标。 */
    record RouteV2Metrics(int generation, int inFlightClaims, int pendingIo,
                          long opsAccepted, long opsRejected, long ioRejected,
                          int monitorSize, int capturedSize, long captureDropped) {
    }
}
