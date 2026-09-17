package com.hsbc.cmb.hk.dbb.automation.framework.common.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 统一配置键注册表骨架（评审08类-7 / C-6 / M-2）。
 *
 * <p>现状 {@code WebFrameworkConfig} / {@code ApiFrameworkConfig} / {@code MonitorConfig} 三枚举平行共存，
 * 新增配置键无单一事实来源、无重复/命名校验。本枚举汇总全框架配置键元数据
 * （key / defaultValue / description），供审计、文档生成、一致性校验复用。
 *
 * <p><b>接线策略</b>：本类为骨架，<b>当前不替换</b>三枚举（避免破坏现有读取路径）。
 * 后续批次将三枚举退化为"经本注册表读取的门面"。本注册表已在类加载时对所有键做
 * <b>重复键 fail-fast 静态校验</b>（见 {@link #validateUniqueKeys()}），新增配置键务必先在此登记，
 * 使本类成为全框架配置键的<b>强制单一事实来源（SSoT）</b>。
 */
public enum ConfigKeys {

    // ===================== Web =====================

    PLAYWRIGHT_SCREENSHOT_STRATEGY("serenity.screenshot.strategy", "AFTER_EACH_STEP",
            "截图策略（默认每步截图；取值见 WebFrameworkConfig.SERENITY_SCREENSHOT_STRATEGY）"),
    PLAYWRIGHT_SCREENSHOT_FULLPAGE("playwright.screenshot.fullpage", "true", "是否全页截图（默认 true）"),

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

    // ===================== 静态校验（C-6 / M-2 核心痛点：键名无静态校验）=====================
    // 类加载即校验全部键唯一，重复键立即 fail-fast，避免新增配置键时键名冲突被静默吞掉。
    static {
        validateUniqueKeys();
    }

    private static void validateUniqueKeys() {
        Set<String> seen = new java.util.HashSet<>();
        for (ConfigKeys c : values()) {
            if (!seen.add(c.key)) {
                throw new IllegalStateException(
                        "ConfigKeys 注册表存在重复配置键: '" + c.key + "'"
                                + " —— 新增配置键前请先查重，避免键名冲突（C-6 / M-2）");
            }
        }
    }
}
