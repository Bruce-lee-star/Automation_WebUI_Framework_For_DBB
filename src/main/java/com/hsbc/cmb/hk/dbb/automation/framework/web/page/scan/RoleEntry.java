package com.hsbc.cmb.hk.dbb.automation.framework.web.page.scan;

/**
 * 一次拾取/解析出的元素定位信息。
 *
 * <p>对齐 {@code page.pause()} 的代码生成：不再只支持「可访问性角色（role）+ 名称（name）」，
 * 而是按 selectorGenerator 打分序（分低者优先）解析出最合适的定位策略（{@link #getStrategy()}）：
 * {@code testid(1) < placeholder(100) < label(120) < role+name(140) < altText(160)
 * < text(180) < title(200) < css #id(500) < css 兜底}。
 * <ul>
 *   <li>{@code role}：角色 + 可访问名，生成 {@code @RoleElement}（保留 NLS 多语言能力）</li>
 *   <li>{@code text} / {@code altText} / {@code title} / {@code placeholder} / {@code testid} / {@code label}
 *       → 统一的语义定位注解 {@code @RoleElement}（以 {@code text=} / {@code label=} / ... 属性表达，
 *       对应 Playwright 的 getBy* 方法，比纯字符串选择器更健壮）；多语言 {@code data-i18n} 属性直接走
 *       {@code @Element("[data-i18n=\"key\"]")}（CSS 属性选择器，无需专门字段）；{@code id} / {@code css}
 *       → {@code @Element}（纯 CSS/XPath，见 {@link #getSelector()}）</li>
 * </ul>
 *
 * <p>{@code tag} 与 {@code text} 为可选辅助信息，仅用于生成代码时的注释，便于人工核对。
 */
public final class RoleEntry {

    private final String role;
    private final String name;
    private final String tag;
    private final String text;
    /** 定位策略：role / testid / placeholder / label / text / altText / title / id / css。null 视为 role。 */
    private final String strategy;
    /** 非 role 策略下已构建好的 Playwright 字符串选择器（如 {@code #logoHeader}、{@code text="Business"}）。 */
    private final String selector;
    /**
     * 角色策略下，若拾取时成功用 {@code name} 反查到 nls 文件中的 key，则存此处；
     * 生成 {@code @RoleElement} 时直接复用该真实 key。未命中则为 null（回退到 name 派生 slug）。
     */
    private final String resolvedKey;
    /**
     * 角色策略下 name 是否剔除了装饰性伪元素/描述文本（如 “ (opens in a new window)”）。
     * 为 true 时生成 {@code @RoleElement} 应加 {@code exact = false}（子串匹配），
     * 否则精确匹配会因 name 比 Playwright 实际 accessible name 短而定位失败。
     */
    private final boolean cleaned;
    /**
     * 在输入框中实际键入的文本（点输入框后输入时捕获）。仅 textbox/searchbox/spinbutton/
     * placeholder 等可输入策略有意义；其余为 null，生成 step 时 {@code type("")} 留空由人工补全。
     */
    private final String value;
    /**
     * 该次点击是否弹出了新页面（target=_blank 链接等）。为 true 时生成 step 应包装为
     * {@code page.waitForPopup(() -> element.click())}，对齐 {@code page.pause()} 的 codegen 输出。
     */
    private final boolean popup;
    /**
     * 该定位器在页面上匹配「一组元素」时的序号（0-based），对齐 Playwright 的
     * {@code locator.nth(index)}。为 {@code -1} 表示唯一匹配、无需索引。
     */
    private final int index;
    /**
     * 该次点击是否触发了下载（anchor 带 download 属性 / 指向文件 URL，或 JS 触发的下载）。
     * 为 true 时生成 step 应包装为 {@code page.waitForDownload(() -> element.click())}，
     * 对齐 {@code page.pause()} 的 codegen 输出；与 {@link #popup} 同时为真时嵌套：
     * {@code waitForDownload(waitForPopup)}。
     */
    private final boolean download;
    /**
     * 该拾取是否为「悬停（hover）」交互（区别于默认的点击 click）。
     * 为 true 时生成 step 应输出 {@code locator.hover()} 而非 {@code .click()}，
     * 对齐 {@code page.pause()} 对 hover 动作的录制；与 click 共用同一去重签名（同元素 hover 后 click 以最近一次交互为准）。
     */
    private final boolean hover;
    /**
     * 该拾取元素所属页面的 Page 类名（由对应页面的 {@code window.__rolePageName} 决定）。
     * 多页面跟随场景下，弹窗/新标签页拾取的元素需落到各自对应的 Page 类，
     * 因此每条 pick 都记录其归属页面类，代码生成时据此分组。
     */
    private final String pageClass;
    /**
     * 该拾取是否为「关闭页面」操作标记（由 onClose 在弹窗关闭时写入当前 step）。
     * 为 true 时生成 step 应输出 {@code page.closeCurrentPage()}，而非元素操作；
     * 与元素 pick 一同落在同一步（start→stop 唯一边界），故关闭页面不会额外拆出 step。
     */
    private final boolean closeOp;
    /**
     * 标题层级（仅对 heading 角色有意义，取值 1–6），对齐 Playwright {@code getByRole(HEADING).setLevel(n)}。
     * 0 表示不限定层级。拾取器从 {@code <h1>–<h6>} 标签或 {@code aria-level} 推导。
     */
    private final int level;
    /**
     * 该拾取是否为「双击（double click）」交互（区别于默认的单击 click）。
     * 为 true 时生成 step 应输出 {@code locator.doubleClick()} 而非 {@code .click()}，
     * 对齐 {@code page.pause()} 对 dblclick 动作的录制；与 click 共用同一去重签名（同元素以最近一次交互为准）。
     */
    private final boolean dblClick;

    public RoleEntry(String role, String name) {
        this(role, name, null, null);
    }

    public RoleEntry(String role, String name, String tag, String text) {
        this(role, name, tag, text, null, null, null, false, null, false, -1, false);
    }

    /** 整页采集便捷构造：角色 + 名称 + tag + 层级 + nls key（strategy 置为角色策略）。 */
    public RoleEntry(String role, String name, String tag, int level, String resolvedKey) {
        this(role, name, tag, null, null, null, resolvedKey, false, null, false, -1, false, null, false, false, level);
    }

    public RoleEntry(String role, String name, String tag, String text,
                     String strategy, String selector) {
        this(role, name, tag, text, strategy, selector, null, false, null, false, -1, false);
    }

    public RoleEntry(String role, String name, String tag, String text,
                     String strategy, String selector, String resolvedKey) {
        this(role, name, tag, text, strategy, selector, resolvedKey, false, null, false, -1, false);
    }

    public RoleEntry(String role, String name, String tag, String text,
                     String strategy, String selector, String resolvedKey, boolean cleaned) {
        this(role, name, tag, text, strategy, selector, resolvedKey, cleaned, null, false, -1, false);
    }

    public RoleEntry(String role, String name, String tag, String text,
                     String strategy, String selector, String resolvedKey, boolean cleaned, String value) {
        this(role, name, tag, text, strategy, selector, resolvedKey, cleaned, value, false, -1, false);
    }

    public RoleEntry(String role, String name, String tag, String text,
                     String strategy, String selector, String resolvedKey, boolean cleaned,
                     String value, boolean popup) {
        this(role, name, tag, text, strategy, selector, resolvedKey, cleaned, value, popup, -1, false);
    }

    public RoleEntry(String role, String name, String tag, String text,
                     String strategy, String selector, String resolvedKey, boolean cleaned,
                     String value, boolean popup, int index) {
        this(role, name, tag, text, strategy, selector, resolvedKey, cleaned, value, popup, index, false);
    }

    public RoleEntry(String role, String name, String tag, String text,
                     String strategy, String selector, String resolvedKey, boolean cleaned,
                     String value, boolean popup, int index, boolean download) {
        this(role, name, tag, text, strategy, selector, resolvedKey, cleaned, value, popup, index, download, null, false);
    }

    public RoleEntry(String role, String name, String tag, String text,
                     String strategy, String selector, String resolvedKey, boolean cleaned,
                     String value, boolean popup, int index, boolean download, String pageClass) {
        this(role, name, tag, text, strategy, selector, resolvedKey, cleaned, value, popup, index, download, pageClass, false, false);
    }

    public RoleEntry(String role, String name, String tag, String text,
                     String strategy, String selector, String resolvedKey, boolean cleaned,
                     String value, boolean popup, int index, boolean download, String pageClass, boolean hover) {
        this(role, name, tag, text, strategy, selector, resolvedKey, cleaned, value, popup, index, download, pageClass, hover, false);
    }

    public RoleEntry(String role, String name, String tag, String text,
                     String strategy, String selector, String resolvedKey, boolean cleaned,
                     String value, boolean popup, int index, boolean download, String pageClass, boolean hover, boolean closeOp) {
        this(role, name, tag, text, strategy, selector, resolvedKey, cleaned, value, popup, index, download, pageClass, hover, closeOp, 0);
    }

    public RoleEntry(String role, String name, String tag, String text,
                     String strategy, String selector, String resolvedKey, boolean cleaned,
                     String value, boolean popup, int index, boolean download, String pageClass, boolean hover, boolean closeOp, int level) {
        this(role, name, tag, text, strategy, selector, resolvedKey, cleaned, value, popup, index, download, pageClass, hover, closeOp, level, false);
    }

    public RoleEntry(String role, String name, String tag, String text,
                     String strategy, String selector, String resolvedKey, boolean cleaned,
                     String value, boolean popup, int index, boolean download, String pageClass, boolean hover, boolean closeOp, int level, boolean dblClick) {
        this.role = role;
        this.name = name;
        this.tag = tag;
        this.text = text;
        this.strategy = strategy;
        this.selector = selector;
        this.resolvedKey = resolvedKey;
        this.cleaned = cleaned;
        this.value = value;
        this.popup = popup;
        this.index = index;
        this.download = download;
        this.hover = hover;
        this.pageClass = pageClass;
        this.closeOp = closeOp;
        this.level = level;
        this.dblClick = dblClick;
    }

    public String getRole() {
        return role;
    }

    public String getName() {
        return name;
    }

    public String getTag() {
        return tag;
    }

    public String getText() {
        return text;
    }

    /** 定位策略；null 或 "role" 表示走角色 + NLS 的 {@code @RoleElement}。 */
    public String getStrategy() {
        return strategy;
    }

    /** 非 role 策略下的 Playwright 字符串选择器（供 {@code @Element} 使用）。 */
    public String getSelector() {
        return selector;
    }

    /** 角色策略下反查到的 nls key；为 null 表示未命中（生成时应回退到 name 派生 slug）。 */
    public String getResolvedKey() {
        return resolvedKey;
    }

    /** 角色策略下 name 是否剔除了装饰性伪元素/描述文本；是则生成 {@code @RoleElement} 时应加 exact=false。 */
    public boolean isCleaned() {
        return cleaned;
    }

    /** 在输入框中实际键入的文本（点输入框后输入时捕获）；非输入策略为 null。 */
    public String getValue() {
        return value;
    }

    /** 该次点击是否弹出新页面；是则生成 step 时包装为 {@code page.waitForPopup(...)}。 */
    public boolean isPopup() {
        return popup;
    }

    /** 一组同定位器元素中的序号（0-based）；-1 表示唯一匹配、无需 {@code nth(index)}。 */
    public int getIndex() {
        return index;
    }

    /** 该次点击是否触发下载；是则生成 step 时包装为 {@code waitForDownload(...)}（可与弹窗嵌套）。 */
    public boolean isDownload() {
        return download;
    }

    /** 该拾取是否为「悬停（hover）」交互；是则生成 step 应输出 {@code locator.hover()}。 */
    public boolean isHover() {
        return hover;
    }

    /** 该拾取是否为「双击（double click）」交互；是则生成 step 应输出 {@code locator.doubleClick()}。 */
    public boolean isDblClick() {
        return dblClick;
    }

    /** 该拾取元素所属页面的 Page 类名（多页面跟随时用于把元素归类到对应 Page 类）。 */
    public String getPageClass() {
        return pageClass;
    }

    /** 该拾取是否为「关闭页面」操作标记；是则生成 step 应输出 {@code page.closeCurrentPage()}。 */
    public boolean isCloseOp() {
        return closeOp;
    }

    /** 标题层级（heading 角色专用，1–6；0 表示不限层级）。 */
    public int getLevel() {
        return level;
    }

    /** 是否走角色 + NLS 的 {@code @RoleElement}（strategy 为空或 "role"）。 */
    public boolean isRoleStrategy() {
        return strategy == null || strategy.isBlank() || "role".equals(strategy);
    }

    @Override
    public String toString() {
        return "RoleEntry{strategy=" + (strategy == null ? "role" : strategy)
                + ", role=" + role + ", name=" + name
                + (resolvedKey != null ? ", resolvedKey=" + resolvedKey : "")
                + (cleaned ? ", cleaned=true" : "")
                + (selector != null ? ", selector=" + selector : "")
                + (tag != null ? ", tag=" + tag : "")
                + (text != null ? ", text=" + text : "")
                + (popup ? ", popup=true" : "")
                + (download ? ", download=true" : "")
                + (hover ? ", hover=true" : "")
                + (dblClick ? ", dblClick=true" : "")
                + (level > 0 ? ", level=" + level : "") + "}";
    }
}
