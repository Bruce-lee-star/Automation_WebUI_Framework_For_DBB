package com.hsbc.cmb.hk.dbb.automation.framework.common.reporting;

import com.hsbc.cmb.hk.dbb.automation.framework.common.result.TestResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D4-2 {@link SerenityResultAdapter} 契约测试。
 *
 * <p>核心保证：
 * <ul>
 *   <li>引擎结果 → 框架语义映射正确（含引擎特有状态收敛）；</li>
 *   <li>框架语义 → 引擎结果可回写；</li>
 *   <li>{@code null} 安全。</li>
 * </ul>
 *
 * <p>注：Serenity 的 {@code TestResult} 与框架 {@link TestResult} 同名，
 * 故 Serenity 侧一律用全限定名引用（避免别名 / 歧义）。
 */
public class SerenityResultAdapterTest {

    private static final net.thucydides.model.domain.TestResult S_SUCCESS =
            net.thucydides.model.domain.TestResult.SUCCESS;
    private static final net.thucydides.model.domain.TestResult S_FAILURE =
            net.thucydides.model.domain.TestResult.FAILURE;
    private static final net.thucydides.model.domain.TestResult S_ERROR =
            net.thucydides.model.domain.TestResult.ERROR;
    private static final net.thucydides.model.domain.TestResult S_PENDING =
            net.thucydides.model.domain.TestResult.PENDING;
    private static final net.thucydides.model.domain.TestResult S_SKIPPED =
            net.thucydides.model.domain.TestResult.SKIPPED;
    private static final net.thucydides.model.domain.TestResult S_UNDEFINED =
            net.thucydides.model.domain.TestResult.UNDEFINED;
    private static final net.thucydides.model.domain.TestResult S_IGNORED =
            net.thucydides.model.domain.TestResult.IGNORED;
    private static final net.thucydides.model.domain.TestResult S_ABORTED =
            net.thucydides.model.domain.TestResult.ABORTED;
    private static final net.thucydides.model.domain.TestResult S_COMPROMISED =
            net.thucydides.model.domain.TestResult.COMPROMISED;
    private static final net.thucydides.model.domain.TestResult S_UNSUCCESSFUL =
            net.thucydides.model.domain.TestResult.UNSUCCESSFUL;

    @Test
    public void mapsCoreEngineResultsToFramework() {
        assertEquals(TestResult.SUCCESS, SerenityResultAdapter.toFramework(S_SUCCESS));
        assertEquals(TestResult.FAILURE, SerenityResultAdapter.toFramework(S_FAILURE));
        assertEquals(TestResult.ERROR, SerenityResultAdapter.toFramework(S_ERROR));
        assertEquals(TestResult.PENDING, SerenityResultAdapter.toFramework(S_PENDING));
        assertEquals(TestResult.SKIPPED, SerenityResultAdapter.toFramework(S_SKIPPED));
    }

    /** 引擎特有状态必须收敛，不能渗进框架。 */
    @Test
    public void engineSpecificStatesAreNormalized() {
        assertEquals(TestResult.PENDING, SerenityResultAdapter.toFramework(S_UNDEFINED), "UNDEFINED 应收敛为 PENDING");
        assertEquals(TestResult.SKIPPED, SerenityResultAdapter.toFramework(S_IGNORED), "IGNORED 应收敛为 SKIPPED");
        assertEquals(TestResult.SKIPPED, SerenityResultAdapter.toFramework(S_ABORTED), "ABORTED 应收敛为 SKIPPED");
        assertEquals(TestResult.FAILURE, SerenityResultAdapter.toFramework(S_COMPROMISED), "COMPROMISED 应收敛为 FAILURE");
        assertEquals(TestResult.FAILURE, SerenityResultAdapter.toFramework(S_UNSUCCESSFUL), "UNSUCCESSFUL 应收敛为 FAILURE");
    }

    /** 框架语义可回写为引擎结果。 */
    @Test
    public void mapsBackToEngineResults() {
        assertEquals(S_SUCCESS, SerenityResultAdapter.toSerenity(TestResult.SUCCESS));
        assertEquals(S_FAILURE, SerenityResultAdapter.toSerenity(TestResult.FAILURE));
        assertEquals(S_ERROR, SerenityResultAdapter.toSerenity(TestResult.ERROR));
        assertEquals(S_PENDING, SerenityResultAdapter.toSerenity(TestResult.PENDING));
        assertEquals(S_SKIPPED, SerenityResultAdapter.toSerenity(TestResult.SKIPPED));
        assertEquals(S_UNDEFINED, SerenityResultAdapter.toSerenity(TestResult.UNKNOWN));
    }

    /** null 安全：不抛异常，回落确定性取值。 */
    @Test
    public void nullSafe() {
        assertEquals(TestResult.UNKNOWN, SerenityResultAdapter.toFramework(null));
        assertEquals(S_UNDEFINED, SerenityResultAdapter.toSerenity(null));
    }

    /** 框架枚举的语义判定方法自洽。 */
    @Test
    public void frameworkSemanticsAreSelfConsistent() {
        assertTrue(TestResult.FAILURE.isFailure());
        assertTrue(TestResult.ERROR.isFailure());
        assertTrue(TestResult.SUCCESS.isSuccess());
        assertTrue(TestResult.PENDING.isPending());
        assertTrue(TestResult.SKIPPED.isPending());
    }
}
