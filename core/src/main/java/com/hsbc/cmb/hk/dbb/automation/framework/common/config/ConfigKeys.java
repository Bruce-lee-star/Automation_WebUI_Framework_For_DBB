package com.hsbc.cmb.hk.dbb.automation.framework.common.config;

import java.util.ArrayList;
import java.util.List;

/**
 * 统一配置键注册表骨架（评审08类-7 / C-6 / M-2）。
 *
 * <p>现状 {@code WebFrameworkConfig} / {@code ApiFrameworkConfig} / {@code MonitorConfig} 三枚举平行共存，
 * 新增配置键无单一事实来源、无重复/命名校验。本枚举汇总全框架配置键元数据
 * （key / defaultValue / description），供审计、文档生成、一致性校验复用。
 *
 * <p><b>接线策略</b>：本类为骨架，<b>当前不替换</b>三枚举（避免破坏现有读取路径）。
 * 后续批次将三枚举退化为"经本注册表读取的门面"，并在此 {@code all()} 上加重复键静态校验。
 */
public enum ConfigKeys {

    // ===================== Web =====================

    PLAYWRIGHT_SCREENSHOT_STRATEGY("serenity.screenshot.strategy", "AFTER_FAILING_STEP",
            "截图策略：仅失败时截图"),
    PLAYWRIGHT_SCREENSHOT_FULLPAGE("playwright.screenshot.fullpage", "false", "是否全页截图"),

    // ===================== API =====================
    API_REQUEST_RESPONSE_LOGS_ENABLED("api.request.response.logging.enabled", "false",
            "API 请求/响应日志（默认关闭，避免明文泄露且绕过敏感脱敏链路）"),

    // ===================== Monitor =====================
    MONITOR_DB_STORE_ENABLED("monitor.db.store.enabled", "false", "Monitor API 响应数据库持久化"),
    MONITOR_FILE_STORE_ENABLED("monitor.file.store.enabled", "false", "Monitor API 响应文件持久化");

    private final String key;
    private final String defaultValue;
    private final String description;

    ConfigKeys(String key, String defaultValue, String description) {
        this.key = key;
        this.defaultValue = defaultValue;
        this.description = description;
    }

    public String key() {
        return key;
    }

    public String defaultValue() {
        return defaultValue;
    }

    public String description() {
        return description;
    }

    /** 全框架配置键清单（供审计/文档/重复校验）。 */
    public static List<ConfigKeys> all() {
        List<ConfigKeys> list = new ArrayList<>();
        for (ConfigKeys c : values()) {
            list.add(c);
        }
        return list;
    }
}
