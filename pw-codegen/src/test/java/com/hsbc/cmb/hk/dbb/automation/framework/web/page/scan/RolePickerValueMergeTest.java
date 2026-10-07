package com.hsbc.cmb.hk.dbb.automation.framework.web.page.scan;

import org.junit.Test;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

/**
 * 「输入框文本必须能合并进权威内存态」契约（{@link RolePickerPickParser#mergePickIntoMap}）。
 *
 * <p><b>回归背景</b>：用户点击 `input#userName` 后输入 `675r5`，面板序号也记为 [3]，但产物里是
 * `logonPage.usernameInput.fill("")` —— 输入内容丢了。原因：输入内容是在点击<b>之后</b>逐字符回传的，
 * 而权威态（{@code javaPickBySig}）保留的是首次点击解析出的 existing 对象，其 {@code value} 当时为空；
 * 合并逻辑原先只维护 pickNos/framePath/dialog/popup，从不合并 value。
 *
 * <p>口径与 pickNos 一致「只增不减」：incoming 非空则采信；incoming 为空则保留已有值
 * （点击回传常带空值，直接覆盖会抹掉用户已输入内容）。
 */
public class RolePickerValueMergeTest {

    private static final String SIG_KEY = "[\"role:textbox:Username#0\",\"LogonPage\"]";

    /** 构造一次拾取回传的原始 payload（模拟浏览器经 binding 投递的 JSON 解析结果）。 */
    private static Map<Object, Object> payload(double pickNo, String value) {
        Map<Object, Object> m = new LinkedHashMap<>();
        m.put("_sig", "role:textbox:Username#0");
        m.put("_sigKey", SIG_KEY);
        m.put("_pageClass", "LogonPage");
        m.put("strategy", "role");
        m.put("role", "textbox");
        m.put("name", "Username");
        // 注意：GSON 以 Map 解析时数字是 Double（parsePickNos 会 intValue()）
        m.put("_pickNos", Arrays.asList(pickNo));
        m.put("value", value);
        return m;
    }

    private static String dedupKey() {
        Map<Object, Object> keySrc = new LinkedHashMap<>();
        keySrc.put("_sigKey", SIG_KEY);
        keySrc.put("_pageClass", "LogonPage");
        return RolePickerPickParser.pickDedupKey(keySrc, RolePickerPickParser.parsePick(payload(3.0, "")));
    }

    @Test
    // @DisplayName: "输入框文本（点击后才产生）必须合并进权威态；空值回传不得抹掉已输入内容"
    public void typedValueIsMergedIntoAuthoritativeEntry() {
        LinkedHashMap<String, RoleEntry> map = new LinkedHashMap<>();
        String key = dedupKey();
        assertNotNull("应能算出权威态键", key);

        // 1) 点击时回传：还没输入 ⇒ value 为空
        RolePickerPickParser.mergePickIntoMap(map, key, RolePickerPickParser.parsePick(payload(3.0, "")));
        assertNotNull("首次回传后应落库", map.get(key));
        assertNull("点击时尚未输入，value 应为空", map.get(key).getValue());

        // 2) 输入后回传（逐字符，最终值）
        RolePickerPickParser.mergePickIntoMap(map, key, RolePickerPickParser.parsePick(payload(3.0, "675r5")));
        assertEquals("输入内容必须合并进权威态，否则生成的 fill(\"\") 拿不到内容",
                "675r5", map.get(key).getValue());

        // 3) 随后的点击回传带空值（用户并未清空输入框）⇒ 保留已输入内容
        RolePickerPickParser.mergePickIntoMap(map, key, RolePickerPickParser.parsePick(payload(4.0, "")));
        assertEquals("空值回传不得抹掉已输入内容", "675r5", map.get(key).getValue());

        // 4) 用户重新输入新值 ⇒ 采信新值
        RolePickerPickParser.mergePickIntoMap(map, key, RolePickerPickParser.parsePick(payload(4.0, "abc")));
        assertEquals("重新输入应生效", "abc", map.get(key).getValue());
    }
}
