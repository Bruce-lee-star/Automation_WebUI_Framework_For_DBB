package com.hsbc.cmb.hk.dbb.automation.tests.glue;

import com.hsbc.cmb.hk.dbb.automation.tests.steps.HomeSteps;
import io.cucumber.java.en.When;
import net.serenitybdd.annotations.Steps;

public class HomeGlue {

    @Steps
    private HomeSteps homeSteps;

    @When("switch profile to {string} and close reminder")
    public void switchProfileToAndCloseReminder(String profile) {
        homeSteps.switchProfileToAndCloseReminder(profile);
    }
}
