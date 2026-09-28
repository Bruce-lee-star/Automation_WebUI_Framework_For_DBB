package com.hsbc.cmb.hk.dbb.automation.framework.route.v2.binding;

import java.util.regex.Pattern;

/**
 * Route V2 pattern 归一化 —— 对齐老仓（v1）{@code RouteEngine.normalizePattern} 语义。
 *
 * <p><b>为什么需要（2026-09-27 E2E 实测根因）</b>：业务层 DSL 传入的是相对端点
 * （如 {@code profile/list}、{@code auth/assert}），而 Playwright 原生
 * {@code context.route(pattern, ...)} 的 glob 在<b>无通配符时按完整 URL 精确匹配</b>
 * ——相对端点直接注册将永不命中真实绝对 URL，导致 handler 不触发、monitor/mock/capture
 * 全部静默失效（E2E 中 capture 记录为空、mock 未命中即此根因）。老仓注册前统一做
 * <b>前后缀通配归一化</b>（相对端点 → 前导双星加斜杠 + 尾部双星），本类复刻该语义，
 * 作为 v2 注册侧的单一实现来源，保证「业务层引用不受影响」的端点语义与 v1 完全一致。</p>
 *
 * <p>归一化规则（与 v1 一致）：</p>
 * <ul>
 *   <li>以双星开头 → 保持；否则以斜杠开头则前缀双星，否则前缀双星加斜杠；</li>
 *   <li>以双星结尾 → 保持；否则去除尾部星号串后追加双星。</li>
 * </ul>
 */
public final class RoutePatterns {

    /** 尾部星号串（含单个 {@code *} 与 {@code **}），归一化时被替换为 {@code **}。 */
    private static final Pattern TRAILING_WILDCARDS = Pattern.compile("\\*+$");

    private RoutePatterns() {
    }

    /**
     * 归一化为 Playwright glob 可命中真实 URL 的 pattern。
     *
     * @param urlPattern 业务层端点 / 相对或绝对 URL pattern；{@code null} 原样返回
     * @return 前后缀通配归一化后的 glob pattern
     */
    public static String normalize(String urlPattern) {
        if (urlPattern == null) {
            return null;
        }
        String normalized = urlPattern;
        if (!normalized.startsWith("**")) {
            normalized = normalized.startsWith("/") ? "**" + normalized : "**/" + normalized;
        }
        if (!normalized.endsWith("**")) {
            normalized = TRAILING_WILDCARDS.matcher(normalized).replaceFirst("") + "**";
        }
        return normalized;
    }
}
