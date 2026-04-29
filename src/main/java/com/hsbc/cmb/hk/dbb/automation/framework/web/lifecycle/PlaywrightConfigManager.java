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
public class PlaywrightConfigManager {

    private static final PlaywrightConfigManager INSTANCE = new PlaywrightConfigManager();

    private static final Logger logger = LoggerFactory.getLogger(PlaywrightConfigManager.class);

    /**
     * 获取配置管理器实例（提供单例模式）
     * 使用方式：PlaywrightManager.config().getXXX() 或 PlaywrightConfigManager.config().getXXX()
     */
    public static PlaywrightConfigManager config() {
        return INSTANCE;
    }
    
    /**
     * 获取逻辑屏幕分辨率（用于 viewport 和窗口大小）
     */
    Dimension getAvailableScreenSize() {
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
    double getSystemDpiScaleFactor() {
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
    public String getBrowserType() {
        // 优先级1: 检查是否有测试用例级别的浏览器覆盖
        if (com.hsbc.cmb.hk.dbb.automation.framework.web.config.BrowserOverrideManager.hasOverride()) {
            String overrideBrowser = com.hsbc.cmb.hk.dbb.automation.framework.web.config.BrowserOverrideManager.getEffectiveBrowserType();
            LoggingConfigUtil.logDebugIfVerbose(logger, "Using override browser type: {}", overrideBrowser);
            return overrideBrowser;
        }
        // 优先级2: 使用配置文件中的默认值
        return FrameworkConfigManager.getString(FrameworkConfig.PLAYWRIGHT_BROWSER_TYPE);
    }

    /**
     * 是否为 headless 模式
     */
    public boolean isHeadless() {
        return FrameworkConfigManager.getBoolean(FrameworkConfig.PLAYWRIGHT_BROWSER_HEADLESS);
    }

    /**
     * 获取浏览器慢动作延迟（毫秒）
     */
    public int getBrowserSlowMo() {
        return FrameworkConfigManager.getInt(FrameworkConfig.PLAYWRIGHT_BROWSER_SLOWMO);
    }

    /**
     * 获取浏览器超时（毫秒）
     */
    public int getBrowserTimeout() {
        return FrameworkConfigManager.getInt(FrameworkConfig.PLAYWRIGHT_BROWSER_TIMEOUT);
    }

    /**
     * 获取浏览器下载路径
     */
    public String getBrowserDownloadsPath() {
        return FrameworkConfigManager.getString(FrameworkConfig.PLAYWRIGHT_BROWSER_DOWNLOADS_PATH);
    }

    /**
     * 获取浏览器 channel
     */
    public String getBrowserChannel() {
        return FrameworkConfigManager.getString(FrameworkConfig.PLAYWRIGHT_BROWSER_CHANNEL);
    }

    /**
     * 判断浏览器类型是否是 Chromium 系列
     */
    public boolean isChromiumBased(String browserType) {
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
    public boolean isWindowMaximize() {
        return FrameworkConfigManager.getBoolean(FrameworkConfig.PLAYWRIGHT_WINDOW_MAXIMIZE);
    }

    /**
     * 获取窗口最大化参数
     */
    public String getWindowMaximizeArgs() {
        return FrameworkConfigManager.getString(FrameworkConfig.PLAYWRIGHT_WINDOW_MAXIMIZE_ARGS);
    }

    /**
     * 获取 Viewport 宽度
     */
    public int getViewportWidth() {
        return FrameworkConfigManager.getInt(FrameworkConfig.PLAYWRIGHT_CONTEXT_VIEWPORT_WIDTH);
    }

    /**
     * 获取 Viewport 高度
     */
    public int getViewportHeight() {
        return FrameworkConfigManager.getInt(FrameworkConfig.PLAYWRIGHT_CONTEXT_VIEWPORT_HEIGHT);
    }

    /**
     * 是否启用触摸
     */
    public boolean hasTouch() {
        return FrameworkConfigManager.getBoolean(FrameworkConfig.PLAYWRIGHT_CONTEXT_HAS_TOUCH);
    }

    /**
     * 是否移动设备模式
     */
    public boolean isMobile() {
        return FrameworkConfigManager.getBoolean(FrameworkConfig.PLAYWRIGHT_CONTEXT_IS_MOBILE);
    }

    /**
     * 获取 Context locale
     */
    public String getContextLocale() {
        return FrameworkConfigManager.getString(FrameworkConfig.PLAYWRIGHT_CONTEXT_LOCALE);
    }

    /**
     * 获取 Context timezone
     */
    public String getContextTimezone() {
        return FrameworkConfigManager.getString(FrameworkConfig.PLAYWRIGHT_CONTEXT_TIMEZONE_ID);
    }

    /**
     * 获取 Context User-Agent
     */
    public String getContextUserAgent() {
        return FrameworkConfigManager.getString(FrameworkConfig.PLAYWRIGHT_CONTEXT_USER_AGENT);
    }

    /**
     * 获取 Context 权限
     */
    public String getContextPermissions() {
        return FrameworkConfigManager.getString(FrameworkConfig.PLAYWRIGHT_CONTEXT_PERMISSIONS);
    }

    /**
     * 获取 ColorScheme
     */
    public String getColorScheme() {
        return FrameworkConfigManager.getString(FrameworkConfig.PLAYWRIGHT_CONTEXT_COLOR_SCHEME);
    }

    /**
     * 获取地理纬度
     */
    public String getGeolocationLatitude() {
        return FrameworkConfigManager.getString(FrameworkConfig.PLAYWRIGHT_CONTEXT_GEOLOCATION_LATITUDE);
    }

    /**
     * 获取地理经度
     */
    public String getGeolocationLongitude() {
        return FrameworkConfigManager.getString(FrameworkConfig.PLAYWRIGHT_CONTEXT_GEOLOCATION_LONGITUDE);
    }

    /**
     * 获取设备缩放因子
     */
    public String getDeviceScaleFactor() {
        return FrameworkConfigManager.getString(FrameworkConfig.PLAYWRIGHT_CONTEXT_DEVICE_SCALE_FACTOR);
    }

    /**
     * 是否启用录屏
     */
    public boolean isRecordVideoEnabled() {
        return FrameworkConfigManager.getBoolean(FrameworkConfig.PLAYWRIGHT_CONTEXT_RECORD_VIDEO_ENABLED);
    }

    /**
     * 获取录屏目录
     */
    public String getRecordVideoDir() {
        return FrameworkConfigManager.getString(FrameworkConfig.PLAYWRIGHT_CONTEXT_RECORD_VIDEO_DIR);
    }

    /**
     * 是否启用 Trace
     */
    public boolean isTraceEnabled() {
        return FrameworkConfigManager.getBoolean(FrameworkConfig.PLAYWRIGHT_CONTEXT_TRACE_ENABLED);
    }

    /**
     * Trace 时是否截图
     */
    public boolean isTraceScreenshots() {
        return FrameworkConfigManager.getBoolean(FrameworkConfig.PLAYWRIGHT_CONTEXT_TRACE_SCREENSHOTS);
    }

    /**
     * Trace 时是否快照
     */
    public boolean isTraceSnapshots() {
        return FrameworkConfigManager.getBoolean(FrameworkConfig.PLAYWRIGHT_CONTEXT_TRACE_SNAPSHOTS);
    }

    /**
     * Trace 时是否记录源码
     */
    public boolean isTraceSources() {
        return FrameworkConfigManager.getBoolean(FrameworkConfig.PLAYWRIGHT_CONTEXT_TRACE_SOURCES);
    }

    /**
     * 获取页面超时（毫秒）
     */
    public int getPageTimeout() {
        return FrameworkConfigManager.getInt(FrameworkConfig.PLAYWRIGHT_PAGE_TIMEOUT);
    }

    /**
     * 获取页面导航超时（毫秒）
     */
    public int getNavigationTimeout() {
        return FrameworkConfigManager.getInt(FrameworkConfig.PLAYWRIGHT_PAGE_NAVIGATION_TIMEOUT);
    }

    /**
     * 获取浏览器重启策略
     */
    public String getRestartStrategy() {
        return FrameworkConfigManager.getString(FrameworkConfig.SERENITY_PLAYWRIGHT_RESTART_BROWSER_FOR_EACH);
    }

    /**
     * 是否全页截图
     */
    public boolean isFullPageScreenshot() {
        return FrameworkConfigManager.getBoolean(FrameworkConfig.PLAYWRIGHT_SCREENSHOT_FULLPAGE);
    }

    /**
     * 获取项目名称
     */
    public String getProjectName() {
        return FrameworkConfigManager.getString(FrameworkConfig.SERENITY_PROJECT_NAME);
    }

    // ==================== 浏览器启动参数和路径 ====================

    /**
     * 获取浏览器启动参数
     * 根据浏览器类型和 channel 返回对应的启动参数
     */
    public String getBrowserArgs() {
        String browserType = getBrowserType();
        String channel = getBrowserChannel();

        switch (browserType.toLowerCase()) {
            case "firefox":
                return FrameworkConfigManager.getString(FrameworkConfig.PLAYWRIGHT_BROWSER_FIREFOX_ARGS);
            case "webkit":
                return FrameworkConfigManager.getString(FrameworkConfig.PLAYWRIGHT_BROWSER_WEBKIT_ARGS);
            case "chromium":
                if ("msedge".equalsIgnoreCase(channel) || "edge".equalsIgnoreCase(channel)) {
                    return FrameworkConfigManager.getString(FrameworkConfig.PLAYWRIGHT_BROWSER_EDGE_ARGS);
                } else if ("chrome".equalsIgnoreCase(channel)) {
                    return FrameworkConfigManager.getString(FrameworkConfig.PLAYWRIGHT_BROWSER_CHROME_ARGS);
                } else {
                    return FrameworkConfigManager.getString(FrameworkConfig.PLAYWRIGHT_BROWSER_CHROMIUM_ARGS);
                }
            default:
                return "";
        }
    }

    /**
     * 获取浏览器可执行文件路径
     * 根据浏览器类型和 channel 返回对应的可执行文件路径
     * 注意：Firefox 和 WebKit 必须使用 Playwright 编译的版本，不支持 executablePath
     */
    public String getBrowserExecutablePath() {
        String browserType = getBrowserType();
        String channel = getBrowserChannel();

        switch (browserType.toLowerCase()) {
            case "firefox":
            case "webkit":
                return null;
            case "chromium":
                if ("msedge".equalsIgnoreCase(channel) || "edge".equalsIgnoreCase(channel)) {
                    return FrameworkConfigManager.getString(FrameworkConfig.PLAYWRIGHT_BROWSER_EDGE_EXECUTABLE_PATH);
                } else if ("chrome".equalsIgnoreCase(channel)) {
                    return FrameworkConfigManager.getString(FrameworkConfig.PLAYWRIGHT_BROWSER_CHROME_EXECUTABLE_PATH);
                } else {
                    return null;
                }
            default:
                return null;
        }
    }

    /**
     * 是否跳过浏览器下载
     */
    public boolean isSkipBrowserDownload() {
        return FrameworkConfigManager.getBoolean(FrameworkConfig.PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD);
    }

    /**
     * 获取页面加载状态
     */
    public String getPageLoadState() {
        return FrameworkConfigManager.getString(FrameworkConfig.PLAYWRIGHT_PAGE_LOAD_STATE);
    }

    // ==================== Axe-core 配置 ====================

    /**
     * 是否启用 axe-core 扫描
     */
    public boolean isAxeScanEnabled() {
        return FrameworkConfigManager.getBoolean(FrameworkConfig.AXE_SCAN_ENABLED);
    }

    /**
     * 获取 axe-core WCAG 标签
     */
    public String getAxeScanTags() {
        return FrameworkConfigManager.getString(FrameworkConfig.AXE_SCAN_TAGS);
    }

    /**
     * 获取 axe-core 报告输出目录
     */
    public String getAxeScanOutputDir() {
        return FrameworkConfigManager.getString(FrameworkConfig.AXE_SCAN_OUTPUT_DIR);
    }

    // ==================== 元素操作配置 ====================

    /**
     * 获取元素操作后延迟时间（毫秒）
     */
    public int getElementActionPostDelay() {
        return FrameworkConfigManager.getInt(FrameworkConfig.PLAYWRIGHT_ELEMENT_ACTION_POST_DELAY);
    }

    /**
     * 获取元素操作最大重试次数
     */
    public int getElementMaxRetry() {
        return FrameworkConfigManager.getInt(FrameworkConfig.PLAYWRIGHT_ELEMENT_RETRY_MAX);
    }

    /**
     * 获取元素操作重试间隔时间（毫秒）
     */
    public int getElementRetryDelayMs() {
        return FrameworkConfigManager.getInt(FrameworkConfig.PLAYWRIGHT_ELEMENT_RETRY_DELAY_MS);
    }

    /**
     * 获取元素操作总超时时间（毫秒）
     */
    public int getElementOperationTimeout() {
        return FrameworkConfigManager.getInt(FrameworkConfig.PLAYWRIGHT_ELEMENT_OPERATION_TIMEOUT);
    }

    /**
     * 是否在失败时自动截图
     */
    public boolean isElementScreenshotOnFailure() {
        return FrameworkConfigManager.getBoolean(FrameworkConfig.PLAYWRIGHT_ELEMENT_SCREENSHOT_ON_FAILURE);
    }

    /**
     * 是否收集详细诊断信息
     */
    public boolean isElementDetailedDiagnostics() {
        return FrameworkConfigManager.getBoolean(FrameworkConfig.PLAYWRIGHT_ELEMENT_DIAGNOSTICS_DETAILED);
    }

    /**
     * 获取失败截图保存路径
     */
    public String getElementScreenshotPath() {
        return FrameworkConfigManager.getString(FrameworkConfig.PLAYWRIGHT_ELEMENT_SCREENSHOT_PATH);
    }

    /**
     * 获取元素等待/操作超时时间（毫秒）
     */
    public int getElementCheckTimeout() {
        return FrameworkConfigManager.getInt(FrameworkConfig.PLAYWRIGHT_ELEMENT_WAIT_TIMEOUT);
    }

    // ==================== Timeout/Polling Config ====================

    /**
     * 获取轮询间隔时间（毫秒）
     */
    public int getPollingInterval() {
        return FrameworkConfigManager.getInt(FrameworkConfig.PLAYWRIGHT_POLLING_INTERVAL);
    }

    /**
     * 获取页面稳定化等待超时（毫秒）
     */
    public int getStabilizeTimeout() {
        return FrameworkConfigManager.getInt(FrameworkConfig.PLAYWRIGHT_STABILIZE_WAIT_TIMEOUT);
    }

    /**
     * 获取截图等待超时（毫秒）
     */
    public int getScreenshotTimeout() {
        return FrameworkConfigManager.getInt(FrameworkConfig.PLAYWRIGHT_SCREENSHOT_WAIT_TIMEOUT);
    }
}
