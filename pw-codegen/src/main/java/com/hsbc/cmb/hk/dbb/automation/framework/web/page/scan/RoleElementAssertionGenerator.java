package com.hsbc.cmb.hk.dbb.automation.framework.web.page.scan;

import java.util.List;

/**
 * 断言类生成器：按「一次封装 = 一个 {@code @Step} 方法」生成<b>独立的</b> {@code *Assertions} 类。
 *
 * <p><b>与步骤生成同构</b>（对齐 {@link RoleElementStepGenerator}）：方法粒度、编号顺序、按页分栏
 * 与步骤类完全一致 —— 一次「封装为断言」= 该类里的一个方法，方法体内是该次<b>全部勾选元素</b>的断言
 * （用户语义："所有的断言封装为一个步骤"）。区别只在方法体：步骤是操作调用，这里是可见性断言。</p>
 *
 * <p><b>为什么独立成类</b>：断言与操作的生命周期不同 —— 操作随流程演进频繁改动，断言是"契约"，
 * 放在一起改动会互相污染 diff；独立类也让"只跑断言"成为可能（复用页面类字段，不依赖步骤类）。</p>
 *
 * <p><b>产物形状</b>（刻意不写 {@code @Step("…")} 描述文案，方法体只有断言）：</p>
 * <pre>{@code
 * package com.demo.pages.login.assertions;
 *
 * import com.demo.pages.login.LoginPage;
 *
 * import static org.hamcrest.MatcherAssert.assertThat;
 * import static org.hamcrest.Matchers.equalTo;
 *
 * public class LoginPageAssertions {
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

    /**
     * 单条「元素可见」断言语句（纯函数：生成器与测试共用，保证产物形状只有一处定义）。
     *
     * @param pageVar 页面对象变量名（如 {@code loginPage}）
     * @param field   页面类字段名（如 {@code userName}）
     */
    public static String assertStatement(String pageVar, String field) {
        return "assertThat(" + pageVar + "." + field + ".isVisible(), equalTo(true))";
    }

    /**
     * 渲染断言类源码。
     *
     * @param packageName      页面类所在包名（断言类落在其 {@code .assertions} 子包）
     * @param className        断言类名（如 {@code LoginPageAssertions}）
     * @param pageImport       需要 import 的页面类全名；{@code null}/空白则不输出该 import
     * @param stepAssertLines  每个内层 List 是一次「封装」的全部断言语句（顺序即生成顺序）
     * @return 完整 Java 类源码
     */
    public static String renderClass(String packageName, String className, String pageImport,
                                     List<List<String>> stepAssertLines) {
        StringBuilder out = new StringBuilder();
        out.append("package ").append(packageName).append(ASSERTIONS_SUBPACKAGE).append(";\n\n");
        if (pageImport != null && !pageImport.trim().isEmpty()) {
            out.append("import ").append(pageImport.trim()).append(";\n\n");
        }
        out.append(ASSERT_THAT_IMPORT).append('\n');
        out.append(EQUAL_TO_IMPORT).append("\n\n");
        out.append("public class ").append(className).append(" {\n");
        if (stepAssertLines == null || stepAssertLines.isEmpty()) {
            out.append("    // 还没有任何断言：请在面板勾选元素后点「封装为断言」生成\n");
        } else {
            for (int i = 0; i < stepAssertLines.size(); i++) {
                List<String> lines = stepAssertLines.get(i);
                out.append('\n');
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
                out.append("    }\n");
            }
        }
        out.append("}\n");
        return out.toString();
    }
}
