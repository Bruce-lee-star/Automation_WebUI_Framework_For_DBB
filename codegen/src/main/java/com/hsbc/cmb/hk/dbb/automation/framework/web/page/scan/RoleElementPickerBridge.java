package com.hsbc.cmb.hk.dbb.automation.framework.web.page.scan;

import com.hsbc.cmb.hk.dbb.automation.framework.web.codegen.spi.RoleCodegenBridge;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * {@link RoleCodegenBridge} 的 SPI 实现，由 {@code framework-codegen} 模块通过
 * {@code META-INF/services} 注册，在运行时注入 {@code framework-web}。
 *
 * <p>所有方法直接委托给同包（{@code framework.web.page.scan}）的拾取器 / 生成器实现，
 * 自身不包含任何业务状态——仅作 web 与 scan 之间的桥接点，从而让 web 对 codegen 保持零编译依赖。
 *
 * <p>线程安全：本类无字段，全部为无状态委托，可安全并发。
 */
public final class RoleElementPickerBridge implements RoleCodegenBridge {

    private static final Logger log = LoggerFactory.getLogger(RoleElementPickerBridge.class);

    @Override
    public boolean isCodegenEnabled() {
        return RoleElementPicker.isCodegenEnabled();
    }

    @Override
    public void markFrameworkClose(Page page) {
        RoleElementPicker.markFrameworkClose(page);
    }

    @Override
    public void cleanupContext(BrowserContext ctx) {
        RoleElementPicker.cleanupContext(ctx);
    }

    @Override
    public void dumpAccessibilityRoles(Page page) {
        List<RoleEntry> entries = RoleElementPageGenerator.collectFromPage(page);
        if (entries.isEmpty()) {
            log.warn("[a11y] 未采集到可交互元素（页面可能尚未就绪或无匹配角色）");
            return;
        }
        StringBuilder sb = new StringBuilder(
                "\n========== Accessibility roles (role = name) ==========\n");
        for (RoleEntry e : entries) {
            sb.append(e.getRole()).append(" = ")
              .append(e.getName() == null ? "" : e.getName()).append('\n');
        }
        log.info(sb.toString());
    }
}
