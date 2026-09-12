package com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.bootstrap;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * resolveNonConflictingDownloadPath 同名去重单元测试（不启动浏览器）：
 * 验证「已存在则插入序号后缀、隐藏文件整体作主名、连续占用递增」等语义。
 */
public class PlaywrightContextManagerDownloadPathTest {

    @Test
    public void returnsOriginalWhenNotConflicting() throws Exception {
        Path dir = Files.createTempDirectory("pw-dl");
        Path result = PlaywrightContextManager.resolveNonConflictingDownloadPath(dir, "report.xlsx");
        assertEquals(dir.resolve("report.xlsx"), result);
    }

    @Test
    public void appendsSequenceWhenConflicting() throws Exception {
        Path dir = Files.createTempDirectory("pw-dl");
        Files.createFile(dir.resolve("report.xlsx"));
        Path result = PlaywrightContextManager.resolveNonConflictingDownloadPath(dir, "report.xlsx");
        assertEquals(dir.resolve("report (1).xlsx"), result);
    }

    @Test
    public void incrementsSequenceForMultipleConflicts() throws Exception {
        Path dir = Files.createTempDirectory("pw-dl");
        Files.createFile(dir.resolve("report.xlsx"));
        Files.createFile(dir.resolve("report (1).xlsx"));
        Path result = PlaywrightContextManager.resolveNonConflictingDownloadPath(dir, "report.xlsx");
        assertEquals(dir.resolve("report (2).xlsx"), result);
    }

    @Test
    public void keepsExtensionOnHiddenFile() throws Exception {
        Path dir = Files.createTempDirectory("pw-dl");
        // 点号在首位视为隐藏文件主名，不应拆出扩展名
        Files.createFile(dir.resolve(".env"));
        Path result = PlaywrightContextManager.resolveNonConflictingDownloadPath(dir, ".env");
        assertEquals(dir.resolve(".env (1)"), result);
    }

    @Test
    public void resolvesToRealFreePathOnDisk() throws Exception {
        Path dir = Files.createTempDirectory("pw-dl");
        Files.createFile(dir.resolve("a.xlsx"));
        Path result = PlaywrightContextManager.resolveNonConflictingDownloadPath(dir, "a.xlsx");
        assertTrue(!Files.exists(result), "去重结果应是一个磁盘上尚不存在的路径");
        // 清理临时目录
        Files.deleteIfExists(dir.resolve("a.xlsx"));
        File[] left = dir.toFile().listFiles();
        if (left != null) {
            for (File f : left) {
                f.delete();
            }
        }
        dir.toFile().delete();
    }
}
