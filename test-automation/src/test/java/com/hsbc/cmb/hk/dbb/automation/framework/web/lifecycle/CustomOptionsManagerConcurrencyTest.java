package com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle;

import com.hsbc.cmb.hk.dbb.automation.framework.core.context.TestContextHolder;
import com.microsoft.playwright.options.ColorScheme;
import com.microsoft.playwright.options.Geolocation;
import org.junit.Test;

import java.nio.file.Paths;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * T3-1 收拢验证：{@link CustomOptionsManager} 的 14 个自定义配置状态
 * （原 static ThreadLocal：customContextOptionsFlag / customStorageStatePath / customLocale /
 * customTimezoneId / customUserAgent / customPermissions / customIsMobile / customHasTouch /
 * customColorScheme / customGeolocation / customDeviceScaleFactor / customViewportWidth /
 * customViewportHeight / customProxyEnabled）已迁入
 * {@link com.hsbc.cmb.hk.dbb.automation.framework.core.context.TestContextHolder}。
 * <p>
 * 原均为默认 null 语义，迁移后等价；隔离性由 TestContextHolder 的 per-thread 上下文保证，
 * 新线程取到全新空上下文，不应继承主线程写入的任何自定义配置。
 */
public class CustomOptionsManagerConcurrencyTest {

    @Test
    public void perThreadCustomOptionsIsolation() throws Exception {
        CustomOptionsManager mgr = CustomOptionsManager.getInstance();
        try {
            // 主线程写入全部 14 项自定义配置
            mgr.setStorageStatePath(Paths.get("/tmp/state.json"))
               .setLocale("zh-CN")
               .setTimezone("Asia/Shanghai")
               .setUserAgent("ua")
               .setPermissions(Arrays.asList("geolocation"))
               .setGeolocation(1.0, 2.0)
               .setDeviceScaleFactor(2.0)
               .setIsMobile(true)
               .setHasTouch(true)
               .setColorScheme(ColorScheme.DARK)
               .setViewportSize(1920, 1080)
               .setProxyEnabled(true);

            // 主线程应能读到写入的值
            assertEquals("zh-CN", mgr.getLocale());
            assertTrue("主线程 isMobile 应为 true", mgr.getIsMobile());
            assertEquals(Integer.valueOf(1920), mgr.getViewportWidth());
            assertEquals(Boolean.TRUE, mgr.isCustomContextOptionsFlag());

            // 其他线程应完全隔离（未设置 → null）
            ExecutorService pool = Executors.newFixedThreadPool(1);
            try {
                Future<String> mismatches = pool.submit(() -> {
                    StringBuilder sb = new StringBuilder();
                    if (mgr.getStorageStatePath() != null) sb.append("storageStatePath ");
                    if (mgr.getLocale() != null) sb.append("locale ");
                    if (mgr.getTimezoneId() != null) sb.append("timezoneId ");
                    if (mgr.getUserAgent() != null) sb.append("userAgent ");
                    if (mgr.getPermissions() != null) sb.append("permissions ");
                    if (mgr.getGeolocation() != null) sb.append("geolocation ");
                    if (mgr.getDeviceScaleFactor() != null) sb.append("deviceScaleFactor ");
                    if (mgr.getIsMobile() != null) sb.append("isMobile ");
                    if (mgr.getHasTouch() != null) sb.append("hasTouch ");
                    if (mgr.getColorScheme() != null) sb.append("colorScheme ");
                    if (mgr.getViewportWidth() != null) sb.append("viewportWidth ");
                    if (mgr.getViewportHeight() != null) sb.append("viewportHeight ");
                    if (mgr.getProxyEnabled() != null) sb.append("proxyEnabled ");
                    if (mgr.isCustomContextOptionsFlag() != null) sb.append("flag ");
                    return sb.toString();
                });
                String res = mismatches.get(5, TimeUnit.SECONDS);
                assertEquals("其他线程不应看到主线程的任何自定义配置: [" + res + "]", "", res);
            } finally {
                pool.shutdown();
            }
        } finally {
            mgr.removeAllThreadLocals();
            TestContextHolder.get().clear();
        }
    }
}
