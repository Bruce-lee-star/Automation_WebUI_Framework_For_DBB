package com.hsbc.cmb.hk.dbb.automation.framework.route.core.engine;

import com.microsoft.playwright.Page;
import com.microsoft.playwright.Route;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import com.hsbc.cmb.hk.dbb.automation.framework.route.core.rule.RouteHandleType;
import com.hsbc.cmb.hk.dbb.automation.framework.route.core.rule.RouteRule;
import com.hsbc.cmb.hk.dbb.automation.framework.route.core.rule.RouteRuleScope;

/**
 *  Phase 5 拆分：跨层（Page + Context）统一解析的纯函数域。
 *
 * <p>本类<strong>无任何可变静态状态</strong>，仅依赖输入参数与同包类型
 * （{@link RouteRule} / {@link RouteRuleScope} / {@link RouteHandleType} / {@link RouteDelay}），可纯单测。
 *
 * <p>结果载体 {@link RouteEngine.CrossLayerMergeResult} / {@link RouteEngine.ResolvedUnified}
 * 仍定义在 {@link RouteEngine}（对外公共契约，不可移动），本类仅承载其构造逻辑。
 * {@link RouteEngine} 保留 {@code mergeCrossLayer} / {@code resolveUnified} 薄转发方法，调用方与
 * {@code Route*Test} 零改动。
 */
public final class RouteUnifiedResolution {

    private static final Logger LOGGER = LoggerFactory.getLogger(RouteUnifiedResolution.class);

    private RouteUnifiedResolution() {
        // 纯工具类，禁止实例化
    }

    /**
     *  B3 链式模型：将规则链合并为<b>一次性有效规则</b>（分发期合并，后注册优先覆盖）。
     *
     * <p>对齐跨层合并的 copyForMerge 模式——<b>绝不就地修改</b>闭包/注册表持有的原始规则：
     * <ul>
     *   <li>链头为基础 copyForMerge（快照其全部能力位与请求条件字段）；</li>
     *   <li>依次 mergeFrom 后续规则（后注册覆盖先注册：MODIFY putAll、DELAY 取 max、MONITOR 基线不可关）；</li>
     *   <li>链上任一规则为 MOCK → 有效规则 type 提升为 MOCK（终结，短路）；</li>
     *   <li>mergeSource 指向链头——session 查询 / times 递减 / 跨层 identity 始终作用于源规则。</li>
     * </ul>
     *
     * <p>size &lt;= 1 时直接返回链头自身（零拷贝，独立规则不受影响）。
     *
     * @param chain 规则链
     * @return 有效规则；链为空返回 null
     */
    private static RouteRule resolveChain(List<RouteRule> chain) {
        if (chain == null || chain.isEmpty()) return null;
        RouteRule head = chain.get(0);
        if (chain.size() == 1) return head;
        RouteRule effective = head.copyForMerge();
        for (int i = 1; i < chain.size(); i++) {
            RouteRule r = chain.get(i);
            if (r == null) continue;
            effective.mergeFrom(r);
            // MOCK 终结提升（对齐跨层规则）：链上任一规则为 MOCK → 有效规则 type=MOCK
            if (r.getType() == RouteHandleType.MOCK) {
                effective.setType(RouteHandleType.MOCK);
            }
        }
        effective.setMergeSource(head);
        return effective;
    }

    /**
     *  Phase 5 准备：跨层（Page + Context）合并纯函数 —— 无 Playwright 依赖，可纯单测。
     *
     * <p>与 {@code dispatchRoute} 内联跨层合并严格等价，作为统一绑定模型的合并核心。
     * 输入已「同层合并」的 page 有效规则与 context 规则链，输出一次性有效规则 + 合并后 DELAY + 是否发生跨层合并。
     *
     * <p>合并语义（对齐 {@code ROUTE_SCOPE_AND_PRIORITY.md} §2 / §5）：
     * <ul>
     *   <li>DELAY 取 max；ctx 层 delay 始终保留，page 层 delay 仅当 ctx 为终结者（MOCK）时丢弃；</li>
     *   <li>能力位跨层 OR 叠加（MODIFY/MONITOR/DELAY 共存），用 {@code copyForMerge()} 构造一次性拷贝，
     *       绝不就地修改输入；</li>
     *   <li>MOCK 为唯一终结者：任一层为 MOCK → 有效规则 type=MOCK（短路）；否则保持 page 主 type。</li>
     * </ul>
     *
     * @param pageEffective 已同层合并的 page 有效规则（不会就地修改）
     * @param ctxChain      context 规则链（非空；内部 {@link #resolveChain} 合并），不会就地修改
     * @return 跨层合并结果
     */
    public static RouteEngine.CrossLayerMergeResult mergeCrossLayer(RouteRule pageEffective, List<RouteRule> ctxChain) {
        RouteRule ctxEffective = resolveChain(ctxChain);
        RouteHandleType pageType = pageEffective.getType();
        RouteHandleType ctxType = ctxEffective.getType();

        long ctxDelay = RouteDelay.clampDelay(RouteDelay.resolveDelay(ctxEffective));
        boolean ctxTerminates = (ctxType == RouteHandleType.MOCK);
        long pageDelay = ctxTerminates ? 0 : RouteDelay.clampDelay(RouteDelay.resolveDelay(pageEffective));
        long delayMs = Math.max(pageDelay, ctxDelay);

        //  关键：用 copyForMerge() 构造一次性有效规则，绝不就地修改 pageEffective / ctxEffective
        RouteRule effective = pageEffective.copyForMerge();
        effective.mergeFrom(ctxEffective);   // 仅叠加能力位（MODIFY/MONITOR/DELAY 共存）
        //  修复（统一绑定「page 特定 > context 全域」）：MOCK 响应体由 mock 提供方决定，
        //    page 为 MOCK → page 的 status/body 胜出；否则若 ctx 为 MOCK → ctx 的 status/body 胜出；
        //    其余类型无响应体，沿用 mergeFrom 的能力位 OR 结果。
        RouteRule mockProvider = (pageType == RouteHandleType.MOCK) ? pageEffective
                : (ctxType == RouteHandleType.MOCK) ? ctxEffective : null;
        if (mockProvider != null) {
            effective.setMockStatus(mockProvider.getMockStatus());
            effective.setMockBody(mockProvider.getMockBody());
        }
        if (pageType == RouteHandleType.MOCK || ctxType == RouteHandleType.MOCK) {
            effective.setType(RouteHandleType.MOCK);
        } else {
            effective.setType(pageType);
        }
        return new RouteEngine.CrossLayerMergeResult(effective, delayMs, true);
    }

    /**
     *  Phase 3 统一绑定模型：单 context handler 下的「按页筛选 + 跨层合并」纯函数（无 Playwright 依赖，可纯单测）。
     *
     * <p>给定一个 pattern 对应的<b>混合 scope 规则链</b>（同一条链里既有 {@code scope=PAGE} 也有 {@code scope=CONTEXT} 的规则）
     * 与请求所属 Page，产出一次性有效规则：
     * <ul>
     *   <li>仅收集 {@code scope==PAGE && pageRef == reqPage} 的规则构成 page 有效链（保住 popup/iframe 专属隔离）；</li>
     *   <li>仅收集 {@code scope==CONTEXT} 的规则构成 context 有效链（作用于同 context 所有页面）；</li>
     *   <li>两条链都非空 → 委托 {@link #mergeCrossLayer}（page 特定 &gt; context 全域、能力位 OR、MOCK 终结、DELAY 取 max）；</li>
     *   <li>仅一条链非空 → 该链 {@link #resolveChain} 即可；</li>
     *   <li>两条链都空 → 返回 null（本页无适用规则，交由后续 handler / fallback 放行）。</li>
     * </ul>
     *
     * <p>注意：{@code reqPage} 为 {@code null} 时（frame/page 不可得），page 级规则一律不命中，仅 CONTEXT 规则生效。
     *
     * @param chain   同 pattern 的规则链
     * @param reqPage 请求所属 Page；可为 null
     * @return 统一解析结果；无任何适用规则时返回 null
     */
    public static RouteEngine.ResolvedUnified resolveUnified(List<RouteRule> chain, Object reqPage) {
        if (chain == null || chain.isEmpty()) return null;
        List<RouteRule> pageChain = new java.util.ArrayList<>();
        List<RouteRule> ctxChain = new java.util.ArrayList<>();
        for (RouteRule r : chain) {
            if (r == null) continue;
            if (r.getScope() == RouteRuleScope.PAGE) {
                Object pr = r.getPageRef();
                //  身份匹配（==）：page 级规则只作用于其注册时所绑定的那个 Page；
                //    reqPage 为 null 时一律不命中。
                if (pr != null && pr == reqPage) {
                    pageChain.add(r);
                }
            } else {
                ctxChain.add(r);
            }
        }
        if (pageChain.isEmpty() && ctxChain.isEmpty()) return null;

        if (!pageChain.isEmpty() && !ctxChain.isEmpty()) {
            RouteEngine.CrossLayerMergeResult m = mergeCrossLayer(resolveChain(pageChain), ctxChain);
            return new RouteEngine.ResolvedUnified(m.rule, m.delayMs);
        } else if (!pageChain.isEmpty()) {
            RouteRule eff = resolveChain(pageChain);
            return new RouteEngine.ResolvedUnified(eff, eff.getDelayMs());
        } else {
            RouteRule eff = resolveChain(ctxChain);
            return new RouteEngine.ResolvedUnified(eff, eff.getDelayMs());
        }
    }

    /**
     *  Phase 3：从 Route 反查请求所属 Page（统一绑定模型下 dispatch 按页筛选的关键）。
     * 任一环节不可达时返回 null（交由 resolveUnified 的降级语义处理）。
     */
    public static Page currentPageOf(Route route) {
        try {
            if (route != null && route.request() != null
                    && route.request().frame() != null
                    && route.request().frame().page() != null) {
                return route.request().frame().page();
            }
        } catch (Exception e) {
            // Page/Context 已关闭时无法反查，返回 null 走兜底（预期竞争，但不得静默，D7-3）
            LOGGER.debug("[RouteUnifiedResolution] resolvePage: page/context closed, fallback to null: {}",
                    e.toString());
        }
        return null;
    }
}
