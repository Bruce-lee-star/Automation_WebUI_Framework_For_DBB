package com.hsbc.cmb.hk.dbb.automation.tests.utils;

import com.microsoft.playwright.Page;

import java.util.HashMap;
import java.util.Map;

/**
 * 在「页面 JS 上下文」中发起各种请求形态（fetch / XHR / image / script），供 route-demo-web (:8899) 的覆盖率测试使用。
 *
 * <p>关键点（与 {@link RouteDemoApi} 一致）：Playwright 的 APIRequestContext 发出的请求<b>不经过</b>
 * page/context 的 route 拦截，框架拦截只对「页面上下文内的 fetch / navigation / 资源加载 / iframe 内请求」生效。
 * 因此所有触发都必须经由本页 {@code window.__cov} 在页面上下文中发起。
 */
public final class RouteDemoCoverageApi {

    private static final String REQUEST =
            "async (o) => { const init={}; if(o.method)init.method=o.method; if(o.headers)init.headers=o.headers;"
            + " if(o.body)init.body=o.body; if(o.referrer){init.referrer=o.referrer;init.referrerPolicy=o.referrerPolicy||'unsafe-url';}"
            + " const r=await fetch(o.url,init); const b=await r.text(); const h={}; r.headers.forEach((v,k)=>h[k]=v);"
            + " return JSON.stringify({status:r.status,body:b,headers:h}); }";

    private static final String XHR =
            "async (o) => { return await new Promise((resolve,reject)=>{ const x=new XMLHttpRequest();"
            + " x.open(o.method||'GET',o.url,true); if(o.headers)Object.keys(o.headers).forEach(k=>x.setRequestHeader(k,o.headers[k]));"
            + " x.onload=()=>resolve(JSON.stringify({status:x.status,body:x.responseText,headers:{}}));"
            + " x.onerror=()=>reject(new Error('xhr error')); x.send(o.body||null); }); }";

    private static final String IMAGE =
            "async (u) => { return await new Promise(res=>{ const i=new Image(); i.onload=()=>res('ok');"
            + " i.onerror=()=>res('err'); i.src=u; }); }";

    private static final String SCRIPT =
            "async (u) => { return await new Promise(res=>{ const s=document.createElement('script'); s.src=u;"
            + " s.onload=()=>res('ok'); s.onerror=()=>res('err'); document.body.appendChild(s); }); }";

    private RouteDemoCoverageApi() {
    }

    /** 页面上下文内 fetch，返回 JSON 字符串 {status, body, headers}。 */
    public static String request(Page page, String url, String method,
                                 Map<String, String> headers, String body, String referrer) {
        Map<String, Object> o = new HashMap<>();
        o.put("url", url);
        if (method != null) o.put("method", method);
        if (headers != null) o.put("headers", headers);
        if (body != null) o.put("body", body);
        if (referrer != null) o.put("referrer", referrer);
        return (String) page.evaluate(REQUEST, o);
    }

    /** 页面上下文内 XMLHttpRequest，返回 JSON 字符串 {status, body, headers}。 */
    public static String xhr(Page page, String url, String method,
                            Map<String, String> headers, String body) {
        Map<String, Object> o = new HashMap<>();
        o.put("url", url);
        o.put("method", method == null ? "GET" : method);
        if (headers != null) o.put("headers", headers);
        if (body != null) o.put("body", body);
        return (String) page.evaluate(XHR, o);
    }

    /** 加载图片（resourceType=image），返回 'ok' / 'err'。 */
    public static String loadImage(Page page, String url) {
        return (String) page.evaluate(IMAGE, url);
    }

    /** 加载脚本（resourceType=script），返回 'ok' / 'err'。 */
    public static String loadScript(Page page, String url) {
        return (String) page.evaluate(SCRIPT, url);
    }
}
