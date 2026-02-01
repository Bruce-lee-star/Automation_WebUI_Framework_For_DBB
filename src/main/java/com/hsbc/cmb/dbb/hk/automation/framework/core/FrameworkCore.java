package com.hsbc.cmb.dbb.hk.automation.framework.core;

import com.hsbc.cmb.dbb.hk.automation.framework.integration.listener.ListenerRegistry;
import com.hsbc.cmb.dbb.hk.automation.framework.lifecycle.PlaywrightManager;
import com.hsbc.cmb.dbb.hk.automation.framework.utils.LoggingConfigUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.hsbc.cmb.dbb.hk.automation.framework.exceptions.InitializationException;

/**
 * 框架核心类
 * 负责框架的初始化、运行、停止和清理
 * 管理Playwright生命周期
 *
 * 注意：监听器通过 SPI 机制自动注册（ThucydidesStepsListenerAdapter）
 * 不需要手动注册 Serenity 监听器
 */
public class FrameworkCore {
    private static final Logger logger = LoggerFactory.getLogger(FrameworkCore.class);
    private static final FrameworkCore INSTANCE = new FrameworkCore();
    private static final FrameworkState frameworkState = FrameworkState.getInstance();

    // 添加 JVM 关闭钩子，确保资源清理
    static {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                logger.info("🚨 JVM Shutdown Hook: Cleaning up resources...");
                if (frameworkState.isInitialized()) {
                    PlaywrightManager.cleanupAll();
                    frameworkState.cleanup();
                }
                logger.info("✅ JVM Shutdown Hook completed");
            } catch (Exception e) {
                logger.error("❌ Error during JVM shutdown cleanup", e);
            }
        }));
    }
    
    // 私有构造函数，防止外部实例化
    private FrameworkCore() {
    }
    
    // 获取单例实例
    public static FrameworkCore getInstance() {
        return INSTANCE;
    }
    
    // 初始化框架
    public void initialize() {
        try {
            if (frameworkState.isInitialized()) {
                LoggingConfigUtil.logInfoIfVerbose(logger, "FrameworkCore is already initialized");
                return;
            }

            logger.info("🚀 Initializing FrameworkCore...");
            LoggingConfigUtil.logDebugIfVerbose(logger, "Starting framework initialization process");

            // 初始化框架状态
            frameworkState.initialize();
            LoggingConfigUtil.logDebugIfVerbose(logger, "Framework state initialized");

            // 初始化Playwright管理器
            PlaywrightManager.initialize();
            LoggingConfigUtil.logDebugIfVerbose(logger, "Playwright manager initialized");

            // 初始化监听器注册表（支持自动重试）
            String basePackage = getBasePackage();
            ListenerRegistry.initialize(basePackage);
            LoggingConfigUtil.logDebugIfVerbose(logger, "Listener registry initialized for package: {}", basePackage);

            logger.info("✅ FrameworkCore initialized successfully");
        } catch (Exception e) {
            logger.error("❌ Failed to initialize FrameworkCore", e);
            frameworkState.setLastException(e);
            throw new InitializationException("Failed to initialize FrameworkCore", e);
        }
    }
    
    // 初始化框架（带自定义监听器包）
    public void initialize(String... listenerPackages) {
        try {
            if (frameworkState.isInitialized()) {
                LoggingConfigUtil.logInfoIfVerbose(logger, "FrameworkCore is already initialized");
                return;
            }

            logger.info("🚀 Initializing FrameworkCore with custom listener packages...");
            LoggingConfigUtil.logDebugIfVerbose(logger, "Starting framework initialization with custom packages");

            // 初始化框架状态
            frameworkState.initialize();
            LoggingConfigUtil.logDebugIfVerbose(logger, "Framework state initialized");

            // 初始化Playwright管理器
            PlaywrightManager.initialize();
            LoggingConfigUtil.logDebugIfVerbose(logger, "Playwright manager initialized");

            logger.info("✅ FrameworkCore initialized successfully");
        } catch (Exception e) {
            logger.error("❌ Failed to initialize FrameworkCore", e);
            frameworkState.setLastException(e);
            throw new InitializationException("Failed to initialize FrameworkCore", e);
        }
    }
    
    // 启动框架
    public void start() {
        try {
            if (!frameworkState.isInitialized()) {
                initialize();
            }
            
            if (frameworkState.isRunning()) {
                LoggingConfigUtil.logInfoIfVerbose(logger, "FrameworkCore is already running");
                return;
            }
            
            logger.info("▶️ Starting FrameworkCore...");
            LoggingConfigUtil.logDebugIfVerbose(logger, "Starting framework core");
            
            // 标记框架为运行状态
            frameworkState.start();
            LoggingConfigUtil.logDebugIfVerbose(logger, "Framework state set to running");
            
            logger.info("✅ FrameworkCore started successfully");
        } catch (Exception e) {
            logger.error("❌ Failed to start FrameworkCore", e);
            frameworkState.setLastException(e);
            throw new InitializationException("Failed to start FrameworkCore", e);
        }
    }
    
    // 停止框架
    public void stop() {
        try {
            if (!frameworkState.isRunning()) {
                LoggingConfigUtil.logInfoIfVerbose(logger, "FrameworkCore is not running");
                return;
            }
            
            logger.info("⏹️ Stopping FrameworkCore...");
            LoggingConfigUtil.logDebugIfVerbose(logger, "Stopping framework core");
            
            // 标记框架为停止状态
            frameworkState.stop();
            LoggingConfigUtil.logDebugIfVerbose(logger, "Framework state set to stopped");
            
            logger.info("✅ FrameworkCore stopped successfully");
        } catch (Exception e) {
            logger.error("❌ Failed to stop FrameworkCore", e);
            frameworkState.setLastException(e);
            throw new InitializationException("Failed to stop FrameworkCore", e);
        }
    }
    
    // 清理框架资源
    public void cleanup() {
        try {
            if (!frameworkState.isInitialized()) {
                LoggingConfigUtil.logInfoIfVerbose(logger, "FrameworkCore is not initialized");
                return;
            }
            
            logger.info("🧹 Cleaning up FrameworkCore...");
            LoggingConfigUtil.logDebugIfVerbose(logger, "Starting framework cleanup");
            
            // 停止框架
            if (frameworkState.isRunning()) {
                stop();
                LoggingConfigUtil.logDebugIfVerbose(logger, "Framework stopped during cleanup");
            }
            
            // 🔧 关键修复：清理所有 Playwright 资源（包括浏览器进程）
            PlaywrightManager.cleanupAll();
            LoggingConfigUtil.logDebugIfVerbose(logger, "Playwright resources cleaned up");
            
            // 清理框架状态
            frameworkState.cleanup();
            LoggingConfigUtil.logDebugIfVerbose(logger, "Framework state cleaned up");
            
            logger.info("✅ FrameworkCore cleaned up successfully");
        } catch (Exception e) {
            logger.error("❌ Failed to cleanup FrameworkCore", e);
            frameworkState.setLastException(e);
            throw new InitializationException("Failed to cleanup FrameworkCore", e);
        }
    }
    
    // 测试开始前的准备
    public void beforeTest() {
        try {
            LoggingConfigUtil.logDebugIfVerbose(logger, "🔍 Preparing for test execution...");
            
            // 确保框架已初始化
            if (!frameworkState.isInitialized()) {
                initialize();
            }
            
            // 确保框架已启动
            if (!frameworkState.isRunning()) {
                start();
            }
            
            // 初始化Playwright实例
            PlaywrightManager.initializeForScenario();
            LoggingConfigUtil.logDebugIfVerbose(logger, "Playwright initialized for scenario");
            
            LoggingConfigUtil.logDebugIfVerbose(logger, "✅ Test preparation completed");
        } catch (Exception e) {
            logger.error("❌ Failed to prepare for test", e);
            frameworkState.setLastException(e);
            throw new InitializationException("Failed to prepare for test", e);
        }
    }
    
    // 测试完成后的清理
    public void afterTest() {
        try {
            LoggingConfigUtil.logDebugIfVerbose(logger, "🧹 Cleaning up after test execution...");
            
            // 清理Playwright资源
            PlaywrightManager.cleanupForScenario();
            LoggingConfigUtil.logDebugIfVerbose(logger, "Playwright resources cleaned up for scenario");
            
            LoggingConfigUtil.logDebugIfVerbose(logger, "✅ Test cleanup completed");
        } catch (Exception e) {
            logger.error("❌ Failed to cleanup after test", e);
            frameworkState.setLastException(e);
            // 不抛出异常，避免影响测试报告
        }
    }
    
    // 获取框架状态
    public FrameworkState getFrameworkState() {
        return frameworkState;
    }
    
    // 动态获取基础包名
    private String getBasePackage() {
        String className = getClass().getName();
        String packageName = className.substring(0, className.lastIndexOf('.'));
        return packageName.substring(0, packageName.lastIndexOf('.'));
    }
    
    // 检查框架是否已初始化
    public boolean isInitialized() {
        return frameworkState.isInitialized();
    }
    
    // 检查框架是否正在运行
    public boolean isRunning() {
        return frameworkState.isRunning();
    }
    
    // 全局异常处理
    public static void handleException(Exception e) {
        logger.error("❌ Exception occurred in FrameworkCore", e);
        frameworkState.setLastException(e);
        
        // 尝试清理资源
        try {
            FrameworkCore.getInstance().cleanup();
        } catch (Exception cleanupException) {
            logger.error("❌ Failed to cleanup resources after exception", cleanupException);
        }
    }
}