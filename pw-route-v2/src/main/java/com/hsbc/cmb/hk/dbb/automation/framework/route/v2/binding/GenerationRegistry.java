package com.hsbc.cmb.hk.dbb.automation.framework.route.v2.binding;

import com.hsbc.cmb.hk.dbb.automation.framework.route.v2.dsl.ApiSpec;

import java.util.concurrent.atomic.AtomicReference;

/**
 * 代际注册表 —— 规则的单一发布点。
 *
 * <p>并发模型：{@link AtomicReference} + CAS 循环。任何线程（业务线程 / 事件线程）
 * 调用 {@link #merge(ApiSpec)} 都会把「旧代 + 新规则」原子地发布为新代；读侧
 * {@link #snapshot()} 无锁拿当前代。不存在「读到一半的规则表」或「新旧代撕裂」。
 */
public final class GenerationRegistry {

    private final AtomicReference<RuleGeneration> current;

    public GenerationRegistry() {
        this.current = new AtomicReference<>(RuleGeneration.empty());
    }

    /**
     * 发布新规则（同 pattern 覆盖）。
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

    public int generation() {
        return current.get().generation();
    }
}
