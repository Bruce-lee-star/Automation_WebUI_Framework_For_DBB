package com.hsbc.cmb.hk.dbb.automation.framework.route;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;

/**
 * {@link RouteRuntimeFactory} SPI 注册中心 —— 惰性、线程安全地经 {@link ServiceLoader} 发现工厂实现。
 *
 * <p>语义对齐框架既有 SPI 模式（{@code MonitorFailureReportSink} / {@code RouteBinderRegistry}）：
 * <ul>
 *   <li><b>惰性解析</b>：首次 {@link #instance()} 才触发 {@code ServiceLoader.load}，双检锁保证只解析一次；</li>
 *   <li><b>失败回退</b>：classpath 无注册、加载抛错时，回退内置默认 {@link DefaultRouteRuntimeFactory}，
 *       行为零回归；</li>
 *   <li><b>可注入</b>：{@link #setInstance} / {@link #reset} 供测试替换/复位（同源 WEB-P0-2 的 setProvider 门面 seam）。</li>
 * </ul>
 *
 * <p>线程安全：{@code volatile instance} + {@code synchronized} 双检锁；{@code ServiceLoader} 每次
 * 调用都新建迭代器（JDK 文档要求），故解析在锁内完成、结果缓存到 {@code instance}，读路径无锁。</p>
 */
public final class RouteRuntimeFactoryRegistry {

    private static final Logger LOGGER = LoggerFactory.getLogger(RouteRuntimeFactoryRegistry.class);

    private static volatile RouteRuntimeFactory instance;
    private static final Object LOCK = new Object();

    private RouteRuntimeFactoryRegistry() {
    }

    /**
     * 取当前生效的工厂实现（惰性解析 + 双检锁）。
     *
     * @return 非 null 的 {@link RouteRuntimeFactory}（SPI 第一个实现，或内置默认）
     */
    public static RouteRuntimeFactory instance() {
        RouteRuntimeFactory factory = instance;
        if (factory == null) {
            synchronized (LOCK) {
                factory = instance;
                if (factory == null) {
                    instance = factory = resolve();
                }
            }
        }
        return factory;
    }

    private static RouteRuntimeFactory resolve() {
        try {
            ServiceLoader<RouteRuntimeFactory> loader = ServiceLoader.load(RouteRuntimeFactory.class);
            List<RouteRuntimeFactory> found = new ArrayList<>();
            for (RouteRuntimeFactory f : loader) {
                found.add(f);
            }
            if (!found.isEmpty()) {
                RouteRuntimeFactory first = found.get(0);
                LOGGER.debug("[Route] RouteRuntimeFactory SPI resolved to '{}' ({} implementation(s) registered)",
                        first.getClass().getName(), found.size());
                return first;
            }
        } catch (Throwable t) {
            // 捕获 Throwable（含 LinkageError/ServiceConfigurationError），绝不阻断调用方
            LOGGER.warn("[Route] RouteRuntimeFactory SPI load failed, fallback to default: {}", t.toString());
        }
        LOGGER.debug("[Route] RouteRuntimeFactory SPI not present on classpath, fallback to DefaultRouteRuntimeFactory");
        return new DefaultRouteRuntimeFactory();
    }

    /**
     * 测试/注入用：显式设置工厂实现（覆盖 SPI 解析结果）。
     *
     * @param factory 非 null 的工厂实现
     * @throws IllegalArgumentException 若 factory 为 null
     */
    public static void setInstance(RouteRuntimeFactory factory) {
        instance = java.util.Objects.requireNonNull(factory, "factory");
    }

    /** 复位为默认（下次 {@link #instance()} 重新经 SPI 解析）。 */
    public static void reset() {
        instance = null;
    }
}
