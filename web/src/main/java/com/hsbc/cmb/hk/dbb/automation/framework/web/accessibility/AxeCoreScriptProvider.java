package com.hsbc.cmb.hk.dbb.automation.framework.web.accessibility;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.microsoft.playwright.Page;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * axe-core 脚本执行引擎（企业级解耦层）。
 * <p>
 * 框架自带 {@code axe.min.js} 资源，经 {@link Page#addScriptTag} 注入目标页面，再经
 * {@link Page#evaluate} 调用 {@code axe.run(...)} 取回结果 JSON，自行映射为框架自有
 * {@link AxeRule}/{@link AxeNode} 模型。彻底消除对 {@code com.deque.html.axecore:playwright}
 * Java 包装器的编译期依赖，使 axe-core 版本与 Playwright 版本相互独立演进。
 * <p>
 * 设计铁律（对齐企业级标准）：
 * <ul>
 *   <li>线程安全：自带资源只读一次并缓存（volatile + 双重检查锁），按 page 注入彼此隔离；
 *       结果模型不可变。</li>
 *   <li>幂等注入：每次扫描前探测 {@code window.axe} 是否已就绪，避免重复注入/覆盖。</li>
 *   <li>健壮错误处理：资源缺失 / 注入失败 / 脚本执行失败均抛 {@link AxeCoreException}（语义化），
 *       而非裸 NPE 或 IOException。</li>
 *   <li>入参校验：page / config 非 null。</li>
 *   <li>可观测性：日志路由回本类 logger，保持生产溯源一致。</li>
 * </ul>
 */
public final class AxeCoreScriptProvider {

    private static final Logger logger = LoggerFactory.getLogger(AxeCoreScriptProvider.class);

    /** classpath 资源路径（以 / 开头，对应 web 模块 resources 根）。 */
    static final String AXE_SCRIPT_RESOURCE =
            "/com/hsbc/cmb/hk/dbb/automation/framework/web/accessibility/axe.min.js";

    private static final Gson GSON = new Gson();

    /** axe.min.js 内容缓存：classpath 资源只读一次。volatile + 双重检查保证线程安全可见性。 */
    private static volatile String axeScriptCache;

    private AxeCoreScriptProvider() {
        // 纯静态工具类，禁止实例化
    }

    /** 单次扫描的原始结果（violations / incomplete / passes）。 */
    public static final class AxeRunResult {
        private final List<AxeRule> violations;
        private final List<AxeRule> incomplete;
        private final List<AxeRule> passes;

        AxeRunResult(List<AxeRule> violations, List<AxeRule> incomplete, List<AxeRule> passes) {
            this.violations = violations;
            this.incomplete = incomplete;
            this.passes = passes;
        }

        public List<AxeRule> violations() { return violations; }

        public List<AxeRule> incomplete() { return incomplete; }

        public List<AxeRule> passes() { return passes; }
    }

    /**
     * 在指定页面执行 axe-core 扫描。
     *
     * @param page           待扫描页面（非 null）
     * @param config         扫描配置（非 null）
     * @param contextSelector 限定扫描范围的 CSS 选择器；null / 空串表示整页
     * @return 扫描原始结果（框架自有模型）
     * @throws AxeCoreException 资源缺失、注入失败或脚本执行失败时抛出（语义化）
     */
    public static AxeRunResult runAxe(Page page, AxeCoreScanner.AxeScanConfig config, String contextSelector) {
        if (page == null) {
            throw new AxeCoreException("page must not be null");
        }
        if (config == null) {
            throw new AxeCoreException("config must not be null");
        }

        String script = loadScript();
        injectIfNeeded(page, script);

        Map<String, Object> arg = new HashMap<>();
        arg.put("context", contextSelector);
        arg.put("options", buildOptions(config));

        String evalScript = "async (arg) => {"
                + "  const ctx = (arg.context && arg.context.length) ? arg.context : document;"
                + "  const results = await axe.run(ctx, arg.options || {});"
                + "  return JSON.stringify(results);"
                + "}";

        Object raw;
        try {
            raw = page.evaluate(evalScript, arg);
        } catch (Exception e) {
            throw new AxeCoreException("axe.run evaluation failed on page: " + page.url(), e);
        }

        String json = (raw instanceof String) ? (String) raw : String.valueOf(raw);
        return parseResults(json);
    }

    /** 若页面尚未注入 axe，则注入自带脚本（幂等：已注入则跳过）。 */
    private static void injectIfNeeded(Page page, String script) {
        Boolean ready;
        try {
            Object probe = page.evaluate("typeof window.axe !== 'undefined' && typeof window.axe.run === 'function'");
            ready = Boolean.TRUE.equals(probe);
        } catch (Exception e) {
            throw new AxeCoreException("Failed to probe axe availability on page: " + page.url(), e);
        }
        if (!ready) {
            try {
                page.addScriptTag(new Page.AddScriptTagOptions().setContent(script));
            } catch (Exception e) {
                throw new AxeCoreException("Failed to inject axe-core script into page: " + page.url(), e);
            }
            logger.debug("axe-core script injected into page: {}", page.url());
        }
    }

    /** 把框架配置映射为 axe.run 的 options 对象（runOnly tags/rules + rules.disable）。 */
    private static Map<String, Object> buildOptions(AxeCoreScanner.AxeScanConfig config) {
        Map<String, Object> options = new HashMap<>();
        List<String> tags = config.getTags();
        List<String> rules = config.getRules();
        List<String> exclude = config.getExcludeRules();
        if (tags != null && !tags.isEmpty()) {
            options.put("runOnly", Map.of("type", "tag", "values", new ArrayList<>(tags)));
        } else if (rules != null && !rules.isEmpty()) {
            options.put("runOnly", Map.of("type", "rule", "values", new ArrayList<>(rules)));
        }
        if (exclude != null && !exclude.isEmpty()) {
            options.put("rules", Map.of("disable", new ArrayList<>(exclude)));
        }
        return options;
    }

    /** 将 axe.run 结果 JSON 解析为框架自有模型。 */
    private static AxeRunResult parseResults(String json) {
        JsonObject root = GSON.fromJson(json, JsonObject.class);
        List<AxeRule> violations = mapRules(root.get("violations"));
        List<AxeRule> incomplete = mapRules(root.get("incomplete"));
        List<AxeRule> passes = mapRules(root.get("passes"));
        return new AxeRunResult(violations, incomplete, passes);
    }

    private static List<AxeRule> mapRules(JsonElement element) {
        List<AxeRule> rules = new ArrayList<>();
        if (element == null || !element.isJsonArray()) {
            return rules;
        }
        for (JsonElement item : element.getAsJsonArray()) {
            if (!item.isJsonObject()) {
                continue;
            }
            JsonObject o = item.getAsJsonObject();
            String id = str(o, "id");
            String impact = strOrNull(o, "impact");
            String description = strOrNull(o, "description");
            String help = strOrNull(o, "help");
            String helpUrl = strOrNull(o, "helpUrl");
            List<AxeNode> nodes = mapNodes(o.get("nodes"));
            rules.add(new AxeRule(id, impact, description, help, helpUrl, nodes));
        }
        return rules;
    }

    private static List<AxeNode> mapNodes(JsonElement element) {
        List<AxeNode> nodes = new ArrayList<>();
        if (element == null || !element.isJsonArray()) {
            return nodes;
        }
        for (JsonElement item : element.getAsJsonArray()) {
            if (!item.isJsonObject()) {
                continue;
            }
            JsonObject o = item.getAsJsonObject();
            List<String> target = mapTarget(o.get("target"));
            String failureSummary = strOrNull(o, "failureSummary");
            String html = strOrNull(o, "html");
            nodes.add(new AxeNode(target, failureSummary, html));
        }
        return nodes;
    }

    private static List<String> mapTarget(JsonElement element) {
        List<String> target = new ArrayList<>();
        if (element == null || !element.isJsonArray()) {
            return target;
        }
        for (JsonElement t : element.getAsJsonArray()) {
            // axe target 元素通常为 CSS 选择器字符串；用 getAsString 避免 JsonPrimitive.toString() 携带 JSON 引号
            if (t.isJsonPrimitive() && t.getAsJsonPrimitive().isString()) {
                target.add(t.getAsString());
            } else {
                target.add(t.toString());
            }
        }
        return target;
    }

    private static String str(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return (e != null && !e.isJsonNull()) ? e.getAsString() : "";
    }

    private static String strOrNull(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return (e != null && !e.isJsonNull()) ? e.getAsString() : null;
    }

    /** 加载 axe.min.js 资源（classpath），仅加载一次并缓存。 */
    static String loadScript() {
        String cached = axeScriptCache;
        if (cached != null) {
            return cached;
        }
        synchronized (AxeCoreScriptProvider.class) {
            cached = axeScriptCache;
            if (cached != null) {
                return cached;
            }
            String loaded = doLoad();
            axeScriptCache = loaded;
            return loaded;
        }
    }

    private static String doLoad() {
        try (InputStream in = AxeCoreScriptProvider.class.getResourceAsStream(AXE_SCRIPT_RESOURCE)) {
            if (in == null) {
                throw new AxeCoreException("axe-core script resource not found on classpath: " + AXE_SCRIPT_RESOURCE
                        + ". Verify the web module bundles axe.min.js under its resources directory.");
            }
            byte[] bytes = readAllBytes(in);
            if (bytes.length == 0) {
                throw new AxeCoreException("axe-core script resource is empty: " + AXE_SCRIPT_RESOURCE);
            }
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new AxeCoreException("Failed to read axe-core script resource: " + AXE_SCRIPT_RESOURCE, e);
        }
    }

    private static byte[] readAllBytes(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            bos.write(buf, 0, n);
        }
        return bos.toByteArray();
    }
}
