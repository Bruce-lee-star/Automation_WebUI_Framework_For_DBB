package com.hsbc.cmb.hk.dbb.automation.tests.api.steps;

import com.hsbc.cmb.hk.dbb.automation.framework.api.core.step.BaseStep;
import io.cucumber.java.en.Then;
import net.serenitybdd.core.steps.UIInteractionSteps;

/**
 * Response Status Steps
 * Handles response status code verification
 */
public class ResponseStatusSteps extends UIInteractionSteps {

        private BaseStep baseStep() { return ApiTestContext.baseStep(); }

    @Then("I should get response status code {int}")
    public void verifyResponseStatusCode(int expectedStatusCode) {
        baseStep().verifyResponseStatusCode(expectedStatusCode);
    }

    @Then("I should get response status code between {int} and {int}")
    public void verifyResponseStatusCodeInRange(int min, int max) {
        int actualCode = baseStep().getResponseCode();
        if (actualCode < min || actualCode > max) {
            throw new AssertionError(String.format(
                "Response status code %d is not in range [%d, %d]", actualCode, min, max
            ));
        }
    }

    @Then("I should get a successful response")
    public void verifySuccessfulResponse() {
        int actualCode = baseStep().getResponseCode();
        if (actualCode < 200 || actualCode >= 300) {
            throw new AssertionError("Response status code " + actualCode + " is not successful (2xx)");
        }
    }
}
