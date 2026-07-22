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

    /** 按 role 给字段名加语义后缀（与设计文档 §6 一致） */
    private static final Map<String, String> ROLE_SUFFIX = Map.of(
            "button", "Btn", "link", "Link", "checkbox", "Chk", "radio", "Radio",
            "combobox", "Combo", "listbox", "List", "slider", "Slider",
            "switch", "Switch", "tab", "Tab", "menuitem", "MenuItem"
    );

    private RoleElementPageGenerator() {}

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
              var INTERACTIVE = ['button','textbox','searchbox','link','checkbox','radio',
                'combobox','listbox','slider','switch','tab','menuitem'];
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
                if (INTERACTIVE.indexOf(role) === -1) continue;
                var name;
                try { name = (typeof el.computedName === 'function') ? el.computedName() : ''; }
                catch (e) { name = ''; }
                if (!name) name = (el.getAttribute('aria-label') || '').trim();
                if (!name) {
                  var t = (el.textContent || '').trim();
                  name = t ? t.substring(0, 120) : '';
                }
                var tag = (el.tagName || '').toLowerCase();
                var key = role + '|' + name + '|' + tag;
                if (seen[key]) continue;
                seen[key] = 1;
                out.push({ role: role, name: name, tag: tag });
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
     * @param nlsFile       类级 {@code @RoleFile} 路径
     * @return 完整 Java 类源码
     */
    public static String generate(Page page, String packageName, String pageClassName, String nlsFile) {
        List<RoleEntry> entries = collectFromPage(page);
        if (entries.isEmpty()) {
            return "// no interactive elements collected (page may not be ready)\n";
        }
        return generate(entries, packageName, pageClassName, nlsFile);
    }

    /** 把整页生成的 Page 类源码打印到日志，便于直接复制 */
    public static void dump(Page page, String packageName, String pageClassName, String nlsFile) {
        log.info("\n========== Generated Page class ==========\n{}",
                generate(page, packageName, pageClassName, nlsFile));
    }

    /** 把整页生成的 Page 类源码写入文件（outputDir 为源码根，如 src/test/java） */
    public static void write(Page page, String outputDir, String packageName,
                             String pageClassName, String nlsFile) {
        write(generate(page, packageName, pageClassName, nlsFile),
                outputDir, packageName, pageClassName, nlsFile);
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
     * @param nlsFile       类级 {@code @RoleFile} 路径
     * @return 完整 Java 类源码
     */
    public static String generate(List<RoleEntry> entries, String packageName,
                                  String pageClassName, String nlsFile) {
        StringBuilder fields = new StringBuilder();
        Set<String> usedNames = new HashSet<>();
        boolean hasRole = false;
        boolean hasSelector = false;
        int idx = 0;
        for (RoleEntry e : entries) {
            if (e.isRoleStrategy()) {
                if (e.getRole() == null) continue;
                hasRole = true;
            } else {
                if (e.getSelector() == null || e.getSelector().isBlank()) continue;
                hasSelector = true;
            }
            appendField(e, idx, usedNames, fields);
            idx++;
        }

        // 按需构建 import：只有存在对应字段类型时才引入，避免未使用的 import。
        StringBuilder imports = new StringBuilder();
        if (hasRole) {
            imports.append("import com.microsoft.playwright.AriaRole;\n\n");
        }
        imports.append("import com.hsbc.cmb.hk.dbb.automation.framework.web.page.PageElement;\n");
        if (hasSelector) {
            imports.append("import com.hsbc.cmb.hk.dbb.automation.framework.web.page.Element;\n");
        }
        if (hasRole) {
            imports.append("import com.hsbc.cmb.hk.dbb.automation.framework.web.page.RoleElement;\n");
            imports.append("import com.hsbc.cmb.hk.dbb.automation.framework.web.page.RoleFile;\n");
        }
        imports.append("import com.hsbc.cmb.hk.dbb.automation.framework.web.page.base.BasePage;\n");

        // 只有存在 @RoleElement 字段时才需要类级 @RoleFile（NLS 文件）。
        String classAnnotation = hasRole ? "@RoleFile(\"" + nlsFile + "\")\n" : "";
        String nlsNote = hasRole
                ? " * 其中 @RoleElement 字段的 key 需与 nls 文件 " + nlsFile + " 的语言表对齐。\n"
                : "";

        return "package " + packageName + ";\n\n"
                + imports + "\n"
                + "/**\n"
                + " * @Generated 自动生成草稿（基于 page.pause() 风格的定位策略链）。请人工 review 后再合入主干。\n"
                + nlsNote
                + " */\n"
                + classAnnotation
                + "public class " + pageClassName + " extends BasePage {\n\n"
                + fields
                + "}\n";
    }

    /** 把条目列表生成的 Page 类源码打印到日志，便于直接复制 */
    public static void dump(List<RoleEntry> entries, String packageName,
                            String pageClassName, String nlsFile) {
        log.info("\n========== Generated Page class ==========\n{}",
                generate(entries, packageName, pageClassName, nlsFile));
    }

    /** 把条目列表生成的 Page 类写入文件（outputDir 为源码根，如 src/test/java） */
    public static void write(List<RoleEntry> entries, String outputDir, String packageName,
                             String pageClassName, String nlsFile) {
        write(generate(entries, packageName, pageClassName, nlsFile),
                outputDir, packageName, pageClassName, nlsFile);
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
            entries.add(new RoleEntry(role, name, tag, null));
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

    /** 按 strategy 给非角色字段命名加语义后缀 */
    private static final Map<String, String> STRATEGY_SUFFIX = Map.of(
            "placeholder", "Input", "altText", "Img"
    );

    private static void appendField(RoleEntry e, int idx, Set<String> used, StringBuilder fields) {
        if (e.isRoleStrategy()) {
            appendRoleField(e, idx, used, fields);
        } else {
            appendSelectorField(e, idx, used, fields);
        }
    }

    /** 角色 + 名称 → {@code @RoleElement}（保留 NLS 多语言能力） */
    private static void appendRoleField(RoleEntry e, int idx, Set<String> used, StringBuilder fields) {
        String role = e.getRole();
        String roleConst = toAriaRoleConst(role);
        String name = e.getName();
        // 命中 nls 反查则用真实 key（复用既有多语言表）；否则直接用字面名称定位，不依赖 nls。
        String resolvedKey = e.getResolvedKey();
        boolean matched = resolvedKey != null && !resolvedKey.isBlank();
        // 字段名优先用 key（更有语义），否则用 name 派生。
        String fieldBase = matched ? resolvedKey : name;
        String field = toFieldName(fieldBase, role, idx, used);       // SignInBtn

        StringBuilder comment = new StringBuilder("    // role=").append(role);
        if (name != null) comment.append(" name=\"").append(name).append("\"");
        if (matched) comment.append(" key=\"").append(resolvedKey).append("\" (nls 反查命中)");
        if (e.getTag() != null) comment.append(" <").append(e.getTag()).append(">");
        if (!roleConst.equals(role == null ? "" : role.toUpperCase(Locale.ROOT))) {
            comment.append(" (→ AriaRole.").append(roleConst).append(")");
        }
        fields.append(comment).append('\n');
        if (matched) {
            fields.append("    @RoleElement(role = AriaRole.")
                  .append(roleConst)
                  .append(", key = \"").append(escapeJava(resolvedKey)).append("\")").append('\n');
        } else {
            // 未反查到 nls key：以字面名称定位，新增 name 属性，无需 nls 文件。
            fields.append("    @RoleElement(role = AriaRole.")
                  .append(roleConst)
                  .append(", name = \"").append(name == null ? "" : escapeJava(name)).append("\")").append('\n');
        }
        fields.append("    public PageElement ").append(field).append(";\n\n");
    }

    /** text / testid / placeholder / altText / title / id / css → {@code @Element}（Playwright 字符串选择器） */
    private static void appendSelectorField(RoleEntry e, int idx, Set<String> used, StringBuilder fields) {
        String strategy = e.getStrategy();
        String selector = e.getSelector();
        // 字段命名基准：优先用 name（text/placeholder/alt/title 等），否则用选择器可读片段
        String label = (e.getName() != null && !e.getName().isBlank())
                ? e.getName()
                : selectorLabel(strategy, selector);
        String suffix = STRATEGY_SUFFIX.getOrDefault(strategy, "");
        String field = toFieldNameWithSuffix(label, suffix, idx, used);

        StringBuilder comment = new StringBuilder("    // strategy=").append(strategy);
        if (e.getName() != null && !e.getName().isBlank()) {
            comment.append(" name=\"").append(e.getName()).append("\"");
        }
        if (e.getTag() != null) comment.append(" <").append(e.getTag()).append(">");
        if ("css".equals(strategy)) comment.append(" (TODO：兜底 css 路径，请人工确认稳定性)");
        fields.append(comment).append('\n');
        fields.append("    @Element(").append(toJavaStringLiteral(selector)).append(")\n");
        fields.append("    public PageElement ").append(field).append(";\n\n");
    }

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
        base = base.substring(0, 1).toUpperCase() + base.substring(1);
        String candidate = base + suffix;
        String unique = candidate;
        int n = 2;
        while (used.contains(unique)) {
            unique = candidate + (n++);
        }
        used.add(unique);
        return unique;
    }

    /** name → 标识符：camel=true 输出 lowerCamelCase，否则输出下划线 slug（小写） */
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
        return s.isEmpty() ? ((camel ? "element" : "element_") + idx) : s;
    }

    private static String toFieldName(String name, String role, int idx, Set<String> used) {
        String base = toIdentifier(name, idx, true);          // signIn
        base = base.substring(0, 1).toUpperCase() + base.substring(1); // SignIn
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

    private static String asString(Object o) {
        return o == null ? null : o.toString();
    }
}
