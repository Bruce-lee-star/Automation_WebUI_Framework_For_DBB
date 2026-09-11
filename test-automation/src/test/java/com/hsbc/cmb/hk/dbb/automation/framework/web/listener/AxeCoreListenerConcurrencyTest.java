package com.hsbc.cmb.hk.dbb.automation.framework.web.listener;

import com.hsbc.cmb.hk.dbb.automation.framework.core.context.TestContextHolder;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * T3-1 收拢验证：{@link AxeCoreListener} 的 axeEnabled / reportGenerated
 * （原静态 {@code ThreadLocal<Boolean> withInitial(() -> false)} 已迁入
 * {@link com.hsbc.cmb.hk.dbb.automation.framework.core.context.TestContextHolder}；
 * 因 TestContext 未设值时返回 null，默认 false 语义改由布尔 helper
 * {@code Boolean.TRUE.equals(...)} 等价保证）。
 * <p>
 * 本测试守护本次重构的关键风险：未设值时读取不得因 Boolean null 自动拆箱抛 NPE，
 * 且默认 false 按线程独立成立。
 * <p>
 * 注：axeEnabled 无公开 setter（由构造/初始化阶段按 FrameworkConfig 决定），
 * 故仅验证默认态，不构造 true 场景。
 */
public class AxeCoreListenerConcurrencyTest {

    @Test
    public void disabledByDefaultAndNoNpe() throws Exception {
        // 测试隔离：先清空当前线程上下文，确保读到的是「未设值」的默认态（避免跨用例污染）。
        TestContextHolder.resetForCurrentThread();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            assertFalse( AxeCoreListener.isEnabled(), "默认应为未启用，且不得因 null 拆箱抛 NPE");
            Future<Boolean> other = pool.submit(AxeCoreListener::isEnabled);
            assertFalse( other.get(5, TimeUnit.SECONDS), "其他线程默认亦为未启用");
        } finally {
            pool.shutdown();
        }
    }
}
