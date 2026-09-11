package com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.concurrent;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WEB-P1-5 种子测试：单任务执行结果（结构化、无浏览器）。
 * 固化成功/失败/取消三态语义与 {@code valueOrThrow()} 的失败回放契约（异常类型 + cause 链 + 诊断信息）。
 */
public class ContextTaskResultTest {

    private static final Throwable CAUSE = new IllegalStateException("worker exploded");

    @Test
    public void success_exposesValueAndMetadata() {
        ContextTaskResult<String> r = ContextTaskResult.success("t1", "value", "worker-1", 120L, List.of());

        assertTrue(r.isSuccess());
        assertEquals("value", r.valueOrThrow());
        assertNull( r.getFailure(), "成功时不应有失败原因");
        assertEquals("t1", r.getTaskName());
        assertEquals("worker-1", r.getThreadName());
        assertEquals(120L, r.getDurationMillis());
    }

    @Test
    public void failure_keepsCauseAndThrowsOnValueAccess() {
        ContextTaskResult<String> r = ContextTaskResult.failure("t2", CAUSE, "worker-2", 50L, List.of());

        assertFalse(r.isSuccess());
        assertSame(CAUSE, r.getFailure());

        CompletionException ex = assertThrows(CompletionException.class, r::valueOrThrow);
        assertSame( CAUSE,  ex.getCause(), "cause 链必须保留，便于编排线程定位根因");
        assertTrue( ex.getMessage().contains("t2"), "异常消息须含任务名");
        assertTrue( ex.getMessage().contains("worker-2"), "异常消息须含线程名");
    }

    @Test
    public void failure_includesPageErrorsInDiagnosticMessage() {
        ContextTaskResult<String> r = ContextTaskResult.failure("t3", CAUSE, "worker-3", 10L,
                List.of("Uncaught TypeError: x is not a function"));

        CompletionException ex = assertThrows(CompletionException.class, r::valueOrThrow);
        assertTrue( ex.getMessage().contains("Uncaught TypeError"), "页面错误须进入诊断消息");
        assertEquals(1, r.getPageErrors().size());
    }

    @Test
    public void cancelled_isNotSuccessAndCarriesCancellationCause() {
        ContextTaskResult<String> r = ContextTaskResult.cancelled("t4", "worker-4");

        assertFalse(r.isSuccess());
        assertTrue(r.getFailure() instanceof IllegalStateException);
        assertTrue(r.getFailure().getMessage().contains("cancelled"));
        assertEquals(0L, r.getDurationMillis());
        assertTrue(r.getPageErrors().isEmpty());
        assertThrows(CompletionException.class, r::valueOrThrow);
    }

    @Test
    public void toString_includesKeyDiagnostics() {
        String s = ContextTaskResult.success("t5", "v", "worker-5", 7L, List.of("e1")).toString();

        assertTrue(s.contains("t5"));
        assertTrue(s.contains("worker-5"));
        assertTrue(s.contains("pageErrors=1"));
    }
}
