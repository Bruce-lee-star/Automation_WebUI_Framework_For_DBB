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
import com.hsbc.cmb.hk.dbb.automation.framework.common.async.AsyncPool;
import com.hsbc.cmb.hk.dbb.automation.framework.common.route.RouteLifecycleRegistry;
import com.hsbc.cmb.hk.dbb.automation.framework.route.core.lifecycle.StoppedCapabilityManager;
import com.hsbc.cmb.hk.dbb.automation.framework.route.util.RouteUtil;

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
        //  跨用例栅栏（2026-09-26）：本用例可能是「复用上一个用例 Context」的场景，而上一用例的
        //    teardown worker 可能仍在同一 Context 上 drain / close 原生路由（实测重叠 ~1.65s）。
        //    两边并发操作同一个 Playwright Connection 会导致驱动对象生命周期错乱
        //    （Object doesn't exist: response@...）→ 会话校验导航失败 → 缓存误删 → 后续用例卡死。
        //    故在注册新路由（= 开始使用该 Context）之前，有界等待在途收尾结束；无在途时零开销。
        awaitInFlightUnroute(context, TEARDOWN_FENCE_MS);
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
                    RouteContextState.removeRouteHandle(context, pattern);
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
        //  B3：闭包捕获规则链引用（而非单个 rule）——同 pattern 后续追加自动可见，分发期合并
        //  捕获 route(...) 返回的 AutoCloseable 句柄（确定性生命周期管理），按 (context, pattern) 记账，
        //  供 case 结束时经有界守护线程 close() 确定性注销（替代「清链但闭包仍挂 context」的技巧）。
        AutoCloseable routeHandle = registerNativeRouteBounded(context, pattern, chain, routeRegisterTimeoutMs());
        RouteContextState.registerRouteHandle(context, pattern, routeHandle);
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
     * 注销指定 pattern 集合的 Playwright 原生路由（异常隔离 + 防挂死）。
     *
     * <p><b>企业级 teardown 契约</b>：本方法处于 {@code @AfterScenario}/{@code RouteDsl.clear} 收尾路径，
     * 必须保证<b>绝不</b>因浏览器不响应而永久阻塞测试主线程。三重保障：
     * <ol>
     *   <li><b>关闭守卫</b>：context/page 已关闭（或查询其关闭状态抛错）时直接跳过，
     *       不对已销毁对象调用 {@code unroute} 触发长阻塞 / 异常。</li>
     *   <li><b>在途收尾（根因缓解）</b>：先等待该 context 的拦截在途请求
     *       （{@code interceptRealResponse} 的异步真实 fetch）排空，确保浏览器侧 handler 已 settle，
     *       {@code unroute} 才能被及时 ACK——根治「浏览器忙于在途请求而不响应 unroute」。</li>
     *   <li><b>异步非阻塞 + 有界看门狗</b>：{@code unroute} 经 Playwright 连接发命令给浏览器并等 ACK，
     *       浏览器不响应时会长时间挂起。旧实现虽已放进守护线程，但主线程仍要 {@code join} 等待
     *       （最多 {@code 在途排空 10s + join 20s = 30s}），case 收尾被拖住。现改为<b>完全异步</b>：
     *       主线程只做完纯内存记账便<b>立即返回</b>，在途排空与原生 unroute 全部在守护线程执行，
     *       并由 {@code AsyncPool} 看门狗在 {@code UNROUTE_TIMEOUT_MS} 后中断失控 worker（<b>不阻塞主线程</b>）。
     *       上下文最终关闭时 Playwright 会自动移除残留路由，无资源泄漏；且 in-JVM 规则链已在
     *       {@link #clearContext(Object)} 第 1 步就地清空（empty-chain 自动 fallback），不会跨场景污染。</li>
     * </ol>
     *
     * @param context  Page 或 BrowserContext 实例
     * @param patterns 要注销的 URL pattern 集合
     */
    public static void unrouteAllForContext(Object context, Set<String> patterns) {
        if (context == null || patterns == null || patterns.isEmpty()) {
            return;
        }
        VerboseLogging.logDebugIfVerbose(RouteEngine.LOGGER,
                "[RouteEngine] unrouteAllForContext: bulk-unrouting all routes ({}) from {} (async, non-blocking)",
                patterns.size(), context.getClass().getSimpleName());

        // 1) 关闭守卫：已关闭的 context 不再发起 unroute（避免对已销毁对象长阻塞），仅清句柄表释放引用
        if (isContextClosed(context)) {
            RouteContextState.clearRouteHandles(toBrowserContext(context));
            RouteEngine.LOGGER.debug("[RouteEngine] unrouteAllForContext skipped: context already closed ({})",
                    context.getClass().getSimpleName());
            return;
        }

        BrowserContext bc = toBrowserContext(context);

        // 2) 主线程只做纯内存记账：取走句柄并立即清表，释放 Java 侧引用（零浏览器往返 ⇒ 零阻塞）
        Map<String, AutoCloseable> handles = RouteContextState.getRouteHandles(bc);
        RouteContextState.clearRouteHandles(bc);

        // 3) 异步执行（fire-and-forget，主线程<b>不 join</b>）：
        //    「在途排空 + 原生 unroute」整体下沉到守护线程 —— case 结束主线程立即返回，
        //    无论业务把 monitor / delay / mock / modify 的 timeout 配成多久，都不会被收尾拖住。
        //  跨用例交接登记必须在 worker 启动【之前】完成，否则下一个用例可能先看到"无在途收尾"而直接复用 Context，
        //  与本 worker 并发操作同一 Connection（见 field PENDING_UNROUTE / awaitInFlightUnroute）。
        RouteContextState.UnrouteTask unrouteTask = RouteContextState.beginUnroute(bc);
        Thread worker = new Thread(new BoundedUnrouteTask(context, handles, bc, unrouteTask),
                "route-unroute-" + System.identityHashCode(context));
        worker.setDaemon(true);
        worker.start();

        // 4) 看门狗（同样异步，不阻塞主线程）：浏览器不响应时中断 worker，避免守护线程无限悬挂累积。
        //    保留「有界」语义，但不再以阻塞主线程为代价。
        try {
            AsyncPool.schedule(() -> {
                if (worker.isAlive()) {
                    //  抢占（置标志 + 中断）：worker 在 drain / 每次 close 前检查标志，被中断即退出，
                    //  与跨用例栅栏共用同一交接机制（确定性停止，而非仅靠中断点命中）。
                    unrouteTask.requestPreempt();
                    RouteEngine.LOGGER.warn("[RouteEngine] unrouteAllForContext worker still running after {}ms for {} "
                                    + "(patterns={}); preempted it. Any Playwright-native routes left will be "
                                    + "removed on context close.",
                            UNROUTE_TIMEOUT_MS, context.getClass().getSimpleName(), patterns.size());
                }
            }, UNROUTE_TIMEOUT_MS);
        } catch (Throwable t) {
            //  看门狗调度失败（如 AsyncPool 已在套件收尾关闭）不影响已启动的 worker，
            //   且原生路由最终由 context 关闭兜底 —— 仅降级为「无看门狗」，绝不影响 case 收尾。
            RouteEngine.LOGGER.debug("[RouteEngine] unroute watchdog scheduling failed: {}", t.getMessage());
        }
    }

    /** unroute 等待浏览器 ACK 的上限（异常兜底）；可由 -Droute.unroute.timeout.ms 覆盖，默认 20s。 */
    private static final long UNROUTE_TIMEOUT_MS =
            RouteUtil.getEnvLong("ROUTE_UNROUTE_TIMEOUT_MS", 20_000L);

    /** 在途拦截请求排空等待上限（根因缓解，正常应远小于此值）；可由 -Droute.unroute.await.ms 覆盖，默认 10s。 */
    private static final long UNROUTE_AWAIT_COMPLETION_MS =
            RouteUtil.getEnvLong("ROUTE_UNROUTE_AWAIT_MS", 10_000L);

    /**
     * 跨用例收尾栅栏的等待上限 —— 取自 {@link RouteLifecycleRegistry#teardownFenceMs()}（<b>单一定义点</b>，
     * 与 web 侧 scenario 初始化共用同一配置项：系统属性 {@code -Droute.teardown.fence.ms} 优先，
     * 其次环境变量 {@code ROUTE_TEARDOWN_FENCE_MS}，默认 2s）。
     */
    private static final long TEARDOWN_FENCE_MS = RouteLifecycleRegistry.teardownFenceMs();

    /**
     * 跨用例交接栅栏：<b>先抢占、再短等</b>在途 unroute 收尾真正结束（下一个用例<b>复用同一 Context 之前</b>调用）。
     *
     * <p><b>为什么必须有</b>：teardown worker 是 fire-and-forget 的守护线程，feature 模式下它常在下一个用例
     * 开始后才收工。此窗口内 worker 会在该 Context 上派发事件 / 发 unroute 往返，而用例线程同时在导航 ——
     * 两个线程并发操作同一个 Playwright {@code Connection}，实测触发
     * {@code Object doesn't exist: response@/worker@...}（对方线程处理到已被驱动回收的句柄），
     * 连锁导致「会话校验导航失败 → 缓存被删除 → 每轮完整登录」。
     *
     * <p><b>为何是"抢占 + 短等"而不是"纯有界等待"（2026-09-26 二次设计）</b>：worker 的 drain 预算为 10s
     * （{@link #UNROUTE_AWAIT_COMPLETION_MS}），而栅栏预算仅 2s ⇒ 纯等待<b>必然超时</b>：白付 2s×N 延迟，
     * 却仍与 worker 并发（内网实测日志：连续两条 {@code Teardown fence timed out after 2002ms/2010ms}）。
     * 改为抢占后，worker 在 drain 前 / 每次 {@code close()} 前检查标志并在被中断时立即退出 ⇒
     * 交接是<b>毫秒级确定性</b>的，且被抢占忽略的残留原生路由由 context 关闭兜底移除
     * （in-JVM 规则链已清空 ⇒ 空链自动 fallback，不会跨场景 mock 泄漏）。
     *
     * <p><b>调用点</b>：{@link #register(BrowserContext, List)}（首次注册路由）与框架侧 scenario 初始化
     * （{@code RouteLifecycle.awaitTeardownFor}）。无在途收尾时<b>零开销</b>（一次 Map 查询、不加锁、不阻塞）。
     *
     * @param context   Page / BrowserContext；无法归约为 BrowserContext 时视为无在途收尾
     * @param timeoutMs 抢占后等待 worker 退出的上限（毫秒）
     * @return true = 无在途收尾或 worker 已在超时内停止；false = worker 未在预算内停止（已告警，调用方继续）
     */
    public static boolean awaitInFlightUnroute(Object context, long timeoutMs) {
        BrowserContext bc = toBrowserContext(context);
        RouteContextState.UnrouteTask task = RouteContextState.unrouteTaskFor(bc);
        if (task == null) {
            return true; // 零开销快速路径：无在途收尾
        }
        task.requestPreempt();
        long start = System.currentTimeMillis();
        boolean stopped = task.await(timeoutMs);
        long waited = System.currentTimeMillis() - start;
        if (stopped) {
            RouteEngine.LOGGER.debug("[RouteEngine] Teardown fence: preempted in-flight unroute on {} in {}ms "
                    + "before reuse (deterministic handoff; no concurrent Connection access)",
                    bc.getClass().getSimpleName(), waited);
        } else {
            RouteEngine.LOGGER.warn("[RouteEngine] Teardown fence: in-flight unroute on {} did not stop within {}ms "
                            + "after preempt — proceeding; residual native routes will be removed on context close",
                    bc.getClass().getSimpleName(), waited);
        }
        return stopped;
    }

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

    /** context/page 是否已关闭（查询抛错按“已关闭”保守处理，避免对已销毁对象阻塞）。 */
    private static boolean isContextClosed(Object context) {
        try {
            if (context instanceof Page) {
                return ((Page) context).isClosed();
            }
            if (context instanceof BrowserContext) {
                return ((BrowserContext) context).isClosed();
            }
        } catch (Exception e) {
            RouteEngine.LOGGER.debug("[RouteEngine] isClosed check failed for {}: {}",
                    context.getClass().getSimpleName(), e.getMessage());
            return true;
        }
        return false;
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

    /**
     * 在<b>守护线程</b>中逐个 {@code close()} 本用例注册的原生路由句柄，使调用方（@AfterScenario）
     * 永不因浏览器不响应而挂死；并支持被<b>下一个用例抢占</b>（确定性交接）。
     *
     * <p><b>为何用「逐 pattern close 自己登记的句柄」而非 {@code context.unrouteAll()}（2026-09-26 跨场景竞态修复）</b>：
     * 本任务可能在<b>下一个用例开始之后</b>才真正执行 —— feature 模式下 Context 跨 scenario 复用，
     * 实测重叠达 5.5s（用例1 收尾 12:34:58.531 起 worker，用例2 12:34:59.24 已注册 3 条规则，
     * worker 直到 12:35:03.996 才收工）。此时若执行 {@code context.unrouteAll()}，会<b>摘掉下一个用例
     * 刚注册的全部原生路由</b>，表现为 {@code Object doesn't exist: response@/request@}、
     * {@code Execution context was destroyed} 以及后续用例卡死（原实现正是如此）。
     *
     * <p>故本任务只 close「自己 spawn 时登记的句柄」，并对<b>已被新用例重新注册的 pattern</b>跳过 ——
     * 否则 close 仍会按 pattern 摘掉新路由。句柄表在 spawn 前已由主线程同步清空，
     * 因此"此刻表中又有同 pattern 条目"即等价于"该 pattern 已被更新的用例接管"，判据零额外状态。
     *
     * <p><b>抢占检查点（2026-09-26 二次设计）</b>：drain 前、每次 {@code close()} 前都检查
     * {@link RouteContextState.UnrouteTask#isPreemptRequested()}；被中断（{@code InterruptedException}）
     * 即刻退出。故下一个用例只需"抢占 + 短等"即可确定性独占该 Context 的 Connection，
     * 而不必赌"栅栏预算 &gt; worker 耗时"（实测 worker drain 预算 10s 远超栅栏 2s，赌必输）。
     *
     * <p>异常隔离 + 被中断安全退出（无线程泄漏）；未被 close 的残留原生路由由 context 关闭兜底移除，
     * 且 in-JVM 规则链已清空（empty-chain 自动 fallback）⇒ 不会造成跨场景 mock 泄漏。
     */
    private static final class BoundedUnrouteTask implements Runnable {
        private final Object context;
        private final Map<String, AutoCloseable> handles;
        /** 可能为 null（非 BrowserContext 场景）：用于 unroute 前先在<b>后台</b>排在途请求。 */
        private final BrowserContext browserContext;
        /** 跨用例交接句柄：无论正常/异常/跳过/被抢占，都必须在 {@code finally} 中结束，否则下个用例会空等满预算。 */
        private final RouteContextState.UnrouteTask task;

        BoundedUnrouteTask(Object context, Map<String, AutoCloseable> handles, BrowserContext browserContext,
                           RouteContextState.UnrouteTask task) {
            this.context = context;
            this.handles = (handles == null) ? java.util.Collections.emptyMap() : handles;
            this.browserContext = browserContext;
            this.task = task;
        }

        @Override
        public void run() {
            //  抢占登记必须发生在任何浏览器往返之前：若抢占已先于本 worker 启动下达，attachWorker 会补一次中断。
            if (task != null) {
                task.attachWorker(Thread.currentThread());
            }
            try {
                //  关闭守卫：context 已销毁时不再发起任何浏览器往返（否则只会得到
                //    「Object doesn't exist」噪音与无谓阻塞）；残留原生路由随 context 销毁自动消失。
                if (browserContext != null && RouteContextState.isContextClosed(browserContext)) {
                    RouteEngine.LOGGER.debug("[RouteEngine] unroute task skipped: context already closed ({})",
                            context.getClass().getSimpleName());
                    return;
                }
                if (isPreempted()) {
                    RouteEngine.LOGGER.debug("[RouteEngine] unroute task skipped: preempted before drain ({})",
                            context.getClass().getSimpleName());
                    return;
                }

                //  在途收尾（原在主线程同步等待，现随本任务一并下沉到守护线程）：
                //    等拦截在途请求排空，浏览器才有空 ACK unroute；这里的阻塞只影响守护线程，不影响 case 收尾。
                if (browserContext != null) {
                    RouteEngine.awaitInterceptCompletion(browserContext, UNROUTE_AWAIT_COMPLETION_MS);
                }

                int closedCount = 0;
                int skippedCount = 0;
                boolean preempted = false;
                for (Map.Entry<String, AutoCloseable> entry : handles.entrySet()) {
                    //  抢占检查点：下一个用例已要求独占该 Connection ⇒ 立即停止触碰它（含 drain 后的每次 close）。
                    if (isPreempted() || Thread.currentThread().isInterrupted()) {
                        preempted = true;
                        break;
                    }
                    //  逐个句柄 close 之前**实时**复查：spawn 前主线程已 clearRouteHandles，
                    //  故该 pattern 此刻若又有条目，即说明它已被更新的用例重新注册（接管）——
                    //  对其 close 会按 pattern 摘掉新用例的原生路由，必须跳过。
                    //  逐句柄复查把竞态窗口收敛到「单次 close 的微秒级」，而非「整批句柄的总时长」。
                    if (RouteContextState.hasRouteHandle(browserContext, entry.getKey())) {
                        skippedCount++;
                        continue;
                    }
                    try {
                        entry.getValue().close();
                        closedCount++;
                    } catch (InterruptedException ie) {
                        //  被抢占中断：保持中断状态并立即停止（后续句柄交给 context 关闭兜底）。
                        Thread.currentThread().interrupt();
                        preempted = true;
                        break;
                    } catch (Exception e) {
                        RouteEngine.LOGGER.warn("[RouteEngine] route handle close() failed: {}", e.getMessage());
                    }
                }
                if (preempted) {
                    RouteEngine.LOGGER.info("[RouteEngine] unroute task preempted by next scenario on {} "
                                    + "(closed={}, skippedOwnedByNewerScenario={}, remaining={}); residual native "
                                    + "routes will be removed on context close",
                            context.getClass().getSimpleName(), closedCount, skippedCount,
                            handles.size() - closedCount - skippedCount);
                } else {
                    RouteEngine.LOGGER.debug("[RouteEngine] unroute task completed for context: {} "
                                    + "(closed={}, skippedOwnedByNewerScenario={})",
                            context.getClass().getSimpleName(), closedCount, skippedCount);
                }
            } catch (Exception e) {
                RouteEngine.LOGGER.warn("[RouteEngine] unroute task failed for context '{}': {}",
                        context.getClass().getSimpleName(), e.getMessage());
            } finally {
                //  跨用例交接：必须无条件结束，唤醒在「复用该 Context 之前」等待的下一个用例。
                RouteContextState.endUnroute(browserContext, task);
            }
        }

        /** 是否已被下一个用例要求停止（无交接句柄时视为未抢占）。 */
        private boolean isPreempted() {
            return task != null && task.isPreemptRequested();
        }
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

        // 5. 原生路由注销：异步（不 join）—— 主线程立即返回，绝不因浏览器不响应而挂住 case 收尾。
        //    未摘掉的原生路由会在 context 关闭时由 Playwright 自动移除；且第 1 步已就地清空规则链，
        //    不存在 mock/delay/modify 跨用例污染风险。
        if (patterns != null && !patterns.isEmpty()) {
            try {
                unrouteAllForContext(context, patterns.keySet());
            } catch (Throwable t) {
                //  原生 unroute 属 best-effort：即便起不来，前 4 步已把 in-JVM 状态清干净，
                //  残留原生路由由 context 关闭兜底移除 —— 绝不让其影响 case 收尾。
                RouteEngine.LOGGER.warn("[RouteEngine] clearContext: async native unroute failed for {}: {}",
                        context.getClass().getSimpleName(), t.getMessage());
            }
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
        //  全局 teardown：一并清掉原生路由句柄记账（各 context 的原生路由已由 teardown worker
        //  按 pattern 精确 close；未被 close 的残留由其 context 关闭兜底移除）。
        RouteContextState.ROUTE_HANDLES.clear();
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