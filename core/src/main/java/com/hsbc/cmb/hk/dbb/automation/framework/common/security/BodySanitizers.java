package com.hsbc.cmb.hk.dbb.automation.framework.common.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 请求/响应体脱敏<b>策略链</b>（SensitiveDataSanitizer 拆分专项 · 阶段 1 抽取）。
 *
 * <p>设计：{@link #sanitizeBody(String)} 退化为<b>按固定顺序遍历不可变策略列表</b>的派发器 ——
 * JSON 树递归 → XML/SOAP 双通道 → form-urlencoded → 纯文本兜底。策略返回 {@code null} 表示
 * 「不适用或解析失败」，交由下一个策略接手；<b>任何一条链路失败都不会原样放行</b>（合规铁律）。
 *
 * <p>历史背景（为什么要分四链）：原实现仅当 body 以 {@code {} 或 [} 开头才处理，
 * 导致 XML（SOAP/ISO20022）、{@code application/x-www-form-urlencoded} 登录表单、纯文本
 * <b>一律明文输出</b>。四条链各自补齐一类格式，且 JSON/XML 链做<b>深度不限</b>的递归
 * （真实响应多为 {@code {"data":{"user":{"token":"..."}}}} 形态，单层正则全部漏网）。
 *
 * <p>本类不持有可变状态；敏感键/值判定与掩码经 {@link SanitizerRules}，纯文本兜底经 {@link FreeTextScanner}。
 */
final class BodySanitizers {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** JSON 树遍历的最大深度，防御恶意深嵌套导致的栈溢出。 */
    private static final int MAX_DEPTH = 64;

    /**
     * form-urlencoded 键值对匹配：group(1)=key，group(2)=分隔符，group(3)=value。
     */
    private static final Pattern FORM_PAIR = Pattern.compile("([^=&?]+)(=)([^&]*)");

    /**
     * XML 元素匹配：group(1)=开标签(含属性)，group(2)=标签名，group(3)=文本内容，group(4)=闭标签。
     * <p>用于 SOAP / ISO20022 报文；仅处理纯文本叶子节点，属性敏感值另由 {@link #XML_ATTR} 处理。
     */
    private static final Pattern XML_ELEMENT = Pattern.compile(
            "(<\\s*([\\w:.-]+)[^>/]*>)([^<]*)(<\\s*/\\s*\\2\\s*>)");

    /** XML 属性匹配：group(1)=属性名，group(2)=引号，group(3)=值。 */
    private static final Pattern XML_ATTR = Pattern.compile(
            "([\\w:.-]+)\\s*=\\s*([\"'])([^\"']*)\\2");

    /** 体脱敏策略：{@code trimmed} 为已 trim 的入参，便于格式判定；返回 {@code null} 表示交下一个策略。 */
    private interface BodyStrategy {
        String sanitize(String body, String trimmed);
    }

    /**
     * 策略链（顺序即语义，不可随意调整）：
     * <ol>
     *   <li>JSON 对象/数组 → Jackson 树递归（深度不限）；解析失败返回 null 落到后续链，绝不原样放行；</li>
     *   <li>XML/SOAP → 元素文本 + 属性值双通道；</li>
     *   <li>form-urlencoded → 键值对逐个匹配（含 {@code =} 且（含 {@code &} 或整体单键值）且不含空白）；</li>
     * </ol>
     * 全部不适用时由 {@link #sanitizeBody(String)} 落到纯文本兜底。
     */
    private static final List<BodyStrategy> STRATEGIES = List.of(
            (body, trimmed) -> (trimmed.startsWith("{") || trimmed.startsWith("[")) ? sanitizeJson(trimmed) : null,
            (body, trimmed) -> trimmed.startsWith("<") ? sanitizeXml(body) : null,
            (body, trimmed) -> isFormLike(trimmed) ? sanitizeForm(body) : null);

    private BodySanitizers() {
    }

    /**
     * 脱敏请求/响应体：按固定顺序遍历 {@link #STRATEGIES}，命中即返回；全不适用时走纯文本兜底。
     *
     * @param body 原始体；null/空原样返回
     * @return 脱敏后的体
     */
    static String sanitizeBody(String body) {
        if (body == null || body.isEmpty()) {
            return body;
        }
        String trimmed = body.trim();
        for (BodyStrategy strategy : STRATEGIES) {
            String sanitized = strategy.sanitize(body, trimmed);
            if (sanitized != null) {
                return sanitized;
            }
        }
        // 纯文本兜底：宁可过度遮蔽，不可漏出
        return FreeTextScanner.sanitizeFreeText(body);
    }

    /** form-urlencoded 特征判定：含 {@code =} 且（含 {@code &} 或整体为单个 k=v），且不含空白换行（排除自然语言）。 */
    private static boolean isFormLike(String trimmed) {
        return trimmed.indexOf('=') > 0
                && (trimmed.indexOf('&') > 0 || !trimmed.matches(".*\\s.*"));
    }

    // ═══════════════════════════════════════════════════════════════
    // JSON：Jackson 树递归
    // ═══════════════════════════════════════════════════════════════

    /**
     * 用 Jackson 递归遍历 JSON 树并就地遮蔽敏感字段。
     *
     * @return 脱敏后的 JSON 字符串；解析失败返回 null（由策略链降级到后续链，绝不原样放行）
     */
    private static String sanitizeJson(String json) {
        try {
            JsonNode root = MAPPER.readTree(json);
            maskNode(root, 0);
            return MAPPER.writeValueAsString(root);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 递归遮蔽节点。命中敏感 key 时，无论其值是标量、对象还是数组，<b>整棵子树</b>都替换为掩码 ——
     * 例如 {@code "credentials":{...}} 下的所有内容都不应出域。
     *
     * <p>修复 R3：超过 {@link #MAX_DEPTH} 的节点不再原样保留（否则深嵌套敏感字段会明文出域），
     * 而是整体掩码：标量直接替换；容器节点由调用方（持有父节点引用）删除该字段。
     */
    private static void maskNode(JsonNode node, int depth) {
        if (node == null) {
            return;
        }
        if (depth > MAX_DEPTH) {
            maskNodeDeep(node);
            throw new MaxDepthExceededException();
        }

        if (node.isObject()) {
            ObjectNode obj = (ObjectNode) node;
            // 先收集字段名，避免遍历中修改导致 ConcurrentModificationException
            Iterator<String> names = obj.fieldNames();
            List<String> fields = new ArrayList<>();
            while (names.hasNext()) {
                fields.add(names.next());
            }

            for (String field : fields) {
                if (SanitizerRules.INSTANCE.isBodyKey(field)) {
                    // 命中：整棵子树替换为掩码（对象/数组/标量一律）
                    obj.put(field, SanitizerRules.MASK);
                } else if (obj.get(field).isValueNode()
                        && SanitizerRules.INSTANCE.isValueSensitive(obj.get(field).asText())) {
                    // 字段名非敏感但值本身形如 PAN/IBAN/HKID/轨道数据 → 按值脱敏
                    obj.put(field, SanitizerRules.MASK);
                } else {
                    try {
                        maskNode(obj.get(field), depth + 1);
                    } catch (MaxDepthExceededException e) {
                        // 修复 R3：超深子节点整体删除，避免深嵌套敏感值出域
                        obj.remove(field);
                    }
                }
            }
        } else if (node.isArray()) {
            ArrayNode arr = (ArrayNode) node;
            for (int i = 0; i < arr.size(); i++) {
                JsonNode child = arr.get(i);
                if (child.isValueNode() && SanitizerRules.INSTANCE.isValueSensitive(child.asText())) {
                    arr.set(i, TextNode.valueOf(SanitizerRules.MASK));
                } else {
                    try {
                        maskNode(child, depth + 1);
                    } catch (MaxDepthExceededException e) {
                        arr.remove(i);
                    }
                }
            }
        }
        // 标量节点：无 key 上下文，由父层决定是否遮蔽
    }

    /** 超深子树兜底：递归把每个标量替换为掩码（容器保留结构但内容已掩码）。 */
    private static void maskNodeDeep(JsonNode node) {
        if (node == null) {
            return;
        }
        if (node.isObject()) {
            ObjectNode obj = (ObjectNode) node;
            Iterator<String> names = obj.fieldNames();
            List<String> fields = new ArrayList<>();
            while (names.hasNext()) {
                fields.add(names.next());
            }
            for (String field : fields) {
                JsonNode child = obj.get(field);
                if (child.isValueNode()) {
                    obj.put(field, SanitizerRules.MASK);
                } else {
                    maskNodeDeep(child);
                }
            }
        } else if (node.isArray()) {
            ArrayNode arr = (ArrayNode) node;
            for (int i = 0; i < arr.size(); i++) {
                JsonNode child = arr.get(i);
                if (child.isValueNode()) {
                    arr.set(i, TextNode.valueOf(SanitizerRules.MASK));
                } else {
                    maskNodeDeep(child);
                }
            }
        }
    }

    /** 超深中断信号：仅用于 unwind 调用栈，不对外抛出。 */
    private static final class MaxDepthExceededException extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }

    // ═══════════════════════════════════════════════════════════════
    // XML / SOAP
    // ═══════════════════════════════════════════════════════════════

    /**
     * 脱敏 XML 报文：元素文本内容 + 属性值双通道。
     * <p>银行系统大量使用 SOAP / ISO20022，原实现对 XML 完全不处理，{@code <Password>s3cr3t</Password>} 直接明文落盘。
     */
    private static String sanitizeXml(String xml) {
        // ① 元素文本：<Password>xxx</Password>
        Matcher m = XML_ELEMENT.matcher(xml);
        StringBuffer sb = new StringBuffer(xml.length());
        while (m.find()) {
            String tagName = m.group(2);
            String text = m.group(3);
            String replacement;
            if ((SanitizerRules.INSTANCE.isBodyKey(stripNamespace(tagName))
                    || SanitizerRules.INSTANCE.isValueSensitive(text)) && text != null && !text.isEmpty()) {
                replacement = m.group(1) + SanitizerRules.MASK + m.group(4);
            } else {
                replacement = m.group(0);
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);

        // ② 属性值：<Card number="4111..." />
        Matcher am = XML_ATTR.matcher(sb.toString());
        StringBuffer out = new StringBuffer(sb.length());
        while (am.find()) {
            String attrName = am.group(1);
            String quote = am.group(2);
            String value = am.group(3);
            String replacement;
            if ((SanitizerRules.INSTANCE.isBodyKey(stripNamespace(attrName))
                    || SanitizerRules.INSTANCE.isValueSensitive(value)) && value != null && !value.isEmpty()) {
                replacement = attrName + "=" + quote + SanitizerRules.MASK + quote;
            } else {
                replacement = am.group(0);
            }
            am.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        am.appendTail(out);
        return out.toString();
    }

    /** 去掉 XML 命名空间前缀（{@code ns:Password} → {@code Password}）。 */
    private static String stripNamespace(String name) {
        if (name == null) {
            return null;
        }
        int colon = name.lastIndexOf(':');
        return colon >= 0 && colon < name.length() - 1 ? name.substring(colon + 1) : name;
    }

    // ═══════════════════════════════════════════════════════════════
    // form-urlencoded
    // ═══════════════════════════════════════════════════════════════

    /**
     * 脱敏 {@code application/x-www-form-urlencoded} 体。
     * <p>登录表单 {@code username=alice&password=s3cr3t} 在原实现下因不以 {@code {} 开头而完全不脱敏。
     * <p>包级可见：文本层（{@link FreeTextScanner#sanitizeLine(String)}）复用同一实现，
     * 避免「同一键值遮蔽规则两处实现」的口径漂移。
     */
    static String sanitizeForm(String form) {
        Matcher m = FORM_PAIR.matcher(form);
        StringBuffer sb = new StringBuffer(form.length());
        while (m.find()) {
            String key = m.group(1);
            String value = m.group(3);
            String replacement;
            if ((SanitizerRules.INSTANCE.isBodyKey(SanitizerRules.urlDecodeQuiet(key))
                    || SanitizerRules.INSTANCE.isValueSensitive(value)) && value != null && !value.isEmpty()) {
                replacement = key + m.group(2) + SanitizerRules.MASK;
            } else {
                replacement = m.group(0);
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        return sb.toString();
    }
}
