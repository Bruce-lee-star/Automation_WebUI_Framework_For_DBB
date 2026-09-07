package com.hsbc.cmb.hk.dbb.automation.tests.route;

import com.hsbc.cmb.hk.dbb.automation.framework.web.utils.NLSUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * 验证 Monitor onResponse 回调线程设置语言后，主测试线程的可见性。
 *
 * <p><b>背景（真实使用姿势）</b>：测试先在 UI 上切语言 → 主线程 {@code setLanguage} 设初值；
 * 随后某个 API 响应回调（跑在 {@code monitor-callback} 线程）依据响应内容
 * {@code setLanguage} 改成实际语言；主线程随后读取 {@code getLanguage()} 做断言。
 *
 * <p><b>历史 bug</b>：双轨实现原为「线程级覆盖无条件优先」，导致主线程一旦设置过初值，
 * 回调线程随后的设置会被主线程陈旧副本<b>永久遮蔽</b>。本测试固化「取较新写入」的修复。
 */
public class NlsUtilsCrossThreadTest {

    @Before
    @After
    public void clear() {
        NLSUtils.reset();
    }

    /** 在独立线程执行（模拟 monitor-callback 线程），并等待其完成。 */
    private static void runOnOtherThread(Runnable task) throws InterruptedException {
        CountDownLatch done = new CountDownLatch(1);
        Thread t = new Thread(() -> {
            try {
                task.run();
            } finally {
                done.countDown();
            }
        }, "simulated-monitor-callback");
        t.setDaemon(true);
        t.start();
        if (!done.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("simulated callback thread did not finish in time");
        }
    }

    /**
     *  核心场景：主线程先设初值，回调线程后改 → 主线程必须读到回调线程的值。
     *
     * <p>修复前：主线程 override="en" 遮蔽了 global="zh"，返回 "en"（错误）。
     */
    @Test
    public void callbackThreadUpdateIsVisibleToMainThread() throws Exception {
        NLSUtils.setLanguage("en");                       // 主线程初值
        assertEquals("en", NLSUtils.getLanguage());

        runOnOtherThread(() -> NLSUtils.setLanguage("zh")); // 回调线程改为 zh

        assertEquals("回调线程设置的语言必须对主线程可见", "zh", NLSUtils.getLanguage());
    }

    /** 反向：回调线程先设、主线程后改 → 主线程应读到自己的新值（本线程后写优先）。 */
    @Test
    public void mainThreadLaterWriteWins() throws Exception {
        runOnOtherThread(() -> NLSUtils.setLanguage("zh"));

        NLSUtils.setLanguage("en");

        assertEquals("主线程后写应优先生效", "en", NLSUtils.getLanguage());
    }

    /** 回调线程设置后，另一个从未设置过的线程也应能读到（回退到全局值）。 */
    @Test
    public void freshThreadFallsBackToGlobal() throws Exception {
        NLSUtils.setLanguage("en");
        runOnOtherThread(() -> NLSUtils.setLanguage("zh"));

        AtomicReference<String> seen = new AtomicReference<>();
        Thread reader = new Thread(() -> seen.set(NLSUtils.getLanguage()), "fresh-reader");
        reader.start();
        reader.join(5000);

        assertEquals("未设置过的线程应回退到全局最新值", "zh", seen.get());
    }

    /** 单一线程内的连续设置仍按最新值生效（回归保护）。 */
    @Test
    public void singleThreadSequentialWrites() {
        NLSUtils.setLanguage("en");
        assertEquals("en", NLSUtils.getLanguage());
        NLSUtils.setLanguage("zh");
        assertEquals("zh", NLSUtils.getLanguage());
        NLSUtils.setLanguage("ja");
        assertEquals("ja", NLSUtils.getLanguage());
    }

    /** reset 同时清线程副本与全局值，避免污染后续用例。 */
    @Test
    public void resetClearsBothTracks() throws Exception {
        NLSUtils.setLanguage("zh");
        runOnOtherThread(NLSUtils::reset);   // 模拟 scenario 间在任意线程清理

        assertNull("reset 后主线程不应再读到值", NLSUtils.getLanguage());

        AtomicReference<String> seen = new AtomicReference<>("dirty");
        Thread reader = new Thread(() -> seen.set(NLSUtils.getLanguage()), "fresh-reader");
        reader.start();
        reader.join(5000);
        assertNull("reset 后其它线程也不应读到残留值", seen.get());
    }
}
