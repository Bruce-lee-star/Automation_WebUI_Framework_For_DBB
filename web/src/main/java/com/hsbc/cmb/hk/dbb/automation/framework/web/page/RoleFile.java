package com.hsbc.cmb.hk.dbb.automation.framework.web.page;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 类级注解：声明本页面所有 {@link RoleElement} 字段共用的 nls 文件路径，
 * 避免在每个字段上重复书写相同路径。
 *
 * <h3>单文件（绝大多数页面）</h3>
 * <pre>
 * &#64;RoleFile("nls/login.nls.json")
 * public class LoginPage extends BasePage { ... }
 * </pre>
 *
 * <h3>多文件（一个页面用到多个 nls json）</h3>
 * 用数组声明本页面涉及的全部 nls 文件，运行时按声明顺序跨文件查找 key；
 * 拾取反查也会合并所有文件，确保来自任意文件的元素都能反查到 key。
 * <pre>
 * &#64;RoleFile({"nls/login.nls.json", "nls/footer.nls.json", "nls/common.nls.json"})
 * public class HomePage extends BasePage { ... }
 * </pre>
 * 若想让某文件作为默认（字段未写 {@code file} 时优先查找、且作为生成代码的类注解首个），
 * 用 {@link #primary()} 指定：
 * <pre>
 * &#64;RoleFile(value = {"nls/login.nls.json", "nls/footer.nls.json"}, primary = "nls/footer.nls.json")
 * </pre>
 *
 * 字段级 {@link RoleElement#file()} 仍可单独覆盖，指向任意文件（含不在类级列表中的）。
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface RoleFile {

    /** nls 文件完整路径数组（classpath 相对或文件系统绝对），如 "nls/login.nls.json"。
     *  单文件时用标量赋值即可：&#64;RoleFile("nls/login.nls.json")（Java 允许标量赋给数组属性）。 */
    String[] value();

    /** 默认主文件：字段未显式指定 {@link RoleElement#file()} 时优先查找、并作为生成代码类注解的首个。
     *  为空表示用 {@link #value()} 数组的第一个。主文件必须出现在 {@link #value()} 中。 */
    String primary() default "";
}
