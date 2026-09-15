package com.hsbc.cmb.hk.dbb.automation.tests.architecture;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;

import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * 架构分层门禁（A-3 补充，主链路分层覆盖）：固化「地基不向上依赖、主代码不耦合测试框架、主代码不用忙等」三条约束。
 *
 * <p>评审 doc13《A-3》要求新增 {@code LayeringArchTest}，覆盖 5 条规则：
 * <ul>
 *   <li><b>core 不向上依赖</b>（本类 {@link #coreMustNotDependUpward}）：{@code framework.common} 不得依赖
 *       {@code framework.web}/{@code framework.route}/{@code framework.api} 等上层模块。</li>
 *   <li><b>route 不依赖 web</b>：已由 {@code ArchitectureTest#routeMustNotDependOnWeb}（L6）覆盖，本类不复述。</li>
 *   <li><b>无循环依赖</b>：已由 {@code ArchitectureTest#frameworkSlicesMustBeFreeOfCycles}（G1）覆盖，本类不复述。</li>
 *   <li><b>主代码无 JUnit4 依赖</b>（本类 {@link #frameworkCodeMustNotDependOnJunit4}）：主代码只能依赖 JUnit5
 *       （{@code org.junit.jupiter}/{@code org.junit.platform}），不得依赖 JUnit4（{@code org.junit}、{@code org.junit.runner} 等）。</li>
 *   <li><b>主代码不调用 Thread.sleep</b>（本类 {@link #frameworkCodeMustNotCallThreadSleep}）：框架主代码不得忙等，
 *       一律走 Playwright 原生等待（{@code page.waitFor*}/{@code Locator.waitFor*}）或 {@code PageWaits} 退避。</li>
 * </ul>
 *
 * <p>放置于 test-automation 而非 core 模块：分层规则需跨模块可见所有 framework 类，
 * 仅 test-automation 的测试 classpath 同时包含全部模块编译产物（core 单测 classpath 看不到 web/route 类，
 * 「core 不向上依赖」会退化为无对象可查、形同虚设）。故与 {@code ArchitectureTest} 同包，统一在此门禁。</p>
 */
public class LayeringArchTest {

    private static final String BASE_PACKAGE = "com.hsbc.cmb.hk.dbb.automation.framework";

    /**
     * 地基不向上依赖（A-3）：{@code framework.common} 作为最底层地基，不得反向依赖任何上层模块
     * （web / route / api）。{@code framework.common.reporting} 属 common 自身子包，不算向上依赖，故不纳入目标。
     * <p>说明：{@code ArchitectureTest} 已有 {@code commonMustNotDependOnWeb}(L2) 与
     * {@code commonMustNotDependOnApi}(L4) 两条针对性规则；本规则以「common 整体不向上」做总闸，
     * 覆盖尚未显式列出的新上层（如 route），防止后续引入 common→route 越过层。</p>
     */
    @Test
    public void coreMustNotDependUpward() {
        noClasses()
                .that().resideInAPackage("..framework.common..")
                .should().dependOnClassesThat(
                        JavaClass.Predicates.resideInAPackage("..framework.web..")
                                .or(JavaClass.Predicates.resideInAPackage("..framework.route.."))
                                .or(JavaClass.Predicates.resideInAPackage("..framework.api..")))
                .check(new ClassFileImporter()
                        .withImportOption(new ImportOption.DoNotIncludeTests())
                        .importPackages(BASE_PACKAGE));
    }

    /**
     * 主代码不耦合 JUnit4（A-3）：框架生产代码只能面向 JUnit5 编程
     * （{@code org.junit.jupiter.*} / {@code org.junit.platform.*}），不得依赖 JUnit4（{@code org.junit}、{@code org.junit.runner}、
     * {@code org.junit.rules}、{@code org.junit.experimental} 等）。
     * <p>JUnit5 升级（doc17）已将测试代码迁移至 jupiter；本规则防止主代码（误）引入 JUnit4 API，
     * 避免运行时 classpath 同时拖入两套 JUnit、破坏升级一致性。</p>
     */
    @Test
    public void frameworkCodeMustNotDependOnJunit4() {
        noClasses()
                .that().resideInAPackage("..framework..")
                .should().dependOnClassesThat(
                        JavaClass.Predicates.resideInAPackage("..org.junit..")
                                .and(JavaClass.Predicates.resideOutsideOfPackage("..org.junit.jupiter.."))
                                .and(JavaClass.Predicates.resideOutsideOfPackage("..org.junit.platform..")))
                .check(new ClassFileImporter()
                        .withImportOption(new ImportOption.DoNotIncludeTests())
                        .importPackages(BASE_PACKAGE));
    }

    /**
     * 主代码不忙等（A-3）：框架生产代码不得调用 {@link Thread#sleep(long)} / {@link Thread#sleep(long, int)}。
     * <p>忙等既不可观测（无日志、无超时上界）又挤占并发度；框架统一走 Playwright 原生等待
     * （{@code page.waitFor*}/{@code Locator.waitFor*}）或 {@code PageWaits} 的带抖动退避。
     * 本规则仅扫主代码（{@code DoNotIncludeTests}），测试代码可保留受控的等待用于同步断言。</p>
     */
    @Test
    public void frameworkCodeMustNotCallThreadSleep() {
        noClasses()
                .that().resideInAPackage("..framework..")
                .should().callMethod(Thread.class, "sleep")
                .check(new ClassFileImporter()
                        .withImportOption(new ImportOption.DoNotIncludeTests())
                        .importPackages(BASE_PACKAGE));
    }
}
