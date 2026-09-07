package com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle;

import com.hsbc.cmb.hk.dbb.automation.framework.common.config.VerboseLogging;
import com.hsbc.cmb.hk.dbb.automation.framework.core.context.ContextKey;
import com.hsbc.cmb.hk.dbb.automation.framework.core.context.TestContextHolder;
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

    // ==================== T3-1 收拢：原 14 个 static ThreadLocal 迁入 TestContext（per-thread 等价） ====================

    static final ContextKey<Boolean> CUSTOM_CONTEXT_OPTIONS_FLAG_KEY = ContextKey.of("customOptionsManager.customContextOptionsFlag", Boolean.class);
    static final ContextKey<Path> CUSTOM_STORAGE_STATE_PATH_KEY = ContextKey.of("customOptionsManager.customStorageStatePath", Path.class);
    //  登录态内容（storageState JSON）缓存键：restoreSession 命中时直接传内存 JSON 给 Playwright，零文件 IO
    static final ContextKey<String> CUSTOM_STORAGE_STATE_KEY = ContextKey.of("customOptionsManager.customStorageState", String.class);
    static final ContextKey<String> CUSTOM_LOCALE_KEY = ContextKey.of("customOptionsManager.customLocale", String.class);
    static final ContextKey<String> CUSTOM_TIMEZONE_ID_KEY = ContextKey.of("customOptionsManager.customTimezoneId", String.class);
    static final ContextKey<String> CUSTOM_USER_AGENT_KEY = ContextKey.of("customOptionsManager.customUserAgent", String.class);
    static final ContextKey<List> CUSTOM_PERMISSIONS_KEY = ContextKey.of("customOptionsManager.customPermissions", List.class);
    static final ContextKey<Boolean> CUSTOM_IS_MOBILE_KEY = ContextKey.of("customOptionsManager.customIsMobile", Boolean.class);
    static final ContextKey<Boolean> CUSTOM_HAS_TOUCH_KEY = ContextKey.of("customOptionsManager.customHasTouch", Boolean.class);
    static final ContextKey<ColorScheme> CUSTOM_COLOR_SCHEME_KEY = ContextKey.of("customOptionsManager.customColorScheme", ColorScheme.class);
    static final ContextKey<Geolocation> CUSTOM_GEOLOCATION_KEY = ContextKey.of("customOptionsManager.customGeolocation", Geolocation.class);
    static final ContextKey<Integer> CUSTOM_DEVICE_SCALE_FACTOR_KEY = ContextKey.of("customOptionsManager.customDeviceScaleFactor", Integer.class);
    static final ContextKey<Integer> CUSTOM_VIEWPORT_WIDTH_KEY = ContextKey.of("customOptionsManager.customViewportWidth", Integer.class);
    static final ContextKey<Integer> CUSTOM_VIEWPORT_HEIGHT_KEY = ContextKey.of("customOptionsManager.customViewportHeight", Integer.class);
    static final ContextKey<Boolean> CUSTOM_PROXY_ENABLED_KEY = ContextKey.of("customOptionsManager.customProxyEnabled", Boolean.class);

    // ==================== 内部工具方法 ====================

    /**
     *  修复 2.3：强制清除当前线程的全部 14 个自定义配置（ T3-1 收拢：由 static ThreadLocal.remove() 改为
     * {@code TestContextHolder.get().remove(key)}，per-thread 等价）。
     * 之前仅通过 cleanupThreadLocals(true) 的 remove 调用清理，但为降低线程池复用场景下的
     * 内存泄漏风险，PlaywrightManager.cleanupForScenario() 会直接调用本方法，不依赖 Bridge 调用链。
     */
    static void removeAllThreadLocals() {
        TestContextHolder.get().remove(CUSTOM_CONTEXT_OPTIONS_FLAG_KEY);
        TestContextHolder.get().remove(CUSTOM_STORAGE_STATE_PATH_KEY);
        TestContextHolder.get().remove(CUSTOM_STORAGE_STATE_KEY);
        TestContextHolder.get().remove(CUSTOM_LOCALE_KEY);
        TestContextHolder.get().remove(CUSTOM_TIMEZONE_ID_KEY);
        TestContextHolder.get().remove(CUSTOM_USER_AGENT_KEY);
        TestContextHolder.get().remove(CUSTOM_PERMISSIONS_KEY);
        TestContextHolder.get().remove(CUSTOM_IS_MOBILE_KEY);
        TestContextHolder.get().remove(CUSTOM_HAS_TOUCH_KEY);
        TestContextHolder.get().remove(CUSTOM_COLOR_SCHEME_KEY);
        TestContextHolder.get().remove(CUSTOM_GEOLOCATION_KEY);
        TestContextHolder.get().remove(CUSTOM_DEVICE_SCALE_FACTOR_KEY);
        TestContextHolder.get().remove(CUSTOM_VIEWPORT_WIDTH_KEY);
        TestContextHolder.get().remove(CUSTOM_VIEWPORT_HEIGHT_KEY);
        TestContextHolder.get().remove(CUSTOM_PROXY_ENABLED_KEY);
    }

    // ==================== 内部工具方法 ====================

    /**
     *  修复 2.3：统一的自定义选项设置模板：设 TestContext → 标记 flag → 日志 → 触发 Context 延迟重建。
     */
    private static <T> void applyCustomOption(T value, String optionName, Runnable setter) {
        setter.run();
        TestContextHolder.get().set(CUSTOM_CONTEXT_OPTIONS_FLAG_KEY, true);
        VerboseLogging.logInfoIfVerbose(logger, "Custom {} set: {} (custom context options auto-enabled)", optionName, value);
        PlaywrightManager.scheduleContextRebuild();
    }

    // ========== 获取方法 ==========

    public Path getStorageStatePath() {
        return TestContextHolder.get().get(CUSTOM_STORAGE_STATE_PATH_KEY);
    }

    public String getStorageState() {
        return TestContextHolder.get().get(CUSTOM_STORAGE_STATE_KEY);
    }

    public String getLocale() {
        return TestContextHolder.get().get(CUSTOM_LOCALE_KEY);
    }

    public String getTimezoneId() {
        return TestContextHolder.get().get(CUSTOM_TIMEZONE_ID_KEY);
    }

    public String getUserAgent() {
        return TestContextHolder.get().get(CUSTOM_USER_AGENT_KEY);
    }

    @SuppressWarnings("unchecked")
    public List<String> getPermissions() {
        return (List<String>) TestContextHolder.get().get(CUSTOM_PERMISSIONS_KEY);
    }

    public Geolocation getGeolocation() {
        return TestContextHolder.get().get(CUSTOM_GEOLOCATION_KEY);
    }

    public Integer getDeviceScaleFactor() {
        return TestContextHolder.get().get(CUSTOM_DEVICE_SCALE_FACTOR_KEY);
    }

    public Boolean getIsMobile() {
        return TestContextHolder.get().get(CUSTOM_IS_MOBILE_KEY);
    }

    public Boolean getHasTouch() {
        return TestContextHolder.get().get(CUSTOM_HAS_TOUCH_KEY);
    }

    public ColorScheme getColorScheme() {
        return TestContextHolder.get().get(CUSTOM_COLOR_SCHEME_KEY);
    }

    public Integer getViewportWidth() {
        return TestContextHolder.get().get(CUSTOM_VIEWPORT_WIDTH_KEY);
    }

    public Integer getViewportHeight() {
        return TestContextHolder.get().get(CUSTOM_VIEWPORT_HEIGHT_KEY);
    }

    public Boolean isCustomContextOptionsFlag() {
        return TestContextHolder.get().get(CUSTOM_CONTEXT_OPTIONS_FLAG_KEY);
    }

    public Boolean getProxyEnabled() {
        return TestContextHolder.get().get(CUSTOM_PROXY_ENABLED_KEY);
    }

    // ========== 设置方法（直接操作 TestContext，支持链式调用）==========

    public CustomOptionsManager setStorageStatePath(Path storageStatePath) {
        applyCustomOption(storageStatePath, "storageStatePath", () -> TestContextHolder.get().set(CUSTOM_STORAGE_STATE_PATH_KEY, storageStatePath));
        return this;
    }

    public CustomOptionsManager setStorageState(String storageState) {
        applyCustomOption(storageState, "storageState", () -> TestContextHolder.get().set(CUSTOM_STORAGE_STATE_KEY, storageState));
        return this;
    }

    public CustomOptionsManager setLocale(String locale) {
        applyCustomOption(locale, "locale", () -> TestContextHolder.get().set(CUSTOM_LOCALE_KEY, locale));
        return this;
    }

    public CustomOptionsManager setTimezone(String timezoneId) {
        applyCustomOption(timezoneId, "timezoneId", () -> TestContextHolder.get().set(CUSTOM_TIMEZONE_ID_KEY, timezoneId));
        return this;
    }

    public CustomOptionsManager setUserAgent(String userAgent) {
        applyCustomOption(userAgent, "userAgent", () -> TestContextHolder.get().set(CUSTOM_USER_AGENT_KEY, userAgent));
        return this;
    }

    public CustomOptionsManager setPermissions(List<String> permissions) {
        applyCustomOption(permissions, "permissions", () -> TestContextHolder.get().set(CUSTOM_PERMISSIONS_KEY, permissions));
        return this;
    }

    public CustomOptionsManager setGeolocation(double latitude, double longitude) {
        applyCustomOption(String.format("(%.4f, %.4f)", latitude, longitude), "geolocation",
                () -> TestContextHolder.get().set(CUSTOM_GEOLOCATION_KEY, new Geolocation(latitude, longitude)));
        return this;
    }

    public CustomOptionsManager setDeviceScaleFactor(double deviceScaleFactor) {
        applyCustomOption(deviceScaleFactor, "deviceScaleFactor",
                () -> TestContextHolder.get().set(CUSTOM_DEVICE_SCALE_FACTOR_KEY, (int) (deviceScaleFactor * 100)));
        return this;
    }

    public CustomOptionsManager setIsMobile(boolean isMobile) {
        applyCustomOption(isMobile, "isMobile", () -> TestContextHolder.get().set(CUSTOM_IS_MOBILE_KEY, isMobile));
        return this;
    }

    public CustomOptionsManager setHasTouch(boolean hasTouch) {
        applyCustomOption(hasTouch, "hasTouch", () -> TestContextHolder.get().set(CUSTOM_HAS_TOUCH_KEY, hasTouch));
        return this;
    }

    public CustomOptionsManager setColorScheme(ColorScheme colorScheme) {
        applyCustomOption(colorScheme, "colorScheme", () -> TestContextHolder.get().set(CUSTOM_COLOR_SCHEME_KEY, colorScheme));
        return this;
    }

    public CustomOptionsManager setViewportSize(int width, int height) {
        applyCustomOption(width + "x" + height, "viewportSize", () -> {
            TestContextHolder.get().set(CUSTOM_VIEWPORT_WIDTH_KEY, width);
            TestContextHolder.get().set(CUSTOM_VIEWPORT_HEIGHT_KEY, height);
        });
        return this;
    }

    public CustomOptionsManager setProxyEnabled(Boolean enabled) {
        applyCustomOption(enabled, "proxyEnabled", () -> TestContextHolder.get().set(CUSTOM_PROXY_ENABLED_KEY, enabled));
        return this;
    }

    // ========== 批量设置方法 ==========

    /**
     *  修复问题2：重命名以准确表达语义。
     * 本方法仅禁用"自定义配置应用"（标记 {@code customContextOptionsFlag = false}），
     * 并不清除其它 TestContext 值（locale、viewport、userAgent 等仍保留旧值）。
     * 若需在 scenario/feature 结束彻底释放所有配置引用、避免线程池复用场景下的残留污染，
     * 应调用 {@link #removeAllThreadLocals()}（或经由 PlaywrightManager.cleanupForScenario()）。
     * <p>
     * 调用后需要手动触发 Context 重建才能生效。
     *
     * @return this，支持链式调用
     */
    public CustomOptionsManager disableCustomOptions() {
        TestContextHolder.get().set(CUSTOM_CONTEXT_OPTIONS_FLAG_KEY, false);
        return this;
    }
}
