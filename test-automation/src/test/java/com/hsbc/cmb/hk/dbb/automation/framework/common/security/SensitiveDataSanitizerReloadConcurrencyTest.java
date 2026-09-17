package com.hsbc.cmb.hk.dbb.automation.framework.common.security;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * C-7：{@link SensitiveDataSanitizer} 规则集「不可变快照 + volatile 原子发布」的并发回归护盾。
 *
 * <p>修复前：生效规则集是共享可变静态集合，{@code loadRulesIfNeeded}/reload 以 {@code clear()+addAll()}
 * 就地改写——并发读取（sanitize*）可观察到被清空/半填充的中间态，导致敏感键"短暂失效"（合规倒退）。
 * 修复后：每次加载构造**不可变快照**并整体 volatile 赋值，读取永远看到某个完整版本。
 *
 * <p>本用例在「多读线程持续读取 + 主线程反复 reloadRules」下断言：任何一次读取都不得看到规则丢失。
 * 若回退为 clear+addAll 实现，本用例会因读取到空集而失败。
 */
class SensitiveDataSanitizerReloadConcurrencyTest {

    @Test
    void concurrentReadsNeverObserveEmptyRuleSetsDuringReload() throws Exception {
        AtomicBoolean stop = new AtomicBoolean(false);
        AtomicInteger violations = new AtomicInteger();

        List<Thread> readers = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            Thread t = new Thread(() -> {
                Map<String, String> headers = Map.of("Authorization", "Bearer secret123");
                while (!stop.get()) {
                    if (!SensitiveDataSanitizer.isSensitiveBodyKey("password")) {
                        violations.incrementAndGet();
                    }
                    // header 集合：Authorization 必须始终被遮蔽
                    if (!SensitiveDataSanitizer.sanitizeHeaders(headers).get("Authorization").contains("***")) {
                        violations.incrementAndGet();
                    }
                    // query 集合：token 必须始终被移除
                    if (SensitiveDataSanitizer.sanitizeUrl("https://h/p?token=abc123").contains("abc123")) {
                        violations.incrementAndGet();
                    }
                }
            }, "sanitizer-reader-" + i);
            t.setDaemon(true);
            t.start();
            readers.add(t);
        }

        for (int i = 0; i < 50; i++) {
            SensitiveDataSanitizer.reloadRules();
        }
        stop.set(true);
        for (Thread t : readers) {
            t.join(2000);
        }

        assertEquals(0, violations.get(),
                "并发 reload 期间读取不得观察到空/半填充规则集（C-7：共享可变静态集竞态）");
    }

    @Test
    void ruleSetsRemainEffectiveAfterReload() {
        SensitiveDataSanitizer.reloadRules();

        assertTrue(SensitiveDataSanitizer.isSensitiveBodyKey("password"));
        assertTrue(SensitiveDataSanitizer.isSensitiveBodyKey("access_token"));
        assertFalse(SensitiveDataSanitizer.isSensitiveBodyKey("username"));

        Map<String, String> headers = Map.of("X-Api-Key", "topsecret");
        String masked = SensitiveDataSanitizer.sanitizeHeaders(headers).get("X-Api-Key");
        assertTrue(masked.contains("***"));
        assertFalse(masked.contains("topsecret"));
    }
}
