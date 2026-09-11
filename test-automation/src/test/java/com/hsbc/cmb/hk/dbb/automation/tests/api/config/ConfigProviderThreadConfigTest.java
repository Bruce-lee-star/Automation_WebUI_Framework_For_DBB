package com.hsbc.cmb.hk.dbb.automation.tests.api.config;

import com.hsbc.cmb.hk.dbb.automation.framework.api.config.ConfigProvider;
import com.hsbc.cmb.hk.dbb.automation.framework.core.context.ContextKey;
import com.hsbc.cmb.hk.dbb.automation.framework.core.context.TestContextHolder;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link ConfigProvider} 配置快照收拢验证（T3-1）：原 {@code static ThreadLocal<Config>} 已收拢为
 * {@code TestContext}/{@code ContextKey<Config>}。验证并行 scenario 线程各自的配置快照互不覆盖。
 *
 * <p>通过反射读取私有静态 {@code THREAD_CONFIG_KEY}（类型安全键）以在两线程注入不同配置，断言
 * {@link ConfigProvider#getConfig()} 返回各自线程的快照——证明 per-scenario 隔离已生效。</p>
 */
public class ConfigProviderThreadConfigTest {

    @SuppressWarnings("unchecked")
    private static ContextKey<Config> threadConfigKey() throws Exception {
        Field f = ConfigProvider.class.getDeclaredField("THREAD_CONFIG_KEY");
        f.setAccessible(true);
        return (ContextKey<Config>) f.get(null);
    }

    @Test
    public void threadConfigIsIsolatedAcrossScenarios() throws Exception {
        final ContextKey<Config> key = threadConfigKey();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Callable<String> taskA = () -> {
                try {
                    TestContextHolder.get().set(key, ConfigFactory.parseString("a=1"));
                    return ConfigProvider.getConfig().getString("a");
                } finally {
                    TestContextHolder.resetForCurrentThread();
                }
            };
            Callable<String> taskB = () -> {
                try {
                    TestContextHolder.get().set(key, ConfigFactory.parseString("b=2"));
                    return ConfigProvider.getConfig().getString("b");
                } finally {
                    TestContextHolder.resetForCurrentThread();
                }
            };
            Future<String> fa = pool.submit(taskA);
            Future<String> fb = pool.submit(taskB);
            assertEquals("1", fa.get());
            assertEquals("2", fb.get());
        } finally {
            pool.shutdown();
        }
    }
}
