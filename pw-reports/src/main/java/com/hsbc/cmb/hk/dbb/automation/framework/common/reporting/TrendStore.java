package com.hsbc.cmb.hk.dbb.automation.framework.common.reporting;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.hsbc.cmb.hk.dbb.automation.framework.common.config.VerboseLogging;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * E-2：历史趋势存储——每场运行在报告目录下落一份 JSON 快照（{@code <reportDir>/trend-history/<buildId>.json}），
 * 汇总时读取历史做对比，不引入数据库。
 *
 * <p>目录随报告目录走：真实运行同为 {@code target/site/serenity}，跨场稳定；单测用 {@code @TempDir} 天然隔离
 * （不会读到其它用例遗留的历史，保护 golden 基线）。
 *
 * <p>趋势属增强能力，任何 IO 失败均降级（记日志、不阻断报告生成）。
 */
public final class TrendStore {

    private static final Logger LOGGER = LoggerFactory.getLogger(TrendStore.class);
    private static final String DIR_NAME = "trend-history";
    private static final Gson GSON = new GsonBuilder().serializeNulls().create();

    private final Path dir;

    public TrendStore(Path baseDir) {
        this.dir = baseDir.resolve(DIR_NAME);
    }

    public Path directory() {
        return dir;
    }

    /** 保存一份快照（文件名 = buildId，文件系统不安全字符以 {@code _} 替换）。 */
    public void save(RunSummary summary) {
        if (summary == null) {
            return;
        }
        try {
            Files.createDirectories(dir);
            String safe = summary.buildId() == null || summary.buildId().isBlank()
                    ? "unknown" : summary.buildId().replaceAll("[^A-Za-z0-9._-]", "_");
            Files.writeString(dir.resolve(safe + ".json"), GSON.toJson(summary), StandardCharsets.UTF_8);
            VerboseLogging.logInfoIfVerbose(LOGGER, "Trend snapshot saved: {}", safe);
        } catch (IOException e) {
            VerboseLogging.logWarnIfVerbose(LOGGER, "Failed to save trend snapshot: {}", e.getMessage());
        }
    }

    /** 按文件名（可排序 buildId）倒序读取最近 {@code n} 场（最新在前）。 */
    public List<RunSummary> recent(int n) {
        if (n <= 0 || !Files.isDirectory(dir)) {
            return List.of();
        }
        List<Path> files = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "*.json")) {
            ds.forEach(files::add);
        } catch (IOException e) {
            return List.of();
        }
        files.sort(Comparator.comparing((Path p) -> p.getFileName().toString()).reversed());
        List<RunSummary> out = new ArrayList<>();
        for (Path p : files) {
            if (out.size() >= n) {
                break;
            }
            try {
                RunSummary s = GSON.fromJson(Files.readString(p, StandardCharsets.UTF_8), RunSummary.class);
                if (s != null) {
                    out.add(s);
                }
            } catch (Exception corrupted) {
                VerboseLogging.logDebugIfVerbose(LOGGER, "Skip corrupted trend snapshot {}: {}",
                        p.getFileName(), corrupted.getMessage());
            }
        }
        return out;
    }

    /** 最近一场快照（无历史返回 {@code null}）。 */
    public RunSummary previous() {
        List<RunSummary> recent = recent(1);
        return recent.isEmpty() ? null : recent.get(0);
    }
}
