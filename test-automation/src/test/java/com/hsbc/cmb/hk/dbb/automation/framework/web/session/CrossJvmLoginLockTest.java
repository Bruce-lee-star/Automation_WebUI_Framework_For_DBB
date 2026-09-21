package com.hsbc.cmb.hk.dbb.automation.framework.web.session;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link CrossJvmLoginLock} 单测（评审 F-11 / P1-2，不启动浏览器）。
 *
 * <p><b>怎么模拟「另一个 JVM」</b>：{@link FileLock} 是 <b>JVM 级</b>持有 —— 同一进程内另一条
 * {@link FileChannel} 对同一区域加锁会抛 {@link java.nio.channels.OverlappingFileLockException}，
 * 与「另一进程已持锁」在<b>调用方视角</b>是同一判定（均视为「未取得」）。故测试用一个独立
 * {@code FileChannel} 作为「他方持锁者」，可确定性覆盖：未取得→重试→他方落盘即复用 / 超时降级。</p>
 *
 * <p>真实跨进程语义由 OS 保证（锁随进程退出自动释放）；这里固化的是<b>框架侧协调逻辑</b>：
 * ① 双重检查（他方「先落盘后释放」，取到锁也不能凭此再登一次）；② 快路径；③ 超时有界降级；
 * ④ 幂等释放与换 key 释放。</p>
 */
public class CrossJvmLoginLockTest {

    private static final String SESSION_KEY = "O63_SIT1_WP7UAT2_2";

    @TempDir
    Path tempDir;

    private FileChannel rawOwner;

    @AfterEach
    public void tearDown() throws Exception {
        CrossJvmLoginLock.releaseLock();
        if (rawOwner != null && rawOwner.isOpen()) {
            rawOwner.close();
        }
    }

    private Path lockPath() {
        return tempDir.resolve(SESSION_KEY + ".login.lock");
    }

    /** 以独立通道模拟「他方 JVM 正持有登录权」。 */
    private void holdRawLock() throws Exception {
        Files.createDirectories(lockPath().getParent());
        rawOwner = FileChannel.open(lockPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        FileLock raw = rawOwner.tryLock();
        assertNotNull(raw, "测试前置：他方应能取得原始文件锁");
    }

    private static void sleep(long ms) throws InterruptedException {
        Thread.sleep(ms);
    }

    @Test
    public void uncontendedAcquireSucceedsAndReleaseIsIdempotent() {
        assertTrue(CrossJvmLoginLock.acquireForLogin(SESSION_KEY, lockPath(), 1_000, () -> false),
                "无竞争时应取得登录权");
        assertTrue(CrossJvmLoginLock.isHeldFor(SESSION_KEY), "取得后应登记为本线程持有");

        CrossJvmLoginLock.releaseLock();
        CrossJvmLoginLock.releaseLock(); // 幂等：重复释放不得抛异常
        assertFalse(CrossJvmLoginLock.isHeldFor(SESSION_KEY), "释放后不应再持有");

        assertTrue(CrossJvmLoginLock.acquireForLogin(SESSION_KEY, lockPath(), 1_000, () -> false),
                "释放后应能被重新取得");
    }

    @Test
    public void fastPathReusesWhenSessionAlreadyAvailableWithoutTakingLock() {
        boolean leader = CrossJvmLoginLock.acquireForLogin(SESSION_KEY, lockPath(), 1_000, () -> true);

        assertFalse(leader, "他方已落盘 session 时应直接复用（返回 false），不得登录");
        assertFalse(Files.exists(lockPath()), "快路径不应争锁，故锁文件都不该被创建");
    }

    @Test
    public void doubleCheckReusesWhenPeerPersistsSessionRightBeforeWeTakeLock() {
        //  第 1 次探测（循环开头快路径）返回 false → 继续争锁；取到锁后的第 2 次探测返回 true
        //  模拟「他方 saveSession 先落盘、后释放锁」：取到锁时其登录其实已完成 → 必须复用而非再登一次。
        AtomicInteger probes = new AtomicInteger();
        boolean leader = CrossJvmLoginLock.acquireForLogin(
                SESSION_KEY, lockPath(), 1_000, () -> probes.incrementAndGet() >= 2);

        assertFalse(leader, "双重检查命中时应复用（返回 false）");
        assertFalse(CrossJvmLoginLock.isHeldFor(SESSION_KEY), "复用路径不应持有锁（刚取得的锁应已释放）");
        verifyLockIsFreeAgain();
    }

    @Test
    public void waitsForOtherOwnerThenReusesItsSessionInsteadOfLoggingInAgain() throws Exception {
        holdRawLock();
        Path sessionMarker = tempDir.resolve(SESSION_KEY + ".json");

        Thread peer = new Thread(() -> {
            try {
                sleep(200);
                Files.writeString(sessionMarker, "{\"cookies\":[]}"); // 他方完成登录并落盘
                rawOwner.close();                                     // 随后释放登录权（saveSession 的顺序）
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }, "peer-jvm");
        peer.start();

        boolean leader = CrossJvmLoginLock.acquireForLogin(
                SESSION_KEY, lockPath(), 5_000, () -> Files.exists(sessionMarker));
        peer.join(5_000);

        assertFalse(leader, "等待期间他方已落盘 ⇒ 必须复用，绝不二次登录");
        verifyLockIsFreeAgain();
    }

    @Test
    public void timesOutAndProceedsAsLeaderWhenPeerNeverFreesTheLock() throws Exception {
        holdRawLock(); // 他方持锁到底，且始终无 session 产出

        long t0 = System.currentTimeMillis();
        boolean leader = CrossJvmLoginLock.acquireForLogin(SESSION_KEY, lockPath(), 300, () -> false);
        long elapsed = System.currentTimeMillis() - t0;

        assertTrue(leader, "超时应有界降级为 leader（不得永久阻塞）");
        assertTrue(elapsed >= 250, "应至少经历一次超时窗口（实测 " + elapsed + "ms）");
        assertFalse(CrossJvmLoginLock.isHeldFor(SESSION_KEY),
                "降级路径并未真正取到锁（他方仍持有），故本线程无锁可释放；releaseLock 为 no-op");
    }

    @Test
    public void acquiringAnotherKeyReleasesThePreviouslyHeldOne() {
        assertTrue(CrossJvmLoginLock.acquireForLogin("KEY_A", tempDir.resolve("A.login.lock"), 500, () -> false));
        assertTrue(CrossJvmLoginLock.acquireForLogin("KEY_B", tempDir.resolve("B.login.lock"), 500, () -> false));

        assertFalse(CrossJvmLoginLock.isHeldFor("KEY_A"), "换 key 时应释放旧锁，避免长期占用已不用的身份");
        assertTrue(CrossJvmLoginLock.isHeldFor("KEY_B"));
    }

    /** 断言锁已可被再次取得（即前一次确实释放干净）。 */
    private void verifyLockIsFreeAgain() {
        assertTrue(CrossJvmLoginLock.acquireForLogin(SESSION_KEY, lockPath(), 1_000, () -> false),
                "锁应已释放，可被再次取得");
        CrossJvmLoginLock.releaseLock();
    }
}
