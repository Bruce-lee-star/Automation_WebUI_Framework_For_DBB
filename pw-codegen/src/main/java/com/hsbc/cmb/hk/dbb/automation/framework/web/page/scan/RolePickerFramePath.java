package com.hsbc.cmb.hk.dbb.automation.framework.web.page.scan;

import com.microsoft.playwright.Frame;
import com.microsoft.playwright.Page;

import java.util.ArrayList;
import java.util.List;

/**
 * frame 嵌套路径计算助手：纯函数，入参为 Playwright 的 Page / Frame，输出自顶向下的 iframe 选择器链或嵌套深度。
 *
 * <p>供 {@link RoleElementPicker} 的跨 frame 聚合（getEntries）、上下文桥（registerContextBridges）
 * 与面板同步（mergeFramePicksToMain）共用，保证 Java 侧与浏览器侧 {@code __framePathOf} 标签形态一致。
 */
final class RolePickerFramePath {

    private RolePickerFramePath() {
    }

    /** Java 侧判定 id 是否可作为稳定定位锚（对应浏览器侧 isStableId：非纯自动生成序号即可）。 */
    private static boolean isStableIdJava(String id) {
        if (id == null || id.trim().isEmpty()) return false;
        String s = id.trim();
        // 纯数字 / 以数字结尾的自动生成 id（如 "id-1"、"auto123"）视为不稳定，其余视为稳定。
        if (s.matches(".*\\d+$") && !s.matches(".*[a-zA-Z].*")) return false;
        return true;
    }

    /** frame 嵌套深度：主框架为 0，其直接子 frame 为 1，frame 内嵌 frame 逐层 +1。用于 getEntries 深度降序遍历，
     *  保证最深层 frame 的拾取先入聚合结果，浅层副本被去重跳过，framePath 始终最完整。 */
    static int frameDepth(Frame fr) {
        int d = 0;
        try {
            Frame cur = fr;
            while (cur != null && cur.parentFrame() != null) {
                d++;
                cur = cur.parentFrame();
            }
        } catch (Exception ignore) {}
        return d;
    }

    /** 计算某 frame 的嵌套路径（自顶向下），主框架返回 null；优先 name / 稳定 id / src 片段 / 退化为 iframe:nth-of-type。
     *  主框架判定改用 page.mainFrame()（file:// 等场景下 iframe 的 parentFrame() 可能误返回 null，
     *  用 mainFrame 参照可稳定区分顶层与嵌套 frame）。
     *  标签形态与浏览器侧 __framePathOf 保持一致，供 RoleElementStepGenerator.frameNameOf 通用解析。 */
    static List<String> computeFramePath(Page page, Frame fr) {
        try {
            if (fr == null || fr == page.mainFrame()) return null;   // 主框架
            List<String> path = new ArrayList<>();
            Frame cur = fr;
            while (cur != null && cur != page.mainFrame()) {
                Frame parent = cur.parentFrame();
                try {
                    com.microsoft.playwright.ElementHandle fe = cur.frameElement();
                    String sel = null;
                    String nm = fe.getAttribute("name");
                    if (nm != null && !nm.trim().isEmpty()) sel = "iframe[name=\"" + nm.trim() + "\"]";
                    if (sel == null) {
                        String id = fe.getAttribute("id");
                        if (id != null && !id.trim().isEmpty() && isStableIdJava(id)) sel = "#" + id.trim();
                    }
                    if (sel == null) {
                        // 无 name / 无稳定 id：用 src 路径片段作为稳定标签（双通道：page.frame 兜底 + CSS frameLocator）
                        String src = fe.getAttribute("src");
                        if (src != null && !src.trim().isEmpty()) {
                            String u = src.trim();
                            int cut = u.indexOf("//");
                            String frag = cut >= 0 ? u.substring(u.indexOf('/', cut + 2)) : u;
                            sel = "iframe[src*=\"" + frag + "\"]";
                        }
                    }
                    if (sel == null) sel = "iframe";
                    path.add(0, sel);
                } catch (Exception ignore) {
                    path.add(0, "iframe");
                }
                cur = parent;
            }
            return path.isEmpty() ? null : path;
        } catch (Exception e) {
            return null;
        }
    }
}
