package com.hsbc.cmb.hk.dbb.automation.framework.web.page;


import com.microsoft.playwright.options.AriaRole;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 基于「可访问性角色 + nls key」的页面元素注解。
 *
 * <p>与 {@link Element}（仅支持 CSS/XPath 静态选择器）不同，本注解通过
 * 「可访问性角色 + nls 文件 key」在运行时解析可访问名称，天然支持多语言切换：
 * 调用 {@code NLSUtils.setLanguage("xx")} 后，同一字段会自动解析为对应语言的可访问名。
 * 字段类型为 {@link PageElement}，因此复用其重试 / 诊断 / 截图全套能力。
 *
 * <p>nls 文件路径通过类级 {@link RoleFile} 声明一次即可，本注解只需写 {@code role} + {@code key}；
 * 若个别字段需指向不同文件，可用 {@link #file()} 覆盖。
 *
 * <h3>使用示例</h3>
 * <pre>
 * &#64;RoleFile("nls/login.nls.json")
 * public class LoginPage extends BasePage {
 *     &#64;RoleElement(role = AriaRole.TEXTBOX, key = "username")
 *     public PageElement USERNAME;
 *
 *     &#64;RoleElement(role = AriaRole.BUTTON, key = "signIn")
 *     public PageElement SIGN_IN;
 * }
 * </pre>
 *
 * Steps 层完全不感知 NLS，直接操作字段：
 * <pre>
 * loginPage.USERNAME.fill("111");
 * loginPage.SIGN_IN.click();
 * </pre>
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RoleElement {

    /** 可访问性角色，如 {@link AriaRole#TEXTBOX}、{@link AriaRole#BUTTON} */
    AriaRole role();

    /** nls 文件内的 key，如 "username" */
    String key() default "";

    /**
     * 可访问名称的字面量覆盖。默认空字符串：表示走 nls {@link #key()} 解析（多语言）。
     * 当元素没有对应 nls key（如拾取时未反查到）时，可填此属性直接用字面名称定位，
     * 此时不再依赖 nls 文件。{@code key()} 与 {@code name()} 至少提供一个。
     */
    String name() default "";

    /**
     * nls 文件路径覆盖。默认空字符串，表示使用类级 {@link RoleFile} 声明的文件。
     * 仅当该字段需要指向与页面其他字段不同的 nls 文件时才填写。
     */
    String file() default "";

    /** 是否精确匹配名称（大小写敏感）。默认 true。 */
    boolean exact() default true;

    /** 元素描述（可选），用于日志与错误信息 */
    String description() default "";
}
