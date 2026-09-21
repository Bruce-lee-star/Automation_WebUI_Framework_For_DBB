package com.hsbc.cmb.hk.dbb.automation.framework.web.codegen.spi;

import java.util.Optional;
import java.util.ServiceLoader;

/**
 * 持有可选的 {@link RoleCodegenBridge} 实现（由 {@code framework-codegen} 通过 SPI 注入）。
 *
 * <p>解析结果按 JVM 进程惰性缓存（classpath 静态，无需刷新）。当 classpath 中无 codegen 模块、
 * 或 SPI 解析抛任何异常时，{@link #getBridge()} 安全降级为 {@link Optional#empty()}，
 * 调用方据此走「codegen 未启用」分支——与原默认关闭语义一致，<b>零回归</b>。
 *
 * <p>线程安全：双检锁 + {@code volatile}，可安全并发调用。
 *
 * @apiNote 仅 web 核心层使用；codegen 模块不得反向依赖本类（SPI 由 JDK 标准机制完成解耦）。
 */
public final class RoleCodegenBridgeRegistry {

    private static volatile Optional<RoleCodegenBridge> resolved;

    private RoleCodegenBridgeRegistry() {
    }

    /**
     * 解析并返回当前 classpath 中的 codegen 桥接实现（若有）。
     *
     * @return 存在实现则返回 {@link Optional#of}，否则 {@link Optional#empty()}
     */
    public static Optional<RoleCodegenBridge> getBridge() {
        if (resolved == null) {
            synchronized (RoleCodegenBridgeRegistry.class) {
                if (resolved == null) {
                    resolved = resolveViaSpi();
                }
            }
        }
        return resolved;
    }

    private static Optional<RoleCodegenBridge> resolveViaSpi() {
        try {
            ServiceLoader<RoleCodegenBridge> loader = ServiceLoader.load(
                    RoleCodegenBridge.class,
                    RoleCodegenBridgeRegistry.class.getClassLoader());
            for (RoleCodegenBridge bridge : loader) {
                // 取首个注册实现（codegen 模块唯一提供方）
                return Optional.of(bridge);
            }
        } catch (Throwable t) {
            // SPI 解析失败（如 classpath 缺 codegen 模块、实现类初始化异常）属预期的可选场景，
            // 静默降级为空，不污染框架核心热路径。
            return Optional.empty();
        }
        return Optional.empty();
    }

    /**
     * 重置惰性缓存。仅用于单元测试，便于在隔离测试中重新触发 SPI 解析。
     */
    static void reset() {
        resolved = null;
    }
}
