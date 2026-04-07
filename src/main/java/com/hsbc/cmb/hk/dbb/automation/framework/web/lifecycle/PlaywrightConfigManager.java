package com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle;

import com.hsbc.cmb.hk.dbb.automation.framework.web.config.FrameworkConfig;
import com.hsbc.cmb.hk.dbb.automation.framework.web.config.FrameworkConfigManager;
import com.hsbc.cmb.hk.dbb.automation.framework.web.utils.LoggingConfigUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.*;
import java.awt.geom.AffineTransform;

/**
 * Playwright 配置管理器 - 负责配置相关逻辑
 * <p>
 * 职责：
 * - 浏览器类型配置
 * - Viewport 配置
 * - 设备缩放因子配置
 * - 屏幕尺寸检测
 */
class PlaywrightConfigManager {
    
    private static final Logger logger = LoggerFactory.getLogger(PlaywrightConfigManager.class);
    
    /**
     * 获取逻辑屏幕分辨率（用于 viewport 和窗口大小）
     */
    static Dimension getAvailableScreenSize() {
        try {
            GraphicsConfiguration gc = GraphicsEnvironment
                    .getLocalGraphicsEnvironment()
                    .getDefaultScreenDevice()
                    .getDefaultConfiguration();

            Rectangle bounds = gc.getBounds();
            int logicalWidth = bounds.width;
            int logicalHeight = bounds.height;

            LoggingConfigUtil.logInfoIfVerbose(logger, "Using logical screen size: {}x{}", logicalWidth, logicalHeight);

            return new Dimension(logicalWidth, logicalHeight);
        } catch (Exception e) {
            LoggingConfigUtil.logWarnIfVerbose(logger, "Failed to get screen size, using default: {}", e.getMessage());
            return new Dimension(1920, 1080);
        }
    }

    /**
     * 获取系统 DPI 缩放因子
     */
    static double getSystemDpiScaleFactor() {
        try {
            GraphicsConfiguration gc = GraphicsEnvironment
                    .getLocalGraphicsEnvironment()
                    .getDefaultScreenDevice()
                    .getDefaultConfiguration();

            AffineTransform transform = gc.getDefaultTransform();
            double scaleX = transform.getScaleX();
            double scaleY = transform.getScaleY();

            // 使用水平和垂直缩放因子的平均值
            double avgScale = (scaleX + scaleY) / 2.0;

            logger.debug("System DPI scale factors: X={}, Y={}, Average: {}", scaleX, scaleY, avgScale);

            return avgScale;
        } catch (Exception e) {
            logger.warn("Failed to get system DPI scale factor, using default 1.0: {}", e.getMessage());
            return 1.0;
        }
    }

    /**
     * 获取浏览器类型
     * 优先使用测试用例级别的覆盖配置（如果存在）
     */
    static String getBrowserType() {
        // 这里需要访问 BrowserOverrideManager，暂时使用默认逻辑
        return FrameworkConfigManager.getString(FrameworkConfig.PLAYWRIGHT_BROWSER_TYPE);
    }

    /**
     * 是否为 headless 模式
     */
    static boolean isHeadless() {
        return FrameworkConfigManager.getBoolean(FrameworkConfig.PLAYWRIGHT_BROWSER_HEADLESS);
    }

    /**
     * 获取浏览器慢动作延迟（毫秒）
     */
    static int getBrowserSlowMo() {
        return FrameworkConfigManager.getInt(FrameworkConfig.PLAYWRIGHT_BROWSER_SLOWMO);
    }

    /**
     * 获取浏览器超时（毫秒）
     */
    static int getBrowserTimeout() {
        return FrameworkConfigManager.getInt(FrameworkConfig.PLAYWRIGHT_BROWSER_TIMEOUT);
    }

    /**
     * 获取浏览器下载路径
     */
    static String getBrowserDownloadsPath() {
        return FrameworkConfigManager.getString(FrameworkConfig.PLAYWRIGHT_BROWSER_DOWNLOADS_PATH);
    }

    /**
     * 获取浏览器 channel
     */
    static String getBrowserChannel() {
        return FrameworkConfigManager.getString(FrameworkConfig.PLAYWRIGHT_BROWSER_CHANNEL);
    }

    /**
     * 判断浏览器类型是否是 Chromium 系列
     */
    static boolean isChromiumBased(String browserType) {
        if (browserType == null) {
            return false;
        }
        return browserType.equalsIgnoreCase("chromium") ||
               browserType.equalsIgnoreCase("chrome") ||
               browserType.equalsIgnoreCase("edge");
    }

    /**
     * 是否最大化窗口
     */
    static boolean isWindowMaximize() {
        return FrameworkConfigManager.getBoolean(FrameworkConfig.PLAYWRIGHT_WINDOW_MAXIMIZE);
    }

    /**
     * 获取窗口最大化参数
     */
    static String getWindowMaximizeArgs() {
        return FrameworkConfigManager.getString(FrameworkConfig.PLAYWRIGHT_WINDOW_MAXIMIZE_ARGS);
    }

    /**
     * 获取 Viewport 宽度
     */
    static int getViewportWidth() {
        return FrameworkConfigManager.getInt(FrameworkConfig.PLAYWRIGHT_CONTEXT_VIEWPORT_WIDTH);
    }

    /**
     * 获取 Viewport 高度
     */
    static int getViewportHeight() {
        return FrameworkConfigManager.getInt(FrameworkConfig.PLAYWRIGHT_CONTEXT_VIEWPORT_HEIGHT);
    }

    /**
     * 是否启用触摸
     */
    static boolean hasTouch() {
        return FrameworkConfigManager.getBoolean(FrameworkConfig.PLAYWRIGHT_CONTEXT_HAS_TOUCH);
    }

    /**
     * 是否移动设备模式
     */
    static boolean isMobile() {
        return FrameworkConfigManager.getBoolean(FrameworkConfig.PLAYWRIGHT_CONTEXT_IS_MOBILE);
    }

    /**
     * 获取 Context locale
     */
    static String getContextLocale() {
        return FrameworkConfigManager.getString(FrameworkConfig.PLAYWRIGHT_CONTEXT_LOCALE);
    }

    /**
     * 获取 Context timezone
     */
    static String getContextTimezone() {
        return FrameworkConfigManager.getString(FrameworkConfig.PLAYWRIGHT_CONTEXT_TIMEZONE_ID);
    }

    /**
     * 获取 Context User-Agent
     */
    static String getContextUserAgent() {
        return FrameworkConfigManager.getString(FrameworkConfig.PLAYWRIGHT_CONTEXT_USER_AGENT);
    }

    /**
     * 获取 Context 权限
     */
    static String getContextPermissions() {
        return FrameworkConfigManager.getString(FrameworkConfig.PLAYWRIGHT_CONTEXT_PERMISSIONS);
    }

    /**
     * 获取 ColorScheme
     */
    static String getColorScheme() {
        return FrameworkConfigManager.getString(FrameworkConfig.PLAYWRIGHT_CONTEXT_COLOR_SCHEME);
    }

    /**
     * 获取地理纬度
     */
    static String getGeolocationLatitude() {
        return FrameworkConfigManager.getString(FrameworkConfig.PLAYWRIGHT_CONTEXT_GEOLOCATION_LATITUDE);
    }

    /**
     * 获取地理经度
     */
    static String getGeolocationLongitude() {
        return FrameworkConfigManager.getString(FrameworkConfig.PLAYWRIGHT_CONTEXT_GEOLOCATION_LONGITUDE);
    }

    /**
     * 获取设备缩放因子
     */
    static String getDeviceScaleFactor() {
        return FrameworkConfigManager.getString(FrameworkConfig.PLAYWRIGHT_CONTEXT_DEVICE_SCALE_FACTOR);
    }

    /**
     * 是否启用录屏
     */
    static boolean isRecordVideoEnabled() {
        return FrameworkConfigManager.getBoolean(FrameworkConfig.PLAYWRIGHT_CONTEXT_RECORD_VIDEO_ENABLED);
    }

    /**
     * 获取录屏目录
     */
    static String getRecordVideoDir() {
        return FrameworkConfigManager.getString(FrameworkConfig.PLAYWRIGHT_CONTEXT_RECORD_VIDEO_DIR);
    }

    /**
     * 是否启用 Trace
     */
    static boolean isTraceEnabled() {
        return FrameworkConfigManager.getBoolean(FrameworkConfig.PLAYWRIGHT_CONTEXT_TRACE_ENABLED);
    }

    /**
     * Trace 时是否截图
     */
    static boolean isTraceScreenshots() {
        return FrameworkConfigManager.getBoolean(FrameworkConfig.PLAYWRIGHT_CONTEXT_TRACE_SCREENSHOTS);
    }

    /**
     * Trace 时是否快照
     */
    static boolean isTraceSnapshots() {
        return FrameworkConfigManager.getBoolean(FrameworkConfig.PLAYWRIGHT_CONTEXT_TRACE_SNAPSHOTS);
    }

    /**
     * Trace 时是否记录源码
     */
    static boolean isTraceSources() {
        return FrameworkConfigManager.getBoolean(FrameworkConfig.PLAYWRIGHT_CONTEXT_TRACE_SOURCES);
    }

    /**
     * 获取页面超时（毫秒）
     */
    static int getPageTimeout() {
        return FrameworkConfigManager.getInt(FrameworkConfig.PLAYWRIGHT_PAGE_TIMEOUT);
    }

    /**
     * 获取页面导航超时（毫秒）
     */
    static int getNavigationTimeout() {
        return FrameworkConfigManager.getInt(FrameworkConfig.PLAYWRIGHT_PAGE_NAVIGATION_TIMEOUT);
    }

    /**
     * 获取浏览器重启策略
     */
    static String getRestartStrategy() {
        return FrameworkConfigManager.getString(FrameworkConfig.SERENITY_PLAYWRIGHT_RESTART_BROWSER_FOR_EACH);
    }

    /**
     * 是否全页截图
     */
    static boolean isFullPageScreenshot() {
        return FrameworkConfigManager.getBoolean(FrameworkConfig.PLAYWRIGHT_SCREENSHOT_FULLPAGE);
    }

    /**
     * 获取项目名称
     */
    static String getProjectName() {
        return FrameworkConfigManager.getString(FrameworkConfig.SERENITY_PROJECT_NAME);
    }
}
