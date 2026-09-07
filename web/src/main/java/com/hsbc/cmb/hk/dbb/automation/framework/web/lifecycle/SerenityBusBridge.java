package com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle;

import net.thucydides.core.steps.StepEventBus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionException;

/**
 * 桥接协作类（设计文档 9.10-③ / 9.3）：Serenity 失败回放。
 *
 * <p>并发任务的失败只发生在工作线程，结果以结构化 {@link ContextTaskResult} 回传<b>编排线程</b>；本类在编排线程把每个失败
 * 经 {@link StepEventBus#testFailed(Throwable)} 标记到 Serenity 报告（复用既有失败通道，零新增报告代码）。
 * 工作线程从不触碰 {@code StepEventBus}（桥接原则）。</p>
 *
 * <p>非 Serenity 环境（如离线单测）下 {@code StepEventBus.getEventBus()} 可能不可用，回放被安全降级，仅保留
 * {@link CompletionException} 抛出语义，不影响调用方既有断言。</p>
 */
final class SerenityBusBridge {

    private static final Logger LOGGER = LoggerFactory.getLogger(SerenityBusBridge.class);

    private SerenityBusBridge() {
    }

    /**
     * 在编排线程回放失败：对每个失败项标记 Serenity，并最终抛出汇总 {@link CompletionException}（若存在失败）。
     *
     * @param results runAll 返回的等序结果
     */
    static void replayFailures(List<? extends ContextTaskResult<?>> results) {
        List<String> summaries = new ArrayList<>();
        for (ContextTaskResult<?> r : results) {
            if (!r.isSuccess()) {
                try {
                    r.valueOrThrow(); // 抛 CompletionException，message 含线程/耗时/页面错误诊断
                } catch (CompletionException ce) {
                    summaries.add(ce.getMessage());
                    markFailure(ce);
                }
            }
        }
        if (!summaries.isEmpty()) {
            throw new CompletionException("ConcurrentContextExecutor: " + summaries.size()
                    + " task(s) failed -> " + summaries, null);
        }
    }

    /** 经 Serenity 事件总线标记失败；不可用时安全降级（不向上抛，避免掩盖原始失败语义）。 */
    private static void markFailure(Throwable failure) {
        try {
            StepEventBus bus = StepEventBus.getEventBus();
            if (bus != null) {
                bus.testFailed(failure);
            }
        } catch (Throwable t) {
            LOGGER.debug("[SerenityBusBridge] testFailed replay skipped (no Serenity bus available): {}",
                    t.getMessage());
        }
    }
}
