package com.hsbc.cmb.hk.dbb.automation.framework.common.logging;

import ch.qos.logback.classic.pattern.MessageConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;

import com.hsbc.cmb.hk.dbb.automation.framework.common.config.FrameworkFlags;
import com.hsbc.cmb.hk.dbb.automation.framework.common.security.SensitiveDataSanitizer;

/**
 * 日志出口强制脱敏 Converter —— D2-4。
 *
 * <p><b>要解决的问题</b>：此前脱敏"靠调用方自觉" —— 谁打印日志谁负责先 {@code sanitize}，
 * 只要有一处忘记（新增日志、第三方库、异常信息拼接），敏感值就直接落到控制台与文件里。
 * 在金融级场景，这种"靠人不出错"的防线不成立。
 *
 * <p><b>本类的做法</b>：接管 logback 的 {@code msg} 转换词（在 {@code logback.xml} 中
 * {@code <conversionRule conversionWord="msg" converterClass="..."/>}），
 * 使<b>所有</b>经 {@code %msg} / {@code %m} 输出的日志消息在写出前统一经
 * {@link SensitiveDataSanitizer#sanitizeFreeText(String)} —— 调用方<b>无法绕过</b>。
 *
 * <p><b>与"调用方自觉"的区别 vs 运维总开关</b>：
 * <ul>
 *   <li>本 Converter <b>默认开启</b>，业务代码无从选择，这就是"出口强制"；</li>
 *   <li>{@link #ENABLED_KEY} 是<b>运维级</b>总开关（默认 true），仅在极端排障 /
 *       性能应急时由运维统一关闭 —— 它不属于"调用方自觉"，关闭需显式动作且影响全进程。</li>
 * </ul>
 *
 * <p><b>健壮性</b>：脱敏本身就是正则处理，若其抛异常，本类<b>回退为原始消息</b>并继续输出 ——
 * 脱敏设施绝不能反过来让日志丢失（排障时日志丢失比泄露更致命）。
 *
 * <p><b>注意（防递归）</b>：本 Converter 内部及其调用的脱敏逻辑<b>不得打印日志</b>，
 * 否则会形成"打印 → 脱敏 → 再打印"的递归。当前 {@code sanitizeFreeText} 为纯函数、无日志。
 *
 * @apiNote 需在 logback 配置中注册；仅作用于 {@code msg}（消息体），不改写异常栈 {@code %ex}。
 */
public class SanitizingMessageConverter extends MessageConverter {

    /**
     * 运维级总开关（默认 <b>true</b> = 强制脱敏）。
     * <p>仅在性能应急等特殊场景由运维统一关闭；业务代码不应依赖它。
     */
    public static final String ENABLED_KEY = "framework.log.sanitize.enabled";

    @Override
    public String convert(ILoggingEvent event) {
        String message = super.convert(event);
        if (message == null || message.isEmpty()) {
            return message;
        }
        if (!FrameworkFlags.isEnabled(ENABLED_KEY, true)) {
            return message;
        }
        try {
            return SensitiveDataSanitizer.sanitizeFreeText(message);
        } catch (Exception e) {
            //  脱敏失败也必须把日志打出来：丢失日志比泄露更妨碍排障（且不因脱敏引入新故障）
            return message;
        }
    }
}
