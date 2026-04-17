package com.hsbc.cmb.hk.dbb.automation.tests.steps;

import com.hsbc.cmb.hk.dbb.automation.framework.web.page.factory.PageObjectFactory;
import com.hsbc.cmb.hk.dbb.automation.framework.web.session.SessionManager;
import com.hsbc.cmb.hk.dbb.automation.framework.web.snapshot.PlaywrightSnapshotSupport;
import com.hsbc.cmb.hk.dbb.automation.tests.pages.HomePage;
import com.hsbc.cmb.hk.dbb.automation.tests.pages.LoginPage;
import com.hsbc.cmb.hk.dbb.automation.tests.utils.BDDUtils;
import net.serenitybdd.annotations.Step;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Login related test steps - 超简化版，使用新的框架层 API
 * <p>
 * 设计理念：
 * 1. 业务层只负责业务逻辑（登录、切换 profile）
 * 2. 框架层负责 session 管理和自动恢复
 * 3. 业务层只传递 session key
 * <p>
 * 使用方式：
 * <pre>
 * @Given("logon DBB {string} environment as user {string}")
 * public void logonDBBEnvironmentAsUser(String env, String username) {
 *     // 生成 session key
 *     String sessionKey = env + "_" + username;
 *
 *     // 【核心】让框架自动处理 session
 *     if (SessionManager.prepareSession(sessionKey)) {
 *         // Session 已准备好，框架已设置 storageStatePath
 *         // 导航到首页（session 会自动应用）
 *         loginPage.navigateTo("https://example.com/home");
 *
 *         // 验证 session 是否有效
 *         if (!loginPage.getCurrentUrl().contains("/logon")) {
 *             homePage.quickLink.waitForVisible(30);
 *             return; // Session 有效，跳过登录
 *         }
 *     }
 *
 *     // Session 无效，执行登录
 *     performLogin(sessionKey);
 * }
 * </pre>
 */
public class LoginSteps {
    private static final Logger logger = LoggerFactory.getLogger(LoginSteps.class);

    // PageObject 字段（非静态初始化）
    // BasePage 构造函数不会创建 Context，Context 只在实际使用时创建
    private LoginPage loginPage = PageObjectFactory.getPage(LoginPage.class);
    private HomePage homePage = PageObjectFactory.getPage(HomePage.class);

    // 状态变量
    private String currentUrl;
    private String sessionKey;

    @Step
    public void logonDBBEnvironmentAsUser(String env, String username) {
        // 准备登录信息
        BDDUtils logonDBBInfo = BDDUtils.getLogonDBBInfo(env, username);
        BDDUtils.setCurrentLoginInfo(logonDBBInfo);
        currentUrl = BDDUtils.getCurrentUrl();

        // 生成 session key
        sessionKey = generateSessionKey(env, username);

        logger.info("========================================");
        logger.info("LoginSteps - SessionKey: {}", sessionKey);
        logger.info("========================================");


        if (SessionManager.restoreSession(sessionKey)) {
                       // Session 已准备好，框架已设置 storageStatePath
            
            // 【关键】从 meta 文件读取 homeUrl
            String homeUrl = SessionManager.loadHomeUrl(sessionKey);
            if (homeUrl == null || homeUrl.isEmpty()) {
                logger.warn("Session file exists but no homeUrl found, cannot skip login");
                // SessionManager.clearSession(sessionKey); // 旧方法已删除
                performLogin();
                return;
            }
            
            logger.info("Session restored, navigating to homeUrl: {}", homeUrl);
            loginPage.navigateTo(homeUrl);
            loginPage.waitForTimeout(5000);
            // 验证 session 是否有效
            String currentUrl = loginPage.getCurrentUrl();
            logger.info("Current URL after navigation: {}", currentUrl);
            
            if (currentUrl.contains("/logon")) {
                logger.warn("Session invalid (redirected to login page)");
                // SessionManager.clearSession(sessionKey); // 旧方法已删除
                performLogin();
                return;
            }
            
            // Session 有效，等待首页元素
            homePage.quickLink.waitForVisible(30);
            logger.info("Session validated successfully, skipping login");
            return; // Session 有效，跳过登录
        }
        
        // Session 无效或不存在，执行登录
        performLogin();
    }

    /**
     * 执行完整登录流程
     */
    private void performLogin() {
        String username = BDDUtils.getCurrentUsername();
        logger.info("No valid session, performing login for: {}", sessionKey);

        loginPage.navigateTo(currentUrl);
        
        // Axe-core accessibility scan on login page
//        AxeCoreScanner.scanPage("Login Page - Initial");
        
        loginPage.userNameIpt.type(username);
        PlaywrightSnapshotSupport.of(loginPage.getPage())
                .visual()
                .baselineName("login-page")
                .updateBaseline(false)
                .snapshot();
        loginPage.nextBtn.click();
        loginPage.paswordIpt.type(BDDUtils.getCurrentPassword());
        PlaywrightSnapshotSupport.of(loginPage.getPage())
                .visual()
                .baselineName("password-page")
                .updateBaseline(false)
                .snapshot();
//        // Axe-core scan after password input
//        AxeCoreScanner.scanPage("Login Page - After Password");
        
        loginPage.physicalDeviceLabel.click();
        loginPage.securityCodeIpt.type(BDDUtils.getSecurityCode(BDDUtils.getCurrentSecurityUrl()));
        loginPage.loginBtn.click();
        loginPage.loginBtn.waitForNotVisible(40);

        String targetProfile = BDDUtils.getCurrentProfile();
        if (targetProfile != null && !targetProfile.isEmpty()) {
            switchProfile(targetProfile);
        }
        homePage.quickLink.waitForVisible(30);
//        AccessibilityScanner.checkAndCollect("logon - home Page");
        
        // Axe-core accessibility scan on home page
//        AxeCoreScanner.scanPage("Home Page - After Login");
        logger.info("Axe-core scans completed for login flow");

        // 【核心】让框架自动保存 session
        // 业务层只传递 session key 和 homeUrl
        // 框架会自动：
        // 1. 获取当前 context
        // 2. 使用 Playwright API 保存 storageState 到文件
        // 3. 保存元数据（homeUrl + timestamp）
        String homeUrl = loginPage.getCurrentUrl();
        SessionManager.saveSession(sessionKey, homeUrl);

        logger.info("Login completed and session saved");
    }

    /**
     * 切换 Profile
     */
    private void switchProfile(String profile) {
        homePage.profileSwitcher.waitForVisible(30).click();
        homePage.locator(String.format("//span[text()='%s']", profile)).click();
        logger.info("Clicked profile: {}", profile);
        if (!homePage.quickLink.isVisible()) {
            throw new RuntimeException("Profile switch failed with " + profile);
        }
        homePage.quickLink.waitForVisible(30);

        // 切换 profile 后保存 session
        String homeUrl = loginPage.getCurrentUrl();
        SessionManager.saveSession(sessionKey, homeUrl);
    }

    /**
     * 生成 session key
     */
    private String generateSessionKey(String env, String username) {
        return env + "_" + username;
    }
}
