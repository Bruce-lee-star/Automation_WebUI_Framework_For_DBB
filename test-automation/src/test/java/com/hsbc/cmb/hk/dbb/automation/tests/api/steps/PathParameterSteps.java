package com.hsbc.cmb.hk.dbb.automation.tests.api.steps;

import com.hsbc.cmb.hk.dbb.automation.framework.api.core.step.BaseStep;
import io.cucumber.datatable.DataTable;
import io.cucumber.java.en.Given;
import net.serenitybdd.core.steps.UIInteractionSteps;

import java.util.List;
import java.util.Map;

/**
 * Path Parameter Steps
 * Handles path parameters manipulation
 */
public class PathParameterSteps extends UIInteractionSteps {

        private BaseStep baseStep() { return ApiTestContext.baseStep(); }

    @Given("I add path parameter {string} with value {string}")
    public void addPathParam(String name, String value) {
        baseStep().addPathParam(name, value);
    }

    @Given("I update path parameter {string} to value {string}")
    public void updatePathParam(String name, String value) {
        baseStep().updatePathParam(name, value);
    }

    @Given("I remove path parameter {string}")
    public void removePathParam(String name) {
        baseStep().removePathParam(name);
    }

    @Given("I clear all path parameters")
    public void clearPathParams() {
        baseStep().clearPathParams();
    }

    @Given("I set path parameters:")
    public void setPathParams(DataTable dataTable) {
        List<Map<String, String>> rows = dataTable.asMaps(String.class, String.class);
        for (Map<String, String> row : rows) {
            baseStep().addPathParam(row.get("name"), row.get("value"));
        }
    }
}
