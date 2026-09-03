package com.hsbc.cmb.hk.dbb.automation.tests.pages;

import com.hsbc.cmb.hk.dbb.automation.framework.web.page.scan.RolePickerScripts;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.base.impl.SerenityBasePage;

import java.net.URL;

/**
 * Page Object for the local scan-test-all.html, mirroring {@link BaiduPage}.
 * Drives the externalised picker scripts via page.evaluate exactly as
 * RoleElementPicker does, so the smoke runs the real extracted constants.
 */
public class PickerPage extends SerenityBasePage {

    // Resolved from the classpath (src/test/resources) so it works regardless of checkout location,
    // unlike a hardcoded file:// path that can miss the test-automation module directory.
    private static final String SCAN_PAGE = resolveScanPage();

    private static String resolveScanPage() {
        URL u = PickerPage.class.getClassLoader().getResource("scan-test-all.html");
        if (u == null) {
            throw new IllegalStateException("scan-test-all.html not found on the test classpath");
        }
        return u.toString();
    }

    public void openScanTestPage() {
        navigateTo(SCAN_PAGE);
        waitForTimeout(500);
    }

    /** Inject the bootstrap (sets window.__rolePanelForce, window.__mergeKey, ...). */
    public void injectPanelBootstrap() {
        getPage().evaluate(RolePickerScripts.PANEL_BOOTSTRAP_SCRIPT);
    }

    /** Set pick mode via the externalised (a) => {...} arg script + args() map. */
    public void setPickMode(String mode) {
        getPage().evaluate(RolePickerScripts.SET_PICK_MODE_JS, RolePickerScripts.args("mode", mode));
    }

    public Object getWindowPickMode() {
        return getPage().evaluate("window.__roleMode");
    }

    public Object readPickState() {
        return getPage().evaluate(RolePickerScripts.PICK_STATE_READER_JS);
    }
}
