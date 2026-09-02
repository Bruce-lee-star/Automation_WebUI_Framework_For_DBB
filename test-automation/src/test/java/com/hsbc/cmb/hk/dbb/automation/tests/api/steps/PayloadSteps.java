package com.hsbc.cmb.hk.dbb.automation.tests.api.steps;

import com.hsbc.cmb.hk.dbb.automation.framework.api.core.step.BaseStep;
import io.cucumber.java.en.Given;
import net.serenitybdd.core.steps.UIInteractionSteps;

/**
 * Payload Steps
 * Handles request body manipulation
 */
public class PayloadSteps extends UIInteractionSteps {

        private BaseStep baseStep() { return ApiTestContext.baseStep(); }

    @Given("I load payload from file {string}")
    public void loadPayloadFromFile(String fileName) {
        baseStep().loadPayload(fileName);
    }

    @Given("I set request body to {string}")
    public void setRequestBody(String body) {
        baseStep().setRequestBody(body);
    }

    @Given("I modify field {string} in request body to value {string}")
    public void modifyFieldInRequestBody(String fieldPath, String value) {
        baseStep().modifyFieldsInRequestPayload(fieldPath, value);
    }

    @Given("I clear request body")
    public void clearRequestBody() {
        baseStep().setRequestBody("");
    }
}
