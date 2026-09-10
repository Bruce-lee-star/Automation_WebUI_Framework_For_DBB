package com.hsbc.cmb.hk.dbb.automation.framework.web.page.base;

import com.hsbc.cmb.hk.dbb.automation.framework.web.codegen.spi.RoleCodegenBridgeRegistry;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.base.BasePage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 可访问性操作工厂（WEB-P1-2 Phase 4）：从 {@link BasePage} 下沉 {@code dumpAccessibilityRoles}。
 *
 * <p>与 {@link LocatorFactory}/{@link CookieManager}/{@link PageFrameShadow}/{@link PageViewport}
 * 同源模式。经 codegen SPI 桥接 dump 角色（classpath 无 framework-codegen 时降级跳过），
 * 日志路由回 {@code BasePage.class}。
 *
 * <p>本类方法为 framework-internal。
 */
public final class PageAccessibility {

    private static final Logger log = LoggerFactory.getLogger(BasePage.class);

    private PageAccessibility() {
    }

    /**
     * 打印当前页面中可交互元素的 {@code role = name}，
     * 便于据此编写 {@code @RoleElement(role = ..., key = ...)} 注解。
     * 用法：临时在测试里调用 {@code loginPage.dumpAccessibilityRoles();}，
     * 查看控制台输出后，把每行 {@code role = name} 抄进注解即可。
     * <p>注意：基于注入脚本遍历 DOM 的 computedRole/computedName（兼容无 Playwright
     * accessibilitySnapshot API 的版本），仅覆盖主 frame；iframe 内元素请对对应 frame 调用。
     */
    public static void dumpAccessibilityRoles(BasePage bp) {
        bp.ensurePageValid();
        // 经 codegen 桥接：codegen 模块在 classpath 时复刻原 a11y dump 行为；
        // 不在时降级跳过（codegen 已成为可选模块，见架构整改计划 T2-2）。
        RoleCodegenBridgeRegistry.getBridge().ifPresentOrElse(
                b -> b.dumpAccessibilityRoles(bp.getPage()),
                () -> log.warn(
                        "[a11y] codegen 模块（framework-codegen）未加载，跳过可访问性角色 dump"));
    }
}
