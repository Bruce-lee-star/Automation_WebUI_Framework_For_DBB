package com.hsbc.cmb.hk.dbb.automation.tests.route;

import com.hsbc.cmb.hk.dbb.automation.framework.route.core.capture.ApiCaptureContext;
import com.hsbc.cmb.hk.dbb.automation.framework.route.core.rule.RouteHandleType;
import com.hsbc.cmb.hk.dbb.automation.framework.route.core.rule.RouteRule;
import com.hsbc.cmb.hk.dbb.automation.framework.route.handler.ModifyHandler;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Frame;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.Request;
import com.microsoft.playwright.Response;
import com.microsoft.playwright.Route;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;

import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 评审 F-08 / P1-4 回归网：{@link ModifyHandler} 的观测<b>必须下沉到工作线程</b>，
 * Playwright 事件线程不得同步等待 {@code page.waitForResponse}。
 *
 * <p><b>为何需要它</b>：原实现在事件线程同步执行 {@code page.waitForResponse}（≤30s），
 * 单 context 内该路由分发被串行化 → 后续请求 handler 全部排队 → 级联超时（F-08）。
 * 本测试把 {@code waitForResponse} 打桩为<b>阻塞 {@value #OBSERVE_BLOCK_MS}ms 后抛
 * {@link PlaywrightException}</b>（等价于「慢后端 / 观测失败」这一最坏路径），据此断言：</p>
 * <ol>
 *   <li>{@code handle(...)} 在调用线程上<b>毫秒级返回</b>（远小于阻塞时长）—— 事件线程零阻塞；</li>
 *   <li>阻塞调用发生在 {@code modify-observe-*} <b>守护</b>线程上，而非调用线程；</li>
 *   <li>在途计数协议：提交前事件线程已 {@code increment}（等待窗口内 {@code activeRequests==1}，
 *       主线程不会误判「全部完成」），任务结束必然回到 0（不泄漏计数、不永久阻塞 awaitCompletion）；</li>
 *   <li>路由生命周期契约在异步路径同样成立：观测失败后 route 仍被放行（恰好终结一次）。</li>
 * </ol>
 *
 * <p>打桩走「observation 失败」分支（而非成功分支）是刻意的：该分支只涉及
 * {@code waitForResponse} → {@code safeResume} 这条最核心的阻塞链路，不牵入落库 / 报告 / 断言等
 * 周边协作方，使本测试聚焦于「事件线程是否被阻塞」这一 F-08 的唯一验收点。</p>
 *
 * <p>用 Playwright 接口 mock（{@code Route}/{@code Request}/{@code Frame}/{@code Page}）驱动，
 * 无需真实浏览器。</p>
 */
public class ModifyHandlerEventThreadOffloadTest {

    /** 打桩的观测阻塞时长：远大于「事件线程是否被阻塞」的判定阈值，使断言方向明确。 */
    private static final long OBSERVE_BLOCK_MS = 2000;

    /** 事件线程允许的最大返回耗时：真实无阻塞时应为毫秒级，2000ms 阻塞必然超此阈值。 */
    private static final long EVENT_THREAD_BUDGET_MS = 1000;

    @Test
    public void handleReturnsImmediatelyAndObservesOnWorkerThread() throws Exception {
        Fixture fx = new Fixture();

        long started = System.currentTimeMillis();
        ModifyHandler.handle(fx.route, fx.rule, 0);
        long elapsed = System.currentTimeMillis() - started;

        assertTrue(elapsed < EVENT_THREAD_BUDGET_MS,
                "F-08：事件线程不得同步等待观测（实测 handle 耗时 " + elapsed + "ms，"
                        + "而被打桩的 waitForResponse 阻塞 " + OBSERVE_BLOCK_MS + "ms）");
        assertTrue(fx.observationStarted.await(5, TimeUnit.SECONDS), "观测任务应已被提交并开始执行");

        Thread observed = fx.observeThread.get();
        assertNotSame(Thread.currentThread(), observed,
                "观测必须在工作线程执行，而不是调用（事件）线程");
        assertTrue(observed.getName().startsWith("modify-observe-"),
                "观测应运行在专用池线程上（实际线程名：" + observed.getName() + "）");
        assertTrue(observed.isDaemon(), "观测线程应为守护线程，不得阻止 JVM 退出");

        //  路由生命周期契约：观测失败后必须放行，绝不留下永久 pending 的请求
        verify(fx.route, timeout(5000)).resume(any(Route.ResumeOptions.class));
    }

    @Test
    public void activeRequestsIsIncrementedBeforeSubmitAndReleasedAfterObservation() throws Exception {
        Fixture fx = new Fixture();
        ApiCaptureContext ctx = ApiCaptureContext.forContext(fx.context);

        assertEquals(0, ctx.getActiveRequests(), "前置：无在途请求");

        ModifyHandler.handle(fx.route, fx.rule, 0);

        //  提交前已在事件线程递增：此后（观测尚在阻塞）不得出现 activeRequests==0 的空窗，
        //  否则 awaitCompletion 会在「已放行但观测未完成」时误判「全部完成」。
        assertEquals(1, ctx.getActiveRequests(),
                "F-08：观测在途期间必须对 awaitCompletion 可见（提交前同步递增）");

        assertTrue(fx.observationStarted.await(5, TimeUnit.SECONDS), "观测任务应已开始执行");
        assertTrue(waitUntilZero(ctx, 5000), "观测结束后在途计数必须回到 0（配对递减，不得泄漏）");
    }

    @Test
    public void repeatedHandlesUseTheSameExecutorAndNeverBlockTheCaller() throws Exception {
        Fixture first = new Fixture();
        ModifyHandler.handle(first.route, first.rule, 0);
        assertTrue(first.observationStarted.await(5, TimeUnit.SECONDS));

        Fixture second = new Fixture();
        long started = System.currentTimeMillis();
        ModifyHandler.handle(second.route, second.rule, 0);
        long elapsed = System.currentTimeMillis() - started;

        //  第一个任务仍在阻塞观测中，第二个请求的事件线程依旧不被反压（有界队列 + 多工作线程）
        assertTrue(elapsed < EVENT_THREAD_BUDGET_MS,
                "并发观测不得反压事件线程（实测 " + elapsed + "ms）");
        assertTrue(second.observationStarted.await(5, TimeUnit.SECONDS),
                "第二个观测任务应并行执行，不被前一个的长阻塞串行化");
    }

    private static boolean waitUntilZero(ApiCaptureContext ctx, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (ctx.getActiveRequests() == 0) {
                return true;
            }
            Thread.sleep(20);
        }
        return false;
    }

    /** 每个用例一套独立的 Playwright mock 链 + 可观测的执行证据。 */
    private static final class Fixture {
        private final BrowserContext context = mock(BrowserContext.class);
        private final Page page = mock(Page.class);
        private final Frame frame = mock(Frame.class);
        private final Request request = mock(Request.class);
        private final Route route = mock(Route.class);
        private final RouteRule rule = new RouteRule();

        private final CountDownLatch observationStarted = new CountDownLatch(1);
        private final AtomicReference<Thread> observeThread = new AtomicReference<>();

        private Fixture() {
            rule.setUrlPattern("/demo/api/users");
            rule.setType(RouteHandleType.MODIFY);

            when(request.url()).thenReturn("http://localhost:8888/demo/api/users");
            when(request.method()).thenReturn("GET");
            when(request.headers()).thenReturn(Collections.emptyMap());
            when(frame.page()).thenReturn(page);
            when(request.frame()).thenReturn(frame);
            when(route.request()).thenReturn(request);
            when(page.context()).thenReturn(context);
            when(page.isClosed()).thenReturn(false);
            when(page.waitForResponse(
                    ArgumentMatchers.<Predicate<Response>>any(),
                    any(Page.WaitForResponseOptions.class),
                    any(Runnable.class)))
                    .thenAnswer(invocation -> {
                        observeThread.set(Thread.currentThread());
                        observationStarted.countDown();
                        Thread.sleep(OBSERVE_BLOCK_MS);
                        throw new PlaywrightException("simulated slow/absent response (F-08 stub)");
                    });
        }
    }

    /** 说明用断言：mock 的 request/frame/page 链自洽（防止 fixture 静默失效使断言失去意义）。 */
    @Test
    public void fixtureWiresTheSamePageChainItStubs() {
        Fixture fx = new Fixture();
        assertSame(fx.page, fx.route.request().frame().page(), "mock 链应指向同一 Page 实例");
        assertEquals("GET", fx.route.request().method());
    }
}
