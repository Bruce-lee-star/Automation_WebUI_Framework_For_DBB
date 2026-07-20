package com.hsbc.cmb.hk.dbb.automation.framework.web.hook;

import com.hsbc.cmb.hk.dbb.automation.framework.web.listener.PlaywrightListener;
import com.hsbc.cmb.hk.dbb.automation.framework.web.route.core.ApiCaptureContext;
import io.cucumber.java.After;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ⭐⭐⭐ 通用步骤失败检测 Hook — 框架层自动注入，业务测试代码零感知。
 *
 * <h2>解决的问题</h2>
 * <p>Serenity listener 回调（stepFinished / testFinished）中检测到的任何失败
 * （API 断言、元素找不到、超时等）都只能影响 Serenity 内部报告模型，无法改变
 * Cucumber/JUnit4 的判定 —— 因为 step 方法已正常 return，Cucumber 已标记为 ✔。
 * </p>
 *
 * <h2>注入机制</h2>
 * <p>通过 classpath {@code cucumber.properties} 中的 {@code cucumber.glue} 属性
 * 将本 Hook 所在的包注册为额外 glue 包，无需在 {@code @CucumberOptions(glue=...)}
 * 中显式声明，也不需要在业务测试代码中添加任何引用。
 * </p>
 *
 * <h2>扩展方式</h2>
 * <p>新增失败检测点时，只需在检测到失败的地方调用
 * {@code PlaywrightListener.STEP_FAILURE.set(new AssertionError(details))}，
 * 本 Hook 无需修改即可自动覆盖。</p>
 *
 * @author Automation Framework
 */
public class StepFailureHook {

    private static final Logger logger = LoggerFactory.getLogger(StepFailureHook.class);

    @After(order = Integer.MAX_VALUE)
    public void checkStepFailures() {
        StringBuilder combinedFailure = new StringBuilder();

        // ── 1. 通用步骤失败标记 ──
        AssertionError stepFailure = PlaywrightListener.STEP_FAILURE.get();
        if (stepFailure != null) {
            combinedFailure.append("[STEP_FAILURE] ")
                    .append(stepFailure.getMessage())
                    .append("\n");
        }

        // ── 2. API 断言兜底（边界场景：PlaywrightListener 未设置 STEP_FAILURE）──
        ApiCaptureContext context = ApiCaptureContext.getCurrent();
        if (context != null && context.hasAssertionFailures()) {
            String report = context.buildFailureReport();
            combinedFailure.append("[API_ASSERTION] ")
                    .append(report)
                    .append("\n");
        }

        // ── 3. 统一抛出 ──
        if (combinedFailure.length() > 0) {
            // 清理 ThreadLocal，防止跨 Scenario 污染
            PlaywrightListener.STEP_FAILURE.remove();

            String fullReport = combinedFailure.toString().trim();
            logger.error("Cucumber @After hook (framework) — scenario failed due to:\n{}", fullReport);

            throw new AssertionError("Scenario failure detected in @After hook:\n" + fullReport);
        }
    }
}
