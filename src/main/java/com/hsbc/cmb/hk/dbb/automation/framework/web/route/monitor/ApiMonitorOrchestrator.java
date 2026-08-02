package com.hsbc.cmb.hk.dbb.automation.framework.web.route.monitor;

import com.hsbc.cmb.hk.dbb.automation.framework.web.utils.LoggingConfigUtil;
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

    /** 全局已注册的 pattern（去重核心：多个 case 监控同一 API 只注册一次） */
    private final Set<String> registeredPatterns = ConcurrentHashMap.newKeySet();

    /** pattern → apiOwner（供失败时反查通知对象，单人） */
    private final Map<String, String> patternToOwner = new ConcurrentHashMap<>();

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

    public static void reset() {
        INSTANCE = null;
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
            LoggingConfigUtil.logInfoIfVerbose(LOGGER,
                    "[ApiMonitor] 功能 '{}' 在监控清单中不存在或为空，跳过", featureKey);
            return 0;
        }

        int registered = 0;
        for (Map.Entry<String, ApiMonitorConfig.EndpointConfig> e : endpoints.entrySet()) {
            String pattern = e.getKey();
            ApiMonitorConfig.EndpointConfig cfg = e.getValue();

            // 去重：同一 pattern 只注册一次
            if (!registeredPatterns.add(pattern)) {
                LoggingConfigUtil.logDebugIfVerbose(LOGGER,
                        "[ApiMonitor] pattern '{}' 已注册，跳过重复监控", pattern);
                continue;
            }

            try {
                com.hsbc.cmb.hk.dbb.automation.framework.web.route.dsl.RouteDsl.on(page)
                        .api(pattern)
                        .monitor()
                        .expectStatus(cfg.getExpectStatus() == null ? 200 : cfg.getExpectStatus())
                        .timeout(cfg.getTimeout() == null ? 30 : cfg.getTimeout())
                        .autoStopOnMatch(cfg.getAutoStopMonitor() == null || cfg.getAutoStopMonitor())
                        .done()
                        .start();
                registered++;
                patternToOwner.put(pattern, cfg.getApiOwner());
                LoggingConfigUtil.logInfoIfVerbose(LOGGER,
                        "[ApiMonitor] 已注册监控：功能='{}' pattern='{}' owner='{}'",
                        featureKey, pattern, cfg.getApiOwner());
            } catch (RuntimeException ex) {
                // 注册失败不影响主流程，但移除去重标记以便下次重试
                registeredPatterns.remove(pattern);
                LOGGER.warn("[ApiMonitor] 注册监控失败：pattern='{}' error={}", pattern, ex.getMessage());
            }
        }
        return registered;
    }

    /** 是否已为该 pattern 注册过监控（供外部判断是否跳过） */
    public boolean isRegistered(String pattern) {
        return registeredPatterns.contains(pattern);
    }

    /** 反查 pattern 对应的 apiOwner（失败通知用，单人） */
    public String getOwner(String pattern) {
        return patternToOwner.get(pattern);
    }

    /** 清空去重记录（测试套件结束时调用） */
    public void clear() {
        registeredPatterns.clear();
        patternToOwner.clear();
    }
}
