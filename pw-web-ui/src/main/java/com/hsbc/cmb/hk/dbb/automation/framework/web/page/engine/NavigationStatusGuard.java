package com.hsbc.cmb.hk.dbb.automation.framework.web.page.engine;

import com.hsbc.cmb.hk.dbb.automation.framework.web.exceptions.AccessDeniedException;
import com.microsoft.playwright.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 导航落点状态守卫 —— 页面导航拿到 <b>403（访问被拒）</b>这类错误时<b>直接抛错</b>，不再往下走。
 *
 * <h3>要解决的问题</h3>
 * <p>{@code page.navigate()} 只要拿到 HTML 文档就算"成功"：服务端返回 403（WAF/网关拦截、权限不足、
 * 会话失效）时框架原先静默继续，于是现象就是<b>"页面导航出现 403 就不动了"</b> —— 其实是在干等
 * 30~60 秒的元素超时（{@code waitForVisible}），最后报一个误导性的"元素不可见"，甚至被误判为
 * 会话失效而触发无意义重登。本守卫在导航那一刻就把真因抛出：错误信息带 <b>状态码 + 请求/最终 URL +
 * 边缘节点线索</b>；截图与导航轨迹由既有步骤失败链路自动落报告。</p>
 *
 * <h3>判定口径（硬编码，不加配置）</h3>
 * <ul>
 *   <li><b>拦截</b>：{@code 401 / 403 / 407} —— "访问被拒"一族，出现即说明页面没加载出来；</li>
 *   <li><b>放行</b>：2xx/3xx、404（负向用例常用）、429（限流）、5xx —— 需要拦再按需加，
 *       刻意不做"逐码配置化"，避免策略散落成配置矩阵、排障时反而看不清框架到底拦什么。</li>
 * </ul>
 *
 * <p><b>不做的事</b>：不自动重试、不自动重登（成因不同、处置相反：WAF 拦截重登无效，
 * 权限不足重试无意义 —— 先如实暴露，恢复动作留给上层）；不猜页面内容（只信状态码与响应头）。</p>
 */
public final class NavigationStatusGuard {

    private static final Logger LOGGER = LoggerFactory.getLogger(NavigationStatusGuard.class);

    /** 访问被拒一族（硬编码）：401 未认证 / 403 禁止 / 407 需代理认证。 */
    private static final Set<Integer> ACCESS_DENIED_STATUSES = Set.of(401, 403, 407);

    private NavigationStatusGuard() {
        // 纯静态守卫，禁止实例化
    }

    /**
     * 校验导航落点：命中访问被拒状态码即 ERROR 日志 + <b>直接抛</b> {@link AccessDeniedException}。
     *
     * <p>放行两种"取不到状态"的情况：响应为 null（{@code about:blank} / {@code setContent} /
     * 驱动未提供主文档响应），以及状态读取抛错（句柄已被回收的驱动竞态）—— 后者应由原有的导航异常
     * 链路表达，不该在守卫里变成新的失败点。</p>
     *
     * @param response     导航返回的主文档响应（可空）
     * @param requestedUrl 框架发起导航的目标 URL（失败信息用；最终 URL 从响应取）
     */
    public static void enforce(Response response, String requestedUrl) {
        if (response == null) {
            return;
        }
        Integer status = safeStatus(response);
        if (status == null || !ACCESS_DENIED_STATUSES.contains(status)) {
            return;
        }
        String finalUrl = safeUrl(response);
        String edgeHints = edgeHints(response);
        String message = String.format(Locale.ROOT,
                "server returned HTTP %d (access denied): the page did not load%s%s. "
                        + "Typical causes: WAF/gateway interception, missing permission, or expired session. "
                        + "Failing at the navigation call keeps the real cause instead of a misleading "
                        + "'element not visible' timeout.",
                status, finalUrl == null ? "" : ", final URL: " + finalUrl, edgeHints);
        LOGGER.error("[Navigation] BLOCKED by server: requested='{}'{} status={}{}",
                requestedUrl,
                finalUrl == null ? "" : " final='" + finalUrl + "'",
                status, edgeHints);
        throw new AccessDeniedException(requestedUrl, status, finalUrl, message);
    }

    /** 取状态码；句柄不可用等驱动竞态返回 null ⇒ 放行，交回原导航异常链路。 */
    private static Integer safeStatus(Response response) {
        try {
            return response.status();
        } catch (Exception | LinkageError e) {
            LOGGER.debug("[Navigation] response status unavailable (guard skips this navigation): {}", e.toString());
            return null;
        }
    }

    /** 取最终 URL（重定向链收尾后的地址）。 */
    private static String safeUrl(Response response) {
        try {
            return response.url();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 边缘节点线索：{@code server} / {@code via} / {@code x-cache} 三个非敏感响应头。
     *
     * <p>用途是<b>帮人判因</b>而非自动判因：出现 {@code AkamaiGHost} 之类线索时基本可判定
     * "请求没到应用"，从而避免把 WAF 拦截当成会话失效去重登。不认识的头一律忽略，不做厂商硬编码。</p>
     */
    private static String edgeHints(Response response) {
        try {
            Map<String, String> headers = response.headers();
            if (headers == null || headers.isEmpty()) {
                return "";
            }
            StringBuilder hints = new StringBuilder();
            appendHint(hints, "server", headers.get("server"));
            appendHint(hints, "via", headers.get("via"));
            appendHint(hints, "x-cache", headers.get("x-cache"));
            return hints.length() == 0 ? "" : ", edge hints: " + hints;
        } catch (Exception e) {
            return "";
        }
    }

    private static void appendHint(StringBuilder hints, String name, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        if (hints.length() > 0) {
            hints.append(", ");
        }
        hints.append(name).append('=').append(value);
    }
}
