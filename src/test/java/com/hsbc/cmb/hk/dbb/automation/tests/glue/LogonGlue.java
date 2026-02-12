package com.hsbc.cmb.hk.dbb.automation.tests.glue;

import com.hsbc.cmb.hk.dbb.automation.framework.web.annotations.AutoBrowser;
import com.hsbc.cmb.hk.dbb.automation.framework.web.config.BrowserOverrideManager;
import com.hsbc.cmb.hk.dbb.automation.tests.steps.LoginSteps;
import io.cucumber.java.en.Given;
import net.serenitybdd.annotations.Steps;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Logon Glue Code - 登录相关的步骤定义
 *
 * 企业级解决方案：添加@AutoBrowser注解，浏览器自动管理
 *
 * 使用方式：
 * 1. 在Glue类上添加@AutoBrowser注解
 * 2. 完成！AutoBrowserManager会自动管理浏览器
 *
 * 优势：
 * - ✅ 零配置：只需一个注解
 * - ✅ 零侵入：步骤代码完全不需要修改
 * - ✅ 自动化：框架自动处理浏览器切换
 * - ✅ 可维护：集中管理，易于扩展
 *
 * @author Automation Framework
 * @version 3.0
 */
@AutoBrowser(verbose = true)  // 启用详细日志
public class LogonGlue {

    private static final Logger logger = LoggerFactory.getLogger(LogonGlue.class);

    @Steps
    private LoginSteps loginSteps;

    /**
     * 登录步骤
     *
     * 浏览器已在AutoBrowserManagerGlue的@Before hook中自动设置好
     * 这里直接执行测试逻辑即可
     *
     * @param env 环境标识
     * @param username 用户名
     */
    @Given("logon DBB {string} environment as user {string}")
    public void logonDBBEnvironmentAsUserGlue(String env, String username) {
        // 打印当前浏览器类型用于调试
        String effectiveBrowser = BrowserOverrideManager.getEffectiveBrowserType();
        String defaultBrowser = BrowserOverrideManager.getDefaultBrowserType();
        logger.info("========================================");
        logger.info("LogonGlue - Scenario: {} as {}", env, username);
        logger.info("  Effective browser: {}", effectiveBrowser);
        logger.info("  Default browser: {}", defaultBrowser);
        logger.info("========================================");

        // 浏览器已自动设置好，直接执行登录即可
        // PlaywrightManager会自动检测是否需要切换浏览器
        loginSteps.logonDBBEnvironmentAsUser(env, username);
    }
}
