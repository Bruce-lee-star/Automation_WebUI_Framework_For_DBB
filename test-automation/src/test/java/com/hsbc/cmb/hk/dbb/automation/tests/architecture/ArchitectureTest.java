package com.hsbc.cmb.hk.dbb.automation.tests.architecture;

import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.state.PlaywrightRuntimeState;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.LifecycleState;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaFieldAccess;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.base.BasePage;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.PlaywrightManager;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

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
 *   <li><b>L5</b>：{@code route} 不得依赖 {@code web.page}
 *       （与 L3 共同保证 page 与 route 双向解耦；已实测 route 对 page 引用为 0）。</li>
 *   <li><b>L6</b>：{@code route} 不得依赖 {@code web}（整模块）
 *       （ROUTE-P1-1 已将 {@code HikariConfigFactory}/{@code MonitorConfig}/{@code LanguageState} 下沉 core，
 *       route/pom 移除 framework-web；route→web 越层已切断，行为不变）。</li>
 *   <li><b>G1</b>：{@code framework} 顶层切片（common/api/web/...）之间不得存在循环依赖
 *       （通用回归防护，覆盖任何尚未显式列出的新循环）。</li>
 *   <li><b>L7</b>：{@code lifecycle} 包树之外不得访问/调用生命周期内部面
 *       （{@code PlaywrightManager.STATE}、三把锁、per-thread 存储键、
 *       {@code CustomOptionsManager.CUSTOM_*_KEY}、内部 seam 方法）。
 *       对应 doc16《lifecycle 跨子包封装治理》Phase 1：子包拆分使 {@code package-private} 失效，
 *       这些成员被迫升为 {@code public}，故以 ArchUnit 把「跨子包可见但不对外」固化为构建期门禁。</li>
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
                .should().dependOnClassesThat().resideInAPackage("..framework.route..")
                .check(new ClassFileImporter()
                        .withImportOption(new ImportOption.DoNotIncludeTests())
                        .importPackages(BASE_PACKAGE));
    }

    @Test
    public void routeCoreMustNotDependOnRouteHandler() {
        noClasses()
                .that().resideInAPackage("..framework.route.core.capture..")
                    .or().resideInAPackage("..framework.route.core.rule..")
                    .or().resideInAPackage("..framework.route.core.engine..")
                    .or().resideInAPackage("..framework.route.core.lifecycle..")
                    .or().resideInAPackage("..framework.route.core.spi..")
                .should().dependOnClassesThat().resideInAPackage("..framework.route.handler..")
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
                .that().resideInAPackage("..framework.route..")
                .should().dependOnClassesThat().resideInAPackage("..framework.web.page..")
                .check(new ClassFileImporter()
                        .withImportOption(new ImportOption.DoNotIncludeTests())
                        .importPackages(BASE_PACKAGE));
    }

    /** L6（ROUTE-P1-3）：route 模块不得依赖 web 模块（解除 route→web 越层；
     *  HikariConfigFactory / MonitorConfig / LanguageState 已下沉 core，route 仅依赖 core）。 */
    @Test
    public void routeMustNotDependOnWeb() {
        noClasses()
                .that().resideInAPackage("..framework.route..")
                .should().dependOnClassesThat().resideInAPackage("..framework.web..")
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
    /**
     * 接口不泄漏门禁（企业级，对齐 playwright-java 1.58.0 官方线程模型）：
     * 任何生产代码（framework 包）不得依赖 {@code com.microsoft.playwright.impl.*} 内部实现类。
     * 框架须且只须面向 Playwright 公开接口（Browser/Page/BrowserContext/Locator/Frame 等）编程，
     * 否则会耦合 driver 内部实现，破坏跨版本兼容与可替换性
     * （WEB-P0-2 DI seam 的替换实现亦无法生效）。
     */
    @Test
    public void mustNotDependOnPlaywrightImpl() {
        noClasses()
                .should().dependOnClassesThat().resideInAPackage("..playwright.impl..")
                .check(new ClassFileImporter()
                        .withImportOption(new ImportOption.DoNotIncludeTests())
                        .importPackages(BASE_PACKAGE));
    }

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

    /**
     * DI seam 防绕过门禁（企业级，对齐 WEB-P0-2）：
     * 生产 framework 代码不得调用 {@code PlaywrightManager.setProvider}，否则会绕过 DI seam
     * 在运行时注入测试替身、破坏「门面 → provider → 协作者实现」的替换链路
     * （WEB-P1-6 复盘要求仅在确有需要时引入多态）。
     * <p>注意：{@code setProvider} 本就是测试 API，仅 test-automation 上层调用方使用
     * （已被 {@code DoNotIncludeTests} 排除），故本规则扫描 framework 主代码即可守住生产边界；
     * {@code PlaywrightManager} 自身仅声明 {@code setProvider}、并不调用它，因此不会触发本规则。</p>
     */
    @Test
    public void frameworkCodeMustNotMutateRuntimeSeam() {
        noClasses()
                .that().resideInAPackage("..framework..")
                .should().callMethodWhere(new DescribedPredicate<JavaMethodCall>("call PlaywrightManager.setProvider seam") {
                    @Override
                    public boolean test(JavaMethodCall call) {
                        return call.getTarget().getOwner().isAssignableTo(PlaywrightManager.class)
                                && "setProvider".equals(call.getTarget().getName());
                    }
                })
                .check(new ClassFileImporter()
                        .withImportOption(new ImportOption.DoNotIncludeTests())
                        .importPackages(BASE_PACKAGE));
    }

    /**
     * DI seam 防绕过门禁（企业级，对齐 WEB-P0-2 验收②）：
     * 非 lifecycle 包树的 framework 代码不得直接依赖 lifecycle 的 {@code *Impl} 协作者实现类，
     * 否则会绕过「接口（角色）= 协作者契约、{@code *Impl}=内部无状态单例」的 DI 替换链路，
     * 破坏可测试替身注入与多实现多态（WEB-P1-6）。
     * <p>说明：{@code *Impl} 仅供 lifecycle 包树内部（含 {@code .serenity} 子包）组合根装配与委托，
     * 故 subject 限制为 lifecycle 包树之外、dependOn 目标限制为 lifecycle 包树内的 {@code *Impl}。</p>
     */
    @Test
    public void frameworkCodeMustNotDependOnLifecycleImpls() {
        noClasses()
                .that().resideInAPackage("..framework..")
                .and().resideOutsideOfPackage("..framework.web.lifecycle..")
                .should().dependOnClassesThat(
                        JavaClass.Predicates.resideInAPackage("..framework.web.lifecycle..")
                                .and(JavaClass.Predicates.simpleNameEndingWith("Impl")))
                .check(new ClassFileImporter()
                        .withImportOption(new ImportOption.DoNotIncludeTests())
                        .importPackages(BASE_PACKAGE, "com.hsbc.cmb.hk.dbb.automation.tests"));
    }

    // ==================== L7：lifecycle 内部面门禁（跨子包封装治理 Phase 1） ====================
    //
    // 背景：doc16《lifecycle 跨子包封装治理》。PlaywrightManager 拆分后协作者被物理拆到
    // browser/context/page/scenario/serenity/bootstrap/media 子包，而 Java 子包 ≠ 同包，
    // package-private 不跨子包生效 —— 上一轮为稳定编译把这些成员升为 public。
    // 「public」在此表示「跨子包刻意可见」，绝不等于「业务可用」：本组规则把它固化为构建期门禁，
    // 任何 lifecycle 包树之外的访问（含业务 test-automation）直接构建失败，
    // 防止封装随后续新增调用点持续退化。终态方案见 doc16 Phase 5（JPMS qualified exports）。

    /** lifecycle 父包全限定名前缀（用于精确匹配 owner，避免同名类误判）。 */
    private static final String LIFECYCLE_PKG =
            "com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.";

    /** 受管状态根与锁对象：可变/敏感内部状态，仅生命周期协作者可触碰。 */
    private static final Set<String> LIFECYCLE_INTERNAL_STATE_FIELDS = new HashSet<>(Arrays.asList(
            "STATE", "CONTEXT_LOCK", "PAGE_LOCK", "SHARED_BROWSER_LOCK",
            "SHARED_KEY_PREFIX", "SHARED_BROWSER_MODE",
            "CONTEXT_KEY", "PAGE_KEY", "CURRENT_CONFIG_ID_KEY"));

    /** PlaywrightManager 上仅供内部协作的 seam 方法。 */
    private static final Set<String> PLAYWRIGHT_MANAGER_INTERNAL_METHODS = new HashSet<>(Arrays.asList(
            "perThreadBrowserLock", "getPageThreadLocal", "getFrameworkState"));

    /** 判定：是否访问了 lifecycle 的内部状态/锁/存储键字段。 */
    private static boolean isLifecycleInternalField(JavaFieldAccess access) {
        String owner = access.getTarget().getOwner().getName();
        String name = access.getTarget().getName();
        if ((LIFECYCLE_PKG + "PlaywrightManager").equals(owner)) {
            return LIFECYCLE_INTERNAL_STATE_FIELDS.contains(name);
        }
        // CustomOptionsManager 的 CUSTOM_*_KEY：per-thread 自定义选项的内部存储键
        if ((LIFECYCLE_PKG + "context.CustomOptionsManager").equals(owner)) {
            return name.startsWith("CUSTOM_") && name.endsWith("_KEY");
        }
        return false;
    }

    /** 判定：是否调用了 lifecycle 的内部生命周期方法（按 owner 精确区分同名的 closeContext）。 */
    private static boolean isLifecycleInternalMethod(JavaMethodCall call) {
        String owner = call.getTarget().getOwner().getName();
        String name = call.getTarget().getName();
        // doc16 Phase 2：状态根角色接口本身亦属内部面（其方法即状态读写入口），整接口对外封闭
        if ((LIFECYCLE_PKG + "LifecycleState").equals(owner)) {
            return true;
        }
        if ((LIFECYCLE_PKG + "PlaywrightManager").equals(owner)) {
            return PLAYWRIGHT_MANAGER_INTERNAL_METHODS.contains(name);
        }
        if ((LIFECYCLE_PKG + "context.CustomOptionsManager").equals(owner)) {
            return "removeAllThreadLocals".equals(name);
        }
        if ((LIFECYCLE_PKG + "serenity.TestContextBridge").equals(owner)) {
            return "drainPageErrors".equals(name);
        }
        if ((LIFECYCLE_PKG + "serenity.SerenityBusBridge").equals(owner)) {
            return "replayFailures".equals(name);
        }
        if ((LIFECYCLE_PKG + "bootstrap.PlaywrightInitializer").equals(owner)) {
            return "initializePlaywrightPaths".equals(name);
        }
        if ((LIFECYCLE_PKG + "bootstrap.PlaywrightContextManager").equals(owner)) {
            return "closeContext".equals(name);
        }
        return (LIFECYCLE_PKG + "serenity.PlaywrightSerenityBridge").equals(owner)
                && "cleanupThreadLocals".equals(name);
    }

    /**
     * L7-a：lifecycle 包树之外不得<b>访问</b>生命周期内部状态字段
     * （{@code PlaywrightManager.STATE}/三把锁/{@code CONTEXT_KEY}/{@code PAGE_KEY}/
     * {@code CURRENT_CONFIG_ID_KEY}/{@code SHARED_KEY_PREFIX}/{@code SHARED_BROWSER_MODE}，
     * 以及 {@code CustomOptionsManager.CUSTOM_*_KEY}）。
     *
     * <p>这些字段承载「可变共享状态」与「锁监视器」：外部直接 {@code synchronized} 同一把锁
     * 会绕过既定锁顺序（PAGE_LOCK → CONTEXT_LOCK）引入死锁风险；直接读写状态容器
     * 则会绕过 {@code PlaywrightRuntimeState} 的不变式。故必须以构建期门禁封死。
     */
    @Test
    public void lifecycleInternalStateMustNotBeUsedOutsideLifecycle() {
        noClasses()
                .that().resideOutsideOfPackage("..framework.web.lifecycle..")
                .should().accessFieldWhere(new DescribedPredicate<JavaFieldAccess>(
                        "access lifecycle internal state/lock/key field") {
                    @Override
                    public boolean test(JavaFieldAccess access) {
                        return isLifecycleInternalField(access);
                    }
                })
                // 刻意不加 DoNotIncludeTests：业务 Page Object / Step 位于 test-automation 的
                // target/test-classes 下，若沿用 DoNotIncludeTests 会被整片排除，守护将只剩 framework 主代码。
                .check(new ClassFileImporter()
                        .importPackages(BASE_PACKAGE, "com.hsbc.cmb.hk.dbb.automation.tests"));
    }

    /**
     * L7-b：lifecycle 包树之外不得<b>调用</b>生命周期内部方法
     * （{@code perThreadBrowserLock}/{@code getPageThreadLocal}/{@code getFrameworkState}/
     * {@code removeAllThreadLocals}/{@code drainPageErrors}/{@code replayFailures}/
     * {@code initializePlaywrightPaths}/{@code PlaywrightContextManager.closeContext}/
     * {@code PlaywrightSerenityBridge.cleanupThreadLocals}），
     * 以及 {@code LifecycleState} 状态根角色接口的<b>任何</b>方法（doc16 Phase 2 新增）。
     *
     * <p>这些方法均为「框架内部 seam」：它们绕过 provider seam 与 Serenity 生命周期编排，
     * 直接操作 per-thread 状态或执行清理，业务侧调用会破坏场景隔离与失败传播语义。
     */
    @Test
    public void lifecycleInternalMethodsMustNotBeCalledOutsideLifecycle() {
        noClasses()
                .that().resideOutsideOfPackage("..framework.web.lifecycle..")
                .should().callMethodWhere(new DescribedPredicate<JavaMethodCall>(
                        "call lifecycle internal method") {
                    @Override
                    public boolean test(JavaMethodCall call) {
                        return isLifecycleInternalMethod(call);
                    }
                })
                // 同上：必须覆盖 test-classes，否则业务侧违规无法被拦截。
                .check(new ClassFileImporter()
                        .importPackages(BASE_PACKAGE, "com.hsbc.cmb.hk.dbb.automation.tests"));
    }
}
