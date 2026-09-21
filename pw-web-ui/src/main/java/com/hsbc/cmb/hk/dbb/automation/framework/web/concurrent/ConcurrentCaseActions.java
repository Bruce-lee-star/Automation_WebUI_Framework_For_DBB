package com.hsbc.cmb.hk.dbb.automation.framework.web.concurrent;

import java.util.ServiceLoader;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 并发领域动作的注册中心（框架内部）。
 *
 * <p>业务以两种零侵入方式之一向框架提供 {@link ConcurrentCaseAction}：
 * <ul>
 *   <li><b>SPI 自动发现（推荐）</b>：在测试模块 {@code META-INF/services/
 *       com.hsbc.cmb.hk.dbb.automation.framework.web.concurrent.ConcurrentCaseAction}
 *       列出实现类全限定名，框架经 {@link ServiceLoader} 惰性发现，业务<b>零注册代码</b>。</li>
 *   <li><b>显式注册</b>：在业务 {@code @Before} 钩子里调
 *       {@link #register(ConcurrentCaseAction)}（如需要运行期切换实现）。</li>
 * </ul>
 * 框架并发 glue 在编排线程经 {@link #require()} 取得动作；若两者皆无则抛语义化异常，
 * 提示业务提供实现。</p>
 *
 * <p>线程安全：注册基于 {@link AtomicReference}，显式注册可覆盖 SPI 默认实现。</p>
 */
public final class ConcurrentCaseActions {

    private static final AtomicReference<ConcurrentCaseAction> REGISTERED = new AtomicReference<>();

    private ConcurrentCaseActions() {
    }

    /** 显式注册（可覆盖 SPI 发现的实现）。 */
    public static void register(ConcurrentCaseAction action) {
        REGISTERED.set(java.util.Objects.requireNonNull(action, "ConcurrentCaseAction must not be null"));
    }

    /**
     * 清空显式注册（包级私有，仅供框架单测复位全局状态，不影响 SPI 发现与运行期行为）。
     */
    static void reset() {
        REGISTERED.set(null);
    }

    /**
     * 取得已注册的动作：优先显式注册，回退 SPI 发现；二者皆无抛语义化异常。
     *
     * @throws IllegalStateException 未提供任何 {@link ConcurrentCaseAction} 实现
     */
    public static ConcurrentCaseAction require() {
        ConcurrentCaseAction explicit = REGISTERED.get();
        if (explicit != null) {
            return explicit;
        }
        ConcurrentCaseAction spi = discoverViaSpi();
        if (spi != null) {
            return spi;
        }
        throw new IllegalStateException(
                "No ConcurrentCaseAction provided. Business must supply one via ConcurrentCaseActions.register(...) "
                        + "or META-INF/services SPI (file: com.hsbc.cmb.hk.dbb.automation.framework.web.concurrent.ConcurrentCaseAction)");
    }

    private static ConcurrentCaseAction discoverViaSpi() {
        ClassLoader tccl = Thread.currentThread().getContextClassLoader();
        ServiceLoader<ConcurrentCaseAction> fromTccl = ServiceLoader.load(ConcurrentCaseAction.class, tccl);
        for (ConcurrentCaseAction a : fromTccl) {
            return a;
        }
        ServiceLoader<ConcurrentCaseAction> fromClass = ServiceLoader.load(ConcurrentCaseAction.class);
        for (ConcurrentCaseAction a : fromClass) {
            return a;
        }
        return null;
    }
}
