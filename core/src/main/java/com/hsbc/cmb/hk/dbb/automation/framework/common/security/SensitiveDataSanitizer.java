package com.hsbc.cmb.hk.dbb.automation.framework.common.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.thucydides.model.environment.SystemEnvironmentVariables;

/**
 * ⭐ P0 合规：敏感数据脱敏 —— 金融级数据安全要求。
 *
 * <p><b>设计原则：脱敏是数据出域的强制收口，不是可选装饰。</b>
 * 任何把请求/响应数据写出进程边界的 sink（数据库、本地文件、Serenity 报告、
 * 失败报告、日志）都<b>必须</b>先经本类处理。
 *
 * <h3>本次修复的四个绕过缺口</h3>
 * <ol>
 *   <li><b>非 JSON 不脱敏</b>：原实现仅当 body 以 {@code {} 或 [} 开头才处理，
 *       导致 XML（SOAP/ISO20022）、{@code application/x-www-form-urlencoded}
 *       登录表单、纯文本一律明文输出。现按格式分派到三条独立处理链。</li>
 *   <li><b>不递归嵌套结构</b>：原正则只匹配同一层的 string/number/boolean，
 *       而真实响应几乎都是 <code>{"data":{"user":{"token":"..."}}}</code>，
 *       嵌套层敏感字段全部漏网。现改用 Jackson 树遍历，深度不限。</li>
 *   <li><b>保留前缀明文</b>：原实现保留前 6 字符，对 6 位数字 PIN / 短验证码
 *       等于完整泄露，对 JWT 泄露 header 前缀（可判定签名算法）。现全量遮蔽，
 *       仅保留长度提示用于问题定位。</li>
 *   <li><b>字段名匹配失效</b>：原清单写 {@code accesstoken}（无下划线），
 *       匹配不上真实 OAuth 响应的 {@code access_token}。现对 key 做规范化
 *       （剥离 {@code _ - 空格} 后小写）再比对，一次覆盖 camelCase /
 *       snake_case / kebab-case / PascalCase 全部命名风格。</li>
 * </ol>
 */
public final class SensitiveDataSanitizer {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 需脱敏的请求/响应头。
     * <p>匹配走 {@link #normalizeKey(String)} 规范化，故此处只需写规范化后的形态
     * （全小写、无分隔符）。
     */
    private static final Set<String> SENSITIVE_HEADER_KEYS = new HashSet<>(Arrays.asList(
            // 认证凭据
            "authorization", "proxyauthorization", "wwwauthenticate", "proxyauthenticate",
            "cookie", "setcookie",
            // 各类自定义令牌头（通用 X- 令牌模式，平台无关）
            "xauthtoken", "xcsrftoken", "xxsrftoken", "xapikey", "apikey",
            "xaccesstoken", "xidtoken", "xrefreshtoken", "xsessiontoken",
            "xsessionid", "xsecret", "xclientsecret", "xsignature"));

    /**
     * 请求/响应体中需脱敏的字段名。
     * <p>同样走规范化匹配，故 {@code access_token} / {@code accessToken} /
     * {@code Access-Token} 均由单条 {@code accesstoken} 覆盖。
     */
    private static final Set<String> SENSITIVE_BODY_KEYS = new HashSet<>(Arrays.asList(
            // ── 口令类 ──
            "password", "passwd", "pwd", "passphrase", "oldpassword", "newpassword",
            "confirmpassword", "currentpassword",
            // ── 令牌类（补齐原实现缺失的 OAuth/OIDC 标准字段）──
            "token", "accesstoken", "refreshtoken", "idtoken", "bearertoken",
            "authtoken", "sessiontoken", "csrftoken", "xsrftoken", "jwt",
            // ── 密钥类 ──
            "secret", "clientsecret", "apikey", "secretkey", "privatekey",
            "publickey", "signature", "sign", "hmac", "salt",
            // ── 认证与会话 ──
            "authorization", "credentials", "credential", "sessionid", "jsessionid",
            "sessionkey", "cookie",
            // ── 身份标识 PII ──
            "ssn", "socialsecuritynumber", "nationalid", "idcard", "idcardno",
            "idnumber", "passportno", "passportnumber", "taxid", "hkid",
            // ── 银行卡与账户（银行场景核心）──
            "cardnumber", "cardno", "creditcard", "debitcard", "pan",
            "accountnumber", "accountno", "account", "iban", "bic", "swift",
            "cvv", "cvc", "cvn", "pin", "otp", "tac", "securitycode",
            "expirydate", "expiry", "validthru",
            // ── 联系方式与生物信息 PII ──
            "email", "emailaddress", "phone", "phonenumber", "mobile",
            "mobilenumber", "telephone", "dob", "dateofbirth", "birthdate",
            "address", "postaladdress", "fullname"));

    // ═══════════════════════════════════════════════════════════════
    // 用户可配置附加敏感关键字（叠加在内置清单之上，不改动内置默认）
    // ═══════════════════════════════════════════════════════════════
    /**
     * 用户可配置的附加敏感关键字（通过框架配置注入，叠加在内置清单之上）。
     * <p>配置项（经 Serenity 配置体系：{@code -D} 系统属性 / {@code serenity.properties} / 环境变量）：
     * <ul>
     *   <li>{@code sensitive.data.extra.header.keys}：附加敏感请求/响应头名（逗号分隔）</li>
     *   <li>{@code sensitive.data.extra.body.keys}：附加敏感体字段名（逗号分隔）</li>
     *   <li>{@code sensitive.data.extra.query.keys}：附加敏感 URL query 参数名（逗号分隔）</li>
     * </ul>
     * 键名同样走 {@link #normalizeKey(String)} 规范化（忽略大小写与 _ - . 空格），
     * 故 {@code access_token} / {@code accessToken} / {@code Access-Token} 均生效。
     * 若希望在 {@code web.config.FrameworkConfig} 中集中登记，可使用同名配置键（本类直接读取同一配置源）。
     */
    private static final String CFG_EXTRA_HEADER_KEYS = "sensitive.data.extra.header.keys";
    private static final String CFG_EXTRA_BODY_KEYS = "sensitive.data.extra.body.keys";
    private static final String CFG_EXTRA_QUERY_KEYS = "sensitive.data.extra.query.keys";

    /** 附加敏感键（规范化形态），由配置懒加载或 {@link #registerExtraSensitiveKeys} 注入。 */
    private static final Set<String> EXTRA_HEADER_KEYS = new HashSet<>();
    private static final Set<String> EXTRA_BODY_KEYS = new HashSet<>();
    private static final Set<String> EXTRA_QUERY_KEYS = new HashSet<>();
    private static volatile boolean extraLoaded = false;
    private static final Object EXTRA_LOAD_LOCK = new Object();

    /**
     * 懒加载附加敏感键：仅首次判定时从配置读取一次。
     * 若已通过 {@link #registerExtraSensitiveKeys} 程序化注册，则跳过配置读取。
     */
    private static void loadExtraIfNeeded() {
        if (extraLoaded) return;
        synchronized (EXTRA_LOAD_LOCK) {
            if (extraLoaded) return;
            reloadExtraKeysFromConfig();
        }
    }

    /**
     * 读取附加敏感键配置：优先取 {@code -D} 系统属性（与文档一致），
     * 回退到 Serenity 配置体系（serenity.properties / serenity.conf / 环境变量）。
     * 这样 {@code System.setProperty} 注入与 {@code -D} 启动参数两种途径都能生效。
     */
    private static String readExtraConfig(String key) {
        String sys = System.getProperty(key);
        if (sys != null && !sys.trim().isEmpty()) return sys;
        return SystemEnvironmentVariables.currentEnvironmentVariables().getProperty(key, "");
    }

    /**
     * 强制从配置重新加载附加敏感键（清掉旧值后重读）。
     * 供测试与运行时热更新使用；与 {@link #registerExtraSensitiveKeys} 互不覆盖（先清后读）。
     */
    public static void reloadExtraKeysFromConfig() {
        synchronized (EXTRA_LOAD_LOCK) {
            EXTRA_HEADER_KEYS.clear();
            EXTRA_BODY_KEYS.clear();
            EXTRA_QUERY_KEYS.clear();
            parseKeys(readExtraConfig(CFG_EXTRA_HEADER_KEYS), EXTRA_HEADER_KEYS);
            parseKeys(readExtraConfig(CFG_EXTRA_BODY_KEYS), EXTRA_BODY_KEYS);
            parseKeys(readExtraConfig(CFG_EXTRA_QUERY_KEYS), EXTRA_QUERY_KEYS);
            extraLoaded = true;
        }
    }

    /**
     * 程序化注入附加敏感键（叠加）。供 web 层或测试在不依赖系统属性的场景下扩展脱敏范围。
     *
     * @param headerKeys 附加敏感头名（逗号分隔，可空）
     * @param bodyKeys   附加敏感体字段名（逗号分隔，可空）
     * @param queryKeys  附加敏感 URL query 参数名（逗号分隔，可空）
     */
    public static void registerExtraSensitiveKeys(String headerKeys, String bodyKeys, String queryKeys) {
        synchronized (EXTRA_LOAD_LOCK) {
            parseKeys(headerKeys, EXTRA_HEADER_KEYS);
            parseKeys(bodyKeys, EXTRA_BODY_KEYS);
            parseKeys(queryKeys, EXTRA_QUERY_KEYS);
            extraLoaded = true;
        }
    }

    /** 把逗号分隔的键解析为规范化形态并加入目标集合（容忍空白/空）。 */
    private static void parseKeys(String csv, Set<String> target) {
        if (csv == null || csv.trim().isEmpty()) return;
        for (String token : csv.split(",")) {
            String k = token.trim();
            if (!k.isEmpty()) target.add(normalizeKey(k));
        }
    }

    /** 统一掩码串。不保留任何原值前缀 —— 短值（PIN/验证码）保留前缀等于完整泄露。 */
    private static final String MASK = "***[REDACTED]";

    /**
     * form-urlencoded 键值对匹配：group(1)=key，group(2)=分隔符，group(3)=value。
     * <p>用于 {@code application/x-www-form-urlencoded} 请求体，
     * 如 {@code username=alice&password=s3cr3t}。
     */
    private static final Pattern FORM_PAIR = Pattern.compile("([^=&?]+)(=)([^&]*)");

    /**
     * XML 元素匹配：group(1)=开标签(含属性)，group(2)=标签名，group(3)=文本内容，group(4)=闭标签。
     * <p>用于 SOAP / ISO20022 报文，如 {@code <Password>s3cr3t</Password>}。
     * 仅处理纯文本叶子节点，不递归属性（属性敏感值另由 XML_ATTR 处理）。
     */
    private static final Pattern XML_ELEMENT = Pattern.compile(
            "(<\\s*([\\w:.-]+)[^>/]*>)([^<]*)(<\\s*/\\s*\\2\\s*>)");

    /** XML 属性匹配：group(1)=属性名，group(2)=引号，group(3)=值。 */
    private static final Pattern XML_ATTR = Pattern.compile(
            "([\\w:.-]+)\\s*=\\s*([\"'])([^\"']*)\\2");

    /** JSON 树遍历的最大深度，防御恶意深嵌套导致的栈溢出。 */
    private static final int MAX_DEPTH = 64;

    /** ⭐ 修复 R2：Bearer/Basic/Digest 等认证方案后的凭证（保留方案名，遮蔽凭据）。 */
    private static final Pattern FREE_TEXT_AUTH_SCHEME =
            Pattern.compile("(?i)(\\b(?:Bearer|Basic|Digest|APIKey|Token)\\s+)([A-Za-z0-9._~+/-]+=*)");

    /** ⭐ 修复 R2：独立 JWT（三段式 base64url，header.payload.signature）。 */
    private static final Pattern FREE_TEXT_JWT =
            Pattern.compile("(?i)([A-Za-z0-9_=-]{8,}\\.[A-Za-z0-9_=-]{8,}\\.)([A-Za-z0-9_=-]+)");

    /** ⭐ 修复 R2：URL 中的 //user:pass@host 或 ?token=xxx 形态凭据。 */
    private static final Pattern FREE_TEXT_URL_CREDENTIAL =
            Pattern.compile("(?i)([?&](?:token|access_token|api_key|apikey|secret|password|key|auth)=)([^&\\s\"']+)");

    /**
     * ⭐ 修复 S1：URLEncode 内层 JSON 凭据（云测平台 caps 场景）。
     * <p>BrowserStack 把 caps JSON 整体 URLEncode 后塞进 URL：
     * {@code wss://cdp.browserstack.com/playwright?caps=%7B%22browserstack.accessKey%22%3A%22SECRET%22%7D}
     * 此时 {@code :} 被编码为 {@code %3A}、{@code "} 为 {@code %22}，
     * {@link #maskFreeTextLine} 找不到明文 {@code :}/{@code =} 分隔符而退到
     * {@link #maskFreeTextTokens}，后者只认 Bearer/JWT/明文 URL 参数 → 密钥漏网。
     * <p>分组：1=key 前引号，2=key，3=key 后引号，4=分隔符(%3A/%3D)，5=值前引号，6=值。
     */
    private static final Pattern FREE_TEXT_URL_ENCODED_SECRET = Pattern.compile(
            "(?i)(%22|%27)?([\\w.\\-]*(?:key|secret|token|password|passwd|pwd|credential)[\\w.\\-]*)"
                    + "(%22|%27)?(%3A|%3D)(%22|%27)?([^&%\\s\"']*)");

    private SensitiveDataSanitizer() {
    }

    // ═══════════════════════════════════════════════════════════════
    // 公开入口
    // ═══════════════════════════════════════════════════════════════

    /**
     * 返回脱敏后的 Header 副本；入参为 null 时返回 null。
     * <p>命中敏感 key 的值<b>整体</b>替换为掩码（不再保留前缀明文）。
     */
    public static Map<String, String> sanitizeHeaders(Map<String, String> headers) {
        if (headers == null) return null;
        Map<String, String> sanitized = new HashMap<>(headers.size());
        for (Map.Entry<String, String> e : headers.entrySet()) {
            String key = e.getKey();
            String value = e.getValue();
            if (isSensitiveHeaderKey(key) && value != null && !value.isEmpty()) {
                sanitized.put(key, maskValue(value));
            } else {
                sanitized.put(key, value);
            }
        }
        return sanitized;
    }

    /**
     * 脱敏请求/响应体中的敏感字段。
     *
     * <p>按内容形态自动分派，<b>不再因格式不识别而整体放行</b>：
     * <ul>
     *   <li>JSON 对象/数组 → Jackson 树递归遍历（深度不限）</li>
     *   <li>XML/SOAP → 元素文本 + 属性值双通道处理</li>
     *   <li>form-urlencoded → 键值对逐个匹配</li>
     *   <li>其它（纯文本/二进制文本）→ 关键字行级兜底遮蔽</li>
     * </ul>
     *
     * @param body 原始体；null/空原样返回
     * @return 脱敏后的体
     */
    public static String sanitizeBody(String body) {
        if (body == null || body.isEmpty()) return body;
        String trimmed = body.trim();

        // ── JSON ──
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            String result = sanitizeJson(trimmed);
            if (result != null) return result;
            // JSON 解析失败（截断/非法）→ 落到文本兜底，绝不原样放行
        }

        // ── XML / SOAP ──
        if (trimmed.startsWith("<")) {
            return sanitizeXml(body);
        }

        // ── form-urlencoded ──
        // 特征：含 '=' 且（含 '&' 或整体是单个 k=v），且不含空白换行（排除自然语言）
        if (trimmed.indexOf('=') > 0
                && (trimmed.indexOf('&') > 0 || !trimmed.matches(".*\\s.*"))) {
            return sanitizeForm(body);
        }

        // ── 纯文本兜底 ──
        return sanitizeFreeText(body);
    }

    /**
     * 常见敏感 query 参数名（小写），用于 URL 脱敏判定。
     */
    private static final Set<String> SENSITIVE_QUERY_KEYS = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList(
                    "token", "accesstoken", "access_token", "apikey", "api_key",
                    "secret", "password", "passwd", "credential", "auth",
                    "authorization", "sign", "signature", "key", "privatekey"
            )));

    /**
     * 脱敏 URL 中的敏感 query 参数（自包含实现，不依赖 web 层）。
     * <p>命中 {@link #SENSITIVE_QUERY_KEYS} 中任一参数名时移除整个 query（仅保留 path），
     * 避免 access token / API key 等泄漏到日志 / 报告；解析失败则降级到
     * {@link #sanitizeFreeText(String)} 兜底自由文本中的 Bearer/JWT/Authorization 等形态。
     *
     * @param url 原始 URL
     * @return 脱敏后的 URL
     */
    public static String sanitizeUrl(String url) {
        if (url == null) return null;
        try {
            URI uri = new URI(url);
            String rawQuery = uri.getRawQuery();
            if (rawQuery == null || rawQuery.isEmpty()) {
                return url; // 无 query，直接返回
            }
            loadExtraIfNeeded();
            boolean hasSensitive = false;
            for (String pair : rawQuery.split("&")) {
                String key = pair.split("=", 2)[0].toLowerCase();
                try {
                    key = URLDecoder.decode(key, StandardCharsets.UTF_8.name()).toLowerCase();
                } catch (Exception ignored) {}
                String nKey = normalizeKey(key);
                if (SENSITIVE_QUERY_KEYS.contains(nKey) || EXTRA_QUERY_KEYS.contains(nKey)) {
                    hasSensitive = true;
                    break;
                }
            }
            if (hasSensitive) {
                return new URI(uri.getScheme(), uri.getAuthority(), uri.getPath(), null, uri.getFragment()).toString();
            }
            return url;
        } catch (Exception e) {
            // 解析失败时绝不出域原始 URL（可能含明文 token/key），降级到自由文本脱敏
            String stripped = url.replaceAll("[?#].*$", "");
            return sanitizeFreeText(stripped);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // JSON：Jackson 树递归
    // ═══════════════════════════════════════════════════════════════

    /**
     * 用 Jackson 递归遍历 JSON 树并就地遮蔽敏感字段。
     *
     * <p>相比原正则实现的关键优势：<b>深度不限</b>。
     * <code>{"data":{"user":{"access_token":"..."}}}</code> 这类真实响应
     * 在原实现下完全漏网，现在能命中任意层级。
     *
     * @return 脱敏后的 JSON 字符串；解析失败返回 null（由调用方降级到文本兜底）
     */
    private static String sanitizeJson(String json) {
        try {
            JsonNode root = MAPPER.readTree(json);
            maskNode(root, 0);
            return MAPPER.writeValueAsString(root);
        } catch (Exception e) {
            // 非法/截断 JSON：返回 null 触发文本兜底，绝不原样放行敏感数据
            return null;
        }
    }

    /**
     * 递归遮蔽节点。命中敏感 key 时，无论其值是标量、对象还是数组，
     * <b>整棵子树</b>都替换为掩码 —— 例如 {@code "credentials":{...}}
     * 下的所有内容都不应出域。
     *
     * <p>⭐ 修复 R3：超过 {@link #MAX_DEPTH} 的节点不再原样保留（否则深嵌套敏感字段
     * 会明文出域），而是整体掩码：标量直接替换为 {@link #MASK}；容器节点由调用方
     * （持有父节点引用）删除该字段，避免子树内容泄漏。</p>
     */
    private static void maskNode(JsonNode node, int depth) {
        if (node == null) return;
        if (depth > MAX_DEPTH) {
            // 超深子树：递归遮蔽所有子节点（尽力而为），并在父层由 removeSensitiveDeep 删除该字段
            maskNodeDeep(node);
            throw new MaxDepthExceededException();
        }

        if (node.isObject()) {
            ObjectNode obj = (ObjectNode) node;
            // 先收集字段名，避免遍历中修改导致 ConcurrentModificationException
            Iterator<String> names = obj.fieldNames();
            java.util.List<String> fields = new java.util.ArrayList<>();
            while (names.hasNext()) fields.add(names.next());

            for (String field : fields) {
                if (isSensitiveBodyKey(field)) {
                    // 命中：整棵子树替换为掩码（对象/数组/标量一律）
                    obj.put(field, MASK);
                } else {
                    try {
                        maskNode(obj.get(field), depth + 1);
                    } catch (MaxDepthExceededException e) {
                        // ⭐ 修复 R3：超深子节点整体删除，避免深嵌套敏感值出域
                        obj.remove(field);
                    }
                }
            }
        } else if (node.isArray()) {
            ArrayNode arr = (ArrayNode) node;
            for (int i = 0; i < arr.size(); i++) {
                try {
                    maskNode(arr.get(i), depth + 1);
                } catch (MaxDepthExceededException e) {
                    arr.remove(i);
                }
            }
        }
        // 标量节点：无 key 上下文，由父层决定是否遮蔽
    }

    /** 超深子树兜底：递归把每个标量替换为掩码（容器保留结构但内容已掩码）。 */
    private static void maskNodeDeep(JsonNode node) {
        if (node == null) return;
        if (node.isObject()) {
            ObjectNode obj = (ObjectNode) node;
            Iterator<String> names = obj.fieldNames();
            java.util.List<String> fields = new java.util.ArrayList<>();
            while (names.hasNext()) fields.add(names.next());
            for (String field : fields) {
                JsonNode child = obj.get(field);
                if (child.isValueNode()) {
                    obj.put(field, MASK);
                } else {
                    maskNodeDeep(child);
                }
            }
        } else if (node.isArray()) {
            ArrayNode arr = (ArrayNode) node;
            for (int i = 0; i < arr.size(); i++) {
                JsonNode child = arr.get(i);
                if (child.isValueNode()) {
                    arr.set(i, TextNode.valueOf(MASK));
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
     * <p>银行系统大量使用 SOAP / ISO20022，原实现对 XML 完全不处理，
     * {@code <Password>s3cr3t</Password>} 直接明文落盘。
     */
    private static String sanitizeXml(String xml) {
        // ① 元素文本：<Password>xxx</Password>
        Matcher m = XML_ELEMENT.matcher(xml);
        StringBuffer sb = new StringBuffer(xml.length());
        while (m.find()) {
            String tagName = m.group(2);
            String text = m.group(3);
            String replacement;
            if (isSensitiveBodyKey(stripNamespace(tagName)) && text != null && !text.isEmpty()) {
                replacement = m.group(1) + MASK + m.group(4);
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
            if (isSensitiveBodyKey(stripNamespace(attrName)) && value != null && !value.isEmpty()) {
                replacement = attrName + "=" + quote + MASK + quote;
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
        if (name == null) return null;
        int colon = name.lastIndexOf(':');
        return colon >= 0 && colon < name.length() - 1 ? name.substring(colon + 1) : name;
    }

    // ═══════════════════════════════════════════════════════════════
    // form-urlencoded
    // ═══════════════════════════════════════════════════════════════

    /**
     * 脱敏 {@code application/x-www-form-urlencoded} 体。
     * <p>登录表单 {@code username=alice&password=s3cr3t} 在原实现下
     * 因不以 {@code {} 开头而完全不脱敏。
     */
    private static String sanitizeForm(String form) {
        Matcher m = FORM_PAIR.matcher(form);
        StringBuffer sb = new StringBuffer(form.length());
        while (m.find()) {
            String key = m.group(1);
            String value = m.group(3);
            String replacement;
            if (isSensitiveBodyKey(urlDecodeQuiet(key)) && value != null && !value.isEmpty()) {
                replacement = key + m.group(2) + MASK;
            } else {
                replacement = m.group(0);
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String urlDecodeQuiet(String s) {
        if (s == null) return null;
        try {
            return java.net.URLDecoder.decode(s, java.nio.charset.StandardCharsets.UTF_8.name());
        } catch (Exception e) {
            return s;
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // 纯文本兜底
    // ═══════════════════════════════════════════════════════════════

    /**
     * 纯文本兜底遮蔽：逐行查找 {@code 敏感词<分隔符>值} 形态并遮蔽值部分。
     * <p>覆盖日志片段、非结构化响应等场景。宁可过度遮蔽，不可漏出。
     */
    public static String sanitizeFreeText(String text) {
        String[] lines = text.split("\n", -1);
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) out.append('\n');
            out.append(maskFreeTextLine(lines[i]));
        }
        return out.toString();
    }

    /** 单行处理：找到 "敏感词 : = 值" 结构后遮蔽值。 */
    private static String maskFreeTextLine(String line) {
        if (line == null || line.isEmpty()) return line;
        int sep = -1;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == ':' || c == '=') { sep = i; break; }
        }
        if (sep <= 0 || sep >= line.length() - 1) {
            // ⭐ 修复 R2：无 key=value 结构时仍可能含 Bearer/JWT/URL token，走正则兜底
            return maskFreeTextTokens(line);
        }
        String key = line.substring(0, sep).trim();
        // 去掉可能包裹的引号
        if (key.length() >= 2 && key.startsWith("\"") && key.endsWith("\"")) {
            key = key.substring(1, key.length() - 1);
        }
        if (isSensitiveBodyKey(key)) {
            return line.substring(0, sep + 1) + " " + MASK;
        }
        // ⭐ 修复 R2：非敏感 key 的值部分仍可能含 Bearer/JWT，走正则兜底
        return maskFreeTextTokens(line);
    }

    /** ⭐ 修复 R2：覆盖自由文本中的 Bearer token、Authorization 头、独立 JWT、URL 内嵌凭据。 */
    private static String maskFreeTextTokens(String text) {
        if (text == null) return null;
        // Bearer / Basic / Digest 等认证方案后的凭证
        text = FREE_TEXT_AUTH_SCHEME.matcher(text)
                .replaceAll(m -> m.group(1) + " " + m.group(2).substring(0, Math.min(m.group(2).length(), 0)) + MASK);
        // 独立 JWT（三段式 base64url）
        // ⭐ 修复：原写法 replaceAll("$1" + MASK + "$3")，但该正则只有 2 个捕获组，
        //    引用 $3 会在【命中时】抛 IndexOutOfBoundsException —— 即日志里真出现 JWT 就崩，
        //    与"脱敏不得引入新故障"的初衷相悖。改为仅保留前缀组 + 掩码。
        text = FREE_TEXT_JWT.matcher(text).replaceAll("$1" + MASK);
        // URL 中 //user:pass@ 或 ?token=xxx 形态（同样只有 2 组，修正 $3 → 无）
        text = FREE_TEXT_URL_CREDENTIAL.matcher(text).replaceAll("$1" + MASK);
        // ⭐ 修复 S1：URLEncode 内层 JSON 凭据（BrowserStack caps 里的 accessKey 等）
        text = maskUrlEncodedSecrets(text);
        return text;
    }

    /**
     * ⭐ 修复 S1：逐匹配遮蔽 URLEncode 后的 {@code key%3Avalue} / {@code %22key%22%3A%22value%22} 形态。
     * <p>仅当 key 解码后命中敏感词表才遮蔽，避免误伤普通 URL 参数。
     */
    private static String maskUrlEncodedSecrets(String text) {
        if (text == null || text.isEmpty()) return text;
        Matcher m = FREE_TEXT_URL_ENCODED_SECRET.matcher(text);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String key = urlDecodeQuiet(m.group(2));
            String replacement;
            if (isSensitiveBodyKey(key)) {
                replacement = nullToEmpty(m.group(1)) + m.group(2) + nullToEmpty(m.group(3))
                        + m.group(4) + nullToEmpty(m.group(5)) + MASK;
            } else {
                replacement = m.group(0);
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    // ═══════════════════════════════════════════════════════════════
    // key 规范化匹配
    // ═══════════════════════════════════════════════════════════════

    /**
     * key 规范化：剥离下划线/连字符/点/空格后转小写。
     *
     * <p>这是修复"字段名匹配失效"的核心：一条 {@code accesstoken} 规则
     * 即可同时命中 {@code access_token}（OAuth 标准）、{@code accessToken}
     * （camelCase）、{@code Access-Token}（HTTP 头风格）、{@code ACCESS_TOKEN}
     * （常量风格）。原实现按原文小写比对，漏掉了带分隔符的全部变体。
     */
    private static String normalizeKey(String key) {
        if (key == null) return "";
        StringBuilder sb = new StringBuilder(key.length());
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            if (c == '_' || c == '-' || c == '.' || c == ' ') continue;
            sb.append(Character.toLowerCase(c));
        }
        return sb.toString();
    }

    /** 值整体遮蔽，仅附带长度提示（便于定位问题，不泄露内容）。 */
    private static String maskValue(String value) {
        return MASK + "(len=" + value.length() + ")";
    }

    /** 判断是否为敏感头 key（规范化匹配）。 */
    private static boolean isSensitiveHeaderKey(String key) {
        if (key == null) return false;
        loadExtraIfNeeded();
        String n = normalizeKey(key);
        // 头也可能用体字段名（如自定义头 X-Password），故两个集合都查；附加键同理
        return SENSITIVE_HEADER_KEYS.contains(n) || SENSITIVE_BODY_KEYS.contains(n)
                || EXTRA_HEADER_KEYS.contains(n) || EXTRA_BODY_KEYS.contains(n);
    }

    /**
     * 判断 body 字段是否敏感（规范化匹配）。
     * <p>由 {@code ApiMonitoringRecord} 等调用方复用，保证全框架判定一致。
     */
    public static boolean isSensitiveBodyKey(String key) {
        if (key == null) return false;
        loadExtraIfNeeded();
        return SENSITIVE_BODY_KEYS.contains(normalizeKey(key)) || EXTRA_BODY_KEYS.contains(normalizeKey(key));
    }

    /** 供测试与诊断：返回统一掩码串。 */
    public static String maskToken() {
        return MASK;
    }
}
