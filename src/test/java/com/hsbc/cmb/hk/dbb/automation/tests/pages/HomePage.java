package com.hsbc.cmb.hk.dbb.automation.tests.pages;

import com.hsbc.cmb.hk.dbb.automation.framework.web.page.Element;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.PageElement;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.base.impl.SerenityBasePage;

/**
 * 首页
 *
 * 链式调用示例：
 * homePage.quickLink.click();
 * homePage.loadingIndicator.waitForNotVisible(30);
 *
 * 如果需要获取选择器字符串：
 * homePage.quickLink.getSelector()
 */
public class HomePage extends SerenityBasePage {

    @Element("a[id='02010000']")
    public PageElement quickLink;

    @Element("[id='topnav.profileswitcher.dropdown']")
    public PageElement profileSwitcher;
}
