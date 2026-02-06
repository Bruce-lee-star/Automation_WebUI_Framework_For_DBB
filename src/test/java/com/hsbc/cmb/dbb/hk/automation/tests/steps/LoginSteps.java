package com.hsbc.cmb.dbb.hk.automation.tests.steps;

import com.hsbc.cmb.dbb.hk.automation.framework.session.SessionManager;
import com.hsbc.cmb.dbb.hk.automation.page.factory.PageObjectFactory;
import com.hsbc.cmb.dbb.hk.automation.tests.pages.HomePage;
import com.hsbc.cmb.dbb.hk.automation.tests.pages.LoginPage;
import com.hsbc.cmb.dbb.hk.automation.tests.utils.BDDUtils;
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
     * 1. Check if user is already logged in (via SessionManager with env+username as key)
     * 2. If logged in, restore session and navigate to URL
     * 3. If not logged in, perform full login flow and save session
     *
     * Session key format: env_username (e.g., O88_SIT1_AABBCCDD)
     * This allows the same username to have different sessions in different environments
     *
     * @param env Environment identifier (e.g., O88_SIT1, O63_SIT1, O38_SIT1)
     * @param username Username (e.g., AABBCCDD, ABCDEW)
     */
    @Step
    public void logonDBBEnvironmentAsUser(String env, String username) {
        BDDUtils logonDBBInfo = BDDUtils.getLogonDBBInfo(env, username);
        BDDUtils.setCurrentLoginInfo(logonDBBInfo);
        currentUrl = BDDUtils.getCurrentUrl();
        String sessionKey = env + "_" + username;

        // Check if user is already logged in (based on env+username)
        if (SessionManager.isUserLoggedIn(env, username)) {
            System.out.println("User already logged in: " + sessionKey + ", restoring session...");
            // Restore session and navigate to home page directly
            boolean restored = SessionManager.restoreSession(env, username);
            if (restored) {
                System.out.println("Session restored successfully for: " + sessionKey);
                // Get saved home URL from session
                SessionManager.UserSession userSession = SessionManager.loadSession(env, username);
                String homeUrl = userSession != null ? userSession.getHomeUrl() : null;
                if (homeUrl != null && !homeUrl.isEmpty()) {
                    System.out.println("Navigating to saved home URL: " + homeUrl);
                    loginPage.navigateTo(homeUrl);
                    homePage.waitForElementVisibleWithinTime(HomePage.quickLink, 10);
                    System.out.println("Skip login successful - user already logged in and navigated to home page");
                } else {
                    // Fallback: navigate to login page and refresh
                    loginPage.navigateTo(currentUrl);
                    loginPage.refresh();
                    System.out.println("Skip login successful - user already logged in (no home URL saved)");
                }
                return;
            } else {
                System.out.println("Failed to restore session for: " + sessionKey + ", will login again");
            }
        }

        // Perform full login flow
        performLogin(env);
    }

    /**
     * Perform full login flow
     * @param env Environment identifier
     */
    private void performLogin(String env) {
        String username = BDDUtils.getCurrentUsername();
        String sessionKey = env + "_" + username;
        System.out.println("Performing login for: " + sessionKey);

        loginPage.navigateTo(currentUrl);
        loginPage.type(LoginPage.USERNAME_INPUT, username);
        loginPage.click(LoginPage.NEXT_BUTTON);
        loginPage.type(LoginPage.PASSWORD_INPUT, BDDUtils.getCurrentPassword());
        loginPage.click(LoginPage.PHYSICAL_DEVICE_LABEL);
        loginPage.type(LoginPage.SECURITY_CODE_INPUT, BDDUtils.getSecurityCode(BDDUtils.getCurrentSecurityUrl()));
        loginPage.click(LoginPage.LOGIN_BUTTON);
        loginPage.waitForElementNotVisible(LoginPage.LOGIN_BUTTON, 30);
        homePage.waitForElementVisibleWithinTime(HomePage.quickLink, 30);
        // Get home page URL after successful login
        String homeUrl = loginPage.getCurrentUrl();
        // Mark user as logged in and save session with home URL
        SessionManager.markUserLoggedIn(env, username);
        SessionManager.saveSession(env, username, homeUrl);
        System.out.println("Login completed and session saved for: " + sessionKey);
        System.out.println("Login successful - home URL: " + homeUrl);
    }
}
