package com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle;

import com.microsoft.playwright.options.ColorScheme;
import com.microsoft.playwright.options.Geolocation;

import java.nio.file.Path;
import java.util.List;

/**
 * 自定义选项管理器
 * 用于访问和设置测试级别的自定义配置（如自定义 locale、viewport 等）
 * <p>
 * 使用方式：PlaywrightManager.customOptions().getLocale()
 */
public class CustomOptionsManager {

    private static final CustomOptionsManager INSTANCE = new CustomOptionsManager();

    /**
     * 获取自定义选项管理器实例
     */
    public static CustomOptionsManager getInstance() {
        return INSTANCE;
    }

    private CustomOptionsManager() {
    }

    // ========== 获取方法（委托给 PlaywrightManager 的包内方法）==========

    public Path getStorageStatePath() {
        return PlaywrightManager.getCustomStorageStatePath();
    }

    public String getLocale() {
        return PlaywrightManager.getCustomLocale();
    }

    public String getTimezoneId() {
        return PlaywrightManager.getCustomTimezoneId();
    }

    public String getUserAgent() {
        return PlaywrightManager.getCustomUserAgent();
    }

    public List<String> getPermissions() {
        return PlaywrightManager.getCustomPermissions();
    }

    public Geolocation getGeolocation() {
        return PlaywrightManager.getCustomGeolocation();
    }

    public Integer getDeviceScaleFactor() {
        return PlaywrightManager.getCustomDeviceScaleFactor();
    }

    public Boolean getIsMobile() {
        return PlaywrightManager.getCustomIsMobile();
    }

    public Boolean getHasTouch() {
        return PlaywrightManager.getCustomHasTouch();
    }

    public ColorScheme getColorScheme() {
        return PlaywrightManager.getCustomColorScheme();
    }

    public Integer getViewportWidth() {
        return PlaywrightManager.getCustomViewportWidth();
    }

    public Integer getViewportHeight() {
        return PlaywrightManager.getCustomViewportHeight();
    }

    public Boolean isCustomContextOptionsFlag() {
        return PlaywrightManager.getCustomContextOptionsFlagValue();
    }

    // ========== 设置方法（委托给 PlaywrightManager，支持链式调用）==========

    /**
     * 设置自定义 StorageState 路径（ThreadLocal 覆盖，用于 session 恢复）
     * <p>
     * 调用后会触发 Context 延迟重建，在下次 getContext()/getPage() 时生效
     *
     * @param storageStatePath StorageState 文件路径
     * @return this，支持链式调用
     */
    public CustomOptionsManager setStorageStatePath(Path storageStatePath) {
        PlaywrightManager.setStorageStatePath(storageStatePath);
        return this;
    }

    /**
     * 设置自定义 Locale（ThreadLocal 覆盖，如 "en-US", "zh-CN"）
     * <p>
     * 调用后会触发 Context 延迟重建，在下次 getContext()/getPage() 时生效
     *
     * @param locale 语言环境
     * @return this，支持链式调用
     */
    public CustomOptionsManager setLocale(String locale) {
        PlaywrightManager.setCustomLocale(locale);
        return this;
    }

    /**
     * 设置自定义 Timezone（ThreadLocal 覆盖，如 "Asia/Shanghai", "America/New_York"）
     * <p>
     * 调用后会触发 Context 延迟重建，在下次 getContext()/getPage() 时生效
     *
     * @param timezoneId 时区ID
     * @return this，支持链式调用
     */
    public CustomOptionsManager setTimezone(String timezoneId) {
        PlaywrightManager.setCustomTimezone(timezoneId);
        return this;
    }

    /**
     * 设置自定义 User Agent（ThreadLocal 覆盖）
     * <p>
     * 调用后会触发 Context 延迟重建，在下次 getContext()/getPage() 时生效
     *
     * @param userAgent 用户代理字符串
     * @return this，支持链式调用
     */
    public CustomOptionsManager setUserAgent(String userAgent) {
        PlaywrightManager.setCustomUserAgent(userAgent);
        return this;
    }

    /**
     * 设置自定义 Permissions（ThreadLocal 覆盖，如 "geolocation", "notifications"）
     * <p>
     * 调用后会触发 Context 延迟重建，在下次 getContext()/getPage() 时生效
     *
     * @param permissions 权限列表
     * @return this，支持链式调用
     */
    public CustomOptionsManager setPermissions(List<String> permissions) {
        PlaywrightManager.setCustomPermissions(permissions);
        return this;
    }

    /**
     * 设置自定义 Geolocation（ThreadLocal 覆盖）
     * <p>
     * 调用后会触发 Context 延迟重建，在下次 getContext()/getPage() 时生效
     *
     * @param latitude  纬度
     * @param longitude 经度
     * @return this，支持链式调用
     */
    public CustomOptionsManager setGeolocation(double latitude, double longitude) {
        PlaywrightManager.setCustomGeolocation(latitude, longitude);
        return this;
    }

    /**
     * 设置自定义 Device Scale Factor（ThreadLocal 覆盖，如 1.0, 2.0 对应普通/Retina）
     * <p>
     * 调用后会触发 Context 延迟重建，在下次 getContext()/getPage() 时生效
     *
     * @param deviceScaleFactor 设备缩放因子
     * @return this，支持链式调用
     */
    public CustomOptionsManager setDeviceScaleFactor(double deviceScaleFactor) {
        PlaywrightManager.setCustomDeviceScaleFactor(deviceScaleFactor);
        return this;
    }

    /**
     * 设置自定义是否为移动端（ThreadLocal 覆盖）
     * <p>
     * 调用后会触发 Context 延迟重建，在下次 getContext()/getPage() 时生效
     *
     * @param isMobile 是否为移动端
     * @return this，支持链式调用
     */
    public CustomOptionsManager setIsMobile(boolean isMobile) {
        PlaywrightManager.setCustomIsMobile(isMobile);
        return this;
    }

    /**
     * 设置自定义是否支持触摸（ThreadLocal 覆盖）
     * <p>
     * 调用后会触发 Context 延迟重建，在下次 getContext()/getPage() 时生效
     *
     * @param hasTouch 是否支持触摸
     * @return this，支持链式调用
     */
    public CustomOptionsManager setHasTouch(boolean hasTouch) {
        PlaywrightManager.setCustomHasTouch(hasTouch);
        return this;
    }

    /**
     * 设置自定义 Color Scheme（ThreadLocal 覆盖，如 dark, light）
     * <p>
     * 调用后会触发 Context 延迟重建，在下次 getContext()/getPage() 时生效
     *
     * @param colorScheme 颜色模式
     * @return this，支持链式调用
     */
    public CustomOptionsManager setColorScheme(ColorScheme colorScheme) {
        PlaywrightManager.setCustomColorScheme(colorScheme);
        return this;
    }

    /**
     * 设置自定义 Viewport 尺寸（ThreadLocal 覆盖）
     * <p>
     * 调用后会触发 Context 延迟重建，在下次 getContext()/getPage() 时生效
     *
     * @param width  视口宽度
     * @param height 视口高度
     * @return this，支持链式调用
     */
    public CustomOptionsManager setViewportSize(int width, int height) {
        PlaywrightManager.setCustomViewportSize(width, height);
        return this;
    }

    /**
     * 设置自定义代理启用开关（ThreadLocal 覆盖，优先于配置文件）
     * <p>
     * 调用后会触发 Context 延迟重建，在下次 getContext()/getPage() 时生效
     *
     * @param enabled 是否启用代理（true=启用，false=禁用）
     * @return this，支持链式调用
     */
    public CustomOptionsManager setProxyEnabled(Boolean enabled) {
        PlaywrightManager.setCustomProxyEnabled(enabled);
        return this;
    }

    /**
     * 获取自定义代理启用开关
     *
     * @return null=未设置（使用配置文件默认值），true/false=已覆盖
     */
    public Boolean getProxyEnabled() {
        return PlaywrightManager.getCustomProxyEnabled();
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
        PlaywrightManager.setCustomContextOptionsFlag(false);
        return this;
    }
}