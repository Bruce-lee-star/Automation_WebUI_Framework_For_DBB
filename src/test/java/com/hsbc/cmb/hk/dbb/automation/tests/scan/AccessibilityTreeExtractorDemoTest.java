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

/**
 * 最小 demo 测试：在真实 Playwright 1.58 下验证 {@link AccessibilityTreeExtractor} 与
 * {@link ResourceBundleNameProvider} 的运行时行为（不依赖 Serenity / 框架 BasePage 初始化）。
 *
 * <p>覆盖本次交付的核心能力：
 * <ol>
 *   <li>{@code Locator.ariaSnapshot()} YAML 解析为中立 {@link AxNode} 树（1.58 已移除旧 accessibility API）；</li>
 *   <li>{@link AccessibilityTreeExtractor#enrichWithDomAttributes} 回读页面 {@code data-i18n} 等语言无关锚点；</li>
 *   <li>{@link ResourceBundleNameProvider} 从 {@code messages_xx.properties} 按 key 取多语言可见名；</li>
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

    /** 验证资源包提供器能按 key 返回「英文 + 中文」多语言可见名。 */
    @Test
    public void i18nProviderReturnsMultiLanguageNames() {
        I18nNameProvider i18n = new ResourceBundleNameProvider("messages", Locale.ENGLISH, new Locale("zh"));
        String[] names = i18n.namesForKey("button_next");
        Assert.assertNotNull("namesForKey must not return null", names);
        Assert.assertTrue("expected EN name 'Next', got: " + String.join(" | ", names), contains(names, "Next"));
        Assert.assertTrue("expected ZH name '下一步' (cross-language locator source), got: " + String.join(" | ", names),
                contains(names, "下一步"));
        System.out.println("[demo] namesForKey(button_next) = " + String.join(" | ", names));
    }

    /** 验证 1.58 ariaSnapshot 解析 + DOM 富化 + 多语言打印 全链路。 */
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

        I18nNameProvider i18n = new ResourceBundleNameProvider("messages", Locale.ENGLISH, new Locale("zh"));

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
