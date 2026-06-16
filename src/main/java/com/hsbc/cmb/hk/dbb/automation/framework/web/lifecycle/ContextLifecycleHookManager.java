package com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
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
         * 重新绑定到新的 Context
         * @param newContext 新的 BrowserContext
         * @return true 如果绑定成功
         */
        boolean rebindTo(BrowserContext newContext);

        /**
         * 重新绑定到新的 Page
         * @param newPage 新的 Page
         * @return true 如果绑定成功
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

    // Context ID -> 规则快照
    private static final Map<String, ContextRuleSnapshot> contextSnapshots = new ConcurrentHashMap<>();
    
    // 当前正在处理的 Context 重建（防止重复触发）
    private static final Set<String> rebuildingContexts = ConcurrentHashMap.newKeySet();

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

        String contextId = getContextId(context);
        
        // 防止重复捕获
        if (contextSnapshots.containsKey(contextId)) {
            logger.debug("[ContextLifecycle] Rules already captured for context {}, reusing existing snapshot", contextId);
            return contextSnapshots.get(contextId);
        }

        logger.debug("[ContextLifecycle] Capturing rules for context {} before rebuild", contextId);

        ContextRuleSnapshot snapshot = new ContextRuleSnapshot(context);

        // 存储快照
        contextSnapshots.put(contextId, snapshot);
        
        logger.info("[ContextLifecycle] Captured snapshot for context {}", contextId);

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

        // 查找是否有旧的 Context ID
        String newContextId = getContextId(newContext);
        
        // 首先尝试用新 Context 本身的 ID 查找
        ContextRuleSnapshot snapshot = contextSnapshots.get(newContextId);
        
        // 如果没找到，尝试通过旧 Context 查找（如果有旧 Context ID 传入）
        if (snapshot == null) {
            logger.debug("[ContextLifecycle] No snapshot found for context {}, checking all snapshots", newContextId);
            
            // 遍历查找匹配的快照（基于 URL pattern 等特征）
            for (Map.Entry<String, ContextRuleSnapshot> entry : contextSnapshots.entrySet()) {
                ContextRuleSnapshot existing = entry.getValue();
                if (existing.getTotalRuleCount() > 0) {
                    // 找到有规则的快照，复用
                    snapshot = existing;
                    logger.debug("[ContextLifecycle] Reusing snapshot from context {} for new context {}", 
                        entry.getKey(), newContextId);
                    break;
                }
            }
        }

        if (snapshot == null) {
            logger.debug("[ContextLifecycle] No snapshot found to rebind for context {}", newContextId);
            return 0;
        }

        // 更新快照中的 Context 引用（指向新 Context）
        String oldContextId = getContextId(snapshot.getOriginalContext());
        
        logger.info("[ContextLifecycle] Rebinding {} rules from context {} to new context {}",
            snapshot.getTotalRuleCount(), oldContextId, newContextId);

        int successCount = 0;
        int failCount = 0;

        // 重绑定所有规则
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

        BrowserContext context = newPage.context();
        ContextRuleSnapshot snapshot = contextSnapshots.get(getContextId(context));

        if (snapshot == null) {
            logger.debug("[ContextLifecycle] No snapshot found for page's context");
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
            rebuildingContexts.add(getContextId(context));
            logger.debug("[ContextLifecycle] Marked context {} as rebuilding", getContextId(context));
        }
    }

    private static void markRebuildComplete(BrowserContext context) {
        if (context != null) {
            rebuildingContexts.remove(getContextId(context));
            logger.debug("[ContextLifecycle] Marked context {} as rebuild complete", getContextId(context));
        }
    }

    /**
     * 获取 Context 的唯一标识
     */
    private static String getContextId(BrowserContext context) {
        // 使用 hashCode 作为标识（Context 实例级别唯一）
        return "context-" + System.identityHashCode(context);
    }

    // ==================== 与 PlaywrightManager 集成 ====================

    /**
     * 在 Context 重建前执行捕获
     * 由 {@link PlaywrightManager#scheduleContextRebuild()} 调用
     */
    public static void onContextAboutToRebuild(BrowserContext oldContext) {
        if (oldContext == null) return;
        
        String contextId = getContextId(oldContext);
        if (rebuildingContexts.contains(contextId)) {
            logger.debug("[ContextLifecycle] Context {} already marked as rebuilding, skipping", contextId);
            return;
        }

        logger.info("[ContextLifecycle] Context about to rebuild: {}", contextId);
        markRebuilding(oldContext);
        
        // 捕获现有规则
        captureRules(oldContext);
    }

    /**
     * 在 Context 重建后执行重绑定
     * 由 PlaywrightManager.getContext() 调用
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
