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
     * 通过 {@code text} / {@code altText} / {@code title} / {@code placeholder} / {@code testId} / {@code i18n}
     * 指定，分别对应 Playwright 的 {@code getByText} / {@code getByAltText} / {@code getByTitle} /
     * {@code getByPlaceholder} / {@code getByTestId} / {@code [data-i18n="key"]}，比纯 CSS/XPath 字符串选择器更健壮、可访问性更友好。
     * 其中 {@code i18n} 为基于 {@code data-i18n} 属性的多语言 key 定位，语言无关、最稳定（如 {@code header_business}）。
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
     * 多语言 i18n key 语义定位（对齐本项目 {@code data-i18n} 约定）。
     * 元素若带 {@code data-i18n="xxx"} 属性，其本体即多语言 key——语言无关、且比可见文本稳定得多。
     * 主要用于<b>非交互元素</b>（如 {@code generic} 角色的 {@code <span>}/{@code <div>}）：拾取时
     * 交互控件优先走 {@code role+name}，仅当其未命中才回退到 {@code i18n}（排在 alt/text 之前）。
     * 运行期等价 CSS 属性选择器 {@code [data-i18n="xxx"]}（精确匹配）。
     * 设置后 {@link #role()} 自动忽略（i18n 是语义定位策略，非角色策略）。
     * <pre>
     *     &#64;RoleElement(i18n = "header_business")                  // [data-i18n="header_business"]，语言无关
     *     public PageElement businessText;
     * </pre>
     */
    String i18n() default "";

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

    /** 元素描述（可选），用于日志与错误信息 */
    String description() default "";
}
