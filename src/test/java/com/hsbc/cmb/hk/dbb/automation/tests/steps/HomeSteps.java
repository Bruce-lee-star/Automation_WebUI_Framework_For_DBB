package com.hsbc.cmb.hk.dbb.automation.tests.steps;

import com.hsbc.cmb.hk.dbb.automation.framework.web.config.BrowserOverrideManager;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.factory.PageObjectFactory;
import com.hsbc.cmb.hk.dbb.automation.framework.web.session.SessionManager;
import com.hsbc.cmb.hk.dbb.automation.tests.pages.HomePage;
import com.hsbc.cmb.hk.dbb.automation.tests.utils.BDDUtils;

public class HomeSteps {

    private HomePage homePage = PageObjectFactory.getPage(HomePage.class);

    /**
     * 切换 Profile 并关闭提醒
     *
     * 注意：Profile 切换会导致旧 session 销毁，新 session 创建
     * 此方法会自动更新 session，确保后续测试可以跳过登录
     *
     * @param profile 目标 profile 名称
     */
    public void switchProfileToAndCloseReminder(String profile) {
        System.out.println("Switching profile to: " + profile);
        homePage.profileSwitcher.click();
        homePage.locator(String.format("//span[text()='%s']", profile)).click();
        homePage.quickLink.waitForVisible(30);
        updateSessionAfterProfileSwitch(profile);
    }


    /**
     * Profile 切换后更新 session
     *
     * @param profile 切换后的 profile 名称（用于日志）
     */
    private void updateSessionAfterProfileSwitch(String profile) {
        String env = BDDUtils.getEnv();
        String username = BDDUtils.getCurrentUsername();
        String homeUrl = homePage.getPage().url();
        try {
            // 更新 session（保存新的 cookies 和 localStorage）
            SessionManager.saveSessionAfterLogin(env, username, homeUrl);
            System.out.println("Session updated after profile switch to: " + profile);
        } catch (Exception e) {
            System.out.println("Warning: Failed to update session after profile switch: " + e.getMessage());
            SessionManager.clearSession(env, username);
        }
    }
}
