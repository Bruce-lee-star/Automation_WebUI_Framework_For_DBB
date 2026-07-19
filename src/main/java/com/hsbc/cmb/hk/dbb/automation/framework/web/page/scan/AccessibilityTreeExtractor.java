package com.hsbc.cmb.hk.dbb.automation.framework.web.page.scan;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.PageElement;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.base.BasePage;
import com.microsoft.playwright.ElementHandle;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.AriaRole;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 获取当前 Page 的 <b>Accessibility（可访问性）树</b>的工具类。
 *
 * <p>底层基于 Playwright 1.58 的 {@code Locator.ariaSnapshot()}（返回 ARIA YAML），
 * 与 Playwright MCP 的 {@code browser_snapshot}、以及浏览器扩展的 AX 树是<b>同一棵语义树</b>，
 * 只是采集通道不同。每个节点带 {@code role}（如 button/link/textbox）与
 * {@code name}（可访问名），可作为 {@code page.getByRole(role, name)} 的稳定定位依据。
 *
 * <p><b>为什么转成 {@link AxNode} 中立模型：</b>
 * <ul>
 *   <li>Playwright 1.58 已移除 {@code page.accessibility().snapshot()}（原 {@code AccessibilityNode} API），
 *       本类改用 {@code ariaSnapshot()} 的 YAML 并解析为中立模型，业务侧只依赖 {@link AxNode}，隔离底层变更。</li>
 *   <li>{@link AxNode} 是纯 POJO，可直接被 Gson 序列化为 JSON，便于留档 / 喂给 PageObject 生成器。</li>
 * </ul>
 *
 * <p><b>注意（AX 树的固有边界）：</b>
 * <ul>
 *   <li>只含<b>语义节点</b>：无 ARIA 语义的 {@code <div onclick>} 不在树内（需 DOM 兜底）。</li>
 *   <li><b>不含坐标 / BoundingBox</b>：需坐标请另行按 role+name 反查定位。</li>
 *   <li>纯语义表示，已过滤静态噪声，只保留有意义的节点。</li>
 * </ul>
 *
 * <p><b>典型用法：</b>
 * <pre>{@code
 * // 1) 当前页语义树
 * AxNode tree = AccessibilityTreeExtractor.snapshotCurrentPage();
 *
 * // 2) 只要可交互元素（button/link/textbox/...）
 * List<AxNode> interactive = AccessibilityTreeExtractor.interactiveElements(tree);
 *
 * // 3) 直接拿到可操作的 PageElement（按 role+name 定位）
 * List<PageElement> els = AccessibilityTreeExtractor.interactiveElementsAsPageElements(tree, this);
 *
 * // 4) 富化 DOM 锚点（data-i18n / data-testid / id），再用 i18n 提供器取多语言 name
 * AccessibilityTreeExtractor.enrichWithDomAttributes(tree, page);
 * I18nNameProvider i18n = new ResourceBundleNameProvider("messages", Locale.ENGLISH, new Locale("zh"));
 * List<PageElement> elsI18n = AccessibilityTreeExtractor.interactiveElementsAsPageElements(tree, this, i18n);
 *
 * // 5) 打印可交互元素表（辅助测试：写 PageObject 前先「看一眼页面」）
 * AccessibilityTreeExtractor.printInteractiveElements(tree, i18n);
 * }</pre>
 */
public final class AccessibilityTreeExtractor {

    private static final Logger logger = LoggerFactory.getLogger(AccessibilityTreeExtractor.class);

    private static final Gson PRETTY_GSON =
            new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private static final Pattern NAME_PATTERN = Pattern.compile("\"([^\"]*)\"");

    /**
     * 被视为“可交互”的 ARIA role 集合，与 Playwright MCP「仅为交互式元素分配 ref」口径一致。
     * 用于 {@link #interactiveElements(AxNode)} 过滤。
     */
    private static final Set<String> INTERACTIVE_ROLES = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList(
                    "button", "link", "textbox", "searchbox", "checkbox", "radio",
                    "combobox", "listbox", "option", "menuitem", "menuitemcheckbox",
                    "menuitemradio", "slider", "spinbutton", "switch", "tab",
                    "treeitem", "gridcell", "textfield")));

    private AccessibilityTreeExtractor() {
    }

    // ==================== 采集入口 ====================

    /**
     * 获取<b>当前线程绑定 Page</b> 的可访问性树。
     * <p>Page 来源于 {@link BasePage#getCurrentPage()}，未初始化时返回 {@code null}。
     *
     * @return 中立模型根节点；页面/快照为空时返回 {@code null}
     */
    public static AxNode snapshotCurrentPage() {
        BasePage current = BasePage.getCurrentPage();
        if (current == null) {
            logger.warn("[A11y] No current BasePage bound to thread; cannot snapshot.");
            return null;
        }
        return snapshot(current.getPage(), true);
    }

    /**
     * 获取指定 Page 的可访问性树。
     *
     * @param page 目标页面，不能为 {@code null}
     * @return 中立模型根节点；快照为空时返回 {@code null}
     */
    public static AxNode snapshot(Page page) {
        return snapshot(page, true);
    }

    /**
     * 获取指定 Page 的可访问性树。
     *
     * <p>Playwright 1.58 起已移除 {@code page.accessibility().snapshot()}，
     * 本方法改用 {@code Locator.ariaSnapshot()}（返回 ARIA YAML）并解析为中立模型 {@link AxNode}。
     *
     * @param page            目标页面，不能为 {@code null}
     * @param interestingOnly 兼容旧签名保留；ariaSnapshot 默认返回语义化节点，该参数当前不生效
     * @return 中立模型根节点；快照为空时返回 {@code null}
     */
    public static AxNode snapshot(Page page, boolean interestingOnly) {
        if (page == null) {
            throw new IllegalArgumentException("page must not be null");
        }
        String yaml;
        try {
            yaml = page.locator("body").ariaSnapshot();
        } catch (RuntimeException e) {
            logger.warn("[A11y] ariaSnapshot() failed: {}", e.getMessage());
            return null;
        }
        if (yaml == null || yaml.isBlank()) {
            return null;
        }
        return parseYaml(yaml);
    }

    // ==================== YAML -> AxNode 解析 ====================

    /**
     * 将 {@code Locator.ariaSnapshot()} 返回的 ARIA YAML 解析为 {@link AxNode} 树。
     * <p>YAML 格式（Playwright）：每行 {@code - <role> "<name>" [ref=ex]}，子节点深度缩进；
     * 无名节点形如 {@code - <role> [ref=ex]}，含子节点形如 {@code - <role>:<name>}（冒号结尾）。
     */
    private static AxNode parseYaml(String yaml) {
        AxNode root = new AxNode();
        root.role = "root";
        Deque<NodeIndent> stack = new ArrayDeque<>();
        for (String rawLine : yaml.split("\n", -1)) {
            if (rawLine.trim().isEmpty()) {
                continue;
            }
            int indent = leadingSpaces(rawLine);
            String content = rawLine.trim();
            if (!content.startsWith("- ")) {
                continue;
            }
            content = content.substring(2).trim();
            AxNode node = parseLine(content);
            while (!stack.isEmpty() && stack.peek().indent >= indent) {
                stack.pop();
            }
            AxNode parent = stack.isEmpty() ? root : stack.peek().node;
            if (parent.children == null) {
                parent.children = new ArrayList<>();
            }
            parent.children.add(node);
            stack.push(new NodeIndent(indent, node));
        }
        return root;
    }

    private static AxNode parseLine(String content) {
        AxNode n = new AxNode();
        Matcher m = NAME_PATTERN.matcher(content);
        if (m.find()) {
            n.name = m.group(1);
            // 从 content 中移除引号包裹的 name 部分，剩余才是 role（+ 可能的冒号/属性）
            content = content.substring(0, m.start()) + content.substring(m.end());
        }
        // 去掉属性段（[ref=ex]、[level=1] 等）
        content = content.replaceAll("\\[[^\\]]*\\]", "").trim();
        // 去掉尾随冒号（含子节点的节点形如 role:）
        if (content.endsWith(":")) {
            content = content.substring(0, content.length() - 1).trim();
        }
        // 去掉残留引号，取首个 token 作为 role（如 "button" / "link" / "textbox"）
        content = content.replace("\"", "").trim();
        if (content.isEmpty()) {
            n.role = null;
        } else {
            int sp = content.indexOf(' ');
            n.role = (sp < 0) ? content : content.substring(0, sp);
        }
        return n;
    }

    private static int leadingSpaces(String s) {
        int i = 0;
        while (i < s.length() && s.charAt(i) == ' ') {
            i++;
        }
        return i;
    }

    /** 解析 YAML 行的缩进栈单元。 */
    private static final class NodeIndent {
        final int indent;
        final AxNode node;

        NodeIndent(int indent, AxNode node) {
            this.indent = indent;
            this.node = node;
        }
    }

    // ==================== 遍历 / 过滤 ====================

    /**
     * 深度优先<b>扁平化</b>整棵树为节点列表（含根，按先序）。
     *
     * @param root 根节点，可为 {@code null}
     * @return 扁平列表；root 为 null 时返回空列表
     */
    public static List<AxNode> flatten(AxNode root) {
        List<AxNode> out = new ArrayList<>();
        collect(root, out);
        return out;
    }

    private static void collect(AxNode node, List<AxNode> out) {
        if (node == null) {
            return;
        }
        out.add(node);
        if (node.children != null) {
            for (AxNode c : node.children) {
                collect(c, out);
            }
        }
    }

    /**
     * 从树中筛出全部<b>可交互</b>节点（role ∈ {@link #INTERACTIVE_ROLES}）。
     *
     * @param root 根节点
     * @return 可交互节点扁平列表
     */
    public static List<AxNode> interactiveElements(AxNode root) {
        List<AxNode> result = new ArrayList<>();
        for (AxNode n : flatten(root)) {
            if (n.role != null && INTERACTIVE_ROLES.contains(n.role.toLowerCase(Locale.ROOT))) {
                result.add(n);
            }
        }
        return result;
    }

    /** 判断某 role 是否被本工具视为可交互。 */
    public static boolean isInteractiveRole(String role) {
        return role != null && INTERACTIVE_ROLES.contains(role.toLowerCase(Locale.ROOT));
    }

    /**
     * 把可交互节点直接转成可操作的 {@link PageElement}（基于 role + name 定位）。
     * <p>等价于 {@link #interactiveElementsAsPageElements(AxNode, BasePage, I18nNameProvider)}
     * 传入 {@code null} 提供器：仅用 AX 的当前语言 name（单语），不解析多语言。
     */
    public static List<PageElement> interactiveElementsAsPageElements(AxNode root, BasePage page) {
        return interactiveElementsAsPageElements(root, page, null);
    }

    /**
     * 带 i18n 提供器的可交互元素转换（推荐用于多语言应用）。
     * <p>对含 {@code i18nKey} 的节点，用 {@link I18nNameProvider#namesForKey(String)} 解析为
     * <b>多语言 name 候选</b>，生成语言无关的定位器
     * （{@code element(role, name_en, name_zh, ...)}，底层正则 OR）；
     * 无 i18nKey 或提供器返回空时，降级用 AX 的当前语言 name。
     *
     * <p><b>配合 DOM 富化可获得最佳效果</b>：先 {@link #enrichWithDomAttributes(AxNode, Page)}
     * 回读页面 {@code data-i18n} 到 {@code AxNode.i18nKey}，再调用本方法即可产出
     * 跨语言稳定的 PageObject 定位器。
     *
     * @param root  AX 树根节点
     * @param page  当前 {@link BasePage}
     * @param i18n  i18n 名称提供器；{@code null} 表示不解析多语言
     * @return 可交互 {@link PageElement} 列表
     */
    public static List<PageElement> interactiveElementsAsPageElements(AxNode root, BasePage page, I18nNameProvider i18n) {
        List<PageElement> out = new ArrayList<>();
        for (AxNode n : interactiveElements(root)) {
            if (n.role == null) {
                continue;
            }
            try {
                AriaRole role = AriaRole.valueOf(n.role.toUpperCase(Locale.ROOT));
                String[] names = resolveNames(n, i18n);
                if (names == null) {
                    out.add(page.element(role));          // 仅 role
                } else {
                    out.add(page.element(role, names));   // role + 多 name（正则 OR）
                }
            } catch (IllegalArgumentException e) {
                logger.debug("[A11y] skip node with unmapped role '{}': {}", n.role, e.getMessage());
            }
        }
        return out;
    }

    /**
     * 解析节点应使用的 name 候选：i18nKey 优先（语言无关），降级 AX name（单语）。
     */
    private static String[] resolveNames(AxNode n, I18nNameProvider i18n) {
        if (i18n != null && n.i18nKey != null && !n.i18nKey.isBlank()) {
            String[] fromKey = i18n.namesForKey(n.i18nKey);
            if (fromKey != null && fromKey.length > 0) {
                return fromKey;
            }
        }
        if (n.name != null && !n.name.isBlank()) {
            return new String[]{n.name};
        }
        return null;
    }

    /**
     * 从真实 DOM <b>富化</b>可交互节点：回读语言无关锚点属性
     * （{@code data-i18n} / {@code data-testid} / {@code id} / {@code tagName}）。
     * <p>底层用 {@code page.getByRole(role[, {name}])} 反查元素再读属性，需真实浏览器页面。
     * 单个节点富化失败（无匹配 / 评估异常）时该节点字段保持 {@code null}，不影响其余节点。
     *
     * <p>富化后，{@link #interactiveElementsAsPageElements(AxNode, BasePage, I18nNameProvider)}
     * 即可用 {@code data-i18n} 作为 key 产出跨语言稳定的多 name 定位器。
     */
    public static void enrichWithDomAttributes(AxNode root, Page page) {
        if (page == null || root == null) {
            return;
        }
        for (AxNode n : interactiveElements(root)) {
            if (n.role == null) {
                continue;
            }
            try {
                AriaRole role = AriaRole.valueOf(n.role.toUpperCase(Locale.ROOT));
                Page.GetByRoleOptions opts = new Page.GetByRoleOptions();
                if (n.name != null && !n.name.isBlank()) {
                    opts.setName(n.name.trim());
                }
                Locator loc = page.getByRole(role, opts);
                if (loc.count() == 0) {
                    continue;   // 当前 name 反查无匹配：跳过该节点富化（用 count 立即判定，避免等待默认超时）
                }
                ElementHandle eh = loc.elementHandle();
                if (eh == null) {
                    continue;
                }
                n.i18nKey = asString(eh.evaluate("el => el.getAttribute('data-i18n')"));
                n.testId  = asString(eh.evaluate("el => el.getAttribute('data-testid')"));
                n.domId   = asString(eh.evaluate("el => el.id"));
                n.domTag  = asString(eh.evaluate("el => el.tagName"));
            } catch (RuntimeException e) {
                logger.debug("[A11y] enrich failed for role='{}', name='{}': {}", n.role, n.name, e.getMessage());
            }
        }
    }

    private static String asString(Object o) {
        return (o == null) ? null : o.toString();
    }

    /**
     * 生成可交互元素的可读表格（辅助测试：写 PageObject 前先「看一眼页面」）。
     * 列：# | role | i18nKey | name(当前语言) | 多语言 name（若提供 i18n 提供器）。
     *
     * @param root AX 树根节点
     * @return 多行文本表格
     */
    public static String describeInteractive(AxNode root) {
        return describeInteractive(root, null);
    }

    public static String describeInteractive(AxNode root, I18nNameProvider i18n) {
        StringBuilder sb = new StringBuilder();
        sb.append("Interactive elements (role / i18nKey / name):\n");
        List<AxNode> list = interactiveElements(root);
        int idx = 0;
        for (AxNode n : list) {
            String role = n.role == null ? "?" : n.role;
            String i18nKey = n.i18nKey == null ? "" : n.i18nKey;
            String name = n.name == null ? "" : n.name;
            String names = "";
            if (i18n != null && n.i18nKey != null && !n.i18nKey.isBlank()) {
                String[] arr = i18n.namesForKey(n.i18nKey);
                names = arr == null ? "" : String.join(" | ", arr);
            }
            sb.append(String.format("%3d | %-12s | %-20s | %-22s | %s%n",
                    ++idx, role, i18nKey, name, names));
        }
        if (idx == 0) {
            sb.append("  (none)\n");
        }
        return sb.toString();
    }

    /** 打印可交互元素表到标准输出（辅助测试）。 */
    public static void printInteractiveElements(AxNode root) {
        System.out.println(describeInteractive(root));
    }

    /** 打印可交互元素表（含多语言 name）到标准输出（辅助测试）。 */
    public static void printInteractiveElements(AxNode root, I18nNameProvider i18n) {
        System.out.println(describeInteractive(root, i18n));
    }

    // ==================== 序列化 ====================

    /**
     * 把中立模型树序列化为<b>美化 JSON</b>（便于留档 / 喂给 PageObject 生成器）。
     *
     * @param node 任意子树节点
     * @return JSON 字符串；node 为 null 时返回 {@code "null"}
     */
    public static String toJson(AxNode node) {
        return PRETTY_GSON.toJson(node);
    }

    /**
     * 获取页面的 <b>ARIA snapshot（YAML）</b>——Playwright 1.58 推荐的快照方式
     * （{@code page.accessibility().snapshot()} 已被移除，改用此 API）。
     * <p>基于 {@code page.locator("body").ariaSnapshot()}。
     *
     * @param page 目标页面
     * @return YAML 文本的 ARIA 树；page 为 null 抛异常
     */
    public static String ariaSnapshotYaml(Page page) {
        if (page == null) {
            throw new IllegalArgumentException("page must not be null");
        }
        return page.locator("body").ariaSnapshot();
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    // ==================== 中立数据模型 ====================

    /**
     * 与 Playwright 解耦的可访问性节点模型（纯 POJO，可 Gson 序列化）。
     * <p>仅保留最稳定通用的要素：{@code role} / {@code name} / {@code children}，
     * 以及 DOM 富化回填的语言无关锚点属性。
     */
    public static final class AxNode {
        /** ARIA role，如 {@code button}/{@code link}/{@code textbox}。 */
        public String role;
        /** 可访问名（当前语言），如 {@code "Sign in"} / {@code "登录"}。 */
        public String name;
        /** 子节点；叶子节点为 {@code null}。 */
        public List<AxNode> children;

        // -------- DOM 富化字段（语言无关锚点，需 enrich* 回填） --------
        /** i18n key（对应页面 {@code data-i18n} 属性），语言无关，优先用于定位。 */
        public String i18nKey;
        /** {@code data-testid} 属性值（语言无关，最稳定位锚点）。 */
        public String testId;
        /** DOM {@code id} 属性值（语言无关）。 */
        public String domId;
        /** DOM 标签名（大写），如 {@code "BUTTON"}。 */
        public String domTag;

        public String getRole() {
            return role;
        }

        public String getName() {
            return name;
        }

        public List<AxNode> getChildren() {
            return children;
        }

        public String getI18nKey() {
            return i18nKey;
        }

        public String getTestId() {
            return testId;
        }

        public String getDomId() {
            return domId;
        }

        public String getDomTag() {
            return domTag;
        }

        @Override
        public String toString() {
            int childCount = children == null ? 0 : children.size();
            return "AxNode{role='" + role + "', name='" + name
                    + "', i18nKey='" + i18nKey + "', testId='" + testId
                    + "', children=" + childCount + '}';
        }
    }
}
