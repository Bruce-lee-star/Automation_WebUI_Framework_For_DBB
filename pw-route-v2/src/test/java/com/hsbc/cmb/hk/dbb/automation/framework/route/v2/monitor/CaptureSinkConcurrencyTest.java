package com.hsbc.cmb.hk.dbb.automation.framework.route.v2.monitor;

import com.hsbc.cmb.hk.dbb.automation.framework.route.v2.dsl.ApiSpec;
import com.hsbc.cmb.hk.dbb.automation.framework.route.v2.dsl.RouteCapability;
import com.hsbc.cmb.hk.dbb.automation.framework.route.v2.exec.RouteIoExecutor;
import com.microsoft.playwright.Request;
import com.microsoft.playwright.Response;
import org.junit.After;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * CaptureSink 并发安全验证（用户核心关切：采集与能力并发、采集自身并发不产生时序竞态）。
 *
 * <p>验证模型：多个“事件线程”同时 recordRequest + onResponse（模拟 Playwright 事件并发），
 * 业务线程同时 dump 消费；断言快照无丢失、无重复、无异常——线程之间只经
 * ConcurrentLinkedQueue + volatile 交换，无共享可变状态。
 */
public class CaptureSinkConcurrencyTest {

    private RouteIoExecutor io;

    @After
    public void tearDown() {
        if (io != null) {
            io.close();
            io = null;
        }
    }

    @Test
    public void concurrentRecordResponseAndDumpLoseNothing() throws Exception {
        io = new RouteIoExecutor("c", 2, 64);
        ApiSpec spec = ApiSpec.builder("/api/**", RouteCapability.MONITOR)
                .capture(true)
                .captureBody(true)
                .build();
        CaptureSink sink = new CaptureSink(io, 100_000, 60_000);

        int workers = 8;
        int perWorker = 20;
        int total = workers * perWorker;
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();

        for (int w = 0; w < workers; w++) {
            final int workerId = w;
            futures.add(pool.submit(() -> {
                start.await();
                for (int i = 0; i < perWorker; i++) {
                    String url = "https://host/api/u/" + workerId + "/" + i;
                    sink.recordRequest(mockRequest(url), spec);
                    sink.onResponseForSpec(spec, mockResponse(url, 200));
                }
                return null;
            }));
        }
        // 消费者线程：与生产者并发 dump（消费式取走，计数以参与总量校验）
        AtomicLong consumed = new AtomicLong();
        CountDownLatch done = new CountDownLatch(1);
        Future<?> consumerFuture = pool.submit(() -> {
            try {
                while (done.getCount() > 0 && !Thread.currentThread().isInterrupted()) {
                    consumed.addAndGet(sink.dump().size());
                }
            } catch (Throwable t) {
                throw new RuntimeException(t);
            }
            return null;
        });

        start.countDown();
        for (Future<?> f : futures) {
            f.get(30, TimeUnit.SECONDS);
        }
        done.countDown();
        consumerFuture.get(15, TimeUnit.SECONDS);
        pool.shutdown();
        assertTrue( "测试线程池未在时限内收敛", pool.awaitTermination(15, TimeUnit.SECONDS));

        io.close();
        io = null;

        // 最终消费：消费者消费量 + 主线程剩余 = 全部快照（无丢失、无重复）
        List<CapturedApiCall> finalCalls = sink.dump();
        assertEquals("并发 record+onResponse+dump 后快照总数必须等于请求总数（无丢失无重复）", (long) total, (long) (consumed.get() + finalCalls.size()));
        for (CapturedApiCall call : finalCalls) {
            assertEquals((long) 200L, (long) call.responseStatus());
        }
    }

    @Test
    public void concurrentDumpIsIdempotentAndSafe() throws Exception {
        io = new RouteIoExecutor("d", 1, 8);
        ApiSpec spec = ApiSpec.builder("/api/users/**", RouteCapability.MONITOR)
                .capture(true)
                .build();
        CaptureSink sink = new CaptureSink(io, 100, 30_000);

        for (int i = 0; i < 50; i++) {
            String url = "https://host/api/users/" + i;
            sink.recordRequest(mockRequest(url), spec);
            sink.onResponseForSpec(spec, mockResponse(url, 200));
        }

        // 两个消费者线程同时 dump：总量恰好 50（原子出队），无重复
        ExecutorService pool = Executors.newFixedThreadPool(2);
        Future<List<CapturedApiCall>> a = pool.submit(sink::dump);
        Future<List<CapturedApiCall>> b = pool.submit(sink::dump);
        int sum = a.get(15, TimeUnit.SECONDS).size() + b.get(15, TimeUnit.SECONDS).size();
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        assertEquals("并发消费不得重复或丢失", (long) 50, (long) sum);
        assertEquals((long) 0, (long) sink.size());
    }

    private Request mockRequest(String url) {
        Request request = mock(Request.class);
        when(request.method()).thenReturn("GET");
        when(request.url()).thenReturn(url);
        when(request.headers()).thenReturn(Map.of("content-type", "application/json"));
        when(request.postData()).thenReturn(null);
        return request;
    }

    private Response mockResponse(String url, int status) {
        Response response = mock(Response.class);
        when(response.url()).thenReturn(url);
        when(response.status()).thenReturn(status);
        when(response.headers()).thenReturn(Map.of("content-type", "application/json"));
        when(response.body()).thenReturn("{\"ok\":true}".getBytes(StandardCharsets.UTF_8));
        return response;
    }
}
