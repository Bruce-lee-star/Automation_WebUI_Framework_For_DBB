package com.hsbc.cmb.hk.dbb.automation.framework.route.core.rule;

import com.hsbc.cmb.hk.dbb.automation.framework.common.config.VerboseLogging;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import com.hsbc.cmb.hk.dbb.automation.framework.route.core.engine.RouteContextState;
import com.hsbc.cmb.hk.dbb.automation.framework.route.core.engine.RouteEngine;
import com.hsbc.cmb.hk.dbb.automation.framework.route.core.engine.RouteException;
import com.hsbc.cmb.hk.dbb.automation.framework.route.core.lifecycle.RouteMonitorSession;
import com.hsbc.cmb.hk.dbb.automation.framework.route.core.lifecycle.StoppedCapabilityManager;

/**
 * 路由规则注册与索引仓库（T2-4 拆分，自 {@code RouteEngine} 提取）。
 *
 * <p>负责将 {@link RouteRule} 注册到 Page / BrowserContext（自动升级为 context 级单原生绑定点），
 * 维护按上下文索引的规则链（{@code RouteContextState.CONTEXT_RULES_BY_CONTEXT}），
 * 并在锁外执行原生 {@code context.route()} 注册（JNI/网络 IO）与 MonitorSession 刷新。
 *
 * <p>pattern 归一化复用 {@code RouteEngine.normalizePattern}（与注册语义一致，单一实现来源）；
 * 原生 route 回调统一委派 {@code RouteEngine.dispatchRoute}；日志复用 {@code RouteEngine.LOGGER}。
 *
 * @apiNote framework-internal：框架内部类型，非公开 API。跨子包 public 可见性仅为分层迁移需要，外部不得依赖。
 */
public final class RuleRepository {

    /**
     * 注册器（内部函数式接口）。
     *
     * @apiNote framework-internal：框架内部类型，非公开 API。跨子包 public 可见性仅为分层迁移需要，外部不得依赖。
     */
    @FunctionalInterface
    public interface RouteRegistrar {
        void register(String pattern, RouteRule rule);
    }

    /** 注册路由规则到 Page（升级为 context 级绑定）。 */
    public static void register(Page page, List<RouteRule> rules) {
        VerboseLogging.logDebugIfVerbose(RouteEngine.LOGGER, "[RouteEngine] ── Registering {} rule(s) on Page ──", rules.size());
        //  Phase 3 统一绑定：page 规则升级为 context 级绑定（单原生绑定点 context.route）。
        //    打 scope=PAGE + pageRef=page 逻辑标签，存进同 context 存储，交由 context.route 统一分发；
        //    不再使用 page.route / PAGE_RULES / reRegisterRules 跨页迁移链路（#6 #7 根除）。
        for (RouteRule r : rules) {
            if (r != null) {
                r.setScope(RouteRuleScope.PAGE);
                r.setPageRef(page);
            }
        }
        register(page.context(), rules);
    }

    /**
     * 注册路由规则到 BrowserContext。
     */
    public static void register(BrowserContext context, List<RouteRule> rules) {
        VerboseLogging.logDebugIfVerbose(RouteEngine.LOGGER, "[RouteEngine] ── Registering {} rule(s) on BrowserContext ──", rules.size());
        registerInternal(context, (pattern, rule) -> {
            Map<String, List<RouteRule>> store = RouteContextState.CONTEXT_RULES_BY_CONTEXT.computeIfAbsent(context, k -> new ConcurrentHashMap<>());
            //  仅在锁内完成 store 的纯内存状态决策，绝不持有锁调用原生 context.route()（JNI/网络 IO）。
            //    原生注册放在锁外执行，避免高并发注册/异常时持锁做 IO 导致线程长时间阻塞甚至重入风险。
            final boolean[] needRegister = {false};
            final boolean[] needRefresh = {false};
            //  用 AtomicReference 而非泛型数组：new List[1] 会产生「未经检查的转换」警告。
            final AtomicReference<List<RouteRule>> chainRef = new AtomicReference<>();
            synchronized (store) {
                List<RouteRule> chain = store.get(pattern);
                if (chain == null) {
                    // 首次注册：建链 + 绑定闭包（闭包捕获链引用，后续追加自动可见）
                    chain = new CopyOnWriteArrayList<>();
                    store.put(pattern, chain);
                    //  仅作清理记录（RouteRegistry 不再用于优先级决策，此处保留供 clearContext 反查 pattern）
                    RouteRegistry.forceRegister(context, pattern, rule.getType());
                    needRegister[0] = true;
                }
                chain.add(rule);
                chainRef.set(chain);
                //  追加规则携带 MONITOR 能力且当前无活跃会话时，锁外刷新（「先 modify 后追加 monitor」逆序场景）
                //  与 Page 级同理：仅按能力位 isMonitorEnabled() 判定，不再混用 type 默认值。
                if (rule.isMonitorEnabled()) {
                    needRefresh[0] = true;
                }
            }
            // 锁外执行原生路由注册（JNI）与 MonitorSession 刷新，不阻塞其它线程对 store 的访问
            if (needRegister[0]) {
                try {
                    registerRouteToContext(context, pattern, chainRef.get(), rule);
                } catch (Throwable t) {
                    //  关键一致性保护：原生注册失败（page 关闭竞态 / pattern 非法等）时，
                    //    必须回滚锁内已提交的 store + RouteRegistry 写入，否则会出现
                    //    「内存认为已注册、但实际路由从未绑定」的静默失效（请求完全不被拦截且无告警）。
                    synchronized (store) {
                        store.remove(pattern);
                    }
                    RouteRegistry.unregister(context, pattern);
                    if (t instanceof RouteException.RouteConnectionUnresponsiveException unresponsive) {
                        //  连接无响应是【环境级致命失败】：回滚后必须【上抛】让用例快速失败。
                        //  若按既有的"记 ERROR 后继续"处理，会静默变成"mock 规则未生效、断言打到真实后端"
                        //  —— 结果不可信且极难定位（与本次卡死同一根因的次生危害）。
                        throw unresponsive;
                    }
                    RouteEngine.LOGGER.error("[RouteEngine] Native route registration failed for pattern '{}' on "
                            + "BrowserContext — rolled back in-memory state to avoid silent mismatch: {}",
                            pattern, t.getMessage());
                }
            }
            if (needRefresh[0]) {
                //  B3：会话绑定链头（mergeSource 归属）；是否创建按整链的 MONITOR 能力判定
                List<RouteRule> chain = chainRef.get();
                boolean chainHasMonitor = false;
                for (RouteRule r : chain) {
                    //  仅按能力位判定（理由同上：type 的 MONITOR 是默认值，不代表监控能力）
                    if (r.isMonitorEnabled()) {
                        chainHasMonitor = true;
                        break;
                    }
                }
                RouteMonitorSession.refreshMonitorSession(context, pattern, chain.get(0), chainHasMonitor);
            }
        }, rules);
    }

    /**
     * 注册路由规则到上下文（自动判断 Page 或 BrowserContext，适配 DSL 层调用）。
     *
     * @param context Page 或 BrowserContext 实例
     * @param rules   路由规则列表
     * @throws IllegalArgumentException 如果 context 类型不支持
     */
    public static void register(Object context, List<RouteRule> rules) {
        if (context instanceof Page) {
            register((Page) context, rules);
        } else  {if (context instanceof BrowserContext) {
            register((BrowserContext) context, rules);
        } else {
            throw new IllegalArgumentException(
                    "Unsupported context type: " + context.getClass().getName()
                            + ". Expected Page or BrowserContext.");
        }} 
    }

    /**
     * 内部统一注册逻辑（异常隔离：单个规则失败不影响后续规则）。
     */
    private static void registerInternal(Object context, RouteRegistrar registrar, List<RouteRule> rules) {
        for (RouteRule rule : rules) {
            try {
                String pattern = rule.getUrlPattern();
                if (pattern == null || pattern.trim().isEmpty()) {
                    RouteEngine.LOGGER.warn("[RouteEngine] Skipping rule with empty urlPattern");
                    continue;
                }
                // 归一化复用 RouteEngine.normalizePattern（与注册语义一致，单一实现来源）
                String normalized = RouteEngine.normalizePattern(pattern);
                VerboseLogging.logDebugIfVerbose(RouteEngine.LOGGER,
                        "[RouteEngine] registerInternal: original='{}' -> normalized='{}', type={}, context={}",
                        pattern, normalized, rule.getType(), context.getClass().getSimpleName());
                registrar.register(normalized, rule);
            } catch (Exception e) {
                RouteEngine.LOGGER.error("[RouteEngine] Failed to register rule for pattern '{}': {}",
                        rule.getUrlPattern(), e.getMessage(), e);
            }
        }
    }

    /**
     * 注册 Playwright 路由到 BrowserContext（实际 route + session 创建 + 跨层级缓存）。
     *
     * <p><b>为何必须"有界"（2026-09-26 卡死根因修复）</b>：Playwright 的 {@code context.route(...)} 底层是
     * {@code setNetworkInterceptionPatterns}，且该命令<b>无客户端超时</b>
     * （playwright 1.62 {@code BrowserContextImpl.updateInterceptionPatterns} 传 {@code NO_TIMEOUT}）——
     * 浏览器不回 ACK 时调用方<b>永久阻塞</b>。实测：{@code main} 线程停在
     * {@code RuleRepository.registerRouteToContext → BrowserContext.route}，两次采样（240s / 300s）
     * 停在<b>同一帧</b>，整轮用例就此挂死（业务栈：{@code LoginSteps.logonDBBEnvironmentAsUser:102 → RouteDsl.start}）。
     *
     * <p>故原生注册改为在守护线程执行、主线程<b>有界等待</b>（系统属性 {@code route.register.timeout.ms}
     * 优先，其次环境变量 {@code ROUTE_REGISTER_TIMEOUT_MS}，默认 20s —— 与 unroute 预算同量级）。
     * 超时即：① 标记该 Context「连接无响应」（后续路由操作<b>立即快速失败</b>，不再各付一次预算）；
     * ② 抛 {@link RouteException.RouteConnectionUnresponsiveException} 让用例<b>快速失败</b>——
     * <b>绝不</b>静默降级为"未注册路由继续跑"（那会让 mock 静默失效、断言打到真实后端，结果不可信）。
     * 被放弃的守护线程随浏览器恢复 / 进程退出回收，不会阻塞任何在途调用。
     */
    private static void registerRouteToContext(BrowserContext context, String pattern, List<RouteRule> chain, RouteRule rule) {
        //  Context 级规则链已在 register(BrowserContext) 中写入 RouteContextState.CONTEXT_RULES_BY_CONTEXT，此处无需重复存储
        RouteEngine.LOGGER.debug("[RouteEngine] Context rule cached: type={}, pattern='{}'",
                rule.getType(), pattern);
        RouteMonitorSession.startMonitorSession(context, rule, pattern);
        RouteEngine.LOGGER.info("[RouteEngine] Route registered: type={}, pattern='{}', context=BrowserContext, scope={}, pageRef=#{}",
                rule.getType(), pattern, rule.getScope(),
                rule.getPageRef() == null ? "null" : String.valueOf(System.identityHashCode(rule.getPageRef())));
        VerboseLogging.logDebugIfVerbose(RouteEngine.LOGGER,
                "[RouteEngine]    rule detail: urlPattern='{}', type={}, delay={}ms, mockStatus={}, record={}, autoStop={}",
                rule.getUrlPattern(), rule.getType(), rule.getDelayMs(), rule.getMockStatus(),
                rule.isRecord(), rule.isAutoStopOnMatch());
    }

    /** 原生路由注册的等待预算默认值（毫秒）：与 unroute 预算（20s）同量级。 */
    static final long DEFAULT_ROUTE_REGISTER_TIMEOUT_MS = 20_000L;

    /**
     * 原生路由注册的等待预算：系统属性 {@code route.register.timeout.ms} 优先，其次环境变量
     * {@code ROUTE_REGISTER_TIMEOUT_MS}，最后默认 {@value #DEFAULT_ROUTE_REGISTER_TIMEOUT_MS}ms。
     *
     * <p>每次调用实时解析（不缓存）——使运维/单测可在运行期调整，且与
     * {@code RouteLifecycleRegistry.teardownFenceMs()} 的解析约定一致。
     */
    private static long routeRegisterTimeoutMs() {
        Long fromProperty = positiveLongOrNull(System.getProperty("route.register.timeout.ms"));
        if (fromProperty != null) {
            return fromProperty;
        }
        Long fromEnv = positiveLongOrNull(System.getenv("ROUTE_REGISTER_TIMEOUT_MS"));
        return fromEnv != null ? fromEnv : DEFAULT_ROUTE_REGISTER_TIMEOUT_MS;
    }

    /** 解析正 long；null / 非法 / 负数一律返回 null（交由调用方回退）。 */
    private static Long positiveLongOrNull(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return null;
        }
        try {
            long value = Long.parseLong(raw.trim());
            return value > 0 ? value : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 在守护线程执行<b>无客户端超时</b>的原生注册，主线程有界等待。
     *
     * <p>契约（由单测守卫）：
     * <ol>
     *   <li>Context 已被标记「无响应」→ <b>立即</b>抛异常，不做任何等待（避免每个 pattern 各付一次预算）；</li>
     *   <li>预算内完成 → 返回原生句柄；注册本身抛错 → 原样上抛（保留既有异常语义与调用方回滚逻辑）；</li>
     *   <li>预算耗尽 → 标记无响应 + 抛 {@link RouteException.RouteConnectionUnresponsiveException}
     *       （<b>有界失败</b>，绝不无限等待；守护线程被放弃但不阻塞任何在途调用）。</li>
     * </ol>
     *
     * @param context  目标上下文（Page 级注册已升级为 Context 级）
     * @param pattern  归一化 pattern
     * @param chain    规则链（闭包捕获，分发期合并）
     * @param timeoutMs 等待预算（毫秒）
     * @return 原生路由句柄（{@code context.route(...)} 的返回值）
     * @apiNote package-private：仅供同包调用与确定性单测传入自定义预算。
     */
    static AutoCloseable registerNativeRouteBounded(BrowserContext context, String pattern,
                                                    List<RouteRule> chain, long timeoutMs) {
        if (RouteContextState.isUnresponsive(context)) {
            throw unresponsiveRouteFailure(pattern,
                    "connection already flagged unresponsive — failing fast without waiting");
        }
        final java.util.concurrent.atomic.AtomicReference<AutoCloseable> handleRef =
                new java.util.concurrent.atomic.AtomicReference<>();
        final java.util.concurrent.atomic.AtomicReference<Throwable> errorRef =
                new java.util.concurrent.atomic.AtomicReference<>();
        Thread worker = new Thread(() -> {
            try {
                handleRef.set(context.route(pattern, route -> RouteEngine.dispatchRoute(route, chain)));
            } catch (Throwable t) {
                errorRef.set(t);
            }
        }, "route-register-" + System.identityHashCode(context));
        worker.setDaemon(true);
        long startMs = System.currentTimeMillis();
        worker.start();
        try {
            worker.join(Math.max(1L, timeoutMs));
        } catch (InterruptedException e) {
            //  中断来自上层收尾（CloseGuard / 套件取消）：如实回报，不吞掉中断状态。
            Thread.currentThread().interrupt();
            throw new RouteException.RouteRuntimeException(
                    "Interrupted while registering native route (browser round-trip has no client timeout)",
                    pattern, null, e);
        }
        if (worker.isAlive()) {
            long waited = System.currentTimeMillis() - startMs;
            RouteContextState.markUnresponsive(context);
            throw unresponsiveRouteFailure(pattern, "browser did not ACK setNetworkInterceptionPatterns within "
                    + waited + "ms (budget=" + timeoutMs + "ms)");
        }
        Throwable error = errorRef.get();
        if (error != null) {
            //  原样上抛，保持既有异常语义（调用方据此回滚内存状态；RouteException 家族须保留子类型）。
            if (error instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (error instanceof Error fatal) {
                throw fatal;
            }
            throw new RouteException.RouteRuntimeException(
                    "Native route registration failed: " + error.getMessage(), pattern, null, error);
        }
        return handleRef.get();
    }

    /**
     * 构造「连接无响应」失败（统一文案：现象 + 为何不等待 + 后续处置），并落 ERROR 日志。
     */
    private static RouteException.RouteConnectionUnresponsiveException unresponsiveRouteFailure(
            String pattern, String reason) {
        String message = "Browser/connection unresponsive on native route registration (" + reason + "). "
                + "Playwright's setNetworkInterceptionPatterns has NO client timeout, so waiting would hang forever. "
                + "Failed fast with a bounded budget; the context is flagged unresponsive and the browser is "
                + "rebuilt on the next scenario.";
        RouteEngine.LOGGER.error("[RouteEngine] {}", message);
        return new RouteException.RouteConnectionUnresponsiveException(message, pattern);
    }

    // ─── 规则索引清理 / 反注册 ───────────────





    /**
     * 该 context 是否已被判定「浏览器/连接无响应」。
     *
     * <p>判定来源见 {@code RouteContextState.UNRESPONSIVE_CONTEXTS}：有界原生注册
     * （{@link #registerNativeRouteBounded}）超时即登记。用途：
     * ① 该 Context 上后续路由操作立即快速失败（不再各付一次预算）；
     * ② web 侧在下一个用例初始化时据此<b>重建 Context/浏览器</b>（{@code RouteLifecycle} SPI），
     * 使"连接损坏"不级联到后续用例。
     *
     * @param context Page / BrowserContext；无法归约时返回 false
     */
    public static boolean isConnectionUnresponsive(Object context) {
        return RouteContextState.isUnresponsive(toBrowserContext(context));
    }


    /** 将 Page / BrowserContext 归约为 BrowserContext（用于查询在途计数）。 */
    private static BrowserContext toBrowserContext(Object context) {
        try {
            if (context instanceof BrowserContext) {
                return (BrowserContext) context;
            }
            if (context instanceof Page) {
                return ((Page) context).context();
            }
        } catch (Exception e) {
            RouteEngine.LOGGER.debug("[RouteEngine] resolve BrowserContext failed: {}", e.getMessage());
        }
        return null;
    }


    /**  移除指定页面的规则缓存（按 pageRef 精确移除，保留同 context 的其它页 / 全局规则）。 */
    public static void removePageRules(Page page) {
        if (page == null)  {return;} 
        //  Phase 3 统一绑定：page 规则存于 context 存储，按 pageRef 精确移除。
        Map<String, List<RouteRule>> scoped = RouteContextState.CONTEXT_RULES_BY_CONTEXT.get(page.context());
        if (scoped != null) {
            for (List<RouteRule> chain : scoped.values()) {
                if (chain == null)  {continue;} 
                chain.removeIf(r -> r != null && r.getScope() == RouteRuleScope.PAGE && r.getPageRef() == page);
            }
            // 自然清理空链（handler 命中 empty chain 分支自动 fallback 放行，与 detachChains 不 unroute 策略一致）
            scoped.values().removeIf(chain -> chain == null || chain.isEmpty());
        }
    }

    /** 统计所有 Context 级规则总数（用于日志）。 */
    public static int contextRuleCount() {
        int count = 0;
        for (Map<String, List<RouteRule>> scoped : RouteContextState.CONTEXT_RULES_BY_CONTEXT.values()) {
            count += scoped.size();
        }
        return count;
    }

    /**  移除指定 pattern 集合中的所有 context 级规则（由 {@link RouteRegistry#clearContext(Object)} 委托）。 */
    public static void removeContextRules(Object context, Set<String> patterns) {
        if (context == null)  {return;} 
        //  必须先移除 context 条目：原实现在 patterns 为空时直接 return，导致「空 Map 残留」
        //    强引用已关闭的 BrowserContext，造成泄漏（见 cleanupClosedContext 注释）。
        Map<String, List<RouteRule>> scoped = RouteContextState.CONTEXT_RULES_BY_CONTEXT.remove(context);
        if (scoped == null)  {return;} 
        // patterns 为 null/空时视为「清理该 context 全部规则」（如 context 关闭时的整体清理）：
        // 复制 keySet 后再遍历，避免并发修改异常（scoped.keySet() 是视图，遍历中 remove 会 CME）。
        Set<String> toRemove = (patterns == null || patterns.isEmpty())
                ? new HashSet<>(scoped.keySet())
                : patterns;
        for (String pattern : toRemove) {
            List<RouteRule> ownerChain = scoped.remove(pattern);
            if (ownerChain != null) {
                //  最后就地清空链内容：BrowserContext 上的路由闭包捕获的是这个 List 引用
                //    （见 detachChains 注释），不清空则 clearContext 后旧规则仍会继续生效。
                ownerChain.clear();
            }
        }
        VerboseLogging.logDebugIfVerbose(RouteEngine.LOGGER,
                "[RouteEngine] Removed {} context rules for context, remaining: {}",
                toRemove.size(), scoped.size());
    }

    /**
     *  清理指定上下文的全部路由状态（场景/用例结束时调用，防止内存泄漏 + 跨用例污染）。
     *
     * <p><b>收尾顺序约定（关键）</b>：先做完<b>全部纯内存清理</b>（零 IO、不阻塞），
     * 最后才把「原生 unroute」<b>异步</b>下沉到守护线程。由此保证：
     * <ol>
     *   <li><b>case 结束不阻塞主线程</b>：原生 {@code unroute}（逐 pattern close 句柄）需浏览器往返 ACK，
     *       浏览器不响应时会长时间挂起（旧实现主线程最多等 {@code 在途排空 10s + join 20s = 30s}）。
     *       改为异步 fire-and-forget 后主线程立即返回。</li>
     *   <li><b>与 timeout 配置无关</b>：monitor 的超时 {@code ScheduledFuture} 在第 2 步即被
     *       {@code MonitorSession.stop()} <b>无条件取消</b>，业务配 60s 还是更久都不会让收尾多等一毫秒；
     *       delay / mock / modify 的规则链在第 1 步被<b>就地 clear</b>（闭包捕获的正是该 List 引用），
     *       后续请求走 empty-chain fallback 放行 —— 不会出现跨用例污染。</li>
     *   <li><b>异常隔离（旧实现的真实缺陷）</b>：每一步独立 try/catch，任一处失败不影响后续清理。
     *       旧实现无隔离，原生 unroute 一旦抛异常会导致其后 monitor 会话 / 防重门控 / 能力标记
     *       <b>全部跳过</b>，形成跨用例残留。</li>
     * </ol>
     */
    public static void clearContext(Object context) {
        // 1. 纯内存：移除 pattern 登记 + 就地清空规则链（monitor / delay / mock / modify 全部类型）
        Map<String, RouteHandleType> patterns = null;
        try {
            patterns = RouteRegistry.removeContextPatterns(context);
            if (patterns != null && !patterns.isEmpty()) {
                removeContextRules(context, patterns.keySet());
            }
        } catch (Throwable t) {
            RouteEngine.LOGGER.warn("[RouteEngine] clearContext: in-JVM rule cleanup failed for {}: {}",
                    context.getClass().getSimpleName(), t.getMessage());
        }

        // 2. 取消 monitor 超时调度（无条件，与其 timeout 配置无关）+ 移除会话
        try {
            RouteMonitorSession.clearMonitorSessions(context);
        } catch (Throwable t) {
            RouteEngine.LOGGER.warn("[RouteEngine] clearContext: monitor session cleanup failed for {}: {}",
                    context.getClass().getSimpleName(), t.getMessage());
        }

        // 3. 清理 Route 防重门控（按 Context 精确清理）
        try {
            RouteEngine.clearDispatchedRoutes(context instanceof BrowserContext ? (BrowserContext) context : null);
        } catch (Throwable t) {
            RouteEngine.LOGGER.warn("[RouteEngine] clearContext: dispatched routes cleanup failed for {}: {}",
                    context.getClass().getSimpleName(), t.getMessage());
        }

        // 4. 清理 per-context 的「已停止能力」标记（防强引用泄漏与跨用例残留，修复 C1）
        try {
            StoppedCapabilityManager.clearStoppedCapabilities(context);
        } catch (Throwable t) {
            RouteEngine.LOGGER.warn("[RouteEngine] clearContext: stopped capabilities cleanup failed for {}: {}",
                    context.getClass().getSimpleName(), t.getMessage());
        }

    }

    /**  Context 生命周期结束（onClose）时清理规则索引与引擎合并引用。 */
    public static void cleanupClosedContext(BrowserContext context) {
        if (context == null)  {return;} 
        //  防重门控 + MonitorSession 必须【无条件】清理，且都早于下方的 scoped 判空分支：
        //    「注册过防重门控 / 纯 monitor 但没有路由规则」的 context 关闭后条目否则会永久残留（空 Map 泄漏）。
        RouteEngine.clearDispatchedRoutes(context);
        RouteMonitorSession.clearMonitorSessions(context);
        //  修复 C1：清理 per-context 的「已停止能力」标记（STOPPED_CAPS 以 BrowserContext 为强引用键）。
        StoppedCapabilityManager.clearStoppedCapabilities(context);
        Map<String, List<RouteRule>> scoped = RouteContextState.CONTEXT_RULES_BY_CONTEXT.get(context);
        if (scoped != null) {
            //  即使 scoped 为空 Map 也要走 removeContextRules：该方法无条件移除 context 条目。
            removeContextRules(context, new HashSet<>(scoped.keySet()));
        }
        //  C-11 强键注册表归零守卫：以上各步已分别清理 route 层强键表，此处统一兜底清零所有强键表
        //  （含引擎层 CONTEXT_ENGINES 与在途任务 PENDING_TASKS），确保 context 关闭后强键表对该 context
        //  零残留（防已关闭 context 被长期持有导致泄漏）。幂等，重复调用安全。
        RouteContextState.removeContextFromAllRegistries(context);
    }

    /**  全局清理统一路由规则存储（测试套件结束时调用，必须逐链 clear，见 detachChains 注释）。 */
    public static void clearAllUnifiedRuleStores() {
        for (Map<String, List<RouteRule>> scoped : RouteContextState.CONTEXT_RULES_BY_CONTEXT.values()) {
            detachChains(scoped);
        }
        RouteContextState.CONTEXT_RULES_BY_CONTEXT.clear();
    }

    /**  断开「已注册的 Playwright 路由闭包」与规则链的关联 —— 就地清空链内容。 */
    private static void detachChains(Map<String, List<RouteRule>> store) {
        if (store == null || store.isEmpty())  {return;} 
        for (List<RouteRule> chain : store.values()) {
            if (chain == null)  {continue;} 
            try {
                chain.clear();
            } catch (UnsupportedOperationException e) {
                //  不可变链不支持 clear()；关键在于【绝不能让异常中断整个清理流程】：
                //    一旦抛出，store.clear() 及调用方后续索引清理会被整段跳过 → 跨用例规则残留污染。
                VerboseLogging.logWarnIfVerbose(RouteEngine.LOGGER,
                        "[RouteEngine] detachChains: immutable rule chain cannot be cleared in place; "
                                + "relying on store.clear() to drop the reference.");
            }
        }
        store.clear();
    }
}