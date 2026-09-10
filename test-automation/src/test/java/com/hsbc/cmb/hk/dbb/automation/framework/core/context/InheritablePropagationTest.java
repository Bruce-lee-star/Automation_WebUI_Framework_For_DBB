package com.hsbc.cmb.hk.dbb.automation.framework.core.context;

import org.junit.After;
import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * CORE-P0-2 验证：按需 {@link InheritableThreadLocal} 传播开关（默认关）。
 * 仅当 INHERIT_MODE 开启且父线程 publishInheritance 后，派生线程自动继承父上下文浅拷贝；
 * 子线程写入不回灌父线程（隔离保留）。
 */
public class InheritablePropagationTest {

    @After
    public void tearDown() {
        TestContextHolder.setInheritMode(false);
        TestContextHolder.resetForCurrentThread();
    }

    @Test
    public void childThreadInheritsParentWhenModeOn() throws Exception {
        TestContextHolder.setInheritMode(true);
        ContextKey<String> k = ContextKey.of("inherit", String.class);
        TestContextHolder.get().set(k, "parent");
        TestContextHolder.publishInheritance();

        CountDownLatch done = new CountDownLatch(1);
        String[] seen = {null};
        Thread child = new Thread(() -> {
            seen[0] = TestContextHolder.get().get(k);
            done.countDown();
        });
        child.start();
        done.await(5, TimeUnit.SECONDS);
        assertEquals("派生线程应继承父上下文", "parent", seen[0]);
    }

    @Test
    public void childThreadDoesNotInheritWhenModeOff() throws Exception {
        TestContextHolder.setInheritMode(false);
        ContextKey<String> k = ContextKey.of("inherit2", String.class);
        TestContextHolder.get().set(k, "parent");
        TestContextHolder.publishInheritance(); // 模式关，不发布

        CountDownLatch done = new CountDownLatch(1);
        String[] seen = {null};
        Thread child = new Thread(() -> {
            seen[0] = TestContextHolder.get().get(k);
            done.countDown();
        });
        child.start();
        done.await(5, TimeUnit.SECONDS);
        assertNull("模式关时派生线程不应继承父上下文", seen[0]);
    }

    @Test
    public void inheritedChildWriteDoesNotLeakToParent() throws Exception {
        TestContextHolder.setInheritMode(true);
        ContextKey<String> k = ContextKey.of("inherit3", String.class);
        TestContextHolder.get().set(k, "parent");
        TestContextHolder.publishInheritance();

        CountDownLatch done = new CountDownLatch(1);
        Thread child = new Thread(() -> {
            TestContextHolder.get().set(k, "child");
            done.countDown();
        });
        child.start();
        done.await(5, TimeUnit.SECONDS);
        assertEquals("子线程写入不应回灌父线程（隔离保留）", "parent", TestContextHolder.get().get(k));
    }
}
