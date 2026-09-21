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
 * (see {@code tools/validate_picker_js.js}, wired into the {@code core} module {@code validate} phase,
 * and {@code RolePickerScriptsJsValidationTest} which runs {@code node --check} on every composed constant).
 * <p>The {@code .js} resources are the single source of truth; any edit must go through the resource files,
 * never re-introduce inline string concatenation here. The generator {@code tools/gen_picker_scripts.js}
 * regenerates both the resources and these declarations from the canonical inline source.</p>
 *
 * <p>常量按【依赖顺序】归类：被其他常量引用的基础常量（MERGE_KEY_SHIM / START_SCRIPT 及其部件）一律前置，
 * 保证其在引用者之前声明（Java 禁止编译期常量表达式中引用后声明的字段），纯整理、零行为变更。</p>
 */
public final class RolePickerScripts {

    private RolePickerScripts() {}

    // =====================================================================
    // A. 面板门控 + 合并去重键（最基础，被后续合并脚本广泛引用，必须前置）
    // =====================================================================

    public static final String PANEL_BOOTSTRAP_SCRIPT = loadScript("panel-bootstrap-script.js");
    public static final String MERGE_KEY_SHIM = loadScript("merge-key-shim.js");

    // =====================================================================
    // B. 核心拾取 / 面板脚本资源（.js 文件加载 + 拼接，START_SCRIPT/PANEL_SCRIPT 被 C/D 引用，前置）
    // =====================================================================

    public static final String START_SCRIPT_A = loadScript("picker-core-a.js");

    public static final String START_SCRIPT_B1 = loadScript("picker-core-b1.js");

    public static final String START_SCRIPT_B2 = loadScript("picker-core-b2.js");

    public static final String START_SCRIPT = concat(concat(START_SCRIPT_A, START_SCRIPT_B1), START_SCRIPT_B2);

    public static final String STOP_SCRIPT = loadScript("picker-stop.js");

    public static final String SHOW_PANEL_SCRIPT = loadScript("panel-show.js");

    public static final String PANEL_SCRIPT_A = loadScript("panel-core-a.js");

    public static final String PANEL_SCRIPT_B = loadScript("panel-core-b.js");

    public static final String PANEL_SCRIPT = concat(PANEL_SCRIPT_A, PANEL_SCRIPT_B);

    // =====================================================================
    // C. 会话 / 门控开关（被引用者在先：STOP_SESSION_ON_JS→CLEANUP、REMOVE_PICK_STATE_JS→CLEAR、
    //    SET_PANEL_FORCE_JS→PANEL_FORCE_AND_ENABLE；SET_NLS_AND_SESSION 引用 START_SCRIPT 已在 B）
    // =====================================================================

    /** 开启面板（墓碑门控未置位时强制置 1）。 */
    public static final String ENABLE_PANEL_JS = loadScript("enable-panel-js.js");

    /** 关闭面板（墓碑门控复位为 0）。 */
    public static final String DISABLE_PANEL_JS = loadScript("disable-panel-js.js");

    /** 停止时清除会话开关（阻断门控脚本在后续新文档自启拾取）。 */
    public static final String STOP_SESSION_ON_JS = loadScript("stop-session-on-js.js");

    /** 停止时清除会话开关 + 复位面板控件状态机（wanted/stopped），随后拼接 STOP_SCRIPT。 */
    public static final String STOP_SESSION_CLEANUP_JS = loadScript("stop-session-cleanup-js.js");

    /** 清除持久化拾取态（__rolePickState）。 */
    public static final String REMOVE_PICK_STATE_JS = loadScript("remove-pick-state-js.js");

    /** 清除持久化拾取态 + 生成代码（双 removeItem）。 */
    public static final String CLEAR_PICKER_STATE_JS = loadScript("clear-picker-state-js.js");

    /** 强制面板重建（置 __rolePanelForce）。 */
    public static final String SET_PANEL_FORCE_JS = loadScript("set-panel-force-js.js");

    /** 强制面板重建 + 开启面板（墓碑门控置 1）。 */
    public static final String PANEL_FORCE_AND_ENABLE_JS = loadScript("panel-force-and-enable-js.js");

    /** ensurePickingActive：会话置位 + 注入反向翻译表（nls 经 JSON.parse 复原），门控函数存在则调用否则注入 START_SCRIPT。 */
    public static final String SET_NLS_AND_SESSION_JS = concat(loadScript("set-nls-and-session-js-head.js"), START_SCRIPT, loadScript("set-nls-and-session-js-tail.js"));

    /** 写入自动生成的页面拾取代码（code 经 Playwright 序列化后作为字符串赋值给 window.__pickerCode）。 */
    public static final String SET_PICKER_CODE_JS = loadScript("set-picker-code-js.js");

    /** 写入 NLS 文件清单（nlsFiles 经 Playwright 序列化后赋值给 window.__nlsFiles）。 */
    public static final String SET_NLS_FILES_JS = loadScript("set-nls-files-js.js");

    // =====================================================================
    // D. 生命周期注入（START_INJECT_JS 引用 START_SCRIPT，已在 B；其余独立）
    // =====================================================================

    /** start() 拾取注入脚本：会话置位 + nls 反查表 + 重挂监听（START_SCRIPT / __roleGatedStart）+ 录制根容器。 */
    public static final String START_INJECT_JS = concat(loadScript("start-inject-js-head.js"), START_SCRIPT, loadScript("start-inject-js-tail.js"));

    /**
     * 把拾取会话状态注入目标页面（不依赖 window.opener，兼容 rel="noopener" / 跨域弹窗）。
     * 实参 a: {nlsFiles, nlsReverseJson, stateJson}；后两者为 JSON 字符串，脚本内 JSON.parse 复原。
     */
    public static final String APPLY_PICK_STATE_JS = loadScript("apply-pick-state-js.js");

    /** 移除常驻面板：摘除点击/悬停/按键/焦点/滚动监听，复位 active，清会话开关并写面板墓碑（阻断门控自启）。 */
    public static final String CLOSE_PANEL_JS = loadScript("close-panel-js.js");

    /** 开始整页/区域扫描前清空浏览器侧拾取全局态（与 Java 内存态对齐，使扫描从空开始）。 */
    public static final String RESET_PICKS_JS = loadScript("reset-picks-js.js");

    public static final String CLEAR_PICKS_JS = loadScript("clear-picks-js.js");

    /** 清空浏览器侧进行中 step（__currentStep）；用于 followPage 把 opener 的 step 整体转移到新页后清空源页。 */
    public static final String CLEAR_CURRENT_STEP_JS = loadScript("clear-current-step-js.js");

    /** 标记本次停止已生效（自愈钩子据此不再复活拾取）。 */
    public static final String SET_PICK_STOPPED_JS = loadScript("set-pick-stopped-js.js");

    /** 读取浏览器侧是否已显式停止拾取（__rolePickStopped），供 onFrameNavigated 跳过重激活。 */
    public static final String IS_PICK_STOPPED_JS = loadScript("is-pick-stopped-js.js");

    /** 读取浏览器侧拾取会话开关是否置位（localStorage / window 双判），供 onFrameNavigated 重激活判定。 */
    public static final String IS_SESSION_ON_JS = loadScript("is-session-on-js.js");

    /** 置拾取激活态并立即重渲染面板。 */
    public static final String SET_PICK_ACTIVE_AND_RENDER_JS = loadScript("set-pick-active-and-render-js.js");

    /** 触发面板重渲染（若存在）。 */
    public static final String RENDER_PICKS_JS = loadScript("render-picks-js.js");

    /** 触发 afterFillJump 钩子（若存在）。 */
    public static final String INVOKE_AFTER_FILL_JUMP_JS = loadScript("invoke-after-fill-jump-js.js");

    // =====================================================================
    // E. 面板 / 页面状态控制（页类名、模式、状态栏、代码填充）
    // =====================================================================

    /** 设置拾取模式并刷新面板开关。实参 a: {mode}。 */
    public static final String SET_PICK_MODE_JS = loadScript("set-pick-mode-js.js");

    /** 仅当浏览器侧页类名与期望值不同时才设置并持久化（避免无谓写入）。实参 a: {pageName}。 */
    public static final String SET_PAGE_NAME_IF_CHANGED_JS = loadScript("set-page-name-if-changed-js.js");

    /** 设置当前页类名并持久化，同时清空 __currentPageInstance。实参 a: {pageName}。 */
    public static final String SET_PAGE_NAME_AND_RESET_INSTANCE_JS = loadScript("set-page-name-and-reset-instance-js.js");

    /** 设置当前页类名并持久化（保留 __currentPageInstance）。实参 a: {pageName}。 */
    public static final String SET_PAGE_NAME_JS = loadScript("set-page-name-js.js");

    /** 设置自动步骤计数。实参 a: {n}。 */
    public static final String SET_AUTO_STEP_COUNT_JS = loadScript("set-auto-step-count-js.js");

    /** 更新面板顶部状态文字（第一步：写入消息对象）。实参 a: {msg}。 */
    public static final String SET_STATUS_MSG_JS = loadScript("set-status-msg-js.js");

    /** 更新面板顶部状态文字（第二步：刷新 DOM）。 */
    public static final String UPDATE_STATUS_DOM_JS = loadScript("update-status-dom-js.js");

    /** 把按页生成的页面类/步骤代码写入面板多 Tab 并更新状态。实参 a: {pageByPage, stepByPage, msg}。 */
    public static final String FILL_CODE_JS = loadScript("fill-code-js.js");

    /** 面板是否已挂载且渲染函数就绪。 */
    public static final String HAS_PANEL_JS = loadScript("has-panel-js.js");

    // =====================================================================
    // F. 区域选择（整页/区域扫描、iframe 帧列举）
    // =====================================================================

    /** 在单个 frame 内执行 __roleScanPage(null)，返回新增元素数；未就绪返回 -1（供调用方补注入）。 */
    public static final String SCAN_PAGE_IN_FRAME_JS = loadScript("scan-page-in-frame-js.js");

    public static final String FRAME_SCAN_JS = loadScript("frame-scan-js.js");

    /** 启动区域点选（调用 window.__roleStartRegionSelect），成功返回 true。 */
    public static final String START_REGION_SELECT_JS = loadScript("start-region-select-js.js");

    /** 清理区域选区态（移除蓝色遮罩 / 事件监听）。 */
    public static final String END_REGION_SELECT_JS = loadScript("end-region-select-js.js");

    public static final String READ_REGION_FRAMES_JS = loadScript("read-region-frames-js.js");

    // =====================================================================
    // G. 状态读取 / 诊断快照（纯读取，无跨常量依赖）
    // =====================================================================

    public static final String PICK_STATE_READER_JS = loadScript("pick-state-reader-js.js");

    /** repickNos 同步删除：读取浏览器侧 __rolePicks 的 sigKey 集合。 */
    public static final String READ_PICK_SIGS_JS = loadScript("read-pick-sigs-js.js");

    /** repickNos 回灌诊断：读取浏览器侧 __rolePicks 的 {k, n} 列表。 */
    public static final String READ_PICK_KEYS_JS = loadScript("read-pick-keys-js.js");

    /** 读取浏览器侧已拾取元素数量（诊断用）。 */
    public static final String READ_PICK_COUNT_JS = loadScript("read-pick-count-js.js");

    /** 读取最近一次拾取签名（兜底空串）。 */
    public static final String READ_LAST_PICK_SIG_JS = loadScript("read-last-pick-sig-js.js");

    /** 读取拾取会话状态 JSON 字符串（picks/steps/currentStep/sigs/active），供跨页面（弹窗开合）搬运。 */
    public static final String READ_PICK_STATE_JSON_JS = loadScript("read-pick-state-json-js.js");

    public static final String READ_FRAME_PICKS_JS = loadScript("read-frame-picks-js.js");

    /** 读取某 iframe 的 window.__rolePicks 原始 JSON 字符串（跨源 frame 经 Playwright 协议读取，不受 file:// 跨源限制）。 */
    public static final String READ_FRAME_PICKS_RAW_JS = loadScript("read-frame-picks-raw-js.js");

    /** getPageOpsWithPage：读取浏览器侧 __steps 中带 op 的项并映射为 {pageClass, op}。 */
    public static final String READ_OPS_JS = loadScript("read-ops-js.js");

    /** 读取「页面级操作」step（含 op 字段，如关闭页面），供生成 closeCurrentPage() 等步骤。 */
    public static final String READ_PAGE_OPS_JS = loadScript("read-page-ops-js.js");

    /** 读取元素 step 序列并归一为 {pageClass, picks}（过滤掉含 op 的页面级操作 step）。 */
    public static final String READ_STEPS_WITH_PAGE_JS = loadScript("read-steps-with-page-js.js");

    /** 导航恢复/激活后的运行时诊断快照（会话开关、激活态、三大监听是否注入）。 */
    public static final String NAV_DIAG_JS = loadScript("nav-diag-js.js");

    /** start() 注入后诊断快照（origin / 会话开关 / 激活态 / 三大监听是否真正挂载）。 */
    public static final String START_DIAG_JS = loadScript("start-diag-js.js");

    public static final String DRAIN_PANEL_CMDS_JS = loadScript("drain-panel-cmds-js.js");

    /** 把最近一次拾取标记为 download（下载监听触发时）。 */
    public static final String MARK_LAST_PICK_DOWNLOAD_JS = loadScript("mark-last-pick-download-js.js");

    /** 把最近一次拾取标记为 upload（文件选择框监听触发时）。 */
    public static final String MARK_LAST_PICK_UPLOAD_JS = loadScript("mark-last-pick-upload-js.js");

    // =====================================================================
    // H. 快照合并（均引用前置的 MERGE_KEY_SHIM）
    // =====================================================================

    /** 整页跳转后，把 pagehide 落盘到 localStorage 的拾取态按权威键合并回当前 window（补回 Java 快照未覆盖的最新点击）。 */
    public static final String MERGE_LOCALSTORAGE_PICKS_JS = loadScripts("merge-localstorage-picks-js-head.js", "merge-key-shim.js", "merge-localstorage-picks-js-tail.js");

    /**
     * 导航后兜底（延迟 60ms 等面板 build 完成）：用【稳定键】对 __rolePicks 做一次全局压实
     * （绝不用 location 兜底键，否则元素随跳转成倍累积），再重渲染面板、滚动到底、恢复上次生成的代码。
     */
    public static final String POST_NAV_COMPACT_AND_RENDER_JS = loadScripts("post-nav-compact-and-render-js-head.js", "merge-key-shim.js", "post-nav-compact-and-render-js-tail.js");

    /**
     * SPA / 同 window 跳转时，把 Java 快照的 picks/steps 合并回当前 window（按 __mergeKey 去重，
     * 并在进行中 step 丢失时从快照补回）。实参 a: {stateJson}（JSON 字符串，脚本内 JSON.parse）。
     */
    public static final String MERGE_SNAPSHOT_PICKS_JS = loadScripts("merge-snapshot-picks-js-head.js", "merge-key-shim.js", "merge-snapshot-picks-js-tail.js");

    /** livePicks 场景补合并：仅把快照里有、而当前 window.__rolePicks 没有的元素按签名去重补回。实参 a: {stateJson}。 */
    public static final String MERGE_MISSING_PICKS_JS = loadScripts("merge-missing-picks-js-head.js", "merge-key-shim.js", "merge-missing-picks-js-tail.js");

    /**
     * 关闭弹窗时把该页已抓元素"按签名合并"回父页（而非整盘覆盖）：只并入父页还没有的 pick。
     * 实参 a: {nlsFiles, nlsReverseJson, closedState}。
     */
    public static final String MERGE_CLOSED_PAGE_PICKS_JS = loadScripts("merge-closed-page-picks-js-head.js", "merge-key-shim.js", "merge-closed-page-picks-js-tail.js");

    /**
     * 关闭弹窗时把其"进行中 step"合并回父页当前 step，并登记 _closeOp 关闭标记与 op='close' 页面级操作。
     * 实参 a: {closedState, closedCls}。
     */
    public static final String MERGE_CLOSE_OP_STEP_JS = loadScripts("merge-close-op-step-js-head.js", "merge-key-shim.js", "merge-close-op-step-js-tail.js");

    /**
     * 把 Java 权威拾取内存态回灌浏览器侧 window.__rolePicks（参数化注入，杜绝字符串拼接破坏语法）。
     * 实参 a: [0]=Base64URL(过滤后 picks JSON)，[1]=Base64URL(删除键 JSON，恒 "[]")，[2]=overwriteNos(boolean)。
     * 由 RolePickerPanelSync.syncPanelToBrowser 外置而来，行为零变更。
     */
    public static final String SYNC_PANEL_TO_BROWSER_JS = loadScript("sync-panel-to-browser-js.js");

    // =====================================================================
    // I. waitForFunction 条件
    // =====================================================================

    /** waitForFunction 条件：用户点击结束拾取（__pickDone 置位）。 */
    public static final String WAIT_PICK_DONE_JS = loadScript("wait-pick-done-js.js");

    /** waitForFunction 条件：代码面板已关闭（__codePanelClosed 置位）。 */
    public static final String WAIT_CODE_PANEL_CLOSED_JS = loadScript("wait-code-panel-closed-js.js");

    // =====================================================================
    // 辅助方法
    // =====================================================================

    /**
     * 构造 evaluate 实参（k1, v1, k2, v2 ...），避免每个调用点重复 new Map + 多次 put。
     * 供 `page.evaluate(SCRIPT, args(...))` 使用，使调用点保持单行。
     */
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
