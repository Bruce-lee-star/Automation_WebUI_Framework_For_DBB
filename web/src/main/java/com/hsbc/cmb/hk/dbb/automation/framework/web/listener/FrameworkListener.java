package com.hsbc.cmb.hk.dbb.automation.framework.web.listener;

/**
 * 框架监听器 SPI 发现契约（对齐 codegen 模块 {@code RoleCodegenBridge} 的 {@code ServiceLoader} 做法）。
 *
 * <p>{@link ListenerRegistry#initialize(String)} 经 JDK {@link java.util.ServiceLoader} 惰性发现本接口的实现类，
 * 作为监听器发现的<b>主路径</b>；原 {@code Class.forName} 全量包扫描降级为<b>容错回退</b>。任一实现类
 * 加载 / 实例化失败仅跳过该类并记 WARN，<b>不影响其它监听器</b>（根治 W-16「单点失败全盘失败」）。</p>
 *
 * <p>监听器若需接入 Serenity 事件总线，仍自行经 {@code StepEventBus.registerListener} 注册
 * （Serenity 自带 {@code StepListener} SPI 负责接线）；本接口仅作为「可被框架发现」的 SPI 契约，
 * 与 Serenity 的注册机制互不耦合。</p>
 *
 * <p><b>D4-1：生命周期方法</b>：本接口不再是纯标记接口，提供
 * {@code before/after Scenario/Step} 与 {@code onFailure} 五个钩子，<b>全部为 default 空实现</b> ——
 * 业务只实现自己关心的回调，无需空实现一堆方法；既有纯标记实现（如仅用于 SPI 发现的类）
 * <b>零改动仍可编译</b>。
 *
 * <p>业务侧<b>只依赖本接口</b>，不接触 Serenity 的 {@code StepListener}；
 * Serenity 事件到本接口的桥接由官方 Adapter {@link FrameworkListenerBridge} 完成，
 * 换测试引擎时业务代码无需改动。
 *
 * <p><b>异常隔离</b>：任一监听器的回调抛异常由 {@link FrameworkListenerBridge} 捕获并记录，
 * 不影响其它监听器与主流程（对齐 W-16「单点失败不拖垮全盘」）。
 *
 * @apiNote 仅 web 核心层与 SPI 声明方相关；实现类须提供 <b>public 无参构造器</b>
 *          以满足 {@code ServiceLoader} 契约。
 */
public interface FrameworkListener {

    /**
     * 场景开始前。
     *
     * @param scenarioName 场景名（含线程 ID 与时间戳后缀，便于并行场景区分）
     */
    default void beforeScenario(String scenarioName) {
    }

    /**
     * 场景结束后（无论成败）。
     *
     * @param scenarioName 场景名
     * @param failed       该场景是否失败（FAILURE / ERROR）
     */
    default void afterScenario(String scenarioName, boolean failed) {
    }

    /** 步骤开始前。 */
    default void beforeStep(String stepName) {
    }

    /** 步骤结束后。 */
    default void afterStep(String stepName) {
    }

    /**
     * 发生失败时（步骤级）。
     *
     * @param scenarioName 所属场景名（可能为空，取决于事件到达时机）
     * @param cause        失败异常；无异常实例时为 {@code null}
     */
    default void onFailure(String scenarioName, Throwable cause) {
    }
}
