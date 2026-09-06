package com.hsbc.cmb.hk.dbb.automation.framework.common.reporting;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * 架构门禁（T2-8）：固化 {@code framework-reporting} 对 route（{@code framework.web.route}）的零编译依赖。
 *
 * <p>reporting 仅经 core 抽象接口 {@code MonitorFailureReportSink} + SPI 与 route 解耦：
 * 监控失败报告的写出实现由 route 模块经 {@code META-INF/services} 在运行时注入，
 * reporting 编译期不得直接引用任何 {@code framework.web.route} 包下的类。
 * 任何回潮（reporting 重新 import route 实现）都会使本测试失败，从而把解环约束变成 CI 硬门禁。
 *
 * <p>采用纯 JUnit 4 + {@link ClassFileImporter} 程序化执行，仅依赖父 POM 已提供的
 * {@code archunit} 与 {@code junit}，不额外引入 {@code archunit-junit} 模块。
 */
public class ReportingRouteDecouplingArchTest {

    private static final String REPORTING_PKG = "com.hsbc.cmb.hk.dbb.automation.framework.common.reporting";
    private static final String ROUTE_PKG = "..framework.web.route..";

    @Test
    public void reportingMustNotDependOnRouteImplementation() {
        var classes = new ClassFileImporter()
                .withImportOption(new ImportOption.DoNotIncludeTests())
                .importPackages(REPORTING_PKG);
        ArchRule rule = noClasses().should().dependOnClassesThat()
                .resideInAPackage(ROUTE_PKG);
        rule.check(classes);
    }

    @Test
    public void reportingMustNotResideInRoutePackage() {
        var classes = new ClassFileImporter()
                .withImportOption(new ImportOption.DoNotIncludeTests())
                .importPackages(REPORTING_PKG);
        // reporting 模块中不应存在 reside 在 route 包的类（route 实现已隔离在 route 模块）
        ArchRule rule = noClasses().should().resideInAPackage(ROUTE_PKG);
        rule.check(classes);
    }
}
