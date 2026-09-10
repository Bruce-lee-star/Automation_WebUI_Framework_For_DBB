package com.hsbc.cmb.hk.dbb.automation.framework.route.core.rule;

/**
 * 优先级裁决（T2-4 拆分，自 {@code RouteEngine} 提取；对应 ARP 验收项「优先级裁决仅一处实现」）。
 *
 * <p>承载能力位 → 优先级裁决的<b>唯一</b>实现：按 {@link RouteHandleType#getPriority()} 顺序
 * 选出首个命中的能力位，等价语义 <b>MOCK 终结短路 → MODIFY → DELAY → MONITOR</b>。
 * 各能力位可被显式停止（stopMonitor/stopModify/stopDelay/stopMock）独立跳过，不影响同 pattern 其它能力。
 *
 * <p>此前该裁决散落在 {@code RouteEngine.selectCapability}（唯一入口但归属不清）；
 * 现收敛为单一策略对象，满足「优先级裁决仅一处实现」的架构验收，并与
 * {@code Dispatcher}（分发）/ {@code HandlerExecutor}（执行）职责解耦。
 *
 * @apiNote framework-internal：框架内部类型，非公开 API。跨子包 public 可见性仅为分层迁移需要，外部不得依赖。
 */
public final class PriorityPolicy {

    /**
     * 能力位选择（取代 InterceptorChain 责任链抽象）：按优先级顺序选出首个命中的能力位。
     *
     * @param rule 已跨层/同层合并后的 finalRule
     * @return 命中的能力类型；无任何能力位命中时返回 null（由调用方 resume 放行）
     */
    public static RouteHandleType selectCapability(RouteRule rule) {
        //  任一能力被显式停止（stopMonitor/stopModify/stopDelay/stopMock）时跳过，不影响同 pattern 其它能力
        if (rule.getType() == RouteHandleType.MOCK
                && !rule.isCapabilityStopped(RouteHandleType.MOCK)) {
            return RouteHandleType.MOCK;
        }
        if (hasModifyCapability(rule)
                && !rule.isCapabilityStopped(RouteHandleType.MODIFY)) {
            return RouteHandleType.MODIFY;
        }
        if ((rule.getType() == RouteHandleType.DELAY || rule.getDelayMs() > 0)
                && !rule.isCapabilityStopped(RouteHandleType.DELAY)) {
            return RouteHandleType.DELAY;
        }
        if (rule.isMonitorEnabled()
                && !rule.isCapabilityStopped(RouteHandleType.MONITOR)) {
            return RouteHandleType.MONITOR;
        }
        return null;
    }

    /** MODIFY 能力位判定：存在任意请求头/体改写项或改方法。 */
    private static boolean hasModifyCapability(RouteRule rule) {
        return (rule.getRequestHeadersToSet() != null && !rule.getRequestHeadersToSet().isEmpty())
                || (rule.getRequestHeadersToRemove() != null && !rule.getRequestHeadersToRemove().isEmpty())
                || (rule.getRequestBodyFieldsToModify() != null && !rule.getRequestBodyFieldsToModify().isEmpty())
                || (rule.getRequestBodyFieldsToAdd() != null && !rule.getRequestBodyFieldsToAdd().isEmpty())
                || (rule.getRequestBodyFieldsToRemove() != null && !rule.getRequestBodyFieldsToRemove().isEmpty())
                || rule.getModifyMethod() != null;
    }
}
