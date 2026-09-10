package com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle;

import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.context.CustomOptionsManager;
import com.hsbc.cmb.hk.dbb.automation.framework.core.context.TestContextHolder;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * T3-3 修复验证：{@link PlaywrightManager#closeContext()} 必须在【context 为 null】时
 * 仍清理 per-thread 状态（自定义配置 / TestServices / BasePage），否则线程池复用场景下
 * 上一个 scenario 的状态会残留并污染下一个 scenario（继承旧 entity/env/自定义配置）。
 */
public class PlaywrightManagerCloseContextCleanupTest {

    @Test
    public void closeContextClearsPerThreadStateWhenContextIsNull() {
        CustomOptionsManager mgr = CustomOptionsManager.getInstance();
        try {
            // 模拟 scenario 写入自定义配置
            mgr.setLocale("zh-CN").setIsMobile(true);
            assertEquals("zh-CN", mgr.getLocale());

            // context 为 null（未创建 / feature 模式复用路径）时调用 closeContext
            PlaywrightManager.closeContext();

            // per-thread 状态必须被无条件清理，不能残留到下一 scenario
            assertNull("closeContext 应无条件清理自定义 locale", mgr.getLocale());
            assertNull("closeContext 应无条件清理自定义 isMobile", mgr.getIsMobile());
            assertNull("closeContext 应无条件清理 customOptionsFlag", mgr.isCustomContextOptionsFlag());
        } finally {
            mgr.removeAllThreadLocals();
            TestContextHolder.get().clear();
        }
    }
}
