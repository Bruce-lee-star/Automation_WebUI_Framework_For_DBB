package com.hsbc.cmb.hk.dbb.automation.framework.web.page.binding;

import com.hsbc.cmb.hk.dbb.automation.framework.web.exceptions.ElementException;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.element.RoleElement;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.element.RoleFile;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.element.RoleOptions;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.engine.BasePage;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.engine.LocatorFactory;
import com.hsbc.cmb.hk.dbb.automation.framework.web.utils.NLSUtils;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.options.AriaRole;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * 角色定位原语（framework-internal）：把「ARIA 角色 + 可访问名」解析为 Playwright {@code Locator}，
 * 统一收敛三条名称来源（字面名 / 正则 / NLS 多语言键）、标题层级与状态三态的全部组合。
 *
 * <p><b>为何独立成类（单一事实来源）：</b>角色定位有两个消费者——
 * <ol>
 *   <li>声明式 {@code @RoleElement} 注解字段（由 {@link RoleElementBinder} 绑定）；</li>
 *   <li>运行期 API（业务契约 {@code SerenityBasePage#elementByRole*}，经录制门面 {@code SerenityPageRecorder}）。</li>
 * </ol>
 * 二者必须共享同一份「NLS 键 → 当前语言可访问名 → 模板/可见文本归一 → byRole」语义，
 * 否则注解路径与运行期路径会在多语言值、模板值（{@code {{var}}}）与状态三态上产生行为分叉。
 *
 * <p><b>懒解析契约：</b>本类的方法由调用方在 {@link Supplier} 内调用，因此
 * {@link NLSUtils#setLanguage(String)} 切换语言、Page 重建后再次定位会自动解析生效
 * （与 {@code @RoleElement} 字段的运行时行为一致）。
 *
 * <p><b>边界：</b>本类为框架内部实现，业务代码不得直接调用（它返回裸 Playwright {@code Locator}，
 * 属类型泄漏，受 {@code ArchitectureTest.businessCodeMustNotUseInternalByLocators} 的同类边界约束）；
 * 业务应使用业务契约 {@code SerenityBasePage#elementByRole*}（返回框架原生
 * {@code PageElement}/{@code PageElementList}）或 {@code @RoleElement} 注解。
 */
public final class RoleLocatorFactory {

    private RoleLocatorFactory() {
        // 纯静态工具类，禁止实例化
    }

    // ==================== NLS 解析（注解路径与运行期路径共用） ====================

    /**
     * 解析页面类声明的 nls 文件有序列表（主文件在首位）：字段/调用级 {@code fileOverride} 优先，
     * 否则取类级 {@link RoleFile} 的全部 {@code value()}，并按 {@code primary()} 把主文件提到首位。
     *
     * @param pageClass    声明 {@code @RoleFile} 的页面类（业务 POJO 自身）
     * @param fileOverride 覆盖文件（可为 {@code null}/空，表示使用类级声明）
     * @param what         报错时的定位上下文（如 {@code "RoleElement field 'username'"}）
     * @return 有序的 nls 文件列表（跨文件按此顺序查找 key，命中即止）
     */
    public static List<String> resolveFiles(Class<?> pageClass, String fileOverride, String what) {
        if (fileOverride != null && !fileOverride.isBlank()) {
            return List.of(fileOverride);
        }
        RoleFile classFile = pageClass.getAnnotation(RoleFile.class);
        if (classFile == null || classFile.value().length == 0) {
            throw new ElementException(what + " needs either file() or a class-level @RoleFile on "
                    + pageClass.getSimpleName());
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

    /**
     * 懒解析的 nls 值供应商：{@link NLSUtils#setLanguage(String)} 切语言后再次 {@code get()}
     * 即返回对应语言的值；跨文件按顺序查找 key，命中即止。
     *
     * <p>绑定只做一次（{@code NLSUtils.bind}），{@code get} 在每次定位时执行，故切语言自动生效。
     */
    public static Supplier<String> nlsValueSupplier(Class<?> pageClass, String fileOverride, String nlsKey) {
        return nlsValueSupplier(resolveFiles(pageClass, fileOverride, "Role nls key '" + nlsKey + "'"), nlsKey);
    }

    /**
     * 懒解析的 nls 值供应商（文件列表已知时使用，避免重复解析 {@code @RoleFile}）。
     *
     * @param files  有序 nls 文件列表（主文件在首位）
     * @param nlsKey nls 键
     */
    public static Supplier<String> nlsValueSupplier(List<String> files, String nlsKey) {
        final NLSUtils.NlsBundle bundle = NLSUtils.bind(files);
        return () -> bundle.get(nlsKey);
    }

    // ==================== 定位原语 ====================

    /** 纯角色定位（不限名称）：对齐 page.pause 的 roleWithoutName，如无文本的 {@code role="listitem"} 结构元素。 */
    public static Locator byRole(BasePage bp, AriaRole role) {
        return LocatorFactory.byRole(bp, role);
    }

    /** 字面可访问名定位。 */
    public static Locator byName(BasePage bp, AriaRole role, String name, boolean exact, int level,
                                 RoleElement.State disabled, RoleElement.State pressed, RoleElement.State expanded) {
        return LocatorFactory.byRole(bp, role, name, exact, level, disabled, pressed, expanded);
    }

    /** 正则可访问名定位（模板值编译而来；正则模式下 Playwright 忽略 exact）。 */
    public static Locator byPattern(BasePage bp, AriaRole role, Pattern namePattern, int level,
                                    RoleElement.State disabled, RoleElement.State pressed, RoleElement.State expanded) {
        return LocatorFactory.byRole(bp, role, namePattern, level, disabled, pressed, expanded);
    }

    /**
     * 由 nls 原始值定位：模板值（含 {@code {{var}}}）编译为正则走 {@code setName(Pattern)}；
     * 否则取「可见文本」——nls 值内嵌的 {@code <img>}/{@code &nbsp;} 等会被浏览器渲染掉，
     * 真实可访问名不含标签，故不能直接用原始字符串当 name。
     */
    public static Locator byNlsValue(BasePage bp, AriaRole role, String rawValue, boolean exact, int level,
                                     RoleElement.State disabled, RoleElement.State pressed, RoleElement.State expanded) {
        if (NLSUtils.isTemplate(rawValue)) {
            return byPattern(bp, role, NLSUtils.templatePattern(rawValue), level, disabled, pressed, expanded);
        }
        return byName(bp, role, NLSUtils.visibleText(rawValue), exact, level, disabled, pressed, expanded);
    }

    // ==================== 正则匹配方式（exact 语义） ====================

    /**
     * 正则匹配方式归一：{@code exact=true} 时把正则锚定为<b>整串匹配</b>（{@code ^(?:…)$}）。
     *
     * <p><b>为何由框架实现：</b>Playwright 对 {@code name} 传入正则时<b>忽略</b> {@code exact} 选项
     * （官方语义：正则本身即可表达精确/模糊）。故运行期 API 的 {@code exact} 由本方法显式落地：
     * {@code true} → 整串锚定；{@code false} → 原样正则（Playwright 原生子串匹配）。
     *
     * <p><b>边界（零回归）：</b>注解路径（{@code @RoleElement} 的模板值）与 NLS 路径<b>不</b>经本方法，
     * 保持既有 Playwright 原生行为——多语言模板在真实站点上常带前后缀文本，锚定会导致失配。
     * 若运行期确需整串匹配，请显式传 {@code Pattern} 并置 {@code exact=true}。
     *
     * @param namePattern 原始正则（非空）
     * @param exact       true：整串匹配；false：原样子串匹配
     * @return 用于 {@code getByRole(role, setName(...))} 的正则
     */
    public static Pattern resolvePattern(Pattern namePattern, boolean exact) {
        if (namePattern == null) {
            throw new ElementException("Role name pattern must not be null");
        }
        return exact
                ? Pattern.compile("^(?:" + namePattern.pattern() + ")$", namePattern.flags())
                : namePattern;
    }

    // ==================== 诊断描述（日志 / 报告测试数据 / 截图命名） ====================

    /** 名称片段：纯角色（无名称限定）。 */
    public static String namePartRole() {
        return "no-name";
    }

    /** 名称片段：字面可访问名。 */
    public static String namePartName(String name) {
        return "name:" + name;
    }

    /** 名称片段：正则可访问名（模板值）；{@code exact=true} 时标注整串匹配。 */
    public static String namePartPattern(Pattern namePattern, boolean exact) {
        return "pattern:" + namePattern.pattern() + (exact ? ",exact" : "");
    }

    /** 名称片段：nls 键（含主文件，便于定位多语言来源）。 */
    public static String namePartKey(String primaryFile, String nlsKey) {
        return "nls:" + primaryFile + "#" + nlsKey;
    }

    /**
     * 组装元素描述：{@code role=<ROLE>[<名称片段><选项后缀>]}。
     * 报告测试数据、verbose 日志与失败截图命名均取此串，故不同定位器必须在描述上可区分。
     */
    public static String describe(AriaRole role, String namePart, String optionsSuffix) {
        return "role=" + role + "[" + namePart + optionsSuffix + "]";
    }

    /**
     * 选项后缀：标题层级与可访问状态（未设置的项不输出），使同一角色下不同定位器在报告/截图中可区分。
     * 例：{@code ,level:2,enabled,expanded}。
     */
    public static String describeOptionsSuffix(RoleOptions options) {
        if (options == null) {
            return "";
        }
        StringBuilder suffix = new StringBuilder();
        if (options.level() > 0) {
            suffix.append(",level:").append(options.level());
        }
        switch (options.disabledState()) {
            case NO:
                suffix.append(",enabled");
                break;
            case YES:
                suffix.append(",disabled");
                break;
            default:
                break;
        }
        switch (options.pressedState()) {
            case YES:
                suffix.append(",pressed");
                break;
            case NO:
                suffix.append(",not-pressed");
                break;
            default:
                break;
        }
        switch (options.expandedState()) {
            case YES:
                suffix.append(",expanded");
                break;
            case NO:
                suffix.append(",collapsed");
                break;
            default:
                break;
        }
        return suffix.toString();
    }

    /** 描述：纯角色（无名称限定）。 */
    public static String describeRole(AriaRole role) {
        return describe(role, namePartRole(), "");
    }

    /** 描述：字面可访问名。 */
    public static String describeName(AriaRole role, String name) {
        return describe(role, namePartName(name), "");
    }

    /** 描述：正则可访问名（模板值）；{@code exact=true} 时标注整串匹配，便于报告/截图对比定位差异。 */
    public static String describePattern(AriaRole role, Pattern namePattern, boolean exact) {
        return describe(role, namePartPattern(namePattern, exact), "");
    }

    /** 描述：nls 键（含主文件，便于定位多语言来源）。 */
    public static String describeKey(AriaRole role, String primaryFile, String nlsKey) {
        return describe(role, namePartKey(primaryFile, nlsKey), "");
    }
}
