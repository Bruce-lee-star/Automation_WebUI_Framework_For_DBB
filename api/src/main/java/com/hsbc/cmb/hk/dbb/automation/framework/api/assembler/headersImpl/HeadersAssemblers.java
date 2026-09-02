package com.hsbc.cmb.hk.dbb.automation.framework.api.assembler.headersImpl; // 可根据项目包结构调整

import com.hsbc.cmb.hk.dbb.automation.framework.api.config.ConfigProvider;
import com.hsbc.cmb.hk.dbb.automation.framework.api.domain.enums.ConfigKeys;
import com.hsbc.cmb.hk.dbb.automation.framework.api.core.entity.Entity;
import com.typesafe.config.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * Assembler for building and validating request headers from configuration
 * 专门处理Headers的构建、校验、配置读取逻辑
 */
public class HeadersAssemblers {
    private static final Logger log = LoggerFactory.getLogger(HeadersAssemblers.class);
    private static final String HEADERS_KEY = ConfigKeys.HEADERS.toString();

    // 私有化构造函数，避免实例化
    private HeadersAssemblers() {}

    /**
     * Build headers map from configuration
     * @param config 配置对象
     * @return 构建后的headers map（空map而非null，避免NPE）
     */
    public static Map<String, Object> buildHeadersFromConfig(Config config) {
        // 初始化空map，避免返回null
        Map<String, Object> headersMap = new HashMap<>();

        // 1. 配置为空校验
        if (config == null || config.isEmpty()) {
            log.error("Configuration is empty, failed to build headers");
            return headersMap;
        }

        // 2. 检查headers节点是否存在
        if (!isBuildHeaders()) {
            log.warn("{} node not found in configuration, headers will be empty", HEADERS_KEY);
            return headersMap;
        }

        // 3. 读取并构建headers
        try {
            Config headersConfig = config.getConfig(HEADERS_KEY);
            for (String key : headersConfig.root().keySet()) {
                headersMap.put(key, headersConfig.getAnyRef(key));
            }
            log.info("Headers constructed successfully, total key-value pairs: {} | headers: {}",
                    headersMap.size(), headersMap);
        } catch (Exception e) {
            log.error("Failed to parse headers from configuration", e);
            // 异常时返回空map，避免上游NPE
            headersMap.clear();
        }

        return headersMap;
    }

    /**
     * 兼容原有逻辑：通过Entity获取配置并构建headers
     * @param entity 实体对象
     * @return 构建后的headers map
     */
    public static Map<String, Object> buildHeadersFromEntity(Entity entity) {
        if (entity == null) {
            log.error("Entity object is null, failed to build headers", new IllegalArgumentException());
            return new HashMap<>();
        }

        // 从ConfigProvider获取全局配置
        Config config = ConfigProvider.getConfig();
        return buildHeadersFromConfig(config);
    }

    /**
     * Check if headers node exists in configuration
     * @return true: 存在headers节点，false: 不存在
     */
    public static boolean isBuildHeaders() {
        Config config = ConfigProvider.getConfig();
        boolean hasHeaders = config != null && config.hasPath(HEADERS_KEY);
        log.info("Headers build required: {} ({} node exists in configuration: {})",
                hasHeaders, HEADERS_KEY, hasHeaders);
        return hasHeaders;
    }
}