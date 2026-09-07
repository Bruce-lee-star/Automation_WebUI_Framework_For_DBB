package com.hsbc.cmb.hk.dbb.automation.framework.web.codegen.spi;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;

/**
 * 框架与「代码生成器（角色元素拾取器 / Page Object 生成器）」之间的编译期解耦契约。
 *
 * <p>web 核心层（页面生命周期 {@code PageLifecycleCoordinator}、Context 生命周期 {@code PlaywrightManager}、
 * 门面 {@code BasePage#dumpAccessibilityRoles()}）仅依赖本接口与 {@link RoleCodegenBridgeRegistry}，
 * <b>绝不直接引用 {@code framework.web.page.scan} 下的任何实现类</b>，从而保持
 * {@code framework-web} 对 {@code framework-codegen} 的零编译依赖。
 *
 * <p>实现由可选的 {@code framework-codegen} 模块通过 {@code java.util.ServiceLoader}（SPI）在运行时注入。
 * 当 classpath 中不存在该模块时，{@link RoleCodegenBridgeRegistry#getBridge()} 返回 {@code empty}，
 * 调用方据此走「codegen 未启用」分支——与原默认关闭行为完全一致，<b>零回归</b>。
 *
 * @apiNote 这是 web 模块对外暴露的唯一 codegen 协作面。任何业务 Page 或框架内部类都不应直接依赖
 *          scan 包的实现类；如需扩展 codegen 行为，请实现本接口并在 codegen 模块内通过 SPI 注册。
 */
public interface RoleCodegenBridge {

    /**
     * 是否启用 codegen（页面对象自动生成）的运行时协作。
     * 默认关闭：框架热路径（页面关闭 / Context 关闭）不会触碰 codegen，
     * 避免对自动化测试引入无关开销与依赖。
     *
     * @return {@code true} 表示已通过 {@code -Ddbb.codegen.enabled=true} 激活
     */
    boolean isCodegenEnabled();

    /**
     * 标记给定页面为「框架主动关闭」，使其 onClose 钩子不再补登记 closeCurrentPage 步骤
     * （代码已显式调用 closeCurrentPage，重复登记会导致回放重复关闭）。
     *
     * @param page 框架主动关闭的 Playwright 页面，不可为 {@code null}
     */
    void markFrameworkClose(Page page);

    /**
     * 清理给定 BrowserContext 下 codegen 维护的会话态（拾取模式 / 最近拾取信号等）。
     *
     * @param ctx 待清理的 BrowserContext，不可为 {@code null}
     */
    void cleanupContext(BrowserContext ctx);

    /**
     * 打印当前页面中可交互元素的 {@code role = name}，便于据此编写 {@code @RoleElement} 注解。
     * 本方法<b>不门控</b> {@link #isCodegenEnabled()}，只要 codegen 实现存在即执行（与原 web 内置行为一致）；
     * 当 codegen 模块不在 classpath 时由调用方降级（跳过 dump）。
     *
     * @param page 待 dump 的 Playwright 页面，不可为 {@code null}
     */
    void dumpAccessibilityRoles(Page page);
}
