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
    //  契约由 RolePickerModeContractTest 以「JS 实读字面量」方式守护。
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

    // ───────────────────────── CAP_* : 容量上限 ─────────────────────────
    /**  URL→类名映射上限。原实现无上限，每派生一个新 URL 的类名就登记一条、只增不减。 */
    static final int CAP_GLOBAL_URL_TO_CLASS_MAX = 1024;
}
