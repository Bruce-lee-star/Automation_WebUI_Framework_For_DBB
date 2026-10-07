package com.hsbc.cmb.hk.dbb.automation.framework.web.page.scan;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import org.junit.After;
import org.junit.Test;

import java.lang.reflect.Proxy;
import java.util.LinkedHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * 面板重同步的「无变化不写回」契约（{@link RolePickerPanelSync#syncPanelToBrowser}）。
 *
 * <p><b>问题</b>：拾取会话的空闲主循环（约 1s 一轮）每轮都会重读面板状态并调用本方法。原实现把
 * 每元素一条的 {@code [diag-sync]} INFO 日志放在 ETag 判重<b>之前</b>，于是状态完全没变的每一轮都会把
 * 同一批元素重打一遍（实测同 3 个元素每秒重复、{@code pickNos} 恒为 {@code []}），刷屏的同时也让
 * "面板其实没变"这一事实不可见。
 *
 * <p>本用例以<b>driver 写回调用次数</b>为判据（而非日志文案）：状态未变时必须为 0 次新增写回。
 */
public class RolePickerPanelResyncTest {

    private static final AtomicInteger EVAL_CALLS = new AtomicInteger();

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> iface, Function<String, Object> special) {
        return (T) Proxy.newProxyInstance(
                RolePickerPanelResyncTest.class.getClassLoader(),
                new Class<?>[]{iface},
                (p, method, args) -> {
                    if ("evaluate".equals(method.getName())) {
                        EVAL_CALLS.incrementAndGet();
                        return null;
                    }
                    Object v = special.apply(method.getName());
                    if (v != null) {
                        return v;
                    }
                    if (method.getReturnType() == boolean.class) {
                        return false;
                    }
                    if (method.getReturnType().isPrimitive()) {
                        return 0;
                    }
                    return null;
                });
    }

    private static Page pageStub(BrowserContext ctx) {
        return proxy(Page.class, n -> "context".equals(n) ? ctx : null);
    }

    @After
    public void cleanup() {
        // 两张按 Page 键的静态缓存都要复位，避免用例间互相影响（CT2-09/20 同口径）
        RolePickerPanelSync.clearAll();
        RoleElementPicker.clearAll();
        EVAL_CALLS.set(0);
    }

    @Test
    // @DisplayName: "面板状态未变时不得重复写回（空闲主循环不再刷屏/空转 driver）"
    public void unchangedStateIsNotRewritten() {
        BrowserContext ctx = proxy(BrowserContext.class, n -> null);
        Page page = pageStub(ctx);
        LinkedHashMap<String, RoleEntry> state = new LinkedHashMap<>();
        RoleEntry e = new RoleEntry("text", "Special announcement");
        state.put("HomePage|text:Special announcement", e);

        RolePickerPanelSync.syncPanelToBrowser(page, null, state, false);
        int afterFirst = EVAL_CALLS.get();
        assertTrue("首次同步应当写回面板（否则用例前提不成立）", afterFirst > 0);

        RolePickerPanelSync.syncPanelToBrowser(page, null, state, false);
        assertEquals("状态未变时不得再次写回（否则空闲期每轮都空转一次 driver 往返并刷屏）",
                afterFirst, EVAL_CALLS.get());

        // 状态确实变化时（新增元素）必须恢复写回
        state.put("HomePage|text:Activate security device.", new RoleEntry("text", "Activate security device."));
        RolePickerPanelSync.syncPanelToBrowser(page, null, state, false);
        assertTrue("状态变化后必须重新写回", EVAL_CALLS.get() > afterFirst);
    }
}
