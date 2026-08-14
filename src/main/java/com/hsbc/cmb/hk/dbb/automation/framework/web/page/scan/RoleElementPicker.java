package com.hsbc.cmb.hk.dbb.automation.framework.web.page.scan;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.RoleElement;
import com.hsbc.cmb.hk.dbb.automation.framework.web.utils.NLSUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.microsoft.playwright.Frame;
import java.util.*;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 交互式拾取器：像 {@code page.pause()} 一样，在浏览器里点击想要的元素，
 * 自动抓取被点元素的 a11y role / name，再交给 {@link RoleElementPageGenerator} 生成 {@code @RoleElement} 代码。
 *
 * <p>与整页 {@code dumpAccessibilityRoles} 不同，本类只采集"用户主动点击"的元素，
 * 适合页面导航/列表很冗杂、只需其中少数几个控件的场景。
 *
 * <h3>原理</h3>
 * 在页面注入捕获阶段的 click / keydown 监听：点击任意元素即拦截其默认行为（避免跳转），
 * 用 {@code element.computedRole}/{@code element.computedName}（带回退）取出 a11y 信息并暂存；
 * 按 ESC 结束拾取。测试线程在 {@code pick(...)} 内阻塞等待，用户在真实浏览器中点选。
 *
 * <h3>前置</h3>
 * 须在<b>有头（headed）</b>浏览器中运行，且页面已导航到目标页。
 *
 * <pre>
 * Page pw = loginPage.getPage();
 * List&lt;RoleEntry&gt; picks = RoleElementPicker.pick(pw);
 * RoleElementPageGenerator.write(picks, "src/test/java",
 *         "com.hsbc...tests.pages", "LoginPage", "nls/login.nls.json");
 * // 或一步到位：
 * RoleElementPicker.pickAndWrite(pw, "src/test/java",
 *         "com.hsbc...tests.pages", "LoginPage", "nls/login.nls.json");
 * </pre>
 */
public final class RoleElementPicker {

    private static final Logger log = LoggerFactory.getLogger(RoleElementPicker.class);

    /**
     * 上下文级"当前会话"桥（对齐 {@code page.pause()} 的 DebugController/Recorder 解耦）：
     * 命令桥/拾取桥不再逐页 {@code exposeFunction}，而是对 {@link BrowserContext} 一次性
     * {@code exposeBinding}——context 下所有当前与未来页面（弹窗/新标签页）、每次导航后的新文档
     * 都自动持有绑定，由 {@code BindingCallback.Source#page()} 天然区分"哪个页面发起"。
     * 同名绑定不可重复注册（重复会抛 {@code PlaywrightException}），故每个 context 仅注册一次；
     * 二次打开（同一 context 再次 {@code openPanel}）只更新下方 Map 指向的"当前会话"队列/状态，
     * 回调动态读取，避免命令/拾取被投递到已失效的旧会话队列。
     */
    private static final Map<BrowserContext, BlockingQueue<CmdEvent>> CTX_CMD_QUEUES = new ConcurrentHashMap<>();
    private static final Map<BrowserContext, LinkedHashMap<String, RoleEntry>> CTX_PICK_STATES = new ConcurrentHashMap<>();
    private static final Set<BrowserContext> CTX_BRIDGED = Collections.newSetFromMap(new ConcurrentHashMap<>());
    /**
     * 拾取模式（一级概念，三种模式互斥）：
     *  - IDLE：待命（面板显示"▶ 开始拾取"，页面点击不拾取任何元素）
     *  - MANUAL：手动拾取（点哪个元素拾哪个，iframe/新窗口/alert 按归属字段区分）
     *  - SCAN_PAGE：整页扫描（穿透 iframe/shadow，扫完自动回 IDLE）
     *  - SCAN_REGION：区域扫描（选区域后扫描，扫完自动回 IDLE）
     * 与浏览器侧 window.__roleMode 同步，由 Java 权威驱动。
     */
    public enum PickMode { IDLE, MANUAL, SCAN_PAGE, SCAN_REGION }
    private static final Map<BrowserContext, PickMode> CTX_PICK_MODES = new ConcurrentHashMap<>();

    /**
     * 标记"由框架主动关闭（BasePage.closeCurrentPage 调 page.close()）"的页面，按 context 隔离。
     * 目的：onClose 监听无法区分"用户/外部手动关闭"与"代码主动 page.close()"——
     * 两者都会触发 onClose。代码主动关闭时不该再登记一条 closeCurrentPage 步骤（否则重复且回放会重复关）。
     * 只有未标记的关闭（即监控到真实外部/手动关闭）才在 onClose 中登记 _closeOp。
     */
    private static final Map<BrowserContext, java.util.Set<Page>> CTX_FRAMEWORK_CLOSED =
            new ConcurrentHashMap<>();

    /** 在 BasePage.closeCurrentPage 调 page.close() 前调用：标记本页为"框架主动关闭"。 */
    public static void markFrameworkClose(Page page) {
        if (page == null) return;
        BrowserContext ctx = page.context();
        CTX_FRAMEWORK_CLOSED.computeIfAbsent(ctx, c -> ConcurrentHashMap.newKeySet()).add(page);
    }

    /** 供 onClose 判断：本次关闭是否来自框架主动调用；读取后清除标记（页面关后即失效）。 */
    private static boolean consumeFrameworkClose(Page closed) {
        if (closed == null) return false;
        try {
            BrowserContext ctx = closed.context();
            java.util.Set<Page> set = CTX_FRAMEWORK_CLOSED.get(ctx);
            if (set != null && set.remove(closed)) {
                if (set.isEmpty()) CTX_FRAMEWORK_CLOSED.remove(ctx);
                return true;
            }
        } catch (Exception ignore) { /* 页面已关，context 可能失效，按未标记处理 */ }
        return false;
    }

    /** 设置某 context 的拾取模式，并同步到所有未关闭页面（驱动面板按钮态与浏览器侧行为）。 */
    private static void setPickMode(Page anyPage, PickMode mode,
                                    LinkedHashMap<Page, String> pageNames) {
        if (anyPage == null || anyPage.isClosed()) return;
        BrowserContext ctx = anyPage.context();
        CTX_PICK_MODES.put(ctx, mode);
        String jsMode = mode.name().toLowerCase();
        if (pageNames != null) {
            for (Page p : pageNames.keySet()) {
                try { if (!p.isClosed()) p.evaluate(
                        "(function(m){ try{ window.__roleMode = m; if(window.__roleRefreshToggle) window.__roleRefreshToggle(); }catch(e){} })('" + jsMode + "')"); }
                catch (Exception ignore) {}
            }
        }
    }
    // 上下文级初始化脚本守卫：面板脚本每 context 仅注册一次；拾取脚本按 nls 内容变化才追加注册
    // （addInitScript 无法撤销，重复注册会累积执行；同 nls 幂等跳过，不同 nls 追加后"后注册者后执行"覆盖生效）。
    private static final Set<BrowserContext> CTX_PANEL_SCRIPTED = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private static final Map<BrowserContext, String> CTX_PICKER_NLS = new ConcurrentHashMap<>();
    // 最近一次成功 start() 注入的页面 origin（手动开始拾取 / onFrameNavigated 跨域强制重注入后更新）。
    // 用于 onFrameNavigated 重激活时区分"同源导航（门控脚本已注入，仅轻量保活）"与"跨域导航（门控因
    // localStorage 隔离未注入，需强制重注入库）"——同源永远不强制 start，杜绝一次导航多次 onFrameNavigated
    // 反复重注入导致"扫描了很多元素"的放大现象。
    private static volatile String LAST_PICK_ORIGIN = "";
    // 跨域强制重注入去抖：key=page，value=上次强制 start 时间戳。同一 page 在 FORCE_START_DEBOUNCE_MS
    // 内不重复强制 start（一次导航会触发 onFrameNavigated 多次：about:blank 过渡/重定向/主框架/iframe）。
    private static final long FORCE_START_DEBOUNCE_MS = 2000L;
    private static final Map<Page, Long> FORCE_START_TS = new ConcurrentHashMap<>();

    /**
     * 会话级持久"已删集合"：用户主动删除的元素键（_sig / _sigKey / 去索引 locatorKey）。
     * 与浏览器端 {@code window.__deletedSigs} 的区别在于后者在【区域扫描入口】会被清空
     * （区域扫描 = 本次选中区域里的"全新候选"语义，不应继承过往删除屏蔽），而本集合跨扫描/跨页面
     * 持久。作用：{@code syncPanelToBrowser} 把 Java 权威内存态 javaPickBySig 回灌浏览器时，
     * 即便 Java 删除因键不匹配未命中实体、且浏览器端 __deletedSigs 已被区域扫描清空，
     * 仍按本集合永久屏蔽已删元素，杜绝"区域扫描后已删元素被主循环复活"的回归。
     * key 为 javaPickBySig 对象引用（每会话稳定，O(1) 反查），值为已删键集合。
     */
    private static final Map<LinkedHashMap<String, RoleEntry>, Set<String>> STATE_DELETED =
            new ConcurrentHashMap<>();



    /**
     * 面板命令事件：由页面内 {@code window.__rolePickerCmd(c)}（经 {@link Page#exposeFunction} 暴露的
     * Java 回调）投递，携带"哪个页面发的命令"。主循环从阻塞队列取出后据此驱动，避免忙轮询所有页面。
     */
    private static final class CmdEvent {
        final Page page;
        final String cmd;
        CmdEvent(Page page, String cmd) { this.page = page; this.cmd = cmd; }
    }

    /**
     * 把"面板命令桥 + 点击拾取桥 + 控制台兜底桥"一次性注册到 {@link BrowserContext}
     * （对齐 {@code page.pause()}：Recorder 用 {@code context.exposeBinding} 注册跨页面/跨导航存活的回传桥）。
     * <ul>
     *   <li>命令桥：页面内 {@code window.__rolePickerCmd(c)} → (来源 page, cmd) 投递进阻塞队列，主循环事件驱动消费；</li>
     *   <li>拾取桥：页面内 {@code window.__roleOnPick(json)} → 单个拾取元素 O(1) 回传进 Java 权威内存态；</li>
     *   <li>控制台桥：捕获 {@code __roleOnPick::<json>} 兜底回传（exposeBinding 异常失效时拾取不丢失）。</li>
     * </ul>
     * context 下所有当前与未来页面自动持有绑定，无需逐页注册/跟踪；每个 context 仅注册一次（幂等），
     * 会话级队列/状态经 CTX_* Map 动态解析，二次打开只换 Map 指向。
     */
    private static void registerContextBridges(BrowserContext ctx,
                                               BlockingQueue<CmdEvent> queue,
                                               LinkedHashMap<String, RoleEntry> javaPickBySig) {
        CTX_CMD_QUEUES.put(ctx, queue);
        // 【关键修复"二次 openPanel 导致已拾元素清零"】二次 openPanel（设计上支持，见类注释"二次打开只换 Map 指向"）
        // 会 new 一个空 javaPickBySig 并传入；若此处直接 put 覆盖，context 权威内存态会被空 map 替换，
        // 后续 __roleOnPick 回调（CTX_PICK_STATES.get(ctx)）全部写进空 map → 此前 LogonPage+SetupSecondPwdPage
        // 已拾的全部元素丢失（日志现象：内存态 49→1）。故二次打开时把旧会话历史迁移合并进本次 map（保留去重顺序），
        // 让 openPanel 后续代码（STATE_DELETED 等）拿到"含历史"的同一引用，历史不丢。
        LinkedHashMap<String, RoleEntry> prev = CTX_PICK_STATES.get(ctx);
        // 【diag-migrate】追踪跨会话迁移是否把脏 pickNos/seq 带入本轮（定位 user_name 首号恒为 2 的根因）。
        if (prev != null) {
            for (java.util.Map.Entry<String, RoleEntry> e : prev.entrySet()) {
                if (e.getValue() != null && (e.getKey() != null && e.getKey().contains("user_name"))) {
                    log.info("[picker][diag-migrate] prev key={} pickNos={} seq={} (prev==javaPickBySig? {})",
                            e.getKey(), e.getValue().getPickNos(), e.getValue().getSeq(), (prev == javaPickBySig));
                }
            }
        }
        if (prev != null && prev != javaPickBySig) {
            for (java.util.Map.Entry<String, RoleEntry> e : prev.entrySet()) {
                if (!javaPickBySig.containsKey(e.getKey())) {
                    // 【修复"跨会话脏序号污染本轮（后拾取元素首号偏小，如 user_name 拿到 2 而非 4/5）"】
                    // 旧实现直接复用 prev 的 RoleEntry 引用（含上一轮被旧 bug 污染的 pickNos，如 [2]）。
                    // 新会话是全新拾取，序号需按本轮点击顺序重新生成；若沿用 prev 的 [2]，syncPanelToBrowser
                    // 会用它覆盖浏览器侧本轮正确累计的 [5]，最终 step 序号错乱。
                    // 修复：迁移历史仅为"保留已拾元素不丢"（见上方二次 openPanel 场景），但其 pickNos 必须清零，
                    // 让面板回退到 [-]，本轮重新点击时从干净状态按真实次序重新累计，杜绝脏序号串台。
                    RoleEntry migrated = e.getValue();
                    if (migrated != null) { migrated.setPickNos(null); migrated.setSeq(0); }
                    javaPickBySig.put(e.getKey(), migrated);
                }
            }
        }
        CTX_PICK_STATES.put(ctx, javaPickBySig);
        boolean first = CTX_BRIDGED.add(ctx);
        log.info("[picker] 上下文桥 registerContextBridges：firstReg={}（命令/拾取/控制台桥，context 级一次注册）", first);
        if (!first) return;
        // 命令桥：BindingCallback 的 Source 自带来源 Page，天然区分命令来自哪个页面
        // （新页/默认页共享同一绑定，CmdEvent.page 记录来源）。绑定对 context 下所有页面、所有导航存活。
        ctx.exposeBinding("__rolePickerCmd", (source, args) -> {
            BlockingQueue<CmdEvent> q = CTX_CMD_QUEUES.get(ctx);
            if (q == null) return null;
            String c = null;
            try {
                if (args != null && args.length > 0) {
                    Object v = args[0];
                    if (v == null) {
                        c = null;
                    } else if (v instanceof String) {
                        // 浏览器侧若以 JSON 字符串传入（如 window.__rolePickerCmd(JSON.stringify({...}))），原样使用
                        c = (String) v;
                    } else {
                        // Playwright 会把 JS 对象反序列化为 Java Map/List/Number/Boolean，
                        // 其 toString() 不是合法 JSON（单等号、无引号），直接 Gson 解析会抛 MalformedJsonException。
                        // 故统一用 GSON 规范序列化为 JSON 字符串再入队（repickNos 等对象命令依赖此路径）。
                        c = GSON.toJson(v);
                    }
                }
            } catch (Exception ignore) {}
            q.offer(new CmdEvent(source.page(), c));
            return null;
        });
        // 拾取桥：浏览器端经 window.__roleOnPick(JSON.stringify(pick)) 异步投递，零往返回传 Java 内存态。
        ctx.exposeBinding("__roleOnPick", (source, args) -> {
            LinkedHashMap<String, RoleEntry> map = CTX_PICK_STATES.get(ctx);
            if (map == null) return null;
            try {
                if (args == null || args.length == 0) return null;
                Object v = args[0];
                if (v == null) return null;
                // 【diag-raw】原始回传参数：确认浏览器经 binding 实际投递给 Java 的 JSON 是否含 _pickNos 字段。
                log.info("[picker][diag-raw] __roleOnPick raw arg type={} value={}", (v == null ? "null" : v.getClass().getSimpleName()), String.valueOf(v));
                @SuppressWarnings("unchecked")
                Map<Object, Object> m = GSON.fromJson(String.valueOf(v), Map.class);
                RoleEntry e = parsePick(m);
                if (e == null) return null;
                // 【关键修复"iframe 元素丢失所属框架上下文 / 监听器好像没起作用"】
                // 旧逻辑仅用浏览器回传的 framePath（parsePick 内部 parseFramePath(m.get("framePath"))）。
                // 在 file:// / 跨源场景下，子 frame 内 window.frameElement 会抛 SecurityError，
                // 浏览器侧 __framePathOf 的兜底（window.name）常取不到值 → 回传的 framePath 为空，
                // 于是生成的代码里 iframe 内元素被当成主页元素：既无 frameOne 前缀、也无 switchToFrame，
                // 直接 .click() 点到的是 <iframe> 节点本身而非进入框架——表现为"框架监听/归属失效"。
                // getEntries 走的是另一条带 Java 侧 computeFramePath backfill 的路径（故 verify 测试通过），
                // 但代码生成读的是本 path 的 javaPickBySig，二者必须一致。故在此用 source.frame().frameElement()
                // 做一次与 getEntries 同源的 backfill：浏览器给了就用浏览器的，否则用 Java 计算的真实框架路径。
                if (e.getFramePath() == null || e.getFramePath().isEmpty()) {
                    try {
                        List<String> fp = computeFramePath(source.page(), source.frame());
                        if (fp != null && !fp.isEmpty()) e.setFramePath(fp);
                    } catch (Exception ignore) {}
                }
                // 去重键与浏览器端保持一致但更精确：定位器唯一型策略（id/css/i18n/text/...）按 locator 签名（_sig）
                // 去重，避免同一元素在"主页↔弹窗"间被重复收录；角色/closeOp 仍按 [sig, pageClass|URL]（_sigKey）区分。
                // 重复点击以最近一次交互为准整条替换（RoleEntry 不可变，更新须替换）；首次插入保序。
                String key = pickDedupKey(m, e);
                synchronized (map) {
                    RoleEntry existing = map.get(key);
                    // 【diag-onpick】i18n 首次回传前，打印权威 map 里该 key 是否已有脏 pickNos/seq（区分"Java 内存态脏"还是"浏览器直接发 [2]"）。
                    if (key != null && key.contains("user_name")) {
                        log.info("[picker][diag-onpick][PRE] key={} mapExistingPickNos={} mapExistingSeq={} incomingRawNos={}",
                                key, (existing == null ? "null" : existing.getPickNos()), (existing == null ? "null" : existing.getSeq()), m.get("_pickNos"));
                    }
                    // 去重回传：自愈/重挂 START_SCRIPT 会重放已有拾取（__rolePicks 重建），主循环每轮
                    // ensurePickingActive 又按 ~1s 重挂，导致同一 pick 被重复回传数十次、日志刷屏。
                    // 若 key 已存在且实体未变化（sigKey/framePath/dialog/popup 均一致），仅静默合并、不打日志；
                    // 仅在「新元素」或「已有元素被增强（补 framePath/弹窗/对话框）」时记录。
                    boolean changed = (existing == null)
                            || !roleEq(existing.getSigKey(), e.getSigKey())
                            || !framePathEq(existing.getFramePath(), e.getFramePath())
                            || (existing.isDialog() != e.isDialog())
                            || (existing.isPopup() != e.isPopup());
                    RoleEntry merged = mergePickIntoMap(map, key, e);
                    log.info("[picker][diag-onpick][BIND] key={} pickNos(after-merge)={} changed={} rawNos={} strategy={} keys={}", key, merged.getPickNos(), changed, m.get("_pickNos"), (e != null ? e.getStrategy() : null), (m != null ? m.keySet() : null));
                    if (changed) {
                        List<String> fpLog = merged.getFramePath();
                        log.info("[picker] __roleOnPick 回传写入内存态：key={} pageClass={} framePath={}（当前内存态大小={}）", key, (merged.getPageClass() == null ? "" : merged.getPageClass()), (fpLog == null || fpLog.isEmpty() ? "" : fpLog.toString()), map.size());
                    }
                }
            } catch (Exception ex) {
                log.warn("[picker] __roleOnPick 回传解析失败：{}", ex.getMessage());
            }
            return null;
        });
        // 删除桥：面板「垃圾桶」删除选中元素时，浏览器端经 window.__roleOnDelete(JSON.stringify(keys)) 回传。
        // 【必需】此前删除只从浏览器 window.__rolePicks 里 filter 掉，未同步 Java 权威内存态 javaPickBySig；
        // 而主循环每轮空闲（~1s）都会 syncPanelToBrowser 把 javaPickBySig 整体 merge 回浏览器，
        // 被删元素随即"复活"，表现为「删除没起作用」；且代码生成读的就是 javaPickBySig，
        // 界面删掉了生成的代码里仍然存在。故必须让删除同时落到 Java 侧。
        // 传入的每个键可能是 _sig 或 _sigKey——因 pickDedupKey 对「定位器唯一型策略」用 _sig 作 map key、
        // 对 role/closeOp 用 _sigKey，浏览器无法预知用了哪个，故两者都发、Java 侧按任一命中即移除。
        ctx.exposeBinding("__roleOnDelete", (source, args) -> {
            LinkedHashMap<String, RoleEntry> map = CTX_PICK_STATES.get(ctx);
            if (map == null) return null;
            try {
                if (args == null || args.length == 0) return null;
                Object v = args[0];
                if (v == null) return null;
                // 入参可能是：① 完整 pick 对象数组（新格式，推荐）或 ② 纯 key 字符串数组（旧格式兼容）。
                // 对完整 pick 对象，用与入库时完全一致的 pickDedupKey 重新算出内存态 map key 再删，
                // 从而精确命中「定位器唯一型策略」(key=_sig) 与「role 策略」(key=_sigKey)，彻底修复删除无效。
                @SuppressWarnings("unchecked")
                List<?> raw = GSON.fromJson(String.valueOf(v), List.class);
                java.util.Set<String> dead = collectDeleteKeys(raw);
                if (dead.isEmpty()) return null;
                // 【修复"跨域/iframe 内元素只进 Java 内存态、不进浏览器 __rolePicks"导致删不掉的残留】
                // 典型：StatusConfirmationLightIcon（testid 策略，位于 crossdomain iframe）。
                // 跨域 iframe 因同源策略无法被 window.frames 访问，浏览器侧删除永远拿不到它，dead 里无它的键，
                // 值级兜底也救不了（兜底仍是用 dead 的键比对）。用户点删除的语义是"删除该页全部拾取元素"，
                // 故这里收集 dead 涉及的 pageClass，把内存态中【同一 pageClass 的其余元素】一并整桶删除。
                // 严格绑定 pageClass，绝不会波及另一页的共用元素（LoginPage 的 footer/HSBC App tab/Language 等）。
                java.util.Set<String> deadPages = new java.util.LinkedHashSet<>();
                for (Object o : raw) {
                    if (o instanceof java.util.Map) {
                        java.util.Map<?, ?> m = (java.util.Map<?, ?>) o;
                        Object pc = m.get("_pageClass");
                        if (pc == null) pc = m.get("pageClass");
                        if (pc != null && !String.valueOf(pc).isEmpty()) deadPages.add(String.valueOf(pc));
                    }
                }
                // 也从 dead 键里反向解析 pageClass（键形如 pc|... 或 ["sig","pc"]）
                for (String d : dead) {
                    if (d == null) continue;
                    int bar = d.indexOf('|');
                    if (bar > 0) {
                        deadPages.add(d.substring(0, bar));
                    } else if (d.startsWith("[") && d.contains("\",\"")) {
                        int c2 = d.lastIndexOf('"');
                        int c1 = d.lastIndexOf('"', c2 - 1);
                        if (c1 >= 0 && c2 > c1) deadPages.add(d.substring(c1 + 1, c2));
                    }
                }
                synchronized (map) {
                    int before = map.size();
                    // ① 按 map key 直接移除；再兜底扫一遍实体上固化的 sigKey，覆盖 key 与 sigKey 不一致的历史数据。
                    map.keySet().removeIf(dead::contains);
                    map.entrySet().removeIf(en -> {
                        RoleEntry re = en.getValue();
                        return re != null && re.getSigKey() != null && dead.contains(re.getSigKey());
                    });
                    // ② 【根治"删除所有元素后页面类没删除干净"——值级匹配兜底】
                    // 只按 map key / 固化 sigKey 匹配时，id/css 型元素的 key 是 Java 侧 locatorKey（含 selector），
                    // 而删除回传的精简 delPick 常缺 selector，collectDeleteKeys 算出的 locatorKey 对不上 →
                    // 这类元素删除 miss，残留在权威内存态，refreshCode 生成页面类时仍出现。
                    // 这里遍历 map 每个 entry，把该 RoleEntry 的所有可识别定位标识（重算 locatorKey /
                    // role+name / strategy+selector / strategy+name / 去索引 sig）逐一与 dead 交叉比对，
                    // 任一命中即删除，彻底摆脱"删除请求 key 与内存态 key 格式不一致"导致的残留。
                    map.entrySet().removeIf(en -> {
                        RoleEntry re = en.getValue();
                        if (re == null) return false;
                        // 【方案 B：页面级隔离】值级兜底也必须绑定 pageClass，否则与 collectDeleteKeys 的
                        // "pc|裸键" 口径脱钩，会退化成跨页裸键匹配（如 LoginPage / SetupSecondPwdPage 同名
                        // 页脚、HSBC App tab、Language 的 _sig 都是裸 "role:link:Language:#0"，裸键比对会
                        // 把另一页同名元素一并删掉）。故所有兜底键一律前缀所属 pageClass。
                        String rpc = (re.getPageClass() != null) ? re.getPageClass() : "";
                        if (dead.contains(en.getKey())) return true;
                        String sigKey = re.getSigKey();
                        if (sigKey != null && dead.contains(sigKey)) return true;
                        // 重算 locatorKey（与入库时同源），覆盖 id/css 型 selector 缺失导致的 key 偏差
                        String lk;
                        try { lk = RoleElementPageGenerator.locatorKey(re); } catch (Exception ignore) { lk = null; }
                        // 必须带 pageClass 前缀比对（同 collectDeleteKeys 的 k1 = pc|lk），杜绝跨页命中
                        if (lk != null && !lk.isEmpty() && dead.contains(rpc + "|" + lk)) return true;
                        // role+name / strategy+selector / strategy+name 兜底（同样前缀 pc）
                        String strategy = re.getStrategy() == null ? "role" : re.getStrategy();
                        if ("role".equals(strategy)) {
                            String rk = "role:" + (re.getRole() == null ? "" : re.getRole()).toLowerCase(java.util.Locale.ROOT)
                                    + ":" + (re.getName() == null ? "" : re.getName());
                            if (dead.contains(rpc + "|" + rk)) return true;
                        } else if ("id".equals(strategy) || "css".equals(strategy)) {
                            if (re.getSelector() != null && dead.contains(rpc + "|" + strategy + ":" + re.getSelector())) return true;
                        } else {
                            if (re.getName() != null && dead.contains(rpc + "|" + strategy + ":" + re.getName())) return true;
                        }
                        return false;
                    });
                    // ③ 【整桶删除同页残留】dead 涉及的 pageClass，把内存态里同一 pageClass 的【剩余】元素一并删除。
                    // 覆盖：跨域/iframe 元素只上送 Java 内存态、浏览器侧 __rolePicks 永远不包含它、dead 里无其键，
                    // 导致 ①② 都删不掉的边缘残留（如 StatusConfirmationLightIcon）。严格绑定 pageClass，不跨页误删。
                    if (!deadPages.isEmpty()) {
                        map.entrySet().removeIf(en -> {
                            RoleEntry re = en.getValue();
                            if (re == null) return false;
                            String rpc = (re.getPageClass() != null) ? re.getPageClass() : "";
                            return !rpc.isEmpty() && deadPages.contains(rpc);
                        });
                    }
                    // 持久记录已删集合（跨区域扫描/跨页面）：即便本页删除全部命中、实体已被移除，
                    // 仍登记 dead 键——其它页面同源元素回灌时同样应被屏蔽，且区域扫描清空浏览器端
                    // __deletedSigs 后仍能靠本集合兜底，杜绝"删除后重启区域扫描又复活"。
                    STATE_DELETED.computeIfAbsent(map, k -> ConcurrentHashMap.newKeySet()).addAll(dead);
                }
                // 【关键修复"删除所有元素后页面类没删除干净"——源头清空 iframe 残留】
                // 删除只清了主框架 __rolePicks 与 Java 权威内存态；iframe 自己的 __rolePicks 仍残留已删元素，
                // 主循环每轮空闲 mergeFramePicksToMain 会把它们合并回 javaPickBySig（虽然 isDeletedKeyInState
                // 已按 STATE_DELETED 拦截，但一旦 key 口径有偏差仍可能复活）。此处彻底清空触发删除页的
                // 所有 frame（主框架 + 各层 iframe）的浏览器侧 __rolePicks/__rolePickSigs，
                // 从源头杜绝残留可被合并——即使 Java 侧拦截漏网，浏览器端也再无残留可回灌。
                try {
                    com.microsoft.playwright.Page srcPage = source.page();
                    if (srcPage != null && !srcPage.isClosed()) {
                        java.util.List<com.microsoft.playwright.Frame> frames = srcPage.frames();
                        for (com.microsoft.playwright.Frame f : frames) {
                            if (f == null) continue;
                            try {
                                f.evaluate("(function(){"
                                        + " window.__rolePicks = [];"
                                        + " window.__rolePickSigs = {};"
                                        + " window.__currentStep = [];"
                                        + " return 1;"
                                        + "})()");
                            } catch (Exception fe) {
                                String u = null; try { u = f.url(); } catch (Exception ignore) {}
                                log.warn("[picker] __roleOnDelete 清空 frame 残留失败（{}）：{}", u, fe.getMessage());
                            }
                        }
                    }
                } catch (Exception pe) {
                    log.warn("[picker] __roleOnDelete 清空 iframe 残留异常：{}", pe.getMessage());
                }
            } catch (Exception ex) {
                log.warn("[picker] __roleOnDelete 回传解析失败：{}", ex.getMessage());
            }
            return null;
        });
        // 控制台兜底桥：context 级 onConsoleMessage 捕获所有页面的 __roleOnPick:: 兜底回传与拾取链路报错，
        // 即使某页面绑定因导航/上下文异常失效，拾取回传也不丢失（按 sig 去重，与 exposeBinding 投递幂等）。
        ctx.onConsoleMessage(msg -> {
            String t = msg.text();
            if (t == null) return;
            if (t.startsWith("__roleOnPick::")) {
                LinkedHashMap<String, RoleEntry> map = CTX_PICK_STATES.get(ctx);
                if (map == null) return;
                try {
                    @SuppressWarnings("unchecked")
                    Map<Object, Object> m = GSON.fromJson(t.substring("__roleOnPick::".length()), Map.class);
                    RoleEntry e = parsePick(m);
                    if (e == null) return;
                    // 【关键修复"iframe 元素丢失所属框架上下文"——console 通道对称回补】
                    // 绑定通道（exposeBinding）有 source.frame() 可 computeFramePath 回补 framePath；
                    // 但在内嵌 iframe 内绑定桥常失效（file:// 跨 origin / 上下文隔离），只经 console.log
                    // 兜底到达。浏览器侧 __framePathOf 在跨源 file:// 下 window.frameElement 受限、
                    // 整个 while 循环抛异常被吞为空数组 → pick 无 framePath → 生成 step 缺 switchToFrame。
                    // 此处利用 msg.page() + iframe 页的 URL（e.getPageClass() 即 location.href）遍历
                    // page.frames() 定位到目标 frame，调用 computeFramePath 回补，与绑定通道对称。
                    if (e.getFramePath() == null || e.getFramePath().isEmpty()) {
                        // 定位 iframe 所在 frame 的 URL：优先浏览器侧记录的 _frameUrl（仅 iframe 内写入，
                        // origin+pathname，不污染 _pageClass）；退化用 _pageClass。file:// 无 query/hash 时
                        // origin+pathname 与 f.url() 通常相等。
                        String pc = null;
                        try { Object fu0 = m.get("_frameUrl"); if (fu0 != null) pc = String.valueOf(fu0); } catch (Exception fuIgn) {}
                        if ((pc == null || pc.isEmpty()) && e.getPageClass() != null && !e.getPageClass().isEmpty()) pc = e.getPageClass();
                        if (pc != null && !pc.isEmpty()) {
                            try {
                                Page p = msg.page();
                                if (p != null && !p.isClosed()) {
                                    for (Frame f : p.frames()) {
                                        // pc 是 origin+pathname（无 query/hash）；f.url() 可能是完整 URL，
                                        // 归一化（去 query/hash、去末尾斜杠）后比较，避免 query/hash 抖动导致漏匹配。
                                        String fu = f.url();
                                        if (fu == null) continue;
                                        int q = fu.indexOf('?');
                                        if (q >= 0) fu = fu.substring(0, q);
                                        int h = fu.indexOf('#');
                                        if (h >= 0) fu = fu.substring(0, h);
                                        while (fu.endsWith("/")) fu = fu.substring(0, fu.length() - 1);
                                        if (pc.equals(fu) || pc.equals(f.url())) {
                                            try {
                                                List<String> fp = computeFramePath(p, f);
                                                if (fp != null && !fp.isEmpty()) {
                                                    e.setFramePath(fp);
                                                }
                                            } catch (Exception frameErr) {}
                                            break;
                                        }
                                    }
                                }
                            } catch (Exception backfillErr) {}
                        }
                    }
                    String key = pickDedupKey(m, e);
                    synchronized (map) {
                        RoleEntry existing = map.get(key);
                        boolean changed = (existing == null)
                                || !roleEq(existing.getSigKey(), e.getSigKey())
                                || !framePathEq(existing.getFramePath(), e.getFramePath())
                                || (existing.isDialog() != e.isDialog())
                                || (existing.isPopup() != e.isPopup());
                        RoleEntry merged = mergePickIntoMap(map, key, e);
                        log.info("[picker][diag-onpick][CONSOLE] key={} pickNos(after-merge)={} changed={} rawNos={} strategy={} keys={}", key, merged.getPickNos(), changed, m.get("_pickNos"), (e != null ? e.getStrategy() : null), (m != null ? m.keySet() : null));
                        if (changed) {
                            List<String> fplog = merged.getFramePath();
                            log.info("[picker] __roleOnPick(console) 回传写入内存态：key={} pageClass={} framePath={}（当前内存态大小={}）", key, (merged.getPageClass() == null ? "" : merged.getPageClass()), (fplog == null || fplog.isEmpty() ? "" : fplog.toString()), map.size());
                        }
                    }
                } catch (Exception ignore) {}
            } else if (t.startsWith("__roleOnDelete::")) {
                // 删除的控制台兜底：与 __roleOnPick:: 对称，绑定失效时删除同样不丢（按键移除天然幂等）。
                LinkedHashMap<String, RoleEntry> map = CTX_PICK_STATES.get(ctx);
                if (map == null) return;
                try {
                    @SuppressWarnings("unchecked")
                    List<?> raw = GSON.fromJson(t.substring("__roleOnDelete::".length()), List.class);
                    java.util.Set<String> dead = collectDeleteKeys(raw);
                    if (dead.isEmpty()) return;
                    synchronized (map) {
                        map.keySet().removeIf(dead::contains);
                        map.entrySet().removeIf(en -> {
                            RoleEntry re = en.getValue();
                            return re != null && re.getSigKey() != null && dead.contains(re.getSigKey());
                        });
                        // 持久记录已删集合（与 exposeBinding 通道对称，保证控制台兜底删除同样写入，杜绝复活）。
                        STATE_DELETED.computeIfAbsent(map, k -> ConcurrentHashMap.newKeySet()).addAll(dead);
                    }
                } catch (Exception ignore) {}
            } else if ("error".equals(msg.type())
                    && (t.contains("rolePick") || t.contains("__record") || t.contains("__role"))) {
                log.info("[browser][error] {}", t);
            } else if (t.startsWith("[roleMouseDiag]")) {
                // 调试鼠标事件日志（mousedown/up/dblclick/contextmenu）实时转发，前缀过滤避免刷屏。
                log.info("[browser]{}", t);
            }
        });
        // 动态 iframe 监听器：context 级对每个（含弹窗/新开）页面挂 onFrameAttached，
        // 覆盖 start() 遍历当时已存在 frame 之外的"运行时新附加/动态创建的 iframe"。
        // 此前动态 iframe 因 start 之后才出现而未注入拾取脚本，导致 iframe 内元素点不到、
        // 生成不出 switchToFrame 包裹的 step。现由 frame 监听器在 frame 一附加即自动注入，
        // 与 start() 的补挂逻辑共用 registerFrameInjection，保证同源 frame 一律可拾取。
        ctx.onPage(p -> registerFrameInjection(p, CTX_PICKER_NLS.get(ctx)));
    }

    /**
     * 为指定页面注入拾取脚本到其所有 frame（含主框架与同源 iframe），并注册 frame 监听器，
     * 使「start 之后动态附加的 iframe」一出现即自动注入。门控脚本仅在拾取会话开启时挂载 START_SCRIPT，
     * 对未参与拾取的页面零侵入。nls 反向表可为 null（仅影响 NLS key 反查，不影响拾取本身）。
     *
     * 复合嵌套 frame 场景（frame 内嵌 frame，多层）：Playwright 的 Frame 接口【没有】onFrameAttached /
     * onFrameNavigated（仅 Page 有），而 Page 级监听的参数 frame 虽只"直接挂名"于主框架，但【任意深度的
     * 子 frame 发生导航时，Page.onFrameNavigated 都会以该子 frame 本身为参数触发】。因此本实现以
     * onFrameNavigated 为核心覆盖信号：每次导航（含嵌套 frame 首次加载）到来即对该 frame 注入，并额外对
     * page.frames()（递归返回全部层）做一次全量补注入兜底，确保 A 内嵌 B、B 内嵌 C 任意深度的 frame 均被注入；
     * onFrameAttached 则负责"动态附加的 iframe"在 about:blank 阶段先注入一层，并遍历其 childFrames() 立即
     * 补入已存在的更深层 frame。
     */
    private static void registerFrameInjection(Page page, String nlsReverseJson) {
        try {
            // ① 对当前已存在的全部 frame（含主框架与任意层嵌套 iframe，page.frames() 递归返回）立即补挂。
            for (Frame f : page.frames()) {
                try { frameInjectOnce(f, nlsReverseJson); } catch (Exception ignore) {}
            }
            // ② 动态附加的 iframe（含运行时创建）：立即注入，并遍历其当前已存在的子 frame 递归补注入。
            page.onFrameAttached(frame -> {
                try {
                    // 页面/连接已关闭时不再注入，避免 connection closed 刷屏
                    if (page.isClosed()) return;
                    frameInjectOnce(frame, nlsReverseJson);
                    for (Frame child : frame.childFrames()) {
                        try { frameInjectOnce(child, nlsReverseJson); } catch (Exception ignore) {}
                    }
                } catch (Exception ignore) {}
            });
            // ③ 任意 frame（含嵌套深层）导航到真实文档 → 立即注入该 frame，并对全树 page.frames() 兜底补注入。
            //    onFrameAttached 多在 about:blank 阶段触发，其 window 随真正子文档加载而销毁，故需在此以
            //    force=true 再注入一次，确保子文档内 __renumberStep 等依赖完整，可被拾取并聚合。
            //    主框架导航由 registerPopupFollow 单独处理（涉及快照/页类派生），此处仅处理子 frame。
            page.onFrameNavigated(frame -> {
                try {
                    // 页面/连接已关闭时不再注入，避免 connection closed 刷屏
                    if (page.isClosed()) return;
                    if (frame == page.mainFrame()) return;
                    frameInjectOnce(frame, nlsReverseJson);
                    for (Frame f : page.frames()) {        // 全量兜底：覆盖本次导航链上更深层的兄弟/子 frame
                        try { frameInjectOnce(f, nlsReverseJson); } catch (Exception ignore) {}
                    }
                } catch (Exception ignore) {}
            });
        } catch (Exception ignore) {}
    }

    /** 对单个 frame 注入拾取脚本：Playwright 的 Frame 无 addInitScript（仅 Page 有），
     *  故对当前已就绪文档直接 evaluate 注入；后续导航由 onFrameNavigated 监听兜底重新注入。
     *  以 force=true 注入门控脚本：动态/子 frame 是独立 window，其会话开关标记与父页不互通
     *  （file:// 下 origin 不同、execution context 随文档切换重置），若仍走 on 判定会误判 false 而 return，
     *  导致 __renumberStep 等依赖未定义。force=true 跳过判定、直接执行 START_SCRIPT，确保子 frame 内拾取依赖完整。
     *  仅影响已开启拾取会话期间注入的 frame，对无关页面无副作用。 */
    private static void frameInjectOnce(Frame frame, String nlsReverseJson) {
        // 连接/页面已关闭或 frame 已分离时，evaluate 必然抛 PlaywrightException: connection closed，
        // 且监听器在浏览器关闭/导航销毁期间会被反复触发，若在此刷屏会大量污染日志。
        // 先在注入前做廉价的有效性检查，无效则静默返回。
        if (frame == null) return;
        try {
            Page owner = frame.page();
            if (owner == null || owner.isClosed()) return;
        } catch (Exception ignore) { return; }
        if (frame.isDetached()) return;
        try {
            frame.evaluate(gatedPickerInitScript(nlsReverseJson, true));
        } catch (Exception ex) {
            // 动态/子 frame 注入失败的两种性质需区分：
            //  (a) 真不可注入：frame 已关闭/已分离 → 永久跳过，丢弃。
            //  (b) 执行上下文竞态：子 frame 在 onFrameAttached 的 about:blank 阶段、或跨源 frame 文档切换瞬间，
            //       execution context 尚未就绪，evaluate 抛 "Execution context was destroyed" / "frame was detached"。
            //       这类是【暂时性】的——onFrameNavigated 兜底虽也会调本方法，但若导航事件与 context 就绪仍有微小错位，
            //       跨源/动态 iframe 可能在本会话内再无机会注入，表现为"iframe 内元素点不到"。
            // 优化：对 (b) 立即重试一次（同方法再 evaluate 一次）。onFrameNavigated 触发时 context 通常已就绪，
            // 首轮失败多为 about:blank 残留，重试一次即可成功，无需引入后台轮询线程。
            // 若重试仍失败，判为 (a) 真不可注入，静默跳过（用 log.debug 避免连接关闭时海量刷屏）。
            String url;
            try { url = frame.url(); } catch (Exception urlEx) { url = "<closed>"; }
            boolean detached = false;
            try { detached = frame.isDetached(); } catch (Exception ignore) {}
            if (!detached) {
                try {
                    frame.evaluate(gatedPickerInitScript(nlsReverseJson, true)); // 竞态重试一次
                    if (log.isDebugEnabled()) {
                        log.debug("[frameInjectOnce][retry-ok] 首轮竞态后重试成功 frame={}", url);
                    }
                    return;
                } catch (Exception ex2) {
                    if (log.isDebugEnabled()) {
                        log.debug("[frameInjectOnce][skip] frame={} : {}", url, ex2.toString());
                    }
                }
            } else if (log.isDebugEnabled()) {
                log.debug("[frameInjectOnce][skip-detached] frame={} : {}", url, ex.toString());
            }
        }
    }

    /**
     * 面板引导脚本：每个新文档解析早期置位面板开关并恢复页名（跨源导航时 localStorage 可能为空/不可写，
     * 用 window.__rolePanelForce 兜底强制重建），保证任意导航/弹窗后 PANEL_SCRIPT 都能通过门禁重建面板。
     */
    private static final String PANEL_BOOTSTRAP_SCRIPT =
            // 墓碑门控：context 级 addInitScript 无法撤销，会话结束（closePanel/finally 置 '0'）后
            // 引导脚本必须自行退出，否则会话结束后的任意导航都会把面板重新拉起来。
            // 仅显式 '0' 视为结束；键不存在（跨源新页首个文档）仍强制重建面板（保持会话内跨源健壮性）。
            "(function(){ try{ if(localStorage.getItem('__rolePanelEnabled')==='0') return; }catch(e){}"
            + " try{localStorage.setItem('__rolePanelEnabled','1');}catch(e){}"
            + " try{window.__rolePanelForce=true;}catch(e){}"
            + " try{var n=localStorage.getItem('__rolePageName'); if(n) window.__rolePageName=n;}catch(e){} })();"
            // ===== 唯一权威的"合并去重键" =====
            // 各处合并快照（load/pageshow 自愈、弹窗关闭回灌、导航后恢复、currentStep 补齐）过去各自手搓
            // JSON.stringify([p._sig, p._pageClass])，与元素入库时 __sigKey() 的口径【不一致】：
            // __sigKey 会优先复用已固化的 p._sigKey，而手搓版本无视它、按当前上下文重算。于是同一个元素
            // 在合并时算出的键 ≠ 入库时登记在 __rolePickSigs 里的键 → 判为"新元素"被再次 push。
            // 由于每次导航/恢复都会触发多个合并点，重复份数随操作次数递增（实测 4→5→6 次）。
            // 这里定义在【引导脚本】而非 START_SCRIPT：合并点最早在文档解析初期的门控脚本里就会执行，
            // 那时 __sigKey 尚未定义，故 __mergeKey 必须自给自足（有 __sigKey 就委托，没有就用同口径兜底）。
            + "(function(){ if (window.__mergeKey) return;"
            + " window.__mergeKey = function(p){ try{"
            + "   if (!p) return '';"
            + "   if (p._sigKey) return p._sigKey;"                      // 固化键优先——与 __sigKey 完全一致
            + "   if (typeof window.__sigKey === 'function') return window.__sigKey(p);"
            + "   var pageKey = p._pageClass || '';"
            + "   if (!pageKey) { try { pageKey = (location.origin||'') + (location.pathname||''); } catch(e){} }"
            + "   return JSON.stringify([p._sig || '', pageKey]);"
            + " }catch(e){ return ''; } }; })();";

    /**
     * {@code window.__mergeKey} 兜底定义，供 {@code page.evaluate} 合并快照前内联一次。
     *
     * <p>正常路径由 {@link #PANEL_BOOTSTRAP_SCRIPT} 在每个新文档最早期定义；但跨源导航 / 新文档尚未
     * 执行完引导脚本时 evaluate 可能先落地，此时若直接调用会抛 {@code TypeError} 使整个合并静默失败，
     * 退化回"全部当新元素追加"的重复老路。故各合并点前统一内联该幂等 shim（已定义则原样保留）。
     */
    private static final String MERGE_KEY_SHIM =
            " if (typeof window.__mergeKey !== 'function') { window.__mergeKey = function(p){ try{"
            + "   if (!p) return '';"
            + "   if (p._sigKey) return p._sigKey;"
            + "   if (typeof window.__sigKey === 'function') return window.__sigKey(p);"
            + "   var pk = p._pageClass || '';"
            + "   if (!pk) { try { pk = (location.origin||'') + (location.pathname||''); } catch(e){} }"
            + "   return JSON.stringify([p._sig || '', pk]);"
            + " }catch(e){ return ''; } }; }";

    /**
     * 门控式拾取初始化脚本（对齐 {@code page.pause()} 的 Recorder：拾取脚本经 context 注入脚本
     * 在【每个新文档】自动重跑，跨导航/弹窗/新标签页由浏览器原生保证监听重挂，无需 Java 端手动跟踪）。
     * 门控：仅当"会话拾取开关"（localStorage __rolePickSessionOn，由 start/stop 置位/清除）打开时
     * 才注入 nls 反向表并挂载 START_SCRIPT；未拾取时新文档零侵入。
     */
    private static String gatedPickerInitScript(String nlsReverseJson) {
        return gatedPickerInitScript(nlsReverseJson, false);
    }

    /**
     * 门控初始化脚本。force=true 时跳过"会话开关"判定、直接执行 START_SCRIPT（定义 __renumberStep 等全套函数），
     * 用于"运行时新附加/导航的 iframe"：这类子 frame 是独立 window，其 localStorage/window 会话标记与父页不互通
     * （file:// 下不同路径 origin 不同、execution context 随文档切换重置），若仍走 on 判定会误判为 false 而直接 return，
     * 导致 __recordPick 调用 __renumberStep 报 "is not a function"。动态 iframe 的注入由 frameInjectOnce 以 force=true 调用，
     * 确保子 frame 内的拾取依赖完整；仅影响已开始拾取会话期间注入的 frame，对无关页面无副作用。
     */
    private static String gatedPickerInitScript(String nlsReverseJson, boolean force) {
        return "(function(){"
                // 诊断回写：浏览器 console 已被吞（无 onConsoleMessage 监听），故把门控执行结果写入
                // window.__gateInit，供 Java 在 onFrameNavigated / start 后回读，定位"刷新后拾取不了"。
                + " var __gi = { ts: Date.now(), url: location.href, origin: location.origin };"
                + " var __force = " + (force ? "true" : "false") + ";"
                + " var on=false;"
                + " try{ on = localStorage.getItem('__rolePickSessionOn')==='1'; }catch(e){ __gi.lsErr = String(e); }"
                + " try{ if(!on) on = !!window.__rolePickSessionOn; }catch(e){}"
                + " if(__force) on = true;"
                + " __gi.switchOn = on;"
                // 无条件定义"开启监听"入口（即便本次文档早期会话开关尚未置位）：仅定义、不调用，
                // 所有调用处（__roleReenable / __roleSpaHeal / start）均带会话开关自检，不会误开启拾取。
                // 提前定义可保证"文档早期会话未开、之后才点开始"的场景下，刷新/跳转/SPA 自愈仍能复用同一入口。
                + " window.__roleGatedStart = function(){ " + START_SCRIPT + " };"
                // ===== 同页 URL 变更（SPA 路由切换）自愈：修复"同页 url 变化后已拾元素消失 / 拾取不了" =====
                // 无条件注册（即便本次文档早期会话未开启）：函数体自带会话开关自检，会话未开时整体 no-op，安全。
                // pushState/replaceState/popstate/hashchange 不会触发 load/pageshow/onFrameNavigated，
                // 框架重渲染 document 子树往往静默移除 document 级点击监听、甚至把 docked 面板 DOM（#__rolePanel）冲掉，
                // 表现为"同页路由切换后，之前抓取的元素看不见、点了也没反应"。
                // 故在此类事件上二次自检：开关仍在则重挂监听；面板 DOM 若被冲掉则重建；并立即重渲染已拾元素。
                // 已拾元素存于 window.__rolePicks（按本标签页累积的展示数组，不随 DOM 重建丢失），重渲染即可恢复显示。
                + " function __roleSpaHeal(){ try {"
                + "   var on2=false; try{ on2 = localStorage.getItem('__rolePickSessionOn')==='1'; }catch(e){}"
                + "   try{ if(!on2) on2 = !!window.__rolePickSessionOn; }catch(e){}"
                + "   if (on2 && typeof window.__roleGatedStart === 'function') { try{ window.__roleGatedStart(); }catch(e){} }"
                + "   try { if (!document.getElementById('__rolePanel') && typeof window.__roleEnsurePanel === 'function') window.__roleEnsurePanel(); } catch(e){}"
                + "   try { if (window.__renderPicks) window.__renderPicks(); } catch(e){}"
                + " } catch(e){} }"
                + " (function(){ try {"
                + "   var __ps = history.pushState, __rs = history.replaceState;"
                + "   history.pushState = function(){ try{ __ps.apply(history, arguments); }catch(e){} __roleSpaHeal(); };"
                + "   history.replaceState = function(){ try{ __rs.apply(history, arguments); }catch(e){} __roleSpaHeal(); };"
                + " } catch(e){} })();"
                + " window.addEventListener('popstate', __roleSpaHeal);"
                + " window.addEventListener('hashchange', __roleSpaHeal);"
                // Ultimate fallback: some SPA/micro-frontend frameworks rebuild the document subtree via innerHTML,
                // neither using pushState nor hashchange. Relying only on the three listeners above would miss such
                // cases and let the panel DOM (#__rolePanel) be silently wiped without triggering self-heal.
                // Use a MutationObserver to self-heal when #__rolePanel disappears; a "phase flag + debounce" prevents
                + " (function(){ try {"
                + "   var __healing=false, __healT=null;"
                + "   var __mo = new MutationObserver(function(){"
                + "     if(__healing) return;"
                + "     if(!document.getElementById('__rolePanel')){"
                + "       if(__healT) clearTimeout(__healT);"
                + "       __healT = setTimeout(function(){ try{ __roleSpaHeal(); }catch(e){} }, 50);"
                + "     }"
                + "   });"
                + "   var __origHeal = __roleSpaHeal;"
                + "   __roleSpaHeal = function(){ __healing=true; try{ __origHeal(); }catch(e){} __healing=false; };"
                + "   __mo.observe(document.documentElement, { childList:true, subtree:true });"
                + " } catch(e){} })();"
                + " if(!on){ window.__gateInit = __gi; return; }"
                // 关键加固：nls 反查表改为「JSON 字符串字面量内联 + JSON.parse」：
                // 用 GSON.toJson 把 nls JSON 文本再包一层引号转义为合法的 JS 字符串字面量，
                // 无论 nls 内容含何种特殊字符都不会破坏脚本语法；即便解析失败也被 catch 降级为 {}。
                // （addInitScript 不支持传参，无法用 arguments[0]，故采用内联字符串方案。）
                + " var __nlsArg = " + (nlsReverseJson == null ? "\"\"" : GSON.toJson(nlsReverseJson)) + ";"
                + " var __o; try { __o = (__nlsArg && typeof __nlsArg === 'string') ? JSON.parse(__nlsArg) : (__nlsArg || {}); } catch(e){ __o = {}; __gi.nlsErr = String(e); }"
                + " window.__nlsReverse = (__o && __o.exact) ? __o.exact : (__o && __o.templates ? {} : (__o || {}));"
                + " window.__nlsTemplates = (__o && __o.templates) ? __o.templates : [];"
                // 记忆体开关兜底：即便跨源/localStorage 不可用，浏览器侧也持有本会话开启态，
                // 供 onFrameNavigated 的会话开关自检（读 window.__rolePickSessionOn）与 load/pageshow 自检使用。
                + " try{ window.__rolePickSessionOn = true; }catch(e){}"
                // start 时复位显式停止标志（被 stop 置 true 后，重新开始时恢复自愈能力，
                // 否则 __roleReenable 会因 stopped=true 永久拒绝自启，导致"停止后再开始拾取不了"）。
                + " try{ window.__rolePickStopped = false; }catch(e){}"
                + " window.__roleGatedStart();"
                // ===== 浏览器侧自愈（核心修复"刷新/跳转后拾取不了"）=====
                // 仅靠文档早期 addInitScript 不可靠：现代 SPA/微前端框架可能在初始化阶段重建 document 子树、
                // 或浏览器以 bfcache 前进/后退/刷新恢复旧文档（addInitScript 不重跑）、或导航瞬间执行上下文竞态，
                // 都可能让文档早期的监听没"粘住"。故在 load 与 pageshow 两个全文档就绪时机二次自检：
                //   开关仍在 且（点击监听缺失 或 未激活）→ 重新执行 START_SCRIPT（幂等：已激活则早退仅保活）。
                // 该机制完全不依赖 Java 侧 onFrameNavigated / ensurePickingActive 的时序，从根上保证"页面怎么变都能拾取"。
                + " function __roleReenable(){ try {"
                + "   if (window.__rolePickStopped === true) return;"   // 显式停止后绝不自愈复活（修复"停止不了"）
                + "   var on2=false; try{ on2 = localStorage.getItem('__rolePickSessionOn')==='1'; }catch(e){}"
                + "   try{ if(!on2) on2 = !!window.__rolePickSessionOn; }catch(e){}"
                + "   if (!on2) return;"
                + "   try{ window.__rolePickSessionOn = true; }catch(e){}"
                + "   if (typeof window.__roleGatedStart === 'function') window.__roleGatedStart();"
                + "   try {"
                // 【修复"跨会话脏序号污染本轮（user_name 拿到 2 而非 4/5）"】
                // 旧实现会从 localStorage['__rolePickState'] 把上一轮残留的 picks（含 user_name:[2]）重新 push 回 __rolePicks，
                // 即使上面已 window.__rolePicks=[] 清空，这里仍把脏数据恢复回来 → dup 分支命中 → 回传脏首号 2。
                // Java 端 javaPickBySig 每次 openPanel 都从空开始，浏览器端也须从空起步，故 start 时彻底不恢复 localStorage 残留。
                + "     try{ localStorage.removeItem('__rolePickState'); }catch(e){}"
                + "     if (false) {"
                + "       var s = JSON.parse('{}');"
                + "       window.__rolePicks = [];"
                + "       window.__rolePickSigs = {};"
                // 去重键必须走权威函数 __mergeKey（内部优先复用已固化的 p._sigKey）。
                // 曾在此处手搓 JSON.stringify([p._sig, p._pageClass])，与入库时 __sigKey() 的口径不一致：
                // 已固化 _sigKey 的元素在这里被重算出另一个键 → 每次导航/恢复合并都判为"新元素"而追加，
                // 是"同组元素重复 4/5/6 次且越扫越多"的真正根因。
                + "       (s.picks||[]).forEach(function(p){"
                + "         var k = window.__mergeKey(p);"
                + "         if (k && window.__rolePickSigs[k]) return;"
                + "         if (k) window.__rolePickSigs[k] = true; window.__rolePicks.push(p); });"
                + "       window.__currentStep = window.__currentStep || [];"
                + "       var __cs = {}; window.__currentStep.forEach(function(p){ var k=window.__mergeKey(p); if(k) __cs[k]=true; });"
                + "       (s.currentStep||[]).forEach(function(p){ var k=window.__mergeKey(p); if(k&&__cs[k])return; if(k)__cs[k]=true; window.__currentStep.push(p); });"
                + "     }"
                + "   } catch(e){}"
                + "   try { if (window.__renderPicks) window.__renderPicks(); } catch(e){}"
                + " } catch(e){} }"
                + " window.addEventListener('load', __roleReenable);"
                + " window.addEventListener('pageshow', __roleReenable);"
                // 关键修复：已加载的子 frame（如 start() 前就完成导航的 iframe）不会再触发 load/pageshow，
                // 若仅依赖上面两个监听，门控脚本永不执行 → 该 iframe 内无拾取监听、postMessage 上送不到顶层。
                // 故若文档当前已处于 readyState!=='loading'（即已加载完成），立即执行一次 __roleReenable 完成挂载；
                // 尚未加载的文档仍走 load/pageshow 自愈路径，二者不冲突。
                + " try{ if (document.readyState !== 'loading') { window.__roleReenable(); } }catch(e){}"
                + " __gi.injected = true;"
                + " __gi.activeAfter = !!window.__rolePickActive;"
                + " __gi.hasClick = typeof window.__rolePickClick === 'function';"
                + " __gi.hasMove = typeof window.__rolePickMove === 'function';"
                + " __gi.hasKey = typeof window.__rolePickKey === 'function';"
                + " window.__gateInit = __gi;"
                + "})();";
    }

    /**
     * 把"面板重建 + 门控拾取"初始化脚本一次性注册到 {@link BrowserContext}：
     * context 下每个页面、每次导航、每个新文档都会自动执行（Playwright 原生保证），
     * 从根上替代"onFrameNavigated 手动重挂 / 逐页 addInitScript / 自愈循环"三套兜底机器。
     * 面板脚本每 context 仅注册一次；拾取脚本按 nls 内容幂等（同 nls 跳过，变化则追加，后注册者后执行覆盖生效）。
     */
    private static void registerContextInitScripts(BrowserContext ctx, String nlsReverseJson) {
        if (CTX_PANEL_SCRIPTED.add(ctx)) {
            ctx.addInitScript(PANEL_BOOTSTRAP_SCRIPT);
            ctx.addInitScript(PANEL_SCRIPT);
        }
        String nls = (nlsReverseJson == null || nlsReverseJson.isEmpty()) ? "{}" : nlsReverseJson;
        if (!nls.equals(CTX_PICKER_NLS.get(ctx))) {
            ctx.addInitScript(gatedPickerInitScript(nls));
            CTX_PICKER_NLS.put(ctx, nls);
        }
    }

    /**
     * 抽干浏览器端兜底命令队列 window.__panelCmds 并入 Java 命令队列 cmdQueue。
     * 当 exposeFunction 绑定（window.__rolePickerCmd）尚未就绪时，面板按钮的 pushCmd 会把命令
     * 暂存进 window.__panelCmds（见 PANEL_SCRIPT）。若不消费，这些命令会静默丢失，导致"点了开始却
     * 拾取不了"。此处由 Java 主循环周期性抽干，保证命令零丢失。与 exposeFunction 投递幂等、不会重复。
     */
    private static void drainPanelCmds(Page page, BlockingQueue<CmdEvent> cmdQueue) {
        if (page == null || page.isClosed() || cmdQueue == null) return;
        try {
            Object raw = page.evaluate("(function(){"
                    + " try { var a = window.__panelCmds || []; window.__panelCmds = []; return a; }"
                    + " catch(e){ return []; } })()");
            if (raw instanceof List) {
                for (Object o : (List<?>) raw) {
                    String c = o == null ? null : o.toString();
                    cmdQueue.offer(new CmdEvent(page, c));
                }
            }
        } catch (Exception ignore) {}
    }





    /**
     * 判断是否处于 CI 运行环境（如 Jenkins / GitHub Actions / GitLab CI / TeamCity）。
     * 拾取面板是本地开发辅助工具，会在 {@link #openPanel}/{@link #start} 内等待人工拾取，
     * 在 CI 自动化中不应打开（否则测试会卡住等待人工交互）。通过常见 CI 环境变量判断。
     */
    private static boolean isCiRun() {
        return System.getenv("JENKINS_URL") != null
                || System.getenv("WORKSPACE") != null
                || System.getenv("BUILD_NUMBER") != null
                || "true".equalsIgnoreCase(System.getenv("CI"))
                || System.getenv("GITLAB_CI") != null
                || System.getenv("GITHUB_ACTIONS") != null
                || System.getenv("TEAMCITY_VERSION") != null;
    }

    /** 用于把参数安全序列化为 JS 字面量（避免手动拼接转义错误） */
    private static final Gson GSON = new Gson();

    // O2：syncPanelToBrowser 的 ETag 缓存——按 Page 记录上次同步的内容签名，未变则跳过整轮同步。
    private static final java.util.Map<Page, String> LAST_SYNC_SIG =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** 反序列化任意拾取态 JSON 的精确泛型类型，避免 {@code fromJson(x, Map.class)} 的未检查转换 */
    private static final java.lang.reflect.Type MAP_STRING_OBJECT_TYPE =
            new TypeToken<java.util.Map<String, Object>>() {}.getType();

    /** 开启拾取模式：注入监听 + 顶部提示条 */
    private static final String START_SCRIPT_A = loadScript("picker-core-a.js");
    private static final String START_SCRIPT_B1 = loadScript("picker-core-b1.js");
    private static final String START_SCRIPT_B2 = loadScript("picker-core-b2.js");
    private static final String START_SCRIPT = concat(concat(START_SCRIPT_A, START_SCRIPT_B1), START_SCRIPT_B2);

    /** 运行时拼接，避免 javac 将 START_SCRIPT_A + START_SCRIPT_B 折叠为单一超长常量（越过 65535 字节上限）。 */
    private static String concat(String a, String b) {
        return a + b;
    }

    /**
     * 从 classpath 资源（src/main/resources/scan/js/ 下）读取浏览器注入脚本。
     * 将原本内联为 Java 文本块的超长 JS 外置为独立 .js 文件，
     * 从根上规避 .class 常量池单条 UTF8 不得超过 65535 字节的硬限制；
     * 同时让 JS 与 Java 解耦，便于独立维护。
     */
    private static String loadScript(String fileName) {
        String path = "/scan/js/" + fileName;
        try (java.io.InputStream in = RoleElementPicker.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("缺失拾取器脚本资源: " + path);
            }
            try (java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
                byte[] buf = new byte[8192];
                int r;
                while ((r = in.read(buf)) != -1) out.write(buf, 0, r);
                return new String(out.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
            }
        } catch (java.io.IOException e) {
            throw new IllegalStateException("读取拾取器脚本资源失败: " + path, e);
        }
    }

    /** 关闭拾取模式：移除监听 + 收尾当前 step + 移除提示条 */
    private static final String STOP_SCRIPT = loadScript("picker-stop.js");

    /** 弹出可复制代码面板（参数 code 为生成的源码） */
    private static final String SHOW_PANEL_SCRIPT = loadScript("panel-show.js");

    /**
     * 注入式常驻面板（docked 在页面右侧，像 DevTools 那样占住窗口一侧、把页面内容挤到左边，
     * 不另开窗口、不盖内容）。由 {@link #openPanel} 通过 addInitScript 注入并随导航重建；
     * 命令通过 {@code window.__panelCmds} 入列，由 Java 主循环轮询消费。
     */
    private static final String PANEL_SCRIPT_A = loadScript("panel-core-a.js");
    private static final String PANEL_SCRIPT_B = loadScript("panel-core-b.js");
    private static final String PANEL_SCRIPT = concat(PANEL_SCRIPT_A, PANEL_SCRIPT_B);

    /** 拾取命令循环的统一返回：动作 + 按页生成的代码 + 状态文案 */
    private enum PickerAction { CONTINUE, ABORT, DONE }
    private static final class PickerResult {
        final PickerAction action;
        final LinkedHashMap<String, String> pageClassByPage;   // 页面类：pageClass → 源码
        final LinkedHashMap<String, String> stepByPage;         // 步骤代码：pageClass → 源码视图
        final String statusMsg;
        PickerResult(PickerAction action, LinkedHashMap<String, String> pageClassByPage,
                     LinkedHashMap<String, String> stepByPage, String statusMsg) {
            this.action = action;
            this.pageClassByPage = pageClassByPage;
            this.stepByPage = stepByPage;
            this.statusMsg = statusMsg;
        }
        /** 合并所有页面的页面类代码（保留旧消费点 / localStorage 兼容） */
        String getCodePage() {
            if (pageClassByPage == null || pageClassByPage.isEmpty()) return "";
            StringBuilder sb = new StringBuilder();
            boolean first = true;
            for (Map.Entry<String, String> e : pageClassByPage.entrySet()) {
                if (!first) sb.append("\n\n");
                sb.append("// ===================== ").append(e.getKey()).append(" =====================\n").append(e.getValue());
                first = false;
            }
            return sb.toString();
        }
        /** 合并所有页面的步骤代码（保留旧消费点 / localStorage 兼容） */
        String getCodeStep() {
            if (stepByPage == null || stepByPage.isEmpty()) return "";
            StringBuilder sb = new StringBuilder();
            boolean first = true;
            for (Map.Entry<String, String> e : stepByPage.entrySet()) {
                if (!first) sb.append("\n\n");
                sb.append("// ===================== ").append(e.getKey()).append(" =====================\n").append(e.getValue());
                first = false;
            }
            return sb.toString();
        }
    }

    /**
     * 统一处理面板命令（start/stop/abort/done）：开始拾取、收集并生成代码、终止、关闭。
     * 结果由调用方用 {@code setStatus/fillCode} 呈现到面板 UI。
     *
     * @return 含后续动作与（stop 时的）生成代码
     */
    private static PickerResult runPickerCommand(Page page, String cmd,
                                                  String packageName, String pageClassName,
                                                  String stepClassName,
                                                  LinkedHashMap<Page, String> pageNames,
                                                  LinkedHashMap<Page, String> snapshots,
                                                  String[] nlsFiles, boolean[] active,
                                                  LinkedHashMap<String, RoleEntry> javaPickBySig) {
        // 【删除单个拾取序号 + 全局重编号】浏览器侧面板点击某序号超链接时，经 __rolePickerCmd 投递
        // JSON 命令 {type:'repickNos', mergeKey, nos:[...]}：把该元素在 Java 权威内存态中的 pickNos
        // 更新为浏览器侧重排后的新序号数组（步骤生成依赖 pickNos 按号展开，故 Java 必须同步）。
        // 纯字符串命令（start/scan/...）走下方 switch；JSON 命令在此先拦截处理。
        if (cmd != null && cmd.trim().startsWith("{")) {
            try {
                Map<String, Object> jc = GSON.fromJson(cmd, Map.class);
                Object t = jc == null ? null : jc.get("type");
                String type = t == null ? null : t.toString();
                if ("repickNos".equals(type)) {
                    String mk = asString(jc.get("mergeKey"));
                    Object nosObj = jc.get("nos");
                    List<Integer> nos = new ArrayList<>();
                    if (nosObj instanceof List) {
                        for (Object o : (List<?>) nosObj) {
                            if (o instanceof Number) nos.add(((Number) o).intValue());
                        }
                    }
                    if (mk != null && !mk.isEmpty()) {
                        synchronized (javaPickBySig) {
                            for (RoleEntry e : javaPickBySig.values()) {
                                if (mk.equals(e.getSigKey())) {
                                    e.setPickNos(nos);
                                    log.info("[picker] repickNos 同步内存态：sigKey={} → nos={}", mk, nos);
                                    break;
                                }
                            }
                        }
                    }
                    // 重编号后把最新内存态回灌浏览器面板，保证面板/快照/Java 三侧序号一致。
                    // 强制刷新 ETag：repickNos 只改序号、元素身份未变，若不清除 LAST_SYNC_SIG，
                    // syncPanelToBrowser 的签名短路会跳过回灌，导致面板序号不刷新（被旧值覆盖）。
                    try { LAST_SYNC_SIG.remove(page); } catch (Exception ignore) {}
                    try { if (!page.isClosed()) syncPanelToBrowser(page, null, javaPickBySig, true); } catch (Exception ignore) {}
                    // 【diag-repick】sync 后回读浏览器侧 __rolePicks 的实际 _pickNos，确认回灌生效（而非旧值残留）。
                    try {
                        @SuppressWarnings("unchecked")
                        List<?> rp = (List<?>) page.evaluate("() => (window.__rolePicks||[]).map(function(p){ return {k:(p._sigKey||p._sig||''), n:(p._pickNos||null)}; })");
                        log.info("[picker][diag-repick] 回灌后浏览器侧 __rolePicks: {}", rp);
                    } catch (Exception ignoreR) {}
                    return new PickerResult(PickerAction.CONTINUE, null, null,
                            "已删除拾取序号并重排（" + (nos == null ? 0 : nos.size()) + " 个序号）");
                }
            } catch (Exception ex) {
                log.warn("[picker] repickNos 命令解析失败：{}", ex.getMessage());
            }
        }
        switch (cmd) {
            case "start":
                // 多实例：会话级"开始"作用于所有已打开页面，使各页面板同步显示 ⏹ 停止
                // （active[0] 是会话权威开关，followPage / onFrameNavigated 据此决定是否重启监听）。
                active[0] = true;
                // 进入手动拾取模式（互斥：此时整页/区域扫描按钮禁用，点击页面只拾取被点元素）。
                setPickMode(pageNames.keySet().iterator().next(), PickMode.MANUAL, pageNames);
                // 反向查表只构建一次（避免对每个被跟踪页面重复读 nls 文件），减少点击"开始"的延迟。
                String startNls = buildNlsReverseJson(Arrays.asList(nlsFiles));
                for (Page p : pageNames.keySet()) {
                    if (!p.isClosed()) { log.info("[picker][start] 对页面 {} 调用 start", p.url()); start(p, startNls); }
                }
                // 注意：开始拾取不做自动避开导航——用户有时也需要拾取 leftmenu/topbar 等全局区域。
                // start(page, nls) 的 root 为 null（整页），点击拾取即整页可点；仅当用户主动用"区域扫描"
                // 点选了某块业务区后，window.__rolePickRoot 才被限定到该容器（区域扫描专属语义）。
                return new PickerResult(PickerAction.CONTINUE, null, null,
                        "RoleElement Picker：点击元素拾取 role/name，按 ESC 结束");
            case "scan": {
                // 整页 role 树扫描：先复用 start 注入拾取库（定义 __roleScanPage/__recordPick），
                // 再对命令来源页运行 window.__roleScanPage()——把整页所有"带可访问名的语义角色元素"
                // 经 __recordPick 记录，与点击拾取同一链路（去重 / 面板渲染 / __roleOnPick 回传 javaPickBySig）。
                // 用户随后点 ⏹ 停止即从 javaPickBySig 生成代码（无需为扫描单独实现生成逻辑）。
                active[0] = true;
                // 进入整页扫描模式（互斥：扫描期间禁用开始/区域扫描按钮）。
                setPickMode(pageNames.keySet().iterator().next(), PickMode.SCAN_PAGE, pageNames);
                String scanNls = buildNlsReverseJson(Arrays.asList(nlsFiles));
                if (!page.isClosed()) start(page, scanNls);
                int added = -1;
                try {
                    // 【关键修复"整页扫描未穿透 iframe"】
                    // 旧实现只对主页面 frame 执行 __roleScanPage()，其内部虽有"遍历 els 遇到 iframe 元素
                    // 递归进入 contentWindow 扫描"的逻辑，但 file:// 下主 frame 的 JS 访问 iframe 的
                    // contentWindow.document 会抛跨源 SecurityError（origin=null），递归被 catch 吞掉、
                    // iframe 内元素一个都扫不到（实测 frameOne/frameTwo 的 __rolePicks 恒为 0）。
                    // 修复：由 Java 侧对 page.frames() 的【每个 frame】分别执行其 own __roleScanPage(null)
                    // （Playwright 的 frame.evaluate 走协议层，不受浏览器同源策略限制，能访问任意层 iframe
                    // 的 __roleScanPage），并在该 frame 上下文扫描、回传，与手动点选同链路。
                    added = 0;
                    for (com.microsoft.playwright.Frame f : page.frames()) {
                        if (f == null) continue;
                        try {
                            Object r = f.evaluate(
                                    "(function(){ try { return (typeof window.__roleScanPage==='function') ? window.__roleScanPage(null) : -1; } catch(e){ return -1; } })()");
                            // 防御性兜底：跨源/动态 iframe 若因注入竞态漏注入（__roleScanPage 未定义，返回 -1），
                            // 此处先强制补注入一次再扫描，确保任意层 iframe（含跨源）都能被整页扫描穿透。
                            if (r instanceof Number && ((Number) r).intValue() < 0) {
                                try {
                                    frameInjectOnce(f, scanNls);
                                    r = f.evaluate(
                                            "(function(){ try { return (typeof window.__roleScanPage==='function') ? window.__roleScanPage(null) : -1; } catch(e){ return -1; } })()");
                                } catch (Exception reInjEx) {
                                    if (log.isDebugEnabled()) log.debug("[picker][scan] iframe 补注入失败（url={}）：{}", f.url(), reInjEx.getMessage());
                                }
                            }
                            if (r instanceof Number) {
                                int n = ((Number) r).intValue();
                                if (n > 0) added += n;
                            }
                        } catch (Exception fe) {
                            log.warn("[picker][scan] frame 扫描失败（url={}）：{}", f.url(), fe.getMessage());
                        }
                    }
                    // 主框架扫描已在上面 frame 循环中覆盖（page.mainFrame() 也在 page.frames() 内）。
                    // 跨 frame 扫描结果经各自 console 兜底回传 Java；此处再触发一次 Java 内存态同步/快照
                    // 合并，确保 iframe 内回传的 pick 也能进入权威内存态。
                } catch (Exception e) {
                    log.warn("[picker][scan] 整页扫描执行失败：{}", e.getMessage());
                }
                log.info("[picker][scan] 整页扫描完成：新增 {} 个语义角色元素", added);
                // 【关键修复"页面元素只有主框架元素"】
                // 扫描出的 iframe 元素 push 进各自 iframe 的 __rolePicks（面板渲染的是主框架 __rolePicks，
                // 看不到 iframe 的；postMessage 上送顶层又受 __rolePanelUI 门禁/监听时机影响不可靠）。
                // 修复：由 Java 侧遍历 page.frames() 读回各 iframe 的 __rolePicks（Playwright 协议访问
                // 不受 file:// 跨源限制），显式合并进【主框架】window.__rolePicks 并触发渲染——
                // 使扫描后主框架 __rolePicks 立即包含全部 iframe 元素，面板"页面元素"Tab 与即时生成的
                // 页面类都显示完整数量（Java 内存态 40 个 → 面板也 40 个，而非仅主框架 32 个）。
                try {
                    mergeFramePicksToMain(page, javaPickBySig);
                    // 再把权威内存态强制回灌主框架（pageClasses=null 同步全部），双保险。
                    if (!page.isClosed() && !javaPickBySig.isEmpty()) {
                        syncPanelToBrowser(page, null, javaPickBySig, false);
                    }
                } catch (Exception syncE) {
                    log.warn("[picker][scan] 扫描后同步 iframe 元素到面板失败：{}", syncE.getMessage());
                }
                // 扫描完成后【立即生成页面类代码】，无需等到点 ⏹ 停止：直接同步读取浏览器侧
                // window.__rolePicks（readPickSnapshot 走 page.evaluate，比依赖异步的 __roleOnPick 回传更可靠），
                // 按 pageClass 分组生成页面类并填入"页面类"Tab，同时自动切到该 Tab 让用户即时看到。
                // 此后用户仍可继续勾选元素「封装为步骤」、或点 ⏹ 停止重新生成（含步骤代码）。
                try {
                    PickSnapshot snap = readPickSnapshot(page);
                    if (snap != null && !snap.entries.isEmpty()) {
                        LinkedHashMap<String, String> codePage = buildPageClassCode(snap.entries, packageName, pageClassName, nlsFiles);
                        if (codePage != null && !codePage.isEmpty()) {
                            // 扫描完成自动回 IDLE：面板按钮复位为"▶ 开始拾取"，页面点击不再拾取。
                            setPickMode(pageNames.keySet().iterator().next(), PickMode.IDLE, pageNames);
                            return new PickerResult(PickerAction.CONTINUE, codePage, null,
                                    "整页扫描完成，已生成页面类（" + snap.entries.size() + " 个字段，" + added
                                            + " 个新增），可继续勾选元素封装步骤，或点 ⏹ 停止生成步骤代码");
                        }
                    }
                } catch (Exception e) {
                    log.warn("[picker][scan] 扫描后即时生成页面类失败：{}", e.getMessage());
                }
                // 扫描完成（无论是否生成页面类）自动回 IDLE。
                setPickMode(pageNames.keySet().iterator().next(), PickMode.IDLE, pageNames);
                return new PickerResult(PickerAction.CONTINUE, null, null,
                        added >= 0
                                ? ("整页扫描：新增 " + added + " 个语义角色元素，点 ⏹ 停止生成代码")
                                : "整页扫描失败（拾取库未就绪，请重试）");
            }
            case "scanRegion": {
                // 区域扫描：点按钮后进入「点选区域」态（__roleStartRegionSelect）：用户点击业务区域内的任意位置，
                // 框架收敛到该业务容器并只扫描这块（避开 leftmenu/topbar 等）；若用户想整页，可在区域扫描后
                // 再点「扫描整页」按钮。点选是浏览器侧异步交互，Java 仅触发并返回提示，结果由浏览器侧回传。
                active[0] = true;
                // 进入区域扫描模式（互斥：扫描期间禁用开始/整页扫描按钮）。
                setPickMode(pageNames.keySet().iterator().next(), PickMode.SCAN_REGION, pageNames);
                String regionNls = buildNlsReverseJson(Arrays.asList(nlsFiles));
                if (!page.isClosed()) start(page, regionNls);
                // 【修复"整页扫描 → 停止拾取 → 区域选择，整页扫描元素被清空"】
                // 原实现为让"区域扫描结果 = 纯本次选中区域元素"，进入区域点选态前清空了三处：
                //   ① 所有 frame 的 __rolePicks/__rolePickSigs
                //   ② Java 权威内存态 javaPickBySig
                //   ③ STATE_DELETED 已删集合
                // 这导致：先整页扫描、再区域选择时，整页扫描的元素被整体清空，无法与区域扫描结果叠加。
                // 现按"整页扫描 + 区域扫描互补补充"的期望移除全部清空：区域扫描 __roleScanPage 本身是
                // 【追加】语义（__scanAdded 记录本次新增并 push 进 __rolePicks），保留已有拾取集即可实现
                // 叠加。同时保留 __rolePickSigs（去重）防重复、保留 STATE_DELETED（已删屏蔽）防已删元素复活。
                try {
                    page.evaluate("(function(){ try { if(typeof window.__roleStartRegionSelect==='function'){ window.__roleStartRegionSelect(); return true; } } catch(e){} return false; })()");
                } catch (Exception e) {
                    log.warn("[picker][scanRegion] 启动区域点选失败：{}", e.getMessage());
                    return new PickerResult(PickerAction.CONTINUE, null, null,
                            "区域扫描启动失败（拾取库未就绪，请重试）");
                }
                return new PickerResult(PickerAction.CONTINUE, null, null,
                        "已开启区域选择：鼠标移入业务区域即聚焦，点击区域即扫描并展示该区域内元素；按 Esc 结束选区");
            }
            case "regionScanned": {
                // 区域扫描点击后由浏览器侧异步通知（window.__rolePickerCmd('regionScanned')）：此时用户点选的
                // 业务区域元素已同步进入 window.__rolePicks，这里与"整页扫描"一样读取快照并生成页面类，
                // 填充"页面类"Tab 并切到该 Tab，使用户即时看到——否则区域扫描只会收集元素、却从不会生成页面类。
                // 每次点选区域都会重算，便于在多个区域间累加后逐步更新页面类。
                // 【关键修复"区域扫描未穿透 iframe"】
                // 区域扫描的 iframe 内元素（postMessage 穿透）进入【各自 iframe】的 __rolePicks，而
                // readPickSnapshot 读的是【主框架】__rolePicks —— 不合并的话页面类/面板只有主框架区域元素，
                // iframe 内元素一个都看不到（表象：区域扫描"穿透不了 iframe"）。
                // 修复：与整页扫描一致，先由 Java 侧把各 iframe 的 __rolePicks 合并进主框架再读快照。
                //
                // 【关键修复"区域扫描跨源 iframe 穿透失败（file:// 下 postMessage 不投递）"】
                // 区域扫描穿透 iframe 的浏览器侧链路是主框架 __roleScanPage 遍历到 iframe 元素时对
                // contentWindow 发 postMessage({__roleScanRequest:true}) 通知 iframe 自扫。但 Chromium 对
                // file:// 不同文件的 iframe 视为跨源，未加 --allow-file-access-from-files 时该 postMessage
                // 无法触发 iframe 内 message 监听（实测 frameOne/frameTwo.__rolePicks 恒为 0），iframe 内
                // 语义元素一个都进不了 iframe.__rolePicks → 合并/面板/页面类全缺 → 表象"区域扫描穿透不了
                // frame"。整页扫描早已改用【Java 侧遍历 page.frames() 逐帧执行 __roleScanPage(null)】
                // （Playwright 协议访问不受浏览器同源策略限制），区域扫描也应走这条可靠路径。
                // 此处：先读回主框架"选中区域根内的 iframe"（按宿主元素 src/name 标记），再对 page.frames()
                // 中匹配这些标记的 frame 执行其 own __roleScanPage(null)，最后合并进主框架。既保证穿透，
                // 又避免把"未选中区域里的 iframe"整块扫出来（回归"区域选非 frame 区域却把 frame 扫出来"）。
                try {
                    // 读回主框架选中区域根内包含的 iframe 标识（src 文件路径 + name/id）
                    java.util.Set<String> regionFrameUrls = new java.util.HashSet<>();
                    java.util.Set<String> regionFrameNames = new java.util.HashSet<>();
                    try {
                        Object marks = page.evaluate("() => {"
                                + " var out = { urls:[], names:[] };"
                                + " var roots = window.__regionSelected || [];"
                                + " for (var i=0;i<roots.length;i++){"
                                + "   var r = roots[i]; if (!r || !r.querySelectorAll) continue;"
                                + "   var fs = r.querySelectorAll('iframe, frame');"
                                + "   for (var j=0;j<fs.length;j++){"
                                + "     var el = fs[j];"
                                + "     var src = el.getAttribute && el.getAttribute('src');"
                                + "     if (src) out.urls.push(src);"
                                + "     if (el.name) out.names.push(el.name);"
                                + "     if (el.id) out.names.push(el.id);"
                                + "   }"
                                + " }"
                                + " return out;"
                                + "}");
                        if (marks instanceof java.util.Map) {
                            Object u = ((java.util.Map<?, ?>) marks).get("urls");
                            if (u instanceof java.util.List) {
                                for (Object o : (java.util.List<?>) u) if (o != null) regionFrameUrls.add(o.toString());
                            }
                            Object n = ((java.util.Map<?, ?>) marks).get("names");
                            if (n instanceof java.util.List) {
                                for (Object o : (java.util.List<?>) n) if (o != null) regionFrameNames.add(o.toString());
                            }
                        }
                    } catch (Exception ig) {}
                    // 对"选中根内 iframe"逐个执行其 own __roleScanPage(null)（Playwright 协议穿透跨源）。
                    // 【关键修复"嵌套 iframe 没扫出来"】
                    // 选中根内 iframe 的标记（regionFrameUrls/Names）来自【主框架】querySelectorAll('iframe,frame')，
                    // 只能拿到直接 iframe（如 frameOne），拿不到嵌套在最深层 iframe 里的 frame（如 frameTwo——
                    // 其宿主 <iframe name="frameTwo"> 在 frameOne 的 document 里，不在主框架 DOM）。若只按
                    // 直接匹配扫描，frameTwo 永远不被执行 __roleScanPage，且 frameOne 自扫时对内部 frameTwo
                    // 的 postMessage 在 file:// 跨源下不投递 → 嵌套 iframe 一个都扫不到。
                    // 修复：判定"某 frame 在选中区域内"改为「它自身或其【任意祖先 frame】宿主匹配选中区域的
                    // iframe 标记」——即一旦 frameOne 在区域内，其内部所有嵌套 iframe（frameTwo 等）都应被
                    // 一并扫描，保证嵌套链路完整穿透。
                    if (!page.isClosed()) {
                        final java.util.Set<String> rUrls = regionFrameUrls;
                        final java.util.Set<String> rNames = regionFrameNames;
                        for (com.microsoft.playwright.Frame f : page.frames()) {
                            if (f == null || f.equals(page.mainFrame())) continue;
                            // 沿父 frame 链向上判断是否落在选中区域内（自身或任意祖先命中即算在区域内）
                            boolean inRegion = false;
                            com.microsoft.playwright.Frame cur = f;
                            while (cur != null && !inRegion) {
                                String cUrl = null, cName = null;
                                try { cUrl = cur.url(); } catch (Exception ignore) {}
                                try { cName = cur.name(); } catch (Exception ignore) {}
                                if (rNames.contains(cName)) inRegion = true;
                                if (!inRegion && cUrl != null) {
                                    for (String mark : rUrls) {
                                        if (mark != null && mark.length() > 0 && cUrl.contains(lastPathSegment(mark))) {
                                            inRegion = true; break;
                                        }
                                    }
                                    if (!inRegion) {
                                        for (String nm : rNames) {
                                            if (nm != null && nm.length() > 0 && cUrl.contains(nm)) { inRegion = true; break; }
                                        }
                                    }
                                }
                                try { cur = cur.parentFrame(); } catch (Exception ignore) { cur = null; }
                            }
                            if (!inRegion) continue;
                            try {
                                Object rf = f.evaluate("(function(){ try { return (typeof window.__roleScanPage==='function') ? window.__roleScanPage(null) : -1; } catch(e){ return -1; } })()");
                                // 防御性兜底：跨源/动态 iframe 若因注入竞态漏注入（__roleScanPage 未定义），
                                // 先强制补注入一次再扫描，确保区域内任意层 iframe（含跨源）都能被区域扫描穿透。
                                if (rf instanceof Number && ((Number) rf).intValue() < 0) {
                                    try {
                                        frameInjectOnce(f, buildNlsReverseJson(Arrays.asList(nlsFiles)));
                                        f.evaluate("(function(){ try { return (typeof window.__roleScanPage==='function') ? window.__roleScanPage(null) : -1; } catch(e){ return -1; } })()");
                                    } catch (Exception reInjEx) {
                                        String fUrl = null; try { fUrl = f.url(); } catch (Exception ignore) {}
                                        log.warn("[picker][regionScanned] 区域 iframe 补注入失败（url={}）：{}", fUrl, reInjEx.getMessage());
                                    }
                                }
                            } catch (Exception fe) {
                                String fUrl = null; try { fUrl = f.url(); } catch (Exception ignore) {}
                                log.warn("[picker][regionScanned] 区域 iframe 扫描失败（url={}）：{}", fUrl, fe.getMessage());
                            }
                        }
                    }
                    // 再把各 iframe 的 __rolePicks 合并进主框架（含本次 Java 侧补扫的 iframe 元素）
                    mergeFramePicksToMain(page, javaPickBySig);
                } catch (Exception mE) {
                    log.warn("[picker][regionScanned] 合并 iframe 元素到主框架失败：{}", mE.getMessage());
                }
                try {
                    PickSnapshot snap = readPickSnapshot(page);
                    if (snap != null && !snap.entries.isEmpty()) {
                        LinkedHashMap<String, String> codePage = buildPageClassCode(snap.entries, packageName, pageClassName, nlsFiles);
                        // 【关键修复"区域扫描后 step 代码被清空"】
                        // 区域扫描每次点选都会触发 regionScanned，本分支此前返回 codeStep=null，
                        // 主循环 fillCode 会把 step Tab 用空 stepByPage 覆盖 → 用户已封装好的步骤代码被清空
                        // （日志：先点「封装为步骤」生成 step，再点击其他区域/html/div 触发 regionScanned，
                        //  步骤代码区就空了）。
                        // 修复：regionScanned 也按当前 snap（含浏览器侧 __steps 已封装的 step）生成 step 代码，
                        // 使每次区域点选刷新页面类的同时【保留并回填】已封装的步骤，不覆盖为空。
                        LinkedHashMap<String, String> codeStep = buildStepCode(snap, packageName, stepClassName);
                        if (codePage != null && !codePage.isEmpty()) {
                            // 区域扫描完成自动回 IDLE：面板按钮复位为"▶ 开始拾取"，页面点击不再拾取。
                            setPickMode(pageNames.keySet().iterator().next(), PickMode.IDLE, pageNames);
                            return new PickerResult(PickerAction.CONTINUE, codePage, codeStep,
                                    "区域扫描完成，已生成页面类（" + snap.entries.size() + " 个字段），可继续点其他区域，或点 ⏹ 停止生成步骤代码");
                        }
                    }
                } catch (Exception e) {
                    log.warn("[picker][regionScanned] 生成页面类失败：{}", e.getMessage());
                }
                // 区域扫描完成（无论是否拾取到元素）自动回 IDLE。
                setPickMode(pageNames.keySet().iterator().next(), PickMode.IDLE, pageNames);
                return new PickerResult(PickerAction.CONTINUE, null, null, "区域扫描未拾取到可定位元素，请点击具体的业务区域");
            }
            case "package": {
                // 面板「封装为步骤」按钮触发：浏览器侧 __packageStep 已把勾选集打包进 window.__steps，
                // 此处读取快照（含 steps/ops）立即生成步骤代码并切到「步骤代码」Tab，无需等到点 ⏹。
                // 同时顺带重算页面类（若扫后又点选了新元素，页面类亦随之更新）。active 保持开启，可继续拾取。
                PickSnapshot snap = null;
                try { snap = readPickSnapshot(page); } catch (Exception ignore) {}
                if (snap == null) snap = new PickSnapshot("", new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
                // 状态外置：优先用 Java 侧内存态（javaPickBySig）覆盖（对导航/关闭导致的浏览器端状态清空免疫）。
                if (!javaPickBySig.isEmpty()) {
                    snap = new PickSnapshot(snap.pageClass, new ArrayList<>(javaPickBySig.values()), snap.steps, snap.ops);
                }
                // manual-mode fallback: start->stop whole session = one step; if packaged keep selection order.
                snap = snapWithAutoStep(snap);
                LinkedHashMap<String, String> codePage = buildPageClassCode(snap.entries, packageName, pageClassName, nlsFiles);
                LinkedHashMap<String, String> codeStep = buildStepCode(snap, packageName, stepClassName);
                // 注：切到步骤 Tab + 精准定位目标 step 由主循环 fillCode 后调用 window.__afterFillJump 统一处理
                // （该函数在浏览器侧读取 window.__pendingJump 记录的目标 step，避免此处提前切 tab 导致定位错位）。
                // 只计真正的 step 数（snap.steps）。页面级操作（closeCurrentPage/switchNewPage）是 step 内联的一行，
                // 不计入 step 总数，否则跨页操作后"封装为一个 step"会被误报成 2 个 step。
                int stepCount = (snap.steps != null ? snap.steps.size() : 0);
                return new PickerResult(PickerAction.CONTINUE, codePage, codeStep,
                        codeStep.isEmpty()
                                ? "（尚无封装的步骤：请先在「页面元素」勾选元素并点「封装为步骤」）"
                                : ("已生成步骤代码：" + stepCount + " 个 step，页面类 " + snap.entries.size() + " 个字段"));
            }
            case "refreshCode": {
                // 面板「删除」按钮触发：元素已从 window.__rolePicks / window.__steps / javaPickBySig 移除，
                // 但「页面类」「步骤代码」两个 Tab 里展示的仍是删除前生成好的旧代码文本
                // （代码是生成时一次性写入 textarea 的快照，不会自动跟随数据变化）。
                // 故此处按最新状态整体重算并回填，使已生成代码中该元素的字段声明与 step 引用一并消失。
                // 与 "package" 同一套生成链路，仅不设置 __pendingJump（不跳转 Tab，留在当前视图）。
                PickSnapshot snap = null;
                try { snap = readPickSnapshot(page); } catch (Exception ignore) {}
                if (snap == null) snap = new PickSnapshot("", new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
                // 与 package 一致：以 Java 侧内存态为准（对导航/关闭导致的浏览器端状态清空免疫）。
                // 【修复"删除元素后步骤代码括号数字不变"】删除后浏览器端 window.__steps 可能仍残留指向已删元素的旧 step，
                // 而 snapWithAutoStep 在 snap.steps 非空时会短路返回旧 steps，导致 step 数/序号不随删除更新。
                // 故此处【不沿用旧 snap.steps】，始终基于当前 javaPickBySig 重新生成 steps：
                //   - 全删空时 javaPickBySig 为空 → snap.steps 置空 → snapWithAutoStep 返回空 step（步骤代码显示"还没有任何 step"）；
                //   - 删部分时 javaPickBySig 含剩余元素 → snap.steps 置空 → snapWithAutoStep 按剩余元素序号重新拆 step，
                //     step 数量与括号序号随删除实时变化。
                // 注：手动模式主流程按点击序号拆 step，删除后重拆符合预期；若用户曾手动"封装为步骤"分组，删除后分组会被重置为按序号。
                if (!javaPickBySig.isEmpty()) {
                    snap = new PickSnapshot(snap.pageClass, new ArrayList<>(javaPickBySig.values()), new ArrayList<>(), snap.ops);
                } else {
                    snap = new PickSnapshot(snap.pageClass, new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
                }
                // manual-mode fallback: start->stop whole session = one step; if packaged keep selection order.
                snap = snapWithAutoStep(snap);
                // 【修复"删除后整页重新扫描一直为 0"】
                // 旧实现在生成页面类前按会话级 STATE_DELETED 永久剔除已删元素，导致用户删除后重新整页扫描、
                // 新识别出的元素即便已重新入库 javaPickBySig，生成时仍被剔除，表现为"再扫描一直都是 0"。
                // 删除语义仅为"从当前内存态移除"（已被 collectDeleteKeys 的 ① ② ③ 兜底 + 源头清空 iframe
                // 残留完整覆盖），不应永久封杀该元素。故此处【不再】按 STATE_DELETED 剔除，以 javaPickBySig
                // 当前内容为准直接生成——重新扫描即可正常出现代码。
                LinkedHashMap<String, String> codePage = buildPageClassCode(snap.entries, packageName, pageClassName, nlsFiles);
                LinkedHashMap<String, String> codeStep = buildStepCode(snap, packageName, stepClassName);
                String refreshMsg = "已删除选中元素，页面类 " + snap.entries.size() + " 个字段"
                        + (codeStep.isEmpty() ? "，当前无步骤代码" : "");
                // 【必须在此处直接回填】元素被删空时 codePage/codeStep 均为空 map，
                // 若交给主循环处理会因「两者都为空」落入 else 分支只更新状态栏，
                // 旧代码将永远残留在 Tab 上（删到一个不剩却还显示着完整页面类）。
                // 这里显式回填空内容，确保代码区随之清空，不留幽灵代码。
                for (Page p : pageNames.keySet()) {
                    if (!p.isClosed()) fillCode(p, codePage, codeStep, refreshMsg);
                }
                // 已自行回填，故返回 null 代码体避免主循环重复 fillCode；
                // statusMsg 仍需返回（而非 null），否则 else 分支会用 null 覆盖掉状态栏文案。
                return new PickerResult(PickerAction.CONTINUE, null, null, refreshMsg);
            }
            case "stop": {
                active[0] = false;
                log.info("[picker][stop] 收到停止命令，对 {} 个被跟踪页面执行停止", pageNames.size());
                // 多实例：停止作用于所有已打开页面，使各页面板同步回 ▶ 开始
                // （否则某页仍显示停止却已失活，造成"点了没反应"的错觉）。
                // 企业级优化：命令来源页用 stopAndRead 把"去激活 + 收尾当前 step + 读回全部拾取态"
                // 合并为 1 次 Java↔浏览器往返；其余被跟踪页仅去激活（stop），避免重复生成代码与多余读快照。
                // （原实现对命令页额外发一次收尾 evaluate + 一次 readPickSnapshot，与 stop() 共 3 次串行往返，
                //  现已合并进 stopAndRead，点击"停止"的端到端延迟显著下降。）
                PickSnapshot snap = null;
                // 折叠已关闭页（如"跳转到新页面后直接关闭"的根页）缓存的 step/op：停止生成时这些页被跳过，
                // 若不折叠，其在 onClose else 分支补登记的 closeCurrentPage 步骤会丢失。
                List<StepRec> closedSteps = new ArrayList<>();
                List<PageOp> closedOps = new ArrayList<>();
                for (Page p : pageNames.keySet()) {
                    if (p.isClosed()) {
                        String cached = snapshots.get(p);
                        if (cached != null && !cached.isEmpty()) {
                            try {
                                java.util.Map<String, Object> cm = GSON.fromJson(cached, MAP_STRING_OBJECT_TYPE);
                                PickSnapshot cs = parsePickSnapshot(cm);
                                closedSteps.addAll(cs.steps);
                                closedOps.addAll(cs.ops);
                            } catch (Exception ignore) {}
                        }
                        continue;
                    }
                    try {
                        if (p == page) snap = stopAndRead(p);
                        else stop(p);
                    } catch (Exception stopEx) {
                        // 密集导航（onFrameNavigated/整页跳转）时命令来源页的 execution context 可能正在
                        // 销毁/重建，stopAndRead/stop 的 page.evaluate 会抛 "Execution context was destroyed"。
                        // 不向上冒泡撕裂主循环会话：标记已失活并降级为读内存态，保证"停止"在任何导航瞬间都生效，
                        // 不再出现"点了停止却卡住/没反应"的假死（active 已被 active[0]=false 复位）。
                        log.warn("[picker][stop] 停止页 {} 时 evaluate 失败（导航中可忽略）：{}",
                                p.url(), stopEx.getMessage());
                        try { p.evaluate("try{window.__rolePickStopped=true;}catch(e){}"); } catch (Exception ignore) {}
                    }
                }
                if (snap == null) snap = readPickSnapshot(page);   // 兜底：命令页不在跟踪集合时
                // 跨域页停止时，主框架 page.evaluate 可能因导航竞态返回空/抛异常（snap.entries 为空）；
                // 此处再从各 frame 的 window.__rolePicks 逐帧兜底读取（与 stop() 的跨 frame 聚合同口径），
                // 确保跨域主框架页自身已被拾取的元素不丢失（修复"跨域页拾取完停止未生成 step"）。
                if (snap == null || snap.entries.isEmpty()) {
                    try {
                        List<RoleEntry> fEntries = new ArrayList<>();
                        List<StepRec> fSteps = new ArrayList<>();
                        List<PageOp> fOps = new ArrayList<>();
                        for (Frame f : page.frames()) {
                            try {
                                // 与 stopAndRead/readPickSnapshot 同口径：直接读各 frame 的 window.__rolePicks/__steps/__ops
                                PickSnapshot fs = parsePickSnapshot(f.evaluate(PICK_STATE_READER_JS));
                                fEntries.addAll(fs.entries); fSteps.addAll(fs.steps); fOps.addAll(fs.ops);
                            } catch (Exception fe) { /* 单 frame 失败忽略，继续其它 frame */ }
                        }
                        if (!fEntries.isEmpty()) {
                            PickSnapshot fb = new PickSnapshot(snap == null ? "" : snap.pageClass, fEntries, fSteps, fOps);
                            // 内存态(javaPickBySig)优先；仅当内存态也为空时才用跨 frame 浏览器兜底态。
                            snap = (!javaPickBySig.isEmpty()) ? snap : fb;
                        }
                    } catch (Exception ff) {
                        log.warn("[picker][stop] 跨 frame 兜底读快照失败 @ {} : {}", page.url(), ff.getMessage());
                    }
                }
                // 状态外置（对齐 page.pause）：优先用 Java 侧内存态（javaPickBySig）作为已拾元素权威来源，
                // O(1) 取回、且对导航/关闭导致的浏览器端状态清空免疫；内存为空（回传桥未触发等异常）时
                // 退回浏览器读快照兜底。steps/ops 仍来自浏览器单次往返（stopAndRead 已合并），保证多页 step 序列正确。
                if (!javaPickBySig.isEmpty()) {
                    List<RoleEntry> memEntries = new ArrayList<>(javaPickBySig.values());
                    snap = new PickSnapshot(snap.pageClass, memEntries, snap.steps, snap.ops);
                }
                // manual-mode (not packaged) fallback: start->stop whole session = one step.
                snap = snapWithAutoStep(snap);
                // 合入已关闭页的步骤/操作（含其补登记的 closeCurrentPage），避免关闭步骤在停止时被跳过而丢失。
                if (!closedSteps.isEmpty() || !closedOps.isEmpty()) {
                    List<StepRec> mergedSteps = new ArrayList<>(snap.steps);
                    mergedSteps.addAll(closedSteps);
                    List<PageOp> mergedOps = new ArrayList<>(snap.ops);
                    mergedOps.addAll(closedOps);
                    snap = new PickSnapshot(snap.pageClass, snap.entries, mergedSteps, mergedOps);
                }
                // 关键修复（修复"开始→停止→再开始→停止，第一次的步骤代码丢失"）：
                // 整页跳转时 onFrameNavigated→applyPickState 会用 snapshots 里的 Java 恢复态【整体覆盖】
                // window.__steps（见 applyPickState：window.__steps = s.steps || []）。而快照此前只在"空闲刷新"时更新，
                // 且 run1 拾取期间 __steps 为空、空闲刷新把恢复态停在"空 steps"；若 run2 中发生整页跳转，
                // 就用这份过期恢复态把第一次的 step 整体覆盖丢失。此处把本次停止后的最新态【立即回写】Java 恢复态，
                // 使任何后续跳转恢复时都含已有 step（含第一次），彻底消除该时序窗口。
                try { snapshots.put(page, readPickStateJson(page)); } catch (Exception ignore) {}
                // 多页面：当前页 window 持有全部被跟踪页面的拾取（跟随新页时搬运、关闭弹窗时合并回父页），
                // 每条 pick/step 都带 _pageClass 标签，据此分组到对应 Page 类；steps 跨页引用也归到对应页。
                String curClass = snap.pageClass;
                LinkedHashMap<String, List<RoleEntry>> entriesByPage = new LinkedHashMap<>();
                LinkedHashMap<String, List<List<RoleEntry>>> stepsByPage = new LinkedHashMap<>();
                List<RoleEntry> allEntries = new ArrayList<>();
                int totalSteps = 0;
                for (RoleEntry e : snap.entries) {
                    String pc = (e.getPageClass() == null || e.getPageClass().isEmpty()) ? curClass : e.getPageClass();
                    entriesByPage.computeIfAbsent(pc, k -> new ArrayList<>()).add(e);
                    allEntries.add(e);
                }
                for (StepRec st : snap.steps) {
                    String pc = (st.pageClass == null || st.pageClass.isEmpty()) ? curClass : st.pageClass;
                    stepsByPage.computeIfAbsent(pc, k -> new ArrayList<>()).add(st.picks);
                    entriesByPage.computeIfAbsent(pc, k -> new ArrayList<>());   // 保证 steps 的页在 entries 中也有类
                    totalSteps++;
                }
                // 页面级操作（如关闭页面）按所属页归类，生成 closeCurrentPage() 等步骤。
                LinkedHashMap<String, List<String>> opsByPage = new LinkedHashMap<>();
                for (PageOp op : snap.ops) {
                    String pc = (op.pageClass == null || op.pageClass.isEmpty()) ? curClass : op.pageClass;
                    opsByPage.computeIfAbsent(pc, k -> new ArrayList<>()).add(op.op);
                    // 注意：页面级操作（closeCurrentPage / switchNewPage 等）已在各页视图内联为 step 内的一行代码，
                    // 不是独立 step，故不再把 op 计入 totalSteps，否则"封装为 1 个 step + 1 个内联跳转操作"会误显为 2 个 step。
                }
                if (entriesByPage.isEmpty()) {
                    // 诊断：跨域/导航竞态下出现"未拾取到元素"时，记录内存态与浏览器侧 picks 数量，便于定位是否漏拾。
                    int jsMem = javaPickBySig.size();
                    int browserPicks = 0;
                    try { browserPicks = ((List<?>) page.evaluate("() => (window.__rolePicks||[]).length")).size(); } catch (Exception ignoreB) {}
                    log.warn("[picker][stop] 未拾取到元素 @ {} : 内存态 javaPickBySig={}, 浏览器 __rolePicks={}, 当前页 origin={}",
                            page.url(), jsMem, browserPicks, safeOrigin(page.url()));
                    // 停止即回 IDLE，面板按钮复位为"▶ 开始拾取"。
                    // 停止即回 IDLE，面板按钮复位为"▶ 开始拾取"。
                    setPickMode(pageNames.keySet().iterator().next(), PickMode.IDLE, pageNames);
                    return new PickerResult(PickerAction.CONTINUE, null, null, "未拾取到元素");
                }
                // 按 pageClass 分别生成页面类（含"仅 step/ops 无元素 pick"的页，也产出空字段类，保证步骤视图引用不悬空）
                LinkedHashMap<String, String> codePage = new LinkedHashMap<>();
                for (Map.Entry<String, List<RoleEntry>> e : entriesByPage.entrySet()) {
                    codePage.put(e.getKey(), RoleElementPageGenerator.generate(e.getValue(), packageName, e.getKey(), nlsFiles));
                }
                LinkedHashMap<String, String> codeStep = buildStepCode(snap, packageName, stepClassName);
                // 【diag-stop】停止并生成代码前，列印全部 entry 的最终 pickNos（生成器即据此按号展开 click）。
                log.info("[picker][diag-stop] ===== before buildStepCode: {} entries =====", allEntries.size());
                for (RoleEntry e : allEntries) {
                    log.info("[picker][diag-stop] entry sigKey={} strategy={} pageClass={} pickNos={}", e.getSigKey(), e.getStrategy(), e.getPageClass(), e.getPickNos());
                }
                // 停止拾取后重置（必须在 buildStepCode 之后，生成已基于累积 pickNos 完成）：
                // 清空 Java 权威内存态每个 entry 的 pickNos，并让浏览器侧 window.__rolePicks 的
                // _pickNos/_pickSeq 归零，使面板干净回退到 [-]，且下一次 start 时全局动作序号从 1 重新计数。
                for (RoleEntry e : javaPickBySig.values()) e.setPickNos(null);
                try {
                    // 【修复"stop→start 第二轮序号错乱（如 user_name 拿到旧号而非从续接点递增）】
                    // 原逻辑只 delete p._pickNos + p._pickSeq=0，但 __rolePicks 数组、__rolePickSigs（key 去重表）、
                    // __sigToPick（sigKey→pick 对象）、以及上一轮缓存的旧 pick 对象（__pickSeq/_pickNos 残留）都仍保留。
                    // start 时设计上"保留既有 picks、从既有重建去重表"，于是第二轮点同一元素会【复用旧 pick 对象】
                    // （其旧 _pickSeq/_pickNos 仍在），导致序号回退/重复。
                    // 修复：stop 时彻底清空浏览器侧全部拾取注册状态（与"下一轮从 1 重新连续"语义一致），
                    // 仅保留已生成的代码结果（Java 侧 buildStepCode 已完成）。start 会重新从空状态累积，序号从 1 起。
                    page.evaluate("try{"
                            + " window.__rolePicks = [];"
                            + " try{ if(window.__rolePickSigs) window.__rolePickSigs = {}; }catch(e){}"
                            + " try{ if(window.__sigToPick) window.__sigToPick = {}; }catch(e){}"
                            + " try{ window.__rolePickSeq = 0; }catch(e){}"
                            + " try{ window.__roleMaxNo = 0; }catch(e){}"
                            + " try{ window.__pickOrder = {}; }catch(e){}"
                            + " if(window.__renderPicks)window.__renderPicks();"
                            + " if(window.refreshSelInfo)window.refreshSelInfo();"
                            + "}catch(e){}");
                } catch (Exception ignore) {}
                int matched = 0;
                for (RoleEntry e : allEntries) {
                    if (e.getResolvedKey() != null) matched++;
                }
                String nlsInfo = (nlsFiles != null && nlsFiles.length > 0)
                        ? "（nls=" + (nlsFiles.length == 1 ? nlsFiles[0] : nlsFiles.length + " 个文件")
                            + "，已反查 " + matched + " 个 key）" : "";
                // 停止即回 IDLE，面板按钮复位为"▶ 开始拾取"，页面点击不再拾取。
                setPickMode(pageNames.keySet().iterator().next(), PickMode.IDLE, pageNames);
                return new PickerResult(PickerAction.CONTINUE, codePage, codeStep,
                        "已生成 " + entriesByPage.size() + " 个页面类 / " + allEntries.size()
                                + " 个页面字段 / " + totalSteps + " 个 step" + nlsInfo);
            }
            case "abort":
                active[0] = false;
                stop(page);
                return new PickerResult(PickerAction.ABORT, null, null, null);
            case "done":
                // 面板『✕ 关闭 / ⏹ 停止』按钮发出的明确关闭命令：停止拾取并结束 openPanel 阻塞循环
                // （主循环对 DONE 抛 PickerAbortedException，中止调用方后续自动登录等代码）。
                stop(page);
                return new PickerResult(PickerAction.DONE, null, null, null);
            default:
                // 【关键修复】未知/未识别命令（含 JSON 命令解析异常后落空、repickNos 等对象命令
                // 在极少数竞态下未命中 type）绝不能再 stop + 返回 DONE，否则会误关面板
                // （表现为"点拾取序号/加号面板直接关闭"）。未知命令一律忽略、继续会话。
                return new PickerResult(PickerAction.CONTINUE, null, null, null);
        }
    }

    /**
     * 用户在面板点击『终止运行』时抛出，用于中断调用方后续代码执行。
     * 调用方可按需捕获以决定是失败退出还是降级处理。
     */
    public static final class PickerAbortedException extends RuntimeException {
        public PickerAbortedException(String message) {
            super(message);
        }
    }

    private RoleElementPicker() {}

    /** 开启拾取模式（手动控制起止时可单独调用，不预加载 nls 反向查表） */
    public static void start(Page page) {
        if (isCiRun()) {
            log.info("[picker] 检测到 CI 运行环境，跳过拾取模式（start）。");
            return;
        }
        start(page, "{}");
    }

    /**
     * 开启拾取模式，并预加载 nls 反向查表（JSON：规范化文本值 → key）。
     * 拾取交互角色元素时，会用 a11y name 反查该表，命中则直接用真实 nls key。
     *
     * @param page              Playwright Page
     * @param nlsReverseJson    nls 反向查表 JSON；为 null/空/"{}" 时退化为不反查（回退 slug）
     */
    public static void start(Page page, String nlsReverseJson) {
        start(page, nlsReverseJson, null);
    }

    /**
     * 开启拾取模式，并预加载 nls 反向查表（JSON：规范化文本值 → key）。
     * 拾取交互角色元素时，会用 a11y name 反查该表，命中则直接用真实 nls key。
     *
     * @param page              Playwright Page
     * @param nlsReverseJson    nls 反向查表 JSON；为 null/空/"{}" 时退化为不反查（回退 slug）
     * @param rootSelector      录制根容器选择器（如 "main"、"#content"）；
     *                          非 null 时只有落在该容器内（含其后代）的点击/悬停才会被录制，
     *                          leftmenu / topbar 等全局导航区域在范围外，自然不被捕获。
     *                          为 null 时退化为整页扫描（原行为）。
     */
    public static void start(Page page, String nlsReverseJson, String rootSelector) {
        // CI 环境：拾取模式是本地开发工具，自动化测试里不应开启并等待人工拾取，直接跳过。
        if (isCiRun()) {
            log.info("[picker] 检测到 CI 运行环境，跳过拾取模式（start）。");
            return;
        }
        // 关键修复：将门控拾取脚本注册到 context 级 addInitScript（仅注册一次），使【之后创建的所有文档/
        // iframe/弹窗】都自动注入 nls 反向表并（会话开关打开时）重挂拾取监听——包括本次 start() 之前已加载、
        // 但导航后才出现的 iframe。若 start() 不注册，仅对顶层文档 page.evaluate 注入，iframe 因未拿到脚本
        // 而无点击监听，表现为"iframe 内点击拾取不到 / postMessage 上送不到顶层"。
        // 注意 openPanel/followPage 也会调用本注册，这里幂等（同 context 同 nls 跳过），重复调用安全。
        try { registerContextInitScripts(page.context(), nlsReverseJson); } catch (Exception ignore) {}
        // 兼容两种格式：新格式 {exact, templates} 拆开注入；旧格式（纯精确表）整体作为 exact。
        // 企业级优化：把"会话开关置位 + nls 反向表注入 + START_SCRIPT 开启监听"合并进同一次 page.evaluate，
        // 点击"开始拾取"只付出 1 次 Java↔浏览器往返（原来 2 次串行），按钮即时响应。
        // 会话开关 __rolePickSessionOn（对齐 page.pause 的 mode 下推）：context 级门控注入脚本
        // （gatedPickerInitScript）据此在【每个新文档】自动重挂拾取监听——导航/弹窗/SPA 整文档替换后
        // 拾取存活由浏览器原生保证，无需 Java 端手动重挂。
        // ⭐ 关键修复：nls 反向表与 rootSelector 原先是「直接字符串拼接进 JS 表达式」，
        // 一旦内容含特殊字符就会破坏整段脚本语法，抛出 SyntaxError 并中断调用方（如 performLogin）。
        // 改为「参数化注入」：Playwright 会自动正确序列化参数，nls 用 JSON.parse 解析，
        // root 直接作为 JS 值传入（null 或字符串），彻底消除字符串拼接破坏语法的可能。
        // 整个注入包 try-catch：拾取器是开发辅助工具，注入失败只告警并继续，绝不中断主测试流程。
        String pickStartScript =
                "(function(args){"
                + " var __nlsArg = args ? args.nls : null;"
                + " var __rootArg = args ? args.root : null;"
                + " var __o = (__nlsArg && typeof __nlsArg === 'string') ? JSON.parse(__nlsArg) : (__nlsArg || {});"
                + " try{localStorage.setItem('__rolePickSessionOn','1');}catch(e){}"
                + " try{window.__rolePickSessionOn=true;}catch(e){}"
                // 重置面板切换控件的"乐观意图"位：避免上一轮 stop/start 残留的 __rolePickWanted 让
                // 按钮的 willStart 计算发出错误命令（关键修复"停止后再点开始却拾取不了"的边界之一）。
                + " try{ window.__rolePickWanted = null; }catch(e){}"
                // 关键修复（停止→再开始拾取不了）：同文档"二次开始"若沿用"已注入"状态（window.__rolePickerLib 已为 true），
                // START_SCRIPT 会跳过整套库定义、仅依赖顶部 addEventListener 保活；一旦该保活因竞态未真正生效
                // （典型如 stop 已 removeEventListener 移除旧函数引用、而重激活路径没把它加回），表现即
                // "停止后再点开始却拾取不了 / 跳转到新页面拾取不到"。这里主动移除旧监听并把 __rolePickerLib 复位为 false，
                // 强制 __roleGatedStart 重新走"完整库定义 + 重新 addEventListener"分支——与刷新/跳转全新文档的
                // 已知可用路径完全一致，从根消除"二次开始"与"刷新"的行为差异。
                // window.__rolePicks 仍用 || [] 保留，__rolePickSigs/__sigToPick 随后从既有 picks 重建，已拾元素与去重都不丢。
                + " try{ if(window.__rolePickClick) document.removeEventListener('click', window.__rolePickClick, true); }catch(e){}"
                + " try{ if(window.__rolePickMove) document.removeEventListener('mousemove', window.__rolePickMove, true); }catch(e){}"
                + " try{ if(window.__rolePickKey) document.removeEventListener('keydown', window.__rolePickKey, true); }catch(e){}"
                + " try{ if(window.__rolePickFocus) document.removeEventListener('focusin', window.__rolePickFocus, true); }catch(e){}"
                + " try{ if(window.__rolePickScroll) document.removeEventListener('scroll', window.__rolePickScroll, true); }catch(e){}"
                + " try{ window.__rolePickerLib = false; }catch(e){}"
                // 【修复"跨会话脏序号污染本轮（后拾取元素首号偏小，如 user_name 拿到 2 而非 4/5）"】
                // 旧实现 start 时 window.__rolePicks 用 || [] 复用上一轮残留（注释称"保留已拾元素"），
                // 但若 stop→start 未配对（测试框架直接重开 start、或同 context 跨 run 复用页面），
                // 上一轮的 user_name:[2]/__pickOrder 残留会命中 dup 分支，回传脏首号 2 污染本轮。
                // Java 端 javaPickBySig 每次 openPanel 都是新 map（从空开始），浏览器端应从空起步与之对齐：
                // 强制清空全部拾取全局态，序号从 1 重新连续，杜绝脏序号串台。
                + " try{ window.__rolePicks = []; }catch(e){}"
                + " try{ window.__rolePickSigs = {}; }catch(e){}"
                + " try{ window.__sigToPick = {}; }catch(e){}"
                + " try{ window.__rolePickSeq = 0; }catch(e){}"
                + " try{ window.__roleMaxNo = 0; }catch(e){}"
                + " try{ window.__pickOrder = {}; }catch(e){}"
                // 【index 需求】每次开始拾取，重置全局动作序号计数器：单会话内 index 从 1 连续递增，
                // 重复点击同一元素时其 _pickNos 追加当前号（如 [1,5]）。stop→start 重开应重新从 1 计数。
                // 注意：若是从快照恢复（restoreSnapshot 已显式续接此值），本行不应执行——restore 路径在 Java 侧单独控制。
                + " try{ window.__rolePickSeq = 0; }catch(e){}"
                + " window.__nlsReverse = (__o && __o.exact) ? __o.exact : (__o && __o.templates ? {} : (__o || {}));"
                + " window.__nlsTemplates = (__o && __o.templates) ? __o.templates : [];"
                // 优先复用门控脚本封装的 window.__roleGatedStart（含 load/pagesight 自愈入口），保证 Java 侧
                // 触发的重注入与浏览器原生重注入走同一份逻辑、nls 反查表一致；不可用时退化直接执行 START_SCRIPT。
                // 由于上面已把 __rolePickerLib 复位为 false，此处的 __roleGatedStart / START_SCRIPT 都会重新定义整套库并重新挂监听，
                // 并将 autoDetectRoot 挂到 window（见 START_SCRIPT_A）。
                + " if (typeof window.__roleGatedStart === 'function') { window.__roleGatedStart(); } else { " + START_SCRIPT + " }"
                // 录制根容器：必须在 START_SCRIPT / __roleGatedStart 执行之后赋值，
                // rootSelector==null 表示「整页」（不施加根约束）——整页扫描 / 点击拾取 / 未指定区域时均真实整页、可点全盘。
                // 关键修复：之前此处无条件 autoDetectRoot() 会把整页扫描/点击拾取也限成某容器，
                // 但用户有时需要拾取整页（含 leftmenu/topbar），故开始拾取不再自动避开导航。
                // 根约束仅由「区域扫描」显式点选产生（用户在 __roleStartRegionSelect 内覆盖 window.__rolePickRoot）。
                // 门控脚本会在每个新文档重挂监听，必须同步写入 window.__rolePickRoot，否则跳转/SPA 替换后根约束丢失。
                + " window.__rolePickRoot = __rootArg;"
                + " try { console.log('[picker] 录制根容器 =', window.__rolePickRoot || '(整页)'); } catch(e){}"
                + " })";
        try {
            // evaluate 仅支持单个参数对象：将 nls 与 root 打包为一个 Map 传入，脚本内从 arguments[0] 解构。
            // 注意：Guava ImmutableMap 不允许 null 值，rootSelector 可能为 null（整页扫描），故用 HashMap 并兜底。
            java.util.Map<String, String> startArgs = new java.util.HashMap<>();
            startArgs.put("nls", nlsReverseJson);
            // rootSelector 允许为 null（整页扫描）；HashMap 与 Playwright 参数序列化均支持 null。
            startArgs.put("root", rootSelector);
            page.evaluate(pickStartScript, startArgs);
        } catch (Exception e) {
            log.warn("[picker] 拾取脚本注入失败（不影响主流程）：{}", e.getMessage());
        }
        log.info("[picker] 拾取模式已开启：在浏览器点击元素即可拾取，按 ESC 结束。");
        // 关键修复：已加载的子 iframe（srcdoc/同域）在 start() 调用前就已触发过 load，
        // 彼时会话开关尚未置位，其门控 START 未挂拾取监听 → iframe 内点击无法被拾取、postMessage 也收不到。
        // 故 start() 置位开关后，主动把拾取监听重挂到当前所有已存在的子 frame（同源可 evaluate；
        // 跨源 frame 因安全限制无法注入，按设计跳过——跨源 iframe 内的元素本就不经主框架拾取）。
        try {
            // 复用统一注入入口：① 对当前已存在的所有子 frame 立即补挂（修复"start 前已加载的 iframe 拾取不到"）；
            // ② 注册 onFrameAttached 监听，使 start 之后动态创建的 iframe 一附加即自动注入拾取脚本
            //    （修复"动态 iframe 内元素点不到、生成不出 switchToFrame 包裹 step"）。
            registerFrameInjection(page, nlsReverseJson);
        } catch (Exception ignore) {}
        // 诊断：start() 注入后确认监听真正挂载（排查"点击没反应"究竟是注入失败还是被后续覆盖）。
        try {
            String d = page.evaluate("(function(){ return JSON.stringify({"
                    + " origin: location.origin,"
                    + " winSwitch: !!window.__rolePickSessionOn,"
                    + " active: !!window.__rolePickActive,"
                    + " hasClick: typeof window.__rolePickClick==='function',"
                    + " hasMove: typeof window.__rolePickMove==='function',"
                    + " hasRecord: typeof window.__recordPick==='function'"
                    + "}); })()").toString();
            log.info("[picker][start] 注入后诊断 @ {} : {}", page.url(), d);
            // 记录本次成功注入的 origin，供 onFrameNavigated 重激活区分同源（门控已注入，仅保活）/跨域（需强制重注入）。
            // 【修复"跳转到新页面拾取不到"】popup 在 onPopup 回调触发时文档还是 about:blank（origin 为空串），
            // 若在此处把 LAST_PICK_ORIGIN 更新为空串，会污染全局跨域判据：后续该 popup 导航到真实跨域页时，
            // onFrameNavigated 用 curOrigin("https://b.com") != "" 误判为 originChanged=true 而强制重注入——
            // 这本应是对的；但更隐蔽的是：若真实页与根页【同源】，空串会让 originChanged 错判、且把好不容易注入的
            // 库因 about:blank 文档随即销毁而丢失，最终表现为"新页面无蓝框、点击无反应"。
            // 故 about:blank/空 origin 绝不更新 LAST_PICK_ORIGIN，保持上一有效 origin 作为去抖基准。
            try {
                String __o = safeOrigin(page.url());
                if (!__o.isEmpty()) LAST_PICK_ORIGIN = __o;
            } catch (Exception ignore) {}
        } catch (Exception e) { log.warn("[picker][start] 诊断读取失败：{}", e.getMessage()); }
    }

    /**
     * 把 nls 文件构建成"规范化后的文本值 → key"的反向查表 JSON（覆盖所有语言），
     * 供浏览器拾取时把 a11y name 反查为对应 nls key。文件缺失/解析失败时返回 "{}"（退化为不反查）。
     *
     * @param nlsFile nls 文件路径（classpath 相对或文件系统绝对），如 "nls/login.nls.json"
     */
    /**
     * 合并多个 nls 文件构建反向查表（覆盖所有语言）：精确表与模板表均跨文件合并，
     * 同一规范化文本/正则源以首个文件优先（putIfAbsent）。供拾取时把 a11y name 反查为对应 key，
     * 从而支持「一个页面用到多个 nls json」的场景。
     */
    /**
     * nls 反向查表缓存：同一组 nls 文件在会话内只解析一次，避免每次开始拾取（openPanel / ▶ 启动 /
     * pick）都重读并解析磁盘上的 nls json。key 为排序去重后的文件路径拼接（忽略传参顺序差异）；
     * 带 TTL，文件被更新后到期自动重建。可通过系统属性 {@code rolePicker.nlsCacheTtlMs} 调整有效期（毫秒）。
     */
    private static final Map<String, CachedNls> NLS_REVERSE_CACHE = new ConcurrentHashMap<>();
    private static final long NLS_CACHE_TTL_MS =
            Long.getLong("rolePicker.nlsCacheTtlMs", 5 * 60 * 1000L);

    private static final class CachedNls {
        final String json;
        final long ts;
        CachedNls(String json) { this.json = json; this.ts = System.currentTimeMillis(); }
        boolean fresh() { return System.currentTimeMillis() - ts < NLS_CACHE_TTL_MS; }
    }

    private static String buildNlsReverseJson(List<String> nlsFiles) {
        if (nlsFiles == null || nlsFiles.isEmpty()) return "{}";
        // 稳定 key：排序 + 去重 + 去首尾空白，忽略传参顺序差异（["a","b"] 与 ["b","a"] 命中同一缓存）
        String key = nlsFiles.stream()
                .filter(f -> f != null && !f.isBlank())
                .map(String::trim)
                .sorted().distinct()
                .collect(Collectors.joining("\u0000"));
        if (key.isEmpty()) return "{}";
        CachedNls cached = NLS_REVERSE_CACHE.get(key);
        if (cached != null && cached.fresh()) return cached.json;
        String json = buildNlsReverseJsonUncached(nlsFiles);
        NLS_REVERSE_CACHE.put(key, new CachedNls(json));
        return json;
    }

    /** 单文件便捷重载（向后兼容），走带缓存的 {@link #buildNlsReverseJson(List)} */
    private static String buildNlsReverseJson(String nlsFile) {
        return buildNlsReverseJson(List.of(nlsFile));
    }

    /** 实际解析 nls 文件构建反向查表（带缓存，外部一律走 {@link #buildNlsReverseJson}） */
    private static String buildNlsReverseJsonUncached(List<String> nlsFiles) {
        try {
            Map<String, String> exact = new LinkedHashMap<>();
            Map<String, String> templates = new LinkedHashMap<>();
            for (String nlsFile : nlsFiles) {
                if (nlsFile == null || nlsFile.isBlank()) continue;
                Map<String, Map<String, String>> tables = NLSUtils.rawTables(nlsFile);
                for (Map<String, String> table : tables.values()) {
                    if (table == null) continue;
                    for (Map.Entry<String, String> en : table.entrySet()) {
                        // 反查 key 必须基于「页面可见文本」：nls 值里常内嵌 <a>/<strong>/<img> 与
                        // &nbsp;/&copy; 等实体，浏览器渲染后可见文本已无标签，故精确表与模板正则
                        // 一律用 NLSUtils.visibleText / templateRegexSource（二者都会剥 HTML + 解码实体）。
                        // 否则如 tab_security_device("保安編碼器&nbsp; <img...>") 的 key 会带 <img>，
                        // 与浏览器算出的可访问名 "保安編碼器" 对不上，反查失败退化为字面值。
                        String visible = NLSUtils.visibleText(en.getValue());
                        if (visible.isEmpty()) continue;
                        if (en.getValue().contains("{{")) {
                            // 含模板变量：无法精确反查，改用正则源（跨语言匹配替换后的可见文本）
                            String src = NLSUtils.templateRegexSource(en.getValue());
                            if (!src.isEmpty()) templates.putIfAbsent(src, en.getKey());
                        } else {
                            exact.putIfAbsent(visible, en.getKey());
                        }
                    }
                }
            }
            if (exact.isEmpty() && templates.isEmpty()) {
                log.warn("[picker] nls 文件无可用条目，无法反查 key：{}", nlsFiles);
                return "{}";
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("exact", exact);
            out.put("templates", templates.entrySet().stream()
                    .map(e -> new String[]{e.getKey(), e.getValue()})
                    .toArray(String[][]::new));
            log.info("[picker] 已加载 nls 反向查表（精确 {} 条 / 模板 {} 条），拾取时将自动匹配 key：{}",
                    exact.size(), templates.size(), nlsFiles);
            return GSON.toJson(out);
        } catch (Exception e) {
            log.warn("[picker] 加载 nls 文件失败，拾取时无法反查 key，将回退到 name 派生 slug：{}", nlsFiles, e);
            return "{}";
        }
    }

    /** 关闭拾取模式，清理注入的监听与提示条 */
    public static void stop(Page page) {
        // CI 环境：拾取模式本就不会开启（start/openPanel 均跳过），此处不注入任何代码。
        if (isCiRun()) {
            log.info("[picker] 检测到 CI 运行环境，跳过停止注入（stop）。");
            return;
        }
        // 先清除会话开关（门控注入脚本据此在后续新文档不再自启拾取），再执行停止收尾。
        // 【修复"停止不了"】置位 window.__rolePickStopped=true：让浏览器侧自愈钩子（__roleReenable，在 load/pageshow
        // 时触发）即便因后续导航/路由变化再次被调用，也直接 return 不再复活拾取；同时清掉 __rolePickWanted，
        // 让开始/停止切换控件的状态机复位（否则 willStart=!(active||wanted) 在 wanted 残留 true 时翻转失效，
        // 表现为"点了停止却仍是开始态/再点开始却拾取不了"）。start() 会重置该标志恢复自愈能力。
        page.evaluate("try{localStorage.removeItem('__rolePickSessionOn');}catch(e){}"
                + " try{window.__rolePickSessionOn=false;}catch(e){}"
                + " try{window.__rolePickStopped=true;}catch(e){}"
                + " try{window.__rolePickWanted=false;}catch(e){}"
                + STOP_SCRIPT);
    }

    /**
     * 读取当前已拾取的元素列表（不阻塞、不生成）。
     * 可在 {@link #start} / {@link #stop} 之间多次调用，实时查看进度。
     */
    @SuppressWarnings("unchecked")
    public static List<RoleEntry> getEntries(Page page) {
        List<RoleEntry> result = new ArrayList<>();
        // 跨 frame 聚合：每个 frame（含主框架与各层 iframe）都持有自己的 window.__rolePicks，
        // 直接在 Java 侧遍历 page.frames() 逐帧读取并合并（不再依赖 iframe→父窗口 postMessage 中继，
        // 该机制在自动化点击场景下面父 message 事件不触发，不可靠）。
        // framePath（iframe 嵌套路径）在 Java 侧用 Playwright 的 Frame.frameElement() 计算——
        // 浏览器侧 window.frameElement 在跨源/ file:// 场景下访问受限（SecurityError），Java 侧从父上下文
        // 取 frameElement 则始终可访问，稳定可靠。浏览器侧若已带 framePath 则优先沿用，否则以 Java 侧补算。
        java.util.Set<String> seenKeys = new java.util.HashSet<>();
        List<Frame> allFrames;
        try { allFrames = page.frames(); } catch (Exception e) { allFrames = new ArrayList<>(); }
        // 复合嵌套 frame（frame 内嵌 frame 多层）：同一条拾取可能被多个 frame 的 window.__rolePicks 收录
        // （如最深层 iframe 内元素，其 pick 会同时出现在最深层与若干祖先 frame）。跨 frame 去重时若浅层先入，
        // 保留的就是浅层 framePath（少算层级）。故按【frame 深度降序】遍历，让最深层 frame 的 pick 先入 result，
        // 浅层副本被 seenKeys 去重跳过，从而 framePath 始终是最深、最完整的嵌套路径。
        allFrames.sort((a, b) -> Integer.compare(frameDepth(b), frameDepth(a)));
        for (Frame fr : allFrames) {
            List<String> fp = computeFramePath(page, fr);   // 自顶向下的 iframe 选择器链（主框架为空）
            Object raw;
            try {
                raw = fr.evaluate("(function(){"
                        + " if (typeof window.__mergeKey !== 'function') { window.__mergeKey = function(p){ try{"
                        + "   if (!p) return ''; if (p._sigKey) return p._sigKey;"
                        + "   if (typeof window.__sigKey === 'function') return window.__sigKey(p);"
                        + "   var pk = p._pageClass || ''; if (!pk) { try { pk = (location.origin||'') + (location.pathname||''); } catch(e){} }"
                        + "   return JSON.stringify([p._sig || '', pk]);"
                        + " }catch(e){ return ''; } }; }"
                        + " var seen = {}; var out = [];"
                        + " (window.__rolePicks||[]).forEach(function(p){ try{ var k = window.__mergeKey(p);"
                        + "   if (!k) { out.push(p); return; } if (seen[k]) return; seen[k]=true; out.push(p);"
                        + " }catch(e){ out.push(p); } }); return out; })()");
            } catch (Exception ignore) { continue; }   // 跨源 frame 读取受限，跳过
            if (raw instanceof List) {
                for (Object o : (List<Object>) raw) {
                    if (o instanceof Map) {
                        Map<Object, Object> m = (Map<Object, Object>) o;
                        RoleEntry e = parsePick(m);
                        if (e == null) continue;
                        // Java 侧二次兜底：与 parsePickSnapshot 同口径。
                        String dk = pickDedupKey(m, e);
                        if (!dk.isEmpty() && !seenKeys.add(dk)) continue;
                        // iframe 嵌套路径：浏览器侧已带则沿用，否则以 Java 侧 frameElement 链补算。
                        if (e.getFramePath() == null && fp != null && !fp.isEmpty()) {
                            e.setFramePath(new ArrayList<>(computeFramePath(page, fr)));
                        }
                        result.add(e);
                    }
                }
            }
        }
        return result;
    }

    /** Java 侧判定 id 是否可作为稳定定位锚（对应浏览器侧 isStableId：非纯自动生成序号即可）。 */
    private static boolean isStableIdJava(String id) {
        if (id == null || id.trim().isEmpty()) return false;
        String s = id.trim();
        // 纯数字 / 以数字结尾的自动生成 id（如 "id-1"、"auto123"）视为不稳定，其余视为稳定。
        if (s.matches(".*\\d+$") && !s.matches(".*[a-zA-Z].*")) return false;
        return true;
    }

    /** frame 嵌套深度：主框架为 0，其直接子 frame 为 1，frame 内嵌 frame 逐层 +1。用于 getEntries 深度降序遍历，
     *  保证最深层 frame 的拾取先入聚合结果，浅层副本被去重跳过，framePath 始终最完整。 */
    private static int frameDepth(Frame fr) {
        int d = 0;
        try {
            Frame cur = fr;
            while (cur != null && cur.parentFrame() != null) {
                d++;
                cur = cur.parentFrame();
            }
        } catch (Exception ignore) {}
        return d;
    }

    /** 计算某 frame 的嵌套路径（自顶向下），主框架返回 null；优先 name / 稳定 id / src 片段 / 退化为 iframe:nth-of-type。
     *  主框架判定改用 page.mainFrame()（file:// 等场景下 iframe 的 parentFrame() 可能误返回 null，
     *  用 mainFrame 参照可稳定区分顶层与嵌套 frame）。
     *  标签形态与浏览器侧 __framePathOf 保持一致，供 RoleElementStepGenerator.frameNameOf 通用解析。 */
    private static List<String> computeFramePath(Page page, Frame fr) {
        try {
            if (fr == null || fr == page.mainFrame()) return null;   // 主框架
            List<String> path = new ArrayList<>();
            Frame cur = fr;
            while (cur != null && cur != page.mainFrame()) {
                Frame parent = cur.parentFrame();
                try {
                    com.microsoft.playwright.ElementHandle fe = cur.frameElement();
                    String sel = null;
                    String nm = fe.getAttribute("name");
                    if (nm != null && !nm.trim().isEmpty()) sel = "iframe[name=\"" + nm.trim() + "\"]";
                    if (sel == null) {
                        String id = fe.getAttribute("id");
                        if (id != null && !id.trim().isEmpty() && isStableIdJava(id)) sel = "#" + id.trim();
                    }
                    if (sel == null) {
                        // 无 name / 无稳定 id：用 src 路径片段作为稳定标签（双通道：page.frame 兜底 + CSS frameLocator）
                        String src = fe.getAttribute("src");
                        if (src != null && !src.trim().isEmpty()) {
                            String u = src.trim();
                            int cut = u.indexOf("//");
                            String frag = cut >= 0 ? u.substring(u.indexOf('/', cut + 2)) : u;
                            sel = "iframe[src*=\"" + frag + "\"]";
                        }
                    }
                    if (sel == null) sel = "iframe";
                    path.add(0, sel);
                } catch (Exception ignore) {
                    path.add(0, "iframe");
                }
                cur = parent;
            }
            return path.isEmpty() ? null : path;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 把 Java 权威拾取内存态（javaPickBySig）同步到浏览器面板展示数组 window.__rolePicks 并触发渲染。
     * 修复"Java 内存态增长、浏览器实时面板空白"：浏览器侧 window.__rolePicks 因去重 / iframe / 导航时序
     * 未被可靠填充，导致点击时面板列表看不到已拾元素。此处以 Java 侧为准，保证面板实时反映已拾内容。
     * 仅用于面板展示；代码生成仍走 javaPickBySig（见 runPickerCommand），不受影响。
     */
    /**
     * 【关键修复"整页/区域扫描后 iframe 内元素不进面板"】
     * 由 Java 侧遍历 page.frames()，读回各 iframe 自己的 window.__rolePicks（Playwright 协议访问不受
     * file:// 跨源限制），按 sigKey 去重后显式合并进【主框架】window.__rolePicks 并触发渲染，使面板与
     * readPickSnapshot（读主框架）都能看到 iframe 内元素。整页扫描与区域扫描共用。
     */
    private static void mergeFramePicksToMain(Page page, LinkedHashMap<String, RoleEntry> javaPickBySig) {
        if (page == null || page.isClosed()) return;
        for (com.microsoft.playwright.Frame f : page.frames()) {
            if (f == null || f.equals(page.mainFrame())) continue;
            try {
                // 【关键修复"合并 iframe 拾取失败：SyntaxError: Unexpected end of input"】
                // 现象：区域扫描穿透 iframe 后，日志每 ~1s 报一次
                //   [picker] 合并 iframe 拾取到主框架失败（url=...picker-iframe-*.html）
                //   Error{ message='SyntaxError: Unexpected end of input at eval' }
                // 根因：iframe 的 pick 含中文名称 / file:// URL / 特殊字符。旧实现是 JSON.stringify 后把
                // 结果字符串【拼进】page.evaluate 的 JS 里（var arr = " + j + "），Playwright 对这段含中文
                // 的 JS 二次解析必然抛 SyntaxError；改为直接传 List<Map> 参数（evaluate(script, arg)）时，
                // Playwright 序列化含特殊字符的 pick 对象内联进表达式仍可能产生不完整 JS（Unexpected end
                // of input，且嵌套 iframe 的 grandchild 更明显）。
                // 最稳妥：在 iframe 上下文先 JSON.stringify 成【字符串】，再把该【字符串】作为参数传给
                // 主框架 evaluate(script, json) —— Playwright 序列化字符串值会正确转义，JS 内 JSON.parse
                // 还原成数组，彻底避免任何 JS 语法错误。
                Object frameJson = f.evaluate("() => JSON.stringify(window.__rolePicks||[])");
                if (frameJson instanceof String) {
                    final String json = (String) frameJson;
                    if (!json.isEmpty() && !"[]".equals(json.trim())) {
                        // 【关键修复"区域扫描穿透了 iframe 却仍看不到 iframe 元素"】
                        // iframe 自扫结果进【iframe 自己的 __rolePicks】，但若没回传 Java（__roleOnPick/console
                        // 兜底链路在 file:// 跨源/嵌套场景偶发失效），javaPickBySig 就缺 iframe 元素——
                        // 而 syncPanelToBrowser 每次用 javaPickBySig【重建】主框架 __rolePicks（虽不清空，
                        // 但 mergeFramePicksToMain 合并进来的 iframe 元素若无 javaPickBySig 支撑，后续生成/
                        // 同步路径仍会缺）。故此处 Java 侧把 iframe __rolePicks 解析成 RoleEntry 一并写入
                        // javaPickBySig，使 iframe 元素进入权威内存态，与主框架合并+面板展示+代码生成对齐。
                        try {
                            @SuppressWarnings("unchecked")
                            java.util.List<?> arr = GSON.fromJson(json, java.util.List.class);
                            if (arr != null) {
                                synchronized (javaPickBySig) {
                                    for (Object item : arr) {
                                        try {
                                            if (!(item instanceof java.util.Map)) continue;
                                            @SuppressWarnings("unchecked")
                                            java.util.Map<Object, Object> m = (java.util.Map<Object, Object>) item;
                                            String strat = asString(m.get("strategy"));
                                            String nm = asString(m.get("name"));
                                            if ("text".equals(strat) && nm != null && nm.length() >= 25) continue; // 与 __roleScanPage 一致剔除整块文本
                                            RoleEntry e = parsePick(m);
                                            if (e == null) continue;
                                            // 复用 __roleOnPick 的 backfill：浏览器 framePath 缺失时用 Java 侧计算真实框架路径
                                            if (e.getFramePath() == null || e.getFramePath().isEmpty()) {
                                                try {
                                                    java.util.List<String> fp = computeFramePath(page, f);
                                                    if (fp != null && !fp.isEmpty()) e.setFramePath(fp);
                                                } catch (Exception ignore) {}
                                            }
                                            String key = pickDedupKey(m, e);
                                            if (key != null && !key.isEmpty()) {
                                                // 【关键修复"删除所有元素后 iframe 元素又复活"】
                                                // 删除只清主框架 __rolePicks + Java 权威内存态 + 主框架 __deletedSigs，
                                                // iframe 自己的 __rolePicks 仍残留已删元素；主循环每轮空闲调用本方法把
                                                // iframe 残留无条件合并回 javaPickBySig（mergePickIntoMap 无任何删除屏蔽），
                                                // 导致已删 iframe 元素复活（用户实测：删除全部 10 个后 iframe 元素又回来）。
                                                // 修复：合并写入前按 STATE_DELETED（跨扫描/跨页面持久已删集合）校验，
                                                // 命中已删键的元素一律跳过，不写入权威内存态——从 Java 侧根治复活。
                                                if (isDeletedKeyInState(javaPickBySig, key, e, m)) continue;
                                                mergePickIntoMap(javaPickBySig, key, e);
                                            }
                                        } catch (Exception ignore) {}
                                    }
                                }
                            }
                        } catch (Exception ignore) {}
                        // 【彻底修复"合并 iframe 拾取到主框架失败：SyntaxError: Unexpected end of input"】
                        // 根因：Playwright Java 的 page.evaluate(script, arg) 会把 String/复杂 arg 拼接进
                        // JS 表达式并二次编译，含中文/特殊字符时抛 SyntaxError；此前先后尝试标准 Base64、
                        // Base64URL 均不可靠。且经诊断，iframe 元素已通过 __roleOnPick/console 回传进入 Java
                        // 权威内存态 javaPickBySig（本方法第 5459 行 mergePickIntoMap 已写入），主循环的
                        // syncPanelToBrowser 会以 javaPickBySig 为准回灌主框架 __rolePicks，因此这里的
                        // page.evaluate 合并是【冗余】的——其唯一副作用是同步主框架 __rolePicks，而该同步
                        // 由主循环负责。故直接移除这段跨 JS 传参合并，从根上消除 SyntaxError，功能不受影响。
                    }
                }
            } catch (Exception fe) {
                // url() 可能触发跨 frame 异常，捕获即可
                try {
                    String feMsg = fe.getMessage() == null ? "" : fe.getMessage();
                    // 登录页动态 iframe（crossdomain.html / about:blank）在导航/关闭/context 销毁瞬间被
                    // evaluate 属正常生命周期竞态（TargetClosedError / Frame was detached），并非合并逻辑
                    // 故障，降级为 debug 避免刷屏；其余真实合并异常仍 WARN 暴露。
                    if (feMsg.contains("closed") || feMsg.contains("detached")
                            || feMsg.contains("TargetClosed") || feMsg.contains("Target page")) {
                        log.debug("[picker] 跳过已关闭/分离的 iframe（url={}）：{}", f.url(), feMsg);
                    } else {
                        log.warn("[picker] 合并 iframe 拾取到主框架失败（url={}）：{}", f.url(), feMsg);
                    }
                } catch (Exception ignore) {}
            }
        }
    }

    /**
     * 把 Java 权威拾取内存态（javaPickBySig）按目标页 pageClass 过滤后同步到该页浏览器面板展示数组
     * window.__rolePicks 并触发渲染。
     * 修复"当前跟随页(current[0])不是用户正在点击的页时，那个页面的面板空白、看不到已拾元素"：
     * 改为对每个被跟踪页面分别同步（调用处遍历 pageNames），使任一页面的面板都能实时反映 Java 侧已拾内容。
     * 仅用于面板展示；代码生成仍走 javaPickBySig（见 runPickerCommand），不受影响。
     * 注意：不再清空 window.__rolePickSigs，避免干扰浏览器端真实点击的去重计数。
     */
    private static void syncPanelToBrowser(Page page, LinkedHashSet<String> pageClasses, LinkedHashMap<String, RoleEntry> state) {
        syncPanelToBrowser(page, pageClasses, state, false);
    }

    /**
     * 把 Java 权威内存态回灌浏览器侧 __rolePicks。
     * @param overwriteNos true=用户经面板显式编辑序号（repickNos）后调用，整体覆盖浏览器侧旧 _pickNos，
     *                     不与其并集（避免"旧序号被并回"导致编辑序号不生效）。
     *                     false=常规拾取回传同步，保留浏览器侧更长 _pickNos 以修复 i18n 并发回传丢号竞态。
     */
    private static void syncPanelToBrowser(Page page, LinkedHashSet<String> pageClasses, LinkedHashMap<String, RoleEntry> state, boolean overwriteNos) {
        if (page == null || page.isClosed() || state == null) return;
        try {
            // O2：ETag 短路——主循环每 ~1s 调一次，但拾取集未变时没必要重算并 evaluate 全量大对象。
            // 用「元素身份+选择器+序号」拼成签名，与上次同页同步比较，相同则直接跳过（含浏览器渲染），
            // 大幅减少拾取静止期以及大拾取集下的 evaluate / JSON 序列化开销。
            StringBuilder sig = new StringBuilder();
            sig.append(pageClasses == null ? "*" : pageClasses.toString());
            for (RoleEntry e : state.values()) {
                String pc = e.getPageClass();
                if (pageClasses == null || pc == null || pc.isEmpty() || pageClasses.contains(pc)) {
                    sig.append('\u0001').append(e.getSigKey()).append('|')
                       .append(e.getStrategy()).append('|').append(e.getSelector())
                       .append('|').append(e.getIndex())
                       // 序号数组纳入签名：仅改拾取序号（如点 +/删除）时元素身份不变，
                       // 若不纳入，ETag 短路会让 syncPanelToBrowser 跳过回灌，导致浏览器侧
                       // _pickNos 被此前某次用旧 pickNos 的回灌覆盖、面板序号不刷新。
                       .append('|').append(e.getPickNos() == null ? "" : e.getPickNos());
                }
            }
            String newSig = sig.toString();
            String prev = LAST_SYNC_SIG.get(page);
            if (newSig.equals(prev)) return;   // 内容未变，跳过整轮同步
            LAST_SYNC_SIG.put(page, newSig);
            // 仅取归属该页任一历史页类的拾取（pageClass 为空的元素兜底同步到所有页，避免漏显示）。
            // 关键修复：用"该页经历过的全部页类集合"而非"当前页类"过滤，使整页跳转后旧页(_pageClass=旧类)
            // 元素仍保留在面板，实现跨页拾取累积可见（不再被新页类过滤冲掉）。
            List<RoleEntry> filtered = new ArrayList<>();
            for (RoleEntry e : state.values()) {
                String pc = e.getPageClass();
                // pageClasses 为 null 表示同步全部（跨页累积不丢，按页类由浏览器子 Tab 分组展示）；
                // 否则只同步归属该页任一历史页类的元素。
                if (pageClasses == null || pc == null || pc.isEmpty() || pageClasses.contains(pc)) filtered.add(e);
            }
            String json = GSON.toJson(filtered);
            // 【diag-sync】写出前逐条列出本次回灌浏览器的每个 entry 的 key 与 pickNos，
            // 用于确认 syncPanelToBrowser 是否把(错误地)为 null/旧值的 pickNos 覆盖回浏览器、冲掉累积序号。
            for (RoleEntry e : filtered) {
                log.info("[picker][diag-sync] write-back key={} sigKey={} strategy={} pickNos={}", pickDedupKey(new LinkedHashMap<Object,Object>(){{put("_sigKey", e.getSigKey());put("_pageClass", e.getPageClass());}}, e), e.getSigKey(), e.getStrategy(), e.getPickNos());
            }
            // 【修复"删除后整页重新扫描一直为 0"】
            // 旧实现把会话级 STATE_DELETED 持久集合推给浏览器做 window.__deletedSigs，面板据此永久隐藏已删元素；
            // 但用户删除后若重新整页扫描，新识别的元素即便已重新入库 javaPickBySig，仍会被 __deletedSigs 命中隐藏，
            // 表现为"再扫描一直都是 0"。删除的语义应只是"从当前内存态移除"（已由 collectDeleteKeys 的 ① ② ③
            // 兜底 + 源头清空 iframe 残留完整覆盖），不应永久封杀该元素再次出现。
            // 故面板同步【不再】下发 STATE_DELETED 隐藏列表——面板始终以 javaPickBySig 为准（已删元素本就不在此
            // 集合内），用户重新扫描即可正常显示。空数组场景 JS indexOf 仍安全。
            String delJson = "[]";
            // 【关键修复"区域扫描穿透不了 frame"】旧实现把 GSON.toJson 生成的 JSON（含中文名称 / file://
            // URL 的 iframe 元素）直接【拼进】page.evaluate 的 JS 表达式（var arr = " + json + "），且该 JS
            // 表达式内还带大量中文注释；Playwright 对含非 ASCII 字符的 JS 表达式二次解析会抛
            // SyntaxError: Unexpected end of input → 同步被 catch 静默吞掉 → 主框架 __rolePicks 不更新，
            // 面板永远看不到 iframe 内元素。修复：json/delJson 改为【参数传递】（evaluate(script, json, del)），
            // 并由 Playwright 安全序列化字符串参数；JS 表达式内【移除所有中文注释】，只保留 ASCII。
            // 【加固】实测确认：Playwright Java 的 page.evaluate(script, arg) 会把 String/复杂 arg 拼接进
            // JS 表达式，若含中文或 + / = 等特殊字符（标准 Base64、原始 json）会二次解析 SyntaxError。
            // 故 json/delJson 先 Base64URL 无 padding（仅 [A-Za-z0-9_-]，不含破坏语法的字符）再作为参数，
            // JS 端先还原成标准 Base64 再 atob+decodeURIComponent+escape 还原 UTF-8 JSON。
            String syncJsonB64 = java.util.Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            String syncDelB64 = java.util.Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(delJson.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            page.evaluate(
                    "(args) => {"
                    + " try {"
                    + "   function __dec(s){ var b=s.replace(/-/g, '+').replace(/_/g, '/'); return decodeURIComponent(escape(atob(b))); }"
                    + "   var arr = JSON.parse(__dec(args[0]));"
                    + "   var del2 = JSON.parse(__dec(args[1]));"
                    + "   if (!(arr instanceof Array)) arr = [];"
                    + "   if (!(del2 instanceof Array)) del2 = [];"
                    + "   if (window.__clearMatchCache) window.__clearMatchCache();"
                    + "   function toPick(p){ if(!p) return p; var o={};"
                    + "     o.strategy=p.strategy; o.role=p.role; o.name=p.name;"
                    + "     o.key=(p.resolvedKey!=null)?p.resolvedKey:undefined;"
                    + "     o.id=(p.strategy==='id' && p.selector)? String(p.selector).replace(/^#/, '') : undefined;"
                    + "     o.css=(p.strategy==='css')?p.selector:undefined;"
                    + "     o.index=p.index; o._pageClass=p.pageClass;"
                    + "     o._sigKey=(p.sigKey!=null&&p.sigKey!=='')?p.sigKey:undefined;"
                    // 透传全局拾取顺序号数组 _pickNos（如 [1,4,7]）：Java 权威内存态已持有（parsePick 解析），
                    // 回灌浏览器时原样写出，避免 syncPanelToBrowser 重建 pick 时丢失 → 跨页导航 index 重置。
                    + "     o._pickNos=(p.pickNos)?p.pickNos:undefined;"
                    + "     return o; }"
                    // 关键修复"repickNos/加号/删除后面板序号不刷新"：此前为 push + __rolePickSigs[k] 去重模式，
                    // 已存在的元素被跳过 push，浏览器侧旧 pick 对象（带着旧 _pickNos）永不更新；repickNos 虽把
                    // 最新 nos 写回 Java 权威态并强制重算 ETag，但 evaluate 重跑时仍因 __rolePickSigs[k] 命中而
                    // 跳过 → 面板序号被旧值钉死。改为【先清空再整体重建】：以 Java 权威内存态为准重建
                    // window.__rolePicks（含最新 _pickNos），保证三侧（面板/Java/快照）序号始终一致。
                    // 安全：主循环每轮已先 mergeFramePicksToMain 把 iframe 元素并入 javaPickBySig，重建不丢元素；
                    // 浏览器侧加号/删除均经 repickNos 同步回 Java，重建时与 Java 态对齐、无回退。
                    + "   var __overwrite = " + (overwriteNos ? "true" : "false") + ";"
                    + "   var __oldNos = {};"
                    + "   (window.__rolePicks||[]).forEach(function(p){ try{ var kk=(p&&p._sigKey)||(typeof window.__pickSig==='function'?window.__pickSig(p):''); if(kk&&Array.isArray(p._pickNos)) __oldNos[kk]=p._pickNos; }catch(e){} });"
                    + "   window.__rolePicks = [];"
                    + "   window.__rolePickSigs = {};"
                    + "   arr.forEach(function(p){"
                    + "     var o = toPick(p);"
                    + "     o._sig = (typeof window.__pickSig==='function') ? (window.__pickSig(o)||'') : '';"
                    + "     var k = (typeof window.__sigKey==='function') ? window.__sigKey(o)"
                    + "            : ((o&&(o._sigKey||o._sig))||null);"
                    + "     if (k) o._sigKey = k;"
                    // 关键修复"sync 重建用残缺 Java 态覆盖浏览器累积序号"：Java 权威态因并发回传竞态
                    // 可能短暂只持有 [2]（真实应为 [2,5,6,7]），而浏览器侧 accumulator 才是完整累积。
                    // 重建时若浏览器旧 __rolePicks 中同 _sigKey 元素持有更长的 _pickNos，则以浏览器侧为准，
                    // 杜绝"sync 一轮就把累积序号冲回旧值"的自循环（label/i18n 元素反复丢失序号的根因）。
                    // 例外：__overwrite=true 表示本次 sync 来自"用户经面板显式编辑序号(repickNos)"，
                    // Java 权威态已是用户意图的完整值，必须整体覆盖、禁止与浏览器旧 _pickNos 并集，
                    // 否则旧序号会被并回，表现为"面板 add/去除序号不生效"。
                    + "     var __old = (__overwrite) ? null : ((k && __oldNos[k]) ? __oldNos[k] : null);"
                    + "     if (__old && Array.isArray(o._pickNos)) {"
                    + "       var __set = {}; var __keep = [];"
                    + "       __old.concat(o._pickNos).forEach(function(n){ if(n!=null && !__set['_'+n]){ __set['_'+n]=1; __keep.push(n); } });"
                    + "       o._pickNos = __keep;"
                    + "     } else if (__old) { o._pickNos = __old; }"
                    + "     var __del = window.__deletedSigs || {};"
                    // 仅按含 pageClass 的 k（=__sigKey）命中已删屏蔽集，裸 o._sig 跨页同名会误屏蔽另一页面共用元素。
                    + "     if (k && (__del[k] || del2.indexOf(k) >= 0)) return;"
                    + "     if (k) window.__rolePickSigs[k]=true;"
                    + "     window.__rolePicks.push(o); });"
                    + "   if (window.__renderPicks) window.__renderPicks();"
                    + " } catch(e){} }",
                    java.util.Arrays.asList(syncJsonB64, syncDelB64));
        } catch (Exception syncE) {
            // 保留日志（而非静默吞）以便诊断：若面板未显示 iframe 元素 / 删除后残留，可由此定位。
            try { log.warn("[picker] 同步面板到浏览器失败：{}", syncE.getMessage()); } catch (Exception ignore) {}
        }
    }

    /**
     * 自愈式保活：会话处于拾取中时，确保某页的点击捕获监听确实已挂载。
     * 任意页面变化（整文档替换、frame 内部跳转、onFrameNavigated 未覆盖到的边界情形）导致监听被静默丢弃后，
     * 主循环空闲期据此重挂 START_SCRIPT，保证"页面如何变化都能继续拾取"（用户核心需求）。
     * 重挂同时补注入 nls 反向查表，使导航后拾取仍能把 a11y name 反查为 key。
     * 幂等：监听已存活则零成本返回，不会重复挂载（START_SCRIPT 内部 __rolePickActive 早退 + 此处先探测）。
     */
    private static void ensurePickingActive(Page page, String nlsReverseJson, String[] nlsFiles) {
        if (page == null || page.isClosed()) return;
        try {
            // 会话处于拾取中（调用方已用 active[0] 守卫）：无条件重挂（幂等）监听，
            // 确保"停止后再开始 / 跳转到新页面 / 框架静默移除 document 级监听"等任何情形下点击必能拾取。
            // 关键修复：移除了原先的"if (active && hasClick) return"早退。
            // 该早退在"window.__rolePickActive 仍为 true 但 click 监听已被框架/导航静默移除"的竞态下会跳过重挂，
            // 表现即"停止后再点开始却拾取不了 / 跳转到新页面拾取不到"——因为激活态显示 true、函数引用还在（hasClick 为真），
            // 于是误判"无需重挂"，而真实监听早已不工作。START_SCRIPT 对同函数引用 addEventListener 幂等、
            // 不重复定义库，按 1s 节奏重挂安全无副作用，故此处改为"会话开则必重挂"。
            page.evaluate("try{localStorage.setItem('__rolePickSessionOn','1');}catch(e){}"
                    + " try{window.__rolePickSessionOn=true;}catch(e){}"
                    + " var __o = " + (nlsReverseJson == null ? "{}" : nlsReverseJson) + ";"
                    + " window.__nlsReverse = (__o && __o.exact) ? __o.exact : (__o && __o.templates ? {} : (__o || {}));"
                    + " window.__nlsTemplates = (__o && __o.templates) ? __o.templates : [];"
                    + "             if (typeof window.__roleGatedStart === 'function') { window.__roleGatedStart(); } else { " + START_SCRIPT + " }");
            // 自愈保活不仅要重挂主框架监听，还须对所有 frame（含弹窗/新页面内的任意嵌套 iframe）重新注入拾取脚本。
            // 否则"打开新页面 / window.open 弹窗 / 链接点击新标签"等场景，其内嵌 iframe 在自愈时不会被重新注入，
            // 表现为弹窗内 iframe 元素拾取不到。registerFrameInjection 对 page.frames() 递归返回的全部层做全量兜底，
            // 与 onFrameNavigated 兜底同源，覆盖 frame 内嵌 frame 的复合情况。
            registerFrameInjection(page, nlsReverseJson);
        } catch (Exception ignore) {}
    }

    /** 停止命令一次性读取的拾取态快照（合并多趟 evaluate 以降低延迟）。 */
    private static final class PickSnapshot {
        final String pageClass;
        final List<RoleEntry> entries;
        final List<StepRec> steps;
        final List<PageOp> ops;
        PickSnapshot(String pageClass, List<RoleEntry> entries, List<StepRec> steps, List<PageOp> ops) {
            this.pageClass = pageClass; this.entries = entries; this.steps = steps; this.ops = ops;
        }
    }

    /** 只读式读取当前页面拾取态的 JS 表达式（返回含 pageClass/picks/steps/ops 的对象字面量）。
     *  与 {@link #STOP_SCRIPT} 拼接即可在"停止"时一次性完成去激活 + 收尾 + 读回，避免多次往返。 */
    private static final String PICK_STATE_READER_JS = "(function(){"
            + " function norm(s){ var t=(s&&typeof s==='object')?s:null;"
            + "   var pc=(t&&typeof t.pageClass==='string')?t.pageClass:'';"
            + "   var ps=(t&&t.picks)?t.picks:(Array.isArray(s)?s:[]);"
            + "   return {pageClass:pc, picks:ps}; }"
            // 读取兜底去重：window.__rolePicks 在 start() 重注入清空 __rolePickSigs + 重建、与
            // pageshow 恢复 / syncPanelToBrowser 每轮同步交错时，可能因重建竞态残留重复项（同组元素整组重复）。
            // 这里在回传 Java 前按权威键 __mergeKey 压缩一次，保证生成链路拿到的 picks 永不重复，
            // 无论浏览器侧数组因何种时序竞态累积了副本，最终页面类都不会出现重复字段。
            + " if (typeof window.__mergeKey !== 'function') { window.__mergeKey = function(p){ try{"
            + "   if (!p) return '';"
            + "   if (p._sigKey) return p._sigKey;"
            + "   if (typeof window.__sigKey === 'function') return window.__sigKey(p);"
            + "   var pk = p._pageClass || '';"
            + "   if (!pk) { try { pk = (location.origin||'') + (location.pathname||''); } catch(e){} }"
            + "   return JSON.stringify([p._sig || '', pk]);"
            + " }catch(e){ return ''; } }; }"
            + " var __seen = {}; var __out = [];"
            + " (window.__rolePicks||[]).forEach(function(p){ try{"
            + "   var k = window.__mergeKey(p);"
            + "   if (!k) { __out.push(p); return; }"  // 极端兜底：无键者原样保留，不丢元素
            + "   if (__seen[k]) return; __seen[k] = true; __out.push(p);"
            + " }catch(e){ __out.push(p); } });"
            + " return {"
            + "   pageClass: (window.__rolePageName||''),"
            + "   picks: __out,"
            + "   steps: Array.from(window.__steps||[]).filter(function(s){"
            + "     return !(s&&typeof s==='object'&&typeof s.op==='string'); }).map(norm),"
            + "   ops: Array.from(window.__steps||[]).filter(function(s){"
            + "     return (s&&typeof s==='object'&&typeof s.op==='string'); })"
            + "     .map(function(s){ return {pageClass:(s.pageClass||''), op:s.op}; })"
            + " };"
            + "})()";

    /** 单次 {@code page.evaluate} 即取回「已拾元素 + step 序列 + 页面级操作 + 当前页类名」，
     *  把停止命令原本分散的多次浏览器往返合并为一次，降低点击"停止"的响应延迟。 */
    @SuppressWarnings("unchecked")
    private static PickSnapshot readPickSnapshot(Page page) {
        return parsePickSnapshot(page.evaluate(PICK_STATE_READER_JS));
    }

    /** "停止"命令专用：单次 {@code page.evaluate} 同时完成「去激活 + 收尾当前 step + 读回全部拾取态」，
     *  把原本 stop()（1 次）+ 收尾 evaluate（1 次）+ readPickSnapshot（1 次）三次往返合并为 1 次，
     *  点击"停止"即时得到快照并转交代码生成，不再串行等待多次 Java↔浏览器往返（企业级：减少关键路径往返）。 */
    @SuppressWarnings("unchecked")
    private static PickSnapshot stopAndRead(Page page) {
        // 与 stop() 一致：先清除会话开关（阻断门控注入脚本在后续新文档自启拾取），
        // 再去激活 + 收尾 + 读回，全部合并进同一次 evaluate（最后一个表达式的值即快照）。
        return parsePickSnapshot(page.evaluate(
                "try{localStorage.removeItem('__rolePickSessionOn');}catch(e){}"
                + " try{window.__rolePickSessionOn=false;}catch(e){}"
                + STOP_SCRIPT + ";" + PICK_STATE_READER_JS));
    }

    /** 把 {@code page.evaluate} 返回的拾取态对象解析为 {@link PickSnapshot}（容错：非 Map 返回空快照）。 */
    @SuppressWarnings("unchecked")
    private static PickSnapshot parsePickSnapshot(Object raw) {
        List<RoleEntry> entries = new ArrayList<>();
        List<StepRec> steps = new ArrayList<>();
        List<PageOp> ops = new ArrayList<>();
        String pageClass = "";
        if (raw instanceof Map) {
            Map<Object, Object> m = (Map<Object, Object>) raw;
            pageClass = asString(m.get("pageClass"));
            Object p = m.get("picks");
            if (p instanceof List) {
                java.util.Set<String> seenKeys = new java.util.HashSet<>();
                for (Object o : (List<Object>) p) {
                    if (!(o instanceof Map)) continue;
                    Map<Object, Object> om = (Map<Object, Object>) o;
                    RoleEntry e = parsePick(om);
                    if (e == null) continue;
                    // 生成链路兜底去重：与 getEntries / PICK_STATE_READER 同口径，
                    // 保证无论浏览器侧 window.__rolePicks 因何种竞态累积了重复副本，生成的页面类都不会出现重复字段。
                    String dk = pickDedupKey(om, e);
                    if (!dk.isEmpty() && !seenKeys.add(dk)) continue;
                    entries.add(e);
                }
            }
            Object st = m.get("steps");
            if (st instanceof List) for (Object o : (List<Object>) st) {
                if (!(o instanceof Map)) continue;
                Map<Object, Object> sm = (Map<Object, Object>) o;
                String pc = asString(sm.get("pageClass"));
                List<RoleEntry> sp = new ArrayList<>();
                Object ps = sm.get("picks");
                if (ps instanceof List) for (Object it : (List<Object>) ps)
                    if (it instanceof Map) { RoleEntry e = parsePick((Map<Object, Object>) it); if (e != null) sp.add(e); }
                steps.add(new StepRec(pc, sp));
            }
            Object op = m.get("ops");
            if (op instanceof List) for (Object o : (List<Object>) op) {
                if (!(o instanceof Map)) continue;
                Map<Object, Object> om = (Map<Object, Object>) o;
                String oop = asString(om.get("op"));
                String opc = asString(om.get("pageClass"));
                if (oop != null && !oop.isEmpty()) ops.add(new PageOp(opc, oop));
            }
        }
        return new PickSnapshot(pageClass, entries, steps, ops);
    }

    /** 一次 step 记录：含所属页面类与本次拾取的元素列表（多页面归类用）。 */
    private static final class StepRec {
        final String pageClass;
        final List<RoleEntry> picks;
        StepRec(String pageClass, List<RoleEntry> picks) {
            this.pageClass = pageClass;
            this.picks = picks;
        }
    }

    /** 一次「页面级操作」记录：如关闭页面（op='close'）。区别于元素拾取 step，不产生元素字段。 */
    private static final class PageOp {
        final String pageClass;
        final String op;
        PageOp(String pageClass, String op) {
            this.pageClass = pageClass;
            this.op = op;
        }
    }

    /**
     * 读取当前已登记的「页面级操作」序列（如关闭页面），供代码生成器产出 closeCurrentPage() 等步骤。
     * 与 {@link #getStepsWithPage} 互补：元素 step 走 getStepsWithPage，页面操作 step 走本方法。
     */
    @SuppressWarnings("unchecked")
    private static List<PageOp> getPageOpsWithPage(Page page) {
        List<PageOp> result = new ArrayList<>();
        Object raw = page.evaluate("Array.from(window.__steps || []).filter(function(s){"
                + " return (s && typeof s === 'object' && typeof s.op === 'string'); })"
                + ".map(function(s){ return {pageClass:(s.pageClass||''), op:s.op}; })");
        if (raw instanceof List) {
            for (Object o : (List<Object>) raw) {
                if (!(o instanceof Map)) continue;
                Map<Object, Object> m = (Map<Object, Object>) o;
                String op = asString(m.get("op"));
                String pc = asString(m.get("pageClass"));
                if (op != null && !op.isEmpty()) result.add(new PageOp(pc, op));
            }
        }
        return result;
    }


    /**
     * 读取当前已拾取的「step 序列」（不阻塞、不生成）。
     * 每个内层 List 是一次「开始 → 停止」拾取出的元素；外层 List 为 step 顺序。
     * 兼容多页面格式：每条 step 可能是数组（旧格式）或 {pageClass, picks} 对象（新格式）。
     */
    @SuppressWarnings("unchecked")
    public static List<List<RoleEntry>> getSteps(Page page) {
        List<List<RoleEntry>> result = new ArrayList<>();
        for (StepRec st : getStepsWithPage(page)) {
            result.add(st.picks);
        }
        return result;
    }

    /**
     * 读取 step 序列并保留每条 step 的所属页面类（用于多页面代码生成按页分组）。
     * 兼容两种格式：旧格式（数组的数组）缺 pageClass（归到当前页），新格式（{pageClass, picks}）。
     */
    @SuppressWarnings("unchecked")
    private static List<StepRec> getStepsWithPage(Page page) {
        List<StepRec> result = new ArrayList<>();
        // 在浏览器内把两种格式归一为 {pageClass, picks}；picks 仍是原始 pick 对象数组。
        // 过滤掉"页面级操作"step（含 op 字段，如关闭页面），它们由 getPageOpsWithPage 单独处理，
        // 否则会被当成"空 pick 的 step"生成无意义方法。
        Object raw = page.evaluate("Array.from(window.__steps || []).filter(function(s){"
                + " return !(s && typeof s === 'object' && typeof s.op === 'string'); }).map(function(s){"
                + " var t = (s && typeof s === 'object') ? s : null;"
                + " var pc = (t && typeof t.pageClass === 'string') ? t.pageClass : '';"
                + " var ps = (t && t.picks) ? t.picks : (Array.isArray(s) ? s : []);"
                + " return {pageClass: pc, picks: ps}; })");
        if (raw instanceof List) {
            for (Object o : (List<Object>) raw) {
                if (!(o instanceof Map)) continue;
                Map<Object, Object> m = (Map<Object, Object>) o;
                String pc = asString(m.get("pageClass"));
                List<RoleEntry> picks = new ArrayList<>();
                java.util.Set<String> seenKeys = new java.util.HashSet<>();
                Object ps = m.get("picks");
                if (ps instanceof List) {
                    for (Object item : (List<Object>) ps) {
                        if (!(item instanceof Map)) continue;
                        Map<Object, Object> itemMap = (Map<Object, Object>) item;
                        RoleEntry e = parsePick(itemMap);
                        if (e == null) continue;
                        // 二次兜底去重：浏览器侧读取层已按 __mergeKey 压缩，这里再用 Java 侧权威键
                        // pickDedupKey 过滤，保证无论哪条同步/恢复路径遗漏，生成的页面类字段不会重复。
                        String dk = pickDedupKey(itemMap, e);
                        if (!dk.isEmpty() && !seenKeys.add(dk)) continue;
                        picks.add(e);
                    }
                }
                result.add(new StepRec(pc, picks));
            }
        }
        return result;
    }

    /**
     * 定位器唯一型策略：同一 locator 跨页面指向同一元素，回传去重时按 locator 签名（_sig）而非 [sig, pageClass|URL]，
     * 避免"回到主页 / 关弹窗"时同一元素被重复收录（如 id=logoHeader 在主页与弹窗各存一份、主页元素多出一份）。
     * 角色策略（role+name）与 closeOp 仍按页面作用域（_sigKey）区分，保留跨页同名元素各自独立。
     */
    private static final java.util.Set<String> LOCATOR_IDENTITY_STRATEGIES = new java.util.HashSet<>(java.util.Arrays.asList(
            "id", "css", "i18n", "text", "title", "placeholder", "label", "testid", "altText"));

    /**
     * 判断 javaPickBySig 权威内存态（mem）是否需要替换浏览器读取的步骤 pick（p）。
     * 覆盖四类增强字段，任一存在差异即替换（幂等，只往"更完整/点击后"方向收敛）：
     *   · checked/setCheckedTarget：final 不可变，无法 merge——若 mem 已反映"点击后状态"而 p 是"点击前状态"，
     *     必须整体替换，否则生成 setCheckedTarget 为点击前值，回放时 checkbox 勾选结果与用户点击后的页面状态相反
     *     （表现为"点击未勾选 checkbox 却生成 setChecked(false)，没选择上"）。
     *   · framePath：mem 含 backfill（iframe 内 console 通道），p 可能为空 → 需替换以生成 switchToFrame。
     *   · dialog / popup：mem 含 Java onDialog/onPopup 双保险标记，p 可能缺失。
     */
    private static boolean needsReplaceByMemory(RoleEntry p, RoleEntry mem) {
        if (p == null || mem == null || p == mem) return false;
        // checked 差异（final 不可 merge）
        if (!java.util.Objects.equals(p.getChecked(), mem.getChecked())) return true;
        if (!java.util.Objects.equals(p.getSetCheckedTarget(), mem.getSetCheckedTarget())) return true;
        // framePath：p 缺、mem 有 → 替换；两者皆非空且相同则不必
        List<String> inFp = p.getFramePath();
        List<String> memFp = mem.getFramePath();
        boolean inEmpty = (inFp == null || inFp.isEmpty());
        boolean memEmpty = (memFp == null || memFp.isEmpty());
        if (inEmpty && !memEmpty) return true;
        if (!inEmpty && !memEmpty && !inFp.equals(memFp)) return true;
        // dialog
        if (!p.isDialog() && mem.isDialog()) return true;
        // popup
        if (!p.isPopup() && mem.isPopup()) return true;
        return false;
    }

    /**
     * 计算拾取回传的权威去重键：
     * - 定位器唯一型策略（i18n/id/css/text/title/...）按「去索引的稳定 locatorKey」去重，
     *   与页面字段 RoleElementPageGenerator.locatorKey 语义一致（均不含 #index）。
     *   ⚠️ 旧实现对这类策略直接用含 #index 的 _sig（如 i18n:forgot_username_title#0）作 key——
     *   同一 data-i18n 值若页面上有多个匹配（count>1），或区域重扫/勾选顺序变化导致 __attachIndex
     *   重新分配 #index 时，删除回传的 _sig 与入库时的 _sig 不一致，javaPickBySig.remove 命中失败，
     *   表现为"data-i18n 元素删了又复活"。改用去索引 locatorKey 后，删除 key 稳定，必能命中。
     * - 其余（role/closeOp）仍按页面作用域键（_sigKey，含 pageClass、不含 index）区分，保留原行为。
     */
    private static String pickDedupKey(Map<Object, Object> m, RoleEntry e) {
        if (m == null) return "";
        Object sig = m.get("_sig");
        Object sigKeyRaw = m.get("_sigKey");
        // 归一化去重键：浏览器侧 __sigKey 对 i18n/role 等元素生成的是 JSON 数组形式
        // ["sig","pageClass"]（如 ["i18n:user_name#0","LogonPage"]），而首次拾取的回传 _sigKey
        // 是 JS 侧 __recordPick 写入的字符串形式 "pageClass|sig"（如 "LogonPage|i18n:user_name"）。
        // 同一元素两次回传的 key 形式不一致会导致 mergePickIntoMap 命中不同条目，重复点击的序号
        // 数组（_pickNos）被存到数组键下、而展示/生成沿用首次字符串键的残缺值 → 序号混乱。
        // 此处把数组形式统一归一为 "pageClass|sig" 字符串，与首次拾取键对齐，确保累积命中同一 entry。
        String sigKey = asString(sigKeyRaw);
        if (sigKey != null && sigKey.startsWith("[")) {
            try {
                List<?> arr = GSON.fromJson(sigKey, List.class);
                if (arr != null && arr.size() == 2) {
                    String s0 = asString(arr.get(0));
                    String s1 = asString(arr.get(1));
                    if (s1 != null && s0 != null) {
                        // 去掉 sig 末尾的 #index 后缀（如 "i18n:user_name#0" → "i18n:user_name"），
                        // 对齐首次拾取字符串键（LogonPage|i18n:user_name 不含 #0），保证同元素不同形式键命中同一 entry。
                        s0 = s0.replaceAll("#\\d+$", "");
                        sigKey = s1 + "|" + s0;
                    }
                }
            } catch (Exception ignore) { /* 保留原值 */ }
        }
        String strategy = (e != null) ? e.getStrategy() : null;
        boolean locatorIdentity = strategy != null && LOCATOR_IDENTITY_STRATEGIES.contains(strategy);
        // 【方案 B：页面级隔离】内存态去重键一律绑定所属 pageClass，使不同页面上 role/name 完全相同
        // 的「共用元素」（如各页页脚链接、Close/Next、HSBC App tab）彻底按页分桶，互不干扰：
        // 删除某页元素时不再波及其它页同名元素（此前 i18n/定位器型策略的 locatorKey 不含 pageClass，
        // 两个页面的同名页脚共享同一 key，删 SetupSecondPwdPage 的页脚会误删 LoginPage 的同名页脚）。
        // pageClass 优先取 RoleEntry（已固化），避免浏览器侧重算键时因 _pageClass 缺失退化到 location 兜底。
        // pageClass 优先取 RoleEntry（已固化），其次回退到原始回传里的 _pageClass，
        // 避免浏览器侧重算键时因 pageClass 缺失退化到裸键（方案 B 隔离会失效）。
        String pc = (e != null && e.getPageClass() != null) ? e.getPageClass()
                : (m != null ? asString(m.get("_pageClass")) : null);
        if (pc == null) pc = "";
        String dedupKey;
        if (locatorIdentity) {
            String lk = RoleElementPageGenerator.locatorKey(e);
            if (lk != null && !lk.isEmpty()) { dedupKey = pc + "|" + lk; log.info("[picker][diag-dedup] strategy={} branch=locatorIdentity key={} _sigKey(raw)={}", strategy, dedupKey, sigKeyRaw); return dedupKey; }
        }
        // role/closeOp 分支：_sigKey 已内嵌 pageClass（JSON.stringify([_sig, pageClass])），
        // 与浏览器 __rolePicks 的 _sigKey 同构，删除/本地过滤均可精确命中，保持原行为。
        if (sigKey != null) { dedupKey = String.valueOf(sigKey); log.info("[picker][diag-dedup] strategy={} branch=sigKey key={} _sigKey(raw)={}", strategy, dedupKey, sigKeyRaw); return dedupKey; }
        // 【方案 B 兜底】_sigKey 缺失时绝不能退化成裸 _sig——否则 LoginPage / SetupSecondPwdPage
        // 上同名共用元素（Language、HSBC App tab、各页脚链接，_sig 完全相同）会共享同一裸键，
        // 删一页即误删另一页。此处一律前缀 pageClass，确保即使缺 _sigKey 也维持按页隔离。
        if (sig != null) {
            String s = String.valueOf(sig);
            dedupKey = pc.isEmpty() ? s : pc + "|" + s;
            log.info("[picker][diag-dedup] strategy={} branch=fallback(_sig) key={} _sigKey(raw)={}", strategy, dedupKey, sigKeyRaw);
            return dedupKey;
        }
        log.info("[picker][diag-dedup] strategy={} branch=EMPTY key=\"\" _sigKey(raw)={}", strategy, sigKeyRaw);
        return "";
    }

    /**
     * 把一次拾取回传（incoming）写入权威内存态 map，并作「增强字段合并」而非直接覆盖。
     *
     * <p>一条 pick 可能经两条通道先后到达：① 绑定桥 __roleOnPick（exposeBinding，可拿到
     * source.frame() 做 framePath 回补，且能拿到 pageClass）；② 控制台兜底 __roleOnPick::...
     * （onConsoleMessage）。两条通道的 RoleEntry 是各自独立 parsePick 出来的对象，若直接 put 覆盖，
     * 后到者会把先到者的已补全字段（framePath / dialog / popup …）抹掉，最终生成代码缺 switchToFrame、
     * 缺 acceptAlert。
     *
     * <p>故此处保留「先到者为基线、后到者补齐空缺」：incoming 优先作为写入对象，但它缺失而已有条目
     * 已具备的增强字段（框架路径、对话框标记、弹窗标记）会被继承回来，使两次回传互为补充而非互相抵消。
     *
     * @return 最终写入 map 的 RoleEntry（供调用方日志使用）
     */
    /** 回传去重辅助：null-safe 等值比较（用于 sigKey 比较）。 */
    private static boolean roleEq(String a, String b) {
        if (a == null) return b == null;
        return a.equals(b);
    }
    /** 回传去重辅助：null-safe 的 framePath 列表等值比较（用于判断是否"增强了框架路径"）。 */
    private static boolean framePathEq(List<String> a, List<String> b) {
        if (a == null) return b == null;
        if (b == null) return false;
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            String x = a.get(i), y = b.get(i);
            if (x == null ? y != null : !x.equals(y)) return false;
        }
        return true;
    }

    private static RoleEntry mergePickIntoMap(LinkedHashMap<String, RoleEntry> map, String key, RoleEntry incoming) {
        if (map == null || key == null || incoming == null) return incoming;
        RoleEntry existing = map.get(key);
        if (existing != null && existing != incoming) {
            // 框架路径：取「非空且更长（嵌套更深、更具体）」的那条，避免任一通道（绑定/控制台）
            // 因各自 parsePick 出的 RoleEntry 独立、互相覆盖时把已补全的 framePath 抹掉。
            // 例：控制台通道先到（__enrichState 已含 framePath）而绑定通道后到（computeFramePath 回补），
            // 或反之——二者皆非空时以更深的链为准；任一为空则继承另一方，杜绝「iframe 元素生成缺 switchToFrame」。
            List<String> inFp = incoming.getFramePath();
            List<String> exFp = existing.getFramePath();
            boolean inEmpty = (inFp == null || inFp.isEmpty());
            boolean exEmpty = (exFp == null || exFp.isEmpty());
            // existing 是最终写回 map 的权威对象（见下方 return），补全须同时落到 existing 与 incoming，
            // 避免返回 existing 时丢失 incoming 补全出的 framePath（否则 iframe 元素生成缺 switchToFrame）。
            if (inEmpty && !exEmpty) {
                incoming.setFramePath(exFp);
                existing.setFramePath(exFp);
            } else if (!inEmpty && !exEmpty && exFp.size() > inFp.size()) {
                // 旧条目链更深（更具体）→ 用旧链；同深时保留新条目（已是 incoming）
                incoming.setFramePath(exFp);
                existing.setFramePath(exFp);
            } else if (!inEmpty && !exEmpty && inFp.size() > exFp.size()) {
                // 新条目链更深 → 用新链补全 existing
                existing.setFramePath(inFp);
            }
            // 对话框标记：任一为 true 即视为触发（onDialog 回写 / 浏览器侧 hook 检测）
            if (!incoming.isDialog() && existing.isDialog()) {
                incoming.setDialog(true);
                existing.setDialog(true);
                if (existing.getDialogType() != null) { incoming.setDialogType(existing.getDialogType()); existing.setDialogType(existing.getDialogType()); }
                if (existing.getDialogAction() != null) { incoming.setDialogAction(existing.getDialogAction()); existing.setDialogAction(existing.getDialogAction()); }
            }
            // 弹窗标记：任一为 true 即视为触发（onPopup 回写）
            if (!incoming.isPopup() && existing.isPopup()) { incoming.setPopup(true); existing.setPopup(true); }
        }
        // 【修复"同一元素重复点击，序号数组被回传 null/旧值覆盖"——覆盖 existing==incoming 同引用场景】
        // 现象（22:36 日志实证）：i18n 元素 label 第三次/四次点击时，dup-send 正确发出 pickNos=[2,5,6,7]，
        // 但 Java 端收到一条「不含 _pickNos 字段」的二次回传（与 existing 同引用），parsePick 以
        // setPickNos(parsePickNos(null)) 把它抹成残缺值，且因 incoming==existing 跳过了原并集保护，
        // 最终内存态只残留 [2]。
        // 故将 pickNos 并集保护【移出 existing!=incoming 判定】：无论引用是否相同，只要 existing 已持有
        // 更大的序号集合，就用「existing ∪ incoming」去重后的更大集覆盖，任一通道传来的 null/残缺值都不会
        // 抹掉已累积的完整序号。并集为空才保留 incoming 原值（兼容首条无序号的候选）。
        if (existing != null) {
            java.util.List<Integer> inNos = incoming.getPickNos();
            java.util.List<Integer> exNos = existing.getPickNos();
            // 【修复"普通拾取回传把累积序号[4,5]冲成[1,2,3]"】
            // 旧实现用 exNos ∪ inNos 并集：当浏览器侧经 repickNos 已把序号重排成 [4,5]（Java 权威态已正确），
            // 又来一条 dup 二次投递 / iframe 自扫回传携带的"部分快照"（如 [1,2] 或 [2]）时，
            // 并集会变成 [1,2,4,5]→重排成 [1,2,3,4]，或 incoming 直接覆盖成短值 [1,2,3]，
            // 把面板真实累积的 [4,5]/[6,7] 抹掉；后续常规 syncPanelToBrowser(overwrite=false)
            // 再以这个被污染的 Java 态为准重建浏览器面板 → 面板序号退回 [1,2,3]。
            // 修复：普通拾取回传【不拥有重置/覆盖全局序号的权限】——只在 incoming 携带的 _pickNos
            // 比 existing 更"完整/更新"（去重集合更大，或含更大的号）时才用 incoming 替换；
            // 否则保留 existing 的累积序号。唯一能整体覆盖序号的是 repickNos（overwriteNos=true 路径）。
            // 这样既保留"首次无序号候选"的正确初始化，又杜绝短值覆盖长值。
            java.util.List<Integer> chosen = pickMoreComplete(exNos, inNos);
            log.info("[picker][diag-merge] key={} incomingNos={} existingNos={} -> chosenNos={}", key, inNos, exNos, (chosen == null ? "null" : chosen));
            if (chosen != null) {
                // 【修复"dup 回传的完整 [2,5,6,7,9] 被 CONSOLE 空回传覆盖回 [2]"】
                // 旧实现用 `if (existing != incoming) existing.setPickNos(...)` 早退：当 BIND 通道与 CONSOLE 通道
                // 对同一元素的两条回传并发到达时，若 existing 与 incoming 是不同对象，existing（map 内持有引用）
                // 的 pickNos 不会被更新，导致 map 中残留旧短值 [2]；随后 CONSOLE 空回传再以 existing=[2] 兜底，
                // 把刚累积的 [2,5,6,7,9] 彻底抹掉（本日志实证：user_name 最终只剩 [2]）。
                // 修复：始终把 chosen 写回【map 内持有的 existing 对象】（若 existing 存在），并同时更新 incoming；
                // 最后 map.put(key, existing != null ? existing : incoming) 保证 map 引用的是被更新的对象。
                // 这样任一通道携带的完整 _pickNos 都不会被另一通道的 null/残缺值覆盖。
                java.util.List<Integer> __chosenCopy = new java.util.ArrayList<>(chosen);
                if (existing != null) existing.setPickNos(__chosenCopy);
                incoming.setPickNos(__chosenCopy);
            }
        }
        map.put(key, existing != null ? existing : incoming);
        return existing != null ? existing : incoming;
    }

    /**
     * 把「删除回传」原始数组统一折算为要移除的内存态 map key 集合。
     * 兼容两种格式：
     * ① 完整 pick 对象数组（新格式）：对每个 pick 用与入库时完全一致的 {@link #pickDedupKey} 重算 key，
     *    从而精确命中「定位器唯一型策略」（key=_sig）与「role 策略」（key=_sigKey）——这是修复"删除无效"的关键；
     * ② 纯 key 字符串数组（旧格式兼容）：直接作为待删键。
     */
    @SuppressWarnings("unchecked")
    private static java.util.Set<String> collectDeleteKeys(List<?> raw) {
        java.util.Set<String> dead = new java.util.HashSet<>();
        if (raw == null) return dead;
        for (Object o : raw) {
            if (o == null) continue;
            if (o instanceof Map) {
                Map<Object, Object> m = (Map<Object, Object>) o;
                RoleEntry e = parsePick(m);
                if (e != null) {
                    // 多通道兜底：javaPickBySig 的真实 key 由 pickDedupKey 决定（方案 B 下已绑定 pageClass）。
                    // 主删除键 k1 即 pickDedupKey，与入库 key 完全同构，确保精确命中；其余 _sig/_sigKey/
                    // sigKey 形态仅作冗余兜底（互不重复加入）。
                    // 【方案 B】删除原「去索引兜底」分支：它把 _sig 去 #index 后（如 "role:link:Privacy...footer"）
                    // 作为跨页共享键加入 dead，会导致删某页页脚时其它页同名页脚被一并清除（误删）。
                    // 方案 B 下 locator 入库键本身就是「pageClass|去索引locatorKey」，k1 已能稳定命中，
                    // 不再需要也不允许去索引跨页兜底。
                    String pc = (e.getPageClass() != null) ? e.getPageClass() : "";
                    String k1 = pickDedupKey(m, e);
                    if (k1 != null && !k1.isEmpty()) dead.add(k1);
                    String k2 = asString(m.get("_sig"));
                    // 定位器型策略的 _sig 可能带 #index，补一个「带 pageClass 前缀」形态以兼容旧数据，
                    // 但务必绑定 pageClass，绝不退化为跨页共享键（否则 SetupSecondPwdPage 删页脚会把
                    // LoginPage 同名页脚的裸 _sig 一并加入 dead，后续 isDeletedKeyInState 又按裸 _sig
                    // 把另一页同名元素永久屏蔽）。故 k2 也一律带 pc 前缀。
                    if (k2 != null && !k2.isEmpty()) {
                        String k2pc = pc + "|" + k2;
                        if (!k2pc.equals(k1)) dead.add(k2pc);
                        if (k2.length() > 2 && Character.isDigit(k2.charAt(k2.length() - 1))) {
                            String k2base = pc + "|" + k2.replaceAll("#\\d+$", "");
                            if (!k2base.isEmpty() && !dead.contains(k2base)) dead.add(k2base);
                        }
                    }
                    String k3 = asString(m.get("_sigKey"));
                    if (k3 != null && !k3.isEmpty() && !k3.equals(k1) && !k3.equals(k2)) dead.add(k3);
                    String k4 = e.getSigKey();
                    if (k4 != null && !k4.isEmpty() && !dead.contains(k4)) dead.add(k4);
                }
            } else {
                String s = String.valueOf(o);
                if (!s.isEmpty()) dead.add(s);
            }
        }
        return dead;
    }

    /** 把一次拾取返回的 map 解析为 {@link RoleEntry}（getEntries 与 getSteps 共用，保证解析一致）。 */
    @SuppressWarnings("unchecked")
    private static RoleEntry parsePick(Map<Object, Object> m) {
        String strategy = asString(m.get("strategy"));
        if (strategy == null || strategy.isBlank()) {
            strategy = "role";
        }
        String role = asString(m.get("role"));
        String name = asString(m.get("name"));
        String tag = asString(m.get("tag"));
        String text = asString(m.get("text"));
        boolean popup = Boolean.parseBoolean(asString(m.get("popup")));
        boolean download = Boolean.parseBoolean(asString(m.get("download")));
        boolean hover = Boolean.parseBoolean(asString(m.get("hover")));
        boolean dblClick = Boolean.parseBoolean(asString(m.get("dblclick")));
        boolean closeOp = Boolean.parseBoolean(asString(m.get("_closeOp")));
        // 原生对话框（alert/confirm/prompt）：前端拦截后打标记，Java 侧解析并映射到 RoleEntry
        boolean dialog = Boolean.parseBoolean(asString(m.get("dialog")));
        String dialogType = asString(m.get("dialogType"));
        String dialogAction = asString(m.get("dialogAction"));
        if (dialogType != null && dialogType.isBlank()) dialogType = null;
        if (dialogAction != null && dialogAction.isBlank()) dialogAction = null;
        // 下拉选择（combobox/listbox）：选中项可见文本 + 选项值（对齐 codegen selectOption 信号）
        boolean select = Boolean.parseBoolean(asString(m.get("select")));
        String optionText = asString(m.get("optionText"));
        if (optionText != null && optionText.isBlank()) optionText = null;
        String optionValue = asString(m.get("optionValue"));
        if (optionValue != null && optionValue.isBlank()) optionValue = null;
        // 复选框勾选状态：true=已勾选（check()）/ false=未勾选（uncheck()）/ null=非复选框
        Boolean checked = null;
        String checkedRaw = asString(m.get("checked"));
        if (checkedRaw != null && !checkedRaw.isBlank()) checked = Boolean.parseBoolean(checkedRaw);
        // 复选框「目标」勾选状态（对齐 page.pause 的 setChecked）：JS 侧在 checkbox 点击后写入的
        // checked 即「操作后」状态，作为 setChecked 的目标值（setChecked 在已满足时幂等跳过，避免误 toggle）。
        Boolean setCheckedTarget = checked;
        // 键盘序列（对齐 page.pause 的 press("Enter")）：用户在输入框聚焦态按的实质按键（非字符输入）。
        String pressKey = asString(m.get("pressKey"));
        if (pressKey != null && pressKey.isBlank()) pressKey = null;
        // 拖拽目标元素定位签名（对齐 page.pause 的 dragTo）：仅拖拽源 pick 非 null。
        String dragDstKey = asString(m.get("dragDstKey"));
        if (dragDstKey != null && dragDstKey.isBlank()) dragDstKey = null;
        // 可访问状态过滤属性（对齐 page.pause() 的 getByRole setDisabled/setPressed/setExpanded）。
        // JS 侧 done() 仅当元素确有该状态时写入 "YES"/"NO"（见 RoleElement.State 三态语义）。
        RoleElement.State disabled = toState(asString(m.get("disabled")));
        RoleElement.State pressed = toState(asString(m.get("pressed")));
        RoleElement.State expanded = toState(asString(m.get("expanded")));
        int index = parseIndex(m.get("index"));
        int level = parseLevel(m.get("level"));
        // 浏览器端固化的元素永久身份键：所有入站链路（exposeBinding 回传 / console 兜底 / 快照读取）
        // 都经本方法解析，故在此统一透传，保证 Java 内存态实体始终携带 _sigKey，
        // 供 syncPanelToBrowser 原样回灌浏览器（详见 RoleEntry#getSigKey 的根因说明）。
        String pickSigKey = asString(m.get("_sigKey"));
        if (pickSigKey != null && pickSigKey.isBlank()) pickSigKey = null;
        if ("role".equals(strategy)) {
            if (role == null && !closeOp) return null;   // 角色策略但无角色：跳过（关闭操作标记除外）
            String resolvedKey = asString(m.get("key"));
            if (resolvedKey != null && resolvedKey.isBlank()) resolvedKey = null;
            boolean cleaned = Boolean.parseBoolean(asString(m.get("cleaned")));
            String value = asString(m.get("value"));
            if (value != null && value.isBlank()) value = null;
            RoleEntry roleEntry = new RoleEntry(role, name, tag, text, "role", null, resolvedKey, cleaned, value, popup, index, download, asString(m.get("_pageClass")), hover, closeOp, level, dblClick, dialog, dialogType, dialogAction, select, optionText, optionValue, checked, setCheckedTarget, pressKey, dragDstKey, disabled, pressed, expanded);
            roleEntry.setSigKey(pickSigKey);
            int roleCount = parseCount(m.get("count"));
            roleEntry.setCount(roleCount);
            roleEntry.setFramePath(parseFramePath(m.get("framePath")));
            // 透传「归属空间」与「连续编号」：space 标注元素位于哪个 iframe/shadow，seq 为用户勾选连续序号。
            String rSpace = asString(m.get("space"));
            if (rSpace != null && !rSpace.isBlank()) roleEntry.setSpace(rSpace);
            roleEntry.setShadowPath(parseFramePath(m.get("shadowPath")));
            // 【修复"step 序号错乱（后拾取元素首号偏小）"】seq 是排序基准，必须反映"用户首次点击该元素的真实动作号"，
            // 只增不回退，不能被 _pickNos 污染。
            // 旧逻辑 seq = _pickNos[0]：但 _pickNos 是会被 Java 每轮 syncPanelToBrowser 整体重建的数组，
            // 并发回传竞态下 Java 权威态可能只持有短值（如 user_name 仅 [2]），重建后浏览器侧 _pickNos 退化成短值，
            // 于是 seq 跟着变成 2，导致"后点的元素排到前面 / 序号错乱"。
            // 修复：优先取 _pickSeq（浏览器侧由只增不回退的 __pickOrder 固化，= 首次真实动作号），
            // 其次才回退 _pickNos 首号 / 原 seq，使顺序严格等于用户点击先后。
            roleEntry.setSeq(parseSeq(m.get("_pickSeq")));
            if (roleEntry.getSeq() == 0) {
                List<Integer> __pn = parsePickNos(m.get("_pickNos"));
                if (__pn != null && !__pn.isEmpty()) roleEntry.setSeq(__pn.get(0));
                else roleEntry.setSeq(parseSeq(m.get("seq")));
            }
            roleEntry.setPageInstanceId(parseInstanceId(m.get("_pageInstanceId")));
            roleEntry.setUrl(asString(m.get("_url")));
            roleEntry.setPickNos(parsePickNos(m.get("_pickNos")));
            return roleEntry;
        }
        String selector = buildSelector(strategy, m);
        if (selector == null || selector.isBlank()) return null;
        String resolvedKey = asString(m.get("key"));
        if (resolvedKey != null && resolvedKey.isBlank()) resolvedKey = null;
        boolean cleaned = Boolean.parseBoolean(asString(m.get("cleaned")));
        String value = asString(m.get("value"));
        if (value != null && value.isBlank()) value = null;
        RoleEntry entry = new RoleEntry(role, name, tag, text, strategy, selector, resolvedKey, cleaned, value, popup, index, download, asString(m.get("_pageClass")), hover, closeOp, level, dblClick, dialog, dialogType, dialogAction, select, optionText, optionValue, checked, setCheckedTarget, pressKey, dragDstKey, disabled, pressed, expanded);
        entry.setSigKey(pickSigKey);
        int nonRoleCount = parseCount(m.get("count"));
        entry.setCount(nonRoleCount);
        entry.setFramePath(parseFramePath(m.get("framePath")));
        // 透传「归属空间」与「连续编号」：space 标注元素位于哪个 iframe/shadow，seq 为用户勾选连续序号。
        String nrSpace = asString(m.get("space"));
        if (nrSpace != null && !nrSpace.isBlank()) entry.setSpace(nrSpace);
        entry.setShadowPath(parseFramePath(m.get("shadowPath")));
        // 【修复"step 序号错乱（后拾取元素首号偏小）"】同角色策略分支：优先 _pickSeq（只增不回退的真实首次动作号），
        // 其次才回退 _pickNos 首号 / 原 seq，使排序严格等于用户点击先后，不被短值污染的 _pickNos 干扰。
        entry.setSeq(parseSeq(m.get("_pickSeq")));
        if (entry.getSeq() == 0) {
            List<Integer> __pn = parsePickNos(m.get("_pickNos"));
            if (__pn != null && !__pn.isEmpty()) entry.setSeq(__pn.get(0));
            else entry.setSeq(parseSeq(m.get("seq")));
        }
        entry.setPageInstanceId(parseInstanceId(m.get("_pageInstanceId")));
        entry.setPickNos(parsePickNos(m.get("_pickNos")));
        return entry;
    }

    /** 从 readPickStateJson 的快照 JSON 解析出某页的拾取列表（页面已关闭时回退用）。 */
    @SuppressWarnings("unchecked")
    private static List<RoleEntry> entriesFromState(String json) {
        List<RoleEntry> r = new ArrayList<>();
        Map<String, Object> m = parseState(json);
        Object picks = m.get("picks");
        if (picks instanceof List) {
            for (Object o : (List<?>) picks) {
                if (o instanceof Map) {
                    RoleEntry e = parsePick((Map<Object, Object>) o);
                    if (e != null) r.add(e);
                }
            }
        }
        return r;
    }

    /** 从 readPickStateJson 的快照 JSON 解析出某页的 step 序列（页面已关闭时回退用）。 */
    @SuppressWarnings("unchecked")
    private static List<List<RoleEntry>> stepsFromState(String json) {
        List<List<RoleEntry>> r = new ArrayList<>();
        Map<String, Object> m = parseState(json);
        Object steps = m.get("steps");
        if (steps instanceof List) {
            for (Object o : (List<?>) steps) {
                List<RoleEntry> step = new ArrayList<>();
                if (o instanceof List) {
                    for (Object it : (List<?>) o) {
                        if (it instanceof Map) {
                            RoleEntry e = parsePick((Map<Object, Object>) it);
                            if (e != null) step.add(e);
                        }
                    }
                }
                r.add(step);
            }
        }
        return r;
    }

    /** 把 readPickStateJson 产出的 JSON 解析为 Map（容错：异常/空返回空 Map）。 */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseState(String json) {
        if (json == null || json.isBlank()) return new LinkedHashMap<>();
        try {
            Map<String, Object> m = GSON.fromJson(json, MAP_STRING_OBJECT_TYPE);
            return m == null ? new LinkedHashMap<>() : m;
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }

    /** 解析一组元素序号；缺省 / 非数值 / 负值均归一为 -1（唯一匹配，无需 nth）。 */
    private static int parseIndex(Object v) {
        if (v == null) return -1;
        try {
            int i = (v instanceof Number) ? ((Number) v).intValue() : Integer.parseInt(v.toString().trim());
            return i >= 0 ? i : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** 解析定位器匹配总数；缺省 / 非数值 / 非正均归一为 1（唯一匹配）。 */
    private static int parseCount(Object v) {
        if (v == null) return 1;
        try {
            int i = (v instanceof Number) ? ((Number) v).intValue() : Integer.parseInt(v.toString().trim());
            return i > 0 ? i : 1;
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    /** 解析用户勾选连续序号（seq）：缺省 / 非数值 / 非正数均归一为 0（未编号）。 */
    private static int parseSeq(Object v) {
        if (v == null) return 0;
        try {
            int i = (v instanceof Number) ? ((Number) v).intValue() : Integer.parseInt(v.toString().trim());
            return i > 0 ? i : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** 解析页面实例序号；缺省 / 非法 / <1 均归一为 1（首个实例）。 */
    private static int parseInstanceId(Object v) {
        if (v == null) return 1;
        try {
            int i = (v instanceof Number) ? ((Number) v).intValue() : Integer.parseInt(v.toString().trim());
            return i >= 1 ? i : 1;
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    /** 解析全局拾取顺序号数组（_pickNos）：浏览器侧为 [1,4,7] 形式的数字数组。
     *  缺省 / 非数组 / 元素非数值均归一为 null（面板将走 __rolePicks 位次兜底）。去重保序、剔除<=0。 */
    @SuppressWarnings("unchecked")
    private static java.util.List<Integer> parsePickNos(Object v) {
        if (!(v instanceof java.util.List)) return null;
        java.util.List<?> raw = (java.util.List<?>) v;
        java.util.List<Integer> out = new java.util.ArrayList<>();
        for (Object o : raw) {
            if (o == null) continue;
            int n;
            try { n = (o instanceof Number) ? ((Number) o).intValue() : Integer.parseInt(o.toString().trim()); }
            catch (NumberFormatException e) { continue; }
            if (n > 0 && !out.contains(n)) out.add(n);
        }
        return out.isEmpty() ? null : out;
    }

    /**
     * 在「普通拾取回传合并」时，从 existing 与 incoming 两侧 _pickNos 中选出"更完整/更新"的那一侧。
     * 规则：
     * ① 任一侧为 null/空，则取另一侧（兼容首次无序号候选初始化）；
     * ② 两侧均非空时，取【去重集合元素更多】的一侧；集合数相同则取【含最大号更大】的一侧
     *    （如 [4,5] 胜出 [1,2]，[1,2,3] 胜出 [1,2]）；仍相同则取并集兜底。
     * 目的：普通拾取回传不得用 dup 二次投递 / iframe 自扫携带的"部分短快照"覆盖或并回
     * 面板经 repickNos 真实累积出的全局序号（如 [4,5]/[6,7]），避免序号被冲回 [1,2,3]。
     */
    private static java.util.List<Integer> pickMoreComplete(java.util.List<Integer> exNos, java.util.List<Integer> inNos) {
        java.util.Set<Integer> exSet = (exNos == null) ? java.util.Collections.emptySet() : new java.util.LinkedHashSet<>(exNos);
        java.util.Set<Integer> inSet = (inNos == null) ? java.util.Collections.emptySet() : new java.util.LinkedHashSet<>(inNos);
        if (exSet.isEmpty() && inSet.isEmpty()) return null;
        if (exSet.isEmpty()) return new java.util.ArrayList<>(inSet);
        if (inSet.isEmpty()) return new java.util.ArrayList<>(exSet);
        if (inSet.size() > exSet.size()) return new java.util.ArrayList<>(inSet);
        if (exSet.size() > inSet.size()) return new java.util.ArrayList<>(exSet);
        // 集合大小相同：比较最大号，取更大的一侧
        int exMax = 0, inMax = 0;
        for (int n : exSet) if (n > exMax) exMax = n;
        for (int n : inSet) if (n > inMax) inMax = n;
        if (inMax > exMax) return new java.util.ArrayList<>(inSet);
        if (exMax > inMax) return new java.util.ArrayList<>(exSet);
        // 完全相等：去重并集兜底（保序）
        java.util.LinkedHashSet<Integer> uni = new java.util.LinkedHashSet<>(exSet);
        uni.addAll(inSet);
        return new java.util.ArrayList<>(uni);
    }

    /** 解析标题层级；缺省 / 非数值 / 非 1–6 均归一为 0（不限层级）。 */
    private static int parseLevel(Object v) {
        if (v == null) return 0;
        try {
            int n = (v instanceof Number) ? ((Number) v).intValue() : Integer.parseInt(v.toString().trim());
            return (n >= 1 && n <= 6) ? n : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** 解析 iframe 嵌套路径：浏览器侧以数组回传（如 ["iframe[name=\"a\"]","#b"]），缺省/非数组返回 null。 */
    @SuppressWarnings("unchecked")
    private static List<String> parseFramePath(Object v) {
        if (v == null) return null;
        List<String> out = new ArrayList<>();
        if (v instanceof List) {
            for (Object o : (List<?>) v) {
                if (o != null) out.add(o.toString());
            }
        } else if (v instanceof String) {
            String s = v.toString().trim();
            if (s.startsWith("[") && s.endsWith("]")) {
                // JSON 数组字符串（console 兜底回传场景）：简单切分
                String inner = s.substring(1, s.length() - 1).trim();
                if (!inner.isEmpty()) {
                    for (String part : inner.split(",")) {
                        String p = part.trim();
                        if (p.startsWith("\"") && p.endsWith("\"")) p = p.substring(1, p.length() - 1);
                        if (!p.isEmpty()) out.add(p);
                    }
                }
            } else if (!s.isEmpty()) {
                out.add(s);
            }
        }
        return out.isEmpty() ? null : out;
    }

    /** 把 JS 侧 done() 写入的可访问状态字符串（"YES"/"NO"）解析为 {@link RoleElement.State}；其余一律 null（不限）。 */
    private static RoleElement.State toState(String s) {
        if (s == null) return null;
        s = s.trim();
        if (s.equalsIgnoreCase("YES")) return RoleElement.State.YES;
        if (s.equalsIgnoreCase("NO")) return RoleElement.State.NO;
        return null;
    }


    /**
     * 从拾取脚本返回的原始片段构建 Playwright 字符串选择器（在 Java 侧统一转义，
     * 避免在 JS 文本块里处理引号转义）。
     */
    private static String buildSelector(String strategy, Map<Object, Object> m) {
        switch (strategy) {
            case "testid":
            case "placeholder":
            case "altText":
            case "title": {
                String attr = asString(m.get("attr"));
                String value = asString(m.get("value"));
                if (attr == null || value == null) return null;
                return "[" + attr + "=\"" + escapeSelectorValue(value) + "\"]";
            }
            case "i18n": {
                String value = asString(m.get("value"));
                if (value == null || value.isBlank()) return null;
                return "[data-i18n=\"" + escapeSelectorValue(value) + "\"]";
            }
            case "text": {
                String name = asString(m.get("name"));
                if (name == null || name.isBlank()) return null;
                return "text=\"" + escapeSelectorValue(name) + "\"";
            }
            case "label": {
                // 对齐 page.pause 的 getByLabel：selector 仅作占位/人工核对，
                // 生成注解与运行期定位均走 @RoleElement(label=...) → byLabel。
                String name = asString(m.get("name"));
                if (name == null || name.isBlank()) return null;
                return "label=\"" + escapeSelectorValue(name) + "\"";
            }
            case "id": {
                String id = asString(m.get("id"));
                return (id == null || id.isBlank()) ? null : "#" + id;
            }
            case "css": {
                String css = asString(m.get("css"));
                return (css == null || css.isBlank()) ? null : css;
            }
            default:
                return null;
        }
    }

    /** 转义选择器值中的反斜杠与双引号，供 CSS 属性选择器 / text= 引擎安全使用。 */
    private static String escapeSelectorValue(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /**
     * 阻塞式拾取：开启模式 → 等待用户点击（ESC 结束或超时）→ 关闭模式 → 返回拾取列表。
     * 期间测试线程会等待，用户在真实（有头）浏览器中点击目标元素。
     *
     * @return 已拾取的 {@link RoleEntry} 列表（可能为空的草稿）
     */
    /**
     * 阻塞式拾取：开启模式 → 等待用户点击（ESC 结束或超时）→ 关闭模式 → 返回拾取列表。
     * 期间测试线程会等待，用户在真实（有头）浏览器中点击目标元素。
     *
     * @return 已拾取的 {@link RoleEntry} 列表（可能为空的草稿）
     */
    public static List<RoleEntry> pick(Page page) {
        // 显式传空 String[]（非 varargs 调用，类型精确），避免 null 传给 String... 触发 imprecise varargs 警告
        return pick(page, new String[0]);
    }

    /**
     * 阻塞式拾取：开启模式（预加载 nls 反向查表）→ 等待用户点击 → 关闭 → 返回列表。
     * 若 {@code nlsFile} 非 null，拾取交互角色元素时会用 a11y name 反查 nls key，
     * 命中则生成的 {@code @RoleElement} 直接复用真实 key，未命中回退 slug。
     *
     * @param page      Playwright Page（须已导航到目标页，且为 headed 浏览器）
     * @param nlsFiles   nls 文件路径（classpath 相对或文件系统绝对）；null 表示不反查
     * @return 已拾取的 {@link RoleEntry} 列表（可能为空的草稿）
     */
    public static List<RoleEntry> pick(Page page, String... nlsFiles) {
        // CI 环境：拾取是本地开发工具，不应开启/注入或阻塞等待人工拾取，直接返回空列表。
        if (isCiRun()) {
            log.info("[picker] 检测到 CI 运行环境，跳过拾取（pick）。");
            return new ArrayList<>();
        }
        String reverse = buildNlsReverseJson(Arrays.asList(nlsFiles));
        start(page, reverse);
        try {
            page.waitForFunction("() => window.__pickDone === true", null,
                    new Page.WaitForFunctionOptions().setTimeout(0));
        } catch (Exception e) {
            log.warn("[picker] 拾取等待结束（超时或中断），将生成已拾取的部分。");
        }
        List<RoleEntry> entries = getEntries(page);
        stop(page);
        log.info("[picker] 已拾取 {} 个元素。", entries.size());
        return entries;
    }

    /** 拾取并直接生成源码字符串（不落盘） */
    public static String pickAndGenerate(Page page, String packageName,
                                         String pageClassName, String... nlsFiles) {
        List<RoleEntry> entries = pick(page, nlsFiles);
        if (entries.isEmpty()) {
            log.warn("[picker] 未拾取到任何元素，未生成代码。");
            return "";
        }
        return RoleElementPageGenerator.generate(entries, packageName, pageClassName, nlsFiles);
    }

    /** 拾取并打印生成的源码到日志 */
    public static void pickAndDump(Page page, String packageName,
                                   String pageClassName, String... nlsFiles) {
        List<RoleEntry> entries = pick(page, nlsFiles);
        if (entries.isEmpty()) {
            log.warn("[picker] 未拾取到任何元素，未生成代码。");
            return;
        }
        RoleElementPageGenerator.dump(entries, packageName, pageClassName, nlsFiles);
    }

    /** 拾取并直接写入文件（outputDir 为源码根，如 src/test/java） */
    public static void pickAndWrite(Page page, String outputDir, String packageName,
                                    String pageClassName, String... nlsFiles) {
        List<RoleEntry> entries = pick(page, nlsFiles);
        if (entries.isEmpty()) {
            log.warn("[picker] 未拾取到任何元素，未生成代码。");
            return;
        }
        RoleElementPageGenerator.write(entries, outputDir, packageName, pageClassName, nlsFiles);
    }

    /**
     * 在页面上弹出一个可复制的代码面板（类似 {@code page.pause()} 的浮层）。
     * 面板含只读代码框 + 「复制代码」/「关闭」按钮；点「关闭」后本方法返回。
     * 阻塞等待用户关闭（不自动超时，直至用户点「关闭」或页面跳转）。
     *
     * @param code 要展示/复制的源码
     */
    public static void showCode(Page page, String code) {
        // CI 环境：不注入任何拾取/代码面板脚本。
        if (isCiRun()) {
            log.info("[picker] 检测到 CI 运行环境，跳过代码面板（showCode）。");
            return;
        }
        page.evaluate("window.__pickerCode = " + GSON.toJson(code));
        page.evaluate(SHOW_PANEL_SCRIPT);
        log.info("[picker] 代码面板已弹出：点『复制代码』复制，点『关闭』结束。");
        try {
            page.waitForFunction("() => window.__codePanelClosed === true", null,
                    new Page.WaitForFunctionOptions().setTimeout(0));
        } catch (Exception e) {
            log.warn("[picker] 代码面板等待结束（超时或页面跳转）。");
        }
    }

    /**
     * 一站式：点选元素 → 生成代码 → 在页面弹出可复制的代码面板。
     * 最贴近 {@code page.pause()} 的体验：拾取完直接弹框，复制即用。
     */
    public static void pickAndShow(Page page, String packageName,
                                   String pageClassName, String... nlsFiles) {
        List<RoleEntry> entries = pick(page, nlsFiles);
        if (entries.isEmpty()) {
            log.warn("[picker] 未拾取到任何元素，未生成代码。");
            return;
        }
        String code = RoleElementPageGenerator.generate(entries, packageName, pageClassName, nlsFiles);
        showCode(page, code);
    }

    /**
     * 打开一个常驻控制面板（类似 {@code page.pause()} 的 inspector），由图标控件驱动整个拾取流程：
     * <ul>
     *   <li>▶/⏹ 切换控件：空闲时显示"开始拾取"（▶，绿），点后进入点选模式并在页面点击目标元素；
     *       拾取中自动变为"停止拾取"（⏹，橙），再点即退出点选并按已点元素生成 {@code @RoleElement} 代码填入面板</li>
     *   <li>📋 复制代码：一键复制面板中的代码</li>
     *   <li>⏻ 终止运行：抛出 {@link PickerAbortedException}，中断调用方后续代码</li>
     *   <li>✕ 关闭面板：标题栏右上角的 X 图标（对齐 {@code page.pause()} 的 inspector 关闭），退出面板</li>
     * </ul>
     * 调用后本方法会阻塞，直到用户关闭面板或终止运行。
     *
     * @param page           Playwright Page（须已导航到目标页，且为 headed 浏览器）
     * @param packageName    生成类的包名
     * @param pageClassName  生成类名
     * @param stepClassName  步骤类名（Tab2 生成用；通常取 {@code pageClassName + "Steps"}）
     * @param nlsFiles       类级 {@code @RoleFile} 路径（可变参数）
     * @throws PickerAbortedException 用户点击『终止运行』时
     */
    /**
     * 一站式：打开拾取面板（简化重载）。
     *
     * <p>只接收 {@code page} 与 NLS 文件，<b>page / steps 类名由当前页面 URL 自动派生</b>，
     * 与框架对弹窗/导航产生的新页面的命名规则一致（{@link #pageClassNameFromUrl} +
     * {@code GLOBAL_URL_TO_CLASS}：取 URL path 末段清洗为 {@code XxxPage}，同一 URL 在多次运行间稳定复用）。
     * 生成类的默认包名沿用 {@code com.hsbc.cmb.hk.dbb.automation.tests}（可在外部直接调用完整重载覆盖）。</p>
     *
     * <p>调用示例：
     * <pre>{@code
     *     RoleElementPicker.openPanel(page, "nls/NLS_footer.json", "nls/NLS_idv_logon.json");
     * }</pre>
     *
     * @param page       Playwright Page（须已导航到目标页，且为 headed 浏览器）
     * @param nlsFiles   类级 {@code @RoleFile} 路径（可变参数，至少 1 个）
     * @throws PickerAbortedException 用户点击『终止运行』时
     */
    public static void openPanel(Page page, String... nlsFiles) {
        if (nlsFiles == null || nlsFiles.length == 0) {
            throw new IllegalArgumentException("[picker] openPanel 至少需要 1 个 NLS 文件路径参数");
        }
        // page / steps 类名由 URL 决定，与弹窗/导航新页面同源派生。
        final String pageClassName = pageClassNameFromUrl(page.url(), GLOBAL_URL_TO_CLASS.values());
        // 【步骤类命名】Step 类不复用 Page 类的 "XxxPage"+Steps（会拼成 XxxPageSteps），
        // 而是去掉 Page 类后缀的 "Page" 再拼 "Steps"，即页面类 LogonPage → 步骤类 LogonSteps。
        final String stepBase = pageClassName.endsWith("Page")
                ? pageClassName.substring(0, pageClassName.length() - 4) : pageClassName;
        final String stepClassName = stepBase + "Steps";
        final String packageName = "com.hsbc.cmb.hk.dbb.automation.tests";
        openPanel(page, packageName, pageClassName, stepClassName, nlsFiles);
    }

    public static void openPanel(Page page, String packageName,
                                 String pageClassName, String stepClassName, String... nlsFiles) {
        // CI 环境：拾取面板是本地开发工具，自动化测试里不应打开并阻塞等待人工拾取，直接跳过。
        if (isCiRun()) {
            log.info("[picker] 检测到 CI 运行环境，跳过拾取面板（openPanel）。");
            return;
        }
        // 上下文级解耦（对齐 page.pause 的 DebugController/Recorder 架构）：
        // 面板重建脚本 + 门控拾取脚本 + 命令/拾取/控制台桥全部一次性注册到 BrowserContext，
        // context 下所有当前与未来页面（导航/弹窗/新标签页）自动生效，由浏览器原生保证存活。
        final BrowserContext ctx = page.context();
        // 弹窗页/导航后点击时需要 nls 反向表反查 key；预先构建一次（含缓存），供门控注入脚本内嵌。
        final String nlsReverseJson = buildNlsReverseJson(Arrays.asList(nlsFiles));
        // 开启面板开关：刷新/导航后 context 级 addInitScript 会自动重建面板，避免"刷新后面板消失"。
        page.evaluate("try{localStorage.setItem('__rolePanelEnabled','1')}catch(e){}");
        // 清掉上一次会话可能残留的拾取落盘态与拾取开关（浏览器上下文虽每次重建，仍防御性清理），
        // 避免 onFrameNavigated 合并时把旧数据误并入本次会话、或门控注入脚本因残留开关误自启拾取。
        page.evaluate("try{localStorage.removeItem('__rolePickState')}catch(e){} try{localStorage.removeItem('__rolePickerCode')}catch(e){}"
                + " try{localStorage.removeItem('__rolePickSessionOn')}catch(e){} try{window.__rolePickSessionOn=false;}catch(e){}");
        // 把关联的 nls 文件路径暴露给面板（标题展示 files=...），并在导航重建后依然可用。
        page.evaluate("window.__nlsFiles = " + GSON.toJson(nlsFiles) + ";");
        // context 级初始化脚本：①面板引导 + 面板重建（任意页面/导航自动执行）；
        // ②门控拾取脚本（会话开关打开时每个新文档自动注入 nls + 重挂拾取监听——
        //   "页面怎么变都能拾取"从此由浏览器原生保证，替代手动重挂/自愈兜底的主路径）。
        registerContextInitScripts(ctx, nlsReverseJson);
        // addInitScript 只对注册后的【新文档】生效：当前已加载文档立即补执行一次面板脚本。
        page.evaluate(PANEL_SCRIPT);
        final Page[] current = { page };
        // 会话级"是否处于拾取中"状态：跨页面跟随 / 导航 / 关闭回退都以此为权威依据，
        // 驱动面板切换控件显示 ⏹ 停止（而不是每次都重置成 ▶ 开始）。
        final boolean[] active = { false };
        // throttle signature for auto-generating step while picking: recompute only when memory state changes.
        final String[] lastAutoGenSig = { "" };
        // rootClosed：连"根页面（最初打开面板的页面）"都关闭时置位，循环据此结束会话。
        final boolean[] rootClosed = { false };
        // pageNames：每个被跟踪页面 → 其 Page 类名。根页用传入的 pageClassName；
        // 新页面（弹窗/新标签页）由 URL 派生（见 pageClassNameFromUrl），保证"元素落到对应页代码"。
        final LinkedHashMap<Page, String> pageNames = new LinkedHashMap<>();
        pageNames.put(page, pageClassName);
        // snapshots：每个被跟踪页面 → 其拾取状态 JSON 快照（readPickStateJson 格式）。
        // 用于导航重建恢复，以及页面关闭后仍能把它拾取的元素生成到对应 Page 类。
        final LinkedHashMap<Page, String> snapshots = new LinkedHashMap<>();
        // urlToClass：会话级"URL → Page 类名"稳定映射。同一 URL 在会话内首次访问时派生类名并记住，
        // 之后再回到该 URL（即使离开又返回）直接复用原类名，而不是重新派生一个重复类
        // （例如默认页 LoginPage → 跳到第二密页 → 回到登录 URL，若每次都重派生会多出 LogonPage，
        // 这正是"回到默认页面，根据 url 又创建了新的 page"的根因）。预置根页 URL → 传入的 pageClassName，
        // 保证回到默认页 URL 时复用原类名。键为"去 query/hash"的归一化 URL。
        // urlToClass 初始化为全局持久映射的当前内容（跨会话复用），再补登记根页 URL→传入类名。
        // 关键修复（修复"同一 URL 来回跳转却生成 XxxPage / XxxPage2 两个类"）：旧实现 urlToClass 是每次
        // pick 会话的局部变量，跨"停止→再开始"或多次运行会被重建，导致同 URL 在新会话重新派生类名；
        // 若既有类名因 pageNames 残留被计入去重，就派生出 XxxPage2。提升为全局持久映射后，同一 URL 首次
        // 派生即记住，之后任何会话/导航都复用，永不再派生重复类。
        final LinkedHashMap<String, String> urlToClass = new LinkedHashMap<>(GLOBAL_URL_TO_CLASS);
        String rootNorm = normalizeUrl(page.url());
        urlToClass.put(rootNorm, pageClassName);
        GLOBAL_URL_TO_CLASS.put(rootNorm, pageClassName);
        // openedPages：会话期间新开出的页面（弹窗/新标签页），关闭面板时一并关闭。
        final List<Page> openedPages = new ArrayList<>();
        // 命令事件队列：面板按钮点击经 exposeFunction 异步投递到这里，主循环阻塞消费（事件驱动，无需忙轮询）。
        final BlockingQueue<CmdEvent> cmdQueue = new LinkedBlockingQueue<>();
        // 拾取状态权威副本（对齐 page.pause：状态外置到 Java 侧，浏览器只做轻量 UI）。
        // 每次点击经 exposeFunction(__roleOnPick) 把"单个"元素零往返、O(1) 回传进此 Map（key=拾取签名⊕pageClass，
        // value=RoleEntry），重复点击以最近一次交互为准整条替换、首次插入保序。stop 时优先用此内存态生成代码，
        // 不依赖浏览器全量读取、且对导航/关闭导致的浏览器端状态清空免疫（比 localStorage 更可靠）。
        final LinkedHashMap<String, RoleEntry> javaPickBySig = new LinkedHashMap<>();
        // closeSignal：关闭事件协调锁。页面关闭（onClose）回调异步执行会把 current[0] 回退到父页，
        // 主循环检测到 current[0] 已关闭后需等待该回退完成；用 wait/notify 精确等待（而非 Thread.sleep），
        // onClose 完成后立即 notify，主循环即时唤醒，不再空等固定时长，也避免忙睡引入的时序抖动。
        final Object closeSignal = new Object();
        // 记录"发生过整页跳转（URL 变化使 pageClass 改变）"的页面（Identity 比较），用于：
        // 同标签跳转到新页面后直接关闭时，补登记 closeCurrentPage 步骤（普通单页录制末尾不追加）。
        final java.util.Set<Page> navigatedPages =
                java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<Page, Boolean>());
        // 把根页类名暴露给面板标题展示（新页面在 followPage 里设置），并持久化以便整页重建后恢复。
        page.evaluate("window.__rolePageName = " + GSON.toJson(pageClassName) + "; window.__currentPageInstance = null;"
                + " try{localStorage.setItem('__rolePageName', " + GSON.toJson(pageClassName) + ");}catch(e){}");
        // 命令桥+拾取桥+控制台桥：context 一次注册，所有当前与未来页面共享（替代逐页 exposeFunction）。
        registerContextBridges(ctx, cmdQueue, javaPickBySig);
        registerPopupFollow(page, null, current, rootClosed, nlsReverseJson, nlsFiles, packageName, pageClassName, stepClassName, active, pageNames, snapshots, urlToClass, openedPages, cmdQueue, navigatedPages, closeSignal, javaPickBySig);
        // 上下文级"任意新页面"监听：覆盖非 window.open 打开的新标签页（onPopup 仅捕获弹窗）。
        // 用 opener()==null 过滤掉弹窗（弹窗已由上面的 onPopup 跟随），避免重复跟随。
        ctx.onPage(p -> {
            if (p.opener() != null) return;
            log.info("[picker] 新页面打开（onPage）：{}", p.url());
            followPage(current[0], p, current, rootClosed, active, nlsReverseJson, nlsFiles, packageName, pageClassName, stepClassName, pageNames, snapshots, urlToClass, openedPages, cmdQueue, navigatedPages, closeSignal, javaPickBySig);
        });

        log.info("[picker] 面板已打开（同窗口 docked 右侧，不另开窗口）：▶ 开始拾取 → 点击元素 → ⏹ 停止生成代码 → 📋 复制；✕ 关闭结束。");
        try {
            while (true) {
                // 当前跟随的页面（可能是弹窗）已关闭：先让 onClose 回调有机会把 current[0]
                // 回退到父页（重建面板、继续拾取），再判定是否真的结束会话。
                // 关键修复：绝不可因"current[0] 指向的弹窗关闭"就直接结束会话——
                // 否则会进入 finally 移除所有面板、却残留点击捕获监听，表现为
                // "面板消失却仍可静默拾取、不阻挡程序"（用户不期望的行为）。
                try {
                    if (current[0].isClosed()) {
                        // 等待 onClose 回调把 current[0] 回退到存活父页（wait/notify 精确唤醒，
                        // 超时仅作兜底，避免 Thread.sleep 固定空等与时序抖动）。
                        synchronized (closeSignal) {
                            try { closeSignal.wait(500); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                        }
                        if (!current[0].isClosed()) continue;   // onClose 已把 current[0] 回退到存活父页
                        if (rootClosed[0]) break;               // 根页关闭且已置位：结束
                        // onClose 未回退（异常/未触发）：在已跟踪页面里找一个存活页继续，
                        // 仅当"确实没有任何存活页面"时才结束会话。
                        Page alive = null;
                        for (Page pg : pageNames.keySet()) {
                            if (pg != null && !pg.isClosed()) { alive = pg; break; }
                        }
                        if (alive == null) break;
                        current[0] = alive;
                        log.info("[picker] 跟随页已关闭，切换到存活页面 {} 继续会话（面板保留）。", alive.url());
                        continue;
                    }
                } catch (Exception ignore) {}
                // 事件驱动取命令：面板按钮点击经 exposeFunction 异步投递到 cmdQueue，这里阻塞等待
                // （最多 1s 超时以周期性检查页面关闭）。命令到达即被唤醒、立即处理，
                // 故点击"开始/停止"等按钮近乎零延迟，无需为命令轮询额外消耗 Java↔浏览器 evaluate。
                CmdEvent ev;
                try {
                    ev = cmdQueue.poll(1000, TimeUnit.MILLISECONDS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
                if (ev == null) {
                    // 抽干浏览器端兜底命令队列 window.__panelCmds：当 exposeFunction 绑定尚未就绪时，
                    // 面板按钮 pushCmd 会把命令推入该队列（见 PANEL_SCRIPT）。若 Java 不消费，▶ 开始/停止等
                    // 命令会静默丢失 → active[0] 永远 false、START_SCRIPT 永不注入、点击拾取不到。
                    // 此处每轮空闲把所有被跟踪页的兜底命令并入 cmdQueue，保证命令零丢失（与 exposeFunction 幂等、不重投）。
                    try {
                        for (Page pg : pageNames.keySet()) {
                            if (!pg.isClosed()) drainPanelCmds(pg, cmdQueue);
                        }
                    } catch (Exception ignore) {}
                    // 空闲（1s 内无命令）：周期性缓存"所有被跟踪页面"的拾取快照，供导航重建/关闭后恢复。
                    // 关键优化：快照刷新不再放在【每个命令迭代】里——否则每次点按钮都要先对"每个被跟踪页面"
                    // 各做一次 page.evaluate 读快照（N 页 = N 次往返），造成"点按钮要好久才有反应"。
                    // 仅空闲时刷新一次即可：onClose 关闭瞬间会自读最新快照；整页跳转前的最后点击另有
                    // localStorage 落盘合并兜底（见 onFrameNavigated），正确性不受影响，点击延迟大幅下降。
                    try {
                        for (Page pg : pageNames.keySet()) {
                            if (pg.isClosed()) continue;
                            String snap = readPickStateJson(pg);
                            String prev = snapshots.get(pg);
                            boolean prevEmpty = (prev == null || prev.isEmpty() || isEmptyState(prev));
                            boolean curEmpty = isEmptyState(snap);
                            if (prevEmpty || !curEmpty) snapshots.put(pg, snap);
                        }
                    } catch (Exception ignore) {}
                    // 以 Java 权威内存态兜底刷新实时面板：浏览器侧 window.__rolePicks 因跨 iframe/导航时序
                    // 可能未可靠填充，导致"内存态增长、面板空白"。每轮空闲用 javaPickBySig 同步【所有】被跟踪页面
                    // 的面板并渲染（按各页 pageClass 过滤只显示该页拾取），保证用户在任一页面点击时面板都实时反映
                    // 已拾元素（生成链路仍走 javaPickBySig，不受影响）。
                    // 【关键修复"区域选择穿透不了 iframe"】
                    // syncPanelToBrowser 只同步 javaPickBySig；区域扫描穿透 iframe 的元素进【iframe 的
                    // __rolePicks】且经 postMessage/console 回传 Java，若回传链路延迟/失败则 javaPickBySig
                    // 暂缺 iframe 元素，syncPanelToBrowser 同步不到 → 面板只见主框架元素（表象"穿透不了"）。
                    // 每轮空闲先 mergeFramePicksToMain 把各 iframe 的 __rolePicks 直接合并进主框架（Playwright
                    // 协议访问不受 file:// 跨源限制，不依赖 Java 回传），再 syncPanelToBrowser 回灌，双保险
                    // 确保区域扫描穿透的 iframe 元素最终一定出现在面板。
                    try {
                        for (Page pg : pageNames.keySet()) {
                            if (pg.isClosed()) continue;
                            try { mergeFramePicksToMain(pg, javaPickBySig); } catch (Exception me) { /* ignore */ }
                            syncPanelToBrowser(pg, null, javaPickBySig, false);
                        }
                    } catch (Exception ignore) {}
                    // 自愈式保活：会话处于拾取中时，校验每个被跟踪页的点击捕获监听是否仍存活，
                    // 丢失则立即重挂 START_SCRIPT（含 nls）——覆盖"页面变化（跳转/URL change/SPA 整文档替换/
                    // frame 内部跳转）后监听被静默丢弃"的所有边界，保证任何时刻都能继续拾取。
                    if (active[0]) {
                        for (Page pg : pageNames.keySet()) {
                            if (!pg.isClosed()) ensurePickingActive(pg, nlsReverseJson, nlsFiles);
                        }
                    }
                    // auto-generate step while picking (no need to click stop): each change in javaPickBySig
                    // rebuilds one step (start->stop = one step) + page classes and fills the panel silently.
                    // page class is auto-derived from each pick's page url; alert/iframe/new-page handled by generator.
                    if (!javaPickBySig.isEmpty()) {
                        StringBuilder sigBuilder = new StringBuilder();
                        sigBuilder.append(javaPickBySig.size()).append('#');
                        for (RoleEntry e : javaPickBySig.values()) {
                            sigBuilder.append(e.getSigKey()).append('|');
                        }
                        String newSig = sigBuilder.toString();
                        if (!newSig.equals(lastAutoGenSig[0])) {
                            lastAutoGenSig[0] = newSig;
                            try {
                                PickSnapshot autoSnap = snapWithAutoStep(
                                        new PickSnapshot(pageClassName, new ArrayList<>(javaPickBySig.values()),
                                                new ArrayList<>(), new ArrayList<>()));
                                LinkedHashMap<String, String> autoPage = buildPageClassCode(autoSnap.entries, packageName, pageClassName, nlsFiles);
                                LinkedHashMap<String, String> autoStep = buildStepCode(autoSnap, packageName, stepClassName);
                                if (!autoPage.isEmpty() || !autoStep.isEmpty()) {
                                    for (Page pg : pageNames.keySet()) {
                                        if (!pg.isClosed()) {
                                            fillCode(pg, autoPage, autoStep, "(picking) auto-generated " + autoSnap.steps.size() + " step(s), " + autoSnap.entries.size() + " field(s)");
                                            try { pg.evaluate("try{window.__roleAutoStepCount = " + autoSnap.steps.size() + ";}catch(e){}"); } catch (Exception ignore) {}
                                        }
                                    }
                                }
                            } catch (Exception autoEx) {
                                log.warn("[picker] auto-generate step failed: {}", autoEx.getMessage());
                            }
                        }
                    }
                    continue;
                }
                if (ev.page != null && ev.page.isClosed()) continue;   // 页面已关闭：丢弃其命令
                current[0] = ev.page;
                String cmd = ev.cmd;
                // 每次开始拾取前，把浏览器真实打开的页面（context.pages()）与内存跟踪表对齐：
                // 任何在 stop→再 start 之间、或 onPage/onPopup/followPage 因异常漏登记的打开页面都会被补登，
                // 确保"停止后再点开始"不会遗漏某页（表现为点了开始却拾取不了）。
                if ("start".equals(cmd)) {
                    reconcileTrackedPages(ev.page, pageNames, snapshots, urlToClass, openedPages, cmdQueue, javaPickBySig);
                }
                PickerResult r;
                try {
                    r = runPickerCommand(current[0], cmd, packageName, pageClassName, stepClassName, pageNames, snapshots, nlsFiles, active, javaPickBySig);
                } catch (Exception cmdEx) {
                    // 命令消费期间（典型：stop 命令的 page.evaluate 撞上整页跳转/导航导致 execution context 销毁）
                    // 抛出的异常若直接冒泡会撕裂主循环、进入 finally 静默关面板，表现为"点了停止却卡住/没反应"。
                    // 此处捕获后降级为 CONTINUE：若该命令是 stop 则 active 已被复位（runPickerCommand 在 evaluate 前先置 active[0]=false），
                    // 会话继续但不会再拾取；其余命令异常仅告警不中断。
                    log.warn("[picker] 处理命令 '{}' 失败（已降级为不中断会话）：{}", cmd, cmdEx.getMessage());
                    if ("stop".equals(cmd)) setPickMode(pageNames.keySet().iterator().next(), PickMode.IDLE, pageNames);
                    r = new PickerResult(PickerAction.CONTINUE, null, null, "命令 " + cmd + " 执行异常：" + cmdEx.getMessage());
                }
                if (r.action == PickerAction.ABORT) {
                    throw new PickerAbortedException("用户通过面板『终止运行』中止了后续代码执行");
                }
                if (r.action == PickerAction.DONE) {
                    // 【人工拾取模式】关闭面板后不再返回到调用方后续代码（如自动登录流程），
                    // 而是抛出中止异常，使 openPanel 调用点之后的逻辑（输入用户名/密码/点登录等）
                    // 完全不执行——面板打开期间用户专注拾取元素，关闭后即交回人工，不自动登录。
                    throw new PickerAbortedException("人工拾取完成，面板已关闭；按设计中止后续代码（如自动登录）执行");
                }
                if ((r.pageClassByPage != null && !r.pageClassByPage.isEmpty())
                        || (r.stepByPage != null && !r.stepByPage.isEmpty())) {
                    // 多实例：把"合并后的全部页面代码"填充到每一个被跟踪页面的面板，
                    // 避免只在当前页（如新页）显示、而默认页面板留空（之前"默认页没生成代码"的根因）。
                    for (Page p : pageNames.keySet()) {
                        if (!p.isClosed()) {
                            fillCode(p, r.pageClassByPage, r.stepByPage, r.statusMsg);
                            // 封装为步骤后：在当前命令页精准跳转到目标 step（切步骤 Tab + 激活子 Tab + 选中高亮）；
                            // 仅命令页执行，避免多页都跳；window.__pendingJump 未设置时 __afterFillJump 直接返回。
                            if (p.equals(page)) {
                                try {
                                    p.evaluate("try{if(window.__afterFillJump)window.__afterFillJump();}catch(e){}");
                                } catch (Exception ignore) {}
                            }
                        }
                    }
                } else {
                    setStatus(current[0], r.statusMsg);
                }
            }
        } finally {
            // 关闭开关并移除面板（撤销 docked 预留的右侧空间）：写墓碑 '0'（而非 remove），
            // 使 context 级引导注入脚本（无法撤销）在之后的导航中自行退出、不再重建面板。
            try { page.evaluate("try{localStorage.setItem('__rolePanelEnabled','0')}catch(e){}"); } catch (Exception ignore) {}
            try { page.evaluate("try{localStorage.removeItem('__rolePickState')}catch(e){}"); } catch (Exception ignore) {}
            // 多实例：可能有多个页面各自带面板（默认页 + 若干弹窗），逐一关闭，避免残留。
            closePanel(page);
            for (Page p : openedPages) {
                try { if (p != null && !p.isClosed()) closePanel(p); } catch (Exception ignore) {}
            }
            if (current[0] != page) closePanel(current[0]);
            // 关闭面板时一并关闭会话期间新开出的页面（弹窗/新标签页），仅保留最初的根页面。
            for (Page p : openedPages) {
                try { if (p != null && !p.isClosed()) p.close(); } catch (Exception ignore) {}
            }
        }
    }

    /**
     * 注册"弹窗跟随"：当页面弹出新标签页（target=_blank）时，把原页面的拾取状态
     * （__rolePicks / __steps / __currentStep / __rolePickSigs）转移到新页面，
     * 并让 {@code current[0]} 指向新页面继续拾取。面板是注入式 docked（同窗口），
     * 故需在弹窗页也重建面板；新页面自身若再弹窗会递归注册，支持多级弹窗。
     */
    private static void registerPopupFollow(Page page, Page parent, Page[] current,
                                        boolean[] rootClosed, String nlsReverseJson,
                                        String[] nlsFiles, String packageName, String pageClassName,
                                        String stepClassName, boolean[] active,
                                        LinkedHashMap<Page, String> pageNames,
                                        LinkedHashMap<Page, String> snapshots,
                                    LinkedHashMap<String, String> urlToClass,
                                    List<Page> openedPages, BlockingQueue<CmdEvent> cmdQueue,
                                    java.util.Set<Page> navigatedPages, Object closeSignal,
                                    LinkedHashMap<String, RoleEntry> javaPickBySig) {
        // 弹窗（window.open / target=_blank 等）跟随：复用 followPage 把 inspector 跟随到新页面。
        page.onPopup(popup -> {
            // 新页面弹出时，把"最近一次拾取的元素"标记为 popup（其触发动作会打开新页），
            // 生成 step 时包装为 waitForNewPage(() -> element.click())（对齐 page.pause() 的 codegen 输出）。
            try {
                String sig = page.evaluate("window.__lastPickSig || ''").toString();
                if (sig != null && !sig.isEmpty()) {
                    RoleEntry e = javaPickBySig.get(sig);
                    if (e != null) { e.setPopup(true); log.info("[picker] onPopup 捕获新页面，回写最近拾取元素 popup 标记：{}", sig); }
                }
            } catch (Exception ex) { log.warn("[picker] onPopup 标记失败（已忽略）：{}", ex.getMessage()); }
            followPage(page, popup, current, rootClosed, active, nlsReverseJson, nlsFiles, packageName, pageClassName, stepClassName, pageNames, snapshots, urlToClass, openedPages, cmdQueue, navigatedPages, closeSignal, javaPickBySig);
        });
        // 原生对话框（alert/confirm/prompt）捕获：Playwright 官方机制，不依赖浏览器侧 JS hook 的脆弱时序。
        // dialog 出现时，把"最近一次拾取的元素"（浏览器侧 window.__lastPickSig）标记为 dialog 并回写 Java 权威内存态，
        // 使其生成 step 时前置插桩 acceptAlert/dismissAlert（对齐 page.pause() 的 codegen 输出）。
        page.onDialog(dialog -> {
            try {
                // 先关闭 dialog（alert 接受、confirm/prompt 拒绝），避免阻塞拾取流程。
                // 必须在 page.evaluate 之前处理：Playwright 在 dialog 仍显示时调用 evaluate 会自动处理 dialog，
                // 导致随后的显式 accept/dismiss 报 "No dialog is showing"（日志曾出现的告警）。
                if (dialog != null) {
                    String t = dialog.type() != null ? dialog.type().toLowerCase() : "alert";
                    if ("confirm".equals(t) || "prompt".equals(t)) dialog.dismiss();
                    else dialog.accept();
                }
                // 再读 sig 回写 Java 内存态（此时 dialog 已处理，evaluate 安全；浏览器侧 __rolePickClick
                // 的 setTimeout(0) 也会经 __roleOnPick 回传带 dialog 标记的元素，二者幂等）。
                String sig = page.evaluate("window.__lastPickSig || ''").toString();
                if (sig != null && !sig.isEmpty()) {
                    RoleEntry e = javaPickBySig.get(sig);
                    if (e != null) {
                        String type = (dialog != null && dialog.type() != null) ? dialog.type().toLowerCase() : "alert";
                        e.setDialog(true);
                        e.setDialogType(type);
                        // alert 默认 accept；confirm/prompt 默认 dismiss（与浏览器侧 hook 约定一致）
                        e.setDialogAction("confirm".equals(type) || "prompt".equals(type) ? "dismiss" : "accept");
                        log.info("[picker] onDialog 捕获 {}，回写最近拾取元素 dialog 标记：{}", type, sig);
                    }
                }
            } catch (Exception ex) {
                log.warn("[picker] onDialog 处理异常（已忽略）：{}", ex.getMessage());
            }
        });
        // 当前页面关闭监听：若关闭的是弹窗，把 inspector 回退到父页（默认页面）并重建面板，
        // 使单一 inspector 回到原页面继续拾取（对齐 page.pause()：关掉弹出的标签页后 inspector 不消失）。
        // 多页面模型下每个页面保留各自独立的拾取（不合并），故此处仅重建父页面板、不回写子页数据。
        page.onClose(closed -> {
            try {
                // 区分"框架主动关闭（closeCurrentPage 主动 page.close()）"与"外部/手动关闭"。
                // 前者已在代码里显式调用 closeCurrentPage()，若在 onClose 再登记 _closeOp 会重复生成；
                // 后者才需要补登记 closeCurrentPage 步骤。consumeFrameworkClose 读取即清除标记（页面关后失效）。
                boolean frameworkClosed = consumeFrameworkClose(closed);
                // 关闭瞬间尝试抓一份最终快照：页面可能已不可 evaluate，此时 readPickStateJson 返回空集
                // （{picks:[],...}，见 2491-2496，不抛异常）。关键修复：绝不能拿空集覆盖主循环此前缓存的快照
                // （那才含新页已拾取的元素）——否则合并时会用空 picks 把新页元素"合并没了"。
                // 仅当活读成功且确实含拾取时才覆盖缓存；活读为空则保留主循环缓存（更可靠）。
                try {
                    String live = readPickStateJson(closed);
                    if (live != null && hasPicks(live)) snapshots.put(closed, live);
                } catch (Exception ignore) {}
                String closedCls = pageNames.get(closed);   // 提升至 if/else 之前，使根页（else）分支也能引用
                if (parent != null && !parent.isClosed()) {
                    // 先切回父页并保留其面板：即使后续合并/渲染操作抛异常，也不影响"回退父页 + 面板存活"，
                    // 否则 onClose 中途异常会跳过 current[0]=parent，主循环将把"弹窗关闭"误判为会话结束，
                    // 进入 finally 删除所有面板却残留点击监听，表现为"面板消失却仍可静默拾取"（用户不期望）。
                    current[0] = parent;
                    // 父页此前一直处于拾取态（active 未被触碰），监听器仍存活；幂等重启以兜底，
                    // 不会因重复 START 而重复挂监听（START_SCRIPT 内已做 __rolePickActive 早退）。
                    try { if (active[0]) parent.evaluate(START_SCRIPT); } catch (Exception ignore) {}
                    // 合并关闭页已抓元素到父页 + 把"关闭该页"内联为当前 step 的一个操作标记：
                    // 与"回退父页/面板存活"解耦，单独容错，避免一处瞬态异常中断整段。
                    try {
                        // 多页面：关闭弹窗时把该页已抓元素"按签名合并"回父页（而非整盘覆盖）。
                        // 只把关闭页中"父页还没有"的 pick 并入父页，父页自身数据永不被抹掉；
                        // 旧页 pick 自带 _pageClass，合并后仍正确归类到各自 Page 类。
                        String closedState = snapshots.get(closed);
                        if (closedState != null && hasPicks(closedState)) {
                            parent.evaluate(
                                "(function(){"
                                + " try{localStorage.setItem('__rolePanelEnabled','1');}catch(e){}"
                                + " window.__nlsFiles = " + GSON.toJson(nlsFiles) + ";"
                                + " var __o = " + (nlsReverseJson == null ? "{}" : nlsReverseJson) + ";"
                                + " window.__nlsReverse = (__o && __o.exact) ? __o.exact : (__o && __o.templates ? {} : (__o || {}));"
                                + " window.__nlsTemplates = (__o && __o.templates) ? __o.templates : [];"
                                + " window.__rolePicks = window.__rolePicks || [];"
                                + " window.__rolePickSigs = window.__rolePickSigs || {};"
                                + MERGE_KEY_SHIM
                                + " var s = " + closedState + ";"
                                // 定位器唯一型策略（id/css/i18n/text/...）按 locator 签名（_sig）全局去重：
                                // 弹窗打开时被 followPage 复制进来的"主页元素"与主页已有元素 locator 相同，
                                // 关弹窗回灌时不应再追加一份（用户明确：回到主页追加一份不是期望的）。
                                // 角色/closeOp 仍按 [sig, pageClass|URL] 区分，跨页同名元素各自独立。
                                + " var __LOCID={id:1,css:1,i18n:1,text:1,title:1,placeholder:1,label:1,testid:1,altText:1};"
                                + " var __loc = {};"
                                + " (window.__rolePicks||[]).forEach(function(p){ if(p&&__LOCID[p.strategy]){ var ls=p._sig||''; if(ls) __loc[ls]=true; } });"
                            + " (s.picks||[]).forEach(function(p){"
                            + "   var sig=(p&&p._sig)||'';"
                            + "   var li=(p&&__LOCID[p.strategy]);"
                            + "   if (li && sig && __loc[sig]) return;"
                            + "   if (li && sig) __loc[sig]=true;"
                            // 同下方各合并点：统一走 __mergeKey，避免与入库口径不一致导致重复追加。
                            + "   var k = window.__mergeKey(p);"
                            + "   if (k && window.__rolePickSigs[k]) return;"
                            + "   if (k) window.__rolePickSigs[k] = true;"
                            + "   window.__rolePicks.push(p); });"
                            + "})()");
                    }
                        // 关键修复：把关闭页"进行中 step"（__currentStep）合并回父页当前 step，
                        // 使弹窗内拾取的元素随同一 step 继续累积——step 的唯一边界是"开始→停止"，
                        // 弹窗打开/关闭都只是同一 step 内的交互，绝不该拆出额外 step。
                        // 同时把"关闭该页"登记为当前 step 内的一个操作标记（_closeOp），而非独立 step，
                        // 使代码生成器产出 closeCurrentPage() 内联在主流程中（用户明确要求"只有一个条件：开始-停止"）。
                        // 关闭标记按 _sig 去重（支持多次关闭同类弹窗），并随 __currentStep 在停止时被收尾进唯一一个 step。
                        if (closedCls != null && !closedCls.isEmpty()) {
                            parent.evaluate(
                                "(function(){"
                                // 关键修复：合并弹窗 currentStep 时【绝不可】用父页全局 __rolePickSigs 去重——
                                // 否则弹窗打开时被 followPage 搬运进弹窗 currentStep 的"默认页元素"（如 A）会因其 sig
                                // 已存在于默认页 __rolePickSigs 而被误删，导致该元素从当前 step 消失
                                //（表现为"关弹窗 / url 变化后元素找不到"）。此处仅对"弹窗 currentStep 自身"
                                // 去重（避免弹窗内重复拾取同一元素），被搬运来的默认页元素必须原样保留。
                                + " var s = " + (closedState == null ? "{}" : closedState) + ";"
                                + " var closeMarker = {_closeOp:true, _pageClass:" + GSON.toJson(closedCls)
                                + "   , _sig:'__close_' + ((window.__roleCloseSeq=(window.__roleCloseSeq||0)+1)), tag:'close'};"
                                + MERGE_KEY_SHIM
                                + " function mergeInto(arr){ if(!arr) return;"
                                + "   var seen = {};"
                                + "   (s.currentStep||[]).forEach(function(p){"
                                + "     var k = window.__mergeKey(p);"
                                + "     if (k && seen[k]) return;"
                                + "     if (k) seen[k] = true;"
                                + "     arr.push(p); });"
                                // 关闭标记插入到"被关闭页的最后一个元素"之后（而非简单 push 到末尾），
                                // 保留跨页时序：关页 → 自动切回父页 → 再在父页拾取的元素应排在 closeCurrentPage 之后。
                                + "   var __closedCls = " + GSON.toJson(closedCls) + ";"
                                + "   var __ins = -1;"
                                + "   for (var __i = 0; __i < arr.length; __i++) {"
                                + "     var __pc = arr[__i] && (arr[__i]._pageClass || arr[__i].pageClass);"
                                + "     if (__pc === __closedCls) __ins = __i; }"
                                + "   if (__ins < 0) __ins = arr.length - 1;"
                                + "   arr.splice(__ins + 1, 0, closeMarker); }"
                                // 仍在进行中（未停止）：并入当前 step（__currentStep 是数组）。
                                + " if (Array.isArray(window.__currentStep)) { mergeInto(window.__currentStep); }"
                                // 已停止：把弹窗的 currentStep 与关闭标记并入"最后一个已生成 step"的 picks，
                                // 不新建 step（保持"开始-停止"才是唯一 step 边界）。
                                + " else { window.__steps = window.__steps || [];"
                                + "   var last = window.__steps[window.__steps.length-1];"
                                + "   if (!last) { last = {pageClass:" + GSON.toJson(closedCls) + ", picks:[]}; window.__steps.push(last); }"
                                + "   if (!last.picks) last.picks = [];"
                                + "   mergeInto(last.picks); }"
                                // 同步登记一条"页面级关闭操作"（op='close'，pageClass=被关弹窗页），
                                // 使 window.__steps 中除 _closeOp 标记外还保有可被解析为 PageOp 的条目。
                                // 这一步关键：代码生成器的 inferPopupTargetVar 据此推断"弹窗目标页对象"
                                // （如 privacyAndSecurityPage），从而把新页面绑到目标页对象而非打开页（修复
                                // "弹窗关闭落在 loginPage 而非 privacyAndSecurityPage"）。_closeOp 仅用于 step 内联渲染，
                                // 不保证进 __rolePicks（会随封装被过滤），故这里单独补登记可供 opsByPage 消费的操作。
                                + " window.__steps = window.__steps || [];"
                                + " window.__steps.push({op:'close', pageClass:" + GSON.toJson(closedCls) + "});"
                                + "})()");
                        // 框架主动关闭（closeCurrentPage）已在代码显式关闭，跳过重复登记 _closeOp；
                        // 但不 return——仍需执行下方父页回退与面板保留逻辑，使 inspector 回到原页面。
                        if (frameworkClosed) {
                            log.info("[picker] 页面由框架主动关闭（closeCurrentPage），不补登记 closeCurrentPage 步骤：{} （页面类：{}）",
                                    closed.url(), closedCls);
                        } else {
                            // 立即刷新父页快照，确保随后父页导航重建时不会因覆盖而丢失该关闭操作。
                            try { snapshots.put(parent, readPickStateJson(parent)); } catch (Exception ignore) {}
                        }
                    }
                    } catch (Exception ignore) { /* 合并失败不影响回退与面板存活 */ }
                    // 面板：有则重渲染（不重建、不闪烁），丢失则兜底挂载一次并立即渲染，确保面板可见、可继续拾取。
                    try {
                        parent.evaluate("try{window.__rolePanelForce=true;}catch(e){}");
                        boolean parentHasPanel = Boolean.TRUE.equals(parent.evaluate(
                                "!!(document.getElementById('__rolePanel') && window.__renderPicks)"));
                        if (parentHasPanel) parent.evaluate("if(window.__renderPicks) window.__renderPicks();");
                        else {
                            // 面板容器确已丢失：兜底挂载一次，挂载后立刻渲染合并回父页的拾取，
                            // 确保面板"挂载即可见、可继续拾取"，杜绝"面板不见却后台静默拾取"的半吊子状态。
                            parent.evaluate(PANEL_SCRIPT);
                            parent.evaluate("if(window.__renderPicks) window.__renderPicks();");
                        }
                        setStatus(parent, "[picker] 已返回默认页面，可继续点选或停止生成代码。");
                    } catch (Exception ignore) { /* 面板渲染失败：忽略，主循环会继续在存活页面试图恢复 */ }
                    log.info("[picker] 页面已关闭（onClose）：{} （页面类：{}），默认页面板保留并合并各页拾取。",
                            closed.url(), pageNames.get(closed));
                } else {
                    // 根页面（默认页）被关闭：若仍有其它被跟踪页面（弹窗）存活，则不结束会话，
                    // 切换到最后一个存活页面并保留其面板——避免"关掉默认页后面板全消失、程序却静默继续"的半吊子状态。
                    // 仅当确实没有任何存活页面时才结束会话。
                    Page survivor = null;
                    for (Page p : openedPages) {
                        if (p != null && !p.isClosed()) survivor = p;
                    }
                    if (survivor != null) {
                        current[0] = survivor;
                        boolean hasPanel = Boolean.TRUE.equals(survivor.evaluate(
                                "!!(document.getElementById('__rolePanel') && window.__renderPicks)"));
                        if (!hasPanel) {
                            survivor.evaluate("try{window.__rolePanelForce=true;}catch(e){}");
                            survivor.evaluate(PANEL_SCRIPT);
                        }
                        survivor.evaluate("if(window.__renderPicks) window.__renderPicks();");
                        setStatus(survivor, "[picker] 默认页面已关闭，已切换到存活页面继续拾取（面板保留）。");
                        log.info("[picker] 根页面已关闭，但仍有存活页面，切换到 {} 继续会话（面板保留）。",
                                survivor.url());
                    } else {
                        rootClosed[0] = true;   // 连根页面都关了、且无其它存活页面：结束会话
                        log.info("[picker] 原页面已关闭，拾取会话结束。");
                    }
                    // 同标签整页跳转到新页面后"直接关闭"该根页：原逻辑只在"有存活父页"的弹窗分支
                    // 登记 _closeOp（→ closeCurrentPage），根页关闭走本 else 分支从不登记；且停止生成时
                    // 已关闭页被跳过，导致关闭步骤丢失。故在此把"关闭当前页"补登记为该页缓存快照里的一条
                    // step（含 _closeOp 标记），停止时由 runPickerCommand 的 stop 分支折叠回最终快照 → 生成
                    // closeCurrentPage()。仅对"发生过整页跳转"的页生效，普通单页录制末尾不会无谓追加。
                    // 框架主动关闭（closeCurrentPage）已在代码显式调用，此处不再补登记，避免重复生成。
                    if (closedCls != null && !closedCls.isEmpty() && navigatedPages.contains(closed)) {
                        if (frameworkClosed) {
                            log.info("[picker] 根页面由框架主动关闭（closeCurrentPage），不补登记 closeCurrentPage 步骤：{} （页面类：{}）",
                                    closed.url(), closedCls);
                        } else {
                            appendCloseOpStep(closed, closedCls, snapshots);
                        }
                    }
                }
            } catch (Exception ignore) { /* 父页亦不可用：忽略 */ }
            finally { synchronized (closeSignal) { closeSignal.notifyAll(); } }
        });
        // 下载监听：点击触发下载时，把该页面最近一次 pick 标记为 download（对齐 waitForDownload 录制）。
        // 覆盖 JS 触发下载（anchor 无 download 属性）等无法在点击时静态判定的场景；
        // anchor 下载属性/扩展名已在 __rolePickClick 静态标记，二者互补、幂等。
        page.onDownload(download -> {
            try {
                page.evaluate("if(window.__rolePicks && window.__rolePicks.length){"
                        + " var p = window.__rolePicks[window.__rolePicks.length-1]; if(p) p.download = true; }");
            } catch (Exception ignore) { /* 页面已关闭等：忽略 */ }
        });
        // 文件选择框监听：出现上传文件选择框时，把该页面最近一次 pick 标记为 upload（对齐 setInputFiles 录制）。
        page.onFileChooser(fc -> {
            try {
                Page fp = fc.page();
                fp.evaluate("if(window.__rolePicks && window.__rolePicks.length){"
                        + " var p = window.__rolePicks[window.__rolePicks.length-1]; if(p) p.upload = true; }");
                log.info("[picker] 捕获文件选择框（上传），已标记最近一次拾取为 upload。");
            } catch (Exception ignore) { /* 页面已关闭等：忽略 */ }
        });
        // 页面崩溃监听：上报崩溃，便于排查录制中断原因。
        page.onCrash(crashed -> log.warn("[picker] 页面崩溃：{}", crashed.url()));
        // 框架导航监听：主框架（同页前进/刷新/跳转）导航后，恢复/保活本页拾取状态。
        // 关键点（修复"同页 URL 变化后元素仍归到第一个 URL 页类 / 重建清空已有元素"）：
        //   1) SPA/框架内路由：window 未销毁，已抓元素仍驻留内存 → 绝不用快照整体覆盖，否则会清掉
        //      快照之后新拾的元素（"重建清空已有元素"的根因）；仅当整页重建（window.__rolePicks 随文档
        //      销毁变空）时，才从快照恢复并刷新面板列表。
        //   2) URL 变化即视为"新页面边界"：依据新 URL 重派生本页类名（window.__rolePageName），
        //      使导航后新拾取的元素落到新页类；导航前已拾元素自带 _pageClass（旧页类）保持不变。
        //      派生时排除本页当前类名自身，避免 pageClassNameFromUrl 去重把同名类误加成 "Xxx2"。
        page.onFrameNavigated(frame -> {
            try {
                if (frame != page.mainFrame()) {
                    // 子框架（iframe）整页导航的重新注入已统一由 registerFrameInjection 的 onFrameNavigated
                    // 处理（持有正确的 nls 反向表），此处不再重复，避免两处逻辑分散与 nls 不一致。
                    return;
                }
                log.info("[picker][nav] 触发 onFrameNavigated：page={} active[0]={}", page.url(), active[0]);
                // 仅当该页属于本次拾取会话（有快照）才处理；其它无关页面 snapshots 为 null 自然跳过。
                // current[0] 只决定"正在操作的页面"，不影响各页自身面板状态的恢复。
                String st = snapshots.get(page);
                String prevCls = pageNames.get(page);
                // 【修复"导航回来后页面类串味 / 扫描为 0"】
                // 旧实现 onFrameNavigated 仅在末尾激活块才按新 URL 重解析类名（且受后续 evaluate 异常影响可能跳过），
                // 导致：手动跳回 logon 后 pageNames/window.__rolePageName 仍停留在上一页 SetupSecondPwdPage，
                // 新拾取元素被打错页类、面板按激活页过滤后显示为 0。此处【提前、无条件】按最新 URL 重解析并刷新
                // 当前页类名（含浏览器侧 window.__rolePageName），且与后续数据恢复解耦——即便恢复逻辑抛异常也不影响
                // 类名正确性。这同时实现"导航后聚焦当前真实页面"的诉求。
                String resolvedCls = resolvePageClassForUrl(page.url(), pageNames.values(), urlToClass);
                if (resolvedCls != null && !resolvedCls.equals(prevCls)) {
                    pageNames.put(page, resolvedCls);
                    prevCls = resolvedCls;
                }
                try {
                    page.evaluate("try{ if(window.__rolePageName!==" + GSON.toJson(resolvedCls) + "){"
                            + "window.__rolePageName=" + GSON.toJson(resolvedCls) + ";"
                            + "try{localStorage.setItem('__rolePageName'," + GSON.toJson(resolvedCls) + ");}catch(e){}"
                            + "}}catch(e){}");
                } catch (Exception ignoreCls) {}
                // URL 变化即视为"页面边界"：打印日志，便于排查录制定位与元素丢失。
                log.info("[picker] 页面 URL 变化（onFrameNavigated）：{} （页面类：{}）", page.url(), prevCls);
                // 跨域判定（与下方 3645 重激活分支同源口径）：跨域导航时门控脚本因 localStorage origin 隔离
                // 未注入库，必须由下方跨域分支 start() 强制重注入整套库（含 nls 反查表 + active 激活 +
                // 从既有 __rolePicks 重建 sigs）。此时若先在此处 applyPickState 把 __rolePickActive 置 false
                // 并把 window.__rolePicks 整体覆盖成旧快照，会与后续的 start() 重注入交错（一次导航触发的
                // 多次 onFrameNavigated 顺序不确定），导致 active 被反复置 false、点击回传绑定在"已被覆盖/
                // 销毁的文档"上失效——表现即"跨域新页面点击有蓝框 active:true，但 __roleOnPick 回传不进 Java"。
                // 故跨域场景【跳过此处 applyPickState 覆盖】，把数据恢复完全交给唯一的 start() 权威重建。
                String __navOrigin = safeOrigin(page.url());
                boolean __navOriginChanged = !__navOrigin.isEmpty() && !__navOrigin.equals(LAST_PICK_ORIGIN);
                // 无论 window 是否随导航销毁，都确保"之前拾取的元素"不丢失：
                //  - 整页重建（livePicks=false）：用 applyPickState 从快照整体恢复（含 nls 反查表）；
                //  - window 仍在（livePicks=true）：把快照中"当前窗口缺少"的 pick/step 合并回来，
                //    避免某些导航把 window.__rolePicks 连带清空，导致"元素不见了"。
                try {
                boolean livePicks = Boolean.TRUE.equals(page.evaluate(
                        "!!(window.__rolePicks && window.__rolePicks.length)"));
                if (!livePicks) {
                    // 仅当 Java 快照非空才整体恢复（含 nls 反查表）；为空不再提前 return，
                    // 改由下方 localStorage 兜底——修复"刷新前未来得及空闲刷新 / 首屏"导致 st 为空、
                    // 早期 return 把 localStorage 兜底也跳过、整页刷新后元素与步骤全丢的问题。
                    // 【修复"跨域新页点击不回传"】跨域导航交给下方 start() 权威重建，此处不再 applyPickState
                    // 覆盖（否则 active 被置 false 且 picks 被旧快照覆盖，与 start() 交错导致回传失效）。
                    if (st != null && !st.isEmpty() && !__navOriginChanged) {
                        applyPickState(page, st, nlsReverseJson, nlsFiles);
                    }
                    // 关键修复：整页跳转（window 重建）后，上面 applyPickState 用的是主循环每 ~1s 刷新的
                    // Java 快照 st，可能【来不及包含跳转前最后点击的元素】（例如刚点的"返回登录"按钮），
                    // 于是该元素被旧快照整体覆盖而丢失。此处把浏览器在 pagehide 时落盘到 localStorage 的
                    // 最新拾取态（含跳转前那次点击）合并回来，以"页面级复合键"去重，补回 st 缺失的最新点击元素。
                    // 仅同域整页跳转 localStorage 才保留，跨域（如弹窗 PDF）自然为空、不影响。
                    page.evaluate("(function(){"
                            + " try {"
                            + "   var raw = localStorage.getItem('__rolePickState'); if(!raw) return;"
                            + "   var s = JSON.parse(raw);"
                            + "   window.__rolePicks = window.__rolePicks || [];"
                            + "   window.__rolePickSigs = window.__rolePickSigs || {};"
                            + MERGE_KEY_SHIM
                            // 定位器唯一型策略（id/css/i18n/text/...）按 locator 签名（_sig）全局去重，
                            // 避免"跳转再返回"合并时同一元素（如 id=logoHeader）被追加副本；
                            // role/closeOp 仍按 [sig, pageClass|URL] 区分（与 close-merge、Java 权威态一致）。
                            + "   var __LOCID={id:1,css:1,i18n:1,text:1,title:1,placeholder:1,label:1,testid:1,altText:1};"
                            + "   var __loc = {};"
                            + "   window.__rolePicks.forEach(function(p){ if(p&&__LOCID[p.strategy]){ var ls=p._sig||''; if(ls) __loc[ls]=true; } });"
                            + "   (s.picks||[]).forEach(function(p){"
                            + "     var sig=(p&&p._sig)||'';"
                            + "     var li=(p&&__LOCID[p.strategy]);"
                            + "     if (li && sig && __loc[sig]) return;"
                            + "     if (li && sig) __loc[sig]=true;"
                            // 统一走 __mergeKey：此前手搓键与入库口径不一致，是重复收录的根因之一。
                            + "     var k = window.__mergeKey(p);"
                            + "     if (k && window.__rolePickSigs[k]) return;"
                            + "     if (k) window.__rolePickSigs[k] = true;"
                            + "     window.__rolePicks.push(p); });"
                            + "   window.__currentStep = window.__currentStep || [];"
                            + "   var __cs = {};"
                            + "   window.__currentStep.forEach(function(p){ var k=window.__mergeKey(p); if(k) __cs[k]=true; });"
                            + "   (s.currentStep||[]).forEach(function(p){"
                            + "     var k = window.__mergeKey(p);"
                            + "     if (k && __cs[k]) return;"
                            + "     if (k) __cs[k] = true;"
                            + "     window.__currentStep.push(p); });"
                            + "   window.__steps = window.__steps || [];"
                            + "   (s.steps||[]).forEach(function(st2){"
                            + "     var dup = window.__steps.some(function(ex){ return JSON.stringify(ex)===JSON.stringify(st2); });"
                            + "     if(!dup) window.__steps.push(st2); });"
                            + " } catch(e){}"
                            + "})()");
                } else {
                    page.evaluate("(function(){"
                            + " var s = " + st + ";"
                            + " var picks = (s && s.picks) || [];"
                            + " window.__rolePicks = window.__rolePicks || [];"
                            + " window.__rolePickSigs = window.__rolePickSigs || {};"
                            + MERGE_KEY_SHIM
                            // 同上：定位器唯一型策略按 _sig 全局去重，防止 SPA 路由/同页跳转合并快照时
                            // 把同一 locator 元素以不同 pageClass 追加成副本；role/closeOp 保持页面作用域键。
                            + " var __LOCID={id:1,css:1,i18n:1,text:1,title:1,placeholder:1,label:1,testid:1,altText:1};"
                            + " var __loc = {};"
                            + " window.__rolePicks.forEach(function(p){ if(p&&__LOCID[p.strategy]){ var ls=p._sig||''; if(ls) __loc[ls]=true; } });"
                            + " picks.forEach(function(p){"
                            + "   var sig=(p&&p._sig)||'';"
                            + "   var li=(p&&__LOCID[p.strategy]);"
                            + "   if (li && sig && __loc[sig]) return;"
                            + "   if (li && sig) __loc[sig]=true;"
                            // 统一走 __mergeKey（复用已固化 _sigKey），口径与入库一致。
                            + "   var k = window.__mergeKey(p);"
                            + "   if (k && window.__rolePickSigs[k]) return;"
                            + "   if (k) window.__rolePickSigs[k] = true;"
                            + "   window.__rolePicks.push(p); });"
                            + " window.__steps = window.__steps || [];"
                            + " (s.steps||[]).forEach(function(st2){"
                            + "   var dup = window.__steps.some(function(ex){ return JSON.stringify(ex)===JSON.stringify(st2); });"
                            + "   if(!dup) window.__steps.push(st2); });"
                            // 关键修复：URL 变化若把"进行中 step"（__currentStep）一并清空、但 window 未销毁
                            // （livePicks 为真），从快照补回，避免当前 step 元素在导航后"凭空消失"。
                            // 仅当仍处于拾取中、当前 __currentStep 已丢失、且快照确有内容时才补，
                            // 防止 stop 后再导航被误恢复出游离 step。
                            + " if (window.__rolePickActive && !Array.isArray(window.__currentStep)"
                            + "     && (s.currentStep||[]).length) {"
                            + "   window.__currentStep = s.currentStep; }"
                            + "})()");
                }
                // 关键修复（跨页累积不丢失）：SPA / 同 window 跳转时 livePicks=true，上面 if 分支【不会】执行，
                // 因而从不把 Java 快照 st 中"当前窗口缺失"的元素合并回来。一旦此类导航把 window.__rolePicks
                // 部分清空（常见框架路由 / 同页整文档替换），之前页（如 Page1）已拾元素便凭空消失，
                // 表现为"跳转到另一页后之前页面的元素不在了"。此处对 livePicks=true 也补一次合并：
                // 仅把 st 里有、而当前 window.__rolePicks 没有的元素按签名去重补回（不整体覆盖，不影响导航后新拾元素）。
                if (livePicks && st != null && !st.isEmpty()) {
                    page.evaluate("(function(){"
                            + " try {"
                            + "   var s = " + st + ";"
                            + "   window.__rolePicks = window.__rolePicks || [];"
                            + "   window.__rolePickSigs = window.__rolePickSigs || {};"
                            + MERGE_KEY_SHIM
                            + "   var __LOCID={id:1,css:1,i18n:1,text:1,title:1,placeholder:1,label:1,testid:1,altText:1};"
                            + "   var __loc = {};"
                            + "   window.__rolePicks.forEach(function(p){ if(p&&__LOCID[p.strategy]){ var ls=p._sig||''; if(ls) __loc[ls]=true; } });"
                            + "   (s.picks||[]).forEach(function(p){"
                            + "     var sig=(p&&p._sig)||'';"
                            + "     var li=(p&&__LOCID[p.strategy]);"
                            + "     if (li && sig && __loc[sig]) return;"
                            + "     if (li && sig) __loc[sig]=true;"
                            // 统一走 __mergeKey：此前手搓键与入库口径不一致，是重复收录的根因之一。
                            + "     var k = window.__mergeKey(p);"
                            + "     if (k && window.__rolePickSigs[k]) return;"
                            + "     if (k) window.__rolePickSigs[k] = true;"
                            + "     window.__rolePicks.push(p); });"
                            + " } catch(e){}"
                            + "})()");
                }
                // 导航后始终重渲染面板列表并滚动到底部，确保已恢复/合并的元素可见（修复"URL 变化后元素看不见"）；
                // 用 setTimeout 兜底等待 PANEL_SCRIPT 的 build() 完成（body 就绪才挂载面板），避免提前渲染找不到节点，
                // 同时恢复上次生成的代码（页面元素 / 步骤代码两个 Tab），刷新后不丢。
                page.evaluate("(function(){ setTimeout(function(){"
                        + MERGE_KEY_SHIM
                        // 关键修复（导航后元素成倍增加）：applyPickState / localStorage 合并 / SPA 合并 任一路径
                        // 可能因元素副本的 _sigKey 在 Java 快照往返中丢失、__pageClass 缺失，导致合并去重键不一致
                        // （location 兜底键会随跳转目标页漂移），每次 onFrameNavigated 触发都多加一份，成倍累积。
                        // 此处用【稳定键】（绝不用 location 兜底）在所有合并结束后做一次全局压实：
                        //   稳定键 = _sigKey 优先；否则 [pickSig, _pageClass || 当前页类]。
                        // 同一区域扫描产出的多份副本（pickSig 相同）无论跳到哪个页面都命中等价稳定键 → 合并为一份。
                        + "   try {"
                        + "     var __stableKey = function(pp){"
                        + "       if (!pp) return '';"
                        + "       if (pp._sigKey) return pp._sigKey;"
                        + "       var __s = (typeof window.__pickSig==='function') ? window.__pickSig(pp) : (pp._sig || '');"
                        + "       var __pk = pp._pageClass || (window.__rolePageName || '');"
                        + "       return JSON.stringify([__s, __pk]);"
                        + "     };"
                        + "     var __seen = {}; var __out = [];"
                        + "     (window.__rolePicks||[]).forEach(function(p){"
                        + "       if (!p) return;"
                        + "       var k = __stableKey(p);"
                        + "       if (k && __seen[k]) return;"
                        + "       if (k) { __seen[k] = true; if (!p._sigKey) p._sigKey = k; }"
                        + "       __out.push(p);"
                        + "     });"
                        + "     window.__rolePicks = __out;"
                        + "     window.__rolePickSigs = __seen;"
                        + "   } catch(e){}"
                        + "   try { if (window.__renderPicks) window.__renderPicks(); } catch(e){}"
                        + "   try { var l = document.getElementById('__rolePickList'); if (l) l.scrollTop = l.scrollHeight; } catch(e){}"
                        + "   try { var code = JSON.parse(localStorage.getItem('__rolePickerCode')||'null');"
                        + "     if (code) {"
                        + "       var pbp = code.pageByPage || (code.page != null ? {'__merged__': code.page} : {});"
                        + "       var sbp = code.stepByPage || (code.step != null ? {'__merged__': code.step} : {});"
                        + "       if (window.__fillCodeTabs) window.__fillCodeTabs({pageByPage: pbp, stepByPage: sbp, msg: code.msg||''});"
                        + "       window.__pickerCode = (code.page != null) ? code.page"
                        + "         : Object.keys(pbp).map(function(k2){return pbp[k2];}).join('\\n\\n');"
                        + "     } } catch(e){}"
                        + " }, 60); })()");
                // 依据新 URL 解析本页类名：优先复用会话级 urlToClass 稳定映射（同一 URL 复用同一类名，
                // 避免"回到默认页 URL 又派生出 LogonPage 之类重复页类"）——仅当该 URL 从未见过时才派生新类名。
                String curCls = pageNames.get(page);
                String newCls = resolvePageClassForUrl(page.url(), pageNames.values(), urlToClass);
                page.evaluate("window.__rolePageName = " + GSON.toJson(newCls) + ";"
                        + " try{localStorage.setItem('__rolePageName', " + GSON.toJson(newCls) + ");}catch(e){}");
                if (!newCls.equals(curCls)) { pageNames.put(page, newCls); navigatedPages.add(page); }
                } catch (Exception restoreEx) {
                    // 数据恢复（applyPickState / 合并 / 渲染 / 类名解析）任一 evaluate 因导航瞬间页面不稳抛异常，
                    // 必须吞掉且【不能影响下方 start() 重激活】——否则会出现"刷新/导航后点了没反应、拾取不了"
                    // （applyPickState 把 __rolePickActive 置 false 后激活被跳过，监听永久失效）。
                    log.warn("[picker][nav] 数据恢复异常（不阻断拾取激活）：{}", restoreEx.getMessage());
                }
                // 整页导航的监听重挂已由 context 门控注入脚本在新文档早期原生完成（gatedPickerInitScript）；
                // 但 applyPickState 恢复数据时会把 __rolePickActive 置 false。若会话仍处于拾取中，
                // 经 start() 幂等恢复激活位（监听已在则早退仅保活），并置位会话开关——
                // 覆盖跨源导航后 localStorage 开关丢失的边界，使该页后续导航恢复浏览器原生保活。
                // 触发条件不再单纯依赖 Java 侧 active[0]（可能与浏览器态不同步），而以浏览器侧会话开关为准，
                // 只要门控脚本此前读到过开关（localStorage/__rolePickSessionOn）就重激活，保证"刷新/跳转后必能拾取"。
                boolean sessionOn = active[0];
                // 【修复"停止不了"】用户已显式停止（stop 置位 __rolePickStopped）后，即便后续发生导航，
                // 也绝不再重激活拾取——否则 stop 后又被 onFrameNavigated 复活，表现为"点了停止还是停不掉"。
                boolean explicitStop = false;
                try {
                    explicitStop = Boolean.TRUE.equals(page.evaluate(
                            "try { return !!window.__rolePickStopped; } catch(e){ return false; }"));
                } catch (Exception ignore) {}
                if (!explicitStop && !sessionOn) {
                    try {
                        sessionOn = Boolean.TRUE.equals(page.evaluate(
                                "try { return localStorage.getItem('__rolePickSessionOn')==='1' || !!window.__rolePickSessionOn; } catch(e){ return !!window.__rolePickSessionOn; }"));
                    } catch (Exception ignore) {}
                }
                if (sessionOn && !explicitStop) {
                    log.info("[picker][nav] 会话拾取中：同步激活状态 @ {}", page.url());
                    // 跨域（或任何门控脚本因 localStorage 隔离未注入）导航后，新文档的 gatedPickerInitScript
                    // 因读不到 localStorage.__rolePickSessionOn 而提前 return，整套拾取库（__recordPick/点击监听）
                    // 从未注入 → 表现为"无蓝框、点击无反应"。Java 主循环权威开关 active[0] 仍为 true，故进入本分支，
                    // 但仅置 window.__rolePickActive 是"假激活"（库不存在）。必须直接 start() 真正重注入整套库。
                    // 同源导航：门控脚本已在新文档早期注入库，仅做轻量保活即可（避免反复重注入竞态放大，见下）。
                    // 同源 vs 跨域判断：门控脚本(gatedPickerInitScript)靠 localStorage.__rolePickSessionOn 在【每个新文档】
                    // 早期注入库——同源导航 localStorage 同域可读到开关 → 库必已注入，只需轻量保活(__rolePickActive=true)；
                    // 跨域导航 localStorage 因 origin 隔离读不到 → 门控 return 未注入 → 必须强制 start() 重注入整套库。
                    // 故以 origin 是否变化作为"是否需要强制重注入"的唯一判据，避免对同源导航（含 SPA hash 变化、整页跳转）
                    // 反复重注入造成"扫描了很多元素"的放大。SPA hash 变化(#/question1)不改变 origin → 视为同源，仅保活。
                    String curOrigin = safeOrigin(page.url());
                    boolean originChanged = !curOrigin.isEmpty() && !curOrigin.equals(LAST_PICK_ORIGIN);
                    if (!originChanged) {
                        // ===== 同源导航：门控脚本已注入库，仅做轻量激活保活 =====
                        // 关键修复（跳转到新页面后元素成倍增加）：监听重挂已由 context 级门控注入脚本
                        // (gatedPickerInitScript) 在新文档早期原生完成（见本方法上方注释），导航数据恢复也已在
                        // 上方 applyPickState/合并 evaluate 中完成。此处【不再调用 start() 重注入整套库】——
                        // 否则一次导航会触发 onFrameNavigated 多次（main frame / iframe / about:blank 过渡 / 重试），
                        // 每次都 start() 一次：清空并重建 __rolePickSigs、异步 page.evaluate 重注入，与 idle 主循环的
                        // syncPanelToBrowser 合并 javaPickBySig 之间存在竞态，合并键未就绪时元素被重复 push，
                        // 形成"反复重注入 + 反复合并"的循环，导致已拾元素成倍累积。
                        // 这里仅做轻量激活保活：置位激活态并触发面板渲染，监听由门控脚本保证存活。
                        try {
                            page.evaluate("try{ window.__rolePickActive = true; if (window.__renderPicks) window.__renderPicks(); }catch(e){}");
                        } catch (Exception ex) {
                            // 导航瞬间新文档执行上下文可能尚未就绪，page.evaluate 会抛"上下文已销毁"类异常；
                            // 此处等待 DOM 就绪后重试一次轻量激活保活（仍不重注入整套库）。
                            log.warn("[picker][nav] 激活保活首轮失败，等待页面就绪后重试 @ {} : {}", page.url(), ex.getMessage());
                            try { page.waitForLoadState(); } catch (Exception ignore2) {}
                            try { page.evaluate("try{ window.__rolePickActive = true; if (window.__renderPicks) window.__renderPicks(); }catch(e){}"); }
                            catch (Exception ex2) { log.warn("[picker][nav] 激活保活重试仍失败 @ {} : {}", page.url(), ex2.getMessage()); }
                        }
                    } else {
                        // ===== 跨域导航：门控脚本因 localStorage 隔离未注入 → 强制 start() 重注入整套库 =====
                        // 去抖：一次跨域导航会触发 onFrameNavigated 多次（about:blank 过渡/重定向/主框架/iframe），
                        // 每次都强制 start 会清空并重建 __rolePickSigs、重复渲染所有元素，表现为"扫描了很多元素"。
                        // 同一 page 在 FORCE_START_DEBOUNCE_MS 内只真正重注入一次。
                        long now = System.currentTimeMillis();
                        Long last = FORCE_START_TS.get(page);
                        if (last != null && (now - last) < FORCE_START_DEBOUNCE_MS) {
                            log.info("[picker][nav] 跨域重注入去抖（{}ms 内已注入，跳过）@ {}", (now - last), page.url());
                        } else {
                            FORCE_START_TS.put(page, now);
                            log.warn("[picker][nav] 检测到跨域导航库未注入，强制 start() 重注入 @ {} : origin={} -> {}",
                                    page.url(), LAST_PICK_ORIGIN, curOrigin);
                            try {
                                start(page, nlsReverseJson);
                            } catch (Exception startEx) {
                                // 导航瞬间新文档执行上下文可能尚未就绪，page.evaluate 会抛"上下文已销毁"类异常；
                                // 等待 DOM/load 就绪后重试一次真正重注入，避免跨域页因首轮竞态失败而仍无蓝框。
                                log.warn("[picker][nav] 跨域重注入首轮失败，等待页面就绪后重试 @ {} : {}", page.url(), startEx.getMessage());
                                try { page.waitForLoadState(); } catch (Exception ignore2) {}
                                try {
                                    start(page, nlsReverseJson);
                                } catch (Exception startEx2) {
                                    log.warn("[picker][nav] 跨域重注入重试仍失败（导航中可忽略）：{}", startEx2.getMessage());
                                }
                            }
                            // 跨域同页跳转：面板脚本(panel-core)经 context 级 addInitScript 已无条件注入，但其显示门禁
                            // localStorage.__rolePanelEnabled 因 origin 隔离读不到、window.__rolePanelForce 随旧文档销毁丢失
                            // → 面板不显示（有蓝框能拾取却看不到已拾列表）。此处显式置位兜底开关确保面板显示。
                            try {
                                page.evaluate("try{ window.__rolePanelForce = true; }catch(e){} try{ localStorage.setItem('__rolePanelEnabled','1'); }catch(e){}");
                            } catch (Exception ignorePanel) {}
                        }
                    }
                } else {
                    log.info("[picker][nav] 未处于拾取会话（active=false 且浏览器侧未开启），跳过激活 @ {}", page.url());
                }
                // ===== 诊断：刷新/导航后真实运行时状态（定位"拾取不了"根因）=====
                // 浏览器 console 已被吞，所有关键信息只能经 page.evaluate 回读。一次性汇总：
                //   lsSwitch   —— 新文档 localStorage 里的会话开关（跨源导航会读不到，暴露 origin 隔离问题）
                //   winSwitch  —— window.__rolePickSessionOn 是否被置位
                //   active     —— __rolePickActive（最终是否处于拾取态）
                //   hasClick/hasMove/hasRecord —— 三大监听/入口函数是否真的被定义（判断 START_SCRIPT 是否注入成功）
                //   gateInit   —— 门控注入脚本本次执行结果（是否读到开关、是否注入），直接显示是"门控没生效"还是"激活被覆盖"
                try {
                    String navDiag = page.evaluate("(function(){"
                            + " try {"
                            + "   var ls='?'; try{ ls = localStorage.getItem('__rolePickSessionOn'); }catch(e){ ls='LS_ERR:'+e; }"
                            + "   var gi = window.__gateInit || null;"
                            + "   return JSON.stringify({"
                            + "     url: location.href, origin: location.origin,"
                            + "     lsSwitch: ls,"
                            + "     winSwitch: !!window.__rolePickSessionOn,"
                            + "     active: !!window.__rolePickActive,"
                            + "     hasClick: typeof window.__rolePickClick === 'function',"
                            + "     hasMove: typeof window.__rolePickMove === 'function',"
                            + "     hasRecord: typeof window.__recordPick === 'function',"
                            + "     hasLib: !!window.__rolePickerLib,"
                            + "     lastClickTs: window.__lastClickTs || 0,"
                            + "     lastClickActive: !!window.__lastClickActive,"
                            + "     mouseLog: (window.__roleMouseLog||[]).slice(-12),"
                            + "     gateInit: gi"
                            + "   });"
                            + " } catch(e){ return 'DIAG_ERR:'+e; }"
                            + "})()").toString();
                    log.info("[picker][nav] 导航恢复/激活后运行时诊断 @ {} : {}", page.url(), navDiag);
                } catch (Exception diagEx) {
                    log.warn("[picker][nav] 读取诊断失败（页面可能已关闭）：{}", diagEx.getMessage());
                }
            } catch (Exception ignore) { /* 页面已关闭等：忽略 */ }
        });
        // 注：已按需求移除 onConsoleMessage / onRequestFailed 监听（避免刷屏、聚焦页面生命周期日志）。
        // 页面生命周期打印见上方 onPage（新页面打开）、onClose（页面关闭）与下方 onFrameNavigated（URL 变化）。
    }

    /**
     * 把 inspector 跟随到 newPage 并重建面板（单实例，供 onPopup 与 context.onPage 共用）。
     * 多页面模型：新页面加载当前面板（携带已抓元素），并继续把新页面拾取的元素归属到对应 Page 类；
     * 元素按各页 window.__rolePageName 打 _pageClass 标签，生成时据此分组，实现"打开新页显示之前抓的元素"。
     */
    /**
     * 开始拾取前，将浏览器当前真实打开的所有页面（context.pages()）与内存跟踪表 pageNames 对齐。
     * 兜底：任何在 stop→再 start 之间、或 onPage/onPopup/followPage 因异常而未登记进 pageNames 的打开页面，
     * 都会被补做最小初始化并纳入跟踪，使该页面在随后的 start 遍历中可被激活拾取，
     * 彻底消除"停止后再点开始，某个已打开页面点了开始却拾取不了"的问题。
     * 已登记页面不重复初始化（幂等）：命令桥/拾取桥用 Map 守卫仅注册一次；面板 addInitScript 仅对漏登页调用一次。
     */
    private static void reconcileTrackedPages(Page trigger, LinkedHashMap<Page, String> pageNames,
                                              LinkedHashMap<Page, String> snapshots,
                                              LinkedHashMap<String, String> urlToClass, List<Page> openedPages,
                                              BlockingQueue<CmdEvent> cmdQueue,
                                              LinkedHashMap<String, RoleEntry> javaPickBySig) {
        if (trigger == null || trigger.isClosed()) return;
        for (Page p : trigger.context().pages()) {
            if (p == null || p.isClosed()) continue;
            if (!pageNames.containsKey(p)) {
                ensurePageTracked(p, pageNames, snapshots, urlToClass, openedPages, cmdQueue, javaPickBySig);
            } else {
                // 【修复"手动跳转后删除跨页误伤 / 再扫描为 0"】
                // 用户可能在面板之外手动导航（如直接改 URL、点原生链接跳转），这类跳转不经过
                // followPage/onPopup 钩子，window.__rolePageName 仍停留在旧页类名，导致新页拾取的元素
                // 被打上旧 pageClass；两个真实不同的页因此共享同一 pageClass，删除时整桶/值级兜底 +
                // STATE_DELETED 会把两页当一页一并清除，且已删键永久屏蔽后续扫描。
                // 此处对【每个已登记页】按当前 URL 重新解析 pageClass 并刷新其自身 window.__rolePageName，
                // 确保手动跳转后的页面拿到正确类名（每页写的是"它自己"的类名，而非当前激活页的），
                // 从源头杜绝跨页 pageClass 串味。幂等、仅当解析结果变化时写回。
                try {
                    String curCls = pageNames.get(p);
                    String newCls = resolvePageClassForUrl(p.url(), pageNames.values(), urlToClass);
                    if (newCls != null && !newCls.equals(curCls)) {
                        pageNames.put(p, newCls);
                    }
                    p.evaluate("try{"
                            + "if(window.__rolePageName!==" + GSON.toJson(newCls) + "){"
                            + "window.__rolePageName=" + GSON.toJson(newCls) + ";"
                            + "try{localStorage.setItem('__rolePageName'," + GSON.toJson(newCls) + ");}catch(e){}"
                            + "}}catch(e){}");
                } catch (Exception refreshEx) {
                    log.warn("[picker] reconcile 刷新页面类名失败：{}", refreshEx.getMessage());
                }
            }
        }
    }

    /**
     * 对漏登记页面补做"可被拾取"的最小初始化（不搬运 opener 的当前 step，start 自身会续接/重置）。
     * 与 followPage 的区别：不注册子页跟随（避免重复 onPopup/onClose 监听），仅靠每次 start 的 reconcile
     * 形成闭环——若漏登页再开子页，子页也会在下次 start 时被补登。
     */
    private static void ensurePageTracked(Page p, LinkedHashMap<Page, String> pageNames,
                                          LinkedHashMap<Page, String> snapshots,
                                          LinkedHashMap<String, String> urlToClass, List<Page> openedPages,
                                          BlockingQueue<CmdEvent> cmdQueue,
                                          LinkedHashMap<String, RoleEntry> javaPickBySig) {
        try {
            // 命令桥/拾取桥/面板重建脚本均已在 context 级一次性注册（registerContextBridges /
            // registerContextInitScripts），本页自动持有，无需逐页补注册。
            // 按 URL 解析并登记本页类名（复用会话稳定映射 urlToClass）
            String cls = resolvePageClassForUrl(p.url(), pageNames.values(), urlToClass);
            pageNames.put(p, cls);
            if (!openedPages.contains(p)) openedPages.add(p);
            // 暴露页面类名 + 开启面板开关（与 openPanel/followPage 一致）
            p.evaluate("window.__rolePageName = " + GSON.toJson(cls) + ";"
                    + " try{localStorage.setItem('__rolePageName', " + GSON.toJson(cls) + ");}catch(e){}");
            p.evaluate("try{localStorage.setItem('__rolePanelEnabled','1');}catch(e){} try{window.__rolePanelForce=true;}catch(e){}");
            p.evaluate(PANEL_SCRIPT);   // 立即重建当前已加载文档的面板
            snapshots.put(p, readPickStateJson(p));
            log.info("[picker][reconcile] 已补登漏跟踪页面并可被拾取：{} -> {}", p.url(), cls);
        } catch (Exception e) {
            log.warn("[picker][reconcile] 补登页面失败（该页本次 start 可能仍无法拾取）：{}", e.getMessage());
        }
    }

    private static void followPage(Page opener, Page newPage, Page[] current, boolean[] rootClosed, boolean[] active,
                                   String nlsReverseJson, String[] nlsFiles, String packageName,
                                   String pageClassName, String stepClassName,
                                   LinkedHashMap<Page, String> pageNames,
                                   LinkedHashMap<Page, String> snapshots,
                                   LinkedHashMap<String, String> urlToClass,
                                   List<Page> openedPages, BlockingQueue<CmdEvent> cmdQueue,
                                   java.util.Set<Page> navigatedPages, Object closeSignal,
                                   LinkedHashMap<String, RoleEntry> javaPickBySig) {
        try {
            final boolean sessionActive = active[0];
            // 命令桥/拾取桥已在 context 级一次性注册（registerContextBridges），新页面自动持有绑定，
            // 无需逐页注册；面板按钮点击/拾取回传经 BindingCallback.Source 天然区分来源页面。
            // 多实例：保留原页面（默认页）面板，不关闭、也不停止其拾取态，
            // 使默认页面板在打开新页时不消失；新页面另行注入一个独立面板。
            // 两个页面各自维护自己的面板与 active 状态，互不干扰（不再调用 closePanel/stop(opener)）。
            // 由 URL 解析新页面的 Page 类名（优先复用 urlToClass 稳定映射，同一 URL 复用同一类名），
            // 登记进 pageNames / openedPages，供生成时落到对应类。
            String cls = resolvePageClassForUrl(newPage.url(), pageNames.values(), urlToClass);
            pageNames.put(newPage, cls);
            openedPages.add(newPage);
            // 关键修复：打开新页面【不再】把默认页当前步收尾成一个 step。step 的唯一边界是"开始→停止"，
            // 弹窗打开/关闭都只是同一 step 内的交互，绝不该切分出额外 step（用户明确要求"只有一个条件：开始-停止"）。
            // 因此此处只把 opener 的"进行中 step"（__currentStep，已带各元素原 _pageClass）整体搬运到新页继续累积，
            // 并把 opener 的 __currentStep 清空（转移而非复制），避免关闭弹窗合并回来时出现重复元素。
            // 旧页 pick 已带原 _pageClass，新页面板会显示之前抓的元素；新页拾取的元素再打上 cls。
            // 当前页始终持有全部页面的 pick 并集，故代码生成可在单一窗口按各元素自身 _pageClass 归类。
            applyPickState(newPage, readPickStateJson(opener), nlsReverseJson, nlsFiles);
            if (opener != null && !opener.isClosed()) {
                opener.evaluate("try{ window.__currentStep = []; }catch(e){}");
            }
            newPage.evaluate("window.__rolePageName = " + GSON.toJson(cls) + "; window.__currentPageInstance = null;"
                    + " try{localStorage.setItem('__rolePageName', " + GSON.toJson(cls) + ");}catch(e){}");
            // 跨源/新页面：localStorage 往往为空或不可写，若直接跑 PANEL_SCRIPT 会因
            // __rolePanelEnabled!=='1' 提前 return，导致新页面没有面板。故显式置位开关，
            // 并用 window.__rolePanelForce 兜底（即使 localStorage 不可用也能重建面板）。
            // 面板重建 addInitScript 已在 context 级注册（registerContextInitScripts），
            // 新页面后续导航（弹窗常伴随重定向）会自动重建面板，无需逐页注册。
            newPage.evaluate("try{localStorage.setItem('__rolePanelEnabled','1');}catch(e){} try{window.__rolePanelForce=true;}catch(e){}");
            // 若会话仍处于拾取中，则在新页面重启点击捕获监听（applyPickState 已把新页 active 置 false）：
            // 经 start() 同时置位会话开关 + 注入 nls，使该页后续导航由 context 门控注入脚本原生保活。
            // 【算法：零等待窗口的双保险注入，杜绝"卡住"】
            // onPopup 回调触发时新页文档可能还是 about:blank（尚未导航到真实 URL）。早期版本把 start() 延迟到
            // onLoadState 触发——但这引入"卡住窗口"：SPA 重定向/不触发 DOMContentLoaded 的页面会让监听永不挂载，
            // 表现为"新页无蓝框、换了个操作才突然好"（实则是别的导航触发 onFrameNavigated 补注入）。
            // 现改为【同步立即 start()】注入当前文档作为兜底，且 start() 已修复：about:blank 不再污染全局
            // LAST_PICK_ORIGIN。随后真实页导航由两条路径无缝接管，无任何等待间隙：
            //   ① context 级门控 addInitScript 在每个新文档早期自动跑，同源导航读得到 localStorage 开关即注入；
            //   ② onFrameNavigated 对跨域导航强制 start() 重注入（LAST_PICK_ORIGIN 未污染故能正确判跨域）。
            // 故弹出瞬间即具备基础监听，导航完成后即被真实库接管，用户体感"立即能拾取、不卡"。
            if (sessionActive) {
                try { start(newPage, nlsReverseJson); } catch (Exception ex) {
                    log.warn("[picker] 新页面同步注入失败（导航中可忽略，将由 onFrameNavigated 补注入）：{}", ex.getMessage());
                }
            }
            // 面板脚本：初始文档也先注入一次；若后续导航重建，onFrameNavigated/PANEL addInitScript 会兜底。
            try { newPage.evaluate(PANEL_SCRIPT); } catch (Exception ignore) {}
            current[0] = newPage;
            // 记录新页初始快照（含搬运来的并集），供导航重建（onFrameNavigated）与关闭回退（onClose）使用。
            snapshots.put(newPage, readPickStateJson(newPage));
            // 新页面若再弹窗/再开页，继续跟随；把"是否处于拾取态"传下去，供其 onClose 回退父页时恢复。
            registerPopupFollow(newPage, opener, current, rootClosed, nlsReverseJson, nlsFiles, packageName, pageClassName, stepClassName, active, pageNames, snapshots, urlToClass, openedPages, cmdQueue, navigatedPages, closeSignal, javaPickBySig);
            log.info("[picker] 已在新页面（{}）注入独立面板，默认页面板保留不消失。", cls);
        } catch (Exception e) {
            log.warn("[picker] 页面跟随失败：{}", e.getMessage());
        }
    }

    /** 由页面 URL 派生 Page 类名：取 path 最后一个 '/' 之后、'?'（及 '#'）之前的片段，
     *  清洗为首字母大写的合法 Java 标识符后加 "Page"。
     *  特殊情况：片段为空（根路径 / 仅域名 / 结尾斜杠）→ 退回 "Index"；
     *  与 used 中已有类名重复时追加 2/3… 去重。 */
    private static String pageClassNameFromUrl(String url, Collection<String> used) {
        String raw = url == null ? "" : url.trim();
        int q = raw.indexOf('?'); if (q >= 0) raw = raw.substring(0, q);
        int h = raw.indexOf('#'); if (h >= 0) raw = raw.substring(0, h);
        int s = raw.lastIndexOf('/');
        String seg = (s >= 0) ? raw.substring(s + 1) : raw;
        if (seg.isEmpty()) seg = "Index";           // 特殊：根路径 / 仅域名
        String base = toClassNameSegment(seg);
        if (base.isEmpty()) base = "Index";
        String candidate = base + "Page";
        String unique = candidate;
        int n = 2;
        while (used.contains(unique)) unique = candidate + (n++);
        return unique;
    }

    /**
     * 跨多次 pick 运行持久化的"URL → Page 类名"稳定映射。
     * 关键修复（修复"同一 URL 来回跳转却生成 XxxPage / XxxPage2 两个类"）：
     * 旧实现 urlToClass 是每次 pick 会话的局部变量，跨"停止→再开始"或多次运行会被重建，导致同 URL
     * 在新会话重新派生类名；若既有类名因 pageNames 残留被计入去重，就派生出 XxxPage2。提升为全局持久
     * 映射后，同一 URL 首次派生即记住，之后任何会话/导航都复用，永不再派生重复类。
     */
    private static final java.util.Map<String, String> GLOBAL_URL_TO_CLASS = new java.util.concurrent.ConcurrentHashMap<>();

    /** 语言/地区码路径片段（首段），如 en / zh / zh-HK / en_US，用于 URL 归一化时忽略语言差异。
     *  仅当首段恰好是一个 IETF 风格的语言码时才剥离，尽量降低误伤真实内容路径的概率。 */
    private static final java.util.regex.Pattern LOCALE_SEGMENT =
            java.util.regex.Pattern.compile("(?i)^/[a-z]{2}([-_][a-z]{2,4})?(?=/|$)");

    /** 安全提取 URL 的 origin（protocol//host[:port]），用于跨域判断。无法解析时返回空串。 */
    private static String safeOrigin(String url) {
        if (url == null) return "";
        try {
            java.net.URI u = java.net.URI.create(url);
            String scheme = u.getScheme();
            if (scheme == null) return "";
            String host = u.getHost();
            if (host == null) return "";
            int port = u.getPort();
            if (port == -1 || port == u.toURL().getDefaultPort()) {
                return scheme + "://" + host;
            }
            return scheme + "://" + host + ":" + port;
        } catch (Exception e) {
            return "";
        }
    }

    /** 归一化 URL：去 query/hash，剥离首段语言/地区码，并去除末尾斜杠，作为 urlToClass 的稳定键。
     *  去除末尾斜杠可让肉眼"相同"但末尾斜杠有差异的 URL（如 /help 与 /help/）映射到同一页类；
     *  剥离语言码可让同一页面在切换语言后（如 /en/accounts 与 /zh/accounts）归并到同一页类，
     *  避免它们被误判为两个不同页面而派生出 XxxPage / XxxPage2（修复"切换语言后同一页生成 Page2"）。 */
    private static String normalizeUrl(String url) {
        String raw = url == null ? "" : url.trim();
        int q = raw.indexOf('?'); if (q >= 0) raw = raw.substring(0, q);
        int h = raw.indexOf('#'); if (h >= 0) raw = raw.substring(0, h);
        // 忽略语言/地区码片段：/en/accounts 与 /zh/accounts 归并为 /accounts，复用同一页类。
        java.util.regex.Matcher lm = LOCALE_SEGMENT.matcher(raw);
        if (lm.find()) {
            raw = raw.substring(0, lm.start()) + raw.substring(lm.end());
            log.debug("[picker][normalize] 剥离语言码，归一化键={}", raw);
        }
        while (raw.length() > 1 && raw.endsWith("/")) raw = raw.substring(0, raw.length() - 1);
        return raw;
    }

    /**
     * 解析某 URL 对应的 Page 类名：优先复用会话级 urlToClass 稳定映射（同一 URL 全程复用同一类名，
     * 避免"离开默认页又回到默认页 URL 时被派生成 LogonPage 等重复类"）。
     * 仅当该 URL 从未出现时才用 pageClassNameFromUrl 派生，并登记进映射；派生时把已有的映射类名
     * 一并计入 used，避免与已分配类重名。
     */
    private static String resolvePageClassForUrl(String url, Collection<String> used,
                                                 LinkedHashMap<String, String> urlToClass) {
        String key = normalizeUrl(url);
        String existing = urlToClass.get(key);
        if (existing != null) return existing;
        Set<String> allUsed = new LinkedHashSet<>(used);
        allUsed.addAll(urlToClass.values());
        String cls = pageClassNameFromUrl(url, allUsed);
        urlToClass.put(key, cls);
        GLOBAL_URL_TO_CLASS.put(key, cls);
        return cls;
    }

    /**
     * 判断某 iframe 元素是否已被用户删除（命中会话级已删集合 STATE_DELETED）。
     * 删除时 collectDeleteKeys 会把多种键形态都记入 dead 集合（pickDedupKey key / _sig / 去索引 _sig /
     * _sigKey / RoleEntry.sigKey），而这里若只比对单一 key 可能漏命中 → iframe 残留元素经
     * mergeFramePicksToMain 复活。故把与删除同口径的候选键全部拿去比对，任一命中即视为已删。
     */
    private static boolean isDeletedKeyInState(LinkedHashMap<String, RoleEntry> map, String key,
                                               RoleEntry e, Map<Object, Object> m) {
        try {
            java.util.Set<String> dead = STATE_DELETED.get(map);
            if (dead == null || dead.isEmpty()) return false;
            // key 即 pickDedupKey（方案 B 下已绑定 pageClass），与 STATE_DELETED 记录同构，精确命中。
            if (key != null && !key.isEmpty() && dead.contains(key)) return true;
            if (e != null && e.getSigKey() != null && dead.contains(e.getSigKey())) return true;
            if (m != null) {
                // 【方案 B】dead 集合里的键均绑定 pageClass（如 "LogonPage|role:link:Language:#0"），
                // 故裸 _sig 比对一律加 pc 前缀匹配；不再使用跨页裸键比对，杜绝删 A 页误伤 B 页同名元素。
                Object sig = m.get("_sig");
                Object pcObj = m.get("_pageClass");
                String pcStr = (pcObj != null && !String.valueOf(pcObj).isEmpty())
                        ? String.valueOf(pcObj) : (e != null && e.getPageClass() != null ? e.getPageClass() : "");
                if (sig != null) {
                    String sigPc = pcStr + "|" + String.valueOf(sig);
                    if (dead.contains(sigPc)) return true;
                }
                Object sk = m.get("_sigKey");
                if (sk != null && dead.contains(String.valueOf(sk))) return true;
            }
            return false;
        } catch (Exception ignore) {
            return false;
        }
    }

    /** 取文件路径/URL 的最后一段（去掉所有路径分隔符前缀），用于 iframe src 与 frame.url() 的模糊匹配。 */
    private static String lastPathSegment(String s) {
        if (s == null || s.isEmpty()) return "";
        String v = s.replace('\\', '/');
        int slash = v.lastIndexOf('/');
        String seg = slash >= 0 ? v.substring(slash + 1) : v;
        int q = seg.indexOf('?');
        if (q >= 0) seg = seg.substring(0, q);
        int h = seg.indexOf('#');
        if (h >= 0) seg = seg.substring(0, h);
        return seg;
    }

    /** 把任意片段清洗为合法 Java 类名的"主体"（首字母大写；- _ . 空格 / 作单词边界；其余字符丢弃）。 */
    private static String toClassNameSegment(String seg) {
        if (seg == null || seg.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        boolean upperNext = true;
        for (int i = 0; i < seg.length(); i++) {
            char c = seg.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                sb.append(upperNext ? Character.toUpperCase(c) : c);
                upperNext = false;
            } else if (c == '-' || c == '_' || c == '.' || c == ' ' || c == '/') {
                upperNext = true;   // 分隔符 → 下一词首字母大写
            }
            // 其余字符丢弃
        }
        String s = sb.toString().replaceAll("[^\\p{L}\\p{N}_$]", "");
        if (s.isEmpty()) return "";
        if (!Character.isJavaIdentifierStart(s.charAt(0))) s = "P" + s;
        return s;
    }

    /** 移除常驻面板，并还原 docked 预留的右侧页面空间。
     *  页面可能已关闭（如关闭的是根页面导致会话结束、或会话收尾时页面已被回收），
     *  故对 evaluate 整体容错，避免 TargetClosedError 冒泡污染测试 step（之前"关闭新页面后 STEP ERROR"的根因）。 */
    private static void closePanel(Page page) {
        try {
            // 移除常驻面板的同时，移除点击/悬停/按键捕获监听并复位 active 标记，
            // 否则面板删了、监听器残留，会出现"面板消失却仍可静默拾取、不阻挡程序"（用户不期望）的半吊子状态。
            page.evaluate("(function(){"
                    + " try{ if(window.__rolePickClick) document.removeEventListener('click', window.__rolePickClick, true); }catch(e){}"
                    + " try{ if(window.__rolePickMove) document.removeEventListener('mousemove', window.__rolePickMove, true); }catch(e){}"
                    + " try{ if(window.__rolePickKey) document.removeEventListener('keydown', window.__rolePickKey, true); }catch(e){}"
                    + " try{ if(window.__rolePickFocus) document.removeEventListener('focusin', window.__rolePickFocus, true); }catch(e){}"
                    + " try{ if(window.__rolePickScroll) document.removeEventListener('scroll', window.__rolePickScroll, true); }catch(e){}"
                    + " try{ window.__rolePickActive = false; }catch(e){}"
                    // 清除会话开关 + 写面板墓碑：阻断 context 门控/引导注入脚本（无法撤销）
                    // 在会话结束后的导航中误自启拾取或重建面板。
                    + " try{ localStorage.removeItem('__rolePickSessionOn'); }catch(e){}"
                    + " try{ window.__rolePickSessionOn = false; }catch(e){}"
                    + " try{ localStorage.setItem('__rolePanelEnabled','0'); }catch(e){}"
                    + " try{ window.__rolePanelForce = false; }catch(e){}"
                    + " var p = document.getElementById('__rolePanel'); if (p) { p.remove();"
                    + " try { document.body.style.marginRight = ''; document.documentElement.style.overflowX = ''; } catch(e){} }"
                    + "})()");
        } catch (Exception ignore) {
            // 页面已关闭/不可操作：忽略，面板与监听随页面销毁一并消失，无需额外处理
        }
    }

    /**
     * 从页面读取当前拾取会话状态（picks / steps / currentStep / sigs / active）的 JSON 字符串，
     * 用于跨页面（弹窗打开/关闭）搬运。
     * 关键点：用浏览器内 JSON.stringify 直接产出字符串返回，而不是让 Playwright 把返回对象
     * 反序列化成 Java 对象再 GSON 重序列化——后者在遇到某些返回形态时会抛"无法序列化"异常，
     * 被 catch 成空串，进而把弹窗/父页的既有拾取整体清空（这是之前"回到原页全没了"的根因）。
     * 返回字符串永远可序列化，彻底规避该问题；页面已关闭或异常时返回空集 JSON。
     */
    private static String readPickStateJson(Page page) {
        try {
            Object res = page.evaluate("(function() {"
                    + " try {"
                    + "   return JSON.stringify({"
                    + "     picks: window.__rolePicks || [],"
                    + "     steps: window.__steps || [],"
                    + "     currentStep: window.__currentStep || [],"
                    + "     sigs: window.__rolePickSigs || {},"
                    + "     active: !!window.__rolePickActive });"
                    + " } catch (e) {"
                    + "   return JSON.stringify({picks:[],steps:[],currentStep:[],sigs:{},active:false});"
                    + " }"
                    + "})()");
            if (res instanceof String) return (String) res;
        } catch (Exception ignore) { /* 页面已关闭等：忽略，返回空集 */ }
        return "{\"picks\":[],\"steps\":[],\"currentStep\":[],\"sigs\":{},\"active\":false}";
    }

    /** 状态 JSON 是否包含至少一个已拾取元素（用于决定是否回写父页，避免误清空父页已有拾取） */
    private static boolean hasPicks(String stateJson) {
        try {
            Map<?, ?> m = GSON.fromJson(stateJson, MAP_STRING_OBJECT_TYPE);
            Object p = m == null ? null : m.get("picks");
            return p instanceof List && !((List<?>) p).isEmpty();
        } catch (Exception ignore) { return false; }
    }

    /** 状态 JSON 是否为"全空"（picks / steps / currentStep 均为空），用于快照更新时识别导航空窗期。 */
    private static boolean isEmptyState(String stateJson) {
        try {
            Map<?, ?> m = GSON.fromJson(stateJson, MAP_STRING_OBJECT_TYPE);
            if (m == null) return true;
            Object p = m.get("picks");
            Object s = m.get("steps");
            Object c = m.get("currentStep");
            boolean empty = (p == null || !(p instanceof List) || ((List<?>) p).isEmpty())
                    && (s == null || !(s instanceof List) || ((List<?>) s).isEmpty())
                    && (c == null || !(c instanceof List) || ((List<?>) c).isEmpty());
            return empty;
        } catch (Exception ignore) { return false; }
    }

    /**
     * 把拾取会话状态注入目标页面：不依赖 window.opener，兼容 rel="noopener" / 跨域弹窗
     * （这类弹窗 opener 为 null，旧逻辑的 opener 转移会整段跳过、导致拾取数据全丢）。
     * 同时注入 nls 反向表（供弹窗页点击时反查 key）。active 统一置 false，由调用方按需用 START 重启。
     */
    private static void applyPickState(Page target, String stateJson, String nlsReverseJson, String[] nlsFiles) {
        // 诊断：applyPickState 用快照恢复数据时会把 __rolePickActive 置 false，
        // 必须依赖 onFrameNavigated 末尾的 start() 重激活才能恢复拾取。若此步后无重激活，
        // 刷新后点击将彻底失效（监听存在但 active=false，__recordPick 直接 return）。
        log.info("[picker][applyPickState] 用 Java 快照恢复数据（picks={} / steps={} / currentStep={}），"
                        + "即将把 __rolePickActive 置 false，等待 onFrameNavigated 重激活；target={}",
                pickCountOf(stateJson), stepCountOf(stateJson), currentStepCountOf(stateJson), target.url());
        target.evaluate("(function() {"
                + " try { localStorage.setItem('__rolePanelEnabled','1'); } catch(e){}"
                + " window.__nlsFiles = " + GSON.toJson(nlsFiles) + ";"
                + " var __o = " + (nlsReverseJson == null ? "{}" : nlsReverseJson) + ";"
                + " window.__nlsReverse = (__o && __o.exact) ? __o.exact : (__o && __o.templates ? {} : (__o || {}));"
                + " window.__nlsTemplates = (__o && __o.templates) ? __o.templates : [];"
                // 保留 start() 已写入的录制根约束（弹窗恢复状态时不覆盖，避免退化成整页录制）。
                + " if (window.__rolePickRoot === undefined) window.__rolePickRoot = null;"
                + " var s = " + stateJson + ";"
                + " window.__rolePicks = s.picks || [];"
                // 【关键修复"导航恢复后点元素/封装不进 step"】applyPickState 仅在导航恢复（非实时扫描）时调用，
                // 此时 __scanning 已 false。扫描产生的候选带 __isScan 标记（仅候选、不进 __currentStep），
                // 经恢复后若保留该标记，用户回 LogonPage 再点这些元素会因 __isScan 守卫（3631 行）进不了选择集，
                // 导致 __currentStep 始终为空、点封装按钮 return 0、Java 侧永远不生成代码。
                // 恢复即视为"已拾取完成"，清除 __isScan 使这些候选等同手动拾取、可正常勾选封装。
                + " (window.__rolePicks || []).forEach(function(p){ if(p&&p.__isScan){ p.__isScan=false; } });"
                + " window.__steps = s.steps || [];"
                + " window.__currentStep = s.currentStep || [];"
                + " window.__rolePickSigs = s.sigs || {};"
                // 【修复"跨页拾取 index 被重置"】恢复快照后，续接全局拾取序号计数器 __rolePickSeq，
                // 取已有 pick._pickNos 的最大值（无则 0），使后续跨页新拾取的序号接着递增（如 8→9），
                // 而非脚本重注入时归零从 1 重数，避免与已恢复的 [1..8] 序号冲突、面板 index 跳回 1。
                + " (function(){ var mx=0; (window.__rolePicks||[]).forEach(function(p){"
                + "   (p&&Array.isArray(p._pickNos)?p._pickNos:[]).forEach(function(n){ if(n>mx)mx=n; }); });"
                + "   window.__rolePickSeq = mx; })();"
                + " window.__rolePickActive = false;"
                + " try { if (window.__renderPicks) window.__renderPicks(); } catch(e){}"
                + "})()");
    }

    // 诊断辅助：从快照 JSON 里安全解析各类计数，避免 applyPickState 日志打印整段 state（可能很大）。
    @SuppressWarnings("unchecked")
    private static int pickCountOf(String stateJson) {
        try {
            java.util.Map<String, Object> m = GSON.fromJson(stateJson, MAP_STRING_OBJECT_TYPE);
            Object p = (m == null) ? null : m.get("picks");
            return (p instanceof java.util.List) ? ((java.util.List<?>) p).size() : 0;
        } catch (Exception e) { return -1; }
    }
    @SuppressWarnings("unchecked")
    private static int stepCountOf(String stateJson) {
        try {
            java.util.Map<String, Object> m = GSON.fromJson(stateJson, MAP_STRING_OBJECT_TYPE);
            Object p = (m == null) ? null : m.get("steps");
            return (p instanceof java.util.List) ? ((java.util.List<?>) p).size() : 0;
        } catch (Exception e) { return -1; }
    }
    @SuppressWarnings("unchecked")
    private static int currentStepCountOf(String stateJson) {
        try {
            java.util.Map<String, Object> m = GSON.fromJson(stateJson, MAP_STRING_OBJECT_TYPE);
            Object p = (m == null) ? null : m.get("currentStep");
            return (p instanceof java.util.List) ? ((java.util.List<?>) p).size() : 0;
        } catch (Exception e) { return -1; }
    }

    /** 更新面板顶部状态文字 */
    private static void setStatus(Page page, String msg) {
        page.evaluate("window.__roleStatusMsg = " + GSON.toJson(msg));
        page.evaluate("var st = document.getElementById('__roleStatus'); if (st) st.textContent = window.__roleStatusMsg;");
    }

    /** 把按页生成的页面类/步骤代码分别写入面板的多 Tab，并更新状态 */
    private static void fillCode(Page page, LinkedHashMap<String, String> pageClassByPage, LinkedHashMap<String, String> stepByPage, String msg) {
        // 企业级优化：把"写入消息对象"与"更新 DOM"合并进同一次 page.evaluate，
        // 点击"停止"后只需 1 次往返即可把分页代码渲染进面板对应 Tab（原来 2 次串行往返）。
        page.evaluate("(function(){"
                + " window.__fillCodeTabs({"
                + " pageByPage:" + GSON.toJson(pageClassByPage == null ? new LinkedHashMap<String, String>() : pageClassByPage)
                + ", stepByPage:" + GSON.toJson(stepByPage == null ? new LinkedHashMap<String, String>() : stepByPage)
                + ", msg:" + GSON.toJson(msg == null ? "" : msg) + "});"
                + "})()");
    }

    /**
     * 由一组已拾取元素按所属页面类分组生成页面类源码（与 stop 命令的生成逻辑一致）。
     * 返回 pageClass → 该页完整页面类源码 的 map，供面板"页面类"Tab 按页分栏展示（对齐"页面元素"Tab）。
     * 单页即一个 entry；多页（弹窗/新标签页）则各元素按其 `_pageClass` 各自成类。
     *
     * @param entries       全部拾取元素（来自浏览器侧 window.__rolePicks，扫描与点击拾取已合并）
     * @param packageName   生成类的包名
     * @param defaultPageClass 兜底页类名（元素未带 _pageClass 时归入此类）
     * @param nlsFiles      nls 反向查表文件（用于定位键反查）
     * @return pageClass → 页面类源码（LinkedHashMap 保序，空列表时返回空 map）
     */
    private static LinkedHashMap<String, String> buildPageClassCode(List<RoleEntry> entries, String packageName,
                                             String defaultPageClass, String[] nlsFiles) {
        LinkedHashMap<String, String> out = new LinkedHashMap<>();
        if (entries == null || entries.isEmpty()) return out;
        LinkedHashMap<String, List<RoleEntry>> entriesByPage = new LinkedHashMap<>();
        for (RoleEntry e : entries) {
            String pc = (e.getPageClass() == null || e.getPageClass().isEmpty()) ? defaultPageClass : e.getPageClass();
            entriesByPage.computeIfAbsent(pc, k -> new ArrayList<>()).add(e);
        }
        for (Map.Entry<String, List<RoleEntry>> en : entriesByPage.entrySet()) {
            out.put(en.getKey(), RoleElementPageGenerator.generate(en.getValue(), packageName, en.getKey(), nlsFiles));
        }
        return out;
    }

    /**
     * 由快照（已拾元素 + 已封装 steps/ops）按页生成步骤代码（与 stop 命令的生成逻辑一致）。
     * 返回 pageClass → 该页"完整可编译的 Step 类视图"源码 的 map，供面板"步骤代码"Tab 按页分栏展示
     * （对齐"页面元素"Tab）。多页时主页视图含跨页 step、弹窗页视图含其 close 操作。
     *
     * @return pageClass → 步骤类视图源码（LinkedHashMap 保序，无 step/操作时返回空 map）
     */
    /**
     * Manual-mode / not-packaged fallback: treat ALL picked entries as ONE step
     * (start -> stop = one step). If already packaged (snap.steps non-empty, ordered by selection),
     * leave it untouched. step.pageClass uses the root page (first entry's pageClass).
     * Cross iframe/new-page/dialog entries keep their framePath/dialog/popup markers,
     * handled per-element by RoleElementStepGenerator (switchToFrame/acceptAlert/waitForNewPage).
     */
    private static PickSnapshot snapWithAutoStep(PickSnapshot snap) {
        if (snap == null) return snap;
        if (snap.steps != null && !snap.steps.isEmpty()) return snap;          // already packaged: keep selection order
        if (snap.entries == null || snap.entries.isEmpty()) return snap;
        List<RoleEntry> all = new ArrayList<>(snap.entries);
        String rootPc = "";
        for (RoleEntry e : all) {
            String pc = e.getPageClass();
            if (pc != null && !pc.isEmpty()) { rootPc = pc; break; }
        }
        if (rootPc.isEmpty()) rootPc = (snap.pageClass == null) ? "" : snap.pageClass;
        // 手动模式（start→stop 未封装）：所有拾取元素封装为【一个步骤】(step1)，
        // 步骤内元素按点击序号（getSeq）升序排列——即"按序号封装为一个步骤"。
        // 序号相等的保持原拾取顺序（避免 ArrayList.sort 不稳定重排）。
        List<RoleEntry> sorted = new ArrayList<>(all);
        sorted.sort(java.util.Comparator.comparingInt((RoleEntry e) -> (e == null ? 0 : e.getSeq()))
                .thenComparingInt(all::indexOf));
        List<StepRec> steps = new ArrayList<>();
        steps.add(new StepRec(rootPc, sorted));
        return new PickSnapshot(snap.pageClass, snap.entries, steps, snap.ops);
    }

    private static LinkedHashMap<String, String> buildStepCode(PickSnapshot snap, String packageName, String stepClassName) {
        LinkedHashMap<String, String> out = new LinkedHashMap<>();
        if (snap == null) return out;
        // 【关键修复"iframe 内元素生成 step 缺 switchToFrame"】
        // snap.steps 来自浏览器侧 window.__steps 读取，其 pick 的 framePath/dialog/popup 等增强字段
        // 是浏览器侧 __enrichState / 点击 handler 在拾取瞬间写入的——但 iframe 内：
        //   · __framePathOf 在 file:// 跨 frame 下整段抛异常被吞为空数组，pick.framePath 未被赋值；
        //   · 绑定桥失效，console 通道的 framePath backfill 又因 e.getPageClass()（iframe URL）被映射到
        //     iframe 子页视图，使浏览器侧 pick.framePath 始终为空。
        // 而 snap.entries 在外层已被 javaPickBySig（带 framePath backfill + 对话框/弹窗双保险）覆盖——
        // entries 含正确增强，steps 不含。生成时 step 循环用 st.picks、entry 循环用 snap.entries，
        // 字段名按 entries 的 locatorKey 匹配，但 switchToFrame 按 st.picks.getFramePath()——于是缺。
        // 此处按 locatorKey 把 entries 的 framePath/dialog/popup 回补到 steps 的 picks 上（idempotent，
        // 取非空/true 优先），保证生成时 step 能拿到与 Page 类字段一致的增强字段。
        if (snap.entries != null && !snap.entries.isEmpty() && snap.steps != null) {
                java.util.Map<String, RoleEntry> byLocKey = new java.util.HashMap<>();
                for (RoleEntry en : snap.entries) {
                    String lk = RoleElementPageGenerator.locatorKey(en);
                    if (lk != null && !lk.isEmpty()) byLocKey.put(lk, en);
                }
                if (!byLocKey.isEmpty()) {
                    List<StepRec> enrichedSteps = new ArrayList<>(snap.steps.size());
                    for (StepRec st : snap.steps) {
                        if (st == null) { enrichedSteps.add(st); continue; }
                        List<RoleEntry> enrichedPicks = new ArrayList<>(st.picks == null ? 0 : st.picks.size());
                        if (st.picks != null) for (RoleEntry p : st.picks) {
                            String lk = (p == null) ? "" : RoleElementPageGenerator.locatorKey(p);
                            RoleEntry mem = (lk.isEmpty()) ? null : byLocKey.get(lk);
                            if (mem != null && p != null) {
                                // javaPickBySig 是权威内存态：含点击后 checked/setCheckedTarget、framePath backfill、
                                // dialog/popup 双保险。RoleEntry 的 checked/setCheckedTarget 为 final 不可变，
                                // 无法 merge，故有增强差异时直接用 mem 替换 pick（保证 setCheckedTarget 是点击后状态，
                                // 否则点击"未勾选"checkbox 会因浏览器侧点击前状态生成 setChecked(false) 导致"没选择上"）。
                                if (needsReplaceByMemory(p, mem)) { p = mem; }
                            }
                            enrichedPicks.add(p);
                        }
                        enrichedSteps.add(new StepRec(st.pageClass, enrichedPicks));
                    }
                    snap = new PickSnapshot(snap.pageClass, snap.entries, enrichedSteps, snap.ops);
                }
            }
        String curClass = (snap.pageClass == null) ? "" : snap.pageClass;
        LinkedHashMap<String, List<RoleEntry>> entriesByPage = new LinkedHashMap<>();
        for (RoleEntry e : snap.entries) {
            String pc = (e.getPageClass() == null || e.getPageClass().isEmpty()) ? curClass : e.getPageClass();
            entriesByPage.computeIfAbsent(pc, k -> new ArrayList<>()).add(e);
        }
        // 生成前对账：steps 与 picks 是两条独立数据源，且 steps 在导航恢复/已关闭页缓存等路径上
        // 是「只增不减」地合并回来的，已删元素仍可能以"幽灵 pick"残留在某条 step 里。
        // 页面类字段只由 picks 生成，故此处按 locatorKey（与字段表同一套匹配口径）把
        // 在 entries 中已不存在的 pick 从 step 中剔除，并丢弃因此变空的 step。
        // 不这样做的话，代码生成侧会靠 field==null 静默 continue 跳过：编译能过，但动作凭空消失。
        java.util.Set<String> aliveKeys = new java.util.HashSet<>();
        for (RoleEntry e : snap.entries) {
            String lk = RoleElementPageGenerator.locatorKey(e);
            if (lk != null && !lk.isEmpty()) aliveKeys.add(lk);
        }
        int droppedPicks = 0, droppedSteps = 0;
        LinkedHashMap<String, List<List<RoleEntry>>> stepsByPage = new LinkedHashMap<>();
        if (snap.steps != null) for (StepRec st : snap.steps) {
            String pc = (st.pageClass == null || st.pageClass.isEmpty()) ? curClass : st.pageClass;
            List<RoleEntry> picks = st.picks;
            if (picks != null && !aliveKeys.isEmpty()) {
                List<RoleEntry> kept = new ArrayList<>(picks.size());
                for (RoleEntry e : picks) {
                    if (e == null) continue;
                    String lk = RoleElementPageGenerator.locatorKey(e);
                    // 键为空者无法对账，保守保留（生成侧仍有 field==null 兜底，不会产生悬空引用）。
                    if (lk == null || lk.isEmpty() || aliveKeys.contains(lk)) kept.add(e);
                    else {
                        droppedPicks++;
                        // 诊断：i18n/定位器型策略元素若因 locatorKey 不匹配被 drop（典型表现"步骤里完全没有这行"），
                        // 打印其 strategy/name/lk 与 aliveKeys 中同类键，便于定位 index(#0) 错位或字段不一致根因。
                        if ("i18n".equals(e.getStrategy()) || (e.getStrategy() != null
                                && LOCATOR_IDENTITY_STRATEGIES.contains(e.getStrategy()))) {
                            log.info("[picker][drop-diag] 步骤元素被对账剔除：strategy={}, name={}, lk={}, count={}, index={}, aliveKeys(i18n类)={}",
                                    e.getStrategy(), e.getName(), lk, e.getCount(), e.getIndex(),
                                    aliveKeys.stream().filter(k -> k != null && k.startsWith(e.getStrategy() + ":")).limit(10).collect(java.util.stream.Collectors.toList()));
                        }
                    }
                }
                picks = kept;
            }
            if (picks == null || picks.isEmpty()) {
                // 整条 step 的元素都已被删：不生成空的 @Step 方法。
                if (st.picks != null && !st.picks.isEmpty()) droppedSteps++;
                continue;
            }
            stepsByPage.computeIfAbsent(pc, k -> new ArrayList<>()).add(picks);
            entriesByPage.computeIfAbsent(pc, k -> new ArrayList<>());
        }
        if (droppedPicks > 0 || droppedSteps > 0) {
            log.info("[picker] 生成前对账：剔除已删元素引用 {} 处，丢弃空 step {} 条", droppedPicks, droppedSteps);
        }
        // 【关键修复"只点了 2 个元素却生成很多步骤"】
        // 旧逻辑曾在此"兜底"：把 javaPickBySig 中位于 iframe 内但未被任何 step 引用的元素补进最后一个 step，
        // 目的是修复"嵌套 iframe 元素不进 step"。但该前提在"整页扫描"引入后不再成立——扫描出的全部 iframe
        // 候选（40 个）也回传进入 javaPickBySig，与"用户真实点击"混在一起。用户只点了 2 个主框架元素时，
        // referencedKeys(2) < aliveKeys(40) 恒成立，兜底便把 36 个未入 step 的 iframe 扫描候选全补进 step，
        // 表现为"点 2 个元素、步骤却一大堆"。
        // 现用户点击的 iframe 元素经 postMessage（不带 __isScan）正常上送顶层进入 __currentStep/__steps，
        // 无需此兜底；故直接移除，让 step 只含用户真实点击/勾选的元素。主框架元素链路不受影响。
        LinkedHashMap<String, List<String>> opsByPage = new LinkedHashMap<>();
        if (snap.ops != null) for (PageOp op : snap.ops) {
            String pc = (op.pageClass == null || op.pageClass.isEmpty()) ? curClass : op.pageClass;
            opsByPage.computeIfAbsent(pc, k -> new ArrayList<>()).add(op.op);
        }
        if (stepsByPage.isEmpty() && opsByPage.isEmpty()) return out;
        return RoleElementStepGenerator.generatePerPage(stepsByPage, entriesByPage, opsByPage, packageName, stepClassName);
    }

    private static String asString(Object o) {
        return o == null ? null : o.toString();
    }

    /** 关闭步骤序号器：保证每次"关闭当前页"标记签名唯一、可去重。 */
    private static final java.util.concurrent.atomic.AtomicInteger CLOSE_SEQ =
            new java.util.concurrent.atomic.AtomicInteger();

    /**
     * 把"关闭当前页"补登记为一条 step（含 _closeOp 标记的 pick），追加进该页缓存快照（snapshots），
     * 供停止生成时（已关闭页被跳过）折叠回最终快照，从而生成 closeCurrentPage() 步骤。
     * 仅用于"同标签整页跳转到新页面后直接关闭"的根页场景（普通弹窗关闭已由 onClose 的父页分支处理）。
     */
    private static void appendCloseOpStep(Page closed, String pageClass,
                                          LinkedHashMap<Page, String> snapshots) {
        try {
            String json = snapshots.get(closed);
            java.util.Map<String, Object> m = (json != null && !json.isEmpty())
                    ? GSON.fromJson(json, MAP_STRING_OBJECT_TYPE) : null;
            if (m == null) m = new java.util.LinkedHashMap<String, Object>();
            java.util.List<java.util.Map<String, Object>> steps;
            Object st = m.get("steps");
            if (st instanceof java.util.List) {
                @SuppressWarnings("unchecked")
                java.util.List<java.util.Map<String, Object>> tmp = (java.util.List<java.util.Map<String, Object>>) st;
                steps = tmp;
            } else {
                steps = new java.util.ArrayList<java.util.Map<String, Object>>();
                m.put("steps", steps);
            }
            // 去重：同页已存在相同 pageClass 的关闭 step 则不重复追加。
            boolean dup = false;
            for (Object s : steps) {
                if (s instanceof java.util.Map && pageClass.equals(((java.util.Map<?, ?>) s).get("pageClass"))
                        && hasClosePick((java.util.Map<?, ?>) s)) { dup = true; break; }
            }
            if (dup) return;
            java.util.Map<String, Object> step = new java.util.LinkedHashMap<String, Object>();
            step.put("pageClass", pageClass);
            java.util.List<java.util.Map<String, Object>> picks = new java.util.ArrayList<java.util.Map<String, Object>>();
            java.util.Map<String, Object> closePick = new java.util.LinkedHashMap<String, Object>();
            closePick.put("_closeOp", Boolean.TRUE);
            closePick.put("_pageClass", pageClass);
            closePick.put("_sig", "__close_" + CLOSE_SEQ.incrementAndGet());
            closePick.put("tag", "close");
            picks.add(closePick);
            step.put("picks", picks);
            steps.add(step);
            snapshots.put(closed, GSON.toJson(m));
        } catch (Exception ignore) { /* 缓存快照不可用：忽略，关闭步骤将缺失（极少见） */ }
    }

    private static boolean hasClosePick(java.util.Map<?, ?> step) {
        Object ps = step.get("picks");
        if (ps instanceof java.util.List) {
            for (Object p : (java.util.List<?>) ps) {
                if (p instanceof java.util.Map && Boolean.TRUE.equals(((java.util.Map<?, ?>) p).get("_closeOp"))) {
                    return true;
                }
            }
        }
        return false;
    }
}


