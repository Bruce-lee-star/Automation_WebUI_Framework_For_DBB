package com.hsbc.cmb.hk.dbb.automation.framework.web.page.scan.model;

import com.hsbc.cmb.hk.dbb.automation.framework.web.page.scan.RoleEntry;
import java.util.List;

/**
 * 停止命令一次性读取的拾取态快照（合并多趟 {@code page.evaluate} 以降低延迟）。
 *
 * @apiNote 框架内部共享数据契约（picker 与 generator 跨包共用）。业务代码不应依赖。
 */
public final class PickSnapshot {
    public final String pageClass;
    public final List<RoleEntry> entries;
    public final List<StepRec> steps;
    public final List<PageOp> ops;

    public PickSnapshot(String pageClass, List<RoleEntry> entries, List<StepRec> steps, List<PageOp> ops) {
        this.pageClass = pageClass;
        this.entries = entries;
        this.steps = steps;
        this.ops = ops;
    }
}
