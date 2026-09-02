package com.hsbc.cmb.hk.dbb.automation.tests.glue;

import com.hsbc.cmb.hk.dbb.automation.tests.steps.BaiduSteps;
import io.cucumber.java.en.And;
import io.cucumber.java.en.When;
import net.serenitybdd.annotations.Steps;

public class BaiduGlue {

    @Steps
    private BaiduSteps baiduSteps;

    @When("open the baidu site")
    public void openTheBaiduSite() {
        baiduSteps.openTheBaiduSite();
    }

    @And("search {string} keywords")
    public void searchKeywords(String keywords) {
        baiduSteps.searchKeywords(keywords);
    }

    @And("ctrl-click the first search result")
    public void ctrlClickFirstResult() {
        baiduSteps.ctrlClickFirstResult();
    }

    @And("switch to the new page")
    public void switchToNewPage() {
        baiduSteps.switchToNewPage();
    }

    @And("wait for {int} seconds")
    public void waitForSeconds(int seconds) {
        baiduSteps.waitForSomeTime();
    }
}
