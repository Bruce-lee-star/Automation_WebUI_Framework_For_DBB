package com.hsbc.cmb.hk.dbb.automation.tests.web;

import io.cucumber.junit.CucumberOptions;
import net.serenitybdd.cucumber.CucumberWithSerenity;
import org.junit.runner.RunWith;

/**
 * E2E 真实浏览器沙箱运行器。
 *
 * <p>与外部依赖解耦：页面由 {@code setContent} 注入本地 HTML，无需外网 / DBB 环境 / REST，
 * 因此本运行器可随时作为「真实浏览器 E2E 是否健康」的快速验证入口。</p>
 *
 * <p><b>运行方式</b></p>
 * <pre>
 * mvn -o -pl test-automation -am verify -Dit.test=CucumberE2ESandboxRunnerIT -Dtags=@e2e-sandbox
 * </pre>
 *
 * <p><b>并行隔离验证</b>（5 个 scenario 并发，验证各线程浏览器上下文互不污染）：</p>
 * <pre>
 * mvn -o -pl test-automation -am verify -Dit.test=CucumberE2ESandboxRunnerIT -Dtags=@e2e-sandbox \
 *   -Dserenity.parallel.for.tests=4 -Dserenity.playwright.restart.browser.for.each=scenario
 * </pre>
 *
 * <p>仅匹配 {@code @e2e-sandbox} 标签，不会干扰默认的 {@code @test1} 主流程。</p>
 */
@RunWith(CucumberWithSerenity.class)
@CucumberOptions(
        features = "src/test/resources/features/web/e2e_sandbox.feature",
        glue = {
                "com.hsbc.cmb.hk.dbb.automation.tests.glue"
        },
        plugin = {
                "pretty",
                "html:target/e2e-sandbox-cucumber-report.html",
                "json:target/e2e-sandbox-cucumber-report.json"
        },
        tags = "@e2e-sandbox",
        dryRun = false
)
@SuppressWarnings("deprecation")
public class CucumberE2ESandboxRunnerIT {

}
