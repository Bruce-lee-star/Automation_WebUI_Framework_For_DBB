package com.hsbc.cmb.hk.dbb.automation.tests.glue;

import com.hsbc.cmb.hk.dbb.automation.framework.web.annotations.AutoBrowser;
import com.hsbc.cmb.hk.dbb.automation.tests.steps.RouteDemoCoverageSteps;
import io.cucumber.java.After;
import io.cucumber.java.Before;
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
public class RouteDemoCapabilityStopGlue {

    @Steps
    private RouteDemoCoverageSteps steps;

    /**
     * feature 模式下 context 不重建 —— Scenario 开始前先预清理一次，同步清掉 STOPPED_CAPS 残留，
     * 保证每个 Scenario 从干净状态开始（避免上一 Scenario 的停止标记污染本 Scenario）。
     */
    @Before
    public void beforeScenario() {
        steps.cleanup();
    }

    @After
    public void afterScenario() {
        steps.cleanup();
    }

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
