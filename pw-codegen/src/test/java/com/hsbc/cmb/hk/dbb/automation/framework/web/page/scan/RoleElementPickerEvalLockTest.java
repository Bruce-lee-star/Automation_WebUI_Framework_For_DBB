package com.hsbc.cmb.hk.dbb.automation.framework.web.page.scan;

import com.microsoft.playwright.Page;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * CT2-09 契约（doc 21 部分修复补全）：evaluate 串行锁必须<b>可清理、可观测，且不破坏串行化语义</b>。
 *
 * <p><b>背景</b>：{@code EVAL_LOCKS} 原为 {@code ConcurrentHashMap}（Page/Frame <b>强引用</b>键），
 * 清理只有受 {@code isCodegenEnabled()} 门控的 context 关闭钩子 —— 运行中开启 codegen、收尾前关闭配置
 * 即漏清，强键下这些 Page/Frame 永久驻留并阻止回收。现改为<b>弱引用键</b>兜底，并暴露条目计数。</p>
 *
 * <p><b>断言口径</b>：用<b>增量</b>而非绝对值，避免与同 JVM 其它用例的残留条目耦合。</p>
 */
class RoleElementPickerEvalLockTest {

    @AfterEach
    void tearDown() {
        RoleElementPicker.clearEvalLocks();
    }

    @Test
    @DisplayName("CT2-09：同一 Page 复用同一把锁 —— 改为弱引用键后串行化语义必须不变（条目数不增）")
    void samePageReusesSameLock() {
        Page page = mock(Page.class);

        RoleElementPicker.pickerEval(page, "1");
        int afterFirst = RoleElementPicker.evalLockCount();
        RoleElementPicker.pickerEval(page, "1");
        int afterSecond = RoleElementPicker.evalLockCount();

        assertEquals(afterFirst, afterSecond,
                "同一 Page 必须复用同一把锁（若每次新建锁则条目增长，且串行化失效）");
    }

    @Test
    @DisplayName("CT2-09：不同 Page 各自一把锁（不得互相串行化）")
    void differentPagesGetDifferentLocks() {
        Page pageA = mock(Page.class);
        Page pageB = mock(Page.class);

        int before = RoleElementPicker.evalLockCount();
        RoleElementPicker.pickerEval(pageA, "1");
        RoleElementPicker.pickerEval(pageB, "1");

        assertEquals(before + 2, RoleElementPicker.evalLockCount(),
                "不同 Page 必须各占一条（不同页之间不应互相阻塞）");
    }

    @Test
    @DisplayName("CT2-09：releaseEvalLocksForPage 必须真正移除条目（清理钩子有效，防无界增长）")
    void releaseForPageRemovesEntry() {
        Page page = mock(Page.class);
        int before = RoleElementPicker.evalLockCount();

        RoleElementPicker.pickerEval(page, "1");
        assertTrue(RoleElementPicker.evalLockCount() > before, "使用后应有条目");

        RoleElementPicker.releaseEvalLocksForPage(page);

        assertEquals(before, RoleElementPicker.evalLockCount(),
                "释放后不得残留 —— 否则长跑套件下按 Page 无界增长");
    }
}
