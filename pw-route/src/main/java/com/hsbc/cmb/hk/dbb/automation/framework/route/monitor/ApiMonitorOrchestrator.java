package com.hsbc.cmb.hk.dbb.automation.framework.route.monitor;

import com.hsbc.cmb.hk.dbb.automation.framework.common.config.VerboseLogging;
import com.hsbc.cmb.hk.dbb.automation.framework.route.core.engine.RouteContextState;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;

import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

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

    /** CT2-19：关闭钩子注册失败累计数（非零 ⇒ 曾出现「钩子未挂上」的 context，已在失败时回滚登记）。 */
    private final AtomicLong closeHookFailures = new AtomicLong();

    /**
     * N-07（doc 21 HIGH）：请求的功能不在监控清单中（或该功能为空）而<b>跳过注册</b>的累计次数。
     *
     * <p><b>为何必须计数</b>：非零意味着「调用方以为在监控、实际一条规则都没注册」——
     * 该功能的 API 断言<b>整体不存在</b>，用例会静默全绿（假绿方向）。此计数供套件末尾 / CI 断言，
     * 把"监控静默消失"变成可观测事实。</p>
     */
    private final AtomicLong missingFeatureSkips = new AtomicLong();

    /** N-07：功能缺失（监控未注册）累计次数，供套件末尾 / CI 断言。 */
    public long getMissingFeatureSkipCount() {
        return missingFeatureSkips.get();
    }

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
            boolean explicitConfigPath = configPath != null && !configPath.trim().isEmpty();
            long skipped = missingFeatureSkips.incrementAndGet();
            //  N-07（doc 21 HIGH，假绿方向）：原实现仅 verbose-gated INFO 后 return 0 —— 调用方把 0 当作
            //    "已注册 / 无需监控"继续执行，于是该功能的 API 监控断言【整体不存在】而用例全绿。
            //    处置：
            //      ① 无论何种情况都留【非 verbose 门控】的 ERROR + 累计计数（绝不静默消失）；
            //      ② 调用方【显式传入清单路径】却查不到该功能 → fail-closed 抛 RouteConfigException
            //         （明确是清单内容/功能名错配，绝不降级为"无监控"）。
            //    注意：本分支与"同 context 内去重返回 0"是两回事 —— 去重路径不受影响（见下方 reg.add）。
            if (explicitConfigPath) {
                throw new com.hsbc.cmb.hk.dbb.automation.framework.route.core.engine.RouteException
                        .RouteConfigException(
                        "[ApiMonitor] 监控清单 '" + configPath + "' 中不存在功能 '" + featureKey
                                + "'（或该功能为空）→ 拒绝静默降级为「无监控」：该功能的 API 断言会整体失效。"
                                + "请核对清单内容与功能名；若该功能确实是可选的，请改用 2 参重载 "
                                + "registerFeature(featureKey, page) 显式表达「未指定清单路径」的语义。",
                        featureKey);
            }
            LOGGER.error("[ApiMonitor] 功能 '{}' 不在监控清单中（或为空）→ 本次不注册任何监控规则，"
                            + "该功能的 API 断言将不存在（若非预期，请检查监控清单加载路径）。累计跳过次数={}",
                    featureKey, skipped);
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
        if (pattern == null || context == null)  {return false;} 
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
        if (context == null)  {return;} 
        if (closeHooks.add(context)) {
            try {
                context.onClose(ignored -> deregisterContext(context));
            } catch (RuntimeException e) {
                // CT2-19：注册失败必须【回滚 closeHooks 标记 + 提升可见性】。
                //  原实现只记 DEBUG 且**不回滚**：closeHooks 以 BrowserContext 为**强引用键**，
                //  注册失败后该 context 既不会被 onClose 自动注销、又被永久钉在 closeHooks 里 →
                //  context 及其全部 Page 无法 GC，且没有任何告警可观测（自相矛盾的"预期竞争但不得静默"）。
                //  回滚后语义：后续 ensureCloseHook 可重试；即便一直失败，也由 {@link #clear()} /
                //  {@link #pruneContextsMarkedClosed()} 兜底清理。
                closeHooks.remove(context);
                long failures = closeHookFailures.incrementAndGet();
                LOGGER.warn("[ApiMonitor] ensureCloseHook failed (context unavailable at registration time) → "
                                + "rolled back hook bookkeeping to avoid strong-ref leak. failures={}, context={}, cause={}",
                        failures, context, e.toString());
            }
        }
    }

    /**
     * CT2-19：防御性清扫 —— 移除「已被显式标记为关闭」的 context 的全部强键登记。
     *
     * <p>存在的意义：{@code deregisterContext} 依赖 Playwright {@code onClose} 回调；一旦钩子注册失败
     * （见 {@link #ensureCloseHook} 的回滚告警）或 context 因崩溃/被杀未经正常 close 流程，
     * 强键登记表会残留该 context 的强引用。本方法以 {@code RouteContextState.isContextClosed}
     * （由 {@code stopContextEngine} 显式置位）为判据做一次主动清扫，作为被动回调之外的兜底网。
     *
     * <p>幂等；判据只依赖「已被显式标记关闭」，不会误伤仍存活的 context（避免破坏 context 内去重语义）。
     *
     * @return 被清扫的 context 数
     */
    public int pruneContextsMarkedClosed() {
        int pruned = 0;
        for (BrowserContext ctx : new ArrayList<>(registeredPatternsByContext.keySet())) {
            if (RouteContextState.isContextClosed(ctx)) {
                deregisterContext(ctx);
                pruned++;
            }
        }
        for (BrowserContext ctx : new ArrayList<>(closeHooks)) {
            if (RouteContextState.isContextClosed(ctx)) {
                deregisterContext(ctx);
                pruned++;
            }
        }
        return pruned;
    }

    /** CT2-19：关闭钩子注册失败累计数（可观测；非零说明存在强键残留风险，已在失败时回滚）。 */
    public long getCloseHookFailures() {
        return closeHookFailures.get();
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