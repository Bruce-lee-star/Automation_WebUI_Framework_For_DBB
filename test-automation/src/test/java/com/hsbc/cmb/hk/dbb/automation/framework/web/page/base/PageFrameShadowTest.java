package com.hsbc.cmb.hk.dbb.automation.framework.web.page.base;

import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.config.PlaywrightConfigManager;
import com.microsoft.playwright.Frame;
import com.microsoft.playwright.Page;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PageFrameShadow 委派类行为护盾（T5-5 模块 4）：验证 frame/shadow 切换正确转发到
 * BasePage 上下文 seam（activateFrame/deactivateFrame/pushShadow/popShadow/clearShadows）
 * 及 Playwright Page（getPage）API；纯 Mockito 隔离、不依赖浏览器。
 *
 * <p>本测试与 BasePage 同包，以便对包级私有的 seam 做白盒校验（verify(bp).activateFrame(...) 等）。
 */
public class PageFrameShadowTest {

    private static BasePage mockBp() {
        BasePage bp = mock(BasePage.class);
        when(bp.getPage()).thenReturn(mock(Page.class));
        PlaywrightConfigManager cfg = mock(PlaywrightConfigManager.class);
        when(cfg.getNavigationTimeout()).thenReturn(1000);
        when(bp.getConfig()).thenReturn(cfg);
        return bp;
    }

    @Test
    public void getFrame_delegatesToPage() {
        BasePage bp = mockBp();
        Frame f = mock(Frame.class);
        when(bp.getPage().frame("f")).thenReturn(f);
        assertSame(f, PageFrameShadow.getFrame(bp, "f"));
        verify(bp.getPage()).frame("f");
    }

    @Test
    public void switchToFrame_activatesMatchedFrame() {
        BasePage bp = mockBp();
        Frame f = mock(Frame.class);
        when(bp.getPage().frame("f")).thenReturn(f);
        assertSame(f, PageFrameShadow.switchToFrame(bp, "f"));
        verify(bp).activateFrame(f);
    }

    @Test
    public void switchToFrame_byIndex_activatesSelectedFrame() {
        BasePage bp = mockBp();
        Frame f0 = mock(Frame.class);
        Frame f1 = mock(Frame.class);
        when(bp.getPage().frames()).thenReturn(Arrays.asList(f0, f1));
        assertSame(f1, PageFrameShadow.switchToFrame(bp, 1));
        verify(bp).activateFrame(f1);
    }

    @Test(expected = IndexOutOfBoundsException.class)
    public void switchToFrame_byIndex_throwsOnOutOfRange() {
        BasePage bp = mockBp();
        when(bp.getPage().frames()).thenReturn(Arrays.asList(mock(Frame.class)));
        PageFrameShadow.switchToFrame(bp, 5);
    }

    @Test
    public void switchToShadow_pushesTrimmedHost() {
        BasePage bp = mockBp();
        PageFrameShadow.switchToShadow(bp, " #h ");
        verify(bp).pushShadow("#h");
    }

    @Test(expected = RuntimeException.class)
    public void switchToShadow_rejectsBlank() {
        BasePage bp = mockBp();
        PageFrameShadow.switchToShadow(bp, "   ");
    }

    @Test
    public void switchToDefaultShadow_popsViaSeam() {
        BasePage bp = mockBp();
        when(bp.popShadow()).thenReturn("host");
        assertEquals("host", PageFrameShadow.switchToDefaultShadow(bp));
        verify(bp).popShadow();
    }

    @Test
    public void switchToDefaultShadowAll_clearsShadows() {
        BasePage bp = mockBp();
        PageFrameShadow.switchToDefaultShadowAll(bp);
        verify(bp).clearShadows();
    }

    @Test
    public void switchToDefaultContent_deactivatesFrame() {
        BasePage bp = mockBp();
        PageFrameShadow.switchToDefaultContent(bp);
        verify(bp).deactivateFrame();
    }

    @Test
    public void getAllFrames_delegatesToPage() {
        BasePage bp = mockBp();
        List<Frame> frames = Arrays.asList(mock(Frame.class));
        when(bp.getPage().frames()).thenReturn(frames);
        assertSame(frames, PageFrameShadow.getAllFrames(bp));
    }

    @Test
    public void executeInFrame_runsActionWithFrame() {
        BasePage bp = mockBp();
        Frame f = mock(Frame.class);
        when(bp.getPage().frame("n")).thenReturn(f);
        final Frame[] captured = {null};
        PageFrameShadow.executeInFrame(bp, "n", fr -> captured[0] = fr);
        assertSame(f, captured[0]);
    }

    @Test(expected = RuntimeException.class)
    public void executeInFrame_throwsWhenFrameMissing() {
        BasePage bp = mockBp();
        when(bp.getPage().frame("n")).thenReturn(null);
        PageFrameShadow.executeInFrame(bp, "n", fr -> { });
    }

    // ---- switchToFrameAndWait：最复杂的并发/事件驱动路径，必须锁定行为 ----

    @Test
    public void switchToFrameAndWait_existingFrame_activatesImmediately() {
        BasePage bp = mockBp();
        Frame f = mock(Frame.class);
        when(bp.getPage().frame("f")).thenReturn(f);
        when(bp.getPage().frames()).thenReturn(Collections.singletonList(f));
        assertSame(f, PageFrameShadow.switchToFrameAndWait(bp, (Runnable) null, "f", 2));
        verify(bp).activateFrame(f);
    }

    @Test
    public void switchToFrameAndWait_listenerCapturesAttachedFrame_byName() {
        BasePage bp = mockBp();
        Page page = bp.getPage();
        when(page.frame("f")).thenReturn(null);
        when(page.frames()).thenReturn(Collections.emptyList());
        Frame attached = mock(Frame.class);
        when(attached.name()).thenReturn("f");
        doAnswer(inv -> {
            @SuppressWarnings("unchecked")
            Consumer<Frame> consumer = inv.getArgument(0, Consumer.class);
            consumer.accept(attached);
            return null;
        })
                .when(page).onFrameAttached(any());
        assertSame(attached, PageFrameShadow.switchToFrameAndWait(bp, () -> { }, "f", 2));
        verify(bp).activateFrame(attached);
        verify(page).offFrameAttached(any());
    }

    @Test
    public void switchToFrameAndWait_matchesByUrlFragment() {
        BasePage bp = mockBp();
        Page page = bp.getPage();
        when(page.frame("embedded")).thenReturn(null);
        when(page.frames()).thenReturn(Collections.emptyList());
        Frame attached = mock(Frame.class);
        when(attached.name()).thenReturn("other");                       // name 不匹配
        when(attached.url()).thenReturn("https://x/foo/embedded/view");  // url 含 "embedded"
        doAnswer(inv -> {
            @SuppressWarnings("unchecked")
            Consumer<Frame> consumer = inv.getArgument(0, Consumer.class);
            consumer.accept(attached);
            return null;
        })
                .when(page).onFrameAttached(any());
        assertSame(attached, PageFrameShadow.switchToFrameAndWait(bp, () -> { }, "embedded", 2));
        verify(bp).activateFrame(attached);
    }

    @Test
    public void switchToFrameAndWait_runsTriggerBeforeAwait() {
        BasePage bp = mockBp();
        Page page = bp.getPage();
        when(page.frame("f")).thenReturn(null);
        when(page.frames()).thenReturn(Collections.emptyList());
        Frame attached = mock(Frame.class);
        when(attached.name()).thenReturn("f");
        doAnswer(inv -> {
            @SuppressWarnings("unchecked")
            Consumer<Frame> consumer = inv.getArgument(0, Consumer.class);
            consumer.accept(attached);
            return null;
        })
                .when(page).onFrameAttached(any());
        Runnable trigger = mock(Runnable.class);
        PageFrameShadow.switchToFrameAndWait(bp, trigger, "f", 2);
        verify(trigger).run();
    }

    @Test(expected = RuntimeException.class)
    public void switchToFrameAndWait_timeout_throwsWhenNeverAttached() {
        BasePage bp = mockBp();
        Page page = bp.getPage();
        when(page.frame("f")).thenReturn(null);
        when(page.frames()).thenReturn(Collections.emptyList());
        PageFrameShadow.switchToFrameAndWait(bp, () -> { }, "f", 1); // 1s 超时，listener 永不触发
    }
}
