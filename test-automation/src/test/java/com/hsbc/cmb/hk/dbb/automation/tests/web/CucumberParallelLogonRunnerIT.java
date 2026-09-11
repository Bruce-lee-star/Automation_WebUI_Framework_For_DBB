package com.hsbc.cmb.hk.dbb.automation.tests.web;

import org.junit.platform.suite.api.ConfigurationParameter;
import org.junit.platform.suite.api.IncludeEngines;
import org.junit.platform.suite.api.SelectClasspathResource;
import org.junit.platform.suite.api.Suite;

/**
 * 并行浏览器隔离运行器 —— 参照 logon DBB，验证 T3-2「每线程独立 Browser」。
 *
 * <p><b>运行方式（关键：开启 Serenity 场景级并行）</b></p>
 * <pre>
 * mvn -o -pl test-automation -am verify ^
 *   -Dit.test=CucumberParallelLogonRunnerIT ^
 *   -Dtags=@parallel-logon ^
 *   -Dserenity.parallel.for.tests=4 ^
 *   -Dserenity.playwright.restart.browser.for.each=scenario
 * </pre>
 *
 * <p>{@code serenity.parallel.for.tests=4} 让 4 个 scenario 在 4 个线程并发执行，
 * 各线程经 T3-2 的 {@code threadId:configId} 键持有独立 Browser；
 * {@code restart.browser.for.each=scenario} 确保每个 scenario 用全新浏览器，避免 feature 级复用与并行交错。</p>
 *
 * <p>本运行器仅匹配 {@code @parallel-logon} 标签，不会干扰默认的 {@code @test1} 主流程。</p>
 *
 * <p>JUnit 5 迁移（原 JUnit4 {@code @RunWith(CucumberWithSerenity.class) + @CucumberOptions}）：
 * 改用 JUnit Platform {@code @Suite} + {@code cucumber} 引擎。</p>
 */
@Suite
@IncludeEngines("cucumber")
@SelectClasspathResource("features/web/parallel_logon_dbb.feature")
@ConfigurationParameter(key = "cucumber.glue", value = "com.hsbc.cmb.hk.dbb.automation.tests.glue")
@ConfigurationParameter(key = "cucumber.filter.tags", value = "@parallel-logon")
@ConfigurationParameter(key = "cucumber.plugin", value = "io.cucumber.core.plugin.SerenityReporterParallel")
public class CucumberParallelLogonRunnerIT {

}
