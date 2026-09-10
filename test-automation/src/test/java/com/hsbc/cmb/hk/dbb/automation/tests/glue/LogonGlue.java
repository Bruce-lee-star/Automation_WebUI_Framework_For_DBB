package com.hsbc.cmb.hk.dbb.automation.tests.glue;

import com.hsbc.cmb.hk.dbb.automation.framework.core.context.ContextKey;
import com.hsbc.cmb.hk.dbb.automation.framework.core.context.TestContextHolder;
import com.hsbc.cmb.hk.dbb.automation.framework.web.annotations.AutoBrowser;
import com.hsbc.cmb.hk.dbb.automation.framework.web.concurrent.ConcurrencyGate;
import com.hsbc.cmb.hk.dbb.automation.framework.web.concurrent.ConcurrencyPartitionKey;
import com.hsbc.cmb.hk.dbb.automation.framework.web.config.BrowserOverrideManager;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.PlaywrightManager;
import com.hsbc.cmb.hk.dbb.automation.framework.web.session.SessionManager;
import com.hsbc.cmb.hk.dbb.automation.tests.steps.LoginSteps;
import io.cucumber.java.After;
import io.cucumber.java.Scenario;
import io.cucumber.java.en.Given;
import net.serenitybdd.annotations.Steps;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * Logon Glue Code - 登录相关的步骤定义
 *
 * <p>企业级解决方案：添加 @AutoBrowser 注解，浏览器自动管理。</p>
 *
 * <p>SSO 并发闸门接入点（9.10-⑤）：本步骤是「建立登录」的边界，故在此接入
 * {@link ConcurrencyGate} —— 相同 (environment, username) 的并行 scenario 被互斥串行，
 * 不同身份并行。总开关 {@code serenity.playwright.concurrent.partition.enabled} 默认 {@code false}
 * 时 {@code acquire/release} 全为 no-op，对既有 {@code login_dbb} / {@code parallel_logon_dbb} 流程零回归。</p>
 *
 * @author Automation Framework
 * @version 3.1
 */
@AutoBrowser(verbose = true)
public class LogonGlue {

    private static final Logger LOGGER = LoggerFactory.getLogger(LogonGlue.class);

    /**
     * 当前 scenario 持有的并发分区键（登录成功后写入，scenario 结束时释放）。
     * 存于 per-thread 的 {@link TestContextHolder}，并发下各线程互不串扰。
     */
    private static final ContextKey<ConcurrencyPartitionKey> CONCURRENCY_GATE_KEY =
            ContextKey.of("logonGlue.concurrencyPartitionKey", ConcurrencyPartitionKey.class);

    @Steps
    private LoginSteps loginSteps;

    /**
     * 登录步骤（SSO 并发闸门接入点）。
     *
     * <p>登录前按 (environment, username) 计算分区键并 {@code acquire}：相同身份在并行 scenario 间被串行化
     * （防止 SSO 会话互踢）；不同身份立即放行、并行执行。登录成功后把 key 存入本线程上下文，
     * 由 {@link #releaseConcurrencyGate(Scenario)} 在 scenario 结束时配对释放。</p>
     *
     * @param env      环境标识，如 "O63_SIT1"
     * @param username 用户名，如 "WP7UAT2_2"
     */
    @Given("logon DBB {string} environment as user {string}")
    public void logonDBBEnvironmentAsUserGlue(String env, String username) {
        LOGGER.info("========================================");
        LOGGER.info("LogonGlue - Scenario: {} as {}", env, username);
        LOGGER.info("  Effective browser: {}", BrowserOverrideManager.getEffectiveBrowserType());
        LOGGER.info("  Default browser: {}", BrowserOverrideManager.getDefaultBrowserType());
        LOGGER.info("========================================");

        // SSO 感知并发分区互斥：相同 (environment,username) 串行，不同身份并行。
        // 闸门关闭（默认）时 acquire/release 为 no-op，行为与未接入一致。
        ConcurrencyPartitionKey key = ConcurrencyPartitionKey.of(Map.of("environment", env, "username", username));
        ConcurrencyGate.acquire(key);
        boolean held = false;
        try {
            loginSteps.logonDBBEnvironmentAsUser(env, username);
            TestContextHolder.get().set(CONCURRENCY_GATE_KEY, key);
            held = true;
        } finally {
            // 登录失败（held=false）立即释放，避免泄漏信号量；成功则由 @After 在 scenario 末释放。
            if (!held) {
                ConcurrencyGate.release(key);
            }
        }
    }

    /**
     * scenario 结束时释放本线程持有的并发闸门（仅当登录成功且仍持有）。
     * 并发下各 worker 线程经 per-thread {@link TestContextHolder} 取到各自 key，互不串扰。
     */
    @After
    public void releaseConcurrencyGate(Scenario scenario) {
        ConcurrencyPartitionKey key = TestContextHolder.get().get(CONCURRENCY_GATE_KEY);
        if (key != null) {
            ConcurrencyGate.release(key);
            TestContextHolder.get().remove(CONCURRENCY_GATE_KEY);
        }
    }
}
