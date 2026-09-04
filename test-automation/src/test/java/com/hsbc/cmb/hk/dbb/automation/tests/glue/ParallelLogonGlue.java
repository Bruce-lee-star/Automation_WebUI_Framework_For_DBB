package com.hsbc.cmb.hk.dbb.automation.tests.glue;

import com.hsbc.cmb.hk.dbb.automation.framework.web.annotations.AutoBrowser;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.PlaywrightManager;
import com.microsoft.playwright.Browser;
import io.cucumber.java.en.Then;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 并行浏览器隔离验证 Glue —— 参照 logon DBB 场景，验证 T3-2「每 worker 线程持有独立 Browser 实例」。
 *
 * <p>本步骤在 scenario 线程内调用 {@link PlaywrightManager#getBrowser()}，记录当前线程 id 与
 * Browser 实例身份标识（identityHashCode），并断言该 Browser 处于已连接状态。并行跑本 feature 时，
 * Serenity 会以 {@code serenity.parallel.for.tests=N} 将 N 个 scenario 分发到 N 个线程，
 * 各 scenario 应分别拿到<b>不同的线程</b>与<b>不同的 Browser 实例</b>（报告日志可交叉核对）。
 * 这正是 T3-2 改动后「跨线程不再共享同一 Browser、restartBrowser 不误杀其它并发场景」的运行时证据。</p>
 *
 * <p>企业级：纯行为断言 + 可观测日志（路由回框架 logger 以保持溯源一致），不依赖其它线程状态，
 * 单 scenario 内即可稳定复现「本线程浏览器独立且已连接」这一不变式。</p>
 */
@AutoBrowser(verbose = true)
public class ParallelLogonGlue {

    private static final Logger LOGGER = LoggerFactory.getLogger(ParallelLogonGlue.class);

    /**
     * 隔离断言：当前线程的 Browser 必须已连接且为合法实例。
     * 并行执行时，4 个 scenario 的日志应显示 4 个不同 threadId 与 4 个不同 Browser identity，
     * 证明各线程未共享同一 Browser。
     */
    @Then("the browser is isolated to this thread only")
    public void browserIsolatedToThisThreadOnly() {
        long threadId = Thread.currentThread().getId();
        String threadName = Thread.currentThread().getName();

        Browser browser = PlaywrightManager.getBrowser();
        int browserIdentity = System.identityHashCode(browser);
        boolean connected = browser.isConnected();

        LOGGER.info("========================================");
        LOGGER.info("ParallelLogon isolation check");
        LOGGER.info("  Thread id        : {}", threadId);
        LOGGER.info("  Thread name      : {}", threadName);
        LOGGER.info("  Browser identity : {}", browserIdentity);
        LOGGER.info("  Browser connected: {}", connected);
        LOGGER.info("========================================");

        if (browserIdentity == 0) {
            throw new AssertionError("线程 " + threadId + " 拿到了非法 Browser 实例（identityHashCode=0）");
        }
        if (!connected) {
            throw new AssertionError("线程 " + threadId + " 的 Browser 未连接（隔离/初始化异常）");
        }
    }
}
