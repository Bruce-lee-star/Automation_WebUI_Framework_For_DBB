package com.hsbc.cmb.hk.dbb.automation.framework.web.page;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 类级注解：声明本页面所有 {@link RoleElement} 字段共用的 nls 文件路径，
 * 避免在每个字段上重复书写相同路径。
 *
 * <pre>
 * &#64;RoleFile("nls/login.nls.json")
 * public class LoginPage extends BasePage { ... }
 * </pre>
 *
 * 若某字段需指向不同文件，可在 {@link RoleElement#file()} 单独覆盖。
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface RoleFile {

    /** nls 文件完整路径（classpath 相对或文件系统绝对），如 "nls/login.nls.json" */
    String value();
}
