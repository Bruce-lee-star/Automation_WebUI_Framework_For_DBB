package com.hsbc.cmb.hk.dbb.automation.framework.common.security;

import com.hsbc.cmb.hk.dbb.automation.framework.common.config.ConfigSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 脱敏<b>规则注册表</b>与共享原语（SensitiveDataSanitizer 拆分专项 · 阶段 2/3 抽取）。
 *
 * <p>职责：集中承载「哪些字段名/参数名算敏感」以及「哪些值按内容算敏感」这两类规则，
 * 并对外提供<b>键判定谓词</b>与<b>值判定谓词</b>；同时收纳脱敏链共用的原语
 * （掩码、键规范化、URL 解码、装饰字符剥离）。
 *
 * <p><b>并发契约（延续 C-7 修复）</b>：生效清单一律以<b>不可变快照 + {@code volatile} 原子发布</b>承载，
 * 重载时整体替换而非 {@code clear+addAll}，故并发读取永不会观察到空/半填充中间态；
 * 附加键/豁免名单等「只增」集合用并发集合，注册即可见。
 *
 * <p><b>与原实现的等价性</b>：本类字段与判定逻辑自 {@code SensitiveDataSanitizer} 逐字迁移，
 * 仅把「静态字段 + 静态方法」改为「单例 {@link #INSTANCE} 的实例状态」；判定口径、懒加载时机、
 * 配置键与回退顺序均未变（由 {@code SensitiveDataSanitizerBehaviorTest} 行为基线守卫）。
 */
final class SanitizerRules {

    private static final Logger LOGGER = LoggerFactory.getLogger(SanitizerRules.class);

    /** 全局唯一规则注册表实例（原为类静态状态，语义不变）。 */
    static final SanitizerRules INSTANCE = new SanitizerRules();

    /** 统一定长掩码：不泄露原值，也不泄露长度（合规要求）。 */
    static final String MASK = "***[REDACTED]";

    // ═══════════════════════════════════════════════════════════════
    // 共享原语
    // ═══════════════════════════════════════════════════════════════

    /**
     * key 规范化：剥离下划线/连字符/点/空格后转小写。
     *
     * <p>这是"字段名匹配失效"修复的核心：一条 {@code accesstoken} 规则即可同时命中
     * {@code access_token}（OAuth 标准）、{@code accessToken}（camelCase）、
     * {@code Access-Token}（HTTP 头风格）、{@code ACCESS_TOKEN}（常量风格）。
     */
    static String normalizeKey(String key) {
        if (key == null) return "";
        StringBuilder sb = new StringBuilder(key.length());
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            if (c == '_' || c == '-' || c == '.' || c == ' ') continue;
            sb.append(Character.toLowerCase(c));
        }
        return sb.toString();
    }

    /**
     * 剥离 key 上的装饰字符（首部 {@code { [ " '} 与尾部 {@code " '}）后再做规范化匹配。
     *
     * <p><b>为什么必须有</b>：截断/非法的 JSON 体会降级到文本兜底链，此时键形如 {@code {"password"}；
     * 若按原文规范化，结果是 {@code {"password"} 而非 {@code password}，匹配不上敏感清单 ——
     * 「非法 JSON 绝不原样放行」的承诺就被击穿。剥离装饰后结构化链与兜底链判定口径一致。
     */
    static String stripKeyDecoration(String key) {
        if (key == null) return null;
        int start = 0;
        int end = key.length();
        while (start < end && "{[ \"'".indexOf(key.charAt(start)) >= 0) start++;
        while (end > start && ("\"'".indexOf(key.charAt(end - 1)) >= 0)) end--;
        return key.substring(start, end);
    }

    /** URL 解码，失败时原样返回（脱敏链绝不因解码失败中断）。 */
    static String urlDecodeQuiet(String s) {
        if (s == null) return null;
        try {
            return java.net.URLDecoder.decode(s, java.nio.charset.StandardCharsets.UTF_8.name());
        } catch (Exception e) {
            return s;
        }
    }

    /** null 安全的空串兜底（用于拼接替换串）。 */
    static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    // ═══════════════════════════════════════════════════════════════
    // 内置清单
    // ═══════════════════════════════════════════════════════════════

    /**
     * 内置默认敏感头清单（不可变兜底；配置覆盖时作为基线，只增不减）。
     * <p>匹配走 {@link #normalizeKey(String)} 规范化，故此处只需写规范化后的形态（全小写、无分隔符）。
     */
    private static final Set<String> DEFAULT_HEADER_KEYS =
            Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
                    // 认证凭据
                    "authorization", "proxyauthorization", "wwwauthenticate", "proxyauthenticate",
                    "cookie", "setcookie",
                    // 各类自定义令牌头（通用 X- 令牌模式，平台无关）
                    "xauthtoken", "xcsrftoken", "xxsrftoken", "xapikey", "apikey",
                    "xaccesstoken", "xidtoken", "xrefreshtoken", "xsessiontoken",
                    "xsessionid", "xsecret", "xclientsecret", "xsignature")));

    /**
     * 内置默认敏感体字段清单（不可变兜底；配置覆盖时作为基线，只增不减）。
     * <p>同样走规范化匹配，故 {@code access_token} / {@code accessToken} / {@code Access-Token}
     * 均由单条 {@code accesstoken} 覆盖。
     */
    private static final Set<String> DEFAULT_BODY_KEYS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
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
            "cvv", "cvv2", "cvc", "cvc2", "cvn", "cid", "pin", "otp", "tac", "securitycode",
            "expirydate", "expiry", "validthru",
            // ── 联系方式与生物信息 PII ──
            "email", "emailaddress", "phone", "phonenumber", "mobile",
            "mobilenumber", "telephone", "dob", "dateofbirth", "birthdate",
            "address", "postaladdress", "fullname")));

    /** 内置默认敏感 query 参数清单（兜底，供配置覆盖时回退）。 */
    private static final Set<String> DEFAULT_QUERY_KEYS = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList(
                    "token", "accesstoken", "access_token", "apikey", "api_key",
                    "secret", "password", "passwd", "credential", "auth",
                    "authorization", "sign", "signature", "key", "privatekey"
            )));

    // ═══════════════════════════════════════════════════════════════
    // 生效快照（不可变 + volatile 原子发布）
    // ═══════════════════════════════════════════════════════════════

    private volatile Set<String> headerKeys = DEFAULT_HEADER_KEYS;
    private volatile Set<String> bodyKeys = DEFAULT_BODY_KEYS;
    private volatile Set<String> queryKeys = DEFAULT_QUERY_KEYS;

    // ── 用户可配置附加敏感关键字（叠加在内置清单之上，不改动内置默认）──
    private static final String CFG_EXTRA_HEADER_KEYS = "sensitive.data.extra.header.keys";
    private static final String CFG_EXTRA_BODY_KEYS = "sensitive.data.extra.body.keys";
    private static final String CFG_EXTRA_QUERY_KEYS = "sensitive.data.extra.query.keys";

    private final Set<String> extraHeaderKeys = ConcurrentHashMap.newKeySet();
    private final Set<String> extraBodyKeys = ConcurrentHashMap.newKeySet();
    private final Set<String> extraQueryKeys = ConcurrentHashMap.newKeySet();
    private volatile boolean extraLoaded;
    private final Object extraLoadLock = new Object();

    // ── 值级识别 ──
    private volatile List<SensitiveValueRecognizer> activeValueRecognizers = new ArrayList<>();
    private final Set<String> valueRecognizerExcludes = ConcurrentHashMap.newKeySet();
    private volatile boolean rulesLoaded;
    private final Object rulesLock = new Object();

    private SanitizerRules() {
    }

    // ═══════════════════════════════════════════════════════════════
    // 键判定谓词
    // ═══════════════════════════════════════════════════════════════

    /**
     * 是否为敏感头 key（规范化匹配）。
     * <p>头也可能用体字段名（如自定义头 {@code X-Password}），故两个集合都查；附加键同理。
     */
    boolean isHeaderKey(String key) {
        if (key == null) return false;
        loadRulesIfNeeded();
        loadExtraIfNeeded();
        String n = normalizeKey(key);
        return headerKeys.contains(n) || bodyKeys.contains(n)
                || extraHeaderKeys.contains(n) || extraBodyKeys.contains(n);
    }

    /**
     * 是否为敏感体字段（规范化匹配）。
     * <p>供 {@code ApiMonitoringRecord} 等调用方复用，保证全框架判定一致。
     */
    boolean isBodyKey(String key) {
        if (key == null) return false;
        loadRulesIfNeeded();
        loadExtraIfNeeded();
        String n = normalizeKey(key);
        return bodyKeys.contains(n) || extraBodyKeys.contains(n);
    }

    /** 是否为敏感 URL query 参数名（规范化匹配，含附加键）。 */
    boolean isQueryKey(String key) {
        if (key == null) return false;
        loadRulesIfNeeded();
        loadExtraIfNeeded();
        String n = normalizeKey(key);
        return queryKeys.contains(n) || extraQueryKeys.contains(n);
    }

    /**
     * 按<b>值内容</b>判定是否敏感（与字段名无关）：覆盖银行卡号 / 银联卡 / IBAN / HKID /
     * 中国大陆身份证号 / 手机号 / 护照 / 港澳通行证 / 统一社会信用代码 / 信用卡轨道数据。
     * 豁免名单命中时返回 false（抑制误报）。
     */
    boolean isValueSensitive(String value) {
        if (value == null) return false;
        String v = value.trim();
        if (v.isEmpty()) return false;
        for (String ex : valueRecognizerExcludes) {
            if (v.equalsIgnoreCase(ex) || v.contains(ex)) return false;
        }
        loadRulesIfNeeded();
        for (SensitiveValueRecognizer r : activeValueRecognizers) {
            if (r.recognizes(v)) return true;
        }
        return false;
    }

    // ═══════════════════════════════════════════════════════════════
    // 注册 / 重载
    // ═══════════════════════════════════════════════════════════════

    /** 懒加载附加敏感键：仅首次判定时从配置读取一次；已程序化注册则跳过配置读取。 */
    private void loadExtraIfNeeded() {
        if (extraLoaded) return;
        synchronized (extraLoadLock) {
            if (extraLoaded) return;
            reloadExtraKeysFromConfig();
        }
    }

    /** 强制从配置重新加载附加敏感键（清掉旧值后重读）；供测试与运行时热更新使用。 */
    void reloadExtraKeysFromConfig() {
        synchronized (extraLoadLock) {
            extraHeaderKeys.clear();
            extraBodyKeys.clear();
            extraQueryKeys.clear();
            parseKeys(readExtraConfig(CFG_EXTRA_HEADER_KEYS), extraHeaderKeys);
            parseKeys(readExtraConfig(CFG_EXTRA_BODY_KEYS), extraBodyKeys);
            parseKeys(readExtraConfig(CFG_EXTRA_QUERY_KEYS), extraQueryKeys);
            extraLoaded = true;
        }
    }

    /**
     * 程序化注入附加敏感键（叠加）。供 web 层或测试在不依赖系统属性的场景下扩展脱敏范围。
     */
    void registerExtraSensitiveKeys(String headerKeysCsv, String bodyKeysCsv, String queryKeysCsv) {
        synchronized (extraLoadLock) {
            parseKeys(headerKeysCsv, extraHeaderKeys);
            parseKeys(bodyKeysCsv, extraBodyKeys);
            parseKeys(queryKeysCsv, extraQueryKeys);
            extraLoaded = true;
        }
    }

    /** 运行时注册自定义值级识别器（与 SPI 机制互补）。 */
    synchronized void registerValueRecognizer(SensitiveValueRecognizer recognizer) {
        if (recognizer == null) return;
        List<SensitiveValueRecognizer> list = new ArrayList<>(activeValueRecognizers);
        list.add(recognizer);
        activeValueRecognizers = list;
    }

    /** 强制重载全部规则（测试 / 运行时热更新；与 {@link #reloadExtraKeysFromConfig()} 互补）。 */
    void reloadRules() {
        synchronized (rulesLock) {
            rulesLoaded = false;
        }
        loadRulesIfNeeded();
    }

    // ═══════════════════════════════════════════════════════════════
    // 私有：配置读取与规则装配
    // ═══════════════════════════════════════════════════════════════

    /**
     * 读取附加敏感键配置：优先取 {@code -D} 系统属性（保留运行时 {@code System.setProperty} 即时生效能力），
     * 回退到 core 统一配置源 {@link ConfigSource#resolve}（合并 serenity.properties / serenity.conf / 环境变量）。
     * 两条路径均经 {@code SecretValue.decryptIfNeeded} 透明解密，与框架其它配置读取一致。
     */
    private static String readExtraConfig(String key) {
        String raw = System.getProperty(key);
        if (raw == null || raw.trim().isEmpty()) {
            raw = ConfigSource.resolve(key, "");
        }
        return raw == null ? "" : SecretValue.decryptIfNeeded(raw);
    }

    /** 统一配置读取：优先系统属性（便于运行时注入/测试），回退 core 配置源。 */
    private static String resolveConfig(String key, String def) {
        String raw = System.getProperty(key);
        if (raw == null || raw.trim().isEmpty()) raw = ConfigSource.resolve(key, def);
        return raw == null ? def : raw;
    }

    /** 把逗号分隔的键解析为规范化形态并加入目标集合（容忍空白/空）。 */
    private static void parseKeys(String csv, Set<String> target) {
        if (csv == null || csv.trim().isEmpty()) return;
        for (String token : csv.split(",")) {
            String k = token.trim();
            if (!k.isEmpty()) target.add(normalizeKey(k));
        }
    }

    /** 懒加载全部规则：内置键清单（可按 profile 覆盖）+ 值识别器过滤 + 豁免名单。 */
    private void loadRulesIfNeeded() {
        if (rulesLoaded) return;
        synchronized (rulesLock) {
            if (rulesLoaded) return;
            // C-7：以「构造不可变快照 + volatile 赋值原子发布」替代 clear+addAll，
            //      并发读取（sanitize*）永不观察到空/半填充中间态，根治 reload 竞态。
            headerKeys = Collections.unmodifiableSet(loadBuiltinSet("header", DEFAULT_HEADER_KEYS));
            bodyKeys = Collections.unmodifiableSet(loadBuiltinSet("body", DEFAULT_BODY_KEYS));
            queryKeys = Collections.unmodifiableSet(loadBuiltinSet("query", DEFAULT_QUERY_KEYS));
            applyValueRecognizerConfig();
            rulesLoaded = true;
        }
    }

    /**
     * 读取某 profile 下某类敏感键清单。<b>始终以内置 DEFAULT 为基，配置项叠加其上</b>
     * （而非整体替换）——满足「新增字段无需改代码」，且市场 profile 只增不减，避免
     * 误配导致内置敏感键（如 password）意外失效的合规倒退。
     */
    private static Set<String> loadBuiltinSet(String category, Set<String> defaults) {
        Set<String> set = new HashSet<>(defaults);
        String profile = resolveConfig("sensitive.data.rules.profile", "defaults").trim();
        String csv = resolveConfig("sensitive.data.profile." + profile + "." + category + ".keys", "");
        if (csv != null && !csv.trim().isEmpty()) {
            for (String t : csv.split(",")) {
                String k = t.trim();
                if (!k.isEmpty()) set.add(normalizeKey(k));
            }
        }
        return set;
    }

    /** 按配置过滤生效的值识别器并加载豁免名单。 */
    private void applyValueRecognizerConfig() {
        String enabled = resolveConfig("sensitive.data.value.recognizers", "").trim();
        List<SensitiveValueRecognizer> all = loadValueRecognizers();
        if (enabled.isEmpty()) {
            activeValueRecognizers = new ArrayList<>(all);
        } else {
            Set<String> en = new HashSet<>();
            for (String t : enabled.split(",")) {
                String k = t.trim().toUpperCase();
                if (!k.isEmpty()) en.add(k);
            }
            List<SensitiveValueRecognizer> filtered = new ArrayList<>();
            for (SensitiveValueRecognizer r : all) {
                if (en.contains(r.name().toUpperCase())) filtered.add(r);
            }
            activeValueRecognizers = filtered;
        }
        valueRecognizerExcludes.clear();
        String ex = resolveConfig("sensitive.data.value.excludes", "");
        if (ex != null && !ex.trim().isEmpty()) {
            for (String t : ex.split(",")) {
                String k = t.trim();
                if (!k.isEmpty()) valueRecognizerExcludes.add(k);
            }
        }
    }

    /** 收集内置 + SPI 发现的值识别器。 */
    private static List<SensitiveValueRecognizer> loadValueRecognizers() {
        List<SensitiveValueRecognizer> list = new ArrayList<>(BuiltinValueRecognizers.builtins());
        try {
            ServiceLoader<SensitiveValueRecognizer> sl = ServiceLoader.load(SensitiveValueRecognizer.class);
            for (SensitiveValueRecognizer r : sl) list.add(r);
        } catch (Throwable e) {
            // SPI 不可用不影响内置识别，但不得静默（D7-3）
            LOGGER.debug("[SanitizerRules] recognizer SPI unavailable, fallback to builtins: {}", e.toString());
        }
        return list;
    }
}
