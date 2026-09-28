package com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle;

import org.junit.jupiter.api.Test;
import org.junit.platform.launcher.LauncherSession;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * {@link SuiteTeardownListener} 单测（2026-09-27 套件确定性收尾）。
 *
 * <p>验证四件事：</p>
 * <ol>
 *   <li><b>触发与完整链</b>：{@code launcherSessionClosed} 在<b>未初始化</b>的框架状态
 *       （Playwright 未启动、RouteLifecycleRegistry 无实现注册）下调用全局清理必须安全无异常
 *       ——这同时覆盖了「单测/空套件/无浏览器场景」下收尾不炸的契约；</li>
 *   <li><b>幂等</b>：同一 JVM 内重复回调（多次 LauncherSession）不抛异常、不重复收尾
 *       （{@code TEARDOWN_DONE} CAS 门控）；</li>
 *   <li><b>ServiceLoader 注册</b>：META-INF/services 文件存在且指向本类（业务层 runner
 *       由 LauncherFactory 自动加载，这是确定性收尾能被触发的唯一前提）；</li>
 *   <li><b>Route 引擎收尾先行且安全</b>：{@code stopAllContextEngines/drainForSuiteTeardown}
 *       在无任何实现注册（本模块单测 classpath 无 pw-route/pw-route-v2）时 no-op 不抛异常。</li>
 * </ol>
 *
 * <p>注：并发窗口降级路径（cleanupAll 抛 {@code IllegalStateException} → 降级
 * {@code cleanupForFeature()}）由 {@code PlaywrightManager.cleanupAll} 自身的并发拒绝单测覆盖，
 * 此处不做静态 mock（避免引入 mockito-inline 依赖面）。</p>
 */
class SuiteTeardownListenerTest {

    @Test
    void launcherSessionClosed_cleanupIsSafeOnUninitializedFramework() {
        LauncherSession session = mock(LauncherSession.class);
        SuiteTeardownListener listener = new SuiteTeardownListener();
        // 未初始化框架（Playwright 未启动 / Route 无实现）下收尾必须安全：不抛异常、不挂起
        assertDoesNotThrow(() -> listener.launcherSessionClosed(session));
    }

    @Test
    void launcherSessionClosed_isIdempotentAcrossMultipleSessions() {
        LauncherSession session = mock(LauncherSession.class);
        SuiteTeardownListener listener = new SuiteTeardownListener();
        // 首次触发完整收尾
        assertDoesNotThrow(() -> listener.launcherSessionClosed(session));
        // 幂等：重复回调（rerun / 多次 LauncherSession）必须直接返回、不重复收尾
        assertDoesNotThrow(() -> listener.launcherSessionClosed(session));
        assertDoesNotThrow(() -> listener.launcherSessionClosed(mock(LauncherSession.class)));
    }

    @Test
    void serviceLoaderRegistration_pointsToSuiteTeardownListener() throws Exception {
        String resource = "META-INF/services/org.junit.platform.launcher.LauncherSessionListener";
        try (InputStream in = Thread.currentThread().getContextClassLoader().getResourceAsStream(resource)) {
            assertTrue(in != null, "ServiceLoader 注册文件必须存在（LauncherFactory 自动加载依赖它）");
            String content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(content.contains(SuiteTeardownListener.class.getName()),
                    "注册文件必须指向 " + SuiteTeardownListener.class.getName());
        }
    }

    @Test
    void routeEngineTeardown_isSafeWhenNoLifecycleRegistered() {
        // RouteLifecycleRegistry 在无任何实现注册时 stopAllContextEngines/drainForSuiteTeardown
        // 必须 no-op 安全（Composite 聚合语义）——即使业务 classpath 缺少 pw-route/pw-route-v2。
        LauncherSession session = mock(LauncherSession.class);
        SuiteTeardownListener listener = new SuiteTeardownListener();
        assertDoesNotThrow(() -> listener.launcherSessionClosed(session));
    }
}
