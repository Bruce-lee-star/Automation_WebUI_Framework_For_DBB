package com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.trace;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Comparator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 产物保留治理守卫（2026-09-17 企业级落盘治理）。
 *
 * <p>核心不变量：<b>本次 run 的产物永不删除</b>（只清理 mtime 早于本次 run 起点的遗留物）——
 * trace 已按用例切分为独立文件，误删即等于删掉用例证据。其余按"保留期 → 总量/条数上限（最旧优先）"收敛。
 */
class ArtifactRetentionTest {

    private Path dir;

    @BeforeEach
    void setUp() throws IOException {
        dir = Files.createTempDirectory("retention-test-");
    }

    @AfterEach
    void tearDown() throws IOException {
        if (Files.exists(dir)) {
            try (var walk = Files.walk(dir)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException ignored) {
                        // 临时目录清理失败不影响断言
                    }
                });
            }
        }
    }

    private Path file(String name, int sizeBytes, long modifiedMs) throws IOException {
        Path p = dir.resolve(name);
        Files.write(p, new byte[sizeBytes]);
        Files.setLastModifiedTime(p, FileTime.fromMillis(modifiedMs));
        return p;
    }

    /** 铁律：mtime >= runStart 的文件（本次 run 产物）一律不删，即便超限。 */
    @Test
    void neverDeletesCurrentRunArtifacts() throws IOException {
        long runStart = 1_000_000L;
        file("old-a.zip", 10, runStart - 10_000);
        file("current-b.zip", 10, runStart + 1);

        // 上限压到 1 字节/1 个文件（`<=0` 语义是"不限制"，故此处用 1 而非 0）：
        // 遗留文件应被删以满足上限，而本次 run 的文件必须保留（即使因此无法满足上限）
        ArtifactRetention.PruneResult r = ArtifactRetention.pruneDirectory(dir, runStart, 0, 1, 1);

        assertEquals(1, r.deleted, "只应删除早于 run 起点的遗留文件");
        assertTrue(Files.exists(dir.resolve("current-b.zip")), "本次 run 的产物绝不能被删除");
        assertTrue(!Files.exists(dir.resolve("old-a.zip")), "遗留文件应被清理");
    }

    /** 条数上限：从最旧开始删，直到满足。 */
    @Test
    void prunesOldestFirstToSatisfyFileCap() throws IOException {
        long runStart = 10_000_000L;
        Path oldest = file("t-1.zip", 10, runStart - 3_000);
        Path middle = file("t-2.zip", 10, runStart - 2_000);
        Path newest = file("t-3.zip", 10, runStart - 1_000);

        ArtifactRetention.PruneResult r = ArtifactRetention.pruneDirectory(dir, runStart, 0, 0, 2);

        assertEquals(1, r.deleted);
        assertEquals(1, r.deletedByCap);
        assertTrue(!Files.exists(oldest), "最旧的候选应首先被删");
        assertTrue(Files.exists(middle) && Files.exists(newest), "较新的候选应保留");
        assertEquals(2, r.remainingFiles);
    }

    /** 总量上限：按字节收敛，同样最旧优先。 */
    @Test
    void prunesToSatisfyTotalSizeCap() throws IOException {
        long runStart = 10_000_000L;
        Path big = file("big-old.zip", 4_000, runStart - 5_000);
        Path small = file("small-new.zip", 1_000, runStart - 1_000);

        ArtifactRetention.PruneResult r = ArtifactRetention.pruneDirectory(dir, runStart, 0, 2_000, 0);

        assertEquals(1, r.deleted, "需删到总字节 <= 2000");
        assertTrue(!Files.exists(big), "最旧（也是最大）的文件应被删以满足容量上限");
        assertTrue(Files.exists(small));
        assertTrue(r.remainingBytes <= 2_000, "剩余字节应在上限内：" + r.remainingBytes);
    }

    /** 保留期：候选龄超过阈值即删（与容量无关）。 */
    @Test
    void prunesByAge() throws IOException {
        long now = System.currentTimeMillis();
        long runStart = now;
        Path ancient = file("ancient.zip", 10, now - 30L * 24 * 3600_000L); // 30 天前
        Path recentOld = file("recent-old.zip", 10, now - 1_000);           // 1 秒前（仍早于 runStart）

        ArtifactRetention.PruneResult r = ArtifactRetention.pruneDirectory(
                dir, runStart, 7L * 24 * 3600_000L, 0, 0);

        assertEquals(1, r.deleted);
        assertEquals(1, r.deletedByAge);
        assertTrue(!Files.exists(ancient), "超龄文件应被删");
        assertTrue(Files.exists(recentOld), "未超龄的遗留文件应保留");
    }

    /** 目录缺失 / 非目录：no-op，绝不抛异常（治理不得影响主流程）。 */
    @Test
    void missingDirectoryIsNoop() {
        ArtifactRetention.PruneResult r = ArtifactRetention.pruneDirectory(
                dir.resolve("not-exists"), 1L, 1L, 1L, 1);
        assertEquals(0, r.deleted);
        ArtifactRetention.PruneResult r2 = ArtifactRetention.pruneDirectory(null, 1L, 1L, 1L, 1);
        assertEquals(0, r2.deleted);
    }

    /** 上限全关（<=0）时不做任何删除。 */
    @Test
    void noCapsMeansNoDeletion() throws IOException {
        long runStart = 10_000_000L;
        file("k.zip", 100, runStart - 1_000);
        ArtifactRetention.PruneResult r = ArtifactRetention.pruneDirectory(dir, runStart, 0, 0, 0);
        assertEquals(0, r.deleted);
        assertEquals(1, r.remainingFiles);
    }
}
