package com.hsbc.cmb.hk.dbb.automation.framework.web.page.scan;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Characterization tests for the pure (browser-free) logic of
 * {@link RolePickerClassNameResolver}: URL → Page class-name derivation,
 * locale-stripping normalization, and the stable URL→class mapping (dedup /
 * cross-invocation reuse via the global persistent cache).
 *
 * <p>These methods were extracted verbatim from {@code RoleElementPicker}; this
 * suite guards them so the remaining coupled core can be split safely later.
 */
public class RolePickerClassNameResolverTest {

    @Before
    public void setUp() {
        RolePickerClassNameResolver.clear();
    }

    @After
    public void tearDown() {
        RolePickerClassNameResolver.clear();
    }

    // ---- pageClassNameFromUrl ----

    @Test
    public void nullUrlYieldsIndexPage() {
        assertEquals("IndexPage",
                RolePickerClassNameResolver.pageClassNameFromUrl(null, Collections.emptySet()));
    }

    @Test
    public void rootPathYieldsIndexPage() {
        assertEquals("IndexPage",
                RolePickerClassNameResolver.pageClassNameFromUrl("https://example.com/", Collections.emptySet()));
    }

    @Test
    public void lastPathSegmentBecomesClassName() {
        assertEquals("AccountsPage",
                RolePickerClassNameResolver.pageClassNameFromUrl("https://example.com/accounts", Collections.emptySet()));
    }

    @Test
    public void queryAndHashStrippedBeforeSegmenting() {
        assertEquals("AccountsPage",
                RolePickerClassNameResolver.pageClassNameFromUrl("https://example.com/accounts?x=1#frag", Collections.emptySet()));
    }

    @Test
    public void separatorCharsCapitalized() {
        assertEquals("MyAccountPage",
                RolePickerClassNameResolver.pageClassNameFromUrl("https://example.com/my-account", Collections.emptySet()));
        assertEquals("UserProfilesPage",
                RolePickerClassNameResolver.pageClassNameFromUrl("https://example.com/user_profiles", Collections.emptySet()));
    }

    @Test
    public void nonIdentifierCharsDropped() {
        // '!' and '@' are not identifier chars and not separators -> dropped; 'b' stays lowercase (no capitalization after a dropped char).
        assertEquals("FoobarPage",
                RolePickerClassNameResolver.pageClassNameFromUrl("https://example.com/foo!@bar", Collections.emptySet()));
    }

    @Test
    public void hashFragmentIsTreatedAsUrlFragmentAndIgnored() {
        // A '#' starts a URL fragment; everything after it is dropped before segmenting.
        assertEquals("AccountsPage",
                RolePickerClassNameResolver.pageClassNameFromUrl("https://example.com/accounts#section", Collections.emptySet()));
    }

    @Test
    public void duplicateAvoidedWithUsedSet() {
        assertEquals("AccountsPage2",
                RolePickerClassNameResolver.pageClassNameFromUrl(
                        "https://example.com/accounts",
                        new HashSet<>(Arrays.asList("AccountsPage"))));
    }

    @Test
    public void localeSegmentNotStrippedHere() {
        // pageClassNameFromUrl does not strip the locale (that is normalizeUrl's job); it still takes the last segment.
        assertEquals("AccountsPage",
                RolePickerClassNameResolver.pageClassNameFromUrl("https://example.com/en/accounts", Collections.emptySet()));
    }

    // ---- normalizeUrl ----

    @Test
    public void normalizeStripsQueryAndHash() {
        assertEquals("https://example.com/accounts",
                RolePickerClassNameResolver.normalizeUrl("https://example.com/accounts?x=1#frag"));
    }

    @Test
    public void normalizeRemovesTrailingSlash() {
        assertEquals("https://example.com/help",
                RolePickerClassNameResolver.normalizeUrl("https://example.com/help/"));
    }

    @Test
    public void normalizeStripsLocaleForPathOnlyInput() {
        assertEquals("/accounts", RolePickerClassNameResolver.normalizeUrl("/en/accounts"));
        assertEquals("/accounts", RolePickerClassNameResolver.normalizeUrl("/zh-HK/accounts"));
    }

    @Test
    public void normalizeStripsLocaleForFullUrl() {
        // Regression: page.url() is a full URL; locale stripping must fire there too,
        // otherwise switching language (e.g. /en/accounts vs /zh/accounts) derives two page classes.
        assertEquals("https://example.com/accounts",
                RolePickerClassNameResolver.normalizeUrl("https://example.com/en/accounts"));
        assertEquals("https://example.com/accounts",
                RolePickerClassNameResolver.normalizeUrl("https://example.com/zh-HK/accounts"));
    }

    @Test
    public void normalizeLocaleAndTrailingSlashCombinedForFullUrl() {
        assertEquals("https://example.com/accounts",
                RolePickerClassNameResolver.normalizeUrl("https://example.com/en/accounts/"));
    }

    @Test
    public void normalizeNullYieldsEmpty() {
        assertEquals("", RolePickerClassNameResolver.normalizeUrl(null));
    }

    // ---- resolvePageClassForUrl ----

    @Test
    public void resolveDerivesAndMutatesMap() {
        LinkedHashMap<String, String> urlToClass = new LinkedHashMap<>();
        String cls = RolePickerClassNameResolver.resolvePageClassForUrl(
                "https://example.com/accounts", Collections.emptySet(), urlToClass);
        assertEquals("AccountsPage", cls);
        assertEquals("AccountsPage", urlToClass.get("https://example.com/accounts"));
    }

    @Test
    public void resolveReusesExistingFromPassedMap() {
        LinkedHashMap<String, String> urlToClass = new LinkedHashMap<>();
        urlToClass.put("https://example.com/accounts", "AccountsPage");
        String cls = RolePickerClassNameResolver.resolvePageClassForUrl(
                "https://example.com/accounts", Collections.emptySet(), urlToClass);
        assertEquals("AccountsPage", cls);
    }

    @Test
    public void resolvePersistsToGlobalCache() {
        LinkedHashMap<String, String> urlToClass = new LinkedHashMap<>();
        RolePickerClassNameResolver.resolvePageClassForUrl(
                "https://example.com/accounts", Collections.emptySet(), urlToClass);
        assertTrue("global persistent mapping should remember the derived class",
                RolePickerClassNameResolver.values().contains("AccountsPage"));
    }

    @Test
    public void resolveDedupAgainstGlobalViaSeed() {
        // Production seeds the session urlToClass from the global snapshot; replicate that to verify dedup.
        LinkedHashMap<String, String> m1 = new LinkedHashMap<>();
        RolePickerClassNameResolver.resolvePageClassForUrl(
                "https://example.com/accounts", Collections.emptySet(), m1);

        LinkedHashMap<String, String> m2 = RolePickerClassNameResolver.snapshot();
        String cls = RolePickerClassNameResolver.resolvePageClassForUrl(
                "https://example.com/v2/accounts", Collections.emptySet(), m2);
        assertEquals("AccountsPage2", cls);
    }
}
