package com.hsbc.cmb.hk.dbb.automation.framework.web.page.scan;

/**
 * 扫描包（T5-1）统一常量归集。
 *
 * <p>把原本散落在各引擎/控制器里的 Java 侧魔法常量集中到此处，按职责分区，消除重复字面量、
 * 降低「Java 与面板 JS 契约键不一致」的风险：
 * <ul>
 *   <li>{@code CMD_*}   —— 面板 JS → Java 引擎命令名（{@code RolePickerCommandEngine#runPickerCommand} 的 cmd 取值）；</li>
 *   <li>{@code MODE_*}  —— 拾取模式枚举 {@link com.hsbc.cmb.hk.dbb.automation.framework.web.page.scan.model.PickMode} 对应的浏览器侧字符串
 *                          （等价于 {@code mode.name().toLowerCase()}，集中后改动一目了然）；</li>
 *   <li>{@code STATE_*} —— Java ↔ 面板 JS 通信的实参键，与对应脚本里的 {@code a.xxx} 字段严格对应；</li>
 *   <li>{@code TIMEOUT_*} —— 超时/去抖阈值（毫秒）；</li>
 *   <li>{@code CAP_*}   —— 容量上限。</li>
 * </ul>
 *
 * <p><b>行为保持零回归：</b>所有取值均与既有生产字节码逐字符等价，仅做「常量引用」替换，
 * 不改动任何运行时行为；契约键（STATE_/CMD_/MODE_）的值必须与面板 JS 侧保持一致。
 */
final class RolePickerConstants {

    private RolePickerConstants() {}

    // ───────────────────────── CMD_* : 面板 → 引擎命令名 ─────────────────────────
    static final String CMD_START = "start";
    static final String CMD_SCAN = "scan";
    static final String CMD_SCAN_REGION = "scanRegion";
    static final String CMD_REGION_SCANNED = "regionScanned";
    /**
     * 区域选择<b>收尾</b>命令（F-07 / P1-7）：用户按 Esc 结束选区时由浏览器侧 {@code finish()} 回传。
     *
     * <p>与 {@link #CMD_REGION_SCANNED} 的分工是本项修复的核心：{@code regionScanned} 是<b>每次点击</b>
     * 区域的增量通知（Java 只刷新选区与代码，<b>不得</b>收尾）；只有本命令才 END 选区并回 IDLE。</p>
     */
    static final String CMD_REGION_DONE = "regionDone";
    static final String CMD_PACKAGE = "package";
    static final String CMD_REFRESH_CODE = "refreshCode";
    static final String CMD_STOP = "stop";
    static final String CMD_ABORT = "abort";
    static final String CMD_DONE = "done";

    // ───────────────────────── MODE_* : PickMode → 浏览器侧字符串 ─────────────────────────
    //  取值必须与面板 JS 的比对字面量严格一致（同一 Java↔JS 契约）：
    //  panel-core-a.js / picker-core-b2.js 全程比对 'idle' / 'manual' / 'scanPage' / 'scanRegion'。
    //  修复评审 F-06：原 scan_page / scan_region（下划线）与 JS 的 scanPage / scanRegion（驼峰）错位，
    //  导致窗口模式判定恒不成立 —— 扫描态按钮禁用/提示失效、focusin 键盘可达拾取入口永久 early-return（静默失效）。
    //  契约由 START_SCRIPT / PANEL_SCRIPT 的 JS 资源 + tools/validate_picker_js.js 在构建期
    //    node --check 兜底（含组合体就地校验）；Java 侧不另设 phantom 测试（原注释引用的
    //    RolePickerModeContractTest 并不存在，属 A-03 / doc 22 揭示的虚假引用）。
    static final String MODE_IDLE = "idle";
    static final String MODE_MANUAL = "manual";
    static final String MODE_SCAN_PAGE = "scanPage";
    static final String MODE_SCAN_REGION = "scanRegion";

    // ───────────────────────── STATE_* : Java↔JS 通信实参键（对应脚本 a.xxx） ─────────────────────────
    static final String STATE_KEY_MODE = "mode";
    static final String STATE_KEY_PAGE_NAME = "pageName";
    static final String STATE_KEY_CODE = "code";
    static final String STATE_KEY_MSG = "msg";
    static final String STATE_KEY_FILES = "files";
    static final String STATE_KEY_NLS = "nls";
    static final String STATE_KEY_AUTO_STEP_COUNT = "n";
    static final String STATE_KEY_STATE_JSON = "stateJson";

    // ───────────────────────── STRATEGY_* : 拾取策略名（Java↔JS 契约键） ─────────────────────────
    // 与面板 JS / 拾取数据 schema 中 strategy 字段取值严格对应；改值时须同步 JS 侧。
    static final String STRATEGY_TEXT = "text";
    static final String STRATEGY_ALT_TEXT = "altText";
    static final String STRATEGY_TITLE = "title";
    static final String STRATEGY_PLACEHOLDER = "placeholder";
    static final String STRATEGY_LABEL = "label";
    static final String STRATEGY_TEST_ID = "testid";
    static final String STRATEGY_ID = "id";
    static final String STRATEGY_CSS = "css";
    static final String STRATEGY_I18N = "i18n";

    // ───────────────────────── TIMEOUT_* : 超时/去抖（毫秒） ─────────────────────────
    /** 同一 page 跨域重注入去抖窗口：窗口内只真正重注入一次，避免重复渲染所有元素。 */
    static final long TIMEOUT_FORCE_START_DEBOUNCE_MS = 2000L;
    /** NLS 反向映射缓存 TTL 默认 5 分钟（可由系统属性 {@code rolePicker.nlsCacheTtlMs} 覆盖）。 */
    static final long TIMEOUT_NLS_CACHE_TTL_MS = 5L * 60 * 1000;
    /** NLS 缓存 TTL 系统属性名。 */
    static final String NLS_CACHE_TTL_PROPERTY = "rolePicker.nlsCacheTtlMs";
    /**
     * 彻底禁用 NLS key 体系的系统属性名：{@code -DrolePicker.nls.disabled=true}。
     *
     * <p>置位后不构建任何 NLS 反查表 ⇒ pick 不带 resolvedKey、面板不显示 key、生成页面类不产生
     * {@code key = ...} 与 {@code @RoleFile}、i18n 策略不出现，元素一律按 role/name（或 id/css）定位。
     * 适用：项目里没有 nls 文件、不希望"key"这种概念出现。
     */
    static final String NLS_DISABLED_PROPERTY = "rolePicker.nls.disabled";
    /**
     * 面板会话最长存活时间（毫秒）—— 超过即自动结束会话并释放面板（N-19，doc 21）。
     *
     * <p><b>要防的缺陷</b>：面板主循环是 {@code while (true)}，且它跑在<b>调用者线程</b>上（本模块不创建
     * 任何线程）。其退出条件只有「根页关闭 / 无存活页 / 线程中断」三类 —— 若这些事件因异常丢失
     * （{@code onClose} 未回退、{@code rootClosed} 未置位、浏览器侧回调被吞），循环会以
     * {@code poll(1000ms)} <b>无限阻塞调用线程</b>：表现为"面板点了没反应、用例永不结束"，
     * 且没有任何超时兜底。</p>
     *
     * <p>默认 30 分钟：远长于任何正常拾取会话（正常为分钟级），又能在事件丢失时兜底结束。
     * 可由系统属性 {@code rolePicker.panelSessionMaxMs} 覆盖；<b>非正值不视为"无上限"</b>，
     * 而是回落默认值并告警（与 N-16 的页面超时纪律一致：{@code 0} 表示无限等待属隐患，
     * 需要更长会话请显式给正数，例如 240 分钟）。</p>
     */
    static final long TIMEOUT_PANEL_SESSION_MAX_MS = 30L * 60 * 1000;

    /** 面板会话最长存活时间的系统属性名（覆盖 {@link #TIMEOUT_PANEL_SESSION_MAX_MS}）。 */
    static final String PANEL_SESSION_MAX_PROPERTY = "rolePicker.panelSessionMaxMs";

    // ───────────────────────── CAP_* : 容量上限 ─────────────────────────
    /**  URL→类名映射上限。原实现无上限，每派生一个新 URL 的类名就登记一条、只增不减。 */
    static final int CAP_GLOBAL_URL_TO_CLASS_MAX = 1024;
}
