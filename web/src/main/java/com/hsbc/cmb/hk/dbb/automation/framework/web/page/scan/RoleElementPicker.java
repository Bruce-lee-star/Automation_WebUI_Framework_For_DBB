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

// 快照解析簇（T5-1 拆分）：readPickSnapshot/stopAndRead/parsePickSnapshot/getPageOpsWithPage/getStepsWithPage
// 已下沉至 RolePickerSnapshotParser，此处桥接保持 runPickerCommand / getSteps 的原调用形态（行为零回归）。
import static com.hsbc.cmb.hk.dbb.automation.framework.web.page.scan.RolePickerSnapshotParser.readPickSnapshot;
import static com.hsbc.cmb.hk.dbb.automation.framework.web.page.scan.RolePickerSnapshotParser.stopAndRead;
import static com.hsbc.cmb.hk.dbb.automation.framework.web.page.scan.RolePickerSnapshotParser.parsePickSnapshot;
import static com.hsbc.cmb.hk.dbb.automation.framework.web.page.scan.RolePickerSnapshotParser.getPageOpsWithPage;
import static com.hsbc.cmb.hk.dbb.automation.framework.web.page.scan.RolePickerSnapshotParser.getStepsWithPage;

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

    static final Logger log = LoggerFactory.getLogger(RoleElementPicker.class);

    /**
     * 上下文级"当前会话"桥（对齐 {@code page.pause()} 的 DebugController/Recorder 解耦）：
     * 命令桥/拾取桥不再逐页 {@code exposeFunction}，而是对 {@link BrowserContext} 一次性
     * {@code exposeBinding}——context 下所有当前与未来页面（弹窗/新标签页）、每次导航后的新文档
     * 都自动持有绑定，由 {@code BindingCallback.Source#page()} 天然区分"哪个页面发起"。
     * 同名绑定不可重复注册（重复会抛 {@code PlaywrightException}），故每个 context 仅注册一次；
     * 二次打开（同一 context 再次 {@code openPanel}）只更新下方 Map 指向的"当前会话"队列/状态，
     * 回调动态读取，避免命令/拾取被投递到已失效的旧会话队列。
     */

    /**
     * 拾取模式（一级概念，三种模式互斥）：
     *  - IDLE：待命（面板显示"▶ 开始拾取"，页面点击不拾取任何元素）
     *  - MANUAL：手动拾取（点哪个元素拾哪个，iframe/新窗口/alert 按归属字段区分）
     *  - SCAN_PAGE：整页扫描（穿透 iframe/shadow，扫完自动回 IDLE）
     *  - SCAN_REGION：区域扫描（选区域后扫描，扫完自动回 IDLE）
     * 与浏览器侧 window.__roleMode 同步，由 Java 权威驱动。
     */
    public enum PickMode { IDLE, MANUAL, SCAN_PAGE, SCAN_REGION }


    /** 在 BasePage.closeCurrentPage 调 page.close() 前调用：标记本页为"框架主动关闭"。 */
    public static void markFrameworkClose(Page page) {
        RolePickerSessionState.markFrameworkClose(page);
    }

    /**
     * 彻底清理指定 BrowserContext 在所有静态 Map 中的状态。
     * <p>
     * 调用时机：BrowserContext 关闭之后（PlaywrightManager.closeContext 钩子 / @AfterMethod / hook）。
     * 否则长跑测试中累积的 CTX_* Map 会导致内存泄漏、上下文复活后命中陈旧状态。
     * </p>
     */
    public static void cleanupContext(BrowserContext ctx) {
        RolePickerSessionState.cleanupContext(ctx);
    }

    /** 清理指定 Page 的 page-level 状态（frameNavigated 跟踪）。 */
    public static void cleanupPage(Page page) {
        RolePickerSessionState.cleanupPage(page);
    }

    /** 清空所有静态 Map —— 主要用于 JVM 关闭或测试集群重置。 */
    public static void clearAll() {
        RolePickerSessionState.clearAll();
    }

    /** 设置某 context 的拾取模式，并同步到所有未关闭页面（驱动面板按钮态与浏览器侧行为）。 */
    static void setPickMode(Page anyPage, PickMode mode,
                                    LinkedHashMap<Page, String> pageNames) {
        if (anyPage == null || anyPage.isClosed()) return;
        BrowserContext ctx = anyPage.context();
        RolePickerSessionState.CTX_PICK_MODES.put(ctx, mode);
        String jsMode = mode.name().toLowerCase();
        if (pageNames != null) {
            for (Page p : pageNames.keySet()) {
                try { if (!p.isClosed()) p.evaluate(RolePickerScripts.SET_PICK_MODE_JS, RolePickerScripts.args("mode", jsMode)); }
                catch (Exception ignore) {}
            }
        }
    }
    // 上下文级初始化脚本守卫：面板脚本每 context 仅注册一次；拾取脚本按 nls 内容变化才追加注册
    // （addInitScript 无法撤销，重复注册会累积执行；同 nls 幂等跳过，不同 nls 追加后"后注册者后执行"覆盖生效）。



    /**
     * 会话级持久"已删集合"：用户主动删除的元素键（_sig / _sigKey / 去索引 locatorKey）。
     * 与浏览器端 {@code window.__deletedSigs} 的区别在于后者在【区域扫描入口】会被清空
     * （区域扫描 = 本次选中区域里的"全新候选"语义，不应继承过往删除屏蔽），而本集合跨扫描/跨页面
     * 持久。作用：{@code syncPanelToBrowser} 把 Java 权威内存态 javaPickBySig 回灌浏览器时，
     * 即便 Java 删除因键不匹配未命中实体、且浏览器端 __deletedSigs 已被区域扫描清空，
     * 仍按本集合永久屏蔽已删元素，杜绝"区域扫描后已删元素被主循环复活"的回归。
     * key 为 javaPickBySig 对象引用（每会话稳定，O(1) 反查），值为已删键集合。
     */




    /**
     * 面板命令事件：由页面内 {@code window.__rolePickerCmd(c)}（经 {@link Page#exposeFunction} 暴露的
     * Java 回调）投递，携带"哪个页面发的命令"。主循环从阻塞队列取出后据此驱动，避免忙轮询所有页面。
     */
    static final class CmdEvent {
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
    static void registerContextBridges(BrowserContext ctx,
                                               BlockingQueue<CmdEvent> queue,
                                               LinkedHashMap<String, RoleEntry> javaPickBySig) {
        RolePickerSessionState.CTX_CMD_QUEUES.put(ctx, queue);
        // 【关键修复"二次 openPanel 导致已拾元素清零"】二次 openPanel（设计上支持，见类注释"二次打开只换 Map 指向"）
        // 会 new 一个空 javaPickBySig 并传入；若此处直接 put 覆盖，context 权威内存态会被空 map 替换，
        // 后续 __roleOnPick 回调（RolePickerSessionState.CTX_PICK_STATES.get(ctx)）全部写进空 map → 此前 LogonPage+SetupSecondPwdPage
        // 已拾的全部元素丢失（日志现象：内存态 49→1）。故二次打开时把旧会话历史迁移合并进本次 map（保留去重顺序），
        // 让 openPanel 后续代码（RolePickerSessionState.STATE_DELETED 等）拿到"含历史"的同一引用，历史不丢。
        LinkedHashMap<String, RoleEntry> prev = RolePickerSessionState.CTX_PICK_STATES.get(ctx);
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
                    if (migrated != null) { migrated.setPickNos(new java.util.ArrayList<>()); migrated.setSeq(0); }
                    javaPickBySig.put(e.getKey(), migrated);
                }
            }
        }
        RolePickerSessionState.CTX_PICK_STATES.put(ctx, javaPickBySig);
        boolean first = RolePickerSessionState.CTX_BRIDGED.add(ctx);
        log.info("[picker] 上下文桥 registerContextBridges：firstReg={}（命令/拾取/控制台桥，context 级一次注册）", first);
        if (!first) return;
        // 命令桥：BindingCallback 的 Source 自带来源 Page，天然区分命令来自哪个页面
        // （新页/默认页共享同一绑定，CmdEvent.page 记录来源）。绑定对 context 下所有页面、所有导航存活。
        registerCmdBridge(ctx);
        // 拾取桥：浏览器端经 window.__roleOnPick(JSON.stringify(pick)) 异步投递，零往返回传 Java 内存态。
        registerPickBridge(ctx);
        // 删除桥：面板「垃圾桶」删除选中元素时，同步落到 Java 侧权威内存态（否则主循环 syncPanelToBrowser 会"复活"被删元素）。
        registerDeleteBridge(ctx);
        // 控制台兜底桥：context 级 onConsoleMessage 捕获所有页面的兜底回传与拾取链路报错，即使绑定失效回传也不丢失。
        registerConsoleBridge(ctx);
        // 动态 iframe 监听器：context 级对每个（含弹窗/新开）页面挂 onFrameAttached，覆盖运行时新附加/动态创建的 iframe。
        registerFrameAttachListener(ctx);
    }







    /** 命令桥：浏览器侧经 window.__rolePickerCmd 投递，零往返把命令入队；BindingCallback 的 Source 自带来源 Page，天然区分命令来自哪个页面。 */
    private static void registerCmdBridge(BrowserContext ctx) {
        ctx.exposeBinding("__rolePickerCmd", (source, args) -> {
            BlockingQueue<CmdEvent> q = RolePickerSessionState.CTX_CMD_QUEUES.get(ctx);
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
    }

    /** 拾取桥：浏览器端经 window.__roleOnPick(JSON.stringify(pick)) 异步投递，零往返回传 Java 内存态，带回补 framePath 与去重合并。 */
    private static void registerPickBridge(BrowserContext ctx) {
        ctx.exposeBinding("__roleOnPick", (source, args) -> {
            LinkedHashMap<String, RoleEntry> map = RolePickerSessionState.CTX_PICK_STATES.get(ctx);
            if (map == null) return null;
            try {
                if (args == null || args.length == 0) return null;
                Object v = args[0];
                if (v == null) return null;
                // 【diag-raw】原始回传参数：确认浏览器经 binding 实际投递给 Java 的 JSON 是否含 _pickNos 字段。
                log.info("[picker][diag-raw] __roleOnPick raw arg type={} value={}", (v == null ? "null" : v.getClass().getSimpleName()), String.valueOf(v));
                @SuppressWarnings("unchecked")
                Map<Object, Object> m = GSON.fromJson(String.valueOf(v), Map.class);
                RoleEntry e = RolePickerPickParser.parsePick(m);
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
                        List<String> fp = RolePickerFramePath.computeFramePath(source.page(), source.frame());
                        if (fp != null && !fp.isEmpty()) e.setFramePath(fp);
                    } catch (Exception ignore) {}
                }
                // 去重键与浏览器端保持一致但更精确：定位器唯一型策略（id/css/i18n/text/...）按 locator 签名（_sig）
                // 去重，避免同一元素在"主页↔弹窗"间被重复收录；角色/closeOp 仍按 [sig, pageClass|URL]（_sigKey）区分。
                // 重复点击以最近一次交互为准整条替换（RoleEntry 不可变，更新须替换）；首次插入保序。
                String key = RolePickerPickParser.pickDedupKey(m, e);
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
                            || !RolePickerPickParser.roleEq(existing.getSigKey(), e.getSigKey())
                            || !RolePickerPickParser.framePathEq(existing.getFramePath(), e.getFramePath())
                            || (existing.isDialog() != e.isDialog())
                            || (existing.isPopup() != e.isPopup());
                    RoleEntry merged = RolePickerPickParser.mergePickIntoMap(map, key, e);
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
    }

    /** 删除桥：面板「垃圾桶」删除选中元素时，浏览器端经 window.__roleOnDelete 回传，精确同步到 Java 侧权威内存态（含值级兜底匹配，杜绝删除残留）。 */
    private static void registerDeleteBridge(BrowserContext ctx) {
        ctx.exposeBinding("__roleOnDelete", (source, args) -> {
            LinkedHashMap<String, RoleEntry> map = RolePickerSessionState.CTX_PICK_STATES.get(ctx);
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
                java.util.Set<String> dead = RolePickerPickParser.collectDeleteKeys(raw);
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
                    // ③ 【已禁用"整桶删除同页残留"】
                    // 原逻辑：收集被删元素的 pageClass，把同 pageClass 的所有元素全部删除。
                    // 问题：用户点击垃圾箱只删除单个元素，但此逻辑会把同页所有元素一并删除，
                    // 导致"删一个丢全部"。JS 侧 __deleteSinglePick 已正确处理浏览器侧删除和重编号，
                    // Java 侧只需精确移除目标元素即可，不再做整桶删除。
                    // 【修复"已删除元素无法重新拾取"】
                    // 旧逻辑：RolePickerSessionState.STATE_DELETED 永久记录已删键，导致 isDeletedKeyInState 检查命中后跳过元素，
                    // 用户永远无法重新拾取已删除的元素。
                    // 新逻辑：不再写入 RolePickerSessionState.STATE_DELETED，允许用户重新拾取。删除的语义是"从当前拾取列表移除"，
                    // 而非"永久封杀该元素"。若需防止跨区域扫描复活，应由浏览器侧 __deletedSigs 临时屏蔽。
                }
                // 【已禁用"清空所有 frame 的 __rolePicks"】
                // 原逻辑：删除元素时清空所有 frame 的 __rolePicks/__rolePickSigs/__currentStep。
                // 问题：用户点击垃圾箱只删除单个元素，但此逻辑会清空所有 frame 的全部元素，
                // 导致"删一个丢全部"。JS 侧 __deleteSinglePick 已正确处理浏览器侧删除和重编号，
                // Java 侧只需精确移除目标元素即可，不再做全量清空。
            } catch (Exception ex) {
                log.warn("[picker] __roleOnDelete 回传解析失败：{}", ex.getMessage());
            }
            return null;
        });
    }

    /** 控制台兜底桥：context 级 onConsoleMessage 捕获所有页面的 __roleOnPick::/__roleOnDelete:: 兜底回传与拾取链路报错，即使绑定失效回传也不丢失。 */
    private static void registerConsoleBridge(BrowserContext ctx) {
        ctx.onConsoleMessage(msg -> {
            String t = msg.text();
            if (t == null) return;
            if (t.startsWith("__roleOnPick::")) {
                LinkedHashMap<String, RoleEntry> map = RolePickerSessionState.CTX_PICK_STATES.get(ctx);
                if (map == null) return;
                try {
                    @SuppressWarnings("unchecked")
                    Map<Object, Object> m = GSON.fromJson(t.substring("__roleOnPick::".length()), Map.class);
                    RoleEntry e = RolePickerPickParser.parsePick(m);
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
                                                List<String> fp = RolePickerFramePath.computeFramePath(p, f);
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
                    String key = RolePickerPickParser.pickDedupKey(m, e);
                    synchronized (map) {
                        RoleEntry existing = map.get(key);
                        boolean changed = (existing == null)
                                || !RolePickerPickParser.roleEq(existing.getSigKey(), e.getSigKey())
                                || !RolePickerPickParser.framePathEq(existing.getFramePath(), e.getFramePath())
                                || (existing.isDialog() != e.isDialog())
                                || (existing.isPopup() != e.isPopup());
                        RoleEntry merged = RolePickerPickParser.mergePickIntoMap(map, key, e);
                        log.info("[picker][diag-onpick][CONSOLE] key={} pickNos(after-merge)={} changed={} rawNos={} strategy={} keys={}", key, merged.getPickNos(), changed, m.get("_pickNos"), (e != null ? e.getStrategy() : null), (m != null ? m.keySet() : null));
                        if (changed) {
                            List<String> fplog = merged.getFramePath();
                            log.info("[picker] __roleOnPick(console) 回传写入内存态：key={} pageClass={} framePath={}（当前内存态大小={}）", key, (merged.getPageClass() == null ? "" : merged.getPageClass()), (fplog == null || fplog.isEmpty() ? "" : fplog.toString()), map.size());
                        }
                    }
                } catch (Exception ignore) {}
            } else if (t.startsWith("__roleOnDelete::")) {
                // 删除的控制台兜底：与 __roleOnPick:: 对称，绑定失效时删除同样不丢（按键移除天然幂等）。
                LinkedHashMap<String, RoleEntry> map = RolePickerSessionState.CTX_PICK_STATES.get(ctx);
                if (map == null) return;
                try {
                    @SuppressWarnings("unchecked")
                    List<?> raw = GSON.fromJson(t.substring("__roleOnDelete::".length()), List.class);
                    java.util.Set<String> dead = RolePickerPickParser.collectDeleteKeys(raw);
                    if (dead.isEmpty()) return;
                    synchronized (map) {
                        map.keySet().removeIf(dead::contains);
                        map.entrySet().removeIf(en -> {
                            RoleEntry re = en.getValue();
                            return re != null && re.getSigKey() != null && dead.contains(re.getSigKey());
                        });
                        // 【修复"i18n元素删除不干净"——值级匹配兜底】
                        // console桥的删除兜底必须与exposeBinding桥的删除逻辑完全一致（见 __roleOnDelete 的删除桥接实现），
                        // 否则i18n/text/css等定位器型策略的元素因key格式不一致导致删除miss。
                        // 复制自exposeBinding __roleOnDelete处理器的值级匹配逻辑。
                        map.entrySet().removeIf(en -> {
                            RoleEntry re = en.getValue();
                            if (re == null) return false;
                            String rpc = (re.getPageClass() != null) ? re.getPageClass() : "";
                            if (dead.contains(en.getKey())) return true;
                            String sigKey = re.getSigKey();
                            if (sigKey != null && dead.contains(sigKey)) return true;
                            String lk;
                            try { lk = RoleElementPageGenerator.locatorKey(re); } catch (Exception ignore) { lk = null; }
                            if (lk != null && !lk.isEmpty() && dead.contains(rpc + "|" + lk)) return true;
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
                        // 【修复"已删除元素无法重新拾取"】
                        // 与 exposeBinding 通道保持一致：不再写入 RolePickerSessionState.STATE_DELETED，允许用户重新拾取已删除的元素。
                        // 删除的语义是"从当前拾取列表移除"，而非"永久封杀该元素"。
                        // RolePickerSessionState.STATE_DELETED.computeIfAbsent(map, k -> ConcurrentHashMap.newKeySet()).addAll(dead);
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
    }

    /** 动态 iframe 监听器：context 级对每个（含弹窗/新开）页面挂 onFrameAttached，覆盖运行时新附加/动态创建的 iframe。 */
    private static void registerFrameAttachListener(BrowserContext ctx) {
        ctx.onPage(p -> RolePickerScriptInjector.registerFrameInjection(p, RolePickerSessionState.CTX_PICKER_NLS.get(ctx)));
    }

    /**
     * 把"面板重建 + 门控拾取"初始化脚本一次性注册到 {@link BrowserContext}：
     * context 下每个页面、每次导航、每个新文档都会自动执行（Playwright 原生保证），
     * 从根上替代"onFrameNavigated 手动重挂 / 逐页 addInitScript / 自愈循环"三套兜底机器。
     * 面板脚本每 context 仅注册一次；拾取脚本按 nls 内容幂等（同 nls 跳过，变化则追加，后注册者后执行覆盖生效）。
     */
    static void registerContextInitScripts(BrowserContext ctx, String nlsReverseJson) {
        if (RolePickerSessionState.CTX_PANEL_SCRIPTED.add(ctx)) {
            ctx.addInitScript(RolePickerScripts.PANEL_BOOTSTRAP_SCRIPT);
            ctx.addInitScript(RolePickerScripts.PANEL_SCRIPT);
        }
        String nls = (nlsReverseJson == null || nlsReverseJson.isEmpty()) ? "{}" : nlsReverseJson;
        if (!nls.equals(RolePickerSessionState.CTX_PICKER_NLS.get(ctx))) {
            ctx.addInitScript(RolePickerScriptInjector.gatedPickerInitScript(nls));
            RolePickerSessionState.CTX_PICKER_NLS.put(ctx, nls);
        }
    }

    /**
     * 抽干浏览器端兜底命令队列 window.__panelCmds 并入 Java 命令队列 cmdQueue。
     * 当 exposeFunction 绑定（window.__rolePickerCmd）尚未就绪时，面板按钮的 pushCmd 会把命令
     * 暂存进 window.__panelCmds（见 PANEL_SCRIPT）。若不消费，这些命令会静默丢失，导致"点了开始却
     * 拾取不了"。此处由 Java 主循环周期性抽干，保证命令零丢失。与 exposeFunction 投递幂等、不会重复。
     */
    static void drainPanelCmds(Page page, BlockingQueue<CmdEvent> cmdQueue) {
        if (page == null || page.isClosed() || cmdQueue == null) return;
        try {
            Object raw = page.evaluate(RolePickerScripts.DRAIN_PANEL_CMDS_JS);
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
    static boolean isCiRun() {
        return System.getenv("JENKINS_URL") != null
                || System.getenv("WORKSPACE") != null
                || System.getenv("BUILD_NUMBER") != null
                || "true".equalsIgnoreCase(System.getenv("CI"))
                || System.getenv("GITLAB_CI") != null
                || System.getenv("GITHUB_ACTIONS") != null
                || System.getenv("TEAMCITY_VERSION") != null;
    }

    /** 用于把参数安全序列化为 JS 字面量（避免手动拼接转义错误） */
    static final Gson GSON = new Gson();

    /** 反序列化任意拾取态 JSON 的精确泛型类型，避免 {@code fromJson(x, Map.class)} 的未检查转换 */
    static final java.lang.reflect.Type MAP_STRING_OBJECT_TYPE =
            new TypeToken<java.util.Map<String, Object>>() {}.getType();

    /** 拾取命令循环的统一返回：动作 + 按页生成的代码 + 状态文案 */
    enum PickerAction { CONTINUE, ABORT, DONE }
    static final class PickerResult {
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
    }

    /**
     * 统一处理面板命令（start/stop/abort/done）：开始拾取、收集并生成代码、终止、关闭。
     * 结果由调用方用 {@code setStatus/fillCode} 呈现到面板 UI。
     *
     * @return 含后续动作与（stop 时的）生成代码
     */
    static PickerResult runPickerCommand(RolePickerContext ctx, Page page, String cmd) {
        return RolePickerCommandEngine.runPickerCommand(ctx, page, cmd);
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
        // 拾取注入脚本本体已外置于 RolePickerScripts.START_INJECT_JS（参数化注入 nls + root，
        // 含脏序号重置、去重表重建、二次开始保活、录制根约束等关键修复的逐条注释见该常量定义）。
        // 此处仅引用常量，Java 侧零字符串拼接，杜绝拼接漂移与 SyntaxError 风险。
        String pickStartScript = RolePickerScripts.START_INJECT_JS;
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
            RolePickerScriptInjector.registerFrameInjection(page, nlsReverseJson);
        } catch (Exception ignore) {}
        // 诊断：start() 注入后确认监听真正挂载（排查"点击没反应"究竟是注入失败还是被后续覆盖）。
        try {
            String d = page.evaluate(RolePickerScripts.START_DIAG_JS).toString();
            log.info("[picker][start] 注入后诊断 @ {} : {}", page.url(), d);
            // 记录本次成功注入的 origin，供 onFrameNavigated 重激活区分同源（门控已注入，仅保活）/跨域（需强制重注入）。
            // 【修复"跳转到新页面拾取不到"】popup 在 onPopup 回调触发时文档还是 about:blank（origin 为空串），
            // 若在此处把 RolePickerSessionState.LAST_PICK_ORIGIN 更新为空串，会污染全局跨域判据：后续该 popup 导航到真实跨域页时，
            // onFrameNavigated 用 curOrigin("https://b.com") != "" 误判为 originChanged=true 而强制重注入——
            // 这本应是对的；但更隐蔽的是：若真实页与根页【同源】，空串会让 originChanged 错判、且把好不容易注入的
            // 库因 about:blank 文档随即销毁而丢失，最终表现为"新页面无蓝框、点击无反应"。
            // 故 about:blank/空 origin 绝不更新 RolePickerSessionState.LAST_PICK_ORIGIN，保持上一有效 origin 作为去抖基准。
            try {
                String __o = safeOrigin(page.url());
                if (!__o.isEmpty()) RolePickerSessionState.LAST_PICK_ORIGIN.put(page, __o);
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
        page.evaluate(RolePickerScripts.STOP_SESSION_CLEANUP_JS + RolePickerScripts.STOP_SCRIPT);
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
        allFrames.sort((a, b) -> Integer.compare(RolePickerFramePath.frameDepth(b), RolePickerFramePath.frameDepth(a)));
        for (Frame fr : allFrames) {
            List<String> fp = RolePickerFramePath.computeFramePath(page, fr);   // 自顶向下的 iframe 选择器链（主框架为空）
            Object raw;
            try {
                raw = fr.evaluate(RolePickerScripts.READ_FRAME_PICKS_JS);
            } catch (Exception ignore) { continue; }   // 跨源 frame 读取受限，跳过
            if (raw instanceof List) {
                for (Object o : (List<Object>) raw) {
                    if (o instanceof Map) {
                        Map<Object, Object> m = (Map<Object, Object>) o;
                        RoleEntry e = RolePickerPickParser.parsePick(m);
                        if (e == null) continue;
                        // Java 侧二次兜底：与 parsePickSnapshot 同口径。
                        String dk = RolePickerPickParser.pickDedupKey(m, e);
                        if (!dk.isEmpty() && !seenKeys.add(dk)) continue;
                        // iframe 嵌套路径：浏览器侧已带则沿用，否则以 Java 侧 frameElement 链补算。
                        if (e.getFramePath() == null && fp != null && !fp.isEmpty()) {
                            e.setFramePath(new ArrayList<>(RolePickerFramePath.computeFramePath(page, fr)));
                        }
                        result.add(e);
                    }
                }
            }
        }
        return result;
    }


    /**
     * 把 Java 权威拾取内存态（javaPickBySig）同步到浏览器面板展示数组 window.__rolePicks 并触发渲染。
     * 修复"Java 内存态增长、浏览器实时面板空白"：浏览器侧 window.__rolePicks 因去重 / iframe / 导航时序
     * 未被可靠填充，导致点击时面板列表看不到已拾元素。此处以 Java 侧为准，保证面板实时反映已拾内容。
     * 仅用于面板展示；代码生成仍走 javaPickBySig（见 runPickerCommand），不受影响。
     */
    static void mergeFramePicksToMain(Page page, LinkedHashMap<String, RoleEntry> javaPickBySig) {
        RolePickerPanelSync.mergeFramePicksToMain(page, javaPickBySig);
    }

    static void syncPanelToBrowser(Page page, LinkedHashSet<String> pageClasses, LinkedHashMap<String, RoleEntry> state) {
        RolePickerPanelSync.syncPanelToBrowser(page, pageClasses, state);
    }

    static void syncPanelToBrowser(Page page, LinkedHashSet<String> pageClasses, LinkedHashMap<String, RoleEntry> state, boolean overwriteNos) {
        RolePickerPanelSync.syncPanelToBrowser(page, pageClasses, state, overwriteNos);
    }

    /**
     * 自愈式保活：会话处于拾取中时，确保某页的点击捕获监听确实已挂载。
     * 任意页面变化（整文档替换、frame 内部跳转、onFrameNavigated 未覆盖到的边界情形）导致监听被静默丢弃后，
     * 主循环空闲期据此重挂 START_SCRIPT，保证"页面如何变化都能继续拾取"（用户核心需求）。
     * 重挂同时补注入 nls 反向查表，使导航后拾取仍能把 a11y name 反查为 key。
     * 幂等：监听已存活则零成本返回，不会重复挂载（START_SCRIPT 内部 __rolePickActive 早退 + 此处先探测）。
     */
    static void ensurePickingActive(Page page, String nlsReverseJson, String[] nlsFiles) {
        if (page == null || page.isClosed()) return;
        try {
            // 会话处于拾取中（调用方已用 active[0] 守卫）：无条件重挂（幂等）监听，
            // 确保"停止后再开始 / 跳转到新页面 / 框架静默移除 document 级监听"等任何情形下点击必能拾取。
            // 关键修复：移除了原先的"if (active && hasClick) return"早退。
            // 该早退在"window.__rolePickActive 仍为 true 但 click 监听已被框架/导航静默移除"的竞态下会跳过重挂，
            // 表现即"停止后再点开始却拾取不了 / 跳转到新页面拾取不到"——因为激活态显示 true、函数引用还在（hasClick 为真），
            // 于是误判"无需重挂"，而真实监听早已不工作。START_SCRIPT 对同函数引用 addEventListener 幂等、
            // 不重复定义库，按 1s 节奏重挂安全无副作用，故此处改为"会话开则必重挂"。
            page.evaluate(RolePickerScripts.SET_NLS_AND_SESSION_JS, RolePickerScripts.args("nls", nlsReverseJson));
            // 自愈保活不仅要重挂主框架监听，还须对所有 frame（含弹窗/新页面内的任意嵌套 iframe）重新注入拾取脚本。
            // 否则"打开新页面 / window.open 弹窗 / 链接点击新标签"等场景，其内嵌 iframe 在自愈时不会被重新注入，
            // 表现为弹窗内 iframe 元素拾取不到。registerFrameInjection 对 page.frames() 递归返回的全部层做全量兜底，
            // 与 onFrameNavigated 兜底同源，覆盖 frame 内嵌 frame 的复合情况。
            RolePickerScriptInjector.registerFrameInjection(page, nlsReverseJson);
        } catch (Exception ignore) {}
    }

    /** 停止命令一次性读取的拾取态快照（合并多趟 evaluate 以降低延迟）。 */
    static final class PickSnapshot {
        final String pageClass;
        final List<RoleEntry> entries;
        final List<StepRec> steps;
        final List<PageOp> ops;
        PickSnapshot(String pageClass, List<RoleEntry> entries, List<StepRec> steps, List<PageOp> ops) {
            this.pageClass = pageClass; this.entries = entries; this.steps = steps; this.ops = ops;
        }
    }



    /** 单次 {@code page.evaluate} 即取回「已拾元素 + step 序列 + 页面级操作 + 当前页类名」，
     *  把停止命令原本分散的多次浏览器往返合并为一次，降低点击"停止"的响应延迟。 */
    @SuppressWarnings("unchecked")


    /** 一次 step 记录：含所属页面类与本次拾取的元素列表（多页面归类用）。 */
    static final class StepRec {
        final String pageClass;
        final List<RoleEntry> picks;
        StepRec(String pageClass, List<RoleEntry> picks) {
            this.pageClass = pageClass;
            this.picks = picks;
        }
    }

    /** 一次「页面级操作」记录：如关闭页面（op='close'）。区别于元素拾取 step，不产生元素字段。 */
    static final class PageOp {
        final String pageClass;
        final String op;
        PageOp(String pageClass, String op) {
            this.pageClass = pageClass;
            this.op = op;
        }
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
     * 定位器唯一型策略：i18n/id/css/text/title/placeholder/label/testid/altText 等，
     * 仅靠 RoleEntry 的 role/name 不足以区分多个匹配（如同一页多个同名 i18n 文本、多个 text=... 按钮），
     * 须用「去索引的稳定 locatorKey」作为内存态去重键（语义与 RoleElementPageGenerator.locatorKey 对齐，均不含 #index）。
     */
    static final java.util.Set<String> LOCATOR_IDENTITY_STRATEGIES = java.util.Collections.unmodifiableSet(
            new java.util.HashSet<>(java.util.Arrays.asList(
            "i18n", "id", "css", "text", "title", "placeholder", "label", "testid", "altText")));

    /**
     * 判断 javaPickBySig 权威内存态（mem）是否需要替换浏览器读取的步骤 pick（p）。
     * 覆盖四类增强字段，任一存在差异即替换（幂等，只往"更完整/点击后"方向收敛）：
     *   · checked/setCheckedTarget：final 不可变，无法 merge——若 mem 已反映"点击后状态"而 p 是"点击前状态"，
     *     必须整体替换，否则生成 setCheckedTarget 为点击前值，回放时 checkbox 勾选结果与用户点击后的页面状态相反
     *     （表现为"点击未勾选 checkbox 却生成 setChecked(false)，没选择上"）。
     *   · framePath：mem 含 backfill（iframe 内 console 通道），p 可能为空 → 需替换以生成 switchToFrame。
     *   · dialog / popup：mem 含 Java onDialog/onPopup 双保险标记，p 可能缺失。
     */


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
        String reverse = RolePickerNlsCache.buildNlsReverseJson(Arrays.asList(nlsFiles));
        start(page, reverse);
        try {
            page.waitForFunction(RolePickerScripts.WAIT_PICK_DONE_JS, null,
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
        page.evaluate(RolePickerScripts.SET_PICKER_CODE_JS, RolePickerScripts.args("code", code));
        page.evaluate(RolePickerScripts.SHOW_PANEL_SCRIPT);
        log.info("[picker] 代码面板已弹出：点『复制代码』复制，点『关闭』结束。");
        try {
            page.waitForFunction(RolePickerScripts.WAIT_CODE_PANEL_CLOSED_JS, null,
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
        final String pageClassName = RolePickerClassNameResolver.pageClassNameFromUrl(page.url(), RolePickerClassNameResolver.values());
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
        RolePickerPanelController.openPanel(page, packageName, pageClassName, stepClassName, nlsFiles);
    }

    /**
     * 注册"弹窗跟随"：当页面弹出新标签页（target=_blank）时，把原页面的拾取状态
     * （__rolePicks / __steps / __currentStep / __rolePickSigs）转移到新页面，
     * 并让 {@code current[0]} 指向新页面继续拾取。面板是注入式 docked（同窗口），
     * 故需在弹窗页也重建面板；新页面自身若再弹窗会递归注册，支持多级弹窗。
     */
    static void registerPopupFollow(RolePickerContext ctx, Page page, Page parent) {
        // 面板会话编排逻辑已下沉至 RolePickerPanelController（T5-1 拆分），此处仅保留转发 facade，
        // 供 RolePickerPageTracker 等既有调用方零改动复用。
        RolePickerPanelController.registerPopupFollow(ctx, page, parent);
    }

    /** 由页面 URL 派生 Page 类名：取 path 最后一个 '/' 之后、'?'（及 '#'）之前的片段，
     *  清洗为首字母大写的合法 Java 标识符后加 "Page"。
     *  特殊情况：片段为空（根路径 / 仅域名 / 结尾斜杠）→ 退回 "Index"；
     *  与 used 中已有类名重复时追加 2/3… 去重。 */

    /** 安全提取 URL 的 origin（protocol//host[:port]），用于跨域判断。无法解析时返回空串。 */
    static String safeOrigin(String url) {
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
    
    /** 取文件路径/URL 的最后一段（去掉所有路径分隔符前缀），用于 iframe src 与 frame.url() 的模糊匹配。 */
    static String lastPathSegment(String s) {
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

    /** 移除常驻面板，并还原 docked 预留的右侧页面空间。
     *  页面可能已关闭（如关闭的是根页面导致会话结束、或会话收尾时页面已被回收），
     *  故对 evaluate 整体容错，避免 TargetClosedError 冒泡污染测试 step（之前"关闭新页面后 STEP ERROR"的根因）。 */
    static void closePanel(Page page) {
        try {
            // 移除常驻面板的同时，移除点击/悬停/按键捕获监听并复位 active 标记，
            // 否则面板删了、监听器残留，会出现"面板消失却仍可静默拾取、不阻挡程序"（用户不期望）的半吊子状态。
            page.evaluate(RolePickerScripts.CLOSE_PANEL_JS);
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
    static String readPickStateJson(Page page) {
        try {
            Object res = page.evaluate(RolePickerScripts.READ_PICK_STATE_JSON_JS);
            if (res instanceof String) return (String) res;
        } catch (Exception ignore) { /* 页面已关闭等：忽略，返回空集 */ }
        return "{\"picks\":[],\"steps\":[],\"currentStep\":[],\"sigs\":{},\"active\":false}";
    }

    /** 状态 JSON 是否包含至少一个已拾取元素（用于决定是否回写父页，避免误清空父页已有拾取） */
    static boolean hasPicks(String stateJson) {
        try {
            Map<?, ?> m = GSON.fromJson(stateJson, MAP_STRING_OBJECT_TYPE);
            Object p = m == null ? null : m.get("picks");
            return p instanceof List && !((List<?>) p).isEmpty();
        } catch (Exception ignore) { return false; }
    }

    /** 状态 JSON 是否为"全空"（picks / steps / currentStep 均为空），用于快照更新时识别导航空窗期。 */
    static boolean isEmptyState(String stateJson) {
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
    static void applyPickState(Page target, String stateJson, String nlsReverseJson, String[] nlsFiles) {
        // 诊断：applyPickState 用快照恢复数据时会把 __rolePickActive 置 false，
        // 必须依赖 onFrameNavigated 末尾的 start() 重激活才能恢复拾取。若此步后无重激活，
        // 刷新后点击将彻底失效（监听存在但 active=false，__recordPick 直接 return）。
        log.info("[picker][applyPickState] 用 Java 快照恢复数据（picks={} / steps={} / currentStep={}），"
                        + "即将把 __rolePickActive 置 false，等待 onFrameNavigated 重激活；target={}",
                pickCountOf(stateJson), stepCountOf(stateJson), currentStepCountOf(stateJson), target.url());
        target.evaluate(RolePickerScripts.APPLY_PICK_STATE_JS, RolePickerScripts.args(
                "nlsFiles", nlsFiles, "nlsReverseJson", nlsReverseJson, "stateJson", stateJson));
    }

    // 诊断辅助：从快照 JSON 里安全解析各类计数，避免 applyPickState 日志打印整段 state（可能很大）。
    @SuppressWarnings("unchecked")
    static int pickCountOf(String stateJson) {
        try {
            java.util.Map<String, Object> m = GSON.fromJson(stateJson, MAP_STRING_OBJECT_TYPE);
            Object p = (m == null) ? null : m.get("picks");
            return (p instanceof java.util.List) ? ((java.util.List<?>) p).size() : 0;
        } catch (Exception e) { return -1; }
    }
    @SuppressWarnings("unchecked")
    static int stepCountOf(String stateJson) {
        try {
            java.util.Map<String, Object> m = GSON.fromJson(stateJson, MAP_STRING_OBJECT_TYPE);
            Object p = (m == null) ? null : m.get("steps");
            return (p instanceof java.util.List) ? ((java.util.List<?>) p).size() : 0;
        } catch (Exception e) { return -1; }
    }
    @SuppressWarnings("unchecked")
    static int currentStepCountOf(String stateJson) {
        try {
            java.util.Map<String, Object> m = GSON.fromJson(stateJson, MAP_STRING_OBJECT_TYPE);
            Object p = (m == null) ? null : m.get("currentStep");
            return (p instanceof java.util.List) ? ((java.util.List<?>) p).size() : 0;
        } catch (Exception e) { return -1; }
    }

    /** 更新面板顶部状态文字 */
    static void setStatus(Page page, String msg) {
        page.evaluate(RolePickerScripts.SET_STATUS_MSG_JS, RolePickerScripts.args("msg", msg));
        page.evaluate(RolePickerScripts.UPDATE_STATUS_DOM_JS);
    }

    /** 把按页生成的页面类/步骤代码分别写入面板的多 Tab，并更新状态 */
    static void fillCode(Page page, LinkedHashMap<String, String> pageClassByPage, LinkedHashMap<String, String> stepByPage, String msg) {
        // 企业级优化：把"写入消息对象"与"更新 DOM"合并进同一次 page.evaluate，
        // 点击"停止"后只需 1 次往返即可把分页代码渲染进面板对应 Tab（原来 2 次串行往返）。
        page.evaluate(RolePickerScripts.FILL_CODE_JS, RolePickerScripts.args(
                "pageByPage", pageClassByPage == null ? new LinkedHashMap<String, String>() : pageClassByPage,
                "stepByPage", stepByPage == null ? new LinkedHashMap<String, String>() : stepByPage,
                "msg", msg == null ? "" : msg));
    }

    static String asString(Object o) {
        return o == null ? null : o.toString();
    }

}