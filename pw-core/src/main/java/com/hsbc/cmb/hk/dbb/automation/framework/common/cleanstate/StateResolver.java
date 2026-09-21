package com.hsbc.cmb.hk.dbb.automation.framework.common.cleanstate;

/**
 * B-6：可清理状态解析器 SPI——把「用例级共享状态的复位与残留判定」从各 glue 手写 {@code @Before}
 * 收口为框架统一机制。
 *
 * <p>实现方在启动时经 {@link CleanStateRegistry#register(StateResolver)} 注册；随后由框架钩子统一：
 * {@code @Before} 复位（{@link #reset()}）、{@code @After} 断言无残留（{@link #isDirty()}）。
 * 这样「忘记清理」从「以后某个用例莫名失败」变成「当场失败并指明是哪个状态残留」。
 *
 * <p>线程安全：{@code reset()} / {@code isDirty()} 可能在不同线程调用，实现须自行保证线程安全，
 * 且 {@code reset()} 应幂等（重播固定基线），以便并行场景下任一用例收尾都能安全复位。
 */
public interface StateResolver {

    /** 状态名（唯一标识，用于注册表键与残留报告）。 */
    String name();

    /** 复位到基线；须幂等、且不抛异常（失败应降级记录，绝不阻断用例收尾）。 */
    void reset();

    /**
     * 是否仍处于「脏」状态（复位未生效 / 收尾残留）。
     * <p>默认 {@code false}（不参与断言），实现方按需覆盖。
     */
    default boolean isDirty() {
        return false;
    }
}
