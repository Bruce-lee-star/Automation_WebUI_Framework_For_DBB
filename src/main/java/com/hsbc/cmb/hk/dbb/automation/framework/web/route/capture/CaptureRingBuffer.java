package com.hsbc.cmb.hk.dbb.automation.framework.web.route.capture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.concurrent.locks.LockSupport;

/**
 * 无锁环形缓冲区 — DROP_NEWEST，固定容量。
 *
 * <p>设计要点：
 * <ul>
 *   <li>生产者（Playwright 事件线程）调用 {@link #publish(CaptureEvent)}，
 *       微秒级返回，永不阻塞事件线程</li>
 *   <li>缓冲区写满时丢弃最新事件（DROP_NEWEST），保留已有数据最多的链</li>
 *   <li>固定容量（默认 8192），不支持动态扩容——扩容会改变已有事件的索引映射，
 *       导致消费者读到错误的 slot</li>
 *   <li>消费者（{@link EventMerger}）调用 {@link #poll(long)} 拉取</li>
 * </ul>
 *
 * <p>为什么不用 DISCARD_OLDEST？
 * <br>CDP 事件按 request→response→loadingFinished 顺序到达。
 * 丢弃最旧 = 破坏"几乎完整的链"，导致已到达的 RESPONSE_META/BODY 全部浪费。
 * 丢弃最新 = 破坏"刚起的链"，已有数据最多的链得以保留，损失最小。
 *
 * <p>容量为何固定？
 * <br>动态扩容会改变索引映射（seq % oldCap → seq % newCap），
 * 导致扩容前写入的事件无法被消费者正确读取。
 * 8192 的容量在 99.9% 的场景下足够——若仍溢出，丢弃最新事件比阻塞事件线程更好。
 */
public class CaptureRingBuffer {

    private static final Logger LOGGER = LoggerFactory.getLogger(CaptureRingBuffer.class);

    /** 默认容量（必须是 2 的幂） */
    static final int DEFAULT_CAPACITY = 8192;

    private final int capacity;
    private final AtomicReferenceArray<CaptureEvent> buffer;
    private final AtomicLong writeSeq = new AtomicLong(0);
    private final AtomicLong readSeq = new AtomicLong(0);
    private final AtomicLong droppedCount = new AtomicLong(0);

    /**
     * @param capacity 缓冲区容量（必须是 2 的幂，默认 8192）
     */
    public CaptureRingBuffer(int capacity) {
        if (Integer.bitCount(capacity) != 1) {
            throw new IllegalArgumentException("capacity must be a power of 2, got " + capacity);
        }
        this.capacity = capacity;
        this.buffer = new AtomicReferenceArray<>(capacity);
    }

    public CaptureRingBuffer() {
        this(DEFAULT_CAPACITY);
    }

    /**
     * 发布事件 — 永不阻塞，微秒级返回。
     *
     * @return true=发布成功，false=被丢弃（缓冲区满）
     */
    public boolean publish(CaptureEvent event) {
        if (event == null) return false;

        long w = writeSeq.getAndIncrement();
        long r = readSeq.get();
        long pending = w - r;

        // 缓冲区满 → DROP_NEWEST
        if (pending >= capacity) {
            droppedCount.incrementAndGet();
            logIfFrequentDrop();
            return false;
        }

        // 写入
        buffer.set(mask(w), event);
        return true;
    }

    /**
     * 消费者拉取事件 — 阻塞等待直到有可用事件或超时。
     *
     * @param timeoutMs 超时毫秒
     * @return 事件，超时返回 null
     */
    public CaptureEvent poll(long timeoutMs) {
        long deadline = System.nanoTime() + timeoutMs * 1_000_000L;

        while (true) {
            long r = readSeq.get();
            long w = writeSeq.get();

            if (r < w) {
                // 有可用事件
                int idx = mask(r);
                CaptureEvent event = buffer.getAndSet(idx, null);
                // 无论是否取到事件都必须推进 readSeq（与 drain 保持一致），
                // 避免 writeSeq 已推进但 slot 尚未写入（null）时陷入忙等/park 循环。
                readSeq.incrementAndGet();
                if (event != null) {
                    return event;
                }
                continue;
            }

            // 无可用事件，检查超时
            if (System.nanoTime() >= deadline) {
                return null;
            }

            // 短暂 park 避免忙等（可响应中断）
            LockSupport.parkNanos(1_000_000L); // 1ms
        }
    }

    /**
     * 排空缓冲区 — 消费所有剩余事件并返回。
     *
     * <p>用于 {@link EventMerger#stop()} 时消费掉所有尚未处理的事件，
     * 避免事件在缓冲区中永久残留。
     *
     * @return 剩余事件列表（按发布顺序排列）
     */
    public java.util.List<CaptureEvent> drain() {
        java.util.List<CaptureEvent> remaining = new java.util.ArrayList<>();
        while (true) {
            long r = readSeq.get();
            long w = writeSeq.get();
            if (r >= w) break;

            int idx = mask(r);
            CaptureEvent event = buffer.getAndSet(idx, null);
            // 无论是否取到事件都必须推进 readSeq，否则在 writeSeq 已推进但 slot
            // 尚未写入（null）时会无限自旋，导致消费者线程 CPU 跑满、测试卡死。
            readSeq.incrementAndGet();
            if (event != null) {
                remaining.add(event);
            }
        }
        return remaining;
    }

    // ── 指标 ──

    /** 当前待消费事件数 */
    public long pending() {
        return writeSeq.get() - readSeq.get();
    }

    /** 累计丢弃事件数 */
    public long droppedCount() {
        return droppedCount.get();
    }

    /** 缓冲区容量 */
    public int capacity() {
        return capacity;
    }

    /** 总发布事件数 */
    public long totalPublished() {
        return writeSeq.get();
    }

    // ── 内部 ──

    /** 位运算取模（容量为 2 的幂，映射恒定不变） */
    private int mask(long seq) {
        return (int) (seq & (capacity - 1));
    }

    /** 丢弃率超过 5% 时记录 WARN 日志（间隔 10 秒） */
    private long lastDropLogTime = 0L;

    private void logIfFrequentDrop() {
        long now = System.currentTimeMillis();
        if (now - lastDropLogTime > 10_000) {
            lastDropLogTime = now;
            long total = writeSeq.get();
            long dropped = droppedCount.get();
            double rate = total > 0 ? (double) dropped / total : 0;
            if (rate > 0.05) {
                LOGGER.warn("[CaptureRingBuffer] Drop rate {} ({} dropped / {} total). "
                                + "Consider increasing capacity (current={}) or reducing capture scope.",
                        String.format("%.1f%%", rate * 100), dropped, total, capacity);
            }
        }
    }

    @Override
    public String toString() {
        return String.format("CaptureRingBuffer{cap=%d, pending=%d, dropped=%d}",
                capacity, pending(), droppedCount.get());
    }
}