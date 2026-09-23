package com.hsbc.cmb.hk.dbb.automation.framework.web.page.scan;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * N-04 契约（doc 21 CRITICAL）：跨域判据必须"同源 = 不重注入"。
 *
 * <p><b>要防的缺陷</b>：原实现在 {@code RolePickerPanelController} 内把判据误写为
 * {@code curOrigin.equals(RolePickerSessionState.LAST_PICK_ORIGIN)} —— 后者是
 * {@code Map<Page,String>}，{@code String.equals(Map)} <b>恒为 false</b>，于是
 * {@code originChanged} 退化为 {@code !curOrigin.isEmpty()}：所有同源导航（含 SPA hash 变化、
 * 整页跳转）都被判为跨域 → 强制 {@code start()} 全量重注入 → 与已修复的
 * 「反复重注入 + 反复合并 → 已拾元素成倍累积」路径重合，等于让该缺陷复发。</p>
 *
 * <p><b>本测试的灵敏度</b>：{@link #sameOriginDoesNotForceReinjection()} 在旧实现下<b>必然失败</b>
 * （旧实现返回 true），故它是该缺陷的有效回归守卫。</p>
 */
class RolePickerPanelControllerOriginTest {

    private static final String SITE_A = "https://a.example.com";
    private static final String SITE_B = "https://b.example.com";

    @Test
    @DisplayName("N-04：同源导航不得被判为需要重注入（旧实现在此必红）")
    void sameOriginDoesNotForceReinjection() {
        assertFalse(RolePickerPanelController.needsForcedReinjection(SITE_A, SITE_A),
                "同源导航（含 SPA hash 变化 / 整页跳转）必须为 false —— 否则会强制 start() 全量重注入，"
                        + "触发『反复重注入 + 反复合并 → 已拾元素成倍累积』的复发路径");
    }

    @Test
    @DisplayName("N-04：跨域导航必须重注入（门控脚本因 origin 隔离未注入库）")
    void crossOriginForcesReinjection() {
        assertTrue(RolePickerPanelController.needsForcedReinjection(SITE_B, SITE_A),
                "跨域 → 库必未注入，必须强制重注入");
    }

    @Test
    @DisplayName("N-04：首次导航（无历史 origin）必须重注入")
    void firstNavigationForcesReinjection() {
        assertTrue(RolePickerPanelController.needsForcedReinjection(SITE_A, null),
                "该 Page 无历史 origin（首次注入前）→ 必须强制重注入");
    }

    @Test
    @DisplayName("N-04：无效文档（about:blank 等空 origin）不触发重注入，保持原有语义")
    void blankOriginDoesNotForceReinjection() {
        assertFalse(RolePickerPanelController.needsForcedReinjection("", SITE_A),
                "空 origin（about:blank 过渡文档）不得触发重注入，避免污染跨域判据");
        assertFalse(RolePickerPanelController.needsForcedReinjection("", null),
                "空 origin 且无历史记录时保持 false（由后续真实导航的 onFrameNavigated 接管）");
    }
}
