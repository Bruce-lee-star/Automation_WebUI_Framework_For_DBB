package com.hsbc.cmb.hk.dbb.automation.framework.web.utils;

import com.hsbc.cmb.hk.dbb.automation.framework.web.config.FrameworkConfig;
import com.hsbc.cmb.hk.dbb.automation.framework.web.config.FrameworkConfigManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 超时配置工具类 - 提供各种操作的超时设置
 */
public class TimeoutConfig {
    private static final Logger logger = LoggerFactory.getLogger(TimeoutConfig.class);
    
    // 默认超时时间（毫秒）
    private static final int DEFAULT_PAGE_LOAD_TIMEOUT = 30000;    // 页面加载超时
    private static final int DEFAULT_NAVIGATION_TIMEOUT = 30000;   // 导航超时
    private static final int DEFAULT_ELEMENT_WAIT_TIMEOUT = 15000; // 元素等待/操作超时（isVisible, exists, click, fill 等）
    
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
     * 获取元素等待时间（用于 isVisible, exists 等方法）
     * 这些方法会重试检查，直到超时，提高测试稳定性
     */
    public static int getElementCheckTimeout() {
        try {
            return FrameworkConfigManager.getInt(FrameworkConfig.PLAYWRIGHT_ELEMENT_WAIT_TIMEOUT);
        } catch (Exception e) {
            logger.debug("Failed to get element wait timeout from config, using default", e);
            return DEFAULT_ELEMENT_WAIT_TIMEOUT;
        }
    }

    /**
     * 获取轮询间隔时间（毫秒）
     * 用于各种等待方法的轮询检查间隔
     */
    public static int getPollingInterval() {
        try {
            return FrameworkConfigManager.getInt(FrameworkConfig.PLAYWRIGHT_POLLING_INTERVAL);
        } catch (Exception e) {
            logger.debug("Failed to get polling interval from config, using default", e);
            return 500;
        }
    }

    /**
     * 获取页面稳定化等待超时时间
     */
    public static int getStabilizeTimeout() {
        try {
            return FrameworkConfigManager.getInt(FrameworkConfig.PLAYWRIGHT_STABILIZE_WAIT_TIMEOUT);
        } catch (Exception e) {
            logger.debug("Failed to get stabilize timeout from config, using default", e);
            return 15000;
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

    /**
     * 获取元素操作超时时间（含重试总时间上限）
     * 复用 playwright.element.wait.timeout 配置，与 getElementCheckTimeout 共享同一配置
     * 用于 PageElement 的 click/fill/doubleClick 等操作的时间预算
     */
    public static int getElementActionTimeout() {
        return getElementCheckTimeout();
    }
}