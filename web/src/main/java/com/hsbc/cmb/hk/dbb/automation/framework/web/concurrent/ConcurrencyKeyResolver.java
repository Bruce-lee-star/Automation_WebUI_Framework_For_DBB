package com.hsbc.cmb.hk.dbb.automation.framework.web.concurrent;

import java.util.Optional;

/**
 * 并发分区键解析器（runner 无关）。
 *
 * <p>将"当前 scenario 的身份"翻译为 {@link ConcurrencyPartitionKey}；无身份（只读 / 无登录场景）
 * 返回 {@link Optional#empty()}，框架据此不进互斥 Map（见 {@link ConcurrencyGate}）。</p>
 *
 * <p>实现应线程安全且不可抛出裸 NPE：解析失败应返回 empty 或抛语义化异常。</p>
 */
public interface ConcurrencyKeyResolver {

    /**
     * 解析当前 scenario 的并发分区键。
     *
     * @return 分区键（存在）；无身份 / 无法解析时返回 empty
     */
    Optional<ConcurrencyPartitionKey> resolve();
}
