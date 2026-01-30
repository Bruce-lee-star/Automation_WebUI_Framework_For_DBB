package com.hsbc.cmb.dbb.hk.automation.framework.util;

import com.hsbc.cmb.dbb.hk.automation.framework.config.FrameworkConfig;
import com.hsbc.cmb.dbb.hk.automation.framework.config.FrameworkConfigManager;

/**
 * 日志配置工具类
 * 提供统一的日志配置检查方法，避免重复代码
 * 通过系统属性统一控制日志输出
 */
public class LoggingConfigUtil {

    /**
     * 检查是否启用了详细日志输出
     * 通过系统属性 framework.verbose.logging 控制
     *
     * @return true 表示启用了详细日志，false 表示未启用
     */
    public static boolean isVerboseLoggingEnabled() {
        return FrameworkConfigManager.getBoolean(FrameworkConfig.FRAMEWORK_VERBOSE_LOGGING);
    }

    /**
     * 检查是否启用了超详细日志输出
     * 通过系统属性 framework.trace.logging 控制
     *
     * @return true 表示启用了超详细日志，false 表示未启用
     */
    public static boolean isTraceLoggingEnabled() {
        return Boolean.parseBoolean(System.getProperty("framework.trace.logging", "false"));
    }

    /**
     * 根据详细日志配置决定是否记录DEBUG日志
     *
     * @param logger 日志记录器
     * @param message 日志消息
     */
    public static void logDebugIfVerbose(org.slf4j.Logger logger, String message) {
        if (isVerboseLoggingEnabled() || isTraceLoggingEnabled()) {
            logger.debug(message);
        }
    }

    /**
     * 根据详细日志配置决定是否记录DEBUG日志（带格式化参数）
     *
     * @param logger 日志记录器
     * @param format 格式化字符串
     * @param args 参数
     */
    public static void logDebugIfVerbose(org.slf4j.Logger logger, String format, Object... args) {
        if (isVerboseLoggingEnabled() || isTraceLoggingEnabled()) {
            logger.debug(format, args);
        }
    }

    /**
     * 根据详细日志配置决定是否记录INFO日志
     *
     * @param logger 日志记录器
     * @param message 日志消息
     */
    public static void logInfoIfVerbose(org.slf4j.Logger logger, String message) {
        if (isVerboseLoggingEnabled() || isTraceLoggingEnabled()) {
            logger.info(message);
        }
    }

    /**
     * 根据详细日志配置决定是否记录INFO日志（带格式化参数）
     *
     * @param logger 日志记录器
     * @param format 格式化字符串
     * @param args 参数
     */
    public static void logInfoIfVerbose(org.slf4j.Logger logger, String format, Object... args) {
        if (isVerboseLoggingEnabled() || isTraceLoggingEnabled()) {
            logger.info(format, args);
        }
    }

    /**
     * 根据详细日志配置决定是否记录TRACE日志
     *
     * @param logger 日志记录器
     * @param message 日志消息
     */
    public static void logTraceIfVerbose(org.slf4j.Logger logger, String message) {
        if (isTraceLoggingEnabled()) {
            logger.trace(message);
        }
    }

    /**
     * 根据详细日志配置决定是否记录TRACE日志（带格式化参数）
     *
     * @param logger 日志记录器
     * @param format 格式化字符串
     * @param args 参数
     */
    public static void logTraceIfVerbose(org.slf4j.Logger logger, String format, Object... args) {
        if (isTraceLoggingEnabled()) {
            logger.trace(format, args);
        }
    }

    /**
     * 根据详细日志配置决定是否记录WARN日志
     *
     * @param logger 日志记录器
     * @param message 日志消息
     */
    public static void logWarnIfVerbose(org.slf4j.Logger logger, String message) {
        if (isVerboseLoggingEnabled() || isTraceLoggingEnabled()) {
            logger.warn(message);
        }
    }

    /**
     * 根据详细日志配置决定是否记录WARN日志（带格式化参数）
     *
     * @param logger 日志记录器
     * @param format 格式化字符串
     * @param args 参数
     */
    public static void logWarnIfVerbose(org.slf4j.Logger logger, String format, Object... args) {
        if (isVerboseLoggingEnabled() || isTraceLoggingEnabled()) {
            logger.warn(format, args);
        }
    }

    /**
     * 根据详细日志配置决定是否记录ERROR日志
     *
     * @param logger 日志记录器
     * @param message 日志消息
     */
    public static void logErrorIfVerbose(org.slf4j.Logger logger, String message) {
        if (isVerboseLoggingEnabled() || isTraceLoggingEnabled()) {
            logger.error(message);
        }
    }

    /**
     * 根据详细日志配置决定是否记录ERROR日志（带格式化参数）
     *
     * @param logger 日志记录器
     * @param format 格式化字符串
     * @param args 参数
     */
    public static void logErrorIfVerbose(org.slf4j.Logger logger, String format, Object... args) {
        if (isVerboseLoggingEnabled() || isTraceLoggingEnabled()) {
            logger.error(format, args);
        }
    }
}