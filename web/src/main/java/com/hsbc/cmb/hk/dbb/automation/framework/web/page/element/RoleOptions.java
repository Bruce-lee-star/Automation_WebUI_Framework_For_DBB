package com.hsbc.cmb.hk.dbb.automation.framework.web.page.element;

/**
 * 角色定位选项（语义化、可读）：用具名方法替代同类型连续位置参数，使调用点自解释。
 *
 * <p><b>为什么需要它：</b>可访问状态过滤若用三个同类型位置参数表达，调用点会退化为
 * {@code elementByRole(role, "Submit", true, 0, NO, ANY, YES)}——既看不出 {@code NO/ANY/YES}
 * 各自修饰哪个状态，也防不住同类型参数错位。改用本类后：
 *
 * <pre>{@code
 * // 只要「可用」的提交按钮（aria-disabled = false）
 * page.elementByRole(AriaRole.BUTTON, "Submit", RoleOptions.defaults().enabledOnly());
 *
 * // 二级标题 + 模糊匹配 + 已展开（aria-expanded = true）
 * page.elementByRole(AriaRole.HEADING, "Business", RoleOptions.defaults().partial().level(2).expanded());
 *
 * // 多语言 key：先按 nls 解析名称，再限定「未按下」的切换按钮（aria-pressed = false）
 * page.elementByRoleKey(AriaRole.BUTTON, "favorite", RoleOptions.defaults().notPressed());
 * }</pre>
 *
 * <p><b>语义映射（方法名对齐 ARIA 属性；未设置的状态一律「不限定」= 不下发 Playwright 选项）：</b>
 * <ul>
 *   <li>{@link #enabledOnly()} / {@link #disabledOnly()} → {@code aria-disabled} / 原生 {@code disabled}
 *       （{@code setDisabled(false/true)}）；</li>
 *   <li>{@link #pressed()} / {@link #notPressed()} → {@code aria-pressed}（切换按钮，{@code setPressed(true/false)}）；</li>
 *   <li>{@link #expanded()} / {@link #collapsed()} → {@code aria-expanded}（菜单/树/手风琴，{@code setExpanded(true/false)}）；</li>
 *   <li>{@link #level(int)} → 标题层级（仅 {@link com.microsoft.playwright.options.AriaRole#HEADING} 有意义）；</li>
 *   <li>{@link #exact()} / {@link #partial()} → 整串 / 子串匹配（字面名对应 {@code setExact}；
 *       正则模式下由框架锚定整串，见 {@code RoleLocatorFactory#resolvePattern}）。</li>
 * </ul>
 *
 * <p><b>可变对象：</b>按调用点即时构造并立即使用（与 Playwright 的 options 类同样式），不做跨线程共享。
 *
 * @see RoleElement.State
 */
public final class RoleOptions {

    private int level;
    private boolean exact = true;
    private RoleElement.State disabled = RoleElement.State.ANY;
    private RoleElement.State pressed = RoleElement.State.ANY;
    private RoleElement.State expanded = RoleElement.State.ANY;

    private RoleOptions() {
    }

    /** 全部取默认：不限定状态、不限定层级、精确匹配。 */
    public static RoleOptions defaults() {
        return new RoleOptions();
    }

    // ==================== 匹配方式 ====================

    /** 精确匹配（默认）：字面名整串相等；正则整串锚定。 */
    public RoleOptions exact() {
        this.exact = true;
        return this;
    }

    /** 模糊匹配：字面名子串相等；正则保持 Playwright 原生子串匹配。 */
    public RoleOptions partial() {
        this.exact = false;
        return this;
    }

    /** 标题层级 1–6（仅 {@link com.microsoft.playwright.options.AriaRole#HEADING} 有意义）；{@code <=0} 表示不限定。 */
    public RoleOptions level(int level) {
        this.level = level;
        return this;
    }

    // ==================== 可访问状态（三态，默认不限定） ====================

    /** 只要<b>可用</b>的元素（{@code aria-disabled = false}）。 */
    public RoleOptions enabledOnly() {
        this.disabled = RoleElement.State.NO;
        return this;
    }

    /** 只要<b>禁用</b>的元素（{@code aria-disabled = true}）。 */
    public RoleOptions disabledOnly() {
        this.disabled = RoleElement.State.YES;
        return this;
    }

    /** 只要<b>已按下</b>的切换按钮（{@code aria-pressed = true}）。 */
    public RoleOptions pressed() {
        this.pressed = RoleElement.State.YES;
        return this;
    }

    /** 只要<b>未按下</b>的切换按钮（{@code aria-pressed = false}）。 */
    public RoleOptions notPressed() {
        this.pressed = RoleElement.State.NO;
        return this;
    }

    /** 只要<b>已展开</b>的元素（{@code aria-expanded = true}）。 */
    public RoleOptions expanded() {
        this.expanded = RoleElement.State.YES;
        return this;
    }

    /** 只要<b>已收起</b>的元素（{@code aria-expanded = false}）。 */
    public RoleOptions collapsed() {
        this.expanded = RoleElement.State.NO;
        return this;
    }

    // ==================== 读取（framework-internal：供定位原语下发 Playwright 选项） ====================

    public int level() {
        return level;
    }

    public boolean isExact() {
        return exact;
    }

    public RoleElement.State disabledState() {
        return disabled;
    }

    public RoleElement.State pressedState() {
        return pressed;
    }

    public RoleElement.State expandedState() {
        return expanded;
    }
}
