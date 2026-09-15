package com.hsbc.cmb.hk.dbb.automation.tests.glue.parallel;

import com.hsbc.cmb.hk.dbb.automation.framework.web.annotations.AutoBrowser;
import com.hsbc.cmb.hk.dbb.automation.framework.web.core.FrameworkCore;
import io.cucumber.java.After;
import io.cucumber.java.Before;
import io.cucumber.java.en.Given;
import net.serenitybdd.annotations.Steps;

/**
 * Route Demo 本地并行冒烟 Glue —— CON-1 引擎级并行 GREEN 验证（绕过内网）。
 *
 * <p>刻意放在独立子包 {@code tests.glue.parallel}，且 runner 的 {@code cucumber.glue} 仅指向本包，
 * 以避免加载共享的 {@code RouteDemoServiceGlue}/{@code RouteDemoCompositeGlue}（同处 {@code tests.glue}）。
 * 那些共享 glue 含使用<b>缓存 Serenity Page 对象</b>的全局 {@code @After}，在引擎级并行下被多线程共享
 * 同一 BasePage 实例 → "BasePage 实例被线程 X 访问，但创建它的线程是 Y"。本 Glue 自管理每线程 context，
 * 不触碰任何共享 Page 对象。</p>
 *
 * <p>每个 scenario 由 {@link #beforeScenario()} 显式 {@link FrameworkCore#beforeTest()} 建当前线程独立的
 * Playwright context/page；{@link #afterScenario()} 清理当前线程资源。</p>
 */
@AutoBrowser(verbose = true)
public class RouteDemoParallelSmokeGlue {

    @Steps
    private RouteDemoParallelSmokeSteps steps;

    /**
     * 每 scenario 显式建当前线程独立的 Playwright context/page。
     *
     * <p><b>严禁用进程级 {@code FrameworkCore.isRunning()} 做守卫</b>：{@code frameworkState.isRunning()}
     * 是进程级单例标志（{@code FrameworkCore} 第 22/303 行），首个 scenario 置 true 后其余并发
     * scenario 会跳过 {@code beforeTest()}，从而跳过 per-thread 的 {@code initializeForScenario()}，
     * 导致拿不到自己的 Page。{@code beforeTest()} 本身即设计为「每 scenario 幂等调用」：
     * {@code initialize()/start()} 受进程级标志守卫只跑一次；{@code initializeForScenario()} 幂等
     * （context 已存在则复用、否则标记按需重建），故每个 scenario 线程都调用一次是正确且安全的。</p>
     */
    @Before
    public void beforeScenario() {
        FrameworkCore.getInstance().beforeTest();
    }

    @After
    public void afterScenario() {
        try {
            steps.cleanup();
        } finally {
            FrameworkCore.getInstance().afterTest();
        }
    }

    @Given("parallel smoke: monitor collects real response")
    public void monitorCollectsRealResponse() {
        steps.monitorCollectsRealResponse();
    }

    @Given("parallel smoke: mock replaces whole response")
    public void mockReplacesWholeResponse() {
        steps.mockReplacesWholeResponse();
    }
}
