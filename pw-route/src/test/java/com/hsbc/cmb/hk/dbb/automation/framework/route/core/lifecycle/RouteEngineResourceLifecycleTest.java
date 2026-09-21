package com.hsbc.cmb.hk.dbb.automation.framework.route.core.lifecycle;

import com.hsbc.cmb.hk.dbb.automation.framework.common.async.AsyncPool;
import com.hsbc.cmb.hk.dbb.automation.framework.route.core.engine.DelayScheduler;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Route 引擎的「并发 / 线程 / 资源清理」生命周期守卫（2026-09-17 专项评审落地）。
 *
 * <p>评审聚焦三问：会不会竞态、线程会不会不释放、资源能不能即时清理。本类把其中的关键不变量
 * 固化为可执行断言：
 * <ol>
 *   <li><b>唯一性</b>：per-context 调度器的 key（{@code PerContextEngine.contextId}）必须唯一 ——
 *       它是 {@code AsyncPool.CONTEXT_SCHEDULERS} 的 key，而该 Map 用「覆盖 put / 按 key remove」
 *       维护：key 碰撞会让旧池失去跟踪、remove 误删他池条目，使「活跃调度器数」这一泄漏判据失真；
 *       原实现只用 {@code System.identityHashCode}（不保证唯一），已改为「自增序号 + hash」。</li>
 *   <li><b>即时释放</b>：引擎关闭后，其调度器必须从 {@code AsyncPool} 的活跃表中消失
 *       （线程/池随 context 释放，不残留）。</li>
 *   <li><b>关闭即时释放 + 不丢 DELAY 任务</b>：{@code ScheduledThreadPoolExecutor} 默认会把<b>尚未到期</b>
 *       的延迟任务<b>保留到原定延时后才执行</b>（{@code shutdownNow()} 既不取消也不返回它们），
 *       于是池与线程会一直滞留到那一刻——而 {@code close()} 已把池移出 {@code AsyncPool} 跟踪表，
 *       这种滞留<b>不可观测</b>。关闭路径必须显式取出这些任务、立即执行（其动作是
 *       {@code route.resume()} 放行请求），从而既即时归还线程、又绝不丢放行。</li>
 * </ol>
 *
 * <p>用 {@code null} 构造 {@link PerContextEngine}：构造函数只依赖
 * {@code identityHashCode(context)}（{@code null} 合法，取 0）与自增序号，不需要真实 BrowserContext；
 * 本模块测试为纯 JUnit（无 Mockito / 无 Playwright 运行时）。
 */
class RouteEngineResourceLifecycleTest {

    /** 引擎数量：足以暴露 identityHashCode 类碰撞（若 key 仍非唯一，活跃计数会少于引擎数）。 */
    private static final int ENGINE_COUNT = 120;

    /**
     * 唯一性 + 即时释放：N 个引擎的 key 必须两两不同、被 AsyncPool 全部跟踪；关闭后活跃数回落到基线。
     */
    @Test
    void contextIdsAreUnique_trackedThenReleasedOnClose() {
        int baseline = AsyncPool.getActiveContextSchedulerCount();
        Set<String> ids = new HashSet<>();
        List<PerContextEngine> engines = new ArrayList<>();

        for (int i = 0; i < ENGINE_COUNT; i++) {
            PerContextEngine engine = new PerContextEngine(null);
            assertTrue(ids.add(engine.contextId),
                    "contextId 必须唯一（重复即 key 碰撞，会让 AsyncPool 覆盖跟踪条目）：" + engine.contextId);
            engines.add(engine);
        }
        assertEquals(baseline + ENGINE_COUNT, AsyncPool.getActiveContextSchedulerCount(),
                "每个引擎的调度器都必须被 AsyncPool 跟踪（计数偏少说明发生了 key 覆盖）");

        for (PerContextEngine engine : engines) {
            engine.close();
        }
        assertEquals(baseline, AsyncPool.getActiveContextSchedulerCount(),
                "引擎关闭后其调度器必须从活跃表移除（否则线程/池随 context 泄漏）");
    }

    /**
     * 关闭不丢 DELAY 任务：宽限期（3s）内未到期的 DELAY 任务被 {@code shutdownNow()} 取消后，
     * 必须<b>立即执行</b>，否则对应请求永不 resume（挂起）。
     */
    @Test
    void pendingDelayTaskIsRunNotDroppedWhenEngineCloses() throws Exception {
        PerContextEngine engine = new PerContextEngine(null);
        CountDownLatch ran = new CountDownLatch(1);
        AtomicBoolean interrupted = new AtomicBoolean(false);

        // 延迟 10s 远超 close() 的 3s 宽限期：修复前该任务会被保留到原定 10s 才执行
        //（池与线程随之滞留，而此时池已不在 AsyncPool 跟踪表中 → 滞留不可观测）
        engine.delayScheduler().schedule(() -> {
            if (Thread.currentThread().isInterrupted()) {
                interrupted.set(true);
            }
            ran.countDown();
        }, 10, TimeUnit.SECONDS);

        long start = System.currentTimeMillis();
        engine.close();

        assertTrue(ran.await(6, TimeUnit.SECONDS),
                "引擎关闭时必须执行（而非丢弃）被取消的 DELAY 任务，否则被拦截请求会永久挂起");
        assertTrue(System.currentTimeMillis() - start < 9_000,
                "任务应在宽限期结束时被立即执行，而不是等满原定 10s 延迟");
        assertEquals(EngineState.CLOSED, engine.state(), "close() 完成后引擎状态应为 CLOSED");
        assertThrows(IllegalStateException.class, engine::delayScheduler,
                "已关闭的引擎不得再对外提供调度器（避免向已停池提交任务）");
        assertTrue(!interrupted.get(), "排空执行不应以中断标志运行（任务按正常路径 resume）");
    }

    /** 排空器自身的健壮性：单个任务抛异常不得中断其余任务的执行。 */
    @Test
    void drainAbandonedDelayTasks_runsAllAndIsolatesFailures() {
        AtomicInteger executed = new AtomicInteger();
        List<Runnable> pending = Arrays.asList(
                executed::incrementAndGet,
                () -> {
                    throw new IllegalStateException("boom");
                },
                executed::incrementAndGet);

        DelayScheduler.drainAbandonedDelayTasks(pending, "unit-test");

        assertEquals(2, executed.get(), "一个任务失败不得阻断其余待执行任务");
    }

    /** 空/ null 入参安全（关闭路径可能无待执行任务）。 */
    @Test
    void drainAbandonedDelayTasks_nullOrEmptyIsNoop() {
        DelayScheduler.drainAbandonedDelayTasks(null, "unit-test");
        DelayScheduler.drainAbandonedDelayTasks(new ArrayList<>(), "unit-test");
    }
}
