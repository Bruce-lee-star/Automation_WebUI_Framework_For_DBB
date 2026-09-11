import org.junit.platform.suite.api.ConfigurationParameter;
import org.junit.platform.suite.api.IncludeEngines;
import org.junit.platform.suite.api.SelectClasspathResource;
import org.junit.platform.suite.api.Suite;

/**
 * Cucumber Test Runner (Serenity + JUnit 5 Platform)。
 *
 * <p>JUnit 5 迁移（原 JUnit4 {@code @RunWith(CucumberWithSerenity.class) + @CucumberOptions}）：
 * 改用 JUnit Platform {@code @Suite} + {@code cucumber} 引擎，默认运行 {@code @test1} 主流程。</p>
 */
@Suite
@IncludeEngines("cucumber")
@SelectClasspathResource("features")
@ConfigurationParameter(key = "cucumber.glue",
        value = "com.hsbc.cmb.hk.dbb.automation.tests.glue,com.hsbc.cmb.hk.dbb.automation.tests.api.steps")
@ConfigurationParameter(key = "cucumber.filter.tags", value = "@test1")
@ConfigurationParameter(key = "cucumber.plugin", value = "io.cucumber.core.plugin.SerenityReporterParallel")
public class CucumberTestRunnerIT {

}
