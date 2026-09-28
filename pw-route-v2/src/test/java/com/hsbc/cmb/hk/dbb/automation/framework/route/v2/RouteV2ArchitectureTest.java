package com.hsbc.cmb.hk.dbb.automation.framework.route.v2;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Route;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.junit.ArchUnitRunner;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.runner.RunWith;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * 架构分层门禁（T0-1 风格）：固化 route2 的"驱动协议调用收口" —— 所有同步阻塞的 Playwright
 * 驱动协议调用（route / unroute / Route.close / fetch）必须经由有界守卫
 * （{@code GuardedDriverCall} / {@code BoundedOps} / IO 线程池），禁止在 binding / dispatch 等
 * 业务代码里直接调用原生驱动 API。
 *
 * <p><b>背景</b>：E2E 实测（test-automation 1.txt）证明，直接调 {@code context.route()} /
 * {@code context.unroute()} 在 Node 驱动不响应时会无限阻塞调用线程（route 注册卡 14 分钟、
 * unroute 卡 11 分钟）。任何"绕过守卫直接调原生 API"的改动都会让本测试失败，把卡死根因挡在
 * 编译 / 架构期。与 web 模块的临时 {@code HangWatchdog} 诊断正交（route2 靠根因设界自洽）。</p>
 *
 * <p><b>合法调用点（白名单）</b>：
 * <ul>
 *   <li>{@code PatternBinder}：bind 经 {@code GuardedDriverCall(FAIL_FAST)}、close 经
 *       {@code GuardedDriverCall(WARN_AND_ABANDON)}（由 {@code PatternBinderGuardedCallTest} 行为级固化）；</li>
 *   <li>{@code RouteAction} / {@code RouteIoExecutor}：fetch 经 {@code BoundedOps} + IO 线程池；</li>
 *   <li>{@code GuardedDriverCall*}：守卫自身（接口 / 实现 / 注册表），不触发本门禁。</li>
 * </ul>
 *
 * <p>@apiNote 仅测试期执行；不影响运行时依赖（route2 运行时零额外依赖）。</p>
 */
@RunWith(ArchUnitRunner.class)
@AnalyzeClasses(
    packages = "com.hsbc.cmb.hk.dbb.automation.framework.route.v2",
    importOptions = ImportOption.DoNotIncludeTests.class)
public class RouteV2ArchitectureTest {

    /**
     * 驱动生命周期调用（route / unroute / Route.close）必须经 {@code GuardedDriverCall} 收口。
     * 唯二合法业务调用点：{@code PatternBinder}（经 guarded 包裹的 lambda）与 GuardedDriverCall 自身。
     */
    @ArchTest
    static final ArchRule driverLifecycleCallsMustGoThroughGuardedDriverCall =
        noClasses()
            .that().resideInAPackage("..route.v2..")
            .and().doNotHaveSimpleName("PatternBinder")
            .and().doNotHaveSimpleName("GuardedDriverCall")
            .and().doNotHaveSimpleName("GuardedDriverCallImpl")
            .and().doNotHaveSimpleName("GuardedDriverCallRegistry")
            .should().callMethod(BrowserContext.class, "route")
            .orShould().callMethod(BrowserContext.class, "unroute")
            .orShould().callMethod(Route.class, "close");

    /**
     * 驱动 fetch（拦截真实响应）必须经 {@code BoundedOps} / IO 线程池
     * （{@code RouteAction} / {@code RouteIoExecutor} 已收口）。禁止其它业务代码直接调
     * {@code Route.fetch}（事件线程严禁同步 fetch，否则卡死主链）。
     */
    @ArchTest
    static final ArchRule driverFetchMustGoThroughBoundedOps =
        noClasses()
            .that().resideInAPackage("..route.v2..")
            .and().doNotHaveSimpleName("RouteAction")
            .and().doNotHaveSimpleName("RouteIoExecutor")
            .and().doNotHaveSimpleName("GuardedDriverCall")
            .and().doNotHaveSimpleName("GuardedDriverCallImpl")
            .and().doNotHaveSimpleName("GuardedDriverCallRegistry")
            .should().callMethod(Route.class, "fetch");
}
