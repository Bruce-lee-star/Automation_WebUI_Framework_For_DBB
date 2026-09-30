package com.hsbc.cmb.hk.dbb.automation.framework.common.route;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Route V2 MONITOR 断言失败快照（核心层 DTO，web 侧报告用）。
 *
 * <p>字段对齐 {@code CapturedExchange} 的定案状态：{@code actualStatus} 为 null 表示
 * 未等到响应（配合 {@code timedOut=true} 说明超时），否则为真实响应码；{@code bodyFailures}
 * 为 body 级断言（jsonPath / bodyContains / bodyRegex / formField）的失败明细，空列表表示
 * 通过或未配置 body 断言。不可变：构造后所有字段不可再修改。
 *
 * @param pattern      命中的规则 pattern（老版 DSL 的 api("xxx") 写法）
 * @param method       请求方法（GET / POST / ...）
 * @param url          请求完整 URL
 * @param expectStatus 期望状态码（未配置为 null）
 * @param actualStatus 实际响应状态码（未等到响应为 null）
 * @param bodyFailures body 断言失败明细（不可变，空列表=无 body 失败）
 * @param timedOut     是否在 monitor 超时窗口内未等到响应
 * @param timeoutMs    该规则配置的 monitor 超时（毫秒；0=永不超时）
 */
public record RouteAssertionFailure(String pattern, String method, String url,
                                      Integer expectStatus, Integer actualStatus,
                                      List<String> bodyFailures, boolean timedOut, long timeoutMs) {

    /**
     * 防御性拷贝构造：bodyFailures 不可变快照，杜绝调用方（IO 线程产物）与报告方（业务线程）
     * 之间共享可变列表。
     */
    public RouteAssertionFailure {
        bodyFailures = bodyFailures == null || bodyFailures.isEmpty()
                ? List.of()
                : Collections.unmodifiableList(new ArrayList<>(bodyFailures));
    }

    /** 防御性 getter：返回不可变副本，杜绝调用方修改内部状态（EI_EXPOSE_REP）。 */
    @Override
    public List<String> bodyFailures() {
        return List.copyOf(bodyFailures);
    }

    /** 单行摘要（供日志/报告首行展示）。 */
    public String summary() {
        if (timedOut) {
            return "[timeout] " + method + " " + url + " (pattern=" + pattern
                    + ", expect=" + expectStatus + ", timeoutMs=" + timeoutMs + ")";
        }
        StringBuilder sb = new StringBuilder(96);
        sb.append("[status=").append(actualStatus).append(", expect=").append(expectStatus).append(']')
                .append(' ').append(method).append(' ').append(url)
                .append(" (pattern=").append(pattern).append(')');
        if (!bodyFailures.isEmpty()) {
            sb.append(" bodyFailures=").append(bodyFailures);
        }
        return sb.toString();
    }
}
