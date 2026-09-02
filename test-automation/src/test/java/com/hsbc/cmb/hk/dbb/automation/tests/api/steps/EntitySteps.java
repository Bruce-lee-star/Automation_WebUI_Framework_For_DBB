package com.hsbc.cmb.hk.dbb.automation.tests.api.steps;

import com.hsbc.cmb.hk.dbb.automation.framework.api.core.step.BaseStep;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.When;
import net.serenitybdd.core.steps.UIInteractionSteps;

/**
 * Entity Initialization Steps
 * Handles entity building and initialization
 *
 * <p>⭐ 修复 P1-14：本类原先使用 {@code @Autowired private BaseStep baseStep;} ——
 * 项目没有 Spring 容器，该字段恒为 null；且本类原本还会给该字段<b>重新赋值</b>，
 * 即便注入成功，重新赋值也只会作用于自己这一个实例，其它 step 类依旧拿不到。
 *
 * <p>现改由 {@link ApiTestContext} 提供 scenario 级共享：
 * <b>本类负责创建（init），其余 step 类负责取用（baseStep()）</b>，
 * 语义等价于原先期望的 Spring 单例，且不引入任何新依赖。
 */
public class EntitySteps extends UIInteractionSteps {

    @Given("an entity")
    public void buildEntity() {
        ApiTestContext.init();
    }

    @Given("an entity with {string}")
    public void buildEntity(String entityName) {
        ApiTestContext.init(entityName);
    }

    @Given("an entity {string} as env {string}")
    public void buildEntity(String entityName, String env) {
        ApiTestContext.init(entityName, env);
    }

    @Given("endpoint {string} with {string} method")
    public void loadEndpointConfig(String endpointName, String method) {
        ApiTestContext.baseStep().loadEndpointConfig(endpointName, method);
    }

    @When("I send {string} request to {string} endpoint")
    public void sendRequestWithEndpoint(String method, String endpointName) {
        BaseStep baseStep = ApiTestContext.baseStep();

        // Load endpoint configuration
        boolean loaded = baseStep.loadEndpointConfig(endpointName, method);
        if (!loaded) {
            throw new RuntimeException("Failed to load endpoint configuration: " + method + " " + endpointName);
        }

        // Send request based on method
        String upperMethod = method.toUpperCase();
        switch (upperMethod) {
            case "GET":
                baseStep.getResource();
                break;
            case "POST":
                baseStep.postPayload();
                break;
            case "PUT":
                baseStep.putPayload();
                break;
            case "PATCH":
                baseStep.patchPayload();
                break;
            case "DELETE":
                baseStep.deleteResource();
                break;
            default:
                throw new RuntimeException("Unsupported HTTP method: " + method);
        }
    }
}
