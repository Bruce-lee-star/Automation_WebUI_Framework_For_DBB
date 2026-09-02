package com.hsbc.cmb.hk.dbb.automation.framework.web.route.core;

/**
 * ⭐ 路由规则作用域标签（Phase 5 统一绑定模型）。
 *
 * <p>用于把「page/context 两层原生绑定（{@code page.route}+{@code context.route}）」收敛为
 * 「单一 context 绑定 + 逻辑 scope 标签」，消除双绑定竞态与 {@code CROSS_LAYER_HANDLED_URLS}
 * URL 去重集依赖。
 *
 * <ul>
 *   <li>{@link #PAGE}：page 级规则。仅作<b>逻辑标签</b>，最终仍绑定在 {@code BrowserContext} 上，
 *       由 {@code pageRef} 区分归属；不再依赖原生 {@code page.route} 与 {@code PAGE_RULES} 弱引用。</li>
 *   <li>{@link #CONTEXT}：context 全域规则（默认）。</li>
 * </ul>
 */
public enum RouteRuleScope {

    /** page 级规则（逻辑标签，绑定在 context 上，按 pageRef 区分归属）。 */
    PAGE,

    /** context 全域规则。 */
    CONTEXT
}
