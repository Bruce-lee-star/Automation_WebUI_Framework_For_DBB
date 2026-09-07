package com.hsbc.cmb.hk.dbb.automation.framework.web.page.scan;

import com.microsoft.playwright.Page;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.hsbc.cmb.hk.dbb.automation.framework.web.page.scan.RoleElementPicker.PickSnapshot;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.scan.RoleElementPicker.StepRec;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.scan.RoleElementPicker.PageOp;

// 行为保持零回归的桥接：StepRec/PageOp/PickSnapshot 仍留在 RoleElementPicker（runPickerCommand 也依赖），
// asString 亦留在 RoleElementPicker（被 runPickerCommand 共用）；此处显式桥接以保持原调用形态。
import static com.hsbc.cmb.hk.dbb.automation.framework.web.page.scan.RoleElementPicker.asString;

/**
 * 快照解析簇：把浏览器侧拾取态（window.__rolePicks/__steps/__ops）读回并解析为 Java 侧的
 * {@link PickSnapshot}/{@link StepRec}/{@link PageOp}，供 {@code runPickerCommand} 与公开 API
 * （{@code getSteps} 等）复用。该逻辑原属 {@link RoleElementPicker}（readPickSnapshot/stopAndRead/
 * parsePickSnapshot/getPageOpsWithPage/getStepsWithPage，约 155 行），按 T5-1「按 扫描/定位/缓存 拆子模块」
 * 抽为独立簇类，公开行为不变。
 */
public final class RolePickerSnapshotParser {

    static PickSnapshot readPickSnapshot(Page page) {
        return parsePickSnapshot(page.evaluate(RolePickerScripts.PICK_STATE_READER_JS));
    }

    /** "停止"命令专用：单次 {@code page.evaluate} 同时完成「去激活 + 收尾当前 step + 读回全部拾取态」，
     *  把原本 stop()（1 次）+ 收尾 evaluate（1 次）+ readPickSnapshot（1 次）三次往返合并为 1 次，
     *  点击"停止"即时得到快照并转交代码生成，不再串行等待多次 Java↔浏览器往返（企业级：减少关键路径往返）。 */
    @SuppressWarnings("unchecked")
    static PickSnapshot stopAndRead(Page page) {
        // 与 stop() 一致：先清除会话开关（阻断门控注入脚本在后续新文档自启拾取），
        // 再去激活 + 收尾 + 读回，全部合并进同一次 evaluate（最后一个表达式的值即快照）。
        return parsePickSnapshot(page.evaluate(
                RolePickerScripts.STOP_SESSION_ON_JS + RolePickerScripts.STOP_SCRIPT + ";" + RolePickerScripts.PICK_STATE_READER_JS));
    }

    /** 把 {@code page.evaluate} 返回的拾取态对象解析为 {@link PickSnapshot}（容错：非 Map 返回空快照）。 */
    @SuppressWarnings("unchecked")
    static PickSnapshot parsePickSnapshot(Object raw) {
        List<RoleEntry> entries = new ArrayList<>();
        List<StepRec> steps = new ArrayList<>();
        List<PageOp> ops = new ArrayList<>();
        String pageClass = "";
        if (raw instanceof Map) {
            Map<Object, Object> m = (Map<Object, Object>) raw;
            pageClass = asString(m.get("pageClass"));
            Object p = m.get("picks");
            if (p instanceof List) {
                java.util.Set<String> seenKeys = new java.util.HashSet<>();
                for (Object o : (List<Object>) p) {
                    if (!(o instanceof Map)) continue;
                    Map<Object, Object> om = (Map<Object, Object>) o;
                    RoleEntry e = RolePickerPickParser.parsePick(om);
                    if (e == null) continue;
                    // 生成链路兜底去重：与 getEntries / PICK_STATE_READER 同口径，
                    // 保证无论浏览器侧 window.__rolePicks 因何种竞态累积了重复副本，生成的页面类都不会出现重复字段。
                    String dk = RolePickerPickParser.pickDedupKey(om, e);
                    if (!dk.isEmpty() && !seenKeys.add(dk)) continue;
                    entries.add(e);
                }
            }
            Object st = m.get("steps");
            if (st instanceof List) for (Object o : (List<Object>) st) {
                if (!(o instanceof Map)) continue;
                Map<Object, Object> sm = (Map<Object, Object>) o;
                String pc = asString(sm.get("pageClass"));
                List<RoleEntry> sp = new ArrayList<>();
                Object ps = sm.get("picks");
                if (ps instanceof List) for (Object it : (List<Object>) ps)
                    if (it instanceof Map) { RoleEntry e = RolePickerPickParser.parsePick((Map<Object, Object>) it); if (e != null) sp.add(e); }
                steps.add(new StepRec(pc, sp));
            }
            Object op = m.get("ops");
            if (op instanceof List) for (Object o : (List<Object>) op) {
                if (!(o instanceof Map)) continue;
                Map<Object, Object> om = (Map<Object, Object>) o;
                String oop = asString(om.get("op"));
                String opc = asString(om.get("pageClass"));
                if (oop != null && !oop.isEmpty()) ops.add(new PageOp(opc, oop));
            }
        }
        return new PickSnapshot(pageClass, entries, steps, ops);
    }

    /**
     * 读取当前已登记的「页面级操作」序列（如关闭页面），供代码生成器产出 closeCurrentPage() 等步骤。
     * 与 {@link #getStepsWithPage} 互补：元素 step 走 getStepsWithPage，页面操作 step 走本方法。
     */
    @SuppressWarnings("unchecked")
    static List<PageOp> getPageOpsWithPage(Page page) {
        List<PageOp> result = new ArrayList<>();
        Object raw = page.evaluate(RolePickerScripts.READ_OPS_JS);
        if (raw instanceof List) {
            for (Object o : (List<Object>) raw) {
                if (!(o instanceof Map)) continue;
                Map<Object, Object> m = (Map<Object, Object>) o;
                String op = asString(m.get("op"));
                String pc = asString(m.get("pageClass"));
                if (op != null && !op.isEmpty()) result.add(new PageOp(pc, op));
            }
        }
        return result;
    }

    /**
     * 读取 step 序列并保留每条 step 的所属页面类（用于多页面代码生成按页分组）。
     * 兼容两种格式：旧格式（数组的数组）缺 pageClass（归到当前页），新格式（{pageClass, picks}）。
     */
    @SuppressWarnings("unchecked")
    static List<StepRec> getStepsWithPage(Page page) {
        List<StepRec> result = new ArrayList<>();
        // 在浏览器内把两种格式归一为 {pageClass, picks}；picks 仍是原始 pick 对象数组。
        // 过滤掉"页面级操作"step（含 op 字段，如关闭页面），它们由 getPageOpsWithPage 单独处理，
        // 否则会被当成"空 pick 的 step"生成无意义方法。
        Object raw = page.evaluate(RolePickerScripts.READ_STEPS_WITH_PAGE_JS);
        if (raw instanceof List) {
            for (Object o : (List<Object>) raw) {
                if (!(o instanceof Map)) continue;
                Map<Object, Object> m = (Map<Object, Object>) o;
                String pc = asString(m.get("pageClass"));
                List<RoleEntry> picks = new ArrayList<>();
                java.util.Set<String> seenKeys = new java.util.HashSet<>();
                Object ps = m.get("picks");
                if (ps instanceof List) {
                    for (Object item : (List<Object>) ps) {
                        if (!(item instanceof Map)) continue;
                        Map<Object, Object> itemMap = (Map<Object, Object>) item;
                        RoleEntry e = RolePickerPickParser.parsePick(itemMap);
                        if (e == null) continue;
                        // 二次兜底去重：浏览器侧读取层已按 __mergeKey 压缩，这里再用 Java 侧权威键
                        // pickDedupKey 过滤，保证无论哪条同步/恢复路径遗漏，生成的页面类字段不会重复。
                        String dk = RolePickerPickParser.pickDedupKey(itemMap, e);
                        if (!dk.isEmpty() && !seenKeys.add(dk)) continue;
                        picks.add(e);
                    }
                }
                result.add(new StepRec(pc, picks));
            }
        }
        return result;
    }
}
