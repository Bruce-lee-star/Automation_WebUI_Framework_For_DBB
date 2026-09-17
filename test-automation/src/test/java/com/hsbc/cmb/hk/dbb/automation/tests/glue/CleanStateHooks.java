package com.hsbc.cmb.hk.dbb.automation.tests.glue;

import com.hsbc.cmb.hk.dbb.automation.framework.common.cleanstate.CleanStateRegistry;
import com.hsbc.cmb.hk.dbb.automation.framework.common.cleanstate.StateResidueException;
import io.cucumber.java.After;
import io.cucumber.java.Before;

import java.util.List;

/**
 * B-6：框架级「用例级隔离」钩子——把「每个 glue 手写 {@code @Before} 重置」收口为统一机制。
 *
 * <p>业务把复位/残留判定注册为 {@code StateResolver}（见 {@link CleanStateRegistry}），本钩子：
 * <ul>
 *   <li>{@code @Before}：{@link CleanStateRegistry#resetAll()} 统一复位（空注册表为空操作）；</li>
 *   <li>{@code @After}：先复位（幂等，兼作收尾清理）再断言无残留——发现残留即抛
 *       {@link StateResidueException}，把「忘记清理」从「以后某个用例莫名失败」变为「当场失败并指明状态」。</li>
 * </ul>
 *
 * <p><b>顺序</b>：{@code @Before} 取 {@code MIN_VALUE + 1}（在 {@code FrameworkHooks} 绑定用例上下文之后、
 * 业务 glue 默认 order 0 之前）；{@code @After} 取 {@code MAX_VALUE - 1}（在所有业务收尾之后、用例上下文解绑之前）。
 *
 * <p>所有方法对未注册状态的场景为空操作，幂等、无副作用。
 */
public class CleanStateHooks {

    @Before(order = Integer.MIN_VALUE + 1)
    public void resetDeclaredState() {
        CleanStateRegistry.resetAll();
    }

    @After(order = Integer.MAX_VALUE - 1)
    public void assertNoResidue() {
        CleanStateRegistry.resetAll();
        List<String> dirty = CleanStateRegistry.dirtyNames();
        if (!dirty.isEmpty()) {
            throw new StateResidueException(dirty);
        }
    }
}
