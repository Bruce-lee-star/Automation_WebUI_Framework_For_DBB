package com.hsbc.cmb.hk.dbb.automation.framework.api.security;

import org.junit.After;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * {@link SecurityAudit} 契约测试：记录、快照不可变、prod 判定、重置隔离。
 */
public class SecurityAuditTest {

    @After
    public void tearDown() {
        SecurityAudit.reset();
    }

    @Test
    public void recordRelaxedTlsAddsOneEvent() {
        assertEquals(0, SecurityAudit.count());
        SecurityAudit.recordRelaxedTls("sit", "http.ssl.relax-validation");
        assertEquals(1, SecurityAudit.count());
        assertEquals("RELAXED_TLS", SecurityAudit.recordedEvents().get(0).type());
    }

    @Test
    public void recordedEventsSnapshotIsImmutable() {
        SecurityAudit.recordRelaxedTls("sit", "http.ssl.relax-validation");
        List<SecurityAudit.SecurityEvent> snapshot = SecurityAudit.recordedEvents();
        assertThrows(UnsupportedOperationException.class, () -> snapshot.add(
                new SecurityAudit.SecurityEvent(java.time.Instant.now(), "X", "y")));
    }

    @Test
    public void isProductionEnvironmentMatchesProdAliasesCaseInsensitively() {
        assertTrue(SecurityAudit.isProductionEnvironment("prod"));
        assertTrue(SecurityAudit.isProductionEnvironment("PRODUCTION"));
        assertTrue(SecurityAudit.isProductionEnvironment(" prd "));
    }

    @Test
    public void isProductionEnvironmentRejectsNonProd() {
        assertFalse(SecurityAudit.isProductionEnvironment("sit"));
        assertFalse(SecurityAudit.isProductionEnvironment("uat"));
        assertFalse(SecurityAudit.isProductionEnvironment(null));
        assertFalse(SecurityAudit.isProductionEnvironment(""));
        assertFalse(SecurityAudit.isProductionEnvironment("   "));
    }

    @Test
    public void resetClearsEvents() {
        SecurityAudit.recordRelaxedTls("sit", "http.ssl.relax-validation");
        SecurityAudit.reset();
        assertEquals(0, SecurityAudit.count());
    }
}
