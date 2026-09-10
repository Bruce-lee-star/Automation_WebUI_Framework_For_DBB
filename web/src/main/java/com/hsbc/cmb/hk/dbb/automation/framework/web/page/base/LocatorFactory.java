package com.hsbc.cmb.hk.dbb.automation.framework.web.page.base;

import com.microsoft.playwright.options.AriaRole;
import com.microsoft.playwright.Frame;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.RoleElement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.regex.Pattern;

/**
 * 定位器工厂（WEB-P1-2 Phase 1）：从 {@link BasePage} 下沉全部 {@code by*} 定位器方法。
 *
 * <p>与 {@link PageFrameShadow} 同源模式：静态工具类、接收 {@code BasePage bp}、经 bp 的
 * 公开 API（{@code getPage} / {@code getCurrentFrame}）与包级 seam（{@code ensurePageValid}）
 * 访问状态，日志路由回 {@code BasePage.class} 保持生产溯源一致。
 *
 * <p>所有 {@code by*} 均为 framework-internal（受 ArchUnit 规则
 * {@code businessCodeMustNotUseInternalByLocators} 约束），业务不得直接调用。
 */
public final class LocatorFactory {

    private static final Logger log = LoggerFactory.getLogger(BasePage.class);

    private LocatorFactory() {
    }

    private static void requireNonNullPage(BasePage bp) {
        if (bp == null) {
            throw new IllegalArgumentException("BasePage instance must not be null when building locators");
        }
    }

    public static Locator byAltText(BasePage bp, String altText) {
        requireNonNullPage(bp);
        bp.ensurePageValid();
        Frame frame = bp.getCurrentFrame();
        Page page = bp.getPage();
        return (frame != null) ? frame.getByAltText(altText) : page.getByAltText(altText);
    }

    public static Locator byRole(BasePage bp, AriaRole role) {
        requireNonNullPage(bp);
        bp.ensurePageValid();
        Frame frame = bp.getCurrentFrame();
        Page page = bp.getPage();
        return (frame != null) ? frame.getByRole(role) : page.getByRole(role);
    }

    public static Locator byRole(BasePage bp, AriaRole role, String name) {
        return byRole(bp, role, name, true);
    }

    public static Locator byRole(BasePage bp, AriaRole role, Pattern namePattern) {
        requireNonNullPage(bp);
        bp.ensurePageValid();
        Frame frame = bp.getCurrentFrame();
        Page page = bp.getPage();
        return (frame != null)
                ? frame.getByRole(role, new Frame.GetByRoleOptions().setName(namePattern))
                : page.getByRole(role, new Page.GetByRoleOptions().setName(namePattern));
    }

    public static Locator byRole(BasePage bp, AriaRole role, String name, boolean exact) {
        requireNonNullPage(bp);
        bp.ensurePageValid();
        Frame frame = bp.getCurrentFrame();
        Page page = bp.getPage();
        return (frame != null)
                ? frame.getByRole(role, new Frame.GetByRoleOptions().setName(name).setExact(exact))
                : page.getByRole(role, new Page.GetByRoleOptions().setName(name).setExact(exact));
    }

    public static Locator byRole(BasePage bp, AriaRole role, String name, boolean exact, int level) {
        requireNonNullPage(bp);
        bp.ensurePageValid();
        Frame frame = bp.getCurrentFrame();
        Page page = bp.getPage();
        Page.GetByRoleOptions popts = new Page.GetByRoleOptions().setName(name).setExact(exact);
        Frame.GetByRoleOptions fopts = new Frame.GetByRoleOptions().setName(name).setExact(exact);
        if (level > 0) {
            popts = popts.setLevel(level);
            fopts = fopts.setLevel(level);
        }
        return (frame != null) ? frame.getByRole(role, fopts) : page.getByRole(role, popts);
    }

    public static Locator byRole(BasePage bp, AriaRole role, Pattern namePattern, int level) {
        requireNonNullPage(bp);
        bp.ensurePageValid();
        Frame frame = bp.getCurrentFrame();
        Page page = bp.getPage();
        Page.GetByRoleOptions popts = new Page.GetByRoleOptions().setName(namePattern);
        Frame.GetByRoleOptions fopts = new Frame.GetByRoleOptions().setName(namePattern);
        if (level > 0) {
            popts = popts.setLevel(level);
            fopts = fopts.setLevel(level);
        }
        return (frame != null) ? frame.getByRole(role, fopts) : page.getByRole(role, popts);
    }

    public static Locator byRole(BasePage bp, AriaRole role, String name, boolean exact, int level,
                                 RoleElement.State disabled, RoleElement.State pressed, RoleElement.State expanded) {
        requireNonNullPage(bp);
        bp.ensurePageValid();
        Frame frame = bp.getCurrentFrame();
        Page page = bp.getPage();
        Page.GetByRoleOptions popts = new Page.GetByRoleOptions().setName(name).setExact(exact);
        Frame.GetByRoleOptions fopts = new Frame.GetByRoleOptions().setName(name).setExact(exact);
        if (level > 0) {
            popts = popts.setLevel(level);
            fopts = fopts.setLevel(level);
        }
        if (disabled != null && disabled != RoleElement.State.ANY) popts = popts.setDisabled(disabled == RoleElement.State.YES);
        if (pressed != null && pressed != RoleElement.State.ANY) popts = popts.setPressed(pressed == RoleElement.State.YES);
        if (expanded != null && expanded != RoleElement.State.ANY) popts = popts.setExpanded(expanded == RoleElement.State.YES);
        if (disabled != null && disabled != RoleElement.State.ANY) fopts = fopts.setDisabled(disabled == RoleElement.State.YES);
        if (pressed != null && pressed != RoleElement.State.ANY) fopts = fopts.setPressed(pressed == RoleElement.State.YES);
        if (expanded != null && expanded != RoleElement.State.ANY) fopts = fopts.setExpanded(expanded == RoleElement.State.YES);
        return (frame != null) ? frame.getByRole(role, fopts) : page.getByRole(role, popts);
    }

    public static Locator byRole(BasePage bp, AriaRole role, Pattern namePattern, int level,
                                 RoleElement.State disabled, RoleElement.State pressed, RoleElement.State expanded) {
        requireNonNullPage(bp);
        bp.ensurePageValid();
        Frame frame = bp.getCurrentFrame();
        Page page = bp.getPage();
        Page.GetByRoleOptions popts = new Page.GetByRoleOptions().setName(namePattern);
        Frame.GetByRoleOptions fopts = new Frame.GetByRoleOptions().setName(namePattern);
        if (level > 0) {
            popts = popts.setLevel(level);
            fopts = fopts.setLevel(level);
        }
        if (disabled != null && disabled != RoleElement.State.ANY) popts = popts.setDisabled(disabled == RoleElement.State.YES);
        if (pressed != null && pressed != RoleElement.State.ANY) popts = popts.setPressed(pressed == RoleElement.State.YES);
        if (expanded != null && expanded != RoleElement.State.ANY) popts = popts.setExpanded(expanded == RoleElement.State.YES);
        if (disabled != null && disabled != RoleElement.State.ANY) fopts = fopts.setDisabled(disabled == RoleElement.State.YES);
        if (pressed != null && pressed != RoleElement.State.ANY) fopts = fopts.setPressed(pressed == RoleElement.State.YES);
        if (expanded != null && expanded != RoleElement.State.ANY) fopts = fopts.setExpanded(expanded == RoleElement.State.YES);
        return (frame != null) ? frame.getByRole(role, fopts) : page.getByRole(role, popts);
    }

    public static Locator byTitle(BasePage bp, String title) {
        requireNonNullPage(bp);
        bp.ensurePageValid();
        Frame frame = bp.getCurrentFrame();
        Page page = bp.getPage();
        return (frame != null) ? frame.getByTitle(title) : page.getByTitle(title);
    }

    public static Locator byTestId(BasePage bp, String testId) {
        requireNonNullPage(bp);
        bp.ensurePageValid();
        Frame frame = bp.getCurrentFrame();
        Page page = bp.getPage();
        // P1 兼容性：覆盖 data-testid/data-test-id/data-test/data-qa 四种常见测试属性
        String sel = "[data-testid=\"" + testId + "\"],[data-test-id=\"" + testId
                + "\"],[data-test=\"" + testId + "\"],[data-qa=\"" + testId + "\"]";
        return (frame != null) ? frame.locator(sel) : page.locator(sel);
    }

    public static Locator byText(BasePage bp, String text) {
        return byText(bp, text, true);
    }

    public static Locator byText(BasePage bp, String text, boolean exact) {
        requireNonNullPage(bp);
        bp.ensurePageValid();
        Frame frame = bp.getCurrentFrame();
        Page page = bp.getPage();
        return (frame != null)
                ? frame.getByText(text, new Frame.GetByTextOptions().setExact(exact))
                : page.getByText(text, new Page.GetByTextOptions().setExact(exact));
    }

    public static Locator byAltText(BasePage bp, String altText, boolean exact) {
        requireNonNullPage(bp);
        bp.ensurePageValid();
        Frame frame = bp.getCurrentFrame();
        Page page = bp.getPage();
        return (frame != null)
                ? frame.getByAltText(altText, new Frame.GetByAltTextOptions().setExact(exact))
                : page.getByAltText(altText, new Page.GetByAltTextOptions().setExact(exact));
    }

    public static Locator byTitle(BasePage bp, String title, boolean exact) {
        requireNonNullPage(bp);
        bp.ensurePageValid();
        Frame frame = bp.getCurrentFrame();
        Page page = bp.getPage();
        return (frame != null)
                ? frame.getByTitle(title, new Frame.GetByTitleOptions().setExact(exact))
                : page.getByTitle(title, new Page.GetByTitleOptions().setExact(exact));
    }

    public static Locator byPlaceholder(BasePage bp, String placeholder) {
        return byPlaceholder(bp, placeholder, true);
    }

    public static Locator byPlaceholder(BasePage bp, String placeholder, boolean exact) {
        requireNonNullPage(bp);
        bp.ensurePageValid();
        Frame frame = bp.getCurrentFrame();
        Page page = bp.getPage();
        return (frame != null)
                ? frame.getByPlaceholder(placeholder, new Frame.GetByPlaceholderOptions().setExact(exact))
                : page.getByPlaceholder(placeholder, new Page.GetByPlaceholderOptions().setExact(exact));
    }

    public static Locator byLabel(BasePage bp, String label) {
        return byLabel(bp, label, true);
    }

    public static Locator byLabel(BasePage bp, String label, boolean exact) {
        requireNonNullPage(bp);
        bp.ensurePageValid();
        Frame frame = bp.getCurrentFrame();
        Page page = bp.getPage();
        return (frame != null)
                ? frame.getByLabel(label, new Frame.GetByLabelOptions().setExact(exact))
                : page.getByLabel(label, new Page.GetByLabelOptions().setExact(exact));
    }

    public static Locator byText(BasePage bp, Pattern text) {
        requireNonNullPage(bp);
        bp.ensurePageValid();
        Frame frame = bp.getCurrentFrame();
        Page page = bp.getPage();
        return (frame != null) ? frame.getByText(text) : page.getByText(text);
    }

    public static Locator byAltText(BasePage bp, Pattern altText) {
        requireNonNullPage(bp);
        bp.ensurePageValid();
        Frame frame = bp.getCurrentFrame();
        Page page = bp.getPage();
        return (frame != null) ? frame.getByAltText(altText) : page.getByAltText(altText);
    }

    public static Locator byTitle(BasePage bp, Pattern title) {
        requireNonNullPage(bp);
        bp.ensurePageValid();
        Frame frame = bp.getCurrentFrame();
        Page page = bp.getPage();
        return (frame != null) ? frame.getByTitle(title) : page.getByTitle(title);
    }

    public static Locator byPlaceholder(BasePage bp, Pattern placeholder) {
        requireNonNullPage(bp);
        bp.ensurePageValid();
        Frame frame = bp.getCurrentFrame();
        Page page = bp.getPage();
        return (frame != null) ? frame.getByPlaceholder(placeholder) : page.getByPlaceholder(placeholder);
    }

    public static Locator byLabel(BasePage bp, Pattern label) {
        requireNonNullPage(bp);
        bp.ensurePageValid();
        Frame frame = bp.getCurrentFrame();
        Page page = bp.getPage();
        return (frame != null) ? frame.getByLabel(label) : page.getByLabel(label);
    }
}
