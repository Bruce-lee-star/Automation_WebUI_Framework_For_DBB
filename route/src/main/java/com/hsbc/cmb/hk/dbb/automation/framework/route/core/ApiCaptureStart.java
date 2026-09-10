package com.hsbc.cmb.hk.dbb.automation.framework.route.core;

import com.microsoft.playwright.Page;

/**
 * 链式启动辅助类（原 {@code ApiCaptureContext} 的内部静态类，Phase 5 抽离为独立类型）。
 */
public class ApiCaptureStart {
    private final Page page;

    ApiCaptureStart(Page page) {
        this.page = page;
    }

    /**
     * 启动采集。
     */
    public void start() {
        ApiCaptureContext.start(page);
    }
}
