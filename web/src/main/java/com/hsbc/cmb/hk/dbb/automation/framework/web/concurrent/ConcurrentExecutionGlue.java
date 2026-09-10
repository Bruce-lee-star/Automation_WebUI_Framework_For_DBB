package com.hsbc.cmb.hk.dbb.automation.framework.web.concurrent;

import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.concurrent.ConcurrentContextOptions;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.concurrent.ContextTaskResult;
import io.cucumber.datatable.DataTable;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 框架层并发执行 glue（<b>非业务代码</b>，随 {@code web} 框架分发）。
 *
 * <p>把并发调度的全部步骤以<b>通用</b>形式暴露为 Cucumber 步骤，业务 feature 直接引用即可，
 * 业务层<b>零并发代码</b>。领域动作由 {@link ConcurrentCaseActions#require()} 在编排线程取得
 * （业务经 SPI 或显式注册提供）。</p>
 *
 * <p>使用（业务 feature）：
 * <pre>
 *   Given 准备并发批次
 *   When 并发执行以下:
 *     | env      | username |
 *     | O63_SIT1 | alice    |
 *   Then 全部并发执行成功
 * </pre>
 * 运行须携带 {@code -Dserenity.playwright.shared.browser.enabled=true} 开启单 Browser 多 Context 模型；
 * 开启 {@code -Dserenity.playwright.concurrent.partition.enabled=true} 后相同身份被 SSO 闸门串行化。</p>
 */
public class ConcurrentExecutionGlue {

    private List<ContextTaskResult<String>> results;

    /** 通用预备：幂等初始化框架 + 预热共享 Browser（单 Browser 多 Context）。 */
    @Given("准备并发批次")
    public void prepareBatch() {
        ConcurrentScenarioExecutor.prepareSharedBrowser();
    }

    /** 通用驱动：把 DataTable 每行作为一个并发用例，经框架线程池并发执行。 */
    @When("并发执行以下:")
    public void runBatch(DataTable dataTable) {
        List<Map<String, String>> rows = dataTable.asMaps(String.class, String.class);
        List<ConcurrentCaseData> cases = rows.stream()
                .map(ConcurrentCaseData::new)
                .collect(Collectors.toList());
        // 显式并行度=用例数（无排队）；单任务超时放大到 600s，避免相同身份被闸门串行后长尾任务在默认
        // 超时被取消（保留既有语义）。
        results = ConcurrentScenarioExecutor.runCases(cases,
                ConcurrentCaseActions.require(),
                ConcurrentContextOptions.builder()
                        .parallelism(cases.size())
                        .perTaskTimeoutMillis(600_000L)
                        .build());
    }

    /** 通用断言：编排线程回放全部失败（桥接 9.3）。 */
    @Then("全部并发执行成功")
    public void assertAllSucceeded() {
        ConcurrentScenarioExecutor.assertAllSucceeded(results);
    }
}
