package com.hsbc.cmb.hk.dbb.automation.framework.common.route;

/**
 * 路由生命周期钩子（核心层 SPI）。
 *
 * <p>用于打破 {@code web ↔ route} 的循环依赖：web 的生命周期类
 * （PlaywrightListener / PlaywrightManager / PlaywrightContextManager）原本直接 import
 * {@code framework.route} 的核心类来清理/重置路由状态，导致 web 与 route 互相依赖、
 * 无法拆成两个独立 Maven 模块。
 *
 * <p>现改为 web 只依赖本核心层接口，由 route 模块在启动时通过
 * {@link RouteLifecycleRegistry} 自注册实现（依赖倒置，与 T1-4 的 RouteHandlerRegistry 同思路）。
 *
 * <p>接口隔离（N-11）：本接口聚合了三组职责，已拆分为
 * {@link CaptureControl}（采集控制面）、{@link RegistryControl}（注册表层）、
 * {@link EngineControl}（引擎控制面）三个正交子接口。本接口继承三者，以保持现有单一实现
 * {@code RouteLifecycleImpl} 与所有调用点（web 模块经 {@link RouteLifecycleRegistry#get()} 调用）
 * 的源码 / 二进制兼容——无需改动任何实现或调用方即可完成拆分。
 *
 * <p>断言失败兜底解析（ROUTE-P0-1）：{@link #resolveFailureCapture()} 在步骤结束聚合失败时，
 * 优先返回当前（per-context）上下文，否则回退全局 {@code SHARED} 上下文——修复「route 事件线程
 * 因上下文失效回退 {@code SHARED} 记录失败标志、而测试线程经 {@code getCurrentCapture()} 只读
 * per-context 实例导致标志漏检、用例静默判 PASS」的金融级漏测。
 */
public interface RouteLifecycle extends CaptureControl, RegistryControl, EngineControl {

    /**
     * 返回应被步骤结束失败聚合检查采纳的采集上下文（ROUTE-P0-1）。
     *
     * <p>优先当前（per-context）上下文：若其含断言失败则直接返回；否则回退全局 {@code SHARED}
     * 上下文（route 事件线程在页面/上下文失效时经 {@code getCurrent()} 回退的落点）。二者皆无失败
     * 时返回当前上下文（非空），由调用方决定后续动作。
     *
     * <p>该兜底符合「宁可错报不可漏测」原则；并发场景下各 scenario 均已绑定 per-context，
     * {@code SHARED} 恒空，不会产生跨 scenario 串扰。
     *
     * @return 非空采集上下文
     */
    CaptureContext resolveFailureCapture();
}
