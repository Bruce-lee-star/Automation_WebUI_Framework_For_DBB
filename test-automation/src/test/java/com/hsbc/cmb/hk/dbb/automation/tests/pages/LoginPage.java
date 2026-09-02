package com.hsbc.cmb.hk.dbb.automation.tests.pages;

import com.hsbc.cmb.hk.dbb.automation.framework.web.page.Element;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.PageElement;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.base.impl.SerenityBasePage;

/**
 * 登录页面 - 支持免登录功能
 *
 * 使用说明：
 * 1. 首次登录时，会保存用户Session（Cookies + LocalStorage）
 * 2. 后续登录时，直接从Session恢复，实现免登录
 * 3. 同一用户在整个测试过程中只需登录一次
 *
 * 链式调用示例：
 * loginPage.USERNAME_INPUT.type(username);
 * loginPage.NEXT_BUTTON.click();
 *
 * 如果需要获取选择器字符串：
 * loginPage.USERNAME_INPUT.getSelector()
 */
public class LoginPage extends SerenityBasePage {

    @Element("#userName")
    public PageElement userNameIpt;

    @Element("[data-i18n='button_next']")
    public PageElement nextBtn;

    @Element("#newPassword")
    public PageElement paswordIpt;

    @Element("[data-i18n='otp_radio_label']")
    public PageElement physicalDeviceLabel;

    @Element("#newSecurityCode")
    public PageElement securityCodeIpt;

    @Element("[data-i18n='button_logon']")
    public PageElement loginBtn;

}
