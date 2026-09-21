package com.hsbc.cmb.hk.dbb.automation.framework.common.config;

/**
 * 统一配置键元数据规范（key / 默认 / 描述 三元组）。
 *
 * <p>Web 与 API 两侧配置项均以本记录描述其键名、默认值与用途说明，确保两域配置口径一致、
 * 命名可发现：{@code key} 即配置源中的实际键名，{@code defaultValue} 为键缺失时的兜底值，
 * {@code description} 为人工可读说明（供文档 / 审计 / 排查使用）。
 *
 * <p>解析由各域自行负责（Web 走 {@link ConfigSource#resolve(String, String)}，API 走 Typesafe Config），
 * 本记录仅承载不可变的元数据，不含任何解析 / 解密逻辑。构造期即强校验 {@code key} 与
 * {@code description} 非空，保证每个配置项都「可定位、可解读」。
 *
 * <p>本记录是 WEB-P1-7「配置体系收敛」抽出的共享元数据规范，取代原先散落在
 * {@code WebFrameworkConfig}（枚举常量）与 {@code ApiFrameworkConfig}（字面量）中不一致的携带方式。
 */
public record ConfigKey(String key, String defaultValue, String description) {

    /**
     * 构造并校验。
     *
     * @param key         配置键名（非空、非空白）
     * @param defaultValue 键缺失时的默认值（null 视为空串）
     * @param description  人工可读说明（非空、非空白）
     * @throws IllegalArgumentException 当 {@code key} 或 {@code description} 为 null/空白
     */
    public ConfigKey {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("ConfigKey.key must not be blank");
        }
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException("ConfigKey.description must not be blank for key=" + key);
        }
        if (defaultValue == null) {
            defaultValue = "";
        }
    }
}
