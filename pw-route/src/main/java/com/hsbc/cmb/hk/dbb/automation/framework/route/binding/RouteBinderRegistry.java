package com.hsbc.cmb.hk.dbb.automation.framework.route.binding;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;

/**
 * {@link RouteBinder} SPI 注册中心 —— 惰性、线程安全地经 {@link ServiceLoader} 发现绑定实现。
 *
 * <p>语义对齐框架既有 SPI 模式（见 {@code MonitorFailureReportSink} / {@code RoleCodegenBridgeRegistry}）：
 * <ul>
 *   <li><b>惰性解析</b>：首次 {@link #instance()} 才触发 {@code ServiceLoader.load}，双检锁保证只解析一次；</li>
 *   <li><b>失败回退</b>：classpath 无注册、加载抛错或被 security manager 阻断时，回退内置默认
 *       （{@link PatternBinder} 的静态工厂），行为零回归；</li>
 *   <li><b>可注入</b>：{@link #setInstance} / {@link #reset} 供测试替换/复位（同源 WEB-P0-2 的 setProvider 门面 seam）。</li>
 * </ul>
 *
 * <p>线程安全：{@code volatile instance} + {@code synchronized} 双检锁；{@code ServiceLoader} 每次
 * 调用都新建迭代器（JDK 文档要求），故解析在锁内完成、结果缓存到 {@code instance}，读路径无锁。</p>
 */
public final class RouteBinderRegistry {

    private static final Logger LOGGER = LoggerFactory.getLogger(RouteBinderRegistry.class);

    private static volatile RouteBinder instance;
    private static final Object LOCK = new Object();

    private RouteBinderRegistry() {
    }

    /**
     * 取当前生效的绑定实现（惰性解析 + 双检锁）。
     *
     * @return 非 null 的 {@link RouteBinder}（SPI 第一个实现，或内置默认）
     */
    public static RouteBinder instance() {
        RouteBinder binder = instance;
        if (binder == null) {
            synchronized (LOCK) {
                binder = instance;
                if (binder == null) {
                    instance = binder = resolve();
                }
            }
        }
        return binder;
    }

    private static RouteBinder resolve() {
        try {
            ServiceLoader<RouteBinder> loader = ServiceLoader.load(RouteBinder.class);
            List<RouteBinder> found = new ArrayList<>();
            for (RouteBinder b : loader) {
                found.add(b);
            }
            if (!found.isEmpty()) {
                RouteBinder first = found.get(0);
                LOGGER.debug("[Route] RouteBinder SPI resolved to '{}' ({} implementation(s) registered)",
                        first.getClass().getName(), found.size());
                return first;
            }
        } catch (Throwable t) {
            // 捕获 Throwable（含 LinkageError/ServiceConfigurationError），绝不阻断调用方
            LOGGER.warn("[Route] RouteBinder SPI load failed, fallback to default PatternBinder: {}", t.toString());
        }
        LOGGER.debug("[Route] RouteBinder SPI not present on classpath, fallback to default DefaultRouteBinder");
        return new DefaultRouteBinder();
    }

    /**
     * 测试/注入用：显式设置实现（覆盖 SPI 解析结果）。
     *
     * @param binder 非 null 的绑定实现
     * @throws IllegalArgumentException 若 binder 为 null
     */
    public static void setInstance(RouteBinder binder) {
        instance = java.util.Objects.requireNonNull(binder, "binder");
    }

    /** 复位为默认（下次 {@link #instance()} 重新经 SPI 解析）。 */
    public static void reset() {
        instance = null;
    }
}
