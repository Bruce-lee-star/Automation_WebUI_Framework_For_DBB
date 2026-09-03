package com.hsbc.cmb.hk.dbb.automation.framework.web.page.scan;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Characterization tests for the picker-injected script constants in {@link RolePickerScripts}.
 *
 * <p>These constants were extracted from {@code RoleElementPicker} (T5-1 step 1). A previous extraction
 * left them as self-referential stubs (e.g. {@code PANEL_BOOTSTRAP_SCRIPT = RolePickerScripts.PANEL_BOOTSTRAP_SCRIPT;}),
 * which evaluate to {@code null} at class-init and silently broke script injection. This suite pins the
 * real (non-null) values so that regression cannot recur unnoticed.
 */
public class RolePickerScriptsTest {

    @Test
    public void allConstantsAreNonNull() {
        assertNotNull("PANEL_BOOTSTRAP_SCRIPT must be a real script, not null",
                RolePickerScripts.PANEL_BOOTSTRAP_SCRIPT);
        assertNotNull("MERGE_KEY_SHIM must be a real script, not null",
                RolePickerScripts.MERGE_KEY_SHIM);
        assertNotNull("START_SCRIPT_A must be a real script, not null",
                RolePickerScripts.START_SCRIPT_A);
        assertNotNull("START_SCRIPT_B1 must be a real script, not null",
                RolePickerScripts.START_SCRIPT_B1);
        assertNotNull("START_SCRIPT_B2 must be a real script, not null",
                RolePickerScripts.START_SCRIPT_B2);
        assertNotNull("START_SCRIPT must be a real script, not null",
                RolePickerScripts.START_SCRIPT);
        assertNotNull("STOP_SCRIPT must be a real script, not null",
                RolePickerScripts.STOP_SCRIPT);
        assertNotNull("SHOW_PANEL_SCRIPT must be a real script, not null",
                RolePickerScripts.SHOW_PANEL_SCRIPT);
        assertNotNull("PANEL_SCRIPT_A must be a real script, not null",
                RolePickerScripts.PANEL_SCRIPT_A);
        assertNotNull("PANEL_SCRIPT_B must be a real script, not null",
                RolePickerScripts.PANEL_SCRIPT_B);
        assertNotNull("PANEL_SCRIPT must be a real script, not null",
                RolePickerScripts.PANEL_SCRIPT);
        assertNotNull("PICK_STATE_READER_JS must be a real script, not null",
                RolePickerScripts.PICK_STATE_READER_JS);
    }

    @Test
    public void inlineScriptsContainExpectedMarkers() {
        assertTrue("PANEL_BOOTSTRAP_SCRIPT should reference the role-panel enabled flag",
                RolePickerScripts.PANEL_BOOTSTRAP_SCRIPT.contains("rolePanelEnabled"));
        assertTrue("MERGE_KEY_SHIM should define window.__mergeKey",
                RolePickerScripts.MERGE_KEY_SHIM.contains("__mergeKey"));
        assertTrue("PICK_STATE_READER_JS should normalize pick state via window.__mergeKey",
                RolePickerScripts.PICK_STATE_READER_JS.contains("window.__mergeKey"));
    }

    @Test
    public void startScriptIsComposedOfItsParts() {
        String composed = RolePickerScripts.concat(
                RolePickerScripts.concat(RolePickerScripts.START_SCRIPT_A, RolePickerScripts.START_SCRIPT_B1),
                RolePickerScripts.START_SCRIPT_B2);
        assertEquals(composed, RolePickerScripts.START_SCRIPT);
    }
}
