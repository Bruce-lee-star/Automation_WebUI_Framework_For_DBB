package com.hsbc.cmb.hk.dbb.automation.framework.web.concurrent;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.PlaywrightRuntime;


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

/**
 * 框架层并发用例执行器（设计文档第九节 9.3「并发驱动 = 框架自建」）。
 *
 * <p><b>职责边界</b>：本类是并发调度的唯一权威所有者，业务层<b>不持有任何并发代码</b>。它负责：
 * <ol>
 *   <li><b>每 worker 线程独立 Browser 模型</b>：{@code keyFor("<threadId>:<configId>")} 保证
 *       每个 worker 线程拿到<b>各自独立的 Browser 实例</b>（N 并行 worker = N 线程 = N Browser），
 *       避免跨线程共享非线程安全的 {@code Browser} 对象（Playwright for Java 官方线程模型约束）。</li>
 *   <li><b>每用例独立线程 / 独立 Context</b>：每个 {@link ConcurrentCaseData} 构建为一个
 *       {@link ContextTask}，worker 线程持有独立 Browser 与隔离的 {@code BrowserContext}。</li>
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
     * 编排线程预热并发运行环境（每 worker 线程独立 Browser + 每用例独立 Context 模型）。
     *
     * <p>幂等：框架全局初始化 + 设统一 configId（仅用于统一浏览器类型 / headed 维度，使各 worker 线程
     * 拿到形态一致的配置）+ 预热。须由并发 feature 的预备步骤在 <b>编排线程</b>（Cucumber runner 线程）调用一次。</p>
     *
     * <p><b>线程安全模型（关键）：</b>Playwright for Java 是多线程绑定，官方 multithreading 文档明确
     * <i>"Playwright Java is not thread safe"</i>——一个 {@code Browser} 对象<b>不得</b>跨线程并发调用。
     * 因此本方法使每个 worker 线程经 {@code BrowserRegistry.keyFor()} 的 {@code "<threadId>:<configId>"} 维度
     * 拿到<b>各自独立的 Browser 实例</b>，隔离性由每线程各自的 {@code BrowserContext} 保证。这与 Node/Python
     * 单线程绑定的「单 Browser 多 Context」范式不同，后者不能直接套用到 Java。共享 Browser 模式
     * （{@code serenity.playwright.shared.browser.enabled}）<b>已从框架彻底移除</b>，并发统一为每线程独立 Browser。</p>
     */
    public static void prepareConcurrentEnvironment() {
        FrameworkCore.getInstance().initialize();
        // 仅统一浏览器类型 / headed 维度，使各 worker 线程拿到形态一致的 configId；
        // 因共享模式默认关闭，keyFor 含 threadId 维度 → 每个 worker 线程得到各自独立的 Browser 实例。
        // 绝不在多线程并发下启用共享 Browser（Playwright Java 非线程安全）。
        PlaywrightRuntime.instance().browserRegistry.setConfigId(PlaywrightRuntime.instance().browserRegistry.workerConfigId());
        PlaywrightManager.getBrowser();
        LOGGER.info("[concurrent] environment prepared (per-worker-thread browser + per-task context model)");
    }

    /**
     * 以框架线程池并发运行全部用例，返回与输入等序的结果列表。
     *
     * <p>每个用例在独立 worker 线程执行：各持独立 Browser 与隔离 Context、按身份经
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
                // 统一 configId 维度（浏览器类型 / headed）；keyFor 含 threadId
                // → 每个 worker 线程得到各自独立的 Browser 实例，互不共享（线程安全）。
                PlaywrightRuntime.instance().browserRegistry.setConfigId(PlaywrightRuntime.instance().browserRegistry.workerConfigId());
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