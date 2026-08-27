package com.hsbc.cmb.hk.dbb.automation.framework.api.core.services;

import com.hsbc.cmb.hk.dbb.automation.framework.api.core.step.BaseStep;
import com.hsbc.cmb.hk.dbb.automation.framework.api.core.step.BaseStepFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * TestServices provides centralized management for API test initialization with chainable calls.
 * <p>
 * This is the ONLY way to create BaseStep instances. Direct instantiation of BaseStep is not allowed.
 * <p>
 * Instance state is held in a {@link ThreadLocal} so parallel tests (e.g. TestNG parallel suites)
 * never overwrite each other's {@code entityName}/{@code env} selections.
 * <p>
 * Example usage:
 * {@code BaseStep baseStep = TestServices.initialize().baseStep();}
 * {@code BaseStep baseStep = TestServices.initialize().withEntity("petstore").baseStep();}
 * {@code BaseStep baseStep = TestServices.initialize().withEntity("petstore").withEnv("dev").baseStep();}
 * </p>
 */
public class TestServices {
    private static final Logger LOGGER = LoggerFactory.getLogger(TestServices.class);

    private static final ThreadLocal<TestServices> THREAD_INSTANCE = ThreadLocal.withInitial(TestServices::new);

    private final ThreadLocal<String> entityName = ThreadLocal.withInitial(() -> null);
    private final ThreadLocal<String> env = ThreadLocal.withInitial(() -> null);

    // Private constructor - thread-local singleton
    private TestServices() {}

    /**
     * Initialize TestServices instance for the current thread.
     * @return TestServices instance for chainable calls
     */
    public static TestServices initialize() {
        return THREAD_INSTANCE.get();
    }

    /**
     * Set the entity name to load configuration for (scoped to the current thread).
     * Configuration will be loaded from {entityName}.conf or {entityName}.properties
     * @param entityName name of the entity/configuration
     * @return this instance for chainable calls
     */
    public TestServices withEntity(String entityName) {
        this.entityName.set(entityName);
        return this;
    }

    /**
     * Set the environment for the entity (scoped to the current thread).
     * Environment-specific configuration will override base configuration
     * @param env environment name (e.g., "dev", "test", "prod")
     * @return this instance for chainable calls
     */
    public TestServices withEnv(String env) {
        this.env.set(env);
        return this;
    }

    /**
     * Create a BaseStep instance for the current thread's entity/env selection.
     * - If entityName is set, loads configuration from {entityName}.conf or {entityName}.properties
     * - If env is set, environment-specific configuration will be applied
     * - If entityName is not set, creates a BaseStep with null entity (for dynamic configuration)
     * <p>
     * Note: This is the ONLY way to create BaseStep instances. Direct instantiation is not allowed.
     *
     * @return BaseStep instance
     */
    public BaseStep baseStep() {
        String entity = this.entityName.get();
        String envVal = this.env.get();
        BaseStep baseStep;
        if (entity != null && !entity.trim().isEmpty()) {
            // Create BaseStep with configured entity
            LOGGER.info("Creating BaseStep with entity: {}, env: {}", entity, envVal);
            baseStep = BaseStepFactory.createWithEntity(entity.trim(), envVal);
        } else {
            // Create BaseStep with null entity (for dynamic configuration)
            LOGGER.info("Creating BaseStep with null entity (dynamic configuration)");
            baseStep = BaseStepFactory.createWithNullEntity();
        }
        return baseStep;
    }

    /**
     * Clear thread-local state. Should be invoked from test framework hooks
     * (e.g. TestNG {@code @AfterMethod} or ThreadPoolExecutor afterExecute)
     * to release ThreadLocal references and prevent leaks in long-lived pools.
     */
    public static void clear() {
        TestServices ts = THREAD_INSTANCE.get();
        if (ts != null) {
            ts.entityName.remove();
            ts.env.remove();
        }
        THREAD_INSTANCE.remove();
    }
}