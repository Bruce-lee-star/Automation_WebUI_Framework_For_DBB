package com.hsbc.cmb.hk.dbb.automation.framework.common.result;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * {@link ResultReporter} 注册表与广播门面 —— D4-2。
 *
 * <p>框架产出的结果经本类广播给所有已注册的上报实现（当前为 Serenity 适配器，
 * 未来可并行接入其它报告引擎）。
 *
 * <p><b>异常隔离</b>：任一上报实现抛异常只记录日志，不影响其它实现与主流程 ——
 * 报告属可观测设施，它的故障不应让测试跑不起来。
 *
 * <p><b>无实现时静默 no-op</b>：纯单测 / 非 Serenity 环境下没有任何上报实现，
 * 调用应安全空转，而不是抛异常。
 */
public final class ResultReporters {

    private static final Logger LOGGER = LoggerFactory.getLogger(ResultReporters.class);

    private static final List<ResultReporter> REPORTERS = new CopyOnWriteArrayList<>();

    private ResultReporters() {
        // 纯静态门面，禁止实例化
    }

    /** 注册一个上报实现（重复注册同一实例会被忽略）。 */
    public static void register(ResultReporter reporter) {
        if (reporter == null || REPORTERS.contains(reporter)) {
            return;
        }
        REPORTERS.add(reporter);
    }

    /** 注销一个上报实现。 */
    public static void unregister(ResultReporter reporter) {
        if (reporter != null) {
            REPORTERS.remove(reporter);
        }
    }

    /** 清空全部上报实现（测试用）。 */
    public static void clear() {
        REPORTERS.clear();
    }

    /** 已注册的实现数量（测试与诊断用）。 */
    public static int size() {
        return REPORTERS.size();
    }

    /** 广播步骤结果。 */
    public static void reportStep(StepResult step) {
        if (step == null) {
            return;
        }
        for (ResultReporter reporter : REPORTERS) {
            try {
                reporter.reportStep(step);
            } catch (Throwable t) {
                LOGGER.warn("[ResultReporters] reporter {} threw in reportStep() — ignored: {}",
                        reporter.getClass().getName(), t.toString());
            }
        }
    }

    /** 广播场景结果。 */
    public static void reportScenario(String scenarioName, TestResult result, long durationMs) {
        for (ResultReporter reporter : REPORTERS) {
            try {
                reporter.reportScenario(scenarioName, result, durationMs);
            } catch (Throwable t) {
                LOGGER.warn("[ResultReporters] reporter {} threw in reportScenario() — ignored: {}",
                        reporter.getClass().getName(), t.toString());
            }
        }
    }
}
