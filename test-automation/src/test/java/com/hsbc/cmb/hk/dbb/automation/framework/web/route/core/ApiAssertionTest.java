package com.hsbc.cmb.hk.dbb.automation.framework.route.core;

import org.junit.Test;

import java.lang.reflect.Method;
import java.util.regex.Pattern;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * 固化 route 模块统一的 Ant-glob 编译实现（{@link RoutePatternCache#antGlobToRegex}）。
 *
 * <p>该实现被 {@link ApiAssertion} 与 {@link ResponseStore} 共同委托，是全局唯一一处
 * Ant-glob→正则转换（T2-5 合并重复的 Glob 匹配后收敛），故本类直接锁定其语义不漂移：
 * <ul>
 *   <li>{@code ApiAssertion} 构造经委托不抛（间接验证统一实现可用）；</li>
 *   <li>{@code RoutePatternCache.antGlobToRegex} 的 {@code **}/{@code *}/特殊字符语义正确；</li>
 *   <li>{@code ApiAssertion.containsGlobWildcard} 通配符探测正确。</li>
 * </ul>
 * <b>不依赖 Playwright</b>。
 */
public class ApiAssertionTest {

    @Test
    public void ctor_compilesGlobWithoutThrowing() {
        // 构造器经 RoutePatternCache.antGlobToRegex 委托，不抛即证明统一实现可用
        ApiAssertion a = new ApiAssertion("/api/**");
        assertNotNull(a);
    }

    @Test
    public void antGlobToRegex_singleStar_doesNotCrossSlash() {
        Pattern p = RoutePatternCache.antGlobToRegex("*.json");
        assertTrue("单层 * 匹配同目录文件", p.matcher("foo.json").matches());
        assertFalse("单层 * 不应跨越 /", p.matcher("a/b.json").matches());
    }

    @Test
    public void antGlobToRegex_doubleStar_crossesSlash() {
        Pattern p = RoutePatternCache.antGlobToRegex("/api/**");
        assertTrue("** 应匹配任意深层路径", p.matcher("/api/x/y").matches());
        assertFalse("** 不应匹配其他前缀", p.matcher("/other/x").matches());
    }

    @Test
    public void antGlobToRegex_escapesSpecialChars() {
        Pattern p = RoutePatternCache.antGlobToRegex("a.b");
        assertTrue("字面点应匹配", p.matcher("a.b").matches());
        assertFalse("未转义的点会错误匹配任意字符", p.matcher("axb").matches());
    }

    @Test
    public void containsGlobWildcard_detectsStar() throws Exception {
        assertTrue((Boolean) containsGlobWildcard.invoke(null, "a*b"));
        assertFalse((Boolean) containsGlobWildcard.invoke(null, "abc"));
    }

    private static final Method containsGlobWildcard;

    static {
        try {
            containsGlobWildcard = ApiAssertion.class.getDeclaredMethod("containsGlobWildcard", String.class);
            containsGlobWildcard.setAccessible(true);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("ApiAssertion 内部签名变更，需同步更新本 UT", e);
        }
    }
}
