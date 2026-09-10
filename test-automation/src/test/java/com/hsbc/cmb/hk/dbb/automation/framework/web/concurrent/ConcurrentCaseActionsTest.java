package com.hsbc.cmb.hk.dbb.automation.framework.web.concurrent;

import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.fail;

/**
 * {@link ConcurrentCaseActions} 注册中心护盾：显式注册覆盖、未提供时抛语义化异常。
 * （SPI 自动发现路径由真实并发运行器端到端覆盖，此处不新增测试 SPI 文件以免污染业务 SPI。）
 */
public class ConcurrentCaseActionsTest {

    @After
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
        try {
            ConcurrentCaseActions.register(null);
            fail("register(null) must throw");
        } catch (NullPointerException expected) {
            // 预期
        }
    }
}
