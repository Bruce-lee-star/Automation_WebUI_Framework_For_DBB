package com.hsbc.cmb.hk.dbb.automation.framework.web.concurrent;

import com.hsbc.cmb.hk.dbb.automation.framework.web.config.WebFrameworkConfig;
import com.hsbc.cmb.hk.dbb.automation.framework.web.exceptions.ConcurrencyGateTimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
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
 * <p>接入点（runner 无关）：调用方<b>显式构造</b> {@link ConcurrencyPartitionKey} 后调 {@link #acquire}
 * （生产上：登录/会话边界的 {@code sessionkey} 键、并发用例执行器的「用例行身份」键），并在收口 finally 中
 * 配对 {@link #release}；需要 RAII 时用 {@link #enter(ConcurrencyPartitionKey)} + try-with-resources。
 * 未启用时恒为 no-op。</p>
 *
 * <p><b>身份模型（2026-09-21 收口，评审 F-11）</b>：<b>不存在</b>「自动推导身份」的隐式通道 ——
 * 键一律由调用方显式构造。原先那条「登录后自动推导 environment/username」的解析链因无生产接入点而
 * 恒空转，容易让人误判闸门已生效，故整体删除。</p>
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

    /**
     * 本线程已获取且<b>尚未释放</b>的闸门键（FIFO）。
     *
     * <p><b>为什么必须有（实测死锁的根治）</b>：许可一旦泄漏（持有者未配对 release），同身份的所有后续
     * 场景会<b>永久</b> park 在该信号量上 —— 实测 4 个 worker 全部 park 在同一 {@code Semaphore$FairSync}
     * （栈 {@code ConcurrencyGate.acquire ← LogonGlue}），整个套件卡死无进展。
     * 本注册表让框架能在<b>场景收口</b>无条件释放本线程持有的全部闸门，与调用方是否记得 release 解耦；
     * 同时使 {@link #release} 能识别「未真正持有」（超时 fail-open / 重复释放）而拒绝虚增许可。
     */
    private static final ThreadLocal<Deque<ConcurrencyPartitionKey>> HELD_BY_THREAD = new ThreadLocal<>();

    private static void recordHeld(ConcurrencyPartitionKey key) {
        Deque<ConcurrencyPartitionKey> held = HELD_BY_THREAD.get();
        if (held == null) {
            held = new ArrayDeque<>();
            HELD_BY_THREAD.set(held);
        }
        held.addLast(key);
    }

    /** 移除本线程对该键的持有记录；返回是否确有记录（false = 本线程并未持有）。 */
    private static boolean forgetHeld(ConcurrencyPartitionKey key) {
        Deque<ConcurrencyPartitionKey> held = HELD_BY_THREAD.get();
        if (held == null) {
            return false;
        }
        boolean removed = held.removeLastOccurrence(key);
        if (held.isEmpty()) {
            HELD_BY_THREAD.remove();
        }
        return removed;
    }

    /** 归还许可 + 递减持有数（归零即移除条目）；不做持有校验，调用方负责语义。 */
    private static void doRelease(ConcurrencyPartitionKey key) {
        GATES.computeIfPresent(key, (k, entry) -> {
            entry.semaphore.release();
            entry.holds--;
            return entry.holds <= 0 ? null : entry;
        });
    }

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
        if (override != null && !override.trim().isEmpty()) {
            return parseTriState(override);
        }
        return parseTriState(WebFrameworkConfig.CONCURRENCY_PARTITION_ENABLED.getValue());
    }

    /**
     * 三态解析：{@code auto}（含空值，默认）→ 引擎级并行为真时启用；其它按 truthy 解析。
     *
     * <p>语义：<b>并行下同身份必须串行</b>，否则同一 sessionKey 被并发使用会互踩（SSO 互踢、
     * storageState 覆写）且现象随机；串行下闸门恒不阻塞（单线程不会自锁），故 auto 无副作用。
     */
    private static boolean parseTriState(String raw) {
        if (raw == null || raw.trim().isEmpty() || "auto".equalsIgnoreCase(raw.trim())) {
            return parallelExecutionEnabled();
        }
        String v = raw.trim();
        return v.equalsIgnoreCase("true") || v.equalsIgnoreCase("yes") || v.equalsIgnoreCase("1");
    }

    /** 引擎级并行是否开启（Cucumber / JUnit5）；用于 {@code auto} 判定。 */
    private static boolean parallelExecutionEnabled() {
        return truthy(System.getProperty("cucumber.execution.parallel.enabled"))
                || truthy(System.getProperty("junit.jupiter.execution.parallel.enabled"));
    }

    private static boolean truthy(String raw) {
        return raw != null && (raw.equalsIgnoreCase("true") || raw.equalsIgnoreCase("yes") || raw.equalsIgnoreCase("1"));
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
     * 实时解析「闸门等待超时后是否失败快」：优先 {@code System.getProperty}，回退
     * {@link WebFrameworkConfig}（默认 {@code true}）。
     *
     * <p>默认 fail-closed：把「未能取得闸门」如实判为该场景失败；设
     * {@code serenity.playwright.concurrent.partition.fail.closed=false} 可退回 fail-open 放行。</p>
     */
    private static boolean failClosedLive() {
        String override = System.getProperty(WebFrameworkConfig.CONCURRENCY_PARTITION_FAIL_CLOSED.getKey());
        if (override != null && !override.trim().isEmpty()) {
            return truthy(override);
        }
        return WebFrameworkConfig.CONCURRENCY_PARTITION_FAIL_CLOSED.getBooleanValue();
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

        //  有界等待（防整套卡死）：许可被泄漏时旧实现 acquireUninterruptibly 会让同身份的后续场景
        //  永久 park（实测 4 个 worker 全 park 在同一 Semaphore$FairSync → 套件无任何进展）。
        //  超时后的行为由 CONCURRENCY_PARTITION_FAIL_CLOSED 决定：默认 fail-closed（如实判失败），
        //  设 false 可退回 fail-open 放行（逃生舱）。
        long maxWait = maxWaitMs();
        boolean acquired;
        if (maxWait <= 0) {
            entry.semaphore.acquireUninterruptibly();
            acquired = true;
        } else {
            try {
                acquired = entry.semaphore.tryAcquire(maxWait, TimeUnit.MILLISECONDS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                acquired = false;
            }
        }
        if (!acquired) {
            //  本次未真正持有 → 回滚前面对 holds 的递增，避免条目永不移除（否则后续 release 语义被破坏）
            GATES.computeIfPresent(key, (k, e) -> {
                e.holds--;
                return e.holds <= 0 ? null : e;
            });
            String detail = String.format(
                    "identity %s waited %dms but did NOT acquire the gate on thread %s "
                            + "(通常为前序同身份场景未释放许可，请检查其收口)",
                    key, maxWait, Thread.currentThread().getName());
            if (failClosedLive()) {
                //  fail-closed（默认）：如实判失败 —— 避免"串行化静默失效 → SSO 互踢/随机 401 却仍可能绿"（F-11）
                LOGGER.error("[concurrency-gate] {}", detail);
                throw new ConcurrencyGateTimeoutException("[concurrency-gate] " + detail);
            }
            //  逃生舱（partition.fail.closed=false）：放行并打 ERROR，代价是本场景串行化失效
            LOGGER.error("[concurrency-gate] {} — proceeding WITHOUT gate "
                    + "(逃生舱已开启：本场景串行化失效，请确认无会话破坏风险)", detail);
            return;
        }
        recordHeld(key);
        ENTER_COUNT.incrementAndGet();
    }

    /** 进入闸门的最大等待（毫秒）：优先系统属性，回退配置；{@code <=0} 表示无限等待。 */
    private static long maxWaitMs() {
        String key = WebFrameworkConfig.CONCURRENCY_PARTITION_MAX_WAIT_MS.getKey();
        String override = System.getProperty(key);
        String raw = (override != null && !override.trim().isEmpty())
                ? override
                : WebFrameworkConfig.CONCURRENCY_PARTITION_MAX_WAIT_MS.getValue();
        try {
            return Long.parseLong(raw.trim());
        } catch (Exception e) {
            LOGGER.warn("[concurrency-gate] invalid '{}' = '{}', fallback to 600000ms", key, raw);
            return 600_000L;
        }
    }

    /**
     * 释放<b>本线程</b>持有的全部闸门（场景收口兜底；幂等，返回实际释放数）。
     *
     * <p><b>这是「许可泄漏 → 整套卡死」的根治点</b>：由框架在场景收尾<b>无条件</b>调用，
     * 与调用方（框架会话路径 / 测试侧 Glue）是否记得 release 完全解耦。
     * 例如测试侧 {@code @After} 因故未执行时，本方法仍会归还其持有的许可。
     */
    public static int releaseAllForCurrentThread() {
        Deque<ConcurrencyPartitionKey> held = HELD_BY_THREAD.get();
        if (held == null) {
            return 0;
        }
        int released = 0;
        ConcurrencyPartitionKey key;
        while ((key = held.pollLast()) != null) {
            doRelease(key);
            released++;
        }
        HELD_BY_THREAD.remove();
        if (released > 0) {
            LOGGER.info("[concurrency-gate] released {} leaked-held gate(s) for thread {} at scenario end",
                    released, Thread.currentThread().getName());
        }
        return released;
    }

    /**
     * scenario 结束（含失败）时配对调用；key==null 直接返回。必须放在 finally 中确保释放不泄漏。
     */
    public static void release(ConcurrencyPartitionKey key) {
        if (key == null) {
            return;
        }
        //  仅释放「本线程确实持有」的键：acquire 因超时 fail-open、或重复释放时必须 no-op ——
        //  否则许可虚增会直接破坏互斥（比卡死更隐蔽）。此处不判 isEnabled()：运行期开关翻转
        //  不应导致已获取的许可无法归还（泄漏 → 卡死）。
        if (!forgetHeld(key)) {
            return;
        }
        //  与 acquire 对称：在同一 per-key 原子段内「归还许可 + 递减持有数」，归零即移除条目
        //  （最后一个持有者离开 → 无人持有 → 无需保留）。不再按 key 重新查表释放：旧实现会在条目被
        //  淘汰/替换时把许可记到别的闸门上（许可虚增）。
        doRelease(key);
    }

    /**
     * 进入并持有 scope：acquire(key) 后返回 {@link ConcurrencyScope}，close 时配对 release。
     * key==null 时返回 no-op scope（不抛异常、不进 Map）。
     */
    public static ConcurrencyScope enter(ConcurrencyPartitionKey key) {
        acquire(key);
        return new ConcurrencyScope(key);
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
