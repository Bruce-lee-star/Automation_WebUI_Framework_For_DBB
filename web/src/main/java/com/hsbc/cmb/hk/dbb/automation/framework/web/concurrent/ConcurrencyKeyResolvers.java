package com.hsbc.cmb.hk.dbb.automation.framework.web.concurrent;

import java.util.Optional;

/**
 * 解析器链与默认链构造。
 *
 * <p>{@link #chain} 返回的解析器按序尝试各子解析器，首个非 empty 即返回（短路）；全部 empty 则 empty。
 * 默认链：{@link TagOverrideKeyResolver#defaultSource()}（命中即覆盖）→ {@link LoginIdentityKeyResolver#defaultSource()}（自动推导）。</p>
 */
public final class ConcurrencyKeyResolvers {

    private ConcurrencyKeyResolvers() {
    }

    /** 按序组合多个解析器；任一为 null 跳过。 */
    public static ConcurrencyKeyResolver chain(ConcurrencyKeyResolver... resolvers) {
        ConcurrencyKeyResolver[] copy = resolvers == null ? new ConcurrencyKeyResolver[0] : resolvers;
        return () -> {
            for (ConcurrencyKeyResolver r : copy) {
                if (r == null) {
                    continue;
                }
                Optional<ConcurrencyPartitionKey> k = r.resolve();
                if (k.isPresent()) {
                    return k;
                }
            }
            return Optional.empty();
        };
    }

    /** 默认解析器链（tag 覆盖优先，回退自动推导）。 */
    public static ConcurrencyKeyResolver defaultChain() {
        return chain(TagOverrideKeyResolver.defaultSource(), LoginIdentityKeyResolver.defaultSource());
    }
}
