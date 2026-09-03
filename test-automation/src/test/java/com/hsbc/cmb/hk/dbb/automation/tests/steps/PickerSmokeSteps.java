package com.hsbc.cmb.hk.dbb.automation.tests.steps;

import com.hsbc.cmb.hk.dbb.automation.framework.web.page.factory.PageObjectFactory;
import com.hsbc.cmb.hk.dbb.automation.tests.pages.PickerPage;
import net.serenitybdd.annotations.Step;

import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasKey;

/**
 * Serenity steps for the picker browser smoke. Delegated to by {@link
 * com.hsbc.cmb.hk.dbb.automation.tests.glue.PickerSmokeGlue}.
 */
public class PickerSmokeSteps {

    private final PickerPage pickerPage = PageObjectFactory.getPage(PickerPage.class);

    @Step
    public void openScanTestPage() {
        pickerPage.openScanTestPage();
    }

    @Step
    public void injectPanelBootstrap() {
        pickerPage.injectPanelBootstrap();
    }

    @Step
    public void setPickMode(String mode) {
        pickerPage.setPickMode(mode);
    }

    @Step
    public void assertWindowPickMode(String mode) {
        Object actual = pickerPage.getWindowPickMode();
        assertThat("window.__roleMode should be set by the externalised arg script",
                String.valueOf(actual), equalTo(mode));
    }

    @Step
    public void assertPickStateReaderHasKeys(String keys) {
        Object state = pickerPage.readPickState();
        assertThat("pick state reader must return an object (Map)",
                state instanceof Map, equalTo(true));
        @SuppressWarnings("unchecked")
        Map<String, Object> m = (Map<String, Object>) state;
        for (String k : keys.split("\\s*,\\s*")) {
            assertThat("pick state must contain key " + k, m, hasKey(k));
        }
    }
}
