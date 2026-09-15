package com.hsbc.cmb.hk.dbb.automation.framework.web.page.recording;

import com.hsbc.cmb.hk.dbb.automation.framework.common.config.VerboseLogging;
import com.hsbc.cmb.hk.dbb.automation.framework.common.reporting.SerenityReporter;
import com.hsbc.cmb.hk.dbb.automation.framework.web.exceptions.ConfigurationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * framework-internal Serenity 录制门面。
 *
 * <p>从原 {@code SerenityBasePage} 旧类（已删除）提升而来，承载其原有 {@code record} / {@code recordAndReturn} /
 * {@code recordVerification} 三个 reusable interceptor 与 per-page 测试数据存储（{@code serenityTestData}）。
 * 行为逐字迁移，零回归。后续由 Page 录制装饰器（Layer A 原生操作）与框架自有方法调用处（Layer B）复用，
 * 使该旧类可退役、业务 Page 零继承。
 *
 * <p><b>注意</b>：本类为框架内部实现，业务代码不得直接调用（见 ArchUnit
 * {@code businessCodeMustNotUseSerenityRecorder}）。
 */
public final class SerenityRecorder {

    private static final Logger logger = LoggerFactory.getLogger(SerenityRecorder.class);

    /** per-page Serenity 测试数据存储；仅在详细日志开启时写入（成功路径零开销）。 */
    private final Map<String, Object> serenityTestData = new HashMap<>();

    private static boolean isVerboseLogging() {
        return VerboseLogging.isVerboseEnabled();
    }

    // ==================== Reusable Interceptors ====================

    /**
     * 无返回值操作的 Serenity 记录拦截器。
     * 操作前刷新 Route Handler 产生的待报告 API 数据到 Serenity 报告（Handler 在异步线程无法直接写入，需主线程批量写入）。
     */
    public void record(String action, Object detail, Runnable operation) {
        SerenityReporter.flushPendingApiOperations();
        if (isVerboseLogging()) logger.info("[Serenity] {}", action);
        addSerenityTestData(action, detail != null ? detail : "executed");
        operation.run();
    }

    /**
     * 有返回值操作的 Serenity 记录拦截器。
     */
    public <T> T recordAndReturn(String action, Object detail, Supplier<T> operation) {
        SerenityReporter.flushPendingApiOperations();
        T result = operation.get();
        addSerenityTestData(action, detail != null ? detail : result);
        return result;
    }

    /**
     * 验证操作的 Serenity 记录拦截器，自动记录 PASS/FAIL。
     */
    public void recordVerification(String verificationName, boolean passed) {
        SerenityReporter.flushPendingApiOperations();
        String status = passed ? "PASS" : "FAIL";
        addSerenityTestData("verification_" + verificationName, status);
        logger.debug(" Verification '{}': {}", verificationName, status);
    }

    // ==================== 测试数据管理 ====================

    /**
     * 添加测试数据到本地存储。仅在详细日志开启时才写入 HashMap，成功路径零开销。
     */
    public void addSerenityTestData(String key, Object value) {
        if (!isVerboseLogging()) return;
        try {
            serenityTestData.put(key, value);
            VerboseLogging.logDebugIfVerbose(logger, "Added Serenity test data: {} = {}", key, value);
        } catch (Exception e) {
            logger.error("Failed to add Serenity test data: {} = {}", key, value, e);
            throw new ConfigurationException("Failed to add Serenity test data: " + key + " = " + value, e);
        }
    }

    public Object getSerenityTestData(String key) {
        return serenityTestData.get(key);
    }

    public Map<String, Object> getSerenityTestDataMap() {
        return new HashMap<>(serenityTestData);
    }

    public void clearSerenityTestData() {
        serenityTestData.clear();
        logger.debug("Cleared all Serenity test data");
    }

    // ==================== Layer A 原生录制（供 RecordingPageProxy 使用，无 per-page 状态） ====================

    /**
     * 录制开关（零开销，D4）：reporting 关闭时返回 false（沿用 {@code VerboseLogging.isVerboseEnabled()} 现状语义）。
     */
    public static boolean isEnabled() {
        return VerboseLogging.isVerboseEnabled();
    }

    /**
     * Layer A 原生操作录制：刷新 Route Handler 产生的待报告 API 数据 + verbose 日志；
     * 不写入 per-page 测试数据（per-page 测试数据归属 {@code BasePage}，Phase 4 前保留）。
     * 业务语义异常由调用方透传（本方法不捕获）。
     */
    public static void recordNative(String action, Object detail, Runnable operation) {
        SerenityReporter.flushPendingApiOperations();
        if (isVerboseLogging()) logger.info("[Serenity][native] {}", action);
        if (operation != null) operation.run();
    }
}
