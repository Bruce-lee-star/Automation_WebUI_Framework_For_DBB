package com.hsbc.cmb.hk.dbb.automation.framework.core.context;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D-6 / PAR-4 验证：用例级测试数据隔离机制。
 * <ul>
 *   <li>{@link ScenarioDataNamespace#uniqueId(String)} 全局唯一（同域多次 / 跨用例域均不碰撞）；</li>
 *   <li>{@link ScenarioDataNamespace#registerCleanup(Runnable)} 钩子在 {@link ScenarioContext#end(String)} 机器强制触发，且按用例域隔离；</li>
 *   <li>钩子异常不中断其余钩子、不向外传播；</li>
 *   <li>{@code null} 钩子抛 {@link IllegalArgumentException}。</li>
 * </ul>
 */
public class ScenarioDataNamespaceTest {

    @AfterEach
    void cleanup() {
        // 套件级兜底，避免用例间残留绑定 / 清理钩子
        ScenarioContext.resetAll();
    }

    @Test
    void uniqueIdIsGloballyUniqueAcrossCalls() {
        String a = ScenarioDataNamespace.uniqueId("user");
        String b = ScenarioDataNamespace.uniqueId("user");
        assertNotEquals(a, b, "同域内多次调用应返回不同唯一标识");
        assertTrue(a.startsWith("user-"), "应带可读前缀，便于后端 / 日志溯源");
    }

    @Test
    void uniqueIdIsUniqueAcrossScenarios() {
        ScenarioContext.begin("s1");
        String s1 = ScenarioDataNamespace.uniqueId("order");
        ScenarioContext.end("s1");

        ScenarioContext.begin("s2");
        String s2 = ScenarioDataNamespace.uniqueId("order");
        ScenarioContext.end("s2");

        assertNotEquals(s1, s2, "不同用例域的 uniqueId 不应碰撞");
    }

    @Test
    void cleanupHookFiresOnScenarioEndAndIsIsolated() {
        ScenarioContext.begin("scenario-A");
        AtomicInteger fired = new AtomicInteger();
        ScenarioDataNamespace.registerCleanup(fired::incrementAndGet);

        // 另一用例的钩子不应被 A 的 end 触发
        ScenarioContext.begin("scenario-B");
        AtomicInteger other = new AtomicInteger();
        ScenarioDataNamespace.registerCleanup(other::incrementAndGet);
        ScenarioContext.end("scenario-B"); // 触发 B
        assertEquals(0, fired.get(), "A 的钩子在 A 未结束前不应触发");
        assertEquals(1, other.get(), "B 的钩子应在 B 结束时触发");

        ScenarioContext.end("scenario-A"); // 触发 A
        assertEquals(1, fired.get(), "A 的钩子应在 A 结束时触发");
    }

    @Test
    void cleanupHookExceptionDoesNotStopOthersNorPropagate() {
        ScenarioContext.begin("scenario-C");
        AtomicInteger good = new AtomicInteger();
        ScenarioDataNamespace.registerCleanup(() -> {
            throw new RuntimeException("boom");
        });
        ScenarioDataNamespace.registerCleanup(good::incrementAndGet);
        // end 不应抛异常（异常被框架吞并记录日志，D7-3）
        ScenarioContext.end("scenario-C");
        assertEquals(1, good.get(), "前一个钩子异常不应阻止后续钩子执行");
    }

    @Test
    void registerCleanupRejectsNull() {
        assertThrows(IllegalArgumentException.class,
                () -> ScenarioDataNamespace.registerCleanup(null));
    }
}
