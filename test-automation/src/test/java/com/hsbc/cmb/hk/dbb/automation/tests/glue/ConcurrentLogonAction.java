package com.hsbc.cmb.hk.dbb.automation.tests.glue;

import com.hsbc.cmb.hk.dbb.automation.framework.web.concurrent.ConcurrentCaseAction;
import com.hsbc.cmb.hk.dbb.automation.framework.web.concurrent.ConcurrentCaseData;
import com.hsbc.cmb.hk.dbb.automation.tests.steps.LoginSteps;

/**
 * 并发登录领域动作（<b>业务提供、框架驱动</b>）。
 *
 * <p>这是业务向框架暴露的<b>唯一并发领域边界</b>：只描述"做什么"（按 env/username 强制真实登录），
 * 不含任何并发代码——线程池、单 Browser 多 Context、SSO 闸门、失败回放全部由框架
 * {@link com.hsbc.cmb.hk.dbb.automation.framework.web.concurrent.ConcurrentScenarioExecutor} 拥有。</p>
 *
 * <p>经 {@code META-INF/services} SPI 自动发现（见同模块 resources 下对应服务文件），无需任何注册代码；
 * 框架并发 glue 在编排线程经 {@link com.hsbc.cmb.hk.dbb.automation.framework.web.concurrent.ConcurrentCaseActions#require()}
 * 取得本动作。</p>
 */
public class ConcurrentLogonAction implements ConcurrentCaseAction {

    @Override
    public void execute(ConcurrentCaseData caseData) throws Exception {
        String env = caseData.get("env");
        String username = caseData.get("username");
        new LoginSteps().concurrentLogin(env, username);
    }
}
