package com.hsbc.cmb.hk.dbb.automation.framework.web.concurrent;

import com.hsbc.cmb.hk.dbb.automation.framework.web.config.WebFrameworkConfig;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 自动推导解析器（默认来源）：从当前登录身份快照 {@link ConcurrencyIdentity} 读取维度值，
 * 维度集合取自 {@code CONCURRENCY_PARTITION_DIMENSIONS} 配置。业务零侵入。
 *
 * <p>若任一激活维度都取不到值（如未登录的只读场景），返回 empty → 不参与互斥。</p>
 */
public final class LoginIdentityKeyResolver implements ConcurrencyKeyResolver {

    private final Set<String> dimensions;
    private final Function<String, Optional<String>> dimensionValueResolver;

    public LoginIdentityKeyResolver(Set<String> dimensions,
                                    Function<String, Optional<String>> dimensionValueResolver) {
        if (dimensions == null || dimensions.isEmpty()) {
            throw new IllegalArgumentException("dimensions must not be empty");
        }
        if (dimensionValueResolver == null) {
            throw new IllegalArgumentException("dimensionValueResolver must not be null");
        }
        this.dimensions = dimensions.stream()
                .map(d -> d.trim().toLowerCase(Locale.ROOT))
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        this.dimensionValueResolver = dimensionValueResolver;
    }

    /** 默认来源：维度取自配置，值取自 {@link ConcurrencyIdentity}。 */
    public static LoginIdentityKeyResolver defaultSource() {
        return new LoginIdentityKeyResolver(parseDimensions(WebFrameworkConfig.CONCURRENCY_PARTITION_DIMENSIONS.getValue()),
                ConcurrencyIdentity::value);
    }

    @Override
    public Optional<ConcurrencyPartitionKey> resolve() {
        Map<String, String> m = new LinkedHashMap<>();
        for (String d : dimensions) {
            dimensionValueResolver.apply(d).ifPresent(v -> m.put(d, v));
        }
        if (m.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(ConcurrencyPartitionKey.of(m));
    }

    /** 解析维度集合配置（逗号分隔、小写、去空白；缺省 environment,username）。 */
    static Set<String> parseDimensions(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return Set.of("environment", "username");
        }
        return java.util.Arrays.stream(raw.split(","))
                .map(s -> s.trim().toLowerCase(Locale.ROOT))
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }
}
