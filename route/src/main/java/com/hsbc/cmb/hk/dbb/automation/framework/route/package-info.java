/**
 * Playwright 网络拦截驱动的 <b>API 流量治理</b>模块（monitor / modify / delay / mock + 流量录制 + 持久化）。
 *
 * <h2>术语定位（R-7 命名决策：保留 {@code route}，不重命名为 {@code traffic}）</h2>
 *
 * <p><b>本模块的 {@code route} 专指 Playwright 的网络拦截能力</b>——即平台 API
 * {@code page.route(pattern, handler)}、{@link com.microsoft.playwright.Route}、{@code unroute}。
 * 本模块是这套能力的治理层（规则注册与分发、四类 Handler、录制、持久化、监控），因此
 * <b>沿用平台词汇是降低而非提高理解成本</b>；若改名为 {@code traffic}，反而会让「模块名」
 * 与「每个类都在用的 {@code com.microsoft.playwright.Route}」及 {@code context.route(...)} 调用
 * 产生新的错位——问题从一个词换到另一个词，还丢掉了与所封装平台的一致性。
 *
 * <p>与另外两处同词不同义的用法区分清楚：
 * <ul>
 *   <li>{@code com.hsbc.cmb.hk.dbb.automation.framework.common.route}（core 模块）：本能力的
 *       <b>核心侧抽象与生命周期 SPI</b>（{@code RouteLifecycle} / {@code RouteLifecycleRegistry}），
 *       其存在是为了「core 不得反向依赖 route 实现模块」，<b>不是</b>第二套实现；</li>
 *   <li>{@code route-demo-web} / {@code route-demo-service}（示例应用）与 Gherkin 标签
 *       {@code @route* / @route-coverage / @route-composite ...}：与本源含义一致，
 *       均指 Playwright 拦截演示，命名无冲突。</li>
 * </ul>
 *
 * <p><b>本模块既不是</b> URL 路由（router / routing table），<b>也不是</b>「用例路由」或
 * Cucumber 的某种路由——后两者在本框架中<b>不存在</b>对应构造，属纯粹的词面联想。
 * 若在文档或沟通中见到「路由」二字，请以本说明为准：先问「是不是 Playwright 的
 * {@code page.route()} 拦截」，是则属本模块。
 *
 * @see com.hsbc.cmb.hk.dbb.automation.framework.route.dsl.RouteDsl
 * @see com.hsbc.cmb.hk.dbb.automation.framework.route.core.engine.RouteEngine
 */
package com.hsbc.cmb.hk.dbb.automation.framework.route;
