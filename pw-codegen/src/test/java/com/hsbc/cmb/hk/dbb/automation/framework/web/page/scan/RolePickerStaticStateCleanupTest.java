package com.hsbc.cmb.hk.dbb.automation.framework.web.page.scan;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import org.junit.After;
import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.function.Function;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * CT2-09 / CT2-20 契约：以 {@code Page} / {@code BrowserContext} 为键的静态缓存，
 * 必须能被 {@code cleanupContext} / {@code cleanupPage} / {@code clearAll} <b>真正回收</b>。
 *
 * <p>修复前的两处缺陷：
 * <ul>
 *   <li><b>CT2-09</b>：{@code CTX_PANEL_SCRIPTED} / {@code CTX_PICKER_NLS} / {@code FORCE_START_TS}
 *       两个清理入口<b>都不清</b>；{@code EVAL_LOCKS}（Page/Frame 键）只 {@code computeIfAbsent}、全仓无 remove
 *       → codegen 侧静态态无界增长，且以已关闭 Page 的强引用为键使其无法 GC。</li>
 *   <li><b>CT2-20</b>：{@code LAST_SYNC_SIG}（Page 键）清理点全仓唯一（重编号后强制刷新 ETag），
 *       普通拾取会话的 Page 永不被移除。</li>
 * </ul>
 *
 * <p>route/codegen 类测试无 Mockito，沿用 JDK 动态代理桩模式。
 */
public class RolePickerStaticStateCleanupTest {

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> iface, Function<String, Object> special) {
        return (T) Proxy.newProxyInstance(
                RolePickerStaticStateCleanupTest.class.getClassLoader(),
                new Class<?>[]{iface},
                (p, method, args) -> {
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

    private static BrowserContext contextStub() {
        return proxy(BrowserContext.class, n -> null);
    }

    private static Page pageStub(BrowserContext ctx) {
        return proxy(Page.class, n -> "context".equals(n) ? ctx : null);
    }

    /** {@code EVAL_LOCKS} 为 private，经反射读取规模作为「是否释放」的确定性判据。 */
    @SuppressWarnings("unchecked")
    private static int evalLocksSize() throws Exception {
        Field f = RoleElementPicker.class.getDeclaredField("EVAL_LOCKS");
        f.setAccessible(true);
        return ((Map<Object, Object>) f.get(null)).size();
    }

    @After
    public void cleanup() {
        // clearAll 现已覆盖全部静态态（含 EVAL_LOCKS / LAST_SYNC_SIG），作为跨用例复位
        RoleElementPicker.clearAll();
    }

    @Test
    // @DisplayName: "CT2-09/20：cleanupContext 必须回收全部按 context/page 的静态缓存"
    public void cleanupContextPurgesAllStaticCaches() throws Exception {
        BrowserContext ctx = contextStub();
        Page page = pageStub(ctx);

        RolePickerSessionState.CTX_PANEL_SCRIPTED.add(ctx);
        RolePickerSessionState.CTX_PICKER_NLS.put(ctx, "zh");
        RolePickerSessionState.FORCE_START_TS.put(page, 1L);
        RolePickerPanelSync.LAST_SYNC_SIG.put(page, "sig");
        RoleElementPicker.pickerEval(page, "1+1"); // 填充 EVAL_LOCKS（按 Page 键）

        assertTrue("前置：EVAL_LOCKS 应已登记该 Page", evalLocksSize() > 0);

        RolePickerSessionState.cleanupContext(ctx);

        assertTrue("CTX_PANEL_SCRIPTED 未被 cleanupContext 清理（CT2-09）", RolePickerSessionState.CTX_PANEL_SCRIPTED.isEmpty());
        assertTrue("CTX_PICKER_NLS 未被 cleanupContext 清理（CT2-09）", RolePickerSessionState.CTX_PICKER_NLS.isEmpty());
        assertTrue("FORCE_START_TS 未被 cleanupContext 清理（CT2-09）", RolePickerSessionState.FORCE_START_TS.isEmpty());
        assertTrue("LAST_SYNC_SIG 未被 cleanupContext 清理（CT2-20）", RolePickerPanelSync.LAST_SYNC_SIG.isEmpty());
        assertEquals("EVAL_LOCKS 未被 cleanupContext 释放（CT2-09：按 Page 键只增不减）", 0, evalLocksSize());
    }

    @Test
    // @DisplayName: "CT2-09/20：cleanupPage 必须回收该 Page 的三张 page-level 静态缓存"
    public void cleanupPagePurgesPageScopedCaches() throws Exception {
        BrowserContext ctx = contextStub();
        Page page = pageStub(ctx);

        RolePickerSessionState.FORCE_START_TS.put(page, 1L);
        RolePickerPanelSync.LAST_SYNC_SIG.put(page, "sig");
        RoleElementPicker.pickerEval(page, "1+1");

        RolePickerSessionState.cleanupPage(page);

        assertTrue("FORCE_START_TS 未被 cleanupPage 清理（CT2-09）", RolePickerSessionState.FORCE_START_TS.isEmpty());
        assertTrue("LAST_SYNC_SIG 未被 cleanupPage 清理（CT2-20）", RolePickerPanelSync.LAST_SYNC_SIG.isEmpty());
        assertEquals("EVAL_LOCKS 未被 cleanupPage 释放（CT2-09）", 0, evalLocksSize());
    }

    @Test
    // @DisplayName: "CT2-09：clearAll 必须清掉此前遗漏的三张静态 Map"
    public void clearAllPurgesPreviouslyMissedMaps() throws Exception {
        BrowserContext ctx = contextStub();
        Page page = pageStub(ctx);

        RolePickerSessionState.CTX_PANEL_SCRIPTED.add(ctx);
        RolePickerSessionState.CTX_PICKER_NLS.put(ctx, "en");
        RolePickerSessionState.FORCE_START_TS.put(page, 2L);
        RolePickerPanelSync.LAST_SYNC_SIG.put(page, "sig");
        RoleElementPicker.pickerEval(page, "1+1");

        RoleElementPicker.clearAll();

        assertTrue("clearAll 未清 CTX_PANEL_SCRIPTED（CT2-09）", RolePickerSessionState.CTX_PANEL_SCRIPTED.isEmpty());
        assertTrue("clearAll 未清 CTX_PICKER_NLS（CT2-09）", RolePickerSessionState.CTX_PICKER_NLS.isEmpty());
        assertTrue("clearAll 未清 FORCE_START_TS（CT2-09）", RolePickerSessionState.FORCE_START_TS.isEmpty());
        assertTrue("clearAll 未清 LAST_SYNC_SIG（CT2-20）", RolePickerPanelSync.LAST_SYNC_SIG.isEmpty());
        assertEquals("clearAll 未清 EVAL_LOCKS（CT2-09）", 0, evalLocksSize());
    }
}
