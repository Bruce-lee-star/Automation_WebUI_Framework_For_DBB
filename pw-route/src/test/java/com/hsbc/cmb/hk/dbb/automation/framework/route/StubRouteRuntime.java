package com.hsbc.cmb.hk.dbb.automation.framework.route;

import com.hsbc.cmb.hk.dbb.automation.framework.common.route.RouteAssertionFailure;
import com.hsbc.cmb.hk.dbb.automation.framework.route.binding.GenerationRegistry;
import com.hsbc.cmb.hk.dbb.automation.framework.route.binding.RuleGeneration;
import com.hsbc.cmb.hk.dbb.automation.framework.route.claim.ClaimRegistry;
import com.hsbc.cmb.hk.dbb.automation.framework.route.claim.PendingGuard;
import com.hsbc.cmb.hk.dbb.automation.framework.route.dsl.ApiSpec;
import com.hsbc.cmb.hk.dbb.automation.framework.route.dsl.RouteCapability;
import com.hsbc.cmb.hk.dbb.automation.framework.route.exec.BoundedOps;
import com.hsbc.cmb.hk.dbb.automation.framework.route.exec.RouteIoExecutor;
import com.hsbc.cmb.hk.dbb.automation.framework.route.monitor.CapturedApiCall;
import com.microsoft.playwright.Request;
import com.microsoft.playwright.Route;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 测试替身（真实实现类，满足"每接口≥2实现：生产 RouteRuntimeImpl + 测试替身"验收）。
 *
 * <p>仅用于验证工厂换实现全链路生效；不依赖真实浏览器/驱动。协作者以真实轻量实例承载
 * （本模块仅 {@code mockito-core}，不支持 mock final 类），{@link #close()} 负责释放其资源。
 */
public final class StubRouteRuntime implements RouteRuntime {

    private final GenerationRegistry generations = new GenerationRegistry();
    private final PendingGuard pending = new PendingGuard(1);
    private final ClaimRegistry claims = new ClaimRegistry(pending, Duration.ofMillis(1));
    private final BoundedOps ops = new BoundedOps(1, Duration.ofMillis(1));
    private final RouteIoExecutor io = new RouteIoExecutor("stub", 1, 1);
    private final AtomicBoolean closed = new AtomicBoolean(false);

    @Override
    public RuleGeneration ruleSnapshot() {
        return generations.snapshot();
    }

    @Override
    public ClaimRegistry claims() {
        return claims;
    }

    @Override
    public PendingGuard pending() {
        return pending;
    }

    @Override
    public BoundedOps ops() {
        return ops;
    }

    @Override
    public RouteIoExecutor io() {
        return io;
    }

    @Override
    public boolean isClosed() {
        return closed.get();
    }

    @Override
    public boolean isDegraded() {
        return false;
    }

    /** T2+ 契约：替身无绑定可撤 ⇒ 等价"无需撤销"。 */
    @Override
    public boolean retireByPurpose(String pattern) {
        return true;
    }
    /** V2-2 契约：替身无绑定 ⇒ 无规则可清（幂等 0）。 */
    @Override
    public int clearRules() {
        return 0;
    }


    /** T2+ 契约：替身不存在不可确证的撤销 ⇒ 始终干净。 */
    @Override
    public boolean isClean() {
        return true;
    }

    /** T2+ 契约：无未确证撤销。 */
    @Override
    public int unconfirmedRetirements() {
        return 0;
    }

    @Override
    public void recordObservation(Request request, ApiSpec spec) {
        // no-op
    }

    @Override
    public List<CapturedApiCall> dumpCapturedApis() {
        return List.of();
    }

    @Override
    public List<RouteAssertionFailure> drainSettledAssertionFailures() {
        return List.of();
    }

    @Override
    public AutoCloseable register(ApiSpec spec) {
        return () -> {
            // no-op
        };
    }

    @Override
    public boolean stop(RouteCapability capability, String pattern) {
        return false;
    }

    @Override
    public boolean stopApi(String pattern) {
        return false;
    }

    @Override
    public void dispatch(Route route, String pattern) {
        // no-op
    }

    @Override
    public RouteMetrics metrics() {
        return new RouteMetrics(0, 0, 0, 0, 0, 0, 0, 0, 0, 0L, 0L, 0, 0, Map.of());
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            try {
                claims.closeAll();
            } catch (Exception ignored) {
                // 测试替身：资源释放失败不影响验证
            }
            try {
                io.close();
            } catch (Exception ignored) {
                // 同上
            }
        }
    }
}
