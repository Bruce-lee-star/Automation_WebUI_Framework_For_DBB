package com.hsbc.cmb.hk.dbb.automation.framework.route.core.capture;

import com.microsoft.playwright.Page;

import org.junit.Test;

import static org.junit.Assert.assertNotNull;
import com.hsbc.cmb.hk.dbb.automation.framework.route.core.capture.ApiCaptureStart;

/**
 * 固化 Phase 5 抽离出的 {@link ApiCaptureStart} 门面契约（与拆分前语义严格一致）。
 *
 * <p>{@code start()} 委托 {@code ApiCaptureContext.start(page)}，触达 Playwright 运行时，
 * 由集成护盾（ApiCaptureTypeQueryTest 等）覆盖；本类仅锁定<b>抽离后构造器签名/可见性不变</b>：
 * 构造器只持有 {@code Page} 字段、不触碰任何 Playwright 方法，因此即使传入 null 也不抛 NPE。
 * 与 {@link ApiCaptureStart} 同包，可访问其 package-private 构造器。<b>不依赖 Playwright</b>。
 */
public class ApiCaptureStartTest {

    @Test
    public void ctor_doesNotTouchPageField() {
        // 构造器仅存储 page，不调用 Playwright 方法 —— 抽离后行为不变
        ApiCaptureStart start = new ApiCaptureStart((Page) null);
        assertNotNull(start);
    }
}
