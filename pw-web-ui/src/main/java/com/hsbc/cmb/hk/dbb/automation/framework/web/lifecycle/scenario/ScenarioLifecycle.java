package com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.scenario;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.PlaywrightManager;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.context.CustomOptionsManager;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.serenity.PlaywrightSerenityBridge;

import com.hsbc.cmb.hk.dbb.automation.framework.core.context.TestContextHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Scenario / Feature 级生命周期编排（WEB-P1-1 Step 7）：从 {@code PlaywrightManager} 下沉。
 *
 * <p>承载 scenario 级清理的<b>顺序敏感</b>编排：清理动作分布在 Serenity 桥的前后两侧，
 * 顺序一旦颠倒会造成真实浏览器资源泄漏或登录态保留优化被静默绕过（详见方法注释）。
 *
 * @apiNote 包级私有：仅供 {@code framework.web.lifecycle} 同包协作，非对外 API。
 */
public final class ScenarioLifecycle {

    private static final Logger logger = LoggerFactory.getLogger(PlaywrightManager.class);

    private ScenarioLifecycle() {
    }

    /**
     * Scenario 级清理。
     *
     * <p>修复 A-1：先调用桥做 scenario 级 Page/Context 关闭（桥内部 synchronized 取
     * {@code CONTEXT_KEY}/{@code PAGE_KEY} 执行真正的 close）。
     *
     * <p>⚠️ <b>顺序不可变更</b>：禁止在桥前 remove page/context 引用，否则桥取到 null，
     * scenario 级 Context/Page 不被关闭（仅触发 onClose 钩子），造成真实浏览器资源泄漏。
     *
     * <p>{@code CustomOptionsManager} 的清理必须放到<b>桥之后</b>：桥在 feature 模式
     * （{@code resetCustomContextOptionsForFeatureMode}）会读取 {@code customStorageStatePath}
     * 来"跨 scenario 保留登录态"；若在桥之前调用 {@code removeAllThreadLocals()} 将其清空，
     * 该优化会被静默绕过。{@code currentConfigId} 与钩子快照不参与桥的决策，可在桥前安全清理。
     */
    public static void cleanupForScenario() {
        cleanupForScenario(false);
    }

    /**
     * 带用例结果的场景收尾（A5，2026-09-26）。
     *
     * <p>{@code scenarioFailed=true} 时，桥在 feature 模式下**仅丢弃本用例的 Page**（失败用例的 Page 可能
     * "存活但已坏"，不许跨用例传染），Context/登录态仍保留 → 同一个 sessionKey 的下个用例
     * 既不背脏数据、又能免登录。
     *
     * @param scenarioFailed 本用例是否失败
     */
    public static void cleanupForScenario(boolean scenarioFailed) {
        TestContextHolder.get().remove(PlaywrightManager.CURRENT_CONFIG_ID_KEY);

        PlaywrightSerenityBridge.cleanupForScenario(scenarioFailed);

        // 桥已返回：Page/Context 已真正关闭，且桥已读完 customStorageStatePath，
        // 此时再清理当前线程的资源型与自定义选项 ThreadLocal 引用。
        CustomOptionsManager.removeAllThreadLocals();
        TestContextHolder.get().remove(PlaywrightManager.PAGE_KEY);
        TestContextHolder.get().remove(PlaywrightManager.CONTEXT_KEY);
    }

    /**
     * Feature 级别的清理（委托给 {@link PlaywrightSerenityBridge}）。
     */
    public static void cleanupForFeature() {
        PlaywrightSerenityBridge.cleanupForFeature();
    }
}