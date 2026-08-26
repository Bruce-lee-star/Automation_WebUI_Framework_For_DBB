package com.hsbc.cmb.hk.dbb.automation.framework.web.route.monitor;

import com.hsbc.cmb.hk.dbb.automation.framework.web.route.capture.NetworkRequestState;
import com.hsbc.cmb.hk.dbb.automation.framework.web.route.core.ApiCaptureContext;
import com.hsbc.cmb.hk.dbb.automation.framework.web.route.core.RouteHandleType;
import com.hsbc.cmb.hk.dbb.automation.framework.web.route.core.RouteRule;
import com.hsbc.cmb.hk.dbb.automation.framework.web.utils.LoggingConfigUtil;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/**
 * Firefox/WebKit 的无拦截 Monitor 注册表。
 *
 * <p>只管理可由 Playwright 请求/响应元数据完成的纯 Monitor；不创建 Route，
 * 不调用 page.route/context.route，也不持有可变 RouteRule。</p>
 */
public final class PassiveMonitorRegistry {
    private static final Logger LOGGER = LoggerFactory.getLogger(PassiveMonitorRegistry.class);
    private static final Map<Page, Map<String, Entry>> PAGE_RULES = new ConcurrentHashMap<>();
    private static final Map<BrowserContext, Map<String, Entry>> CONTEXT_RULES = new ConcurrentHashMap<>();

    private PassiveMonitorRegistry() {
    }

    /** 仅接管不需要 body、也不包含 route 行为或高级请求条件的纯 Monitor。 */
    public static boolean isEligible(RouteRule rule) {
        return rule != null
                && rule.getType() == RouteHandleType.MONITOR
                && rule.isMonitorEnabled()
                && (rule.getJsonPathAssertions() == null || rule.getJsonPathAssertions().isEmpty())
                && isEmpty(rule.getRequestHeadersToSet())
                && isEmpty(rule.getRequestHeadersToRemove())
                && isEmpty(rule.getRequestBodyFieldsToModify())
                && isEmpty(rule.getRequestBodyFieldsToAdd())
                && isEmpty(rule.getRequestBodyFieldsToRemove())
                && isBlank(rule.getModifyMethod())
                && rule.getDelayMs() == 0
                && rule.getDelayMinMs() == 0
                && rule.getDelayMaxMs() == 0
                && isEmpty(rule.getMatchHeaders())
                && isEmpty(rule.getMatchQuery())
                && isBlank(rule.getMatchBodyRegex())
                && isBlank(rule.getMatchContentType())
                && isBlank(rule.getMatchReferrer())
                && isBlank(rule.getMatchOrigin())
                && isBlank(rule.getMatchFrameUrl());
    }

    public static void register(Page page, String pattern, RouteRule rule) {
        if (page == null || !isEligible(rule)) return;
        PAGE_RULES.computeIfAbsent(page, ignored -> new ConcurrentHashMap<>())
                .put(pattern, new Entry(MonitorRuleSnapshot.from(rule)));
    }

    public static void register(BrowserContext context, String pattern, RouteRule rule) {
        if (context == null || !isEligible(rule)) return;
        CONTEXT_RULES.computeIfAbsent(context, ignored -> new ConcurrentHashMap<>())
                .put(pattern, new Entry(MonitorRuleSnapshot.from(rule)));
    }

    /** 由 Playwright onResponse 调用；异常只会触发既有 fail-fast，不会回抛到浏览器回调。 */
    public static void process(NetworkRequestState state) {
        if (state == null || state.status() == null || !state.markProcessed()) return;
        Entry entry = find(state);
        if (entry == null || !entry.accepting()) return;
        try {
            ApiCaptureContext context = ApiCaptureContext.forContext(state.page().context());
            MonitorResponseProcessor.processEvent(entry.snapshot, context,
                    new MonitorResponse(state.url(), state.status(), null, state.method(),
                            state.requestBody(), state.requestHeaders(), state.responseHeaders()));
            entry.onMatched();
        } catch (Exception e) {
            LoggingConfigUtil.logTraceIfVerbose(LOGGER,
                    "[PassiveMonitorRegistry] Passive monitor processing failed: {}", e.getMessage());
        }
    }

    public static void remove(Page page, String pattern) {
        remove(PAGE_RULES, page, pattern);
    }

    public static void remove(BrowserContext context, String pattern) {
        remove(CONTEXT_RULES, context, pattern);
    }

    public static void clear(Page page) {
        if (page != null) PAGE_RULES.remove(page);
    }

    public static void clear(BrowserContext context) {
        if (context != null) CONTEXT_RULES.remove(context);
    }

    public static void clear(Object context) {
        if (context instanceof Page page) clear(page);
        else if (context instanceof BrowserContext browserContext) clear(browserContext);
    }

    private static Entry find(NetworkRequestState state) {
        Entry pageEntry = findBest(PAGE_RULES.get(state.page()), state);
        if (pageEntry != null) return pageEntry;
        try {
            return findBest(CONTEXT_RULES.get(state.page().context()), state);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static Entry findBest(Map<String, Entry> entries, NetworkRequestState state) {
        if (entries == null || entries.isEmpty()) return null;
        Entry best = null;
        int specificity = -1;
        for (Entry entry : entries.values()) {
            MonitorRuleSnapshot snapshot = entry.snapshot;
            if (!entry.accepting() || !entry.urlPattern.matcher(state.url()).matches()) continue;
            if (snapshot.matchMethod() != null && !snapshot.matchMethod().equalsIgnoreCase(state.method())) continue;
            if (snapshot.onlyMainFrame() && !state.mainFrame()) continue;
            if (snapshot.onlyApiCall() && !isApiResource(state.resourceType())) continue;
            if (!snapshot.resourceTypes().isEmpty()
                    && !snapshot.resourceTypes().contains(normalize(state.resourceType()))) continue;
            int candidate = snapshot.urlPattern().replace("*", "").length();
            if (candidate > specificity) {
                best = entry;
                specificity = candidate;
            }
        }
        return best;
    }

    private static boolean isApiResource(String resourceType) {
        String normalized = normalize(resourceType);
        return "xhr".equals(normalized) || "fetch".equals(normalized);
    }

    private static String normalize(String resourceType) {
        return resourceType == null ? "" : resourceType.toLowerCase();
    }

    private static <K> void remove(Map<K, Map<String, Entry>> registry, K context, String pattern) {
        Map<String, Entry> entries = registry.get(context);
        if (entries == null) return;
        entries.remove(pattern);
        if (entries.isEmpty()) registry.remove(context, entries);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static boolean isEmpty(Map<?, ?> value) {
        return value == null || value.isEmpty();
    }

    private static boolean isEmpty(Iterable<?> value) {
        return value == null || !value.iterator().hasNext();
    }

    private static final class Entry {
        private final MonitorRuleSnapshot snapshot;
        private final Pattern urlPattern;
        private final long createdAt = System.currentTimeMillis();
        private final AtomicInteger matches = new AtomicInteger();
        private final AtomicBoolean stopped = new AtomicBoolean();

        private Entry(MonitorRuleSnapshot snapshot) {
            this.snapshot = snapshot;
            this.urlPattern = glob(snapshot.urlPattern());
        }

        private boolean accepting() {
            return !stopped.get() && (snapshot.timeoutMs() <= 0
                    || System.currentTimeMillis() - createdAt <= snapshot.timeoutMs());
        }

        private void onMatched() {
            int count = matches.incrementAndGet();
            if (snapshot.autoStopOnMatch() && count >= Math.max(1, snapshot.minMatches())) {
                stopped.set(true);
            }
        }

        private static Pattern glob(String glob) {
            StringBuilder regex = new StringBuilder("^");
            for (int i = 0; i < glob.length(); i++) {
                char c = glob.charAt(i);
                if (c == '*' && i + 1 < glob.length() && glob.charAt(i + 1) == '*') {
                    regex.append(".*");
                    i++;
                } else if (c == '*') {
                    // 修复 P1-8：单层通配符只匹配路径段内字符（不含 '/'），避免误跨目录匹配/路径遍历。
                    // 跨层贪婪匹配由 '**'（下方转 '.*'）承担。
                    regex.append("[^/?]*");
                } else {
                    regex.append(Pattern.quote(String.valueOf(c)));
                }
            }
            return Pattern.compile(regex.append('$').toString());
        }
    }
}
