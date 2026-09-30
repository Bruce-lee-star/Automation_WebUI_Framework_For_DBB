package com.hsbc.cmb.hk.dbb.automation.framework.route.binding;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;

/**
 * {@link GuardedDriverCall} SPI 注册中心 —— 惰性、线程安全地经 {@link ServiceLoader} 发现实现。
 *
 * <p>语义对齐框架既有 SPI 模式（{@code RouteBinderRegistry} / {@code RouteRuntimeFactoryRegistry}）：
 * <ul>
 *   <li><b>惰性解析</b>：首次 {@link #instance()} 才触发 {@code ServiceLoader.load}，双检锁保证只解析一次；</li>
 *   <li><b>失败回退</b>：classpath 无注册、加载抛错时，回退内置默认 {@link GuardedDriverCallImpl}，零回归；</li>
 *   <li><b>可注入</b>：{@link #setInstance} / {@link #reset} 供测试替换/复位（真多态 seam）。</li>
 * </ul>
 *
 * <p>线程安全：{@code volatile instance} + {@code synchronized} 双检锁；{@code ServiceLoader} 每次
 * 调用都新建迭代器（JDK 文档要求），故解析在锁内完成、结果缓存到 {@code instance}，读路径无锁。</p>
 */
public final class GuardedDriverCallRegistry {

    private static final Logger LOGGER = LoggerFactory.getLogger(GuardedDriverCallRegistry.class);

    private static volatile GuardedDriverCall instance;
    private static final Object LOCK = new Object();

    private GuardedDriverCallRegistry() {
    }

    /**
     * 取当前生效的守护原语实现（惰性解析 + 双检锁）。
     *
     * @return 非 null 的 {@link GuardedDriverCall}（SPI 第一个实现，或内置默认）
     */
    public static GuardedDriverCall instance() {
        GuardedDriverCall guard = instance;
        if (guard == null) {
            synchronized (LOCK) {
                guard = instance;
                if (guard == null) {
                    instance = guard = resolve();
                }
            }
        }
        return guard;
    }

    private static GuardedDriverCall resolve() {
        try {
            ServiceLoader<GuardedDriverCall> loader = ServiceLoader.load(GuardedDriverCall.class);
            List<GuardedDriverCall> found = new ArrayList<>();
            for (GuardedDriverCall g : loader) {
                found.add(g);
            }
            if (!found.isEmpty()) {
                GuardedDriverCall first = found.get(0);
                LOGGER.debug("[Route] GuardedDriverCall SPI resolved to '{}' ({} implementation(s) registered)",
                        first.getClass().getName(), found.size());
                return first;
            }
        } catch (Throwable t) {
            // 捕获 Throwable（含 LinkageError/ServiceConfigurationError），绝不阻断调用方
            LOGGER.warn("[Route] GuardedDriverCall SPI load failed, fallback to default: {}", t.toString());
        }
        LOGGER.debug("[Route] GuardedDriverCall SPI not present on classpath, fallback to GuardedDriverCallImpl");
        return new GuardedDriverCallImpl();
    }

    /**
     * 测试/注入用：显式设置守护原语实现（覆盖 SPI 解析结果）。
     *
     * @param guard 非 null 的原语实现
     * @throws IllegalArgumentException 若 guard 为 null
     */
    public static void setInstance(GuardedDriverCall guard) {
        instance = java.util.Objects.requireNonNull(guard, "guard");
    }

    /** 复位为默认（下次 {@link #instance()} 重新经 SPI 解析）。 */
    public static void reset() {
        instance = null;
    }
}
