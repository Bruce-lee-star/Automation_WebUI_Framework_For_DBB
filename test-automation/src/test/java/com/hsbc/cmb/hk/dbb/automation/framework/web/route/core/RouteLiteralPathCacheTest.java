package com.hsbc.cmb.hk.dbb.automation.framework.route.core;

import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * 固化 Phase 5 死代码隔离载体 {@link RouteLiteralPathCache} 的逻辑契约。
 *
 * <p>该类虽为 {@code @Deprecated} 死代码（活跃路径见 {@code RouteUtil#literalPathOf}），
 * 但其抽离实现保持完整、可纯单测；本类锁定其「去除通配符字面前缀」语义与缓存命中不变量，
 * 防止将来若复用该缓存路径时发生行为漂移。
 * <b>不依赖 Playwright</b>。
 */
public class RouteLiteralPathCacheTest {

    @After
    public void tearDown() {
        RouteLiteralPathCache.clear();
    }

    @Test
    public void literalPathOf_stripsLeadingAndTrailingDoubleStar() {
        assertEquals("/api/", RouteLiteralPathCache.literalPathOf("/api/**"));
        assertEquals("/foo/", RouteLiteralPathCache.literalPathOf("**/foo/"));
    }

    @Test
    public void literalPathOf_truncatesAtFirstSingleStar() {
        // 去首尾 ** 后截断到首个 * 之前
        assertEquals("/foo/", RouteLiteralPathCache.literalPathOf("**/foo/*/bar"));
    }

    @Test
    public void literalPathOf_noWildcard_returnsAsIs() {
        assertEquals("api/users", RouteLiteralPathCache.literalPathOf("api/users"));
    }

    @Test
    public void literalPathOf_nullOrEmpty_returnsNull() {
        assertNull(RouteLiteralPathCache.literalPathOf(null));
        assertNull(RouteLiteralPathCache.literalPathOf(""));
    }

    @Test
    public void literalPathOf_cachesSameResultOnRepeat() {
        String first = RouteLiteralPathCache.literalPathOf("/api/**");
        String second = RouteLiteralPathCache.literalPathOf("/api/**");
        assertEquals("重复调用应命中缓存且结果一致", first, second);
    }
}
