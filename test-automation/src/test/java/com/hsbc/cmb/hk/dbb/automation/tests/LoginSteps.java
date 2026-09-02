package com.hsbc.cmb.hk.dbb.automation.tests;

import net.serenitybdd.annotations.Step;

import com.hsbc.cmb.hk.dbb.automation.framework.web.page.factory.PageObjectFactory;
import com.hsbc.cmb.hk.dbb.automation.framework.web.session.SessionManager;
import com.hsbc.cmb.hk.dbb.automation.framework.web.utils.NLSUtils;
import com.hsbc.cmb.hk.dbb.automation.tests.utils.BDDUtils;

/**
 * 登录步骤。对齐框架 {@code tests.steps.LoginSteps#logonDBBEnvironmentAsUser(String, String)}
 * 与 Gherkin：Given logon DBB "{env}" environment as user "{username}"。
 *
 * <p>优化点（对比原硬编码 step1）：
 * <ol>
 *   <li>凭证不再写死，统一经 {@link BDDUtils} 按 (env, username) 动态获取（用户名/密码/验证码）；</li>
 *   <li>通过 {@link SessionManager} 复用与保存登录态，实现“免登录”（同一用户只登录一次）；</li>
 *   <li>补全最后的登录提交动作（buttonLogonBtn）。</li>
 * </ol>
 *
 * <p>如需完整能力（API mock/monitor、profile 切换、TargetClosed 降级重试），可直接复用框架
 * {@code tests.steps.LoginSteps}，本类为聚焦“去硬编码 + session”的精简对齐版。
 */
public class LoginSteps {

    private final LoginPage loginPage = PageObjectFactory.getPage(LoginPage.class);

    /**
     * 登录 DBB。对齐 Given：logon DBB "{env}" environment as user "{username}"
     *
     * @param env      环境标识，如 "O63_SIT1"
     * @param username 用户名，如 "WP7UAT2_2"
     */
    @Step
    public void logon(String env, String username) {
        // 0) RoleElement 页对象依赖 NLS 多语言定位：当前语言必须由框架显式感知，
        //    否则 RoleElementBinder 在运行时解析 key（如 user_name）会因 currentLang 未设置而抛异常。
        //    logon 页默认英文，语言值与 NLS 文件第一层语言 Key 一致（en-US）。
        //    若运行环境 logon 页以其它语言展示，请改为对应语言 Key（如 zh-HK）。
        NLSUtils.setLanguage("en-US");

        // 1) 动态获取登录信息（替代硬编码用户名/密码/验证码）
        BDDUtils loginInfo = BDDUtils.getLogonDBBInfo(env, username);
        BDDUtils.setCurrentLoginInfo(loginInfo);
        String loginUrl = BDDUtils.getCurrentUrl();

        // 2) session key 用于复用/保存登录态
        String sessionKey = env + "_" + username;

        // 3) 尝试复用已有 session：命中且仍有效则直接跳过登录
        if (SessionManager.restoreSession(sessionKey)) {
            String homeUrl = SessionManager.getHomeUrl(sessionKey);
            if (homeUrl != null && !homeUrl.isEmpty()) {
                loginPage.navigateTo(homeUrl);
                if (!loginPage.getCurrentUrl().contains("/logon")) {
                    return; // session 有效，免登录
                }
            }
        }

        // 4) session 失效/不存在：执行完整登录流程
        loginPage.navigateTo(loginUrl);
        loginPage.userNameInput.type(username);
        loginPage.privacyAndSecurityFooterLink.click();
        loginPage.waitForTimeout(5000);
        loginPage.switchToPage(1);
        loginPage.closeCurrentPage();
        loginPage.switchToPage(0);
        loginPage.buttonNextBtn.click();
        loginPage.passwordInput.type(BDDUtils.getCurrentPassword());
        loginPage.otpRadioLabelRadio.check();
        loginPage.securityCodeInput.type(BDDUtils.getSecurityCode(BDDUtils.getCurrentSecurityUrl()));
        loginPage.buttonLogonBtn.click();
        loginPage.buttonLogonBtn.waitForNotVisible(60);

        // 5) 保存登录态，供后续场景/步骤免登录复用
        SessionManager.saveSession(sessionKey, loginPage.getCurrentUrl());
    }
}
