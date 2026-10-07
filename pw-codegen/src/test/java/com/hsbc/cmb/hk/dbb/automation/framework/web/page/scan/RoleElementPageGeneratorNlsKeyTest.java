package com.hsbc.cmb.hk.dbb.automation.framework.web.page.scan;

import org.junit.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * NLS 文件与 {@code key = "..."} 定位的绑定契约。
 *
 * <p>用户反馈：「我没传 nls file，元素定位不应该有 key=」。根因：{@code @RoleElement(key = "...")} 依赖
 * 类级 {@code @RoleFile} 才能解析，而 {@code @RoleFile} 只在传入 NLS 文件时才生成。此前生成器只要元素能
 * 反查到 NLS key（resolvedKey）就无条件输出 {@code key=}，于是"没传 NLS 却生成 key=" ⇒ 产物里没有任何
 * NLS 表，定位器运行期无法解析。
 *
 * <p>本用例锁定：① 不传 NLS 文件 ⇒ 不得出现 {@code key=}（改用 {@code name = "..."}），且字段名按可访问名
 * 派生；② 传了 NLS 文件 ⇒ 仍按 key 定位并输出 {@code @RoleFile}（既有能力不被削弱）。
 */
public class RoleElementPageGeneratorNlsKeyTest {

    private static final String PKG = "com.example.pages";

    /** role 策略 + 可反查到 NLS key 的元素（name 为可访问名，resolvedKey 为 NLS key）。 */
    private static RoleEntry headingWithKey() {
        return new RoleEntry("heading", "Username", "h1", null,
                "role", null, "title_username_page",
                false, null, false, 0, false, "LogonPage");
    }

    private static String generate(List<RoleEntry> entries, String... nlsFiles) {
        return RoleElementPageGenerator.generate(entries, PKG, "LogonPage", nlsFiles);
    }

    @Test
    // @DisplayName: "未传 NLS 文件 ⇒ 不得出现 key= 定位，改用可访问名"
    public void withoutNlsFilesKeyLocatorIsNotEmitted() {
        String src = generate(Collections.singletonList(headingWithKey()));
        assertFalse("未传 NLS 文件却生成了 key= 定位（运行期无 NLS 表可解析）：\n" + src,
                src.contains("key = \""));
        assertFalse("未传 NLS 文件却生成了类级 @RoleFile：\n" + src, src.contains("@RoleFile("));
        assertTrue("应回退为可访问名定位 name = \"Username\"：\n" + src, src.contains("name = \"Username\""));
        assertTrue("字段名应按可访问名派生（usernameTitle…），不应按 key（titleUsernamePage）：\n" + src,
                !src.contains("titleUsernamePage"));
    }

    @Test
    // @DisplayName: "传了 NLS 文件 ⇒ 仍按 key 定位并输出 @RoleFile"
    public void withNlsFilesKeyLocatorIsEmitted() {
        String src = generate(Collections.singletonList(headingWithKey()), "nls/logon.properties");
        assertTrue("@RoleFile 应指向传入的 NLS 文件：\n" + src, src.contains("@RoleFile(\"nls/logon.properties\")"));
        assertTrue("传了 NLS 文件时应保留 key= 定位：\n" + src, src.contains("key = \"title_username_page\""));
        assertFalse("按 key 定位时不应再输出 name=：\n" + src, src.contains("name = \"Username\""));
    }
}
