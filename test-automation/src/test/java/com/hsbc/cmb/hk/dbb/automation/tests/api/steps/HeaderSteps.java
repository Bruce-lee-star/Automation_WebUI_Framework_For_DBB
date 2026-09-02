package com.hsbc.cmb.hk.dbb.automation.tests.api.steps;

import com.hsbc.cmb.hk.dbb.automation.framework.api.core.step.BaseStep;
import io.cucumber.datatable.DataTable;
import io.cucumber.java.en.Given;
import net.serenitybdd.core.steps.UIInteractionSteps;

import java.util.List;
import java.util.Map;

/**
 * Header Steps
 * Handles request headers manipulation
 */
public class HeaderSteps extends UIInteractionSteps {

        private BaseStep baseStep() { return ApiTestContext.baseStep(); }

    @Given("I set request header {string} to {string}")
    public void setRequestHeader(String name, String value) {
        baseStep().addRequestHeader(name, value);
    }

    @Given("I add request header {string} with value {string}")
    public void addRequestHeader(String name, String value) {
        baseStep().addRequestHeader(name, value);
    }

    @Given("I update request header {string} to value {string}")
    public void updateRequestHeader(String name, String value) {
        baseStep().updateHeader(name, value);
    }

    @Given("I remove request header {string}")
    public void removeRequestHeader(String name) {
        baseStep().removeHeader(name);
    }

    @Given("I clear all request headers")
    public void clearRequestHeaders() {
        baseStep().clearHeader();
    }

    @Given("I set request headers:")
    public void setRequestHeaders(DataTable dataTable) {
        List<Map<String, String>> rows = dataTable.asMaps(String.class, String.class);
        for (Map<String, String> row : rows) {
            baseStep().addRequestHeader(row.get("name"), row.get("value"));
        }
    }
}
