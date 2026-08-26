package com.hsbc.cmb.hk.dbb.automation.framework.web.route.monitor;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.hsbc.cmb.hk.dbb.automation.framework.web.utils.LoggingConfigUtil;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;

/**
 * 按功能配置的 API 监控清单（JSON）。
 *
 * <p>JSON 结构（功能名 → api endpoint → 监控参数 + apiOwner）：
 * <pre>{@code
 * {
 *   "login": {
 *     "api/login":       { "timeout": 30, "autoStopMonitor": true,  "apiOwner": "a@x.com", "expectStatus": 200 },
 *     "api/auth/assert": { "timeout": 30, "autoStopMonitor": false, "apiOwner": "b@x.com", "expectStatus": 200 }
 *   },
 *   "transfer": {
 *     "api/transfer/**": { "timeout": 45, "autoStopMonitor": true,  "apiOwner": "c@x.com" }
 *   }
 * }
 * }</pre>
 *
 * <p>解析自 classpath/config 下的 {@code api-monitor-config.json}。
 */
public class ApiMonitorConfig {

    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger(ApiMonitorConfig.class);

    /** 功能名 → (endpoint pattern → 监控参数) */
    private volatile Map<String, Map<String, EndpointConfig>> features;

    /** 默认配置文件路径（classpath 或文件系统） */
    public static final String DEFAULT_CONFIG_PATH = "config/api-monitor-config.json";

    private static volatile ApiMonitorConfig INSTANCE;

    public Map<String, Map<String, EndpointConfig>> getFeatures() {
        Map<String, Map<String, EndpointConfig>> f = features;
        return f == null ? Collections.emptyMap() : f;
    }

    /** ⭐ P2: 加锁保护，避免运行时并发 set 导致的不一致（volatile 仅保证可见性，不保证原子发布）。 */
    public synchronized void setFeatures(Map<String, Map<String, EndpointConfig>> features) {
        this.features = features;
    }

    /**
     * 单例加载配置（懒加载，使用默认路径 {@link #DEFAULT_CONFIG_PATH}）。
     * 找不到文件返回空配置，不抛异常，便于无监控场景。
     */
    public static ApiMonitorConfig getInstance() {
        if (INSTANCE == null) {
            synchronized (ApiMonitorConfig.class) {
                if (INSTANCE == null) {
                    INSTANCE = load(DEFAULT_CONFIG_PATH);
                }
            }
        }
        return INSTANCE;
    }

    /**
     * 先加载指定的 JSON 监控清单文件，再使用 {@link #registerFeature} 按功能名注册。
     *
     * <p>加载顺序：文件系统（允许外部覆盖）→ classpath。
     *
     * @param configPath JSON 清单路径（如 {@code config/api-monitor-config.json}
     *                  或 {@code src/test/resources/config/api-monitor-config-uat.json}）
     * @return 已加载的配置实例（供链式调用 / 校验）
     */
    /**
     * 加载指定的 JSON 监控清单并设为单例实例。
     * 修复 P1-9：必须与 {@link #getInstance()} 共用同一把类锁，否则 {@code volatile INSTANCE}
     * 的"无锁覆盖"会破坏双检锁语义，导致并发线程拿到不一致（半初始化或旧）的 config 实例。
     */
    public static ApiMonitorConfig loadFrom(String configPath) {
        synchronized (ApiMonitorConfig.class) {
            INSTANCE = load(configPath);
            return INSTANCE;
        }
    }

    /** 用于测试或显式重载（与 loadFrom/getInstance 共用类锁，保证可见性一致） */
    public static void reset() {
        synchronized (ApiMonitorConfig.class) {
            INSTANCE = null;
        }
    }

    /**
     * 读取 JSON 清单。
     *
     * @param path 配置文件路径；为 null 或空时回退到 {@link #DEFAULT_CONFIG_PATH}
     */
    private static ApiMonitorConfig load(String path) {
        String configPath = (path == null || path.trim().isEmpty()) ? DEFAULT_CONFIG_PATH : path.trim();
        ApiMonitorConfig cfg = new ApiMonitorConfig();
        InputStream in = null;
        try {
            // 1) 先尝试文件系统（允许外部覆盖）
            java.nio.file.Path fsPath = java.nio.file.Paths.get(configPath);
            if (java.nio.file.Files.exists(fsPath)) {
                in = java.nio.file.Files.newInputStream(fsPath);
            } else {
                // 2) 再尝试 classpath
                ClassLoader cl = Thread.currentThread().getContextClassLoader();
                if (cl == null) {
                    cl = ApiMonitorConfig.class.getClassLoader();
                }
                in = cl.getResourceAsStream(configPath);
            }
            if (in == null) {
                LoggingConfigUtil.logInfoIfVerbose(LOGGER,
                        "[ApiMonitorConfig] 未找到 {}，跳过 API 监控清单加载", configPath);
                return cfg;
            }
            Gson gson = new GsonBuilder().create();
            ApiMonitorConfig parsed = gson.fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), ApiMonitorConfig.class);
            if (parsed != null && parsed.getFeatures() != null) {
                cfg.setFeatures(parsed.getFeatures());
            }
            LoggingConfigUtil.logInfoIfVerbose(LOGGER,
                    "[ApiMonitorConfig] 已加载 API 监控清单（{}），功能数={}", configPath, cfg.getFeatures().size());
            return cfg;
        } catch (IOException e) {
            LOGGER.warn("[ApiMonitorConfig] 加载 {} 失败：{}", configPath, e.getMessage());
            return cfg;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException ignored) {
                    // ignore
                }
            }
        }
    }

    /** 单个 endpoint 的监控配置 */
    public static class EndpointConfig {
        /** 监控超时秒数，0 表示永不超时 */
        private Integer timeout;
        /** 命中后是否自动停止监控 */
        private Boolean autoStopMonitor;
        /**
         * API owner 邮箱（失败时通知对象，单人）。
         * 一个人员可负责多个 endpoint——只需在多个 endpoint 下都配置同一邮箱即可，
         * 归集时按 owner 分组，最终一人一封汇总邮件。
         */
        private String apiOwner;
        /** 期望的 HTTP 状态码，不配置则不校验 */
        private Integer expectStatus;
        /** 可选描述 */
        private String description;

        public Integer getTimeout() {
            return timeout;
        }

        public Boolean getAutoStopMonitor() {
            return autoStopMonitor;
        }

        public String getApiOwner() {
            return apiOwner;
        }

        public Integer getExpectStatus() {
            return expectStatus;
        }

        public String getDescription() {
            return description;
        }
    }
}
