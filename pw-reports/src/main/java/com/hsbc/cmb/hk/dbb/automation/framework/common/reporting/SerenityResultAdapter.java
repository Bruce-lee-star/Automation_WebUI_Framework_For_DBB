package com.hsbc.cmb.hk.dbb.automation.framework.common.reporting;

import com.hsbc.cmb.hk.dbb.automation.framework.common.result.TestResult;

/**
 * Serenity 结果 ⇄ 框架自有结果的<b>双向 Adapter</b> —— D4-2。
 *
 * <p>这是"换报告引擎不需重写"的关键接缝：
 * <ul>
 *   <li>{@link #toFramework(net.thucydides.model.domain.TestResult)} —— 引擎结果 → 框架语义
 *       （框架内部流转、业务读取一律用框架枚举）；</li>
 *   <li>{@link #toSerenity(TestResult)} —— 框架语义 → 引擎结果（回写引擎时需要）。</li>
 * </ul>
 * 引擎特有状态在此收敛（如 Serenity 的 {@code UNDEFINED/IGNORED/ABORTED/COMPROMISED/UNSUCCESSFUL}
 * 归并为框架最接近的语义），<b>不让引擎方言渗进框架</b>。
 *
 * <p>本类是纯函数（无状态、无副作用），可独立单测。
 */
public final class SerenityResultAdapter {

    private SerenityResultAdapter() {
        // 纯静态 Adapter，禁止实例化
    }

    /**
     * Serenity 结果 → 框架 {@link TestResult}。
     *
     * @param serenity Serenity 结果；{@code null} 时为 {@link TestResult#UNKNOWN}
     */
    public static TestResult toFramework(net.thucydides.model.domain.TestResult serenity) {
        if (serenity == null) {
            return TestResult.UNKNOWN;
        }
        switch (serenity) {
            case SUCCESS:
                return TestResult.SUCCESS;
            case FAILURE:
                return TestResult.FAILURE;
            case ERROR:
                return TestResult.ERROR;
            case PENDING:
            case UNDEFINED:
                return TestResult.PENDING;
            case SKIPPED:
            case IGNORED:
            case ABORTED:
                return TestResult.SKIPPED;
            case COMPROMISED:
            case UNSUCCESSFUL:
                return TestResult.FAILURE;
            default:
                return TestResult.UNKNOWN;
        }
    }

    /**
     * 框架 {@link TestResult} → Serenity 结果。
     *
     * @param framework 框架结果；{@code null} 时为 {@code UNDEFINED}
     */
    public static net.thucydides.model.domain.TestResult toSerenity(TestResult framework) {
        if (framework == null) {
            return net.thucydides.model.domain.TestResult.UNDEFINED;
        }
        switch (framework) {
            case SUCCESS:
                return net.thucydides.model.domain.TestResult.SUCCESS;
            case FAILURE:
                return net.thucydides.model.domain.TestResult.FAILURE;
            case ERROR:
                return net.thucydides.model.domain.TestResult.ERROR;
            case PENDING:
                return net.thucydides.model.domain.TestResult.PENDING;
            case SKIPPED:
                return net.thucydides.model.domain.TestResult.SKIPPED;
            case UNKNOWN:
            default:
                return net.thucydides.model.domain.TestResult.UNDEFINED;
        }
    }
}
