package com.hsbc.cmb.hk.dbb.automation.framework.web.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 框架配置管理器
 * 
 * 提供高级配置管理功能：
 * 1. 统一配置获取
 * 2. 配置验证
 * 3. 动态配置更新
 * 4. 配置缓存和刷新
 * <p>
 * 并发契约：本类是全局共享的静态工具类，{@code getValue()/setValue()} 会在每次
 * {@code getPage()/getContext()} 时被多个 scenario 线程并发调用，因此缓存必须是并发容器，
 * 且缓存开关的写入必须对其他线程立即可见。
 */
public class FrameworkConfigManager {
    
    private static final Logger logger = LoggerFactory.getLogger(FrameworkConfigManager.class);
    
    // 配置缓存（并发安全。注意：ConcurrentHashMap 不接受 null 值，见 getValue/setValue 的 null 处理）
    private static final Map<FrameworkConfig, Object> configCache = new ConcurrentHashMap<>();
    
    // 是否启用缓存（volatile：保证 enableCache()/disableCache() 的写入对其他线程立即可见）
    private static volatile boolean cacheEnabled = true;
    
    /**
     * 私有构造函数，防止实例化
     */
    private FrameworkConfigManager() {
    }

    /**
     * 获取配置值（支持类型推断）
     * @param config 配置项
     * @return 配置值
     */
    @SuppressWarnings("unchecked")
    public static <T> T getValue(FrameworkConfig config) {
        if (!cacheEnabled) {
            return (T) resolve(config);
        }
        // 原实现的 containsKey→get→put 是 check-then-act：多线程并发读写裸 HashMap 会造成
        // 结构性损坏（丢条目 / 扩容死循环 / ConcurrentModificationException）。
        // 改用原子 computeIfAbsent；映射函数返回 null 时不写入条目，语义等价于原先
        // "解析失败(null) 不入缓存、下次重算"，且天然满足 ConcurrentHashMap 的 non-null 约束。
        return (T) configCache.computeIfAbsent(config, FrameworkConfigManager::resolve);
    }

    /**
     * 解析配置的原始值（缓存未命中时调用）。解析失败返回 null。
     */
    @SuppressWarnings("unchecked")
    private static Object resolve(FrameworkConfig config) {
        return config.mapValue(v -> {
            // 尝试按类型解析
            if (config.getKey().toLowerCase().contains("timeout") ||
                config.getKey().toLowerCase().contains("wait")) {
                try {
                    return (Object) Integer.valueOf(v);
                } catch (NumberFormatException e) {
                    logger.warn("Failed to parse integer value for {}: {}", config.getKey(), v);
                    return null;
                }
            }
            if (config.getKey().toLowerCase().contains("enabled") ||
                config.getKey().toLowerCase().contains("has")) {
                return (Object) Boolean.valueOf(v);
            }
            return (Object) v;
        });
    }

    /**
     * 获取字符串配置值
     * @param config 配置项
     * @return 字符串值
     */
    public static String getString(FrameworkConfig config) {
        Object value = getValue(config);
        if (value == null) {
            return config.getDefaultValue();
        }
        // 确保返回字符串类型
        return value.toString();
    }

    /**
     * 获取整数配置值
     * @param config 配置项
     * @return 整数值
     */
    public static Integer getInt(FrameworkConfig config) {
        Object value = getValue(config);
        if (value == null) {
            try {
                return Integer.parseInt(config.getDefaultValue());
            } catch (NumberFormatException e) {
                logger.warn("Invalid default integer value for {}: {}",
                    config.getKey(), config.getDefaultValue());
                return 0;
            }
        }
        // 如果已经是整数类型，直接返回
        if (value instanceof Integer) {
            return (Integer) value;
        }
        // 否则尝试解析字符串
        try {
            return Integer.parseInt(value.toString());
        } catch (NumberFormatException e) {
            logger.warn("Invalid integer value for {}: {}, using default: {}",
                config.getKey(), value, config.getDefaultValue());
            try {
                return Integer.parseInt(config.getDefaultValue());
            } catch (NumberFormatException ex) {
                logger.warn("Invalid default integer value for {}: {}",
                    config.getKey(), config.getDefaultValue());
                return 0;
            }
        }
    }

    /**
     * 获取整数配置值（带默认值）
     * @param config 配置项
     * @param defaultValue 默认值
     * @return 整数值
     */
    public static Integer getInt(FrameworkConfig config, int defaultValue) {
        String value = getString(config);
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            logger.debug("Invalid integer value for {}: {}, using provided default: {}",
                config.getKey(), value, defaultValue);
            return defaultValue;
        }
    }

    /**
     * 获取长整数配置值
     * @param config 配置项
     * @return 长整数值
     */
    public static Long getLong(FrameworkConfig config) {
        String value = getString(config);
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            logger.warn("Invalid long value for {}: {}, using default: {}",
                config.getKey(), value, config.getDefaultValue());
            return Long.parseLong(config.getDefaultValue());
        }
    }

    /**
     * 获取布尔配置值
     * @param config 配置项
     * @return 布尔值
     */
    public static Boolean getBoolean(FrameworkConfig config) {
        String value = getString(config);
        return value != null && (value.equalsIgnoreCase("true") || 
            value.equalsIgnoreCase("yes") || 
            value.equalsIgnoreCase("1"));
    }

    /**
     * 设置配置值
     * @param config 配置项
     * @param value 新值
     */
    public static void setValue(FrameworkConfig config, String value) {
        config.setValue(value);
        if (cacheEnabled) {
            // ConcurrentHashMap 不接受 null 值：null 表示"无可缓存值"，移除旧缓存条目，
            // 使下次 getValue() 重新解析，而不是抛出 NullPointerException。
            if (value != null) {
                configCache.put(config, value);
            } else {
                configCache.remove(config);
            }
        }
        logger.debug("Updated config: {} = {}", config.getKey(), value);
    }

    /**
     * 设置整数配置值
     * @param config 配置项
     * @param value 新值
     */
    public static void setInt(FrameworkConfig config, int value) {
        setValue(config, String.valueOf(value));
    }

    /**
     * 设置布尔配置值
     * @param config 配置项
     * @param value 新值
     */
    public static void setBoolean(FrameworkConfig config, boolean value) {
        setValue(config, String.valueOf(value));
    }

    /**
     * 清空配置缓存
     */
    public static void clearCache() {
        configCache.clear();
        logger.debug("Config cache cleared");
    }

    /**
     * 禁用配置缓存
     */
    public static void disableCache() {
        cacheEnabled = false;
        clearCache();
    }

    /**
     * 启用配置缓存
     */
    public static void enableCache() {
        cacheEnabled = true;
    }

    /**
     * 验证配置值
     * @param config 配置项
     * @return 是否有效
     */
    public static boolean isValid(FrameworkConfig config) {
        try {
            String value = config.getValue();
            
            // 验证数字类型
            if (config.getKey().toLowerCase().contains("timeout") ||
                config.getKey().toLowerCase().contains("wait")) {
                Integer.parseInt(value);
            }
            
            // 验证布尔类型
            if (config.getKey().toLowerCase().contains("enabled") ||
                config.getKey().toLowerCase().contains("has")) {
                Boolean.parseBoolean(value);
            }
            
            return true;
        } catch (Exception e) {
            logger.warn("Invalid config value for {}: {}", config.getKey(), e.getMessage());
            return false;
        }
    }

    /**
     * 打印所有配置
     */
    public static void printAllConfigs() {
        logger.info("=== Framework Configuration ===");
        for (FrameworkConfig config : FrameworkConfig.values()) {
            String value = config.getValue();
            String displayValue = value != null ? value : "null";
            logger.info("{} = {} ({})", config.getKey(), displayValue, config.getDescription());
        }
        logger.info("===============================");
    }

    /**
     * 获取配置摘要
     * @return 配置摘要字符串
     */
    public static String getConfigSummary() {
        StringBuilder sb = new StringBuilder();
        sb.append("Framework Configuration Summary:\n");
        
        for (FrameworkConfig config : FrameworkConfig.values()) {
            String value = config.getValue();
            sb.append(String.format("  %-40s = %s\n", config.getKey(), value));
        }
        
        return sb.toString();
    }
}
