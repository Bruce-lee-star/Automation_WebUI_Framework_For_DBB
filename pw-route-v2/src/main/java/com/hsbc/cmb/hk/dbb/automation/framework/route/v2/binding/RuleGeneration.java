package com.hsbc.cmb.hk.dbb.automation.framework.route.v2.binding;

import com.hsbc.cmb.hk.dbb.automation.framework.route.v2.dsl.ApiSpec;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 规则代（不可变快照）。
 *
 * <p>一次 {@code merge} 产生新一代：{@code generation} 严格递增、规则表不可变。
 * 读侧（事件线程）在任何时刻拿到的快照都是完整一致的，无锁、无撕裂读。
 *
 * <p>同 pattern 语义：后注册覆盖先注册（{@link GenerationRegistry#merge} 时以新规则替换旧规则）。
 */
public final class RuleGeneration {

    private final int generation;
    private final Map<String, ApiSpec> rules;

    private RuleGeneration(int generation, Map<String, ApiSpec> rules) {
        this.generation = generation;
        this.rules = Collections.unmodifiableMap(rules);
    }

    /** 初始代（gen=0，空规则表）。 */
    public static RuleGeneration empty() {
        return new RuleGeneration(0, Collections.emptyMap());
    }

    /**
     * 基于上一代构建下一代。
     *
     * @param base   上一代
     * @param merged 合并后的规则表（已是不可变拷贝）
     */
    public static RuleGeneration next(RuleGeneration base, Map<String, ApiSpec> merged) {
        return new RuleGeneration(base.generation + 1, merged);
    }

    public int generation() {
        return generation;
    }

    /** 该代可见的全部规则（不可变视图）。 */
    public Map<String, ApiSpec> rules() {
        return rules;
    }

    /** 按 pattern 取规则（可能为 null——规则已退役或从未注册）。 */
    public ApiSpec specFor(String pattern) {
        return rules.get(pattern);
    }

    /** 将新规则并入本代，产出合并后的规则表（供 next 使用）。 */
    public Map<String, ApiSpec> mergeInto(String pattern, ApiSpec spec) {
        Map<String, ApiSpec> merged = new LinkedHashMap<>(rules);
        merged.put(pattern, spec);
        return merged;
    }
}
