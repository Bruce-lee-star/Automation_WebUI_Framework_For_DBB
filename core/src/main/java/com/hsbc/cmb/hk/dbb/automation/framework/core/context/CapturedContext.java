package com.hsbc.cmb.hk.dbb.automation.framework.core.context;

import java.util.Map;

/**
 * 测试上下文的不可变快照载体（N-10 跨线程传播桥）。
 *
 * <p>由 {@link TestContextHolder#capture()} 创建，包含提交线程上下文的浅拷贝；经
 * {@link TestContextHolder#restore(CapturedContext)} 在工作线程恢复。快照为值拷贝，
 * 工作线程对上下文的写入不会影响原始提交线程（隔离保留），杜绝线程池复用导致的跨 scenario 串扰。
 */
public final class CapturedContext {

    private final Map<ContextKey<?>, Object> snapshot;

    CapturedContext(Map<ContextKey<?>, Object> snapshot) {
        this.snapshot = snapshot;
    }

    Map<ContextKey<?>, Object> snapshot() {
        return snapshot;
    }
}
