package com.hsbc.cmb.hk.dbb.automation.tests.glue;

import com.hsbc.cmb.hk.dbb.automation.framework.web.annotations.AutoBrowser;
import com.hsbc.cmb.hk.dbb.automation.tests.steps.RouteDemoCoverageSteps;
import io.cucumber.java.After;
import io.cucumber.java.Before;
import io.cucumber.java.en.Given;
import net.serenitybdd.annotations.Steps;

/**
 * Route DSL 100% 覆盖 Glue —— 配合 route-demo-web（:8899）。
 *
 * <p>类上 {@code @AutoBrowser} 由框架托管浏览器 / Context / Page，业务委托 {@link RouteDemoCoverageSteps}。
 *
 * <p>前置：route-demo-web 已在 http://localhost:8899 启动。
 * 运行：mvn verify -Dcucumber.filter.tags=@route-coverage
 */
@AutoBrowser(verbose = true)
public class RouteDemoCoverageGlue {

    @Steps
    private RouteDemoCoverageSteps steps;

    /**
     * feature 模式下 context 不重建 —— Scenario 开始前先预清理一次，保证即便上一个 Scenario 的 @After
     * 因异常未执行，本 Scenario 也不会继承到残留路由 / 采集状态。
     */
    @Before
    public void beforeScenario() {
        steps.cleanup();
    }

    @After
    public void afterScenario() {
        steps.cleanup();
    }

    // ── Modify ──
    @Given("route coverage: modify setRequestHeaders map")
    public void modifySetRequestHeadersMap() { steps.modifySetRequestHeadersMap(); }

    @Given("route coverage: modifyMethod changes outgoing method")
    public void modifyMethodChangesOutgoing() { steps.modifyMethodChangesOutgoing(); }

    // ── Mock ──
    @Given("route coverage: mockBodyFromFile")
    public void mockBodyFromFileCoverage() { steps.mockBodyFromFileCoverage(); }

    @Given("route coverage: mockBodyFromFile with overrides")
    public void mockBodyFromFileWithOverrides() { steps.mockBodyFromFileWithOverrides(); }

    @Given("route coverage: mockHeader")
    public void mockHeaderCoverage() { steps.mockHeaderCoverage(); }

    @Given("route coverage: mockStatus")
    public void mockStatusCoverage() { steps.mockStatusCoverage(); }

    @Given("route coverage: replaceField pure mock")
    public void replaceFieldCoverage() { steps.replaceFieldCoverage(); }

    @Given("route coverage: replaceFields pure mock")
    public void replaceFieldsCoverage() { steps.replaceFieldsCoverage(); }

    // ── Delay ──
    @Given("route coverage: randomDelay")
    public void randomDelayCoverage() { steps.randomDelayCoverage(); }

    // ── times ──
    @Given("route coverage: times one shot")
    public void timesOneShotCoverage() { steps.timesOneShotCoverage(); }

    // ── 条件匹配 ──
    @Given("route coverage: matchMethod")
    public void condMatchMethod() { steps.condMatchMethod(); }

    @Given("route coverage: resourceType image")
    public void condResourceType() { steps.condResourceType(); }

    @Given("route coverage: resourceType script")
    public void condResourceTypeScript() { steps.condResourceTypeScript(); }

    @Given("route coverage: onlyXhr")
    public void condOnlyXhr() { steps.condOnlyXhr(); }

    @Given("route coverage: onlyFetch")
    public void condOnlyFetch() { steps.condOnlyFetch(); }

    @Given("route coverage: onlyApi")
    public void condOnlyApi() { steps.condOnlyApi(); }

    @Given("route coverage: matchHeader")
    public void condMatchHeader() { steps.condMatchHeader(); }

    @Given("route coverage: matchQuery")
    public void condMatchQuery() { steps.condMatchQuery(); }

    @Given("route coverage: matchBodyRegex")
    public void condMatchBodyRegex() { steps.condMatchBodyRegex(); }

    @Given("route coverage: matchContentType")
    public void condMatchContentType() { steps.condMatchContentType(); }

    @Given("route coverage: matchReferrer")
    public void condMatchReferrer() { steps.condMatchReferrer(); }

    @Given("route coverage: matchOrigin")
    public void condMatchOrigin() { steps.condMatchOrigin(); }

    @Given("route coverage: matchFrameUrl")
    public void condMatchFrameUrl() { steps.condMatchFrameUrl(); }

    @Given("route coverage: onlyMainFrame")
    public void condOnlyMainFrame() { steps.condOnlyMainFrame(); }

    @Given("route coverage: allowAllFrames")
    public void condAllowAllFrames() { steps.condAllowAllFrames(); }

    @Given("route coverage: onlyApiCall")
    public void condOnlyApiCall() { steps.condOnlyApiCall(); }

    @Given("route coverage: allowAllRequests")
    public void condAllowAllRequests() { steps.condAllowAllRequests(); }

    // ── Monitor ──
    @Given("route coverage: monitor response body readable from main thread")
    public void monitorResponseBodyReadable() { steps.monitorResponseBodyReadable(); }
}
