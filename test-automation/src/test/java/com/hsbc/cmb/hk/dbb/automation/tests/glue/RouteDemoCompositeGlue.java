package com.hsbc.cmb.hk.dbb.automation.tests.glue;

import com.hsbc.cmb.hk.dbb.automation.framework.web.annotations.AutoBrowser;
import com.hsbc.cmb.hk.dbb.automation.tests.steps.RouteDemoCompositeSteps;
import io.cucumber.java.After;
import io.cucumber.java.Before;
import io.cucumber.java.en.Given;
import net.serenitybdd.annotations.Steps;

/**
 * Route 复合场景 Glue —— 与 {@link RouteDemoServiceGlue} 同构：
 * 类上 {@code @AutoBrowser} 由框架托管浏览器 / Context / Page，业务委托 Steps。
 *
 * <p>每个 Scenario 前后均做数据与路由清理，保证 scenario 间零污染：
 * <ul>
 *   <li>{@code @Before}：重置 demo service 后端数据（写操作会真实改动服务端列表）</li>
 *   <li>{@code @After}：注销路由规则 + 释放 Context 级采集上下文</li>
 * </ul>
 *
 * <p>前置：route-demo-service 已在 http://localhost:8888 启动。
 * 运行：mvn verify -Dcucumber.filter.tags=@route-composite
 */
@AutoBrowser(verbose = true)
public class RouteDemoCompositeGlue {

    @Steps
    private RouteDemoCompositeSteps steps;

    /** 每个 Scenario 前重置后端数据，避免上个 Scenario 的写操作泄漏。 */
    @Before
    public void beforeScenario() {
        steps.resetDemoData();
    }

    /** 每个 Scenario 后注销规则并释放采集上下文，保证资源 / 线程 / 状态不残留。 */
    @After
    public void afterScenario() {
        steps.cleanup();
    }

    // ── A. 单层复合 ──

    @Given("route composite: modify plus monitor both recorded")
    public void modifyPlusMonitorBothRecorded() {
        steps.modifyPlusMonitorBothRecorded();
    }

    @Given("route composite: modify plus delay both applied")
    public void modifyPlusDelayBothApplied() {
        steps.modifyPlusDelayBothApplied();
    }

    @Given("route composite: modify delay monitor triple composite")
    public void modifyDelayMonitorTripleComposite() {
        steps.modifyDelayMonitorTripleComposite();
    }

    @Given("route composite: mock plus delay delayed then short circuit")
    public void mockPlusDelayDelayedThenShortCircuit() {
        steps.mockPlusDelayDelayedThenShortCircuit();
    }

    @Given("route composite: mock plus modify mock short circuits modify")
    public void mockPlusModifyMockShortCircuitsModify() {
        steps.mockPlusModifyMockShortCircuitsModify();
    }

    // ── B. 跨层复合 ──

    @Given("route composite: context monitor plus page mock")
    public void contextMonitorPlusPageMock() {
        steps.contextMonitorPlusPageMock();
    }

    @Given("route composite: context delay plus page delay takes max")
    public void contextDelayPlusPageDelayTakesMax() {
        steps.contextDelayPlusPageDelayTakesMax();
    }

    @Given("route composite: context modify plus page modify merged")
    public void contextModifyPlusPageModifyMerged() {
        steps.contextModifyPlusPageModifyMerged();
    }

    @Given("route composite: context modify plus page mock short circuits")
    public void contextModifyPlusPageMockShortCircuits() {
        steps.contextModifyPlusPageMockShortCircuits();
    }

    // ── C. DSL 方法覆盖 ──

    @Given("route composite: modify all request methods")
    public void modifyAllRequestMethods() {
        steps.modifyAllRequestMethods();
    }

    @Given("route composite: mock times one shot")
    public void mockTimesOneShot() {
        steps.mockTimesOneShot();
    }

    @Given("route composite: request condition matching")
    public void requestConditionMatching() {
        steps.requestConditionMatching();
    }

    // ── E. 各 Handler 落库契约 ──

    @Given("route composite: mock handler persists contract")
    public void mockHandlerPersistsContract() {
        steps.mockHandlerPersistsContract();
    }

    @Given("route composite: mock intercept handler persists contract")
    public void mockInterceptHandlerPersistsContract() {
        steps.mockInterceptHandlerPersistsContract();
    }

    @Given("route composite: modify handler persists contract")
    public void modifyHandlerPersistsContract() {
        steps.modifyHandlerPersistsContract();
    }

    @Given("route composite: monitor handler persists contract")
    public void monitorHandlerPersistsContract() {
        steps.monitorHandlerPersistsContract();
    }

    @Given("route composite: delay handler persists contract")
    public void delayHandlerPersistsContract() {
        steps.delayHandlerPersistsContract();
    }

    // ── F. onResponse 设置主线程变量 ──

    @Given("route composite: monitor response body readable from main thread")
    public void monitorResponseBodyReadableMainThread() {
        steps.monitorResponseBodyReadableMainThread();
    }

    // ── D. 清理与隔离 ──

    @Given("route composite: cleanup resets route and capture state")
    public void cleanupResetsRouteAndCaptureState() {
        steps.cleanupResetsRouteAndCaptureState();
    }
}
