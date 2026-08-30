package com.hsbc.cmb.hk.dbb.automation.framework.web.utils;

import com.hsbc.cmb.hk.dbb.automation.framework.api.utility.JsonUtils;
import com.jayway.jsonpath.TypeRef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * NLS（多语言）工具。
 *
 * <p>设计要点：
 * <ol>
 *   <li>定位表达式只认「文件路径 + key」，语言类型不作为参数传入。</li>
 *   <li>当前语言由语言切换按钮调用 {@link #setLanguage(String)} 显式告知框架；
 *       未设置就调用 {@link #get(String, String)} 会抛异常，强制「切语言必须先通知框架」。</li>
 *   <li>nls 文件结构：{ 语言Key: { key: value, ... }, ... }，语言 Key 由 setLanguage 指定。</li>
 *   <li>文件路径支持 classpath 相对路径（如 "nls/login.nls.json"）或文件系统绝对路径。</li>
 *   <li><b>多文件支持</b>：一个页面对应多个 nls json 时，用 {@link #bind(String...)} 传入多个文件，
 *       运行时按声明顺序跨文件查找 key（命中即止）。例：
 *       {@code NLSUtils.bind("nls/common.nls.json", "nls/login.nls.json")}，
 *       之后 {@code bundle.get("username")} 会先在 common 找、找不到再在 login 找。
 *       单文件 {@link #bind(String)} 行为不变（缺失即抛），多文件时仅全部文件都缺失才抛。</li>
 * </ol>
 */
public final class NLSUtils {

    private static final Logger log = LoggerFactory.getLogger(NLSUtils.class);

    /**
     * 当前语言 — 进程级全局值（带写入序号，见 {@link LangValue}）。
     *
     * <p>⭐ 跨线程修正（两轮）：
     *
     * <p><b>第一轮</b>：原实现纯 {@code ThreadLocal}，在 Monitor 的 onResponse 回调线程里
     * {@code setLanguage} 后，测试主线程读不到（线程副本隔离）。改为「全局 + 线程级覆盖」双轨。
     *
     * <p><b>第二轮（本次修复 —— 覆盖遮蔽 bug）</b>：双轨的判定原为「线程覆盖优先，否则回退全局」，
     * 这会产生<b>陈旧值遮蔽</b>：
     * <pre>
     *   主线程   setLanguage("en")  →  global="en", 主线程 override="en"
     *   回调线程 setLanguage("zh")  →  global="zh", 回调线程 override="zh"
     *   主线程   getLanguage()      →  主线程 override="en"（陈旧）→ 返回 "en"  ❌ 应为 "zh"
     * </pre>
     * 即只要主线程曾经设置过语言，回调线程随后的设置就<b>永远</b>对主线程不可见 ——
     * 而「先设初值、再由 API 响应回调改成实际语言」恰恰是最典型的用法。
     *
     * <p>修复：为每次写入分配<b>全局单调序号</b>，{@code getLanguage()} 比较全局值与线程覆盖的
     * 序号，取<b>较新</b>者。这样既保留并发隔离能力（本线程后写仍优先生效），
     * 又保证跨线程的最新写入不会被陈旧线程副本遮蔽。
     */
    private static final java.util.concurrent.atomic.AtomicReference<LangValue> globalLang =
            new java.util.concurrent.atomic.AtomicReference<>();

    /** 线程级语言覆盖（用于并发多场景互不干扰的隔离场景），同样带写入序号。 */
    private static final ThreadLocal<LangValue> threadLangOverride = new ThreadLocal<>();

    /** 全局单调写入序号：用于判定「哪个写入更新」。 */
    private static final java.util.concurrent.atomic.AtomicLong LANG_WRITE_SEQ =
            new java.util.concurrent.atomic.AtomicLong();

    /**
     * 带写入序号的语言值。
     *
     * @param lang 语言标识
     * @param seq  写入时的全局单调序号（越大越新）
     */
    private record LangValue(String lang, long seq) {}

    /** 缓存：filePath -> (lang -> (key -> value)) */
    private static final Map<String, Map<String, Map<String, String>>> CACHE =
            new ConcurrentHashMap<>();

    private static final TypeRef<Map<String, Map<String, String>>> LANG_TABLE_TYPE =
            new TypeRef<Map<String, Map<String, String>>>() {};

    private NLSUtils() {
    }

    /**
     * 语言切换时调用，告知框架当前显示的语言。
     * 值应取自对应 nls 文件第一层的语言 Key（如 "en"、"zh"）。
     *
     * @param lang 语言标识
     */
    public static void setLanguage(String lang) {
        if (lang == null) {
            // 传入 null 等价于清除：同样写入「空值标记」使其它线程的副本失效
            threadLangOverride.remove();
            globalLang.set(clearedMarker());
        } else {
            // ⭐ 同时写入全局值与当前线程副本，且两者共享同一个序号：
            //   单线程场景行为不变；跨线程场景（Monitor 回调线程设置、主线程读取）
            //   由 getLanguage() 的「序号取新」判定保证可见。
            LangValue v = new LangValue(lang, LANG_WRITE_SEQ.incrementAndGet());
            globalLang.set(v);
            threadLangOverride.set(v);
        }
        log.info("[NLS] language switched to: {}", lang);
    }

    public static String getLanguage() {
        // ⭐ 取「写入更新的那个」，而非无条件优先线程副本（否则陈旧副本会遮蔽跨线程新值）
        LangValue override = threadLangOverride.get();
        LangValue global = globalLang.get();
        if (override == null) return global == null ? null : global.lang;
        if (global == null) return override.lang;
        return global.seq() >= override.seq() ? global.lang() : override.lang();
    }

    /**
     * 构造一个「较新序号的空值标记」：用于清除/重置场景，使其它线程的陈旧线程副本
     * 因序号更旧而被 {@link #getLanguage()} 忽略（线程副本无法被跨线程直接擦除）。
     */
    private static LangValue clearedMarker() {
        return new LangValue(null, LANG_WRITE_SEQ.incrementAndGet());
    }

    /**
     * 清理语言状态，避免污染后续用例。
     *
     * <p>同时清线程副本与全局值（仅清线程副本是不够的：全局值会继续被其它线程读到）。
     * 全局侧写入「空值标记」并占用更新的序号，使任何线程残留的陈旧副本均因序号更旧而失效。
     */
    public static void reset() {
        threadLangOverride.remove();
        globalLang.set(clearedMarker());
    }

    /**
     * 绑定一个 nls 文件，返回一个句柄。之后调用方只需传 key，
     * 文件路径只在此处出现一次。文件内容按路径缓存（仅解析一次），
     * 语言仍由 {@link #setLanguage(String)} 全局决定。
     *
     * @param filePath nls 文件完整路径（classpath 相对或文件系统绝对）
     * @return 绑定该文件的句柄
     */
    public static NlsBundle bind(String filePath) {
        return new NlsBundle(List.of(filePath));
    }

    /** 绑定多个 nls 文件：运行时按声明顺序跨文件查找 key（命中即止），适合一个页面对应多个 nls json。 */
    public static NlsBundle bind(String... filePaths) {
        return new NlsBundle(Arrays.asList(filePaths));
    }

    /** 绑定多个 nls 文件（列表形式）。 */
    public static NlsBundle bind(List<String> filePaths) {
        return new NlsBundle(filePaths);
    }

    /**
     * 绑定某个 nls 文件的句柄：调用方只需传 key，无需重复写文件路径。
     * 语言由 {@link NLSUtils#setLanguage(String)} 全局控制，与具体 bundle 无关。
     */
    public static final class NlsBundle {
        private final List<String> files;

        private NlsBundle(List<String> files) {
            this.files = List.copyOf(files);
        }

        /** 取当前语言下某个 key 的可访问名：按文件声明顺序跨文件查找，命中即止。
         *  单文件时与旧行为一致（缺失即抛）；多文件时任一文件命中即返回，全部缺失才抛（信息含所有文件）。 */
        public String get(String key) {
            if (files.size() == 1) {
                return NLSUtils.get(files.get(0), key);
            }
            StringBuilder errors = new StringBuilder();
            for (String f : files) {
                try {
                    return NLSUtils.get(f, key);
                } catch (IllegalStateException e) {
                    if (errors.length() > 0) errors.append("; ");
                    errors.append('[').append(f).append("] ").append(e.getMessage());
                }
            }
            throw new IllegalStateException("[NLS] missing key '" + key
                    + "' in any of files " + files + " -> " + errors);
        }

        /** 返回绑定的主要文件路径（多文件时为首个） */
        public String path() {
            return files.get(0);
        }

        /** 返回绑定的全部文件路径（多文件时长度 > 1） */
        public List<String> paths() {
            return files;
        }
    }

    /**
     * 取当前语言下，指定 nls 文件中某个 key 的可访问名。
     *
     * @param filePath nls 文件完整路径（classpath 相对或文件系统绝对），如 "nls/login.nls.json"
     * @param key      功能内 key，如 "username"
     * @return 当前语言下的文本
     */
    public static String get(String filePath, String key) {
        String lang = getLanguage();
        if (lang == null || lang.isEmpty()) {
            throw new IllegalStateException(
                    "[NLS] current language not set — call setLanguage(\"xx\") first "
                            + "(e.g. right after switching language in the UI). file=" + filePath);
        }
        Map<String, Map<String, String>> byLang = load(filePath);
        Map<String, String> table = byLang.get(lang);
        if (table == null) {
            throw new IllegalStateException(
                    "[NLS] no language table '" + lang + "' in file: " + filePath);
        }
        String value = table.get(key);
        if (value == null) {
            throw new IllegalStateException(
                    "[NLS] missing key '" + key + "' for lang=" + lang + " in file: " + filePath);
        }
        return value;
    }

    /**
     * 返回 nls 文件原始结构（语言 Key → key → value），供需要在“文本值 → key”反向查表时使用
     * （如 {@code RoleElementPicker} 把拾取的 a11y name 反查对应 nls key，避免重新派生 slug）。
     * 内容按路径缓存，仅解析一次。
     *
     * @param filePath nls 文件完整路径（classpath 相对或文件系统绝对）
     * @return 语言 Key → key → value 的二维表
     */
    public static Map<String, Map<String, String>> rawTables(String filePath) {
        return load(filePath);
    }

    private static Map<String, Map<String, String>> load(String filePath) {
        return CACHE.computeIfAbsent(filePath, NLSUtils::readAndParse);
    }

    /**
     * 判断 nls 文本值是否含模板变量（形如 {@code {{deviceModel}}}、{@code {{current_username}}}）。
     * 这类值在运行时由页面注入真实值，拾取到的可见文本与 nls 原始值不一致，无法精确匹配，需走正则。
     */
    public static boolean isTemplate(String value) {
        return value != null && value.contains("{{");
    }

    /**
     * 把含模板变量的 nls 文本值编译为「跨语言/跨引擎通用」的正则源字符串（不含 {@code ^$} 锚点，
     * 由调用方决定匹配语义）。用途：
     * <ul>
     *   <li>拾取反查：注入浏览器后配合 {@code new RegExp(src).test(可见文本)} 反查 key；</li>
     *   <li>运行时定位：{@link #templatePattern(String)} 包装为 {@link Pattern} 传给 Playwright 的
     *       {@code getByText(Pattern)} / {@code getByAltText(Pattern)} 等做正则匹配。</li>
     * </ul>
     * 处理细节：
     * <ol>
     *   <li>归一化空白（\r\n→\n、&amp;nbsp;→空格、折叠）；</li>
     *   <li>剥离 HTML 标签（{@code getByText} 比对的是可见文本不含标签；{@code <br>} 转空格）；</li>
     *   <li>解码常见 HTML 实体（可见文本里是实体对应的字符，如 {@code &amp;copy;}→©）；</li>
     *   <li>转义正则元字符，但把 {@code {{var}} 占位符替换为 {@code (.*?)}（非贪婪，匹配任意真实值）。</li>
     * </ol>
     * 占位符名在各国语言里保持一致（如 {{deviceModel}}），故任意语言编译出的正则源都能还原匹配。
     */
    /**
     * 把任意 nls 文本值归一化为「页面可见文本」：
     * 归一化空白（\r\n→\n、&amp;nbsp;→空格、折叠）、
     * 剥离 HTML 标签（{@code <br>} 转空格、其余标签删除）、
     * 解码常见 HTML 实体（可见文本里是实体对应的字符，如 {@code &amp;copy;}→©）。
     *
     * <p>用途：nls 值里常内嵌 {@code <a>/<strong>/<img>} 等标签与实体（如
     * {@code tab_security_device = "保安編碼器&nbsp; <img ...>"}），
     * 但浏览器渲染后的可见文本不含标签（只有 “保安編碼器”），
     * 故拾取反查 / 运行时定位都应基于可见文本，而非原始字符串，否则必然匹配不上。
     */
    public static String visibleText(String value) {
        if (value == null) return "";
        return stripHtmlAndNormalize(value).trim();
    }

    /** 归一化 + 剥 HTML + 解码实体（不 trim，供 {@link #templateRegexSource} 复用，保持历史行为一致）。 */
    private static String stripHtmlAndNormalize(String value) {
        String t = value.replace("\r\n", "\n").replace('\u00A0', ' ');
        t = t.replaceAll("<br\\s*/?>", " ").replaceAll("<[^>]+>", " ");
        t = decodeEntities(t);
        return t.replaceAll("\\s+", " ");
    }

    /**
     * 把含模板变量的 nls 文本值编译为「跨语言/跨引擎通用」的正则源字符串（不含 {@code ^$} 锚点，
     * 由调用方决定匹配语义）。用途：
     * <ul>
     *   <li>拾取反查：注入浏览器后配合 {@code new RegExp(src).test(可见文本)} 反查 key；</li>
     *   <li>运行时定位：{@link #templatePattern(String)} 包装为 {@link Pattern} 传给 Playwright 的
     *       {@code getByText(Pattern)} / {@code getByAltText(Pattern)} 等做正则匹配。</li>
     * </ul>
     * 处理细节：
     * <ol>
     *   <li>归一化空白（\r\n→\n、&amp;nbsp;→空格、折叠）；</li>
     *   <li>剥离 HTML 标签（{@code getByText} 比对的是可见文本不含标签；{@code <br>} 转空格）；</li>
     *   <li>解码常见 HTML 实体（可见文本里是实体对应的字符，如 {@code &amp;copy;}→©）；</li>
     *   <li>转义正则元字符，但把 {@code {{var}} 占位符替换为 {@code (.*?)}（非贪婪，匹配任意真实值）。</li>
     * </ol>
     * 占位符名在各国语言里保持一致（如 {{deviceModel}}），故任意语言编译出的正则源都能还原匹配。
     */
    public static String templateRegexSource(String value) {
        if (value == null) return "";
        String t = stripHtmlAndNormalize(value);
        return escapeRegexKeepingPlaceholders(t);
    }

    /** 把含模板变量的 nls 值编译为正则 {@link Pattern}（{@link Pattern#DOTALL}，使 {@code .} 可跨换行）。 */
    public static Pattern templatePattern(String value) {
        String src = templateRegexSource(value);
        return src.isEmpty() ? Pattern.compile(Pattern.quote(value == null ? "" : value), Pattern.DOTALL)
                             : Pattern.compile(src, Pattern.DOTALL);
    }

    private static String decodeEntities(String s) {
        return s.replace("&nbsp;", " ")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&apos;", "'")
                .replace("&amp;", "&")
                .replace("&copy;", "©");
    }

    private static String escapeRegexKeepingPlaceholders(String t) {
        StringBuilder sb = new StringBuilder();
        int i = 0, n = t.length();
        while (i < n) {
            if (t.startsWith("{{", i)) {
                int end = t.indexOf("}}", i);
                if (end < 0) {
                    sb.append(escapeRegexLiteral(t.substring(i)));
                    break;
                }
                sb.append("(.*?)");
                i = end + 2;
            } else {
                int next = t.indexOf("{{", i);
                if (next < 0) next = n;
                sb.append(escapeRegexLiteral(t.substring(i, next)));
                i = next;
            }
        }
        return sb.toString();
    }

    private static String escapeRegexLiteral(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            // 这些字符在 JS/Java 正则里都是元字符，统一转义（两边通用）
            if ("[](){}.*+?^$|\\".indexOf(c) >= 0) {
                sb.append('\\').append(c);
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static Map<String, Map<String, String>> readAndParse(String filePath) {
        Map<String, Map<String, String>> parsed = JsonUtils.fromJson(readFile(filePath), LANG_TABLE_TYPE);
        if (parsed == null) {
            throw new IllegalStateException("[NLS] failed to parse file: " + filePath);
        }
        return parsed;
    }

    private static String readFile(String filePath) {
        try (InputStream in = Thread.currentThread().getContextClassLoader().getResourceAsStream(filePath)) {
            if (in != null) {
                try {
                    return new String(in.readAllBytes(), StandardCharsets.UTF_8);
                } catch (IOException e) {
                    throw new IllegalStateException("[NLS] read failed: " + filePath, e);
                }
            }
        } catch (IOException ignored) {
            // classpath 资源不存在，继续尝试文件系统
        }
        Path p = Paths.get(filePath);
        if (Files.exists(p)) {
            try {
                return Files.readString(p, StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new IllegalStateException("[NLS] read failed: " + filePath, e);
            }
        }
        throw new IllegalStateException("[NLS] file not found (classpath or filesystem): " + filePath);
    }
}
