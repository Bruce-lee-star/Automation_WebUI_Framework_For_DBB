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

/**
 * 下载附件挂 Serenity 报告 —— 内部实现收口（不对外暴露）。
 *
 * <p>为什么需要单独收口：把下载文件"附到报告"涉及两步副作用，必须一并做对：
 * <ol>
 *   <li><b>复制到持久目录</b> {@code site/report-attachments/thread-<id>/}：scenario 收尾会清本线程
 *       {@code target/downloads/thread-<id>/}，若直接引用临时下载目录，报告生成时文件已被删
 *       ⇒ 附件链接失效。先复制出来，文件即独立于 scenario 生命周期。</li>
 *   <li><b>按线程分目录防串扰</b>：归档落在 {@code site/report-attachments/thread-<id>/}（与下载临时目录
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
    static final String REPORT_ATTACHMENTS_DIR = "site/report-attachments";

    /** 解析最近下载的默认等待（毫秒）：与下载异步保存窗口对齐。 */
    private static final long DEFAULT_AWAIT_MS = 15_000L;

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
        // 先等"新登记出现"（异步保存已落盘 + 入 DownloadRegistry），超时再退回最近一次已登记路径
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
        // 1) 复制到持久目录：按线程分子目录，避免并行下同名文件互相覆盖（REPLACE_EXISTING）造成串扰；
        //    该目录不参与 scenario 清理，保证报告生成时文件仍在
        Path archive = Paths.get(REPORT_ATTACHMENTS_DIR, "thread-" + Thread.currentThread().getId(),
                src.getFileName().toString());
        try {
            Files.createDirectories(archive.getParent());
            Files.copy(src, archive, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
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
}
