package com.hsbc.cmb.hk.dbb.automation.tests.steps;

import com.hsbc.cmb.hk.dbb.automation.framework.web.monitoring.ApiMonitorAndMockManager;
import com.hsbc.cmb.hk.dbb.automation.framework.web.monitoring.RealApiMonitor;
import com.hsbc.cmb.hk.dbb.automation.framework.web.session.SessionManager;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.factory.PageObjectFactory;
import com.hsbc.cmb.hk.dbb.automation.tests.pages.HomePage;
import com.hsbc.cmb.hk.dbb.automation.tests.pages.LoginPage;
import com.hsbc.cmb.hk.dbb.automation.tests.utils.BDDUtils;
import net.serenitybdd.annotations.Step;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Login related test steps - supports skip login functionality
 * <p>
 * Description:
 * - The same user only needs to log in once during entire test run
 * - Use SessionManager to manage user login status
 * - Support Cookie and LocalStorage persistence
 */
public class LoginSteps {
    private static final Logger logger = LoggerFactory.getLogger(LoginSteps.class);
    private static final LoginPage loginPage = PageObjectFactory.getPage(LoginPage.class);
    private static final HomePage homePage = PageObjectFactory.getPage(HomePage.class);
    private String currentUrl;

    @Step
    public void logonDBBEnvironmentAsUser(String env, String username) {
        BDDUtils logonDBBInfo = BDDUtils.getLogonDBBInfo(env, username);
        BDDUtils.setCurrentLoginInfo(logonDBBInfo);
        currentUrl = BDDUtils.getCurrentUrl();

        logger.info("========================================");
        logger.info("LoginSteps - User: {}, Environment: {}", username, env);
        logger.info("========================================");

        // Mock场景选择：
        // 【场景1】直接Mock - 直接提供完整响应数据（不需要监控）
        //   ApiMonitorAndMockManager.mockDirectSuccess(context, "/rest/lastLoginTime", "{\"lastLoginTime\":1735689600000}");
        //
        // 【场景2】捕获后Mock - 基于真实响应修改字段（需要监控，推荐）
        //   ApiMonitorAndMockManager.captureAndMockFieldWithOriginalStatus(context, "/rest/lastLoginTime", "lastLoginTime", 1735689600000L, 30);
        //
        // 【场景3】捕获后Mock + 自动停止 - 基于真实响应修改字段，第一次调用后自动停止mock
        //   ApiMonitorAndMockManager.captureAndMockFieldsWithOriginalStatus(context, "/rest/lastLoginTime", 
        //       Map.of("lastLoginTime", 1735689600000L), 30, true);
        //
        // 【手动停止所有Mock】
        //   ApiMonitorAndMockManager.stopAllMocks(context);  // 停止Context上的所有mock
        //   ApiMonitorAndMockManager.stopAllMocks(page);     // 停止Page上的所有mock
        //
        // 【手动停止指定Mock】
        //   ApiMonitorAndMockManager.stopMock(context, "/rest/lastLoginTime");  // 停止指定URL的mock
        //   ApiMonitorAndMockManager.stopMock(page, "/rest/lastLoginTime");     // 停止指定URL的mock
        //
        // 当前使用：【场景2】捕获后Mock - 自动使用原始API的状态码，修改lastLoginTime字段

        ApiMonitorAndMockManager.captureAndMockFieldWithOriginalStatus(
            loginPage.getPage().context(),
            "/rest/lastLoginTime",
            "lastLoginTime",
            1735689600000L,  // 2025年时间戳
            30   // 等待30秒获取真实响应
        );

        // 【简化】尝试恢复Session并跳过登录（如果可用）
        boolean skippedLogin = tryRestoreSessionAndNavigate(env, username);
        if (skippedLogin) {
            return; // Session已恢复，跳过登录
        }

        // Perform full login flow
        performLogin(env);
    }

    /**
     * 【简化】尝试恢复Session并导航到首页
     *
     * @param env      Environment identifier
     * @param username Username
     * @return true表示成功恢复session并跳过登录，false表示需要执行登录
     */
    private boolean tryRestoreSessionAndNavigate(String env, String username) {
        // 检查并尝试恢复session
        if (!SessionManager.restoreSession(env, username)) {
            return false;
        }

        String sessionKey = env + "_" + username;
        logger.info(" Session restored successfully for: {}", sessionKey);

        // 获取保存的home URL并导航
        SessionManager.UserSession userSession = SessionManager.loadSession(env, username);
        String homeUrl = userSession != null ? userSession.getHomeUrl() : null;

        if (homeUrl != null && !homeUrl.isEmpty()) {
            logger.info("Navigating to saved home URL: {}", homeUrl);
            loginPage.navigateTo(homeUrl);
            if (!homePage.quickLink.isVisible()){
                SessionManager.clearSession(env, username);
                return false;
            }
            homePage.quickLink.waitForVisible(30);
            logger.info(" Skip login successful - user already logged in and navigated to home page");

            // 【重要】跳过登录后，保存更新后的homeUrl和更新session时间戳
            String currentHomeUrl = loginPage.getCurrentUrl();
            SessionManager.saveSessionAfterLogin(env, username, currentHomeUrl);
            logger.info("Session updated after skip login with new home URL: {}", currentHomeUrl);
        } else {
            // Fallback: navigate to login page and refresh
            loginPage.navigateTo(currentUrl);
            loginPage.refresh();
            logger.info(" Skip login successful - user already logged in (no home URL saved)");

            // 【重要】跳过登录后，更新session
            String currentHomeUrl = loginPage.getCurrentUrl();
            SessionManager.saveSessionAfterLogin(env, username, currentHomeUrl);
            logger.info("Session updated after skip login with current URL: {}", currentHomeUrl);
        }

        return true; // Session已恢复并更新
    }

    /**
     * Perform full login flow
     *
     * @param env Environment identifier
     */
    private void performLogin(String env) {
        String username = BDDUtils.getCurrentUsername();
        String sessionKey = env + "_" + username;  // 使用推荐的env_username格式
        logger.info("Performing login for: {}", sessionKey);

        loginPage.navigateTo(currentUrl);
        loginPage.userNameIpt.type(username);
        RealApiMonitor.with(loginPage.getPage().context())
                .monitorApi("/rest/lastLoginTime", 200)
                .stopAfterSeconds(200)
                .build();
        loginPage.nextBtn.click();
        loginPage.paswordIpt.type(BDDUtils.getCurrentPassword());
        loginPage.physicalDeviceLabel.click();
        loginPage.securityCodeIpt.type(BDDUtils.getSecurityCode(BDDUtils.getCurrentSecurityUrl()));
        loginPage.loginBtn.click();
        loginPage.loginBtn.waitForNotVisible(40);

        // 【重要】停止所有mock，让后续API调用正常服务器
        logger.info(" Stopping all mocks - all APIs will call real server");
        ApiMonitorAndMockManager.stopAllMocks(loginPage.getPage().context());

        String targetProfile = BDDUtils.getCurrentProfile();
        if (targetProfile != null && !targetProfile.isEmpty()) {
            switchProfile(targetProfile);
        }
        homePage.quickLink.waitForVisible(30);

        String homeUrl = loginPage.getCurrentUrl();
        SessionManager.saveSessionAfterLogin(env, username, homeUrl);
    }

    /**
     * 切换 Profile
     * Profile 切换会导致旧 session 销毁，新 session 创建
     * 必须等待切换完成后才能保存 session
     *
     * @param profile 目标 profile 名称
     */
    private void switchProfile(String profile) {
        logger.info("Starting profile switch to: {}", profile);
        logger.info("Current URL before switch: {}", loginPage.getCurrentUrl());

        homePage.profileSwitcher.waitForVisible(30).click();
        logger.info("Clicked profile switcher");

        homePage.locator(String.format("//span[text()='%s']", profile)).click();
        logger.info("Clicked profile: {}", profile);

        // Wait longer for switch to process and page to reload
        logger.info("Waiting for page reload after profile switch...");
        try {
            // Wait for URL to change or page to reload
            Thread.sleep(5000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        logger.info("Current URL after click: {}", loginPage.getCurrentUrl());
        logger.info("Checking if quickLink is visible...");

        // Give more time for quickLink to appear
        logger.info("Waiting for quickLink to appear...");
        try {
            Thread.sleep(3000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        if (!homePage.quickLink.isVisible()){
            logger.info(" quickLink is NOT visible - profile switch failed!");
            logger.info("Page URL: {}", loginPage.getCurrentUrl());
            logger.info("Checking if page has loaded properly...");
            // Try to get page title for debugging
            try {
                String pageTitle = loginPage.getPage().title();
                logger.info("Page title: {}", pageTitle);
            } catch (Exception e) {
                logger.info("Failed to get page title: {}", e.getMessage());
            }
            throw new RuntimeException("Profile switch failed with " + profile);
        }

        logger.info(" quickLink is visible - profile switch successful");
        homePage.quickLink.waitForVisible(30);
    }
}
