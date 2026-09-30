package com.hsbc.cmb.hk.dbb.automation.framework.route.v2.binding;

import com.hsbc.cmb.hk.dbb.automation.framework.route.v2.dsl.ApiSpec;
import com.hsbc.cmb.hk.dbb.automation.framework.route.v2.dsl.RouteCapability;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 代际注册表 —— 规则的单一发布点。
 *
 * <p>并发模型：{@link AtomicReference} + CAS 循环。任何线程（业务线程 / 事件线程）
 * 调用 {@link #merge(ApiSpec)} 都会把「旧代 + 新规则」原子地发布为新代；读侧
 * {@link #snapshot()} 无锁拿当前代。不存在「读到一半的规则表」或「新旧代撕裂」。</p>
 *
 * <p>存储为 pattern → (capability → 规则)。每条能力位拥有独立身份令牌，使"重新注册恢复能力 /
 * 关闭句柄仅撤本能力位"等语义在单规则多能力位模型下依然成立。</p>
 */
public final class GenerationRegistry {

    private final AtomicReference<RuleGeneration> current;

    public GenerationRegistry() {
        this.current = new AtomicReference<>(RuleGeneration.empty());
    }

    /**
     * 发布新规则（按能力位并入：同能力位覆盖）。
     *
     * @return 发布后的新代
     */
    public RuleGeneration merge(ApiSpec spec) {
        while (true) {
            RuleGeneration base = current.get();
            RuleGeneration next = RuleGeneration.next(base, base.mergeInto(spec.pattern(), spec));
            if (current.compareAndSet(base, next)) {
                return next;
            }
            // 其它线程已发布新代 → 重试（乐观并发，极少冲突）
        }
    }

    /** 当前代快照（不可变，读侧无锁）。 */
    public RuleGeneration snapshot() {
        return current.get();
    }

    /**
     * 条件发布：仅当当前代仍为 {@code expected} 时切换为 {@code next}。
     * 供 stop* 系列使用（读-改-写必须原子，否则两个并发 stop 可能互相覆盖）。
     *
     * @return true=发布成功；false=其它线程已发布新代（调用方应重读重试）
     */
    public boolean compareAndSet(RuleGeneration expected, RuleGeneration next) {
        return current.compareAndSet(expected, next);
    }

    /**
     * 关闭单条能力位（句柄 close）：仅当 (pattern, capability) 当前仍是 {@code expected} 实例时移除该能力位。
     *
     * <p><b>令牌语义</b>：同 pattern 后续重新注册同能力位会用新实例覆盖该条目，此时 old 句柄的
     * {@code expected} 已不等于当前条目 ⇒ 拒绝摘除（不误撤新规则）。若移除后该 pattern 已无任何能力位，
     * 则整体移除该 pattern 条目。</p>
     *
     * @return true=该 pattern 已整体移除（无可保留的能力位，调用方应同时摘掉原生绑定）；
     *         false=该能力位已被替换/停止（拒绝关闭新规则），或并发重试失败
     */
    public boolean removeCapabilityIfCurrent(String pattern, RouteCapability cap, ApiSpec expected) {
        while (true) {
            RuleGeneration base = current.get();
            ApiSpec currentCap = base.rules().getOrDefault(pattern, Collections.emptyMap()).get(cap);
            if (currentCap != expected) {
                return false; // 令牌不一致：能力位已被重新注册取代 → 拒绝关闭新规则
            }
            Map<String, Map<RouteCapability, ApiSpec>> next = base.removeCapability(pattern, cap);
            if (current.compareAndSet(base, RuleGeneration.next(base, next))) {
                return !next.containsKey(pattern); // true=pattern 已整体移除
            }
        }
    }

    /**
     * 目的达成退役单条能力位（MONITOR 超时 / 断言结算）：仅当 (pattern, capability) 当前仍是
     * {@code expected} 实例时，将该能力位自身 spec 标记 disabled（<b>不</b>摘原生绑定，
     * handler 仍注册，仅 dispatch 跳过该能力位）。幂等。
     *
     * @return true=已生效；false=能力位已被替换/已停（拒绝误撤新规则），或并发重试失败
     */
    public boolean disableCapabilityIfCurrent(String pattern, RouteCapability cap, ApiSpec expected) {
        while (true) {
            RuleGeneration base = current.get();
            ApiSpec currentCap = base.rules().getOrDefault(pattern, Collections.emptyMap()).get(cap);
            if (currentCap != expected || currentCap.isStopped(cap)) {
                return false; // 令牌不一致或已停 → 拒绝（新规则自有生命周期）
            }
            Map<String, Map<RouteCapability, ApiSpec>> next = base.stopCapability(pattern, cap);
            if (current.compareAndSet(base, RuleGeneration.next(base, next))) {
                return true;
            }
        }
    }

    /**
     * 停用单条能力位（stop* 系列，用户主动停止）：该能力位存在且未停止即标记 disabled。
     * 不要求身份令牌（用户意图即停掉该能力位，无论由哪次注册引入）。
     *
     * @return true=已生效（能力位存在且被停）；false=不存在/已停（并发重试失败返回 false）
     */
    public boolean disableCapability(String pattern, RouteCapability cap) {
        while (true) {
            RuleGeneration base = current.get();
            ApiSpec currentCap = base.rules().getOrDefault(pattern, Collections.emptyMap()).get(cap);
            if (currentCap == null || currentCap.isStopped(cap)) {
                return false; // 不存在/已停 → 幂等
            }
            Map<String, Map<RouteCapability, ApiSpec>> next = base.stopCapability(pattern, cap);
            if (current.compareAndSet(base, RuleGeneration.next(base, next))) {
                return true;
            }
        }
    }

    /** 整体移除 pattern（clearRules / 退役整条规则）。仅当当前条目仍是 {@code expected} 时移除。 */
    public boolean removePatternIfCurrent(String pattern, Map<RouteCapability, ApiSpec> expected) {
        while (true) {
            RuleGeneration base = current.get();
            if (base.rules().get(pattern) != expected) {
                return false;
            }
            Map<String, Map<RouteCapability, ApiSpec>> next = base.removePattern(pattern);
            if (current.compareAndSet(base, RuleGeneration.next(base, next))) {
                return true;
            }
        }
    }

    public int generation() {
        return current.get().generation();
    }
}
