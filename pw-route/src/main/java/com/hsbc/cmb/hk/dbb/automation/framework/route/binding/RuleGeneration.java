package com.hsbc.cmb.hk.dbb.automation.framework.route.binding;

import com.hsbc.cmb.hk.dbb.automation.framework.route.dsl.ApiSpec;
import com.hsbc.cmb.hk.dbb.automation.framework.route.dsl.RouteCapability;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 规则代（不可变快照）。
 *
 * <p>一次 {@code merge} 产生新一代：{@code generation} 严格递增、规则表不可变。
 * 读侧（事件线程）在任何时刻拿到的快照都是完整一致的，无锁、无撕裂读。
 *
 * <p><b>存储模型（参照 V1 单规则多能力位）</b>：同 pattern 可同时承载 MOCK / MODIFY_REQUEST /
 * DELAY / MONITOR 多个能力位，每个能力位独立存储一条 {@link ApiSpec}（key = (pattern, capability)）。
 * {@link #specFor(String)} 在构建代时按 {@link ApiSpec#merge} 合并为<b>单条视图</b>，供 dispatcher
 * 在单 handler 内按 {@code RouteCapability.executionOrder()} 统一编排（DELAY→MODIFY→MOCK→MONITOR）。
 *
 * <p>该模型使每条能力位拥有独立身份令牌：重新注册同能力位 = 覆盖其自身条目（能力恢复，不继承旧 disabled）；
 * 关闭某能力位句柄 = 仅摘除该 (pattern, capability) 条目（不影响同 pattern 其它能力位）。
 */
public final class RuleGeneration {

    private final int generation;
    /** pattern → (capability → 规则)。不可变。 */
    private final Map<String, Map<RouteCapability, ApiSpec>> rules;
    /** pattern → 合并视图（派发用）。不可变。 */
    private final Map<String, ApiSpec> index;

    private RuleGeneration(int generation,
                          Map<String, Map<RouteCapability, ApiSpec>> rules,
                          Map<String, ApiSpec> index) {
        this.generation = generation;
        this.rules = Collections.unmodifiableMap(rules);
        this.index = Collections.unmodifiableMap(index);
    }

    /** 初始代（gen=0，空规则表）。 */
    public static RuleGeneration empty() {
        return new RuleGeneration(0, Collections.emptyMap(), Collections.emptyMap());
    }

    /**
     * 基于上一代构建下一代。
     *
     * @param base   上一代
     * @param merged 合并后的规则表（已是不可变拷贝），结构为 pattern → (capability → 规则)
     */
    public static RuleGeneration next(RuleGeneration base, Map<String, Map<RouteCapability, ApiSpec>> merged) {
        Map<String, ApiSpec> idx = new LinkedHashMap<>(merged.size());
        for (Map.Entry<String, Map<RouteCapability, ApiSpec>> e : merged.entrySet()) {
            ApiSpec mergedSpec = mergeCaps(e.getValue().values());
            if (mergedSpec != null) {
                idx.put(e.getKey(), mergedSpec);
            }
        }
        return new RuleGeneration(base.generation + 1, merged, idx);
    }

    /** 将同一 pattern 的多个能力位合并为单条视图（供 dispatcher 编排）。 */
    private static ApiSpec mergeCaps(Collection<ApiSpec> caps) {
        ApiSpec result = null;
        for (ApiSpec s : caps) {
            result = (result == null) ? s : result.merge(s);
        }
        return result;
    }

    public int generation() {
        return generation;
    }

    /** 该代可见的全部规则（pattern → (capability → 规则)，不可变视图）。 */
    public Map<String, Map<RouteCapability, ApiSpec>> rules() {
        return rules;
    }

    /** 按 pattern 取合并后的单条视图（可能为 null——规则已退役或从未注册）。 */
    public ApiSpec specFor(String pattern) {
        return index.get(pattern);
    }

    /** 将新规则并入本代，产出合并后的规则表（供 next 使用）。同能力位覆盖（重新注册=恢复）。 */
    public Map<String, Map<RouteCapability, ApiSpec>> mergeInto(String pattern, ApiSpec spec) {
        Map<String, Map<RouteCapability, ApiSpec>> merged = new LinkedHashMap<>(rules);
        Map<RouteCapability, ApiSpec> byCap =
                new LinkedHashMap<>(merged.getOrDefault(pattern, Collections.emptyMap()));
        byCap.put(spec.capability(), spec); // 同能力位：覆盖（重新注册恢复，不继承旧 disabled）
        merged.put(pattern, byCap);
        return merged;
    }

    /** 剥离某能力位（句柄关闭 / 退役）：从 (pattern, capability) 移除；若该 pattern 已无任何能力位则整体移除。 */
    public Map<String, Map<RouteCapability, ApiSpec>> removeCapability(String pattern, RouteCapability cap) {
        Map<String, Map<RouteCapability, ApiSpec>> merged = new LinkedHashMap<>(rules);
        Map<RouteCapability, ApiSpec> byCap =
                new LinkedHashMap<>(merged.getOrDefault(pattern, Collections.emptyMap()));
        byCap.remove(cap);
        if (byCap.isEmpty()) {
            merged.remove(pattern);
        } else {
            merged.put(pattern, byCap);
        }
        return merged;
    }

    /** 停用某能力位（stop / 退役）：给该能力位自身 spec 加 disabled，规则表不移除该 pattern。 */
    public Map<String, Map<RouteCapability, ApiSpec>> stopCapability(String pattern, RouteCapability cap) {
        Map<String, Map<RouteCapability, ApiSpec>> merged = new LinkedHashMap<>(rules);
        Map<RouteCapability, ApiSpec> byCap =
                new LinkedHashMap<>(merged.getOrDefault(pattern, Collections.emptyMap()));
        ApiSpec existing = byCap.get(cap);
        if (existing != null && !existing.isStopped(cap)) {
            byCap.put(cap, existing.withStopped(cap));
        }
        merged.put(pattern, byCap);
        return merged;
    }

    /** 整体移除 pattern（clearRules / 退役整条规则）。 */
    public Map<String, Map<RouteCapability, ApiSpec>> removePattern(String pattern) {
        Map<String, Map<RouteCapability, ApiSpec>> merged = new LinkedHashMap<>(rules);
        merged.remove(pattern);
        return merged;
    }
}
