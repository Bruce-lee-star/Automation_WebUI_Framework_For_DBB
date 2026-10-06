package com.hsbc.cmb.hk.dbb.automation.framework.web.exceptions;

/**
 * <b>访问被拒</b>：页面导航落地到 401 / 403 / 407 时抛出。
 *
 * <p>判定硬编码在 {@code NavigationStatusGuard}（<b>不加配置</b>）：这三码就是"访问被拒"这一族，
 * 出现即说明<b>页面没加载出来</b>，必须当场抛错，而不是继续往下走。</p>
 *
 * <p>典型成因三类，处置方向完全不同，故失败信息只陈述事实（状态码 + 请求/最终 URL + 响应头线索
 * {@code server} / {@code via} / {@code x-cache}）而不做单一归因：</p>
 * <ol>
 *   <li><b>WAF / 网关拦截</b>（边缘节点直接返回 403，请求根本没到应用）—— 重试与重新登录都无效；</li>
 *   <li><b>权限不足</b>（该账号确实没有该页权限）—— 用例或数据问题；</li>
 *   <li><b>会话失效</b>（服务端把请求当未认证）—— 清会话 + 重登是正确动作。</li>
 * </ol>
 */
public class AccessDeniedException extends NavigationException {

    private static final long serialVersionUID = 1L;

    /** 服务端返回的状态码（401 / 403 / 407）。 */
    private final int status;

    /** 框架发起导航的目标 URL。 */
    private final String requestedUrl;

    /** 最终 URL（重定向链收尾后的地址；取不到时为 null）。 */
    private final String finalUrl;

    public AccessDeniedException(String requestedUrl, int status, String finalUrl, String message) {
        super(requestedUrl, message);
        this.requestedUrl = requestedUrl;
        this.status = status;
        this.finalUrl = finalUrl;
    }

    public int getStatus() {
        return status;
    }

    public String getRequestedUrl() {
        return requestedUrl;
    }

    public String getFinalUrl() {
        return finalUrl;
    }
}
