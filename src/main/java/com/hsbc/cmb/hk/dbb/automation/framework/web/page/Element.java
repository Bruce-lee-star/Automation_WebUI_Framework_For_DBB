package com.hsbc.cmb.hk.dbb.automation.framework.web.page;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 页面元素注解 - 用于标记Page中的元素字段。
 * <p>
 * <b>选择器仅支持 CSS 和 XPath 两种类型。</b>
 * CSS 选择器直接书写，XPath 选择器以 {@code //} 或 {@code xpath=} 开头。
 *
 * <h3>使用示例</h3>
 * <pre>
 * public class LoginPage extends SerenityBasePage {
 *     // CSS 选择器
 *     &#64;Element("#userName")
 *     public PageElement USERNAME_INPUT;
 *
 *     &#64;Element("[data-testid='submit-btn']")
 *     public PageElement SUBMIT_BTN;
 *
 *     // XPath 选择器
 *     &#64;Element("//button[contains(@class, 'primary')]")
 *     public PageElement PRIMARY_BTN;
 *
 *     // 元素列表（动态查询所有匹配元素）
 *     &#64;Element(".menu-item")
 *     public List&lt;PageElement&gt; menuItems;
 * }
 * </pre>
 *
 * <h3>List&lt;PageElement&gt; 使用示例</h3>
 * <pre>
 * for (PageElement button : loginButtons) {
 *     button.click();
 * }
 * loginButtons.get(0).click();
 * int count = loginButtons.size();
 * if (!loginButtons.isEmpty()) {
 *     loginButtons.first().click();
 * }
 * </pre>
 *
 * 注意：
 * 1. 仅支持 CSS 选择器和 XPath 选择器
 * 2. CSS 选择器直接书写（如 {@code "#id"}、{@code ".class"}），XPath 以 {@code //} 或 {@code xpath=} 开头
 * 3. 支持单个 PageElement 和 List&lt;PageElement&gt; 两种类型
 * 4. List&lt;PageElement&gt; 是动态列表，每次访问都会重新查询匹配的元素
 * 5. 字段会自动初始化，无需手动 new PageElement()
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Element {

    /**
     * 元素选择器（仅支持 CSS 和 XPath）。
     * CSS 直接书写，XPath 以 {@code //} 或 {@code xpath=} 开头。
     */
    String value();

    /**
     * 元素描述（可选）
     * 用于日志和错误信息
     */
    String description() default "";
}
