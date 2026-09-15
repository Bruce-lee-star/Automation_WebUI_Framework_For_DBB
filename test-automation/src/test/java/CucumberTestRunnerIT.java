import org.junit.platform.suite.api.ConfigurationParameter;
import org.junit.platform.suite.api.IncludeEngines;
import org.junit.platform.suite.api.SelectClasspathResource;
import org.junit.platform.suite.api.Suite;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Cucumber Test Runner (Serenity + JUnit 5 Platform)。
 *
 * <p>JUnit 5 迁移（原 JUnit4 {@code @RunWith(CucumberWithSerenity.class) + @CucumberOptions}）：
 * 改用 JUnit Platform {@code @Suite} + {@code cucumber} 引擎。</p>
 *
 * <p><b>标签单一事实来源（B-2）：</b>本 Runner <b>不</b>写死 {@code cucumber.filter.tags}，
 * 完全由 failsafe 的 {@code -Dcucumber.filter.tags=${tags}} 驱动（默认 {@code not @skip}）。
 * 这样 {@code -Dtags=...} 在任何环境都确定性生效，避免 Runner 硬编码与系统属性双源冲突。</p>
 */
@Suite
@IncludeEngines("cucumber")
@SelectClasspathResource("features")
@ConfigurationParameter(key = "cucumber.glue",
        value = "com.hsbc.cmb.hk.dbb.automation.tests.glue,com.hsbc.cmb.hk.dbb.automation.tests.api.steps")
@ConfigurationParameter(key = "cucumber.plugin", value = "io.cucumber.core.plugin.SerenityReporterParallel")
public class CucumberTestRunnerIT {

    private static final Logger LOGGER = LoggerFactory.getLogger(CucumberTestRunnerIT.class);

    /**
     * 启动自检（B-2）：标签必须由 failsafe 的 {@code -Dcucumber.filter.tags=${tags}} 单一来源注入。
     * 若运行环境未提供该属性（如 IDE 直接跑 @Suite 而忘了 -Dtags），Cucumber 会静默运行全部 feature，
     * 此处显式告警，避免"默认只跑 1 场景"的反面——"静默全跑"——在无人察觉时拖垮构建。
     */
    static {
        String tags = System.getProperty("cucumber.filter.tags");
        if (tags == null || tags.isBlank()) {
            LOGGER.warn("No -Dcucumber.filter.tags detected: running all features (unexpected default). "
                    + "Specify tag filtering explicitly via failsafe -Dtags=... or the <tags> property in the root/test-automation pom.");
        } else {
            LOGGER.info("Cucumber tag filter (single source of truth): cucumber.filter.tags={}", tags);
        }
    }
}
