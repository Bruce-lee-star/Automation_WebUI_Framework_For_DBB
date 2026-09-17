package com.hsbc.cmb.hk.dbb.automation.tests.glue;

import com.hsbc.cmb.hk.dbb.automation.framework.common.cleanstate.CleanStateRegistry;
import com.hsbc.cmb.hk.dbb.automation.framework.common.cleanstate.RequiresCleanState;
import com.hsbc.cmb.hk.dbb.automation.framework.common.cleanstate.StateResolver;
import com.hsbc.cmb.hk.dbb.automation.framework.web.annotations.AutoBrowser;
import com.hsbc.cmb.hk.dbb.automation.tests.steps.RouteDemoCoverageSteps;
import io.cucumber.java.en.Given;
import net.serenitybdd.annotations.Steps;

/**
 * 按能力维度显式停止 Glue —— 配合 route-demo-web（:8899）。
 *
 * <p>验证 {@code RouteDsl.stopMonitor / stopModify / stopDelay / stopMock / stopApi}：
 * 停止某能力只影响该能力，同 API 的其余能力不受影响；stopApi 停止全部能力（路由仍注册，走真实后端）。
 *
 * <p>前置：route-demo-web 已在 http://localhost:8899 启动。
 * 运行：mvn verify -Dcucumber.filter.tags=@route-capability-stop
 */
@AutoBrowser(verbose = true)
@RequiresCleanState({"route-capability-stop-route-rules"})
public class RouteDemoCapabilityStopGlue {

    // B-6：原手写 @Before 预清理 / @After 清理改由框架 CleanStateHooks 统一驱动
    //      （复位前后各一次、幂等，清掉 STOPPED_CAPS 等残留，保证每个 Scenario 从干净状态开始）。
    static {
        CleanStateRegistry.register(new StateResolver() {
            @Override public String name() { return "route-capability-stop-route-rules"; }
            @Override public void reset() { new RouteDemoCoverageSteps().cleanup(); }
        });
    }

    @Steps
    private RouteDemoCoverageSteps steps;

    @Given("route capability-stop: monitor 停止后 modify 与 delay 仍生效")
    public void monitorStop() {
        steps.capabilityStopMonitorLeavesModifyAndDelay();
    }

    @Given("route capability-stop: modify 停止后 monitor 与 delay 仍生效")
    public void modifyStop() {
        steps.capabilityStopModifyLeavesMonitorAndDelay();
    }

    @Given("route capability-stop: delay 停止后 monitor 与 modify 仍生效")
    public void delayStop() {
        steps.capabilityStopDelayLeavesMonitorAndModify();
    }

    @Given("route capability-stop: mock 停止后回退真实后端")
    public void mockStop() {
        steps.capabilityStopMockFallsBackToReal();
    }

    @Given("route capability-stop: stopApi 停止全部能力")
    public void stopAll() {
        steps.capabilityStopAllStopsEverything();
    }
}
