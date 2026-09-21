package com.hsbc.cmb.hk.dbb.automation.framework.common.security;

/**
 * 值级敏感数据识别器（SPI 扩展点）。
 *
 * <p>与 {@link SensitiveDataSanitizer} 的「字段名白名单」互补：按<b>值本身的形态</b>
 * 识别敏感数据，覆盖字段名白名单漏网的场景——例如 {@code note} 字段里出现的银行卡号 /
 * IBAN / HKID / 信用卡轨道数据。字段名白名单只能拦「叫 password 的字段」，无法拦
 * 「叫 note 但内容是卡号」的字段，值级识别正是补这个洞。
 *
 * <p><b>业务扩展</b>：通过 JDK {@link java.util.ServiceLoader} 机制注册自定义识别器——
 * 在 {@code META-INF/services/com.hsbc.cmb.hk.dbb.automation.framework.common.security.SensitiveValueRecognizer}
 * 中登记实现类全限定名即可，无需改框架代码。内置识别器见 {@code BuiltinValueRecognizers}。
 */
public interface SensitiveValueRecognizer {

    /**
     * 识别器名称（配置 {@code sensitive.data.value.recognizers} 与诊断用，
     * 如 {@code PAN} / {@code IBAN} / {@code HKID} / {@code TRACK}）。
     */
    String name();

    /**
     * 判定给定原始值是否应被脱敏。
     *
     * @param value 待判定原始值（可能含空白 / 分隔符）；实现应容忍 null / 空
     * @return true 表示该值应被脱敏
     */
    boolean recognizes(String value);
}
