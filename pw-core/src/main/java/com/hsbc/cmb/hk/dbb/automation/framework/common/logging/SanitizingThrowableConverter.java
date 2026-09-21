package com.hsbc.cmb.hk.dbb.automation.framework.common.logging;

import ch.qos.logback.classic.pattern.ThrowableProxyConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;

import com.hsbc.cmb.hk.dbb.automation.framework.common.config.FrameworkFlags;
import com.hsbc.cmb.hk.dbb.automation.framework.common.security.SensitiveDataSanitizer;

/**
 * 接管 logback 的 {@code ex} 转换词 —— 异常栈（含 caused-by / suppressed 链）在写出前
 * 统一经 {@link SensitiveDataSanitizer} 脱敏（C-2 / L-1）。
 *
 * <p>实现方式：覆写 {@link #convert(ILoggingEvent)}，对 {@code %ex} 的完整输出（异常类名 +
 * message + 全部栈帧）逐行脱敏。凭据（{@code password=...}、{@code token=...}）绝大多数经
 * 异常 message 出域，栈帧本身通常不含凭据；逐行处理对两者都安全。
 * 示例：{@code "Caused by: ...: password=s3cr3t"} → 输出 {@code password= ***[REDACTED]}。
 *
 * <p><b>L-3 健壮性约束</b>：与 {@code msg} 转换词不同，异常栈属于"高风险字段"。
 * 脱敏设施本身若抛异常，<b>禁止回退为原始栈</b>（否则明文凭据直接落盘），改为输出
 * {@code [LOG SUPPRESSED: sanitizer failure]}，宁可丢失栈细节也不泄露凭据。
 *
 * <p>同 {@link SanitizingMessageConverter}，受运维开关 {@code framework.log.sanitize.enabled}
 * （默认 true）控制；关闭时原样返回（属运维显式动作，影响全进程）。
 *
 * @apiNote 需在 logback 配置中注册 {@code <conversionRule conversionWord="ex" .../>}。
 */
public class SanitizingThrowableConverter extends ThrowableProxyConverter {

    /** L-3：脱敏失败时替代原始栈文本（绝不输出明文凭据）。 */
    private static final String SUPPRESSED = "[LOG SUPPRESSED: sanitizer failure]";

    @Override
    public String convert(ILoggingEvent event) {
        String raw = super.convert(event);
        if (raw == null || raw.isEmpty()) {
            return raw;
        }
        if (!FrameworkFlags.isEnabled(SanitizingMessageConverter.ENABLED_KEY, true)) {
            return raw;
        }
        try {
            return sanitizeThrowableLine(raw);
        } catch (Exception e) {
            // L-3：高风险字段必须抑制，回退原文 = 明文泄露
            return SUPPRESSED;
        }
    }

    /**
     * 实际脱敏入口（protected，可被测试子类覆写以模拟脱敏失败，验证 L-3 抑制路径）。
     * 对整段 %ex 输出逐行脱敏，覆盖异常 message 内嵌的 {@code key=value} 凭据。
     */
    protected String sanitizeThrowableLine(String in) {
        return SensitiveDataSanitizer.sanitizeLine(in);
    }

    /** 暴露 L-3 抑制标记，供测试断言。 */
    static String suppressedMarker() {
        return SUPPRESSED;
    }
}
