package com.hsbc.cmb.hk.dbb.automation.tests.architecture;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.base.BasePage;
import org.junit.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

/**
 * 架构分层门禁（T0-1）：固化企业级分层约束，防止越层依赖与循环依赖复发。
 *
 * <p>当前已落地的规则：
 * <ul>
 *   <li><b>L1</b>：{@code api} 不得依赖 {@code web}
 *       （T1-1 已将 {@code SensitiveDataSanitizer} 上提到 {@code common.security}，消除 api→web 越层）。</li>
 *   <li><b>L2</b>：{@code common} 不得依赖 {@code web}
 *       （T1-2 已将日志详细度入口统一到 {@code common.config.VerboseLogging}，移除旧的 {@code web.utils.LoggingConfigUtil}；common→web 越层已切断）。</li>
 *   <li><b>L3</b>：{@code web.page} 不得依赖 {@code web.route}
 *       （T1-3 已将共享报告器 {@code SerenityReporter} 上提到 {@code common.reporting}，
 *       page 与 route 改为共同依赖 common，消除 page→route 越层；报告行为不变）。</li>
 *   <li><b>C1</b>：{@code web.route.core} 不得依赖 {@code web.route.handler}
 *       （T1-4 引入 core 侧 {@code RouteHandlerRegistry} 注册表 + {@code RouteDelay} 工具，
 *       RouteEngine 不再 import 任何具体 Handler，循环依赖已打破；行为不变）。</li>
 *   <li><b>L4</b>：{@code common} 不得依赖 {@code api}
 *       （地基包保持零上层依赖；已实测 common 对 api 引用为 0）。</li>
 *   <li><b>L5</b>：{@code web.route} 不得依赖 {@code web.page}
 *       （与 L3 共同保证 page 与 route 双向解耦；已实测 route 对 page 引用为 0）。</li>
 *   <li><b>G1</b>：{@code framework} 顶层切片（common/api/web/...）之间不得存在循环依赖
 *       （通用回归防护，覆盖任何尚未显式列出的新循环）。</li>
 * </ul>
 */
public class ArchitectureTest {

    private static final String BASE_PACKAGE = "com.hsbc.cmb.hk.dbb.automation.framework";

    /** L2：地基包 common 不得反向依赖 web。 */
    @Test
    public void commonMustNotDependOnWeb() {
        noClasses()
                .that().resideInAPackage("..framework.common..")
                .should().dependOnClassesThat().resideInAPackage("..framework.web..")
                .check(new ClassFileImporter()
                        .withImportOption(new ImportOption.DoNotIncludeTests())
                        .importPackages(BASE_PACKAGE));
    }

    @Test
    public void apiMustNotDependOnWeb() {
        noClasses()
                .that().resideInAPackage("..framework.api..")
                .should().dependOnClassesThat().resideInAPackage("..framework.web..")
                .check(new ClassFileImporter()
                        .withImportOption(new ImportOption.DoNotIncludeTests())
                        .importPackages(BASE_PACKAGE));
    }

    @Test
    public void pageMustNotDependOnRoute() {
        noClasses()
                .that().resideInAPackage("..framework.web.page..")
                .should().dependOnClassesThat().resideInAPackage("..framework.web.route..")
                .check(new ClassFileImporter()
                        .withImportOption(new ImportOption.DoNotIncludeTests())
                        .importPackages(BASE_PACKAGE));
    }

    @Test
    public void routeCoreMustNotDependOnRouteHandler() {
        noClasses()
                .that().resideInAPackage("..framework.web.route.core..")
                .should().dependOnClassesThat().resideInAPackage("..framework.web.route.handler..")
                .check(new ClassFileImporter()
                        .withImportOption(new ImportOption.DoNotIncludeTests())
                        .importPackages(BASE_PACKAGE));
    }

    /** L4：地基包 common 不得反向依赖 api（保持 common 为最底层、零上层依赖）。 */
    @Test
    public void commonMustNotDependOnApi() {
        noClasses()
                .that().resideInAPackage("..framework.common..")
                .should().dependOnClassesThat().resideInAPackage("..framework.api..")
                .check(new ClassFileImporter()
                        .withImportOption(new ImportOption.DoNotIncludeTests())
                        .importPackages(BASE_PACKAGE));
    }

    /** L5：web.route 不得依赖 web.page（与 L3 共同保证 page 与 route 双向解耦）。 */
    @Test
    public void routeMustNotDependOnPage() {
        noClasses()
                .that().resideInAPackage("..framework.web.route..")
                .should().dependOnClassesThat().resideInAPackage("..framework.web.page..")
                .check(new ClassFileImporter()
                        .withImportOption(new ImportOption.DoNotIncludeTests())
                        .importPackages(BASE_PACKAGE));
    }

    /** G1：framework 顶层切片（common/api/web/...）之间不得存在循环依赖（通用回归防护）。 */
    @Test
    public void frameworkSlicesMustBeFreeOfCycles() {
        slices().matching("..framework.(*)..")
                .should().beFreeOfCycles()
                .check(new ClassFileImporter()
                        .withImportOption(new ImportOption.DoNotIncludeTests())
                        .importPackages(BASE_PACKAGE));
    }

    /**
     * API 边界门禁（企业级）：业务代码（framework 包之外，即 test-automation 等上层）
     * 不得直接调用 {@code BasePage} 的 {@code byRole/byText/byLabel/byAltText/byTitle/byTestId/byPlaceholder}
     * 定位器工厂（返回裸 Playwright {@code Locator}，属类型泄漏）。
     * 这些 factory 仅供 {@code web.page.binding.RoleElementBinder} 与 NLS 内部路由使用；
     * 业务方应使用 {@code @RoleElement} 注解或 {@link #element(String)}/{@link #locator(String)} 返回的框架原生类型。
     */
    @Test
    public void businessCodeMustNotUseInternalByLocators() {
        noClasses()
                .that().resideOutsideOfPackage("..framework.web.page..")
                .should().callMethodWhere(new DescribedPredicate<JavaMethodCall>("call BasePage.by* internal locator factory") {
                    @Override
                    public boolean test(JavaMethodCall call) {
                        return call.getTarget().getOwner().isAssignableTo(BasePage.class)
                                && call.getTarget().getName().startsWith("by");
                    }
                })
                .check(new ClassFileImporter()
                        .withImportOption(new ImportOption.DoNotIncludeTests())
                        .importPackages(BASE_PACKAGE, "com.hsbc.cmb.hk.dbb.automation.tests"));
    }
}
