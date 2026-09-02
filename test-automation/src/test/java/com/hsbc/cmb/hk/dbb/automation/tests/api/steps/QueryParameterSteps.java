package com.hsbc.cmb.hk.dbb.automation.tests.api.steps;

import com.hsbc.cmb.hk.dbb.automation.framework.api.core.step.BaseStep;
import io.cucumber.datatable.DataTable;
import io.cucumber.java.en.Given;
import net.serenitybdd.core.steps.UIInteractionSteps;

import java.util.List;
import java.util.Map;

/**
 * Query Parameter Steps
 * Handles query parameters manipulation
 */
public class QueryParameterSteps extends UIInteractionSteps {

        private BaseStep baseStep() { return ApiTestContext.baseStep(); }

    @Given("I set query parameter {string} to {string}")
    public void setQueryParam(String name, String value) {
        baseStep().addQueryParam(name, value);
    }

    @Given("I add query parameter {string} with value {string}")
    public void addQueryParam(String name, String value) {
        baseStep().addQueryParam(name, value);
    }

    @Given("I update query parameter {string} to value {string}")
    public void updateQueryParam(String name, String value) {
        baseStep().updateQueryParam(name, value);
    }

    @Given("I remove query parameter {string}")
    public void removeQueryParam(String name) {
        baseStep().removeQueryParam(name);
    }

    @Given("I clear all query parameters")
    public void clearQueryParams() {
        baseStep().clearQueryParams();
    }

    @Given("I set query parameters:")
    public void setQueryParams(DataTable dataTable) {
        List<Map<String, String>> rows = dataTable.asMaps(String.class, String.class);
        for (Map<String, String> row : rows) {
            baseStep().addQueryParam(row.get("name"), row.get("value"));
        }
    }
}
