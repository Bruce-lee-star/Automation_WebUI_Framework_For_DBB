package com.hsbc.cmb.hk.dbb.automation.framework.web.core;

import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.PlaywrightManager;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 框架状态管理类
 * 存储和管理框架的全局状态信息
 */
public class FrameworkState {
    private static final FrameworkState INSTANCE = new FrameworkState();
    
    // 框架初始化状态
    private final AtomicBoolean initialized = new AtomicBoolean(false);
    
    // 框架运行状态
    private final AtomicBoolean running = new AtomicBoolean(false);
    
    // 全局配置信息
    private final Map<String, Object> configuration = new ConcurrentHashMap<>();
    
    // 自定义全局变量
    private final Map<String, Object> contextVariables = new ConcurrentHashMap<>();
    
    // 错误信息
    private Exception lastException;
    
    // 私有构造函数，防止外部实例化
    private FrameworkState() {
    }
    
    // 获取单例实例
    public static FrameworkState getInstance() {
        return INSTANCE;
    }
    
    // 初始化框架状态
    public void initialize() {
        initialized.set(true);
        running.set(false);
        configuration.clear();
        contextVariables.clear();
        lastException = null;
    }
    
    // 启动框架
    public void start() {
        running.set(true);
    }
    
    // 停止框架
    public void stop() {
        running.set(false);
    }
    
    // 重置框架状态（不清理Playwright资源，用于重试场景）
    public void reset() {
        initialized.set(false);
        running.set(false);
        configuration.clear();
        contextVariables.clear();
        lastException = null;
    }
    
    // 清理框架状态
    public void cleanup() {
        try {
            // 清理Playwright资源
            PlaywrightManager.cleanupAll();
        } finally {
            // 重置状态
            initialized.set(false);
            running.set(false);
            configuration.clear();
            contextVariables.clear();
            lastException = null;
        }
    }
    
    // 设置配置项
    public void setConfiguration(String key, Object value) {
        configuration.put(key, value);
    }
    
    // 获取配置项
    public <T> T getConfiguration(String key, Class<T> type) {
        Object value = configuration.get(key);
        if (value == null) {
            return null;
        }
        if (type.isInstance(value)) {
            return type.cast(value);
        }
        return null;
    }
    
    // 获取配置项（带默认值）
    public <T> T getConfiguration(String key, Class<T> type, T defaultValue) {
        T value = getConfiguration(key, type);
        return (value != null) ? value : defaultValue;
    }
    
    // 设置上下文变量
    public void setContextVariable(String key, Object value) {
        contextVariables.put(key, value);
    }
    
    // 获取上下文变量
    public <T> T getContextVariable(String key, Class<T> type) {
        Object value = contextVariables.get(key);
        if (value == null) {
            return null;
        }
        if (type.isInstance(value)) {
            return type.cast(value);
        }
        return null;
    }
    
    // 移除上下文变量
    public void removeContextVariable(String key) {
        contextVariables.remove(key);
    }
    
    // 清除所有上下文变量
    public void clearContextVariables() {
        contextVariables.clear();
    }
    
    // 设置最后一个异常
    public void setLastException(Exception exception) {
        this.lastException = exception;
    }
    
    // 获取最后一个异常
    public Exception getLastException() {
        return lastException;
    }
    
    // 检查框架是否已初始化
    public boolean isInitialized() {
        return initialized.get();
    }
    
    // 检查框架是否正在运行
    public boolean isRunning() {
        return running.get();
    }
    
    // 获取所有配置信息
    public Map<String, Object> getConfiguration() {
        return configuration;
    }
    
    // 获取所有上下文变量
    public Map<String, Object> getContextVariables() {
        return contextVariables;
    }
}
