package com.hsbc.cmb.hk.dbb.automation.framework.web.page.scan;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;

/**
 * 「没有 NLS 文件 ⇒ 不应存在 key 这种概念」的两条保证：
 *
 * <ul>
 *   <li>不传任何 NLS 文件 ⇒ 反查表为空（不构建）⇒ 浏览器侧拿不到 key（pick 无 resolvedKey、无 i18n 策略）；</li>
 *   <li>即使上层按惯例传了 NLS 文件，也可用 {@code -DrolePicker.nls.disabled=true} <b>彻底关闭</b> key 体系
 *       ⇒ 反查表同样为空 ⇒ 面板、产物（{@code key = ...} / {@code @RoleFile}）、策略一律不出现 key。</li>
 * </ul>
 *
 * <p>用户反馈原文：「我其实没有任何 nls file，不应该有 key 这种存在」。
 */
public class RolePickerNlsDisabledSwitchTest {

    @Test
    // @DisplayName: "未传 NLS 文件 ⇒ 反查表为空（key 无从产生）"
    public void emptyNlsFilesProducesNoReverseTable() {
        assertEquals("未传 NLS 文件时必须返回空反查表", "{}",
                RolePickerNlsCache.buildNlsReverseJson(Collections.emptyList()));
    }

    @Test
    // @DisplayName: "-DrolePicker.nls.disabled=true ⇒ 即使传了 NLS 文件也不构建反查表（彻底无 key）"
    public void disabledSwitchTurnsOffKeyLookupEntirely() {
        System.setProperty(RolePickerConstants.NLS_DISABLED_PROPERTY, "true");
        try {
            assertEquals("开关置位后必须返回空反查表（不构建任何 key）", "{}",
                    RolePickerNlsCache.buildNlsReverseJson(Arrays.asList("nls/logon.properties")));
        } finally {
            System.clearProperty(RolePickerConstants.NLS_DISABLED_PROPERTY);
        }
    }
}
