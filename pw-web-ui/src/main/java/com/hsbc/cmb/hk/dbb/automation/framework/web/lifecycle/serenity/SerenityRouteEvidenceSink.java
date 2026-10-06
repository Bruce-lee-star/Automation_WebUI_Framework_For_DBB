package com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.serenity;

import com.hsbc.cmb.hk.dbb.automation.framework.common.route.RouteEvidenceSink;
import net.serenitybdd.core.Serenity;
import net.thucydides.core.steps.StepEventBus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * {@link RouteEvidenceSink} 的 Serenity 实现 —— 待报告队列 + 主线程 flush（对齐 master 的
 * {@code framework/web/route/util/SerenityReporter}）。
 *
 * <p><b>为什么必须入队而不是直接写</b>：内核的上报发生在 Playwright 事件线程 / IO 池线程上，
 * 与 Serenity 的测试主线程没有 ThreadLocal 关联 —— 在这些线程上直接调
 * {@code Serenity.recordReportData()} 数据会丢。故 {@link #record} 只入队（线程安全），
 * 由框架在测试主线程的收尾钩子（{@code PlaywrightSerenityBridge.cleanupForScenario}）调用
 * {@link #flush()} 批量写入。</p>
 *
 * <p><b>降级语义</b>：非 Serenity 环境（如纯 JUnit 单测）没有注册 base step listener —— 此时
 * 直接清空队列并静默返回，绝不产生 ERROR 噪音、也不积压。队列上限 {@code serenity.route.maxPendingRecords}
 * （默认 500），超限丢最旧并 WARN。</p>
 */
public final class SerenityRouteEvidenceSink implements RouteEvidenceSink {

    private static final Logger LOGGER = LoggerFactory.getLogger(SerenityRouteEvidenceSink.class);

    /** 单次待报告上限：防极端场景积压（丢最旧，与 master 同策略）。 */
    private static final int MAX_PENDING =
            Integer.getInteger("serenity.route.maxPendingRecords", 500);

    /** 待报告记录：{operation, 脱敏 url, detail}。 */
    private static final Queue<String[]> PENDING = new ConcurrentLinkedQueue<>();

    /** 待报告条数（O(1) 近似值，仅用于上限判定与日志）。 */
    private static final AtomicInteger PENDING_COUNT = new AtomicInteger(0);

    @Override
    public void record(String operation, String url, String detail) {
        try {
            if (PENDING_COUNT.incrementAndGet() > MAX_PENDING) {
                PENDING.poll();
                PENDING_COUNT.decrementAndGet();
                LOGGER.warn("[Route] 待报告队列超过上限 {}，丢弃最旧一条（是否忘了 flush？）", MAX_PENDING);
            }
            PENDING.offer(new String[] {operation == null ? "-" : operation, maskUrl(url), detail});
        } catch (Exception e) {
            // 上报是旁路：入队失败绝不影响路由主流程
            LOGGER.debug("[Route] evidence enqueue skipped: {}", e.toString());
        }
    }

    @Override
    public void flush() {
        if (PENDING.isEmpty()) {
            return;
        }
        if (!StepEventBus.getEventBus().isBaseStepListenerRegistered()) {
            // 非 Serenity 环境（纯单测）：清空避免积压，且不触发 ERROR 日志
            int drained = 0;
            while (PENDING.poll() != null) {
                drained++;
            }
            PENDING_COUNT.set(0);
            LOGGER.debug("[Route] evidence flush skipped (no Serenity listener), drained {} record(s)", drained);
            return;
        }
        String[] item;
        while ((item = PENDING.poll()) != null) {
            PENDING_COUNT.decrementAndGet();
            try {
                Serenity.recordReportData()
                        .withTitle(String.format("[Route %s] %s", item[0], item[1]))
                        .andContents(item[2]);
            } catch (Exception e) {
                LOGGER.debug("[Route] evidence write skipped: {}", e.toString());
            }
        }
    }

    /** URL 脱敏：去掉查询串（DBB 的 token/otp 等都在 query 里），避免报告泄漏凭据。 */
    private static String maskUrl(String url) {
        if (url == null) {
            return "-";
        }
        int queryAt = url.indexOf('?');
        return queryAt < 0 ? url : url.substring(0, queryAt) + "?***";
    }
}
