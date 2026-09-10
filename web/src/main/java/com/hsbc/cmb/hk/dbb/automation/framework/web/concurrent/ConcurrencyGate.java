package com.hsbc.cmb.hk.dbb.automation.framework.web.concurrent;

import com.hsbc.cmb.hk.dbb.automation.framework.web.config.WebFrameworkConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
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
 * </ul>
 * </p>
 *
 * <p>接入点（runner 无关）：在 scenario 建立登录后的生命周期 hook 调用 {@link #enter(ConcurrencyKeyResolver)}
 * （经解析器链取 key），返回的 {@link ConcurrencyScope} 在 try-with-resources 中配对 release；未启用时恒为 no-op。</p>
 */
public final class ConcurrencyGate {

    private static final Logger LOGGER = LoggerFactory.getLogger(ConcurrencyGate.class);

    /** 活跃闸门上限：超出时对"完全空闲"的闸门做 best-effort 淘汰，防止海量 key 下 Map 无界增长。 */
    private static final int MAX_GATES = 4096;

    private static final ConcurrentHashMap<ConcurrencyPartitionKey, Semaphore> GATES = new ConcurrentHashMap<>();
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
            } catch (NumberFormatException ignored) {
                // 非法值回落默认解析
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
        Semaphore gate = GATES.computeIfAbsent(key, k -> new Semaphore(perKeyPermits(), true));
        boolean wouldBlock = gate.availablePermits() == 0;
        if (wouldBlock) {
            SERIALIZED_IDENTITY_COUNT.incrementAndGet();
            LOGGER.info("[concurrency-gate] identity {} serialized (blocked) on {}", key, Thread.currentThread().getName());
        }
        LOGGER.debug("[concurrency-gate] acquire {} on {} (permits left={})",
                key, Thread.currentThread().getName(), gate.availablePermits());
        gate.acquireUninterruptibly();
        ENTER_COUNT.incrementAndGet();
    }

    /**
     * scenario 结束（含失败）时配对调用；key==null 直接返回。必须放在 finally 中确保释放不泄漏。
     */
    public static void release(ConcurrencyPartitionKey key) {
        if (!isEnabled() || key == null) {
            return;
        }
        Semaphore gate = GATES.get(key);
        if (gate != null) {
            gate.release();
        }
        if (GATES.size() > MAX_GATES) {
            evictIdleGates();
        }
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

    /** best-effort：Map 超过上限时移除"完全空闲"（许可已全部归还）的闸门以收敛内存。 */
    private static void evictIdleGates() {
        for (Map.Entry<ConcurrencyPartitionKey, Semaphore> e : GATES.entrySet()) {
            if (GATES.size() <= MAX_GATES) {
                break;
            }
            if (e.getValue().availablePermits() == perKeyPermits()) {
                GATES.remove(e.getKey(), e.getValue());
            }
        }
    }

    /** 观测快照：进入次数 / 被串行化的身份数 / 活跃闸门数（不同身份键数）。 */
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
