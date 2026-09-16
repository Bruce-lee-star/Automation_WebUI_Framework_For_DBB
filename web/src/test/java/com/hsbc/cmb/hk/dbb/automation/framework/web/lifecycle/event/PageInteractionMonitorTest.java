package com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.event;

import com.hsbc.cmb.hk.dbb.automation.framework.web.config.WebFrameworkConfig;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Dialog;
import com.microsoft.playwright.FileChooser;
import com.microsoft.playwright.Frame;
import com.microsoft.playwright.Page;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 回归护盾：锁定 {@link PageInteractionMonitor} 的页面级交互事件监听<b>注册行为、配置门控与处理器语义</b>。
 *
 * <p>覆盖：
 * <ul>
 *   <li>{@code register(BrowserContext)} 经 {@code context.onPage} 接线，并对新页面传播交互监听；</li>
 *   <li>{@code register(Page)} 按配置条件化：默认（dialog=dismiss）注册 onFrameNavigated/onPopup/onDialog，
 *       不注册 onFileChooser（默认关闭）；policy=ignore 时不注册 onDialog；fileChooser 启用时注册 onFileChooser；</li>
 *   <li>onDialog 默认按 dismiss 处置；业务声明 accept 时按声明处置；</li>
 *   <li>onFrameNavigated 记录主框架导航轨迹，drain 可回放；</li>
 *   <li>onPopup 记录未受管弹窗，markPopupClaimed 后 drain 清空；</li>
 *   <li>onFileChooser 从配置目录自动 setFiles（单文件上传 / 多文件（无 glob）判歧义取消）；resolveUploadCandidates 纯逻辑；</li>
 *   <li>null 安全（注册与处理器均不抛异常）。</li>
 * </ul>
 *
 * <p>纯 Mockito 隔离，不依赖浏览器运行时；与 {@link PageInteractionMonitor} 同包以访问其 package-private 静态方法。
 */
@SuppressWarnings("unchecked")
public class PageInteractionMonitorTest {

    // ===================== 注册接缝 =====================

    @Test
    public void registerContextWiresOnPageAndPropagatesInteractions() {
        BrowserContext context = mock(BrowserContext.class);
        org.mockito.ArgumentCaptor<Consumer<Page>> captor = forClass(Consumer.class);

        PageInteractionMonitor.register(context);

        verify(context, times(1)).onPage(captor.capture());

        Page newPage = mock(Page.class);
        captor.getValue().accept(newPage);

        // 默认 dialog=dismiss → onDialog 注册；fileChooser 默认关闭 → 不注册
        verify(newPage, times(1)).onFrameNavigated(any());
        verify(newPage, times(1)).onPopup(any());
        verify(newPage, times(1)).onDialog(any());
        verify(newPage, times(0)).onFileChooser(any());
    }

    @Test
    public void registerPageRegistersInteractionListeners() {
        Page page = mock(Page.class);
        PageInteractionMonitor.register(page);

        verify(page, times(1)).onFrameNavigated(any());
        verify(page, times(1)).onPopup(any());
        verify(page, times(1)).onDialog(any());
        verify(page, times(0)).onFileChooser(any());
    }

    // ===================== 6. onDialog =====================

    @Test
    public void dialogPolicyIgnoreDoesNotRegisterOnDialog() {
        WebFrameworkConfig.PLAYWRIGHT_PAGE_DIALOG_POLICY.setValue("ignore");
        try {
            Page page = mock(Page.class);
            PageInteractionMonitor.register(page);
            verify(page, times(0)).onDialog(any());
        } finally {
            WebFrameworkConfig.PLAYWRIGHT_PAGE_DIALOG_POLICY.setValue("dismiss");
        }
    }

    @Test
    public void onDialogDismissesByDefaultPolicy() {
        Page page = mock(Page.class);
        PageInteractionMonitor.register(page);

        org.mockito.ArgumentCaptor<Consumer<Dialog>> captor = forClass(Consumer.class);
        verify(page).onDialog(captor.capture());

        Dialog dialog = mock(Dialog.class);
        when(dialog.page()).thenReturn(page);
        when(dialog.type()).thenReturn("alert");
        when(dialog.message()).thenReturn("are you sure?");
        captor.getValue().accept(dialog);

        verify(dialog, times(1)).dismiss();
        verify(dialog, times(0)).accept();
    }

    @Test
    public void onDialogHonorsExplicitDeclaration() {
        Page page = mock(Page.class);
        PageInteractionMonitor.register(page);

        org.mockito.ArgumentCaptor<Consumer<Dialog>> captor = forClass(Consumer.class);
        verify(page).onDialog(captor.capture());

        // 业务显式声明接受 → 处理器优先按声明执行
        assertTrue(PageInteractionMonitor.declareDialogAction(page, true));

        Dialog dialog = mock(Dialog.class);
        when(dialog.page()).thenReturn(page);
        when(dialog.type()).thenReturn("confirm");
        when(dialog.message()).thenReturn("ok?");
        captor.getValue().accept(dialog);

        verify(dialog, times(1)).accept();
        verify(dialog, times(0)).dismiss();
    }

    // ===================== 1. onFrameNavigated =====================

    @Test
    public void onFrameNavigatedRecordsMainFrameIntoTrail() {
        Page page = mock(Page.class);
        PageInteractionMonitor.register(page);

        org.mockito.ArgumentCaptor<Consumer<Frame>> captor = forClass(Consumer.class);
        verify(page).onFrameNavigated(captor.capture());

        Frame frame = mock(Frame.class);
        when(frame.page()).thenReturn(page);
        when(page.mainFrame()).thenReturn(frame);
        when(frame.url()).thenReturn("https://example.com/dashboard");

        PageInteractionMonitor.drainNavigationTrail(); // 清空本线程残留
        captor.getValue().accept(frame);

        String trail = PageInteractionMonitor.drainNavigationTrail();
        assertTrue(trail.contains("[main] https://example.com/dashboard"), "trail=" + trail);
    }

    // ===================== 4. onPopup =====================

    @Test
    public void onPopupRecordedAndClaimedClears() {
        Page page = mock(Page.class);
        PageInteractionMonitor.register(page);

        org.mockito.ArgumentCaptor<Consumer<Page>> captor = forClass(Consumer.class);
        verify(page).onPopup(captor.capture());

        Page popup = mock(Page.class);
        when(popup.url()).thenReturn("https://example.com/popup");
        when(popup.title()).thenReturn("Report");

        PageInteractionMonitor.drainUnmanagedPopups(); // 清空残留
        captor.getValue().accept(popup);

        String recorded = PageInteractionMonitor.drainUnmanagedPopups();
        assertTrue(recorded.contains("url=https://example.com/popup"), "popups=" + recorded);

        // 框架认领后再次 drain 应为空（已在 markPopupClaimed 中剔除）
        PageInteractionMonitor.markPopupClaimed(popup);
        assertEquals("", PageInteractionMonitor.drainUnmanagedPopups());
    }

    // ===================== 7. onFileChooser =====================

    @Test
    public void fileChooserDisabledDoesNotRegisterListener() {
        assertFalse(PageInteractionMonitor.isFileChooserEnabled());
        Page page = mock(Page.class);
        PageInteractionMonitor.register(page);
        verify(page, times(0)).onFileChooser(any());
    }

    @Test
    public void onFileChooserUploadsSingleCandidate(@TempDir Path tempDir) throws Exception {
        java.nio.file.Files.write(tempDir.resolve("invoice.pdf"), new byte[]{1, 2, 3});

        WebFrameworkConfig.PLAYWRIGHT_PAGE_FILE_CHOOSER_ENABLED.setValue("true");
        WebFrameworkConfig.PLAYWRIGHT_PAGE_FILE_CHOOSER_DIR.setValue(tempDir.toString());
        try {
            Page page = mock(Page.class);
            PageInteractionMonitor.register(page);

            org.mockito.ArgumentCaptor<Consumer<FileChooser>> captor = forClass(Consumer.class);
            verify(page).onFileChooser(captor.capture());

            FileChooser fc = mock(FileChooser.class);
            captor.getValue().accept(fc);

            org.mockito.ArgumentCaptor<Path[]> filesCaptor = forClass(Path[].class);
            verify(fc).setFiles(filesCaptor.capture());
            Path[] uploaded = filesCaptor.getValue();
            assertEquals(1, uploaded.length);
            assertEquals(tempDir.resolve("invoice.pdf"), uploaded[0]);
        } finally {
            WebFrameworkConfig.PLAYWRIGHT_PAGE_FILE_CHOOSER_ENABLED.setValue("false");
        }
    }

    @Test
    public void onFileChooserDismissesWhenAmbiguous(@TempDir Path tempDir) throws Exception {
        java.nio.file.Files.write(tempDir.resolve("a.pdf"), new byte[]{1});
        java.nio.file.Files.write(tempDir.resolve("b.pdf"), new byte[]{2});

        WebFrameworkConfig.PLAYWRIGHT_PAGE_FILE_CHOOSER_ENABLED.setValue("true");
        WebFrameworkConfig.PLAYWRIGHT_PAGE_FILE_CHOOSER_DIR.setValue(tempDir.toString());
        try {
            Page page = mock(Page.class);
            PageInteractionMonitor.register(page);

            org.mockito.ArgumentCaptor<Consumer<FileChooser>> captor = forClass(Consumer.class);
            verify(page).onFileChooser(captor.capture());

            FileChooser fc = mock(FileChooser.class);
            captor.getValue().accept(fc);

            org.mockito.ArgumentCaptor<Path[]> filesCaptor = forClass(Path[].class);
            verify(fc).setFiles(filesCaptor.capture());
            assertEquals(0, filesCaptor.getValue().length, "ambiguous → must dismiss (empty array)");
        } finally {
            WebFrameworkConfig.PLAYWRIGHT_PAGE_FILE_CHOOSER_ENABLED.setValue("false");
        }
    }

    /** resolveUploadCandidates 纯逻辑：glob 多匹配确定性排序后全返回；无 glob 取全部常规文件。 */
    @Test
    public void resolveUploadCandidatesWithGlob(@TempDir Path tempDir) throws Exception {
        java.nio.file.Files.write(tempDir.resolve("a.txt"), new byte[]{1});
        java.nio.file.Files.write(tempDir.resolve("b.txt"), new byte[]{2});
        java.nio.file.Files.write(tempDir.resolve("c.csv"), new byte[]{3});

        List<Path> matched = PageInteractionMonitor.resolveUploadCandidates(tempDir, "*.txt");
        assertEquals(2, matched.size());
        assertEquals(tempDir.resolve("a.txt"), matched.get(0));
        assertEquals(tempDir.resolve("b.txt"), matched.get(1));

        List<Path> all = PageInteractionMonitor.resolveUploadCandidates(tempDir, "");
        assertEquals(3, all.size());

        assertEquals(0, PageInteractionMonitor.resolveUploadCandidates(tempDir.resolve("nope"), "").size());
    }

    // ===================== null 安全 =====================

    @Test
    public void nullSafeRegistrationAndHandlers() {
        PageInteractionMonitor.register((Page) null);
        PageInteractionMonitor.register((BrowserContext) null);

        // 处理器入参为 null 不应抛异常
        PageInteractionMonitor.markPopupClaimed(null);
        assertEquals("", PageInteractionMonitor.drainNavigationTrail());
        assertEquals("", PageInteractionMonitor.drainUnmanagedPopups());
        assertFalse(PageInteractionMonitor.declareDialogAction(null, true));
    }
}
