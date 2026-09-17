package com.hsbc.cmb.hk.dbb.automation.framework.common.cleanstate;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * B-6：{@link CleanStateRegistry} 契约——注册/复位/残留判定/注销，以及断言式清理的数据来源。
 */
class CleanStateRegistryTest {

    @AfterEach
    void cleanup() {
        // 注册表是静态单例，逐用例清空避免跨用例污染
        CleanStateRegistry.clearResolvers();
    }

    private static StateResolver resolver(String name, AtomicInteger resetCounter, BooleanSupplier dirty) {
        return new StateResolver() {
            @Override public String name() { return name; }
            @Override public void reset() { resetCounter.incrementAndGet(); }
            @Override public boolean isDirty() { return dirty.getAsBoolean(); }
        };
    }

    @Test
    void registerAndResetAll_invokesEveryResolver() {
        AtomicInteger a = new AtomicInteger();
        AtomicInteger b = new AtomicInteger();
        CleanStateRegistry.register(resolver("a", a, () -> false));
        CleanStateRegistry.register(resolver("b", b, () -> false));

        CleanStateRegistry.resetAll();

        assertEquals(1, a.get());
        assertEquals(1, b.get());
        assertEquals(2, CleanStateRegistry.registeredNames().size());
    }

    @Test
    void dirtyNames_reportsOnlyDirtyInStableOrder() {
        CleanStateRegistry.register(resolver("b-dirty", new AtomicInteger(), () -> true));
        CleanStateRegistry.register(resolver("a-dirty", new AtomicInteger(), () -> true));
        CleanStateRegistry.register(resolver("c-clean", new AtomicInteger(), () -> false));

        List<String> dirty = CleanStateRegistry.dirtyNames();

        assertEquals(List.of("a-dirty", "b-dirty"), dirty, "仅脏状态、按名稳定排序");
    }

    @Test
    void resetByName_resetsOnlyTarget() {
        AtomicInteger a = new AtomicInteger();
        AtomicInteger b = new AtomicInteger();
        CleanStateRegistry.register(resolver("a", a, () -> false));
        CleanStateRegistry.register(resolver("b", b, () -> false));

        CleanStateRegistry.reset("a");

        assertEquals(1, a.get());
        assertEquals(0, b.get());
    }

    @Test
    void unregister_removesResolver() {
        AtomicInteger a = new AtomicInteger();
        CleanStateRegistry.register(resolver("a", a, () -> false));

        CleanStateRegistry.unregister("a");
        CleanStateRegistry.resetAll();

        assertEquals(0, a.get());
        assertTrue(CleanStateRegistry.registeredNames().isEmpty());
    }

    @Test
    void emptyRegistry_isNoOp() {
        CleanStateRegistry.resetAll();

        assertTrue(CleanStateRegistry.dirtyNames().isEmpty());
        assertTrue(CleanStateRegistry.registeredNames().isEmpty());
    }

    @Test
    void isDirty_defaultsToFalse() {
        StateResolver plain = new StateResolver() {
            @Override public String name() { return "plain"; }
            @Override public void reset() { }
        };
        CleanStateRegistry.register(plain);

        assertTrue(CleanStateRegistry.dirtyNames().isEmpty(), "默认 isDirty=false 不参与断言");
    }
}
