package com.hsbc.cmb.hk.dbb.automation.framework.web.page.engine;

import com.hsbc.cmb.hk.dbb.automation.framework.web.core.RuntimeProvider;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.PlaywrightManager;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import org.junit.After;
import org.junit.Test;

import java.lang.reflect.Proxy;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;

/**
 * CT2-03 验收用例：两线程<b>同时首次</b>访问同一 {@code BasePage} 时，线程归属根锚点必须成立
 * —— 恰有一线程赢得归属，另一线程被拒绝。
 *
 * <p><b>为什么必须专测这条路径</b>：既有 {@code BasePageThreadOwnershipConcurrencyTest} 覆盖的是
 * 「归属<b>已确立之后</b>的跨线程访问」——那正是守卫<b>能</b>正确拒绝的路径；而守卫唯一的竞态窗口是
 * 「实例<b>首次</b>被使用」：原实现「读 volatile → 判 null → 写」在无 CAS 时，两线程可双双读到
 * {@code null} 并双双通过，写入后者覆盖前者 → 根锚点失效 → 共享 Page 并发使用（pipe closed /
 * 静默操作错对象）。该路径此前<b>没有任何用例覆盖</b>，测试的存在反而制造了虚假信心。
 *
 * <p>实现：每轮新建一个 {@code BasePage}，两线程经 {@link CyclicBarrier} 对齐起点后同时冲刺
 * {@code getPage()}；断言每轮 <b>恰有 1 个</b>线程收到线程归属 {@code IllegalStateException}。
 * 重复 {@value #ROUNDS} 轮以放大「双双通过」的复现概率。
 *
 * <p>无浏览器依赖：经 WEB-P0-2 DI seam（{@code PlaywrightManager.setProvider}）注入桩，
 * 仅在 JVM 内验证线程归属语义。
 */
public class PageContextStateOwnershipRaceTest {

    private static final int ROUNDS = 200;

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> iface) {
        return (T) Proxy.newProxyInstance(
                PageContextStateOwnershipRaceTest.class.getClassLoader(),
                new Class<?>[]{iface},
                (p, method, args) -> {
                    if (method.getReturnType() == boolean.class) {
                        return false;
                    }
                    if (method.getReturnType().isPrimitive()) {
                        return 0;
                    }
                    return null;
                });
    }

    private static RuntimeProvider provider(Page page, BrowserContext ctx) {
        return new RuntimeProvider() {
            @Override
            public Playwright getPlaywright() {
                return null;
            }

            @Override
            public Browser getBrowser() {
                return null;
            }

            @Override
            public BrowserContext getContext() {
                return ctx;
            }

            @Override
            public Page getPage() {
                return page;
            }
        };
    }

    @After
    public void tearDown() {
        PlaywrightManager.resetProvider();
    }

    /** 是否为「线程归属」拒绝（区别于其它异常 —— 其它异常不得计入）。 */
    private static boolean isOwnershipViolation(Throwable t) {
        return t instanceof IllegalStateException
                && t.getMessage() != null
                && t.getMessage().contains("bound to thread");
    }

    @Test(timeout = 180_000)
    // @DisplayName: "CT2-03：两线程同时首次访问同一 BasePage → 每轮恰有一线程被归属守卫拒绝"
    public void simultaneousFirstAccessHasExactlyOneOwner() throws Exception {
        Page page = proxy(Page.class);
        BrowserContext ctx = proxy(BrowserContext.class);
        PlaywrightManager.setProvider(provider(page, ctx));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < ROUNDS; round++) {
                final BasePage bp = new BasePage() {
                };
                final CyclicBarrier barrier = new CyclicBarrier(2);
                Callable<Throwable> racer = () -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    try {
                        bp.getPage();
                        return null;
                    } catch (Throwable t) {
                        return t;
                    }
                };
                Future<Throwable> f1 = pool.submit(racer);
                Future<Throwable> f2 = pool.submit(racer);
                Throwable t1 = f1.get(20, TimeUnit.SECONDS);
                Throwable t2 = f2.get(20, TimeUnit.SECONDS);

                int rejected = (isOwnershipViolation(t1) ? 1 : 0) + (isOwnershipViolation(t2) ? 1 : 0);
                assertEquals("第 " + round + " 轮：必须恰有一线程被线程归属守卫拒绝（CT2-03 根锚点竞态）。"
                                + "实际 rejected=" + rejected + ", t1=" + t1 + ", t2=" + t2, 1, rejected);
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
