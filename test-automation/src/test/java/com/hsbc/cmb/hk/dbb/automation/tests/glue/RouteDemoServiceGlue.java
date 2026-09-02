package com.hsbc.cmb.hk.dbb.automation.tests.glue;

import com.hsbc.cmb.hk.dbb.automation.framework.web.annotations.AutoBrowser;
import com.hsbc.cmb.hk.dbb.automation.tests.steps.RouteDemoServiceSteps;
import io.cucumber.java.After;
import io.cucumber.java.en.Given;
import net.serenitybdd.annotations.Steps;

/**
 * Route Demo Service 集成测试 Glue —— 参照 login_dbb 的模式：
 * 在 Glue 类上加 {@code @AutoBrowser} 注解，由框架零侵入地托管浏览器 / Context / Page，
 * 业务步骤全部委托给 {@link RouteDemoServiceSteps}（与 {@code LogonGlue} 委托 {@code LoginSteps} 一致）。
 *
 * <p>前置：route-demo-service 已在 http://localhost:8888 启动。
 * 运行：mvn verify -Dcucumber.filter.tags=@route
 */
@AutoBrowser(verbose = true)
public class RouteDemoServiceGlue {

    @Steps
    private RouteDemoServiceSteps steps;

    @After
    public void afterScenario() {
        steps.cleanup();
    }

    @Given("route demo: monitor collects real response")
    public void monitorCollectsRealResponse() {
        steps.monitorCollectsRealResponse();
    }

    @Given("route demo: mock replaces whole response")
    public void mockReplacesWholeResponse() {
        steps.mockReplacesWholeResponse();
    }

    @Given("route demo: mock intercept real response then replace field")
    public void mockInterceptRealResponseThenReplaceField() {
        steps.mockInterceptRealResponseThenReplaceField();
    }

    @Given("route demo: conditional modify array element by role")
    public void conditionalModifyArrayElementByRole() {
        steps.conditionalModifyArrayElementByRole();
    }

    @Given("route demo: conditional modify numeric greater than")
    public void conditionalModifyNumericGreaterThan() {
        steps.conditionalModifyNumericGreaterThan();
    }

    @Given("route demo: conditional modify not equals")
    public void conditionalModifyNotEquals() {
        steps.conditionalModifyNotEquals();
    }

    @Given("route demo: conditional modify greater than")
    public void conditionalModifyGreaterThan() {
        steps.conditionalModifyGreaterThan();
    }

    @Given("route demo: conditional modify less than")
    public void conditionalModifyLessThan() {
        steps.conditionalModifyLessThan();
    }

    @Given("route demo: conditional modify less than or equal")
    public void conditionalModifyLessThanOrEqual() {
        steps.conditionalModifyLessThanOrEqual();
    }

    @Given("route demo: conditional modify contains")
    public void conditionalModifyContains() {
        steps.conditionalModifyContains();
    }

    @Given("route demo: conditional modify not contains")
    public void conditionalModifyNotContains() {
        steps.conditionalModifyNotContains();
    }

    @Given("route demo: conditional modify regex")
    public void conditionalModifyRegex() {
        steps.conditionalModifyRegex();
    }

    @Given("route demo: conditional modify exists")
    public void conditionalModifyExists() {
        steps.conditionalModifyExists();
    }

    @Given("route demo: conditional modify not exists")
    public void conditionalModifyNotExists() {
        steps.conditionalModifyNotExists();
    }

    @Given("route demo: modify request body forwarded to server")
    public void modifyRequestBodyIsForwardedToServer() {
        steps.modifyRequestBodyIsForwardedToServer();
    }

    @Given("route demo: delay applies latency")
    public void delayAppliesLatency() {
        steps.delayAppliesLatency();
    }

    @Given("route demo: delay plus monitor still captures")
    public void delayPlusMonitorMonitorStillCaptures() {
        steps.delayPlusMonitorMonitorStillCaptures();
    }

    @Given("route demo: modify then monitor same api both active")
    public void modifyThenMonitorSameApiBothActive() {
        steps.modifyThenMonitorSameApiBothActive();
    }

    @Given("route demo: monitor then modify same api both active")
    public void monitorThenModifySameApiBothActive() {
        steps.monitorThenModifySameApiBothActive();
    }

    @Given("route demo: priority mock and monitor sees mocked response")
    public void priorityMockAndMonitorSeesMockedResponse() {
        steps.priorityMockAndMonitorSeesMockedResponse();
    }

    @Given("route demo: single context rule disabled after clear")
    public void singleContextRuleDisabledAfterClear() {
        steps.singleContextRuleDisabledAfterClear();
    }

    @Given("route demo: multi context isolation")
    public void multiContextIsolation() {
        steps.multiContextIsolation();
    }
}
