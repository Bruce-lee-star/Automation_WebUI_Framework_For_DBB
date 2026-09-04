package com.hsbc.cmb.hk.dbb.automation.tests.glue;

import com.hsbc.cmb.hk.dbb.automation.framework.web.annotations.AutoBrowser;
import com.hsbc.cmb.hk.dbb.automation.tests.steps.E2ESandboxSteps;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import net.serenitybdd.annotations.Steps;

/**
 * E2E 沙箱 Gherkin 步骤定义。
 *
 * <p>与 {@link com.hsbc.cmb.hk.dbb.automation.tests.glue.LogonGlue} 保持一致的写法：
 * 类上标注 {@code @AutoBrowser} 由框架自动托管浏览器生命周期，业务步骤零侵入。</p>
 *
 * <p>对应 feature：{@code src/test/resources/features/web/e2e_sandbox.feature}</p>
 */
@AutoBrowser(verbose = true)
public class E2ESandboxGlue {

    @Steps
    private E2ESandboxSteps sandboxSteps;

    @Given("the E2E sandbox page is open")
    public void openSandboxPage() {
        sandboxSteps.openSandbox();
    }

    @When("I sign in to the sandbox as {string} with password {string}")
    public void signInToSandbox(String user, String password) {
        sandboxSteps.signIn(user, password);
    }

    @Then("the sandbox message contains {string}")
    public void sandboxMessageContains(String expected) {
        sandboxSteps.verifyMessageText(expected);
    }

    @When("I load async data")
    public void loadAsyncData() {
        sandboxSteps.loadAsyncData();
    }

    @Then("the sandbox async panel contains {string}")
    public void sandboxAsyncPanelContains(String expected) {
        sandboxSteps.verifyAsyncPanelText(expected);
    }

    @When("I increment the sandbox counter {int} times")
    public void incrementSandboxCounter(int times) {
        sandboxSteps.incrementCounter(times);
    }

    @Then("the sandbox counter is {string}")
    public void sandboxCounterIs(String expected) {
        sandboxSteps.verifyCounter(expected);
    }

    @Then("the sandbox browser is isolated to this thread")
    public void sandboxBrowserIsolated() {
        sandboxSteps.verifyBrowserIsolation();
    }
}
