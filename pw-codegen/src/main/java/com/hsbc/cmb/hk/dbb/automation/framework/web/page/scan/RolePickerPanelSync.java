package com.hsbc.cmb.hk.dbb.automation.framework.web.page.scan;

import com.google.gson.Gson;
import com.microsoft.playwright.BrowserContext;
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
     * CT2-20：释放指定 Page 的同步签名缓存（页面关闭 / 拾取会话结束）。
     *
     * <p>{@code LAST_SYNC_SIG} 以 {@link Page} <b>强引用</b>为键，而清理点此前全仓唯一
     * （{@code RolePickerCommandEngine} 重编号后为强制刷新 ETag 而 remove）——
     * 普通拾取会话的页面<b>永不被移除</b>，长跑套件下按 Page 无界增长并阻止已关闭 Page 回收。
     */
    static void cleanupPage(Page page) {
        if (page != null) {
            LAST_SYNC_SIG.remove(page);
        }
    }

    /** CT2-20：释放属于指定 Context 的全部同步签名缓存。 */
    static void cleanupContext(BrowserContext ctx) {
        if (ctx == null) {
            return;
        }
        LAST_SYNC_SIG.keySet().removeIf(p -> {
            if (p == null) {
                return true;
            }
            try {
                return p.context() == ctx;
            } catch (Exception ignore) {
                return true; // 页面已关闭，保守清理
            }
        });
    }

    /** CT2-20：清空全部同步签名缓存（JVM 关闭 / 集群重置）。 */
    static void clearAll() {
        LAST_SYNC_SIG.clear();
    }

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
                                                } catch (Exception ignore) { RolePickerQuiet.ignore("RolePickerPanelSync", ignore); }
                                            }
                                            String key = RolePickerPickParser.pickDedupKey(m, e);
                                            if (key != null && !key.isEmpty()) {
                                                //  N-18：此处原有「会话级已删集合（STATE_DELETED）命中即跳过」的分支，
                                                //    自删除语义改为「仅从当前拾取列表移除、允许重新拾取」后该集合已无写入点，
                                                //    判定恒为 false —— 纯死分支，已连同那个死状态一起删除。
                                                //    留着它的唯一效果是让人误以为「已删元素复活」已被防住。
                                                RolePickerPickParser.mergePickIntoMap(javaPickBySig, key, e);
                                            }
                                        } catch (Exception ignore) { RolePickerQuiet.ignore("RolePickerPanelSync", ignore); }
                                    }
                                }
                            }
                        } catch (Exception ignore) { RolePickerQuiet.ignore("RolePickerPanelSync", ignore); }
                    }
                }
            } catch (Exception fe) {
                try {
                    String feMsg = fe.getMessage() == null ? "" : fe.getMessage();
                    if (feMsg.contains("closed") || feMsg.contains("detached")
                            || feMsg.contains("TargetClosed") || feMsg.contains("Target page")) {
                        log.debug("[picker] skipping closed/detached iframe (url={}): {}", f.url(), feMsg);
                    } else {
                        log.warn("[picker] failed to merge iframe picks into the main frame (url={}): {}", f.url(), feMsg);
                    }
                } catch (Exception ignore) { RolePickerQuiet.ignore("RolePickerPanelSync", ignore); }
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
        //  N-20（doc 21 MEDIUM）：快照必须【在锁内完成对可变 RoleEntry 的全部读取】。
        //  原实现只在锁内拷了 values() 的【引用】（浅快照），随后在锁外读 getPageClass / getSigKey /
        //  getStrategy / getSelector / getIndex / getPickNos，并执行 GSON.toJson(filtered)（会遍历
        //  RoleEntry 的全部字段）、还要打印 diag 日志 —— 而写方（派发线程）正是在 synchronized(state)
        //  内改结构并（如 setFramePath）改字段：读方在锁外读这些可变字段属数据竞态，可能观测到撕裂 /
        //  半更新状态（getPickNos 还可能返回正在被修改的集合）。
        //  现把「过滤 + ETag 签名 + JSON 序列化 + diag 日志」整体移入同一把锁；锁【不】延伸到
        //  page.evaluate 阻塞调用与 CHM 读写，避免把网络等待带进临界区。
        StringBuilder sig = new StringBuilder();
        sig.append(pageClasses == null ? "*" : pageClasses.toString());
        List<RoleEntry> filtered = new ArrayList<>();
        String json;
        synchronized (state) {
            for (RoleEntry e : state.values()) {
                String pc = e.getPageClass();
                if (pageClasses != null && pc != null && !pc.isEmpty() && !pageClasses.contains(pc)) {
                    continue;
                }
                filtered.add(e);
                sig.append('\u0001').append(e.getSigKey()).append('|')
                   .append(e.getStrategy()).append('|').append(e.getSelector())
                   .append('|').append(e.getIndex())
                   .append('|').append(e.getPickNos() == null ? "" : e.getPickNos());
            }
            json = GSON.toJson(filtered);
            for (RoleEntry e : filtered) {
                Map<Object, Object> keySrc = new LinkedHashMap<>();
                keySrc.put("_sigKey", e.getSigKey());
                keySrc.put("_pageClass", e.getPageClass());
                log.info("[picker][diag-sync] write-back key={} sigKey={} strategy={} pickNos={}",
                        RolePickerPickParser.pickDedupKey(keySrc, e), e.getSigKey(), e.getStrategy(), e.getPickNos());
            }
        }
        try {
            String newSig = sig.toString();
            String prev = LAST_SYNC_SIG.get(page);
            if (newSig.equals(prev)) return;
            LAST_SYNC_SIG.put(page, newSig);
            String delJson = "[]";
            String syncJsonB64 = java.util.Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            String syncDelB64 = java.util.Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(delJson.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            pickerEval(page, RolePickerScripts.SYNC_PANEL_TO_BROWSER_JS,
                    java.util.Arrays.asList(syncJsonB64, syncDelB64, overwriteNos));
        } catch (Exception syncE) {
            try { log.warn("[picker] failed to sync the panel to the browser: {}", syncE.getMessage()); } catch (Exception ignore) { RolePickerQuiet.ignore("RolePickerPanelSync", ignore); }
        }
    }

}
