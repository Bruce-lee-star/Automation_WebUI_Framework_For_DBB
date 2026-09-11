package com.hsbc.cmb.hk.dbb.automation.framework.web.codegen.spi;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * WEB-P1-5 种子测试：codegen SPI 桥接注册表（无浏览器）。
 * 覆盖惰性缓存稳定性、解析结果非空、{@code reset()} 后可重新解析等契约。
 */
public class RoleCodegenBridgeRegistryTest {

    @Test
    public void getBridge_neverThrowsAndReturnsNonNull() {
        Optional<RoleCodegenBridge> bridge = RoleCodegenBridgeRegistry.getBridge();

        assertNotNull( bridge, "getBridge 必须返回非 null Optional（无实现时为 empty，不得抛异常）");
    }

    @Test
    public void getBridge_returnsCachedOptionalOnSubsequentCalls() {
        Optional<RoleCodegenBridge> first = RoleCodegenBridgeRegistry.getBridge();
        Optional<RoleCodegenBridge> second = RoleCodegenBridgeRegistry.getBridge();

        assertSame( first,  second, "解析结果应按 JVM 进程惰性缓存，重复调用返回同一 Optional 实例");
    }

    /**
     * 断言 reset 后重新解析结果稳定（幂等）。
     * 注意：不可断言「前后非同一实例」——无实现时 {@link Optional#empty()} 为 JVM 单例，
     * reset 前后必然同一实例，按实例身份判定会误报。
     */
    @Test
    public void reset_reResolvesToSameResult() {
        Optional<RoleCodegenBridge> before = RoleCodegenBridgeRegistry.getBridge();

        RoleCodegenBridgeRegistry.reset();
        Optional<RoleCodegenBridge> after = RoleCodegenBridgeRegistry.getBridge();

        assertNotNull(after);
        assertEquals( before,  after, "reset 只清空惰性缓存，重新解析结果应与之前一致");
    }
}
