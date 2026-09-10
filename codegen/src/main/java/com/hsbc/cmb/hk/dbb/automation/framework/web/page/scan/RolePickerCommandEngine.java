package com.hsbc.cmb.hk.dbb.automation.framework.web.page.scan;

import com.google.gson.reflect.TypeToken;
import com.microsoft.playwright.Frame;
import com.microsoft.playwright.Page;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import com.hsbc.cmb.hk.dbb.automation.framework.web.page.scan.model.PickMode;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.scan.model.PickerResult;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.scan.model.PickerAction;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.scan.model.PickSnapshot;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.scan.model.StepRec;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.scan.model.PageOp;

import static com.hsbc.cmb.hk.dbb.automation.framework.web.page.scan.RolePickerBridgeRegistry.GSON;
import static com.hsbc.cmb.hk.dbb.automation.framework.web.page.scan.RoleElementPicker.MAP_STRING_OBJECT_TYPE;
import static com.hsbc.cmb.hk.dbb.automation.framework.web.page.scan.RoleElementPicker.asString;
import static com.hsbc.cmb.hk.dbb.automation.framework.web.page.scan.RoleElementPicker.syncPanelToBrowser;
import static com.hsbc.cmb.hk.dbb.automation.framework.web.page.scan.RoleElementPicker.setPickMode;
import static com.hsbc.cmb.hk.dbb.automation.framework.web.page.scan.RoleElementPicker.mergeFramePicksToMain;
import static com.hsbc.cmb.hk.dbb.automation.framework.web.page.scan.RoleElementPicker.readPickStateJson;
import static com.hsbc.cmb.hk.dbb.automation.framework.web.page.scan.RoleElementPicker.safeOrigin;
import static com.hsbc.cmb.hk.dbb.automation.framework.web.page.scan.RoleElementPicker.lastPathSegment;
import static com.hsbc.cmb.hk.dbb.automation.framework.web.page.scan.RoleElementPicker.fillCode;
import static com.hsbc.cmb.hk.dbb.automation.framework.web.page.scan.RoleElementPicker.stop;
import static com.hsbc.cmb.hk.dbb.automation.framework.web.page.scan.RoleElementPicker.start;
import static com.hsbc.cmb.hk.dbb.automation.framework.web.page.scan.RoleElementPicker.pickerEval;
import static com.hsbc.cmb.hk.dbb.automation.framework.web.page.scan.RolePickerSnapshotParser.readPickSnapshot;
import static com.hsbc.cmb.hk.dbb.automation.framework.web.page.scan.RolePickerSnapshotParser.stopAndRead;
import static com.hsbc.cmb.hk.dbb.automation.framework.web.page.scan.RolePickerSnapshotParser.parsePickSnapshot;

/**
 * 鍛戒护寮曟搸绨囷細鎵胯浇 RoleElementPicker 鐨?runPickerCommand锛堥潰鏉挎寚浠ゅ鐞嗕笌浠ｇ爜鐢熸垚缂栨帓锛岀害 640 琛岋級銆? * 鎸?T5-1 浠?RoleElementPicker 鎶界锛涘師 runPickerCommand 鍦?RoleElementPicker 涓粎鐣?1 琛岄棬闈㈠鎵橈紝鍏紑琛屼负瀹屽叏涓嶅彉銆? */
public final class RolePickerCommandEngine {

    private static final Logger log = LoggerFactory.getLogger(RolePickerCommandEngine.class);
    private static PickerResult handleRepickNos(RolePickerContext ctx, Page page, String cmd) {
        LinkedHashMap<String, RoleEntry> javaPickBySig = ctx.javaPickBySig;
        if (cmd != null && cmd.trim().startsWith("{")) {
            try {
                Map<String, Object> jc = GSON.fromJson(cmd, new TypeToken<Map<String, Object>>(){}.getType());
                Object t = jc == null ? null : jc.get("type");
                String type = t == null ? null : t.toString();
                if ("repickNos".equals(type)) {
                    String mk = asString(jc.get("mergeKey"));
                    Object nosObj = jc.get("nos");
                    List<Integer> nos = new ArrayList<>();
                    if (nosObj instanceof List) {
                        for (Object o : (List<?>) nosObj) {
                            if (o instanceof Number) nos.add(((Number) o).intValue());
                        }
                    }
                    if (mk != null && !mk.isEmpty()) {
                        synchronized (javaPickBySig) {
                            for (RoleEntry e : javaPickBySig.values()) {
                                if (mk.equals(e.getSigKey())) {
                                    e.setPickNos(nos);
                                    log.info("[picker] repickNos 同步内存态：sigKey={} → nos={}", mk, nos);
                                    break;
                                }
                            }
                        }
                    }
                    // 【关键修复】同步删除浏览器侧已不存在的元素
                    // 用户删除元素后，浏览器侧 __rolePicks 已移除该元素，但 Java 侧 javaPickBySig 仍保留旧记录。
                    // 当用户重新拾取该元素时，Java 误认为是"已存在元素的新序号"，执行合并逻辑导致序号错误。
                    // 解决方案：读取浏览器侧当前 __rolePicks 的 sigKey 集合，删除 Java 侧不存在的元素。
                    try {
                        @SuppressWarnings("unchecked")
                        List<?> browserPicks = (List<?>) pickerEval(page, RolePickerScripts.READ_PICK_SIGS_JS);
                        java.util.Set<String> browserSigs = new java.util.HashSet<>();
                        if (browserPicks != null) {
                            for (Object o : browserPicks) {
                                if (o != null) browserSigs.add(String.valueOf(o));
                            }
                        }
                        synchronized (javaPickBySig) {
                            java.util.Iterator<java.util.Map.Entry<String, RoleEntry>> it = javaPickBySig.entrySet().iterator();
                            while (it.hasNext()) {
                                java.util.Map.Entry<String, RoleEntry> entry = it.next();
                                RoleEntry e = entry.getValue();
                                if (e == null) continue;
                                String sigKey = e.getSigKey();
                                // 如果 Java 侧元素的 sigKey 不在浏览器侧 __rolePicks 中，则删除该元素
                                if (sigKey != null && !browserSigs.contains(sigKey)) {
                                    log.info("[picker] repickNos 同步删除：sigKey={}（浏览器侧已不存在）", sigKey);
                                    it.remove();
                                }
                            }
                        }
                    } catch (Exception syncEx) {
                        log.warn("[picker] repickNos 同步删除失败：{}", syncEx.getMessage());
                    }
                    // 重编号后把最新内存态回灌浏览器面板，保证面板/快照/Java 三侧序号一致。
                    // 强制刷新 ETag：repickNos 只改序号、元素身份未变，若不清除 LAST_SYNC_SIG，
                    // syncPanelToBrowser 的签名短路会跳过回灌，导致面板序号不刷新（被旧值覆盖）。
                    try { RolePickerPanelSync.LAST_SYNC_SIG.remove(page); } catch (Exception ignore) {}
                    try { if (!page.isClosed()) syncPanelToBrowser(page, null, javaPickBySig, true); } catch (Exception ignore) {}
                    // 【diag-repick】sync 后回读浏览器侧 __rolePicks 的实际 _pickNos，确认回灌生效（而非旧值残留）。
                    try {
                        @SuppressWarnings("unchecked")
                        List<?> rp = (List<?>) pickerEval(page, RolePickerScripts.READ_PICK_KEYS_JS);
                        log.info("[picker][diag-repick] 回灌后浏览器侧 __rolePicks: {}", rp);
                    } catch (Exception ignoreR) {}
                    return new PickerResult(PickerAction.CONTINUE, null, null,
                            "已删除拾取序号并重排（" + (nos == null ? 0 : nos.size()) + " 个序号）");
                }
            } catch (Exception ex) {
                log.warn("[picker] repickNos 命令解析失败：{}", ex.getMessage());
            }
        }
        return null;
    }

    private static PickerResult cmdStart(RolePickerContext ctx, Page page) {
        AtomicBoolean active = ctx.active;
        String[] nlsFiles = ctx.nlsFiles;
        ConcurrentHashMap<Page, String> pageNames = ctx.pageNames;
        LinkedHashMap<String, RoleEntry> javaPickBySig = ctx.javaPickBySig;
        // 多实例：会话级"开始"作用于所有已打开页面，使各页面板同步显示 ⏹ 停止
        // （active.get() 是会话权威开关，followPage / onFrameNavigated 据此决定是否重启监听）。
        active.set(true);
        // 【关键修复】清空 Java 侧内存态中元素的序号（保留元素本身），使第二轮拾取序号从 1 开始
        // 用户需求：重新拾取时保留已在页面元素列表中的元素，但序号从 1 重新开始
        // 浏览器侧 start 脚本也会同步清空 __rolePicks 中每个元素的 _pickNos，保持 Java/浏览器状态一致
        for (RoleEntry e : javaPickBySig.values()) {
            if (e != null) {
                e.setPickNos(new java.util.ArrayList<>());  // 清空序号为 []（保留元素，语义与面板删除一致）
            }
        }
        // 进入手动拾取模式（互斥：此时整页/区域扫描按钮禁用，点击页面只拾取被点元素）。
        setPickMode(pageNames.keySet().iterator().next(), PickMode.MANUAL, pageNames);
        // 反向查表只构建一次（避免对每个被跟踪页面重复读 nls 文件），减少点击"开始"的延迟。
        String startNls = RolePickerNlsCache.buildNlsReverseJson(Arrays.asList(nlsFiles));
        for (Page p : pageNames.keySet()) {
            if (!p.isClosed()) { log.info("[picker][start] 对页面 {} 调用 start", p.url()); start(p, startNls); }
        }
        // 注意：开始拾取不做自动避开导航——用户有时也需要拾取 leftmenu/topbar 等全局区域。
        // start(page, nls) 的 root 为 null（整页），点击拾取即整页可点；仅当用户主动用"区域扫描"
        // 点选了某块业务区后，window.__rolePickRoot 才被限定到该容器（区域扫描专属语义）。
        return new PickerResult(PickerAction.CONTINUE, null, null,
                "RoleElement Picker：点击元素拾取 role/name，按 ESC 结束");
    }

    private static PickerResult cmdAbort(RolePickerContext ctx, Page page) {
        AtomicBoolean active = ctx.active;
        active.set(false);
        stop(page);
        return new PickerResult(PickerAction.ABORT, null, null, null);
    }

    private static PickerResult cmdDone(RolePickerContext ctx, Page page) {
        // 面板『✕ 关闭 / ⏹ 停止』按钮发出的明确关闭命令：停止拾取并结束 openPanel 阻塞循环
        // （主循环对 DONE 抛 PickerAbortedException，中止调用方后续自动登录等代码）。
        stop(page);
        return new PickerResult(PickerAction.DONE, null, null, null);
    }

    private static PickerResult cmdUnknown(RolePickerContext ctx, Page page) {
        // 【关键修复】未知/未识别命令（含 JSON 命令解析异常后落空、repickNos 等对象命令
        // 在极少数竞态下未命中 type）绝不能再 stop + 返回 DONE，否则会误关面板
        // （表现为"点拾取序号/加号面板直接关闭"）。未知命令一律忽略、继续会话。
        return new PickerResult(PickerAction.CONTINUE, null, null, null);
    }

    private static PickerResult cmdScan(RolePickerContext ctx, Page page) {
        AtomicBoolean active = ctx.active;
        String[] nlsFiles = ctx.nlsFiles;
        String packageName = ctx.packageName;
        String pageClassName = ctx.pageClassName;
        ConcurrentHashMap<Page, String> pageNames = ctx.pageNames;
        LinkedHashMap<String, RoleEntry> javaPickBySig = ctx.javaPickBySig;
        // 整页 role 树扫描：先复用 start 注入拾取库（定义 __roleScanPage/__recordPick），
        // 再对命令来源页运行 window.__roleScanPage()——把整页所有"带可访问名的语义角色元素"
        // 经 __recordPick 记录，与点击拾取同一链路（去重 / 面板渲染 / __roleOnPick 回传 javaPickBySig）。
        // 用户随后点 ⏹ 停止即从 javaPickBySig 生成代码（无需为扫描单独实现生成逻辑）。
        active.set(true);
        // 进入整页扫描模式（互斥：扫描期间禁用开始/区域扫描按钮）。
        setPickMode(pageNames.keySet().iterator().next(), PickMode.SCAN_PAGE, pageNames);
        String scanNls = RolePickerNlsCache.buildNlsReverseJson(Arrays.asList(nlsFiles));
        // 【关键修复"全页扫描后旧元素仍持有旧序号（如 i18n:user_name 残留 [2,12]）"】
        // start() 只清空浏览器侧 __rolePicks 的 _pickNos，但 Java 侧 javaPickBySig 仍保留旧序号。
        // 扫描时 __recordPick 因 __scanning=true 不分配序号（_pickNos=null/空），
        // 回传 Java 后 mergePickIntoMap 用 pickMoreComplete 合并：incoming=null, existing=[2,12] → 保留 [2,12]。
        // 结果：全页扫描后旧元素仍持有旧序号，点加号时新序号从旧最大值+1 开始而非从 1 开始。
        // 修复：在全页扫描前清空 Java 内存态中所有元素的序号，使扫描结果从空开始。
        // 注意：只清空序号（保留元素本身），与 start 命令处理器的行为一致。
        for (RoleEntry e : javaPickBySig.values()) {
            if (e != null) e.setPickNos(new java.util.ArrayList<>());
        }
        // 【关键修复"重新扫描后点加号序号不重置"】
        // 旧实现：start() 的 JS 注入只清空 _pickNos 但保留 __rolePicks 元素列表，
        // 并重建 __rolePickSigs 去重表。当 __roleScanPage 运行时，__recordPick 在 dup 检查中
        // 发现所有元素都已存在（__rolePickSigs 命中），只新增真正"新"的元素。
        // 结果：第二次全页扫描只新增 1 个元素（而非全部 20+），重新扫描形同虚设。
        // 修复：在 start() 之前先清空浏览器侧 __rolePicks 和 __rolePickSigs，使扫描
        // 能重新发现所有页面元素。start() 的 JS 注入仍会重置计数器（__rolePickSeq=0 等），
        // 确保扫描后点加号新序号从 1 开始。
        if (!page.isClosed()) {
            try {
                pickerEval(page, RolePickerScripts.RESET_PICKS_JS);
            } catch (Exception ignored) {}
            start(page, scanNls);
        }
        int added = -1;
        try {
            // 【关键修复"整页扫描未穿透 iframe"】
            // 旧实现只对主页面 frame 执行 __roleScanPage()，其内部虽有"遍历 els 遇到 iframe 元素
            // 递归进入 contentWindow 扫描"的逻辑，但 file:// 下主 frame 的 JS 访问 iframe 的
            // contentWindow.document 会抛跨源 SecurityError（origin=null），递归被 catch 吞掉、
            // iframe 内元素一个都扫不到（实测 frameOne/frameTwo 的 __rolePicks 恒为 0）。
            // 修复：由 Java 侧对 page.frames() 的【每个 frame】分别执行其 own __roleScanPage(null)
            // （Playwright 的 frame.evaluate 走协议层，不受浏览器同源策略限制，能访问任意层 iframe
            // 的 __roleScanPage），并在该 frame 上下文扫描、回传，与手动点选同链路。
            added = 0;
            for (com.microsoft.playwright.Frame f : page.frames()) {
                if (f == null) continue;
                try {
                    Object r = pickerEval(f, RolePickerScripts.SCAN_PAGE_IN_FRAME_JS);
                    // 防御性兜底：跨源/动态 iframe 若因注入竞态漏注入（__roleScanPage 未定义，返回 -1），
                    // 此处先强制补注入一次再扫描，确保任意层 iframe（含跨源）都能被整页扫描穿透。
                    if (r instanceof Number && ((Number) r).intValue() < 0) {
                        try {
                            RolePickerScriptInjector.frameInjectOnce(f, scanNls);
                            r = pickerEval(f, RolePickerScripts.FRAME_SCAN_JS);
                        } catch (Exception reInjEx) {
                            if (log.isDebugEnabled()) log.debug("[picker][scan] iframe 补注入失败（url={}）：{}", f.url(), reInjEx.getMessage());
                        }
                    }
                    if (r instanceof Number) {
                        int n = ((Number) r).intValue();
                        if (n > 0) added += n;
                    }
                } catch (Exception fe) {
                    log.warn("[picker][scan] frame 扫描失败（url={}）：{}", f.url(), fe.getMessage());
                }
            }
            // 主框架扫描已在上面 frame 循环中覆盖（page.mainFrame() 也在 page.frames() 内）。
            // 跨 frame 扫描结果经各自 console 兜底回传 Java；此处再触发一次 Java 内存态同步/快照
            // 合并，确保 iframe 内回传的 pick 也能进入权威内存态。
        } catch (Exception e) {
            log.warn("[picker][scan] 整页扫描执行失败：{}", e.getMessage());
        }
        log.info("[picker][scan] 整页扫描完成：新增 {} 个语义角色元素", added);
        // 【关键修复"页面元素只有主框架元素"】
        // 扫描出的 iframe 元素 push 进各自 iframe 的 __rolePicks（面板渲染的是主框架 __rolePicks，
        // 看不到 iframe 的；postMessage 上送顶层又受 __rolePanelUI 门禁/监听时机影响不可靠）。
        // 修复：由 Java 侧遍历 page.frames() 读回各 iframe 的 __rolePicks（Playwright 协议访问
        // 不受 file:// 跨源限制），显式合并进【主框架】window.__rolePicks 并触发渲染——
        // 使扫描后主框架 __rolePicks 立即包含全部 iframe 元素，面板"页面元素"Tab 与即时生成的
        // 页面类都显示完整数量（Java 内存态 40 个 → 面板也 40 个，而非仅主框架 32 个）。
        try {
            mergeFramePicksToMain(page, javaPickBySig);
            // 再把权威内存态强制回灌主框架（pageClasses=null 同步全部），双保险。
            // 【关键修复"重新扫描后旧序号残留"】overwriteNos=true 强制用 Java 侧 pickNos（已被上方
            // setPickNos(null) 清空）覆盖浏览器侧，绕过 __oldNos 保护逻辑（该逻辑会保留浏览器侧
            // 旧 pickNos，如果 start() 注入失败则旧序号不被清除，导致"重新扫描后点加号序号不重置"）。
            if (!page.isClosed() && !javaPickBySig.isEmpty()) {
                syncPanelToBrowser(page, null, javaPickBySig, true);
            }
        } catch (Exception syncE) {
            log.warn("[picker][scan] 扫描后同步 iframe 元素到面板失败：{}", syncE.getMessage());
        }
        // 扫描完成后【立即生成页面类代码】，无需等到点 ⏹ 停止：直接同步读取浏览器侧
        // window.__rolePicks（readPickSnapshot 走 page.evaluate，比依赖异步的 __roleOnPick 回传更可靠），
        // 按 pageClass 分组生成页面类并填入"页面类"Tab，同时自动切到该 Tab 让用户即时看到。
        // 此后用户仍可继续勾选元素「封装为步骤」、或点 ⏹ 停止重新生成（含步骤代码）。
        try {
            PickSnapshot snap = readPickSnapshot(page);
            if (snap != null && !snap.entries.isEmpty()) {
                LinkedHashMap<String, String> codePage = RolePickerCodeAssembler.buildPageClassCode(snap.entries, packageName, pageClassName, nlsFiles);
                if (codePage != null && !codePage.isEmpty()) {
                    // 扫描完成自动回 IDLE：面板按钮复位为"▶ 开始拾取"，页面点击不再拾取。
                    setPickMode(pageNames.keySet().iterator().next(), PickMode.IDLE, pageNames);
                    return new PickerResult(PickerAction.CONTINUE, codePage, null,
                            "整页扫描完成，已生成页面类（" + snap.entries.size() + " 个字段，" + added
                                    + " 个新增），可继续勾选元素封装步骤，或点 ⏹ 停止生成步骤代码");
                }
            }
        } catch (Exception e) {
            log.warn("[picker][scan] 扫描后即时生成页面类失败：{}", e.getMessage());
        }
        // 扫描完成（无论是否生成页面类）自动回 IDLE。
        setPickMode(pageNames.keySet().iterator().next(), PickMode.IDLE, pageNames);
        return new PickerResult(PickerAction.CONTINUE, null, null,
                added >= 0
                        ? ("整页扫描：新增 " + added + " 个语义角色元素，点 ⏹ 停止生成代码")
                        : "整页扫描失败（拾取库未就绪，请重试）");
    }

    private static PickerResult cmdScanRegion(RolePickerContext ctx, Page page) {
        AtomicBoolean active = ctx.active;
        String[] nlsFiles = ctx.nlsFiles;
        ConcurrentHashMap<Page, String> pageNames = ctx.pageNames;
        // 区域扫描：点按钮后进入「点选区域」态（__roleStartRegionSelect）：用户点击业务区域内的任意位置，
        // 框架收敛到该业务容器并只扫描这块（避开 leftmenu/topbar 等）；若用户想整页，可在区域扫描后
        // 再点「扫描整页」按钮。点选是浏览器侧异步交互，Java 仅触发并返回提示，结果由浏览器侧回传。
        active.set(true);
        // 进入区域扫描模式（互斥：扫描期间禁用开始/整页扫描按钮）。
        setPickMode(pageNames.keySet().iterator().next(), PickMode.SCAN_REGION, pageNames);
        String regionNls = RolePickerNlsCache.buildNlsReverseJson(Arrays.asList(nlsFiles));
        if (!page.isClosed()) start(page, regionNls);
        // 【修复"整页扫描 → 停止拾取 → 区域选择，整页扫描元素被清空"】
        // 原实现为让"区域扫描结果 = 纯本次选中区域元素"，进入区域点选态前清空了三处：
        //   ① 所有 frame 的 __rolePicks/__rolePickSigs
        //   ② Java 权威内存态 javaPickBySig
        //   ③ RolePickerSessionState.STATE_DELETED 已删集合
        // 这导致：先整页扫描、再区域选择时，整页扫描的元素被整体清空，无法与区域扫描结果叠加。
        // 现按"整页扫描 + 区域扫描互补补充"的期望移除全部清空：区域扫描 __roleScanPage 本身是
        // 【追加】语义（__scanAdded 记录本次新增并 push 进 __rolePicks），保留已有拾取集即可实现
        // 叠加。同时保留 __rolePickSigs（去重）防重复、保留 RolePickerSessionState.STATE_DELETED（已删屏蔽）防已删元素复活。
        try {
            pickerEval(page, RolePickerScripts.START_REGION_SELECT_JS);
        } catch (Exception e) {
            log.warn("[picker][scanRegion] 启动区域点选失败：{}", e.getMessage());
            return new PickerResult(PickerAction.CONTINUE, null, null,
                    "区域扫描启动失败（拾取库未就绪，请重试）");
        }
        return new PickerResult(PickerAction.CONTINUE, null, null,
                "已开启区域选择：鼠标移入业务区域即聚焦，点击区域即扫描并展示该区域内元素；按 Esc 结束选区");
    }

    private static PickerResult cmdRegionScanned(RolePickerContext ctx, Page page) {
        String[] nlsFiles = ctx.nlsFiles;
        String packageName = ctx.packageName;
        String pageClassName = ctx.pageClassName;
        String stepClassName = ctx.stepClassName;
        ConcurrentHashMap<Page, String> pageNames = ctx.pageNames;
        LinkedHashMap<String, RoleEntry> javaPickBySig = ctx.javaPickBySig;
        // 区域扫描点击后由浏览器侧异步通知（window.__rolePickerCmd('regionScanned')）：此时用户点选的
        // 业务区域元素已同步进入 window.__rolePicks，这里与"整页扫描"一样读取快照并生成页面类，
        // 填充"页面类"Tab 并切到该 Tab，使用户即时看到——否则区域扫描只会收集元素、却从不会生成页面类。
        // 每次点选区域都会重算，便于在多个区域间累加后逐步更新页面类。
        // 【关键修复"区域扫描未穿透 iframe"】
        // 区域扫描的 iframe 内元素（postMessage 穿透）进入【各自 iframe】的 __rolePicks，而
        // readPickSnapshot 读的是【主框架】__rolePicks —— 不合并的话页面类/面板只有主框架区域元素，
        // iframe 内元素一个都看不到（表象：区域扫描"穿透不了 iframe"）。
        // 修复：与整页扫描一致，先由 Java 侧把各 iframe 的 __rolePicks 合并进主框架再读快照。
        //
        // 【关键修复"区域扫描跨源 iframe 穿透失败（file:// 下 postMessage 不投递）"】
        // 区域扫描穿透 iframe 的浏览器侧链路是主框架 __roleScanPage 遍历到 iframe 元素时对
        // contentWindow 发 postMessage({__roleScanRequest:true}) 通知 iframe 自扫。但 Chromium 对
        // file:// 不同文件的 iframe 视为跨源，未加 --allow-file-access-from-files 时该 postMessage
        // 无法触发 iframe 内 message 监听（实测 frameOne/frameTwo.__rolePicks 恒为 0），iframe 内
        // 语义元素一个都进不了 iframe.__rolePicks → 合并/面板/页面类全缺 → 表象"区域扫描穿透不了
        // frame"。整页扫描早已改用【Java 侧遍历 page.frames() 逐帧执行 __roleScanPage(null)】
        // （Playwright 协议访问不受浏览器同源策略限制），区域扫描也应走这条可靠路径。
        // 此处：先读回主框架"选中区域根内的 iframe"（按宿主元素 src/name 标记），再对 page.frames()
        // 中匹配这些标记的 frame 执行其 own __roleScanPage(null)，最后合并进主框架。既保证穿透，
        // 又避免把"未选中区域里的 iframe"整块扫出来（回归"区域选非 frame 区域却把 frame 扫出来"）。
        try {
            // 读回主框架选中区域根内包含的 iframe 标识（src 文件路径 + name/id）
            java.util.Set<String> regionFrameUrls = new java.util.HashSet<>();
            java.util.Set<String> regionFrameNames = new java.util.HashSet<>();
            try {
                Object marks = pickerEval(page, RolePickerScripts.READ_REGION_FRAMES_JS);
                if (marks instanceof java.util.Map) {
                    Object u = ((java.util.Map<?, ?>) marks).get("urls");
                    if (u instanceof java.util.List) {
                        for (Object o : (java.util.List<?>) u) if (o != null) regionFrameUrls.add(o.toString());
                    }
                    Object n = ((java.util.Map<?, ?>) marks).get("names");
                    if (n instanceof java.util.List) {
                        for (Object o : (java.util.List<?>) n) if (o != null) regionFrameNames.add(o.toString());
                    }
                }
            } catch (Exception ig) {}
            // 对"选中根内 iframe"逐个执行其 own __roleScanPage(null)（Playwright 协议穿透跨源）。
            // 【关键修复"嵌套 iframe 没扫出来"】
            // 选中根内 iframe 的标记（regionFrameUrls/Names）来自【主框架】querySelectorAll('iframe,frame')，
            // 只能拿到直接 iframe（如 frameOne），拿不到嵌套在最深层 iframe 里的 frame（如 frameTwo——
            // 其宿主 <iframe name="frameTwo"> 在 frameOne 的 document 里，不在主框架 DOM）。若只按
            // 直接匹配扫描，frameTwo 永远不被执行 __roleScanPage，且 frameOne 自扫时对内部 frameTwo
            // 的 postMessage 在 file:// 跨源下不投递 → 嵌套 iframe 一个都扫不到。
            // 修复：判定"某 frame 在选中区域内"改为「它自身或其【任意祖先 frame】宿主匹配选中区域的
            // iframe 标记」——即一旦 frameOne 在区域内，其内部所有嵌套 iframe（frameTwo 等）都应被
            // 一并扫描，保证嵌套链路完整穿透。
            if (!page.isClosed()) {
                final java.util.Set<String> rUrls = regionFrameUrls;
                final java.util.Set<String> rNames = regionFrameNames;
                for (com.microsoft.playwright.Frame f : page.frames()) {
                    if (f == null || f.equals(page.mainFrame())) continue;
                    // 沿父 frame 链向上判断是否落在选中区域内（自身或任意祖先命中即算在区域内）
                    boolean inRegion = false;
                    com.microsoft.playwright.Frame cur = f;
                    while (cur != null && !inRegion) {
                        String cUrl = null, cName = null;
                        try { cUrl = cur.url(); } catch (Exception ignore) {}
                        try { cName = cur.name(); } catch (Exception ignore) {}
                        if (rNames.contains(cName)) inRegion = true;
                        if (!inRegion && cUrl != null) {
                            for (String mark : rUrls) {
                                if (mark != null && mark.length() > 0 && cUrl.contains(lastPathSegment(mark))) {
                                    inRegion = true; break;
                                }
                            }
                            if (!inRegion) {
                                for (String nm : rNames) {
                                    if (nm != null && nm.length() > 0 && cUrl.contains(nm)) { inRegion = true; break; }
                                }
                            }
                        }
                        try { cur = cur.parentFrame(); } catch (Exception ignore) { cur = null; }
                    }
                    if (!inRegion) continue;
                    try {
                        Object rf = pickerEval(f, RolePickerScripts.SCAN_PAGE_IN_FRAME_JS);
                        // 防御性兜底：跨源/动态 iframe 若因注入竞态漏注入（__roleScanPage 未定义），
                        // 先强制补注入一次再扫描，确保区域内任意层 iframe（含跨源）都能被区域扫描穿透。
                        if (rf instanceof Number && ((Number) rf).intValue() < 0) {
                            try {
                                RolePickerScriptInjector.frameInjectOnce(f, RolePickerNlsCache.buildNlsReverseJson(Arrays.asList(nlsFiles)));
                                pickerEval(f, RolePickerScripts.SCAN_PAGE_IN_FRAME_JS);
                            } catch (Exception reInjEx) {
                                String fUrl = null; try { fUrl = f.url(); } catch (Exception ignore) {}
                                log.warn("[picker][regionScanned] 区域 iframe 补注入失败（url={}）：{}", fUrl, reInjEx.getMessage());
                            }
                        }
                    } catch (Exception fe) {
                        String fUrl = null; try { fUrl = f.url(); } catch (Exception ignore) {}
                        log.warn("[picker][regionScanned] 区域 iframe 扫描失败（url={}）：{}", fUrl, fe.getMessage());
                    }
                }
            }
            // 再把各 iframe 的 __rolePicks 合并进主框架（含本次 Java 侧补扫的 iframe 元素）
            mergeFramePicksToMain(page, javaPickBySig);
        } catch (Exception mE) {
            log.warn("[picker][regionScanned] 合并 iframe 元素到主框架失败：{}", mE.getMessage());
        }
        try {
            PickSnapshot snap = readPickSnapshot(page);
            if (snap != null && !snap.entries.isEmpty()) {
                LinkedHashMap<String, String> codePage = RolePickerCodeAssembler.buildPageClassCode(snap.entries, packageName, pageClassName, nlsFiles);
                // 【关键修复"区域扫描后 step 代码被清空"】
                // 区域扫描每次点选都会触发 regionScanned，本分支此前返回 codeStep=null，
                // 主循环 fillCode 会把 step Tab 用空 stepByPage 覆盖 → 用户已封装好的步骤代码被清空
                // （日志：先点「封装为步骤」生成 step，再点击其他区域/html/div 触发 regionScanned，
                //  步骤代码区就空了）。
                // 修复：regionScanned 也按当前 snap（含浏览器侧 __steps 已封装的 step）生成 step 代码，
                // 使每次区域点选刷新页面类的同时【保留并回填】已封装的步骤，不覆盖为空。
                LinkedHashMap<String, String> codeStep = RolePickerCodeAssembler.buildStepCode(snap, packageName, stepClassName);
                if (codePage != null && !codePage.isEmpty()) {
                    // 区域扫描完成：先清理浏览器侧选区态（移除蓝色遮罩、事件监听等），
                    // 再回 IDLE 使面板按钮复位为"▶ 开始拾取"。
                    // 【关键修复"区域扫描关闭不了、蓝色框框常驻"】旧实现只回 IDLE 但浏览器侧
                    // __roleEndRegionSelect 未调用，导致蓝色遮罩常驻、事件监听残留。
                    try { if (!page.isClosed()) pickerEval(page, RolePickerScripts.END_REGION_SELECT_JS); } catch (Exception ignored) {}
                    setPickMode(pageNames.keySet().iterator().next(), PickMode.IDLE, pageNames);
                    return new PickerResult(PickerAction.CONTINUE, codePage, codeStep,
                            "区域扫描完成，已生成页面类（" + snap.entries.size() + " 个字段）");
                }
            }
        } catch (Exception e) {
            log.warn("[picker][regionScanned] 生成页面类失败：{}", e.getMessage());
        }
        // 区域扫描完成（无论是否拾取到元素）自动清理选区态并回 IDLE。
        try { if (!page.isClosed()) pickerEval(page, RolePickerScripts.END_REGION_SELECT_JS); } catch (Exception ignored) {}
        setPickMode(pageNames.keySet().iterator().next(), PickMode.IDLE, pageNames);
        return new PickerResult(PickerAction.CONTINUE, null, null, "区域扫描未拾取到可定位元素，请点击具体的业务区域");
    }

    private static PickerResult cmdPackage(RolePickerContext ctx, Page page) {
        LinkedHashMap<String, RoleEntry> javaPickBySig = ctx.javaPickBySig;
        String packageName = ctx.packageName;
        String pageClassName = ctx.pageClassName;
        String stepClassName = ctx.stepClassName;
        String[] nlsFiles = ctx.nlsFiles;
        // 面板「封装为步骤」按钮触发：浏览器侧 __packageStep 已把勾选集打包进 window.__steps，
        // 此处读取快照（含 steps/ops）立即生成步骤代码并切到「步骤代码」Tab，无需等到点 ⏹。
        // 同时顺带重算页面类（若扫后又点选了新元素，页面类亦随之更新）。active 保持开启，可继续拾取。
        PickSnapshot snap = null;
        try { snap = readPickSnapshot(page); } catch (Exception ignore) {}
        if (snap == null) snap = new PickSnapshot("", new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
        // 状态外置：优先用 Java 侧内存态（javaPickBySig）覆盖（对导航/关闭导致的浏览器端状态清空免疫）。
        if (!javaPickBySig.isEmpty()) {
            snap = new PickSnapshot(snap.pageClass, new ArrayList<>(javaPickBySig.values()), snap.steps, snap.ops);
        }
        // manual-mode fallback: start->stop whole session = one step; if packaged keep selection order.
        snap = RolePickerCodeAssembler.snapWithAutoStep(snap);
        LinkedHashMap<String, String> codePage = RolePickerCodeAssembler.buildPageClassCode(snap.entries, packageName, pageClassName, nlsFiles);
        LinkedHashMap<String, String> codeStep = RolePickerCodeAssembler.buildStepCode(snap, packageName, stepClassName);
        // 注：切到步骤 Tab + 精准定位目标 step 由主循环 fillCode 后调用 window.__afterFillJump 统一处理
        // （该函数在浏览器侧读取 window.__pendingJump 记录的目标 step，避免此处提前切 tab 导致定位错位）。
        // 只计真正的 step 数（snap.steps）。页面级操作（closeCurrentPage/switchNewPage）是 step 内联的一行，
        // 不计入 step 总数，否则跨页操作后"封装为一个 step"会被误报成 2 个 step。
        int stepCount = (snap.steps != null ? snap.steps.size() : 0);
        return new PickerResult(PickerAction.CONTINUE, codePage, codeStep,
                codeStep.isEmpty()
                        ? "（尚无封装的步骤：请先在「页面元素」勾选元素并点「封装为步骤」）"
                        : ("已生成步骤代码：" + stepCount + " 个 step，页面类 " + snap.entries.size() + " 个字段"));
    }

    private static PickerResult cmdRefreshCode(RolePickerContext ctx, Page page) {
        LinkedHashMap<String, RoleEntry> javaPickBySig = ctx.javaPickBySig;
        String packageName = ctx.packageName;
        String pageClassName = ctx.pageClassName;
        String stepClassName = ctx.stepClassName;
        String[] nlsFiles = ctx.nlsFiles;
        ConcurrentHashMap<Page, String> pageNames = ctx.pageNames;
        ConcurrentHashMap<Page, String> snapshots = ctx.snapshots;
        // 面板「删除」按钮触发：元素已从 window.__rolePicks / window.__steps / javaPickBySig 移除，
        // 但「页面类」「步骤代码」两个 Tab 里展示的仍是删除前生成好的旧代码文本
        // （代码是生成时一次性写入 textarea 的快照，不会自动跟随数据变化）。
        // 故此处按最新状态整体重算并回填，使已生成代码中该元素的字段声明与 step 引用一并消失。
        // 与 "package" 同一套生成链路，仅不设置 __pendingJump（不跳转 Tab，留在当前视图）。
        PickSnapshot snap = null;
        try { snap = readPickSnapshot(page); } catch (Exception ignore) {}
        if (snap == null) snap = new PickSnapshot("", new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
        // 与 package 一致：以 Java 侧内存态为准（对导航/关闭导致的浏览器端状态清空免疫）。
        // 【修复"删除元素后步骤代码括号数字不变"】删除后浏览器端 window.__steps 可能仍残留指向已删元素的旧 step，
        // 而 snapWithAutoStep 在 snap.steps 非空时会短路返回旧 steps，导致 step 数/序号不随删除更新。
        // 故此处【不沿用旧 snap.steps】，始终基于当前 javaPickBySig 重新生成 steps：
        //   - 全删空时 javaPickBySig 为空 → snap.steps 置空 → snapWithAutoStep 返回空 step（步骤代码显示"还没有任何 step"）；
        //   - 删部分时 javaPickBySig 含剩余元素 → snap.steps 置空 → snapWithAutoStep 按剩余元素序号重新拆 step，
        //     step 数量与括号序号随删除实时变化。
        // 注：手动模式主流程按点击序号拆 step，删除后重拆符合预期；若用户曾手动"封装为步骤"分组，删除后分组会被重置为按序号。
        if (!javaPickBySig.isEmpty()) {
            snap = new PickSnapshot(snap.pageClass, new ArrayList<>(javaPickBySig.values()), new ArrayList<>(), snap.ops);
        } else {
            snap = new PickSnapshot(snap.pageClass, new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
        }
        // manual-mode fallback: start->stop whole session = one step; if packaged keep selection order.
        snap = RolePickerCodeAssembler.snapWithAutoStep(snap);
        // 【修复"删除后整页重新扫描一直为 0"】
        // 旧实现在生成页面类前按会话级 RolePickerSessionState.STATE_DELETED 永久剔除已删元素，导致用户删除后重新整页扫描、
        // 新识别出的元素即便已重新入库 javaPickBySig，生成时仍被剔除，表现为"再扫描一直都是 0"。
        // 删除语义仅为"从当前内存态移除"（已被 collectDeleteKeys 的 ① ② ③ 兜底 + 源头清空 iframe
        // 残留完整覆盖），不应永久封杀该元素。故此处【不再】按 RolePickerSessionState.STATE_DELETED 剔除，以 javaPickBySig
        // 当前内容为准直接生成——重新扫描即可正常出现代码。
        LinkedHashMap<String, String> codePage = RolePickerCodeAssembler.buildPageClassCode(snap.entries, packageName, pageClassName, nlsFiles);
        LinkedHashMap<String, String> codeStep = RolePickerCodeAssembler.buildStepCode(snap, packageName, stepClassName);
        String refreshMsg = "已删除选中元素，页面类 " + snap.entries.size() + " 个字段"
                + (codeStep.isEmpty() ? "，当前无步骤代码" : "");
        // 【必须在此处直接回填】元素被删空时 codePage/codeStep 均为空 map，
        // 若交给主循环处理会因「两者都为空」落入 else 分支只更新状态栏，
        // 旧代码将永远残留在 Tab 上（删到一个不剩却还显示着完整页面类）。
        // 这里显式回填空内容，确保代码区随之清空，不留幽灵代码。
        for (Page p : pageNames.keySet()) {
            if (!p.isClosed()) fillCode(p, codePage, codeStep, refreshMsg);
        }
        // 已自行回填，故返回 null 代码体避免主循环重复 fillCode；
        // statusMsg 仍需返回（而非 null），否则 else 分支会用 null 覆盖掉状态栏文案。
        return new PickerResult(PickerAction.CONTINUE, null, null, refreshMsg);
    }

    private static PickerResult cmdStop(RolePickerContext ctx, Page page) {
        AtomicBoolean active = ctx.active;
        String[] nlsFiles = ctx.nlsFiles;
        String packageName = ctx.packageName;
        String pageClassName = ctx.pageClassName;
        String stepClassName = ctx.stepClassName;
        ConcurrentHashMap<Page, String> pageNames = ctx.pageNames;
        ConcurrentHashMap<Page, String> snapshots = ctx.snapshots;
        LinkedHashMap<String, RoleEntry> javaPickBySig = ctx.javaPickBySig;
        active.set(false);
        log.info("[picker][stop] 收到停止命令，对 {} 个被跟踪页面执行停止", pageNames.size());
        // 多实例：停止作用于所有已打开页面，使各页面板同步回 ▶ 开始
        // （否则某页仍显示停止却已失活，造成"点了没反应"的错觉）。
        // 企业级优化：命令来源页用 stopAndRead 把"去激活 + 收尾当前 step + 读回全部拾取态"
        // 合并为 1 次 Java↔浏览器往返；其余被跟踪页仅去激活（stop），避免重复生成代码与多余读快照。
        // （原实现对命令页额外发一次收尾 evaluate + 一次 readPickSnapshot，与 stop() 共 3 次串行往返，
        //  现已合并进 stopAndRead，点击"停止"的端到端延迟显著下降。）
        PickSnapshot snap = null;
        // 折叠已关闭页（如"跳转到新页面后直接关闭"的根页）缓存的 step/op：停止生成时这些页被跳过，
        // 若不折叠，其在 onClose else 分支补登记的 closeCurrentPage 步骤会丢失。
        List<StepRec> closedSteps = new ArrayList<>();
        List<PageOp> closedOps = new ArrayList<>();
        for (Page p : pageNames.keySet()) {
            if (p.isClosed()) {
                String cached = snapshots.get(p);
                if (cached != null && !cached.isEmpty()) {
                    try {
                        java.util.Map<String, Object> cm = GSON.fromJson(cached, MAP_STRING_OBJECT_TYPE);
                        PickSnapshot cs = parsePickSnapshot(cm);
                        closedSteps.addAll(cs.steps);
                        closedOps.addAll(cs.ops);
                    } catch (Exception ignore) {}
                }
                continue;
            }
            try {
                if (p == page) snap = stopAndRead(p);
                else stop(p);
            } catch (Exception stopEx) {
                // 密集导航（onFrameNavigated/整页跳转）时命令来源页的 execution context 可能正在
                // 销毁/重建，stopAndRead/stop 的 page.evaluate 会抛 "Execution context was destroyed"。
                // 不向上冒泡撕裂主循环会话：标记已失活并降级为读内存态，保证"停止"在任何导航瞬间都生效，
                // 不再出现"点了停止却卡住/没反应"的假死（active 已被 active.get()=false 复位）。
                log.warn("[picker][stop] 停止页 {} 时 evaluate 失败（导航中可忽略）：{}",
                        p.url(), stopEx.getMessage());
                try { p.evaluate(RolePickerScripts.SET_PICK_STOPPED_JS); } catch (Exception ignore) {}
            }
        }
        if (snap == null) snap = readPickSnapshot(page);   // 兜底：命令页不在跟踪集合时
        // 跨域页停止时，主框架 page.evaluate 可能因导航竞态返回空/抛异常（snap.entries 为空）；
        // 此处再从各 frame 的 window.__rolePicks 逐帧兜底读取（与 stop() 的跨 frame 聚合同口径），
        // 确保跨域主框架页自身已被拾取的元素不丢失（修复"跨域页拾取完停止未生成 step"）。
        if (snap == null || snap.entries.isEmpty()) {
            try {
                List<RoleEntry> fEntries = new ArrayList<>();
                List<StepRec> fSteps = new ArrayList<>();
                List<PageOp> fOps = new ArrayList<>();
                for (com.microsoft.playwright.Frame f : page.frames()) {
                    try {
                        // 与 stopAndRead/readPickSnapshot 同口径：直接读各 frame 的 window.__rolePicks/__steps/__ops
                        PickSnapshot fs = parsePickSnapshot(pickerEval(f, RolePickerScripts.PICK_STATE_READER_JS));
                        fEntries.addAll(fs.entries); fSteps.addAll(fs.steps); fOps.addAll(fs.ops);
                    } catch (Exception fe) { /* 单 frame 失败忽略，继续其它 frame */ }
                }
                if (!fEntries.isEmpty()) {
                    PickSnapshot fb = new PickSnapshot(snap == null ? "" : snap.pageClass, fEntries, fSteps, fOps);
                    // 内存态(javaPickBySig)优先；仅当内存态也为空时才用跨 frame 浏览器兜底态。
                    snap = (!javaPickBySig.isEmpty()) ? snap : fb;
                }
            } catch (Exception ff) {
                log.warn("[picker][stop] 跨 frame 兜底读快照失败 @ {} : {}", page.url(), ff.getMessage());
            }
        }
        // 状态外置（对齐 page.pause）：优先用 Java 侧内存态（javaPickBySig）作为已拾元素权威来源，
        // O(1) 取回、且对导航/关闭导致的浏览器端状态清空免疫；内存为空（回传桥未触发等异常）时
        // 退回浏览器读快照兜底。steps/ops 仍来自浏览器单次往返（stopAndRead 已合并），保证多页 step 序列正确。
        if (!javaPickBySig.isEmpty()) {
            List<RoleEntry> memEntries = new ArrayList<>(javaPickBySig.values());
            snap = new PickSnapshot(snap.pageClass, memEntries, snap.steps, snap.ops);
        }
        // manual-mode (not packaged) fallback: start->stop whole session = one step.
        snap = RolePickerCodeAssembler.snapWithAutoStep(snap);
        // 合入已关闭页的步骤/操作（含其补登记的 closeCurrentPage），避免关闭步骤在停止时被跳过而丢失。
        if (!closedSteps.isEmpty() || !closedOps.isEmpty()) {
            List<StepRec> mergedSteps = new ArrayList<>(snap.steps);
            mergedSteps.addAll(closedSteps);
            List<PageOp> mergedOps = new ArrayList<>(snap.ops);
            mergedOps.addAll(closedOps);
            snap = new PickSnapshot(snap.pageClass, snap.entries, mergedSteps, mergedOps);
        }
        // 关键修复（修复"开始→停止→再开始→停止，第一次的步骤代码丢失"）：
        // 整页跳转时 onFrameNavigated→applyPickState 会用 snapshots 里的 Java 恢复态【整体覆盖】
        // window.__steps（见 applyPickState：window.__steps = s.steps || []）。而快照此前只在"空闲刷新"时更新，
        // 且 run1 拾取期间 __steps 为空、空闲刷新把恢复态停在"空 steps"；若 run2 中发生整页跳转，
        // 就用这份过期恢复态把第一次的 step 整体覆盖丢失。此处把本次停止后的最新态【立即回写】Java 恢复态，
        // 使任何后续跳转恢复时都含已有 step（含第一次），彻底消除该时序窗口。
        try { snapshots.put(page, readPickStateJson(page)); } catch (Exception ignore) {}
        // 多页面：当前页 window 持有全部被跟踪页面的拾取（跟随新页时搬运、关闭弹窗时合并回父页），
        // 每条 pick/step 都带 _pageClass 标签，据此分组到对应 Page 类；steps 跨页引用也归到对应页。
        String curClass = snap.pageClass;
        LinkedHashMap<String, List<RoleEntry>> entriesByPage = new LinkedHashMap<>();
        LinkedHashMap<String, List<List<RoleEntry>>> stepsByPage = new LinkedHashMap<>();
        List<RoleEntry> allEntries = new ArrayList<>();
        int totalSteps = 0;
        for (RoleEntry e : snap.entries) {
            String pc = (e.getPageClass() == null || e.getPageClass().isEmpty()) ? curClass : e.getPageClass();
            entriesByPage.computeIfAbsent(pc, k -> new ArrayList<>()).add(e);
            allEntries.add(e);
        }
        for (StepRec st : snap.steps) {
            String pc = (st.pageClass == null || st.pageClass.isEmpty()) ? curClass : st.pageClass;
            stepsByPage.computeIfAbsent(pc, k -> new ArrayList<>()).add(st.picks);
            entriesByPage.computeIfAbsent(pc, k -> new ArrayList<>());   // 保证 steps 的页在 entries 中也有类
            totalSteps++;
        }
        // 页面级操作（如关闭页面）按所属页归类，生成 closeCurrentPage() 等步骤。
        LinkedHashMap<String, List<String>> opsByPage = new LinkedHashMap<>();
        for (PageOp op : snap.ops) {
            String pc = (op.pageClass == null || op.pageClass.isEmpty()) ? curClass : op.pageClass;
            opsByPage.computeIfAbsent(pc, k -> new ArrayList<>()).add(op.op);
            // 注意：页面级操作（closeCurrentPage / switchNewPage 等）已在各页视图内联为 step 内的一行代码，
            // 不是独立 step，故不再把 op 计入 totalSteps，否则"封装为 1 个 step + 1 个内联跳转操作"会误显为 2 个 step。
        }
        if (entriesByPage.isEmpty()) {
            // 诊断：跨域/导航竞态下出现"未拾取到元素"时，记录内存态与浏览器侧 picks 数量，便于定位是否漏拾。
            int jsMem = javaPickBySig.size();
            int browserPicks = 0;
            try { browserPicks = ((List<?>) pickerEval(page, RolePickerScripts.READ_PICK_COUNT_JS)).size(); } catch (Exception ignoreB) {}
            log.warn("[picker][stop] 未拾取到元素 @ {} : 内存态 javaPickBySig={}, 浏览器 __rolePicks={}, 当前页 origin={}",
                    page.url(), jsMem, browserPicks, safeOrigin(page.url()));
            // 停止即回 IDLE，面板按钮复位为"▶ 开始拾取"。
            // 停止即回 IDLE，面板按钮复位为"▶ 开始拾取"。
            setPickMode(pageNames.keySet().iterator().next(), PickMode.IDLE, pageNames);
            return new PickerResult(PickerAction.CONTINUE, null, null, "未拾取到元素");
        }
        // 按 pageClass 分别生成页面类（含"仅 step/ops 无元素 pick"的页，也产出空字段类，保证步骤视图引用不悬空）
        LinkedHashMap<String, String> codePage = new LinkedHashMap<>();
        for (Map.Entry<String, List<RoleEntry>> e : entriesByPage.entrySet()) {
            codePage.put(e.getKey(), RoleElementPageGenerator.generate(e.getValue(), packageName, e.getKey(), nlsFiles));
        }
        LinkedHashMap<String, String> codeStep = RolePickerCodeAssembler.buildStepCode(snap, packageName, stepClassName);
        // 【diag-stop】停止并生成代码前，列印全部 entry 的最终 pickNos（生成器即据此按号展开 click）。
        log.info("[picker][diag-stop] ===== before buildStepCode: {} entries =====", allEntries.size());
        for (RoleEntry e : allEntries) {
            log.info("[picker][diag-stop] entry sigKey={} strategy={} pageClass={} pickNos={}", e.getSigKey(), e.getStrategy(), e.getPageClass(), e.getPickNos());
        }
        // 停止拾取后重置（必须在 buildStepCode 之后，生成已基于累积 pickNos 完成）：
        // 清空 Java 权威内存态每个 entry 的 pickNos，并让浏览器侧 window.__rolePicks 的
        // _pickNos/_pickSeq 归零，使面板干净回退到 [-]，且下一次 start 时全局动作序号从 1 重新计数。
        for (RoleEntry e : javaPickBySig.values()) e.setPickNos(new java.util.ArrayList<>());
        try {
            // 【修复"stop→start 第二轮序号错乱（如 user_name 拿到旧号而非从续接点递增）】
            // 原逻辑只 delete p._pickNos + p._pickSeq=0，但 __rolePicks 数组、__rolePickSigs（key 去重表）、
            // __sigToPick（sigKey→pick 对象）、以及上一轮缓存的旧 pick 对象（__pickSeq/_pickNos 残留）都仍保留。
            // start 时设计上"保留既有 picks、从既有重建去重表"，于是第二轮点同一元素会【复用旧 pick 对象】
            // （其旧 _pickSeq/_pickNos 仍在），导致序号回退/重复。
            // 修复：stop 时彻底清空浏览器侧全部拾取注册状态（与"下一轮从 1 重新连续"语义一致），
            // 停止拾取：保留元素列表（__rolePicks），但清除所有序号（重置为 [-,+]）
            // 这样第二轮拾取时，元素仍在列表中，显示为 [-,+]，用户可重新勾选分配序号
            pickerEval(page, RolePickerScripts.CLEAR_PICKS_JS);
        } catch (Exception ignore) {}
        int matched = 0;
        for (RoleEntry e : allEntries) {
            if (e.getResolvedKey() != null) matched++;
        }
        String nlsInfo = (nlsFiles != null && nlsFiles.length > 0)
                ? "（nls=" + (nlsFiles.length == 1 ? nlsFiles[0] : nlsFiles.length + " 个文件")
                    + "，已反查 " + matched + " 个 key）" : "";
        // 停止即回 IDLE，面板按钮复位为"▶ 开始拾取"，页面点击不再拾取。
        setPickMode(pageNames.keySet().iterator().next(), PickMode.IDLE, pageNames);
        return new PickerResult(PickerAction.CONTINUE, codePage, codeStep,
                "已生成 " + entriesByPage.size() + " 个页面类 / " + allEntries.size()
                        + " 个页面字段 / " + totalSteps + " 个 step" + nlsInfo);
    }

    static PickerResult runPickerCommand(RolePickerContext ctx, Page page, String cmd) {
        // 【删除单个拾取序号 + 全局重编号】浏览器侧面板点击某序号超链接时，经 __rolePickerCmd 投递
        // JSON 命令 {type:'repickNos', mergeKey, nos:[...]}：把该元素在 Java 权威内存态中的 pickNos
        // 更新为浏览器侧重排后的新序号数组（步骤生成依赖 pickNos 按号展开，故 Java 必须同步）。
        // 纯字符串命令（start/scan/...）走下方 switch；JSON 命令在此先拦截处理。
        PickerResult repick = handleRepickNos(ctx, page, cmd);
        if (repick != null) return repick;
        switch (cmd) {
            case RolePickerConstants.CMD_START: return cmdStart(ctx, page);
            case RolePickerConstants.CMD_SCAN: return cmdScan(ctx, page);
            case RolePickerConstants.CMD_SCAN_REGION: return cmdScanRegion(ctx, page);
            case RolePickerConstants.CMD_REGION_SCANNED: return cmdRegionScanned(ctx, page);
            case RolePickerConstants.CMD_PACKAGE: return cmdPackage(ctx, page);
            case RolePickerConstants.CMD_REFRESH_CODE: return cmdRefreshCode(ctx, page);
            case RolePickerConstants.CMD_STOP: return cmdStop(ctx, page);
            case RolePickerConstants.CMD_ABORT: return cmdAbort(ctx, page);
            case RolePickerConstants.CMD_DONE: return cmdDone(ctx, page);
            default: return cmdUnknown(ctx, page);
        }
    }}
