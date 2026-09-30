package com.hsbc.cmb.hk.dbb.automation.framework.route.lifecycle;

import com.hsbc.cmb.hk.dbb.automation.framework.common.route.RouteAssertionFailure;
import com.hsbc.cmb.hk.dbb.automation.framework.common.route.RouteAssertionProbe;
import com.hsbc.cmb.hk.dbb.automation.framework.common.route.RouteAssertionRegistry;
import com.hsbc.cmb.hk.dbb.automation.framework.route.RouteEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Route V2 断言失败探针 SPI 实现 —— 自注册到核心层 {@link RouteAssertionRegistry}。
 *
 * <p>职责：把 V2 各 context 运行时（{@link RouteRuntime}）已定案的 MONITOR 断言失败
 * 聚合给 web 侧消费（步骤结束抛 AssertionError / 用例收尾标记 FAILURE）。
 * 消费式语义保证幂等——失败被取走即清空，两条上报路径不会重复触发。
 *
 * <p>注册时机：本类静态块自注册；由 {@link RouteEngine} 静态块在业务首次使用 V2 时触发加载，
 * 或 web 侧 {@code RouteAssertionRegistry.get()} 延迟加载。与 {@link RouteLifecycleImpl}
 * 的注册机制一致（classpath 无 V2 时 registry 保持 null、调用方跳过）。
 */
public final class RouteAssertionProbeImpl implements RouteAssertionProbe {

    private static final Logger LOGGER = LoggerFactory.getLogger(RouteAssertionProbeImpl.class);

    static {
        RouteAssertionRegistry.register(new RouteAssertionProbeImpl());
        LOGGER.debug("[Route] RouteAssertionProbeImpl registered as assertion probe");
    }

    @Override
    public List<RouteAssertionFailure> drainAndResolveFailures() {
        return RouteEngine.drainSettledAssertionFailures();
    }
}
