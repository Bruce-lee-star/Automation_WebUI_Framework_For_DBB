package com.hsbc.cmb.hk.dbb.automation.framework.web.page.scan;

import org.junit.Test;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Characterization tests for {@link RolePickerScriptInjector}: the frame/script injection
 * orchestration extracted (verbatim) from {@code RoleElementPicker} in T5-1 step 3 (cluster 2).
 *
 * <p>The injected browser script is a large hand-built JS string; a regression that accidentally
 * truncates/splits it would silently break picking. This pins the shape of the generated script
 * so such corruption is caught immediately.
 */
public class RolePickerScriptInjectorTest {

    @Test
    public void gatedPickerInitScript_gatedModeContainsGateAndStartEntrypoints() {
        String script = RolePickerScriptInjector.gatedPickerInitScript("{}");
        assertNotNull(script);
        assertTrue("script must define the gated start entrypoint", script.contains("window.__roleGatedStart"));
        assertTrue("script must set the session-on flag", script.contains("__rolePickSessionOn"));
        assertTrue("script must register load/pageshow self-heal", script.contains("__roleReenable"));
    }

    @Test
    public void gatedPickerInitScript_forceModeStillDefinesStartEntrypoint() {
        String script = RolePickerScriptInjector.gatedPickerInitScript("{}", true);
        assertNotNull(script);
        assertTrue("force mode must still define the gated start entrypoint", script.contains("window.__roleGatedStart"));
    }
}
