package com.hsbc.cmb.hk.dbb.automation.framework.web.page.scan;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;

/**
 * 「没有 NLS 文件 ⇒ 不存在 key」的默认行为契约（用户反馈：「我其实没有任何 nls file，不应该有 key 这种存在」）。
 *
 * <p>无需任何开关/配置：不传 NLS 文件（或只传空串/空白）时，反查表恒为空 ⇒ 浏览器侧拿不到 key
 * （pick 无 resolvedKey、i18n 策略不出现）⇒ 面板不显示 key、生成页面类也不产生 {@code key = ...}
 * 与 {@code @RoleFile}（生成侧另有 allowKey 门控 + {@code RoleElementPageGeneratorNlsKeyTest} 锁定）。
 */
public class RolePickerNlsAbsenceTest {

    @Test
    // @DisplayName: "不传 NLS 文件 ⇒ 反查表为空（key 无从产生）"
    public void emptyNlsFilesProducesNoReverseTable() {
        assertEquals("不传 NLS 文件时必须返回空反查表", "{}",
                RolePickerNlsCache.buildNlsReverseJson(Collections.emptyList()));
    }

    @Test
    // @DisplayName: "只传 null/空白路径 ⇒ 同样不构建反查表"
    public void blankNlsFileEntriesProduceNoReverseTable() {
        assertEquals("只传空白/空值时必须返回空反查表", "{}",
                RolePickerNlsCache.buildNlsReverseJson(Arrays.asList(null, "", "   ")));
    }
}
