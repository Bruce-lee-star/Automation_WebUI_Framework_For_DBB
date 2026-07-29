package com.hsbc.cmb.hk.dbb.automation.framework.api.playwright.client;

import com.hsbc.cmb.hk.dbb.automation.framework.api.playwright.core.entity.ApiRequestEntity;
import com.microsoft.playwright.APIRequestContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * PlaywrightApiClientManager - {@link ApiRequestContext} 生命周期的<b>门面</b>。
 * <p>
 * 实际缓存与复用逻辑已下沉到线程作用域的 {@link ApiContextScope}（并行安全）。
 * 本类仅保留：上下文获取/释放的便捷入口，以及代理维度的 key/URL 计算。
 * <p>
 * 企业级能力：
 * <ul>
 *   <li><b>SSL</b>：由 {@link ApiContextScope} 依据配置设置 {@code ignoreHTTPSErrors}。</li>
 *   <li><b>代理</b>：依据实体 proxy 字段计算上下文代理维度。</li>
 * </ul>
 */
public final class PlaywrightApiClientManager {

    private static final Logger LOGGER = LoggerFactory.getLogger(PlaywrightApiClientManager.class);

    private PlaywrightApiClientManager() {
    }

    /**
     * 获取（或懒创建）与给定实体匹配的 APIRequestContext。委托给当前线程作用域，并行安全。
     *
     * @param entity 请求实体（提供 proxy / SSL 维度），不可为 null
     */
    public static APIRequestContext getContext(ApiRequestEntity entity) {
        return ApiContextScope.current().getOrCreate(entity);
    }

    /**
     * 释放当前线程作用域持有的所有上下文与自建 Playwright 实例（场景级清理）。
     * 建议在 {@code @AfterScenario / @After} 钩子中调用，避免纯接口场景下的 Node 进程泄漏。
     */
    public static void dispose() {
        ApiContextScope.disposeCurrent();
    }

    public static boolean isUsingSharedPlaywright() {
        ApiContextScope scope = ApiContextScope.getCurrent();
        return scope != null && scope.isUsingSharedPlaywright();
    }

    /** 代理维度缓存 key：无代理为 "default" */
    static String proxyKey(ApiRequestEntity entity) {
        String proxy = proxyUrl(entity);
        return proxy != null ? proxy : "default";
    }

    static String proxyUrl(ApiRequestEntity entity) {
        if (entity.getProxyHost() == null || entity.getProxyHost().trim().isEmpty()) {
            return null;
        }
        String schema = entity.getProxySchema() != null && !entity.getProxySchema().trim().isEmpty()
                ? entity.getProxySchema().trim() : "http";
        return schema + "://" + entity.getProxyHost() + ":" + entity.getProxyPort();
    }

    static String maskProxy(String proxy) {
        // 简单脱敏：仅记录主机端口，不记录任何凭据
        return proxy.replaceAll("//.*@", "//***@");
    }
}
