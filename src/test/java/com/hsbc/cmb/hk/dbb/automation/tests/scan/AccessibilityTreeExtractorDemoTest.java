package com.hsbc.cmb.hk.dbb.automation.tests.scan;

import com.hsbc.cmb.hk.dbb.automation.framework.web.page.scan.AccessibilityTreeExtractor;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.scan.AccessibilityTreeExtractor.AxNode;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.scan.I18nNameProvider;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.scan.ResourceBundleNameProvider;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 最小 demo 测试：在真实 Playwright 1.58 下验证 {@link AccessibilityTreeExtractor} 与
 * {@link ResourceBundleNameProvider} 的运行时行为（不依赖 Serenity / 框架 BasePage 初始化）。
 *
 * <p>覆盖本次交付的核心能力：
 * <ol>
 *   <li>{@code Locator.ariaSnapshot()} YAML 解析为中立 {@link AxNode} 树（1.58 已移除旧 accessibility API）；</li>
 *   <li>{@link AccessibilityTreeExtractor#enrichWithDomAttributes} 回读页面 {@code data-i18n} 等语言无关锚点；</li>
 *   <li>每语言<b>独立 NLS</b>（不同目录/文件）：切语言即切换专属资源包，key 可不对齐，缺失跳过；</li>
 *   <li>{@link ResourceBundleNameProvider#namesForKey(String)} 跨语言合并全部语言 name（生成跨语言稳定定位器）；</li>
 *   <li>{@link ResourceBundleNameProvider#namesForKeyInLocale(String, Locale)} 切到某语言单独看该语言 NLS；</li>
 *   <li>{@link AccessibilityTreeExtractor#printInteractiveElements} 输出可交互元素表（含多语言 name）。</li>
 * </ol>
 *
 * <p>运行：{@code mvn -o test -Dtest=AccessibilityTreeExtractorDemoTest}
 */
public class AccessibilityTreeExtractorDemoTest {

    private static Playwright playwright;
    private static Browser browser;
    private static Page page;

    /** 一段含 {@code data-i18n} 语言无关锚点的演示页面（按钮当前语言文本为英文）。 */
    private static final String DEMO_HTML = "<!DOCTYPE html><html><head><meta charset='utf-8'></head><body>"
            + "<button data-i18n='button_next'>Next</button>"
            + "<a href='#' data-i18n='link_forgot_password'>Forgot password</a>"
            + "<input type='text' data-i18n='label_username' aria-label='Username' />"
            + "<div class='banner'>Static, non-interactive text</div>"
            + "</body></html>";

    /**
     * 每语言独立 NLS 映射：切语言即切到对应专属资源包（不同目录、key 可不对齐）。
     * en 在 i18n/en，zh 在 i18n/zh；zh 故意缺 label_username 以演示缺失跳过。
     */
    private static ResourceBundleNameProvider independentNls() {
        return new ResourceBundleNameProvider(Map.of(
                Locale.ENGLISH, "i18n/en/messages",
                new Locale("zh"), "i18n/zh/messages"));
    }

    @BeforeClass
    public static void setUp() {
        playwright = Playwright.create();
        browser = playwright.chromium().launch();
        page = browser.newPage();
        page.setContent(DEMO_HTML);
    }

    @AfterClass
    public static void tearDown() {
        if (browser != null) {
            browser.close();
        }
        if (playwright != null) {
            playwright.close();
        }
    }

    /** 验证每语言独立 NLS：namesForKey 跨语言合并「英文 + 中文」全部可见名。 */
    @Test
    public void i18nProviderReturnsMultiLanguageNames() {
        I18nNameProvider i18n = independentNls();
        String[] names = i18n.namesForKey("button_next");
        Assert.assertNotNull("namesForKey must not return null", names);
        Assert.assertTrue("expected EN name 'Next', got: " + String.join(" | ", names), contains(names, "Next"));
        Assert.assertTrue("expected ZH name '下一步' (cross-language locator source), got: " + String.join(" | ", names),
                contains(names, "下一步"));
        System.out.println("[demo] namesForKey(button_next) = " + String.join(" | ", names));
    }

    /** 验证「切语言有不同 NLS」：不同语言加载各自独立资源包，缺失 key 静默跳过，跨语言仍合并全部。 */
    @Test
    public void switchingLanguageUsesDifferentNls() {
        ResourceBundleNameProvider i18n = independentNls();

        // 切到「英文 NLS」：专属资源包，含 EN 可见名
        String[] en = i18n.namesForKeyInLocale("button_next", Locale.ENGLISH);
        Assert.assertTrue("EN NLS should resolve button_next=Next", contains(en, "Next"));

        // 切到「中文 NLS」：不同的资源包（另一目录），key 集合可不对齐
        String[] zh = i18n.namesForKeyInLocale("button_next", new Locale("zh"));
        Assert.assertTrue("ZH NLS should resolve button_next=下一步", contains(zh, "下一步"));

        // 跨语言合并定位器：与「当前运行语言」无关，始终含全部语言 name
        String[] all = i18n.namesForKey("button_next");
        Assert.assertTrue(contains(all, "Next"));
        Assert.assertTrue(contains(all, "下一步"));

        // 关键演示：中文 NLS 缺少 label_username（不同 NLS 的 key 不对齐）
        // → 跨语言合并仅剩 EN 的 Username，绝不抛错、不阻断
        String[] username = i18n.namesForKey("label_username");
        Assert.assertTrue("cross-language merge should keep EN 'Username'", contains(username, "Username"));
        Assert.assertFalse("ZH NLS has no label_username -> cross-language merge must NOT contain 用户名",
                contains(username, "用户名"));
        System.out.println("[demo] switching language -> namesForKey(label_username) = " + String.join(" | ", username));
    }

    /** 验证 1.58 ariaSnapshot 解析 + DOM 富化 + 多语言打印 全链路（用每语言独立 NLS）。 */
    @Test
    public void snapshotEnrichAndPrint() {
        AxNode tree = AccessibilityTreeExtractor.snapshot(page);
        Assert.assertNotNull("ariaSnapshot parse returned null (Playwright 1.58 API)", tree);

        List<AxNode> interactive = AccessibilityTreeExtractor.interactiveElements(tree);
        Assert.assertTrue("should find >=3 interactive nodes (button/link/textbox); parsed=" + interactive.size(),
                interactive.size() >= 3);
        System.out.println("[demo] interactive node count = " + interactive.size());

        // DOM 富化：回读 data-i18n / tag 等语言无关锚点
        AccessibilityTreeExtractor.enrichWithDomAttributes(tree, page);

        AxNode nextBtn = findByI18nKey(interactive, "button_next");
        Assert.assertNotNull("button_next should be enriched from data-i18n", nextBtn);
        Assert.assertEquals("BUTTON", nextBtn.getDomTag());
        Assert.assertEquals("button_next", nextBtn.getI18nKey());

        I18nNameProvider i18n = independentNls();

        // 「看一眼页面」——可交互元素表（含多语言 name），直接打到 stdout
        System.out.println("[demo] ---- printInteractiveElements(tree, i18n) ----");
        AccessibilityTreeExtractor.printInteractiveElements(tree, i18n);

        // 多语言 name 解析正确（即跨语言定位器 element(role, "Next", "下一步") 的来源）
        String[] names = i18n.namesForKey("button_next");
        Assert.assertTrue(contains(names, "下一步"));
    }

    private static AxNode findByI18nKey(List<AxNode> nodes, String key) {
        for (AxNode n : nodes) {
            if (key.equals(n.getI18nKey())) {
                return n;
            }
        }
        return null;
    }

    private static boolean contains(String[] arr, String v) {
        if (arr == null) {
            return false;
        }
        for (String s : arr) {
            if (v.equals(s)) {
                return true;
            }
        }
        return false;
    }
}
