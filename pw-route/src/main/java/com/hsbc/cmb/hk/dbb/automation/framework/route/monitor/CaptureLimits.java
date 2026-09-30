package com.hsbc.cmb.hk.dbb.automation.framework.route.monitor;

/**
 * CAPTURE 采集的默认上限常量（单一事实来源）。
 *
 * <p>所有上限都是<b>防泄漏防线</b>而非业务语义：采集是尽力而为（best-effort），
 * 超限丢弃并计数，绝不阻塞事件线程、绝不让运行时内存随采集无限增长。
 */
final class CaptureLimits {

    private CaptureLimits() {
    }

    /** 响应体采集截断上限默认值（字节，64 KiB）。 */
    static final int DEFAULT_BODY_LIMIT_BYTES = 64 * 1024;

    /** 请求体预览截断上限（字节，2 KiB；与 CapturedExchange 的 postDataPreview 一致）。 */
    static final int REQUEST_BODY_PREVIEW_MAX = 2048;

    /** per-context 采集队列上限默认值（条；超出丢弃新记录并计数）。 */
    static final int DEFAULT_MAX_CAPTURED = 1000;

    /** 未响应请求的超时定案窗口默认值（毫秒，30s；与 monitor 默认超时一致）。 */
    static final long DEFAULT_TIMEOUT_MS = 30_000L;
}
