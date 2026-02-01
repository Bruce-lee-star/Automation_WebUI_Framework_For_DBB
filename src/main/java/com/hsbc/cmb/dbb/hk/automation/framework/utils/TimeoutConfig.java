package com.hsbc.cmb.dbb.hk.automation.framework.utils;

import com.hsbc.cmb.dbb.hk.automation.framework.config.FrameworkConfig;
import com.hsbc.cmb.dbb.hk.automation.framework.config.FrameworkConfigManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 超时配置工具类 - 提供各种操作的超时设置
 */
public class TimeoutConfig {
    private static final Logger logger = LoggerFactory.getLogger(TimeoutConfig.class);
    
    // 默认超时时间（毫秒）
    private static final int DEFAULT_ELEMENT_TIMEOUT = 10000;      // 元素等待超时
    private static final int DEFAULT_PAGE_LOAD_TIMEOUT = 30000;    // 页面加载超时
    private static final int DEFAULT_NAVIGATION_TIMEOUT = 30000;   // 导航超时
    private static final int DEFAULT_ACTION_TIMEOUT = 15000;        // 操作超时
    private static final int DEFAULT_SHORT_TIMEOUT = 3000;         // 短超时
    private static final int DEFAULT_LONG_TIMEOUT = 60000;         // 长超时
    
    /**
     * 获取元素等待超时时间
     */
    public static int getElementTimeout() {
        try {
            return FrameworkConfigManager.getInt(FrameworkConfig.PLAYWRIGHT_PAGE_TIMEOUT);
        } catch (Exception e) {
            logger.debug("Failed to get element timeout from config, using default", e);
            return DEFAULT_ELEMENT_TIMEOUT;
        }
    }
    
    /**
     * 获取页面加载超时时间
     */
    public static int getPageLoadTimeout() {
        try {
            return FrameworkConfigManager.getInt(FrameworkConfig.PLAYWRIGHT_PAGE_NAVIGATION_TIMEOUT);
        } catch (Exception e) {
            logger.debug("Failed to get page load timeout from config, using default", e);
            return DEFAULT_PAGE_LOAD_TIMEOUT;
        }
    }
    
    /**
     * 获取导航超时时间
     */
    public static int getNavigationTimeout() {
        try {
            return FrameworkConfigManager.getInt(FrameworkConfig.PLAYWRIGHT_PAGE_NAVIGATION_TIMEOUT);
        } catch (Exception e) {
            logger.debug("Failed to get navigation timeout from config, using default", e);
            return DEFAULT_NAVIGATION_TIMEOUT;
        }
    }
    
    /**
     * 获取操作超时时间
     */
    public static int getActionTimeout() {
        try {
            return FrameworkConfigManager.getInt(FrameworkConfig.PLAYWRIGHT_PAGE_TIMEOUT);
        } catch (Exception e) {
            logger.debug("Failed to get action timeout from config, using default", e);
            return DEFAULT_ACTION_TIMEOUT;
        }
    }
    
    /**
     * 获取短超时时间
     */
    public static int getShortTimeout() {
        return DEFAULT_SHORT_TIMEOUT;
    }
    
    /**
     * 获取长超时时间
     */
    public static int getLongTimeout() {
        return DEFAULT_LONG_TIMEOUT;
    }
    
    /**
     * 获取页面稳定化等待超时时间
     */
    public static int getStabilizeTimeout() {
        try {
            return FrameworkConfigManager.getInt(FrameworkConfig.PLAYWRIGHT_STABILIZE_WAIT_TIMEOUT);
        } catch (Exception e) {
            logger.debug("Failed to get stabilize timeout from config, using default", e);
            return 8000;
        }
    }
    
    /**
     * 获取截图等待超时时间
     */
    public static int getScreenshotTimeout() {
        try {
            return FrameworkConfigManager.getInt(FrameworkConfig.PLAYWRIGHT_SCREENSHOT_WAIT_TIMEOUT);
        } catch (Exception e) {
            logger.debug("Failed to get screenshot timeout from config, using default", e);
            return 5000;
        }
    }
}