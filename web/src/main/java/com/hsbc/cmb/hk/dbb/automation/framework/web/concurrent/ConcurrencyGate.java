package com.hsbc.cmb.hk.dbb.automation.framework.web.concurrent;

import com.hsbc.cmb.hk.dbb.automation.framework.web.config.WebFrameworkConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicLong;

/**
 * SSO 感知并发闸门（runner 无关、串行安全）：按 {@link ConcurrencyPartitionKey} 对 scenario 做互斥分区。
 *
 * <p>语义（企业级约束）：
 * <ul>
 *   <li>相同身份（同 key）→ 串行；不同身份 → 并行；无身份（key==null）→ 直接放行（不进 Map）。</li>
 *   <li>总开关 {@code CONCURRENCY_PARTITION_ENABLED} 默认 false → {@link #acquire}/{@link #release} 全为 no-op，行为零回归。</li>
 *   <li>每 key 持有独立 {@link Semaphore}（公平、许可数取自 {@code CONCURRENCY_PARTITION_PER_KEY_PERMITS}，默认 1）。</li>
 *   <li>{@link #acquire} 用 {@link Semaphore#acquireUninterruptibly()} 避免吞掉 / 打断测试线程中断策略；
 *       {@link #release} 必须放在调用方清理收口的 finally 中，与 acquire 严格配对（见 {@link ConcurrencyScope}）。</li>
 *   <li>{@link ConcurrentHashMap} 不允许 null key —— 已用 {@code if (key == null) return} 规避。</li>
 *   <li>条目随<b>最后一个持有者</b> {@link #release} 即移除 → Map 大小 == 在途身份数（天然有界，无需淘汰机制）。</li>
 * </ul>
 * </p>
 *
 * <p>接入点（runner 无关）：在 scenario 建立登录后的生命周期 hook 调用 {@link #enter(ConcurrencyKeyResolver)}
 * （经解析器链取 key），返回的 {@link ConcurrencyScope} 在 try-with-resources 中配对 release；未启用时恒为 no-op。</p>
 */
public final class ConcurrencyGate {

    private static final Logger LOGGER = LoggerFactory.getLogger(ConcurrencyGate.class);

    /**
     * 活跃闸门表：key → 闸门条目。
     *
     * <p><b>条目生命周期（2026-09-17 评审修复）</b>：条目在其<b>最后一个持有者释放时立即移除</b>，
     * 故表大小 == 在途身份数（受并发度约束），天然有界 —— 原先的 {@code MAX_GATES=4096} + 空闲淘汰
     * 机制随之删除：它只因"条目从不移除"才需要，且其判据（许可是否全空闲）有真实的正确性缺陷
     * （见 {@link GateEntry}）。
     */
    private static final ConcurrentHashMap<ConcurrencyPartitionKey, GateEntry> GATES = new ConcurrentHashMap<>();

    /**
     * 闸门条目：信号量 + 「在途持有计数」。
     *
     * <p><b>为什么必须计持有数（评审修复，对应本轮 web 待复核项 1）</b>：原实现直接存 {@link Semaphore}，
     * 淘汰以「许可是否全空闲」为判据 —— 这在 {@code acquire} 的
     * {@code computeIfAbsent(取到闸门) → acquireUninterruptibly(占用许可)} 之间存在窗口：闸门刚被取到、
     * 许可尚未被占用时看起来"完全空闲"而被淘汰；随后另一线程对同一 key 会创建<b>新</b>闸门并立即取得许可，
     * 而先前线程仍在旧闸门内 → <b>同一身份键被并发进入</b>（串行化保证破裂）。更糟的是
     * {@code release(key)} 按 key 重新查表：条目被淘汰/替换时，释放会记到<b>别的</b>闸门上
     * （许可虚增，进一步放宽互斥）。
     *
     * <p>修复方式：把「取用（lookup）+ 持有计数递增」放进同一次 {@code compute}（对同一 key 原子），
     * 释放侧对称地放进 {@code computeIfPresent}（归还许可 + 递减，归零则移除）。于是
     * ① <b>在途闸门不可能被误判为空闲</b>（判据是持有数，而非许可余量）；
     * ② 释放永远作用于<b>自己那次取用所在的条目</b>，不存在错记。
     * {@code holds} 仅在 Map 的 per-key 原子段内读写，故用普通 {@code int}。
     */
    private static final class GateEntry {
        private final Semaphore semaphore;
        private int holds;

        GateEntry(int permits) {
            this.semaphore = new Semaphore(permits, true);
        }
    }
    private static final AtomicLong ENTER_COUNT = new AtomicLong();
    private static final AtomicLong SERIALIZED_IDENTITY_COUNT = new AtomicLong();

    private ConcurrencyGate() {
    }

    /** 闸门是否启用（读取配置，运行期可经系统属性 / serenity.conf 切换）。 */
    public static boolean isEnabled() {
        return isEnabledLive();
    }

    /**
     * 实时解析启用开关：优先读 {@code System.getProperty}（命令行 {@code -D} 与单测即时切换均生效），
     * 回退 {@link WebFrameworkConfig}（经 Serenity 配置体系，含 serenity.conf）。
     * 双重读取规避 Serenity {@code SystemEnvironmentVariables} 单例缓存导致的运行期不可见问题。
     */
    private static boolean isEnabledLive() {
        String override = System.getProperty(WebFrameworkConfig.CONCURRENCY_PARTITION_ENABLED.getKey());
        if (override != null) {
            return override.equalsIgnoreCase("true") || override.equalsIgnoreCase("yes") || override.equalsIgnoreCase("1");
        }
        return WebFrameworkConfig.CONCURRENCY_PARTITION_ENABLED.getBooleanValue();
    }

    private static int perKeyPermits() {
        return perKeyPermitsLive();
    }

    /**
     * 实时解析每 key 许可数：优先 {@code System.getProperty}，回退 {@link WebFrameworkConfig}（默认 1）。
     */
    private static int perKeyPermitsLive() {
        String override = System.getProperty(WebFrameworkConfig.CONCURRENCY_PARTITION_PER_KEY_PERMITS.getKey());
        if (override != null) {
            try {
                return Math.max(1, Integer.parseInt(override.trim()));
            } catch (NumberFormatException e) {
                // 非法值回落默认解析；配置错误必须可见，不得静默（D7-3）
                LOGGER.warn("[ConcurrencyGate] invalid override '{}' = '{}', fallback to default",
                        WebFrameworkConfig.CONCURRENCY_PARTITION_PER_KEY_PERMITS.getKey(), override);
            }
        }
        int p = WebFrameworkConfig.CONCURRENCY_PARTITION_PER_KEY_PERMITS.getIntValue();
        return p < 1 ? 1 : p;
    }

    /**
     * 进入 scenario 时调用；key==null 直接返回（无身份场景不参与互斥）。
     * 会阻塞直至获得该身份许可（相同身份被串行化）。
     */
    public static void acquire(ConcurrencyPartitionKey key) {
        if (!isEnabled() || key == null) {
            return;
        }
        int permits = perKeyPermits();
        //  取用与持有计数递增必须在同一次 compute 内（对该 key 原子）：否则"刚取到、尚未占许可"的闸门
        //  会被并发淘汰误判为空闲（详见 GateEntry）。
        GateEntry entry = GATES.compute(key, (k, existing) -> {
            GateEntry e = (existing == null) ? new GateEntry(permits) : existing;
            e.holds++;
            return e;
        });
        boolean wouldBlock = entry.semaphore.availablePermits() == 0;
        if (wouldBlock) {
            SERIALIZED_IDENTITY_COUNT.incrementAndGet();
            LOGGER.info("[concurrency-gate] identity {} serialized (blocked) on {}", key, Thread.currentThread().getName());
        }
        LOGGER.debug("[concurrency-gate] acquire {} on {} (permits left={})",
                key, Thread.currentThread().getName(), entry.semaphore.availablePermits());
        entry.semaphore.acquireUninterruptibly();
        ENTER_COUNT.incrementAndGet();
    }

    /**
     * scenario 结束（含失败）时配对调用；key==null 直接返回。必须放在 finally 中确保释放不泄漏。
     */
    public static void release(ConcurrencyPartitionKey key) {
        if (!isEnabled() || key == null) {
            return;
        }
        //  与 acquire 对称：在同一 per-key 原子段内「归还许可 + 递减持有数」，归零即移除条目
        //  （最后一个持有者离开 → 无人持有 → 无需保留）。不再按 key 重新查表释放：旧实现会在条目被
        //  淘汰/替换时把许可记到别的闸门上（许可虚增）。
        GATES.computeIfPresent(key, (k, entry) -> {
            entry.semaphore.release();
            entry.holds--;
            return entry.holds <= 0 ? null : entry;
        });
    }

    /**
     * 进入并持有 scope：acquire(key) 后返回 {@link ConcurrencyScope}，close 时配对 release。
     * key==null 时返回 no-op scope（不抛异常、不进 Map）。
     */
    public static ConcurrencyScope enter(ConcurrencyPartitionKey key) {
        acquire(key);
        return new ConcurrencyScope(key);
    }

    /**
     * 经解析器链解析 key 并进入；解析为空（无身份）时返回 no-op scope。
     */
    public static ConcurrencyScope enter(ConcurrencyKeyResolver resolver) {
        Objects.requireNonNull(resolver, "resolver must not be null");
        Optional<ConcurrencyPartitionKey> key = resolver.resolve();
        return enter(key.orElse(null));
    }

    /** 观测快照：进入次数 / 被串行化的身份数 / 活跃闸门数（= <b>在途</b>身份数，条目随最后一个持有者释放即移除）。 */
    public static ConcurrencyGateStats stats() {
        return new ConcurrencyGateStats(ENTER_COUNT.get(), SERIALIZED_IDENTITY_COUNT.get(), GATES.size());
    }

    /** 闸门运行期观测快照（不可变）。 */
    public static final class ConcurrencyGateStats {
        public final long enterCount;
        public final long serializedIdentityCount;
        public final int activeGates;

        public ConcurrencyGateStats(long enterCount, long serializedIdentityCount, int activeGates) {
            this.enterCount = enterCount;
            this.serializedIdentityCount = serializedIdentityCount;
            this.activeGates = activeGates;
        }

        @Override
        public String toString() {
            return "ConcurrencyGateStats{enterCount=" + enterCount
                    + ", serializedIdentityCount=" + serializedIdentityCount
                    + ", activeGates=" + activeGates + '}';
        }
    }
}
