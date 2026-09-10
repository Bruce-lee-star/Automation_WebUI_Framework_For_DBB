package com.hsbc.cmb.hk.dbb.automation.framework.web.listener.resiliencypkg;

import com.hsbc.cmb.hk.dbb.automation.framework.web.listener.FrameworkListener;

/**
 * 构造器必败的坏类，用于表征「单类实例化失败仅跳过、不中止整个注册表」（W-16 根治）。
 * 注意：用构造器抛错（而非静态块）以通过编译——static 初始化器不允许「只能异常结束」。
 */
public class BadLoadListener implements FrameworkListener {
    public BadLoadListener() {
        throw new RuntimeException("deliberate instantiation failure for WEB-P1-3 resilience test");
    }
}
