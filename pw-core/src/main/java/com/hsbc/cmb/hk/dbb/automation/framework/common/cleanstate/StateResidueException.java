package com.hsbc.cmb.hk.dbb.automation.framework.common.cleanstate;

import java.util.List;

/**
 * B-6：用例结束后检测到「状态残留」时抛出——断言式清理。
 *
 * <p>把「忘记清理 → 以后某个用例莫名失败」提前为「当场失败并指明残留状态名」，大幅降低排查成本。
 */
public class StateResidueException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient List<String> residue;

    public StateResidueException(List<String> residue) {
        super("用例结束后状态残留（隔离失效）: " + residue
                + " —— 请检查对应 StateResolver.reset() 是否被调用 / 是否生效");
        this.residue = residue == null ? List.of() : List.copyOf(residue);
    }

    /** 残留状态名快照。 */
    public List<String> residue() {
        return residue;
    }
}
