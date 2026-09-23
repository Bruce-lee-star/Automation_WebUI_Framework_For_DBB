package com.hsbc.cmb.hk.dbb.automation.framework.route.monitor;

import com.hsbc.cmb.hk.dbb.automation.framework.route.core.engine.RouteException;
import com.microsoft.playwright.Page;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * N-07 契约（doc 21 HIGH）：监控「静默消失」必须被杜绝。
 *
 * <p><b>要防的缺陷</b>：{@code registerFeature(...)} 在「功能不在监控清单中（或该功能为空）」时，
 * 原实现只打一条 verbose-gated INFO 就 {@code return 0}。调用方拿到 0 会当作"已注册 / 无需监控"
 * 继续执行 —— 该功能的 API 监控断言<b>整体不存在</b>，用例却全绿（假绿方向：把"没监控"变成"监控通过"）。</p>
 *
 * <p><b>修复后的契约</b>：
 * <ol>
 *   <li>任何情况下都留<b>非 verbose 门控</b>的 ERROR + 累计计数（{@link ApiMonitorOrchestrator#getMissingFeatureSkipCount()}）；</li>
 *   <li>调用方<b>显式传入清单路径</b>却查不到该功能 → fail-closed 抛 {@link RouteException.RouteConfigException}
 *       （清单内容/功能名错配，绝不降级为"无监控"）。</li>
 * </ol>
 *
 * <p>本用例在旧实现下必红：旧实现既不抛异常，也没有任何计数可断言。</p>
 */
class ApiMonitorOrchestratorMissingFeatureTest {

    @TempDir
    Path tempDir;

    @AfterEach
    void tearDown() {
        // 复位单例，避免本用例注入的清单污染其它用例
        ApiMonitorConfig.reset();
    }

    @Test
    @DisplayName("N-07：显式清单路径 + 功能不存在 → fail-closed（抛 RouteConfigException 且计数递增）")
    void explicitConfigPathWithMissingFeatureFailsClosed() throws Exception {
        Path config = tempDir.resolve("monitor.json");
        Files.writeString(config,
                "{\"features\":{\"other\":{\"api/other\":{\"timeout\":30}}}}",
                StandardCharsets.UTF_8);

        ApiMonitorOrchestrator orchestrator = ApiMonitorOrchestrator.getInstance();
        long before = orchestrator.getMissingFeatureSkipCount();

        RouteException.RouteConfigException thrown = assertThrows(
                RouteException.RouteConfigException.class,
                () -> orchestrator.registerFeature("login", config.toString(), mock(Page.class)),
                "显式给出了清单路径却查不到该功能，必须 fail-closed —— 否则该功能的 API 断言会整体消失而用例全绿");

        assertTrue(thrown.getMessage().contains("login"),
                "异常信息必须点明缺失的功能名，便于定位： " + thrown.getMessage());
        assertTrue(orchestrator.getMissingFeatureSkipCount() > before,
                "「监控未注册」计数必须递增（可观测，供套件末尾 / CI 断言）");
    }

    @Test
    @DisplayName("N-07：未指定清单路径 + 功能不存在 → 不抛异常，但必须计数（拒绝静默）")
    void missingFeatureWithoutExplicitPathIsCountedNotSilent() {
        // 注入一个不含 "login" 的清单（走单例路径，即未显式传路径）
        ApiMonitorConfig.getInstance().setFeatures(new HashMap<>());

        ApiMonitorOrchestrator orchestrator = ApiMonitorOrchestrator.getInstance();
        long before = orchestrator.getMissingFeatureSkipCount();

        int registered = orchestrator.registerFeature("login", mock(Page.class));

        assertEquals(0, registered, "无规则可注册 → 返回 0（语义不变）");
        assertTrue(orchestrator.getMissingFeatureSkipCount() > before,
                "即便不 fail-closed，也必须计数 + ERROR —— 否则『监控静默消失』在报告与日志中均不可见");
    }
}
