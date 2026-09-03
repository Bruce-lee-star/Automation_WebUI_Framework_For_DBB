package com.hsbc.cmb.hk.dbb.automation.framework.web.page.scan;

import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Characterization tests for the picker-injected script constants in {@link RolePickerScripts}.
 *
 * <p>These constants were extracted from {@code RoleElementPicker} (T5-1 step 1). A previous extraction
 * left them as self-referential stubs (e.g. {@code PANEL_BOOTSTRAP_SCRIPT = RolePickerScripts.PANEL_BOOTSTRAP_SCRIPT;}),
 * which evaluate to {@code null} at class-init and silently broke script injection. This suite pins the
 * real (non-null) values so that regression cannot recur unnoticed.
 *
 * <p>T5-1 step 2 externalized the remaining inline {@code page.evaluate("...")} scripts into constants.
 * Those are pure string relocations, so the dominant risk is a <em>transcription</em> error: a dropped
 * {@code +} or an unbalanced brace would produce syntactically invalid JS that no unit test sees
 * (the picker's browser JS has no headless coverage). The tests below pin that risk by asserting
 * delimiter balance, the arg-passing arrow-function contract, and the {@code JSON.parse} equivalence
 * for arguments that were originally spliced in as object literals.
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

    // ------------------------------------------------------------------
    // T5-1 step 2: guards for the externalized evaluate() scripts
    // ------------------------------------------------------------------

    @Test
    public void everyScriptConstantIsNonNullAndNonBlank() throws Exception {
        List<Field> fields = scriptConstantFields();
        assertTrue("expected the extracted scripts to be numerous, found " + fields.size(), fields.size() >= 30);
        for (Field f : fields) {
            String v = (String) f.get(null);
            assertNotNull(f.getName() + " must not be null (self-referential stub?)", v);
            assertFalse(f.getName() + " must not be blank", v.trim().isEmpty());
        }
    }

    /**
     * The single most valuable guard for the extraction: a dropped {@code +} or a lost brace during the
     * mechanical move produces JS that throws on injection. Balanced delimiters (outside string literals)
     * catch that class of transcription error without needing a browser.
     *
     * <p>External scripts loaded via {@code loadScript(...)} (and their {@code concat} parts) are skipped
     * here: they are read verbatim from .js resources, not produced by the extraction refactor, and the
     * delimiter heuristic cannot model JS regex / template literals. Only inline constants are at risk of a
     * dropped {@code +} or brace.
     */
    // File-loaded scripts (and their concat parts) are read verbatim from .js resources, not produced by
    // the extraction refactor, so the delimiter-balance heuristic (which cannot model JS regex / template
    // literals) must skip them. Inline constants that embed such scripts are skipped for the same reason:
    // START_INJECT_JS and SET_NLS_AND_SESSION_JS both inline START_SCRIPT, which carries regex / template
    // literals. Only the genuinely inline constants are at risk of a dropped '+' or brace.
    private static final java.util.Set<String> EXTERNAL_OR_FRAGMENT = new java.util.HashSet<>(java.util.Arrays.asList(
            "START_SCRIPT_A", "START_SCRIPT_B1", "START_SCRIPT_B2", "START_SCRIPT",
            "STOP_SCRIPT", "SHOW_PANEL_SCRIPT",
            "PANEL_SCRIPT_A", "PANEL_SCRIPT_B", "PANEL_SCRIPT", "SET_NLS_AND_SESSION_JS", "START_INJECT_JS"));

    @Test
    public void everyInlineScriptConstantHasBalancedJsDelimiters() throws Exception {
        for (Field f : scriptConstantFields()) {
            if (EXTERNAL_OR_FRAGMENT.contains(f.getName())) {
                continue;
            }
            String v = (String) f.get(null);
            assertTrue(f.getName() + " has unbalanced JS delimiters"
                            + " (a '+' or brace was likely lost during extraction)",
                    hasBalancedDelimitersOutsideStrings(v));
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
            assertTrue("arg-taking script must be '(a) => ...' for Playwright to bind args, got: "
                    + s.substring(0, Math.min(40, s.length())), s.trim().startsWith("(a) =>"));
        }
    }

    /**
     * These arguments were originally spliced in as JS <em>object literals</em>, not strings. Passing them
     * as args makes them JS strings, so each script must {@code JSON.parse} them back; dropping the parse
     * would leave {@code var s} holding a string and every {@code s.picks} lookup silently undefined.
     */
    @Test
    public void jsonArgumentScriptsParseTheirArguments() {
        assertTrue("APPLY_PICK_STATE_JS must parse stateJson",
                RolePickerScripts.APPLY_PICK_STATE_JS.contains("JSON.parse(a.stateJson)"));
        assertTrue("APPLY_PICK_STATE_JS must parse nlsReverseJson with an empty-object default",
                RolePickerScripts.APPLY_PICK_STATE_JS.contains("JSON.parse(a.nlsReverseJson || '{}')"));
        assertTrue("MERGE_SNAPSHOT_PICKS_JS must parse the snapshot",
                RolePickerScripts.MERGE_SNAPSHOT_PICKS_JS.contains("JSON.parse(a.stateJson)"));
        assertTrue("MERGE_MISSING_PICKS_JS must parse the snapshot",
                RolePickerScripts.MERGE_MISSING_PICKS_JS.contains("JSON.parse(a.stateJson)"));
        assertTrue("MERGE_CLOSED_PAGE_PICKS_JS must parse the closed page state",
                RolePickerScripts.MERGE_CLOSED_PAGE_PICKS_JS.contains("JSON.parse(a.closedState)"));
        assertTrue("MERGE_CLOSE_OP_STEP_JS must parse the closed page state with a default",
                RolePickerScripts.MERGE_CLOSE_OP_STEP_JS.contains("JSON.parse(a.closedState || '{}')"));
        assertTrue("SET_NLS_AND_SESSION_JS must parse nls",
                RolePickerScripts.SET_NLS_AND_SESSION_JS.contains("JSON.parse(a.nls)"));
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
            assertTrue("merge script must inline MERGE_KEY_SHIM and define window.__mergeKey",
                    s.contains("window.__mergeKey"));
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
            assertTrue("merge script must dedupe locator-identity strategies by _sig (__LOCID)",
                    s.contains("__LOCID"));
        }
    }

    /**
     * The two page-name setters differ subtly but importantly: the one used at session start also clears
     * {@code __currentPageInstance}, the one used after navigation must not (it would drop the live
     * instance). Pin the distinction so a future merge of the two cannot silently reset it.
     */
    @Test
    public void setPageNameVariantsKeepTheirDifference() {
        assertTrue("session-start variant must clear __currentPageInstance",
                RolePickerScripts.SET_PAGE_NAME_AND_RESET_INSTANCE_JS.contains("__currentPageInstance = null"));
        assertFalse("navigation variant must NOT touch __currentPageInstance",
                RolePickerScripts.SET_PAGE_NAME_JS.contains("__currentPageInstance"));
        assertTrue("conditional variant must compare before writing",
                RolePickerScripts.SET_PAGE_NAME_IF_CHANGED_JS.contains("window.__rolePageName!==a.pageName"));
    }

    @Test
    public void closeOpStepScriptKeepsItsMarkers() {
        assertTrue(RolePickerScripts.MERGE_CLOSE_OP_STEP_JS.contains("_closeOp"));
        assertTrue(RolePickerScripts.MERGE_CLOSE_OP_STEP_JS.contains("__roleCloseSeq"));
        assertTrue(RolePickerScripts.MERGE_CLOSE_OP_STEP_JS.contains("op:'close'"));
        assertTrue("close marker must be spliced in after the closed page's last element",
                RolePickerScripts.MERGE_CLOSE_OP_STEP_JS.contains("arr.splice(__ins + 1, 0, closeMarker)"));
    }

    /**
     * The post-navigation compaction deliberately uses a <em>stable</em> key: falling back to
     * {@code location} would make the key drift per navigation and duplicate picks on every hop.
     */
    @Test
    public void postNavCompactionUsesStableKeyNotLocation() {
        assertTrue(RolePickerScripts.POST_NAV_COMPACT_AND_RENDER_JS.contains("__stableKey"));
        assertTrue("stable key must fall back to __rolePageName, never to location",
                RolePickerScripts.POST_NAV_COMPACT_AND_RENDER_JS
                        .contains("pp._pageClass || (window.__rolePageName || '')"));
    }

    @Test
    public void argsHelperBuildsOrderedMap() {
        Map<String, Object> m = RolePickerScripts.args("a", 1, "b", "x");
        assertEquals(2, m.size());
        assertEquals(Integer.valueOf(1), m.get("a"));
        assertEquals("x", m.get("b"));
        assertTrue("no args should yield an empty map", RolePickerScripts.args().isEmpty());
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

    /**
     * Counts {@code {} () []} only outside single/double-quoted string literals (honouring backslash
     * escapes), so that delimiters appearing inside JS strings do not produce false failures.
     */
    private static boolean hasBalancedDelimitersOutsideStrings(String js) {
        int curly = 0;
        int paren = 0;
        int square = 0;
        char quote = 0;
        boolean escaped = false;
        for (int i = 0; i < js.length(); i++) {
            char c = js.charAt(i);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (c == '\\') {
                escaped = true;
                continue;
            }
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                }
                continue;
            }
            if (c == '\'' || c == '"') {
                quote = c;
                continue;
            }
            switch (c) {
                case '{': curly++; break;
                case '}': curly--; break;
                case '(': paren++; break;
                case ')': paren--; break;
                case '[': square++; break;
                case ']': square--; break;
                default: break;
            }
            if (curly < 0 || paren < 0 || square < 0) {
                return false;
            }
        }
        return curly == 0 && paren == 0 && square == 0 && quote == 0;
    }
}
