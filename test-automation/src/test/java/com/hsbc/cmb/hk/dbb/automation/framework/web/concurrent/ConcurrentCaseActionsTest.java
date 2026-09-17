package com.hsbc.cmb.hk.dbb.automation.framework.web.concurrent;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link ConcurrentCaseActions} 注册中心护盾：显式注册覆盖、未提供时抛语义化异常。
 * （SPI 自动发现路径由真实并发运行器端到端覆盖，此处不新增测试 SPI 文件以免污染业务 SPI。）
 */
public class ConcurrentCaseActionsTest {

    @AfterEach
    public void tearDown() {
        ConcurrentCaseActions.reset();
    }

    @Test
    public void spiDiscoveryReturnsActionWhenNotExplicitlyRegistered() {
        // 本模块 META-INF/services 已声明 ConcurrentLogonAction，require() 应经 SPI 自动发现并返回非空实例。
        assertNotNull(ConcurrentCaseActions.require());
    }

    @Test
    public void registerThenRequireReturnsSameInstance() {
        ConcurrentCaseAction stub = caseData -> { };
        ConcurrentCaseActions.register(stub);
        assertSame(stub, ConcurrentCaseActions.require());
    }

    @Test
    public void explicitRegisterOverridesPrevious() {
        ConcurrentCaseAction first = caseData -> { };
        ConcurrentCaseAction second = caseData -> { };
        ConcurrentCaseActions.register(first);
        ConcurrentCaseActions.register(second);
        assertSame(second, ConcurrentCaseActions.require());
        assertFalse(first.equals(ConcurrentCaseActions.require()));
    }

    @Test
    public void registerRejectsNull() {
        // 用 assertThrows 而非「try + catch NPE」：后者被判为 DCN_NULLPOINTER_EXCEPTION（异常控制流），
        // 且一旦 register 未抛异常，fail(...) 抛出的 AssertionError 也不会被误当成"预期"。
        assertThrows(NullPointerException.class, () -> ConcurrentCaseActions.register(null),
                "register(null) must throw");
    }
}
