package com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle;

import com.hsbc.cmb.hk.dbb.automation.framework.web.utils.LoggingConfigUtil;
import com.microsoft.playwright.options.ColorScheme;
import com.microsoft.playwright.options.Geolocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.List;

/**
 * 自定义选项管理器 — <b>Context 自定义选项数据的唯一持有者和设置入口</b>。
 *
 * <p>所有自定义 Context 选项（locale、viewport、userAgent 等）的数据存储与设置
 * 均由此类统一管理。外部调用者通过 {@code PlaywrightManager.customOptions()} 获取本实例。
 *
 * <p>使用方式：
 * <pre>{@code
 * // 获取/读取
 * String locale = PlaywrightManager.customOptions().getLocale();
 *
 * // 设置（支持链式调用）
 * PlaywrightManager.customOptions()
 *     .setLocale("zh-CN")
 *     .setTimezone("Asia/Shanghai")
 *     .setViewportSize(1920, 1080)
 *     .setIsMobile(false);
 * }</pre>
 */
public class CustomOptionsManager {

    private static final Logger logger = LoggerFactory.getLogger(CustomOptionsManager.class);
    private static final CustomOptionsManager INSTANCE = new CustomOptionsManager();

    public static CustomOptionsManager getInstance() {
        return INSTANCE;
    }

    private CustomOptionsManager() {
    }

    // ==================== ThreadLocal 数据存储（14个） ====================

    static final ThreadLocal<Boolean> customContextOptionsFlag = new ThreadLocal<>();
    static final ThreadLocal<Path> customStorageStatePath = new ThreadLocal<>();
    static final ThreadLocal<String> customLocale = new ThreadLocal<>();
    static final ThreadLocal<String> customTimezoneId = new ThreadLocal<>();
    static final ThreadLocal<String> customUserAgent = new ThreadLocal<>();
    static final ThreadLocal<List<String>> customPermissions = new ThreadLocal<>();
    static final ThreadLocal<Boolean> customIsMobile = new ThreadLocal<>();
    static final ThreadLocal<Boolean> customHasTouch = new ThreadLocal<>();
    static final ThreadLocal<ColorScheme> customColorScheme = new ThreadLocal<>();
    static final ThreadLocal<Geolocation> customGeolocation = new ThreadLocal<>();
    static final ThreadLocal<Integer> customDeviceScaleFactor = new ThreadLocal<>();
    static final ThreadLocal<Integer> customViewportWidth = new ThreadLocal<>();
    static final ThreadLocal<Integer> customViewportHeight = new ThreadLocal<>();
    static final ThreadLocal<Boolean> customProxyEnabled = new ThreadLocal<>();

    // ==================== 内部工具方法 ====================

    /**
     * 统一的自定义选项设置模板：设 ThreadLocal → 标记 flag → 日志 → 触发 Context 延迟重建。
     */
    private static <T> void applyCustomOption(T value, String optionName, Runnable setter) {
        setter.run();
        customContextOptionsFlag.set(true);
        LoggingConfigUtil.logInfoIfVerbose(logger, "Custom {} set: {} (custom context options auto-enabled)", optionName, value);
        PlaywrightManager.scheduleContextRebuild();
    }

    // ========== 获取方法 ==========

    public Path getStorageStatePath() {
        return customStorageStatePath.get();
    }

    public String getLocale() {
        return customLocale.get();
    }

    public String getTimezoneId() {
        return customTimezoneId.get();
    }

    public String getUserAgent() {
        return customUserAgent.get();
    }

    public List<String> getPermissions() {
        return customPermissions.get();
    }

    public Geolocation getGeolocation() {
        return customGeolocation.get();
    }

    public Integer getDeviceScaleFactor() {
        return customDeviceScaleFactor.get();
    }

    public Boolean getIsMobile() {
        return customIsMobile.get();
    }

    public Boolean getHasTouch() {
        return customHasTouch.get();
    }

    public ColorScheme getColorScheme() {
        return customColorScheme.get();
    }

    public Integer getViewportWidth() {
        return customViewportWidth.get();
    }

    public Integer getViewportHeight() {
        return customViewportHeight.get();
    }

    public Boolean isCustomContextOptionsFlag() {
        return customContextOptionsFlag.get();
    }

    public Boolean getProxyEnabled() {
        return customProxyEnabled.get();
    }

    // ========== 设置方法（直接操作 ThreadLocal，支持链式调用）==========

    public CustomOptionsManager setStorageStatePath(Path storageStatePath) {
        applyCustomOption(storageStatePath, "storageStatePath", () -> customStorageStatePath.set(storageStatePath));
        return this;
    }

    public CustomOptionsManager setLocale(String locale) {
        applyCustomOption(locale, "locale", () -> customLocale.set(locale));
        return this;
    }

    public CustomOptionsManager setTimezone(String timezoneId) {
        applyCustomOption(timezoneId, "timezoneId", () -> customTimezoneId.set(timezoneId));
        return this;
    }

    public CustomOptionsManager setUserAgent(String userAgent) {
        applyCustomOption(userAgent, "userAgent", () -> customUserAgent.set(userAgent));
        return this;
    }

    public CustomOptionsManager setPermissions(List<String> permissions) {
        applyCustomOption(permissions, "permissions", () -> customPermissions.set(permissions));
        return this;
    }

    public CustomOptionsManager setGeolocation(double latitude, double longitude) {
        applyCustomOption(String.format("(%.4f, %.4f)", latitude, longitude), "geolocation",
                () -> customGeolocation.set(new Geolocation(latitude, longitude)));
        return this;
    }

    public CustomOptionsManager setDeviceScaleFactor(double deviceScaleFactor) {
        applyCustomOption(deviceScaleFactor, "deviceScaleFactor",
                () -> customDeviceScaleFactor.set((int) (deviceScaleFactor * 100)));
        return this;
    }

    public CustomOptionsManager setIsMobile(boolean isMobile) {
        applyCustomOption(isMobile, "isMobile", () -> customIsMobile.set(isMobile));
        return this;
    }

    public CustomOptionsManager setHasTouch(boolean hasTouch) {
        applyCustomOption(hasTouch, "hasTouch", () -> customHasTouch.set(hasTouch));
        return this;
    }

    public CustomOptionsManager setColorScheme(ColorScheme colorScheme) {
        applyCustomOption(colorScheme, "colorScheme", () -> customColorScheme.set(colorScheme));
        return this;
    }

    public CustomOptionsManager setViewportSize(int width, int height) {
        applyCustomOption(width + "x" + height, "viewportSize", () -> {
            customViewportWidth.set(width);
            customViewportHeight.set(height);
        });
        return this;
    }

    public CustomOptionsManager setProxyEnabled(Boolean enabled) {
        applyCustomOption(enabled, "proxyEnabled", () -> customProxyEnabled.set(enabled));
        return this;
    }

    // ========== 批量设置方法 ==========

    /**
     * 清除当前线程所有自定义选项（恢复使用配置文件默认值）
     * <p>
     * 调用后需要手动触发 Context 重建才能生效
     *
     * @return this，支持链式调用
     */
    public CustomOptionsManager clearAll() {
        customContextOptionsFlag.set(false);
        return this;
    }
}
