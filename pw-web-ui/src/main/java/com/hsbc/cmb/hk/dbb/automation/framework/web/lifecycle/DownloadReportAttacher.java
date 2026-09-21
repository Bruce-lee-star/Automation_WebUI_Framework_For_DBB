package com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle;

import net.serenitybdd.core.Serenity;
import net.serenitybdd.core.reports.AndContent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 下载附件挂 Serenity 报告 —— 内部实现收口（不对外暴露）。
 *
 * <p>为什么需要单独收口：把下载文件"附到报告"涉及两步副作用，必须一并做对：
 * <ol>
 *   <li><b>复制到持久目录</b> {@code target/site/serenity/report-attachments/thread-<id>/}：scenario 收尾会清本线程
 *       {@code target/downloads/thread-<id>/}，若直接引用临时下载目录，报告生成时文件已被删
 *       ⇒ 附件链接失效。先复制出来，文件即独立于 scenario 生命周期。</li>
 *   <li><b>按线程分目录防串扰</b>：归档落在 {@code target/site/serenity/report-attachments/thread-<id>/}（与下载临时目录
 *       {@code target/downloads/thread-<id>/} 同一隔离模型）。并行下若两个线程下载<b>同名</b>文件，
 *       平铺共享目录 + {@code REPLACE_EXISTING} 会互相覆盖 → 一个 scenario 的附件显示成另一个的文件；
 *       按线程分目录后即使同名也互不干扰。源文件名在同线程内已通过 {@code resolveNonConflictingDownloadPath}
 *       做 {@code " (1)"/" (2)"} 序号去重，故子目录内亦不会自撞。</li>
 *   <li><b>嵌入 Serenity 报告</b>：经 {@code Serenity.recordReportData().withTitle(...).fromFile(...).downloadable()}
 *       把该持久副本登记为"可下载附件"，在报告里渲染成下载链接。这是 Serenity 4.2.0 把任意文件
 *       （pdf/xlsx/zip/csv…）附到报告的唯一稳定入口（4.2.0 没有 {@code StepEventBus.embed}）。
 *       该调用走 ThreadLocal 事件总线，仅挂到<b>当前线程</b>的 scenario 报告，不会跨线程。</li>
 * </ol>
 *
 * <p>行为铁律：嵌入是<b>尽力而为</b>——报告挂接失败（如非 Serenity 上下文）只记 warn，不抛、不影响 case 本身；
 * 持久副本复制失败才返回 false。日志路由回 {@link PlaywrightManager#class} 以保生产溯源一致。</p>
 */
final class DownloadReportAttacher {

    /** 报告附件持久根目录（约定，见 doc 10 §7.5）：相对执行模块根，独立于 scenario 清理。
     *  实际归档落在 {@code <根>/thread-<id>/<filename>}，按线程隔离防并行同名串扰。 */
    static final String REPORT_ATTACHMENTS_DIR = "target/site/serenity/report-attachments";

    /** 解析最近下载的默认等待（毫秒）：与下载异步保存窗口对齐。 */
    private static final long DEFAULT_AWAIT_MS = 15_000L;

    /** Windows/跨平台非法文件名字符（含 ASCII 控制字符）；用于 #2 文件名清洗。 */
    private static final Pattern ILLEGAL_FILE_NAME_CHARS =
            Pattern.compile("[\\\\/:*?\"<>|\u0000-\u001f\u007f]");
    /** 归档文件名长度上限（NTFS 兼容余量，避免超长名复制失败）。 */
    private static final int MAX_ARCHIVE_NAME_LEN = 240;

    /** 归档保留期（毫秒）：超期文件在下次运行首次挂附件时清理（#3 磁盘治理）。
     *  归档目录不参与 scenario 清理（报告生成时文件须在），故用保守保留期而非立即删——
     *  24h 足以覆盖报告生成窗口，又不会让目录无限增长（类比 PlaywrightInitializer 临时目录治理）。 */
    private static final long ARCHIVE_RETENTION_MS = TimeUnit.HOURS.toMillis(24);

    /** 本 JVM 是否已执行过过期归档清理（惰性单次，避免每次 attach 重复扫描）。 */
    private static final AtomicBoolean STALE_CLEANUP_DONE = new AtomicBoolean(false);

    private static final Logger logger = LoggerFactory.getLogger(PlaywrightManager.class);

    private DownloadReportAttacher() {
    }

    /**
     * 门面入口：解析最近一次完成的下载并挂到报告。
     *
     * @param caption 报告里显示的标题；null/空白时回退为文件名
     * @return true 表示已归档（并尽力嵌入报告）；false 表示无可用下载
     */
    static boolean attachLastDownload(String caption) {
        // 首次使用时惰性清理历史 run 的过期归档（24h 前），防 target/site/serenity/report-attachments 无限增长
        cleanupStaleArchivesIfFirstUse();
        // 先等"新登记出现"（异步保存已落盘 + 入 DownloadRegistry），超时再退回最近一次已登记路径
        // 取"当前线程 context 登记的最后一条下载"(每个 scenario 独立 context,并行不串扰,已单测固化)。
        // 【语义边界】① 单 scenario 多次下载只调一次会挂到"最后一条"而非刚触发的那条——需挂多个请直接传 Path;
        // ② 15s 内未等到新登记则退回 getLastDownloadPath() 取已登记最近一条,超时边界上可能取到旧下载(取舍非 bug)。
        Path src = PlaywrightManager.awaitLastDownloadPath(DEFAULT_AWAIT_MS);
        if (src == null) {
            src = PlaywrightManager.getLastDownloadPath();
        }
        return attachResolved(src, caption);
    }

    /**
     * 已解析源文件：复制到持久目录并嵌入报告（独立可测，不依赖下载查询链路）。
     *
     * @param src    已落盘且已登记的下载文件绝对路径
     * @param caption 报告标题（null/空白回退为文件名）
     * @return true 表示已归档；false 表示源文件不存在或复制失败
     */
    static boolean attachResolved(Path src, String caption) {
        if (src == null || !Files.exists(src)) {
            logger.warn("attachLastDownloadToReport: no completed download to attach "
                    + "(call after triggering download + await*)");
            return false;
        }
        // 0) 清洗文件名：Download.suggestedFilename() 来自响应 Content-Disposition（外部不可信），
        //    可能含 Windows 保留字符 \ : * ? " < > | 或控制字符，直接落盘/复制会抛 IOException 致静默失败；
        //    清洗为下划线并防覆盖，保证归档稳健（跨平台 + 防御性）
        String rawName = src.getFileName().toString();
        String safeName = sanitizeFileName(rawName);
        if (!safeName.equals(rawName)) {
            logger.debug("attachLastDownloadToReport: sanitized illegal chars in download filename '{}' -> '{}'",
                    rawName, safeName);
        }
        // 1) 复制到持久目录：按线程分子目录，避免并行下同名文件互相覆盖（REPLACE_EXISTING）造成串扰；
        //    该目录不参与 scenario 清理，保证报告生成时文件仍在
        Path archive = resolveNonConflicting(Paths.get(REPORT_ATTACHMENTS_DIR,
                "thread-" + Thread.currentThread().getId(), safeName));
        try {
            Files.createDirectories(archive.getParent());
            Files.copy(src, archive, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {   // #6: 目录创建/复制失败(含极少见非 IOException 如 SecurityException)一律归档失败,不冒泡到 case
            logger.error("attachLastDownloadToReport: failed to archive download '{}' to {}",
                    src.getFileName(), archive, e);
            return false;
        }
        // 2) 嵌入 Serenity 报告（best-effort：失败不阻断 case）
        //    注意：FromFile.fromFile(...) 返回 void，不能与 downloadable() 链式调用，须先持引用再分步调用
        try {
            String title = (caption == null || caption.isBlank()) ? src.getFileName().toString() : caption;
            AndContent entry = Serenity.recordReportData().withTitle(title);
            entry.fromFile(archive);   // 读取文件内容（checked IOException，被外层 catch 覆盖）
            entry.downloadable();      // 标记为可下载附件
            logger.info("Attached download '{}' to Serenity report; archived at {}", src.getFileName(), archive);
        } catch (Exception e) {
            logger.warn("Attached file '{}' archived at {} but Serenity embedding failed (non-fatal): {}",
                    src.getFileName(), archive, e.getMessage());
        }
        return true;
    }

    // ===== 过期归档清理（#3 磁盘治理） =====

    /**
     * 本 JVM 首次挂附件时惰性清理过期归档（幂等，单次）。独立抽出便于单测直接驱动。
     */
    static void cleanupStaleArchivesIfFirstUse() {
        if (STALE_CLEANUP_DONE.compareAndSet(false, true)) {
            cleanupStaleArchives();
        }
    }

    /**
     * 清理 {@code target/site/serenity/report-attachments} 下 lastModified 早于保留期（24h）的归档文件；
     * 清空后的 thread-* 目录一并删除。全程尽力而为：任何 IO 失败只记 warn，绝不影响本次挂附件。
     *
     * <p>设计约束：归档目录<b>不参与 scenario 清理</b>（报告生成时文件须仍在），故不能像
     * {@code target/downloads} 那样随场景删；改用「保守保留期 + 惰性触发」，只删历史 run 的
     * 陈旧文件（24h 足以覆盖报告生成窗口），不影响本次 run 正在产生的附件。</p>
     */
    static void cleanupStaleArchives() {
        Path root = Paths.get(REPORT_ATTACHMENTS_DIR);
        if (!Files.isDirectory(root)) {
            return;
        }
        Instant cutoff = Instant.now().minusMillis(ARCHIVE_RETENTION_MS);
        try (Stream<Path> dirs = Files.list(root)) {
            dirs.filter(Files::isDirectory).forEach(dir -> {
                try (Stream<Path> files = Files.list(dir)) {
                    files.filter(Files::isRegularFile).forEach(f -> {
                        try {
                            if (Files.getLastModifiedTime(f).toInstant().isBefore(cutoff)) {
                                Files.deleteIfExists(f);
                                logger.debug("attachLastDownloadToReport: removed stale report attachment {}", f);
                            }
                        } catch (IOException e) {
                            logger.warn("attachLastDownloadToReport: failed to inspect/delete stale archive {}: {}",
                                    f, e.getMessage());
                        }
                    });
                } catch (IOException e) {
                    logger.warn("attachLastDownloadToReport: failed to list archive dir {}: {}", dir, e.getMessage());
                }
                try (Stream<Path> rest = Files.list(dir)) {
                    if (!rest.findAny().isPresent()) {
                        Files.deleteIfExists(dir);
                    }
                } catch (IOException ignored) {
                    // 目录非空或不可读，保留即可
                }
            });
        } catch (IOException e) {
            logger.warn("attachLastDownloadToReport: stale archive cleanup failed (non-fatal): {}", e.getMessage());
        }
    }

    /**
     * 清洗下载文件名中的非法/不安全字符（#2 健壮性）。
     *
     * <p>响应 {@code Content-Disposition} 的 filename 是外部不可信输入，可能含 Windows 保留字符
     * {@code \ : * ? " < > |} 或 ASCII 控制字符，直接用作本地文件名会令 {@code Files.copy}/{@code saveAs}
     * 抛 {@code IOException} 而静默失败。统一替换为下划线，并兜底空名/纯点名、截断超长名。</p>
     *
     * <p>包级私有：同包单测可直接验证清洗规则（框架铁律——测试移入同包而非提升可见性）。</p>
     *
     * @param name 原始文件名（可为 null）
     * @return 安全的文件名（绝不返回 null 或空串）
     */
    static String sanitizeFileName(String name) {
        if (name == null) {
            return "download";
        }
        String cleaned = ILLEGAL_FILE_NAME_CHARS.matcher(name).replaceAll("_").trim();
        // 空串或纯点名（"." / ".."）在 Windows 非法，回退为默认名
        if (cleaned.isEmpty() || cleaned.chars().allMatch(ch -> ch == '.')) {
            cleaned = "download";
        }
        if (cleaned.length() > MAX_ARCHIVE_NAME_LEN) {
            int dot = cleaned.lastIndexOf('.');
            String base = dot > 0 ? cleaned.substring(0, dot) : cleaned;
            String ext = dot > 0 ? cleaned.substring(dot) : "";
            int keep = Math.max(0, MAX_ARCHIVE_NAME_LEN - ext.length());
            cleaned = base.substring(0, Math.min(base.length(), keep)) + ext;
        }
        return cleaned;
    }

    /**
     * 若目标路径已存在（如清洗后同名碰撞），追加 {@code " (1)"/" (2)"}… 序号，避免 REPLACE_EXISTING 覆盖丢文件。
     */
    private static Path resolveNonConflicting(Path candidate) {
        if (!Files.exists(candidate)) {
            return candidate;
        }
        String fileName = candidate.getFileName().toString();
        int dot = fileName.lastIndexOf('.');
        String base = dot > 0 ? fileName.substring(0, dot) : fileName;
        String ext = dot > 0 ? fileName.substring(dot) : "";
        Path parent = candidate.getParent();
        int i = 1;
        Path next;
        do {
            next = parent.resolve(base + " (" + i + ")" + ext);
            i++;
        } while (Files.exists(next));
        return next;
    }
}
