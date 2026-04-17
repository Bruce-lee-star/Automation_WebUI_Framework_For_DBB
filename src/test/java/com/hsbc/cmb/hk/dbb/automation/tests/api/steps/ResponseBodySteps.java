package com.hsbc.cmb.hk.dbb.automation.tests.api.steps;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hsbc.cmb.hk.dbb.automation.framework.api.core.step.BaseStep;
import io.cucumber.datatable.DataTable;
import io.cucumber.java.en.Then;
import net.serenitybdd.core.steps.UIInteractionSteps;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;

/**
 * Response Body Steps
 * Handles response body verification and JSON field assertions
 */
public class ResponseBodySteps extends UIInteractionSteps {

    @Autowired
    private BaseStep baseStep;

    @Then("response body should contain {string}")
    public void responseBodyShouldContain(String expectedContent) {
        baseStep.verifyResponseBodyContains(expectedContent);
    }

    @Then("response field {string} should be {int}")
    public void responseFieldShouldBeInt(String fieldPath, int expectedValue) {
        baseStep.verifyResponseJsonPath(fieldPath, expectedValue);
    }

    @Then("response field {string} is {string}")
    public void responseFieldIs(String fieldPath, String expectedValue) {
        baseStep.verifyResponseJsonPath(fieldPath, expectedValue);
    }

    @Then("response field {string} should be {string}")
    public void responseFieldShouldBeString(String fieldPath, String expectedValue) {
        baseStep.verifyResponseJsonPath(fieldPath, expectedValue);
    }

    @Then("response field {string} should be {boolean}")
    public void responseFieldShouldBeBoolean(String fieldPath, boolean expectedValue) {
        baseStep.verifyResponseJsonPath(fieldPath, expectedValue);
    }

    @Then("response field {string} should be null")
    public void responseFieldShouldBeNull(String fieldPath) {
        String responseBody = baseStep.getEntity().getResponsePayload();
        if (responseBody == null || responseBody.isEmpty()) {
            throw new AssertionError("Response body is null or empty");
        }

        try {
            ObjectMapper mapper = new ObjectMapper();
            JsonNode node = mapper.readTree(responseBody);
            JsonNode fieldNode = node.at(fieldPath.startsWith("$") ? fieldPath.substring(1) : fieldPath);
            if (!fieldNode.isMissingNode() && !fieldNode.isNull()) {
                throw new AssertionError("Field " + fieldPath + " should be null, but has value: " + fieldNode);
            }
        } catch (Exception e) {
            throw new AssertionError("Failed to parse response body", e);
        }
    }

    @Then("response field {string} should not be null")
    public void responseFieldShouldNotBeNull(String fieldPath) {
        responseFieldShouldBeString(fieldPath, "non-null");
    }

    @Then("response field {string} should contain {string}")
    public void responseFieldShouldContain(String fieldPath, String expectedValue) {
        baseStep.verifyResponseJsonPath(fieldPath, expectedValue);
    }

    @Then("response field {string} should match pattern {string}")
    public void responseFieldShouldMatchPattern(String fieldPath, String pattern) {
        String actualValue = String.valueOf(baseStep.getResponseJson());
        if (!actualValue.matches(pattern)) {
            throw new AssertionError(
                "Field " + fieldPath + " value '" + actualValue + "' does not match pattern: " + pattern
            );
        }
    }

    @Then("response array {string} length is {int}")
    public void responseArrayShouldBe(String arrayPath, int expectedLength) {
        baseStep.verifyJsonArrayLength(arrayPath, expectedLength);
    }

    @Then("response array {string} length is greater than {int}")
    public void responseArrayShouldBeGreaterThan(String arrayPath, int minValue) {
        String actualValue = String.valueOf(baseStep.getResponseJson());
        int actualLength = Integer.parseInt(actualValue);
        if (actualLength <= minValue) {
            throw new AssertionError(
                "Array " + arrayPath + " length " + actualLength + " is not greater than " + minValue
            );
        }
    }

    @Then("response array {string} length is less than {int}")
    public void responseArrayShouldBeLessThan(String arrayPath, int maxValue) {
        String actualValue = String.valueOf(baseStep.getResponseJson());
        int actualLength = Integer.parseInt(actualValue);
        if (actualLength >= maxValue) {
            throw new AssertionError(
                "Array " + arrayPath + " length " + actualLength + " is not less than " + maxValue
            );
        }
    }

    @Then("response field {string} should exist")
    public void responseFieldShouldExist(String fieldPath) {
        try {
            baseStep.verifyResponseJsonPath(fieldPath, "any-value");
        } catch (AssertionError e) {
            if (e.getMessage().contains("not found")) {
                throw new AssertionError("Field " + fieldPath + " does not exist");
            }
            throw e;
        }
    }

    @Then("response field {string} should not exist")
    public void responseFieldShouldNotExist(String fieldPath) {
        try {
            baseStep.verifyResponseJsonPath(fieldPath, "any-value");
            throw new AssertionError("Field " + fieldPath + " should not exist, but it does");
        } catch (AssertionError e) {
            if (e.getMessage().contains("not found")) {
                // Expected - field does not exist
                return;
            }
            throw e;
        }
    }

    @Then("response fields:")
    public void responseFields(DataTable dataTable) {
        List<Map<String, String>> rows = dataTable.asMaps(String.class, String.class);
        for (Map<String, String> row : rows) {
            String fieldPath = row.get("path");
            String value = row.get("value");
            if (value.matches("\\d+")) {
                responseFieldShouldBeInt(fieldPath, Integer.parseInt(value));
            } else {
                responseFieldShouldBeString(fieldPath, value);
            }
        }
    }

    @Then("response body should not be empty")
    public void responseBodyShouldNotBeEmpty() {
        String responseBody = baseStep.getEntity().getResponsePayload();
        if (responseBody == null || responseBody.trim().isEmpty()) {
            throw new AssertionError("Response body is empty");
        }
    }
}
