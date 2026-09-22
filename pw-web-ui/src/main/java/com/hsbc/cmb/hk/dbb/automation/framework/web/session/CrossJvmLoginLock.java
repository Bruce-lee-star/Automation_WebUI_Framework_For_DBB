package com.hsbc.cmb.hk.dbb.automation.framework.web.session;

import com.hsbc.cmb.hk.dbb.automation.framework.common.config.VerboseLogging;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;

/**
 * 跨 JVM（跨进程）登录单飞文件锁（评审 F-11 / P1-2）。
 *
 * <p><b>要解决的问题</b>：{@link SessionManager} 的 JVM 内单飞（{@code loginGuards}）只能协调<b>同一
 * JVM</b> 内的并发线程。当 Maven Surefire/Failsafe 以 {@code forkCount>0} 分叉、或 CI 做分片时，
 * <b>多个 JVM</b> 都会看到「无 session 文件」而各自执行真实登录 —— 服务端（单会话策略）会把对端踢下线，
 * 表现为随机 401 / 重登录失败，且现象难以复现（源码注释亦自承该缺口）。</p>
 *
 * <p><b>机制</b>：以「每 sessionKey 一个锁文件」上的 {@link FileLock}（OS 级、跨进程、进程退出即自动释放）
 * 作为登录权令牌：</p>
 * <ol>
 *   <li>先试 {@link FileChannel#tryLock()}；成功即取得登录权（本进程为 leader），<b>锁需持有到 session
 *       落盘</b>（{@link SessionManager#saveSession}），使其它进程在此期间只能等待；</li>
 *   <li>失败分两种：<b>他进程</b>持锁（{@code tryLock()} 返回 {@code null}）与<b>本 JVM 其它线程</b>持锁
 *       （抛 {@link OverlappingFileLockException}，因文件锁是 JVM 级持有）—— 二者都按「未取得」处理，
 *       重试等待；</li>
 *   <li>等待期间每次重试前用 {@code sessionAvailable} 探测「他方是否已落盘」：一旦可用即放弃竞争返回
 *       {@code false}，调用方直接复用其结果，<b>不再触发第二次登录</b>（与 JVM 内 follower 语义一致）。</li>
 * </ol>
 *
 * <p><b>超时的取舍</b>：等待超过 timeout 仍未取得且他方也未产出 session 时，记 ERROR 后<b>按 leader 继续</b>。
 * 这是刻意的有界降级：锁文件由 OS 在进程退出时自动释放，故「超时」意味着对端仍存活但异常缓慢；
 * 此时若改为失败快，会把整个套件拖红，代价高于「极小概率的双重登录」。</p>
 *
 * <p><b>持有与管理</b>：持有者以 {@link ThreadLocal} 记录（同一线程同一 sessionKey 重复申请为幂等），
 * 换 key 时先释放旧锁；{@link #releaseLock()} 幂等，须在 session 落盘后与场景收口兜底两处调用。</p>
 *
 * @apiNote 框架内部能力（包级私有）：仅供 {@link SessionManager} 接线与同包单测使用。
 */
final class CrossJvmLoginLock implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(CrossJvmLoginLock.class);

    /** 竞争重试间隔（毫秒）：短间隔以尽快感知「他方已落盘」，又不至于空转烧 CPU。 */
    private static final long RETRY_INTERVAL_MS = 100L;

    /** 本线程当前持有的跨进程登录锁（同一线程同一 sessionKey 幂等）。 */
    private static final ThreadLocal<CrossJvmLoginLock> HELD = new ThreadLocal<>();

    private final String sessionKey;
    private final FileChannel channel;
    private final FileLock lock;

    private CrossJvmLoginLock(String sessionKey, FileChannel channel, FileLock lock) {
        this.sessionKey = sessionKey;
        this.channel = channel;
        this.lock = lock;
    }

    /**
     * 申请跨进程登录权（等待他方让出或他方已落盘）。
     *
     * @param sessionKey       session 标识（仅用于日志与幂等判定）
     * @param lockPath         锁文件路径（父目录不存在时自动创建）
     * @param timeoutMs        最长等待毫秒（{@code <=0} 表示只试一次、不等待）
     * @param sessionAvailable 探测「他方是否已把 session 落盘可用」；返回 true 即放弃竞争
     * @return {@code true} 表示<b>已取得</b>登录权（本进程为 leader，调用方必须最终调用
     *         {@link #releaseLock()}）；{@code false} 表示<b>未取得</b>（他方已落盘 session，调用方应直接复用）
     */
    static boolean acquireForLogin(String sessionKey, Path lockPath, long timeoutMs,
                                   BooleanSupplier sessionAvailable) {
        CrossJvmLoginLock held = HELD.get();
        if (held != null) {
            if (held.sessionKey.equals(sessionKey)) {
                return true; // 同 key 重复申请：幂等
            }
            held.release(); // 换 key（如 feature 模式切 user）：先释放旧锁，避免长期占用已不用的身份
        }

        long deadline = System.currentTimeMillis() + Math.max(0L, timeoutMs);
        boolean warnedOnce = false;
        while (true) {
            //  快路径：他方已把 session 落盘 ⇒ 连锁都不必争，直接复用（不产生第二次登录）。
            if (sessionAvailable != null && sessionAvailable.getAsBoolean()) {
                VerboseLogging.logInfoIfVerbose(LOGGER,
                        "[cross-jvm-login] session for {} is already available -> reuse it (no second login)",
                        sessionKey);
                return false;
            }

            try {
                Path parent = lockPath.getParent();
                if (parent == null) {
                    parent = lockPath.toAbsolutePath();
                }
                Files.createDirectories(parent);
            } catch (IOException e) {
                //  锁文件不可建（只读文件系统等）：无法协调，降级为「直接当 leader」并保持可见（不得静默）。
                LOGGER.warn("[cross-jvm-login] cannot create lock dir for sessionKey={} -> proceeding as leader "
                        + "without cross-process coordination: {}", sessionKey, e.toString());
                return true;
            }

            FileChannel channel = null;
            FileLock lock = null;
            try {
                channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                lock = channel.tryLock();
            } catch (OverlappingFileLockException e) {
                //  同 JVM 其它线程已持有该文件锁（文件锁为 JVM 级）：按「未取得」处理
                closeQuietly(channel);
                channel = null;
            } catch (IOException e) {
                closeQuietly(channel);
                channel = null;
                if (!warnedOnce) {
                    warnedOnce = true;
                    LOGGER.warn("[cross-jvm-login] lock attempt failed for sessionKey={} -> retrying until timeout: {}",
                            sessionKey, e.toString());
                }
            }

            if (lock != null && channel != null) {
                //  双重检查（关键）：他方 saveSession 的顺序是「先落盘、后释放锁」，故取到锁的同时
                //  极可能他方刚完成登录 —— 此时若直接当 leader 会立刻产生第二次登录。故取到锁后再探一次。
                if (sessionAvailable != null && sessionAvailable.getAsBoolean()) {
                    closeQuietly(channel); // 关闭通道即释放刚取得（但不需要）的锁
                    VerboseLogging.logInfoIfVerbose(LOGGER,
                            "[cross-jvm-login] acquired lock for {} but session was persisted meanwhile "
                                    + "-> reuse it (no second login)", sessionKey);
                    return false;
                }
                HELD.set(new CrossJvmLoginLock(sessionKey, channel, lock));
                VerboseLogging.logInfoIfVerbose(LOGGER,
                        "[cross-jvm-login] acquired login right for sessionKey={} (lock={})", sessionKey, lockPath);
                return true;
            }

            if (System.currentTimeMillis() >= deadline) {
                LOGGER.error("[cross-jvm-login] timed out {}ms waiting for login right of sessionKey={} "
                                + "→ proceeding as leader (bounded degradation: a slow peer may cause a "
                                + "twofold login; OS releases the lock automatically when that process exits)",
                        timeoutMs, sessionKey);
                return true;
            }
            if (Thread.currentThread().isInterrupted()) {
                //  被中断时 parkNanos 会立即返回 —— 若继续循环将退化为热自旋，故按「放弃等待、降级为 leader」退出。
                LOGGER.warn("[cross-jvm-login] interrupted while waiting for login right of sessionKey={} "
                        + "→ proceeding as leader", sessionKey);
                return true;
            }
            parkRetryInterval();
        }
    }

    /** 本线程是否正持有指定 sessionKey 的跨进程登录锁。 */
    static boolean isHeldFor(String sessionKey) {
        CrossJvmLoginLock held = HELD.get();
        return held != null && held.sessionKey.equals(sessionKey);
    }

    /**
     * 释放本线程持有的跨进程登录锁（幂等；未持有时为 no-op）。
     *
     * <p>调用点两处：① session 成功/失败落盘后（{@link SessionManager#saveSession}）；
     * ② 场景收口兜底（{@link SessionManager#releaseSessionGate()}），确保登录异常路径不长期占锁。</p>
     */
    static void releaseLock() {
        CrossJvmLoginLock held = HELD.get();
        if (held != null) {
            held.release();
        }
    }

    private void release() {
        HELD.remove();
        try {
            if (lock.isValid()) {
                lock.release();
            }
        } catch (IOException e) {
            LOGGER.warn("[cross-jvm-login] failed to release lock for sessionKey={}: {}", sessionKey, e.toString());
        } finally {
            closeQuietly(channel);
        }
    }

    @Override
    public void close() {
        release();
    }

    private static void closeQuietly(FileChannel channel) {
        if (channel == null) {
            return;
        }
        try {
            channel.close();
        } catch (IOException ignored) {
            // 关闭失败无需处理：进程退出时 OS 亦会释放其文件锁
            LOGGER.debug("closeQuietly: failed to close file channel (OS releases lock on exit): {}",
                    ignored.getMessage());
        }
    }

    /**
     * 重试间隔等待：<b>基于 {@link LockSupport#parkNanos}</b>，不调用 {@link Thread#sleep}。
     *
     * <p>框架主代码禁忙等由架构门禁 {@code LayeringArchTest#frameworkCodeMustNotCallThreadSleep} 守护；
     * 与 {@code PlaywrightManager}/{@code BrowserStartupImpl} 的退避实现保持同一方式。
     * 允许虚假唤醒：外层循环每轮都会重探「他方是否已落盘」与截止时间，语义不受影响。</p>
     */
    private static void parkRetryInterval() {
        LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(RETRY_INTERVAL_MS));
    }
}
