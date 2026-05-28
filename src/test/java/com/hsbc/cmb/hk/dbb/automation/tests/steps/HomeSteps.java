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
     * 【延迟机制—单一切换点】
     * LoginSteps 登录后不再切 profile，只将配置中的 profile 存入
     * BDDUtils.targetProfile。此方法负责一次性切换到最终目标 profile：
     * 1. 用 Scenario 指定的 profile 覆盖 targetProfile
     * 2. 检查当前是否已在目标 profile → 是则跳过
     * 3. 否则执行一次切换
     *
     * 注意：Profile 切换会导致旧 session 销毁，新 session 创建。
     * 此方法会自动更新 session，确保后续测试可以跳过登录。
     *
     * @param profile 目标 profile 名称（来自 Scenario When 步骤）
     */
    public void switchProfileToAndCloseReminder(String profile) {
        // 【延迟机制】用 Scenario 指定的 profile 覆盖 LoginSteps 设置的默认值
        BDDUtils.setTargetProfile(profile);
        String targetProfile = BDDUtils.getTargetProfile();

        logger.info("Single-switch profile: current='{}', target='{}'",
                homePage.profileSwitcher.getText(), targetProfile);

        // 如果已在目标 profile，跳过切换
        if (homePage.profileSwitcher.getText().contains(targetProfile)) {
            logger.info("Already on target profile '{}', skipping switch", targetProfile);
            return;
        }

        // 唯一一次切换
        homePage.profileSwitcher.waitForVisible(30).click();
        homePage.locator(String.format("//span[text()='%s']", targetProfile)).click();
        if (!homePage.quickLink.isVisible()) {
            throw new RuntimeException("Profile switch failed with " + targetProfile);
        }
        homePage.quickLink.waitForVisible(30);

        logger.info("Profile switched successfully: {}", targetProfile);

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
