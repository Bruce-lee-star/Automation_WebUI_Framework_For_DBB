package com.hsbc.cmb.hk.dbb.automation.framework.web.page.scan;

import com.hsbc.cmb.hk.dbb.automation.framework.web.page.RoleElement;
import com.hsbc.cmb.hk.dbb.automation.framework.web.utils.NlsNameTranslator;
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
import java.util.stream.Collectors;

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
            // 跳过“整区域级匿名布局 css 定位”：选择器以 body 开头（cssPathOf 在 5 层内找不到 stable id，
            // 只能拼出 body > div:nth-of-type(...) 这类整页级路径）、或纯裸 div 链（div > div > ...，
            // 无任何 id/属性/class 锚点）。这类定位不稳定、无业务语义，不应生成页面类字段。
            if ("css".equals(e.getStrategy())) {
                String sel = e.getSelector();
                if (sel != null && (sel.startsWith("body") || sel.startsWith("html")
                        || isBareDivChain(sel))) continue;
            }
            String sig = locatorKey(e);
            if (!seenLocators.add(sig)) continue;
            specs.add(makeField(e, specs.size(), usedNames));
        }
        return specs;
    }

    /** 计算单个元素的字段名 + 注解（与 appendField/appendRoleField/appendSelectorField 旧逻辑一致，仅改为返回结构化结果）。 */
    private static GeneratedField makeField(RoleEntry e, int idx, Set<String> usedNames) {
        // iframe 层级前缀：让嵌套 iframe 内的元素字段名带归属层级（如 frameOneIframe1），
        // 消除「iframe1」这类仅靠标题文字、看不出位于哪个 iframe 的语义模糊命名。
        String framePrefix = framePrefix(e.getFramePath());
        if (e.isRoleStrategy()) {
            String roleConst = toAriaRoleConst(e.getRole());
            String name = e.getName();
            String resolvedKey = e.getResolvedKey();
            boolean matched = resolvedKey != null && !resolvedKey.isBlank();
            String fieldBase = matched ? resolvedKey : name;
            String field = toFieldName(framePrefix, fieldBase, e.getRole(), idx, usedNames);
            StringBuilder ann = new StringBuilder("    @RoleElement(role = AriaRole.").append(roleConst);
            if (matched) {
                ann.append(", key = \"").append(escapeJava(resolvedKey)).append("\"");
            } else {
                ann.append(", name = \"").append(name == null ? "" : escapeJava(name)).append("\"");
            }
            // 标题层级：仅 heading 角色有意义，1–6；0 表示不限层级（对齐 getByRole(HEADING).setLevel(n)）。
            if (e.getLevel() > 0) ann.append(", level = ").append(e.getLevel());
            if (e.isCleaned()) ann.append(", exact = false");
            // 可访问状态过滤属性（对齐 page.pause() 的 getByRole setDisabled/setPressed/setExpanded）
            if (e.getDisabled() != null) ann.append(", disabled = RoleElement.State.").append(e.getDisabled().name());
            if (e.getPressed() != null) ann.append(", pressed = RoleElement.State.").append(e.getPressed().name());
            if (e.getExpanded() != null) ann.append(", expanded = RoleElement.State.").append(e.getExpanded().name());
            appendFrame(ann, e);
            ann.append(")");
            return new GeneratedField(field, ann.toString(), e);
        }
        String strategy = e.getStrategy();
        String resolvedKey = e.getResolvedKey();
        boolean matched = resolvedKey != null && !resolvedKey.isBlank();
        String base = matched ? resolvedKey
                : ((e.getName() != null && !e.getName().isBlank())
                    ? e.getName() : selectorLabel(strategy, locatingSelector(e)));
        String suffix = STRATEGY_SUFFIX.getOrDefault(strategy, "");
        String field = toFieldNameWithSuffix(framePrefix, base, suffix, idx, usedNames);
        String annotation;
        switch (strategy) {
            case "text":
            case "altText":
            case "title":
            case "placeholder":
            case "label":
                if (matched) {
                    StringBuilder ann = new StringBuilder("    @RoleElement(key = \"").append(escapeJava(resolvedKey)).append("\"");
                    if (e.isCleaned()) ann.append(", exact = false");
                    appendFrame(ann, e);
                    ann.append(")");
                    annotation = ann.toString();
                } else {
                    StringBuilder ann = new StringBuilder("    @RoleElement(").append(strategy).append(" = ").append(toJavaStringLiteral(e.getName()));
                    appendFrame(ann, e);
                    ann.append(")");
                    annotation = ann.toString();
                }
                break;
            case "testid": {
                StringBuilder ann = new StringBuilder("    @RoleElement(testId = ").append(toJavaStringLiteral(e.getName()));
                appendFrame(ann, e);
                ann.append(")");
                annotation = ann.toString();
                break;
            }
            case "id":
            case "css":
            default: {
                StringBuilder ann = new StringBuilder("    @Element(").append(toJavaStringLiteral(locatingSelector(e)));
                appendFrame(ann, e);
                ann.append(")");
                annotation = ann.toString();
                break;
            }
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
              // 交互/语义标签白名单：带这些标签的元素 computedRole 才可能返回非 generic 角色；
              // 其余纯容器（div/span/section...）即便算 computedRole 也多半是 generic，可跳过，
              // 大幅减少昂贵的 el.computedRole() 调用（O5：整页扫描卡顿主因）。
              var SEMANTIC_TAGS = { a:1, button:1, input:1, select:1, textarea:1, label:1,
                img:1, h1:1, h2:1, h3:1, h4:1, h5:1, h6:1, table:1, th:1, td:1, li:1,
                nav:1, main:1, header:1, footer:1, form:1, article:1, dialog:1, option:1,
                progress:1, meter:1, summary:1, details:1, figure:1, figcaption:1, dl:1, dt:1, dd:1 };
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
                var tag = (el.tagName || '').toLowerCase();
                // 快速跳过：既无 role 属性、又非语义标签的纯容器，直接忽略，不调 computedRole。
                var hasRoleAttr = !!(el.getAttribute && el.getAttribute('role'));
                if (!hasRoleAttr && !SEMANTIC_TAGS[tag]) continue;
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
                // 仅在确实无 aria-label 时再用文本兜底；computedName 已优先，此处无需二次计算。
                if (!name) {
                  var t = (el.textContent || '').trim();
                  name = t ? t.substring(0, 120) : '';
                }
                if (!name) continue;   // 无名称的语义角色跳过（与 click 拾取一致）
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
            // 标注元素归属空间（space）：iframe / shadow 嵌套位置。非默认 "main" 时写注释，
            // 便于阅读生成类时直观看出该元素位于哪个空间（与 step 内 frame 切换逻辑同源）。
            String space = s.entry.getSpace();
            List<String> framePath = s.entry.getFramePath();
            if (space != null && !"main".equals(space)) {
                fields.append("    // space: ").append(space).append("\n");
            }
            // 【明确"iframe 元素属于哪个控件"】当元素来自某个 iframe（framePath 非空）时，
            // 额外标注其归属的 iframe 控件层级，让使用者一眼知道该字段是「frameOne 里的 …」还是
            // 「frameTwo→frameOne 嵌套里的 …」，避免与主页元素混淆。
            if (framePath != null && !framePath.isEmpty()) {
                String frameReadable = framePath.stream()
                        .map(seg -> seg.replaceAll("^\\[|\\]$", ""))
                        .collect(Collectors.joining(" → "));
                fields.append("    // 控件: iframe [").append(frameReadable).append("]\n");
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

    /**
     * 取得元素的「定位用 selector」：
     * <ul>
     *   <li>普通元素：直接返回 {@code entry.getSelector()}（可能含 iframe 衔接，由 {@code switchToFrame} 处理）。</li>
     *   <li>位于 open shadow 内的元素：剥离最外层宿主前缀，仅返回 shadow 内部的相对路径。
     *       因为运行时已通过 {@code switchToShadow(host)} 进入该 shadow，继续用 {@code >>>} 前缀穿透，
     *       若 selector 仍带宿主会重复导致 shadow 内定位失败。</li>
     * </ul>
     */
    private static String locatingSelector(RoleEntry e) {
        String sel = e.getSelector();
        if (sel == null) return null;
        java.util.List<String> sp = e.getShadowPath();
        if (sp != null && !sp.isEmpty()) {
            String host = sp.get(0); // 最外层宿主选择器（与 __cssSelectorOf 生成的衔接前缀一致）
            String prefix = host + " > ";
            if (sel.startsWith(prefix)) {
                return sel.substring(prefix.length());
            }
            // 退化情况：selector 恰好等于 host 本身
            if (sel.equals(host)) {
                return "*";
            }
        }
        return sel;
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

    /**
     * 对齐 page.pause() 的 frameLocator 录制：若元素位于 iframe 内（framePath 非空），
     * 向注解追加 {@code frame = {"seg1", "seg2"}}（自顶向下逐层），供运行期 {@link
     * com.hsbc.cmb.hk.dbb.automation.framework.web.page.binding.RoleElementBinder} 用
     * {@code page.frameLocator(seg).locator(...)} 逐层下钻。
     */
    private static void appendFrame(StringBuilder ann, RoleEntry e) {
        List<String> fp = e.getFramePath();
        if (fp == null || fp.isEmpty()) return;
        ann.append(", frame = {");
        for (int i = 0; i < fp.size(); i++) {
            if (i > 0) ann.append(", ");
            ann.append(toJavaStringLiteral(fp.get(i)));
        }
        ann.append("}");
    }

    private static String toFieldNameWithSuffix(String prefix, String name, String suffix, int idx, Set<String> used) {
        String base = containsCjk(name) ? NlsNameTranslator.toIdentifier(name, false) : toIdentifier(name, idx, true);
        if (base.isEmpty()) {
            base = "element" + idx;
        }
        // camelCase 字段名（首字母小写）：prefix(iframe 层级) + base + suffix 依次拼接
        String candidate = prefix + base + suffix;
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

    /**
     * 保留原大小写的标识符转换：专用于 iframe 层级前缀（name/id 来自 HTML 属性，
     * 用户期望 frameOne 就是 frameOne，而非被 toIdentifier 压成全小写 frameone）。
     * 仅做合法性清洗：非法字符丢弃（并作为单词边界使后续首字母大写），首字符若非合法
     * 标识符起始则前置 Field；除首字符强制小写外，其余字符保留原名大小写。
     */
    private static String toPreserveCaseIdentifier(String name, int idx) {
        if (name == null || name.isBlank()) {
            return "frame" + idx;
        }
        StringBuilder sb = new StringBuilder();
        boolean upperNext = false;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (Character.isLetterOrDigit(c) || c == '_' || c == '$') {
                if (sb.length() == 0) {
                    // 首字符：强制小写，且若原为首字母/数字则顺延清洗逻辑
                    sb.append(Character.toLowerCase(c));
                } else {
                    sb.append(upperNext ? Character.toUpperCase(c) : c);
                }
                upperNext = false;
            } else {
                upperNext = true; // 分隔符后是下一个词的首字母，保留原大小写
            }
        }
        String s = sb.toString();
        // 合法 Java 标识符字符清洗（理论上已处理，双保险）
        s = s.replaceAll("[^\\p{L}\\p{N}_$]", "");
        if (s.isEmpty()) {
            return "frame" + idx;
        }
        if (!Character.isJavaIdentifierStart(s.charAt(0))) {
            s = "Frame" + s;
        }
        return s;
    }

    /** 判断字符串是否含 CJK（中日韩统一表意文字），用于决定字段名是否走中文→英文翻译。 */
    private static boolean containsCjk(String s) {
        if (s == null) return false;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) >= 0x4E00 && s.charAt(i) <= 0x9FFF) return true;
        }
        return false;
    }

    private static String toFieldName(String prefix, String name, String role, int idx, Set<String> used) {
        String base = containsCjk(name) ? NlsNameTranslator.toIdentifier(name, false) : toIdentifier(name, idx, true); // userName（lowerCamel）
        // camelCase 字段名（首字母小写）：prefix(iframe 层级) + base + role 后缀 依次拼接
        String suffix = ROLE_SUFFIX.getOrDefault(role.toLowerCase(Locale.ROOT), "");
        String candidate = prefix + base + suffix;
        String unique = candidate;
        int n = 2;
        while (used.contains(unique)) {
            unique = candidate + (n++);
        }
        used.add(unique);
        return unique;
    }

    /**
     * 由 framePath（自顶向下的 iframe 段，如 [iframe[name="frameOne"], iframe[name="frameTwo"]]）
     * 拼出字段名前缀（camelCase，如 "frameOneFrameTwo"），空 path 返回 ""。
     * 用于给 iframe 内元素命名时带层级归属，避免「iframe1」这类语义模糊的命名。
     */
    private static String framePrefix(List<String> framePath) {
        if (framePath == null || framePath.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (String seg : framePath) {
            String id = frameSegId(seg);
            if (id != null && !id.isBlank()) {
                // 保留 iframe name 原大小写（frameOne → frameOne，而非被 toIdentifier 压成 frameone）
                sb.append(toPreserveCaseIdentifier(id, sb.length()));
            }
        }
        return sb.toString();
    }

    /** 从单条 iframe 段中抽取可识别的锚点（name="x" / name=x / #id），用于层级前缀。 */
    private static String frameSegId(String seg) {
        if (seg == null) return "";
        int n = seg.indexOf("name=\"");
        if (n >= 0) {
            int end = seg.indexOf('"', n + 6);
            if (end > n + 6) return seg.substring(n + 6, end);
        }
        int h = seg.indexOf('#');
        if (h >= 0) {
            int end = h + 1;
            while (end < seg.length() && (Character.isLetterOrDigit(seg.charAt(end)) || seg.charAt(end) == '-' || seg.charAt(end) == '_')) {
                end++;
            }
            if (end > h + 1) return seg.substring(h + 1, end);
        }
        n = seg.indexOf("name=");
        if (n >= 0) {
            String rest = seg.substring(n + 5).trim();
            int sp = rest.indexOf(' ');
            return sp > 0 ? rest.substring(0, sp) : rest;
        }
        return "";
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
    private static boolean isValidEntry(RoleEntry e) {        if (e == null) return false;
        if (e.isRoleStrategy()) {
            return e.getRole() != null && !e.getRole().isBlank();
        }
        boolean hasName = e.getName() != null && !e.getName().isBlank();
        boolean hasSelector = e.getSelector() != null && !e.getSelector().isBlank();
        return hasName || hasSelector;
    }

    /**
     * 判断 css 选择器是否为“纯裸 div 链”：仅由 div / nth-of-type / 子代组合构成，
     * 不含有任何 #id、[属性]、.class、其他标签名等稳定锚点。如 "div > div > div > div > div"、
     * "body > div:nth-of-type(3) > div > div > div:nth-of-type(4)"（body 开头已在调用处先行拦截）。
     * 这类定位随 DOM 结构微调即失效，无业务价值，生成页面类时跳过。
     */
    private static boolean isBareDivChain(String sel) {
        if (sel == null || sel.isBlank()) return false;
        // 含稳定锚点之一即视为有效定位，不跳过
        if (sel.indexOf('#') >= 0) return false;       // #id
        if (sel.indexOf('[') >= 0) return false;        // [属性]
        if (sel.indexOf('.') >= 0) return false;        // .class
        // 拆成各段，逐段检查：每段应为 div / html / body 或 div:nth-of-type(n)
        // （允许空白/子代符）。html、body 是整页级骨架节点，无业务锚点，一并视为裸链跳过。
        String[] parts = sel.split(">");
        if (parts.length < 2) return false;             // 单个选择器（如 #id、.cls、tag）不过滤
        for (String p : parts) {
            String t = p.trim();
            if (t.isEmpty()) continue;
            if (t.equals("html") || t.equals("body")) continue;
            if (!t.equals("div") && !t.matches("div:nth-of-type\\(\\d+\\)")) return false;
        }
        return true;
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
