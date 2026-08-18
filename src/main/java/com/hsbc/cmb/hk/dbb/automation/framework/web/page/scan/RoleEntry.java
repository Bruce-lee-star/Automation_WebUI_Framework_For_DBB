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
    private boolean popup;
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
    private boolean hover;
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
     * 该拾取元素所属页面的「实例序号」：同一 {@code pageClass} 被打开多次时用于区分不同实例
     * （例如同一 URL 在标签 A、标签 B 各打开一次，分别为实例 1、实例 2）。
     * 默认 1 表示首个实例；生成 step 时变量名按 {@code pageClass#instanceId} 维度分配
     * （如 loginPage、loginPage2），使同页多实例在代码层面可区分、可独立切换/关闭。
     */
    private int pageInstanceId = 1;
    /**
     * 该拾取发生时所在页面的 URL（元素级，由浏览器侧 __recordPick 写入 location.href）。
     * 用于生成 step 时按"页面 URL 在后续 step 中不再出现"反推该实例页已关闭，从而在精确位置补 closeCurrentPage()，
     * 而不依赖后续是否回到打开页的元素（修复"关闭新页面顺序错位"残留）。
     */
    private String url;
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
    private boolean dialog;
    /** 原生对话框类型：{@code alert} / {@code confirm} / {@code prompt}（dialog=true 时有效）。 */
    private String dialogType;
    /** 对话框处理动作：{@code accept}（默认 alert）/ {@code dismiss}（默认 confirm/prompt）。 */
    private String dialogAction;

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
     * 复选框目标勾选状态（对齐 page.pause() 的 setChecked 语义）：拾取时记录元素「应达到」的勾选状态，
     * {@code true}=勾选（生成 {@code setChecked(true)}）；{@code false}=取消（生成 {@code setChecked(false)}）；
     * 与 {@link #checked}（当前状态）不同：{@code setChecked} 在目标已满足时幂等跳过，避免误 toggle。
     * 仅 role=checkbox 且录制到明确勾选动作时有意义；{@code null}=非复选框/未捕获。
     */
    private final Boolean setCheckedTarget;

    /**
     * 键盘序列（如 {@code "Enter"} / {@code "Tab"}），对齐 page.pause() 的 {@code press("Enter")} 录制。
     * 用户在输入框聚焦态下按的实质按键（非字符输入），生成 step 应输出 {@code locator.press("Enter")}；
     * 与 {@link #value}（字符输入）互补：value 走 fill/type，pressKey 走 press。{@code null}=无键盘序列。
     */
    private final String pressKey;

    /**
     * 拖拽目标元素的定位签名（{@code locatorKey}），对齐 page.pause() 的 {@code source.dragTo(target)} 录制。
     * 仅在「拖拽源」pick 上非 null；生成端据其在同页 {@code keyToField} 反查目标字段名，生成
     * {@code srcField.dragTo(dstField)}。{@code null}=非拖拽源。
     */
    private final String dragDstKey;

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

    /**
     * 该元素所在的 iframe 嵌套路径（自顶向下，0 层表示主框架本身）。
     *
     * <p>对齐 page.pause() 的 {@code frameLocator} 录制：元素落在 iframe 内时，
     * 生成的定位器须以 {@code page.frameLocator(...).locator(...)} 包裹，否则在主框架上执行
     * 会找不到该元素（运行时必失败）。路径由浏览器端按帧链推导：
     * 每帧优先用其 {@code name} / {@code id} / 稳定的 css 选择器，顶层主框架记为空串。
     *
     * <p>透传字段（不参与代码生成语义、不参与去重签名），浏览器侧 {@code __computePick}
     * 写入、这里原样持有。设计为可变字段而非构造器参数：与 {@link #sigKey} 同理，
     * 纯透传信息，且 {@code RoleEntry} 已有 10+ 个重载构造器，加参数会波及全部调用点。
     * 仅顶层主框架内的元素此项为空（null 或空数组），生成时跳过 frameLocator 包裹。
     */
    private java.util.List<String> framePath;

    /**
     * 全局单调时间戳（源自浏览器侧 {@code pick.__ts = Date.now()}），用于跨 frame 按真实点击时序排序，
     * 还原用户 frame1→frame2→frame1→frame2 的穿插点击顺序。
     * 默认 0（未设置时排在最前以保证兼容旧数据）。不参与去重签名、不参与代码生成。
     */
    private long order;

    public long getOrder() { return order; }
    public void setOrder(long order) { this.order = order; }

    /**
     * 全局拾取顺序号数组：手动模式下每次拾取动作分配一个递增序号并追加进本数组（去重保序）。
     * 同一元素被重复点取时保留多个编号（如 [1,4,7]）——首号为首次拾取位次、其余为后续重拾位次。
     * 对应浏览器侧 {@code pick._pickNos}。纯透传信息，不参与去重签名、不参与代码生成（仅面板前缀展示用）。
     * Java 权威内存态需将其持久，否则 syncPanelToBrowser 重建浏览器 pick 时会丢，导致跨页导航 index 重置。
     */
    private java.util.List<Integer> pickNos;

    public java.util.List<Integer> getPickNos() { return pickNos; }
    /** 设置 pickNos，并防御性做【去重 + 升序排序】。
     *  本 setter 是 Java 权威内存态所有 pickNos 写入的统一入口（parsePick/mergePick/repickNos/clearStop 等），
     *  即使上游传入时顺序混乱或含重复，进入权威态的 pickNos 始终是"去重、升序"的规范数组，
     *  从根消除 [12,13,14,15,3] 这类乱序输出。
     *  【关键修复】空列表统一保留为"空数组 []"而非 null：消除 Java 端 null 与 [] 的语义双轨制
     *  （START 重扫描原 setPickNos(null)、面板删除原 nos=[]，导致两端状态机不一致、排查噪声大）。
     *  仅当参数本身为 null 时仍置 null（保留"未初始化"语义，普通清空请用 new ArrayList<>()）。 */
    public void setPickNos(java.util.List<Integer> pickNos) {
        if (pickNos == null) { this.pickNos = null; return; }
        java.util.LinkedHashSet<Integer> uniq = new java.util.LinkedHashSet<>(pickNos);
        java.util.List<Integer> sorted = new java.util.ArrayList<>(uniq);
        java.util.Collections.sort(sorted);
        this.pickNos = sorted;  // 空列表 → 空 ArrayList（GSON 序列化为 []，而非 null）
    }

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
        this(role, name, tag, text, strategy, selector, resolvedKey, cleaned, value, popup, index, download, pageClass, hover, closeOp, level, false, false, null, null, false, null, null, null, null, null, null, null, null, null);
    }

    public RoleEntry(String role, String name, String tag, String text,
                     String strategy, String selector, String resolvedKey, boolean cleaned,
                     String value, boolean popup, int index, boolean download, String pageClass, boolean hover, boolean closeOp, int level, boolean dblClick) {
        this(role, name, tag, text, strategy, selector, resolvedKey, cleaned, value, popup, index, download, pageClass, hover, closeOp, level, dblClick, false, null, null, false, null, null, null, null, null, null, null, null, null);
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
                dialog, dialogType, dialogAction, false, null, null, null, null, null, null, null, null, null);
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
                     Boolean setCheckedTarget, String pressKey, String dragDstKey,
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
        this.setCheckedTarget = setCheckedTarget;
        this.pressKey = pressKey;
        this.dragDstKey = dragDstKey;
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
    /** 动态标记是否弹出新页面（供拾取器 Java 侧 page.onPopup 监听器在捕获真实弹窗时回填标记）。 */
    public void setPopup(boolean popup) {
        this.popup = popup;
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

    /** 强制清除 hover 标记（点击拾取场景下，确保生成的 step 不使用 {@code .hover()}）。 */
    public void setHover(boolean hover) {
        this.hover = hover;
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

    /** 同步对话框标记（用于生成链路把权威内存态的 dialog 标记补回 step 元素）。 */
    public void setDialog(boolean dialog) {
        this.dialog = dialog;
    }

    public void setDialogType(String dialogType) {
        this.dialogType = dialogType;
    }

    public void setDialogAction(String dialogAction) {
        this.dialogAction = dialogAction;
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
     * 复选框「目标」勾选状态（对齐 setChecked）：{@code true}=应勾选→{@code setChecked(true)}；
     * {@code false}=应取消→{@code setChecked(false)}；{@code null}=非复选框/未捕获。
     */
    public Boolean getSetCheckedTarget() {
        return setCheckedTarget;
    }

    /** 键盘序列（如 "Enter"），对齐 page.pause() 的 press 录制；null=无键盘序列。 */
    public String getPressKey() {
        return pressKey;
    }

    /** 拖拽目标元素定位签名（locatorKey），对齐 dragTo 录制；null=非拖拽源。 */
    public String getDragDstKey() {
        return dragDstKey;
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

    /** 该拾取所属页面实例序号（同 pageClass 多次打开时 1,2,3… 区分不同实例）。 */
    public int getPageInstanceId() {
        return pageInstanceId;
    }

    /** 设置页面实例序号（浏览器回传解析时写入；默认 1）。 */
    public void setPageInstanceId(int pageInstanceId) {
        this.pageInstanceId = pageInstanceId;
    }

    /** 该拾取发生时所在页面的 URL（元素级；浏览器回传解析时写入）。 */
    public String getUrl() {
        return url;
    }

    /** 设置拾取发生时的页面 URL（浏览器侧 __recordPick 写入 location.href 后透传）。 */
    public void setUrl(String url) {
        this.url = url;
    }

    /** pageClass + 实例序号组成的稳定实例键（用于代码生成阶段区分同页多实例）。 */
    public String getInstanceKey() {
        return (pageClass == null ? "" : pageClass) + "#" + pageInstanceId;
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

    /**
     * 该元素所在的 iframe 嵌套路径（顶层主框架为 null 或空）。
     * 生成 frameLocator 时使用：路径非空时以 {@code page.frameLocator(...).locator(...)} 包裹。
     */
    public java.util.List<String> getFramePath() {
        return framePath;
    }

    /** 设置 iframe 嵌套路径（回传写入内存态时调用）。 */
    public void setFramePath(java.util.List<String> framePath) {
        this.framePath = framePath;
    }

    /**
     * 元素所属「空间」的可读标识，融合 iframe 与 shadowRoot 两个维度，供用户在拾取面板中
     * 一眼看出该元素落在哪个空间（主文档 / 某 frame / 某 shadowRoot / frame 内的 shadowRoot）。
     *
     * <p>取值示例：
     * <ul>
     *   <li>{@code "main"} —— 主文档（既不在 iframe 也不在 shadow 内）；</li>
     *   <li>{@code "frame:login"} —— 落在 name/id 为 login 的 iframe 内；</li>
     *   <li>{@code "shadow:hostComp"} —— 落在宿主标签为 hostComp 的 open shadowRoot 内；</li>
     *   <li>{@code "frame:login>shadow:comp"} —— 落在 frame:login 内的 shadowRoot 里；</li>
     *   <li>{@code "frame:a>frame:b"} —— 嵌套 iframe。</li>
     * </ul>
     *
     * <p>透传字段（不参与代码生成语义、不参与去重签名），浏览器侧 {@code __computePick}
     * 在算完 {@code framePath} 之后补充 shadow 维度、合并写入。仅主文档元素为 {@code "main"}（默认）。
     */
    private String space = "main";

    public String getSpace() { return space; }
    public void setSpace(String space) { this.space = (space == null || space.isEmpty()) ? "main" : space; }

    /**
     * 元素所在 open shadowRoot 的【结构化宿主链】（自顶向下），用于生成「显式切换 shadow」step。
     *
     * <p>浏览器侧 {@code __computePick} 沿 {@code el.getRootNode().host} 向上收集每一层 open shadow 的
     * 宿主 CSS 选择器（如 {@code ["#app-host", "comp-menu#menu"]}）。step 生成时据此在 frame 切换之后
     * 对称生成 {@code switchToShadow(host)} 进入 / {@code switchToDefaultShadow()} 退出，
     * 对齐 page.pause() 的 shadow 穿透录制，便于在 shadow 上下文内正确定位并提升步骤可读性。
     *
     * <p>主文档 / 仅 iframe 内的元素此项为空（null 或空列表），生成时跳过 shadow 切换。
     */
    private java.util.List<String> shadowPath;

    public java.util.List<String> getShadowPath() { return shadowPath; }
    public void setShadowPath(java.util.List<String> shadowPath) {
        this.shadowPath = shadowPath;
        // 同步维护 space 归属空间标识：元素位于 open shadow 内时，space 应为 shadow:<宿主>，
        // 与浏览器侧 __computePick 的 space 语义一致。仅当 space 仍是默认 "main"（未由浏览器显式设置）
        // 且 shadowPath 非空时推导，避免覆盖浏览器已精确计算的多层 shadow/iframe 组合 space。
        if ((this.space == null || "main".equals(this.space))
                && shadowPath != null && !shadowPath.isEmpty()) {
            this.space = "shadow:" + String.join(">shadow:", shadowPath);
        }
    }

    /**
     * 用户在拾取面板中「勾选元素」时分配的【动态连续序号】（按勾选先后顺序）。
     *
     * <p>序号驱动步骤生成顺序：{@code generate} 遍历 step 元素前按本字段升序排布，
     * 取消勾选某元素时，剩余元素的序号会【自动重排】为连续值（1,2,3…），
     * 故本字段是「选择集内的相对顺序」而非固定 id。未勾选（不在选择集）的元素序号为 0。
     *
     * <p>透传字段（不参与代码生成语义、不参与去重签名），由浏览器侧
     * {@code window.__currentStep} 选择集维护。默认 0 表示尚未编入步骤序列。
     */
    private int seq = 0;

    /**
     * 步骤内生成顺序号。优先用面板侧 __renumberStep 回填的 seq（代表用户勾选/重排后的序列）；
     * 若 seq 未赋值（=0，典型场景：主框架直接拾取的元素不经过 postMessage 通道、__renumberStep 未对其赋值），
     * 则回退到 pickNos 的首号（即用户首次拾取该元素的动作序号），保证"按拾取序号产生 step"始终成立。
     */
    public int getSeq() {
        if (seq > 0) return seq;
        if (pickNos != null && !pickNos.isEmpty()) {
            Integer first = pickNos.get(0);
            if (first != null && first > 0) return first;
        }
        return seq;
    }
    public void setSeq(int seq) { this.seq = seq; }

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
