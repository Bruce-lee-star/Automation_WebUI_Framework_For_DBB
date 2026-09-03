package com.hsbc.cmb.hk.dbb.automation.framework.web.page.scan;

import com.google.gson.Gson;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Characterization tests for {@link RolePickerNlsCache}: the nls reverse-lookup JSON builder
 * extracted (verbatim) from {@code RoleElementPicker} in T5-1 step 2.
 *
 * <p>Pinned behavior:
 * <ul>
 *   <li>non-null / non-empty reverse JSON with {@code exact} and {@code templates} sections;</li>
 *   <li>exact keys are <em>visible</em> text (HTML stripped, entities decoded); templates carry
 *       regex sources for {@code {{...}}} placeholders;</li>
 *   <li>file-order independence (sorted/dedup key) so the same set of files hits one cache entry;</li>
 *   <li>empty / null / missing / no-entry inputs fall back to {@code "{}"};</li>
 *   <li>single-file overload equals the one-element-list overload.</li>
 * </ul>
 */
public class RolePickerNlsCacheTest {

    private static final Gson GSON = new Gson();
    private Path fixture;

    @Before
    public void setUp() throws IOException {
        RolePickerNlsCache.clear();
        // en/zh with: a plain entry, an HTML/entity entry, and a {{placeholder}} template entry.
        String json = "{\n"
                + "  \"en\": {\n"
                + "    \"tab_accounts\": \"Accounts\",\n"
                + "    \"tab_security_device\": \"保安編碼器&nbsp; <img src=\\\"x\\\">\",\n"
                + "    \"greeting_user\": \"Hello {{name}}\"\n"
                + "  },\n"
                + "  \"zh\": {\n"
                + "    \"tab_accounts\": \"账户\",\n"
                + "    \"greeting_user\": \"你好 {{name}}\"\n"
                + "  }\n"
                + "}";
        fixture = Files.createTempFile("picker-nls-", ".json");
        Files.write(fixture, json.getBytes(StandardCharsets.UTF_8));
    }

    @After
    public void tearDown() throws IOException {
        RolePickerNlsCache.clear();
        if (fixture != null) {
            try { Files.deleteIfExists(fixture); } catch (IOException ignored) {}
        }
    }

    private Map<String, Object> parse(String json) {
        @SuppressWarnings("unchecked")
        Map<String, Object> m = GSON.fromJson(json, Map.class);
        return m;
    }

    @Test
    @SuppressWarnings("unchecked")
    public void buildsExactAndTemplateSections() {
        String json = RolePickerNlsCache.buildNlsReverseJson(List.of(fixture.toString()));
        assertFalse("result must not be the empty-fallback object", "{}".equals(json));

        Map<String, Object> out = parse(json);
        assertTrue(out.containsKey("exact"));
        assertTrue(out.containsKey("templates"));

        Map<String, String> exact = (Map<String, String>) out.get("exact");
        assertEquals("tab_accounts", exact.get("Accounts"));        // plain
        assertEquals("tab_accounts", exact.get("账户"));            // zh plain
        assertEquals("tab_security_device", exact.get("保安編碼器")); // HTML + &nbsp; stripped to visible text

        List<List<String>> templates = (List<List<String>>) out.get("templates");
        assertEquals(2, templates.size());
        assertTrue(templates.contains(List.of("Hello (.*?)", "greeting_user")));
        assertTrue(templates.contains(List.of("你好 (.*?)", "greeting_user")));
    }

    @Test
    public void visibleTextBasedKeysNotRawHtml() {
        // Guard against the historical bug: key must be the rendered visible text, not the raw
        // "<img...>" string (otherwise the browser-computed accessible name would never match).
        String json = RolePickerNlsCache.buildNlsReverseJson(List.of(fixture.toString()));
        Map<String, Object> out = parse(json);
        @SuppressWarnings("unchecked")
        Map<String, String> exact = (Map<String, String>) out.get("exact");
        assertFalse("exact keys must not contain raw HTML tags",
                exact.keySet().stream().anyMatch(k -> k.contains("<")));
    }

    @Test
    public void fileOrderDoesNotChangeResult() throws IOException {
        Path a = Files.createTempFile("nls-a-", ".json");
        Path b = Files.createTempFile("nls-b-", ".json");
        try {
            Files.write(a, "{\"en\":{\"a_key\":\"Apple\"}}".getBytes(StandardCharsets.UTF_8));
            Files.write(b, "{\"en\":{\"b_key\":\"Banana\"}}".getBytes(StandardCharsets.UTF_8));
            String ab = RolePickerNlsCache.buildNlsReverseJson(List.of(a.toString(), b.toString()));
            String ba = RolePickerNlsCache.buildNlsReverseJson(List.of(b.toString(), a.toString()));
            assertEquals("same file set (any order) must hit one cache key", ab, ba);
        } finally {
            Files.deleteIfExists(a);
            Files.deleteIfExists(b);
        }
    }

    @Test
    public void emptyListFallsBackToEmptyObject() {
        assertEquals("{}", RolePickerNlsCache.buildNlsReverseJson(List.of()));
    }

    @Test
    public void nullListFallsBackToEmptyObject() {
        assertEquals("{}", RolePickerNlsCache.buildNlsReverseJson((List<String>) null));
    }

    @Test
    public void missingFileFallsBackToEmptyObject() {
        // readAndParse throws; the builder must swallow it and return "{}" so picking degrades gracefully.
        assertEquals("{}", RolePickerNlsCache.buildNlsReverseJson(
                List.of("no/such/file/picker-missing-nls.json")));
    }

    @Test
    public void fileWithNoEntriesFallsBackToEmptyObject() throws IOException {
        Path empty = Files.createTempFile("nls-empty-", ".json");
        try {
            Files.write(empty, "{\"en\":{}}".getBytes(StandardCharsets.UTF_8));
            assertEquals("{}", RolePickerNlsCache.buildNlsReverseJson(List.of(empty.toString())));
        } finally {
            Files.deleteIfExists(empty);
        }
    }

    @Test
    public void singleFileOverloadEqualsListOverload() {
        String one = RolePickerNlsCache.buildNlsReverseJson(fixture.toString());
        String list = RolePickerNlsCache.buildNlsReverseJson(List.of(fixture.toString()));
        assertEquals(one, list);
    }
}
