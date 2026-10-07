package com.hsbc.cmb.hk.dbb.automation.framework.web.page.scan;

import com.hsbc.cmb.hk.dbb.automation.framework.web.page.scan.model.PickSnapshot;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.scan.model.StepRec;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * 步骤生成顺序契约：<b>step 之间的顺序 = 面板「封装为步骤」的先后</b>；
 * <b>step 内部的操作顺序 = 各元素拾取序号（pickNos）升序，同一元素多号各生成一次操作</b>。
 *
 * <p>本用例的输入刻意把 picks 数组打乱（模拟浏览器侧回传顺序不可控），锁定"顺序只由封装顺序与序号决定，
 * 与数组排列无关"这一契约 —— 用户报"生成的步骤没有按顺序来"时，可据此判据区分
 * ①生成器排序缺陷（这里会红）与 ②面板序号/多页视图（这里恒绿，需按页视图去看）。
 */
public class RoleStepOrderContractTest {

    private static final String PAGE = "LoginPage";

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

    /** 取该页视图里第 n 个 step 的方法体。 */
    private static String bodyOf(String src, int methodNo) {
        int i = src.indexOf("public void step" + methodNo + "()");
        assertTrue("未生成 step" + methodNo + "：" + src, i > 0);
        int end = src.indexOf("\n    }", i);
        return src.substring(i, end < 0 ? src.length() : end);
    }

    /**
     * 按操作行出现的先后，抽出被操作的元素（用生成器实际分配的字段名匹配）。
     * 必须收集<b>全部</b>出现位置：同一元素多号时会生成多行相同操作（如 A(1) → … → A(4)）。
     */
    private static List<String> operatedFields(String body, List<RoleEntry> entries) {
        Map<String, String> keyToField = new LinkedHashMap<>();
        for (RoleElementPageGenerator.GeneratedField f : RoleElementPageGenerator.assignFields(entries)) {
            keyToField.put(RoleElementPageGenerator.locatorKey(f.entry), f.fieldName);
        }
        List<int[]> hits = new ArrayList<>();
        List<String> names = new ArrayList<>();
        for (Map.Entry<String, String> en : keyToField.entrySet()) {
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile(java.util.regex.Pattern.quote("." + en.getValue() + "."))
                    .matcher(body);
            while (m.find()) {
                hits.add(new int[]{m.start(), names.size()});
            }
            names.add(en.getValue());
        }
        hits.sort((x, y) -> Integer.compare(x[0], y[0]));
        List<String> ordered = new ArrayList<>();
        for (int[] h : hits) {
            ordered.add(names.get(h[1]));
        }
        return ordered;
    }

    private static String generate(List<RoleEntry> entries, List<StepRec> steps) {
        PickSnapshot snap = new PickSnapshot(PAGE, entries, steps, new ArrayList<>());
        LinkedHashMap<String, String> out = RolePickerCodeAssembler.buildStepCode(snap, "com.example.steps", "DemoSteps");
        assertTrue("应生成该页视图，实际键=" + out.keySet(), out.containsKey(PAGE));
        return out.get(PAGE);
    }

    @Test
    // @DisplayName: "step 内部按 pickNos 升序（与 picks 数组排列无关）；同一元素多号各生成一次操作"
    public void operationsAreOrderedByPickNos() {
        RoleEntry a = entry("A", "k_a");
        RoleEntry b = entry("B", "k_b");
        List<RoleEntry> entries = Arrays.asList(a, b);
        // A 被点两次（号 1、4），B 被点两次（号 2、3）；step 的 picks 故意反序给（B 在前）
        String src = generate(entries, Collections.singletonList(
                new StepRec(PAGE, Arrays.asList(pick(b, 2, 3), pick(a, 1, 4)))));

        String fA = fieldOf(a, entries);
        String fB = fieldOf(b, entries);
        // 期望：A(1) → B(2) → B(3) → A(4)
        String body = bodyOf(src, 1);
        assertEquals("操作顺序应为 A(1) → B(2) → B(3) → A(4)，实际生成体：\n" + body,
                Arrays.asList(fA, fB, fB, fA), operatedFields(body, entries));
    }

    @Test
    // @DisplayName: "step 之间的顺序 = 面板封装先后（与 picks 数组排列、页内多元素无关）"
    public void stepSequenceFollowsPackageOrder() {
        RoleEntry a = entry("A", "k_a");
        RoleEntry b = entry("B", "k_b");
        List<RoleEntry> entries = Arrays.asList(a, b);   // 权威 entries（字段名来源）
        // 真实形态：浏览器侧"每个序号一个 pick 克隆"（各自单号），同一元素在不同 step 里是不同对象；
        // 故这里用同 locatorKey 的新实例模拟克隆，避免"同实例被两个 step 共享、后一个把前一个的号改掉"。
        String src = generate(entries, Arrays.asList(
                // 面板第 1 个封装：B(2) 与 A(1)，数组逆序给出
                new StepRec(PAGE, Arrays.asList(pick(entry("B", "k_b"), 2), pick(entry("A", "k_a"), 1))),
                // 面板第 2 个封装：A(3)
                new StepRec(PAGE, Arrays.asList(pick(entry("A", "k_a"), 3)))));

        String body1 = bodyOf(src, 1);
        String body2 = bodyOf(src, 2);
        assertEquals("step1 内应按号排成 A(1)→B(2)，实际生成体：\n" + body1,
                Arrays.asList(fieldOf(a, entries), fieldOf(b, entries)),
                operatedFields(body1, entries));
        assertEquals("step2 应只含 A(3) 一次操作，实际生成体：\n" + body2,
                Collections.singletonList(fieldOf(a, entries)),
                operatedFields(body2, entries));
        assertTrue("两个 step 方法应同时存在（面板第 1、2 个封装）：" + src,
                src.contains("public void step1()") && src.contains("public void step2()"));
    }

    private static String fieldOf(RoleEntry e, List<RoleEntry> entries) {
        for (RoleElementPageGenerator.GeneratedField f : RoleElementPageGenerator.assignFields(entries)) {
            String lk = RoleElementPageGenerator.locatorKey(f.entry);
            String want = RoleElementPageGenerator.locatorKey(e);
            if (lk != null && lk.equals(want)) {
                return f.fieldName;
            }
        }
        throw new AssertionError("未找到字段名：" + RoleElementPageGenerator.locatorKey(e));
    }
}
