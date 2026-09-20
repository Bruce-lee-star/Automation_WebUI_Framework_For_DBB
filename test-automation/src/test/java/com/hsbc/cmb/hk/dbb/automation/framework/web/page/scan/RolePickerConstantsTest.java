package com.hsbc.cmb.hk.dbb.automation.framework.web.page.scan;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A-4：{@link RolePickerConstants} 契约测试——固化 Java↔面板 JS 的关键契约键。
 *
 * <p>重点是 {@code MODE_*} 与 {@link PickMode} 枚举的一致性（防止枚举改名而常量未同步，导致
 * Java 与浏览器侧模式字符串错配），以及命令名/实参键的唯一性（防复制粘贴引入歧义）。
 */
class RolePickerConstantsTest {

    /**
     * {@code MODE_*} 是「浏览器侧模式字符串」（Java 权威下发 {@code window.__roleMode}），取值由面板 JS 的
     * 比对字面量决定，<b>不是</b> {@link PickMode} 枚举名的小写镜像。
     *
     * <p>原断言写死「= 枚举名小写」，等于把评审 F-06 的错位（Java {@code scan_page} vs JS
     * {@code scanPage}）当成契约固化下来，使「扫描/键盘拾取静默失效」长期保持绿灯。
     * 现按浏览侧真实契约断言；与 JS 脚本字面量的一致性由 {@link RolePickerModeContractTest} 实读脚本守护。</p>
     */
    @Test
    void modeConstants_followBrowserSideContractValues() {
        assertEquals("idle", RolePickerConstants.MODE_IDLE);
        assertEquals("manual", RolePickerConstants.MODE_MANUAL);
        assertEquals("scanPage", RolePickerConstants.MODE_SCAN_PAGE);
        assertEquals("scanRegion", RolePickerConstants.MODE_SCAN_REGION);
    }

    @Test
    void commandNames_areDistinct() {
        // Set.of 遇到重复元素会抛 IllegalArgumentException，故其自身即为唯一性断言
        Set<String> cmds = Set.of(
                RolePickerConstants.CMD_START, RolePickerConstants.CMD_SCAN, RolePickerConstants.CMD_SCAN_REGION,
                RolePickerConstants.CMD_REGION_SCANNED, RolePickerConstants.CMD_PACKAGE,
                RolePickerConstants.CMD_REFRESH_CODE, RolePickerConstants.CMD_STOP,
                RolePickerConstants.CMD_ABORT, RolePickerConstants.CMD_DONE);
        assertEquals(9, cmds.size());
    }

    @Test
    void stateKeys_areDistinctAndNonBlank() {
        Set<String> keys = Set.of(
                RolePickerConstants.STATE_KEY_MODE, RolePickerConstants.STATE_KEY_PAGE_NAME,
                RolePickerConstants.STATE_KEY_CODE, RolePickerConstants.STATE_KEY_MSG,
                RolePickerConstants.STATE_KEY_FILES, RolePickerConstants.STATE_KEY_NLS,
                RolePickerConstants.STATE_KEY_AUTO_STEP_COUNT, RolePickerConstants.STATE_KEY_STATE_JSON);
        assertEquals(8, keys.size());
        keys.forEach(k -> assertTrue(k != null && !k.isBlank(), "契约实参键不可为空"));
    }

    @Test
    void capacityAndTimeouts_arePositive() {
        assertTrue(RolePickerConstants.CAP_GLOBAL_URL_TO_CLASS_MAX > 0);
        assertTrue(RolePickerConstants.TIMEOUT_NLS_CACHE_TTL_MS > 0);
        assertTrue(RolePickerConstants.TIMEOUT_FORCE_START_DEBOUNCE_MS > 0);
    }
}
