package com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle;

import net.thucydides.core.steps.BaseStepListener;
import net.thucydides.core.steps.StepEventBus;
import net.thucydides.model.domain.TestOutcome;
import org.junit.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeoutException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Serenity 桥接单测（设计文档 9.10-⑤）：断言编排线程把并发任务失败经 {@link StepEventBus#testFailed} 正确标记，
 * 且失败回放具备异常安全性（监听器异常不污染原始失败语义）。
 */
public class SerenityBusBridgeTest {

    /** 捕获 testFailed 事件的监听器（复用 Serenity 基类，仅覆盖测试方法）。 */
    private static final class CapturingListener extends BaseStepListener {
        final List<Throwable> failures = new ArrayList<>();

        CapturingListener() {
            super(new File("target/serenity-bridge-test"));
        }

        @Override
        public void testFailed(TestOutcome result, Throwable throwable) {
            Throwable t = throwable;
            if (t == null) {
                t = new RuntimeException(result != null ? result.getTitle() : "testFailed");
            }
            //  解包到根因（replayFailures 经 valueOrThrow 透传 CompletionException，根因为真实失败）
            while (t.getCause() != null && t.getCause() != t) {
                t = t.getCause();
            }
            failures.add(t);
        }
    }

    private static CapturingListener bind() {
        CapturingListener listener = new CapturingListener();
        StepEventBus.getEventBus().registerListener(listener);
        return listener;
    }

    private static void unbind(CapturingListener listener) {
        try {
            StepEventBus.getEventBus().dropListener(listener);
        } catch (Throwable ignored) {
            //  离线环境 dropListener 不存在或失败均不致命
        }
    }

    @Test
    public void orchestrationThreadMarksEachFailureViaTestFailed() {
        CapturingListener listener = bind();
        try {
            List<ContextTaskResult<?>> results = new ArrayList<>();
            results.add(ContextTaskResult.success("ok", 1, "t1", 1L, List.of(), Map.of()));
            results.add(ContextTaskResult.failure("bad1", new RuntimeException("boom1"), "t2", 2L, List.of(), Map.of()));
            results.add(ContextTaskResult.failure("bad2", new TimeoutException("slow"), "t3", 3L, List.of(), Map.of()));

            try {
                SerenityBusBridge.replayFailures(results);
                fail("expected CompletionException");
            } catch (CompletionException ce) {
                //  汇总异常应被抛出（既有断言语义保留）
                assertTrue(ce.getMessage().contains("2 task(s) failed"));
            }
            //  每个失败项都经 StepEventBus.testFailed 标记到 Serenity
            assertEquals(2, listener.failures.size());
            boolean sawBoom = listener.failures.stream()
                    .anyMatch(t -> t.getMessage() != null && t.getMessage().contains("boom1"));
            boolean sawSlow = listener.failures.stream()
                    .anyMatch(t -> t.getMessage() != null && t.getMessage().contains("slow"));
            assertTrue("boom1 marked", sawBoom);
            assertTrue("slow marked", sawSlow);
        } finally {
            unbind(listener);
        }
    }

    @Test
    public void allSuccessMarksNothingAndDoesNotThrow() {
        CapturingListener listener = bind();
        try {
            List<ContextTaskResult<?>> results = new ArrayList<>();
            results.add(ContextTaskResult.success("ok1", 1, "t1", 1L, List.of(), Map.of()));
            results.add(ContextTaskResult.success("ok2", 2, "t2", 1L, List.of(), Map.of()));

            SerenityBusBridge.replayFailures(results); // 不应抛
            assertTrue("no Serenity failures marked", listener.failures.isEmpty());
        } finally {
            unbind(listener);
        }
    }

    @Test
    public void listenerExceptionDoesNotEscapeReplay() {
        //  模拟 Serenity 监听器内部异常：回放仍须抛出汇总 CompletionException，不向上泄露监听器异常
        BaseStepListener explosive = new BaseStepListener(new File("target/serenity-bridge-test")) {
            @Override
            public void testFailed(TestOutcome result, Throwable throwable) {
                throw new RuntimeException("listener boom");
            }
        };
        StepEventBus.getEventBus().registerListener(explosive);
        try {
            List<ContextTaskResult<?>> results = new ArrayList<>();
            results.add(ContextTaskResult.failure("bad", new RuntimeException("x"), "t1", 1L, List.of(), Map.of()));
            try {
                SerenityBusBridge.replayFailures(results);
                fail("expected CompletionException");
            } catch (CompletionException ce) {
                assertFalse(ce.getMessage().contains("listener boom"));
            }
        } finally {
            try {
                StepEventBus.getEventBus().dropListener(explosive);
            } catch (Throwable ignored) {
                //  离线环境忽略
            }
        }
    }
}
