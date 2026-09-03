package com.hsbc.cmb.hk.dbb.automation.tests.glue;

import com.hsbc.cmb.hk.dbb.automation.tests.steps.PickerSmokeSteps;
import io.cucumber.java.en.And;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import net.serenitybdd.annotations.Steps;

/**
 * Cucumber glue for the picker browser smoke. Mirrors {@link BaiduGlue}: the
 * @When/@Given/@Then annotations map Gherkin steps onto Serenity @Step methods.
 */
public class PickerSmokeGlue {

    @Steps
    private PickerSmokeSteps pickerSteps;

    @Given("the local scan test page is open")
    public void theLocalScanTestPageIsOpen() {
        pickerSteps.openScanTestPage();
    }

    @When("the panel bootstrap script is injected")
    public void thePanelBootstrapScriptIsInjected() {
        pickerSteps.injectPanelBootstrap();
    }

    @When("the pick mode is set to {string} through the externalised arg script")
    public void thePickModeIsSetToThroughTheExternalisedArgScript(String mode) {
        pickerSteps.setPickMode(mode);
    }

    @Then("the window pick mode equals {string}")
    public void theWindowPickModeEquals(String mode) {
        pickerSteps.assertWindowPickMode(mode);
    }

    @Then("the pick state reader returns an object with keys {string}")
    public void thePickStateReaderReturnsAnObjectWithKeys(String keys) {
        pickerSteps.assertPickStateReaderHasKeys(keys);
    }
}
