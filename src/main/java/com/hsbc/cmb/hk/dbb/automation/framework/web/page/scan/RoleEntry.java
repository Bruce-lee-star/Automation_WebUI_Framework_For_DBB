package com.hsbc.cmb.hk.dbb.automation.framework.web.page.scan;

import com.hsbc.cmb.hk.dbb.automation.framework.web.page.RoleElement;

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

    /**
     * 该次交互是否触发了浏览器原生对话框（alert / confirm / prompt）。
     * 注意与 ARIA role="dialog"（模态 DOM 元素）区分——此处专指原生弹窗。
     * 对齐 page.pause() 的 dialog 信号（前置插桩，不包裹动作）。
     */
    private final boolean dialog;
    /** 原生对话框类型：{@code alert} / {@code confirm} / {@code prompt}（dialog=true 时有效）。 */
    private final String dialogType;
    /** 对话框处理动作：{@code accept}（默认 alert）/ {@code dismiss}（默认 confirm/prompt）。 */
    private final String dialogAction;

    /**
     * 该拾取元素是否为「下拉选择」交互（role=combobox/listbox，含原生 {@code <select>} 与自定义列表）。
     * 为 true 时生成 step 应输出 {@code selectByVisibleText("选项文本")} / {@code selectByValue(...)}，
     * 对齐 page.pause() 对 selectOption 信号的录制（目前框架 draft 降级为 click，此处补齐具体选择动作）。
     */
    private final boolean select;
    /**
     * 下拉选择时选中的「可见文本」（{@code <option>} 文案或 {@code aria-selected} 选项的可访问名），
     * 对应 {@code selectByVisibleText(...)}。仅 {@link #select} 为 true 时有意义。
     */
    private final String optionText;
    /**
     * 下拉选择时选中的「选项值」（{@code <option>.value}），对应 {@code selectByValue(...)}。
     * 仅 {@link #select} 为 true 时有意义；优先用 {@link #optionText}（更语义、更健壮）。
     */
    private final String optionValue;
    /**
     * 复选框（role=checkbox）拾取时的实际勾选状态：
     * {@code true}=当前已勾选（生成 {@code check()}）；{@code false}=当前未勾选（生成 {@code uncheck()}）；
     * {@code null}=非复选框/未捕获（走默认 check()）。对齐 page.pause() 对 check/uncheck 信号的分别录制。
     */
    private final Boolean checked;

    /**
     * 元素的可访问「禁用」状态（对齐 page.pause() 的 getByRole 过滤 {@code setDisabled}）：
     * {@code YES}=当前禁用；{@code NO}=当前可用；{@code null}=非禁用/未捕获。
     * 来源：原生 {@code disabled} 属性或 {@code aria-disabled="true"}。
     */
    private final RoleElement.State disabled;

    /**
     * 切换按钮（role=button 带 aria-pressed）的「按下」状态（对齐 getByRole {@code setPressed}）：
     * {@code YES}=已按下；{@code NO}=未按下；{@code null}=非 toggle/未捕获。
     * 来源：{@code aria-pressed="true"/"false"}。
     */
    private final RoleElement.State pressed;

    /**
     * 可展开元素（带 aria-expanded）的「展开」状态（对齐 getByRole {@code setExpanded}）：
     * {@code YES}=已展开；{@code NO}=已收起；{@code null}=无可展开性/未捕获。
     * 来源：{@code aria-expanded="true"/"false"}。
     */
    private final RoleElement.State expanded;

    /**
     * 浏览器端固化的「合并去重键」（{@code pick._sigKey}）——元素的永久身份，透传字段。
     *
     * <p>根因修复：该键在浏览器端入库瞬间由 {@code __sigKey()} 生成并固化到 pick 上，经
     * {@code __roleOnPick} 回传 Java。此前 Java 侧只把它当作内存态 Map 的 key 使用，
     * <b>并未存进实体</b>；于是 {@code syncPanelToBrowser} 把内存态回灌浏览器时，
     * 产出的 pick 不带 {@code _sigKey}，浏览器只能在<b>当前页上下文</b>重算键——
     * 区域扫描元素的 {@code pageClass} 常为空，重算会退化到 {@code location.origin+pathname} 兜底，
     * 每导航到一个新页面就算出一个不同的键，{@code __rolePickSigs} 判为新元素再 push 一份，
     * 表现为「区域扫描 → 停止 → 手动跳转其它页面，已拾元素成倍增加」（导航 N 次得 N 份）。
     *
     * <p>故此处让 Java 实体<b>原样持有</b>该键并在回灌时带回浏览器，使元素身份在
     * 浏览器 → Java → 浏览器 的整个往返链路中恒定不变，从源头根除重复。
     *
     * <p>设计为可变字段而非构造器参数：纯透传的身份标识，不参与任何代码生成语义，
     * 且 {@code RoleEntry} 已有 10+ 个重载构造器，加参数会波及全部调用点。
     */
    private String sigKey;

    /**
     * 该定位器在页面上匹配到的「元素总数」（一组同定位器元素的数量）。
     *
     * <p>用于 step 代码生成时判断是否需要在目标 locator 后追加 {@code .nth(index)} 消歧：
     * 仅当 {@code count > 1}（即页面上确实有多个同定位器元素）时才加 nth，
     * 唯一匹配（count==1）即使 index 被固化为 0 也不应生成 {@code .nth(0)}（冗余且误导）。
     *
     * <p>透传字段（不参与代码生成语义、不参与去重签名），浏览器侧 {@code __attachIndex}
     * 写入、这里原样持有。设计为可变字段而非构造器参数：与 {@link #sigKey} 同理，
     * 纯透传信息，且 {@code RoleEntry} 已有 10+ 个重载构造器，加参数会波及全部调用点。
     */
    private int count = 1;

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
        this(role, name, tag, text, strategy, selector, resolvedKey, cleaned, value, popup, index, download, pageClass, hover, closeOp, level, false, false, null, null, false, null, null, null, null, null, null);
    }

    public RoleEntry(String role, String name, String tag, String text,
                     String strategy, String selector, String resolvedKey, boolean cleaned,
                     String value, boolean popup, int index, boolean download, String pageClass, boolean hover, boolean closeOp, int level, boolean dblClick) {
        this(role, name, tag, text, strategy, selector, resolvedKey, cleaned, value, popup, index, download, pageClass, hover, closeOp, level, dblClick, false, null, null, false, null, null, null, null, null, null);
    }

    /**
     * 完整构造（含 dialog 相关字段）。
     *
     * @param dialog       是否触发浏览器原生对话框
     * @param dialogType   对话框类型 alert/confirm/prompt
     * @param dialogAction 处理动作 accept/dismiss
     */
    public RoleEntry(String role, String name, String tag, String text,
                     String strategy, String selector, String resolvedKey, boolean cleaned,
                     String value, boolean popup, int index, boolean download, String pageClass, boolean hover, boolean closeOp, int level, boolean dblClick,
                     boolean dialog, String dialogType, String dialogAction) {
        this(role, name, tag, text, strategy, selector, resolvedKey, cleaned, value, popup, index, download, pageClass, hover, closeOp, level, dblClick,
                dialog, dialogType, dialogAction, false, null, null, null, null, null, null);
    }

    /**
     * 完整构造（含 dialog + select/check 字段）。
     *
     * @param select     是否为下拉选择交互（combobox/listbox）
     * @param optionText 选中项可见文本（对应 selectByVisibleText）
     * @param optionValue 选中项值（对应 selectByValue）
     * @param checked    复选框勾选状态（true=check / false=uncheck / null=默认）
     * @param disabled   可访问「禁用」状态（对齐 getByRole setDisabled）：YES=禁用 / NO=可用 / null=不限
     * @param pressed    切换按钮「按下」状态（对齐 getByRole setPressed）：YES=按下 / NO=未按下 / null=不限
     * @param expanded   可展开元素「展开」状态（对齐 getByRole setExpanded）：YES=展开 / NO=收起 / null=不限
     */
    public RoleEntry(String role, String name, String tag, String text,
                     String strategy, String selector, String resolvedKey, boolean cleaned,
                     String value, boolean popup, int index, boolean download, String pageClass, boolean hover, boolean closeOp, int level, boolean dblClick,
                     boolean dialog, String dialogType, String dialogAction,
                     boolean select, String optionText, String optionValue, Boolean checked,
                     RoleElement.State disabled, RoleElement.State pressed, RoleElement.State expanded) {
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
        this.dialog = dialog;
        this.dialogType = dialogType;
        this.dialogAction = dialogAction;
        this.select = select;
        this.optionText = optionText;
        this.optionValue = optionValue;
        this.checked = checked;
        this.disabled = disabled;
        this.pressed = pressed;
        this.expanded = expanded;
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

    /** 该次交互是否触发了浏览器原生对话框（alert/confirm/prompt）。 */
    public boolean isDialog() {
        return dialog;
    }

    /** 原生对话框类型：alert / confirm / prompt。 */
    public String getDialogType() {
        return dialogType;
    }

    /** 对话框处理动作：accept / dismiss。 */
    public String getDialogAction() {
        return dialogAction;
    }

    /** 该拾取是否为「下拉选择」交互（combobox/listbox）；是则生成 step 应输出 selectByVisibleText(...) 等。 */
    public boolean isSelect() {
        return select;
    }

    /** 下拉选择时选中的可见文本（对应 selectByVisibleText）；非下拉选择为 null。 */
    public String getOptionText() {
        return optionText;
    }

    /** 下拉选择时选中的选项值（对应 selectByValue）；非下拉选择为 null。 */
    public String getOptionValue() {
        return optionValue;
    }

    /**
     * 复选框勾选状态：{@code true}=已勾选→{@code check()}；{@code false}=未勾选→{@code uncheck()}；
     * {@code null}=非复选框/未捕获（走默认 {@code check()}）。
     */
    public Boolean getChecked() {
        return checked;
    }

    /**
     * 可访问「禁用」状态：{@code YES}=当前禁用；{@code NO}=当前可用；
     * {@code null}=非禁用/未捕获（生成 {@code @RoleElement} 时不带 disabled 属性）。
     * 对齐 page.pause() 的 getByRole {@code setDisabled} 过滤。
     */
    public RoleElement.State getDisabled() {
        return disabled;
    }

    /**
     * 切换按钮「按下」状态：{@code YES}=已按下；{@code NO}=未按下；
     * {@code null}=非 toggle/未捕获。对齐 getByRole {@code setPressed} 过滤。
     */
    public RoleElement.State getPressed() {
        return pressed;
    }

    /**
     * 可展开元素「展开」状态：{@code YES}=已展开；{@code NO}=已收起；
     * {@code null}=无可展开性/未捕获。对齐 getByRole {@code setExpanded} 过滤。
     */
    public RoleElement.State getExpanded() {
        return expanded;
    }

    /** 该拾取元素所属页面的 Page 类名（多页面跟随时用于把元素归类到对应 Page 类）。 */
    public String getPageClass() {
        return pageClass;
    }

    /** 该拾取是否为「关闭页面」操作标记；是则生成 step 应输出 {@code page.closeCurrentPage()}。 */
    public boolean isCloseOp() {
        return closeOp;
    }

    /**
     * 浏览器端固化的合并去重键（{@code pick._sigKey}）；可能为 null（旧数据/未固化）。
     * 仅用于跨「浏览器 ↔ Java」往返时保持元素身份恒定，不参与代码生成。
     */
    public String getSigKey() {
        return sigKey;
    }

    /** 设置浏览器端固化的合并去重键（回传写入内存态时调用）。 */
    public void setSigKey(String sigKey) {
        this.sigKey = sigKey;
    }

    /** 该定位器在页面上匹配到的元素总数（同定位器元素个数）；1 表示唯一匹配。 */
    public int getCount() {
        return count;
    }

    /** 设置定位器匹配总数（回传写入内存态时调用）。 */
    public void setCount(int count) {
        this.count = count;
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
            + (level > 0 ? ", level=" + level : "")
            + (select ? ", select=" + optionText + (optionValue != null ? "(" + optionValue + ")" : "") : "")
            + (checked != null ? ", checked=" + checked : "")
            + (disabled != null ? ", disabled=" + disabled : "")
            + (pressed != null ? ", pressed=" + pressed : "")
            + (expanded != null ? ", expanded=" + expanded : "")
            + (dialog ? ", dialog=" + dialogType + "(" + dialogAction + ")" : "") + "}";
    }
}
