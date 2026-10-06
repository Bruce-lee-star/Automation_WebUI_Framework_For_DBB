package com.hsbc.cmb.hk.dbb.automation.framework.web.page.scan;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * N-17 / N-19 契约（doc 21）。
 *
 * <p><b>N-17</b>：pw-codegen 原有 64 处空 {@code catch} —— 吞噬是<b>有意</b>的（页面已关闭 / 脚本未注入等
 * 属常态，不该打断会话），但空块是<b>零信号</b>：真实故障（如桥注册失败 → "面板点了没反应"）无法归因。
 * 现统一经 {@link RolePickerQuiet#ignore} 收口：DEBUG 日志 + 可断言计数。</p>
 *
 * <p><b>N-19</b>：面板主循环 {@code while (true)} 跑在<b>调用者线程</b>上，退出条件只有
 * 「根页关闭 / 无存活页 / 中断」；事件丢失时会无限阻塞调用线程。新增会话级 deadline 兜底，
 * 此处守卫其默认值必须为<b>正</b>（{@code 0} 会等于"无上限"，正是要消除的隐患）。</p>
 */
public class RolePickerQuietAndSessionCapTest {

    @Test
    // @DisplayName: "N-17：吞噬异常必须可计数（把零信号变成可断言事实）"
    public void swallowedExceptionsAreCounted() {
        RolePickerQuiet.reset();
        long before = RolePickerQuiet.getSwallowedCount();

        RolePickerQuiet.ignore("unit-test#a", new IllegalStateException("boom"));
        RolePickerQuiet.ignore("unit-test#b", null);

        assertEquals("每次被吞噬的异常都必须计数 —— 否则真实故障会静默消失、无从归因", before + 2, RolePickerQuiet.getSwallowedCount());
    }

    @Test
    // @DisplayName: "N-17：null 异常亦安全（不得 NPE、也不得漏计）"
    public void nullThrowableIsSafe() {
        RolePickerQuiet.reset();

        RolePickerQuiet.ignore("unit-test#null", null);

        assertEquals("无 cause 时同样要计数", 1, RolePickerQuiet.getSwallowedCount());
    }

    @Test
    // @DisplayName: "N-19：会话 deadline 默认值必须为正（0/负数等于无上限，正是要消除的隐患）"
    public void sessionCapDefaultIsPositive() {
        assertTrue("面板会话必须有正的兜底上限，否则事件丢失时会无限阻塞调用者线程", RolePickerConstants.TIMEOUT_PANEL_SESSION_MAX_MS > 0);
        assertEquals("属性名是对外契约（文档与用例据此覆盖），不得改名", "rolePicker.panelSessionMaxMs", RolePickerConstants.PANEL_SESSION_MAX_PROPERTY);
    }
}
