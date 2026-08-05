package com.hsbc.cmb.hk.dbb.automation.framework.web.page;


import com.microsoft.playwright.options.AriaRole;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 页面元素注解：统一支持「角色定位」与「语义定位」两种策略，字段类型均为 {@link PageElement}，
 * 复用其重试 / 诊断 / 截图全套能力。
 *
 * <h3>1. 角色定位（默认，支持多语言 NLS）</h3>
 * 通过「可访问性角色 + nls key」在运行时解析可访问名称，调用 {@code NLSUtils.setLanguage("xx")}
 * 后同一字段会自动解析为对应语言的可访问名。nls 文件路径通过类级 {@link RoleFile} 声明一次即可；
 * 一个页面对应多个 nls json 时，{@link RoleFile} 支持数组形式一次声明全部文件，运行时按声明顺序跨文件查找 key。
 * <pre>
 * &#64;RoleFile("nls/login.nls.json")
 * public class LoginPage extends BasePage {
 *     &#64;RoleElement(role = AriaRole.TEXTBOX, key = "username")
 *     public PageElement USERNAME;
 * }
 * </pre>
 *
 * <h3>2. 语义定位（对齐 page.pause() 推荐定位器优先级链）</h3>
     * 通过 {@code text} / {@code altText} / {@code title} / {@code placeholder} / {@code testId} / {@code label}
     * 指定，分别对应 Playwright 的 {@code getByText} / {@code getByAltText} / {@code getByTitle} /
     * {@code getByPlaceholder} / {@code getByTestId} / {@code getByLabel}，比纯 CSS/XPath 字符串选择器更健壮、可访问性更友好。
     * 多语言 {@code data-i18n} 属性（如 {@code header_business}）直接走 CSS 属性选择器
     * {@code &#64;Element("[data-i18n=\"header_business\"]")} 即可，无需在注解里增设专门字段（i18n 本质上就是
     * 一个普通 DOM 属性，用 CSS 属性选择器 {@code [data-i18n="key"]} 表达最简洁、运行期最稳定）。
 * 设置任一语义属性后，{@link #role()} 自动忽略（默认值 {@link AriaRole#NONE} 即表示「非角色」）。
 * <pre>
 *     &#64;RoleElement(text = "Business")
 *     public PageElement businessLink;
 *
 *     &#64;RoleElement(altText = "Company logo")
 *     public PageElement logo;
 * </pre>
 *
 * <p>Steps 层完全不感知底层策略，直接操作字段：
 * <pre>
 * loginPage.USERNAME.fill("111");
 * loginPage.businessLink.click();
 * </pre>
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RoleElement {

    /**
     * 可访问性角色，如 {@link AriaRole#TEXTBOX}、{link AriaRole#BUTTON}。
     * 默认 {@link AriaRole#NONE}：表示不使用角色策略，改由下方语义属性（text/altText/...）定位；
     * 设置任一语义属性后本属性被忽略。
     */
    AriaRole role() default AriaRole.NONE;

    /**
     * 标题层级（仅对 {@link AriaRole#HEADING} 有意义），对齐 Playwright {@code getByRole(HEADING).setLevel(n)}。
     * 取值 1–6（对应 {@code h1}–{@code h6} / {@code aria-level}），默认 0 表示不限定层级（匹配任意层级标题）。
     * <p>用于进一步收窄标题定位又不依赖 DOM 结构与文案——比纯文本/CSS 更抗重构。拾取器会自动从
     * {@code <h1>–<h6>} 标签或 {@code aria-level} 推导该值。
     * <pre>
     *     &#64;RoleElement(role = AriaRole.HEADING, level = 2, key = "header_business")   // h2，语言无关 + 抗结构
     *     public PageElement businessHeading;
     * </pre>
     */
    int level() default 0;

    /**
     * nls 文件内的 key，如 "username"。两种场景生效：
     * <ul>
     *   <li>角色策略（role 已设置）：与 {@link #role()} 配合，运行时解析为对应语言可访问名并 {@code byRole}。</li>
     *   <li>语义文本策略（text/altText/title/placeholder/label）：生成器拾取到的可见文本若在 nls 反查命中，
     *       <b>只</b>输出 {@code key = "..."}（不保留语义属性）；运行时统一按 {@code getByText(key 解析)} 兜底
     *       （用户已接受该降级）。未命中 nls 时则仅输出对应语义属性的字面量（如 text = "Submit"）。</li>
     * </ul>
     * <p>nls 值若含模板变量（如 {@code We've sent a notification to {{deviceModel}} - {{deviceName}}}），
     * 拾取时会用“占位符→{@code .*?}”的正则源做反查（DOM 文本已被页面注入真实值），命中同样只输出 key；
     * 运行时把模板编译为正则 {@link java.util.regex.Pattern} 后用 {@code getByText(Pattern)} 等匹配真实文本，
     * 因此多语言 + 动态变量两者兼得。变量名在各语言中保持一致，故任意语言编译出的正则都能还原匹配。</p>
     */
    String key() default "";

    /**
     * 可访问名称的字面量覆盖（仅角色策略）。默认空字符串：表示走 nls {@link #key()} 解析（多语言）。
     * <p>一个页面通常有大量元素走 {@link #key()}（故类级 {@link RoleFile} 仍需声明）；
     * 仅当某个元素的名称在 nls 中找不到对应 key 时，才用本属性直接以字面名称定位，并跳过 nls。
     * <pre>
     * &#64;RoleFile("nls/login.nls.json")   // 页面其余元素都走这里
     * public class LoginPage extends BasePage {
     *     &#64;RoleElement(role = AriaRole.BUTTON, key = "signIn")          // 多语言
     *     public PageElement SIGN_IN;
     *
     *     &#64;RoleElement(role = AriaRole.BUTTON, name = "Sign in")        // nls 中无此 key，用字面量
     *     public PageElement SIGN_IN_LITERAL;
     * }
     * </pre>
     */
    String name() default "";

    /**
     * nls 文件路径覆盖。默认空字符串，表示使用类级 {@link RoleFile} 声明的文件。
     * 仅当该字段需要指向与页面其他字段不同的 nls 文件时才填写。
     */
    String file() default "";

    // ------------------------------------------------------------------
    // 语义定位属性（对齐 page.pause() 优先级链）。设置任一即启用语义策略，忽略 role。
    // ------------------------------------------------------------------

    /** 可见文本语义定位，等价于 {@code page.getByText(value, {exact:exact})}。 */
    String text() default "";

    /** alt 文本语义定位（常用于图片 / 图标），等价于 {@code page.getByAltText(value, {exact:exact})}。 */
    String altText() default "";

    /** title 属性语义定位，等价于 {@code page.getByTitle(value, {exact:exact})}。 */
    String title() default "";

    /** 表单控件 placeholder 语义定位，等价于 {@code page.getByPlaceholder(value, {exact:exact})}。 */
    String placeholder() default "";

    /** 测试标记 data-testid 语义定位，等价于 {@code page.getByTestId(value)}（忽略 exact）。 */
    String testId() default "";

    /**
     * 关联 label 文本，等价于 Playwright 的 {@code page.getByLabel(value)}（与 page.pause() 生成的定位器对齐）。
     * 通过关联 &lt;label&gt; 的文本 / {@code aria-labelledby} / {@code aria-label} 反查并定位
     * <b>对应的表单控件（input/select/textarea 等）</b>，与 {@link #role()} + {@link #name()}（按可访问名）是两条独立策略，
     * 但最终都定位到该 input 控件。
     * <p><b>「Label 是 Label，input 是 input」</b>：label 文本属于 label，控件属于控件，两条策略分开表达——
     * <ul>
     *   <li>操作 / 校验 label 文本本身 → 用 {@link #text()}（{@code getByText}）；</li>
     *   <li>操作 label 关联的 input 控件 → 用本属性（{@code getByLabel}）。</li>
     * </ul>
     * <pre>
     *     &#64;RoleElement(label = "Username")                          // getByLabel  → 定位 &lt;input&gt; 控件
     *     public PageElement usernameInput;
     *
     *     &#64;RoleElement(text = "Username")                           // getByText   → 定位 &lt;label&gt; 文本
     *     public PageElement usernameLabel;
     * </pre>
     * 设置本属性后，{@link #role()} 自动忽略（label 是语义定位策略，非角色策略）。
     */
    String label() default "";

    /** 名称 / 文本是否精确匹配（大小写敏感）。默认 true。对 testId / label 不生效。 */
    boolean exact() default true;

    /**
     * 可访问状态过滤的三态枚举，对齐 Playwright {@code getByRole(role).setDisabled/setPressed/setExpanded(value)}。
     * Playwright 的这三个方法只有 true/false 两态（不调用 = 不限定），因此本框架用三态表达：
     * <ul>
     *   <li>{@link #ANY}：不限定（默认，不调用 setXxx，匹配任意状态的元素）；</li>
     *   <li>{@link #YES}：只匹配「处于该状态」的元素（{@code setXxx(true)}）；</li>
     *   <li>{@link #NO}：只匹配「不处于该状态」的元素（{@code setXxx(false)}）。</li>
     * </ul>
     */
    enum State {
        /** 不限定（默认，不调用 setXxx）。 */
        ANY,
        /** 只匹配处于该状态的元素（等价于 Playwright {@code setXxx(true)}）。 */
        YES,
        /** 只匹配不处于该状态的元素（等价于 Playwright {@code setXxx(false)}）。 */
        NO
    }

    /**
     * 可访问「禁用」状态过滤，对齐 Playwright {@code getByRole(role).setDisabled(value)}。
     * <pre>
     *     &#64;RoleElement(role = AriaRole.BUTTON, name = "Submit", disabled = RoleElement.State.NO)   // 只定位可用按钮
     *     public PageElement SUBMIT;
     * </pre>
     */
    State disabled() default State.ANY;

    /**
     * 切换按钮「按下」状态过滤，对齐 Playwright {@code getByRole(role).setPressed(value)}。
     * 仅对带 {@code aria-pressed} 的元素有意义（如折叠面板触发、收藏开关）。
     */
    State pressed() default State.ANY;

    /**
     * 可展开元素「展开」状态过滤，对齐 Playwright {@code getByRole(role).setExpanded(value)}。
     * 仅对带 {@code aria-expanded} 的元素有意义（如菜单、树节点、手风琴）。
     */
    State expanded() default State.ANY;

    /** 元素描述（可选），用于日志与错误信息 */
    String description() default "";
}
