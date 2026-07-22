package com.hsbc.cmb.hk.dbb.automation.framework.web.page.scan;

/**
 * 一次拾取/解析出的元素定位信息。
 *
 * <p>对齐 {@code page.pause()} 的代码生成：不再只支持「可访问性角色（role）+ 名称（name）」，
 * 而是按优先级链解析出最合适的定位策略（{@link #getStrategy()}）：
 * <ul>
 *   <li>{@code role}：角色 + 可访问名，生成 {@code @RoleElement}（保留 NLS 多语言能力）</li>
 *   <li>{@code testid} / {@code placeholder} / {@code text} / {@code altText} / {@code title}
 *       / {@code id} / {@code css}：生成 {@code @Element}（Playwright 字符串选择器，见 {@link #getSelector()}）</li>
 * </ul>
 *
 * <p>{@code tag} 与 {@code text} 为可选辅助信息，仅用于生成代码时的注释，便于人工核对。
 */
public final class RoleEntry {

    private final String role;
    private final String name;
    private final String tag;
    private final String text;
    /** 定位策略：role / testid / placeholder / text / altText / title / id / css。null 视为 role。 */
    private final String strategy;
    /** 非 role 策略下已构建好的 Playwright 字符串选择器（如 {@code #logoHeader}、{@code text="Business"}）。 */
    private final String selector;
    /**
     * 角色策略下，若拾取时成功用 {@code name} 反查到 nls 文件中的 key，则存此处；
     * 生成 {@code @RoleElement} 时直接复用该真实 key。未命中则为 null（回退到 name 派生 slug）。
     */
    private final String resolvedKey;

    public RoleEntry(String role, String name) {
        this(role, name, null, null);
    }

    public RoleEntry(String role, String name, String tag, String text) {
        this(role, name, tag, text, null, null, null);
    }

    public RoleEntry(String role, String name, String tag, String text,
                     String strategy, String selector) {
        this(role, name, tag, text, strategy, selector, null);
    }

    public RoleEntry(String role, String name, String tag, String text,
                     String strategy, String selector, String resolvedKey) {
        this.role = role;
        this.name = name;
        this.tag = tag;
        this.text = text;
        this.strategy = strategy;
        this.selector = selector;
        this.resolvedKey = resolvedKey;
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

    /** 是否走角色 + NLS 的 {@code @RoleElement}（strategy 为空或 "role"）。 */
    public boolean isRoleStrategy() {
        return strategy == null || strategy.isBlank() || "role".equals(strategy);
    }

    @Override
    public String toString() {
        return "RoleEntry{strategy=" + (strategy == null ? "role" : strategy)
                + ", role=" + role + ", name=" + name
                + (resolvedKey != null ? ", resolvedKey=" + resolvedKey : "")
                + (selector != null ? ", selector=" + selector : "")
                + (tag != null ? ", tag=" + tag : "")
                + (text != null ? ", text=" + text : "") + "}";
    }
}
