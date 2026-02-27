package com.hsbc.cmb.hk.dbb.automation.tests.steps;

import com.hsbc.cmb.hk.dbb.automation.framework.web.config.BrowserOverrideManager;
import com.hsbc.cmb.hk.dbb.automation.framework.web.monitoring.ApiMonitorAndMockManager;
import com.hsbc.cmb.hk.dbb.automation.framework.web.monitoring.RealApiMonitor;
import com.hsbc.cmb.hk.dbb.automation.framework.web.session.SessionManager;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.factory.PageObjectFactory;
import com.hsbc.cmb.hk.dbb.automation.tests.pages.HomePage;
import com.hsbc.cmb.hk.dbb.automation.tests.pages.LoginPage;
import com.hsbc.cmb.hk.dbb.automation.tests.utils.BDDUtils;
import net.serenitybdd.annotations.Step;
/**
 * Login related test steps - supports skip login functionality
 *
 * Description:
 * - The same user only needs to log in once during the entire test run
 * - Use SessionManager to manage user login status
 * - Support Cookie and LocalStorage persistence
 */
public class LoginSteps {
    private static final LoginPage loginPage = PageObjectFactory.getPage(LoginPage.class);
    private static final HomePage homePage = PageObjectFactory.getPage(HomePage.class);
    private String currentUrl;

    /**
     * Logon to DBB environment as specified user with session management
     *
     * This method implements skip login functionality:
     * 1. Check if user is already logged in (via SessionManager with env+username+browser as key)
     * 2. If logged in, restore session and navigate to URL
     * 3. If not logged in, perform full login flow and save session
     *
     * Session key format: env_username_browser (e.g., O88_SIT1_AABBCCDD_chromium)
     * This allows the same username to have different sessions in different environments and browsers
     * @param env Environment identifier (e.g., O88_SIT1, O63_SIT1, O38_SIT1)
     * @param username Username (e.g., AABBCCDD, ABCDEW)
     */
    @Step
    public void logonDBBEnvironmentAsUser(String env, String username) {
        BDDUtils logonDBBInfo = BDDUtils.getLogonDBBInfo(env, username);
        BDDUtils.setCurrentLoginInfo(logonDBBInfo);
        currentUrl = BDDUtils.getCurrentUrl();
        ApiMonitorAndMockManager.mockSuccess(loginPage.getPage().context(), "/rest/lastLoginTime", "{\n" +
                "  \"lastLoginTime\" : 1672215146000,\n" +
                "  \"shortName\" : \"SUAAAA\",\n" +
                "  \"accountNumber\" : \"003205697001\",\n" +
                "  \"time\" : \"2025-02-27 18:04:06\"\n" +
                "}");
        // 【简化】尝试恢复Session并跳过登录（如果可用）
        boolean skippedLogin = tryRestoreSessionAndNavigate(env, username);
        if (skippedLogin) {
            loginPage.getPage().pause();
            return; // Session已恢复，跳过登录
        }

        // Perform full login flow
        performLogin(env);
    }

    /**
     * 【简化】尝试恢复Session并导航到首页
     *
     * @param env Environment identifier
     * @param username Username
     * @return true表示成功恢复session并跳过登录，false表示需要执行登录
     */
    private boolean tryRestoreSessionAndNavigate(String env, String username) {
        // 检查并尝试恢复session
        if (!SessionManager.restoreSession(env, username)) {
            return false; // Session不可用，需要登录
        }

        String browserType = BrowserOverrideManager.getEffectiveBrowserType();
        String sessionKey = env + "_" + username + "_" + browserType;
        System.out.println("Session restored successfully for: " + sessionKey);

        // 获取保存的home URL并导航
        SessionManager.UserSession userSession = SessionManager.loadSession(env, username);
        String homeUrl = userSession != null ? userSession.getHomeUrl() : null;

        if (homeUrl != null && !homeUrl.isEmpty()) {
            System.out.println("Navigating to saved home URL: " + homeUrl);
            loginPage.navigateTo(homeUrl);
            homePage.quickLink.waitForVisible(30);
            System.out.println("Skip login successful - user already logged in and navigated to home page");

            // 【重要】跳过登录后，保存更新后的homeUrl和更新session时间戳
            String currentHomeUrl = loginPage.getCurrentUrl();
            SessionManager.saveSessionAfterLogin(env, username, currentHomeUrl);
            System.out.println("Session updated after skip login with new home URL: " + currentHomeUrl);
        } else {
            // Fallback: navigate to login page and refresh
            loginPage.navigateTo(currentUrl);
            loginPage.refresh();
            System.out.println("Skip login successful - user already logged in (no home URL saved)");

            // 【重要】跳过登录后，更新session
            String currentHomeUrl = loginPage.getCurrentUrl();
            SessionManager.saveSessionAfterLogin(env, username, currentHomeUrl);
            System.out.println("Session updated after skip login with current URL: " + currentHomeUrl);
        }

        return true; // Session已恢复并更新
    }

    /**
     * Perform full login flow
     * @param env Environment identifier
     */
    private void performLogin(String env) {
        String username = BDDUtils.getCurrentUsername();
        String browserType = BrowserOverrideManager.getEffectiveBrowserType();
        String sessionKey = env + "_" + username + "_" + browserType;
        System.out.println("Performing login for: " + sessionKey);

        loginPage.navigateTo(currentUrl);
        loginPage.USERNAME_INPUT.type(username);
        RealApiMonitor.with(loginPage.getPage().context())
                .monitorApi("auth/login", 200)
                .stopAfterSeconds(10)
                .build();
        loginPage.NEXT_BUTTON.click();
        loginPage.PASSWORD_INPUT.type(BDDUtils.getCurrentPassword());
        loginPage.PHYSICAL_DEVICE_LABEL.click();
        loginPage.SECURITY_CODE_INPUT.type(BDDUtils.getSecurityCode(BDDUtils.getCurrentSecurityUrl()));
        loginPage.LOGIN_BUTTON.click();
        loginPage.LOGIN_BUTTON.waitForNotVisible(40);
        homePage.quickLink.waitForVisible(30);

        // Get home page URL after successful login
        String homeUrl = loginPage.getCurrentUrl();
        // 【简化】一行代码：标记用户已登录并保存session
        SessionManager.saveSessionAfterLogin(env, username, homeUrl);
    }
}
