package com.hsbc.cmb.hk.dbb.automation.framework.web.listener;

import com.hsbc.cmb.hk.dbb.automation.framework.web.listener.resiliencypkg.BadLoadListener;
import com.hsbc.cmb.hk.dbb.automation.framework.web.listener.resiliencypkg.GoodSpiListener;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WEB-P1-3 表征测试：监听器注册改 SPI（{@link ServiceLoader} 主发现 + {@code Class.forName} 容错回退）。
 *
 * <p>固化两项契约（无行为变更，仅根治 W-16「单点失败全盘失败」）：
 * ① 扫描包内存在静态初始化必败的坏类时，{@link ListenerRegistry#initialize(String)} 不得中止；
 * ② SPI 主发现路径成功登记正常 {@link FrameworkListener} 实现，坏类被逐类跳过（含 WARN 告警）。
 *
 * <p>手法：测试资源 {@code META-INF/services/...FrameworkListener} 声明 {@link GoodSpiListener}（正常）
 * 与 {@link BadLoadListener}（实例化抛错）；同包 {@code Broken}（静态初始化抛错，源码化夹具）经包扫描
 * 覆盖 {@code Class.forName} 加载失败分支；SPI 与两条扫描路径（实例化失败 / 加载失败）均被覆盖。
 */
public class ListenerRegistryResilienceTest {

    @AfterEach
    public void tearDown() {
        ListenerRegistry.cleanup();
    }

    @Test
    public void spiDiscoveryRegistersGoodListener_andSkipsBadClass_withoutAborting() {
        ListenerRegistry.initialize("com.hsbc.cmb.hk.dbb.automation.framework.web.listener.resiliencypkg");

        // ① 单类失败绝不中止整个注册表初始化（根治 W-16）
        assertTrue( ListenerRegistry.isInitialized(), "initialize 不应因坏类而中止");

        List<Object> listeners = ListenerRegistry.getRegisteredListeners();

        // ② SPI 主发现路径成功登记正常监听器
        boolean hasGood = listeners.stream().anyMatch(l -> l instanceof GoodSpiListener);
        assertTrue( hasGood, "SPI 应发现并登记 GoodSpiListener");

        // ③ 坏类（静态初始化失败）应被逐类跳过，不得登记
        boolean hasBad = listeners.stream().anyMatch(l -> l instanceof BadLoadListener);
        assertFalse( hasBad, "坏类应被跳过，不得登记");
    }

    @Test
    public void cleanupResetsRegistryState() {
        ListenerRegistry.initialize("com.hsbc.cmb.hk.dbb.automation.framework.web.listener.resiliencypkg");
        assertTrue(ListenerRegistry.isInitialized());
        ListenerRegistry.cleanup();
        assertFalse(ListenerRegistry.isInitialized());
        assertTrue( ListenerRegistry.getRegisteredListeners().isEmpty(), "cleanup 后监听器列表应清空");
    }
}
