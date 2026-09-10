package com.hsbc.cmb.hk.dbb.automation.framework.web.page.base;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.options.Cookie;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.base.BasePage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Cookie 操作工厂（WEB-P1-2 Phase 2）：从 {@link BasePage} 下沉全部 Cookie 操作。
 *
 * <p>与 {@link LocatorFactory}/{@link PageFrameShadow} 同源模式：静态工具类、接收
 * {@code BasePage bp}、经 bp 的公开 API（{@code getContext}/{@code getPage}）与包级
 * seam（{@code ensurePageValid}/{@code ensureContextValid}）访问状态，日志路由回
 * {@code BasePage.class} 保持生产溯源一致。
 *
 * <p>全部方法均为 framework-internal。
 */
public final class CookieManager {

    private static final Logger log = LoggerFactory.getLogger(BasePage.class);

    private CookieManager() {
    }

    /**
     * 获取当前 BrowserContext 中所有 Cookie
     *
     * @return Cookie 列表
     */
    public static List<Cookie> getCookies(BasePage bp) {
        bp.ensureContextValid();
        return bp.getContext().cookies();
    }

    /**
     * 获取指定 URL 相关的 Cookie
     *
     * @param url 目标 URL
     * @return Cookie 列表
     */
    public static List<Cookie> getCookies(BasePage bp, String url) {
        bp.ensureContextValid();
        return bp.getContext().cookies(url);
    }

    /**
     * 获取多个 URL 相关的 Cookie
     *
     * @param urls 目标 URL 列表
     * @return Cookie 列表
     */
    public static List<Cookie> getCookies(BasePage bp, List<String> urls) {
        bp.ensureContextValid();
        return bp.getContext().cookies(urls);
    }

    /**
     * 根据名称获取指定 Cookie
     *
     * @param name Cookie 名称
     * @return Cookie 对象，不存在时返回 null
     */
    public static Cookie getCookie(BasePage bp, String name) {
        bp.ensureContextValid();
        return bp.getContext().cookies().stream()
                .filter(c -> c.name.equals(name))
                .findFirst()
                .orElse(null);
    }

    /**
     * 检查指定名称的 Cookie 是否存在
     *
     * @param name Cookie 名称
     * @return 存在返回 true
     */
    public static boolean hasCookie(BasePage bp, String name) {
        return getCookie(bp, name) != null;
    }

    /**
     * 添加单个 Cookie
     *
     * @param cookie Playwright Cookie 对象
     */
    public static void addCookie(BasePage bp, Cookie cookie) {
        bp.ensureContextValid();
        bp.getContext().addCookies(List.of(cookie));
    }

    /**
     * 批量添加 Cookie
     *
     * @param cookies Cookie 列表
     */
    public static void addCookies(BasePage bp, List<Cookie> cookies) {
        bp.ensureContextValid();
        bp.getContext().addCookies(cookies);
    }

    /**
     * 根据名称删除指定 Cookie
     *
     * @param name Cookie 名称
     */
    public static void deleteCookie(BasePage bp, String name) {
        bp.ensureContextValid();
        bp.getContext().clearCookies(new BrowserContext.ClearCookiesOptions().setName(name));
    }

    /**
     * 清除当前 BrowserContext 中的所有 Cookie
     */
    public static void clearCookies(BasePage bp) {
        bp.ensureContextValid();
        bp.getContext().clearCookies();
    }

    /**
     * 获取当前页面 URL 关联的所有 Cookie
     *
     * @return Cookie 列表
     */
    public static List<Cookie> getCookiesForCurrentPage(BasePage bp) {
        bp.ensurePageValid();
        bp.ensureContextValid();
        return bp.getContext().cookies(bp.getPage().url());
    }
}
