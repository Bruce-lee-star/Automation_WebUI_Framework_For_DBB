package com.hsbc.cmb.hk.dbb.automation.tests.api.steps;

import io.cucumber.java.After;
import io.cucumber.java.Before;

/**
 * API 场景钩子：在 scenario 前后摘除 {@link ApiTestContext} 中共享的 BaseStep。
 *
 * <p>  的配套措施：{@code ApiTestContext} 基于 ThreadLocal 实现
 * scenario 级共享。若不清理，当 Cucumber 复用线程执行下一个 scenario（或在并行
 * 执行下）时，会读到上一个场景遗留的 BaseStep，导致 entity 配置、请求参数与
 * 响应状态<b>跨场景串扰</b>——这类问题表现为随机失败，极难定位。
 *
 * <p>注：{@code @Before} 与 {@code @After} 不指定标签，对全部场景生效；
 * 对纯 Web 场景而言 {@code clear()} 是空操作，无副作用。
 */
public class ApiScenarioHooks {

    @Before
    public void beforeScenario() {
        ApiTestContext.clear();
    }

    @After
    public void afterScenario() {
        ApiTestContext.clear();
    }
}
