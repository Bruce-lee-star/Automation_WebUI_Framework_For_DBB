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

import java.time.Duration;
import java.util.List;
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
    public GenerationRegistry generations() {
        return generations;
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

    @Override
    public void recordObservation(Request request, ApiSpec spec) {
        // no-op
    }

    @Override
    public List<CapturedApiCall> dumpCapturedApis() {
        return List.of();
    }

    @Override
    public List<RouteV2AssertionFailure> drainSettledAssertionFailures() {
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
    public RouteV2Metrics metrics() {
        return new RouteV2Metrics(0, 0, 0, 0, 0, 0, 0, 0, 0);
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
