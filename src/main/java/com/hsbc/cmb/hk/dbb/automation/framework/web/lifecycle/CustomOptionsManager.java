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
}