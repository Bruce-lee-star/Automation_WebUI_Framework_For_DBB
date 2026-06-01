package com.hsbc.cmb.hk.dbb.automation.tests.steps;

import com.hsbc.cmb.hk.dbb.automation.framework.web.session.SessionManager;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.factory.PageObjectFactory;
import com.hsbc.cmb.hk.dbb.automation.tests.pages.HomePage;
import com.hsbc.cmb.hk.dbb.automation.tests.pages.LoginPage;
import com.hsbc.cmb.hk.dbb.automation.tests.utils.BDDUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HomeSteps {

    private static final Logger logger = LoggerFactory.getLogger(HomeSteps.class);

    // PageObject 字段（非静态初始化）
    // BasePage 构造函数不会创建 Context，Context 只在实际使用时创建
    private HomePage homePage = PageObjectFactory.getPage(HomePage.class);
    private LoginPage loginPage = PageObjectFactory.getPage(LoginPage.class);

    /**
     * 切换 Profile 并关闭提醒。
     *
     * 注意：Profile 切换会导致旧 session 销毁，新 session 创建。
     *
     * @param profile 目标 profile 名称
     */
    public void switchProfileToAndCloseReminder(String profile) {
        logger.info("Switch profile: current='{}', target='{}'",
                homePage.profileSwitcher.getText(), profile);

        if (homePage.profileSwitcher.getText().contains(profile)) {
            logger.info("Already on profile '{}', skipping switch", profile);
            return;
        }

        homePage.profileSwitcher.waitForVisible(30).click();
        homePage.locator(String.format("//span[text()='%s']", profile)).click();
        if (!homePage.quickLink.isVisible()) {
            throw new RuntimeException("Profile switch failed with " + profile);
        }
        homePage.quickLink.waitForVisible(30);

        logger.info("Profile switched successfully: {}", profile);

        // 保存切换后的 session
        String sessionKey = generateSessionKey();
        String homeUrl = homePage.getPage().url();
        SessionManager.saveSession(sessionKey, homeUrl);
    }

    /**
     * 生成 session key（业务层自定义）
     */
    private String generateSessionKey() {
        return BDDUtils.getEnv() + "_" + BDDUtils.getCurrentUsername();
    }
}
