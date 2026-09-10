package com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.serenity;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.PlaywrightManager;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.PlaywrightRuntime;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.state.PlaywrightRuntimeState;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.provider.DefaultRuntimeProvider;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.concurrent.ConcurrentContextExecutor;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.concurrent.ConcurrentContextOptions;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.concurrent.ContextTask;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.concurrent.ContextTaskResult;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.event.PageEventMonitor;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.config.PlaywrightConfigManager;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.config.ProxyConfigResolver;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.browser.BrowserRegistry;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.browser.BrowserRegistryImpl;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.browser.BrowserStartup;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.browser.BrowserStartupImpl;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.browser.BrowserRestart;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.browser.BrowserRestartImpl;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.browser.BrowserCleanup;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.browser.BrowserCleanupImpl;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.browser.BrowserCrashGuard;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.context.ContextRegistry;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.context.ContextRegistryImpl;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.context.CustomOptions;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.context.CustomOptionsManager;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.page.PageRegistry;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.page.PageRegistryImpl;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.scenario.ScenarioLifecycle;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.serenity.PlaywrightSerenityBridge;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.serenity.TestContextBridge;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.bootstrap.PlaywrightContextManager;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.bootstrap.PlaywrightInitializer;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.media.PlaywrightScreenshotManager;

import net.serenitybdd.core.Serenity;
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
public final class SerenityBusBridge {

    private static final Logger LOGGER = LoggerFactory.getLogger(SerenityBusBridge.class);

    private SerenityBusBridge() {
    }

    /**
     * 在编排线程回放失败：对每个失败项标记 Serenity，并最终抛出汇总 {@link CompletionException}（若存在失败）。
     *
     * @param results runAll 返回的等序结果
     */
    public static void replayFailures(List<? extends ContextTaskResult<?>> results) {
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

    /**
     * 在编排线程显式标注"浏览器崩溃恢复重跑"事件（WEB-P1-4 验收 ②：重跑事件在 Serenity 报告中可见）。
     *
     * <p>仅对 {@link ContextTaskResult#isReplayed()} 为 true 的结果写入一条报告数据（标题固定、
     * 内容含任务名 / 重跑类型 / 首次失败诊断），使"曾因崩溃被自动重跑一次"在 HTML 报告中清晰可查，
     * 不与最终是否成功相混淆（成功重跑同样标注）。</p>
     *
     * <p>必须在<b>编排线程</b>调用（{@code runAll} 的调用方线程），工作线程不触碰 Serenity 总线（桥接原则 9.3）。
     * 非 Serenity 环境（如离线单测）下安全降级，仅记录调试日志，不影响调用方既有断言。</p>
     *
     * @param results runAll 返回的等序结果
     */
    public static void recordCrashRecoveryIfAny(List<? extends ContextTaskResult<?>> results) {
        if (results == null) {
            return;
        }
        for (ContextTaskResult<?> r : results) {
            if (!r.isReplayed()) {
                continue;
            }
            try {
                String type = r.getRecoveryType() == null ? "crash" : r.getRecoveryType();
                String firstMsg = r.getFirstFailureMessage() == null ? "n/a" : r.getFirstFailureMessage();
                Serenity.recordReportData()
                        .withTitle("BROWSER CRASH RECOVERY / 浏览器崩溃恢复重跑")
                        .andContents("Task '" + r.getTaskName() + "' was auto-replayed once after "
                                + type + " (bounded to 1). First failure: " + firstMsg);
            } catch (Throwable t) {
                LOGGER.debug("[SerenityBusBridge] crash-recovery annotation skipped (no Serenity bus available): {}",
                        t.getMessage());
            }
        }
    }
}
