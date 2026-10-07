package com.hsbc.cmb.hk.dbb.automation.framework.web.page.scan;

import java.util.List;

/**
 * 断言类生成器：按「一次封装 = 一个 {@code @Step} 方法」生成<b>独立的</b> {@code *Assertions} 类。
 *
 * <p><b>与步骤类同构</b>（对齐 {@link RoleElementStepGenerator} 的类壳与命名）：同样的包结构约定
 * （断言的 {@code .assertions} 子包 ↔ 步骤的 {@code .steps}）、同样的"页面实例获取方式"
 * （{@code PageObjectFactory.getPage(XxxPage.class)} 字段）、同样的方法粒度（一次封装 = 一个方法，
 * 方法体内是该次<b>全部勾选元素</b>的断言）。区别只在方法体：步骤是操作调用，这里是可见性断言。</p>
 *
 * <p><b>为什么独立成类</b>：断言与操作的生命周期不同 —— 操作随流程演进频繁改动，断言是"契约"，
 * 混在一起会让 diff 互相污染；独立类也让"只跑断言"成为可能（复用页面类字段，不依赖步骤类）。</p>
 *
 * <p><b>产物形状</b>（注解刻意不写 {@code @Step("…")} 描述文案，方法体只有断言）：</p>
 * <pre>{@code
 * package com.demo.pages.login.assertions;
 *
 * import net.serenitybdd.annotations.Step;
 *
 * import com.demo.pages.login.LoginPage;
 * import com.hsbc.cmb.hk.dbb.automation.framework.web.page.factory.PageObjectFactory;
 *
 * public class LoginPageAssertions {
 *
 *     private final LoginPage loginPage = PageObjectFactory.getPage(LoginPage.class);
 *
 *     @Step
 *     public void assertStep1() {
 *         assertThat(loginPage.userName.isVisible(), equalTo(true));
 *         assertThat(loginPage.password.isVisible(), equalTo(true));
 *     }
 * }
 * }</pre>
 *
 * <p>产物为草稿，人工 review 后再合入主干（与页面类/步骤类同一约定）。</p>
 */
public final class RoleElementAssertionGenerator {

    private RoleElementAssertionGenerator() {}

    /** 断言类所在子包后缀（与步骤类的 {@code .steps} 对称）。 */
    public static final String ASSERTIONS_SUBPACKAGE = ".assertions";

    private static final String ASSERT_THAT_IMPORT = "import static org.hamcrest.MatcherAssert.assertThat;";
    private static final String EQUAL_TO_IMPORT = "import static org.hamcrest.Matchers.equalTo;";
    private static final String PAGE_OBJECT_FACTORY =
            "com.hsbc.cmb.hk.dbb.automation.framework.web.page.factory.PageObjectFactory";

    /**
     * 单条「元素可见」断言语句（纯函数：生成器与测试共用，产物形状只有一处定义）。
     *
     * @param pageVar 页面对象变量名（如 {@code loginPage}）
     * @param field   页面类字段名（如 {@code userName}）
     */
    public static String assertStatement(String pageVar, String field) {
        return "assertThat(" + pageVar + "." + field + ".isVisible(), equalTo(true))";
    }

    /**
     * 断言类名派生（与步骤类同一规则：{@code XxxPage} → {@code XxxAssertions}）。
     *
     * @param pageClassName 页面类名（如 {@code LoginPage}）
     * @return 断言类名（如 {@code LoginPageAssertions}）
     */
    public static String assertionsClassNameOf(String pageClassName) {
        if (pageClassName == null || pageClassName.isEmpty()) {
            return "PageAssertions";
        }
        return pageClassName.endsWith("Page")
                ? pageClassName.substring(0, pageClassName.length() - 4) + "Assertions"
                : pageClassName + "Assertions";
    }

    /** 页面对象变量名（首字母小写，与步骤类一致）。 */
    public static String pageVarOf(String pageClassName) {
        if (pageClassName == null || pageClassName.isEmpty()) {
            return "page";
        }
        return Character.toLowerCase(pageClassName.charAt(0)) + pageClassName.substring(1);
    }

    /**
     * 渲染断言类源码（类壳与 import 对齐步骤类，保证产物可编译）。
     *
     * @param packageName      页面类所在包名（断言类落在其 {@code .assertions} 子包）
     * @param pageClassName    页面类名（用于 import、字段类型与 {@code PageObjectFactory.getPage}）
     * @param stepAssertLines  每个内层 List 是一次「封装」的全部断言语句（顺序即生成顺序）
     * @return 完整 Java 类源码
     */
    public static String renderClass(String packageName, String pageClassName,
                                     List<List<String>> stepAssertLines) {
        String className = assertionsClassNameOf(pageClassName);
        String pageVar = pageVarOf(pageClassName);
        StringBuilder out = new StringBuilder();
        out.append("package ").append(packageName).append(ASSERTIONS_SUBPACKAGE).append(";\n\n");
        out.append("import net.serenitybdd.annotations.Step;\n\n");
        out.append("import ").append(packageName).append('.').append(pageClassName).append(";\n");
        out.append("import ").append(PAGE_OBJECT_FACTORY).append(";\n\n");
        out.append(ASSERT_THAT_IMPORT).append('\n');
        out.append(EQUAL_TO_IMPORT).append("\n\n");
        out.append("public class ").append(className).append(" {\n\n");
        // 与步骤类同款的页面实例获取方式（沿用框架单例工厂，保证与业务代码拿到同一个页面对象）
        out.append("    private final ").append(pageClassName).append(' ').append(pageVar)
                .append(" = PageObjectFactory.getPage(").append(pageClassName).append(".class);\n\n");
        if (stepAssertLines == null || stepAssertLines.isEmpty()) {
            out.append("    // 还没有任何断言：请在面板勾选元素后点「封装为断言」生成\n");
        } else {
            for (int i = 0; i < stepAssertLines.size(); i++) {
                List<String> lines = stepAssertLines.get(i);
                // 裸 @Step：不带描述文案（用户要求：只要断言）
                out.append("    @Step\n");
                out.append("    public void assertStep").append(i + 1).append("() {\n");
                if (lines == null || lines.isEmpty()) {
                    out.append("        // 该次封装未勾选任何元素\n");
                } else {
                    for (String line : lines) {
                        out.append("        ").append(line).append(";\n");
                    }
                }
                out.append("    }\n\n");
            }
        }
        out.append("}\n");
        return out.toString();
    }
}
