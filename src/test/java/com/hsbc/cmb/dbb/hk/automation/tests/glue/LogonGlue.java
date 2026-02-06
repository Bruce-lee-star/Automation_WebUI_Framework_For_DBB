package com.hsbc.cmb.dbb.hk.automation.tests.glue;

import com.hsbc.cmb.dbb.hk.automation.tests.steps.LoginSteps;
import io.cucumber.java.en.Given;
import net.serenitybdd.annotations.Steps;

public class LogonGlue {
    @Steps
    private LoginSteps loginSteps;

    @Given("logon DBB {string} environment as user {string}")
    public void logonDBBEnvironmentAsUserGlue(String env, String username) {
        loginSteps.logonDBBEnvironmentAsUser(env, username);
    }
}
