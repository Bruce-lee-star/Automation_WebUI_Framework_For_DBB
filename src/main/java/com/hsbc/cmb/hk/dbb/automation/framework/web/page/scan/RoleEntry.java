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
 *   <li>{@code text} / {@code altText} / {@code title} / {@code placeholder} / {@code testid} / {@code label} / {@code i18n}
 *       → 统一的语义定位注解 {@code @RoleElement}（以 {@code text=} / {@code label=} / ... 属性表达，
 *       对应 Playwright 的 getBy* 方法，比纯字符串选择器更健壮）；{@code id} / {@code css}
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

    public RoleEntry(String role, String name) {
        this(role, name, null, null);
    }

    public RoleEntry(String role, String name, String tag, String text) {
        this(role, name, tag, text, null, null, null, false);
    }

    public RoleEntry(String role, String name, String tag, String text,
                     String strategy, String selector) {
        this(role, name, tag, text, strategy, selector, null, false);
    }

    public RoleEntry(String role, String name, String tag, String text,
                     String strategy, String selector, String resolvedKey) {
        this(role, name, tag, text, strategy, selector, resolvedKey, false);
    }

    public RoleEntry(String role, String name, String tag, String text,
                     String strategy, String selector, String resolvedKey, boolean cleaned) {
        this.role = role;
        this.name = name;
        this.tag = tag;
        this.text = text;
        this.strategy = strategy;
        this.selector = selector;
        this.resolvedKey = resolvedKey;
        this.cleaned = cleaned;
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
                + (text != null ? ", text=" + text : "") + "}";
    }
}
