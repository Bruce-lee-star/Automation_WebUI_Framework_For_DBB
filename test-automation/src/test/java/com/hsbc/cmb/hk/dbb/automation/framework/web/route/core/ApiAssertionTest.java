package com.hsbc.cmb.hk.dbb.automation.framework.web.route.core;

import org.junit.Test;

import java.lang.reflect.Method;
import java.util.regex.Pattern;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * 固化 Phase 5 抽离出的 {@link ApiAssertion} 编译层契约（与拆分前语义严格一致）。
 *
 * <p>{@code statusIs/jsonPath/bodyContains/isMock/get} 等运行时方法依赖
 * {@code ApiCaptureContext.getCurrent()} 全局采集上下文（由集成护盾覆盖）；
 * 本类锁定<b>抽离后独立的 glob 编译实现</b>不应发生漂移：
 * <ul>
 *   <li>构造器调用私有 {@code globToRegex} 不抛；</li>
 *   <li>{@code globToRegex} 的 {@code **}/{@code *}/特殊字符语义与 {@link RoutePatternCache} 平行一致；</li>
 *   <li>{@code containsGlobWildcard} 通配符探测正确。</li>
 * </ul>
 * 通过反射访问 private 静态方法（同包 + setAccessible），避免为测试放宽可见性。
 * <b>不依赖 Playwright</b>。
 */
public class ApiAssertionTest {

    @Test
    public void ctor_compilesGlobWithoutThrowing() {
        ApiAssertion a = new ApiAssertion("/api/**");
        assertNotNull(a);
    }

    @Test
    public void globToRegex_singleStar_doesNotCrossSlash() throws Exception {
        Pattern p = (Pattern) globToRegex.invoke(null, "*.json");
        assertTrue("单层 * 匹配同目录文件", p.matcher("foo.json").matches());
        assertFalse("单层 * 不应跨越 /", p.matcher("a/b.json").matches());
    }

    @Test
    public void globToRegex_doubleStar_crossesSlash() throws Exception {
        Pattern p = (Pattern) globToRegex.invoke(null, "/api/**");
        assertTrue("** 应匹配任意深层路径", p.matcher("/api/x/y").matches());
        assertFalse("** 不应匹配其他前缀", p.matcher("/other/x").matches());
    }

    @Test
    public void globToRegex_escapesSpecialChars() throws Exception {
        Pattern p = (Pattern) globToRegex.invoke(null, "a.b");
        assertTrue("字面点应匹配", p.matcher("a.b").matches());
        assertFalse("未转义的点会错误匹配任意字符", p.matcher("axb").matches());
    }

    @Test
    public void containsGlobWildcard_detectsStar() throws Exception {
        assertTrue((Boolean) containsGlobWildcard.invoke(null, "a*b"));
        assertFalse((Boolean) containsGlobWildcard.invoke(null, "abc"));
    }

    private static final Method globToRegex;
    private static final Method containsGlobWildcard;

    static {
        try {
            globToRegex = ApiAssertion.class.getDeclaredMethod("globToRegex", String.class);
            globToRegex.setAccessible(true);
            containsGlobWildcard = ApiAssertion.class.getDeclaredMethod("containsGlobWildcard", String.class);
            containsGlobWildcard.setAccessible(true);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("ApiAssertion 内部签名变更，需同步更新本 UT", e);
        }
    }
}
