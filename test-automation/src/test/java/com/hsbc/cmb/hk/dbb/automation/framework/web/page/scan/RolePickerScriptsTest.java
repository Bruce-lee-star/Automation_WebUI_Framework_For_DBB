package com.hsbc.cmb.hk.dbb.automation.framework.web.page.scan;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Characterization tests for the picker-injected script constants in {@link RolePickerScripts}.
 *
 * <p>These constants were extracted from {@code RoleElementPicker} (T5-1 step 1). A previous extraction
 * left them as self-referential stubs (e.g. {@code PANEL_BOOTSTRAP_SCRIPT = RolePickerScripts.PANEL_BOOTSTRAP_SCRIPT;}),
 * which evaluate to {@code null} at class-init and silently broke script injection. This suite pins the
 * real (non-null) values so that regression cannot recur unnoticed.
 *
 * <p>CG-P2-N12 externalized every picker script into {@code core/src/main/resources/scan/js/*.js}
 * resources (loaded verbatim via {@link RolePickerScripts#loadScript}); no JS source remains inline in Java.
 * The dominant regression risk is now a <em>syntax</em> error in a resource file — covered at build time by
 * {@code core} module's {@code validate} phase ({@code tools/validate_picker_js.js}, V8 parse) and at test
 * time by {@link RolePickerScriptsJsValidationTest} ({@code node --check} on every composed constant + the
 * gate template). The assertions below pin behavioural contracts (non-null, arg arrow form, JSON.parse,
 * merge-key shim, page-name variant distinction) that must survive the externalization.
 */
public class RolePickerScriptsTest {

    @Test
    public void allConstantsAreNonNull() {
        assertNotNull(
                RolePickerScripts.PANEL_BOOTSTRAP_SCRIPT, "PANEL_BOOTSTRAP_SCRIPT must be a real script, not null");
        assertNotNull(
                RolePickerScripts.MERGE_KEY_SHIM, "MERGE_KEY_SHIM must be a real script, not null");
        assertNotNull(
                RolePickerScripts.START_SCRIPT_A, "START_SCRIPT_A must be a real script, not null");
        assertNotNull(
                RolePickerScripts.START_SCRIPT_B1, "START_SCRIPT_B1 must be a real script, not null");
        assertNotNull(
                RolePickerScripts.START_SCRIPT_B2, "START_SCRIPT_B2 must be a real script, not null");
        assertNotNull(
                RolePickerScripts.START_SCRIPT, "START_SCRIPT must be a real script, not null");
        assertNotNull(
                RolePickerScripts.STOP_SCRIPT, "STOP_SCRIPT must be a real script, not null");
        assertNotNull(
                RolePickerScripts.SHOW_PANEL_SCRIPT, "SHOW_PANEL_SCRIPT must be a real script, not null");
        assertNotNull(
                RolePickerScripts.PANEL_SCRIPT_A, "PANEL_SCRIPT_A must be a real script, not null");
        assertNotNull(
                RolePickerScripts.PANEL_SCRIPT_B, "PANEL_SCRIPT_B must be a real script, not null");
        assertNotNull(
                RolePickerScripts.PANEL_SCRIPT, "PANEL_SCRIPT must be a real script, not null");
        assertNotNull(
                RolePickerScripts.PICK_STATE_READER_JS, "PICK_STATE_READER_JS must be a real script, not null");
    }

    @Test
    public void inlineScriptsContainExpectedMarkers() {
        assertTrue(
                RolePickerScripts.PANEL_BOOTSTRAP_SCRIPT.contains("rolePanelEnabled"), "PANEL_BOOTSTRAP_SCRIPT should reference the role-panel enabled flag");
        assertTrue(
                RolePickerScripts.MERGE_KEY_SHIM.contains("__mergeKey"), "MERGE_KEY_SHIM should define window.__mergeKey");
        assertTrue(
                RolePickerScripts.PICK_STATE_READER_JS.contains("window.__mergeKey"), "PICK_STATE_READER_JS should normalize pick state via window.__mergeKey");
    }

    @Test
    public void startScriptIsComposedOfItsParts() {
        String composed = RolePickerScripts.concat(
                RolePickerScripts.concat(RolePickerScripts.START_SCRIPT_A, RolePickerScripts.START_SCRIPT_B1),
                RolePickerScripts.START_SCRIPT_B2);
        assertEquals(composed, RolePickerScripts.START_SCRIPT);
    }

    // ------------------------------------------------------------------
    // T5-1 step 2: guards for the externalized evaluate() scripts
    // ------------------------------------------------------------------

    @Test
    public void everyScriptConstantIsNonNullAndNonBlank() throws Exception {
        List<Field> fields = scriptConstantFields();
        assertTrue( fields.size() >= 30, "expected the extracted scripts to be numerous, found " + fields.size());
        for (Field f : fields) {
            String v = (String) f.get(null);
            assertNotNull(f.getName() + " must not be null (self-referential stub?)", v);
            assertFalse(v.trim().isEmpty(), f.getName() + " must not be blank");
        }
    }

    /**
     * Playwright only passes {@code arg} to a string expression it recognises as a function. Every
     * arg-taking script must therefore keep the {@code (a) => {...}} arrow form — silently reverting one
     * to {@code (function(){...})()} would make {@code args} be ignored and break injection at runtime.
     */
    @Test
    public void argumentTakingScriptsKeepArrowFunctionForm() {
        String[] arrow = {
                RolePickerScripts.SET_PICK_MODE_JS,
                RolePickerScripts.SET_PAGE_NAME_JS,
                RolePickerScripts.SET_PAGE_NAME_AND_RESET_INSTANCE_JS,
                RolePickerScripts.SET_PAGE_NAME_IF_CHANGED_JS,
                RolePickerScripts.SET_AUTO_STEP_COUNT_JS,
                RolePickerScripts.SET_STATUS_MSG_JS,
                RolePickerScripts.FILL_CODE_JS,
                RolePickerScripts.APPLY_PICK_STATE_JS,
                RolePickerScripts.MERGE_SNAPSHOT_PICKS_JS,
                RolePickerScripts.MERGE_MISSING_PICKS_JS,
                RolePickerScripts.MERGE_CLOSED_PAGE_PICKS_JS,
                RolePickerScripts.MERGE_CLOSE_OP_STEP_JS,
                RolePickerScripts.SET_NLS_AND_SESSION_JS,
                RolePickerScripts.SET_PICKER_CODE_JS,
                RolePickerScripts.SET_NLS_FILES_JS,
                RolePickerScripts.SYNC_PANEL_TO_BROWSER_JS,
        };
        for (String s : arrow) {
            assertTrue( s.trim().startsWith("(a) =>"), "arg-taking script must be '(a) => ...' for Playwright to bind args, got: "
                    + s.substring(0, Math.min(40, s.length())));
        }
    }

    /**
     * These arguments were originally spliced in as JS <em>object literals</em>, not strings. Passing them
     * as args makes them JS strings, so each script must {@code JSON.parse} them back; dropping the parse
     * would leave {@code var s} holding a string and every {@code s.picks} lookup silently undefined.
     */
    @Test
    public void jsonArgumentScriptsParseTheirArguments() {
        assertTrue(
                RolePickerScripts.APPLY_PICK_STATE_JS.contains("JSON.parse(a.stateJson)"), "APPLY_PICK_STATE_JS must parse stateJson");
        assertTrue(
                RolePickerScripts.APPLY_PICK_STATE_JS.contains("JSON.parse(a.nlsReverseJson || '{}')"), "APPLY_PICK_STATE_JS must parse nlsReverseJson with an empty-object default");
        assertTrue(
                RolePickerScripts.MERGE_SNAPSHOT_PICKS_JS.contains("JSON.parse(a.stateJson)"), "MERGE_SNAPSHOT_PICKS_JS must parse the snapshot");
        assertTrue(
                RolePickerScripts.MERGE_MISSING_PICKS_JS.contains("JSON.parse(a.stateJson)"), "MERGE_MISSING_PICKS_JS must parse the snapshot");
        assertTrue(
                RolePickerScripts.MERGE_CLOSED_PAGE_PICKS_JS.contains("JSON.parse(a.closedState)"), "MERGE_CLOSED_PAGE_PICKS_JS must parse the closed page state");
        assertTrue(
                RolePickerScripts.MERGE_CLOSE_OP_STEP_JS.contains("JSON.parse(a.closedState || '{}')"), "MERGE_CLOSE_OP_STEP_JS must parse the closed page state with a default");
        assertTrue(
                RolePickerScripts.SET_NLS_AND_SESSION_JS.contains("JSON.parse(a.nls)"), "SET_NLS_AND_SESSION_JS must parse nls");
    }

    @Test
    public void mergeScriptsInlineTheMergeKeyShim() {
        String[] withShim = {
                RolePickerScripts.MERGE_LOCALSTORAGE_PICKS_JS,
                RolePickerScripts.POST_NAV_COMPACT_AND_RENDER_JS,
                RolePickerScripts.MERGE_SNAPSHOT_PICKS_JS,
                RolePickerScripts.MERGE_MISSING_PICKS_JS,
                RolePickerScripts.MERGE_CLOSED_PAGE_PICKS_JS,
                RolePickerScripts.MERGE_CLOSE_OP_STEP_JS,
        };
        for (String s : withShim) {
            assertTrue(
                    s.contains("window.__mergeKey"), "merge script must inline MERGE_KEY_SHIM and define window.__mergeKey");
        }
    }

    @Test
    public void mergeScriptsUseLocatorIdentityDedup() {
        String[] withLocId = {
                RolePickerScripts.MERGE_LOCALSTORAGE_PICKS_JS,
                RolePickerScripts.MERGE_SNAPSHOT_PICKS_JS,
                RolePickerScripts.MERGE_MISSING_PICKS_JS,
                RolePickerScripts.MERGE_CLOSED_PAGE_PICKS_JS,
        };
        for (String s : withLocId) {
            assertTrue(
                    s.contains("__LOCID"), "merge script must dedupe locator-identity strategies by _sig (__LOCID)");
        }
    }

    /**
     * The two page-name setters differ subtly but importantly: the one used at session start also clears
     * {@code __currentPageInstance}, the one used after navigation must not (it would drop the live
     * instance). Pin the distinction so a future merge of the two cannot silently reset it.
     */
    @Test
    public void setPageNameVariantsKeepTheirDifference() {
        assertTrue(
                RolePickerScripts.SET_PAGE_NAME_AND_RESET_INSTANCE_JS.contains("__currentPageInstance = null"), "session-start variant must clear __currentPageInstance");
        assertFalse(
                RolePickerScripts.SET_PAGE_NAME_JS.contains("__currentPageInstance"), "navigation variant must NOT touch __currentPageInstance");
        assertTrue(
                RolePickerScripts.SET_PAGE_NAME_IF_CHANGED_JS.contains("window.__rolePageName!==a.pageName"), "conditional variant must compare before writing");
    }

    @Test
    public void closeOpStepScriptKeepsItsMarkers() {
        assertTrue(RolePickerScripts.MERGE_CLOSE_OP_STEP_JS.contains("_closeOp"));
        assertTrue(RolePickerScripts.MERGE_CLOSE_OP_STEP_JS.contains("__roleCloseSeq"));
        assertTrue(RolePickerScripts.MERGE_CLOSE_OP_STEP_JS.contains("op:'close'"));
        assertTrue(
                RolePickerScripts.MERGE_CLOSE_OP_STEP_JS.contains("arr.splice(__ins + 1, 0, closeMarker)"), "close marker must be spliced in after the closed page's last element");
    }

    /**
     * The post-navigation compaction deliberately uses a <em>stable</em> key: falling back to
     * {@code location} would make the key drift per navigation and duplicate picks on every hop.
     */
    @Test
    public void postNavCompactionUsesStableKeyNotLocation() {
        assertTrue(RolePickerScripts.POST_NAV_COMPACT_AND_RENDER_JS.contains("__stableKey"));
        assertTrue(
                RolePickerScripts.POST_NAV_COMPACT_AND_RENDER_JS
                        .contains("pp._pageClass || (window.__rolePageName || '')"), "stable key must fall back to __rolePageName, never to location");
    }

    @Test
    public void argsHelperBuildsOrderedMap() {
        Map<String, Object> m = RolePickerScripts.args("a", 1, "b", "x");
        assertEquals(2, m.size());
        assertEquals(Integer.valueOf(1), m.get("a"));
        assertEquals("x", m.get("b"));
        assertTrue( RolePickerScripts.args().isEmpty(), "no args should yield an empty map");
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private static List<Field> scriptConstantFields() {
        List<Field> out = new ArrayList<>();
        for (Field f : RolePickerScripts.class.getDeclaredFields()) {
            if (f.getType() != String.class) {
                continue;
            }
            int m = f.getModifiers();
            if (Modifier.isStatic(m) && Modifier.isFinal(m)) {
                f.setAccessible(true);
                out.add(f);
            }
        }
        return out;
    }
}
