package com.hsbc.cmb.hk.dbb.automation.framework.web.page.element;

import com.hsbc.cmb.hk.dbb.automation.framework.web.exceptions.ElementOperationException;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.engine.BasePage;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.mockito.ArgumentCaptor;

/**
 * WEB-P1-5 种子测试：元素门面 {@code PageElement}（无浏览器，Mockito 隔离 BasePage/Page/Locator）。
 * 覆盖两种构造的入参守卫、选择器/页面访问器、以及底层 Locator 的三条解析路径
 * （动态 supplier / 页面选择器 / 组合定位 nth）。
 */
public class PageElementTest {

    private static BasePage bpWith(Page page) {
        BasePage bp = mock(BasePage.class);
        when(bp.getPage()).thenReturn(page);
        return bp;
    }

    // ---------- 入参守卫 ----------

    @Test
    public void supplierConstructor_rejectsNullSupplier() {
        BasePage bp = bpWith(mock(Page.class));
        assertThrows(IllegalArgumentException.class, () -> new PageElement(null, "desc", bp));
    }

    @Test
    public void supplierConstructor_rejectsBlankDescription() {
        BasePage bp = bpWith(mock(Page.class));
        assertThrows(IllegalArgumentException.class, () -> new PageElement(() -> mock(Locator.class), "  ", bp));
    }

    @Test
    public void supplierConstructor_rejectsNullPage() {
        assertThrows(IllegalArgumentException.class, () -> new PageElement(() -> mock(Locator.class), "desc", null));
    }

    @Test
    public void selectorConstructor_rejectsBlankSelector() {
        BasePage bp = bpWith(mock(Page.class));
        assertThrows(IllegalArgumentException.class, () -> new PageElement("  ", bp));
    }

    @Test
    public void selectorConstructor_rejectsNullPage() {
        assertThrows(IllegalArgumentException.class, () -> new PageElement("#sel", null));
    }

    // ---------- 访问器 ----------

    @Test
    public void getters_exposeSelectorAndOwningPage() {
        BasePage bp = bpWith(mock(Page.class));
        PageElement el = new PageElement("#login", bp);

        assertEquals("#login", el.getSelector());
        assertSame(bp, el.getPage());
    }

    // ---------- Locator 解析路径 ----------

    @Test
    public void locatorInternal_prefersDynamicSupplier() {
        BasePage bp = bpWith(mock(Page.class));
        Locator loc = mock(Locator.class);
        PageElement el = new PageElement(() -> loc, "role=button", bp);

        assertSame( loc,  el.locatorInternal(), "动态 supplier 优先，保证语言/页面切换后自动重解析");
        verify(bp).getPage();
    }

    @Test
    public void locatorInternal_fallsBackToPageSelectorResolution() {
        BasePage bp = bpWith(mock(Page.class));
        Locator loc = mock(Locator.class);
        when(bp.locatorInternal("#login")).thenReturn(loc);
        PageElement el = new PageElement("#login", bp);

        assertSame(loc, el.locatorInternal());
        verify(bp).locatorInternal("#login");
    }

    // ---------- 组合定位 ----------

    @Test
    public void nth_returnsNewElementWithIndexedSelector_andResolvesLazily() {
        BasePage bp = bpWith(mock(Page.class));
        PageElement el = new PageElement("#link", bp);

        PageElement second = el.nth(2);

        assertNotSame( el,  second, "nth 应返回新元素，不原地修改");
        assertTrue(
                second.getSelector().contains("nth=2"), "新元素选择器须带索引便于诊断：" + second.getSelector());
    }

    // ---------- 上传文件解析：resources 首选 / 用户路径兜底 / 多文件 ----------

    private static PageElement elWithLocator(BasePage bp, Locator loc) {
        when(bp.locatorInternal("#file")).thenReturn(loc);
        return new PageElement("#file", bp);
    }

    @Test
    public void uploadFile_prefersClasspathResource_whenPresent() {
        BasePage bp = bpWith(mock(Page.class));
        Locator loc = mock(Locator.class);
        PageElement el = elWithLocator(bp, loc);

        el.uploadFile("test-upload/sample.txt");

        ArgumentCaptor<Path[]> captor = ArgumentCaptor.forClass(Path[].class);
        verify(loc).setInputFiles(captor.capture(), any(Locator.SetInputFilesOptions.class));
        Path[] resolved = captor.getValue();
        assertEquals(1, resolved.length, "应解析出单个文件");
        assertTrue(Files.exists(resolved[0]), "解析出的文件应真实存在");
        assertTrue(resolved[0].toString().replace('\\', '/').endsWith("test-upload/sample.txt"),
                "应优先取自 resources(classpath)，而非用户字面路径：" + resolved[0]);
    }

    @Test
    public void uploadFile_normalizesBackslashSeparator_forClasspathResource() {
        BasePage bp = bpWith(mock(Page.class));
        Locator loc = mock(Locator.class);
        PageElement el = elWithLocator(bp, loc);

        // 资源名用 Windows 风格反斜杠；classpath 资源名恒为 '/'，框架须归一化后在任意系统命中
        el.uploadFile("test-upload\\sample.txt");

        ArgumentCaptor<Path[]> captor = ArgumentCaptor.forClass(Path[].class);
        verify(loc).setInputFiles(captor.capture(), any(Locator.SetInputFilesOptions.class));
        Path[] resolved = captor.getValue();
        assertEquals(1, resolved.length);
        assertTrue(resolved[0].toString().replace('\\', '/').endsWith("test-upload/sample.txt"),
                "反斜杠资源名应归一化并从 resources 命中：" + resolved[0]);
    }

    @Test
    public void uploadFile_fallsBackToUserSpecifiedPath_whenResourceMissing(@TempDir Path tempDir) throws IOException {
        BasePage bp = bpWith(mock(Page.class));
        Locator loc = mock(Locator.class);
        PageElement el = elWithLocator(bp, loc);

        Path userFile = tempDir.resolve("user-upload.txt");
        Files.writeString(userFile, "hello");

        el.uploadFile(userFile.toAbsolutePath().toString());

        ArgumentCaptor<Path[]> captor = ArgumentCaptor.forClass(Path[].class);
        verify(loc).setInputFiles(captor.capture(), any(Locator.SetInputFilesOptions.class));
        Path[] resolved = captor.getValue();
        assertEquals(1, resolved.length);
        assertEquals(userFile.toAbsolutePath().toString(), resolved[0].toAbsolutePath().toString(),
                "resources 未命中时应退回用户指定路径");
    }

    @Test
    public void uploadFile_normalizesSeparator_forUserSpecifiedPath(@TempDir Path tempDir) throws IOException {
        BasePage bp = bpWith(mock(Page.class));
        Locator loc = mock(Locator.class);
        PageElement el = elWithLocator(bp, loc);

        // 用户指定路径用正斜杠书写；框架按当前系统分隔符归一化后仍能命中（跨系统）
        Path userFile = tempDir.resolve("sub").resolve("user3.txt");
        Path userDir = userFile.getParent();   // 无父目录时为 null（SpotBugs NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE）
        if (userDir == null) {
            throw new IllegalStateException("unexpected: no parent dir for " + userFile);
        }
        Files.createDirectories(userDir);
        Files.writeString(userFile, "x");
        String forwardSlashPath = userFile.toString().replace('\\', '/');

        el.uploadFile(forwardSlashPath);

        ArgumentCaptor<Path[]> captor = ArgumentCaptor.forClass(Path[].class);
        verify(loc).setInputFiles(captor.capture(), any(Locator.SetInputFilesOptions.class));
        Path[] resolved = captor.getValue();
        assertEquals(1, resolved.length);
        assertEquals(userFile.toAbsolutePath().toString(), resolved[0].toAbsolutePath().toString(),
                "用户指定路径应经系统分隔符归一化后命中：" + resolved[0]);
    }

    @Test
    public void uploadFile_throwsWhenNeitherResourceNorUserPathExists() {
        BasePage bp = bpWith(mock(Page.class));
        Locator loc = mock(Locator.class);
        PageElement el = elWithLocator(bp, loc);

        ElementOperationException ex = assertThrows(ElementOperationException.class,
                () -> el.uploadFile("no/such/resource.txt"));
        assertTrue(ex.getMessage().contains("上传文件不存在"),
                "两端都找不到应抛明确异常：" + ex.getMessage());
        verify(loc, never()).setInputFiles(any(Path[].class), any(Locator.SetInputFilesOptions.class));
    }

    @Test
    public void uploadFile_resolvesMixedResourcesAndUserPaths_forMultiFile(@TempDir Path tempDir) throws IOException {
        BasePage bp = bpWith(mock(Page.class));
        Locator loc = mock(Locator.class);
        PageElement el = elWithLocator(bp, loc);

        Path userFile = tempDir.resolve("user2.txt");
        Files.writeString(userFile, "x");

        el.uploadFile("test-upload/sample.txt", userFile.toAbsolutePath().toString());

        ArgumentCaptor<Path[]> captor = ArgumentCaptor.forClass(Path[].class);
        verify(loc).setInputFiles(captor.capture(), any(Locator.SetInputFilesOptions.class));
        Path[] resolved = captor.getValue();
        assertEquals(2, resolved.length, "多文件应逐个解析为 2 个路径");
        assertTrue(resolved[0].toString().replace('\\', '/').endsWith("test-upload/sample.txt"),
                "第一个文件应取自 resources");
        assertEquals(userFile.toAbsolutePath().toString(), resolved[1].toAbsolutePath().toString(),
                "第二个文件（用户路径）应真实存在并按字面路径解析");
    }
}
