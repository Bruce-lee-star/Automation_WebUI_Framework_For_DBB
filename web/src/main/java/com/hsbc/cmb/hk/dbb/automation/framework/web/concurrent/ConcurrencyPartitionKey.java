package com.hsbc.cmb.hk.dbb.automation.framework.web.concurrent;

import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * 不可变并发分区键：指纹化一组身份维度（environment / username / tenant / role / locale …）。
 *
 * <p>equals/hashCode 基于<b>规范化后</b>的维度元组（维度名小写、去空白；值仅去空白、保留大小写以兼容
 * 大小写敏感的 IdP 用户名）。相同身份 → 同一键 → 同一道互斥闸门；维度顺序无关。</p>
 */
public final class ConcurrencyPartitionKey {

    private final Map<String, String> dimensions; // 规范化（维度名小写、按字典序）、不可变
    private final int hash;

    private ConcurrencyPartitionKey(Map<String, String> normalized) {
        this.dimensions = Collections.unmodifiableMap(new TreeMap<>(normalized));
        this.hash = this.dimensions.hashCode();
    }

    /**
     * 由原始维度映射构建（至少含一个非空值维度）。
     * 空 / 全空值映射、null 维度名或空白维度名抛 {@link IllegalArgumentException}。
     */
    public static ConcurrencyPartitionKey of(Map<String, String> rawDimensions) {
        if (rawDimensions == null || rawDimensions.isEmpty()) {
            throw new IllegalArgumentException("ConcurrencyPartitionKey requires at least one dimension");
        }
        TreeMap<String, String> normalized = new TreeMap<>();
        for (Map.Entry<String, String> e : rawDimensions.entrySet()) {
            if (e.getKey() == null) {
                throw new IllegalArgumentException("dimension name must not be null");
            }
            String name = e.getKey().trim().toLowerCase(Locale.ROOT);
            if (name.isEmpty()) {
                throw new IllegalArgumentException("dimension name must not be blank");
            }
            if (e.getValue() == null || e.getValue().trim().isEmpty()) {
                continue; // 空值维度不参与指纹
            }
            normalized.put(name, e.getValue().trim());
        }
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("ConcurrencyPartitionKey requires at least one non-blank dimension value");
        }
        return new ConcurrencyPartitionKey(normalized);
    }

    /** 不可变维度映射（维度名小写、按字典序）。 */
    public Map<String, String> dimensions() {
        return dimensions;
    }

    /** 人类可读指纹（env=sit1|username=alice），用于日志与诊断。 */
    public String fingerprint() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : dimensions.entrySet()) {
            if (sb.length() > 0) {
                sb.append('|');
            }
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.toString();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ConcurrencyPartitionKey)) {
            return false;
        }
        return dimensions.equals(((ConcurrencyPartitionKey) o).dimensions);
    }

    @Override
    public int hashCode() {
        return hash;
    }

    @Override
    public String toString() {
        return "ConcurrencyPartitionKey{" + fingerprint() + '}';
    }
}
