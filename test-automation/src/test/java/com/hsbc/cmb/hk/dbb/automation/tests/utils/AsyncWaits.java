package com.hsbc.cmb.hk.dbb.automation.tests.utils;

import java.time.Duration;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * 测试层统一异步等待原语（B-4）。
 *
 * <p>背景：步骤层曾散落 {@code Thread.sleep} 手写轮询/固定等待，既不可观测（无超时上界语义）又易 flaky。
 * 本类把「有界轮询」集中到唯一实现（超时上界 + 固定间隔 + 中断安全），步骤层只表达意图：
 * <ul>
 *   <li>{@link #awaitTrue} —— 等待某条件成立（用于「应出现」的正向语义；超时未成立返回 {@code false}）；</li>
 *   <li>{@link #awaitResult} —— 等待某供给器返回非空值（用于等待异步采集记录出现）。</li>
 * </ul>
 *
 * <p>ArchUnit 门禁 {@code LayeringArchTest#stepsMustNotCallThreadSleep} 禁止 {@code *Steps} 步骤类
 * 直接调用 {@code Thread.sleep}；受控等待只允许经本工具（本类名不以 {@code Steps} 结尾，不在禁令范围内），
 * 与既有主代码规则 {@code frameworkCodeMustNotCallThreadSleep} 的「测试代码可保留受控等待」原则一致。
 *
 * <p>线程安全：无状态，所有方法可并发调用。
 */
public final class AsyncWaits {

    private AsyncWaits() {
    }

    /**
     * 轮询直到 {@code condition} 成立；超时仍未成立返回 {@code false}。
     * 线程被中断时保留中断标志并立即返回 {@code false}（不再等待）。
     */
    public static boolean awaitTrue(Duration timeout, Duration interval, BooleanSupplier condition) {
        long deadline = System.nanoTime() + timeout.toNanos();
        long intervalNanos = Math.max(1L, interval.toNanos());
        while (true) {
            if (condition.getAsBoolean()) {
                return true;
            }
            if (System.nanoTime() >= deadline) {
                return false;
            }
            if (!sleepInterruptibly(intervalNanos)) {
                return false;
            }
        }
    }

    /**
     * 轮询直到 {@code supplier} 返回非 {@code null}；超时仍为 {@code null} 则返回 {@code null}。
     */
    public static <T> T awaitResult(Duration timeout, Duration interval, Supplier<T> supplier) {
        long deadline = System.nanoTime() + timeout.toNanos();
        long intervalNanos = Math.max(1L, interval.toNanos());
        while (true) {
            T value = supplier.get();
            if (value != null) {
                return value;
            }
            if (System.nanoTime() >= deadline) {
                return null;
            }
            if (!sleepInterruptibly(intervalNanos)) {
                return null;
            }
        }
    }

    /** 便捷：以毫秒构造 {@link Duration}（如 {@code AsyncWaits.ms(4000)}）。 */
    public static Duration ms(long millis) {
        return Duration.ofMillis(millis);
    }

    /** 可中断睡眠；被中断时恢复中断标志并返回 {@code false}。 */
    private static boolean sleepInterruptibly(long nanos) {
        try {
            Thread.sleep(nanos / 1_000_000L, (int) (nanos % 1_000_000L));
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
