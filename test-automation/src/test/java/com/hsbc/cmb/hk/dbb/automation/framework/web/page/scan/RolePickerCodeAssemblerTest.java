package com.hsbc.cmb.hk.dbb.automation.framework.web.page.scan;

import org.junit.Test;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Characterization tests for {@link RolePickerCodeAssembler}: the source-generation orchestration
 * extracted (verbatim) from {@code RoleElementPicker} in T5-1 step 3.
 *
 * <p>Pinned behavior (pure, browser-free):
 * <ul>
 *   <li>{@code needsReplaceByMemory}: the in-memory (post-click) entry must win over the stale
 *       one when any enhancement field (dialog / popup / framePath) differs; identical =&gt; no replace.</li>
 *   <li>{@code hasClosePick}: a step whose picks carry {@code _closeOp=true} is the auto-close step.</li>
 * </ul>
 */
public class RolePickerCodeAssemblerTest {

    private static RoleEntry entry() {
        return new RoleEntry("button", "Submit");
    }

    @Test
    public void needsReplaceByMemory_identicalIsFalse() {
        RoleEntry a = entry();
        RoleEntry b = entry();
        assertFalse(RolePickerCodeAssembler.needsReplaceByMemory(a, b));
    }

    @Test
    public void needsReplaceByMemory_dialogDiffIsTrue() {
        // mem(第二参) 比 p(第一参) 多了 dialog 增强 → 必须换用内存态
        RoleEntry a = entry();
        RoleEntry b = entry();
        b.setDialog(true);
        assertTrue(RolePickerCodeAssembler.needsReplaceByMemory(a, b));
    }

    @Test
    public void needsReplaceByMemory_framePathDiffIsTrue() {
        RoleEntry a = entry();
        RoleEntry b = entry();
        a.setFramePath(Arrays.asList("iframe#1", "body"));
        b.setFramePath(Arrays.asList("iframe#2", "body"));
        assertTrue(RolePickerCodeAssembler.needsReplaceByMemory(a, b));
    }

    @Test
    public void needsReplaceByMemory_framePathOnlyInMemoryIsTrue() {
        // 浏览器侧 click 后 backfill 出 framePath，而 snap.picks 缺 —— 必须用内存态替换
        RoleEntry a = entry();                 // 旧：无 framePath
        RoleEntry b = entry();
        b.setFramePath(Arrays.asList("iframe#1"));
        assertTrue(RolePickerCodeAssembler.needsReplaceByMemory(a, b));
    }

    @Test
    public void needsReplaceByMemory_popupDiffIsTrue() {
        RoleEntry a = entry();
        RoleEntry b = entry();
        b.setPopup(true);
        assertTrue(RolePickerCodeAssembler.needsReplaceByMemory(a, b));
    }

    @Test
    public void hasClosePick_noCloseOpFlagIsFalse() {
        Map<String, Object> step = new LinkedHashMap<>();
        Map<String, Object> pick = new LinkedHashMap<>();
        pick.put("name", "Submit");
        step.put("picks", Arrays.asList(pick));
        assertFalse(RolePickerCodeAssembler.hasClosePick(step));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void hasClosePick_withCloseOpFlagIsTrue() {
        Map<String, Object> step = new LinkedHashMap<>();
        Map<String, Object> pick = new LinkedHashMap<>();
        pick.put("_closeOp", Boolean.TRUE);
        step.put("picks", Arrays.asList(pick));
        assertTrue(RolePickerCodeAssembler.hasClosePick(step));
    }
}
