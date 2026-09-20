package com.hsbc.cmb.hk.dbb.automation.tests.web;

import org.junit.platform.suite.api.ConfigurationParameter;
import org.junit.platform.suite.api.IncludeEngines;
import org.junit.platform.suite.api.SelectClasspathResource;
import org.junit.platform.suite.api.Suite;

/**
 * 本地 route-demo 并行 GREEN 冒烟运行器 —— 验证 CON-1 引擎级并行（Cucumber fixed 策略）。
 *
 * <p>前置：route-demo-service 须在 http://localhost:8888 启动
 * （{@code mvn -f route-demo-service/pom.xml spring-boot:run}）。</p>
 *
 * <p>运行（梯度 2→4→8 由 -Dparallelism 控制）：</p>
 * <pre>
 * mvn -o -pl test-automation -am verify -Pparallel ^
 *   -Dit.test=CucumberRouteDemoParallelSmokeRunnerIT ^
 *   -Dcucumber.filter.tags=@route-parallel-smoke ^
 *   -Dparallelism=8 -DmaxPoolSize=8 ^
 *   "-Dcve.gate.skip=true" "-Dcheckstyle.skip=true" "-Dspotbugs.skip=true"
 * </pre>
 *
 * <p><b>浏览器关闭时机（零配置默认行为）</b>：框架自 WEB-F1 起，Browser 跟随 Context 边界，
 * 每个 scenario 收尾即关闭本线程 Browser（下一 scenario 首次 {@code getBrowser()} 懒重建），
 * 不再保留到套件级 {@code cleanupAll()} 才统一关闭——避免 headed 长跑时浏览器窗口堆积。
 * 无需任何开关；自定义并发执行器（SSO 分区）模式自动跳过此关闭。</p>
 *
 * <p>本运行器仅匹配 {@code @route-parallel-smoke} 标签，复用 {@code RouteDemoServiceGlue}
 * 的 monitor / mock 步骤（均只读后端 / 拦截式 MOCK，per-Context 隔离），不写后端数据，
 * 故可安全并发，作为 nightly/CI 梯度压测（4→8→16）的本地代理验证，绕过内网限制。</p>
 */
@Suite
@IncludeEngines("cucumber")
@SelectClasspathResource("features/web/route_demo_parallel_smoke.feature")
@ConfigurationParameter(key = "cucumber.glue",
        value = "com.hsbc.cmb.hk.dbb.automation.tests.glue.parallel")
@ConfigurationParameter(key = "cucumber.filter.tags", value = "@route-parallel-smoke")
@ConfigurationParameter(key = "cucumber.plugin", value = "io.cucumber.core.plugin.SerenityReporterParallel")
public class CucumberRouteDemoParallelSmokeRunnerIT {

}
