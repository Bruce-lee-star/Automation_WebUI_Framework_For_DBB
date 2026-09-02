package com.hsbc.cmb.hk.dbb.automation.tests.api.steps;

import com.hsbc.cmb.hk.dbb.automation.framework.api.core.step.BaseStep;
import io.cucumber.java.en.Given;
import net.serenitybdd.core.steps.UIInteractionSteps;

/**
 * Base Configuration Steps
 * Handles setting base URI and endpoint
 */
public class BaseConfigurationSteps extends UIInteractionSteps {

        private BaseStep baseStep() { return ApiTestContext.baseStep(); }

    @Given("I set base URI to {string}")
    public void setBaseUri(String baseUri) {
        baseStep().setBaseUri(baseUri);
    }

    @Given("I set endpoint to {string}")
    public void setEndpoint(String endpoint) {
        baseStep().setEndpoint(endpoint);
    }
}
