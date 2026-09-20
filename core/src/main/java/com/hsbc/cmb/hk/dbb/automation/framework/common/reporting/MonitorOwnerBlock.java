package com.hsbc.cmb.hk.dbb.automation.framework.common.reporting;

import java.util.List;

/**
 * 同一 apiOwner 下的失败记录聚合（供 HTML 报告按「谁 API 发给谁」分组展示）。
 */
public class MonitorOwnerBlock {

    private final String owner;
    private final List<MonitorFailureItem> items;

    public MonitorOwnerBlock(String owner, List<MonitorFailureItem> items) {
        this.owner = owner;
        // 防御性拷贝为不可变集合，避免返回内部可变引用（EI_EXPOSE_REP2）
        this.items = items == null ? List.of() : List.copyOf(items);
    }

    public String getOwner() {
        return owner;
    }

    public List<MonitorFailureItem> getItems() {
        return items;
    }
}
