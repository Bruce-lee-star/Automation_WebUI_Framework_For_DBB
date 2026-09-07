package com.hsbc.cmb.hk.dbb.automation.framework.web.page.scan;

import com.google.gson.Gson;
import com.microsoft.playwright.Frame;
import com.microsoft.playwright.Page;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import static com.hsbc.cmb.hk.dbb.automation.framework.web.page.scan.RoleElementPicker.pickerEval;

/**
 * 面板同步引擎：把 Java 权威拾取内存态（javaPickBySig）合并 iframe 拾取、回灌浏览器面板，
 * 并维护按 Page 的 ETag 签名缓存以短路静止期同步。
 * 从 {@link RoleElementPicker} 抽出，方法体原样迁移，行为零变更。
 */
final class RolePickerPanelSync {

    private static final Logger log = LoggerFactory.getLogger(RolePickerPanelSync.class);

    /** 与 RoleElementPicker 同语义的 JSON 序列化器（Gson 无状态，独立实例行为等价）。 */
    private static final Gson GSON = new Gson();

    // O2：syncPanelToBrowser 的 ETag 缓存——按 Page 记录上次同步的内容签名，未变则跳过整轮同步。
    static final Map<Page, String> LAST_SYNC_SIG = new ConcurrentHashMap<>();

    private RolePickerPanelSync() {}

    /**
     * 【关键修复"整页/区域扫描后 iframe 内元素不进面板"】
     * 由 Java 侧遍历 page.frames()，读回各 iframe 自己的 window.__rolePicks（Playwright 协议访问不受
     * file:// 跨源限制），按 sigKey 去重后显式合并进【主框架】window.__rolePicks 并触发渲染，使面板与
     * readPickSnapshot（读主框架）都能看到 iframe 内元素。整页扫描与区域扫描共用。
     */
    static void mergeFramePicksToMain(Page page, LinkedHashMap<String, RoleEntry> javaPickBySig) {
        if (page == null || page.isClosed()) return;
        for (Frame f : page.frames()) {
            if (f == null || f.equals(page.mainFrame())) continue;
            try {
                Object frameJson = pickerEval(f, RolePickerScripts.READ_FRAME_PICKS_RAW_JS);
                if (frameJson instanceof String) {
                    final String json = (String) frameJson;
                    if (!json.isEmpty() && !"[]".equals(json.trim())) {
                        try {
                            @SuppressWarnings("unchecked")
                            List<?> arr = GSON.fromJson(json, List.class);
                            if (arr != null) {
                                synchronized (javaPickBySig) {
                                    for (Object item : arr) {
                                        try {
                                            if (!(item instanceof Map)) continue;
                                            @SuppressWarnings("unchecked")
                                            Map<Object, Object> m = (Map<Object, Object>) item;
                                            String strat = RoleElementPicker.asString(m.get("strategy"));
                                            String nm = RoleElementPicker.asString(m.get("name"));
                                            if (RolePickerConstants.STRATEGY_TEXT.equals(strat) && nm != null && nm.length() >= 25) continue;
                                            RoleEntry e = RolePickerPickParser.parsePick(m);
                                            if (e == null) continue;
                                            if (e.getFramePath() == null || e.getFramePath().isEmpty()) {
                                                try {
                                                    List<String> fp = RolePickerFramePath.computeFramePath(page, f);
                                                    if (fp != null && !fp.isEmpty()) e.setFramePath(fp);
                                                } catch (Exception ignore) {}
                                            }
                                            String key = RolePickerPickParser.pickDedupKey(m, e);
                                            if (key != null && !key.isEmpty()) {
                                                if (isDeletedKeyInState(javaPickBySig, key, e, m)) continue;
                                                RolePickerPickParser.mergePickIntoMap(javaPickBySig, key, e);
                                            }
                                        } catch (Exception ignore) {}
                                    }
                                }
                            }
                        } catch (Exception ignore) {}
                    }
                }
            } catch (Exception fe) {
                try {
                    String feMsg = fe.getMessage() == null ? "" : fe.getMessage();
                    if (feMsg.contains("closed") || feMsg.contains("detached")
                            || feMsg.contains("TargetClosed") || feMsg.contains("Target page")) {
                        log.debug("[picker] 跳过已关闭/分离的 iframe（url={}）：{}", f.url(), feMsg);
                    } else {
                        log.warn("[picker] 合并 iframe 拾取到主框架失败（url={}）：{}", f.url(), feMsg);
                    }
                } catch (Exception ignore) {}
            }
        }
    }

    /**
     * 把 Java 权威拾取内存态（javaPickBySig）按目标页 pageClass 过滤后同步到该页浏览器面板展示数组
     * window.__rolePicks 并触发渲染。
     * 修复"当前跟随页(current[0])不是用户正在点击的页时，那个页面的面板空白、看不到已拾元素"：
     * 改为对每个被跟踪页面分别同步（调用处遍历 pageNames），使任一页面的面板都能实时反映 Java 侧已拾内容。
     * 仅用于面板展示；代码生成仍走 javaPickBySig（见 runPickerCommand），不受影响。
     * 注意：不再清空 window.__rolePickSigs，避免干扰浏览器端真实点击的去重计数。
     */
    static void syncPanelToBrowser(Page page, LinkedHashSet<String> pageClasses, LinkedHashMap<String, RoleEntry> state) {
        syncPanelToBrowser(page, pageClasses, state, false);
    }

    /**
     * 把 Java 权威内存态回灌浏览器侧 __rolePicks。
     * @param overwriteNos true=用户经面板显式编辑序号（repickNos）后调用，整体覆盖浏览器侧旧 _pickNos，
     *                     不与其并集（避免"旧序号被并回"导致编辑序号不生效）。
     *                     false=常规拾取回传同步，保留浏览器侧更长 _pickNos 以修复 i18n 并发回传丢号竞态。
     */
    static void syncPanelToBrowser(Page page, LinkedHashSet<String> pageClasses, LinkedHashMap<String, RoleEntry> state, boolean overwriteNos) {
        if (page == null || page.isClosed() || state == null) return;
        // 读者侧加锁前快照：state（即 javaPickBySig）写入方在 synchronized(state) 内结构修改，本读端此前未取锁即
        // 两次遍历 state.values()，与派发线程并发写入存在 ConcurrentModificationException 风险。此处加同一把锁取
        // 不可变快照，后续基于快照构建 ETag 签名与过滤列表，锁不延伸到 page.evaluate 阻塞调用。
        List<RoleEntry> snap;
        synchronized (state) { snap = new ArrayList<>(state.values()); }
        try {
            StringBuilder sig = new StringBuilder();
            sig.append(pageClasses == null ? "*" : pageClasses.toString());
            for (RoleEntry e : snap) {
                String pc = e.getPageClass();
                if (pageClasses == null || pc == null || pc.isEmpty() || pageClasses.contains(pc)) {
                    sig.append('\u0001').append(e.getSigKey()).append('|')
                       .append(e.getStrategy()).append('|').append(e.getSelector())
                       .append('|').append(e.getIndex())
                       .append('|').append(e.getPickNos() == null ? "" : e.getPickNos());
                }
            }
            String newSig = sig.toString();
            String prev = LAST_SYNC_SIG.get(page);
            if (newSig.equals(prev)) return;
            LAST_SYNC_SIG.put(page, newSig);
            List<RoleEntry> filtered = new ArrayList<>();
            for (RoleEntry e : snap) {
                String pc = e.getPageClass();
                if (pageClasses == null || pc == null || pc.isEmpty() || pageClasses.contains(pc)) filtered.add(e);
            }
            String json = GSON.toJson(filtered);
            for (RoleEntry e : filtered) {
                log.info("[picker][diag-sync] write-back key={} sigKey={} strategy={} pickNos={}", RolePickerPickParser.pickDedupKey(new LinkedHashMap<Object, Object>() {{
                    put("_sigKey", e.getSigKey());
                    put("_pageClass", e.getPageClass());
                }}, e), e.getSigKey(), e.getStrategy(), e.getPickNos());
            }
            String delJson = "[]";
            String syncJsonB64 = java.util.Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            String syncDelB64 = java.util.Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(delJson.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            pickerEval(page, RolePickerScripts.SYNC_PANEL_TO_BROWSER_JS,
                    java.util.Arrays.asList(syncJsonB64, syncDelB64, overwriteNos));
        } catch (Exception syncE) {
            try { log.warn("[picker] 同步面板到浏览器失败：{}", syncE.getMessage()); } catch (Exception ignore) {}
        }
    }

    /**
     * 判断某 iframe 元素是否已被用户删除（命中会话级已删集合 RolePickerSessionState.STATE_DELETED）。
     * 删除时 collectDeleteKeys 会把多种键形态都记入 dead 集合（pickDedupKey key / _sig / 去索引 _sig /
     * _sigKey / RoleEntry.sigKey），而这里若只比对单一 key 可能漏命中 → iframe 残留元素经
     * mergeFramePicksToMain 复活。故把与删除同口径的候选键全部拿去比对，任一命中即视为已删。
     */
    static boolean isDeletedKeyInState(LinkedHashMap<String, RoleEntry> map, String key,
                                       RoleEntry e, Map<Object, Object> m) {
        try {
            Set<String> dead = RolePickerSessionState.STATE_DELETED.get(map);
            if (dead == null || dead.isEmpty()) return false;
            if (key != null && !key.isEmpty() && dead.contains(key)) return true;
            if (e != null && e.getSigKey() != null && dead.contains(e.getSigKey())) return true;
            if (m != null) {
                Object sig = m.get("_sig");
                Object pcObj = m.get("_pageClass");
                String pcStr = (pcObj != null && !String.valueOf(pcObj).isEmpty())
                        ? String.valueOf(pcObj) : (e != null && e.getPageClass() != null ? e.getPageClass() : "");
                if (sig != null) {
                    String sigPc = pcStr + "|" + String.valueOf(sig);
                    if (dead.contains(sigPc)) return true;
                }
                Object sk = m.get("_sigKey");
                if (sk != null && dead.contains(String.valueOf(sk))) return true;
            }
            return false;
        } catch (Exception ignore) {
            return false;
        }
    }
}
