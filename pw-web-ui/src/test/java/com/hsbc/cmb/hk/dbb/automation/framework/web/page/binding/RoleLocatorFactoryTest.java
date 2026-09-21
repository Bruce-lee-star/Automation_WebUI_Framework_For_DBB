package com.hsbc.cmb.hk.dbb.automation.framework.web.page.binding;

import com.hsbc.cmb.hk.dbb.automation.framework.web.exceptions.ElementException;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.element.RoleElement;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.element.RoleFile;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.element.RoleOptions;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.engine.BasePage;
import com.hsbc.cmb.hk.dbb.automation.framework.web.utils.NLSUtils;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.AriaRole;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 角色定位原语（{@link RoleLocatorFactory}）单测：无浏览器，纯 Mockito 隔离 BasePage/Page/Locator。
 *
 * <p>覆盖三件事：
 * <ol>
 *   <li>{@code @RoleFile} 文件解析（声明顺序 / primary 提升 / file 覆盖 / 缺失报错诊断）；</li>
 *   <li>多语言懒解析契约——切语言后同一 supplier 解析到新语言值（运行期 API 与注解路径共用的核心语义）；</li>
 *   <li>三条名称来源（字面名 / 正则 / nls 值）对 {@code getByRole} 的委托，以及描述串格式
 *       （报告测试数据、失败截图命名依赖该格式，故逐字钉住）。</li>
 * </ol>
 */
class RoleLocatorFactoryTest {

    private static final String FILE_A = "nls/role-locator-test.nls.json";
    private static final String FILE_B = "nls/role-locator-secondary.nls.json";

    /** 类级声明两个文件（无 primary）：保持声明顺序。 */
    @RoleFile({FILE_A, FILE_B})
    static class TwoFilePage {
    }

    /** primary 指向第二个文件：解析时需被提升到首位。 */
    @RoleFile(value = {FILE_A, FILE_B}, primary = FILE_B)
    static class PrimarySecondPage {
    }

    /** 单文件声明：供 NLS 值解析使用（文件存在于测试 classpath）。 */
    @RoleFile(FILE_A)
    static class SingleFilePage {
    }

    /** 无 {@code @RoleFile}：解析应给出可操作的报错诊断。 */
    static class NoRoleFilePage {
    }

    @AfterEach
    void resetNls() {
        NLSUtils.reset();
    }

    // ==================== @RoleFile 解析 ====================

    @Test
    void resolveFiles_keepsDeclarationOrder() {
        assertEquals(List.of(FILE_A, FILE_B), RoleLocatorFactory.resolveFiles(TwoFilePage.class, "", "ctx"));
    }

    @Test
    void resolveFiles_promotesPrimaryToFirst() {
        assertEquals(List.of(FILE_B, FILE_A),
                RoleLocatorFactory.resolveFiles(PrimarySecondPage.class, "", "ctx"),
                "primary 声明的文件必须被提升到首位（跨文件查找的优先序）");
    }

    @Test
    void resolveFiles_fileOverrideWinsOverClassLevel() {
        assertEquals(List.of("nls/custom.json"),
                RoleLocatorFactory.resolveFiles(TwoFilePage.class, "nls/custom.json", "ctx"),
                "字段/调用级 file() 覆盖类级声明");
    }

    @Test
    void resolveFiles_missingRoleFile_reportsActionableError() {
        ElementException ex = assertThrows(ElementException.class,
                () -> RoleLocatorFactory.resolveFiles(NoRoleFilePage.class, "", "RoleElement field 'username'"));

        assertTrue(ex.getMessage().contains("RoleElement field 'username'"), "报错须带上定位上下文");
        assertTrue(ex.getMessage().contains("@RoleFile"), "报错须提示缺少类级 @RoleFile");
        assertTrue(ex.getMessage().contains("NoRoleFilePage"), "报错须指出具体页面类");
    }

    // ==================== 多语言懒解析 ====================

    @Test
    void nlsValueSupplier_resolvesCurrentLanguage_lazily() {
        Supplier<String> value = RoleLocatorFactory.nlsValueSupplier(SingleFilePage.class, "", "signIn");

        NLSUtils.setLanguage("en-US");
        assertEquals("Sign in", value.get());

        NLSUtils.setLanguage("zh-CN");
        assertEquals("登入", value.get(), "切语言后同一 supplier 必须解析到新语言值（懒解析契约）");
    }

    @Test
    void nlsValueSupplier_nullOverride_usesClassLevelSingleFile() {
        Supplier<String> value = RoleLocatorFactory.nlsValueSupplier(SingleFilePage.class, null, "welcomeHeader");
        NLSUtils.setLanguage("zh-CN");
        assertEquals("欢迎使用商务理财", value.get());
    }

    // ==================== 定位原语委托 ====================

    @Test
    void byRole_delegatesToGetByRole_withoutOptions() {
        Page page = mock(Page.class);
        Locator locator = mock(Locator.class);
        when(page.getByRole(AriaRole.BUTTON)).thenReturn(locator);

        assertEquals(locator, RoleLocatorFactory.byRole(bpWith(page), AriaRole.BUTTON));
        verify(page).getByRole(AriaRole.BUTTON);
    }

    @Test
    void byName_delegatesToGetByRoleWithResolvedOptions() {
        Page page = mock(Page.class);
        Locator locator = mock(Locator.class);
        when(page.getByRole(any(AriaRole.class), any(Page.GetByRoleOptions.class))).thenReturn(locator);

        assertEquals(locator, RoleLocatorFactory.byName(bpWith(page), AriaRole.TEXTBOX, "Username", true, 0,
                RoleElement.State.ANY, RoleElement.State.ANY, RoleElement.State.ANY));
        verify(page).getByRole(any(AriaRole.class), any(Page.GetByRoleOptions.class));
    }

    @Test
    void byPattern_delegatesToGetByRoleWithPatternOptions() {
        Page page = mock(Page.class);
        Locator locator = mock(Locator.class);
        when(page.getByRole(any(AriaRole.class), any(Page.GetByRoleOptions.class))).thenReturn(locator);

        assertEquals(locator, RoleLocatorFactory.byPattern(bpWith(page), AriaRole.HEADING,
                Pattern.compile("Welcome.*"), 2, RoleElement.State.ANY, RoleElement.State.ANY, RoleElement.State.ANY));
        verify(page).getByRole(any(AriaRole.class), any(Page.GetByRoleOptions.class));
    }

    @Test
    void byNlsValue_templateValue_andPlainValue_bothLocateNamed() {
        Page page = mock(Page.class);
        Locator locator = mock(Locator.class);
        when(page.getByRole(any(AriaRole.class), any(Page.GetByRoleOptions.class))).thenReturn(locator);
        BasePage bp = bpWith(page);

        // 模板值（含 {{var}}）→ 编译为正则
        assertNotNull(RoleLocatorFactory.byNlsValue(bp, AriaRole.STATUS, "Notification for {{deviceModel}}",
                true, 0, RoleElement.State.ANY, RoleElement.State.ANY, RoleElement.State.ANY));
        // 普通值（含 HTML 实体）→ 归一为可见文本
        assertNotNull(RoleLocatorFactory.byNlsValue(bp, AriaRole.BUTTON, "&nbsp;Sign in",
                true, 0, RoleElement.State.ANY, RoleElement.State.ANY, RoleElement.State.ANY));
    }

    // ==================== 描述串（报告/截图命名） ====================

    @Test
    void describe_formatsAreStable() {
        assertEquals("role=BUTTON[no-name]", RoleLocatorFactory.describeRole(AriaRole.BUTTON));
        assertEquals("role=BUTTON[name:Submit]", RoleLocatorFactory.describeName(AriaRole.BUTTON, "Submit"));
        assertEquals("role=HEADING[pattern:Welcome.*]",
                RoleLocatorFactory.describePattern(AriaRole.HEADING, Pattern.compile("Welcome.*"), false));
        assertEquals("role=HEADING[pattern:Welcome.*,exact]",
                RoleLocatorFactory.describePattern(AriaRole.HEADING, Pattern.compile("Welcome.*"), true),
                "exact 必须在描述串中可见，报告/截图才能区分两种匹配方式");
        assertEquals("role=TEXTBOX[nls:nls/login.nls.json#username]",
                RoleLocatorFactory.describeKey(AriaRole.TEXTBOX, "nls/login.nls.json", "username"));
    }

    // ==================== exact 语义（整串锚定） ====================
    @Test
    void resolvePattern_exact_anchorsWholeString() {
        Pattern anchored = RoleLocatorFactory.resolvePattern(Pattern.compile("Sign\\s+in"), true);
        assertEquals("^(?:Sign\\s+in)$", anchored.pattern(),
                "exact=true 须锚定为整串匹配（Playwright 原生对正则忽略 exact，由框架显式落地）");
    }

    @Test
    void resolvePattern_inexact_keepsPatternAsIs() {
        assertEquals("Sign.*", RoleLocatorFactory.resolvePattern(Pattern.compile("Sign.*"), false).pattern());
    }

    @Test
    void resolvePattern_preservesFlags_andRejectsNull() {
        Pattern anchored = RoleLocatorFactory.resolvePattern(
                Pattern.compile("sign in", Pattern.CASE_INSENSITIVE | Pattern.DOTALL), true);

        assertEquals(Pattern.CASE_INSENSITIVE | Pattern.DOTALL, anchored.flags(), "锚定不得丢失原正则标志位");
        assertThrows(ElementException.class, () -> RoleLocatorFactory.resolvePattern(null, true));
    }

    // ==================== 语义化选项（RoleOptions） ====================

    @Test
    void roleOptions_defaults_areUnrestricted() {
        RoleOptions defaults = RoleOptions.defaults();

        assertTrue(defaults.isExact(), "默认精确匹配");
        assertEquals(0, defaults.level(), "默认不限定标题层级");
        assertEquals(RoleElement.State.ANY, defaults.disabledState(), "默认不限定 available/disabled");
        assertEquals(RoleElement.State.ANY, defaults.pressedState());
        assertEquals(RoleElement.State.ANY, defaults.expandedState());
    }

    @Test
    void roleOptions_semanticMethods_mapToThreeState() {
        RoleOptions options = RoleOptions.defaults().partial().level(2)
                .enabledOnly().notPressed().collapsed();

        assertFalse(options.isExact());
        assertEquals(2, options.level());
        assertEquals(RoleElement.State.NO, options.disabledState(), "enabledOnly 即 aria-disabled=false");
        assertEquals(RoleElement.State.NO, options.pressedState(), "notPressed 即 aria-pressed=false");
        assertEquals(RoleElement.State.NO, options.expandedState(), "collapsed 即 aria-expanded=false");
    }

    @Test
    void describeOptionsSuffix_marksOnlyConfiguredStates() {
        assertEquals("", RoleLocatorFactory.describeOptionsSuffix(null), "空选项不产生后缀");
        assertEquals("", RoleLocatorFactory.describeOptionsSuffix(RoleOptions.defaults()));
        assertEquals(",level:2", RoleLocatorFactory.describeOptionsSuffix(RoleOptions.defaults().level(2)));
        assertEquals(",enabled", RoleLocatorFactory.describeOptionsSuffix(RoleOptions.defaults().enabledOnly()));
        assertEquals(",disabled", RoleLocatorFactory.describeOptionsSuffix(RoleOptions.defaults().disabledOnly()));
        assertEquals(",pressed", RoleLocatorFactory.describeOptionsSuffix(RoleOptions.defaults().pressed()));
        assertEquals(",not-pressed", RoleLocatorFactory.describeOptionsSuffix(RoleOptions.defaults().notPressed()));
        assertEquals(",expanded", RoleLocatorFactory.describeOptionsSuffix(RoleOptions.defaults().expanded()));
        assertEquals(",collapsed", RoleLocatorFactory.describeOptionsSuffix(RoleOptions.defaults().collapsed()));
        assertEquals(",level:3,pressed,expanded",
                RoleLocatorFactory.describeOptionsSuffix(RoleOptions.defaults().level(3).pressed().expanded()),
                "后缀顺序固定：层级 → disabled → pressed → expanded");
    }

    private static BasePage bpWith(Page page) {
        BasePage bp = mock(BasePage.class);
        when(bp.getPage()).thenReturn(page);
        when(bp.getCurrentFrame()).thenReturn(null);
        return bp;
    }
}
