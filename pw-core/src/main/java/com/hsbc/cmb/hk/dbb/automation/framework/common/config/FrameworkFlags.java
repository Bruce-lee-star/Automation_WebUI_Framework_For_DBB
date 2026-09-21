package com.hsbc.cmb.hk.dbb.automation.framework.common.config;

import java.util.Locale;

/**
 * 框架自有开关的<b>确定性</b>解析门面 —— D2-1 附带治理。
 *
 * <p><b>背景：两个实测踩到的坑（修复记录，勿回退）</b>
 * <ol>
 *   <li><b>键名必须与业务 / 上游配置隔离</b>：曾在 core 引入裸键
 *       {@code api.traffic.log.enabled}，结果 {@code System.getProperty} 与
 *       {@code System.getenv} 均为 {@code null} 的情况下，经 {@link ConfigSource}
 *       竟解析出 {@code true}；而 {@code api.something.else.enabled} 与加前缀的
 *       {@code framework.api.traffic.log.enabled} 都正确回落默认值。
 *       ⇒ 框架自有开关<b>一律加 {@link #PREFIX}</b>，避免撞车。</li>
 *   <li><b>SPI 合并源会缓存属性快照，导致开关不可预期</b>：实测
 *       {@code setProperty(k,"true")} 之后再 {@code clearProperty(k)}，
 *       {@link ConfigSource} 的 Serenity SPI 源<b>仍返回旧值 "true"</b>。
 *       对"默认必须关闭"的安全 / 诊断开关，这种非确定性不可接受。
 *       ⇒ 本门面<b>只认系统属性与环境变量</b>，不经过 SPI，行为确定。</li>
 * </ol>
 *
 * <p><b>适用范围</b>：框架自身的开关位（诊断 / 安全 / 基础设施）。
 * 业务配置（如 {@code environment}、数据源地址等仍可能写在 {@code serenity.conf} 里）
 * 请继续用 {@link ConfigSource} —— 那里需要 SPI 合并源的能力。
 *
 * <p>优先级：系统属性 → 环境变量 → 默认值（与 {@link ConfigSource} 前两级一致）。
 */
public final class FrameworkFlags {

    /** 框架自有开关的统一键前缀。 */
    public static final String PREFIX = "framework.";

    private FrameworkFlags() {
        // 纯静态门面，禁止实例化
    }

    /**
     * 解析布尔开关。
     *
     * @param key          开关键（建议以 {@link #PREFIX} 开头）
     * @param defaultValue 未配置时的默认值
     */
    public static boolean isEnabled(String key, boolean defaultValue) {
        String v = resolve(key, null);
        return v == null ? defaultValue : Boolean.parseBoolean(v);
    }

    /**
     * 解析字符串开关。
     *
     * @param key          开关键（建议以 {@link #PREFIX} 开头）
     * @param defaultValue 未配置时的默认值
     */
    public static String resolve(String key, String defaultValue) {
        String fromSys = System.getProperty(key);
        if (fromSys != null && !fromSys.trim().isEmpty()) {
            return fromSys.trim();
        }
        String fromEnv = System.getenv(toEnvKey(key));
        if (fromEnv != null && !fromEnv.trim().isEmpty()) {
            return fromEnv.trim();
        }
        return defaultValue;
    }

    /** 配置键 → 环境变量名：大写，点/连字符转下划线（与 {@link ConfigSource#toEnvKey} 同源）。 */
    public static String toEnvKey(String key) {
        return key.toUpperCase(Locale.ROOT).replace('.', '_').replace('-', '_');
    }
}
