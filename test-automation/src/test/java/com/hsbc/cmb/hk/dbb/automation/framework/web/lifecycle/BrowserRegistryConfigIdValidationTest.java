package com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle;

import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.browser.BrowserRegistry;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.browser.BrowserRegistryImpl;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * BrowserRegistry configId 形态校验（对齐 playwright-java 1.58.0 官方线程模型 / 共享 Browser 只读契约）。
 *
 * <p>无浏览器依赖：仅验证 {@link BrowserRegistry#validateConfigIdShape(String)} 的纯函数契约——
 * 合法 configId（形如 {@code "<browserType>_<headless>[_channel]"}）必须含 {@code '_'} 分隔符，
 * 缺失时抛 {@link IllegalArgumentException}（语义化异常），而非让 {@link BrowserRegistry#getBrowser()}
 * 静默误判浏览器类型 / 裸 NPE。
 *
 * <p>同包白盒测试：直接访问包级私有 {@code validateConfigIdShape}（与 {@code BasePageThreadOwnershipConcurrencyTest}
 * 访问包级私有 {@code setPageReference} 同范式）。
 */
public class BrowserRegistryConfigIdValidationTest {

    @Test
    public void validateConfigIdShape_acceptsValidShapes() {
        BrowserRegistryImpl.INSTANCE.validateConfigIdShape("chromium_headless");
        BrowserRegistryImpl.INSTANCE.validateConfigIdShape("firefox_headed");
        BrowserRegistryImpl.INSTANCE.validateConfigIdShape("chromium_headless_channel");
    }

    @Test
    public void validateConfigIdShape_rejectsMissingUnderscore() {
        assertThrows(IllegalArgumentException.class,
                () -> BrowserRegistryImpl.INSTANCE.validateConfigIdShape("chromiumheadless"));
    }

    @Test
    public void validateConfigIdShape_rejectsNull() {
        assertThrows(IllegalArgumentException.class,
                () -> BrowserRegistryImpl.INSTANCE.validateConfigIdShape(null));
    }
}
