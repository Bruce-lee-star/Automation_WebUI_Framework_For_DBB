package com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.context;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.PlaywrightManager;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.PlaywrightRuntime;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.state.PlaywrightRuntimeState;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.provider.DefaultRuntimeProvider;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.concurrent.ConcurrentContextExecutor;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.concurrent.ConcurrentContextOptions;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.concurrent.ContextTask;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.concurrent.ContextTaskResult;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.event.PageEventMonitor;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.config.PlaywrightConfigManager;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.config.ProxyConfigResolver;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.browser.BrowserRegistry;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.browser.BrowserRegistryImpl;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.browser.BrowserStartup;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.browser.BrowserStartupImpl;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.browser.BrowserRestart;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.browser.BrowserRestartImpl;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.browser.BrowserCleanup;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.browser.BrowserCleanupImpl;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.browser.BrowserCrashGuard;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.context.ContextRegistry;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.context.ContextRegistryImpl;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.context.CustomOptionsManager;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.page.PageRegistry;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.page.PageRegistryImpl;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.scenario.ScenarioLifecycle;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.serenity.PlaywrightSerenityBridge;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.serenity.SerenityBusBridge;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.serenity.TestContextBridge;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.bootstrap.PlaywrightContextManager;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.bootstrap.PlaywrightInitializer;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.media.PlaywrightScreenshotManager;

import com.microsoft.playwright.options.ColorScheme;
import com.microsoft.playwright.options.Geolocation;

import java.nio.file.Path;
import java.util.List;

/**
 * 自定义 Context 选项 —— <b>门面契约接口</b>。
 *
 * <p>由 {@link PlaywrightManager#customOptions()} 返回，是业务与框架代码操作「per-thread 自定义 Context 选项」
 * 的<b>唯一抽象</b>。具体实现为 {@link CustomOptionsManager}（单例）。
 * 返回此接口而非具体类，使调用方依赖抽象（面向接口编程），并隔离具体类内部的生命周期控制
 * （如 {@code removeAllThreadLocals()}，包级私有，不对业务暴露）。</p>
 *
 * <p><b>语义约束（与实现一致）</b>：所有 setter 写入本线程 {@code TestContextHolder} 中的自定义选项，
 * 并触发延迟 Context 重建（下次 {@link PlaywrightManager#getContext()} 生效），不会污染其它线程；
 * 典型用法为会话恢复（{@code setStorageState/Path}）与场景级设备仿真（{@code setLocale/setViewportSize}）。
 * 请在导航前设置。</p>
 */
public interface CustomOptions {

    // ========== 获取方法 ==========

    Path getStorageStatePath();

    String getStorageState();

    String getLocale();

    String getTimezoneId();

    String getUserAgent();

    @SuppressWarnings("unchecked")
    List<String> getPermissions();

    Geolocation getGeolocation();

    Integer getDeviceScaleFactor();

    Boolean getIsMobile();

    Boolean getHasTouch();

    ColorScheme getColorScheme();

    Integer getViewportWidth();

    Integer getViewportHeight();

    Boolean isCustomContextOptionsFlag();

    Boolean getProxyEnabled();

    // ========== 设置方法（落 per-thread TestContext，触发延迟 Context 重建）==========

    CustomOptions setStorageStatePath(Path storageStatePath);

    CustomOptions setStorageState(String storageState);

    /**
     * 仅写入 storageState（<b>不</b>触发重建、<b>不</b>置 flag）。
     * 供「就地换会话」在已有 Context 上经 {@code BrowserContext.setStorageState} 直接应用后，
     * 同步 customOptions 与当前 Context 一致，避免后续因其它自定义配置触发重建时丢失本次会话。
     */
    CustomOptions setStorageStateWithoutRebuild(String storageState);

    /**
     * 同 {@link #setStorageStateWithoutRebuild(String)}，接受 storageState 文件路径。
     */
    CustomOptions setStorageStatePathWithoutRebuild(Path storageStatePath);

    CustomOptions setLocale(String locale);

    CustomOptions setTimezone(String timezoneId);

    CustomOptions setUserAgent(String userAgent);

    CustomOptions setPermissions(List<String> permissions);

    CustomOptions setGeolocation(double latitude, double longitude);

    CustomOptions setDeviceScaleFactor(double deviceScaleFactor);

    CustomOptions setIsMobile(boolean isMobile);

    CustomOptions setHasTouch(boolean hasTouch);

    CustomOptions setColorScheme(ColorScheme colorScheme);

    CustomOptions setViewportSize(int width, int height);

    CustomOptions setProxyEnabled(Boolean enabled);

    /**
     * 停止应用自定义选项（仅置标志位为 false，不清除已设置的 per-thread 值）。
     * 要彻底释放请依赖 Scenario 结束的框架自动清理。
     *
     * @return this，支持链式调用
     */
    CustomOptions disableCustomOptions();
}
