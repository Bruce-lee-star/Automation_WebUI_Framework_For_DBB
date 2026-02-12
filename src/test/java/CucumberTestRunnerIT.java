import io.cucumber.junit.CucumberOptions;
import net.serenitybdd.cucumber.CucumberWithSerenity;
import org.junit.runner.RunWith;

@RunWith(CucumberWithSerenity.class)
@CucumberOptions(
        features = "src/test/resources/features/web",
        glue = {
            "com.hsbc.cmb.hk.dbb.automation.tests.web.steps",
        },
        plugin = {
            "pretty",
            "html:target/cucumber-report.html",
            "json:target/cucumber-report.json",
            "rerun:target/rerun.txt"
        },
        monochrome = true,
        dryRun = false,
        tags = "@test11"
)
public class CucumberTestRunnerIT {


}
