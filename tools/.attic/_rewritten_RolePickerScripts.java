package com.hsbc.cmb.hk.dbb.automation.framework.web.page.scan;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Browser-injected script constants and resource loader, extracted from RoleElementPicker (T5-1 step 1).
 * Restored real values: the original step-1 extraction left self-referential null stubs, dropping the
 * actual scripts. Values copied verbatim from the pre-extraction source. Pure data + stateless helpers.
 *
 * <h3>Externalized JS (CG-P2-N12)</h3>
 * Every browser-side script body lives in a {@code .js} resource under
 * {@code core/src/main/resources/scan/js/} and is loaded verbatim via {@link #loadScript(String)}
 * (or {@link #loadScripts(String...)} / {@link #concat(String...)} for the few scripts that compose parts).
 * No JS <em>source</em> is embedded as a Java string literal anywhere in this class — this keeps the
 * scripts lintable / version-controlled and lets the build fail fast on a syntax error
 * (see {@code tools/validate_picker_js.js}, wired into the {@code core} module {@code validate} phase).
 * <p>The {@code .js} resources are the single source of truth; any edit must go through the resource files,
 * never re-introduce inline string concatenation here.</p>
 */
public final class RolePickerScripts {

    private RolePickerScripts() {}

    // =====================================================================
    // 核心拾取 / 面板脚本资源（.js 文件加载；这些部件供其它脚本组合引用，前置声明）
    // =====================================================================

    public static final String START_SCRIPT = loadScript("picker-core.js");

    public static final String STOP_SCRIPT = loadScript("picker-stop.js");

    public static final String SHOW_PANEL_SCRIPT = loadScript("panel-show.js");

    public static final String PANEL_SCRIPT = loadScript("panel-core.js");

    public static final String PANEL_BOOTSTRAP_SCRIPT = loadScript("panel-bootstrap-script.js");
    public static final String MERGE_KEY_SHIM = loadScript("merge-key-shim.js");
    public static final String DISABLE_PANEL_JS = loadScript("disable-panel-js.js");
    public static final String STOP_SESSION_ON_JS = loadScript("stop-session-on-js.js");
    public static final String STOP_SESSION_CLEANUP_JS = loadScript("stop-session-cleanup-js.js");
    public static final String REMOVE_PICK_STATE_JS = loadScript("remove-pick-state-js.js");
    public static final String CLEAR_PICKER_STATE_JS = loadScript("clear-picker-state-js.js");
    public static final String SET_PANEL_FORCE_JS = loadScript("set-panel-force-js.js");
    public static final String PANEL_FORCE_AND_ENABLE_JS = loadScript("panel-force-and-enable-js.js");
    public static final String SET_NLS_AND_SESSION_JS = concat(loadScript("set-nls-and-session-js-head.js"), START_SCRIPT, loadScript("set-nls-and-session-js-tail.js"));
    public static final String SET_PICKER_CODE_JS = loadScript("set-picker-code-js.js");
    public static final String SET_NLS_FILES_JS = loadScript("set-nls-files-js.js");
    public static final String START_INJECT_JS = concat(loadScript("start-inject-js-head.js"), START_SCRIPT, loadScript("start-inject-js-tail.js"));
    public static final String APPLY_PICK_STATE_JS = loadScript("apply-pick-state-js.js");
    public static final String CLOSE_PANEL_JS = loadScript("close-panel-js.js");
    public static final String RESET_PICKS_JS = loadScript("reset-picks-js.js");
    public static final String CLEAR_PICKS_JS = loadScript("clear-picks-js.js");
    public static final String CLEAR_CURRENT_STEP_JS = loadScript("clear-current-step-js.js");
    public static final String SET_PICK_STOPPED_JS = loadScript("set-pick-stopped-js.js");
    public static final String IS_PICK_STOPPED_JS = loadScript("is-pick-stopped-js.js");
    public static final String IS_SESSION_ON_JS = loadScript("is-session-on-js.js");
    public static final String SET_PICK_ACTIVE_AND_RENDER_JS = loadScript("set-pick-active-and-render-js.js");
    public static final String RENDER_PICKS_JS = loadScript("render-picks-js.js");
    public static final String INVOKE_AFTER_FILL_JUMP_JS = loadScript("invoke-after-fill-jump-js.js");
    public static final String SET_PICK_MODE_JS = loadScript("set-pick-mode-js.js");
    public static final String SET_PAGE_NAME_IF_CHANGED_JS = loadScript("set-page-name-if-changed-js.js");
    public static final String SET_PAGE_NAME_AND_RESET_INSTANCE_JS = loadScript("set-page-name-and-reset-instance-js.js");
    public static final String SET_PAGE_NAME_JS = loadScript("set-page-name-js.js");
    public static final String SET_AUTO_STEP_COUNT_JS = loadScript("set-auto-step-count-js.js");
    public static final String SET_STATUS_MSG_JS = loadScript("set-status-msg-js.js");
    public static final String UPDATE_STATUS_DOM_JS = loadScript("update-status-dom-js.js");
    public static final String FILL_CODE_JS = loadScript("fill-code-js.js");
    public static final String HAS_PANEL_JS = loadScript("has-panel-js.js");
    public static final String SCAN_PAGE_IN_FRAME_JS = loadScript("scan-page-in-frame-js.js");
    public static final String FRAME_SCAN_JS = loadScript("frame-scan-js.js");
    public static final String START_REGION_SELECT_JS = loadScript("start-region-select-js.js");
    public static final String END_REGION_SELECT_JS = loadScript("end-region-select-js.js");
    public static final String READ_REGION_FRAMES_JS = loadScript("read-region-frames-js.js");
    public static final String PICK_STATE_READER_JS = loadScript("pick-state-reader-js.js");
    public static final String READ_PICK_SIGS_JS = loadScript("read-pick-sigs-js.js");
    public static final String READ_PICK_KEYS_JS = loadScript("read-pick-keys-js.js");
    public static final String READ_PICK_COUNT_JS = loadScript("read-pick-count-js.js");
    public static final String READ_LAST_PICK_SIG_JS = loadScript("read-last-pick-sig-js.js");
    public static final String READ_PICK_STATE_JSON_JS = loadScript("read-pick-state-json-js.js");
    public static final String READ_FRAME_PICKS_JS = loadScript("read-frame-picks-js.js");
    public static final String READ_FRAME_PICKS_RAW_JS = loadScript("read-frame-picks-raw-js.js");
    public static final String READ_OPS_JS = loadScript("read-ops-js.js");
    public static final String READ_PAGE_OPS_JS = loadScript("read-page-ops-js.js");
    public static final String READ_STEPS_WITH_PAGE_JS = loadScript("read-steps-with-page-js.js");
    public static final String NAV_DIAG_JS = loadScript("nav-diag-js.js");
    public static final String START_DIAG_JS = loadScript("start-diag-js.js");
    public static final String DRAIN_PANEL_CMDS_JS = loadScript("drain-panel-cmds-js.js");
    public static final String MARK_LAST_PICK_DOWNLOAD_JS = loadScript("mark-last-pick-download-js.js");
    public static final String MARK_LAST_PICK_UPLOAD_JS = loadScript("mark-last-pick-upload-js.js");
    public static final String MERGE_LOCALSTORAGE_PICKS_JS = loadScripts("merge-localstorage-picks-js-head.js", "merge-key-shim.js", "merge-localstorage-picks-js-tail.js");
    public static final String POST_NAV_COMPACT_AND_RENDER_JS = loadScripts("post-nav-compact-and-render-js-head.js", "merge-key-shim.js", "post-nav-compact-and-render-js-tail.js");
    public static final String MERGE_SNAPSHOT_PICKS_JS = loadScripts("merge-snapshot-picks-js-head.js", "merge-key-shim.js", "merge-snapshot-picks-js-tail.js");
    public static final String MERGE_MISSING_PICKS_JS = loadScripts("merge-missing-picks-js-head.js", "merge-key-shim.js", "merge-missing-picks-js-tail.js");
    public static final String MERGE_CLOSED_PAGE_PICKS_JS = loadScripts("merge-closed-page-picks-js-head.js", "merge-key-shim.js", "merge-closed-page-picks-js-tail.js");
    public static final String MERGE_CLOSE_OP_STEP_JS = loadScripts("merge-close-op-step-js-head.js", "merge-key-shim.js", "merge-close-op-step-js-tail.js");
    public static final String SYNC_PANEL_TO_BROWSER_JS = loadScript("sync-panel-to-browser-js.js");
    public static final String WAIT_PICK_DONE_JS = loadScript("wait-pick-done-js.js");
    public static final String WAIT_CODE_PANEL_CLOSED_JS = loadScript("wait-code-panel-closed-js.js");

    // =====================================================================
    // 构造 evaluate 实参（k1, v1, k2, v2 ...），避免每个调用点重复 new Map + 多次 put。
    // 供 `page.evaluate(SCRIPT, args(...))` 使用，使调用点保持单行。
    // =====================================================================

    public static Map<String, Object> args(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return m;
    }

    /** 顺序拼接多个脚本片段（空安全），用于组合 head / 资源部件 / tail。 */
    static String concat(String... parts) {
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p != null) {
                sb.append(p);
            }
        }
        return sb.toString();
    }

    /** 依次加载多个 {@code .js} 资源并拼接（空安全）。 */
    static String loadScripts(String... names) {
        StringBuilder sb = new StringBuilder();
        for (String n : names) {
            sb.append(loadScript(n));
        }
        return sb.toString();
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
