package com.hsbc.cmb.hk.dbb.automation.framework.route.v2.util;

import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.PathNotFoundException;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathFactory;
import java.io.StringReader;
import java.io.StringWriter;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

/**
 * 内容类型感知的字段替换器 —— MOCK intercept / mockBodyFromFile 的字段覆盖。
 *
 * <p>替换语义按响应 Content-Type 路由（不把 JSON 当默认）：
 * <ul>
 *   <li>{@code application/json} 家族 → JSONPath（{@code JsonPath.set}，缺失字段按路径创建）；</li>
 *   <li>{@code application/x-www-form-urlencoded} → 表单字段（存在则替换首个值，不存在则追加）；</li>
 *   <li>{@code application/xml} / {@code text/xml} → XPath 文本节点替换（JDK 内置，
 *       XXE 已防护：禁 DOCTYPE / 外部实体）；</li>
 *   <li>其它文本类型 → 字面字符串首次出现替换（path 即被替换的子串）；</li>
 *   <li>未知 / 二进制 → 明确失败（保留原 body，不猜测）。</li>
 * </ul>
 *
 * <p>纯函数：不修改入参；单字段替换失败不影响其它字段（fail-open，失败明细可观测）。
 */
public final class FieldReplacer {

    private static final Logger LOGGER = LoggerFactory.getLogger(FieldReplacer.class);

    /** 替换结果：body=替换后的体（失败时保留原体）；failures=失败明细（空=全部成功）。 */
    public record ReplaceResult(String body, List<String> failures) {
        public ReplaceResult {
            failures = Collections.unmodifiableList(new ArrayList<>(failures));
        }
    }

    private FieldReplacer() {
    }

    /** 对 body 依次应用全部字段替换（每个字段独立 fail-open）。 */
    public static ReplaceResult replace(MediaType mediaType, String body, Map<String, Object> replacements) {
        String current = body;
        List<String> failures = new ArrayList<>();
        for (Map.Entry<String, Object> e : replacements.entrySet()) {
            try {
                current = replaceOne(mediaType, current, e.getKey(), e.getValue());
            } catch (Exception ex) {
                failures.add("replace field '" + e.getKey() + "' failed: " + ex.getMessage());
                LOGGER.debug("[RouteV2] field replacement failed path='{}': {}", e.getKey(), ex.toString());
                // 保留当前 body，继续下一个字段
            }
        }
        return new ReplaceResult(current, failures);
    }

    /**
     * 字段替换 + 条件字段修改（when...thenSet，对齐现有 RouteDsl）：
     * 先应用条件替换（满足 when 条件才替换 then 字段；仅 JSON 生效），再应用普通替换（优先级更高）。
     * 每个步骤独立 fail-open，失败明细可观测。
     */
    public static ReplaceResult replace(MediaType mediaType, String body, Map<String, Object> replacements,
                                        java.util.List<com.hsbc.cmb.hk.dbb.automation.framework.route.v2.dsl.ConditionalField> conditionals) {
        String current = body;
        List<String> failures = new ArrayList<>();
        if (conditionals != null && !conditionals.isEmpty()) {
            if (!mediaType.isJson()) {
                failures.add("conditional replacement not applicable: content-type is "
                        + (mediaType.raw() == null ? "<missing>" : mediaType.raw()));
            } else {
                DocumentContext ctx = null;
                for (com.hsbc.cmb.hk.dbb.automation.framework.route.v2.dsl.ConditionalField c : conditionals) {
                    try {
                        if (ctx == null) {
                            ctx = JsonPath.parse(current);
                        }
                        if (ConditionEvaluator.evaluate(ctx, c)) {
                            current = replaceOne(mediaType, current, c.thenPath(), c.value());
                        }
                    } catch (Exception ex) {
                        failures.add("conditional 'when " + c.whenPath() + "' failed: " + ex.getMessage());
                        LOGGER.debug("[RouteV2] conditional replacement failed when='{}': {}", c.whenPath(), ex.toString());
                    }
                }
            }
        }
        ReplaceResult plain = replace(mediaType, current, replacements);
        failures.addAll(plain.failures());
        return new ReplaceResult(plain.body(), failures);
    }

    private static String replaceOne(MediaType mediaType, String body, String path, Object value) throws Exception {
        if (mediaType.isJson()) {
            return replaceJson(body, path, value);
        }
        if (mediaType.isFormUrlEncoded()) {
            return replaceForm(body, path, String.valueOf(value), mediaType.charset());
        }
        if (mediaType.isText()) {
            String subtype = mediaType.subtype();
            if (subtype != null && subtype.contains("xml")) {
                return replaceXml(body, path, String.valueOf(value));
            }
            return replaceText(body, path, String.valueOf(value));
        }
        throw new IllegalStateException("field replacement not applicable: content-type is "
                + (mediaType.raw() == null ? "<missing>" : mediaType.raw()));
    }

    private static String replaceJson(String body, String path, Object value) {
        DocumentContext context;
        try {
            context = JsonPath.parse(body);
        } catch (Exception e) {
            throw new IllegalArgumentException("body is not valid JSON: " + e.getMessage(), e);
        }
        try {
            context.set(path, value);
        } catch (PathNotFoundException e) {
            // 缺失字段：父路径存在时按对象属性创建；父路径也不存在则 put 抛 PathNotFoundException
            context.put(parentPath(path), lastKey(path), value);
        }
        return context.jsonString();
    }

    private static String parentPath(String path) {
        int dot = path.lastIndexOf('.');
        return dot < 0 ? "$" : path.substring(0, dot);
    }

    private static String lastKey(String path) {
        int dot = path.lastIndexOf('.');
        return dot < 0 ? path : path.substring(dot + 1);
    }

    private static String replaceForm(String body, String key, String value, Charset charset) {
        Map<String, List<String>> fields = parseForm(body, charset);
        List<String> values = fields.computeIfAbsent(key, k -> new ArrayList<>());
        if (values.isEmpty()) {
            values.add(value);
        } else {
            values.set(0, value); // 替换首个值（重复键保留其余）
        }
        return encodeForm(fields, charset);
    }

    private static String replaceXml(String body, String xpath, String value) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        // XXE 防护（企业级强制）：禁 DOCTYPE、禁外部实体、禁 include
        try {
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        } catch (javax.xml.parsers.ParserConfigurationException e) {
            throw new IllegalStateException("XML parser does not support XXE protection", e);
        }
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        factory.setNamespaceAware(true);

        Document document = factory.newDocumentBuilder().parse(new InputSource(new StringReader(body)));
        XPath xpathEngine = XPathFactory.newInstance().newXPath();
        NodeList nodes = (NodeList) xpathEngine.evaluate(xpath, document, XPathConstants.NODESET);
        if (nodes.getLength() == 0) {
            throw new IllegalArgumentException("xpath not found: " + xpath);
        }
        nodes.item(0).setTextContent(value);

        Transformer transformer = TransformerFactory.newInstance().newTransformer();
        transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
        StringWriter writer = new StringWriter();
        transformer.transform(new DOMSource(document), new StreamResult(writer));
        return writer.toString();
    }

    private static String replaceText(String body, String literal, String value) {
        if (!body.contains(literal)) {
            throw new IllegalArgumentException("literal not found in body: " + literal);
        }
        return body.replaceFirst(Pattern.quote(literal), Matcher.quoteReplacement(value));
    }

    /** 保序解析表单（LinkedHashMap：编码时保持原始字段顺序）。 */
    static Map<String, List<String>> parseForm(String body, Charset charset) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        if (body == null || body.isEmpty()) {
            return result;
        }
        for (String pair : body.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            String k = eq < 0 ? pair : pair.substring(0, eq);
            String v = eq < 0 ? "" : pair.substring(eq + 1);
            try {
                k = URLDecoder.decode(k, charset);
                v = URLDecoder.decode(v, charset);
            } catch (IllegalArgumentException e) {
                LOGGER.trace("form decode failed, keep raw: {}", e.toString());
            }
            result.computeIfAbsent(k, key -> new ArrayList<>()).add(v);
        }
        return result;
    }

    static String encodeForm(Map<String, List<String>> fields, Charset charset) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, List<String>> e : fields.entrySet()) {
            for (String value : e.getValue()) {
                if (sb.length() > 0) {
                    sb.append('&');
                }
                sb.append(encode(e.getKey(), charset)).append('=').append(encode(value, charset));
            }
        }
        return sb.toString();
    }

    private static String encode(String s, Charset charset) {
        return URLEncoder.encode(s, charset);
    }
}
