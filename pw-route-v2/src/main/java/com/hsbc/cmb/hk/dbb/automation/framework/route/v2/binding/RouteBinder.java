package com.hsbc.cmb.hk.dbb.automation.framework.route.v2.binding;

import com.hsbc.cmb.hk.dbb.automation.framework.route.v2.RouteRuntime;
import com.hsbc.cmb.hk.dbb.automation.framework.route.v2.dsl.ApiSpec;
import com.microsoft.playwright.BrowserContext;

/**
 * 路由绑定 SPI —— 把一条规则绑定到驱动（Playwright 原生 {@code context.route}）的抽象契约。
 *
 * <p>设计动机：{@link PatternBinder} 是绑定到 Playwright 驱动的具体实现，但其创建逻辑原本被
 * {@link RouteRuntime#register} 硬编码直接调用。抽成本 SPI 后：
 * <ul>
 *   <li><b>可插拔</b>：测试可注册 Mock 绑定（不真连驱动），将来可换不同驱动绑定策略；</li>
 *   <li><b>解耦</b>：调用方经 {@link RouteBinderRegistry} 取实现，不硬编码依赖具体绑定类；</li>
 *   <li><b>fail-safe</b>：classpath 无注册或加载失败时 {@link RouteBinderRegistry} 回退内置默认
 *       （{@link PatternBinder}），等价于默认开启，行为零回归。</li>
 * </ul>
 *
 * <p>契约：{@link #bind} 必须恰好执行一次驱动注册（幂等由 {@link PatternBinder} 的原子标志保证），
 * 返回 {@link PatternBinder}（{@link AutoCloseable}，{@code close()} 幂等注销）。实现类须为
 * 无状态、线程安全（{@code bind} 可能被多个 context 并发调用）。</p>
 *
 * <p>注册方式：在 {@code META-INF/services/} 下以本接口全限定名为文件名、实现类全限定名为内容登记。</p>
 */
public interface RouteBinder {

    /**
     * 创建并注册一条规则到驱动的绑定。
     *
     * @param context 目标 BrowserContext
     * @param spec    规则（pattern 取自 spec）
     * @param runtime 所属运行时（事件转发目标）
     * @return 绑定句柄（{@link AutoCloseable}，关闭即注销）；非 null
     */
    PatternBinder bind(BrowserContext context, ApiSpec spec, RouteRuntime runtime);
}
