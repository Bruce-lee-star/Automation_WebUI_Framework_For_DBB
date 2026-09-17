package com.hsbc.cmb.hk.dbb.automation.framework.web.page.scan;

import com.hsbc.cmb.hk.dbb.automation.framework.web.page.scan.model.PickSnapshot;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A-4：{@link RolePickerSnapshotParser#parsePickSnapshot(Object)} 的容错解析契约（纯函数，无浏览器依赖）。
 *
 * <p>浏览器侧拾取态经 {@code page.evaluate} 回传的是 {@code Map}/{@code List} 结构，任何非预期形态
 * （null / 非 Map / 元素非 Map）都必须优雅降级为空快照，绝不抛异常——否则一次竞态或脚本版本不一致
 * 就会让"停止并生成"整条链路崩溃。
 */
class RolePickerSnapshotParserTest {

    @Test
    void nonMapRaw_returnsEmptySnapshot() {
        PickSnapshot s = RolePickerSnapshotParser.parsePickSnapshot("not-a-map");

        assertNotNull(s);
        assertEquals("", s.pageClass);
        assertTrue(s.entries.isEmpty());
        assertTrue(s.steps.isEmpty());
        assertTrue(s.ops.isEmpty());
    }

    @Test
    void nullRaw_returnsEmptySnapshot() {
        PickSnapshot s = RolePickerSnapshotParser.parsePickSnapshot(null);

        assertNotNull(s);
        assertTrue(s.entries.isEmpty() && s.steps.isEmpty() && s.ops.isEmpty());
    }

    @Test
    void parsesPageClassAndOps() {
        Map<Object, Object> raw = new HashMap<>();
        raw.put("pageClass", "LoginPage");
        raw.put("ops", List.of(Map.of("op", "closePage", "pageClass", "LoginPage")));

        PickSnapshot s = RolePickerSnapshotParser.parsePickSnapshot(raw);

        assertEquals("LoginPage", s.pageClass);
        assertEquals(1, s.ops.size());
        assertEquals("closePage", s.ops.get(0).op);
        assertEquals("LoginPage", s.ops.get(0).pageClass);
    }

    @Test
    void opsWithoutOpValue_areSkipped() {
        Map<Object, Object> raw = new HashMap<>();
        raw.put("ops", List.of(Map.of("pageClass", "P"), Map.of("op", "", "pageClass", "P")));

        assertTrue(RolePickerSnapshotParser.parsePickSnapshot(raw).ops.isEmpty(),
                "缺少 op 值的页面操作应被跳过");
    }

    @Test
    void nonMapElements_areTolerated() {
        Map<Object, Object> raw = new HashMap<>();
        raw.put("picks", List.of("not-a-map", 42));
        raw.put("steps", List.of("nope"));
        raw.put("ops", List.of("nope"));

        PickSnapshot s = RolePickerSnapshotParser.parsePickSnapshot(raw);

        assertTrue(s.entries.isEmpty() && s.steps.isEmpty() && s.ops.isEmpty(),
                "非 Map 元素应被容忍并跳过，而非抛异常");
    }
}
