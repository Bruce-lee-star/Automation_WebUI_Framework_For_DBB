package com.hsbc.cmb.hk.dbb.automation.tests.web;

import org.junit.platform.suite.api.ConfigurationParameter;
import org.junit.platform.suite.api.IncludeEngines;
import org.junit.platform.suite.api.SelectClasspathResource;
import org.junit.platform.suite.api.Suite;

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
 *
 * <p>JUnit 5 迁移（原 JUnit4 {@code @RunWith(CucumberWithSerenity.class) + @CucumberOptions}）：
 * 改用 JUnit Platform {@code @Suite} + {@code cucumber} 引擎。</p>
 */
@Suite
@IncludeEngines("cucumber")
@SelectClasspathResource("features/web/e2e_sandbox.feature")
@ConfigurationParameter(key = "cucumber.glue", value = "com.hsbc.cmb.hk.dbb.automation.tests.glue")
@ConfigurationParameter(key = "cucumber.filter.tags", value = "@e2e-sandbox")
@ConfigurationParameter(key = "cucumber.plugin", value = "io.cucumber.core.plugin.SerenityReporterParallel")
public class CucumberE2ESandboxRunnerIT {

}
