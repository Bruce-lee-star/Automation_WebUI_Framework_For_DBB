/**
 * API 测试步骤定义（⚠️ 启用前必读）。
 *
 * <h2>现状</h2>
 * 本包共 10 个 step 类（{@code BaseConfigurationSteps}、{@code EntitySteps}、
 * {@code HeaderSteps}、{@code PathParameterSteps}、{@code QueryParameterSteps}、
 * {@code PayloadSteps}、{@code RequestSteps}、{@code ResponseStatusSteps}、
 * {@code ResponseHeaderSteps}、{@code ResponseBodySteps}），存在两个致命问题，
 * 导致它们<b>从未真正运行过</b>：
 *
 * <ol>
 *   <li><b>不在 glue 扫描范围内</b>：Serenity 的 glue 配置为
 *       {@code com.hsbc.cmb.hk.dbb.automation.tests.glue}，
 *       本包位于 {@code tests.api.steps}，不在其下。因此
 *       {@code features/api/route_demo_api_restassured.feature} 虽会被加载，
 *       但其所有步骤都解析不到（undefined）。</li>
 *   <li><b>@Autowired 无容器支撑</b>：所有 step 类均以
 *       {@code @Autowired private BaseStep baseStep;} 注入依赖，但项目<b>没有</b>
 *       {@code cucumber-spring} / {@code serenity-spring}，Cucumber 不会为 step 类
 *       创建 Spring 容器，该字段恒为 {@code null} → 任何步骤一执行即 NPE。</li>
 * </ol>
 *
 * <h2>为什么不直接改成 new BaseStep()</h2>
 * {@code BaseStep} 继承自 {@code RestJobProvider}，而后者<b>没有无参构造</b>——
 * 它需要一个 {@code entityName} 来构建 {@code Entity}。因此"去掉 @Autowired"
 * 并不能让代码跑起来，必须先解决 entity 的创建与在步骤间的传递方式。
 *
 * <h2>启用方案（二选一，需由 owner 决策）</h2>
 * <ol>
 *   <li><b>引入 Spring 容器</b>：添加 {@code cucumber-spring}（或 {@code serenity-spring}）
 *       依赖并配置 {@code BaseStep} 为 Bean；随后把本包追加到 glue 配置中。
 *       适合希望沿用现行注入写法的场景。</li>
 *   <li><b>去 Spring 化</b>：把 {@code @Autowired} 字段改为显式实例化，并先解决
 *       entity 生命周期——例如新增一个 {@code @Given("I create entity {string}")}
 *       前置步骤把 entityName 存入 scenario 上下文，后续步骤按需构造/复用
 *       {@code BaseStep}。改动可控且不再引入新依赖，推荐此方案。</li>
 * </ol>
 *
 * <h2>验证前提（B-8：本节已更新，原述过时）</h2>
 * 运行器不再写死标签：默认标签经根 {@code <properties>} 的 {@code tags = "not @skip"}（单一事实来源，
 * 由 failsafe 注入 {@code cucumber.filter.tags}）驱动，仓库内未显式打 {@code @skip} 的 feature 都会被执行，
 * 不再存在"实际执行 0 个场景"的情况。本包步骤若仍未纳入 glue 扫描 / 无容器支撑，须按上文方案修复后
 * 方能被 feature 真正调用。
 */
package com.hsbc.cmb.hk.dbb.automation.tests.api.steps;
