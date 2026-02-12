package com.hsbc.cmb.hk.dbb.automation.framework.web.annotations;

import com.hsbc.cmb.hk.dbb.automation.framework.web.enums.BrowserSwitchStrategy;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 浏览器选择注解
 * 
 * 企业级解决方案：不依赖hooks，通过注解驱动浏览器选择
 * 
 * 使用场景：
 * 1. 在步骤定义类级别使用，自动根据scenario标签选择浏览器
 * 2. 无需手动调用任何方法，零侵入
 * 3. AOP自动处理，对用户完全透明
 * 
 * 示例：
 * <pre>
 * {@literal @}AutoBrowser
 * public class LoginGlue {
 *     {@literal @}Given("I login")
 *     public void login() {
 *         // 浏览器已自动设置好，直接使用即可
 *         page.navigate(url);
 *     }
 * }
 * </pre>
 * 
 * 优势：
 * - 零配置：添加注解即可
 * - 自动化：框架自动处理浏览器切换
 * - 可维护：集中管理，易于扩展
 * 
 * @author Automation Framework
 * @version 2.0
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface AutoBrowser {
    /**
     * 是否启用浏览器自动切换
     * 默认true
     * 
     * @return true启用，false禁用
     */
    boolean enabled() default true;
    
    /**
     * 浏览器切换策略
     * 
     * @see BrowserSwitchStrategy
     */
    BrowserSwitchStrategy strategy() default BrowserSwitchStrategy.LAZY;
    
    /**
     * 是否在scenario开始时打印日志
     * 默认true
     */
    boolean verbose() default true;
}
