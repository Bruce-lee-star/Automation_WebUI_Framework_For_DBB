package com.hsbc.cmb.hk.dbb.automation.framework.web.page.binding;

import com.hsbc.cmb.hk.dbb.automation.framework.web.exceptions.ElementException;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.PageElement;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.RoleElement;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.RoleFile;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.base.BasePage;
import com.hsbc.cmb.hk.dbb.automation.framework.web.utils.NLSUtils;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.options.AriaRole;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * 将 {@link RoleElement} 注解字段绑定为 {@link PageElement} 的运行时引擎。
 * <p>
 * 从 {@code BasePage} 抽离：RoleElement 是独立子系统（与 {@code scan} 包同源），
 * 其绑定逻辑（6+ 语义策略、NLS 跨文件解析、模板/可见文本归一）属于独立职责，
 * 抽离后便于在不启动浏览器的情况下做细粒度单测（直接对 {@link #bind} 验证策略选择与 key 解析）。
 * <p>
 * 所有 Locator 经由 {@code self}（持有该字段的 {@link BasePage}）的 {@code by*} 原语构建，
 * 因此绑定与页面生命周期、iframe 上下文保持一致；Locator 不缓存，页面切换后再次操作会自动重绑新 Page。
 */
public class RoleElementBinder {

    private final BasePage self;

    public RoleElementBinder(BasePage self) {
        this.self = self;
    }

    /**
     * 创建 @RoleElement 注解字段对应的 PageElement 并写回字段。
     * 支持两种策略（通过动态 Locator 供应商绑定，不缓存、页面切换后自动重绑新 Page）：
     * <ul>
     *   <li>语义定位（text / altText / title / placeholder / testId / label）：任一属性非空即启用，
     *       对应 Playwright 的 getBy* 方法，忽略 role；多语言 {@code data-i18n} 属性则直接用
     *       {@code @Element("[data-i18n=\"key\"]")}（CSS 属性选择器）表达，无需专门字段；</li>
     *   <li>角色定位（role + nls key / 字面 name）：保留多语言能力，
     *       因此 {@code NLSUtils.setLanguage("xx")} 后下次操作会自动解析为对应语言的可访问名。</li>
     * </ul>
     */
    public void bind(Field field, RoleElement a) {
        try {
            field.setAccessible(true);
            Supplier<Locator> supplier;
            String desc;

            if (a.altText() != null && !a.altText().isEmpty()) {
                desc = a.description().isEmpty() ? "altText=" + a.altText() : a.description();
                final String v = a.altText();
                // 命中 NLS（key 优先）：解析为对应语言文本后 getByAltText，支持多语言。
                if (a.key() != null && !a.key().isEmpty()) {
                    List<String> files = resolveRoleFiles(a);
                    final NLSUtils.NlsBundle bundle = NLSUtils.bind(files);
                    final String theKey = a.key();
                    desc = a.description().isEmpty() ? "altText[nls:" + files.get(0) + "#" + theKey + "]" : a.description();
                    supplier = () -> byNlsValue("altText", bundle.get(theKey), a.exact());
                } else {
                    supplier = () -> self.byAltText(v, a.exact());
                }
            } else if (a.title() != null && !a.title().isEmpty()) {
                desc = a.description().isEmpty() ? "title=" + a.title() : a.description();
                final String v = a.title();
                if (a.key() != null && !a.key().isEmpty()) {
                    List<String> files = resolveRoleFiles(a);
                    final NLSUtils.NlsBundle bundle = NLSUtils.bind(files);
                    final String theKey = a.key();
                    desc = a.description().isEmpty() ? "title[nls:" + files.get(0) + "#" + theKey + "]" : a.description();
                    supplier = () -> byNlsValue("title", bundle.get(theKey), a.exact());
                } else {
                    supplier = () -> self.byTitle(v, a.exact());
                }
            } else if (a.placeholder() != null && !a.placeholder().isEmpty()) {
                desc = a.description().isEmpty() ? "placeholder=" + a.placeholder() : a.description();
                final String v = a.placeholder();
                if (a.key() != null && !a.key().isEmpty()) {
                    List<String> files = resolveRoleFiles(a);
                    final NLSUtils.NlsBundle bundle = NLSUtils.bind(files);
                    final String theKey = a.key();
                    desc = a.description().isEmpty() ? "placeholder[nls:" + files.get(0) + "#" + theKey + "]" : a.description();
                    supplier = () -> byNlsValue("placeholder", bundle.get(theKey), a.exact());
                } else {
                    supplier = () -> self.byPlaceholder(v, a.exact());
                }
            } else if (a.testId() != null && !a.testId().isEmpty()) {
                desc = a.description().isEmpty() ? "testId=" + a.testId() : a.description();
                final String v = a.testId();
                supplier = () -> self.byTestId(v);
            } else if (a.label() != null && !a.label().isEmpty()) {
                // label 语义定位（对齐 page.pause() 的 getByLabel）：按关联 label 文本定位对应控件。
                // 与 role+name 是两条独立策略，但最终都定位到该 input 控件；label 文本本身用 text 定位。
                desc = a.description().isEmpty() ? "label=" + a.label() : a.description();
                final String v = a.label();
                if (a.key() != null && !a.key().isEmpty()) {
                    List<String> files = resolveRoleFiles(a);
                    final NLSUtils.NlsBundle bundle = NLSUtils.bind(files);
                    final String theKey = a.key();
                    desc = a.description().isEmpty() ? "label[nls:" + files.get(0) + "#" + theKey + "]" : a.description();
                    supplier = () -> byNlsValue("label", bundle.get(theKey), a.exact());
                } else {
                    supplier = () -> self.byLabel(v, a.exact());
                }
            } else if (a.text() != null && !a.text().isEmpty()) {
                desc = a.description().isEmpty() ? "text=" + a.text() : a.description();
                final String v = a.text();
                if (a.key() != null && !a.key().isEmpty()) {
                    // 命中 NLS：key 优先，解析为对应语言可见文本后按 getByText 定位，支持多语言。
                    List<String> files = resolveRoleFiles(a);
                    final NLSUtils.NlsBundle bundle = NLSUtils.bind(files);
                    final String theKey = a.key();
                    desc = a.description().isEmpty()
                            ? "text[nls:" + files.get(0) + "#" + theKey + "]"
                            : a.description();
                    supplier = () -> byNlsValue("text", bundle.get(theKey), a.exact());
                } else {
                    supplier = () -> self.byText(v, a.exact());
                }
            } else {
                // 无语义属性（text/altText/... 均未设）。此时优先按「角色定位」解析，
                // 否则退化为「仅 key 的 NLS 文本定位器」。注意：role + key 是 RoleElement 的
                // 主用场景，必须先判 role，否则 key 会被误当成 getByText 而永远走不到角色策略。
                AriaRole role = a.role();
                if (role != AriaRole.NONE) {
                    // 角色定位（role + 字面 name 或 nls key）
                    final String literalName = a.name();
                    if (literalName != null && !literalName.isEmpty()) {
                        // name 字面量覆盖：该元素名称不在 nls 中（页面上少数找不到 key 的元素），
                        // 直接用字面名称定位，跳过 nls，因此该字段本身无需 @RoleFile。
                        desc = a.description().isEmpty()
                                ? "role=" + role + "[name:" + literalName + "]"
                                : a.description();
                        final String nameVal = literalName;
                        supplier = () -> self.byRole(role, nameVal, a.exact(), a.level(), a.disabled(), a.pressed(), a.expanded());
                    } else if (a.key() != null && !a.key().isEmpty()) {
                        // role + key：走 nls 多语言解析。页面其余元素大多走这里，故类级 @RoleFile 仍需声明。
                        // 注意：必须用 resolveRoleFiles（复数）跨文件查找，与 text/altText/title 等语义路径一致；
                        // 若用 resolveRoleFile（单数，仅取首位文件），当 key 不在首位文件时就会报 missing key。
                        List<String> files = resolveRoleFiles(a);
                        final String theKey = a.key();
                        String primaryFile = files.get(0);
                        desc = a.description().isEmpty()
                                ? "role=" + role + "[nls:" + primaryFile + "#" + theKey + "]"
                                : a.description();
                        final NLSUtils.NlsBundle bundle = NLSUtils.bind(files);
                        // 懒解析（与语义路径 byNlsValue 一致）：bundle.get 放入 lambda，运行中
                        // NLSUtils.setLanguage 切语言后再次定位可解析到新语言的可访问名。
                        supplier = () -> {
                            String raw = bundle.get(theKey);
                            // 模板值（含 {{var}}）：编译为正则走 setName(Pattern)（官方原生支持，
                            // 正则模式下 exact 被忽略），与语义路径 byNlsValue 的模板处理对齐。
                            if (NLSUtils.isTemplate(raw)) {
                                return self.byRole(role, NLSUtils.templatePattern(raw), a.level(), a.disabled(), a.pressed(), a.expanded());
                            }
                            // 角色名取「可见文本」：nls 值内嵌的 <img>/&nbsp; 等会被浏览器渲染掉，
                            // 真实可访问名不含标签，故不能直接用原始字符串当 name（否则如 tab_security_device 匹配失败）。
                            return self.byRole(role, NLSUtils.visibleText(raw), a.exact(), a.level(), a.disabled(), a.pressed(), a.expanded());
                        };
                    } else {
                        // 纯 role 无 name（对齐 page.pause 的 roleWithoutName，score 510）：如
                        // <div role="listitem"> 无文本、role="img" 无 alt 的纯结构/装饰元素。
                        // 直接调用 getByRole(role) 不带 name（BasePage.byRole(AriaRole) 无 name 重载）。
                        desc = a.description().isEmpty()
                                ? "role=" + role + "[no-name]"
                                : a.description();
                        supplier = () -> self.byRole(role);
                    }
                } else if (a.key() != null && !a.key().isEmpty()) {
                    // 仅声明 key（无 role、无语义属性）：视作 NLS 文本定位器，解析 key 为对应语言可见文本后
                    // 按 getByText 定位（与 text + key 等价，但注解更简洁）。
                    List<String> files = resolveRoleFiles(a);
                    final NLSUtils.NlsBundle bundle = NLSUtils.bind(files);
                    final String theKey = a.key();
                    String primaryFile = files.get(0);
                    desc = a.description().isEmpty()
                            ? "text[nls:" + primaryFile + "#" + theKey + "]"
                            : a.description();
                    supplier = () -> byNlsValue("text", bundle.get(theKey), a.exact());
                } else {
                    throw new ElementException("RoleElement requires a role or a semantic attribute "
                            + "(altText/title/placeholder/testId/label/text): " + field.getName());
                }
            }

            // 对齐 page.pause() 的 frameLocator 录制：元素位于 iframe 内时，把底层 Locator 依次用
            // page.frameLocator(seg).locator(...) 包裹（自顶向下逐层下钻），否则在主框架上执行会找不到元素。
            String[] frames = a.frame();
            if (frames != null && frames.length > 0) {
                final Supplier<Locator> baseSupplier = supplier;
                final List<String> frameSegs = Arrays.asList(frames);
                StringBuilder fb = new StringBuilder();
                for (int i = 0; i < frameSegs.size(); i++) {
                    fb.append(i == 0 ? "" : ".");
                    fb.append("frameLocator(\"").append(frameSegs.get(i)).append("\")");
                }
                desc = desc + " [" + fb + "]";
                supplier = () -> {
                    Locator l = baseSupplier.get();
                    for (String seg : frameSegs) {
                        // FrameLocator.locator 返回下层 Locator，逐层下钻到 iframe 内的真实元素。
                        l = self.getPage().frameLocator(seg).locator(l);
                    }
                    return l;
                };
            }

            field.set(self, new PageElement(supplier, desc, self));
        } catch (Exception e) {
            // 关键修复 P3-27：保留原始 cause 便于调试 —— ElementException 应传入原异常
            throw new ElementException("Init RoleElement field failed: " + field.getName(), e);
        }
    }

    /**
     * 语义定位的统一入口：把 attr（text/altText/title/placeholder/label）映射到对应的 getBy* 原语。
     * 模板值（含 {{var}}）走正则 setName(Pattern)；否则先按可见文本归一（剥离 <img>/&nbsp; 等渲染层标记），
     * 再按精确/模糊定位，与浏览器渲染后的真实可访问名对齐。
     */
    private Locator byNlsValue(String attr, String resolvedValue, boolean exact) {
        Pattern p = NLSUtils.isTemplate(resolvedValue) ? NLSUtils.templatePattern(resolvedValue) : null;
        if (p != null) {
            switch (attr) {
                case "altText":     return self.byAltText(p);
                case "title":       return self.byTitle(p);
                case "placeholder": return self.byPlaceholder(p);
                case "label":       return self.byLabel(p);
                case "text":
                default:            return self.byText(p);
            }
        }
        // 非模板值若内嵌 HTML/实体（<a>/<strong>/<img>/&nbsp; 等），必须按可见文本定位，
        // 与浏览器渲染后的实际文本对齐（否则如 tab_security_device 这类值会匹配失败）。
        String visible = NLSUtils.visibleText(resolvedValue);
        switch (attr) {
            case "altText":     return self.byAltText(visible, exact);
            case "title":       return self.byTitle(visible, exact);
            case "placeholder": return self.byPlaceholder(visible, exact);
            case "label":       return self.byLabel(visible, exact);
            case "text":
            default:            return self.byText(visible, exact);
        }
    }

    /**
     * 解析 @RoleElement 字段对应的 nls 文件有序列表（从主到次）。
     * 优先取字段 file() 覆盖（可指向任意文件，含不在类级列表中的，作为单文件列表）；
     * 否则取类级 @RoleFile 的全部 value()，并按 primary() 把主文件提到首位。
     * 运行时按此顺序跨文件查找 key（命中即止）。
     */
    private List<String> resolveRoleFiles(RoleElement a) {
        if (a.file() != null && !a.file().isBlank()) {
            return List.of(a.file());
        }
        RoleFile classFile = self.getClass().getAnnotation(RoleFile.class);
        if (classFile == null || classFile.value().length == 0) {
            throw new ElementException("RoleElement field '" + a.key()
                    + "' needs either file() or a class-level @RoleFile on "
                    + self.getClass().getSimpleName());
        }
        List<String> ordered = new ArrayList<>(Arrays.asList(classFile.value()));
        String primary = classFile.primary();
        if (primary != null && !primary.isBlank()) {
            int idx = ordered.indexOf(primary);
            if (idx > 0) {
                ordered.remove(idx);
                ordered.add(0, primary);
            }
        }
        return ordered;
    }
}
