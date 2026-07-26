package com.hsbc.cmb.hk.dbb.automation.tests;

import com.hsbc.cmb.hk.dbb.automation.framework.web.page.PageElement;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.RoleElement;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.RoleFile;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.factory.PageObjectFactory;
import com.hsbc.cmb.hk.dbb.automation.framework.web.utils.NLSUtils;

import org.junit.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * 离线验证 RoleElement 机制（无需启动浏览器 / 连接 HSBC 环境）：
 * <ol>
 *   <li>{@link #everyRoleElementKeyResolvesInNls()} — 每个带 <code>key</code> 的字段，
 *       其 nls key 都能在类级 {@link RoleFile} 声明的文件里解析出非空值；
 *       这是 RoleElement 定位器不“静默失效”的最关键前提。</li>
 *   <li>{@link #roleElementFieldsAreBoundToPageElement()} — 页面对象实例化后，
 *       每个 {@link RoleElement} 字段都被 BasePage 反射绑定成非空的 {@link PageElement}。</li>
 * </ol>
 *
 * <p>运行：<code>mvn -o test -Dtest=RoleElementBindingTest</code>
 */
public class RoleElementBindingTest {

    @Test
    public void everyRoleElementKeyResolvesInNls() {
        NLSUtils.setLanguage("en-US");
        RoleFile rf = LoginPage.class.getAnnotation(RoleFile.class);
        assertNotNull("@RoleFile must be declared on LoginPage", rf);
        var bundle = NLSUtils.bind(rf.value());

        List<String> problems = new ArrayList<>();
        int checked = 0;
        for (Field f : LoginPage.class.getDeclaredFields()) {
            RoleElement a = f.getAnnotation(RoleElement.class);
            if (a == null) continue;
            String key = a.key();
            if (key == null || key.isEmpty()) continue; // 仅校验 role + nls key 类字段
            checked++;
            String v;
            try {
                v = bundle.get(key);
            } catch (Exception e) {
                problems.add(f.getName() + " -> key=" + key + " (" + e.getMessage() + ")");
                continue;
            }
            if (v == null || v.isBlank()) {
                problems.add(f.getName() + " -> key=" + key + " resolved empty");
            }
        }
        assertTrue("RoleElement nls keys unresolved/missing (checked=" + checked + "):\n"
                + String.join("\n", problems), problems.isEmpty());
    }

    @Test
    public void roleElementFieldsAreBoundToPageElement() {
        LoginPage page = PageObjectFactory.getPage(LoginPage.class);
        assertNotNull("PageObjectFactory.getPage(LoginPage.class) should return an instance", page);

        List<String> unbound = new ArrayList<>();
        int bound = 0;
        for (Field f : LoginPage.class.getDeclaredFields()) {
            if (!f.isAnnotationPresent(RoleElement.class)) continue;
            f.setAccessible(true);
            Object v;
            try {
                v = f.get(page);
            } catch (Exception e) {
                unbound.add(f.getName() + " (get failed: " + e.getMessage() + ")");
                continue;
            }
            if (!(v instanceof PageElement)) {
                unbound.add(f.getName() + " (not a PageElement: "
                        + (v == null ? "null" : v.getClass().getSimpleName()) + ")");
            } else {
                bound++;
            }
        }
        assertEquals("all @RoleElement fields must bind to PageElement; unbound=" + unbound,
                0, unbound.size());
        assertTrue("expected at least one @RoleElement field bound, got " + bound, bound > 0);
    }
}
