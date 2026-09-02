package com.hsbc.cmb.hk.dbb.automation.tests;

import com.microsoft.playwright.options.AriaRole;

import com.hsbc.cmb.hk.dbb.automation.framework.web.page.PageElement;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.RoleElement;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.RoleFile;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.base.BasePage;

@RoleFile({"nls/NLS_footer.json", "nls/NLS_idv_logon.json"})
public class LoginPage extends BasePage {

    @RoleElement(role = AriaRole.LINK, name = "Language:", exact = false)
    public PageElement languageLink;

    @RoleElement(role = AriaRole.HEADING, key = "title_username_page", level = 1)
    public PageElement titleUsernamePage;

    @RoleElement(role = AriaRole.TEXTBOX, key = "user_name")
    public PageElement userNameInput;

    @RoleElement(role = AriaRole.BUTTON, key = "forgot_username")
    public PageElement forgotUsernameBtn;

    @RoleElement(role = AriaRole.BUTTON, key = "button_next")
    public PageElement buttonNextBtn;

    @RoleElement(role = AriaRole.HEADING, name = "New to online banking?", level = 3, exact = false)
    public PageElement newToOnlineBanking;

    @RoleElement(role = AriaRole.LINK, key = "header_business", exact = false)
    public PageElement headerBusinessLink;

    @RoleElement(role = AriaRole.LINK, name = "Register as a user", exact = false)
    public PageElement registerAsAUserLink;

    @RoleElement(role = AriaRole.HEADING, name = "Need help?", level = 3, exact = false)
    public PageElement needHelp;

    @RoleElement(role = AriaRole.LINK, name = "Troubleshooting login issues", exact = false)
    public PageElement troubleshootingLoginIssuesLink;

    @RoleElement(role = AriaRole.HEADING, key = "tab_hsbc_app", level = 2, exact = false)
    public PageElement tabHsbcApp;

    @RoleElement(role = AriaRole.LINK, key = "tab_hsbc_app", exact = false)
    public PageElement tabHsbcAppLink;

    @RoleElement(role = AriaRole.HEADING, name = "Maintenance Schedule", level = 3, exact = false)
    public PageElement maintenanceSchedule;

    @RoleElement(role = AriaRole.LINK, name = "Maintenance Schedule", exact = false)
    public PageElement maintenanceScheduleLink;

    @RoleElement(role = AriaRole.LINK, name = "Payment Tracker", exact = false)
    public PageElement paymentTrackerLink;

    @RoleElement(role = AriaRole.LINK, name = "DEPOSIT PROTECTION SCHEME MEMBER", exact = false)
    public PageElement depositProtectionSchemeMemberLink;

    @RoleElement(role = AriaRole.LINK, key = "Privacy and Security footer", exact = false)
    public PageElement privacyAndSecurityFooterLink;

    @RoleElement(role = AriaRole.LINK, key = "Terms of Use footer", exact = false)
    public PageElement termsOfUseFooterLink;

    @RoleElement(role = AriaRole.LINK, key = "Hyperlink Policy footer", exact = false)
    public PageElement hyperlinkPolicyFooterLink;

    @RoleElement(role = AriaRole.LINK, key = "Online Security footer", exact = false)
    public PageElement onlineSecurityFooterLink;

    @RoleElement(role = AriaRole.HEADING, key = "title_hello_authenticate_page", level = 1, exact = false)
    public PageElement titleHelloAuthenticatePage;

    @RoleElement(role = AriaRole.LINK, key = "button_logon", exact = false)
    public PageElement buttonLogonLink;

    @RoleElement(role = AriaRole.TEXTBOX, key = "password")
    public PageElement passwordInput;

    @RoleElement(role = AriaRole.GROUP, key = "new_password_radio_title")
    public PageElement newPasswordRadioTitle;

    @RoleElement(role = AriaRole.RADIO, key = "push_auth_radio_label")
    public PageElement pushAuthRadioLabelRadio;

    @RoleElement(role = AriaRole.RADIO, key = "otp_radio_label")
    public PageElement otpRadioLabelRadio;

    @RoleElement(role = AriaRole.LINK, key = "forgot_password")
    public PageElement forgotPasswordLink;

    @RoleElement(role = AriaRole.BUTTON, key = "button_logon")
    public PageElement buttonLogonBtn;

    @RoleElement(role = AriaRole.LINK, key = "tab_security_device", exact = false)
    public PageElement tabSecurityDeviceLink;

    @RoleElement(role = AriaRole.LINK, name = "How to get the security code via your security device?", exact = false)
    public PageElement howToGetTheSecurityCodeViaYourSecurityDeviceLink;

    @RoleElement(role = AriaRole.IMG, key = "button_logon", exact = false)
    public PageElement buttonLogon;

    @RoleElement(role = AriaRole.IMG, key = "bib-reactivation-app-store", exact = false)
    public PageElement bibReactivationAppStore;

    @RoleElement(role = AriaRole.IMG, key = "bib-reactivation-apk-file", exact = false)
    public PageElement bibReactivationApkFile;

    @RoleElement(role = AriaRole.TEXTBOX, key = "security_code")
    public PageElement securityCodeInput;

}
