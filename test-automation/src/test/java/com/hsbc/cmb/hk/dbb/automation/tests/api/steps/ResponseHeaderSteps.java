package com.hsbc.cmb.hk.dbb.automation.tests.api.steps;

import com.hsbc.cmb.hk.dbb.automation.framework.api.core.step.BaseStep;
import io.cucumber.datatable.DataTable;
import io.cucumber.java.en.Then;
import net.serenitybdd.core.steps.UIInteractionSteps;

import java.util.List;
import java.util.Map;

/**
 * Response Header Steps
 * Handles response header verification
 */
public class ResponseHeaderSteps extends UIInteractionSteps {

        private BaseStep baseStep() { return ApiTestContext.baseStep(); }

    @Then("response header {string} should exist")
    public void responseHeaderShouldExist(String headerName) {
        baseStep().verifyResponseHeader(headerName, ".*");
    }

    @Then("response header {string} should be {string}")
    public void responseHeaderShouldBe(String headerName, String expectedValue) {
        baseStep().verifyResponseHeader(headerName, expectedValue);
    }

    @Then("response header {string} should contain {string}")
    public void responseHeaderShouldContain(String headerName, String expectedValue) {
        Map<String, String> headers = baseStep().getEntity().getResponseHeaders();
        String actualValue = headers.get(headerName);
        if (actualValue == null || !actualValue.contains(expectedValue)) {
            throw new AssertionError(String.format(
                "Response header %s does not contain '%s'. Actual: %s",
                headerName, expectedValue, actualValue
            ));
        }
    }

    @Then("response header {string} should match pattern {string}")
    public void responseHeaderShouldMatchPattern(String headerName, String pattern) {
        Map<String, String> headers = baseStep().getEntity().getResponseHeaders();
        String actualValue = headers.get(headerName);
        if (actualValue == null || !actualValue.matches(pattern)) {
            throw new AssertionError(String.format(
                "Response header %s does not match pattern '%s'. Actual: %s",
                headerName, pattern, actualValue
            ));
        }
    }

    @Then("response headers should contain:")
    public void responseHeadersShouldContain(DataTable dataTable) {
        List<Map<String, String>> rows = dataTable.asMaps(String.class, String.class);
        for (Map<String, String> row : rows) {
            responseHeaderShouldContain(row.get("name"), row.get("value"));
        }
    }

    @Then("response header {string} should not exist")
    public void responseHeaderShouldNotExist(String headerName) {
        Map<String, String> headers = baseStep().getEntity().getResponseHeaders();
        if (headers.containsKey(headerName)) {
            throw new AssertionError("Response header " + headerName + " should not exist, but found: " + headers.get(headerName));
        }
    }

    @Then("response should have at least {int} headers")
    public void responseShouldHaveAtLeastHeaders(int minCount) {
        Map<String, String> headers = baseStep().getEntity().getResponseHeaders();
        if (headers.size() < minCount) {
            throw new AssertionError(
                "Response has " + headers.size() + " headers, expected at least " + minCount
            );
        }
    }

    @Then("response headers are:")
    public void responseHeadersAre(DataTable dataTable) {
        List<Map<String, String>> rows = dataTable.asMaps(String.class, String.class);
        for (Map<String, String> row : rows) {
            String name = row.get("name");
            String value = row.get("value");
            if (value.matches(".*\\*.*")) {
                // Pattern matching
                responseHeaderShouldMatchPattern(name, value);
            } else {
                // Exact match
                responseHeaderShouldBe(name, value);
            }
        }
    }
}
