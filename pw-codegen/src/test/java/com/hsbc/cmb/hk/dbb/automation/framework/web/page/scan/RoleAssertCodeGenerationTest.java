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
            // 断言行可能是可见性断言（isVisible）或输入类元素的值断言（inputValue）——两者都算"该元素有断言"
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile(java.util.regex.Pattern.quote("." + field + ".")
                            + "(?:isVisible|inputValue)\\(\\)").matcher(src);
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

    @Test
    // @DisplayName: "断言与步骤逐行同构：条数相同、逐行元素相同（回归用户『断言和步骤生成的代码不一样』）"
    public void assertionsMirrorStepOperationsLineByLine() {
        RoleEntry title = entry("Title", "k_title");
        RoleEntry user = entry("User", "k_user");
        RoleEntry input = entry("Input", "k_input");
        RoleEntry newTo = entry("NewTo", "k_new");
        RoleEntry text = entry("Text", "k_text");
        List<RoleEntry> entries = Arrays.asList(title, user, input, newTo, text);
        // 复刻实测会话：title=[1,3,7] user=[2,4] input=[5] newTo=[6] text=[8]（共 8 次操作）
        List<StepRec> steps = Collections.singletonList(new StepRec(PAGE, Arrays.asList(
                pick(title, 1, 3, 7), pick(user, 2, 4), pick(input, 5), pick(newTo, 6), pick(text, 8))));

        PickSnapshot snap = new PickSnapshot(PAGE, entries, steps, new ArrayList<>());
        String stepSrc = RolePickerCodeAssembler.buildStepCode(snap, PKG, "LogonSteps").get(PAGE);
        String assertSrc = RolePickerCodeAssembler.buildAssertCode(snap, PKG).get(PAGE);

        List<String> stepLines = operationsInOrder(stepSrc, entries);
        List<String> assertLines = assertedFields(assertSrc, entries);
        assertEquals("步骤侧应按号展开为 8 次操作：" + stepSrc, 8, stepLines.size());
        assertEquals("断言行数必须等于步骤操作行数（否则就是用户看到的『不一样』）：\n步骤=" + stepLines
                + "\n断言=" + assertLines, stepLines.size(), assertLines.size());
        assertEquals("逐行对应的元素必须完全一致", stepLines, assertLines);
    }

    @Test
    // @DisplayName: "输入类元素捕获到输入值时生成值断言（inputValue），无值则回落可见性断言"
    public void fillOperationProducesValueAssertion() {
        RoleEntry input = new RoleEntry("textbox", "Username");
        input.setValue("687");
        String src = assertCode(Collections.singletonList(input), Collections.singletonList(
                new StepRec(PAGE, Collections.singletonList(pick(input, 5))))).get(PAGE);
        assertTrue("应为输入行生成值断言：\n" + src, src.contains(".inputValue(), equalTo(\"687\"))"));
        assertFalse("输入行不应再生成可见性断言：\n" + src, src.contains("isVisible()"));

        // 无输入值 ⇒ 回落可见性断言（与步骤侧 fill("") 留待补全同口径）
        RoleEntry empty = new RoleEntry("textbox", "Username");
        String src2 = assertCode(Collections.singletonList(empty), Collections.singletonList(
                new StepRec(PAGE, Collections.singletonList(pick(empty, 5))))).get(PAGE);
        assertTrue("无输入值应回落到可见性断言：\n" + src2, src2.contains(".isVisible(), equalTo(true))"));
    }

    /** 按出现顺序抽出步骤里被操作的元素（同一元素多次操作会出现多次）。 */
    private static List<String> operationsInOrder(String src, List<RoleEntry> entries) {
        List<int[]> hits = new ArrayList<>();
        List<String> names = new ArrayList<>();
        for (RoleElementPageGenerator.GeneratedField f : RoleElementPageGenerator.assignFields(entries)) {
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile(java.util.regex.Pattern.quote("." + f.fieldName + ".")).matcher(src);
            while (m.find()) {
                hits.add(new int[]{m.start(), names.size()});
            }
            names.add(f.fieldName);
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
    // @DisplayName: "断言行与步骤操作行一一对应：按 pickNos 逐号展开（同元素多号各出一条）"
    public void assertionLinesFollowPickNosOrder() {
        RoleEntry a = entry("A", "k_a");
        RoleEntry b = entry("B", "k_b");
        List<RoleEntry> entries = Arrays.asList(a, b);
        // A 号 1、4；B 号 2、3；step 的 picks 反序给出（B 在前）
        String src = assertCode(entries, Collections.singletonList(
                new StepRec(PAGE, Arrays.asList(pick(b, 2, 3), pick(a, 1, 4))))).get(PAGE);

        assertTrue("应生成该页断言类：" + src, src != null);
        // 与 buildStepCode 同口径：逐号展开 ⇒ 与 step 的操作行严格一一对应（A(1) B(2) B(3) A(4)）
        assertEquals("应与步骤同构：A(1)→B(2)→B(3)→A(4)",
                Arrays.asList(fieldOf(a, entries), fieldOf(b, entries), fieldOf(b, entries), fieldOf(a, entries)),
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
