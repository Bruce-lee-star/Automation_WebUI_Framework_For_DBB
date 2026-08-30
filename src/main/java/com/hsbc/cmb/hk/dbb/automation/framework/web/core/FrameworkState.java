package com.hsbc.cmb.hk.dbb.automation.framework.web.core;

import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.PlaywrightManager;

import java.util.Collections;
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
    // ⭐ 评审 A2 复核结论：框架内部（src/main + src/test）对 configuration 的
    //    getter/setter 【均无调用点】—— 全部 32 处 frameworkState.* 调用只涉及生命周期方法
    //    （isInitialized / initialize / start / stop / cleanup / setLastException）。
    //    因此"并行 scenario 经此处串扰"在当前代码并不成立，改为 ThreadLocal 属过度设计。
    //    这里保留为【业务层扩展点】（外部项目可跨 step 存上下文），故不删除；
    //    将来若真被并行写入，再按需线程化。
    private final Map<String, Object> configuration = new ConcurrentHashMap<>();
    
    // 自定义全局变量
    // 自定义全局变量（保留理由同 configuration，见上方 A2 复核结论）
    private final Map<String, Object> contextVariables = new ConcurrentHashMap<>();
    
    // 错误信息（volatile 保证多线程可见性）
    private volatile Exception lastException;
    
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

    /**
     * 仅标记已初始化（不重置/清理任何状态）。
     * 用于 PlaywrightManager 的懒初始化路径：configId 已设置但 frameworkState 尚未
     * 经过完整 initialize() 时，避免 getContext()/getPage() 误判"未初始化"而抛异常。
     */
    public void markInitialized() {
        initialized.set(true);
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
    
    /**
     * 获取所有配置信息。
     * <p>
     * ⭐ 修复 P3-30：原实现直接把内部 Map 引用交出，调用方 {@code clear()/put()} 即可
     * 绕过 {@link #setConfiguration} 改写全局状态（无同步、无校验，且是共享单例）。
     * 改为返回不可修改视图：读取行为不变，写入快速失败（UnsupportedOperationException）。
     */
    public Map<String, Object> getConfiguration() {
        return Collections.unmodifiableMap(configuration);
    }
    
    /**
     * 获取所有上下文变量。
     *
     * <p>同样返回不可修改视图，理由见 {@link #getConfiguration()}。
     * 需要修改请使用 {@link #setContextVariable} / {@link #removeContextVariable}。
     */
    public Map<String, Object> getContextVariables() {
        return Collections.unmodifiableMap(contextVariables);
    }
}
