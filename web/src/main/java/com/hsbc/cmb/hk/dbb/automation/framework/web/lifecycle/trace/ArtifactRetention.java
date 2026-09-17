package com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.trace;

import com.hsbc.cmb.hk.dbb.automation.framework.common.config.VerboseLogging;
import com.hsbc.cmb.hk.dbb.automation.framework.web.config.FrameworkConfigManager;
import com.hsbc.cmb.hk.dbb.automation.framework.web.config.WebFrameworkConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

/**
 * 测试产物（trace / 截图）的<b>企业级保留治理</b>：磁盘总上限 + 文件数上限 + 保留期，且<b>绝不删除本次 run 的产物</b>。
 *
 * <h2>为什么需要"本次 run 不删"这条铁律</h2>
 * <p>trace 已按 scenario 切分为独立文件（见 {@link ScenarioTraceRecorder}），删掉一个文件就等于删掉一个用例的证据。
 * 若按"文件年龄"盲删（常见做法：只按 mtime 排序），在长跑/并行场景下会**误删当前 run 正在使用或刚产出的证据**，
 * 排障时"证据凭空消失"比没有更糟。故本类的删除范围被硬性限定为：
 * <b>mtime 早于本次 run 起点</b>的文件（即上一次/更早 run 的遗留产物）。
 *
 * <h2>策略（按优先级）</h2>
 * <ol>
 *   <li><b>保留期</b>：候选文件龄 &gt; {@code max.age.days} → 删除；</li>
 *   <li><b>总量上限</b>：目录总字节 &gt; {@code max.total.mb}、或文件数 &gt; {@code max.files} → 从<b>最旧</b>候选开始删，
 *       直到满足（候选耗尽即停止并 WARN —— 本次 run 产物超限时宁可超限也不删证据）；</li>
 *   <li>任何异常（目录缺失/权限/占用）一律降级为日志，绝不影响测试主流程。</li>
 * </ol>
 *
 * <p>调用时机：每次 trace 导出后（内部 60s 节流）+ 套件收尾强制一次。
 *
 * @apiNote framework-internal：由 trace 导出与套件收尾调用，业务代码不得依赖。
 */
public final class ArtifactRetention {

    private static final Logger logger = LoggerFactory.getLogger(ArtifactRetention.class);

    /**
     * 本次 run 起点：只清理早于此时刻的文件。
     *
     * <p>假设：一次 run == 一个 JVM（本仓库 failsafe {@code forkCount=1}）。若将来启用了多 JVM 分叉，
     * 各 fork 的起点不同，仍满足"不删本次 run 产物"（各 fork 只清理更早的遗留物）。
     */
    private static final long RUN_START_MS = System.currentTimeMillis();

    /** 节流：避免每个用例都扫目录。 */
    private static final long PRUNE_INTERVAL_MS = 60_000L;
    private static final AtomicLong LAST_PRUNE_MS = new AtomicLong(0L);

    private ArtifactRetention() {
    }

    /** 由调用点触发的节流式清理（每次 trace 导出后调用，实际至多 60s 一次）。 */
    public static void pruneIfDue() {
        long now = System.currentTimeMillis();
        long last = LAST_PRUNE_MS.get();
        if (now - last < PRUNE_INTERVAL_MS) {
            return;
        }
        if (!LAST_PRUNE_MS.compareAndSet(last, now)) {
            return;
        }
        pruneNow();
    }

    /** 立即按配置对全部目标目录执行一次保留治理（套件收尾调用）。 */
    public static void pruneNow() {
        if (!FrameworkConfigManager.getBoolean(WebFrameworkConfig.PLAYWRIGHT_ARTIFACTS_RETENTION_ENABLED)) {
            return;
        }
        long maxAgeMillis = Math.max(0L,
                WebFrameworkConfig.PLAYWRIGHT_ARTIFACTS_RETENTION_MAX_AGE_DAYS.getIntValue()) * 24L * 3600_000L;
        long maxTotalBytes = Math.max(0L,
                WebFrameworkConfig.PLAYWRIGHT_ARTIFACTS_RETENTION_MAX_TOTAL_MB.getIntValue()) * 1024L * 1024L;
        int maxFiles = Math.max(0, WebFrameworkConfig.PLAYWRIGHT_ARTIFACTS_RETENTION_MAX_FILES.getIntValue());
        for (String dir : resolvedDirs()) {
            try {
                PruneResult result = pruneDirectory(Paths.get(dir), RUN_START_MS, maxAgeMillis, maxTotalBytes, maxFiles);
                if (result.deleted > 0) {
                    logger.info("[artifacts] retention pruned {} file(s) from {} ({})", result.deleted, dir,
                            result.summary());
                } else {
                    VerboseLogging.logDebugIfVerbose(logger, "[artifacts] retention: nothing to prune in {}", dir);
                }
            } catch (Exception e) {
                // 产物治理绝不能影响测试主流程
                VerboseLogging.logDebugIfVerbose(logger, "[artifacts] retention skipped for {}: {}", dir, e.getMessage());
            }
        }
    }

    /** 解析待治理目录（逗号分隔，允许为空）。 */
    private static List<String> resolvedDirs() {
        String raw = WebFrameworkConfig.PLAYWRIGHT_ARTIFACTS_RETENTION_DIRS.getValue();
        List<String> dirs = new ArrayList<>();
        if (raw == null) {
            return dirs;
        }
        for (String part : raw.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                dirs.add(trimmed);
            }
        }
        return dirs;
    }

    /**
     * 对单个目录执行保留治理（<b>纯逻辑</b>，可直接单测）。
     *
     * @param dir           目标目录（不存在则 no-op）
     * @param runStartMs    本次 run 起点：{@code mtime >= runStartMs} 的文件一律不删
     * @param maxAgeMillis  保留期（{@code <=0} 表示不按年龄删）
     * @param maxTotalBytes 目录总字节上限（{@code <=0} 表示不限制）
     * @param maxFiles      文件数上限（{@code <=0} 表示不限制）
     * @return 删除统计
     */
    static PruneResult pruneDirectory(Path dir, long runStartMs, long maxAgeMillis,
                                      long maxTotalBytes, int maxFiles) {
        PruneResult result = new PruneResult();
        if (dir == null || !Files.isDirectory(dir)) {
            return result;
        }
        List<Path> protectedFiles = new ArrayList<>();
        List<Path> candidates = new ArrayList<>();
        List<Long> sizes = new ArrayList<>();
        try (Stream<Path> stream = Files.list(dir)) {
            for (Path p : (Iterable<Path>) stream::iterator) {
                if (!Files.isRegularFile(p)) {
                    continue;
                }
                long size = Files.size(p);
                if (Files.getLastModifiedTime(p).toMillis() >= runStartMs) {
                    protectedFiles.add(p);
                    result.protectedBytes += size;
                } else {
                    candidates.add(p);
                    sizes.add(size);
                }
            }
        } catch (IOException e) {
            VerboseLogging.logDebugIfVerbose(logger, "[artifacts] cannot list {}: {}", dir, e.getMessage());
            return result;
        }

        long now = System.currentTimeMillis();
        long totalBytes = result.protectedBytes;
        for (int i = 0; i < candidates.size(); i++) {
            totalBytes += sizes.get(i);
        }
        int remainingFiles = protectedFiles.size() + candidates.size();

        //  1) 保留期：候选超龄即删
        if (maxAgeMillis > 0) {
            for (int i = candidates.size() - 1; i >= 0; i--) {
                Path p = candidates.get(i);
                if (now - lastModified(p) > maxAgeMillis) {
                    long size = sizes.get(i);
                    if (deleteQuietly(p)) {
                        result.deleted++;
                        result.deletedByAge++;
                        totalBytes -= size;
                        remainingFiles--;
                        candidates.remove(i);
                        sizes.remove(i);
                    }
                }
            }
        }

        //  2) 总量/条数上限：从最旧候选开始删（候选耗尽即停：优先保住本次 run 与就近证据）
        if (maxTotalBytes > 0 || maxFiles > 0) {
            sortByModifiedAsc(candidates, sizes);
            int i = 0;
            while (i < candidates.size()
                    && ((maxTotalBytes > 0 && totalBytes > maxTotalBytes)
                        || (maxFiles > 0 && remainingFiles > maxFiles))) {
                Path p = candidates.get(i);
                long size = sizes.get(i);
                if (deleteQuietly(p)) {
                    result.deleted++;
                    result.deletedByCap++;
                    totalBytes -= size;
                    remainingFiles--;
                    candidates.remove(i);
                    sizes.remove(i);
                } else {
                    i++;
                }
            }
            if ((maxTotalBytes > 0 && totalBytes > maxTotalBytes) || (maxFiles > 0 && remainingFiles > maxFiles)) {
                logger.warn("[artifacts] retention caps not met in {} (kept {}) — 本次 run 产物受保护，"
                        + "宁可超限也不删当前证据；如需收敛请清理历史目录", dir, remainingFiles);
            }
        }

        result.remainingBytes = totalBytes;
        result.remainingFiles = remainingFiles;
        return result;
    }

    /** 按 mtime 升序（最旧在前）排列候选与其尺寸。 */
    private static void sortByModifiedAsc(List<Path> files, List<Long> sizes) {
        Integer[] order = new Integer[files.size()];
        for (int i = 0; i < order.length; i++) {
            order[i] = i;
        }
        java.util.Arrays.sort(order, Comparator.comparingLong(i -> lastModified(files.get(i))));
        List<Path> sortedFiles = new ArrayList<>(files.size());
        List<Long> sortedSizes = new ArrayList<>(sizes.size());
        for (int idx : order) {
            sortedFiles.add(files.get(idx));
            sortedSizes.add(sizes.get(idx));
        }
        files.clear();
        files.addAll(sortedFiles);
        sizes.clear();
        sizes.addAll(sortedSizes);
    }

    private static long lastModified(Path p) {
        try {
            return Files.getLastModifiedTime(p).toMillis();
        } catch (IOException e) {
            return Long.MAX_VALUE; // 读不到 mtime 视为"最新"，避免误删
        }
    }

    private static boolean deleteQuietly(Path p) {
        try {
            Files.deleteIfExists(p);
            return true;
        } catch (Exception e) {
            VerboseLogging.logDebugIfVerbose(logger, "[artifacts] cannot delete {}: {}", p.getFileName(), e.getMessage());
            return false;
        }
    }

    /** 保留治理统计（含 summary，供日志与单测断言）。 */
    static final class PruneResult {
        int deleted;
        int deletedByAge;
        int deletedByCap;
        long protectedBytes;
        long remainingBytes;
        int remainingFiles;

        String summary() {
            return "deleted=" + deleted + " (age=" + deletedByAge + ", cap=" + deletedByCap + "), remaining="
                    + remainingFiles + " file(s)/" + (remainingBytes / 1024) + "KB, protected="
                    + (protectedBytes / 1024) + "KB(this run)";
        }
    }
}
