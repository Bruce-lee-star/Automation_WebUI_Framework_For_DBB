package com.hsbc.cmb.hk.dbb.automation.framework.web.page.scan;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.microsoft.playwright.Page;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import com.hsbc.cmb.hk.dbb.automation.framework.web.page.scan.model.PickSnapshot;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.scan.model.StepRec;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.scan.model.PageOp;

/**
 * Code-generation assembler (T5-1 step 3): relocates RoleElementPicker's pure
 * source-generation orchestration into this class. Inputs/outputs are plain data
 * (List<RoleEntry> / PickSnapshot / LinkedHashMap); it reads/writes no CTX_* runtime
 * state and triggers no Playwright/browser calls, only delegating emission to
 * RoleElementPageGenerator / RoleElementStepGenerator plus entries<->steps enrichment
 * and dedup reconciliation. Behavior is identical to the original inline logic.
 */
final class RolePickerCodeAssembler {

    private static final Logger log = LoggerFactory.getLogger(RolePickerCodeAssembler.class);
    private static final Gson GSON = new Gson();
    private static final Type MAP_STRING_OBJECT_TYPE = new TypeToken<Map<String, Object>>() {}.getType();
    /** 关闭步骤序号器：保证每次"关闭当前页"标记签名唯一、可去重。 */
    private static final java.util.concurrent.atomic.AtomicInteger CLOSE_SEQ =
            new java.util.concurrent.atomic.AtomicInteger();

    /**
     * 由一组已拾取元素按所属页面类分组生成页面类源码（与 stop 命令的生成逻辑一致）。
     * 返回 pageClass → 该页完整页面类源码 的 map，供面板"页面类"Tab 按页分栏展示（对齐"页面元素"Tab）。
     * 单页即一个 entry；多页（弹窗/新标签页）则各元素按其 `_pageClass` 各自成类。
     *
     * @param entries       全部拾取元素（来自浏览器侧 window.__rolePicks，扫描与点击拾取已合并）
     * @param packageName   生成类的包名
     * @param defaultPageClass 兜底页类名（元素未带 _pageClass 时归入此类）
     * @param nlsFiles      nls 反向查表文件（用于定位键反查）
     * @return pageClass → 页面类源码（LinkedHashMap 保序，空列表时返回空 map）
     */
    static LinkedHashMap<String, String> buildPageClassCode(List<RoleEntry> entries, String packageName,
                                             String defaultPageClass, String[] nlsFiles) {
        LinkedHashMap<String, String> out = new LinkedHashMap<>();
        if (entries == null || entries.isEmpty()) return out;
        LinkedHashMap<String, List<RoleEntry>> entriesByPage = new LinkedHashMap<>();
        for (RoleEntry e : entries) {
            String pc = (e.getPageClass() == null || e.getPageClass().isEmpty()) ? defaultPageClass : e.getPageClass();
            entriesByPage.computeIfAbsent(pc, k -> new ArrayList<>()).add(e);
        }
        for (Map.Entry<String, List<RoleEntry>> en : entriesByPage.entrySet()) {
            out.put(en.getKey(), RoleElementPageGenerator.generate(en.getValue(), packageName, en.getKey(), nlsFiles));
        }
        return out;
    }

    /**
     * 由快照（已拾元素 + 已封装 steps/ops）按页生成步骤代码（与 stop 命令的生成逻辑一致）。
     * 返回 pageClass → 该页"完整可编译的 Step 类视图"源码 的 map，供面板"步骤代码"Tab 按页分栏展示
     * （对齐"页面元素"Tab）。多页时主页视图含跨页 step、弹窗页视图含其 close 操作。
     *
     * @return pageClass → 步骤类视图源码（LinkedHashMap 保序，无 step/操作时返回空 map）
     */
    /**
     * Manual-mode / not-packaged fallback: treat ALL picked entries as ONE step
     * (start -> stop = one step). If already packaged (snap.steps non-empty, ordered by selection),
     * leave it untouched. step.pageClass uses the root page (first entry's pageClass).
     * Cross iframe/new-page/dialog entries keep their framePath/dialog/popup markers,
     * handled per-element by RoleElementStepGenerator (switchToFrame/acceptAlert/waitForNewPage).
     */
    static PickSnapshot snapWithAutoStep(PickSnapshot snap) {
        if (snap == null) return snap;
        if (snap.steps != null && !snap.steps.isEmpty()) return snap;          // already packaged: keep selection order
        if (snap.entries == null || snap.entries.isEmpty()) return snap;
        List<RoleEntry> all = new ArrayList<>(snap.entries);
        String rootPc = "";
        for (RoleEntry e : all) {
            String pc = e.getPageClass();
            if (pc != null && !pc.isEmpty()) { rootPc = pc; break; }
        }
        if (rootPc.isEmpty()) rootPc = (snap.pageClass == null) ? "" : snap.pageClass;
        // 手动模式（start→stop 未封装）：所有拾取元素封装为【一个步骤】(step1)，
        // 步骤内元素按全局拾取序号（pickNos 首号）升序排列——即"按拾取顺序封装为一个步骤"。
        // 序号相等的保持原拾取顺序（避免 ArrayList.sort 不稳定重排）。
        // 注意：getSeq() 在 __renumberStep 中被设为步骤索引（i+1），会覆盖全局序号，
        // 因此此处直接用 pickNos 首号排序，而非 getSeq()。
        // 同元素多次点击（如 pickNos=[1,2,3]）由 RoleElementStepGenerator 内部的
        // __repeat = pickNos.size() 展开为多个重复操作，无需在此拆分 step。
        List<RoleEntry> sorted = new ArrayList<>(all);
        sorted.sort(java.util.Comparator.comparingInt((RoleEntry e) -> {
            if (e == null) return Integer.MAX_VALUE;
            java.util.List<Integer> nos = e.getPickNos();
            if (nos != null && !nos.isEmpty() && nos.get(0) != null && nos.get(0) > 0) {
                return nos.get(0);
            }
            return Integer.MAX_VALUE;
        }).thenComparingInt(all::indexOf));
        List<StepRec> steps = new ArrayList<>();
        steps.add(new StepRec(rootPc, sorted));
        return new PickSnapshot(snap.pageClass, snap.entries, steps, snap.ops);
    }

    static LinkedHashMap<String, String> buildStepCode(PickSnapshot snap, String packageName, String stepClassName) {
        LinkedHashMap<String, String> out = new LinkedHashMap<>();
        if (snap == null) return out;
        // 【关键修复"iframe 内元素生成 step 缺 switchToFrame"】
        // snap.steps 来自浏览器侧 window.__steps 读取，其 pick 的 framePath/dialog/popup 等增强字段
        // 是浏览器侧 __enrichState / 点击 handler 在拾取瞬间写入的——但 iframe 内：
        //   · __framePathOf 在 file:// 跨 frame 下整段抛异常被吞为空数组，pick.framePath 未被赋值；
        //   · 绑定桥失效，console 通道的 framePath backfill 又因 e.getPageClass()（iframe URL）被映射到
        //     iframe 子页视图，使浏览器侧 pick.framePath 始终为空。
        // 而 snap.entries 在外层已被 javaPickBySig（带 framePath backfill + 对话框/弹窗双保险）覆盖——
        // entries 含正确增强，steps 不含。生成时 step 循环用 st.picks、entry 循环用 snap.entries，
        // 字段名按 entries 的 locatorKey 匹配，但 switchToFrame 按 st.picks.getFramePath()——于是缺。
        // 此处按 locatorKey 把 entries 的 framePath/dialog/popup 回补到 steps 的 picks 上（idempotent，
        // 取非空/true 优先），保证生成时 step 能拿到与 Page 类字段一致的增强字段。
        if (snap.entries != null && !snap.entries.isEmpty() && snap.steps != null) {
                java.util.Map<String, RoleEntry> byLocKey = new java.util.HashMap<>();
                for (RoleEntry en : snap.entries) {
                    String lk = RoleElementPageGenerator.locatorKey(en);
                    if (lk != null && !lk.isEmpty()) byLocKey.put(lk, en);
                }
                if (!byLocKey.isEmpty()) {
                    List<StepRec> enrichedSteps = new ArrayList<>(snap.steps.size());
                    for (StepRec st : snap.steps) {
                        if (st == null) { enrichedSteps.add(st); continue; }
                        List<RoleEntry> enrichedPicks = new ArrayList<>(st.picks == null ? 0 : st.picks.size());
                        if (st.picks != null) for (RoleEntry p : st.picks) {
                            String lk = (p == null) ? "" : RoleElementPageGenerator.locatorKey(p);
                            RoleEntry mem = (lk.isEmpty()) ? null : byLocKey.get(lk);
                            if (mem != null && p != null) {
                                // javaPickBySig 是权威内存态：含点击后 checked/setCheckedTarget、framePath backfill、
                                // dialog/popup 双保险。RoleEntry 的 checked/setCheckedTarget 为 final 不可变，
                                // 无法 merge，故有增强差异时直接用 mem 替换 pick（保证 setCheckedTarget 是点击后状态，
                                // 否则点击"未勾选"checkbox 会因浏览器侧点击前状态生成 setChecked(false) 导致"没选择上"）。
                                if (needsReplaceByMemory(p, mem)) { p = mem; }
                            }
                            enrichedPicks.add(p);
                        }
                        enrichedSteps.add(new StepRec(st.pageClass, enrichedPicks));
                    }
                    snap = new PickSnapshot(snap.pageClass, snap.entries, enrichedSteps, snap.ops);
                }
            }
        String curClass = (snap.pageClass == null) ? "" : snap.pageClass;
        LinkedHashMap<String, List<RoleEntry>> entriesByPage = new LinkedHashMap<>();
        for (RoleEntry e : snap.entries) {
            String pc = (e.getPageClass() == null || e.getPageClass().isEmpty()) ? curClass : e.getPageClass();
            entriesByPage.computeIfAbsent(pc, k -> new ArrayList<>()).add(e);
        }
        // 生成前对账：steps 与 picks 是两条独立数据源，且 steps 在导航恢复/已关闭页缓存等路径上
        // 是「只增不减」地合并回来的，已删元素仍可能以"幽灵 pick"残留在某条 step 里。
        // 页面类字段只由 picks 生成，故此处按 locatorKey（与字段表同一套匹配口径）把
        // 在 entries 中已不存在的 pick 从 step 中剔除，并丢弃因此变空的 step。
        // 不这样做的话，代码生成侧会靠 field==null 静默 continue 跳过：编译能过，但动作凭空消失。
        java.util.Set<String> aliveKeys = new java.util.HashSet<>();
        for (RoleEntry e : snap.entries) {
            String lk = RoleElementPageGenerator.locatorKey(e);
            if (lk != null && !lk.isEmpty()) aliveKeys.add(lk);
        }
        int droppedPicks = 0, droppedSteps = 0;
        LinkedHashMap<String, List<List<RoleEntry>>> stepsByPage = new LinkedHashMap<>();
        if (snap.steps != null) for (StepRec st : snap.steps) {
            String pc = (st.pageClass == null || st.pageClass.isEmpty()) ? curClass : st.pageClass;
            List<RoleEntry> picks = st.picks;
            if (picks != null && !aliveKeys.isEmpty()) {
                List<RoleEntry> kept = new ArrayList<>(picks.size());
                for (RoleEntry e : picks) {
                    if (e == null) continue;
                    String lk = RoleElementPageGenerator.locatorKey(e);
                    // 键为空者无法对账，保守保留（生成侧仍有 field==null 兜底，不会产生悬空引用）。
                    if (lk == null || lk.isEmpty() || aliveKeys.contains(lk)) kept.add(e);
                    else {
                        droppedPicks++;
                        // 诊断：i18n/定位器型策略元素若因 locatorKey 不匹配被 drop（典型表现"步骤里完全没有这行"），
                        // 打印其 strategy/name/lk 与 aliveKeys 中同类键，便于定位 index(#0) 错位或字段不一致根因。
                        if (RolePickerConstants.STRATEGY_I18N.equals(e.getStrategy()) || (e.getStrategy() != null
                                && RoleElementPicker.LOCATOR_IDENTITY_STRATEGIES.contains(e.getStrategy()))) {
                            log.info("[picker][drop-diag] 步骤元素被对账剔除：strategy={}, name={}, lk={}, count={}, index={}, aliveKeys(i18n类)={}",
                                    e.getStrategy(), e.getName(), lk, e.getCount(), e.getIndex(),
                                    aliveKeys.stream().filter(k -> k != null && k.startsWith(e.getStrategy() + ":")).limit(10).collect(java.util.stream.Collectors.toList()));
                        }
                    }
                }
                picks = kept;
            }
            if (picks == null || picks.isEmpty()) {
                // 整条 step 的元素都已被删：不生成空的 @Step 方法。
                if (st.picks != null && !st.picks.isEmpty()) droppedSteps++;
                continue;
            }
            stepsByPage.computeIfAbsent(pc, k -> new ArrayList<>()).add(picks);
            entriesByPage.computeIfAbsent(pc, k -> new ArrayList<>());
        }
        if (droppedPicks > 0 || droppedSteps > 0) {
            log.info("[picker] 生成前对账：剔除已删元素引用 {} 处，丢弃空 step {} 条", droppedPicks, droppedSteps);
        }
        // 【关键修复"只点了 2 个元素却生成很多步骤"】
        // 旧逻辑曾在此"兜底"：把 javaPickBySig 中位于 iframe 内但未被任何 step 引用的元素补进最后一个 step，
        // 目的是修复"嵌套 iframe 元素不进 step"。但该前提在"整页扫描"引入后不再成立——扫描出的全部 iframe
        // 候选（40 个）也回传进入 javaPickBySig，与"用户真实点击"混在一起。用户只点了 2 个主框架元素时，
        // referencedKeys(2) < aliveKeys(40) 恒成立，兜底便把 36 个未入 step 的 iframe 扫描候选全补进 step，
        // 表现为"点 2 个元素、步骤却一大堆"。
        // 现用户点击的 iframe 元素经 postMessage（不带 __isScan）正常上送顶层进入 __currentStep/__steps，
        // 无需此兜底；故直接移除，让 step 只含用户真实点击/勾选的元素。主框架元素链路不受影响。
        LinkedHashMap<String, List<String>> opsByPage = new LinkedHashMap<>();
        if (snap.ops != null) for (PageOp op : snap.ops) {
            String pc = (op.pageClass == null || op.pageClass.isEmpty()) ? curClass : op.pageClass;
            opsByPage.computeIfAbsent(pc, k -> new ArrayList<>()).add(op.op);
        }
        if (stepsByPage.isEmpty() && opsByPage.isEmpty()) return out;
        return RoleElementStepGenerator.generatePerPage(stepsByPage, entriesByPage, opsByPage, packageName, stepClassName);
    }

    static void appendCloseOpStep(Page closed, String pageClass,
                                          Map<Page, String> snapshots) {
        try {
            String json = snapshots.get(closed);
            java.util.Map<String, Object> m = (json != null && !json.isEmpty())
                    ? GSON.fromJson(json, MAP_STRING_OBJECT_TYPE) : null;
            if (m == null) m = new java.util.LinkedHashMap<String, Object>();
            java.util.List<java.util.Map<String, Object>> steps;
            Object st = m.get("steps");
            if (st instanceof java.util.List) {
                @SuppressWarnings("unchecked")
                java.util.List<java.util.Map<String, Object>> tmp = (java.util.List<java.util.Map<String, Object>>) st;
                steps = tmp;
            } else {
                steps = new java.util.ArrayList<java.util.Map<String, Object>>();
                m.put("steps", steps);
            }
            // 去重：同页已存在相同 pageClass 的关闭 step 则不重复追加。
            boolean dup = false;
            for (Object s : steps) {
                if (s instanceof java.util.Map && pageClass.equals(((java.util.Map<?, ?>) s).get("pageClass"))
                        && hasClosePick((java.util.Map<?, ?>) s)) { dup = true; break; }
            }
            if (dup) return;
            java.util.Map<String, Object> step = new java.util.LinkedHashMap<String, Object>();
            step.put("pageClass", pageClass);
            java.util.List<java.util.Map<String, Object>> picks = new java.util.ArrayList<java.util.Map<String, Object>>();
            java.util.Map<String, Object> closePick = new java.util.LinkedHashMap<String, Object>();
            closePick.put("_closeOp", Boolean.TRUE);
            closePick.put("_pageClass", pageClass);
            closePick.put("_sig", "__close_" + CLOSE_SEQ.incrementAndGet());
            closePick.put("tag", "close");
            picks.add(closePick);
            step.put("picks", picks);
            steps.add(step);
            snapshots.put(closed, GSON.toJson(m));
        } catch (Exception ignore) { /* 缓存快照不可用：忽略，关闭步骤将缺失（极少见） */ }
    }

    static boolean hasClosePick(java.util.Map<?, ?> step) {
        Object ps = step.get("picks");
        if (ps instanceof java.util.List) {
            for (Object p : (java.util.List<?>) ps) {
                if (p instanceof java.util.Map && Boolean.TRUE.equals(((java.util.Map<?, ?>) p).get("_closeOp"))) {
                    return true;
                }
            }
        }
        return false;
    }

    static boolean needsReplaceByMemory(RoleEntry p, RoleEntry mem) {
        if (p == null || mem == null || p == mem) return false;
        // checked 差异（final 不可 merge）
        if (!java.util.Objects.equals(p.getChecked(), mem.getChecked())) return true;
        if (!java.util.Objects.equals(p.getSetCheckedTarget(), mem.getSetCheckedTarget())) return true;
        // framePath：p 缺、mem 有 → 替换；两者皆非空且相同则不必
        List<String> inFp = p.getFramePath();
        List<String> memFp = mem.getFramePath();
        boolean inEmpty = (inFp == null || inFp.isEmpty());
        boolean memEmpty = (memFp == null || memFp.isEmpty());
        if (inEmpty && !memEmpty) return true;
        if (!inEmpty && !memEmpty && !inFp.equals(memFp)) return true;
        // dialog
        if (!p.isDialog() && mem.isDialog()) return true;
        // popup
        if (!p.isPopup() && mem.isPopup()) return true;
        return false;
    }
}