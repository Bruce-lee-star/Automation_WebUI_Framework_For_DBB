package com.hsbc.cmb.hk.dbb.automation.framework.web.route.capture;

/** NetworkExchange 旁路诊断指标快照，不替代既有 CaptureMetrics。 */
public record NetworkExchangeMetrics(
        long terminalCount,
        long networkFailures,
        long correlationTimeouts,
        long bodyAvailable,
        long bodyEmpty,
        long bodyNotRequested,
        long bodyUnavailable,
        long evictedCount,
        long missingFinishCount
) {
}
