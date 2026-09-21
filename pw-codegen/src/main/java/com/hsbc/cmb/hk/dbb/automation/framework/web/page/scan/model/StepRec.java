package com.hsbc.cmb.hk.dbb.automation.framework.web.page.scan.model;

import com.hsbc.cmb.hk.dbb.automation.framework.web.page.scan.RoleEntry;
import java.util.List;

/**
 * 一次 step 记录：含所属页面类与本次拾取的元素列表（多页面归类用）。
 *
 * @apiNote 框架内部共享数据契约（picker 与 generator 跨包共用）。业务代码不应依赖。
 */
public final class StepRec {
    public final String pageClass;
    public final List<RoleEntry> picks;

    public StepRec(String pageClass, List<RoleEntry> picks) {
        this.pageClass = pageClass;
        this.picks = picks;
    }
}
