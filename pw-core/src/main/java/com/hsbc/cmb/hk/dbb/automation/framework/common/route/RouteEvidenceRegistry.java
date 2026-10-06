package com.hsbc.cmb.hk.dbb.automation.framework.common.route;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ServiceLoader;

/**
 * {@link RouteEvidenceSink} 注册表 —— 把"内核命中"与"报告实现"解耦地接起来。
 *
 * <p>取值顺序：显式 {@link #setInstance} 注入（测试替身/运行时切换）优先，其次 SPI
 * （{@code META-INF/services/...RouteEvidenceSink}，由 pw-web-ui 等实现模块声明），都没有则为
 * {@code null} —— 此时所有上报都是<b>静默空操作</b>：观测是旁路，缺失实现绝不影响路由主流程。</p>
 *
 * <p><b>失败语义</b>：{@link #record}/{@link #flush} 吞掉实现抛出的任何异常（仅记 DEBUG），
 * 理由同上 —— 报告写坏了也不能让用例因"观测失败"而失败。</p>
 */
public final class RouteEvidenceRegistry {

    private static final Logger LOGGER = LoggerFactory.getLogger(RouteEvidenceRegistry.class);

    /** 当前实现（可运行时替换；volatile 保证可见性）。 */
    private static volatile RouteEvidenceSink sink = loadFromSpi();

    private RouteEvidenceRegistry() {
    }

    /** 显式注入实现（用于测试替身或非 SPI 环境）。传 {@code null} 即关闭上报。 */
    public static void setInstance(RouteEvidenceSink instance) {
        sink = instance;
    }

    /** 当前实现；无实现时返回 {@code null}。 */
    public static RouteEvidenceSink get() {
        return sink;
    }

    /** 命中处上报（任意线程）；无实现/实现异常一律静默。 */
    public static void record(String operation, String url, String detail) {
        RouteEvidenceSink current = sink;
        if (current == null) {
            return;
        }
        try {
            current.record(operation, url, detail);
        } catch (RuntimeException e) {
            LOGGER.debug("[Route] evidence record skipped (non-fatal): {}", e.toString());
        }
    }

    /** 主线程刷入报告；无实现/实现异常一律静默。 */
    public static void flush() {
        RouteEvidenceSink current = sink;
        if (current == null) {
            return;
        }
        try {
            current.flush();
        } catch (RuntimeException e) {
            LOGGER.debug("[Route] evidence flush skipped (non-fatal): {}", e.toString());
        }
    }

    /** SPI 兜底加载（ServiceLoader 首个实现）；任何异常都视为"无实现"。 */
    private static RouteEvidenceSink loadFromSpi() {
        try {
            for (RouteEvidenceSink candidate : ServiceLoader.load(RouteEvidenceSink.class)) {
                if (candidate != null) {
                    return candidate;
                }
            }
        } catch (Exception | LinkageError e) {
            LOGGER.debug("[Route] evidence sink SPI not available: {}", e.toString());
        }
        return null;
    }
}
