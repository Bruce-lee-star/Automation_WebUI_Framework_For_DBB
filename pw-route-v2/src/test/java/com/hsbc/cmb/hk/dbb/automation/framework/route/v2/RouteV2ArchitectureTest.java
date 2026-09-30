package com.hsbc.cmb.hk.dbb.automation.framework.route.v2;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Route;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.junit.ArchUnitRunner;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.runner.RunWith;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.regex.Pattern;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * 架构分层门禁（T0-1 风格）：固化 route2 的"驱动协议调用收口" —— 所有同步阻塞的 Playwright
 * 驱动协议调用（route / unroute / Route.close / fetch）必须经由有界守卫
 * （{@code GuardedDriverCall} / {@code BoundedOps} / IO 线程池），禁止在 binding / dispatch 等
 * 业务代码里直接调用原生驱动 API。
 *
 * <p><b>背景</b>：{@code context.route()} / {@code context.unroute()} 在客户端是 {@code NO_TIMEOUT}
 * 且由调用线程自己泵消息，驱动不响应时会拖住调用线程 —— 故必须经有界守卫收口。任何"绕过守卫直接调
 * 原生 API"的改动都会让本测试失败，把根因挡在编译 / 架构期。</p>
 *
 * <p><b>2026-09-29 更正</b>：旧表述"route 注册卡 14 分钟 / unroute 卡 11 分钟"不准确 —— 探针实测该调用
 * 在界值后约 3~5 秒即抛 {@code Object doesn't exist}（客户端 {@code Connection.dispatch} 未按消息隔离异常），
 * 已由框架自建客户端 DBBN-PATCH-01 修复。</p>
 *
 * <p><b>资源 / 线程池门禁（评审 23 号 V2-5）</b>：route.v2 内禁止"污染 JVM 级共享池"与"无界线程池"用法 ——
 * {@code CompletableFuture.delayedExecutor}（唯一合法替代：{@code RouteDelayScheduler}）、
 * {@code ForkJoinPool.commonPool()}、{@code Executors.newCachedThreadPool()}。
 * 注意：<b>刻意不禁止</b> {@code new Thread(...)} —— 模块在 {@code ThreadFactory} 内构造具名 daemon 线程
 * （{@code sweeper} / {@code HangWatchdog} / {@code RouteDelayScheduler}）正是正确做法，一刀切会误伤。</p>
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
            .should().callMethod(BrowserContext.class, "route", String.class, Consumer.class)
            .orShould().callMethod(BrowserContext.class, "route", String.class, Consumer.class, BrowserContext.RouteOptions.class)
            .orShould().callMethod(BrowserContext.class, "route", Pattern.class, Consumer.class)
            .orShould().callMethod(BrowserContext.class, "route", Pattern.class, Consumer.class, BrowserContext.RouteOptions.class)
            .orShould().callMethod(BrowserContext.class, "route", Predicate.class, Consumer.class)
            .orShould().callMethod(BrowserContext.class, "route", Predicate.class, Consumer.class, BrowserContext.RouteOptions.class)
            .orShould().callMethod(BrowserContext.class, "unroute", String.class)
            .orShould().callMethod(BrowserContext.class, "unroute", String.class, Consumer.class)
            .orShould().callMethod(BrowserContext.class, "unroute", Pattern.class)
            .orShould().callMethod(BrowserContext.class, "unroute", Pattern.class, Consumer.class)
            .orShould().callMethod(BrowserContext.class, "unroute", Predicate.class)
            .orShould().callMethod(BrowserContext.class, "unroute", Predicate.class, Consumer.class)
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
            .should().callMethod(Route.class, "fetch")
            .orShould().callMethod(Route.class, "fetch", Route.FetchOptions.class);

    /**
     * V2-5（评审 23 号）：禁止 {@code CompletableFuture.delayedExecutor} —— 其两参重载会把任务落到
     * {@code ForkJoinPool.commonPool()}（JVM 级共享池），与模块"有界并发"原则冲突。
     * 唯一合法替代：{@code RouteDelayScheduler}（模块自有 daemon、线程数有界）。
     */
    @ArchTest
    static final ArchRule noForkJoinCommonPoolDelayInRouteV2 =
        noClasses()
            .that().resideInAPackage("..route.v2..")
            .should().callMethod(CompletableFuture.class, "delayedExecutor", long.class, java.util.concurrent.TimeUnit.class)
            .orShould().callMethod(CompletableFuture.class, "delayedExecutor", long.class, java.util.concurrent.TimeUnit.class, java.util.concurrent.Executor.class);

    /**
     * V2-5：禁止 {@code ForkJoinPool.commonPool()} —— JVM 级共享、并行度 = 核数 - 1，不受模块约束。
     */
    @ArchTest
    static final ArchRule noForkJoinCommonPoolInRouteV2 =
        noClasses()
            .that().resideInAPackage("..route.v2..")
            .should().callMethod(ForkJoinPool.class, "commonPool");

    /**
     * V2-5：禁止 {@code Executors.newCachedThreadPool}（无界线程池）。有界池（newFixedThreadPool /
     * newScheduledThreadPool / newSingleThreadScheduledExecutor）不受限制 —— 模块既有 daemon 先例均属合法。
     */
    @ArchTest
    static final ArchRule noUnboundedThreadPoolInRouteV2 =
        noClasses()
            .that().resideInAPackage("..route.v2..")
            .should().callMethod(Executors.class, "newCachedThreadPool")
            .orShould().callMethod(Executors.class, "newCachedThreadPool", java.util.concurrent.ThreadFactory.class);
}
