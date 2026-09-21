package com.hsbc.cmb.hk.dbb.automation.framework.web.page.recording;

import com.hsbc.cmb.hk.dbb.automation.framework.web.exceptions.ElementException;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.element.PageElement;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.element.PageElementList;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.element.RoleFile;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.element.RoleOptions;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.engine.BasePage;
import com.microsoft.playwright.options.AriaRole;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 角色定位运行期 API 的录制门面单测（无浏览器，fake {@link BasePage} 子类）。
 *
 * <p>钉住「描述串 → 最近操作元素测试数据」的映射：运行期 API 与 {@code @RoleElement} 注解路径
 * 必须产出同一格式的元素描述，报告与失败诊断才能一致可读。
 */
class SerenityPageRecorderElementByRoleTest {

    /** 声明类级 {@code @RoleFile} 的测试页面类（仅解析路径，不读取文件）。 */
    @RoleFile("nls/role-locator-test.nls.json")
    static class NlsPage {
    }

    /** 无 {@code @RoleFile}：NLS 解析应给出可操作的报错。 */
    static class PlainPage {
    }

    /** 浏览器无关的 fake BasePage：仅作真实 BasePage 子类（无浏览器初始化）。 */
    static final class FakePage extends BasePage {
    }

    private String prevLogging;

    @BeforeEach
    void setUp() {
        prevLogging = System.getProperty("serenity.logging");
        System.setProperty("serenity.logging", "VERBOSE");
    }

    @AfterEach
    void tearDown() {
        if (prevLogging == null) {
            System.clearProperty("serenity.logging");
        } else {
            System.setProperty("serenity.logging", prevLogging);
        }
    }

    private static String lastElement(SerenityPageRecorder recorder) {
        Object recorded = recorder.recorder().getSerenityTestData("lastActionElement");
        return recorded == null ? null : recorded.toString();
    }

    @Test
    void elementByRole_roleOnly_recordsNoNameDescription() {
        SerenityPageRecorder recorder = new SerenityPageRecorder();

        PageElement element = recorder.elementByRole(new FakePage(), AriaRole.LISTITEM);

        assertNotNull(element);
        assertEquals("role=LISTITEM[no-name]", lastElement(recorder));
    }

    @Test
    void elementByRole_nameOverloads_recordNameDescription() {
        SerenityPageRecorder recorder = new SerenityPageRecorder();
        FakePage bp = new FakePage();

        assertEquals("role=BUTTON[name:Submit]", describe(recorder, bp, recorder.elementByRole(bp, AriaRole.BUTTON, "Submit")));
        assertEquals("role=BUTTON[name:Submit]", describe(recorder, bp, recorder.elementByRole(bp, AriaRole.BUTTON, "Submit", true)));
        assertEquals("role=HEADING[name:Business,level:2]",
                describe(recorder, bp, recorder.elementByRole(bp, AriaRole.HEADING, "Business", false, 2)),
                "层级须体现在描述串中");
        assertEquals("role=BUTTON[name:Submit,enabled,expanded]",
                describe(recorder, bp, recorder.elementByRole(bp, AriaRole.BUTTON, "Submit",
                        RoleOptions.defaults().enabledOnly().expanded())),
                "状态选项须体现在描述串中（报告可区分同一角色的不同定位器）");
    }

    @Test
    void elementByRole_pattern_recordsPatternDescription() {
        SerenityPageRecorder recorder = new SerenityPageRecorder();

        recorder.elementByRole(new FakePage(), AriaRole.HEADING, Pattern.compile("Welcome.*"), 2);

        assertEquals("role=HEADING[pattern:Welcome.*,level:2]", lastElement(recorder),
                "层级须体现在描述串中（同一正则的不同层级是两个不同定位器）");
    }

    @Test
    void elementByRole_patternWithExact_recordsExactMarker() {
        SerenityPageRecorder recorder = new SerenityPageRecorder();
        FakePage bp = new FakePage();

        recorder.elementByRole(bp, AriaRole.HEADING, Pattern.compile("Welcome.*"), true, 2);
        assertEquals("role=HEADING[pattern:Welcome.*,exact,level:2]", lastElement(recorder));

        recorder.elementByRole(bp, AriaRole.HEADING, Pattern.compile("Welcome.*"),
                RoleOptions.defaults().partial().collapsed());
        assertEquals("role=HEADING[pattern:Welcome.*,collapsed]", lastElement(recorder));
    }

    @Test
    void elementByRoleKey_recordsNlsDescriptionWithPrimaryFile() {
        SerenityPageRecorder recorder = new SerenityPageRecorder();

        PageElement element = recorder.elementByRoleKey(new FakePage(), NlsPage.class, AriaRole.TEXTBOX, "username", 0);

        assertNotNull(element);
        assertEquals("role=TEXTBOX[nls:nls/role-locator-test.nls.json#username]", lastElement(recorder));
    }

    @Test
    void elementByRoleKey_withoutRoleFile_reportsActionableError() {
        SerenityPageRecorder recorder = new SerenityPageRecorder();

        ElementException ex = assertThrows(ElementException.class,
                () -> recorder.elementByRoleKey(new FakePage(), PlainPage.class, AriaRole.TEXTBOX, "username", 0));

        assertEquals(true, ex.getMessage().contains("PlainPage"), "报错须指出缺少 @RoleFile 的页面类");
    }

    @Test
    void elementsByRole_recordsAndReturnsFrameworkList() {
        SerenityPageRecorder recorder = new SerenityPageRecorder();
        FakePage bp = new FakePage();

        PageElementList list = recorder.elementsByRole(bp, AriaRole.LISTITEM);
        assertNotNull(list);
        assertEquals("role=LISTITEM[no-name]", lastElement(recorder));

        recorder.elementsByRole(bp, AriaRole.BUTTON, "Submit");
        assertEquals("role=BUTTON[name:Submit]", lastElement(recorder));
    }

    private static String describe(SerenityPageRecorder recorder, FakePage bp, PageElement element) {
        assertNotNull(element, "role locating must return a framework PageElement");
        return lastElement(recorder);
    }
}
