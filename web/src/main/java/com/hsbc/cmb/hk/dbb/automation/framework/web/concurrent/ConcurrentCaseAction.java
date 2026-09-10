package com.hsbc.cmb.hk.dbb.automation.framework.web.concurrent;

/**
 * 并发用例的领域动作（业务提供，框架驱动）。
 *
 * <p><b>分层契约</b>：并发调度的全部机制（线程池、单 Browser 多 Context、共享 Browser、SSO 闸门、
 * {@code runAll}、失败回放）由框架 {@link ConcurrentScenarioExecutor} 拥有；业务<b>只提供"做什么"</b>
 * （即一个用例要执行的领域逻辑），绝不包含任何并发代码。本接口即业务向框架注入领域动作的唯一边界。</p>
 *
 * <p>实现方经 {@link ConcurrentCaseActions#register(ConcurrentCaseAction)} 注册，或由
 * {@code META-INF/services} SPI 自动发现（推荐，零注册代码）。框架的并发 glue 在编排线程经
 * {@link ConcurrentCaseActions#require()} 取得该动作并以并发方式驱动。</p>
 *
 * <p>⚠️ 实现约定（桥接原则 9.3）：本动作在<b>独立 worker 线程</b>执行，禁止直接触碰 Serenity
 * {@code StepEventBus} 报告 API；失败经异常返回，由 {@link ConcurrentScenarioExecutor} 在编排线程统一回放。</p>
 */
@FunctionalInterface
public interface ConcurrentCaseAction {

    /**
     * 执行单个并发用例。
     *
     * @param caseData 该用例的数据（即 feature DataTable 的一行），经 {@link ConcurrentCaseData} 包装，
     *                 以 {@link ConcurrentCaseData#get(String)} 取各列值
     * @throws Exception 任意异常被框架收口进 {@link com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.concurrent.ContextTaskResult}，
     *                  不会污染线程池
     */
    void execute(ConcurrentCaseData caseData) throws Exception;
}
