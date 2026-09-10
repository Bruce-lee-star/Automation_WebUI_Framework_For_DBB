package com.hsbc.cmb.hk.dbb.automation.framework.web.page.scan.model;

/**
 * 面板命令循环的统一返回动作，驱动调用方决定「继续 / 终止 / 完成」。
 *
 * @apiNote 框架内部共享数据契约（picker 与 generator 跨包共用）。业务代码不应依赖。
 */
public enum PickerAction {
    CONTINUE, ABORT, DONE
}
