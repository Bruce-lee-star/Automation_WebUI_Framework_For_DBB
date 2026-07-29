package com.hsbc.cmb.hk.dbb.automation.framework.api.playwright.core.services;

import com.hsbc.cmb.hk.dbb.automation.framework.api.core.entity.Entity;
import com.hsbc.cmb.hk.dbb.automation.framework.api.core.entity.EntityBuilder;
import com.hsbc.cmb.hk.dbb.automation.framework.api.playwright.client.ApiContextScope;
import com.hsbc.cmb.hk.dbb.automation.framework.api.playwright.core.entity.ApiRequestEntity;
import com.hsbc.cmb.hk.dbb.automation.framework.api.playwright.core.step.PlaywrightApiStep;
import com.hsbc.cmb.hk.dbb.automation.framework.api.playwright.core.step.PlaywrightApiStepFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * PlaywrightApiTestServices - Playwright 接口测试的统一入口（链式调用）
 * <p>
 * 镜像 RestAssured 版 {@code TestServices}，是创建 {@link PlaywrightApiStep} 的<b>唯一</b>入口
 * （直接实例化 PlaywrightApiStep 不被允许，其构造器为包级）。
 * <p>
 * 设计要点：
 * <ul>
 *   <li>该入口负责在创建步骤前预初始化共享的 {@link PlaywrightApiClientManager#getContext()}，
 *       以保证 API 请求上下文（复用/独立 Playwright）已就绪。</li>
 *   <li>{@link #withEntity(String)} 仅设置实体名（用于后续端点配置按实体加载），
 *       不在此处提前加载完整配置，保持与动态配置场景一致。</li>
 *   <li>纯接口场景（未复用共享 Playwright 实例）务必在 {@code @AfterScenario} 调用 {@link #cleanup()}，
 *       以释放 APIRequestContext 及自建的 Playwright 实例，避免 Node 进程泄漏。</li>
 * </ul>
 * <p>
 * 示例：
 * {@code PlaywrightApiStep api = PlaywrightApiTestServices.initialize().withBaseUri("https://api.example.com").baseStep();}
 * {@code PlaywrightApiStep api = PlaywrightApiTestServices.initialize().withEntity("petstore").baseStep();}
 * {@code // 纯接口场景收尾：PlaywrightApiTestServices.cleanup();}
 */
public class PlaywrightApiTestServices {

    private static final Logger LOGGER = LoggerFactory.getLogger(PlaywrightApiTestServices.class);

    private String entityName; // 待加载的实体/配置名
    private String env;        // 环境名（dev/test/prod）
    private String baseUri;    // 可选：直接指定 baseUri，覆盖默认配置

    // 私有构造：仅允许通过 initialize() 创建
    private PlaywrightApiTestServices() {
    }

    /**
     * 初始化入口实例（无状态：每次返回新实例，避免并行场景共享可变配置字段）。
     *
     * @return 入口实例（用于链式调用）
     */
    public static PlaywrightApiTestServices initialize() {
        return new PlaywrightApiTestServices();
    }

    /**
     * 设置实体名。实体配置将从 {entityName}.conf / {entityName}.properties 加载。
     *
     * @param entityName 实体/配置名
     * @return 本入口实例（用于链式调用）
     */
    public PlaywrightApiTestServices withEntity(String entityName) {
        this.entityName = entityName;
        return this;
    }

    /**
     * 设置环境名。环境专属配置会覆盖基础配置。
     * <p>
     * 机制与 RestAssured 版 {@code EntityBuilder.build(name, env)} 对齐：
     * 通过把 {@code env} 透传给 {@code EntityBuilder.build(name, env)} 加载环境专属配置
     * （如 dev/test/prod 的不同 baseUri、header）。<b>不写入任何 JVM 系统属性</b>，
     * 以免破坏多环境并行场景（每个入口实例自带 env 字段，调用 {@link #baseStep()} 时消费）。
     *
     * @param env 环境名（如 dev/test/prod）
     * @return 本入口实例（用于链式调用）
     */
    public PlaywrightApiTestServices withEnv(String env) {
        if (env != null && !env.trim().isEmpty()) {
            this.env = env.trim();
            LOGGER.info("API environment set to: {}", this.env);
        }
        return this;
    }

    /**
     * 直接指定 baseUri，覆盖默认配置。
     *
     * @param baseUri 基础 URI
     * @return 本入口实例（用于链式调用）
     */
    public PlaywrightApiTestServices withBaseUri(String baseUri) {
        this.baseUri = baseUri;
        return this;
    }

    /**
     * 创建 {@link PlaywrightApiStep} 实例。
     * <ul>
     *   <li>若设置了 entityName，则将其写入实体；否则为 null 实体（动态配置）。</li>
     *   <li>若设置了 baseUri，则覆盖默认 baseUri。</li>
     *   <li>创建前确保共享的 API 请求上下文已初始化。</li>
     * </ul>
     * 这是创建 PlaywrightApiStep 的<b>唯一</b>方式。
     *
     * @return PlaywrightApiStep 实例
     */
    public PlaywrightApiStep baseStep() {
        ApiRequestEntity entity = new ApiRequestEntity();

        if (entityName != null && !entityName.trim().isEmpty()) {
            LOGGER.info("Creating PlaywrightApiStep with entity: {}, env: {}", entityName, env);
            entity.setEntityName(entityName.trim());
            // 与 RestAssured 版 EntityBuilder.build(name, env) 对称：一次性加载 baseUri/basePath/默认头
            Entity cfg = EntityBuilder.build(entityName.trim(), env);
            if (cfg.getBaseUri() != null) {
                entity.setBaseUri(cfg.getBaseUri());
            }
            if (cfg.getBasePath() != null) {
                entity.setBasePath(cfg.getBasePath());
            }
            cfg.getRequestHeaders().forEach(entity::addRequestHeader);
        } else {
            LOGGER.info("Creating PlaywrightApiStep with null entity (dynamic configuration)");
        }

        if (baseUri != null && !baseUri.trim().isEmpty()) {
            entity.setBaseUri(baseUri.trim());
        }

        // 绑定当前线程（= 场景线程）的作用域，交由 PlaywrightApiStep 在 close() 时显式释放，
        // 即使 @After 钩子在另一线程执行也能精准处置该作用域（跨线程安全）。
        // 注意：不再此处急切创建 APIRequestContext —— execute() 首次发请求时才会懒创建。
        ApiContextScope scope = ApiContextScope.current();

        // 消费配置后立即重置，避免配置在多个场景/步骤间串场（单例状态泄漏）
        this.entityName = null;
        this.env = null;
        this.baseUri = null;

        return PlaywrightApiStepFactory.createWithEntity(entity, scope);
    }

    /**
     * 释放 Playwright 接口框架持有的资源（APIRequestContext + 可能自建的独立 Playwright 实例）。
     * <p>
     * 建议在 {@code @AfterScenario} / {@code @After} 钩子中调用，避免纯接口场景下 Node 进程泄漏。
     * 复用共享 Playwright 实例时，不会关闭共享实例（由框架统一管理）。
     */
    public static void cleanup() {
        LOGGER.info("Cleaning up Playwright API resources (current scenario scope).");
        ApiContextScope.disposeCurrent();
    }
}
