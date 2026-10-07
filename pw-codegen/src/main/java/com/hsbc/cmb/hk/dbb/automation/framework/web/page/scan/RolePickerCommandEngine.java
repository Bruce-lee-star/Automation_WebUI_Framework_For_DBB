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
                                    log.info("[picker] repickNos synced to in-memory state: sigKey={} -> nos={}", mk, nos);
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
                                    log.info("[picker] repickNos synced as deleted: sigKey={} (no longer present browser-side)", sigKey);
                                    it.remove();
                                }
                            }
                        }
                    } catch (Exception syncEx) {
                        log.warn("[picker] failed to sync repickNos deletion: {}", syncEx.getMessage());
                    }
                    // 重编号后把最新内存态回灌浏览器面板，保证面板/快照/Java 三侧序号一致。
                    // 强制刷新 ETag：repickNos 只改序号、元素身份未变，若不清除 LAST_SYNC_SIG，
                    // syncPanelToBrowser 的签名短路会跳过回灌，导致面板序号不刷新（被旧值覆盖）。
                    try { RolePickerPanelSync.LAST_SYNC_SIG.remove(page); } catch (Exception ignore) { RolePickerQuiet.ignore("RolePickerCommandEngine", ignore); }
                    try { if (!page.isClosed()) syncPanelToBrowser(page, null, javaPickBySig, true); } catch (Exception ignore) { RolePickerQuiet.ignore("RolePickerCommandEngine", ignore); }
                    // 【diag-repick】sync 后回读浏览器侧 __rolePicks 的实际 _pickNos，确认回灌生效（而非旧值残留）。
                    try {
                        @SuppressWarnings("unchecked")
                        List<?> rp = (List<?>) pickerEval(page, RolePickerScripts.READ_PICK_KEYS_JS);
                        log.info("[picker][diag-repick] browser-side __rolePicks after backfill: {}", rp);
                    } catch (Exception ignoreR) { RolePickerQuiet.ignore("RolePickerCommandEngine#ignoreR", ignoreR); }
                    return new PickerResult(PickerAction.CONTINUE, null, null,
                            "已删除拾取序号并重排（" + (nos == null ? 0 : nos.size()) + " 个序号）");
                }
            } catch (Exception ex) {
                log.warn("[picker] failed to parse repickNos command: {}", ex.getMessage());
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
        synchronized (javaPickBySig) {
            for (RoleEntry e : javaPickBySig.values()) {
                if (e != null) {
                    e.setPickNos(new java.util.ArrayList<>());  // 清空序号为 []（保留元素，语义与面板删除一致）
                }
            }
        }
        // 进入手动拾取模式（互斥：此时整页/区域扫描按钮禁用，点击页面只拾取被点元素）。
        setPickMode(pageNames.keySet().iterator().next(), PickMode.MANUAL, pageNames);
        // 反向查表只构建一次（避免对每个被跟踪页面重复读 nls 文件），减少点击"开始"的延迟。
        String startNls = RolePickerNlsCache.buildNlsReverseJson(Arrays.asList(nlsFiles));
        for (Page p : pageNames.keySet()) {
            if (!p.isClosed()) { log.info("[picker][start] calling start for page {}", p.url()); start(p, startNls); }
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
        try { snap = readPickSnapshot(page); } catch (Exception ignore) { RolePickerQuiet.ignore("RolePickerCommandEngine", ignore); }
        if (snap == null) snap = new PickSnapshot("", new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
        // 状态外置：优先用 Java 侧内存态（javaPickBySig）覆盖（对导航/关闭导致的浏览器端状态清空免疫）。
        synchronized (javaPickBySig) {
            if (!javaPickBySig.isEmpty()) {
                List<RoleEntry> snapshotEntries = new ArrayList<>(javaPickBySig.values());
                snap = new PickSnapshot(snap.pageClass, snapshotEntries, snap.steps, snap.ops);
            }
        }
        // manual-mode fallback: start->stop whole session = one step; if packaged keep selection order.
        snap = RolePickerCodeAssembler.snapWithAutoStep(snap);
        LinkedHashMap<String, String> codePage = RolePickerCodeAssembler.buildPageClassCode(snap.entries, packageName, pageClassName, nlsFiles);
        LinkedHashMap<String, String> codeStep = RolePickerCodeAssembler.buildStepCode(snap, packageName, stepClassName);
        // 断言类：与步骤同源同序（同一次封装的勾选元素），独立成类 → 面板「断言」Tab
        LinkedHashMap<String, String> codeAssert = RolePickerCodeAssembler.buildAssertCode(snap, packageName);
        // 注：切到步骤 Tab + 精准定位目标 step 由主循环 fillCode 后调用 window.__afterFillJump 统一处理
        // （该函数在浏览器侧读取 window.__pendingJump 记录的目标 step，避免此处提前切 tab 导致定位错位）。
        // 只计真正的 step 数（snap.steps）。页面级操作（closeCurrentPage/switchNewPage）是 step 内联的一行，
        // 不计入 step 总数，否则跨页操作后"封装为一个 step"会被误报成 2 个 step。
        int stepCount = (snap.steps != null ? snap.steps.size() : 0);
        return new PickerResult(PickerAction.CONTINUE, codePage, codeStep, codeAssert,
                codeStep.isEmpty()
                        ? "（尚无封装的步骤：请先在「页面元素」勾选元素并点「封装为步骤」）"
                        : ("已生成步骤代码：" + stepCount + " 个 step，页面类 " + snap.entries.size() + " 个字段"
                                + (codeAssert.isEmpty() ? "" : "，断言类 " + codeAssert.size() + " 个")));
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
        try { snap = readPickSnapshot(page); } catch (Exception ignore) { RolePickerQuiet.ignore("RolePickerCommandEngine", ignore); }
        if (snap == null) snap = new PickSnapshot("", new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
        // 与 package 一致：以 Java 侧内存态为准（对导航/关闭导致的浏览器端状态清空免疫）。
        // 【修复"删除元素后步骤代码括号数字不变"】删除后浏览器端 window.__steps 可能仍残留指向已删元素的旧 step，
        // 而 snapWithAutoStep 在 snap.steps 非空时会短路返回旧 steps，导致 step 数/序号不随删除更新。
        // 故此处【不沿用旧 snap.steps】，始终基于当前 javaPickBySig 重新生成 steps：
        //   - 全删空时 javaPickBySig 为空 → snap.steps 置空 → snapWithAutoStep 返回空 step（步骤代码显示"还没有任何 step"）；
        //   - 删部分时 javaPickBySig 含剩余元素 → snap.steps 置空 → snapWithAutoStep 按剩余元素序号重新拆 step，
        //     step 数量与括号序号随删除实时变化。
        // 注：手动模式主流程按点击序号拆 step，删除后重拆符合预期；若用户曾手动"封装为步骤"分组，删除后分组会被重置为按序号。
        synchronized (javaPickBySig) {
            if (!javaPickBySig.isEmpty()) {
                List<RoleEntry> snapshotEntries = new ArrayList<>(javaPickBySig.values());
                snap = new PickSnapshot(snap.pageClass, snapshotEntries, new ArrayList<>(), snap.ops);
            } else {
                snap = new PickSnapshot(snap.pageClass, new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
            }
        }
        // manual-mode fallback: start->stop whole session = one step; if packaged keep selection order.
        snap = RolePickerCodeAssembler.snapWithAutoStep(snap);
        // 【修复"删除后整页重新扫描一直为 0"】
        // 旧实现在生成页面类前按会话级已删集合永久剔除已删元素，导致用户删除后重新整页扫描、
        // 新识别出的元素即便已重新入库 javaPickBySig，生成时仍被剔除，表现为"再扫描一直都是 0"。
        // 删除语义仅为"从当前内存态移除"（已被 collectDeleteKeys 的 ① ② ③ 兜底 + 源头清空 iframe
        // 残留完整覆盖），不应永久封杀该元素。故此处不按任何"已删集合"剔除（该会话级状态已按 N-18 删除），以 javaPickBySig
        // 当前内容为准直接生成——重新扫描即可正常出现代码。
        LinkedHashMap<String, String> codePage = RolePickerCodeAssembler.buildPageClassCode(snap.entries, packageName, pageClassName, nlsFiles);
        LinkedHashMap<String, String> codeStep = RolePickerCodeAssembler.buildStepCode(snap, packageName, stepClassName);
        LinkedHashMap<String, String> codeAssert = RolePickerCodeAssembler.buildAssertCode(snap, packageName);
        String refreshMsg = "已删除选中元素，页面类 " + snap.entries.size() + " 个字段"
                + (codeStep.isEmpty() ? "，当前无步骤代码" : "");
        // 【必须在此处直接回填】元素被删空时 codePage/codeStep 均为空 map，
        // 若交给主循环处理会因「两者都为空」落入 else 分支只更新状态栏，
        // 旧代码将永远残留在 Tab 上（删到一个不剩却还显示着完整页面类）。
        // 这里显式回填空内容，确保代码区随之清空，不留幽灵代码。
        for (Page p : pageNames.keySet()) {
            if (!p.isClosed()) fillCode(p, codePage, codeStep, codeAssert, refreshMsg);
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
        log.info("[picker][stop] stop command received; stopping {} tracked page(s)", pageNames.size());
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
                    } catch (Exception ignore) { RolePickerQuiet.ignore("RolePickerCommandEngine", ignore); }
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
                log.warn("[picker][stop] evaluate failed while stopping page {} (ignorable during navigation): {}",
                        p.url(), stopEx.getMessage());
                try { p.evaluate(RolePickerScripts.SET_PICK_STOPPED_JS); } catch (Exception ignore) { RolePickerQuiet.ignore("RolePickerCommandEngine", ignore); }
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
                    boolean javaEmpty;
                    synchronized (javaPickBySig) { javaEmpty = javaPickBySig.isEmpty(); }
                    snap = javaEmpty ? fb : snap;
                }
            } catch (Exception ff) {
                log.warn("[picker][stop] cross-frame fallback snapshot read failed @ {} : {}", page.url(), ff.getMessage());
            }
        }
        // 状态外置（对齐 page.pause）：优先用 Java 侧内存态（javaPickBySig）作为已拾元素权威来源，
        // O(1) 取回、且对导航/关闭导致的浏览器端状态清空免疫；内存为空（回传桥未触发等异常）时
        // 退回浏览器读快照兜底。steps/ops 仍来自浏览器单次往返（stopAndRead 已合并），保证多页 step 序列正确。
        synchronized (javaPickBySig) {
            if (!javaPickBySig.isEmpty()) {
                List<RoleEntry> memEntries = new ArrayList<>(javaPickBySig.values());
                snap = new PickSnapshot(snap.pageClass, memEntries, snap.steps, snap.ops);
            }
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
        try { snapshots.put(page, readPickStateJson(page)); } catch (Exception ignore) { RolePickerQuiet.ignore("RolePickerCommandEngine", ignore); }
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
            try { browserPicks = ((List<?>) pickerEval(page, RolePickerScripts.READ_PICK_COUNT_JS)).size(); } catch (Exception ignoreB) { RolePickerQuiet.ignore("RolePickerCommandEngine#readPickCount", ignoreB); }
            log.warn("[picker][stop] no elements picked @ {} : in-memory javaPickBySig={}, browser __rolePicks={}, current page origin={}",
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
        // 【修复"停止后「断言」Tab 为空（没有生成断言代码）」】本路径原先只生成 page/step，且用 PickerResult 的
        // 兼容构造（assertByPage=null）⇒ 面板「断言」Tab 被渲染成"（暂无生成）"，用户表现为"没生成断言"。
        // 断言与步骤同源（都由 snap.steps 派生），此处一并生成并回填。
        LinkedHashMap<String, String> codeAssert = RolePickerCodeAssembler.buildAssertCode(snap, packageName);
        // 【diag-stop】停止并生成代码前，列印全部 entry 的最终 pickNos（生成器即据此按号展开 click）。
        log.info("[picker][diag-stop] ===== before buildStepCode: {} entries =====", allEntries.size());
        for (RoleEntry e : allEntries) {
            log.info("[picker][diag-stop] entry sigKey={} strategy={} pageClass={} pickNos={}", e.getSigKey(), e.getStrategy(), e.getPageClass(), e.getPickNos());
        }
        // 停止拾取后重置（必须在 buildStepCode 之后，生成已基于累积 pickNos 完成）：
        // 清空 Java 权威内存态每个 entry 的 pickNos，并让浏览器侧 window.__rolePicks 的
        // _pickNos/_pickSeq 归零，使面板干净回退到 [-]，且下一次 start 时全局动作序号从 1 重新计数。
        synchronized (javaPickBySig) {
            for (RoleEntry e : javaPickBySig.values()) e.setPickNos(new java.util.ArrayList<>());
        }
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
        } catch (Exception ignore) { RolePickerQuiet.ignore("RolePickerCommandEngine", ignore); }
        int matched = 0;
        for (RoleEntry e : allEntries) {
            if (e.getResolvedKey() != null) matched++;
        }
        String nlsInfo = (nlsFiles != null && nlsFiles.length > 0)
                ? "（nls=" + (nlsFiles.length == 1 ? nlsFiles[0] : nlsFiles.length + " 个文件")
                    + "，已反查 " + matched + " 个 key）" : "";
        // 停止即回 IDLE，面板按钮复位为"▶ 开始拾取"，页面点击不再拾取。
        setPickMode(pageNames.keySet().iterator().next(), PickMode.IDLE, pageNames);
        String assertInfo = codeAssert.isEmpty() ? "" : "，断言类 " + codeAssert.size() + " 个（按页）";
        return new PickerResult(PickerAction.CONTINUE, codePage, codeStep, codeAssert,
                "已生成 " + entriesByPage.size() + " 个页面类 / " + allEntries.size()
                        + " 个页面字段 / " + totalSteps + " 个 step" + assertInfo + nlsInfo);
    }

    static PickerResult runPickerCommand(RolePickerContext ctx, Page page, String cmd) {
        // 【删除单个拾取序号 + 全局重编号】浏览器侧面板点击某序号超链接时，经 __rolePickerCmd 投递
        // JSON 命令 {type:'repickNos', mergeKey, nos:[...]}：把该元素在 Java 权威内存态中的 pickNos
        // 更新为浏览器侧重排后的新序号数组（步骤生成依赖 pickNos 按号展开，故 Java 必须同步）。
        // 纯字符串命令（start/package/stop...）走下方 switch；JSON 命令在此先拦截处理。
        // 【已移除「扫描整页」与「区域扫描」】scan / scanRegion / regionScanned / regionDone 四类命令
        // 不再有分支处理：它们会把整页/整区的候选元素批量走「拾取记录」链路（每次记录分配一个全局
        // 递增拾取号），使仅有 8 个元素的会话累积出上百个序号、并因 Java 侧"只增不减"的并集合并不断
        // 放大，最终生成结果错乱（详见 panel-core-a.js 工具栏注释）。这四类命令现落入 default
        // （cmdUnknown）被安全忽略，会话不中断。
        PickerResult repick = handleRepickNos(ctx, page, cmd);
        if (repick != null) return repick;
        switch (cmd) {
            case RolePickerConstants.CMD_START: return cmdStart(ctx, page);
            case RolePickerConstants.CMD_PACKAGE: return cmdPackage(ctx, page);
            case RolePickerConstants.CMD_REFRESH_CODE: return cmdRefreshCode(ctx, page);
            case RolePickerConstants.CMD_STOP: return cmdStop(ctx, page);
            case RolePickerConstants.CMD_ABORT: return cmdAbort(ctx, page);
            case RolePickerConstants.CMD_DONE: return cmdDone(ctx, page);
            default: return cmdUnknown(ctx, page);
        }
    }}
