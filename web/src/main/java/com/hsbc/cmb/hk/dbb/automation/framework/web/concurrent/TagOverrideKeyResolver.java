package com.hsbc.cmb.hk.dbb.automation.framework.web.concurrent;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * 显式覆盖解析器：特殊 scenario 通过 tag 覆盖自动推导。
 *
 * <p>支持两种 tag：
 * <ul>
 *   <li>{@code @sso=ENV:user:tenant} —— 按位置映射到 environment/username/tenant/role/locale；</li>
 *   <li>{@code @concurrencyKey=env=SIT1;username=alice;tenant=t1} —— 显式维度键值对。</li>
 * </ul>
 * 命中即返回；否则返回 empty，交由解析器链回退到 {@link LoginIdentityKeyResolver}。</p>
 */
public final class TagOverrideKeyResolver implements ConcurrencyKeyResolver {

    public static final String TAG_SSO = "sso";
    public static final String TAG_CONCURRENCY_KEY = "concurrencyKey";

    private static final List<String> SSO_POSITIONAL = List.of("environment", "username", "tenant", "role", "locale");

    private final Function<String, Optional<String>> tagValueResolver;

    public TagOverrideKeyResolver(Function<String, Optional<String>> tagValueResolver) {
        if (tagValueResolver == null) {
            throw new IllegalArgumentException("tagValueResolver must not be null");
        }
        this.tagValueResolver = tagValueResolver;
    }

    /** 默认来源：忽略所有 tag（运行时由 Serenity 集成注入真实 tag 读取器）。 */
    public static TagOverrideKeyResolver defaultSource() {
        return new TagOverrideKeyResolver(tag -> Optional.empty());
    }

    /** 显式构造：直接从给定 tag 映射读取（便于单测与集成）。 */
    public static TagOverrideKeyResolver fromTags(Map<String, String> tags) {
        Map<String, String> copy = Map.copyOf(tags);
        return new TagOverrideKeyResolver(tag -> Optional.ofNullable(copy.get(tag)));
    }

    @Override
    public Optional<ConcurrencyPartitionKey> resolve() {
        Optional<String> sso = tagValueResolver.apply(TAG_SSO);
        if (sso.isPresent()) {
            return parseSso(sso.get());
        }
        Optional<String> ck = tagValueResolver.apply(TAG_CONCURRENCY_KEY);
        if (ck.isPresent()) {
            return parseConcurrencyKey(ck.get());
        }
        return Optional.empty();
    }

    private static Optional<ConcurrencyPartitionKey> parseSso(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return Optional.empty();
        }
        String[] parts = raw.split(":");
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < parts.length && i < SSO_POSITIONAL.size(); i++) {
            String v = parts[i].trim();
            if (!v.isEmpty()) {
                m.put(SSO_POSITIONAL.get(i), v);
            }
        }
        return m.isEmpty() ? Optional.empty() : Optional.of(ConcurrencyPartitionKey.of(m));
    }

    private static Optional<ConcurrencyPartitionKey> parseConcurrencyKey(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return Optional.empty();
        }
        Map<String, String> m = new LinkedHashMap<>();
        for (String pair : raw.split(";")) {
            String[] kv = pair.split("=", 2);
            if (kv.length == 2) {
                String name = kv[0].trim();
                String val = kv[1].trim();
                if (!name.isEmpty() && !val.isEmpty()) {
                    m.put(name, val);
                }
            }
        }
        return m.isEmpty() ? Optional.empty() : Optional.of(ConcurrencyPartitionKey.of(m));
    }
}
