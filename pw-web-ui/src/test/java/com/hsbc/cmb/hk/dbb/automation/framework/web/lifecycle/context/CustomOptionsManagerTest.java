package com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.context;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link CustomOptionsManager} 登录态/storageState 维持的契约测试（W-4 构建块）。
 * <p>验证：{@code setStorageStateWithoutRebuild} 仅写内存、不置 customOptions flag；
 * 崩溃重建路径（{@code BrowserRestartImpl.restoreStorageStateFlag}）经 {@link #enableCustomOptions()} 置位 flag 后，
 * 后续 {@code getContext()} 即可经既有逻辑把已保存的 storageState 重应用到新建 Context，恢复登录态。</p>
 * <p>注意 {@link #isCustomContextOptionsFlag()} 在未置位时返回 {@code null}（装箱 Boolean），断言需空安全。</p>
 */
class CustomOptionsManagerTest {

    private final CustomOptionsManager manager = CustomOptionsManager.getInstance();

    /**
     * 用例隔离（本轮修复既有失败）：flag 与 storageState 都活在 {@code TestContextHolder}
     * （per-thread TestContext）中，而 {@link CustomOptionsManager} 是进程级单例，状态跨用例存活。
     *
     * <p>原实现只在 {@code @AfterEach} 里清 storageState，<b>不清 flag</b>：只要有一个用例
     * （如 {@link #enableCustomOptions_sets_flag_independent_of_storageState}）留下
     * {@code flag=true}，后续用例的 {@code assertNull(isCustomContextOptionsFlag())} 就必然假失败
     * （实测 2 条失败，且换 JUnit 方法序就会换一组）。</p>
     *
     * <p>统一改用框架提供的完整复位入口
     * {@link CustomOptionsManager#removeAllThreadLocals()}（flag + storageState + 其余 12 个自定义
     * 选项键），前后各清一次，使用例既不污染别人也不受别人污染。</p>
     */
    @BeforeEach
    void resetManagerStateBeforeCase() {
        CustomOptionsManager.removeAllThreadLocals();
    }

    @AfterEach
    void resetManagerStateAfterCase() {
        CustomOptionsManager.removeAllThreadLocals();
    }

    @Test
    void withoutRebuild_does_not_set_flag_but_keeps_storageState() {
        manager.setStorageStateWithoutRebuild("{\"cookies\":[]}");
        assertEquals("{\"cookies\":[]}", manager.getStorageState());
        assertNull(manager.isCustomContextOptionsFlag(),
                "setStorageStateWithoutRebuild 故意不置 flag（避免误触 Context 重建），未置位时返回 null");
    }

    @Test
    void enableCustomOptions_makes_storageState_reapplied_on_next_context() {
        manager.setStorageStateWithoutRebuild("{\"cookies\":[{\"name\":\"sid\"}]}");
        assertNull(manager.isCustomContextOptionsFlag());

        // 模拟崩溃重建后恢复登录态：置位 flag
        manager.enableCustomOptions();

        assertTrue(Boolean.TRUE.equals(manager.isCustomContextOptionsFlag()),
                "重建后 enableCustomOptions 应置位 flag，使 getContext 重应用 storageState");
        assertEquals("{\"cookies\":[{\"name\":\"sid\"}]}", manager.getStorageState(),
                "storageState 数据应跨崩溃重建持续存活（per-thread TestContext）");
    }

    @Test
    void enableCustomOptions_sets_flag_independent_of_storageState() {
        // 无 storageState 时重建路径仍应置位 flag（行为零变更：flag 仅作「应用 storageState」前置条件）
        manager.setStorageStateWithoutRebuild(null);
        manager.enableCustomOptions();
        assertSame(Boolean.TRUE, manager.isCustomContextOptionsFlag());
    }

    @Test
    void flag_false_after_reset() {
        manager.enableCustomOptions();
        assertTrue(Boolean.TRUE.equals(manager.isCustomContextOptionsFlag()));
        // disableCustomOptions 应取消 flag（供对照，确保 enable/disable 对称）
        manager.disableCustomOptions();
        assertFalse(Boolean.TRUE.equals(manager.isCustomContextOptionsFlag()));
    }
}
