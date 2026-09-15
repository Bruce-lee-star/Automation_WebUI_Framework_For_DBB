package com.hsbc.cmb.hk.dbb.automation.tests.web;

import org.junit.platform.suite.api.ConfigurationParameter;
import org.junit.platform.suite.api.IncludeEngines;
import org.junit.platform.suite.api.SelectClasspathResource;
import org.junit.platform.suite.api.Suite;

/**
 * 并发登录运行器 —— 验证 SSO 感知并发闸门（ConcurrencyGate）+ 框架自建并发执行器（每线程独立 Browser）。
 *
 * <p><b>真正并发由框架自建，而非 Serenity 并行</b>：{@code serenity.parallel.for.tests} 在
 * {@code CucumberWithSerenity}（JUnit 4）中是历史空操作，多个 scenario 实际串行于 {@code main} 线程。
 * 本运行器仅匹配单个 scenario（{@code @concurrent-logon}），由<b>框架层</b>
 * {@code ConcurrentScenarioExecutor}（随 web 框架分发，业务层零并发代码）在编排线程
 * 把 6 个 (env, username) 登录构建为 6 个 {@code ContextTask} 并提交
 * {@link com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.concurrent.ConcurrentContextExecutor#runAll}，
 * <b>线程池真正并发</b>执行。每个 worker 线程拿到<b>各自独立的 Browser 实例</b>
 * （共享 Browser 模式已从框架移除，并发统一为每线程独立 Browser），各自持有隔离的 BrowserContext；相同 (env, username) 经
 * {@code ConcurrencyGate} 串行、不同身份并行。</p>
 *
 * <p><b>运行方式</b>（并发默认即每线程独立 Browser，无需共享 Browser 开关）：</p>
 * <pre>
 * # 1) 不同身份并行、相同身份并行（仅验证跨环境隔离，闸门默认关闭、零回归）
 * mvn -o -pl test-automation verify ^
 *   -Dit.test=CucumberConcurrentLogonRunnerIT ^
 *   -Dtags=@concurrent-logon
 *
 * # 2) 开启 SSO 互斥：相同身份串行，不同身份并行（R7/SSO 互踢防护）
 * mvn -o -pl test-automation verify ^
 *   -Dit.test=CucumberConcurrentLogonRunnerIT ^
 *   -Dtags=@concurrent-logon ^
 *   -Dserenity.playwright.concurrent.partition.enabled=true
 * </pre>
 *
 * <p>开启 {@code partition.enabled=true} 后，feature 中 4 个相同 (O63_SIT1, WP7UAT2_2) 任务被
 * {@link com.hsbc.cmb.hk.dbb.automation.framework.web.concurrent.ConcurrencyGate} 串行化，
 * 日志出现 {@code [concurrency-gate] identity ... serialized (blocked)}；其余不同身份仍并行。
 * 关闭时退化为纯并行隔离验证。</p>
 *
 * <p>本运行器仅匹配 {@code @concurrent-logon} 标签，不干扰默认主流程。</p>
 *
 * <p>JUnit 5 迁移（原 JUnit4 {@code @RunWith(CucumberWithSerenity.class) + @CucumberOptions}）：
 * 改用 JUnit Platform {@code @Suite} + {@code cucumber} 引擎。</p>
 */
@Suite
@IncludeEngines("cucumber")
@SelectClasspathResource("features/web/concurrent_logon_dbb.feature")
@ConfigurationParameter(key = "cucumber.glue",
        value = "com.hsbc.cmb.hk.dbb.automation.tests.glue,com.hsbc.cmb.hk.dbb.automation.framework.web.concurrent")
@ConfigurationParameter(key = "cucumber.filter.tags", value = "@concurrent-logon")
@ConfigurationParameter(key = "cucumber.plugin", value = "io.cucumber.core.plugin.SerenityReporterParallel")
public class CucumberConcurrentLogonRunnerIT {
}
