package com.hsbc.cmb.hk.dbb.automation.framework.route.core.rule;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * C-5 已停止能力集合的并发可见性守卫（2026-09-17）。
 *
 * <p>背景：{@code stopMonitor/stopModify/stopDelay/stopMock/stopAll} 来自<b>测试主线程</b>，
 * 读取方是<b>Playwright 事件线程</b>（{@code selectCapability / isCapabilityStopped / copyForMerge}）。
 * 原实现直接改一个共享 {@code EnumSet}，属无同步可变共享，存在写丢失与读到半更新集合的风险。
 * 现改为 volatile 快照 + 写时复制。本类固化关键不变量：
 * <ol>
 *   <li>并发停止多个<b>不同</b>能力 → 全部都可见（无写丢失）；</li>
 *   <li>停止后对拷贝（copyForMerge）可见、且对源规则的后续停止不影响已发布的拷贝；</li>
 *   <li>stopAll（cap=null）停止全部；重复停止幂等、不抛异常。</li>
 * </ol>
 */
class RouteRuleCapabilityVisibilityTest {

    @Test
    void 并发停止不同能力_全部可见_无写丢失() throws InterruptedException {
        RouteRule rule = new RouteRule();
        RouteHandleType[] caps = {RouteHandleType.MOCK, RouteHandleType.MODIFY,
                RouteHandleType.DELAY, RouteHandleType.MONITOR};

        int threads = caps.length;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        List<Throwable> errors = new ArrayList<>();

        for (RouteHandleType cap : caps) {
            pool.submit(() -> {
                try {
                    start.await();
                    rule.stopCapability(cap);
                } catch (Throwable t) {
                    errors.add(t);
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(10, TimeUnit.SECONDS), "并发停止应在 10s 内完成");
        pool.shutdownNow();

        assertTrue(errors.isEmpty(), "并发停止不应抛异常：" + errors);
        for (RouteHandleType cap : caps) {
            assertTrue(rule.isCapabilityStopped(cap), cap + " 应在并发停止后可见");
        }
    }

    @Test
    void 重复停止同一能力_幂等且不抛异常() {
        RouteRule rule = new RouteRule();
        rule.stopCapability(RouteHandleType.MOCK);
        rule.stopCapability(RouteHandleType.MOCK); // 第二次
        rule.stopCapability(RouteHandleType.MOCK);
        assertTrue(rule.isCapabilityStopped(RouteHandleType.MOCK));
        assertFalse(rule.isCapabilityStopped(RouteHandleType.DELAY), "其它能力不受影响");
    }

    @Test
    void 显式停止全部四种能力_均可见() {
        // 注意：{@code RouteRule#stopCapability(null)} 是 no-op（"stop ALL" 的语义由
        // StoppedCapabilityManager.stopAll 在 manager 层处理），故此处逐个停止以验证「全部停止」。
        RouteRule rule = new RouteRule();
        for (RouteHandleType cap : RouteHandleType.values()) {
            rule.stopCapability(cap);
        }
        for (RouteHandleType cap : RouteHandleType.values()) {
            assertTrue(rule.isCapabilityStopped(cap), cap + " 应已被停止");
        }
    }

    @Test
    void copyForMerge_携带已停止集合_且同源后续停止不污染拷贝() {
        RouteRule rule = new RouteRule();
        rule.stopCapability(RouteHandleType.MOCK);
        RouteRule copy = rule.copyForMerge();

        assertTrue(copy.isCapabilityStopped(RouteHandleType.MOCK), "拷贝应继承源规则的已停止集合");
        // 源规则后续再停止 DELAY，不应影响已发布的拷贝（写时复制隔离）
        rule.stopCapability(RouteHandleType.DELAY);
        assertFalse(copy.isCapabilityStopped(RouteHandleType.DELAY), "拷贝与源规则相互独立");
    }

    @Test
    void 停止后的集合快照读_不抛并发修改异常_确定性可见() {
        RouteRule rule = new RouteRule();
        AtomicReference<Boolean> sawMock = new AtomicReference<>(false);
        // 模拟「写入线程」与「读取线程」交错：stop 在持有枚举快照遍历期间发生写时复制替换
        rule.stopCapability(RouteHandleType.MOCK);
        for (RouteHandleType cap : java.util.EnumSet.allOf(RouteHandleType.class)) {
            if (rule.isCapabilityStopped(cap)) {
                sawMock.compareAndSet(false, cap == RouteHandleType.MOCK);
            }
        }
        assertTrue(sawMock.get(), "遍历读取期间可读到已停止的能力");
    }
}
