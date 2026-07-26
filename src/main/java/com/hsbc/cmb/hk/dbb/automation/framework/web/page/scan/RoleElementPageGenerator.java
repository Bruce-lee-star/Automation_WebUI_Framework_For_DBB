package com.hsbc.cmb.hk.dbb.automation.framework.web.page.scan;

import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.AriaRole;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 根据 a11y 信息生成带 {@code @RoleFile} + {@code @RoleElement} 的 Page 类源码。
 *
 * <p>与 {@code BasePage} 解耦：仅接收 Playwright 的 {@link Page} 或一组 {@link RoleEntry}，
 * 不依赖框架页面基类，因此可作为独立脚手架在任意上下文（测试、main、CI 工具）中调用。
 * 产物为草稿，人工 review 后再合入主干（对齐 PAGEOBJECT_GENERATOR_DESIGN.md §7，注解风格）。
 *
 * <h3>用法</h3>
 * <pre>
 * Page pw = loginPage.getPage();
 * // 整页 a11y tree 直接生成
 * RoleElementPageGenerator.write(pw, "src/test/java",
 *         "com.hsbc...tests.pages", "LoginPage", "nls/login.nls.json");
 *
 * // 按需拾取：只生成用户点选的元素
 * List&lt;RoleEntry&gt; picks = RoleElementPicker.pick(pw);
 * RoleElementPageGenerator.write(picks, "src/test/java",
 *         "com.hsbc...tests.pages", "LoginPage", "nls/login.nls.json");
 * </pre>
 */
public final class RoleElementPageGenerator {

    private static final Logger log = LoggerFactory.getLogger(RoleElementPageGenerator.class);

    /** 按 role 给字段名加语义后缀（与设计文档 §6 一致）；文本框类角色记 Input，与 label/placeholder 区分。
     *  用 ofEntries 而非 of：of 最多 10 对（20 参数），此处已超，ofEntries 无此上限。 */
    private static final Map<String, String> ROLE_SUFFIX = Map.ofEntries(
            Map.entry("button", "Btn"), Map.entry("link", "Link"),
            Map.entry("checkbox", "Chk"), Map.entry("radio", "Radio"),
            Map.entry("combobox", "Combo"), Map.entry("listbox", "List"),
            Map.entry("slider", "Slider"), Map.entry("switch", "Switch"),
            Map.entry("tab", "Tab"), Map.entry("menuitem", "MenuItem"),
            Map.entry("textbox", "Input"), Map.entry("searchbox", "Input"),
            Map.entry("spinbutton", "Input")
    );

    private RoleElementPageGenerator() {}

    /**
     * 一次字段分配结果：字段名 + 注解文本（含 4 空格缩进，不含字段声明行）。
     * Page 类（Tab1 页面元素）与 Step 类（Tab2 步骤代码）共用本结果，
     * 确保两处引用的是完全相同的字段名（如 userNameIpt / nextBtn）。
     */
    public static final class GeneratedField {
        public final String fieldName;
        public final String annotation;
        public final RoleEntry entry;
        public GeneratedField(String fieldName, String annotation, RoleEntry entry) {
            this.fieldName = fieldName;
            this.annotation = annotation;
            this.entry = entry;
        }
    }

    /**
     * 把 entries 解析为去重后的字段列表（唯一命名来源）。Page 类与 Step 类均调用本方法，
     * 避免各自命名导致 Tab1 与 Tab2 字段名不一致。
     */
    public static List<GeneratedField> assignFields(List<RoleEntry> entries) {
        List<GeneratedField> specs = new ArrayList<>();
        Set<String> usedNames = new HashSet<>();
        Set<String> seenLocators = new HashSet<>();
        for (RoleEntry e : entries) {
            if (!isValidEntry(e)) continue;
            String sig = locatorKey(e);
            if (!seenLocators.add(sig)) continue;
            specs.add(makeField(e, specs.size(), usedNames));
        }
        return specs;
    }

    /** 计算单个元素的字段名 + 注解（与 appendField/appendRoleField/appendSelectorField 旧逻辑一致，仅改为返回结构化结果）。 */
    private static GeneratedField makeField(RoleEntry e, int idx, Set<String> usedNames) {
        if (e.isRoleStrategy()) {
            String roleConst = toAriaRoleConst(e.getRole());
            String name = e.getName();
            String resolvedKey = e.getResolvedKey();
            boolean matched = resolvedKey != null && !resolvedKey.isBlank();
            String fieldBase = matched ? resolvedKey : name;
            String field = toFieldName(fieldBase, e.getRole(), idx, usedNames);
            StringBuilder ann = new StringBuilder("    @RoleElement(role = AriaRole.").append(roleConst);
            if (matched) {
                ann.append(", key = \"").append(escapeJava(resolvedKey)).append("\"");
            } else {
                ann.append(", name = \"").append(name == null ? "" : escapeJava(name)).append("\"");
            }
            // 标题层级：仅 heading 角色有意义，1–6；0 表示不限层级（对齐 getByRole(HEADING).setLevel(n)）。
            if (e.getLevel() > 0) ann.append(", level = ").append(e.getLevel());
            if (e.isCleaned()) ann.append(", exact = false");
            ann.append(")");
            return new GeneratedField(field, ann.toString(), e);
        }
        String strategy = e.getStrategy();
        String resolvedKey = e.getResolvedKey();
        boolean matched = resolvedKey != null && !resolvedKey.isBlank();
        String base = matched ? resolvedKey
                : ((e.getName() != null && !e.getName().isBlank())
                    ? e.getName() : selectorLabel(strategy, e.getSelector()));
        String suffix = STRATEGY_SUFFIX.getOrDefault(strategy, "");
        String field = toFieldNameWithSuffix(base, suffix, idx, usedNames);
        String annotation;
        switch (strategy) {
            case "text":
            case "altText":
            case "title":
            case "placeholder":
            case "label":
                if (matched) {
                    String ann = "    @RoleElement(key = \"" + escapeJava(resolvedKey) + "\"";
                    if (e.isCleaned()) ann += ", exact = false";
                    ann += ")";
                    annotation = ann;
                } else {
                    annotation = "    @RoleElement(" + strategy + " = " + toJavaStringLiteral(e.getName()) + ")";
                }
                break;
            case "testid":
                annotation = "    @RoleElement(testId = " + toJavaStringLiteral(e.getName()) + ")";
                break;
            case "id":
            case "css":
            default:
                annotation = "    @Element(" + toJavaStringLiteral(e.getSelector()) + ")";
                break;
        }
        return new GeneratedField(field, annotation, e);
    }

    /**
     * 运行期反射得到的合法 {@link AriaRole} 常量名集合（大写）。
     * 用于生成代码时校验 role 是否可编译，避免再出现 AriaRole.INPUT / AriaRole.SPAN 这类
     * 把 HTML 标签名误当角色常量的编译错误。取不到时回退为空集合（直接信任输入）。
     */
    private static final Set<String> VALID_ARIA_ROLES;
    static {
        Set<String> set = new HashSet<>();
        try {
            for (AriaRole r : AriaRole.values()) {
                set.add(r.name());
            }
        } catch (Exception e) {
            // 反射失败：留空，下方 toAriaRoleConst 直接信任输入
        }
        VALID_ARIA_ROLES = set;
    }

    /** 把 a11y role 字符串映射为合法的 AriaRole 常量名；非标准角色兜底为 GENERIC（必存在） */
    private static String toAriaRoleConst(String role) {
        if (role == null || role.isBlank()) {
            return "GENERIC";
        }
        String up = role.toUpperCase(Locale.ROOT);
        if (VALID_ARIA_ROLES.isEmpty() || VALID_ARIA_ROLES.contains(up)) {
            return up;
        }
        // 不在枚举中（如个别自定义/拼写异常角色）：兜底用 GENERIC 保证可编译，真实角色已写进注释
        return "GENERIC";
    }

    /** 注入脚本：遍历整页 DOM，收集可交互角色的 role/name/tag（基于 computedRole/computedName） */
    private static final String COLLECT_SCRIPT = """
            () => {
              // 覆盖所有"有意义的" ARIA 角色（对齐 click 拾取）：仅排除 generic/none/presentation。
              var NON_ROLE = { generic:1, none:1, presentation:1 };
              function getHeadingLevel(el) {
                var al = el.getAttribute && el.getAttribute('aria-level');
                if (al) { var n = parseInt(al, 10); if (!isNaN(n) && n > 0) return n; }
                var m = /^H([1-6])$/.exec((el.tagName || '').toUpperCase());
                if (m) return parseInt(m[1], 10);
                return 0;
              }
              var out = [];
              var seen = {};
              var els = document.querySelectorAll('*');
              for (var i = 0; i < els.length; i++) {
                var el = els[i];
                var role;
                try { role = (typeof el.computedRole === 'function') ? el.computedRole() : el.getAttribute('role'); }
                catch (e) { role = el.getAttribute('role'); }
                if (!role) continue;
                role = role.toLowerCase();
                if (NON_ROLE[role]) continue;
                var name;
                try { name = (typeof el.computedName === 'function') ? el.computedName() : ''; }
                catch (e) { name = ''; }
                if (!name) name = (el.getAttribute('aria-label') || '').trim();
                if (!name) {
                  var t = (el.textContent || '').trim();
                  name = t ? t.substring(0, 120) : '';
                }
                if (!name) continue;   // 无名称的语义角色跳过（与 click 拾取一致）
                var tag = (el.tagName || '').toLowerCase();
                var lvl = (role === 'heading') ? getHeadingLevel(el) : 0;
                var dk = el.getAttribute('data-i18n');
                var key = (dk && dk.trim()) ? dk.trim() : '';
                var k = role + '|' + name + '|' + tag + '|' + lvl + '|' + key;
                if (seen[k]) continue;
                seen[k] = 1;
                out.push({ role: role, name: name, tag: tag, level: lvl, key: key });
              }
              return out;
            }
            """;

    // ------------------------------------------------------------------
    // 整页 a11y tree 入口（便捷方式）
    // ------------------------------------------------------------------

    /**
     * 从整页 DOM 收集可交互元素的 a11y 信息（role/name），生成完整可编译的 Page 类源码。
     * 使用注入脚本遍历 {@code computedRole}/{@code computedName}，不依赖已移除的
     * Playwright accessibilitySnapshot API。
     *
     * @param page          当前 Playwright Page（须已导航到目标页）
     * @param packageName   生成类的包名
     * @param pageClassName 生成类名
     * @param nlsFiles       类级 {@code @RoleFile} 路径
     * @return 完整 Java 类源码
     */
    public static String generate(Page page, String packageName, String pageClassName, String... nlsFiles) {
        List<RoleEntry> entries = collectFromPage(page);
        if (entries.isEmpty()) {
            return "// no interactive elements collected (page may not be ready)\n";
        }
        return generate(entries, packageName, pageClassName, nlsFiles);
    }

    /** 把整页生成的 Page 类源码打印到日志，便于直接复制 */
    public static void dump(Page page, String packageName, String pageClassName, String... nlsFiles) {
        log.info("\n========== Generated Page class ==========\n{}",
                generate(page, packageName, pageClassName, nlsFiles));
    }

    /** 把整页生成的 Page 类源码写入文件（outputDir 为源码根，如 src/test/java） */
    public static void write(Page page, String outputDir, String packageName,
                             String pageClassName, String... nlsFiles) {
        write(generate(page, packageName, pageClassName, nlsFiles),
                outputDir, packageName, pageClassName,
                nlsFiles.length > 0 ? nlsFiles[0] : "");
    }

    // ------------------------------------------------------------------
    // 条目列表入口（按需拾取 / 自定义来源）
    // ------------------------------------------------------------------

    /**
     * 从一组已解析的 {@link RoleEntry} 生成完整可编译的 Page 类源码。
     * 这是核心方法：整页 tree、按需拾取、或外部来源都先转换为条目列表再调用本方法。
     *
     * @param entries       元素列表（role + name）
     * @param packageName   生成类的包名
     * @param pageClassName 生成类名
     * @param nlsFiles       类级 {@code @RoleFile} 路径
     * @return 完整 Java 类源码
     */
    public static String generate(List<RoleEntry> entries, String packageName,
                                  String pageClassName, String... nlsFiles) {
        List<GeneratedField> specs = assignFields(entries);
        StringBuilder fields = new StringBuilder();
        boolean hasRole = false;          // 任意 @RoleElement 字段（角色或语义）
        boolean hasAriaRole = false;      // 角色策略字段（需 import AriaRole 常量）
        boolean hasElement = false;
        for (GeneratedField s : specs) {
            RoleEntry e = s.entry;
            if (e.isRoleStrategy()) {
                hasRole = true;
                hasAriaRole = true;
            } else {
                switch (e.getStrategy()) {
                    case "text":
                    case "altText":
                    case "title":
                    case "placeholder":
                    case "testid":
                    case "label":
                        hasRole = true; break;   // 语义字段也归类于 @RoleElement
                    default:
                        hasElement = true; break;   // id / css 等纯 CSS/XPath
                }
            }
            fields.append(s.annotation).append("\n    public PageElement ").append(s.fieldName).append(";\n\n");
        }

        // 按需构建 import：只有存在对应字段类型时才引入，避免未使用的 import。
        StringBuilder imports = new StringBuilder();
        if (hasAriaRole) {
            imports.append("import com.microsoft.playwright.options.AriaRole;\n\n");
        }
        imports.append("import com.hsbc.cmb.hk.dbb.automation.framework.web.page.PageElement;\n");
        if (hasElement) {
            imports.append("import com.hsbc.cmb.hk.dbb.automation.framework.web.page.Element;\n");
        }
        if (hasRole) {
            imports.append("import com.hsbc.cmb.hk.dbb.automation.framework.web.page.RoleElement;\n");
            imports.append("import com.hsbc.cmb.hk.dbb.automation.framework.web.page.RoleFile;\n");
        }
        imports.append("import com.hsbc.cmb.hk.dbb.automation.framework.web.page.base.BasePage;\n");

        // 只有存在 @RoleElement 字段时才需要类级 @RoleFile（NLS 文件）。
        // 支持一页面对应多个 nls 文件：单文件生成 @RoleFile("x")，多文件生成 @RoleFile({"a","b"})。
        String classAnnotation = "";
        if (hasRole && nlsFiles != null && nlsFiles.length > 0) {
            if (nlsFiles.length == 1) {
                classAnnotation = "@RoleFile(\"" + escapeJava(nlsFiles[0]) + "\")\n";
            } else {
                StringBuilder sb = new StringBuilder("@RoleFile({");
                for (int i = 0; i < nlsFiles.length; i++) {
                    if (i > 0) sb.append(", ");
                    sb.append('"').append(escapeJava(nlsFiles[i])).append('"');
                }
                sb.append("})\n");
                classAnnotation = sb.toString();
            }
        }

        return "package " + packageName + ";\n\n"
                + imports + "\n"
                + classAnnotation
                + "public class " + pageClassName + " extends BasePage {\n\n"
                + fields
                + "}\n";
    }

    /** 把条目列表生成的 Page 类源码打印到日志，便于直接复制 */
    public static void dump(List<RoleEntry> entries, String packageName,
                            String pageClassName, String... nlsFiles) {
        log.info("\n========== Generated Page class ==========\n{}",
                generate(entries, packageName, pageClassName, nlsFiles));
    }

    /** 把条目列表生成的 Page 类写入文件（outputDir 为源码根，如 src/test/java） */
    public static void write(List<RoleEntry> entries, String outputDir, String packageName,
                             String pageClassName, String... nlsFiles) {
        write(generate(entries, packageName, pageClassName, nlsFiles),
                outputDir, packageName, pageClassName,
                nlsFiles.length > 0 ? nlsFiles[0] : "");
    }

    // ------------------------------------------------------------------
    // 采集：整页 DOM → RoleEntry 列表（与 BasePage.dumpAccessibilityRoles 共用）
    // ------------------------------------------------------------------

    /**
     * 用注入脚本遍历整页 DOM，收集可交互元素的 role/name/tag。
     * 这是整页生成与 {@code BasePage.dumpAccessibilityRoles} 共用的采集入口，
     * 不依赖已移除的 Playwright accessibilitySnapshot API。
     *
     * @param page 已导航到目标页的 Playwright Page（主 frame）
     * @return 采集到的元素列表（按 DOM 顺序、已去重）
     */
    public static List<RoleEntry> collectFromPage(Page page) {
        List<RoleEntry> entries = new ArrayList<>();
        Object raw = page.evaluate(COLLECT_SCRIPT);
        if (!(raw instanceof List)) {
            return entries;
        }
        for (Object o : (List<?>) raw) {
            if (!(o instanceof Map)) continue;
            Map<?, ?> m = (Map<?, ?>) o;
            String role = asString(m.get("role"));
            String name = asString(m.get("name"));
            String tag = asString(m.get("tag"));
            if (role == null || role.isBlank()) continue;
            int level = 0;
            Object lv = m.get("level");
            if (lv instanceof Number) level = ((Number) lv).intValue();
            else if (lv != null) { try { level = Integer.parseInt(lv.toString()); } catch (Exception ignore) { } }
            if (level < 1 || level > 6) level = 0;
            String key = asString(m.get("key"));
            if (key != null && key.isBlank()) key = null;
            entries.add(new RoleEntry(role, name, tag, level, key));
        }
        return entries;
    }

    // ------------------------------------------------------------------
    // 内部工具
    // ------------------------------------------------------------------

    private static void write(String source, String outputDir, String packageName,
                              String pageClassName, String nlsFile) {
        String relative = packageName.replace('.', '/');
        Path dir = Path.of(outputDir, relative);
        try {
            Files.createDirectories(dir);
            Path file = dir.resolve(pageClassName + ".java");
            Files.writeString(file, source, StandardCharsets.UTF_8);
            log.info("[a11y] Page class written: {}", file);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to write generated page: " + e.getMessage(), e);
        }
    }

    /** 按 strategy 给非角色字段命名加语义后缀，使不同定位策略产出可区分的字段名：
     *  label → Label、placeholder → Input、text → Text、altText → Img、title → Title、testid → TestId */
    private static final Map<String, String> STRATEGY_SUFFIX = Map.of(
            "placeholder", "Input", "label", "Label", "altText", "Img",
            "text", "Text", "title", "Title", "testid", "TestId"
    );



    /** 从选择器里抽取一个可读片段用于字段命名（id/css 无 name 时） */
    private static String selectorLabel(String strategy, String selector) {
        if (selector == null) return "";
        if (selector.startsWith("#")) return selector.substring(1);
        // [attr="value"] → value
        int eq = selector.indexOf("=\"");
        if (eq >= 0) {
            int end = selector.indexOf('"', eq + 2);
            if (end > eq + 2) return selector.substring(eq + 2, end);
        }
        return selector;
    }

    /** 把选择器包成合法的 Java 字符串字面量（转义反斜杠与双引号） */
    private static String toJavaStringLiteral(String s) {
        if (s == null) return "\"\"";
        return "\"" + escapeJava(s) + "\"";
    }

    /** 转义 Java 字符串字面量内部的反斜杠与双引号（不含外层引号） */
    private static String escapeJava(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String toFieldNameWithSuffix(String name, String suffix, int idx, Set<String> used) {
        String base = toIdentifier(name, idx, true);
        if (base.isEmpty()) {
            base = "element" + idx;
        }
        // camelCase 字段名（首字母小写）：base 已为 lowerCamel，直接拼后缀
        String candidate = base + suffix;
        String unique = candidate;
        int n = 2;
        while (used.contains(unique)) {
            unique = candidate + (n++);
        }
        used.add(unique);
        return unique;
    }

    /** name → 标识符：camel=true 输出 lowerCamelCase，否则输出下划线 slug（小写）。
     *  特殊字符（标点、符号、空白等）一律丢弃并作为单词边界；仅保留字母/数字/_/$。
     *  保证结果是合法 Java 标识符：非空，且首字符为合法起始（前导数字或残留特殊字符时前置 Field）。 */
    private static String toIdentifier(String name, int idx, boolean camel) {
        if (name == null || name.isBlank()) {
            return (camel ? "element" : "element_") + idx;
        }
        StringBuilder sb = new StringBuilder();
        boolean upperNext = !camel;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                sb.append(upperNext ? Character.toUpperCase(c) : Character.toLowerCase(c));
                upperNext = false;
            } else if (camel) {
                upperNext = true;
            } else if (sb.length() > 0 && sb.charAt(sb.length() - 1) != '_') {
                sb.append('_');
            }
        }
        String s = sb.toString().replaceAll("_+", camel ? "" : "_").replaceAll("^_|_$", "");
        // 防特殊字符残留：仅保留合法 Java 标识符字符（字母/数字/_/$）
        s = s.replaceAll("[^\\p{L}\\p{N}_$]", "");
        if (s.isEmpty()) {
            return (camel ? "element" : "element_") + idx;
        }
        // 合法 Java 标识符不能以数字或特殊字符开头：前置 Field 修正（如 1MarSun → Field1MarSun）
        if (!Character.isJavaIdentifierStart(s.charAt(0))) {
            s = "Field" + s;
        }
        return s;
    }

    private static String toFieldName(String name, String role, int idx, Set<String> used) {
        String base = toIdentifier(name, idx, true);          // userName（lowerCamel）
        // camelCase 字段名（首字母小写）：base 已为 lowerCamel，直接拼后缀
        String suffix = ROLE_SUFFIX.getOrDefault(role.toLowerCase(Locale.ROOT), "");
        String candidate = base + suffix;
        String unique = candidate;
        int n = 2;
        while (used.contains(unique)) {
            unique = candidate + (n++);
        }
        used.add(unique);
        return unique;
    }

    /**
     * 条目是否可生成字段：
     *  - role 策略需有 role；
     *  - 语义策略（text/title/placeholder/label/i18n/testid）按 name 生成 {@code @RoleElement}，
     *    仅需 name 非空；
     *  - 纯选择器策略（id/css）按 selector 生成 {@code @Element}，需 selector 非空。
     * 旧逻辑对非 role 策略一律只校验 selector，导致 placeholder/text/title/label/i18n/testid
     * 等策略（其 selector 恒为 null）被整体丢弃——表现即“生成的 Page 类与 step 缺大量元素”。
     * 现放宽为 name 或 selector 任一非空即有效，与 {@link #makeField} 的实际生成逻辑一致。
     */
    private static boolean isValidEntry(RoleEntry e) {
        if (e == null) return false;
        if (e.isRoleStrategy()) {
            return e.getRole() != null && !e.getRole().isBlank();
        }
        boolean hasName = e.getName() != null && !e.getName().isBlank();
        boolean hasSelector = e.getSelector() != null && !e.getSelector().isBlank();
        return hasName || hasSelector;
    }

    /** 定位器签名：相同签名视为同一定位器，生成时去重。
     *  role 策略按 role+name/key；语义策略按 strategy+name；id/css 按 strategy+selector。
     *  包级可见，供 {@link RoleElementStepGenerator} 复用，保证字段名与 Page 类一致。 */
    static String locatorKey(RoleEntry e) {
        if (e.isRoleStrategy()) {
            String role = (e.getRole() == null ? "" : e.getRole()).toLowerCase(Locale.ROOT);
            String name = e.getName() == null ? "" : e.getName();
            String key = (e.getResolvedKey() != null && !e.getResolvedKey().isBlank())
                    ? e.getResolvedKey() : name;
            return "role:" + role + ":" + key;
        }
        String strategy = e.getStrategy() == null ? "" : e.getStrategy();
        if ("id".equals(strategy) || "css".equals(strategy)) {
            return strategy + ":" + (e.getSelector() == null ? "" : e.getSelector());
        }
        String name = e.getName() == null ? "" : e.getName();
        return strategy + ":" + name;
    }

    private static String asString(Object o) {
        return o == null ? null : o.toString();
    }
}
