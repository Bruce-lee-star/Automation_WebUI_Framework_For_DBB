package com.hsbc.cmb.hk.dbb.automation.framework.web.page.scan.model;

/**
 * 一次「页面级操作」记录：如关闭页面（{@code op='close'}）。
 * 区别于元素拾取 step，不产生元素字段。
 *
 * @apiNote 框架内部共享数据契约（picker 与 generator 跨包共用）。业务代码不应依赖。
 */
public final class PageOp {
    public final String pageClass;
    public final String op;

    public PageOp(String pageClass, String op) {
        this.pageClass = pageClass;
        this.op = op;
    }
}
