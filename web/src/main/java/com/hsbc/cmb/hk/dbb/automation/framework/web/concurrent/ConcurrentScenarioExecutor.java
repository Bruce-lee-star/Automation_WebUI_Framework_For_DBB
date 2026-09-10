package com.hsbc.cmb.hk.dbb.automation.framework.web.concurrent;import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.PlaywrightRuntime;


import com.hsbc.cmb.hk.dbb.automation.framework.web.core.FrameworkCore;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.concurrent.ConcurrentContextExecutor;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.concurrent.ConcurrentContextOptions;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.concurrent.ContextTask;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.concurrent.ContextTaskResult;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.PlaywrightManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 框架层并发用例执行器（设计文档第九节 9.3「并发驱动 = 框架自建」）。
 *
 * <p><b>职责边界</b>：本类是并发调度的唯一权威所有者，业务层<b>不持有任何并发代码</b>。它负责：
 * <ol>
 *   <li><b>单 Browser 多 Context 模型</b>：编排线程设共享 {@code configId} 并预热共享 Browser，
 *       使所有 worker 经 {@code keyFor("shared:<configId>")} 命中<b>同一 Browser 实例</b>
 *       （前提：{@code serenity.playwright.shared.browser.enabled=true}）。</li>
 *   <li><b>每用例独立线程 / 独立 Context</b>：每个 {@link ConcurrentCaseData} 构建为一个
 *       {@link ContextTask}，worker 线程复用共享 Browser、各自持有隔离的 {@code BrowserContext}。</li>
 *   <li><b>SSO 感知互斥</b>：相同 (env, username) 身份经 {@link ConcurrencyGate} 串行、不同身份并行
 *       （总开关默认关闭 → 全 no-op 零回归）。</li>
 *   <li><b>失败回放</b>：{@link #assertAllSucceeded(List)} 在编排线程经 Serenity 既有通道回放
 *       （桥接原则 9.3：worker 线程不直接触碰 {@code StepEventBus}）。</li>
 * </ol>
 * </p>
 *
 * <p>业务只提供领域动作 {@link ConcurrentCaseAction}（经 {@link ConcurrentCaseActions} 注入），
 * 并写普通 feature（含用例 DataTable）。并发机制对业务完全透明。</p>
 */
public final class ConcurrentScenarioExecutor {

    private static final Logger LOGGER = LoggerFactory.getLogger(ConcurrentScenarioExecutor.class);

    private ConcurrentScenarioExecutor() {
    }

    /**
     * 编排线程预热共享 Browser（单 Browser 多 Context 模型）。
     *
     * <p>幂等：框架全局初始化 + 设共享 configId + 预热共享 Browser。须由并发 feature 的预备步骤在
     * <b>编排线程</b>（Cucumber runner 线程）调用一次，确保后续所有 worker 命中同一 Browser 实例。</p>
     *
     * @throws IllegalStateException 共享开关未开启且预热失败（语义化提示）
     */
    public static void prepareSharedBrowser() {
        FrameworkCore.getInstance().initialize();
        PlaywrightRuntime.instance().browserRegistry.setConfigId(PlaywrightRuntime.instance().browserRegistry.sharedConfigId());
        PlaywrightManager.getBrowser();
        LOGGER.info("[concurrent] shared browser prepared (single-browser multi-context model)");
    }

    /**
     * 以框架线程池并发运行全部用例，返回与输入等序的结果列表。
     *
     * <p>每个用例在独立 worker 线程执行：复用共享 Browser、各自隔离 Context、按身份经
     * {@link ConcurrencyGate} 互斥；失败被收口进 {@link ContextTaskResult}（不污染线程池）。</p>
     *
     * @param cases  用例列表（DataTable 每行一个）
     * @param action 领域动作（业务提供，框架驱动）
     * @param options 并发选项（并行度 / 单任务超时 / 虚拟线程）
     * @param <T>   结果载荷类型
     * @return 与 {@code cases} 等序的结果列表
     */
    public static <T> List<ContextTaskResult<T>> runCases(List<ConcurrentCaseData> cases,
                                                           ConcurrentCaseAction action,
                                                           ConcurrentContextOptions options) {
        if (cases == null || cases.isEmpty()) {
            return List.of();
        }
        java.util.Objects.requireNonNull(action, "ConcurrentCaseAction must not be null");
        List<ContextTask<T>> tasks = new ArrayList<>(cases.size());
        for (ConcurrentCaseData c : cases) {
            ConcurrencyPartitionKey key = c.identityPartition();
            String name = c.name();
            tasks.add(ContextTask.of(name, () -> {
                // 共享 configId → 所有 worker 命中同一共享 Browser（单 Browser + 多 Context）
                PlaywrightRuntime.instance().browserRegistry.setConfigId(PlaywrightRuntime.instance().browserRegistry.sharedConfigId());
                ConcurrencyGate.acquire(key); // 相同身份串行、不同身份并行；key==null 时恒 no-op
                try {
                    action.execute(c);
                    return null;
                } finally {
                    ConcurrencyGate.release(key);
                }
            }));
        }
        return ConcurrentContextExecutor.runAll(tasks, options);
    }

    /**
     * 编排线程回放：任一用例失败则经 Serenity 既有通道标记并抛出（带全部失败汇总，桥接 9.3）。
     */
    public static <T> void assertAllSucceeded(List<ContextTaskResult<T>> results) {
        ConcurrentContextExecutor.assertAllSucceeded(results);
    }
}
