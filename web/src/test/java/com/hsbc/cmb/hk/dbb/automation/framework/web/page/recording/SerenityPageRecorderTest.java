package com.hsbc.cmb.hk.dbb.automation.framework.web.page.recording;

import com.hsbc.cmb.hk.dbb.automation.framework.web.page.PageElement;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.base.BasePage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;

/**
 * Phase 3 专属 UT：验证 {@link SerenityPageRecorder}（Layer B 录制门面）。
 * 用浏览器无关的 fake {@link BasePage} 子类（覆写 {@code element}）验证录制+委托，避免启动真实浏览器。
 */
class SerenityPageRecorderTest {

    private String prevLogging;

    @BeforeEach
    void setUp() {
        prevLogging = System.getProperty("serenity.logging");
        System.setProperty("serenity.logging", "VERBOSE"); // 开启录制开关，使 per-page 测试数据被写入
    }

    @AfterEach
    void tearDown() {
        if (prevLogging == null) {
            System.clearProperty("serenity.logging");
        } else {
            System.setProperty("serenity.logging", prevLogging);
        }
    }

    /** 浏览器无关的 fake BasePage：仅作真实 BasePage 子类（无浏览器初始化）。 */
    static final class FakePage extends BasePage {
    }

    @Test
    void element_recordsAndDelegates() {
        SerenityPageRecorder recorder = new SerenityPageRecorder();
        FakePage bp = new FakePage();

        PageElement result = recorder.element(bp, "#submit");

        // 录制门面直接构造 PageElement 包裹选择器（避免回调 bp.element 形成递归），返回值透传
        assertNotNull(result, "element must return a PageElement wrapping the selector");
        assertEquals("#submit", recorder.recorder().getSerenityTestData("lastActionElement"));
    }

    @Test
    void recorder_isPerInstance() {
        SerenityPageRecorder a = new SerenityPageRecorder();
        SerenityPageRecorder b = new SerenityPageRecorder();
        assertNotNull(a.recorder());
        assertNotSame(a.recorder(), b.recorder(), "each page recorder holds its own per-page state");
    }
}
