package com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.bootstrap;

import com.hsbc.cmb.hk.dbb.automation.framework.common.logging.TestLogCapture;
import com.hsbc.cmb.hk.dbb.automation.framework.core.context.TestContextHolder;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.PlaywrightManager;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * onPage 归属判定单测（<b>不启动浏览器</b>）：固化「框架受管页 vs 额外页」判据与日志。
 *
 * <p><b>背景</b>：Playwright 的 {@code BrowserContext.onPage} 对 Context 内<b>任何</b>新页触发 ——
 * 既含框架自身 {@code createPage()} 的 {@code context.newPage()}（此刻尚未导航，url 恒为
 * {@code about:blank}），也含业务 {@code window.open}/{@code target=_blank} 弹窗、测试直接
 * {@code context.newPage()} 以及泄漏页。早期实现把它笼统记成 "detected via window.open()"，
 * 在并行逐 scenario 重建下被误读为「同一窗口多开了一个 about:blank 页」。</p>
 *
 * <p><b>本测试固化什么</b>：
 * <ol>
 *   <li>{@code createPage()} 期间触发（受管页）⇒ 判 {@code frameworkManaged=true}，
 *       about:blank 属「首次 navigate 前」的<b>预期</b>状态；</li>
 *   <li>非 {@code createPage()} 触发（额外页）⇒ 判 {@code frameworkManaged=false}；</li>
 *   <li>日志语义：额外页恒记 INFO（含 {@code aboutBlank} 标记）；仅当 Context 内页数 &gt;1 才追加
 *       WARN 与归属清单（{@code [managed]} = 框架登记页 / {@code [extra]} = 非登记页、新页标 {@code (new)}），
 *       避免把「首建受管页」的 about:blank 误报成多开页。</li>
 * </ol>
 *
 * <p>@apiNote 与 {@link PlaywrightContextManager} 同包，故可白盒驱动其包级私有判定能力
 * （{@code classifyNewPageEvent} / {@code logNewPageEvent} / {@code NewPageEvent}）。</p>
 */
public class PlaywrightContextManagerNewPageClassificationTest {

    /** 判定与日志为纯静态能力，但归属清单读用例级 PAGE_KEY，故本用例结束须复位，避免污染其它测试。 */
    @AfterEach
    public void clearRegisteredPage() {
        TestContextHolder.get().remove(PlaywrightManager.PAGE_KEY);
    }

    private static Page pageWithUrl(String url) {
        Page p = mock(Page.class);
        when(p.url()).thenReturn(url);
        when(p.isClosed()).thenReturn(false);
        return p;
    }

    private static BrowserContext contextOf(Page... pages) {
        BrowserContext ctx = mock(BrowserContext.class);
        when(ctx.pages()).thenReturn(new ArrayList<>(List.of(pages)));
        return ctx;
    }

    private static String captureLog(BrowserContext ctx, Page appeared, PlaywrightContextManager.NewPageEvent event) {
        try (TestLogCapture capture = TestLogCapture.of(PlaywrightContextManager.class, "%level %msg%n")) {
            PlaywrightContextManager.logNewPageEvent(ctx, appeared, event);
            return capture.content();
        }
    }

    @Test
    public void frameworkCreatedPageIsJudgedManagedAndAboutBlankIsExpected() {
        Page p = pageWithUrl("about:blank");
        BrowserContext ctx = contextOf(p);

        PlaywrightContextManager.NewPageEvent event =
                PlaywrightContextManager.classifyNewPageEvent(ctx, p, true);

        assertTrue(event.frameworkManaged, "createPage() 执行期间触发 ⇒ 应判为框架受管页");
        assertEquals(1, event.pageCount, "新 Context 首个受管页 ⇒ 页数应为 1");
        assertTrue(event.aboutBlank, "尚未 navigate ⇒ about:blank");
    }

    @Test
    public void pageCreatedOutsideFrameworkIsJudgedExtraEvenWhenAboutBlank() {
        Page first = pageWithUrl("about:blank");
        Page second = pageWithUrl("about:blank");
        BrowserContext ctx = contextOf(first, second);

        PlaywrightContextManager.NewPageEvent event =
                PlaywrightContextManager.classifyNewPageEvent(ctx, second, false);

        assertFalse(event.frameworkManaged, "非 createPage() 触发 ⇒ 应判为额外页（弹窗 / 泄漏）");
        assertEquals(2, event.pageCount, "同一 Context 已存在受管页 ⇒ 页数应为 2");
        assertTrue(event.aboutBlank, "额外页同样可能仍处于 about:blank（尚未导航）");
    }

    @Test
    public void extraPageLogsInfoAndWarnsWhenContextHoldsMoreThanOnePage() {
        Page first = pageWithUrl("about:blank");
        Page second = pageWithUrl("about:blank");
        BrowserContext ctx = contextOf(first, second);
        PlaywrightContextManager.NewPageEvent event =
                PlaywrightContextManager.classifyNewPageEvent(ctx, second, false);

        String captured = captureLog(ctx, second, event);

        assertTrue(captured.contains("INFO [multi-page] Extra page (not framework-managed)"),
                "额外页应恒记 INFO 并明确标注非框架受管：" + captured);
        assertTrue(captured.contains("aboutBlank=true"), "应输出 aboutBlank 标记供现场判定：" + captured);
        assertTrue(captured.contains("WARN [multi-page] Context=#") && captured.contains("now holds 2 pages (expected 1 managed)"),
                "页数 >1 应追加 WARN 与页数：" + captured);
        assertTrue(captured.contains("[extra] (new) about:blank"),
                "未登记受管页时两页均 [extra]，且新页标 (new)：" + captured);
    }

    @Test
    public void inventoryMarksPageRegisteredUnderScenarioKeyAsManaged() {
        Page registered = pageWithUrl("https://app.local/home");
        Page extra = pageWithUrl("about:blank");
        BrowserContext ctx = contextOf(registered, extra);
        TestContextHolder.get().set(PlaywrightManager.PAGE_KEY, registered);
        PlaywrightContextManager.NewPageEvent event =
                PlaywrightContextManager.classifyNewPageEvent(ctx, extra, false);

        String captured = captureLog(ctx, extra, event);

        assertTrue(captured.contains("[managed] https://app.local/home"),
                "用例级 PAGE_KEY 登记的页应标 [managed]：" + captured);
        assertTrue(captured.contains("[extra] (new) about:blank"),
                "新出现的额外页应标 [extra] (new)：" + captured);
    }

    @Test
    public void extraPageOnSinglePageContextLogsInfoWithoutMultiPageWarning() {
        Page only = pageWithUrl("about:blank");
        BrowserContext ctx = contextOf(only);
        PlaywrightContextManager.NewPageEvent event =
                PlaywrightContextManager.classifyNewPageEvent(ctx, only, false);

        String captured = captureLog(ctx, only, event);

        assertTrue(captured.contains("INFO [multi-page] Extra page (not framework-managed)"),
                "额外页仍应记 INFO：" + captured);
        assertFalse(captured.contains("now holds"),
                "页数=1 不构成「同 Context 多页」，不应触发 WARN：" + captured);
    }

    @Test
    public void contextThatLostItsPagesIsTreatedAsEmptyInsteadOfThrowing() {
        Page p = pageWithUrl("about:blank");
        BrowserContext ctx = mock(BrowserContext.class);
        when(ctx.pages()).thenThrow(new IllegalStateException("Target page, context or browser has been closed"));

        PlaywrightContextManager.NewPageEvent event =
                PlaywrightContextManager.classifyNewPageEvent(ctx, p, false);

        assertEquals(0, event.pageCount, "Context 已失效时页数应降级为 0（不抛异常），保证收尾期日志不反噬");
        assertEquals("about:blank", event.url);
    }
}
