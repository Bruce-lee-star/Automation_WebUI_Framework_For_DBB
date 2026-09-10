package com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.bootstrap;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 下载保存异常分类护盾：{@link PlaywrightContextManager#isContextClosedError(Throwable)}。
 *
 * <p>关闭时序导致的 {@code TargetClosedError}（Playwright 在 {@code BrowserContext.close()} 时先清理未完成
 * 下载）必须被识别为「预期噪音」并降级为 DEBUG；而真实保存失败（磁盘满 / 路径非法等）<b>不得被误降级</b>，
 * 以保证告警信噪比。
 *
 * <p>同包白盒测试：本类与 {@link PlaywrightContextManager} 同属 {@code lifecycle.bootstrap} 包，
 * 以便直接访问其<b>包级私有</b>分类方法——刻意提升为 {@code public} 会扩大生产 API 面并需额外 ArchUnit 豁免。
 */
public class DownloadSaveErrorClassifierTest {

    private static final String CLOSED_MSG = "Target page, context or browser has been closed";

    @Test
    public void targetClosedMessageIsExpectedNoise() {
        assertTrue(PlaywrightContextManager.isContextClosedError(new RuntimeException(CLOSED_MSG)));
    }

    @Test
    public void targetClosedInCauseChainIsDetected() {
        RuntimeException cause = new RuntimeException(CLOSED_MSG);
        assertTrue(PlaywrightContextManager.isContextClosedError(
                new RuntimeException("saveAs failed", cause)));
    }

    @Test
    public void realSaveFailureIsNotDowngraded() {
        assertFalse(PlaywrightContextManager.isContextClosedError(
                new IllegalStateException("No space left on device")));
        assertFalse(PlaywrightContextManager.isContextClosedError(
                new RuntimeException("Access denied")));
    }

    @Test
    public void nullAndPlainExceptionAreSafe() {
        assertFalse(PlaywrightContextManager.isContextClosedError(null));
        assertFalse(PlaywrightContextManager.isContextClosedError(new RuntimeException()));
    }
}
