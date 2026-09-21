package com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link DownloadReportAttacher} 单元验证：
 * <ul>
 *   <li>已落盘源文件 → 复制到 {@code site/report-attachments/} 并返回 true（Serenity 嵌入为 best-effort，不影响结果）；</li>
 *   <li>源文件不存在 → 返回 false（不抛）。</li>
 * </ul>
 * 测试放在与本类同包（{@code framework.web.lifecycle}）以访问包级私有实现，符合框架"测试移入同包而非提升可见性"铁律。
 */
class DownloadReportAttacherTest {

    @Test
    void attachResolved_copiesToReportAttachmentsDirAndReturnsTrue() throws IOException {
        Path src = Files.createTempFile("export-", ".csv");
        Files.writeString(src, "col1,col2\n1,2\n");
        try {
            boolean ok = DownloadReportAttacher.attachResolved(src, "导出文件");
            assertTrue(ok, "attachResolved should return true for an existing source");

            Path archive = Paths.get(DownloadReportAttacher.REPORT_ATTACHMENTS_DIR,
                    "thread-" + Thread.currentThread().getId(), src.getFileName().toString());
            assertTrue(Files.exists(archive), "file must be archived under site/report-attachments/thread-<id>");
            assertEquals("col1,col2\n1,2\n", Files.readString(archive), "archived content must match source");
        } finally {
            Files.deleteIfExists(src);
            Path archive = Paths.get(DownloadReportAttacher.REPORT_ATTACHMENTS_DIR, src.getFileName().toString());
            Files.deleteIfExists(archive);
        }
    }

    @Test
    void attachResolved_returnsFalseWhenSourceMissing() {
        Path missing = Paths.get("target/downloads/thread-999/does-not-exist.csv");
        assertFalse(DownloadReportAttacher.attachResolved(missing, "x"),
                "should return false when source does not exist");
    }
}
