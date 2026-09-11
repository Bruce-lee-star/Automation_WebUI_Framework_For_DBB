package com.hsbc.cmb.hk.dbb.automation.framework.web;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * 架构门禁（T2-2）：固化 {@code framework-web} 对 codegen（原 {@code framework.web.page.scan} 包）
 * 的零编译依赖。
 *
 * <p>web 核心层（{@code BasePage}、{@code PageLifecycleCoordinator}、{@code PlaywrightManager}）
 * 仅允许依赖 {@code framework.web.codegen.spi.RoleCodegenBridge} / {@code RoleCodegenBridgeRegistry}，
 * 绝不可直接引用 scan 包下的实现类；实现由 {@code framework-codegen} 模块经 SPI 在运行时注入。
 * 任何回潮（web 重新 import scan 实现）都会使本测试失败，从而把结构约束变成 CI 硬门禁。
 *
 * <p>采用纯 JUnit 4 + {@link ClassFileImporter} 程序化执行，仅依赖父 POM 已提供的核心
 * {@code archunit} 与 {@code junit}，不额外引入 {@code archunit-junit} 模块。
 */
public class CodegenDecouplingArchTest {

    private static final String WEB_PKG = "com.hsbc.cmb.hk.dbb.automation.framework.web";
    private static final String SCAN_PKG = "..framework.web.page.scan..";

    @Test
    public void webMustNotDependOnScanImplementation() {
        var classes = new ClassFileImporter()
                .withImportOption(new ImportOption.DoNotIncludeTests())
                .importPackages(WEB_PKG);
        ArchRule rule = noClasses().should().dependOnClassesThat()
                .resideInAPackage(SCAN_PKG);
        rule.check(classes);
    }

    @Test
    public void webMustNotContainScanPackage() {
        var classes = new ClassFileImporter()
                .withImportOption(new ImportOption.DoNotIncludeTests())
                .importPackages(WEB_PKG);
        // web 模块中不应存在 reside 在 scan 包的类（scan 已物理迁出至 codegen 模块）
        ArchRule rule = noClasses().should().resideInAPackage(SCAN_PKG);
        rule.check(classes);
    }
}
