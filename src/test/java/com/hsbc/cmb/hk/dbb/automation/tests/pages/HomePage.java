package com.hsbc.cmb.hk.dbb.automation.tests.pages;

import com.hsbc.cmb.hk.dbb.automation.framework.web.page.base.impl.SerenityBasePage;

public class HomePage extends SerenityBasePage {

    public static final String quickLink = "a[id='02010000']";
    // 使用更具体的选择器来避免匹配多个 loading 指示器
    // 添加 :first-child 来选择第一个匹配的元素
    public static final String loadingIndicator = ".MuiCircularProgress-root:first-child .MuiCircularProgress-svg";
}
