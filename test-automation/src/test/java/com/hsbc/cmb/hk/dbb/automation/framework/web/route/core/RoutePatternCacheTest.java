package com.hsbc.cmb.hk.dbb.automation.framework.web.route.core;

import org.junit.Test;

import java.util.regex.Pattern;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * 固化 Phase 5 抽离出的 {@link RoutePatternCache} 纯逻辑契约（与拆分前语义严格一致）。
 *
 * <p>覆盖两类契约：
 * <ol>
 *   <li><b>glob → 正则编译语义</b>：{@code **} 跨斜杠、{@code *} 不跨斜杠、正则特殊字符被转义；</li>
 *   <li><b>缓存命中</b>：相同 glob 重复调用返回同一 {@link Pattern} 实例（ConcurrentHashMap 命中）。</li>
 * </ol>
 * 与 {@link RoutePatternCache} 同包，可直接访问其 package-private 静态方法 {@code antGlobToRegex}。
 * <b>不依赖 Playwright</b>，纯编译 + 缓存不变量校验。
 */
public class RoutePatternCacheTest {

    @Test
    public void singleStar_doesNotCrossSlash() {
        Pattern p = RoutePatternCache.antGlobToRegex("*.json");
        assertTrue("单层 * 应匹配同目录文件", p.matcher("foo.json").matches());
        assertFalse("单层 * 不应跨越 /", p.matcher("a/b.json").matches());
    }

    @Test
    public void doubleStar_crossesSlash() {
        Pattern p = RoutePatternCache.antGlobToRegex("/api/**");
        assertTrue("** 应匹配任意深层路径", p.matcher("/api/x/y/z").matches());
        assertFalse("** 不应匹配其他前缀", p.matcher("/other/x").matches());
    }

    @Test
    public void specialChars_areEscaped() {
        // '.' 必须被转义为字面点，而非正则"任意字符"
        Pattern p = RoutePatternCache.antGlobToRegex("a.b");
        assertTrue("字面点应匹配", p.matcher("a.b").matches());
        assertFalse("未转义的点会错误匹配任意字符", p.matcher("axb").matches());
    }

    @Test
    public void regexMetacharacters_doNotBreakMatch() {
        // ()[]{} 等应被转义，能作为字面量匹配
        Pattern p = RoutePatternCache.antGlobToRegex("v1/users(active)");
        assertTrue(p.matcher("v1/users(active)").matches());
    }

    @Test
    public void resultIsCached_sameReferenceOnRepeat() {
        // 第二次调用应命中 PATTERN_CACHE，返回同一 Pattern 实例（锁定缓存契约）
        Pattern first = RoutePatternCache.antGlobToRegex("/cached/glob.json");
        Pattern second = RoutePatternCache.antGlobToRegex("/cached/glob.json");
        assertSame("重复 glob 应命中缓存返回同一实例", first, second);
    }

    @Test
    public void distinctGlobs_produceDistinctPatterns() {
        Pattern a = RoutePatternCache.antGlobToRegex("a/**");
        Pattern b = RoutePatternCache.antGlobToRegex("b/**");
        assertNotSame("不同 glob 不应共享同一 Pattern", a, b);
    }
}
