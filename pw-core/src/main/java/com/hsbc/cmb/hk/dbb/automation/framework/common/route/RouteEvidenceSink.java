package com.hsbc.cmb.hk.dbb.automation.framework.common.route;

/**
 * Route 命中证据上报 SPI —— 由框架公共层定义，实现在<b>上层模块</b>（如 pw-web-ui 的 Serenity 实现）。
 *
 * <p><b>为什么是 SPI 而不是直接写报告</b>：route 内核（pw-route）不得依赖任何报告框架，否则内核被
 * Serenity 绑架；而"命中证据要进报告"又是框架职责，不能落到业务/测试层（否则每个用例都要自己写）。
 * 故内核只在命中处调用本接口，落到哪里由实现决定 —— 与 {@link RouteLifecycle}/{@code RouteLifecycleRegistry}
 * 同一套路（SPI + {@code META-INF/services} 注册，实现缺失即静默降级）。</p>
 *
 * <p><b>线程契约</b>：{@link #record} 会在 Playwright 事件线程或 IO 池线程被调用，
 * 实现<b>必须线程安全</b>，且<b>不得</b>在这些线程上直接写报告（Serenity 只认测试主线程）——
 * 正确做法是入队，再由 {@link #flush()} 在主线程批量写入（参照 master 的 {@code SerenityReporter}）。</p>
 */
public interface RouteEvidenceSink {

    /**
     * 记录一次路由命中（任意线程可调）。
     *
     * @param operation 能力位（DELAY / MODIFY_REQUEST / MOCK / MONITOR；CAPTURE 随 MONITOR 上报）
     * @param url       实际请求 URL
     * @param detail    详情（由内核渲染，含脱敏后的请求/响应摘要）
     */
    void record(String operation, String url, String detail);

    /**
     * 在<b>测试主线程</b>把已入队的记录批量写入报告（默认空实现：不需要 flush 的实现可忽略）。
     */
    default void flush() {
        // 默认无操作：多数实现是"入队 + 由框架在场景收尾 flush"，但并非所有实现都需要
    }
}
