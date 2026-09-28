package com.hsbc.cmb.hk.dbb.automation.framework.route.v2.binding;

import com.hsbc.cmb.hk.dbb.automation.framework.route.v2.dsl.ApiSpec;
import com.hsbc.cmb.hk.dbb.automation.framework.route.v2.dsl.RouteCapability;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * 代际注册表并发验证：
 * <ul>
 *   <li>并发 merge 后快照一致（无撕裂读 / 无丢规则）；</li>
 *   <li>同 pattern 后注册覆盖先注册；</li>
 *   <li>代序号严格递增。</li>
 * </ul>
 */
public class GenerationRegistryConcurrencyTest {

    private ApiSpec spec(String pattern) {
        return ApiSpec.builder(pattern, RouteCapability.MONITOR).build();
    }

    @Test
    public void concurrentMergesAllVisible() throws Exception {
        GenerationRegistry registry = new GenerationRegistry();
        int threads = 8;
        int perThread = 50;
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<?>> futures = new ArrayList<>();

            for (int t = 0; t < threads; t++) {
                final int threadId = t;
                futures.add(pool.submit(() -> {
                    try {
                        start.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    for (int i = 0; i < perThread; i++) {
                        registry.merge(spec("/api/t" + threadId + "/i" + i));
                    }
                }));
            }
            start.countDown();
            for (Future<?> f : futures) {
                f.get(10, TimeUnit.SECONDS);
            }
            pool.shutdownNow();
        }

        RuleGeneration snapshot = registry.snapshot();
        assertEquals("全部并发 merge 的规则都必须在最终快照中可见", (Object) (threads * perThread), (Object) snapshot.rules().size());
        assertTrue( "代序号必须随每次 merge 递增", snapshot.generation() >= threads * perThread);
    }

    @Test
    public void samePatternLaterMergeOverwrites() {
        GenerationRegistry registry = new GenerationRegistry();
        registry.merge(ApiSpec.builder("/api/users", RouteCapability.MOCK).mockBody("old-body").build());
        registry.merge(ApiSpec.builder("/api/users", RouteCapability.MOCK).mockStatus(200).build());

        ApiSpec current = registry.snapshot().specFor("/api/users");
        assertNotNull(current);
        assertEquals("同 pattern 覆盖后规则表只保留一条", (long) 1, (long) registry.snapshot().rules().size());
        assertNull( "后注册规则必须完全覆盖先注册规则", current.mockBody());
        assertEquals((long) 200L, (long) current.mockStatus());
    }

    @Test
    public void snapshotIsStableAcrossMerges() {
        GenerationRegistry registry = new GenerationRegistry();
        RuleGeneration gen1 = registry.snapshot();
        registry.merge(spec("/api/a"));
        RuleGeneration gen2 = registry.snapshot();

        assertTrue(gen1.rules().isEmpty());
        assertEquals((long) 0, (long) gen1.generation());
        assertNotNull(gen2.specFor("/api/a"));
        assertEquals((long) 1, (long) gen2.generation());
        // 旧快照 gen1 不可变：即使有新代发布，旧代内容不变
        assertTrue(gen1.rules().isEmpty());
    }
}
