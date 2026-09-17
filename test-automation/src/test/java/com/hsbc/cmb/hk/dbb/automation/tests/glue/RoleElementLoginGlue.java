package com.hsbc.cmb.hk.dbb.automation.tests.glue;

import com.hsbc.cmb.hk.dbb.automation.tests.RoleElementLoginSteps;
import io.cucumber.java.en.Given;

/**
 * Glue 步骤：以 {@code @RoleElement} 标注的 {@link com.hsbc.cmb.hk.dbb.automation.tests.LoginPage}
 * 驱动登录，用于验证 RoleElement 页面对象机制在运行时能否正常定位并操作元素。
 *
 * <p>与框架 {@code LogonGlue}（使用 {@code @Element} 风格的 {@code pages.LoginPage}）区分，
 * 本 Glue 专跑 RoleElement 这条路径。
 *
 * <p>B-3：协作的步骤类已由 {@code tests.LoginSteps} 更名为 {@code tests.RoleElementLoginSteps}，
 * 消除与 {@code tests.steps.LoginSteps} 的同名同类隐患（行为零变更）。
 */
public class RoleElementLoginGlue {

    private final RoleElementLoginSteps loginSteps = new RoleElementLoginSteps();

    @Given("I logon DBB {string} environment as user {string} using the RoleElement page")
    public void logonUsingRoleElementPage(String env, String username) {
        loginSteps.logon(env, username);
    }
}
