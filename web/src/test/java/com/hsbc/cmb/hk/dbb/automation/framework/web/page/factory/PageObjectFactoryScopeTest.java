package com.hsbc.cmb.hk.dbb.automation.framework.web.page.factory;

import com.hsbc.cmb.hk.dbb.automation.framework.core.context.ScenarioContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PageObjectFactory 作用域与发布语义守卫（2026-09-17 评审落地）。
 *
 * <p>本轮修掉的两个真实缺陷：
 * <ol>
 *   <li><b>检查-创建竞态</b>：原 {@code getPage} 是「读取 → 为 null 则创建 → 存回」，
 *       并发首调用会各自创建并顺序覆盖 → 同一时刻不同调用方拿到<b>不同实例</b>（身份不一致），
 *       后来者静默覆盖前者。现改为「读 → 创建 → putIfAbsent 竞争发布 → 落败者返回已发布者」。</li>
 *   <li><b>请求作用域身份错位</b>：原以 {@code Thread.currentThread().getName()} 为键 →
 *       线程池复用下「请求作用域」退化为「线程作用域」（跨用例复用同一实例），且线程改名即换作用域。
 *       现以用例身份（{@code ScenarioContext.currentScenarioId()}）为键，无绑定时回退线程身份。</li>
 * </ol>
 */
class PageObjectFactoryScopeTest {

    /** 纯 POJO 充当被测 PageObject（无需浏览器）。 */
    public static class ScopedPage {
    }

    @AfterEach
    void tearDown() {
        ScenarioContext.endCurrent();
        PageObjectFactory.unregister(ScopedPage.class);
        PageObjectFactory.clearAll();
    }

    private static PageObjectFactory.CreationConfig config(PageObjectFactory.LifecycleStrategy strategy) {
        return new PageObjectFactory.Builder().lifecycle(strategy).build();
    }

    /** 并发首调用：全部调用方必须看到<b>同一</b>已发布实例（旧实现会各自创建并互相覆盖）。 */
    @Test
    void concurrentFirstCallsPublishSingleInstance() throws Exception {
        PageObjectFactory.register(ScopedPage.class, ScopedPage::new);
        int threads = 8;
        CyclicBarrier barrier = new CyclicBarrier(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            CompletionService<Object> results = new ExecutorCompletionService<>(pool);
            for (int i = 0; i < threads; i++) {
                results.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS); // 尽量让 8 个线程同时进入首次获取
                    return PageObjectFactory.getPage(ScopedPage.class,
                            config(PageObjectFactory.LifecycleStrategy.SINGLETON));
                });
            }
            Set<Object> seen = ConcurrentHashMap.newKeySet();
            for (int i = 0; i < threads; i++) {
                seen.add(results.take().get(5, TimeUnit.SECONDS));
            }
            assertEquals(1, seen.size(),
                    "同一 (策略, key) 的所有调用方必须看到同一实例，实际看到 " + seen.size() + " 个");
        } finally {
            pool.shutdownNow();
        }
    }

    /** 请求作用域必须随「用例」变化 —— 旧实现以线程名为键，换用例仍会复用同一实例。 */
    @Test
    void requestScopeIsBoundToScenarioNotToThreadName() {
        PageObjectFactory.register(ScopedPage.class, ScopedPage::new);
        PageObjectFactory.LifecycleStrategy requestScoped = PageObjectFactory.LifecycleStrategy.REQUEST_SCOPED;

        ScenarioContext.begin("scope-test-scn-A");
        Object firstInA = PageObjectFactory.getPage(ScopedPage.class, config(requestScoped));
        Object secondInA = PageObjectFactory.getPage(ScopedPage.class, config(requestScoped));
        assertSame(firstInA, secondInA, "同一用例内应复用同一实例");

        ScenarioContext.endCurrent();
        ScenarioContext.begin("scope-test-scn-B");
        Object firstInB = PageObjectFactory.getPage(ScopedPage.class, config(requestScoped));
        assertNotSame(firstInA, firstInB,
                "换用例必须换实例（按线程名为键会错误复用上一个用例的实例）");
    }

    /** 用例收尾回收请求作用域：否则实例（及其持有的 Page/Context 引用）会随用例数累积。 */
    @Test
    void endRequestScopeReleasesScenarioScopedInstances() {
        PageObjectFactory.register(ScopedPage.class, ScopedPage::new);
        ScenarioContext.begin("scope-test-scn-C");
        PageObjectFactory.getPage(ScopedPage.class, config(PageObjectFactory.LifecycleStrategy.REQUEST_SCOPED));

        int before = PageObjectFactory.getInstanceCount();
        PageObjectFactory.endRequestScope();
        int after = PageObjectFactory.getInstanceCount();

        assertTrue(before > after, "收尾后请求作用域实例应被回收（before=" + before + ", after=" + after + "）");
    }

    /** 原型策略：每次都必须新建（不缓存、不参与发布竞争）。 */
    @Test
    void prototypeAlwaysCreatesNewInstance() {
        PageObjectFactory.register(ScopedPage.class, ScopedPage::new);
        Object p1 = PageObjectFactory.getPage(ScopedPage.class,
                config(PageObjectFactory.LifecycleStrategy.PROTOTYPE));
        Object p2 = PageObjectFactory.getPage(ScopedPage.class,
                config(PageObjectFactory.LifecycleStrategy.PROTOTYPE));
        assertNotSame(p1, p2, "原型策略每次调用都应得到新实例");
    }

    /** 线程隔离策略在并发下仍须隔离（回归守卫：原子化重构不得破坏隔离语义）。 */
    @Test
    void threadIsolatedInstancesRemainIsolatedPerThread() throws Exception {
        PageObjectFactory.register(ScopedPage.class, ScopedPage::new);
        PageObjectFactory.LifecycleStrategy threadIsolated = PageObjectFactory.LifecycleStrategy.THREAD_ISOLATED;
        Object mainInstance = PageObjectFactory.getPage(ScopedPage.class, config(threadIsolated));

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Object other = pool.submit(() -> PageObjectFactory.getPage(ScopedPage.class, config(threadIsolated)))
                    .get(5, TimeUnit.SECONDS);
            assertNotSame(mainInstance, other, "不同线程应拿到各自的线程隔离实例");
            assertSame(mainInstance, PageObjectFactory.getPage(ScopedPage.class, config(threadIsolated)),
                    "同一线程内应复用同一实例");
        } finally {
            pool.shutdownNow();
        }
    }
}
