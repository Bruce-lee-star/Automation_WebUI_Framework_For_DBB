package com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.bootstrap;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 下载保存路径解析单元测试（不启动浏览器）。
 *
 * <p><b>2026-09-21 契约变更（并发同名覆盖修复）</b>：由「先查后选」（`Files.exists` 探测空闲路径）改为
 * <b>原子占位</b>（{@code Files.createFile} / {@code CREATE_NEW} 抢占）。因此返回值是<b>已被本次调用创建并
 * 占位</b>的文件（随后由 {@code download.saveAs(...)} 覆写；保存失败由调用方回滚删除）。</p>
 *
 * <p>本测试固化两点：① 命名规则不变（已占用则插序号、隐藏文件不拆扩展名）；② <b>并发同名下载必然拿到
 * 互不相同的路径</b>——这是原来「先查后选」实现给不出的保证。</p>
 */
public class PlaywrightContextManagerDownloadPathTest {

    @Test
    public void returnsOriginalWhenNotConflicting() throws Exception {
        Path dir = Files.createTempDirectory("pw-dl");
        Path result = PlaywrightContextManager.resolveNonConflictingDownloadPath(dir, "report.xlsx");
        assertEquals(dir.resolve("report.xlsx"), result);
        assertTrue(Files.exists(result), "原子占位应创建该文件（随后由 saveAs 覆写）");
        cleanup(dir);
    }

    @Test
    public void appendsSequenceWhenConflicting() throws Exception {
        Path dir = Files.createTempDirectory("pw-dl");
        Files.createFile(dir.resolve("report.xlsx"));
        Path result = PlaywrightContextManager.resolveNonConflictingDownloadPath(dir, "report.xlsx");
        assertEquals(dir.resolve("report (1).xlsx"), result);
        assertTrue(Files.exists(result), "占位文件应已创建");
        cleanup(dir);
    }

    @Test
    public void incrementsSequenceForMultipleConflicts() throws Exception {
        Path dir = Files.createTempDirectory("pw-dl");
        Files.createFile(dir.resolve("report.xlsx"));
        Files.createFile(dir.resolve("report (1).xlsx"));
        Path result = PlaywrightContextManager.resolveNonConflictingDownloadPath(dir, "report.xlsx");
        assertEquals(dir.resolve("report (2).xlsx"), result);
        cleanup(dir);
    }

    @Test
    public void keepsExtensionOnHiddenFile() throws Exception {
        Path dir = Files.createTempDirectory("pw-dl");
        // 点号在首位视为隐藏文件主名，不应拆出扩展名
        Files.createFile(dir.resolve(".env"));
        Path result = PlaywrightContextManager.resolveNonConflictingDownloadPath(dir, ".env");
        assertEquals(dir.resolve(".env (1)"), result);
        cleanup(dir);
    }

    /**
     * 并发同名下载（16 路）：每个调用都必须拿到<b>互不相同</b>且已被自己占位的路径。
     *
     * <p>旧的「先查后选」实现下，多个线程可能探测到同一"空闲"路径 → 互相覆盖；本测试即该缺陷的守护。</p>
     */
    @Test
    public void concurrentReservationsNeverCollide() throws Exception {
        Path dir = Files.createTempDirectory("pw-dl-conc");
        int threads = 16;
        CountDownLatch start = new CountDownLatch(1);
        Set<Path> paths = Collections.synchronizedSet(new HashSet<>());
        List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            for (int i = 0; i < threads; i++) {
                pool.execute(() -> {
                    try {
                        start.await();
                        paths.add(PlaywrightContextManager.resolveNonConflictingDownloadPath(dir, "report.xlsx"));
                    } catch (Throwable t) {
                        errors.add(t);
                    }
                });
            }
            start.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(20, TimeUnit.SECONDS), "并发占位应全部完成");
        } finally {
            pool.shutdownNow();
        }

        assertTrue(errors.isEmpty(), "并发占位不应抛异常：" + errors);
        assertEquals(threads, paths.size(),
                "并发同名下载必须拿到互不相同的路径（原子占位保证），实际拿到 " + paths.size() + " 个不同路径");
        for (Path p : paths) {
            assertTrue(Files.exists(p), "每个占位路径都应已被创建：" + p);
        }
        cleanup(dir);
    }

    /** 递归清理临时目录（best-effort）。 */
    private static void cleanup(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // 测试清理失败不影响断言结论
                }
            });
        }
    }
}
