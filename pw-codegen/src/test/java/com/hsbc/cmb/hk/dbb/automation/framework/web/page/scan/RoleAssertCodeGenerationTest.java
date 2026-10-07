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
 * 断言类生成契约（{@code RolePickerCodeAssembler.buildAssertCode}）。
 *
 * <p>断言与步骤<b>同源</b>（都由 {@code snap.steps} 派生），因此面板「断言」Tab 的顺序必须与
 * 面板「封装为步骤」的先后一致；且同一元素在一次封装内只断言一次（可见性断言重复无意义）。
 * 本用例锁定三件事：
 * <ol>
 *   <li>同一次封装内，断言行按拾取序号（pickNos）升序，与步骤内的操作顺序一致；</li>
 *   <li>多次封装的 method 编号（assertStepN）严格按面板封装先后；</li>
 *   <li>跨页元素<b>不得</b>被静默丢弃（否则「断言比步骤少行」，表现为"顺序/数量对不上"）。</li>
 * </ol>
 */
public class RoleAssertCodeGenerationTest {

    private static final String PAGE = "LoginPage";
    private static final String OTHER = "PopupPage";
    private static final String PKG = "com.example.steps";

    private static RoleEntry entry(String name, String key) {
        return new RoleEntry("button", name, "button", 1, key);
    }

    private static RoleEntry pick(RoleEntry e, int... nos) {
        List<Integer> l = new ArrayList<>();
        for (int n : nos) {
            l.add(n);
        }
        e.setPickNos(l);
        return e;
    }

    private static LinkedHashMap<String, String> assertCode(List<RoleEntry> entries, List<StepRec> steps) {
        PickSnapshot snap = new PickSnapshot(PAGE, entries, steps, new ArrayList<>());
        return RolePickerCodeAssembler.buildAssertCode(snap, PKG);
    }

    /** 断言语句按出现顺序抽出被断言的元素（用生成器实际分配/查询到的字段名匹配）。 */
    private static List<String> assertedFields(String src, List<RoleEntry> entries) {
        LinkedHashMap<String, String> keyToField = new LinkedHashMap<>();
        for (RoleElementPageGenerator.GeneratedField f : RoleElementPageGenerator.assignFields(entries)) {
            keyToField.put(RoleElementPageGenerator.locatorKey(f.entry), f.fieldName);
        }
        List<int[]> hits = new ArrayList<>();
        List<String> names = new ArrayList<>();
        for (String field : keyToField.values()) {
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile(java.util.regex.Pattern.quote("." + field + ".isVisible()")).matcher(src);
            while (m.find()) {
                hits.add(new int[]{m.start(), names.size()});
            }
            names.add(field);
        }
        hits.sort((x, y) -> Integer.compare(x[0], y[0]));
        List<String> ordered = new ArrayList<>();
        for (int[] h : hits) {
            ordered.add(names.get(h[1]));
        }
        return ordered;
    }

    private static String fieldOf(RoleEntry e, List<RoleEntry> entries) {
        String want = RoleElementPageGenerator.locatorKey(e);
        for (RoleElementPageGenerator.GeneratedField f : RoleElementPageGenerator.assignFields(entries)) {
            if (want != null && want.equals(RoleElementPageGenerator.locatorKey(f.entry))) {
                return f.fieldName;
            }
        }
        throw new AssertionError("未找到字段名：" + want);
    }

    @Test
    // @DisplayName: "断言行按拾取序号升序（与步骤内操作顺序一致），同元素多号只断言一次"
    public void assertionLinesFollowPickNosOrder() {
        RoleEntry a = entry("A", "k_a");
        RoleEntry b = entry("B", "k_b");
        List<RoleEntry> entries = Arrays.asList(a, b);
        // A 号 1、4；B 号 2、3；step 的 picks 反序给出（B 在前）
        String src = assertCode(entries, Collections.singletonList(
                new StepRec(PAGE, Arrays.asList(pick(b, 2, 3), pick(a, 1, 4))))).get(PAGE);

        assertTrue("应生成该页断言类：" + src, src != null);
        assertEquals("同一次封装内应 A(1)→B(2)（B 只断言一次）",
                Arrays.asList(fieldOf(a, entries), fieldOf(b, entries)),
                assertedFields(src, entries));
    }

    @Test
    // @DisplayName: "多次封装的断言方法编号严格按面板封装先后"
    public void assertionMethodsFollowPackageOrder() {
        RoleEntry a = entry("A", "k_a");
        RoleEntry b = entry("B", "k_b");
        List<RoleEntry> entries = Arrays.asList(a, b);
        String src = assertCode(entries, Arrays.asList(
                // 面板第 1 个封装：B(2)、A(1)
                new StepRec(PAGE, Arrays.asList(pick(entry("B", "k_b"), 2), pick(entry("A", "k_a"), 1))),
                // 面板第 2 个封装：A(3)
                new StepRec(PAGE, Arrays.asList(pick(entry("A", "k_a"), 3))))).get(PAGE);

        assertTrue("应生成 assertStep1：" + src, src.contains("public void assertStep1()"));
        assertTrue("应生成 assertStep2：" + src, src.contains("public void assertStep2()"));
        assertTrue("应标注面板全局封装序号（与步骤类同口径）：" + src,
                src.contains("// 面板第 1 个「封装为步骤」") && src.contains("// 面板第 2 个「封装为步骤」"));
        int i1 = src.indexOf("public void assertStep1()");
        int i2 = src.indexOf("public void assertStep2()");
        assertTrue("assertStep1 应排在 assertStep2 之前", i1 > 0 && i1 < i2);
        assertTrue("step1 内应先 A 后 B：" + src, src.indexOf("assertThat",
                src.indexOf("assertStep1()")) < src.indexOf(fieldOf(b, entries)));
    }

    @Test
    // @DisplayName: "跨页元素不得被静默丢弃（断言比步骤少行即为此缺陷）"
    public void crossPageStepDoesNotLoseAssertions() {
        RoleEntry a = entry("A", "k_a");
        List<RoleEntry> entries = Collections.singletonList(a);
        // 该 step 归属其它页（跨页封装：owner 取首个元素所在页），但其元素在 entries 中存活。
        LinkedHashMap<String, String> codes = assertCode(entries, Collections.singletonList(
                new StepRec(OTHER, Collections.singletonList(pick(a, 1)))));
        // 断言类按 step 的 owner 页归档；关键是它必须【包含】属于 LoginPage 的元素的断言，
        // 并在类里声明 LoginPage 字段，否则跨页元素被静默丢弃或产物不可编译。
        String src = codes.get(OTHER);
        assertTrue("应按 step 归属页生成断言类，实际键=" + codes.keySet(), src != null);
        assertFalse("跨页 step 的断言不得被渲染成空方法：\n" + src, src.contains("未勾选任何元素"));
        assertTrue("必须声明元素所属页（LoginPage）的字段：\n" + src,
                src.contains("PageObjectFactory.getPage(LoginPage.class)"));
        assertEquals("跨页元素的断言必须仍然生成（且引用其所属页变量）",
                Collections.singletonList(fieldOf(a, entries)), assertedFields(src, entries));
    }
}
