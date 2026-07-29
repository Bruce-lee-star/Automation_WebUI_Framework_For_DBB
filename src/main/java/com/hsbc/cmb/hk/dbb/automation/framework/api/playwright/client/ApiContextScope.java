package com.hsbc.cmb.hk.dbb.automation.framework.api.playwright.client;

import com.hsbc.cmb.hk.dbb.automation.framework.api.config.FrameworkConfig;
import com.hsbc.cmb.hk.dbb.automation.framework.api.playwright.client.ApiInterceptor;
import com.hsbc.cmb.hk.dbb.automation.framework.api.playwright.client.RetryStrategy;
import com.hsbc.cmb.hk.dbb.automation.framework.api.playwright.core.entity.ApiRequestEntity;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.PlaywrightManager;
import com.microsoft.playwright.APIRequest;
import com.microsoft.playwright.APIRequestContext;
import com.microsoft.playwright.Playwright;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * ApiContextScope - 场景（线程）作用域的 API 请求上下文生命周期管理器。
 * <p>
 * 替代原先 {@link PlaywrightApiClientManager} 中的<b>静态全局缓存</b>，解决并行场景下：
 * <ul>
 *   <li>多场景共享同一份上下文缓存、互相干扰的问题；</li>
 *   <li>{@code cleanup()} 一处调用关闭所有场景上下文的问题。</li>
 * </ul>
 * 每个测试线程绑定一个独立作用域，上下文按代理维度缓存其中；场景结束时仅关闭当前线程作用域，
 * 并行安全且无串场。
 */
public final class ApiContextScope {

    private static final Logger LOGGER = LoggerFactory.getLogger(ApiContextScope.class);

    /** 当前线程的作用域（并行安全：每线程独立） */
    private static final ThreadLocal<ApiContextScope> CURRENT = new ThreadLocal<>();

    private final Map<String, APIRequestContext> contextCache = new HashMap<>();
    private Playwright ownedPlaywright;
    private boolean usingShared;
    private boolean closed;

    /** 本场景（线程）作用域内的拦截器链（场景级覆盖），与全局默认链合并后执行，并行安全 */
    private final List<ApiInterceptor> interceptors = new CopyOnWriteArrayList<>();

    /** 本场景（线程）作用域内的重试策略（场景级覆盖）；为 null 时回退到全局默认 */
    private volatile RetryStrategy retryStrategy;

    private ApiContextScope() {
    }

    /**
     * 获取当前线程的作用域，不存在则创建并绑定（每线程唯一）。
     */
    public static ApiContextScope current() {
        ApiContextScope scope = CURRENT.get();
        if (scope == null) {
            scope = new ApiContextScope();
            CURRENT.set(scope);
        }
        return scope;
    }

    /** 读取当前线程作用域（可能为 null） */
    public static ApiContextScope getCurrent() {
        return CURRENT.get();
    }

    /**
     * 若当前线程绑定的正是给定作用域，则清除其 ThreadLocal 绑定（避免线程池复用时持有已关闭的作用域）。
     * 跨线程调用 {@code close()} 时该作用域可能并不绑定在当前线程，此时静默忽略。
     */
    public static void clearIfCurrent(ApiContextScope scope) {
        if (scope != null && CURRENT.get() == scope) {
            CURRENT.remove();
        }
    }

    /**
     * 关闭当前线程作用域并释放其上下文与（自建的）Playwright 实例，随后清除 ThreadLocal，
     * 避免线程池复用导致的泄漏。建议在 {@code @After}/@AfterScenario 中调用。
     */
    public static void disposeCurrent() {
        ApiContextScope scope = CURRENT.get();
        if (scope != null) {
            scope.dispose();
            CURRENT.remove();
        }
    }

    /**
     * 获取（或懒创建）与实体匹配的 APIRequestContext，按代理维度缓存于本作用域。
     */
    public APIRequestContext getOrCreate(ApiRequestEntity entity) {
        if (closed) {
            throw new IllegalStateException("ApiContextScope has been closed; create a new step instead.");
        }
        if (entity == null) {
            throw new IllegalArgumentException("ApiRequestEntity must not be null");
        }
        String key = PlaywrightApiClientManager.proxyKey(entity);
        // 快速路径：缓存命中直接返回，避免每次请求都进入 synchronized
        // （ThreadLocal 保证每线程独立作用域，HashMap 仅被本线程访问，无并发问题）
        APIRequestContext cached = contextCache.get(key);
        if (cached != null) {
            return cached;
        }
        synchronized (this) {
            cached = contextCache.get(key);
            if (cached != null) {
                return cached;
            }
            Playwright playwright = tryReuseSharedPlaywright();
            if (playwright == null) {
                // 纯接口模式：自建轻量 Playwright（不启动浏览器）。
                Map<String, String> env = new HashMap<>();
                env.put("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1");
                env.put("NODE_OPTIONS", "--no-deprecation");
                ownedPlaywright = Playwright.create(new Playwright.CreateOptions().setEnv(env));
                playwright = ownedPlaywright;
                usingShared = false;
                LOGGER.info("Created dedicated Playwright instance for API requests (pure-API mode).");
            } else {
                usingShared = true;
                LOGGER.info("Reusing shared Playwright instance from PlaywrightManager for API requests.");
            }

            APIRequest.NewContextOptions options = new APIRequest.NewContextOptions()
                    .setTimeout((long) FrameworkConfig.getConnectionTimeout())
                    .setIgnoreHTTPSErrors(FrameworkConfig.isSslRelaxValidation());
            String proxy = PlaywrightApiClientManager.proxyUrl(entity);
            if (proxy != null) {
                options.setProxy(proxy);
                LOGGER.info("API request context will use proxy: {}", PlaywrightApiClientManager.maskProxy(proxy));
            }
            if (FrameworkConfig.isSslRelaxValidation()) {
                LOGGER.info("API request context relaxes SSL certificate validation (ignoreHTTPSErrors=true).");
            }

            APIRequestContext context = playwright.request().newContext(options);
            contextCache.put(key, context);
            return context;
        }
    }

    private static Playwright tryReuseSharedPlaywright() {
        try {
            Playwright shared = PlaywrightManager.getPlaywright();
            return shared;
        } catch (Throwable t) {
            LOGGER.debug("Shared Playwright not available, will create dedicated instance: {}", t.getMessage());
            return null;
        }
    }

    /** 释放本作用域持有的所有上下文与自建 Playwright 实例（幂等） */
    public synchronized void dispose() {
        if (closed) {
            return;
        }
        for (Map.Entry<String, APIRequestContext> entry : contextCache.entrySet()) {
            try {
                entry.getValue().dispose();
            } catch (Throwable t) {
                LOGGER.warn("Failed to dispose APIRequestContext [{}]: {}", entry.getKey(), t.getMessage());
            }
        }
        contextCache.clear();
        if (ownedPlaywright != null) {
            try {
                ownedPlaywright.close();
            } catch (Throwable t) {
                LOGGER.warn("Failed to close dedicated Playwright instance: {}", t.getMessage());
            }
            ownedPlaywright = null;
        }
        closed = true;
    }

    public boolean isUsingSharedPlaywright() {
        return usingShared;
    }

    public boolean isClosed() {
        return closed;
    }

    // ============ 拦截器 / 重试策略（场景级覆盖，并行安全） ============

    /** 追加本场景级拦截器（与全局默认链合并执行） */
    public void addInterceptor(ApiInterceptor interceptor) {
        if (interceptor != null) {
            interceptors.add(interceptor);
        }
    }

    /** 本场景级拦截器链（可能为空） */
    public List<ApiInterceptor> getInterceptors() {
        return interceptors;
    }

    /** 设置本场景级重试策略覆盖（为 null 则回退全局默认） */
    public void setRetryStrategy(RetryStrategy strategy) {
        if (strategy != null) {
            this.retryStrategy = strategy;
        }
    }

    /** 本场景级重试策略（可能为 null，表示回退全局默认） */
    public RetryStrategy getRetryStrategy() {
        return retryStrategy;
    }
}
