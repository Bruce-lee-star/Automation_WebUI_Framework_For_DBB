package com.hsbc.cmb.hk.dbb.automation.tests.pages;

import com.hsbc.cmb.hk.dbb.automation.framework.web.page.Element;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.PageElement;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.PageElementList;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.base.impl.SerenityBasePage;

/**
 * 百度搜索 Page Object。
 * <p>选择器基于 https://www.baidu.com 实际 DOM 结构。
 */
public class BaiduPage extends SerenityBasePage {

    @Element("#chat-textarea")
    public PageElement searchInput;

    @Element("#chat-submit-button")
    public PageElement searchBtn;

    /** 搜索结果标题列表，选择器：h3.t */
    @Element("h3.t")
    public PageElementList resultItems;


    @Element(".navbar__title")
    public PageElement pageTitle;

    @Element("h1[class^='lemmaTitle']")
    public PageElement seleniumTitle;
}
