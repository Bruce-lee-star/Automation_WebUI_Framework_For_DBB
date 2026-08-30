package com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.hsbc.cmb.hk.dbb.automation.framework.web.utils.LoggingConfigUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Context 生命周期钩子管理器
 * 
 * <p>解决 Context 重建时导致 Mock/Intercept 规则丢失的问题。
 * 
 * <p>核心机制：
 * <ul>
 *   <li>当 Context 即将重建时，捕获所有已注册的规则</li>
 *   <li>Context 重建完成后，自动将规则重新绑定到新 Context</li>
 * </ul>
 * 
 * <p>支持的组件：
 * <ul>
 *   <li>ApiMonitorAndMockManager - Mock 和 Intercept 规则</li>
 *   <li>RealApiMonitor - 响应监听器</li>
 *   <li>ApiRequestModifier - 请求修改规则</li>
 * </ul>
 * 
 * <p>使用方式：
 * <pre>{@code
 * // 框架自动处理，无需手动调用
 * // 当 CustomOptionsManager.setXXX() 设置自定义选项时，自动触发 scheduleContextRebuild() 并执行规则保存和恢复
 * }</pre>
 *
 * <p>⚠️ 接入前提：本机制为"捕获-恢复"框架，需各 Route 组件（ApiMonitorAndMockManager 等）
 * 实现 {@link RuleSnapshot} 并通过 {@link RuleCapturer} 注册到本管理器后才会实际捕获/恢复规则。
 * 若未接入任何 {@code RuleCapturer}，{@link #captureRules} 产生的快照内规则列表为空，
 * {@link #rebindRules} 将直接返回 0（不会误操作旧 Route 对象）。新接入实现类时请严格遵守
 * {@link RuleSnapshot#rebindTo(BrowserContext)} 的契约：必须在新 Context 上重新注册规则。
 */
public class ContextLifecycleHookManager {

    private static final Logger logger = LoggerFactory.getLogger(ContextLifecycleHookManager.class);

    // ==================== 注册的钩子接口 ====================

    /**
     * 规则快照接口 - 需要被 Context 重建机制管理的组件实现此接口
     */
    public interface RuleSnapshot {
        /**
         * 获取快照的唯一标识
         */
        String getId();

        /**
         * 获取关联的 URL 模式
         */
        String getUrlPattern();

        /**
         * 重新绑定到新的 Context。
         * <p>
         * ⭐ 契约（修复问题1）：Playwright 的 Route 对象与旧 Context 生命周期绑定，旧 Context 关闭后全部失效，
         * <b>不可跨 Context 复用</b>。实现类必须在 {@code newContext} 上重新调用 {@code newContext.route(url, handler)}
         * 注册相同的拦截规则，而不是持有/操作旧 Route 对象（否则 Firefox/WebKit 下会抛
         * {@code Object doesn't exist: route}）。
         * <p>
         * 若规则的恢复改由 DSL 在测试代码中重新声明，则无需实现本接口的 rebind 逻辑。
         *
         * @param newContext 新的 BrowserContext
         * @return true 如果重新注册成功
         */
        boolean rebindTo(BrowserContext newContext);

        /**
         * 重新绑定到新的 Page。
         * <p>契约同 {@link #rebindTo(BrowserContext)}：实现类须在新 Page 上重新注册，禁止复用旧 Runtime 对象。
         *
         * @param newPage 新的 Page
         * @return true 如果重新注册成功
         */
        boolean rebindTo(Page newPage);
    }

    /**
     * 规则捕获器接口 - 组件实现此接口以提供规则快照
     */
    public interface RuleCapturer {
        /**
         * 捕获当前所有规则
         * @param context 关联的 Context
         * @return 规则快照列表
         */
        List<RuleSnapshot> captureRules(BrowserContext context);
    }

    // ==================== 单个 Context 的规则存储 ====================

    /**
     * 单个 Context 的规则快照集合
     */
    public static class ContextRuleSnapshot {
        private final BrowserContext originalContext;
        private final List<RuleSnapshot> mockRules = new ArrayList<>();
        private final List<RuleSnapshot> interceptRules = new ArrayList<>();
        private final List<RuleSnapshot> monitorRules = new ArrayList<>();
        private final List<RuleSnapshot> modifierRules = new ArrayList<>();
        private final long timestamp;

        public ContextRuleSnapshot(BrowserContext context) {
            this.originalContext = context;
            this.timestamp = System.currentTimeMillis();
        }

        public void addMockRule(RuleSnapshot rule) {
            mockRules.add(rule);
        }

        public void addInterceptRule(RuleSnapshot rule) {
            interceptRules.add(rule);
        }

        public void addMonitorRule(RuleSnapshot rule) {
            monitorRules.add(rule);
        }

        public void addModifierRule(RuleSnapshot rule) {
            modifierRules.add(rule);
        }

        public BrowserContext getOriginalContext() {
            return originalContext;
        }

        public List<RuleSnapshot> getAllRules() {
            List<RuleSnapshot> all = new ArrayList<>();
            all.addAll(mockRules);
            all.addAll(interceptRules);
            all.addAll(monitorRules);
            all.addAll(modifierRules);
            return all;
        }

        public int getTotalRuleCount() {
            return mockRules.size() + interceptRules.size() + monitorRules.size() + modifierRules.size();
        }

        public long getTimestamp() {
            return timestamp;
        }
    }

    // ==================== 内部存储 ====================

    /**
     * ⭐ 修复 P0-1：按线程存储规则快照（替代原先的 identityHashCode(Context) 方案）。
     * 原方案用 Context 对象的内存哈希做 key，导致：(1) 并行/重建时快照串扰（Test B 误复用 Test A 规则）；
     * (2) 旧 Context 的哈希 key 永不移除，内存泄漏。改用线程 ID 作为 key，因为 Context 重建发生在
     * 同一 Serenity scenario 线程内，线程天然隔离，且 scenario 结束时可通过 clearSnapshotForCurrentThread() 精确清理。
     */
    // 线程 ID -> 规则快照
    private static final Map<Long, ContextRuleSnapshot> contextSnapshots = new ConcurrentHashMap<>();

    // 当前正在处理的线程（防止重复触发）
    private static final Set<Long> rebuildingThreads = ConcurrentHashMap.newKeySet();

    // ==================== 公开 API ====================

    /**
     * 捕获指定 Context 的所有规则
     * 
     * <p>在 Context 重建前调用此方法保存所有规则状态
     * 
     * @param context 即将重建的 Context
     * @return 规则快照，可以用于后续恢复
     */
    private static ContextRuleSnapshot captureRules(BrowserContext context) {
        if (context == null) {
            logger.debug("[ContextLifecycle] captureRules called with null context, skipping");
            return null;
        }

        long threadKey = Thread.currentThread().threadId();

        // ⭐ 修复 P0-1：按线程存快照，同一线程重建只更新一次（防止重复捕获）
        ContextRuleSnapshot existing = contextSnapshots.get(threadKey);
        if (existing != null) {
            logger.debug("[ContextLifecycle] Rules already captured for thread {}, reusing existing snapshot", threadKey);
            return existing;
        }

        logger.debug("[ContextLifecycle] Capturing rules for thread {} before rebuild", threadKey);

        ContextRuleSnapshot snapshot = new ContextRuleSnapshot(context);

        // 存储快照（按线程隔离，避免跨线程/跨 Context 串扰）
        contextSnapshots.put(threadKey, snapshot);

        logger.info("[ContextLifecycle] Captured snapshot for thread {}", threadKey);

        return snapshot;
    }

    /**
     * 重绑定规则到新的 Context
     * 
     * <p>在 Context 重建完成后调用此方法恢复所有规则
     * 
     * @param newContext 新的 BrowserContext
     * @return 成功重绑定的规则数量
     */
    private static int rebindRules(BrowserContext newContext) {
        if (newContext == null) {
            logger.warn("[ContextLifecycle] rebindRules called with null context");
            return 0;
        }

        long threadKey = Thread.currentThread().threadId();

        // ⭐ 修复 P0-1：按线程精确取快照，不再遍历复用其他线程/旧 Context 的快照，
        // 彻底消除并行测试下规则串扰（Test B 误复用 Test A 规则）。
        ContextRuleSnapshot snapshot = contextSnapshots.get(threadKey);

        if (snapshot == null) {
            logger.debug("[ContextLifecycle] No snapshot found to rebind for thread {}", threadKey);
            return 0;
        }

        String newContextId = getContextId(newContext);
        String oldContextId = getContextId(snapshot.getOriginalContext());

        logger.info("[ContextLifecycle] Rebinding {} rules from thread {} (context {}) to new context {}",
            snapshot.getTotalRuleCount(), threadKey, oldContextId, newContextId);

        int successCount = 0;
        int failCount = 0;

        // 重绑定所有规则（各 RuleSnapshot.rebindTo 在新 Context 上重新注册规则，而非复用旧 Route 对象）
        for (RuleSnapshot rule : snapshot.getAllRules()) {
            try {
                if (rule.rebindTo(newContext)) {
                    successCount++;
                } else {
                    failCount++;
                }
            } catch (Exception e) {
                logger.warn("[ContextLifecycle] Failed to rebind rule {}: {}", rule.getId(), e.getMessage());
                failCount++;
            }
        }

        logger.info("[ContextLifecycle] Rebind complete: {} success, {} failed", successCount, failCount);

        return successCount;
    }

    /**
     * 重绑定规则到新的 Page
     * 
     * @param newPage 新的 Page
     * @return 成功重绑定的规则数量
     */
    public static int rebindRulesToPage(Page newPage) {
        if (newPage == null) {
            logger.warn("[ContextLifecycle] rebindRulesToPage called with null page");
            return 0;
        }

        // ⭐ 修复 P0-1：按线程取快照（与 rebindRules 一致）
        ContextRuleSnapshot snapshot = contextSnapshots.get(Thread.currentThread().threadId());

        if (snapshot == null) {
            logger.debug("[ContextLifecycle] No snapshot found for page's thread");
            return 0;
        }

        int successCount = 0;
        for (RuleSnapshot rule : snapshot.getAllRules()) {
            try {
                if (rule.rebindTo(newPage)) {
                    successCount++;
                }
            } catch (Exception e) {
                logger.warn("[ContextLifecycle] Failed to rebind rule {} to page: {}", rule.getId(), e.getMessage());
            }
        }

        return successCount;
    }

    private static void markRebuilding(BrowserContext context) {
        if (context != null) {
            rebuildingThreads.add(Thread.currentThread().threadId());
            logger.debug("[ContextLifecycle] Marked thread {} as rebuilding", Thread.currentThread().threadId());
        }
    }

    private static void markRebuildComplete(BrowserContext context) {
        if (context != null) {
            rebuildingThreads.remove(Thread.currentThread().threadId());
            logger.debug("[ContextLifecycle] Marked thread {} as rebuild complete", Thread.currentThread().threadId());
        }
    }

    /**
     * 获取 Context 的日志标识（仅用于日志，不再作为存储 key）
     */
    private static String getContextId(BrowserContext context) {
        return "context-" + System.identityHashCode(context);
    }

    /**
     * ⭐ 修复 P0-1 + 五章缺失：Scenario 结束时清除当前线程的规则快照，防止内存泄漏。
     * 由 PlaywrightManager.cleanupForScenario() 调用。
     */
    public static void clearSnapshotForCurrentThread() {
        long threadKey = Thread.currentThread().threadId();
        ContextRuleSnapshot removed = contextSnapshots.remove(threadKey);
        rebuildingThreads.remove(threadKey);
        if (removed != null) {
            LoggingConfigUtil.logTraceIfVerbose(logger, "[ContextLifecycle] Cleared snapshot for thread {}", threadKey);
        }
    }

    // ==================== 与 PlaywrightManager 集成 ====================
    // ⭐ 修复 P2-20：本节方法（onContextAboutToRebuild / onContextRebuilt / rebindRulesToPage）
    //    经全仓库检索确认【当前均无调用点】。其原 Javadoc 声称
    //    "由 PlaywrightManager#scheduleContextRebuild() / getContext() 调用"，与事实不符，
    //    属<b>误导性注释</b>——会让维护者误以为"Context 重建时路由规则会自动保留"。
    //    事实是：Context 重建时已注册的路由规则会被静默丢弃。
    //
    //    选择"如实标注"而非直接删除的原因：它们是 public API，外部业务层可能已调用；
    //    且"重建后保留规则"本身是合理需求，实现已具备，只差接线。
    //
    //    ▸ 若要真正启用：在 PlaywrightManager.scheduleContextRebuild() 内、关闭旧 Context
    //      之前调用 onContextAboutToRebuild(oldContext)；在 getContext() 创建新 Context
    //      之后调用 onContextRebuilt(newContext)。
    //      启用前须确认 RouteRegistry 允许对【新 Context】重新注册相同规则，否则
    //      rebindRules 会因快照中的旧 Route 句柄失效而直接返回 0（见 rebindRules 注释）。

    /**
     * 在 Context 重建前执行规则捕获。
     *
     * <p>⚠️ <b>当前无调用点</b>（详见本节顶部 P2-20 说明）：原 Javadoc 声称
     * "由 {@link PlaywrightManager#scheduleContextRebuild()} 调用"，但实际并未接入，
     * 因此 Context 重建时路由规则<b>不会</b>被保留。
     */
    public static void onContextAboutToRebuild(BrowserContext oldContext) {
        if (oldContext == null) return;

        long threadKey = Thread.currentThread().threadId();
        if (rebuildingThreads.contains(threadKey)) {
            logger.debug("[ContextLifecycle] Thread {} already marked as rebuilding, skipping", threadKey);
            return;
        }

        logger.info("[ContextLifecycle] Context about to rebuild (thread {})", threadKey);
        markRebuilding(oldContext);

        // 捕获现有规则
        captureRules(oldContext);
    }

    /**
     * 在 Context 重建后执行规则重绑定。
     *
     * <p>⚠️ <b>当前无调用点</b>（详见本节顶部 P2-20 说明）：原 Javadoc 声称
     * "由 PlaywrightManager.getContext() 调用"，但实际并未接入。
     */
    public static void onContextRebuilt(BrowserContext newContext) {
        if (newContext == null) return;
        
        String contextId = getContextId(newContext);
        logger.info("[ContextLifecycle] Context rebuilt: {}", contextId);
        
        // 重绑定规则
        rebindRules(newContext);
        
        // 标记重建完成
        markRebuildComplete(newContext);
    }
}
