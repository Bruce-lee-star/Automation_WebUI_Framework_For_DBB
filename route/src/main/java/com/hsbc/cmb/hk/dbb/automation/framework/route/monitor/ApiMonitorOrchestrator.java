package com.hsbc.cmb.hk.dbb.automation.framework.route.monitor;

import com.hsbc.cmb.hk.dbb.automation.framework.common.config.VerboseLogging;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Route;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * API 监控清单编排器。
 *
 * <p>职责：
 * <ul>
 *   <li>读取 {@link ApiMonitorConfig} 中“按功能配置”的监控清单</li>
 *   <li>将配置翻译为现有 {@code RouteDsl.on(page).api(...).monitor()...} 规则</li>
 *   <li><b>跨 case 去重</b>：同一 endpoint pattern 在多个 case 中只注册一次</li>
 * </ul>
 *
 * <p>用法（在 case / scenario 开始时）：
 * <pre>{@code
 *   ApiMonitorOrchestrator.getInstance().registerFeature("login", page);
 * }</pre>
 */
public class ApiMonitorOrchestrator {

    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger(ApiMonitorOrchestrator.class);

    /**
     * 每 BrowserContext 已注册 pattern（去重核心）。
     * <p>X-3 / R-2：从进程级单例改为 <b>context 级</b>——并行下不同 context 各自独立去重，
     * 不再因全局去重导致后注册的 context 跳过自己的监控规则注册（缺口型跨 context 串扰）。
     * 同一 context 内跨 case 去重语义保持不变。
     */
    private final Map<BrowserContext, Set<String>> registeredPatternsByContext = new ConcurrentHashMap<>();

    /** pattern → apiOwner（供失败时反查通知对象，单人）。owner 与 pattern 绑定属配置元数据，全局共享无串扰风险。 */
    private final Map<String, String> patternToOwner = new ConcurrentHashMap<>();

    /** 已注册 context 关闭钩子的 context 集合（幂等，防止重复注册 onClose 监听） */
    private final Set<BrowserContext> closeHooks = ConcurrentHashMap.newKeySet();

    private static volatile ApiMonitorOrchestrator INSTANCE;

    public static ApiMonitorOrchestrator getInstance() {
        if (INSTANCE == null) {
            synchronized (ApiMonitorOrchestrator.class) {
                if (INSTANCE == null) {
                    INSTANCE = new ApiMonitorOrchestrator();
                }
            }
        }
        return INSTANCE;
    }


    /**
     * 注册某功能下所有 endpoint 的监控规则（使用已加载的默认清单）。
     *
     * @param featureKey  功能名（对应 JSON 的一级 key）
     * @param page        当前页面
     * @return 本次实际新注册的规则数（已存在的 pattern 不计）
     */
    public int registerFeature(String featureKey, Page page) {
        return registerFeature(featureKey, (String) null, page);
    }

    /**
     * 先加载指定 JSON 监控清单，再按功能名注册其下所有 endpoint 的监控规则。
     *
     * <p>典型流程：先 {@code ApiMonitorConfig.loadFrom(path)} 加载清单，
     * 再 {@code orchestrator.registerFeature("login", page)} 按功能名注册。
     * 传入 {@code configPath} 时会先加载该清单（覆盖默认单例）。
     *
     * @param featureKey  功能名（对应 JSON 的一级 key）
     * @param configPath  清单 JSON 路径；为 null 时使用已加载的默认清单
     * @param page        当前页面
     * @return 本次实际新注册的规则数（已存在的 pattern 不计）
     */
    public int registerFeature(String featureKey, String configPath, Page page) {
        ApiMonitorConfig config = (configPath == null || configPath.trim().isEmpty())
                ? ApiMonitorConfig.getInstance()
                : ApiMonitorConfig.loadFrom(configPath);
        Map<String, ApiMonitorConfig.EndpointConfig> endpoints =
                config.getFeatures().get(featureKey);
        if (endpoints == null || endpoints.isEmpty()) {
            VerboseLogging.logInfoIfVerbose(LOGGER,
                    "[ApiMonitor] 功能 '{}' 在监控清单中不存在或为空，跳过", featureKey);
            return 0;
        }

        int registered = 0;
        BrowserContext ctx = page.context();
        //  为当前 context 注册一次关闭钩子：context 关闭时自动释放其下 pattern 的去重标记，防跨 context 残留。
        ensureCloseHook(ctx);
        //  X-3 / R-2：去重按 context 隔离，避免并行下后注册 context 被全局去重跳过（缺口型串扰）。
        Set<String> reg = registeredPatternsByContext.computeIfAbsent(ctx, k -> ConcurrentHashMap.newKeySet());
        for (Map.Entry<String, ApiMonitorConfig.EndpointConfig> e : endpoints.entrySet()) {
            String pattern = e.getKey();
            ApiMonitorConfig.EndpointConfig cfg = e.getValue();

            // 去重：同一 context 内同一 pattern 只注册一次
            if (!reg.add(pattern)) {
                VerboseLogging.logDebugIfVerbose(LOGGER,
                        "[ApiMonitor] pattern '{}' 已注册（context 内去重），跳过重复监控", pattern);
                continue;
            }

            try {
                com.hsbc.cmb.hk.dbb.automation.framework.route.dsl.RouteDsl.on(page)
                        .api(pattern)
                        .monitor()
                        .expectStatus(cfg.getExpectStatus() == null ? 200 : cfg.getExpectStatus())
                        .timeout(cfg.getTimeout() == null ? 30 : cfg.getTimeout())
                        .autoStopOnMatch(cfg.getAutoStopMonitor() == null || cfg.getAutoStopMonitor())
                        .done()
                        .start();
                registered++;
                patternToOwner.put(pattern, cfg.getApiOwner());
                VerboseLogging.logInfoIfVerbose(LOGGER,
                        "[ApiMonitor] 已注册监控：功能='{}' pattern='{}' owner='{}'",
                        featureKey, pattern, cfg.getApiOwner());
            } catch (RuntimeException ex) {
                // 注册失败不影响主流程，但移除去重标记以便同 context 下次重试
                reg.remove(pattern);
                LOGGER.warn("[ApiMonitor] Failed to register monitor: pattern='{}' error={}", pattern, ex.getMessage());
            }
        }
        return registered;
    }

    /** 指定 context 是否已注册该 pattern（context 级去重查询；X-3 / R-2 后不再提供无 context 版本）。 */
    public boolean isRegistered(String pattern, BrowserContext context) {
        if (pattern == null || context == null) return false;
        Set<String> reg = registeredPatternsByContext.get(context);
        return reg != null && reg.contains(pattern);
    }

    /** 反查 pattern 对应的 apiOwner（失败通知用，单人） */
    public String getOwner(String pattern) {
        return patternToOwner.get(pattern);
    }

    /** 清空去重记录（测试套件结束时调用；亦作为 {@link #deregisterContext(BrowserContext)} 的兜底） */
    public void clear() {
        registeredPatternsByContext.clear();
        patternToOwner.clear();
        closeHooks.clear();
    }

    /**
     *  为指定 context 幂等注册关闭钩子：context 关闭时自动释放其下所有已注册 pattern 的去重标记、
     * owner 映射与 context 关联，避免进程级单例状态跨 context 残留。
     * <p>复用 Playwright 的 {@code onClose} 机制（与 ApiCaptureContext 的 context 清理同源），
     * 多个 onClose 监听可并存，互不干扰。
     */
    private void ensureCloseHook(BrowserContext context) {
        if (context == null) return;
        if (closeHooks.add(context)) {
            try {
                context.onClose(ignored -> deregisterContext(context));
            } catch (RuntimeException e) {
                // context 已不可用时忽略（如注册时 page 已关闭）；预期竞争，但不得静默（D7-3）
                LOGGER.debug("[ApiMonitor] ensureCloseHook: context unavailable, skip hook: {}", e.toString());
            }
        }
    }

    /**
     * 释放指定 context 下所有已注册 pattern 的去重标记、owner 映射与 context 关联（context 关闭时调用）。
     * <p>传入 {@code null} 等同于 {@link #clear()}（套件结束兜底）。
     * <p>仅释放该 context 的条目，其它 context 的注册不受影响 —— 既修复跨 context 残留，
     * 又保留「同一 context 内跨 case 去重」的设计意图。
     */
    public void deregisterContext(BrowserContext context) {
        if (context == null) {
            clear();
            return;
        }
        closeHooks.remove(context);
        //  X-3 / R-2：仅释放该 context 的注册集合，其它 context 不受影响（消除跨 context 残留）
        registeredPatternsByContext.remove(context);
        // patternToOwner 为全局配置元数据（owner 与 pattern 绑定，跨 context 不变），套件结束由 clear() 统一清空
    }
}
