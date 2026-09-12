package com.hsbc.cmb.hk.dbb.automation.framework.web.listener;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * 官方 Adapter：把 Serenity 生命周期事件桥接到 {@link FrameworkListener} —— D4-1。
 *
 * <p><b>职责</b>：业务只实现 {@link FrameworkListener}（纯框架接口，不碰 Serenity），
 * 由本类在 Serenity 回调点统一转发。换测试引擎时只需替换本 Adapter，业务监听器不动。
 *
 * <p><b>异常隔离（关键）</b>：任一监听器回调抛异常都在此被捕获并记录，
 * <b>绝不影响其它监听器，也绝不中断主流程</b> —— 对齐 W-16「单点失败不拖垮全盘」：
 * 监听器属于可观测 / 增强设施，它的缺陷不该让测试跑不起来。
 *
 * <p><b>监听器来源</b>：{@link ListenerRegistry#getRegisteredListeners()} 中
 * 所有 {@link FrameworkListener} 实例（含 SPI 与包扫描两条发现路径）。
 *
 * @apiNote framework-internal：由 {@code PlaywrightListener} 在生命周期点调用，
 *          业务代码无需直接使用。
 */
public final class FrameworkListenerBridge {

    private static final Logger logger = LoggerFactory.getLogger(FrameworkListenerBridge.class);

    private FrameworkListenerBridge() {
        // 纯静态门面，禁止实例化
    }

    /** 场景开始。 */
    public static void beforeScenario(String scenarioName) {
        for (FrameworkListener listener : listeners()) {
            try {
                listener.beforeScenario(scenarioName);
            } catch (Throwable t) {
                warn(listener, "beforeScenario", t);
            }
        }
    }

    /** 场景结束。 */
    public static void afterScenario(String scenarioName, boolean failed) {
        for (FrameworkListener listener : listeners()) {
            try {
                listener.afterScenario(scenarioName, failed);
            } catch (Throwable t) {
                warn(listener, "afterScenario", t);
            }
        }
    }

    /** 步骤开始。 */
    public static void beforeStep(String stepName) {
        for (FrameworkListener listener : listeners()) {
            try {
                listener.beforeStep(stepName);
            } catch (Throwable t) {
                warn(listener, "beforeStep", t);
            }
        }
    }

    /** 步骤结束。 */
    public static void afterStep(String stepName) {
        for (FrameworkListener listener : listeners()) {
            try {
                listener.afterStep(stepName);
            } catch (Throwable t) {
                warn(listener, "afterStep", t);
            }
        }
    }

    /** 失败发生。 */
    public static void onFailure(String scenarioName, Throwable cause) {
        for (FrameworkListener listener : listeners()) {
            try {
                listener.onFailure(scenarioName, cause);
            } catch (Throwable t) {
                warn(listener, "onFailure", t);
            }
        }
    }

    // ═══════════════════════════════════════════════════════════
    // 内部
    // ═══════════════════════════════════════════════════════════

    /** 取出所有已注册的 FrameworkListener（每次实时取，支持运行期注册）。 */
    private static List<FrameworkListener> listeners() {
        List<FrameworkListener> result = new ArrayList<>();
        for (Object candidate : ListenerRegistry.getRegisteredListeners()) {
            if (candidate instanceof FrameworkListener) {
                result.add((FrameworkListener) candidate);
            }
        }
        return result;
    }

    private static void warn(FrameworkListener listener, String callback, Throwable t) {
        logger.warn("[FrameworkListener] listener {} threw in {}() - ignored to protect the run: {}",
                listener.getClass().getName(), callback, t.toString());
    }
}
