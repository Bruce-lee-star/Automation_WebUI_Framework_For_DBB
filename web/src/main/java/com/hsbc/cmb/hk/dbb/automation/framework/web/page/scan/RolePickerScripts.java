package com.hsbc.cmb.hk.dbb.automation.framework.web.page.scan;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Browser-injected script constants and resource loader, extracted from RoleElementPicker (T5-1 step 1).
 * Pure data (script strings) + stateless helpers only; no shared mutable state, zero logic risk.
 * Literals migrated verbatim; RoleElementPicker references them via alias delegation, behavior identical.
 */
final class RolePickerScripts {

    private RolePickerScripts() {}
    static final String PANEL_BOOTSTRAP_SCRIPT = RolePickerScripts.PANEL_BOOTSTRAP_SCRIPT;

    static final String MERGE_KEY_SHIM = RolePickerScripts.MERGE_KEY_SHIM;

    static final String START_SCRIPT_A = RolePickerScripts.START_SCRIPT_A;

    static final String START_SCRIPT_B1 = RolePickerScripts.START_SCRIPT_B1;

    static final String START_SCRIPT_B2 = RolePickerScripts.START_SCRIPT_B2;

    static final String START_SCRIPT = RolePickerScripts.START_SCRIPT;

    static final String STOP_SCRIPT = RolePickerScripts.STOP_SCRIPT;

    static final String SHOW_PANEL_SCRIPT = RolePickerScripts.SHOW_PANEL_SCRIPT;

    static final String PANEL_SCRIPT_A = RolePickerScripts.PANEL_SCRIPT_A;

    static final String PANEL_SCRIPT_B = RolePickerScripts.PANEL_SCRIPT_B;

    static final String PANEL_SCRIPT = RolePickerScripts.PANEL_SCRIPT;

    static final String PICK_STATE_READER_JS = RolePickerScripts.PICK_STATE_READER_JS;

    static String concat(String a, String b) {
        return a + b;
    }

    static String loadScript(String fileName) {
        String path = "/scan/js/" + fileName;
        try (InputStream in = RolePickerScripts.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("missing picker script resource: " + path);
            }
            try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buf = new byte[8192];
                int r;
                while ((r = in.read(buf)) != -1) out.write(buf, 0, r);
                return new String(out.toByteArray(), StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            throw new IllegalStateException("failed to read picker script resource: " + path, e);
        }
    }

}