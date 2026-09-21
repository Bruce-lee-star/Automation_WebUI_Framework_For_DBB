package com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

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
            Path archive = Paths.get(DownloadReportAttacher.REPORT_ATTACHMENTS_DIR,
                    "thread-" + Thread.currentThread().getId(), src.getFileName().toString());
            Files.deleteIfExists(archive);
        }
    }

    @Test
    void attachResolved_returnsFalseWhenSourceMissing() {
        Path missing = Paths.get("target/downloads/thread-999/does-not-exist.csv");
        assertFalse(DownloadReportAttacher.attachResolved(missing, "x"),
                "should return false when source does not exist");
    }

    // ===== #2 文件名清洗（包级私有方法直接验证） =====

    @Test
    void sanitizeFileName_replacesWindowsIllegalChars() {
        String got = DownloadReportAttacher.sanitizeFileName("report*:?a|b<c>.csv");
        assertEquals("report___a_b_c_.csv", got, "illegal chars must be replaced by underscore");
        assertFalse(got.matches(".*[\\\\/:*?\"<>|].*"), "archived name must not contain illegal chars: " + got);
    }

    @Test
    void sanitizeFileName_fallsBackForEmptyAndDotOnlyAndNull() {
        assertEquals("download", DownloadReportAttacher.sanitizeFileName(""));
        assertEquals("download", DownloadReportAttacher.sanitizeFileName("   "));
        assertEquals("download", DownloadReportAttacher.sanitizeFileName("..."));
        assertEquals("download", DownloadReportAttacher.sanitizeFileName(null));
    }

    @Test
    void sanitizeFileName_keepsLegalNameUnchanged() {
        assertEquals("export-123.csv", DownloadReportAttacher.sanitizeFileName("export-123.csv"));
    }

    // ===== #3 过期归档清理 =====

    @Test
    void cleanupStaleArchives_removesOnlyFilesOlderThanRetention() throws Exception {
        Path threadDir = Paths.get(DownloadReportAttacher.REPORT_ATTACHMENTS_DIR, "thread-cleantest");
        Files.createDirectories(threadDir);
        Path oldFile = threadDir.resolve("stale-old.csv");
        Path freshFile = threadDir.resolve("stale-fresh.csv");
        Files.writeString(oldFile, "old");
        Files.writeString(freshFile, "fresh");
        // 把 oldFile 的修改时间拨到 2 天前（超过 24h 保留期）
        Files.setLastModifiedTime(oldFile, FileTime.from(Instant.now().minus(Duration.ofDays(2))));
        try {
            DownloadReportAttacher.cleanupStaleArchives();
            assertFalse(Files.exists(oldFile), "超过保留期的历史 run 归档应被清理");
            assertTrue(Files.exists(freshFile), "保留期内的归档必须保留（报告生成时仍被引用）");
        } finally {
            Files.deleteIfExists(freshFile);
            Files.deleteIfExists(threadDir);
        }
    }

    // ===== #4 并发反例：同名文件跨线程 attach 互不串扰（固化 thread-<id> 隔离模型） =====

    @Test
    void attachResolved_concurrentSameNameFilesDoNotInterfere() throws Exception {
        int workers = 4;
        String sameName = "data.csv";
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        CyclicBarrier barrier = new CyclicBarrier(workers);
        List<Long> workerThreadIds = new CopyOnWriteArrayList<>();
        List<Path> tempDirs = new ArrayList<>();
        List<Future<String[]>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < workers; i++) {
                // 每个线程在独立目录创建【同名】源文件，内容不同 —— 模拟并行 scenario 各自下载同名报表
                Path dir = Files.createTempDirectory("concurrent-dl-" + i);
                tempDirs.add(dir);
                Path src = dir.resolve(sameName);
                Files.writeString(src, "content-" + i);
                final String expected = "content-" + i;
                futures.add(pool.submit(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    long tid = Thread.currentThread().getId();
                    workerThreadIds.add(tid);
                    boolean ok = DownloadReportAttacher.attachResolved(src, "并发附件");
                    return new String[]{String.valueOf(tid), String.valueOf(ok), expected};
                }));
            }
            for (Future<String[]> f : futures) {
                String[] out = f.get(30, TimeUnit.SECONDS);
                long tid = Long.parseLong(out[0]);
                assertTrue(Boolean.parseBoolean(out[1]), "worker attach should succeed");
                Path archive = Paths.get(DownloadReportAttacher.REPORT_ATTACHMENTS_DIR,
                        "thread-" + tid, sameName);
                assertTrue(Files.exists(archive), "each worker must archive under its own thread-<id> dir");
                assertEquals(out[2], Files.readString(archive),
                        "同名文件并发 attach 不得互相覆盖（跨线程串扰回归反例）");
            }
        } finally {
            pool.shutdownNow();
            for (Path dir : tempDirs) {
                try (Stream<Path> s = Files.list(dir)) {
                    s.forEach(p -> p.toFile().delete());
                } catch (IOException ignored) {
                    // temp 目录清理尽力而为
                }
                Files.deleteIfExists(dir);
            }
            for (Long tid : workerThreadIds) {
                Path threadDir = Paths.get(DownloadReportAttacher.REPORT_ATTACHMENTS_DIR, "thread-" + tid);
                try (Stream<Path> s = Files.list(threadDir)) {
                    s.forEach(p -> p.toFile().delete());
                } catch (IOException ignored) {
                    // 归档清理尽力而为
                }
                Files.deleteIfExists(threadDir);
            }
        }
    }
}
