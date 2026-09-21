package com.hsbc.cmb.hk.dbb.automation.framework.web.page.scan;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 评审 F-07 / P1-7 契约网：区域扫描的「点击多个区域累加 + 按 Esc 收尾」语义必须在
 * <b>Java↔JS 两侧一致</b>。
 *
 * <p><b>原失效链（亲验）</b>：JS 的 {@code onClick} 每次点击区域都发 {@code regionScanned}
 * 并累加选区（设计为多选），而 Java 侧 {@code cmdRegionScanned} 收到<b>首次</b>通知就
 * {@code END_REGION_SELECT} + 回 IDLE —— 摘除了浏览器侧选区监听，第二个区域永远点不上，
 * 「多选区域」静默退化为单选。</p>
 *
 * <p><b>修复后的分工</b>：{@code regionScanned} = 每次点击的<b>增量</b>通知（只刷新代码）；
 * 收尾只认 {@code regionDone}，由浏览器侧 {@code finish()}（Esc）回传。
 * 本测试固化这两条<b>跨语言契约</b>——它们的错位正是 F-06 / F-07 的共同根因，
 * 且此类错位编译期完全无声，只能靠本网兜住。</p>
 *
 * <p>引擎侧的「不再收尾」属运行期行为，需真机交互验证（见评审报告对 F-07 的标注）；
 * 此处固化的是协议面，即"谁在什么时候发什么命令"。</p>
 */
public class RegionSelectMultiSelectContractTest {

    /** 区域选择 JS 的类路径位置（core 模块资源）。 */
    private static final String REGION_JS = "/scan/js/picker-core-b2.js";

    @Test
    public void javaAndJsAgreeOnRegionDoneCommandName() throws IOException {
        assertNotNull(RolePickerConstants.CMD_REGION_DONE, "Java 侧必须登记 regionDone 命令");
        assertTrue(RolePickerConstants.CMD_REGION_DONE.equals("regionDone"),
                "命令线值必须与 JS 严格一致（JS 发 'regionDone'），实际：" + RolePickerConstants.CMD_REGION_DONE);

        String js = readResource(REGION_JS);
        assertTrue(js.contains("__rolePickerCmd('regionDone')"),
                "F-07：浏览器侧 Esc 收尾（finish）必须回传 regionDone —— 否则 Java 模式停在 scanRegion，"
                        + "面板按钮永久置灰，且无从收尾");
    }

    @Test
    public void regionClickStillSendsIncrementalNotificationWithoutFinishingSelection() throws IOException {
        String onClick = regionClickHandler(readResource(REGION_JS));

        assertTrue(onClick.contains("'regionScanned'"),
                "F-07：每次点击区域都应发增量通知 regionScanned（Java 据此刷新代码），实际片段：\n" + onClick);
        assertFalse(onClick.contains("finish("),
                "F-07：点击区域的处理里绝不能调用 finish()（finish 只在 Esc/Java END 时收尾）——"
                        + "首次点击即收尾正是「多选退化为单选」的根因，实际片段：\n" + onClick);
        assertTrue(onClick.contains("selected.push(root)"),
                "F-07：点击区域应把区域累加进已选集合（多选语义）");
    }

    /** 截取区域点击处理函数体（{@code function onClick(e) {} } 至下一个同级函数声明）。 */
    private static String regionClickHandler(String js) {
        int start = js.indexOf("function onClick(e) {");
        assertTrue(start >= 0, "未找到区域点击处理函数 onClick（JS 结构变更请同步本契约测试）");
        int end = js.indexOf("\n                function ", start);
        return end > start ? js.substring(start, end) : js.substring(start);
    }

    private static String readResource(String path) throws IOException {
        try (InputStream in = RegionSelectMultiSelectContractTest.class.getResourceAsStream(path)) {
            assertNotNull(in, "类路径未找到资源：" + path + "（core 资源未被打包会让本契约网静默失效）");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
