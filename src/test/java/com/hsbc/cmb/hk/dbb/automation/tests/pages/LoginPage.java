package com.hsbc.cmb.hk.dbb.automation.tests.pages;

import com.hsbc.cmb.hk.dbb.automation.framework.web.page.base.impl.SerenityBasePage;

/**
 * 登录页面 - 支持免登录功能
 * 
 * 使用说明：
 * 1. 首次登录时，会保存用户Session（Cookies + LocalStorage）
 * 2. 后续登录时，直接从Session恢复，实现免登录
 * 3. 同一用户在整个测试过程中只需登录一次
 */
public class LoginPage extends SerenityBasePage {
    // 页面元素选择器
    public static final String USERNAME_INPUT = "#userName";
    public static final String NEXT_BUTTON = "[data-i18n='button_next']";
    public static final String PASSWORD_INPUT = "#newPassword";
    public static final String PHYSICAL_DEVICE_LABEL = "[data-i18n='otp_radio_label']";
    public static final String SECURITY_CODE_INPUT = "#newSecurityCode";
    public static final String LOGIN_BUTTON = "[data-i18n='button_logon']";

}
