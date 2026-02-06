import io.cucumber.junit.CucumberOptions;
import net.serenitybdd.cucumber.CucumberWithSerenity;
import org.junit.runner.RunWith;

@RunWith(CucumberWithSerenity.class)
@CucumberOptions(
        features = "src/test/resources/features",
        glue = {
            "com.hsbc.cmb.dbb.hk.automation.tests.glue"
        },
        plugin = {
            "pretty",
            "html:target/cucumber-report.html",
            "json:target/cucumber-report.json",
            "rerun:target/rerun.txt"
        },
        monochrome = true,
        dryRun = false,
        tags = "@test1"
)
public class CucumberTestRunnerIT {


}
