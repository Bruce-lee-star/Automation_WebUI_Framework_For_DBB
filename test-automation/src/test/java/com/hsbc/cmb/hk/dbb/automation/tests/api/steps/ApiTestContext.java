package com.hsbc.cmb.hk.dbb.automation.tests.api.steps;

import com.hsbc.cmb.hk.dbb.automation.framework.api.core.services.TestServices;
import com.hsbc.cmb.hk.dbb.automation.framework.api.core.step.BaseStep;

/**
 * API 测试步骤间的共享上下文（对应 CODE_REVIEW_REPORT.md P1-14）。
 *
 * <h2>背景：为什么需要它</h2>
 * 本包原有 10 个 step 类一律用 {@code @Autowired private BaseStep baseStep;} 注入依赖，
 * 但项目<b>没有</b> cucumber-spring / serenity-spring，Cucumber 不会为 step 类建 Spring 容器，
 * 该字段恒为 {@code null}，任何步骤一执行就 NPE。
 *
 * <p>更关键的是，这套设计真正依赖的是"<b>所有 step 共享同一个 BaseStep 实例</b>"：
 * {@code EntitySteps} 会在 {@code Given an entity with "xxx"} 时创建 BaseStep 并赋值给自己，
 * 后续步骤（设 endpoint、发请求、校验响应）必须复用<b>同一个</b>实例，否则 entity 配置、
 * 请求参数与响应状态根本串不起来。{@code @Autowired} 在此想借的正是 Spring 的单例语义。
 *
 * <h2>方案：不去引入 Spring，改用显式 scenario 级共享</h2>
 * {@link BaseStep} 的构造器是 package-private，官方入口是
 * {@code TestServices.initialize().baseStep()}；本类即在该工厂之上提供
 * <b>线程（scenario）级单例</b>，语义等价于原先期望的 Spring 单例，却不引入任何新依赖：
 * <ul>
 *   <li>{@code EntitySteps} 负责 {@code init(...)} 创建；</li>
 *   <li>其余 step 类通过 {@link #baseStep()} 取用同一实例；</li>
 *   <li>scenario 结束后由 {@code ApiScenarioHooks} 调 {@link #clear()} 摘除，
 *       避免线程复用导致跨场景串扰。</li>
 * </ul>
 */
public final class ApiTestContext {

    private static final ThreadLocal<BaseStep> BASE_STEP = new ThreadLocal<>();

    private ApiTestContext() {
    }

    /** 创建并持有默认 BaseStep（无 entityName，走动态配置）。 */
    public static BaseStep init() {
        return set(TestServices.initialize().baseStep());
    }

    /** 创建并持有指定 entityName 的 BaseStep。 */
    public static BaseStep init(String entityName) {
        return set(TestServices.initialize().withEntity(entityName).baseStep());
    }

    /** 创建并持有指定 entityName + 环境的 BaseStep。 */
    public static BaseStep init(String entityName, String env) {
        return set(TestServices.initialize().withEntity(entityName).withEnv(env).baseStep());
    }

    /**
     * 取当前 scenario 的共享 BaseStep。
     *
     * @throws IllegalStateException 尚未初始化时抛出 —— 提示调用方先写
     *                               {@code Given an entity with "<entityName>"} 步骤，
     *                               比 NPE 更利于定位。
     */
    public static BaseStep baseStep() {
        BaseStep step = BASE_STEP.get();
        if (step == null) {
            throw new IllegalStateException(
                    "BaseStep has not been initialized for this scenario. "
                            + "Please start the scenario with: Given an entity with \"<entityName>\"");
        }
        return step;
    }

    /** 摘除当前线程的 BaseStep（scenario 结束时调用，防止线程复用串扰）。 */
    public static void clear() {
        BASE_STEP.remove();
    }

    private static BaseStep set(BaseStep step) {
        BASE_STEP.set(step);
        return step;
    }
}
