package com.hsbc.cmb.hk.dbb.automation.framework.api.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SecurityAudit} 契约测试：记录、快照不可变、prod 判定、重置隔离。
 */
class SecurityAuditTest {

    @AfterEach
    void tearDown() {
        SecurityAudit.reset();
    }

    @Test
    void recordRelaxedTlsAddsOneEvent() {
        assertEquals(0, SecurityAudit.count());
        SecurityAudit.recordRelaxedTls("sit", "http.ssl.relax-validation");
        assertEquals(1, SecurityAudit.count());
        assertEquals("RELAXED_TLS", SecurityAudit.recordedEvents().get(0).type());
    }

    @Test
    void recordedEventsSnapshotIsImmutable() {
        SecurityAudit.recordRelaxedTls("sit", "http.ssl.relax-validation");
        List<SecurityAudit.SecurityEvent> snapshot = SecurityAudit.recordedEvents();
        assertThrows(UnsupportedOperationException.class, () -> snapshot.add(
                new SecurityAudit.SecurityEvent(java.time.Instant.now(), "X", "y")));
    }

    @Test
    void isProductionEnvironmentMatchesProdAliasesCaseInsensitively() {
        assertTrue(SecurityAudit.isProductionEnvironment("prod"));
        assertTrue(SecurityAudit.isProductionEnvironment("PRODUCTION"));
        assertTrue(SecurityAudit.isProductionEnvironment(" prd "));
    }

    @Test
    void isProductionEnvironmentRejectsNonProd() {
        assertFalse(SecurityAudit.isProductionEnvironment("sit"));
        assertFalse(SecurityAudit.isProductionEnvironment("uat"));
        assertFalse(SecurityAudit.isProductionEnvironment(null));
        assertFalse(SecurityAudit.isProductionEnvironment(""));
        assertFalse(SecurityAudit.isProductionEnvironment("   "));
    }

    @Test
    void resetClearsEvents() {
        SecurityAudit.recordRelaxedTls("sit", "http.ssl.relax-validation");
        SecurityAudit.reset();
        assertEquals(0, SecurityAudit.count());
    }
}
