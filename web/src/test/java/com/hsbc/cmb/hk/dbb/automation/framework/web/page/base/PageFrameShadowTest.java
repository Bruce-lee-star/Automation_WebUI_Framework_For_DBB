package com.hsbc.cmb.hk.dbb.automation.framework.web.page.base;

import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.config.PlaywrightConfigManager;
import com.microsoft.playwright.Frame;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * WEB-P1-5 种子测试：frame / shadow 切换子模块（无浏览器，纯 Mockito 隔离 BasePage/Page/Frame）。
 * 覆盖 null/空白入参守卫、按名与按索引切换、shadow 入栈/出栈/清空、切换回主文档、在 frame 内执行动作。
 */
public class PageFrameShadowTest {

    private static BasePage bpWith(Page page) {
        BasePage bp = mock(BasePage.class);
        when(bp.getPage()).thenReturn(page);
        return bp;
    }

    @Test
    public void getFrame_nullBp_throwsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class, () -> PageFrameShadow.getFrame(null, "n"));
    }

    @Test
    public void getFrame_delegatesToPageFrameLookup() {
        Page page = mock(Page.class);
        Frame frame = mock(Frame.class);
        when(page.frame("main")).thenReturn(frame);
        BasePage bp = bpWith(page);

        assertEquals(frame, PageFrameShadow.getFrame(bp, "main"));
        verify(page).frame("main");
    }

    @Test
    public void switchToFrame_byName_looksUpFrameAndActivates() {
        Page page = mock(Page.class);
        Frame frame = mock(Frame.class);
        when(page.frame("main")).thenReturn(frame);
        BasePage bp = bpWith(page);

        assertEquals(frame, PageFrameShadow.switchToFrame(bp, "main"));
        verify(bp).activateFrame(frame);
    }

    @Test
    public void switchToFrame_blankSelector_throwsIllegalArgumentException() {
        BasePage bp = bpWith(mock(Page.class));
        assertThrows(IllegalArgumentException.class, () -> PageFrameShadow.switchToFrame(bp, "   "));
    }

    @Test
    public void switchToFrame_notFound_throwsRuntimeException() {
        Page page = mock(Page.class);
        Locator loc = mock(Locator.class);
        when(page.frame("missing")).thenReturn(null);
        when(page.locator(anyString())).thenReturn(loc);
        when(loc.elementHandle()).thenReturn(null);
        when(page.frames()).thenReturn(List.of());
        BasePage bp = bpWith(page);

        assertThrows(RuntimeException.class, () -> PageFrameShadow.switchToFrame(bp, "missing"));
    }

    @Test
    public void switchToFrame_byIndex_activatesSelectedFrame() {
        Page page = mock(Page.class);
        Frame f0 = mock(Frame.class);
        Frame f1 = mock(Frame.class);
        when(page.frames()).thenReturn(List.of(f0, f1));
        BasePage bp = bpWith(page);

        assertEquals(f1, PageFrameShadow.switchToFrame(bp, 1));
        verify(bp).activateFrame(f1);
    }

    @Test
    public void switchToFrame_byIndexOutOfRange_throwsIndexOutOfBounds() {
        Page page = mock(Page.class);
        when(page.frames()).thenReturn(List.of(mock(Frame.class)));
        BasePage bp = bpWith(page);

        assertThrows(IndexOutOfBoundsException.class, () -> PageFrameShadow.switchToFrame(bp, 5));
    }

    @Test
    public void switchToShadow_pushesShadowHost() {
        BasePage bp = bpWith(mock(Page.class));

        PageFrameShadow.switchToShadow(bp, "#host");

        verify(bp).pushShadow("#host");
    }

    @Test
    public void switchToShadow_blankHost_throwsRuntimeException() {
        BasePage bp = bpWith(mock(Page.class));
        assertThrows(RuntimeException.class, () -> PageFrameShadow.switchToShadow(bp, ""));
    }

    @Test
    public void switchToDefaultShadow_popsOneShadow() {
        BasePage bp = bpWith(mock(Page.class));
        when(bp.popShadow()).thenReturn("#host");

        assertEquals("#host", PageFrameShadow.switchToDefaultShadow(bp));
    }

    @Test
    public void switchToDefaultShadowAll_clearsAllShadows() {
        BasePage bp = bpWith(mock(Page.class));

        PageFrameShadow.switchToDefaultShadowAll(bp);

        verify(bp).clearShadows();
    }

    @Test
    public void switchToDefaultContent_deactivatesFrame() {
        BasePage bp = bpWith(mock(Page.class));

        PageFrameShadow.switchToDefaultContent(bp);

        verify(bp).deactivateFrame();
    }

    @Test
    public void getAllFrames_returnsPageFrames() {
        Page page = mock(Page.class);
        List<Frame> frames = List.of(mock(Frame.class));
        when(page.frames()).thenReturn(frames);
        BasePage bp = bpWith(page);

        assertEquals(frames, PageFrameShadow.getAllFrames(bp));
    }

    @Test
    public void executeInFrame_invokesActionWhenFrameFound() {
        Page page = mock(Page.class);
        Frame frame = mock(Frame.class);
        when(page.frame("main")).thenReturn(frame);
        BasePage bp = bpWith(page);
        java.util.concurrent.atomic.AtomicReference<Frame> seen = new java.util.concurrent.atomic.AtomicReference<>();

        PageFrameShadow.executeInFrame(bp, "main", seen::set);

        assertEquals(frame, seen.get());
    }

    @Test
    public void executeInFrame_notFound_throwsRuntimeException() {
        Page page = mock(Page.class);
        when(page.frame("nope")).thenReturn(null);
        BasePage bp = bpWith(page);

        assertThrows(RuntimeException.class, () -> PageFrameShadow.executeInFrame(bp, "nope", f -> {
        }));
    }

    @Test
    public void switchToFrameAndWait_alreadyAttached_returnsExistingFrameWithoutWaiting() {
        Page page = mock(Page.class);
        Frame frame = mock(Frame.class);
        when(page.frame("main")).thenReturn(frame);
        BasePage bp = bpWith(page);
        PlaywrightConfigManager config = mock(PlaywrightConfigManager.class);
        when(bp.getConfig()).thenReturn(config);
        when(config.getNavigationTimeout()).thenReturn(30000);

        assertEquals(frame, PageFrameShadow.switchToFrameAndWait(bp, "main"));
        verify(bp).activateFrame(frame);
    }
}
