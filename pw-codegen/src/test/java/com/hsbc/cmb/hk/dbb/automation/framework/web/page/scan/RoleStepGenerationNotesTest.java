package com.hsbc.cmb.hk.dbb.automation.framework.web.page.scan;

import com.hsbc.cmb.hk.dbb.automation.framework.web.page.scan.model.PickSnapshot;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.scan.model.StepRec;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 步骤生成的「可观测性注记」契约（{@link RoleElementStepGenerator.ViewNotes} +
 * {@code RolePickerCodeAssembler.buildStepCode}）。
 *
 * <p>锁定两类"面板看着对、产物看着不对"的误读来源，避免它们被当成生成器 bug 反复排查：
 * <ol>
 *   <li><b>面板全局序号 vs 本页局部编号</b>：每份按页视图的 {@code stepN} 从 1 重新计数
 *       （Java 方法名只在类内唯一，多页时必然如此），故产物里 {@code step1} 可能对应"面板第 2 个封装"；</li>
 *   <li><b>生成前对账丢弃</b>：面板某条 step 仍引用着已被删除的元素时，该 pick 会被剔除
 *       （症状＝产物"凭空少一行"），必须在产物里留下注记，而不是只在日志里 INFO 一行。</li>
 * </ol>
 *
 * <p>纯离线用例：{@code buildStepCode} → {@code generatePerPage} 全链路无浏览器依赖，且本模块无 Mockito，
 * 沿用「真实 {@code RoleEntry} 夹具」的写法。
 */
public class RoleStepGenerationNotesTest {

    private static final String PAGE = "LoginPage";
    private static final String PKG = "com.example.steps";

    /**
     * 构造一个 locatorKey 稳定可对账的元素：{@code RoleEntry(role, name, tag, level, resolvedKey)}
     * 会把 strategy 置为角色策略，故 {@code locatorKey} = {@code role:button:<key>}。
     */
    private static RoleEntry entry(String name, String key) {
        return new RoleEntry("button", name, "button", 1, key);
    }

    /** 给元素标注拾取序号（面板序号语义）。不调用即等价于"面板上无序号元素"。 */
    private static RoleEntry pick(RoleEntry e, int... nos) {
        List<Integer> l = new ArrayList<>();
        for (int n : nos) {
            l.add(n);
        }
        e.setPickNos(l);
        return e;
    }

    private static String generate(List<RoleEntry> entries, List<StepRec> steps) {
        PickSnapshot snap = new PickSnapshot(PAGE, entries, steps, new ArrayList<>());
        LinkedHashMap<String, String> out = RolePickerCodeAssembler.buildStepCode(snap, PKG, "DemoSteps");
        assertTrue("应生成该页的按页视图，实际键=" + out.keySet(), out.containsKey(PAGE));
        return out.get(PAGE);
    }

    /** 只取 step 方法体之后的部分，避免命中类顶部字段声明里的同名标识。 */
    private static String afterFirstStep(String src) {
        int i = src.indexOf("public void step1()");
        assertTrue("未生成 step1 方法：" + src, i > 0);
        return src.substring(i);
    }

    private static int count(String s, String needle) {
        int n = 0;
        int i = s.indexOf(needle);
        while (i >= 0) {
            n++;
            i = s.indexOf(needle, i + needle.length());
        }
        return n;
    }

    @Test
    // @DisplayName: "无丢弃时 stepN 前的注记 = 面板全局封装序号，且单页不出现局部编号差异说明"
    public void panelIndexNoteMatchesPanelOrder() {
        RoleEntry a = entry("A", "k_a");
        RoleEntry b = entry("B", "k_b");
        String src = generate(Arrays.asList(a, b), Arrays.asList(
                new StepRec(PAGE, Collections.singletonList(pick(a, 1))),
                new StepRec(PAGE, Collections.singletonList(pick(b, 2)))));

        assertTrue(src, src.contains("// 面板第 1 个「封装为步骤」"));
        assertTrue(src, src.contains("// 面板第 2 个「封装为步骤」"));
        assertTrue(src, src.contains("public void step1()"));
        assertTrue(src, src.contains("public void step2()"));
        assertFalse("单页视图整体序号应与面板一致，不应出现局部编号差异说明：" + src, src.contains("本页视图内为"));
    }

    @Test
    // @DisplayName: "面板第 1 个封装整条被对账丢弃时，产物只剩 step1 且注记标明它其实是面板第 2 个"
    public void panelIndexSurvivesFullyDroppedStep() {
        RoleEntry a = entry("A", "k_a");
        RoleEntry ghost = entry("Ghost", "k_ghost"); // 不在 entries 中 ⇒ 对账丢弃
        String src = generate(Collections.singletonList(a), Arrays.asList(
                new StepRec(PAGE, Collections.singletonList(pick(ghost, 1))),
                new StepRec(PAGE, Collections.singletonList(pick(a, 2)))));

        assertTrue("注记必须标明真实的面板序号与局部编号之差：" + src,
                src.contains("// 面板第 2 个「封装为步骤」（本页视图内为 step1）"));
        assertTrue(src, src.contains("public void step1()"));
        assertFalse("面板第 1 个封装的元素已全删，不应生成 step2：" + src, src.contains("public void step2()"));
    }

    @Test
    // @DisplayName: "step 内单个 pick 被对账丢弃时，其余操作保留，且产物里留下被丢弃引用的注记"
    public void droppedPickIsNotedAndStepSurvives() {
        RoleEntry a = entry("A", "k_a");
        RoleEntry ghost = entry("Ghost", "k_ghost");
        RoleEntry b = entry("B", "k_b");
        String src = generate(Arrays.asList(a, b), Collections.singletonList(
                new StepRec(PAGE, Arrays.asList(pick(a, 1), pick(ghost, 2), pick(b, 3)))));

        assertTrue(src, src.contains("public void step1()"));
        assertEquals("丢弃的 pick 不应产生操作（应只剩 A/B 两次 click）：" + src,
                2, count(afterFirstStep(src), ".click()"));
        assertTrue("产物必须留注记，免翻日志：" + src, src.contains("生成前对账丢弃了 1 个已删除元素的引用"));
        assertTrue("注记应含被丢弃元素的定位键：" + src, src.contains("role:button:k_ghost"));
        assertFalse("注记不应把仍存活的元素也列进去：" + src, src.contains("role:button:k_a"));
    }

    @Test
    // @DisplayName: "无拾取序号的元素在步骤内垫底（与面板占位号 2147483647 的语义一致）"
    public void elementWithoutPickNosSortsLast() {
        RoleEntry late = entry("Late", "k_late");   // 无 pickNos ⇔ 面板上的"无序号元素"
        RoleEntry first = entry("First", "k_first");

        // 字段名由生成器分配，按 locatorKey 反查才能定位到具体操作行。
        String fLate = null;
        String fFirst = null;
        for (RoleElementPageGenerator.GeneratedField f : RoleElementPageGenerator.assignFields(Arrays.asList(late, first))) {
            String lk = RoleElementPageGenerator.locatorKey(f.entry);
            if (lk != null && lk.endsWith(":k_late")) {
                fLate = f.fieldName;
            } else if (lk != null && lk.endsWith(":k_first")) {
                fFirst = f.fieldName;
            }
        }
        assertTrue("未解析出 k_late 的字段名", fLate != null && !fLate.isEmpty());
        assertTrue("未解析出 k_first 的字段名", fFirst != null && !fFirst.isEmpty());

        // 故意把"无序号元素"排在列表最前，验证生成侧不会跟着它走。
        String src = generate(Arrays.asList(late, first),
                Collections.singletonList(new StepRec(PAGE, Arrays.asList(late, pick(first, 1)))));

        String body = afterFirstStep(src);
        int iFirst = body.indexOf("." + fFirst + ".");
        int iLate = body.indexOf("." + fLate + ".");
        assertTrue("两个元素的操作都应在 step1 内：" + body, iFirst >= 0 && iLate >= 0);
        assertTrue("无序号元素必须垫底（有号元素在前），实际 first@" + iFirst + " late@" + iLate + "：\n" + body,
                iFirst < iLate);
    }
}
