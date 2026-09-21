package com.hsbc.cmb.hk.dbb.automation.framework.common.route;

/**
 * 采集控制面——{@link RouteLifecycle} 接口隔离拆分（N-11）后的三个正交子接口之一。
 *
 * <p>职责：ApiCapture 上下文重置 / 停止 / 当前上下文获取，以及 Monitor 失败采集的场景名归属
 * （{@code MonitorFailureCollector} 去重归属）。测试桩若只需采集控制能力，可仅实现本接口。
 */
public interface CaptureControl {

    /** 重置当前线程的 ApiCapture 上下文（含语言 ThreadLocal 清理）。等价于 {@code ApiCaptureContext.resetCurrent()}。 */
    void resetCaptureCurrent();

    /** 停止当前 ApiCapture 采集。等价于 {@code ApiCaptureContext.stop()}。 */
    void stopCapture();

    /** 取当前采集上下文（用于 markStepStart 等实例操作）。 */
    CaptureContext getCurrentCapture();

    /** 设置当前场景名（MonitorFailureCollector 去重归属）。 */
    void setMonitorScenario(String name);

    /** 清除当前场景（MonitorFailureCollector）。 */
    void clearMonitorScenario();

    /** 清除当前 feature（MonitorFailureCollector）。 */
    void clearMonitorFeature();
}
