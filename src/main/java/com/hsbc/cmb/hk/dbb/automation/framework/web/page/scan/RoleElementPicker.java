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
    // 上下文级初始化脚本守卫：面板脚本每 context 仅注册一次；拾取脚本按 nls 内容变化才追加注册
    // （addInitScript 无法撤销，重复注册会累积执行；同 nls 幂等跳过，不同 nls 追加后"后注册者后执行"覆盖生效）。
    private static final Set<BrowserContext> CTX_PANEL_SCRIPTED = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private static final Map<BrowserContext, String> CTX_PICKER_NLS = new ConcurrentHashMap<>();

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
        if (prev != null && prev != javaPickBySig) {
            for (java.util.Map.Entry<String, RoleEntry> e : prev.entrySet()) {
                if (!javaPickBySig.containsKey(e.getKey())) javaPickBySig.put(e.getKey(), e.getValue());
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
                    c = v == null ? null : v.toString();
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
                synchronized (map) { RoleEntry merged = mergePickIntoMap(map, key, e); List<String> fpLog = merged.getFramePath(); log.info("[picker] __roleOnPick 回传写入内存态：key={} pageClass={} framePath={}（当前内存态大小={}）", key, (merged.getPageClass() == null ? "" : merged.getPageClass()), (fpLog == null || fpLog.isEmpty() ? "" : fpLog.toString()), map.size()); }
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
                        RoleEntry merged = mergePickIntoMap(map, key, e);
                        List<String> fplog = merged.getFramePath();
                        log.info("[picker] __roleOnPick(console) 回传写入内存态：key={} pageClass={} framePath={}（当前内存态大小={}）", key, (merged.getPageClass() == null ? "" : merged.getPageClass()), (fplog == null || fplog.isEmpty() ? "" : fplog.toString()), map.size());
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
            // 动态/子 frame 注入失败（多为跨源 frame 受安全限制 evaluate 抛错），按设计跳过，
            // 其内元素不经主框架拾取，不影响其它 frame。
            // 用 log.debug 而非 System.err，避免连接关闭时的海量失败刷屏。
            String url;
            try { url = frame.url(); } catch (Exception urlEx) { url = "<closed>"; }
            if (log.isDebugEnabled()) {
                log.debug("[frameInjectOnce][skip] frame={} : {}", url, ex.toString());
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
                + "     var raw = localStorage.getItem('__rolePickState'); if (raw) {"
                + "       var s = JSON.parse(raw);"
                + "       window.__rolePicks = window.__rolePicks || [];"
                + "       window.__rolePickSigs = window.__rolePickSigs || {};"
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
    private static final String START_SCRIPT_A = """
            (function() {
              // 保活（幂等）：任何 start 都确保 document 级点击/键盘/悬浮监听已挂载。
              // 导航（尤其某些 SPA/微前端框架在路由切换时重建 document 子树）可能移除这些监听，
              // 而 window.__rolePickActive 仍为 true；若直接早退会导致"导航到新页面后点了没反应、拾取不到"。
              // 故先重挂（addEventListener 对同一函数引用幂等），再判断是否已在拾取中。
              if (window.__rolePickClick) document.addEventListener('click', window.__rolePickClick, true);
              if (window.__rolePickDblClick) document.addEventListener('dblclick', window.__rolePickDblClick, true);
              if (window.__rolePickMove) document.addEventListener('mousemove', window.__rolePickMove, true);
              if (window.__rolePickKey) document.addEventListener('keydown', window.__rolePickKey, true);
              if (window.__rolePickFocus) document.addEventListener('focusin', window.__rolePickFocus, true);
              if (window.__rolePickScroll) document.addEventListener('scroll', window.__rolePickScroll, true);
              if (window.__rolePickActive) return;   // 已在拾取中：仅保活监听，不重置状态/不重复定义库
              window.__rolePickActive = true;
              window.__scanMode = 'pick';   // 手动拾取模式（点击元素即定位）
              window.__rolePicks = window.__rolePicks || [];
              window.__steps = window.__steps || [];
              // 保留"进行中的 step"：跨页面切换（弹窗打开/关闭）会先 applyPickState 把父页/弹窗的
              // __currentStep 搬过来，再执行本 START 重启监听。若这里硬置为 []，会把刚搬来的当前 step
              // 整个抹掉——这正是"元素定位在但步骤没了"的根因。用 || [] 续接：
              //  - 正常首次 ▶：__currentStep 为 undefined → 得 []（新 step）；
              //  - stop 后再 ▶：stop 已把 __currentStep 置 null → 得 []（新 step）；
              //  - 跨页切换重启：__currentStep 已被搬运为非空数组 → 原样续接，不丢步骤。
              window.__currentStep = window.__currentStep || [];
              window.__pickDone = false;

              // 拾取提示与计数不再用左下角独立控件，而是写入主面板标题状态条（#__roleStatus），
              // 由 openPanel(...) 的常驻面板呈现（见 PANEL_SCRIPT 与 __rolePickClick）。

              // ============================================================================
              // Playwright 注入脚本 roleUtils.ts 算法的忠实移植（getAriaRole + getElementAccessibleName）
              // 来源：microsoft/playwright packages/playwright-core/src/server/injected/roleUtils.ts
              // page.pause()/Inspector 的"拾取元素"正是用这套 W3C ARIA + accname 算法计算 role 与 name，
              // 因此直接移植，保证 picker 结果与 Inspector 完全一致（不再依赖浏览器 computedRole/computedName）。
              // ============================================================================
              // 企业级优化：roleUtils 移植 + 各拾取/去重/落盘辅助函数定义是"静态库"，
              // 同一 window 内只需解析/编译一次（首次 start）。后续 start（stop→再 start、
              // 或多页跟随）用一次性守卫跳过这近千行的重解析/重编译，点击"开始"的端到端延迟显著下降；
              // 所有对外入口（window.__recordPick / __computePick / __pickSig / __sigKey /
              // __persistPickState 等）都挂在 window 上会持续存活，跳过定义后仍可被点击 handler 正常调用。
              // 自动推断录制根容器（辅助函数，当前不再被默认使用）：
              // 历史曾用于"开始拾取默认避开导航"，但用户需要时可整页拾取，故默认不再调用。
              // 现保留为可选能力——区域扫描与显式根选择走各自逻辑；如需"默认避开导航"可单独调用。
              // 定义在此处（库守卫之外）并挂到 window，避免被守卫跳过导致潜在"未定义"。
              // 策略：① 优先 <main>；② 否则返回"面积最大、且自身不是 landmark"的内容容器；③ 都找不到返回 null。
              function autoDetectRoot() {
                try {
                  var mainEl = document.querySelector('main');
                  if (mainEl) return 'main';
                  var LANDMARK = 'nav, header, aside, footer, [role=navigation], [role=banner], [role=complementary], [role=contentinfo]';
                  var best = null, bestArea = 0;
                  var all = document.querySelectorAll('body > *, body');
                  for (var i = 0; i < all.length; i++) {
                    var el = all[i];
                    if (!el || el.nodeType !== 1) continue;
                    if (el.matches && el.matches(LANDMARK)) continue;            // 跳过导航类 landmark
                    var r = el.getBoundingClientRect();
                    var area = (r.width || 0) * (r.height || 0);
                    // 直接子级里若含 landmark（如 leftmenu 与内容并列），取该非 landmark子级为根
                    if (area > bestArea) { bestArea = area; best = el; }
                  }
                  if (best && best !== document.body) {
                    // 用稳定选择器表达：优先 id，其次 tag
                    if (best.id) return '#' + best.id;
                    return best.tagName ? best.tagName.toLowerCase() : null;
                  }
                } catch (e) { /* 推断失败忽略 */ }
                return null;
              }
              window.autoDetectRoot = autoDetectRoot;
              if (!window.__rolePickerLib) {
              var kGlobalAriaAttributes = ['aria-atomic','aria-busy','aria-controls','aria-current','aria-describedby','aria-details','aria-disabled','aria-dropeffect','aria-errormessage','aria-flowto','aria-grabbed','aria-haspopup','aria-hidden','aria-invalid','aria-keyshortcuts','aria-label','aria-labelledby','aria-live','aria-owns','aria-relevant','aria-roledescription'];
              function hasGlobalAriaAttribute(e) {
                for (var i = 0; i < kGlobalAriaAttributes.length; i++) if (e.hasAttribute(kGlobalAriaAttributes[i])) return true;
                return false;
              }
              var kAncestorPreventingLandmark = 'article:not([role]), aside:not([role]), main:not([role]), nav:not([role]), section:not([role]), [role=article], [role=complementary], [role=main], [role=navigation], [role=region]';
              function closestSafe(el, sel) {
                try { return el.closest ? el.closest(sel) : null; } catch (e) { return null; }
              }
              var kImplicitRoleByTagName = {
                'A': function(e){ return e.hasAttribute('href') ? 'link' : null; },
                'AREA': function(e){ return e.hasAttribute('href') ? 'link' : null; },
                'ARTICLE': function(){ return 'article'; },
                'ASIDE': function(){ return 'complementary'; },
                'BLOCKQUOTE': function(){ return 'blockquote'; },
                'BUTTON': function(){ return 'button'; },
                'CAPTION': function(){ return 'caption'; },
                'CODE': function(){ return 'code'; },
                'DATALIST': function(){ return 'listbox'; },
                'DD': function(){ return 'definition'; },
                'DEL': function(){ return 'deletion'; },
                'DETAILS': function(){ return 'group'; },
                'DFN': function(){ return 'term'; },
                'DIALOG': function(){ return 'dialog'; },
                'DT': function(){ return 'term'; },
                'EM': function(){ return 'emphasis'; },
                'FIELDSET': function(){ return 'group'; },
                'FIGURE': function(){ return 'figure'; },
                'FOOTER': function(e){ return closestSafe(e, kAncestorPreventingLandmark) ? null : 'contentinfo'; },
                'FORM': function(e){ return (e.hasAttribute('aria-label') || e.hasAttribute('aria-labelledby')) ? 'form' : null; },
                'H1': function(){ return 'heading'; }, 'H2': function(){ return 'heading'; },
                'H3': function(){ return 'heading'; }, 'H4': function(){ return 'heading'; },
                'H5': function(){ return 'heading'; }, 'H6': function(){ return 'heading'; },
                'HEADER': function(e){ return closestSafe(e, kAncestorPreventingLandmark) ? null : 'banner'; },
                'HR': function(){ return 'separator'; },
                'HTML': function(){ return 'document'; },
                'IMG': function(e){ return (e.getAttribute('alt') === '') && !hasGlobalAriaAttribute(e) && isNaN(Number(String(e.getAttribute('tabindex')))) ? 'presentation' : 'img'; },
                'INPUT': function(e){
                  var type = (e.getAttribute('type') || 'text').toLowerCase();
                  if (type === 'search') return e.hasAttribute('list') ? 'combobox' : 'searchbox';
                  if (['email','tel','text','url',''].indexOf(type) !== -1) {
                    var list = getIdRefs(e, e.getAttribute('list'))[0];
                    return (list && list.tagName === 'DATALIST') ? 'combobox' : 'textbox';
                  }
                  if (type === 'hidden') return '';
                  var map = { 'button':'button','checkbox':'checkbox','image':'button','number':'spinbutton','radio':'radio','range':'slider','reset':'button','submit':'button' };
                  return map[type] || 'textbox';
                },
                'INS': function(){ return 'insertion'; },
                'LI': function(){ return 'listitem'; },
                'MAIN': function(){ return 'main'; },
                'MARK': function(){ return 'mark'; },
                'MATH': function(){ return 'math'; },
                'MENU': function(){ return 'list'; },
                'METER': function(){ return 'meter'; },
                'NAV': function(){ return 'navigation'; },
                'OL': function(){ return 'list'; },
                'OPTGROUP': function(){ return 'group'; },
                'OPTION': function(){ return 'option'; },
                'OUTPUT': function(){ return 'status'; },
                'P': function(){ return 'paragraph'; },
                'PROGRESS': function(){ return 'progressbar'; },
                'SECTION': function(e){ return (e.hasAttribute('aria-label') || e.hasAttribute('aria-labelledby')) ? 'region' : null; },
                'SELECT': function(e){ return (e.hasAttribute('multiple') || e.size > 1) ? 'listbox' : 'combobox'; },
                'STRONG': function(){ return 'strong'; },
                'SUB': function(){ return 'subscript'; },
                'SUP': function(){ return 'superscript'; },
                'SVG': function(){ return 'img'; },
                'TABLE': function(){ return 'table'; },
                'TBODY': function(){ return 'rowgroup'; },
                'TD': function(e){ var table = closestSafe(e,'table'); var role = table ? getExplicitAriaRole(table) : ''; return (role==='grid'||role==='treegrid') ? 'gridcell' : 'cell'; },
                'TEXTAREA': function(){ return 'textbox'; },
                'TFOOT': function(){ return 'rowgroup'; },
                'TH': function(e){
                  if (e.getAttribute('scope') === 'col') return 'columnheader';
                  if (e.getAttribute('scope') === 'row') return 'rowheader';
                  var table = closestSafe(e,'table'); var role = table ? getExplicitAriaRole(table) : '';
                  return (role==='grid'||role==='treegrid') ? 'gridcell' : 'cell';
                },
                'THEAD': function(){ return 'rowgroup'; },
                'TIME': function(){ return 'time'; },
                'TR': function(){ return 'row'; },
                'UL': function(){ return 'list'; }
              };
              var kPresentationInheritanceParents = {
                'DD': ['DL','DIV'], 'DIV': ['DL'], 'DT': ['DL','DIV'], 'LI': ['OL','UL'],
                'TBODY': ['TABLE'], 'TD': ['TR'], 'TFOOT': ['TABLE'], 'TH': ['TR'],
                'THEAD': ['TABLE'], 'TR': ['THEAD','TBODY','TFOOT','TABLE']
              };
              function getImplicitAriaRole(element) {
                var fn = kImplicitRoleByTagName[element.tagName.toUpperCase()];
                var implicitRole = (fn ? fn(element) : null) || '';
                if (!implicitRole) return null;
                var ancestor = element;
                while (ancestor) {
                  var parent = ancestor.parentElement;
                  var parents = kPresentationInheritanceParents[ancestor.tagName];
                  if (!parents || !parent || parents.indexOf(parent.tagName) === -1) break;
                  var per = getExplicitAriaRole(parent);
                  if ((per === 'none' || per === 'presentation') && !hasGlobalAriaAttribute(parent)) return per;
                  ancestor = parent;
                }
                return implicitRole;
              }
              var allRoles = ['alert','alertdialog','application','article','banner','blockquote','button','caption','cell','checkbox','code','columnheader','combobox','command','complementary','composite','contentinfo','definition','deletion','dialog','directory','document','emphasis','feed','figure','form','generic','grid','gridcell','group','heading','img','input','insertion','landmark','link','list','listbox','listitem','log','main','marquee','math','meter','menu','menubar','menuitem','menuitemcheckbox','menuitemradio','navigation','none','note','option','paragraph','presentation','progressbar','radio','radiogroup','range','region','roletype','row','rowgroup','rowheader','scrollbar','search','searchbox','section','sectionhead','select','separator','slider','spinbutton','status','strong','structure','subscript','superscript','switch','tab','table','tablist','tabpanel','term','textbox','time','timer','toolbar','tooltip','tree','treegrid','treeitem','widget','window'];
              var abstractRoles = ['command','composite','input','landmark','range','roletype','section','sectionhead','select','structure','widget','window'];
              var validRoles = allRoles.filter(function(r){ return abstractRoles.indexOf(r) === -1; });
              function getExplicitAriaRole(element) {
                var roles = (element.getAttribute('role') || '').split(' ').map(function(r){ return r.trim(); });
                for (var i = 0; i < roles.length; i++) if (validRoles.indexOf(roles[i]) !== -1) return roles[i];
                return null;
              }
              function getAriaRole(element) {
                var explicitRole = getExplicitAriaRole(element);
                if (explicitRole) {
                  if ((explicitRole === 'none' || explicitRole === 'presentation') && !hasGlobalAriaAttribute(element)) return getImplicitAriaRole(element);
                  return explicitRole;
                }
                // 对齐 Playwright 1.58 getAriaRole：无显式 role 时，可编辑元素视为 textbox，
                // 否则富文本/可编辑 div 会退化成 generic 被 NON_ROLE 跳过，无法被 role+name 捕获
                // （page.pause() 会识别为 textbox）。
                try { if (element.isContentEditable) return 'textbox'; } catch (e) {}
                try { if (element.tagName === 'LI' && (element.value)) return 'listitem'; } catch (e) {}
                return getImplicitAriaRole(element);
              }
              function getRole(el) { return getAriaRole(el) || 'generic'; }

              // 把 <label> 解析为其关联的表单控件：点击复选框/单选/文本框的"标签文字"时，
              // 对齐 page.pause()/Inspector——直接拾取它所指代的控件（role + name=标签文本），
              // 而非无语义的 label 文本节点。
              //   - HTMLLabelElement.control 覆盖"包裹式"关联（含 display:none 的隐藏控件）；
              //   - 否则回退到 for 属性指向的控件；都没有则不解析，保留 label 自身走 text 兜底。
              function resolveLabel(el) {
                if (!el || el.tagName !== 'LABEL') return el;
                try { if (el.control) return el.control; } catch (e) {}
                var forAttr = el.getAttribute && el.getAttribute('for');
                if (forAttr) {
                  try { var byId = el.ownerDocument.getElementById(forAttr); if (byId) return byId; } catch (e) {}
                }
                return el;
              }
              // 【关键修复"Uncaught ReferenceError: resolveElement is not defined"】
              // __rolePickDragDown / __rolePickDragUp 使用 resolveElement 解析拖拽源/目标元素，但该函数
              // 一直缺失（拖拽触发 mousedown 时在浏览器 console 抛 ReferenceError）。这里补上：
              // 用 composedPath 穿透 open shadow DOM，并把 SVG/文本等无语义 target 上溯到最近的可拾取祖先
              // （SVG path/circle 在拾取里 role=graphic/generic 且无稳定 sig，拖拽目标应解析到其宿主控件）。
              function resolveElement(t) {
                if (!t) return t;
                try {
                  if (typeof t.composedPath === 'function' && t.composedPath().length) {
                    t = t.composedPath()[0];
                  }
                } catch (e) {}
                var n = 0;
                while (t && t.nodeType === 1 && n < 12) {
                  var tag = (t.tagName || '').toUpperCase();
                  if (t.closest && (t.closest('#__rolePanel, #__roleCodeOverlay'))) return null;
                  // SVG 内部节点（path/circle/rect/g/line/polyline 等）或无 role 的图形节点 → 上溯宿主
                  var isSvgNode = tag === 'SVG' || tag === 'PATH' || tag === 'CIRCLE' || tag === 'RECT'
                    || tag === 'G' || tag === 'LINE' || tag === 'POLYLINE' || tag === 'POLYGON'
                    || tag === 'TEXT' || tag === 'ELLIPSE' || tag === 'USE' || tag === 'DEFS';
                  var hasOwnRole = t.getAttribute && (t.getAttribute('role') || t.getAttribute('aria-label') || t.getAttribute('aria-labelledby'));
                  if (!isSvgNode || hasOwnRole) break;
                  t = t.parentElement;
                  n++;
                }
                return t;
              }

              // ===== 可访问名称（W3C accname 算法，移植自 roleUtils.getElementAccessibleName）=====
              function normalizeAccessibleName(s) {
                return (s || '')
                  .replace(/\\r\\n/g, '\\n')
                  .replace(/\\u00A0/g, ' ')
                  .replace(/\\s\\s+/g, ' ')
                  .trim();
              }
              function getComputedStyleSafe(el) {
                try { var w = el.ownerDocument && el.ownerDocument.defaultView; return w ? w.getComputedStyle(el) : null; } catch (e) { return null; }
              }
              function getPseudo(el, pseudo) {
                try { var w = el.ownerDocument && el.ownerDocument.defaultView; return w ? w.getComputedStyle(el, pseudo) : null; } catch (e) { return null; }
              }
              function isHiddenForAria(element) {
                if (!element || !element.tagName) return false;
                var t = element.tagName;
                if (t === 'STYLE' || t === 'SCRIPT' || t === 'NOSCRIPT' || t === 'TEMPLATE') return true;
                var ah = element.getAttribute && element.getAttribute('aria-hidden');
                if (ah === 'true') return true;
                var cs = getComputedStyleSafe(element);
                if (cs && (cs.display === 'none' || cs.visibility === 'hidden')) return true;
                var parent = element.parentElement;
                if (parent && parent !== element) return isHiddenForAria(parent);
                return false;
              }
              function getIdRefs(element, ref) {
                if (!ref) return [];
                var root = element.ownerDocument;
                if (!root) return [];
                var ids = ref.split(' ').filter(function(id){ return !!id; });
                var set = [];
                for (var i = 0; i < ids.length; i++) {
                  try {
                    var first = root.querySelector('#' + (window.CSS && CSS.escape ? CSS.escape(ids[i]) : ids[i]));
                    if (first && set.indexOf(first) === -1) set.push(first);
                  } catch (e) {}
                }
                return set;
              }
              var kProhibitName = ['caption','code','definition','deletion','emphasis','generic','insertion','mark','paragraph','presentation','strong','subscript','suggestion','superscript','term','time'];
              var kAlwaysNameFromContent = ['button','cell','checkbox','columnheader','gridcell','heading','link','menuitem','menuitemcheckbox','menuitemradio','option','radio','row','rowheader','switch','tab','tooltip','treeitem'];
              var kDescendantNameFromContent = ['','caption','code','contentinfo','definition','deletion','emphasis','insertion','list','listitem','mark','none','paragraph','presentation','region','row','rowgroup','section','strong','subscript','superscript','table','term','time'];
              function allowsNameFromContent(role, targetDescendant) {
                if (kAlwaysNameFromContent.indexOf(role) !== -1) return true;
                if (targetDescendant && kDescendantNameFromContent.indexOf(role) !== -1) return true;
                return false;
              }
              function getAriaLabelledByElements(element) {
                var ref = element.getAttribute('aria-labelledby');
                if (ref === null) return null;
                return getIdRefs(element, ref);
              }
              function findOwned(element, selector) {
                var res = Array.prototype.slice.call(element.querySelectorAll(selector));
                getIdRefs(element, element.getAttribute('aria-owns')).forEach(function(o){
                  if (o.matches && o.matches(selector)) res.push(o);
                  res.push.apply(res, Array.prototype.slice.call(o.querySelectorAll(selector)));
                });
                return res;
              }
              function getPseudoContent(pseudoStyle) {
                if (!pseudoStyle) return '';
                var content = pseudoStyle.content;
                var first = content.charAt(0);
                var last = content.charAt(content.length - 1);
                if ((first === "'" && last === "'") || (first === '"' && last === '"')) {
                  var unquoted = content.slice(1, -1);
                  var disp = pseudoStyle.display || 'inline';
                  if (disp !== 'inline') return ' ' + unquoted + ' ';
                  return unquoted;
                }
                return '';
              }
              function getAccessibleNameFromAssociatedLabels(labels, options) {
                var res = [];
                for (var i = 0; i < labels.length; i++) {
                  var n = getElementAccessibleNameInternal(labels[i], {
                    includeHidden: options.includeHidden, visitedElements: options.visitedElements,
                    embeddedInLabelledBy: 'none', embeddedInLabel: 'self',
                    embeddedInTextAlternativeElement: false, embeddedInTargetElement: 'none'
                  });
                  if (n) res.push(n);
                }
                return res.join(' ');
              }
              function getElementAccessibleNameInternal(element, options) {
                if (options.visitedElements.has(element)) return '';
                var childOptions = {
                  includeHidden: options.includeHidden,
                  visitedElements: options.visitedElements,
                  embeddedInLabelledBy: options.embeddedInLabelledBy === 'self' ? 'descendant' : options.embeddedInLabelledBy,
                  embeddedInLabel: options.embeddedInLabel === 'self' ? 'descendant' : options.embeddedInLabel,
                  embeddedInTextAlternativeElement: options.embeddedInTextAlternativeElement,
                  embeddedInTargetElement: options.embeddedInTargetElement === 'self' ? 'descendant' : options.embeddedInTargetElement
                };
                if (!options.includeHidden && options.embeddedInLabelledBy !== 'self' && isHiddenForAria(element)) {
                  options.visitedElements.add(element);
                  return '';
                }
                var labelledBy = getAriaLabelledByElements(element);
                if (options.embeddedInLabelledBy === 'none') {
                  var lbName = (labelledBy || []).map(function(ref){
                    return getElementAccessibleNameInternal(ref, {
                      includeHidden: options.includeHidden, visitedElements: options.visitedElements,
                      embeddedInLabelledBy: 'self', embeddedInLabel: 'none',
                      embeddedInTextAlternativeElement: false, embeddedInTargetElement: 'none'
                    });
                  }).join(' ');
                  if (lbName) return lbName;
                }
                var role = getAriaRole(element) || '';
                if (options.embeddedInLabel !== 'none' || options.embeddedInLabelledBy !== 'none') {
                  var labels = (element.labels || []);
                  var isOwnLabel = false;
                  for (var li = 0; li < labels.length; li++) { if (labels[li] === element) { isOwnLabel = true; break; } }
                  var isOwnLabelledBy = labelledBy ? labelledBy.indexOf(element) !== -1 : false;
                  if (!isOwnLabel && !isOwnLabelledBy) {
                    if (role === 'textbox') {
                      options.visitedElements.add(element);
                      if (element.tagName === 'INPUT' || element.tagName === 'TEXTAREA') return element.value || '';
                      return element.textContent || '';
                    }
                    if (role === 'combobox' || role === 'listbox') {
                      options.visitedElements.add(element);
                      var selected = [];
                      if (element.tagName === 'SELECT') {
                        selected = Array.prototype.slice.call(element.selectedOptions);
                        if (!selected.length && element.options.length) selected.push(element.options[0]);
                      } else {
                        var lb = role === 'combobox' ? findOwned(element, '*').filter(function(e){ return getAriaRole(e) === 'listbox'; })[0] : element;
                        if (lb) selected = findOwned(lb, '[aria-selected="true"]').filter(function(e){ return getAriaRole(e) === 'option'; });
                      }
                      return selected.map(function(o){ return getElementAccessibleNameInternal(o, childOptions); }).join(' ');
                    }
                    if (role === 'progressbar' || role === 'scrollbar' || role === 'slider' || role === 'spinbutton' || role === 'meter') {
                      options.visitedElements.add(element);
                      if (element.hasAttribute('aria-valuetext')) return element.getAttribute('aria-valuetext') || '';
                      if (element.hasAttribute('aria-valuenow')) return element.getAttribute('aria-valuenow') || '';
                      return element.getAttribute('value') || '';
                    }
                    if (role === 'menu') {
                      options.visitedElements.add(element);
                      return '';
                    }
                  }
                }
                var ariaLabel = element.getAttribute('aria-label') || '';
                if (ariaLabel.trim()) { options.visitedElements.add(element); return ariaLabel; }
                if (role !== 'presentation' && role !== 'none') {
                  if (element.tagName === 'INPUT' && ['button','submit','reset'].indexOf(element.type) !== -1) {
                    options.visitedElements.add(element);
                    var val = element.value || '';
                    if (val.trim()) return val;
                    if (element.type === 'submit') return 'Submit';
                    if (element.type === 'reset') return 'Reset';
                    return element.getAttribute('title') || '';
                  }
                  if (element.tagName === 'INPUT' && element.type === 'image') {
                    options.visitedElements.add(element);
                    var ilabels = (element.labels || []);
                    if (ilabels.length && options.embeddedInLabelledBy === 'none') return getAccessibleNameFromAssociatedLabels(ilabels, options);
                    var alt = element.getAttribute('alt') || '';
                    if (alt.trim()) return alt;
                    var ititle = element.getAttribute('title') || '';
                    if (ititle.trim()) return ititle;
                    return 'Submit';
                  }
                  if (element.tagName === 'BUTTON' && !labelledBy) {
                    options.visitedElements.add(element);
                    var blabels = (element.labels || []);
                    if (blabels.length) return getAccessibleNameFromAssociatedLabels(blabels, options);
                  }
                  if (element.tagName === 'OUTPUT' && !labelledBy) {
                    options.visitedElements.add(element);
                    var olabels = (element.labels || []);
                    if (olabels.length) return getAccessibleNameFromAssociatedLabels(olabels, options);
                    return element.getAttribute('title') || '';
                  }
                  if ((element.tagName === 'TEXTAREA' || element.tagName === 'SELECT' || element.tagName === 'INPUT') && !labelledBy) {
                    options.visitedElements.add(element);
                    var flabels = (element.labels || []);
                    if (flabels.length) return getAccessibleNameFromAssociatedLabels(flabels, options);
                    var usePlaceholder = (element.tagName === 'INPUT' && ['text','password','search','tel','email','url'].indexOf(element.type) !== -1) || element.tagName === 'TEXTAREA';
                    var placeholder = element.getAttribute('placeholder') || '';
                    var title = element.getAttribute('title') || '';
                    if (!usePlaceholder || title) return title;
                    return placeholder;
                  }
                  if (element.tagName === 'FIELDSET' && !labelledBy) {
                    options.visitedElements.add(element);
                    for (var c = element.firstElementChild; c; c = c.nextElementSibling) {
                      if (c.tagName === 'LEGEND') return getElementAccessibleNameInternal(c, { includeHidden: options.includeHidden, visitedElements: options.visitedElements, embeddedInLabelledBy: 'none', embeddedInLabel: 'none', embeddedInTextAlternativeElement: true, embeddedInTargetElement: 'none' });
                    }
                    return element.getAttribute('title') || '';
                  }
                  if (element.tagName === 'FIGURE' && !labelledBy) {
                    options.visitedElements.add(element);
                    for (var fc = element.firstElementChild; fc; fc = fc.nextElementSibling) {
                      if (fc.tagName === 'FIGCAPTION') return getElementAccessibleNameInternal(fc, { includeHidden: options.includeHidden, visitedElements: options.visitedElements, embeddedInLabelledBy: 'none', embeddedInLabel: 'none', embeddedInTextAlternativeElement: true, embeddedInTargetElement: 'none' });
                    }
                    return element.getAttribute('title') || '';
                  }
                  if (element.tagName === 'IMG') {
                    options.visitedElements.add(element);
                    var imalt = element.getAttribute('alt') || '';
                    if (imalt.trim()) return imalt;
                    var imtitle = element.getAttribute('title') || '';
                    if (imtitle.trim()) return imtitle;
                    return '';
                  }
                  if (element.tagName === 'TABLE') {
                    options.visitedElements.add(element);
                    for (var tc = element.firstElementChild; tc; tc = tc.nextElementSibling) {
                      if (tc.tagName === 'CAPTION') return getElementAccessibleNameInternal(tc, { includeHidden: options.includeHidden, visitedElements: options.visitedElements, embeddedInLabelledBy: 'none', embeddedInLabel: 'none', embeddedInTextAlternativeElement: true, embeddedInTargetElement: 'none' });
                    }
                    var summary = element.getAttribute('summary') || '';
                    if (summary) return summary;
                  }
                  if (element.tagName === 'AREA') {
                    options.visitedElements.add(element);
                    var aalt = element.getAttribute('alt') || '';
                    if (aalt.trim()) return aalt;
                    var atitle = element.getAttribute('title') || '';
                    if (atitle.trim()) return atitle;
                    return '';
                  }
                  if (element.tagName.toUpperCase() === 'SVG' || element.ownerSVGElement) {
                    options.visitedElements.add(element);
                    for (var sc = element.firstElementChild; sc; sc = sc.nextElementSibling) {
                      if (sc.tagName.toUpperCase() === 'TITLE' && sc.ownerSVGElement) return getElementAccessibleNameInternal(sc, { includeHidden: options.includeHidden, visitedElements: options.visitedElements, embeddedInLabelledBy: 'self', embeddedInLabel: 'none', embeddedInTextAlternativeElement: false, embeddedInTargetElement: 'none' });
                    }
                    if (element.ownerSVGElement && element.tagName.toUpperCase() === 'A') {
                      var xtitle = element.getAttribute('xlink:title') || '';
                      if (xtitle.trim()) { options.visitedElements.add(element); return xtitle; }
                    }
                  }
                }
                if (allowsNameFromContent(role, options.embeddedInTargetElement === 'descendant') || options.embeddedInLabelledBy !== 'none' || options.embeddedInLabel !== 'none' || options.embeddedInTextAlternativeElement) {
                  options.visitedElements.add(element);
                  var tokens = [];
                  // 生成定位 name 时（includeAdvisory=false）忽略装饰性伪元素内容，
                  // 否则" (opens in a new window)"等提示会被算进 name，与可见文本不一致。
                  tokens.push(options.includeAdvisory ? getPseudoContent(getPseudo(element, '::before')) : '');
                  // aria-describedby 指向的是"描述"而非"名称"，其引用的后代不应计入可访问名，
                  // 否则描述文本会污染定位用 name（与 Playwright getByRole 行为对齐）。
                  var descRefs = options.includeAdvisory ? [] : getIdRefs(element, element.getAttribute('aria-describedby'));
                  var child = element.firstChild;
                  // 性能：超大子树（如可点击的大容器 / 列表）逐子节点 getComputedStyle 会拖慢单次点击；
                  // 设预算封顶（本层新访问元素上限），超阈值即停止下钻，避免拾取卡顿。
                  // 定位用 name 过长本就无意义，封顶对生成结果影响可忽略。
                  var __budget0 = options.visitedElements.size;
                  while (child) {
                    if (options.visitedElements.size - __budget0 > 4096) break;
                    if (child.nodeType === 1) {
                      if (descRefs.indexOf(child) !== -1) { child = child.nextSibling; continue; }
                      var cs = getComputedStyleSafe(child);
                      var d = cs ? (cs.display || 'inline') : 'inline';
                      var tk = getElementAccessibleNameInternal(child, childOptions);
                      if (d !== 'inline' || child.tagName === 'BR') tk = ' ' + tk + ' ';
                      tokens.push(tk);
                    } else if (child.nodeType === 3) {
                      tokens.push(child.textContent || '');
                    }
                    child = child.nextSibling;
                  }
                  tokens.push(options.includeAdvisory ? getPseudoContent(getPseudo(element, '::after')) : '');
                  var name = tokens.join('');
                  if (name.trim()) return name;
                }
                if (role !== 'presentation' && role !== 'none' || element.tagName === 'IFRAME') {
                  options.visitedElements.add(element);
                  var ttl = element.getAttribute('title') || '';
                  if (ttl.trim()) return ttl;
                }
                options.visitedElements.add(element);
                return '';
              }
              function getElementAccessibleName(element, includeAdvisory) {
                var role = getAriaRole(element) || '';
                if (kProhibitName.indexOf(role) !== -1) return '';
                return normalizeAccessibleName(getElementAccessibleNameInternal(element, {
                  includeHidden: false, visitedElements: new Set(),
                  embeddedInLabelledBy: 'none', embeddedInLabel: 'none',
                  embeddedInTextAlternativeElement: false, embeddedInTargetElement: 'self',
                  includeAdvisory: (includeAdvisory !== false)
                }));
              }
              // 返回 {name, cleaned}：name 为剔除装饰性伪元素/描述文本后的干净可见文本；
              // cleaned=true 表示原 name 含被剔除的提示性文本，生成 @RoleElement 时需用 exact=false 子串匹配。
              function getNameInfo(el) {
                var clean = getElementAccessibleName(el, false);
                var raw = getElementAccessibleName(el, true);
                return { name: clean, cleaned: (clean !== raw) };
              }

              // 推导标题层级：优先 aria-level，否则 h1–h6 标签，无则 0（不限层级）。
              // 供 step 4 的 heading 角色生成 @RoleElement(level=N)，对齐 getByRole(HEADING).setLevel(n)。
              function getHeadingLevel(el) {
                var al = el.getAttribute && el.getAttribute('aria-level');
                if (al) { var n = parseInt(al, 10); if (!isNaN(n) && n > 0) return n; }
                var m = /^H([1-6])$/.exec((el.tagName || '').toUpperCase());
                if (m) return parseInt(m[1], 10);
                return 0;
              }

              // 无语义角色：这类元素通常不是用户想捕获的"控件"，应向上回溯
              var NON_ROLE = { generic:1, none:1, presentation:1 };
              // 可作为点击目标的"交互角色"：仅这些角色才用 role+name 定位（对齐 pause 优先级）；
              // region/navigation/list 等容器角色不算，避免点文本时误抓到外层容器。
              var INTERACTIVE_ROLES = { button:1, link:1, checkbox:1, radio:1, tab:1,
                menuitem:1, menuitemcheckbox:1, menuitemradio:1, option:1, 'switch':1,
                textbox:1, combobox:1, listbox:1, searchbox:1, slider:1, spinbutton:1, treeitem:1 };

              // 判断 id 是否"稳定"（可作为持久选择器）：排除自动生成/含长数字/非法字符的 id
              function isStableId(id) {
                if (!id) return false;
                if (id.length > 40) return false;
                if (/\\d{4,}/.test(id)) return false;               // 连续 4+ 数字，疑似动态
                return /^[A-Za-z][\\w-]*$/.test(id);                 // 首字母 + 单词字符/连字符
              }
              function ownVisibleText(el) {
                return (el.textContent || '').replace(/\\s+/g, ' ').trim();
              }
              // 是否"可输入"元素（对齐 page.pause() 的 fill 录制范围）：文本框/多行/数字/搜索/
              // contenteditable，以及 role 为 textbox/searchbox/spinbutton 的控件。
              function isEditable(el) {
                if (!el) return false;
                var tg = (el.tagName || '').toLowerCase();
                if (tg === 'textarea') return true;
                if (tg === 'input') {
                  var type = (el.getAttribute('type') || 'text').toLowerCase();
                  return ['text','search','email','tel','url','password','number'].indexOf(type) !== -1;
                }
                if (el.getAttribute && el.getAttribute('contenteditable') === 'true') return true;
                var r = (getRole(el) || '').toLowerCase();
                return r === 'textbox' || r === 'searchbox' || r === 'spinbutton';
              }
              // 取可输入元素的当前值（input/textarea 用 .value；contenteditable 用 textContent）
              function editableValue(el) {
                if (!el) return '';
                var tg = (el.tagName || '').toLowerCase();
                if (tg === 'input' || tg === 'textarea') return el.value != null ? el.value : '';
                return (el.textContent || '');
              }
              // 生成最短可用 css 路径（tag + :nth-of-type 链，遇稳定 id 祖先即止）——兜底用，标记需人工确认
              function cssPathOf(el) {
                // 对齐 page.pause()：shadow DOM 场景下，攀父链遇到 shadowRoot 边界时停在"影子宿主"上，
                // 不再跨出 shadow（否则会产出跨越 shadow 边界、运行期无效的 css）。得到的路径仅在该 shadow
                // 树内有效；若整条都无锚点则为纯位置链，由 __isNthOnlyCss 按原规则拦截/标记。
                function __cssParent(n) {
                  if (!n) return null;
                  var p = n.parentElement;
                  if (p) return p;
                  // 若在 shadowRoot 内，parentElement 为空；取 shadow host 作为边界锚（不继续向上穿出）。
                  var rn = null;
                  try { rn = n.getRootNode ? n.getRootNode() : null; } catch (e) {}
                  if (rn && rn.host) return rn.host;   // 影子宿主：作为该链在 shadow 边界的停止锚点
                  return null;
                }
                var parts = [], node = el, depth = 0;
                // 第一趟：与原算法一致，最多 5 层内若遇到稳定 #id 则用作锚点并停止。
                // shadow DOM 边界处理：元素在 shadow 内时 parent 为影子宿主（host）；host 属于外层树，
                // 下一轮其 __cssParent 取外层 parentElement 继续向上，故不在此"break"，而是把 host 标签
                // 作为路径普通一段带入（与内部元素标签并列），既保留 shadow 内目标元素自身的 tag，
                // 又通过 host 自然衔接外层祖先，产出的 css 形如 "host > #inner-id"（仍仅在该 shadow 树内有效）。
                while (node && node.nodeType === 1 && depth++ < 5) {
                  var idv = node.getAttribute && node.getAttribute('id');
                  if (isStableId(idv)) { parts.unshift('#' + idv); break; }
                  var sel = node.tagName.toLowerCase();
                  var parent = __cssParent(node);
                  if (parent && parent !== (node.getRootNode ? node.getRootNode().host : null)) {
                    // 普通父节点：计算同级 nth-of-type（与 Playwright css 引擎一致）
                    var same = [];
                    for (var c = parent.firstElementChild; c; c = c.nextElementSibling) {
                      if (c.tagName === node.tagName) same.push(c);
                    }
                    if (same.length > 1) sel += ':nth-of-type(' + (same.indexOf(node) + 1) + ')';
                  }
                  // 若 parent 是 shadow host：不额外处理，sel 已是 node 自身标签（已含 nth），下一步 node=host 进入外层树。
                  parts.unshift(sel);
                  node = parent;
                }
                var css = parts.join(' > ');
                // 【稳定性增强】若 5 层内无任何锚点（#id/.class/[attr]），得到的是纯 tag 位置链
                // （如 "div:nth-of-type(3) > div"），这类选择器随布局微变即失效（page.pause 也仅把 css 作最后兜底）。
                // 继续向上回溯到第一个带锚点的祖先，把它作为前缀锚点，使最终 css 形如
                // "#app > div:nth-of-type(2) > span"，既有稳定锚点又保留局部层级，鲁棒性显著提升。
                // 上限放宽到 12 层（仅作锚点寻找，不计入选择器长度），避免极端深嵌套时仍退化为纯布局链。
                //
                // 【关键限定】该锚点增强**仅在"非区域扫描态"启用**：
                //   区域扫描（window.__roleScanRoot 非空）无差别遍历区域内全部元素，大量无语义布局 div
                //   会退化出纯 tag 位置链，本就该被 __recordPick 的 __isNthOnlyCss 拦截；若此处先给它加上
                //   "#app " 锚点前缀，__isNthOnlyCss 因见到 '#' 而 return false，导致拦截失效、记录下
                //   "#app > div:nth-of-type(2) > ..." 这种长 css（即"区域选择出现很长 css"的回归根因）。
                //   因此区域态保持原始纯位置链，__isNthOnlyCss 才能正确判真并拦截噪音；
                //   手动点选态保留锚点前缀，与 page.pause 一致地提升单点元素的 css 兜底稳定性。
                // 附加防御：若原始 css 已是整页级骨架链（含 body / html 段落），绝不画蛇添足加锚点——
                // 这类链无业务价值，加了锚点（如 ".Mozilla body > ..."）反而绕开 __recordPick 的
                // body 开头拦截、记录下更长更废的选择器。
                if (__isNthOnlyCss(css) && !window.__roleScanRoot
                    && css.indexOf('body') === -1 && css.indexOf('html') === -1) {
                  var anchor = node, aDepth = 0;
                  while (anchor && anchor.nodeType === 1 && aDepth++ < 12) {
                    var aid = anchor.getAttribute && anchor.getAttribute('id');
                    if (isStableId(aid)) { return aid ? ('#' + aid + ' ' + css) : css; }
                    // 带 .class 或 [attr] 的祖先也可作锚点（优先 #id，这里退一步取首个含 class/attr 的）
                    if (anchor.className && ('' + anchor.className).trim()) {
                      var cls = ('' + anchor.className).trim().split(/\\s+/)[0];
                      if (cls) return '.' + cls + ' ' + css;
                    }
                    var attrs = anchor.attributes;
                    if (attrs) {
                      for (var ai = 0; ai < attrs.length; ai++) {
                        var an = attrs[ai].name;
                        if (an !== 'class' && an !== 'style' && an !== 'id' && an.indexOf('data-') === 0) {
                          return '[' + an + '] ' + css;
                        }
                      }
                    }
                    anchor = anchor.parentElement;
                  }
                }
                return css;
              }
              // 判断 css 兜底路径是否为"纯布局型"（无锚点路径）。
              // 典型垃圾形态：
              //   div:nth-of-type(3) > div > div > div:nth-of-type(3) > div:nth-of-type(17)
              //   div > div > span
              // 判定标准：整条路径里找不到任何**语义锚点**——即没有 #id、没有 .class、
              // 没有属性选择器（[data-testid=...] / [data-i18n=...]），只由 tag 名与
              // 位置索引（nth-of-type / nth-child）拼接而成。
              // 这类选择器完全依赖 DOM 的兄弟顺序与层级，页面任何布局微调（插一个 div、
              // 换一次栅格）即全部失效，且对阅读者毫无业务含义。
              // 注意：只要路径中**任意一段**带 #id / .class / [attr]，就认为有锚点而放行，
              // 因为该段能把定位约束在一个语义节点上，后续的 nth-of-type 只是相对偏移。
              //
              // 【适用范围】仅用于**区域扫描**，由 window.__roleScanRoot 非空判定区域态
              // （注意不能用 __scanMode：__roleScanPage 入口会把它统一覆写为 'page'）。
              // 区域扫描 querySelectorAll('*') 无差别遍历，区域内大量无语义布局 div 会退化出这类
              // 路径，是噪音的唯一来源，需要过滤。而手动点选 / 整页扫描不使用本判定——page.pause()
              // 的 selectorGenerator 永远产出选择器、从不拒绝录制元素，那两个场景保持与 pause 一致。
              function __isNthOnlyCss(css) {
                if (!css) return false;
                var segs = css.split(' > ');
                for (var si = 0; si < segs.length; si++) {
                  var sg = segs[si];
                  if (sg.indexOf('#') !== -1) return false;   // #id 锚点
                  if (sg.indexOf('.') !== -1) return false;   // .class 锚点
                  if (sg.indexOf('[') !== -1) return false;   // [attr=...] 锚点（testid / data-i18n 等）
                }
                // 全程无锚点：无论有没有 nth-of-type，都是纯 tag 位置链 → 判为噪音。
                // （含 nth 的靠索引，不含 nth 的靠层级，稳定性同样为零。）
                return true;
              }

              // ============================================================================
              // 定位策略链（忠实对齐 page.pause() 的 selectorGenerator 打分序，分低者优先）：
              //   testId(1) < role+name(100) < placeholder(120) < label(140)
              //     < altText(160) < text(180) < title(200) < css #id(500)
              //     < roleWithoutName(510) < [name=](520) / [type=](521) < css 路径(needsReview)
              //   （roleWithoutName(510) 已对齐：纯无 name 语义角色生成 @RoleElement(role=...) 无 name，
              //     经 Binder 无 name 重载 getByRole(role) 定位，与 pause 的 roleWithoutName 一致。）
              // 算法分两步（与 recorder 一致）：
              //   ① 重定位：label → 关联控件；再向上回溯（≤5 层）找交互角色祖先，
              //      点按钮内文字/图标时抓按钮本身；
              //   ② 在重定位后的目标元素上按打分序依次尝试候选：
              //      1. data-testid 族        → getByTestId（本项目扩展：data-testid/-test-id/-test/-qa 同权）
              //      2. 交互角色 + 可访问名    → getByRole（打分 100，先于 placeholder/label；生成 @RoleElement，保留 NLS 多语言）
              //         注：仅 NON_ROLE（generic/none/presentation）外的"有意义"语义角色命中；
              //         无语义角色元素继续走下方 data-i18n / alt / text 兜底。
              //      2.5 非交互元素的 data-i18n 多语言 key → @Element("[data-i18n=...]")（CSS 属性选择器，本项目约定，
              //          仅在上方 role+name 未命中时生效：交互控件已走 role，这里专补 generic span/div 等稳定定位）
              //      3. INPUT/TEXTAREA 的 placeholder → getByPlaceholder（pause 仅对这两类标签，打分 120）
              //      4. 表单控件原生关联 label → getByLabel（打分 140，置于 role+name 之后，与 pause 一致）
              //         注：仅"直接点击 <label> 本身"时生效（originalIsLabel）；若点是表单控件本身，则跳过本分支，
              //         避免"点了输入框却生成 label 策略"的错位。
              //      5. IMG/AREA/INPUT 的 alt  → getByAltText（pause 限定 APPLET/AREA/IMG/INPUT，打分 160，先于 text）
              //      6. 可见文本（≤80 字符，pause 截断阈值）→ getByText
              //         注：pause 中先试子串/前缀（非 exact，score 180）再试精确（exact，score 185）；
              //         本项目先出非 exact 候选（稳定容忍文案微调），再出 exact。
              //      7. title                  → getByTitle（200）
              //      8. 稳定 id                → #id（500 分，必须排在语义候选之后）
              //      8.5 [name=]（520）/ input|textarea|select[type=]（521）→ css 属性选择器
              //          （对齐 pause 属性级 css 兜底层级，排在长 css 路径之前）
              //      9. css 路径兜底（needsReview=true）
              // 顺序短路模型：本项目以"按打分序短路尝试、首个命中即返回"来等价 pause 的打分排序；
              // 故无需单独实现 penalizeScoreForLength（长文本 >80 字符直接跳过 text 即为长度惩罚的特例）。
              // 仅返回"原始片段"（strategy/role/name/attr/value/id/css），
            """;
    private static final String START_SCRIPT_B1 = """
              // 由 Java 侧负责拼接并转义选择器字符串，避免在 Java 文本块里处理引号转义。
              // ============================================================================
              // 与 Java 侧 normalize() 保持一致：把 name 归一化（回车换行→换行、nbsp→空格、折叠空白、trim），
              // 用于和预加载的 nls 反向查表（已用同样规则规范化）做精确匹配。
              function normName(s) {
                return (s || '').replace(/\\r\\n/g, '\\n').replace(/\\u00A0/g, ' ').replace(/\\s+/g, ' ').trim();
              }
              // 用规范化后的可见文本在预加载的 window.__nlsReverse 里反查 nls key；
              // 命中返回 {key, exact}：exact=true 表示 name 与 nls 值完全一致（精确匹配即可定位），
              // exact=false 表示经前缀/子串/模板命中（运行时须子串匹配，否则会因 name 比真实可访问名
              // 短/长而定位失败，如按钮文案 "Log out" 命中短标签 key "Log"）。
              function nlsKeyInfo(s) {
                if (!s) return { key: null, exact: false };
                var sn = normName(s);
                // 1) 精确反查（无模板变量的值）
                if (window.__nlsReverse && sn) {
                  var k = window.__nlsReverse[sn] || null;
                  if (k && k.trim()) return { key: k.trim(), exact: true };
                }
                // 1.5) 前缀反查：a11y name 常是「短标签 + 空格 + 其余内容」复合串
                //      （典型如页脚链接：可见短标签 "Hyperlink Policy" 在 NLS 中作为
                //      "Hyperlink Policy footer" 的 value 存在）。取「最长且为 name 前缀」
                //      的精确表值，用其 key —— 运行时按该短值做子串匹配即可定位，
                //      避免回退成拼接字面 name（如 "Hyperlink Policy Hyperlink ..."）。
                if (window.__nlsReverse && sn) {
                  var preKey = null, preLen = -1;
                  for (var vk in window.__nlsReverse) {
                    if (!Object.prototype.hasOwnProperty.call(window.__nlsReverse, vk)) continue;
                    var v = vk, kk = window.__nlsReverse[vk];
                    if (!v || !kk || !v.length) continue;
                    if (sn.indexOf(v) === 0 && v.length > preLen) { preLen = v.length; preKey = kk; }
                  }
                  if (preKey && preKey.trim()) return { key: preKey.trim(), exact: false };
                }
                // 1.6) 子串反查（兜底）：name 中部包含某精确表值（非前缀，避免与 1.5 重复），
                //      取最长匹配者，用于「说明文本包住短标签」等其它复合形态。
                if (window.__nlsReverse && sn) {
                  var subKey = null, subLen = -1;
                  for (var vk in window.__nlsReverse) {
                    if (!Object.prototype.hasOwnProperty.call(window.__nlsReverse, vk)) continue;
                    var v = vk, kk = window.__nlsReverse[vk];
                    if (!v || !kk || !v.length) continue;
                    var p = sn.indexOf(v);
                    if (p > 0 && v.length > subLen) { subLen = v.length; subKey = kk; }
                  }
                  if (subKey && subKey.trim()) return { key: subKey.trim(), exact: false };
                }
                // 2) 模板反查：遍历 window.__nlsTemplates（[[正则源, key], ...]），
                //    用归一化后的可见文本测试正则，命中取「字面前缀最长」者（更具体，避免误配短模板）。
                //    模板值含通配 (.*?)，永远非精确。
                if (window.__nlsTemplates && window.__nlsTemplates.length) {
                  var txt = normName(s);
                  var best = null, bestLen = -1;
                  for (var i = 0; i < window.__nlsTemplates.length; i++) {
                    var re = window.__nlsTemplates[i];
                    if (!re || !re[0]) continue;
                    try {
                      if (new RegExp(re[0]).test(txt)) {
                        var litLen = re[0].replace(/\\(\\.\\*\\?\\)/g, '').length;
                        if (litLen > bestLen) { bestLen = litLen; best = re[1]; }
                      }
                    } catch (e) {}
                  }
                  if (best) return { key: best, exact: false };
                }
                return { key: null, exact: false };
              }
              // 关联 label 文本（对齐 pause buildNoTextCandidates：仅 INPUT/TEXTAREA/SELECT 的
              // 原生关联 label（for/包裹），取第一个，归一化后截断 80）。aria-label 不在此列——
              // 它已进入可访问名，由 role+name 候选表达。
              function labelTextOf(el) {
                var tg = (el.tagName || '').toLowerCase();
                if (tg !== 'input' && tg !== 'textarea' && tg !== 'select') return '';
                try {
                  // 对齐 page.pause() 的 getElementLabels：遍历全部关联 label（含 aria-labelledby 指向的多个），
                  // 逐个归一化后拼接（PW 用 normalizeWhiteSpace 后 join(' ')），而非只取第一个。
                  var labels = el.labels;
                  if (labels && labels.length) {
                    var parts = [];
                    for (var i = 0; i < labels.length; i++) {
                      var s = normName(labels[i].textContent || '');
                      if (s) parts.push(s);
                    }
                    var joined = parts.join(' ');
                    if (joined) return joined.slice(0, 80);
                  }
                } catch (e) {}
                return '';
              }
              // 计算 pick 定位器在整页上匹配的元素集合（文档顺序），用于判定"一组元素"并给出序号。
              // 与生成端的定位策略对齐：role→role+name（exact=!cleaned）、text→最内层文本相等、
              // 属性类→属性值相等、label→关联控件。id/css 视为唯一（返回空）。
              function __normSafe(s) {
                try { return normName(s || ''); } catch (e) { return (s || '').replace(/\\s+/g, ' ').trim(); }
              }
              // 同帧内（一次同步执行流）对同一 pick 的 __matchingElements 结果缓存，避免 syncPanelToBrowser
              // 每 1s 对每个元素重复全页 querySelectorAll + getNameInfo（O(N²) 级卡顿主因，O1）。
              // 每次 syncPanelToBrowser / 每次点选会话前调用 window.__clearMatchCache() 清空。
              window.__matchingCache = window.__matchingCache || {};
              function __clearMatchCache() { try { window.__matchingCache = {}; } catch (e) {} }
              // 对齐 page.pause()：穿透 open shadow DOM 收集全页元素（Web Components 场景）。
              // 深度优先遍历 document 与各级 shadowRoot，返回扁平元素数组。
              function __allElementsInDoc(root) {
                var out = [];
                (function walk(node) {
                  if (!node) return;
                  var kids = (node.querySelectorAll ? node.querySelectorAll('*') : null);
                  if (!kids) return;
                  for (var i = 0; i < kids.length; i++) {
                    out.push(kids[i]);
                    var sr = null;
                    try { sr = kids[i].shadowRoot; } catch (e) {}
                    if (sr && (sr.mode === 'open' || sr.mode == null)) walk(sr);
                  }
                })(root);
                return out;
              }
              function __allAttrNodesInDoc(root, attr) {
                var out = [];
                (function walk(node) {
                  if (!node) return;
                  var local = null;
                  try { local = node.querySelectorAll('[' + attr + ']'); } catch (e) {}
                  if (local) for (var i = 0; i < local.length; i++) out.push(local[i]);
                  var kids = (node.querySelectorAll ? node.querySelectorAll('*') : null);
                  if (!kids) return;
                  for (var j = 0; j < kids.length; j++) {
                    var sr = null;
                    try { sr = kids[j].shadowRoot; } catch (e) {}
                    if (sr && (sr.mode === 'open' || sr.mode == null)) walk(sr);
                  }
                })(root);
                return out;
              }
              function __allFormNodesInDoc(root) {
                var out = [];
                (function walk(node) {
                  if (!node) return;
                  var local = null;
                  try { local = node.querySelectorAll('input,textarea,select'); } catch (e) {}
                  if (local) for (var i = 0; i < local.length; i++) out.push(local[i]);
                  var kids = (node.querySelectorAll ? node.querySelectorAll('*') : null);
                  if (!kids) return;
                  for (var j = 0; j < kids.length; j++) {
                    var sr = null;
                    try { sr = kids[j].shadowRoot; } catch (e) {}
                    if (sr && (sr.mode === 'open' || sr.mode == null)) walk(sr);
                  }
                })(root);
                return out;
              }
              var __rawMatchingElements = function(pick) {
                if (!pick) return [];
                var all = __allElementsInDoc(document), out = [], i, x;
                if (pick.strategy === 'role') {
                  var role = (pick.role || '').toLowerCase();
                  var tgt = __normSafe(pick.name);
                  var exact = !pick.cleaned;   // 生成端 @RoleElement exact = !cleaned
                  for (i = 0; i < all.length; i++) {
                    x = all[i];
                    if ((getRole(x) || '').toLowerCase() !== role) continue;
                    if (closestSafe(x, '[aria-hidden="true"]')) continue;   // 对齐 getByRole 默认排除 aria-hidden
                    var nm = __normSafe(getNameInfo(x).name);
                    if (!nm) continue;
                    if (exact ? (nm === tgt) : (nm.indexOf(tgt) !== -1)) out.push(x);
                  }
                  return out;
                }
                if (pick.strategy === 'text') {
                  var t2 = __normSafe(pick.name);
                  for (i = 0; i < all.length; i++) {
                    x = all[i];
                    if (__normSafe(x.textContent) !== t2) continue;
                    var childMatch = false;   // 仅保留最内层匹配，贴近 getByText 行为
                    for (var c = 0; c < x.children.length; c++) {
                      if (__normSafe(x.children[c].textContent) === t2) { childMatch = true; break; }
                    }
                    if (!childMatch) out.push(x);
                  }
                  return out;
                }
                var attr = null;
                if (pick.strategy === 'placeholder') attr = 'placeholder';
                else if (pick.strategy === 'altText') attr = 'alt';
                else if (pick.strategy === 'title') attr = 'title';
                else if (pick.strategy === 'i18n') attr = 'data-i18n';
                else if (pick.strategy === 'testid') attr = pick.attr || null;
                if (attr) {
                  var want = (pick.value != null ? pick.value : pick.name);
                  var nodes = __allAttrNodesInDoc(document, attr);
                  for (i = 0; i < nodes.length; i++) {
                    if ((nodes[i].getAttribute(attr) || '').trim() === want) out.push(nodes[i]);
                  }
                  return out;
                }
                if (pick.strategy === 'label') {
                  var t3 = __normSafe(pick.name);
                  var forms = __allFormNodesInDoc(document);
                  for (i = 0; i < forms.length; i++) {
                    var lbls = forms[i].labels;
                    if (lbls && lbls.length && __normSafe(lbls[0].textContent).indexOf(t3) !== -1) out.push(forms[i]);
                  }
                  return out;
                }
                return out;   // id / css 视为唯一
              };
              // 缓存包装：同一 __pickSig 在一帧内只算一次（全页 querySelectorAll + getNameInfo 较重）。
              function __matchingElements(pick) {
                if (!pick) return [];
                var ck = (typeof window.__pickSig === 'function') ? window.__pickSig(pick) : null;
                if (ck) {
                  var cached = window.__matchingCache[ck];
                  if (cached) return cached;
                  var res = __rawMatchingElements(pick);
                  window.__matchingCache[ck] = res;
                  return res;
                }
                return __rawMatchingElements(pick);
              }
              // 给 pick 附上该定位器在页面上的序号（index/count），生成 step 时输出 .nth(index)，
              // 对齐 page.pause() 的 first()/nth() 消歧。
              //
              // 【关键】序号必须"无条件"赋值（哪怕当前只匹配到 1 个），不能写成 if (ms.length > 1)。
              // 序号是元素身份的一部分（__pickSig 会把它拼进签名），而 __matchingElements 只反映
              // "计算签名那一刻的 DOM"。若按匹配数有条件赋值，同一个元素会在两种签名间跳变：
              //   · 区域扫描时区域内只有它一个同名元素 → ms.length===1 → 无 index → 'role:link:X'
              //   · 整页扫描时全页有多个同名元素     → ms.length>1  → index=0 → 'role:link:X#0'
              // 两个签名被 Java 侧当成不同 key，同一元素每扫一轮就多存一份（实测重复 4~5 次）。
              // 统一赋值后签名恒定为 'role:link:X#0'，既不再重复，又保留"同 role 同 name 的第 2 个
              // 元素（#1）能被独立拾取"的能力——这正是 page.pause() 的语义。
              // 对齐 page.pause() 的 getByRole 状态过滤：把元素当前可访问状态一并带出，
              // 供 @RoleElement 生成 disabled=/pressed=/expanded=/checked= 精确过滤。
              // 抽出为独立函数，所有定位策略（role/text/...）都经 __attachIndex 统一调用，
              // 修复"role 策略走 __attachIndex 直出、跳过 done() 导致状态/iframe 路径全部丢失"的问题。
              function __enrichState(pick, el) {
                if (!pick || !el) return pick;
                try {
                  // disabled：原生 disabled 属性或 aria-disabled（button/input/... 通用）
                  if (el.hasAttribute && el.hasAttribute('aria-disabled')) pick.disabled = (el.getAttribute('aria-disabled') === 'true') ? 'YES' : 'NO';
                  else if (typeof el.disabled === 'boolean' && el.disabled) pick.disabled = 'YES';
                } catch (e) {}
                try {
                  if (el.hasAttribute && el.hasAttribute('aria-pressed')) {
                    var pv = el.getAttribute('aria-pressed');
                    pick.pressed = (pv === 'true' || pv === 'mixed') ? 'YES' : 'NO';
                  }
                } catch (e) {}
                try {
                  if (el.hasAttribute && el.hasAttribute('aria-expanded')) pick.expanded = (el.getAttribute('aria-expanded') === 'true') ? 'YES' : 'NO';
                } catch (e) {}
                try {
                  var pRole = (pick.role || '').toLowerCase();
                  if (pRole === 'checkbox' || pRole === 'radio' || pRole === 'switch'
                      || pRole === 'menuitemcheckbox' || pRole === 'menuitemradio' || pRole === 'treeitem') {
                    var cb = el;
                    if (cb.type === 'checkbox' || cb.type === 'radio') pick.checked = !!cb.checked;
                    else if (cb.hasAttribute && cb.hasAttribute('aria-checked')) {
                      var _av = cb.getAttribute('aria-checked');
                      pick.checked = (_av === 'true' || _av === 'mixed');
                    }
                  }
                } catch (e) {}
                // iframe 嵌套路径：仅当元素确实位于 iframe 内时写入，供 Java 侧生成 frameLocator。
                try {
                  var fp = __framePathOf();
                  if (fp && fp.length) pick.framePath = fp;
                } catch (e) {}
                // 归属空间（space）：融合「iframe 链」+「open shadow 链」，标注该元素位于哪个空间。
                // 约定格式（与 RoleEntry.space 注释一致）：main / frame:login / shadow:hostComp /
                // frame:login>shadow:comp / frame:a>frame:b。仅主文档元素为 "main"。
                // 该字段为可读标注（不参与代码生成语义），驱动生成的是下方独立的 shadowPath（结构化）。
                try {
                  // 收集 open shadow 宿主链（自顶向下）：每跳一层 shadow 取其宿主的 CSS 选择器。
                  var shadowHosts = []; // 自底向上收集，后反转
                  try {
                    var n = el;
                    while (n) {
                      var rn = (n.getRootNode ? n.getRootNode() : null);
                      if (rn && rn.host) {
                        var h = rn.host;
                        var ht = (h.tagName || '').toLowerCase();
                        var hid = (h.getAttribute && h.getAttribute('id')) || '';
                        var hcls = (h.getAttribute && h.getAttribute('class')) || '';
                        var hsel = ht + (hid ? '#' + hid : (hcls ? '.' + hcls.trim().split(/\s+/).join('.') : ''));
                        shadowHosts.unshift(hsel); // 自底向上，最终反转得到自顶向下
                        n = h;
                      } else break;
                    }
                  } catch (se) {}
                  // 结构化 shadow 路径（自顶向下），供 Java 侧生成「显式切换 shadow」step。
                  // 注意：shadowHosts 经上方循环逐层 unshift 后已是【自顶向下】顺序（最外层宿主在前），
                  // 与下方 space 字符串的口径一致（space 用 shadowHosts[length-1] 即最顶层起拼），故此处不可再 reverse。
                  if (shadowHosts.length) pick.shadowPath = shadowHosts.slice();
                  // 组装 readable space（约定格式）
                  var fp2 = (pick.framePath && pick.framePath.length) ? pick.framePath : null;
                  var spaceParts = [];
                  if (fp2) { for (var fi = 0; fi < fp2.length; fi++) {
                    var seg = fp2[fi];
                    var mName = seg.match(/^iframe\\[name=["']([^"']+)["']\\]$/);
                    var mId = seg.match(/^#([\\w-]+)$/);
                    spaceParts.push(mName ? ('frame:' + mName[1]) : (mId ? ('frame:' + mId[1]) : seg));
                  } }
                  if (shadowHosts.length) {
                    for (var si = shadowHosts.length - 1; si >= 0; si--) {
                      spaceParts.push('shadow:' + shadowHosts[si]);
                    }
                  }
                  pick.space = (spaceParts.length ? spaceParts.join('>') : 'main');
                } catch (e) {}
                // 【关键修复"面板勾选 alert/弹窗元素只生成 click"】
                // 真实点击触发 dialog/popup 时由 Java onDialog/onPopup 回写标记；但"面板勾选扫描候选"不经
                // 真实点击，无法获得标记 → 生成 step 只是裸 click。此处启发式识别会触发 dialog / 弹窗的元素，
                // 使扫描/勾选也能带上 dialog/popup 标记，封装时生成 acceptAlert/switchToNewPage 而非裸 click。
                //   · dialog：元素任一 on* 事件属性源码含 alert( / confirm( / prompt( → 标记 dialog，
                //     alert 默认 accept、confirm/prompt 默认 dismiss（与 Java onDialog 语义一致）；
                //   · popup：a[target=_blank]（新标签）或任一 on* 事件属性含 window.open( → 标记 popup。
                try {
                  if (!pick.dialog && !pick.popup) {
                    var __evtSrc = '';
                    var __elAttrs = el.attributes;
                    if (__elAttrs) {
                      for (var __ai = 0; __ai < __elAttrs.length; __ai++) {
                        var __an = __elAttrs[__ai].name || '';
                        if (__an.indexOf('on') === 0) __evtSrc += ' ' + (__elAttrs[__ai].value || '');
                      }
                    }
                    if (__evtSrc) {
                      var __elc = __evtSrc.toLowerCase();
                      if (__elc.indexOf('alert(') !== -1 || __elc.indexOf('confirm(') !== -1 || __elc.indexOf('prompt(') !== -1) {
                        pick.dialog = true;
                        pick.dialogType = (__elc.indexOf('confirm(') !== -1) ? 'confirm'
                                : (__elc.indexOf('prompt(') !== -1) ? 'prompt' : 'alert';
                        pick.dialogAction = (pick.dialogType === 'confirm' || pick.dialogType === 'prompt') ? 'dismiss' : 'accept';
                      }
                      if (__elc.indexOf('window.open(') !== -1) pick.popup = true;
                    }
                  }
                  var __pickTag = (el.tagName || '').toLowerCase();
                  if (!pick.popup && __pickTag === 'a') {
                    var __aTgt = (el.getAttribute && el.getAttribute('target')) || '';
                    if (__aTgt && __aTgt.charAt(0) === '_' && __aTgt.toLowerCase() !== '_self') pick.popup = true;
                  }
                } catch (e) {}
                return pick;
              }
              function __attachIndex(pick, el) {
                try {
                  var ms = __matchingElements(pick);
                  var idx = ms.indexOf(el);
                  if (idx >= 0) { pick.count = ms.length; pick.index = idx; }
                  else if (ms.length <= 1) { pick.count = 1; pick.index = 0; }
                } catch (e) {}
                // 统一在此做状态/iframe 路径富化（所有策略都会经过），保证 role 策略也不再漏掉。
                try { __enrichState(pick, el); } catch (e) {}
                return pick;
              }
              window.__computePick = function(t) {
                // ① 重定位（对齐 recorder retarget）：label → 控件；向上找交互角色祖先
                // originalIsLabel 标记"用户点的是 <label> 本身"而非直接点控件——
                // 点击 label 时走 getByLabel 策略（定位到其关联控件），点击控件本身时跳过本分支，
                // 改为 role+name/placeholder 等控件-centric 定位，避免"点输入框却生成 label 策略"的错位。
                var originalIsLabel = !!(t && t.tagName === 'LABEL');
                var el = resolveLabel(t);
                var cur = t, guard = 0;
                while (cur && guard++ < 5) {
                  var node = resolveLabel(cur);
                  if (INTERACTIVE_ROLES[(getRole(node) || '').toLowerCase()]) {
                    // 区域扫描时，重定位上界必须是"区域根内部"的交互角色：
                    // 若向上到达区域根本身（整个区域容器）或其之上的祖先，则停止重定位，
                    // 保留当前 el（target 自身或其下层最近交互角色），避免把"整个区域"当成一个定位。
                    if (window.__roleScanRoot && (node === window.__roleScanRoot || !window.__roleScanRoot.contains(node))) break;
                    el = node; break;
                  }
                  cur = cur.parentElement;
                }
                window.__lastPickEl = el;
                var tag = (el.tagName || '').toLowerCase();
                // 对齐 page.pause() 的 frameLocator 录制：计算元素所在 iframe 的嵌套路径（自顶向下）。
                // 每帧优先取 name / id / 稳定 css 选择器；主框架（window.self === window.top）返回空数组。
                function __framePathOf() {
                  var path = [];
                  try {
                    var w = window;
                    while (w && w !== w.top) {
                      // 取当前层 iframe 的选择器：优先 frameElement 的 name / id，
                      // 若访问 frameElement 受限（如 file:// 下跨源 SecurityError）则退化为当前 frame 的 window.name。
                      var sel = null;
                      try {
                        var fe = w.frameElement;
                        if (fe) {
                          var nm = fe.getAttribute && fe.getAttribute('name');
                          if (nm && nm.trim()) sel = 'iframe[name="' + nm.trim() + '"]';
                          if (!sel) {
                            var fid = fe.getAttribute && fe.getAttribute('id');
                            if (isStableId(fid)) sel = '#' + fid;
                          }
                          if (!sel) {
                            // 无 name / 无稳定 id：用 src 片段作为稳定标签（供 page.frame 兜底 + CSS frameLocator 双通道定位）
                            var fsrc = fe.getAttribute && fe.getAttribute('src');
                            if (fsrc && fsrc.trim()) {
                              var u = fsrc.trim();
                              // 取 url 中可辨识的片段（去掉协议/域名前缀，保留路径+query），提升跨上下文可读性
                              var cut = u.indexOf('//');
                              var frag = cut >= 0 ? u.slice(u.indexOf('/', cut + 2)) : u;
                              sel = 'iframe[src*="' + frag + '"]';
                            }
                          }
                          if (!sel) {
                            var p = fe.parentElement;
                            var s = 'iframe';
                            if (p) {
                              var same = [];
                              for (var c = p.firstElementChild; c; c = c.nextElementSibling) {
                                if (c.tagName === fe.tagName) same.push(c);
                              }
                              if (same.length > 1) s += ':nth-of-type(' + (same.indexOf(fe) + 1) + ')';
                            }
                            sel = s;
                          }
                        }
                      } catch (feErr) { /* frameElement 访问受限，下面用 window.name 兜底 */ }
                      if (!sel) {
                        // 退化：用 frame 自身的 window.name（file:// / 跨源下仍可访问，不受 frameElement SecurityError 限制）
                        try { if (w.name && w.name.trim()) sel = 'iframe[name="' + w.name.trim() + '"]'; } catch (_) {}
                      }
                      if (!sel) sel = 'iframe';
                      path.unshift(sel);
                      w = w.parent;
                    }
                  } catch (e) { path = []; }
                  return path;
                }
                function done(o) {
                  o.tag = tag;
                  o.text = ownVisibleText(el).slice(0, 120);
                  // 状态/iframe 路径富化统一由 __enrichState 完成（__attachIndex 与此处共用），
                  // 保证所有策略（含 role 直出路径）都能带上 ARIA 状态、checked、framePath 等。
                  try { __enrichState(o, el); } catch (e) {}
                  // 可见文本类语义策略（text/altText/title/placeholder/label）做 NLS 反查：
                  // 命中则仅带 key（生成 @RoleElement(key=...)），未命中则字面文本。
                  // 注意：key-only 在运行时统一按 getByText(key 解析) 兜底（用户已接受该降级）。
                  // role 策略在上方已自行反查，不走这里。
                  var KEY_ONLY = { text:1, altText:1, title:1, placeholder:1, label:1 };
                  if (KEY_ONLY[o.strategy]) {
                    var ki = nlsKeyInfo(o.name);
                    o.key = ki.key;
                    o.matched = !!(o.key);
                    // 非精确命中（前缀/子串/模板）：运行时须按子串匹配才能定位，
                    // 否则按钮文案 "Log out" 命中短标签 key "Log" 时精确匹配会找不到。
                    o.cleaned = !ki.exact;
                  }
                  return __attachIndex(o, el);
                }
                // JS 侧转义选择器值（与 Java 侧 escapeSelectorValue 语义一致：转义反斜杠与双引号），
                // 供下方 [name=] / [type=] 等 css 属性选择器安全拼接。
                function escapeSelectorValue(s) {
                  return (s == null ? '' : String(s)).replace(new RegExp('\\\\', 'g'), '\\\\\\\\').replace(new RegExp('"', 'g'), '\\\\"');
                }
                // 文本候选可见性判定（对齐 page.pause 的 suitableTextAlternatives：getByText 只匹配可见文本）。
                // 隐藏元素（display:none / visibility:hidden / opacity:0）不产生 text 候选。
                function __isVisibleForText(node) {
                  try {
                    var cs = getComputedStyleSafe(node);
                    if (!cs || cs.display === 'none' || cs.visibility === 'hidden'
                        || parseFloat(cs.opacity || '1') === 0) return false;
                    return true;
                  } catch (e) { return true; }
                }
                // ② 按 pause 打分序出候选（分低者优先）
                // 1. testId（打分 1，最优；本项目扩展 data-testid/-test-id/-test/-qa 同权）
                var testAttrs = ['data-testid','data-test-id','data-test','data-qa'];
                for (var i = 0; i < testAttrs.length; i++) {
                  var tv = el.getAttribute(testAttrs[i]);
                  if (tv && tv.trim()) return done({ strategy:'testid', attr:testAttrs[i], value:tv.trim(), name:tv.trim() });
                }
                // 2. 语义角色 + 可访问名（role+name，打分 100，先于 placeholder/label）
                //    覆盖所有"有意义的" ARIA 角色（button/link/textbox/heading/img/listitem/region 等），
                //    对齐 page.pause 的 getByRole 对全部角色生效；仅 generic/none/presentation 这类
                //    无语义角色走下方 data-i18n / alt / text 兜底。
                var r = (getRole(el) || '').toLowerCase();
                if (r && !NON_ROLE[r]) {
                  var nameInfo = getNameInfo(el);
                  var nm = nameInfo.name;
                  if (nm) {
                    var lvl = (r === 'heading') ? getHeadingLevel(el) : 0;
                    // 元素若带 data-i18n，其属性值即多语言 key——语言无关、比可见文本稳定，
                    // 优先作为 @RoleElement 的 key（运行时仍按 role+name 定位，兼得 i18n 稳定与 role 抗结构）。
                    var i18nAttr = el.getAttribute('data-i18n');
                    if (i18nAttr && i18nAttr.trim()) {
                      return __attachIndex({ strategy:'role', role:r, name:nm, key:i18nAttr.trim(), matched:true,
                        cleaned:false, level:lvl,
                        tag:tag, text: ownVisibleText(el).slice(0, 120) }, el);
                    }
                    // 否则用可见 name 反查 nls key（精确 + 前缀/子串 + 模板），命中则复用真实 key，
                    // 未命中则留空回退字面 name。cleaned 触发条件：
                    //   a) nameInfo.cleaned —— 剔除了装饰性伪元素/描述文本（如 " (opens in a new window)"）；
                    //   b) !nls.exact —— NLS 非精确命中（前缀/子串/模板），运行时须子串匹配才能定位。
                    var nls = nlsKeyInfo(nm);
                    return __attachIndex({ strategy:'role', role:r, name:nm, key:nls.key, matched: !!nls.key,
                      cleaned: nameInfo.cleaned || !nls.exact, level:lvl,
                      tag:tag, text: ownVisibleText(el).slice(0, 120) }, el);
                  }
                  // 有角色无名称：不放行到下方 data-i18n/placeholder/label/alt/text/title/id 等带名候选
                  // （它们都要求有可访问名，本分支已无 name 自然不命中），而是落到下方 step 8.5 的
                  // roleWithoutName(510) 兜底（#id 优先于它，故先过 #id 再 roleWithoutName），与 pause 一致。
                }
                // 2.5 data-i18n 多语言 key（本项目约定）：走到这里说明元素无语义角色（generic 的 span/div 等，
                //     有语义角色的元素已在上方 step 2 走 role+key）。对无角色的纯文本/容器元素，data-i18n 的
                //     属性值即多语言 key——语言无关、比可见文本稳定，生成时转为 @Element("[data-i18n=\"key\"]")
                //     （CSS 属性选择器），无需在 @RoleElement 设专门字段。
                var i18n = el.getAttribute('data-i18n');
                if (i18n && i18n.trim()) return done({ strategy:'i18n', value:i18n.trim(), name:i18n.trim() });
                // 3. placeholder（120；pause 仅对 INPUT/TEXTAREA）
                if (tag === 'input' || tag === 'textarea') {
                  var ph = el.getAttribute('placeholder');
                  if (ph && ph.trim()) return done({ strategy:'placeholder', attr:'placeholder', value:ph.trim(), name:ph.trim() });
                }
                // 4. 原生关联 label → getByLabel（140，置于 role+name 之后，与 pause 一致）
                //    仅当用户"直接点击 <label> 本身"时生效（originalIsLabel）；
                //    此时 getByLabel 会定位到该 label 关联的控件，符合"点标签=操作控件"的直觉。
                //    若点击的是表单控件本身，则跳过本分支（输入控件已在上方 role+name 定位），
                //    避免"点了输入框却生成 label 策略"的错位。
                if (originalIsLabel) {
                  // 若 label 关联的控件本身是 checkbox / radio，则改用控件自身的 role 策略，
                  // 使其与"直接点击该 checkbox/radio"生成完全相同的 _sigKey（role:checkbox:name#0），
                  // 在拾取去重处天然合并为同一条，避免 step 里同时出现 label.click() 与 chk.setChecked() 的重复。
                  var __ctrlRole = (getRole(el) || '').toLowerCase();
                  if (__ctrlRole === 'checkbox' || __ctrlRole === 'radio') {
                    var __cb = getNameInfo(el);
                    if (__cb && __cb.name) return done({ strategy:'role', role:__ctrlRole, name:__cb.name, nlsKey:__cb.nlsKey, resolvedKey:__cb.resolvedKey });
                  }
                  var lbl = labelTextOf(el);
                  if (lbl) return done({ strategy:'label', name:lbl });
                }
                // 5. alt（160；pause 中限定 APPLET/AREA/IMG/INPUT，先于 text）
                if (tag === 'img' || tag === 'area' || tag === 'input') {
                  var alt = el.getAttribute('alt');
                  if (alt && alt.trim()) return done({ strategy:'altText', attr:'alt', value:alt.trim(), name:alt.trim() });
                }
                // 6. 可见文本（180；对齐 page.pause 的 getByText 稳定性取舍）：
                //    pause 中先试子串/前缀（非 exact，score 180）再试精确（exact，score 185）。
                //    对齐 pause 的 suitableTextAlternatives：隐藏元素（display:none / visibility:hidden /
                //    opacity:0）不产生 text 候选，让位给下方 title / id / css（getByText 本就只匹配可见文本）。
                //    注：可见性判定复用 getComputedStyleSafe（与拾取主流程同一实现），避免对隐藏元素的文本误生成。
                //    本项目先出非 exact 候选（稳定容忍文案微调：去掉两端空白/装饰、按归一化 name 反查，
                //    命中则运行时子串匹配），再出精确候选。
                //    · 文案 ≤80 字符：精确匹配（exact:true），语义清晰且稳定。
                //    · 长文案（>80）：跳过 text 策略，长文本作定位锚点极易随文案/排版变化而失效，
                //      让位给更稳定的 title / id / css（对齐 page.pause 的"优先最短稳定选择器"原则），
                //      从源头避免生成脆弱、易碎的整段文本定位器。
                if (__isVisibleForText(el)) {
                  var ot = ownVisibleText(el);
                  if (ot && ot.length <= 80) {
                    // 6a. 非 exact 优先：归一化可见文本反查 nls（前缀/子串/模板命中即非精确），
                    //     让运行时按子串匹配，容忍文案微调。done() 内 KEY_ONLY 会据 nlsKeyInfo(name)
                    //     重算 cleaned（= !exact），非精确命中即生成 @RoleElement(text=..., exact=false)。
                    var otNls = nlsKeyInfo(ot);
                    if (otNls.key && !otNls.exact) {
                      return done({ strategy:'text', name:ot, key:otNls.key, matched:true });
                    }
                    // 6b. 精确匹配：直接用可见文本（done 内 KEY_ONLY 精确命中 → cleaned=false → exact 默认）。
                    return done({ strategy:'text', name:ot });
                  }
                }
                // 7. title（200）
                var title = el.getAttribute('title');
                if (title && title.trim()) return done({ strategy:'title', attr:'title', value:title.trim(), name:title.trim() });
                // 8. 稳定 id（500）
                var id = el.getAttribute('id');
                if (isStableId(id)) return done({ strategy:'id', id:id });
                // 8.5 无名称的语义角色（roleWithoutName，510，对齐 page.pause）：
                //     仅当元素有 ARIA 角色（NON_ROLE 之外）但无可见名/aria-label 时命中（如
                //     <div role="listitem"> 无文本、role="img" 无 alt 的纯结构/装饰元素）。
                //     排在 #id(500) 之后、[name=](520) 之前，与 pause 一致。生成 @RoleElement(role=...) 无 name。
                if (r && !NON_ROLE[r]) return done({ strategy:'role', role:r });
                // 8.6 属性级 css 候选（对齐 page.pause 的 score 520/521 兜底层级，
                //     排在 roleWithoutName 之后、完整 css 路径之前，使 [name=] / input[type=] 比自动长 css 路径更稳定）：
                //   · [name=...]（520）：任意带 name 属性的元素。
                //   · input/textarea/select 的 type（520/521）：如 input[type=search]。
                // （注：PW 的 tag 级 css（530）在歧义时会被更高分的完整 css 路径覆盖；本项目直接保留
                //   下方的 cssPathOf 精确长路径作为最终兜底，不退化成裸 tag 选择器，以保持定位唯一性。）
                var nmAttr = el.getAttribute('name');
                if (nmAttr && nmAttr.trim()) return done({ strategy:'css', css: tag + '[name="' + escapeSelectorValue(nmAttr.trim()) + '"]', needsReview:false });
                if (tag === 'input' || tag === 'textarea' || tag === 'select') {
                  var ty = el.getAttribute('type');
                  if (ty && ty.trim()) return done({ strategy:'css', css: tag + '[type="' + escapeSelectorValue(ty.trim()) + '"]', needsReview:false });
                }
                // 9. 完整 css 路径兜底（needsReview=true）。
                return done({ strategy:'css', css: cssPathOf(el), needsReview:true });
              };

              // 定位器签名（与 Java 端 RoleElementPageGenerator.locatorKey 规则一致）：
              // role 按 role+name/key；id/css 按选择器；其余语义策略按 strategy+name。
              // 同一签名重复点击不入列、计数不增，避免生成端才去重导致"点了但计数不变"的困惑。
              window.__pickSig = function(pick) {
                if (!pick) return '';
                var base;
                if (pick.strategy === 'role') {
                  base = 'role:' + (pick.role || '') + ':' + (pick.key || pick.name || '');
                } else if (pick.strategy === 'i18n') base = 'i18n:' + (pick.name || '');
                else if (pick.strategy === 'id') base = 'id:' + (pick.id || '').replace(/^#/, '');
                else if (pick.strategy === 'css') base = 'css:' + (pick.css || '');
                else base = pick.strategy + ':' + (pick.name || '');
                // 一组同定位器元素（如页面上两条 role/name 完全相同的 link）按序号区分签名，
                // 使 nth(0)/nth(1) 均可独立拾取、互不去重——对齐 page.pause() 用 nth() 消歧的语义。
                // __attachIndex 已保证 index 恒被赋值（唯一匹配时为 0），签名因此稳定不跳变；
                // 若此处退回"有多个匹配才加序号"，同一元素会在 'X' 与 'X#0' 间摇摆而被重复收录。
                // 页面字段仍按不含序号的 locatorKey 归一为同一个 PageElement。
                if (pick.index != null && pick.index >= 0) base += '#' + pick.index;
                return base;
              };
              // 去重复合键：签名 + 所属 Page 类。关键修复：不同页面上 role/name 完全相同的"共用元素"
              // （如各页都有的 Close / Next 按钮、页眉页脚链接）原本只按签名去重，会被误判为重复而被丢弃
              // （表现：跳到新页面，有些元素没抓到 / 关弹窗后弹窗元素丢失）。把 _pageClass 纳入去重键后，
              // 同一页内仍按签名去重（同一元素重复点只保留一份），但跨页同名元素各自独立保留。
              window.__sigKey = function(pick) {
                // 已固化过键则直接复用：键一旦生成就是该元素的永久身份，绝不因"当前在哪个页面"而改变。
                // 跨页同步下来的元素（syncPanelToBrowser 写入 _sigKey）在别的页面被重算时，会因
                // _pageClass 缺失退化到 location 兜底而算出新键，造成同一元素重复收录——此处提前返回即可根除。
                if (pick && pick._sigKey) return pick._sigKey;
                var pageKey = (pick && pick._pageClass) || '';
                // 页面类（_pageClass）就是页面身份，稳定可靠：有它时去重键只用 [pickSig, pageClass]，
                // 不再附加 URL。此前无条件把 location.href 拼进键，导致"URL change 后再回到本页"时
                // 因 query/hash 抖动使同一元素的键改变、去重失配 → role 元素整组重复收录（本次 bug 根因）。
                // 仅当 _pageClass 尚未派生（整页跳转瞬间为空）才用规范化路径（origin+pathname，去掉 query/hash）
                // 兜底区分跨页同名元素，避免旧页签名误判；query/hash 抖动被归一，不再重复。
                if (!pageKey) {
                  try { if (location) pageKey = (location.origin || '') + (location.pathname || ''); } catch (e) {}
                }
                return JSON.stringify([window.__pickSig(pick) || '', pageKey]);
              };
              // 落盘最新拾取态到 localStorage：用于"点击会触发整页跳转的元素"场景——
              // 跳转瞬间 onFrameNavigated 恢复用的 Java 快照由主循环每 ~1s 刷新，可能来不及包含刚刚这次点击，
              // 导致刚点的元素在 window 重建后被旧快照覆盖丢失（表现：点了"返回登录"按钮却没被拾取上）。
              // pagehide/beforeunload 时把最新态写入 localStorage（同域整页跳转间 localStorage 保留），
              // 新窗口 onFrameNavigated 再据此合并补回最新点击的元素。
              // 即时落盘（供 pagehide/beforeunload 调用，不被节流，确保整页跳转瞬间 localStorage 是最新态）。
              function __persistNow() {
                try {
                  var payload = JSON.stringify({
                    picks: window.__rolePicks || [],
                    steps: window.__steps || [],
                    currentStep: window.__currentStep || [],
                    sigs: window.__rolePickSigs || {}
                  });
                  // O3：内容未变则跳过落盘，避免拾取静止时仍每 ~120ms 做一次全量序列化/写 localStorage
                  // （随元素增多 O(n²) 的卡顿来源之一）。pagehide/beforeunload 触发的即时落盘若内容相同也无副作用。
                  if (window.__lastPersistJson === payload) return;
                  window.__lastPersistJson = payload;
                  localStorage.setItem('__rolePickState', payload);
                } catch (e) {}
                window.__lastPersist = Date.now();
              }
              // 企业级优化：每次点击都全量序列化 localStorage（随拾取增多 O(n²)）是拾取卡顿来源之一。
              // 改为节流——相邻高频拾取最多每 ~120ms 落盘一次；但 pagehide/beforeunload 走 __persistNow 即时落盘，
              // 保证"点击触发整页跳转"前最后一击一定已写入 localStorage（onFrameNavigated 合并恢复依赖它，不丢元素）。
              window.__persistPickState = function() {
                var now = Date.now();
                if (now - (window.__lastPersist || 0) < 120) {
                  if (!window.__persistTimer) {
                    window.__persistTimer = setTimeout(function() {
                      window.__persistTimer = null; __persistNow();
                    }, 130);
                  }
                  return;
                }
                __persistNow();
              };
              if (!window.__rolePersistHooked) {
                window.__rolePersistHooked = true;
                window.addEventListener('pagehide', __persistNow);
                window.addEventListener('beforeunload', __persistNow);
              }
              // 每次注入都从既有 picks 重建签名表，保证与 __rolePicks 严格同步；
              // 去重键改为"签名+页面类"（__sigKey），跨页同名元素不再互相误删。
              window.__rolePickerLib = true;
              }
              window.__rolePickSigs = {};
              window.__sigToPick = {};
              (window.__rolePicks || []).forEach(function(p) {
                var k = window.__sigKey(p);
                // 同 __recordPick：重建签名表时也把键固化回 pick，
                // 使从快照/localStorage 恢复进来的旧元素同样拥有稳定身份，后续合并不再重算出新键。
                if (k && p && !p._sigKey) p._sigKey = k;
                if (k) window.__rolePickSigs[k] = true;
                var s = window.__pickSig(p);
                if (s) window.__sigToPick[s] = p;
              });
              // 兜底压缩：window.__rolePicks 一旦因某次"sigs 清空后、异步合并前"的竞态残留重复项，
              // 会永久累积（重建签名表只会补键、不会删数组里的副本）。这里读取前按权威键 __mergeKey
              // 再做一次整组去重，使数组不再随时间成倍膨胀，localStorage 落盘与 pageshow 恢复也就不会越滚越大。
              (function(){
                if (typeof window.__mergeKey !== 'function') { window.__mergeKey = function(p){ try{
                  if (!p) return ''; if (p._sigKey) return p._sigKey;
                  if (typeof window.__sigKey === 'function') return window.__sigKey(p);
                  var pk = p._pageClass || ''; if (!pk) { try { pk = (location.origin||'') + (location.pathname||''); } catch(e){} }
                  return JSON.stringify([p._sig || '', pk]);
                }catch(e){ return ''; } }; }
                var seen = {}; var out = [];
                (window.__rolePicks||[]).forEach(function(p){ try{ var k = window.__mergeKey(p);
                  if (!k) { out.push(p); return; } if (seen[k]) return; seen[k]=true; out.push(p);
                }catch(e){ out.push(p); } });
                window.__rolePicks = out;
              })();
              // 一次性注册输入监听：点击可输入元素后键入的内容回写到对应 pick 的 value，
              // 使生成的 step 带上真实文本（对齐 page.pause() 的 fill 录制）。
              if (!window.__roleInputHooked) {
                window.__roleInputHooked = true;
                document.addEventListener('input', function(ev) {
                  var p = window.__activeInputPick, elc = window.__activeInputEl;
                  if (p && ev.target && ev.target === elc) {
                    p.value = editableValue(ev.target);
                    var s = document.getElementById('__roleStatus');
                    if (s) {
                      var base = s.textContent.replace(/（已输入：.*）/g, '');
                      s.textContent = base + (p.value ? '（已输入：' + p.value + '）' : '');
                    }
                    // 【关键修复"fill 未跟随输入"（尤其 iframe 内输入框）】
                    // iframe 内点击输入框时，__activeInputPick 是 iframe 上下文内的 pick 对象；用户随后输入，
                    // 此处只更新了 iframe 内 p.value，但顶层 __steps / Java 内存态里的拷贝仍是点击时的空值，
                    // 生成 fill("") 而非真实文本。
                    // 修复：输入变化的瞬间，把携带新 value 的 pick 立即重传 Java（__roleOnPick 幂等覆盖）
                    // 并 postMessage 上送顶层（顶层按 _sigKey 去重、覆盖 value），确保 stop 生成拿到真实输入。
                    try {
                      if (typeof window.__sigKey === 'function') p._sigKey = window.__sigKey(p);
                      if (typeof window.__roleOnPick === 'function') window.__roleOnPick(JSON.stringify(p));
                      try { console.log('__roleOnPick::' + JSON.stringify(p)); } catch (_) {}
                      if (window.self !== window.top) {
                        try { if (window.top && window.top !== window.self) window.top.postMessage({ __rolePickMsg: true, pick: p, __fromFrame: true }, '*'); } catch (e) {}
                      }
                    } catch (_e) {}
                  }
                }, true);
              }
              // 实时悬停高亮 + 悬停拾取（hover）模式：鼠标移到元素上即时描边，开启"悬停拾取"后
              // 停留在元素上约 0.45s 即记录为 hover 动作（生成 .hover()），与点击拾取（click）互补。
              var __hoverBox = null;
              function __ensureHoverBox() {
                if (__hoverBox) return __hoverBox;
                __hoverBox = document.createElement('div');
                __hoverBox.id = '__roleHoverBox';
                __hoverBox.style.cssText = 'position:fixed;pointer-events:none;z-index:2147483646;'
                  + 'border:2px solid #29b6f6;background:rgba(41,182,246,0.12);'
                  + 'box-shadow:0 0 0 1px rgba(0,0,0,.35);border-radius:2px;display:none;';
                (document.body || document.documentElement).appendChild(__hoverBox);
                return __hoverBox;
              }
              function __showHoverBox(el) {
                var b = __ensureHoverBox();
                if (!el || !el.getBoundingClientRect) { b.style.display = 'none'; return; }
                var r = el.getBoundingClientRect();
                if (!r.width && !r.height) { b.style.display = 'none'; return; }
                b.style.display = 'block';
                b.style.left = r.left + 'px'; b.style.top = r.top + 'px';
                b.style.width = r.width + 'px'; b.style.height = r.height + 'px';
              }
              var __hoverTimer = null, __hoverTarget = null;
              // 共享拾取逻辑：isHover=true 记录为 hover 动作，否则 click 动作；多页面标签与历史 click 行为一致。
              window.__recordPick = function(target, isHover) {
                // 性能/正确性：仅拾取激活态才记录（点击/悬停），避免 stop 后残留监听或导航瞬间
                // 跑完整 role/name 计算并把元素误回传 Java；非激活态直接返回。
                if (!window.__rolePickActive) return null;
                // 【关键修复"区域选择模式下点击不触发拾取"】
                // 区域扫描态（__scanMode==='region'，用户正在点选业务区域）下，点击的语义是"选中区域并
                // 扫描其子元素"，而非"拾取被点击的元素"。若点击拾取监听未被完全摘除，点击 #sec-role 等
                // 容器会被 __recordPick 记录成整块 text 定位（"1. 角色与状态 提交 禁用按钮..."），混入
                // 页面元素/页面类。此处：区域选择态且【非扫描中】时点击直接不记录——仅当 __roleScanPage
                // 扫描时（__scanMode 被覆写为 'page' 且 __scanning=true）才记录区域内子元素。
                if (window.__scanMode === 'region' && !window.__scanning) return null;
                var t = target;
                if (!t) return null;
                // 录制根容器约束：若指定了 window.__rolePickRoot，只有落在该选择器内（含其自身）
                // 的元素才被录制；leftmenu / topbar 等全局导航区域在范围外，自然不被捕获。
                // 对整页 scan 与点击/悬停/双击录制统一生效。选择器无效（querySelector 返回 null）
                // 时退化成整页录制，避免误杀全部拾取。
                if (window.__rolePickRoot) {
                  try {
                    var __rootEl = document.querySelector(window.__rolePickRoot);
                    if (__rootEl && !(t === __rootEl || __rootEl.contains(t))) {
                      // 根外点击（多为 leftmenu/topbar 等全局导航）：面板状态条临时提示，2 秒后恢复计数。
                      try {
                        var __st = document.getElementById('__roleStatus');
                        if (__st) {
                          __st.textContent = '⚠ 点击在录制根容器（' + window.__rolePickRoot + '）之外，已忽略（导航区不录制）';
                          if (window.__roleStatusTimer) clearTimeout(window.__roleStatusTimer);
                          window.__roleStatusTimer = setTimeout(function() {
                            if (__st) __st.textContent = 'RoleElement Picker：已拾取 '
                              + (window.__rolePicks ? window.__rolePicks.length : 0) + ' 个，按 ESC 结束';
                          }, 2000);
                        }
                      } catch (e2) { /* 面板不存在时忽略 */ }
                      return null;
                    }
                  } catch (e) { /* 选择器非法时忽略约束 */ }
                }
                var pick = window.__computePick(t);
                // 剔除"整页级骨架 css 定位"：css 选择器以 body / html 开头（cssPathOf 在 5 层内遇不到
                // stable id，只能生成 "body > div:nth-of-type(...)" / "html > body > ..." 这种整页级兜底
                // 路径，无业务价值、随 DOM 微调即失效）。无论手动拾取还是扫描态都拦截——这类选择器本就不该
                // 进入拾取集（有语义角色/稳定 id 的元素不会落到这里），从源头保证面板列表与生成的页面类都不含它。
                // 补充：无锚点纯位置链 css（div:nth-of-type(3) > div > ...）只在**区域扫描态**拦截。
                // 区域扫描无差别遍历区域内全部元素，是这类噪音的唯一来源；手动点选保持 pause 语义，
                // 用户主动点的元素即便只能退化到 css 兜底也照常记录。
                // 区域态判定用 __roleScanRoot 而非 __scanMode：__roleScanPage 入口会把 __scanMode
                // 无条件覆写为 'page'（区域/整页共用该函数），此处读不到 'region'；而 __roleScanRoot
                // 仅在区域扫描期间被赋为区域根元素，整页扫描与非扫描态均为 null，是可靠的区域态标志。
                if (pick && pick.strategy === 'css' && pick.css
                    && (window.__roleScanRoot
                        ? (pick.css.indexOf('body') !== -1 || pick.css.indexOf('html') !== -1
                           || (typeof __isNthOnlyCss === 'function' && __isNthOnlyCss(pick.css)))
                        : (pick.css.indexOf('body') === 0 || pick.css.indexOf('html') === 0))) {
                  try { console.log('[rolePick][skip body/html/nth-css] css=' + pick.css); } catch (e2) {}
                  return null;
                }
                pick._pageClass = window.__rolePageName || '';
                // 记录拾取发生时所在 frame 的 URL（主框架 _pageClass 已用类名，无需；iframe 内 _pageClass
                // 为空，用 _frameUrl 供 Java console 通道回补 framePath 时按 URL 匹配 page.frames() 定位 frame）。
                // origin+pathname 与 __sigKey 兜底口径一致，去 query/hash 保持跨导航稳定；不影响 _pageClass 语义，
                // 故 iframe 元素仍归属主页面类（按 framePath 切换），不会误分到独立 iframe 页面类。
                try { pick._frameUrl = (location.origin || '') + (location.pathname || ''); } catch (e) { pick._frameUrl = ''; }
                // 页面实例序号：同一 pageClass 被打开多次（同页多标签）时区分不同实例。
                // 维护 window.__pageInstanceSeq 映射（pageClass -> 已打开实例数）；每次进入/打开一个页面
                // （__rolePageName 被赋值，见下方各种 onPopup/onLoad 钩子）时该映射 +1 并固化为该页实例号。
                // 同一页面内的多次拾取共享同一实例号（不重复 +1）。
                try {
                  window.__pageInstanceSeq = window.__pageInstanceSeq || {};
                  var __pc = pick._pageClass || '__main';
                  if (window.__currentPageInstance == null || window.__currentPageInstance.page !== __pc) {
                    window.__pageInstanceSeq[__pc] = (window.__pageInstanceSeq[__pc] || 0) + 1;
                    window.__currentPageInstance = { page: __pc, seq: window.__pageInstanceSeq[__pc] };
                  }
                  pick._pageInstanceId = window.__currentPageInstance.seq;
                } catch (e) { pick._pageInstanceId = 1; }
                try { pick._sig = window.__pickSig ? window.__pickSig(pick) : null; } catch (e) { pick._sig = null; }
                if (window.__lastPickEl && isEditable(window.__lastPickEl)) {
                  pick.value = editableValue(window.__lastPickEl);
                  window.__activeInputPick = pick;
                  window.__activeInputEl = window.__lastPickEl;
                } else {
                  window.__activeInputPick = null;
                  window.__activeInputEl = null;
                }
                pick.hover = !!isHover;
                // 对齐 page.pause() 的 source.dragTo(target)：拖拽手势（mousedown 源 → mouseup 目标）在 mouseup
                // 时触发本函数记录「源」元素；此时 window.__dragSrcEl/__dragDstKey 已由拖拽监听置好，
                // 把目标元素的定位签名挂到源 pick 上，生成端据 keyToField 反查目标字段并输出 dragTo。
                if (window.__dragSrcEl && el === window.__dragSrcEl && window.__dragDstKey) {
                  pick.dragDstKey = window.__dragDstKey;
                }
                // —— 下拉选择 / 复选框 状态捕获（对齐 page.pause() 的 selectOption 与 check/uncheck 信号）——
                // combobox/listbox（含原生 <select> 与自定义列表）：拾取时读取当前选中项，
                // 生成 step 时输出 selectByVisibleText("选项文本")（优先）/ selectByValue(...)。
                var pRole = (pick.role || '').toLowerCase();
                if (pRole === 'combobox' || pRole === 'listbox') {
                  var sel = window.__lastPickEl;
                  if (sel) {
                    try {
                      var optText = null, optVal = null;
                      if (sel.tagName === 'SELECT') {
                        var o = sel.selectedOptions && sel.selectedOptions.length ? sel.selectedOptions[0] : null;
                        if (o) { optText = (o.textContent || '').trim(); optVal = o.value; }
                      } else {
                        var selEl = sel.querySelector('[aria-selected="true"]');
                        if (selEl) optText = (selEl.getAttribute('aria-label') || selEl.textContent || '').trim();
                      }
                      if (optText != null) {
                        pick.select = true;
                        pick.optionText = optText;
                        pick.optionValue = optVal;
                      }
                    } catch (e) {}
                    // change 事件触发时（用户在下拉中选了某一项）更新选中态并回传，对齐 codegen 的 selectOption 信号。
                    try {
                      if (!sel.__pwSelBound) {
                        sel.__pwSelBound = true;
                        sel.addEventListener('change', function() {
                          try {
                            var o = sel.selectedOptions && sel.selectedOptions.length ? sel.selectedOptions[0] : null;
                            if (!o) return;
                            pick.select = true;
                            pick.optionText = (o.textContent || '').trim();
                            pick.optionValue = o.value;
                            if (typeof window.__sigKey === 'function') pick._sigKey = window.__sigKey(pick);
                            if (typeof window.__roleOnPick === 'function') window.__roleOnPick(JSON.stringify(pick));
                            try { console.log('__roleOnPick::' + JSON.stringify(pick)); } catch (_) {}
                          } catch (e) {}
                        }, true);
                      }
                    } catch (e) {}
                  }
                }
                // checkbox：记录当前勾选状态，生成 step 时按已勾选走 check()、未勾选走 uncheck()（对齐 codegen）。
                // 修复：原 var cb = pick._el 中 pick._el 从未被赋值（真实点击元素挂在 el 上，
                // 其副本即 window.__lastPickEl），导致原生 checkbox/radio 的 checked 始终为 undefined。
                // 改用点击元素的真实引用 el；并补充 ARIA checkbox/switch 的 aria-checked 采集。
                try {
                  if (pRole === 'checkbox' || pRole === 'radio' || pRole === 'switch'
                      || pRole === 'menuitemcheckbox' || pRole === 'menuitemradio' || pRole === 'treeitem') {
                    var cb = el;
                    if (cb.type === 'checkbox' || cb.type === 'radio') {
                      pick.checked = !!cb.checked;
                    } else if (cb.hasAttribute('aria-checked')) {
                      var _av = cb.getAttribute('aria-checked');
                      pick.checked = (_av === 'true' || _av === 'mixed');
                    }
                  }
                } catch (e) {}
                var sig = window.__pickSig(pick);
                var key = window.__sigKey(pick);
                var dup = key && window.__rolePickSigs[key];
                if (!dup) {
                  if (key) window.__rolePickSigs[key] = true;
                  // 【关键】入库瞬间把去重键固化到 pick 上，使其成为该元素的永久身份。
                  // 否则 pick 进入 __rolePicks 时不带 _sigKey，后续任何一次合并（load/pageshow 自愈、
                  // 导航恢复、localStorage 回灌）都会在【新页面上下文】里重算键：此时 _pageClass 可能
                  // 尚未派生而退化到 location 兜底，算出的键与登记在 __rolePickSigs 里的旧键不等
                  // → 判为新元素再次 push，每轮导航多一份（实测 4→5→6 次）。固化后键恒定，重复根除。
                  if (key) pick._sigKey = key;
                  window.__rolePicks.push(pick);
                  // 扫描态下抑制逐元素控制台日志：避免 N 次 console 事件触发 Java onConsoleMessage 监听器空转。
                  if (!window.__scanning) {
                    try { console.log('[rolePick][push] len=' + window.__rolePicks.length + ' render=' + (typeof window.__renderPicks)); } catch(_){}
                  }
                  // sig→pick 直接映射，重复点击时 O(1) 定位原 pick（避免线性扫描 __rolePicks）。
                  if (sig) window.__sigToPick[sig] = pick;
                } else {
                  if (!window.__scanning) {
                    try { console.log('[rolePick][dup] key=' + key + ' href=' + (location && location.href)); } catch(_){}
                  }
                  // O(1) 去重定位：用 sig→pick 映射直接取到已存在的 pick，不再遍历整个数组。
                  var existing = sig ? window.__sigToPick[sig] : null;
                  if (existing) {
                    existing.hover = !!isHover;
                    if (window.__lastPickEl && isEditable(window.__lastPickEl)) {
                      window.__activeInputPick = existing;
                      window.__activeInputEl = window.__lastPickEl;
                    }
                  } else {
                    // 兜底：极端情况下映射缺失（如注入重建未同步），退回线性扫描保正确。
                    for (var i = 0; i < window.__rolePicks.length; i++) {
                      if (window.__pickSig(window.__rolePicks[i]) === sig) {
                        window.__rolePicks[i].hover = !!isHover;
                        if (window.__lastPickEl && isEditable(window.__lastPickEl)) {
                          window.__activeInputPick = window.__rolePicks[i];
                          window.__activeInputEl = window.__lastPickEl;
                        }
                        break;
                      }
                    }
                  }
                }
                var __now = Date.now();
                var __rapid = sig && sig === window.__lastPickSig && (__now - (window.__lastPickTs || 0)) < 500;
                window.__lastPickSig = sig;
                window.__lastPickTs = __now;
                // 选择模型（替代原"所有拾取自动并入 currentStep = 一个 step"）：
                //   · 整页扫描期间（window.__scanning 为 true）的元素只是"候选"，不直接入选当前 step，
                //     由用户在面板上勾选后再「封装为步骤」；
                //   · 页面上的实时点选（非扫描、非重复）视为用户主动拾取，自动入选当前 step（选择集）；
                //   · 重复拾取（dup）不重复入选，避免 step 内出现重复元素。
                // 【关键修复"用户点击扫描候选不进 step"】
                // 原条件含 !dup：整页扫描会把候选 push 进 __rolePickSigs（回传 javaPickBySig），扫描后
                // 用户点击这些候选元素时 dup=true，被误判为"重复点击"而拒绝入选 step → 用户点的元素
                // 不出现在步骤里。dup 本意是防"同一元素重复入选"；应改判"该元素是否已在当前 step 中"
                // 而非"是否已收录过（扫描也算收录）"。故：非扫描态点击，只要元素不在当前 __currentStep
                // 就入选 step（用户主动点击扫描候选 = 明确选择）；已在 step 中的重复点击仍被排除。
                if (window.__currentStep && !window.__scanning && !__rapid) {
                  var __inStep = false;
                  if (dup) {
                    // 仅当元素已在 __rolePickSigs 中才需查 __currentStep 去重；全新元素直接入选。
                    try {
                      for (var __si = 0; __si < window.__currentStep.length; __si++) {
                        if (window.__currentStep[__si]
                            && ((sig && window.__currentStep[__si]._sig === sig)
                                || (key && window.__currentStep[__si]._sigKey === key))) {
                          __inStep = true; break;
                        }
                      }
                    } catch (e) { __inStep = false; }
                  }
                  if (!__inStep) window.__currentStep.push(pick);
                }
                // 修复：__renumberStep / __currentStep 等 UI 辅助函数仅定义在主框架（PANEL_SCRIPT）内。
                // iframe 内拾取时本函数在子 frame 上下文执行，这些函数未定义，直接调用会抛 TypeError 中断后续
                // 回传逻辑（__roleOnPick 绑定回传、__roleOnPick:: 控制台兜底、postMessage 上送），导致
                // 「frame 元素拾取成功（已 push 进子 frame 数组）但 Java 内存态/面板均收不到」的假象。
                // 故全部加 typeof 守卫 + try，确保子 frame 内即便 UI 辅助缺失也不影响元素回传。
                try { if (typeof window.__renumberStep === 'function') window.__renumberStep(); } catch (_) {}
                """;
    private static final String START_SCRIPT_B2 = """

                // 状态外置（对齐 page.pause）：去掉"每次点击全量序列化 localStorage"这一 O(n) 瓶颈，
                // 点击延迟不再随已拾元素增多而变慢。整页跳转前的最后点击由 pagehide/beforeunload 的
                // __persistNow 同步即时落盘兜底（见 __persistPickState 定义），不丢失。
                // —— UI 反馈 + Java 回传（仅在非扫描态执行）——
                // 扫描态（window.__scanning 为 true）下整体跳过：避免对整页每个元素都触发
                //   · __renderPicks() —— 逐元素全量重渲染，N 个元素即 O(N²) 的致命瓶颈；
                //   · __roleOnPick 单次绑定回传 + console.log —— N 次 Java 往返 / 控制台事件洪流。
                // 改为扫描结束统一「一次性渲染 + 批量回传」（见 __roleScanPage 尾部），可访问名/去重等核心逻辑仍照常执行。
                if (!window.__scanning) {
                  var prev = t.style.outline;
                  t.style.outline = dup ? '3px solid #ff9800' : (isHover ? '3px solid #29b6f6' : '3px solid #ffeb3b');
                  // 400ms 后直接清除高亮（而非恢复 prev）：避免快速重复点选同一元素时，
                  // 第二次捕获到的 prev 已是上一次设置的黄色框，setTimeout 又把黄框"恢复"回来，
                  // 造成"选择/封装完成后页面元素上的黄色框始终不消失"的残留问题。
                  setTimeout(function() { try { t.style.outline = ''; t.style.outlineOffset = ''; } catch (e) {} }, 400);
                  var statusEl = document.getElementById('__roleStatus');
                  if (statusEl) {
                    var extra = dup ? '（重复，已忽略）'
                      : ((pick && pick.matched) ? '（key=' + pick.key + '）' : '');
                    if (isHover) extra = '（悬停）' + extra;
                    var stepNo = (window.__steps ? window.__steps.length : 0) + 1;
                    statusEl.textContent =
                      'RoleElement Picker：已拾取 ' + window.__rolePicks.length + ' 个 / 第 ' + stepNo + ' 个 step' + extra + '，按 ESC 结束';
                  }
                  if (window.__renderPicks) window.__renderPicks();
                  // 状态外置（对齐 page.pause）：把单个 pick 经 exposeFunction 事件驱动、零往返、O(1) 回传 Java 内存，
                  // 由 Java 侧持有权威拾取态（javaPickBySig），点击不再依赖浏览器端大数组的全量序列化/持久化。
                  if (typeof window.__roleOnPick === 'function') {
                    try {
                      // 附带浏览器端去重键 __sigKey，使 Java 内存去重粒度与浏览器 __rolePickSigs 完全一致。
                      if (typeof window.__sigKey === 'function') pick._sigKey = window.__sigKey(pick);
                      window.__roleOnPick(JSON.stringify(pick));
                    } catch (e) { try { console.error('[rolePick][onPick-expose-fail] ' + (e && e.message)); } catch(_){} }
                  }
                  // 控制台兜底回传：即使 exposeFunction 因导航/上下文异常失效，Java 的 onConsoleMessage 仍能捕获并落盘
                  // （按 sig 去重，与 exposeFunction 调用幂等）。同时便于排查"二次拾取没反应"。
                  try { console.log('__roleOnPick::' + JSON.stringify(pick)); } catch(_){}
                  // 跨 frame 同步：点击若发生在 iframe 内，顶层主框架可见面板读的是主框架 window.__rolePicks，
                  // 而本 frame 把 pick push 进了自己的 window.__rolePicks（主框架读不到），表现为"内存态增长、面板空白"。
                  // 故把 pick postMessage 给顶层窗口（window.top），由顶层面板监听聚合进其 window.__rolePicks 并渲染。
                  // 注意必须用 window.top 而非 window.parent：中间层 iframe 自身没有 message 监听器（面板仅顶层构建），
                  // 发给 window.parent（而非 window.top）：由每一层 frame 的消息监听逐层向父转发（见 message 监听的
                  // 向上中继逻辑），即使跨多级嵌套 / Playwright 下 window.top 直达投递异常，pick 也能可靠上送顶层。
                  // 纯前端同步，不依赖 Java 主循环轮询（postMessage 不受同源限制，跨源 iframe 同样生效）。
                  if (window.self !== window.top) {
                    // 【关键修复"嵌套 iframe 通信"】
                    // 原实现只发 window.parent、依赖每层 frame 的 message 监听逐层向上转发到顶层。
                    // 但中间层 iframe（frameOne）的 message 监听可能因 PANEL_SCRIPT 门禁/注入时机未注册，
                    // 导致 frameTwo（grandchild）的 pick 停在中间层、顶层 __currentStep 收不到（面板 '-'）。
                    // 改为"直达 + 逐层"双保险：
                    //   · 直达：window.top.postMessage 直接投递顶层（postMessage 不受同源限制；顶层监听有
                    //     __rolePickSigs 按 sigKey 去重，重复投递幂等）。
                    //   · 回退：若 window.top 跨域/受限抛异常，再发 window.parent 让已注册监听的中间层转发。
                    var __sent = false;
                    try { if (window.top && window.top !== window.self) { window.top.postMessage({ __rolePickMsg: true, pick: pick, __fromFrame: true }, '*'); __sent = true; } } catch (e) {}
                    if (!__sent && window.parent && window.parent !== window.self) {
                      try { window.parent.postMessage({ __rolePickMsg: true, pick: pick, __fromFrame: true }, '*'); } catch (e2) {}
                    }
                  }
                }
                return pick;
              };
              // 整页 role 树扫描（对齐 page.pause 的 role-centric 理念，但更完整）：
              // 遍历整页所有元素，凡"有语义角色（非 generic/none/presentation）且有可访问名"的可见元素，
              // 一律经 __recordPick 记录为 pick——复用点击拾取的全套链路（去重 / 面板渲染 / __roleOnPick 回传 Java）。
              // 与点击录制"录到什么才有什么"互补：扫描把整页所有语义角色元素一次性收全，用户随后停止即生成。
              // 扫描并收录语义角色元素。
              // @param scanRoot {Element|null|Array<Element>} 区域扫描根：
              //   - 单个 Element：仅遍历该根（含后代）子树，实现"只 scan 这块"；
              //   - 数组 Element[]：依次遍历每个根子树并合并（多选区域同时扫描）；
              //   - null：退化为整页扫描（原行为）。
              window.__roleScanPage = function(scanRoot) {
                if (typeof window.__recordPick !== 'function') return -1;
                // 归一化：单根 → 数组，便于统一遍历；null → 整页标记
                var roots = null;
                if (scanRoot == null) {
                  roots = null; // 整页
                } else if (Array.isArray(scanRoot)) {
                  roots = scanRoot.filter(function(r){ return r && r.nodeType === 1; });
                  if (!roots.length) roots = null;
                } else if (scanRoot.nodeType === 1) {
                  roots = [scanRoot];
                }
                var isRegion = !!roots;
                function __visForScan(el) {
                  try {
                    if (!el || !el.getBoundingClientRect) return false;
                    var cs = window.getComputedStyle(el);
                    if (!cs || cs.display === 'none' || cs.visibility === 'hidden' || parseFloat(cs.opacity || '1') === 0) return false;
                    var r = el.getBoundingClientRect();
                    // 【关键修复"区域扫描穿透 iframe 却看不到 iframe 内语义元素"】
                    // 在 iframe 内执行穿透自扫时（window.self !== window.top），docked 右侧拾取面板会把页面
                    // 内容压缩/遮挡，iframe 内语义元素（button/checkbox 等）的 getBoundingClientRect 可能
                    // 被算成 0 宽/高，从而被下方 r.width>0&&r.height>0 过滤 → iframe 语义元素一个都不收录，
                    // 只剩 body/容器整块 text 被回传（用户日志正是只有 text:iframe 嵌套子页面...）。
                    // 故对 iframe 内元素放宽宽高判定：只要 display/visibility/opacity 正常就视为可见并收录。
                    // 主框架元素仍按原宽高判定，避免误收不可见元素。
                    if (window.self !== window.top) return true;
                    return (r.width > 0 && r.height > 0);
                  } catch (e) { return true; }
                }
                var prevActive = window.__rolePickActive;
                window.__rolePickActive = true;   // 扫描期间强制激活，使 __recordPick 记录（结束后还原）
                var prevMode = window.__scanMode;
                window.__scanMode = 'page';       // 整页/区域扫描模式（与手动拾取 'pick' 区分）
                // 关键修复：扫描期间临时清空「字符串根约束」__rolePickRoot。
                // 区域扫描的范围由调用方传入的 roots 数组（已选容器）决定，不应再叠加
                // document.querySelector(__rolePickRoot) 的字符串约束——否则 rootToSelector 生成的
                // .class / tag[role] 选择器可能匹配到页面上「第一个」同名元素而非扫描目标容器，
                // 导致目标容器内所有子元素被误判为"根外"而全部忽略（表现：只有区域容器自身被定位，
                // 区域内的子元素一个都没扫到）。扫描结束后还原，不影响后续点击/悬停拾取的根约束。
                var prevRoot = window.__rolePickRoot;
                window.__rolePickRoot = null;
                // 区域扫描时记录"区域根"，供 __computePick 在重定位时约束上界——
                // 子元素最多重定位到区域根"内部"的交互角色，绝不被重定位到区域根本身或根之上
                // （否则会将"整个区域容器"作为定位结果，表现：选了区域却只定位到整个区域）。
                var prevScanRoot = window.__roleScanRoot;
                window.__roleScanRoot = (isRegion && roots.length) ? roots[0] : null;
                window.__scanning = true;         // 抑制 __recordPick 的逐元素 UI 反馈/Java 回传，结束统一处理
                var added = 0;
                // 本次扫描真正新增的 pick 列表：扫描结束只回传这些，避免把历史 / 其它页面同步下来的
                // 元素按当前页上下文重算键后重复写入 Java 内存态（见函数尾部批量回传处说明）。
                var __scanAdded = [];
                // 区域根面积（用于下方"剔除与整个区域等大的元素"的面积阈值）
                var rootArea = 0;
                if (isRegion && roots.length) {
                  var r0 = roots[0].getBoundingClientRect ? roots[0].getBoundingClientRect() : null;
                  if (r0) rootArea = (r0.width || 0) * (r0.height || 0);
                }
                try {
                  var els;
                  if (!isRegion) {
                    // 穿透 shadow DOM 收集全文档元素（含各级 open shadowRoot）
                    els = __allElementsInDoc(document);
                  } else {
                    // 多根：先把每棵子树的元素收集进一个数组（querySelectorAll 返回的是各根并列的实时集合，
                    // 用数组快照避免遍历中 DOM 变动影响；重复元素由 __recordPick 内部去重兜底）。
                    // 同时穿透每棵子树内的 open shadowRoot。
                    els = [];
                    for (var ri = 0; ri < roots.length; ri++) {
                      var sub = __allElementsInDoc(roots[ri]);
                      for (var ni = 0; ni < sub.length; ni++) els.push(sub[ni]);
                    }
                  }
                  for (var i = 0; i < els.length; i++) {
                    var el = els[i];
                    // 【关键修复"整页/区域扫描对 iframe 支持不好"】
                    // 原扫描只遍历当前 frame 的 document，遇到 iframe 元素因 getRole(iframe)=generic
                    // 被 NON_ROLE 过滤，iframe 内元素一个都扫不到。这里遇到 iframe/frame 时，递归进入其
                    // contentWindow，用该 frame 自己的 __roleScanPage（其上下文有自带的 __recordPick/
                    // __framePathOf，能正确计算嵌套 framePath）执行扫描；结果记录进该 frame 的 __rolePicks
                    // 并逐层上送，与手动点选同链路。
                    if (el && (el.tagName === 'IFRAME' || el.tagName === 'FRAME')) {
                      // 【关键修复"整页/区域扫描未穿透 iframe"——跨源穿透】
                      // 主 frame 的 JS 访问 iframe 的 contentWindow.document 在 file://（origin=null）
                      // 或跨域下会抛 SecurityError，导致 iframe 内元素一个都扫不到。分两条路径穿透：
                      //   · 同源可访问：直接递归调用 __iw.__roleScanPage(null)（该 frame 上下文自带
                      //     __recordPick/__framePathOf，能正确算嵌套 framePath）；
                      //   · 跨源受限：改用 contentWindow.postMessage 通知该 iframe 自扫（iframe 内
                      //     注册的 message 监听收到 __roleScanRequest 后执行自己的 __roleScanPage(null)，
                      //     结果经绑定桥/console 兜底回传 Java，并 push 进 iframe 的 __rolePicks）。
                      var __inRootOk = true;
                      if (isRegion && roots.length) {
                        // 区域态：若该 iframe 不落在任何选中根内，不扫描其内部（保持"只扫所选区域"语义）
                        __inRootOk = false;
                        for (var __ri = 0; __ri < roots.length; __ri++) {
                          try { if (roots[__ri].contains(el)) { __inRootOk = true; break; } } catch (_) {}
                        }
                      }
                      if (__inRootOk) {
                        try {
                          var __iw = el.contentWindow;
                          var __accessed = false;
                          try {
                            if (__iw && __iw.document && typeof __iw.__roleScanPage === 'function' && __iw !== window) {
                              __iw.__roleScanPage(null);
                              __accessed = true;
                            }
                          } catch (__acErr) { __accessed = false; }
                          if (!__accessed && __iw && __iw.postMessage) {
                            // 跨源：无法 JS 直调，postMessage 通知 iframe 自扫
                            try { __iw.postMessage({ __roleScanRequest: true, __roleScanRoot: 'page' }, '*'); } catch (_pm) {}
                          }
                        } catch (__ie) {}
                      }
                      continue;   // iframe 容器自身不作为可拾取元素
                    }
                    if (isRegion && roots.indexOf(el) !== -1) continue;   // 区域根容器自身不作为定位记录
                    if (el.closest && el.closest('#__rolePanel, #__roleCodeOverlay, #__roleHoverBox')) continue;
                    // 区域扫描：剔除"与整个区域几乎等大"的元素（面积 >= 区域根 90%）。
                    // 这类元素本质上就是区域根本身或其等价大容器（哪怕它不是 DOM 树上的 root 节点，
                    // 例如 root 内部一个覆盖整区域的 list/table 容器），记录它会表现为"只定位了整个区域"。
                    if (isRegion && roots.length && rootArea > 0) {
                      var ea = (el.getBoundingClientRect ? el.getBoundingClientRect() : null);
                      if (ea && ea.width > 0 && ea.height > 0) {
                        var eaArea = ea.width * ea.height;
                        // 与整个区域几乎等大（>=90%）：区域根自身或其等价大容器，跳过
                        if (eaArea >= rootArea * 0.9) continue;
                        // 占区域一半以上（>=50%）且只是"容器拼凑的文字"定位（text 策略）：
                        // 这类元素本质是整个区域容器的整块文本（如把卡片内所有按钮名拼成一条），
                        // 记录它会表现为"整区域定位"，跳过。
                        if (eaArea >= rootArea * 0.5) {
                          try {
                            var _probe = window.__computePick(el);
                            if (_probe && _probe.strategy === 'text') continue;
                          } catch (e) {}
                        }
                      }
                    }
                    // 区域扫描：只保留"有稳定定位策略"的元素（getByRole / 带 id / 带 testid 等）。
                    // 跳过 css 路径从 body 开头的无名布局 div——这类元素 5 层内遇不到 stable id，
                    // cssPathOf 会一路拼到 body，生成 "body > div:nth-of-type(...)" 这种"整区域级"定位，
                    // 无业务价值（用户要区域内可稳定定位的子元素，而非整个区域）。
                    // 有 id 的容器 / 带 id 祖先的元素，其 css 路径以 #id 开头，不会被跳过。
                    // 仅区域扫描做无锚点 css 过滤。
                    // 原因：区域扫描是 querySelectorAll('*') 无差别遍历，区域内大量无语义的布局 div
                    // 会退化出 "div:nth-of-type(3) > div > div > ..." 这种纯位置链兜底路径，是噪音主源。
                    // 手动点选是用户主动指定的单个元素、整页扫描已有角色+可访问名双重约束，
                    // 都应保持与 page.pause() 一致：该生成 css 兜底就正常生成，不额外丢弃元素。
                    if (isRegion) {
                      var _cssProbe = (typeof cssPathOf === 'function') ? cssPathOf(el) : '';
                      // 区域态：css 兜底路径只要"退化到 body/html 骨架级"（无论是否带锚点前缀如
                      // ".Mozilla body > ..."，原判定仅拦 body 开头，会漏掉带前缀的形态）或"纯位置链
                      // （__isNthOnlyCss）"，即视为无稳定定位价值的噪音，仅在 __computePick 也只剩
                      // css 兜底时才跳过（保留具备 role/name/testid 等语义定位能力的元素）。
                      var _isBodyLevel = _cssProbe && (_cssProbe.indexOf('body') !== -1 || _cssProbe.indexOf('html') !== -1);
                      var _isNthOnly = _cssProbe && (typeof __isNthOnlyCss === 'function') && __isNthOnlyCss(_cssProbe);
                      if (_cssProbe && (_isBodyLevel || _isNthOnly)) {
                        // 该元素自身没有可锚定的 css 路径，但它可能仍具备语义定位能力
                        // （role+name / testid / label / placeholder 等），那类元素不该被误杀。
                        // 因此只有当 __computePick 最终也只能退化到 css 策略时才跳过。
                        var _pk = null;
                        try { _pk = window.__computePick(el); } catch (e) {}
                        if (!_pk || _pk.strategy === 'css') continue;
                      }
                    }
                    // 1) 先按语义角色过滤：最便宜的判定，可剔除绝大多数 generic div/span，
                    //    避免后续对它们做昂贵的可见性（getComputedStyle）/可访问名计算。
                    var role = (getRole(el) || '').toLowerCase();
                    if (!role || NON_ROLE[role]) continue;
                    // 2) 再按可访问名过滤：只需"有名字"，用 clean 名称一次计算即可（省去 getNameInfo 的二次计算）。
                    var name = (typeof getElementAccessibleName === 'function') ? getElementAccessibleName(el, false) : null;
                    if (!name) continue;
                    // 3) 最后才做昂贵的可见性判定（getComputedStyle + getBoundingClientRect）。
                    //    绝大多数元素已在 1)/2) 被过滤，getComputedStyle 调用量从「全体元素」降至「语义角色+带名元素」。
                    if (!__visForScan(el)) continue;
                    var before = window.__rolePicks ? window.__rolePicks.length : 0;
                    var pk = window.__recordPick(el, false);   // 内部核心去重仍执行；UI/回传副作用因 __scanning 被跳过
                    var after = window.__rolePicks ? window.__rolePicks.length : 0;
                    // 【关键修复"区域扫描额外生成整块文本定位"】
                    // 区域/整页扫描会把"整区域文本拼接"的元素定位成 text 策略（如 #sec-role 自身被
                    // __computePick 退化为 text，名称为其所有子元素文本拼接："1. 角色与状态 提交 禁用按钮..."），
                    // 这类元素不是可交互子元素、无业务价值，却会出现在页面元素/页面类里。
                    // 此处对扫描态新增的 text 策略元素做"整块文本"判定：名称超长（>=25 字符，明显是多子元素
                    // 拼接）即从 __rolePicks 回退移除，不计入本次新增。短文本的 text 元素（如独立段落/标签）
                    // 不受影响；点击拾取（__scanning=false）不受影响。
                    if (pk && pk.strategy === 'text' && (pk.name || '').length >= 25) {
                      if (after > before && window.__rolePicks) { window.__rolePicks.pop(); }
                      try { if (window.__rolePickSigs && pk._sigKey && window.__rolePickSigs[pk._sigKey]) delete window.__rolePickSigs[pk._sigKey]; } catch (e) {}
                      continue;
                    }
                    // 只登记「本次扫描真正新增」的 pick，供扫描结束后精确批量回传。
                    // 不能在结束时遍历整个 __rolePicks：该数组还含历史拾取与 syncPanelToBrowser
                    // 从 Java 同步下来的**其它页面**元素，全量回传会把它们按当前页上下文重算键后再写一遍。
                    if (pk && after > before) { added++; __scanAdded.push(window.__rolePicks[after - 1]); }
                  }
                } catch (e) {
                  try { console.error('[roleScan] ' + (e && e.message)); } catch (_) {}
                }
                window.__scanning = false;
                window.__rolePickActive = prevActive;
                window.__rolePickRoot = prevRoot;  // 还原字符串根约束（不影响后续点击/悬停拾取）
                // 一次性渲染 + 批量回传 Java：替代逐元素的 O(N²) 渲染与 N 次回传 / 控制台事件洪流。
                try { if (window.__renderPicks) window.__renderPicks(); } catch (e) {}
                // 只回传本次扫描新增的 pick（__scanAdded），不再遍历整个 window.__rolePicks。
                // 原因（修复"区域扫描 A 页 → 跳转 B 页 → 整页扫描 B，A 页元素成倍重复"）：
                // __rolePicks 除本次新增外，还含 ① 本页历史拾取 ② syncPanelToBrowser 从 Java 内存态
                // 同步下来的**其它页面**元素。全量回传时第 __sigKey(p) 会在**当前页上下文**重算去重键，
                // 而同步下来的元素只带 _sig 不带 _sigKey，一旦其 _pageClass 为空就退化到用当前 location
                // 兜底（见 __sigKey 第 1282 行），算出与 Java 中原键不同的新键 → 同一元素被重复写入内存态，
                // 每扫描一次翻一倍（用户实测同组 12 个元素重复 4 次）。
                if (__scanAdded.length) {
                  for (var k = 0; k < __scanAdded.length; k++) {
                    var p = __scanAdded[k];
                    if (!p) continue;
                    try {
                      if (typeof window.__sigKey === 'function') p._sigKey = window.__sigKey(p);
                      if (typeof window.__roleOnPick === 'function') window.__roleOnPick(JSON.stringify(p));
                      // 【关键修复"整页/区域扫描未穿透 iframe"】
                      // iframe 内递归扫描走的是 iframe 自己的 __roleScanPage，批量回传仅经绑定桥 __roleOnPick。
                      // 但 iframe 内绑定桥常失效（跨 frame/上下文隔离，前面分析过），扫描结果会整批丢失，
                      // 表现为"扫描扫不到 iframe 内元素"。此处补 console 兜底回传（__roleOnPick::...），
                      // 与手动点选的"绑定桥 + console 双保险"对齐，确保 iframe 内扫描结果必达 Java。
                      try { console.log('__roleOnPick::' + JSON.stringify(p)); } catch (_) {}
                      // 【关键修复"页面元素面板只有主框架元素"】
                      // __recordPick 里的"iframe 内元素 postMessage 上送顶层 __rolePicks"逻辑（点击拾取路径）
                      // 被包裹在 `if (!window.__scanning)` 内——扫描态 __scanning=true 时被整段跳过。
                      // 因此扫描出的 iframe 元素只 push 进【本 frame】的 __rolePicks 并经上面回传 Java，
                      // 从不会上送到顶层主框架 __rolePicks → 顶层面板"页面元素"Tab 只显示主框架元素、
                      // 扫描后即时生成的页面类也漏掉 iframe 内元素（Java 内存态却有 40 个，面板只见 32 个）。
                      // 修复：本 frame 若是 iframe，批量回传时把新增元素 postMessage 上送顶层聚合（与点击
                      // 拾取同链路，顶层 __rolePickSigs 按 sigKey 去重幂等）。上层 message 监听收到
                      // __rolePickMsg 后 push 进顶层 __rolePicks 并触发 __renderPicks，面板随即可见。
                      if (window.self !== window.top) {
                        // 【关键修复"checkbox 被默认勾选进选择集"】
                        // 本批量回传在 __roleScanPage 尾部、__scanning 已置 false 后执行；若此时把 iframe
                        // 元素 postMessage 上送顶层，顶层 message 监听读到 __scanning=false 会误判为"点击
                        // 拾取"而把元素 push 进 __currentStep（选择集）→ 面板里 checkbox 等扫描候选被默认勾选。
                        // 修复：上送时打上 __isScan 标记，顶层据此【不入选 step】，仅聚合进 __rolePicks 供面板
                        // 展示，保持"扫描出的元素是候选、由用户勾选后封装"的语义。
                        var __sent2 = false;
                        try {
                          if (window.top && window.top !== window.self) {
                            window.top.postMessage({ __rolePickMsg: true, __isScan: true, pick: p, __fromFrame: true }, '*');
                            __sent2 = true;
                          }
                        } catch (__e2) {}
                        if (!__sent2 && window.parent && window.parent !== window.self) {
                          try { window.parent.postMessage({ __rolePickMsg: true, __isScan: true, pick: p, __fromFrame: true }, '*'); } catch (__e3) {}
                        }
                      }
                    } catch (e) {}
                  }
                }
                var statusEl = document.getElementById('__roleStatus');
                if (statusEl) {
                  var pickedN = window.__rolePicks ? window.__rolePicks.length : 0;
                  statusEl.textContent = 'RoleElement Picker：' + (isRegion ? ('区域扫描完成（' + roots.length + ' 个区域）') : '整页扫描完成') + '，已拾取 ' + pickedN + ' 个语义角色元素，按 ESC 结束';
                }
                // 扫描结束：清除整页扫描态（区域扫描点击也会调用本函数，但区域态标志独立，此处只清 __pageScanning），
                // 恢复 scan/region 按钮可用；若异常也保证清除，避免按钮卡死置灰。
                // 还原模式标识（整页/区域扫描都经此路径，区域态由 __regionSelecting 独立控制，不影响）。
                window.__pageScanning = false;
                window.__scanMode = prevMode;
                window.__roleScanRoot = prevScanRoot;   // 还原区域根（整页/非扫描时为 null）
                try { if (window.__roleRefreshToggle) window.__roleRefreshToggle(); } catch (e) {}
                // 【修复"整页扫描完成后应聚焦到当前页 Tab"】
                // 整页扫描（非区域扫描）结束后，自动把面板切到「页面元素」Tab，并把激活的子 Tab
                // 设为【当前扫描页】（window.__rolePageName，即刚整页扫描的那个 pageClass），
                // 让用户即时看到本次整页扫描出来的元素清单；即便此前停留在其它页的子 Tab 也不受影响。
                // 区域扫描为「叠加补充」语义，不强制切 Tab，避免打断用户连续选区。
                if (!isRegion) {
                  try {
                    var __curPage = window.__rolePageName || '';
                    window.__roleActiveTab = 'page';
                    if (__curPage) window.__roleActivePageClass = __curPage;
                    if (typeof window.__roleShowTab === 'function') window.__roleShowTab();
                    if (typeof window.__renderPicks === 'function') window.__renderPicks();
                  } catch (e2) {}
                }
                return added;
              };
              // 区域扫描（悬停聚焦 + 点击多选模式）：一个页面常有多个业务区域，支持「选取多个区域」。
              // 交互：鼠标移入区域即实时高亮（跟随切换）；点击 = 把当前区域加入/移出「已选集合」（多选、可重复点取消）；
              // 按 Esc 完成选区，合并扫描所有已选区域（__roleScanPage 支持数组多根）；未选任何区域则退化为整页。
              // 颜色：青色=悬停预览，绿色=已选中。面板内不参与选区。
              window.__roleStartRegionSelect = function() {
                if (typeof window.__roleScanPage !== 'function') return;
                // 显式模式标识：区域扫描态。三种模式（手动拾取 / 整页扫描 / 区域扫描）复用同一套
                // __rolePickActive / __recordPick，但靠 __scanMode 显式区分，避免"区域被当成点击拾取的元素"。
                window.__scanMode = 'region';
                // 进入区域扫描时【不再清空】__rolePicks / __rolePickSigs / __deletedSigs / __currentStep。
                // 修复：整页扫描 → 停止拾取 → 再区域选择时，整页扫描的页面元素被清空。
                // 原因：区域扫描 __roleScanPage 本身是「追加」语义（__scanAdded 记录本次新增并 push 进
                // __rolePicks），只有此处入口清空了拾取集才导致整页扫描结果丢失。现在保留已有拾取集，
                // 让区域扫描结果与整页扫描元素【叠加】，符合"整页扫描 + 区域选择互补补充"的期望。
                // 同时保留 __rolePickSigs（去重表）防止同 sig 元素重复、保留 __deletedSigs（已删屏蔽）
                // 防止已删元素在区域扫描时复活、保留 __currentStep（已勾选封装的选择集）防止已封装元素丢失。
                // 区域扫描结束后用户仍可手动勾选追加，行为不变。
                // 进入选区态时临时摘掉 START_SCRIPT 的文档级「点击拾取」「悬停高亮」监听，避免与区域聚焦冲突；
                // 选区结束后再把原监听加回。
                var hadPickClick = !!window.__rolePickClick;
                var hadPickMove = !!window.__rolePickMove;
                try { if (window.__rolePickClick) document.removeEventListener('click', window.__rolePickClick, true); } catch (e) {}
                try { if (window.__rolePickMove) document.removeEventListener('mousemove', window.__rolePickMove, true); } catch (e) {}
                // 视觉提示遮罩（不拦截事件，pointer-events:none），仅告知用户处于「选区态」
                var mask = document.createElement('div');
                mask.style.cssText = 'position:fixed;inset:0;z-index:2147483646;pointer-events:none;cursor:crosshair;background:rgba(33,150,243,.04)';
                document.body.appendChild(mask);
                // 区域扫描收敛：点击位置（多为按钮/链接/单元格等叶子）向上找到"所在业务区域容器"，
                // 并把它作为该区域的扫描根——这样扫描的是「区域里所有可定位元素」，而不是仅那个叶子本身。
                // 之前 acceptable 太宽松，会直接接受叶子元素，导致 querySelectorAll('*') 只扫到叶子内部、
                // 几乎无有意义元素（表现：只有区域那个元素被定位，而非区域的元素）。现改为"向上收敛到区域块"。
                function pickRoot(target) {
                  if (!target || target.nodeType !== 1) return null;
                  var LANDMARK = 'nav, header, aside, footer, [role=navigation], [role=banner], [role=complementary], [role=contentinfo]';
                  if (target.id === '__rolePanel' || target.id === '__roleCodeOverlay' || target.id === '__roleHoverBox') return null;
                  if (target.matches && target.matches(LANDMARK)) return null;
                  // 明确"业务区域语义"：这些标签/role 即代表一块业务区，应作为收敛目标，而非更大的页面骨架。
                  var REGION_STRICT = 'main, [role=main], section, article, form, fieldset,'
                    + ' [role=region], [role=dialog], [role=group], [role=list], [role=menu], [role=tablist], [role=toolbar]';
                  // 具体业务块 class：仅认真正业务块（card/panel/box/item/modal/dialog/list/group...），
                  // 排除 app/content/container/wrapper/layout/main 等"页面骨架"class（它们面积大、不是用户想选的区域）。
                  var BUSINESS_CLS = /(card|panel|box|section|block|modal|dialog|form-|item|widget|tile|cell|row-group|list-|group|fieldset|accordion|tab-)/i;
                  // 面积上限：超过视口 40% 的一律视为"页面骨架/整页"，不当作可收敛的业务区域（避免选区过大，
                  // 退化成整页扫描）。该阈值也用于兜底向上收敛时对"有 id/class 祖先"的面积约束。
                  var MAX_AREA = (window.innerWidth || 1280) * (window.innerHeight || 800) * 0.4;
                  function elArea(el) { var r = el.getBoundingClientRect ? el.getBoundingClientRect() : null; return r ? (r.width || 0) * (r.height || 0) : 0; }
                  var INLINE = { A:1, SPAN:1, BUTTON:1, INPUT:1, SELECT:1, TEXTAREA:1, LABEL:1, TD:1, TH:1, TR:1, LI:1, IMG:1, I:1, B:1, STRONG:1, EM:1, CODE:1, SMALL:1, SUB:1, SUP:1 };
                  // 是否为"合适的区域容器"：必须带明确业务语义标识，且面积未超骨架阈值。
                  function isContainer(el) {
                    if (!el || el === document.body) return false;
                    if (el.id === '__rolePanel' || el.id === '__roleCodeOverlay' || el.id === '__roleHoverBox') return false;
                    if (el.matches && el.matches(LANDMARK)) return false;     // 导航类 landmark 不收敛
                    var tag = (el.tagName || '').toUpperCase();
                    if (INLINE[tag]) return false;                            // 行内/叶子语义标签不收敛
                    if (el.matches && el.matches(REGION_STRICT)) return true; // 业务区域语义标签/role（高优先）
                    var area = elArea(el);
                    if (area > MAX_AREA) return false;                        // 过大=页面骨架，排除
                    var role = el.getAttribute && el.getAttribute('role');
                    if (role && !el.matches(LANDMARK)) return true;           // 显式非导航 role（list/menu/tablist...）
                    var cls = (el.className && el.className.baseVal !== undefined ? el.className.baseVal : (el.className || '')) + '';
                    if (BUSINESS_CLS.test(cls)) return true;                 // 具体业务块 class
                    if (el.id) return true;                                  // 有 id 且面积未超限的容器
                    return false;
                  }
                  // 1) 自身若是业务容器，直接返回（如点了 section/article/form 本身）
                  if (isContainer(target)) return target;
                  // 2) 向上找「最近的、合格业务容器」：大骨架因面积超限被跳过，继续向上；更大祖先通常也超限，
                  //    故实际会在"最近的、带语义标识/有 id 且不过大"的祖先处停下，避免选到整个页面。
                  var cur = target.parentElement;
                  var lastIdBlock = null;   // 仅记录"带 id 且面积未超限"的块级祖先（拒绝无名布局 div 当区域）
                  while (cur && cur !== document.body) {
                    if (isContainer(cur)) return cur;
                    if (cur.id && elArea(cur) <= MAX_AREA) lastIdBlock = cur;
                    cur = cur.parentElement;
                  }
                  // 3) 兜底：绝不退化到 main/body（否则区域扫描会退化成"整页扫描"，
                  //    表现：选了一小块区域却定位了整个页面/大区域）。
                  //    优先用带 id 且不过大的块级祖先；都没有则向上找"最近的非叶子、非骨架语义祖先"
                  //    （即使是无名 div，也比 main/body 小得多，更贴近用户点选位置）；
                  //    极端情况下退回点击元素自身（扫描其后代），确保区域小而精准。
                  if (lastIdBlock) return lastIdBlock;
                  var cur2 = target.parentElement;
                  while (cur2 && cur2 !== document.body) {
                    var tg = (cur2.tagName || '').toUpperCase();
                    if (INLINE[tg]) { cur2 = cur2.parentElement; continue; }
                    if (cur2.matches && cur2.matches(LANDMARK)) { cur2 = cur2.parentElement; continue; }
                    if (elArea(cur2) > MAX_AREA) { cur2 = cur2.parentElement; continue; } // 过大骨架跳过
                    // 命中"有业务语义标识"的祖先即停（role/class/id），否则继续向上，直到贴近的小容器
                    if (cur2.getAttribute && (cur2.getAttribute('role') || cur2.className || cur2.id)) return cur2;
                    cur2 = cur2.parentElement;
                  }
                  return target;   // 极端：裸页面无任何语义祖先，退回点击元素自身
                }
                var selected = window.__regionSelected = (window.__regionSelected || []);  // 已选区域根数组（暴露为全局，供封装/删除/停止时统一清高亮）
                var lastHover = null;
                function labelOf(el) { return (el.tagName ? el.tagName.toLowerCase() : '?') + (el.id ? '#' + el.id : ''); }
                function clearOutline(el) { try { el.style.outline = el.__prevOutline || ''; el.style.outlineOffset = el.__prevOffset || ''; } catch (e) {} }
                function setOutline(el, color) { try { el.__prevOutline = el.style.outline; el.__prevOffset = el.style.outlineOffset; el.style.outline = '2px solid ' + color; el.style.outlineOffset = '2px'; } catch (e) {} }
                function containsRoot(arr, el) { for (var i = 0; i < arr.length; i++) if (arr[i] === el) return true; return false; }
                // 重绘所有已选区域（绿色）并把悬停区叠加青色
                function repaint(hover) {
                  for (var i = 0; i < selected.length; i++) setOutline(selected[i], '#43a047'); // 绿色=已选
                  if (hover && !containsRoot(selected, hover)) setOutline(hover, '#0097a7');     // 青色=悬停预览
                }
                function status(msg) { var st = document.getElementById('__roleStatus'); if (st) st.textContent = msg; }
                function onMove(e) {
                  var t = e.target;
                  if (t && t.closest && t.closest('#__rolePanel, #__roleCodeOverlay, #__roleHoverBox')) {
                    if (lastHover) { clearOutline(lastHover); lastHover = null; repaint(null); }
                    status('鼠标在录制面板上，移出面板再选区域');
                    return;
                  }
                  var root = pickRoot(t);
                  if (root === lastHover) return;            // 同一区域不重复重绘
                  if (lastHover) clearOutline(lastHover);
                  lastHover = root;
                  repaint(root);
                  if (root) status('当前区域：' + labelOf(root) + '（点击=选/取消；已选 ' + selected.length + ' 个；Esc 完成扫描）');
                  else status('该位置不在业务容器内，挪到业务区（如表单/内容区）再点');
                }
                // 统一清除区域选择遗留的页面高亮框（绿色 #43a047 已选 / 青色 #0097a7 悬停预览）。
                // 暴露为全局，供「封装为步骤」「删除」「停止拾取」等收尾动作调用——
                // 否则用户不按 Esc 而直接封装/停止时，区域绿框会一直残留在页面上。
                window.__clearRegionOutlines = function() {
                  try {
                    var arr = window.__regionSelected || [];
                    for (var i = 0; i < arr.length; i++) { try { clearOutline(arr[i]); } catch (e) {} }
                    arr.length = 0;
                    if (lastHover) { try { clearOutline(lastHover); } catch (e) {} lastHover = null; }
                  } catch (e) {}
                };
                function finish() {
                  document.removeEventListener('mousemove', onMove, true);
                  document.removeEventListener('click', onClick, true);
                  document.removeEventListener('keydown', onEsc, true);
                  if (mask && mask.parentNode) mask.remove();
                  window.__clearRegionOutlines();
                  if (lastHover) clearOutline(lastHover);
                  // 各区域已在点击时即时扫描并展示，此处仅收尾；恢复整页拾取（不设字符串根约束，避免误约束）。
                  window.__rolePickRoot = null;
                  window.__regionSelecting = false;   // 退出区域选择态：恢复 scan/region 按钮可用
                  window.__scanMode = null;           // 清除模式标识（回到"无模式"，等待手动拾取/扫描指令）
                  try { window.__roleRefreshToggle && window.__roleRefreshToggle(); } catch (e) {}
                  restorePick();
                }
                function onEsc(e) {
                  if (e && (e.key === 'Escape' || e.keyCode === 27)) { e.preventDefault(); e.stopPropagation(); finish(); }
                }
                // 兼容：某些环境 keydown 不冒泡到 capture 阶段被拦截，也挂到 document（非 capture）兜底。
                function onEscBubble(e) {
                  if (e && (e.key === 'Escape' || e.keyCode === 27)) { e.preventDefault(); e.stopPropagation(); finish(); }
                }
                function onClick(e) {
                  var t = e.target;
                  // 关键修复：点击落在录制面板/代码浮层内时，必须「原样放行」事件（不 preventDefault/不 stopPropagation），
                  // 否则 document 级 capture 监听会吞掉面板内按钮（如"停止拾取"）的点击，导致状态切不动。
                  if (t && t.closest && t.closest('#__rolePanel, #__roleCodeOverlay, #__roleHoverBox')) {
                    return;   // 直接放行，交给面板按钮正常处理
                  }
                  e.preventDefault(); e.stopPropagation();
                  var root = pickRoot(t);
                  if (!root) { status('该位置无法收敛到业务容器，请点具体的业务区域（如表单/内容区）'); return; }
                  // 点击区域 = 立即扫描并展示该区域内所有可定位子元素（松开鼠标即出结果，无需按 Esc）。
                  // 区域容器自身不作为定位目标：__roleScanPage 内部用 root.querySelectorAll('*') 仅遍历后代，不含 root 自身。
                  if (!containsRoot(selected, root)) { selected.push(root); }
                  setOutline(root, '#43a047');   // 绿色=已扫描区域
                  try { window.__roleScanPage([root]); } catch (err) { try { console.error('[roleScan] ' + (err && err.message)); } catch (_) {} }
                  // 通知 Java 侧：区域元素已入 window.__rolePicks，请重新读取快照并生成"页面类"（与整页扫描一致，
                  // 否则区域扫描只会收集元素却永远不生成页面类）。用命令桥事件驱动，无需 Java 轮询。
                  // 【关键修复"区域扫描穿透不了 frame"】
                  // 区域根内若含 iframe，__roleScanPage 走 postMessage 异步穿透（file:// 下跨源受限），
                  // iframe 自扫结果进【各自 iframe】的 __rolePicks 需要一点事件循环时间。若立即发
                  // regionScanned，Java 侧 mergeFramePicksToMain 可能读到空的 iframe.__rolePicks，iframe
                  // 语义元素合并不进主框架/面板 → 表象"穿透不了 frame"。故延迟一拍再通知，确保 iframe
                  // 自扫完成、iframe.__rolePicks 已填充后再让 Java 合并（主循环空闲的 mergeFramePicksToMain
                  // 仍作最终兜底）。
                  try { setTimeout(function(){ if (window.__rolePickerCmd) window.__rolePickerCmd('regionScanned'); }, 250); } catch (e) {}
                  status('已扫描区域：' + labelOf(root) + '（已生成页面类；可继续点其他区域，按 Esc 结束）');
                  if (lastHover) { clearOutline(lastHover); lastHover = null; }
                }
                function restorePick() {
                  try { if (hadPickClick && window.__rolePickClick) document.addEventListener('click', window.__rolePickClick, true); } catch (e) {}
                  try { if (hadPickMove && window.__rolePickMove) document.addEventListener('mousemove', window.__rolePickMove, true); } catch (e) {}
                }
                // 把选区监听引用挂到 window，便于 STOP_SCRIPT（停止命令）在区域选区态中也能移除它们（否则残留导致状态卡死）。
                window.__roleRegionMove = onMove;
                window.__roleRegionClick = onClick;
                window.__roleRegionEsc = onEsc;
                window.__roleRegionEscB = onEscBubble;
                window.__roleEndRegionSelect = finish;   // 统一收尾入口（finish 内已清标志+restorePick+刷新按钮）
                document.addEventListener('mousemove', onMove, true);
                document.addEventListener('click', onClick, true);
                document.addEventListener('keydown', onEsc, true);
                document.addEventListener('keydown', onEscBubble, false);   // 兜底：capture 被吞时仍能 Esc 结束
                status('鼠标移入区域即聚焦（挪动跟随）；点击区域即扫描并展示该区域内元素；按 Esc 结束选区');
              };
              // 悬停高亮 + 悬停拾取（hover）模式：开启后鼠标停在元素上约 0.45s 即记录 hover 动作。
              window.__rolePickMove = function(ev) {
                if (!window.__rolePickActive) {
                  if (window.__hoverRaf) { try { cancelAnimationFrame(window.__hoverRaf); } catch(e){} window.__hoverRaf = null; }
                  __showHoverBox(null); window.__lastHoverTarget = null; return;
                }
                var t = ev.target;
                if (t && t.closest && t.closest('#__rolePanel, #__roleCodeOverlay')) {
                  __showHoverBox(null); window.__lastHoverTarget = null; return;
                }
                // 性能：合并相邻 mousemove 到下一帧，且仅在目标元素变化时重排，
                // 避免每像素移动都触发 getBoundingClientRect + style 强制回流（整页卡顿的根因）。
                if (window.__lastHoverTarget === t) return;
                window.__lastHoverTarget = t;
                if (window.__hoverRaf) return;
                window.__hoverRaf = requestAnimationFrame(function() {
                  window.__hoverRaf = null;
                  if (window.__rolePickActive) __showHoverBox(window.__lastHoverTarget);
                });
                if (window.__roleHoverMode) {
                  if (t !== __hoverTarget) {
                    __hoverTarget = t;
                    if (__hoverTimer) { clearTimeout(__hoverTimer); __hoverTimer = null; }
                    __hoverTimer = setTimeout(function() {
                      if (window.__rolePickActive && t && t.closest
                          && !t.closest('#__rolePanel, #__roleCodeOverlay')) {
                        window.__recordPick(t, true);
                      }
                    }, 450);
                  }
                }
              };
              // 【关键修复"鼠标 hover 到 frame 后失焦，hover 高亮框未去除"】
              // 每个 frame 各自的 __rolePickMove 维护各自的 __roleHoverBox；鼠标从 iframe 移出到主框架时，
              // iframe 的 mousemove 不再触发，iframe 的 __roleHoverBox 保持显示（残留青色边框）。
              // 用 document 级 mouseleave（仅在鼠标真正离开该 frame 的 document 边界时触发，不冒泡、
              // 同一 document 内移动不会误触发）在鼠标离开该 frame 时清除其 hover box 与 hover 状态。
              window.__rolePickLeave = function() {
                try {
                  if (window.__hoverRaf) { cancelAnimationFrame(window.__hoverRaf); window.__hoverRaf = null; }
                } catch (e) {}
                try { if (window.__hoverTimer) { clearTimeout(window.__hoverTimer); window.__hoverTimer = null; } } catch (e) {}
                try { window.__hoverTarget = null; } catch (e) {}
                try { window.__lastHoverTarget = null; } catch (e) {}
                try {
                  var hb = document.getElementById('__roleHoverBox');
                  if (hb) hb.style.display = 'none';
                } catch (e) {}
              };
              document.addEventListener('mouseleave', window.__rolePickLeave, false);
              // ===== 原生对话框（alert/confirm/prompt）拦截 =====
              // 对齐 page.pause() 的 dialog 信号：在点击触发业务（业务可能调用 alert/confirm）时捕获。
              // 记录 {type, message, seq} 到 window.__pwDialogs；点击 handler 在宏任务里回查，
              // 若本次点击后产生了 dialog，则给对应 pick 打 dialog 标记并重传（覆盖内存态），
              // 生成 step 时前置插桩 page.onDialog(...)。
              window.__pwDialogs = [];
              window.__pwDlgSeq = 0;
              (function() {
                // 对话框真正产生的时刻，把"最近一次拾取的元素"打上 dialog 标记并重传 Java 内存态。
                // 不依赖点击 handler 的 setTimeout(0) 回查（当业务里 alert 是 setTimeout 异步触发时，
                // 0ms 回查会早于 dialog 产生而漏判）；此处 dialog 本体已存在，标记必然命中。
                // 即便 Java 侧 page.onDialog 因跨 page/iframe 实例未触发，内存态（javaPickBySig）也能拿到标记，
                // 生成 step 时前置插桩 acceptAlert/dismissAlert（与 page.onDialog 双保险、幂等）。
                function markLastPickDialog(type) {
                  try {
                    var sig = window.__lastPickSig || '';
                    if (!sig) return;
                    var pick = window.__sigToPick ? window.__sigToPick[sig] : null;
                    if (!pick) return;
                    // alert 默认 accept；confirm/prompt 默认 dismiss（与 Java onDialog 约定一致）
                    var action = (type === 'alert') ? 'accept' : 'dismiss';
                    pick.dialog = true;
                    pick.dialogType = type;
                    pick.dialogAction = action;
                    if (typeof window.__sigKey === 'function') pick._sigKey = window.__sigKey(pick);
                    if (typeof window.__roleOnPick === 'function') window.__roleOnPick(JSON.stringify(pick));
                    try { console.log('__roleOnPick::' + JSON.stringify(pick)); } catch (_) {}
                  } catch (e) {}
                }
                function hook(orig, type) {
                  return function(msg) {
                    try {
                      window.__pwDialogs.push({ type: type, message: (msg == null ? '' : String(msg)), seq: ++window.__pwDlgSeq });
                    } catch (e) {}
                    // 对话框产生的瞬间即给最近一次点击元素打 dialog 标记（双保险之一）
                    markLastPickDialog(type);
                    // 调用原始实现以维持页面既有行为（避免吞掉业务弹窗）
                    if (typeof orig === 'function') { return orig.apply(this, arguments); }
                  };
                }
                try { window.alert   = hook(window.alert,   'alert'); }   catch (e) {}
                try { window.confirm = hook(window.confirm, 'confirm'); } catch (e) {}
                try { window.prompt  = hook(window.prompt,  'prompt'); }  catch (e) {}
              })();
              document.addEventListener('mousemove', window.__rolePickMove, true);
              window.__rolePickClick = function(event) {
                // 诊断：记录最近一次点击到达 handler 的时间与当时激活态（供 Java 侧回读，确认点击是否被监听捕获）。
                window.__lastClickTs = Date.now();
                window.__lastClickActive = !!window.__rolePickActive;
                // 对齐 page.pause()：用 composedPath()[0] 取代 event.target，穿透 open shadow DOM，
                // 点击 Web Component 内部按钮也能拿到真实目标元素（event.target 在 shadow 边界会停在宿主上）。
                var t = (typeof event.composedPath === 'function' && event.composedPath().length)
                  ? event.composedPath()[0] : event.target;
                if (t && t.closest && t.closest('#__rolePanel, #__roleCodeOverlay')) {
                  return;
                }
                var pick = window.__recordPick(t, false);
                if (!pick) return;
                // 记录点击前的 dialog 计数，供宏任务回查"本次点击是否触发了原生对话框"
                var dlgBefore = window.__pwDialogs ? window.__pwDialogs.length : 0;
                var clickPick = pick;
                // 对齐 page.pause()：拾取模式下【点击穿透】——元素的真实事件与默认行为照常触发
                // （button 的 onclick、链接跳转、表单提交都会真实发生），仅在此同步记录/定位该元素，
                // 不再用 preventDefault 吞掉元素的真实交互。曾经为"留在当前页连续拾取"而阻止 a[href]/form 的默认导航，
                // 但那样会吞掉点击（如点按钮却没触发其事件/业务），与 page.pause() 的"点击即真实交互 + 定位"不一致。
                // 跨页导航由 Java 侧 onFrameNavigated / onPopup（以及 SPA heal）接管，拾取状态不丢。
                // 仅按 codegen 需求给链接打 popup/download 标记（不阻止默认行为）。
                var aEl = t.closest ? t.closest('a[href]') : null;
                if (aEl) {
                  var tgt = aEl.getAttribute('target');
                  var opensNew = !!(tgt && tgt.charAt(0) === '_' && tgt.toLowerCase() !== '_self');   // _blank / _new ...
                  // 下载链接判定：带 download 属性，或 href 指向常见文件扩展名（.pdf/.xls/.csv...）。
                  // 不阻止默认行为，放行让浏览器真正发起下载；同时打 download 标记，
                  // 生成 step 时包装为 waitForDownload（对齐 page.pause() 的 codegen 输出）。
                  var href = aEl.getAttribute('href') || '';
                  var isDownloadLink = !!aEl.getAttribute('download')
                    || /\\.(pdf|zip|docx?|xlsx?|csv|pptx?|txt|json|xml|png|jpe?g|gif|exe|msi|tar|gz|rar|7z|mp3|mp4|mov)(\\?|#|$)/i.test(href);
                  if (isDownloadLink) {
                    if (pick) { pick.download = true; if (opensNew) pick.popup = true; }   // 弹窗 + 下载：嵌套 waitForDownload(waitForPopup)
                  } else if (opensNew && pick) {
                    // 标记"该点击会弹出新页面"：生成 step 时包装为
                    // page.waitForPopup(() -> element.click())（对齐 page.pause() 的 codegen 输出）。
                    pick.popup = true;
                  }
                  // 同标签页普通链接：放行真实导航（不再 preventDefault）
                }
                // 弹窗/下载标记：__recordPick 内部的回传（__roleOnPick / __roleOnPick::）发生在本 click handler
                // 设置 pick.popup / pick.download 之前（2121 行已 JSON.stringify 序列化，彼时两标记仍为 false），
                // 若不在此补发一次，Java 权威内存态 javaPickBySig 拿到的就是"无 popup/download 标记"的版本，
                // 生成 step 时就不会包 switchToNewPage / waitForDownload——表现为"waitForPopup 没起作用"。
                // 与 dialog 双保险对称：设置完标记后立刻按 _sigKey 覆盖重传，确保 stop 生成能拿到，
                // 即使 Java 侧 page.onPopup 因跨 page/iframe 实例未触发也不漏判（二者幂等）。
                if (pick && (pick.popup || pick.download)) {
                  try {
                    if (typeof window.__sigKey === 'function') pick._sigKey = window.__sigKey(pick);
                    if (typeof window.__roleOnPick === 'function') window.__roleOnPick(JSON.stringify(pick));
                    try { console.log('__roleOnPick::' + JSON.stringify(pick)); } catch (_) {}
                  } catch (e) {}
                }
                // 按钮/表单提交：放行真实提交（点击按钮会真实触发业务，业务可能调用 alert/confirm），
                // 不再 preventDefault。原生对话框在业务里同步弹出，故用宏任务回查本次点击是否触发了 dialog。
                // 对齐 page.pause()：dialog 作为该 action 的前置信号，生成 step 时前置插桩 page.onDialog(...)。
                try {
                  setTimeout(function() {
                    try {
                      if (!clickPick) return;
                      var after = window.__pwDialogs ? window.__pwDialogs.length : 0;
                      if (after > dlgBefore) {
                        // 取本次点击后新增的最后一个 dialog（即本次点击触发者）
                        var d = window.__pwDialogs[after - 1];
                        var type = d && d.type ? d.type : 'alert';
                        // 方案1：alert 默认 accept；confirm/prompt 默认 dismiss
                        var action = (type === 'alert') ? 'accept' : 'dismiss';
                        clickPick.dialog = true;
                        clickPick.dialogType = type;
                        clickPick.dialogAction = action;
                        // 按 _sigKey 覆盖内存态并重传，确保 stop 生成能拿到 dialog 标记
                        if (typeof window.__sigKey === 'function') clickPick._sigKey = window.__sigKey(clickPick);
                        if (typeof window.__roleOnPick === 'function') window.__roleOnPick(JSON.stringify(clickPick));
                        try { console.log('__roleOnPick::' + JSON.stringify(clickPick)); } catch (_) {}
                      }
                      // 复选/单选/开关：浏览器拾取模式下点击是"真实穿透"（同步阶段 __enrichState 读到的
                      // pick.checked 是【点击前】状态，但真实点击已使元素 toggle）。若按点击前状态生成
                      // setChecked(旧) 回放，会把元素设回原状态，表现为"checkbox 没选上"。
                      // 故在此宏任务（点击 toggle 已完成后）重新读取【点击后】状态作为 setCheckedTarget，
                      // 覆盖内存态并重传，使生成 setChecked(目标状态) 幂等正确勾选。
                      var pr = (clickPick.role || '').toLowerCase();
                      if (pr === 'checkbox' || pr === 'radio' || pr === 'switch'
                          || pr === 'menuitemcheckbox' || pr === 'menuitemradio' || pr === 'treeitem') {
                        try {
                          // 优先用 __recordPick 内解析好的真实控件（window.__lastPickEl），它已从
                          // composedPath[0] 穿透到 checkbox/radio 本身，状态读取最准；点击的原始 target（t）
                          // 可能是 <label>（label for= 关联的兄弟 input），需再解析。
                          var cb = (window.__lastPickEl) ? window.__lastPickEl : t;
                          if (cb && cb.tagName === 'LABEL') {
                            // 原生 <label for="x"> 关联的兄弟控件：用 label.control / htmlFor 定位；
                            // 嵌套 <label><input> 用 querySelector 向下找。
                            var lblCtrl = null;
                            try { lblCtrl = cb.control; } catch (e) {}
                            if (!lblCtrl && cb.htmlFor) { try { lblCtrl = document.getElementById(cb.htmlFor); } catch (e) {} }
                            if (!lblCtrl) { try { lblCtrl = cb.querySelector('input[type=checkbox],input[type=radio],*[role=checkbox],*[role=radio],*[role=switch],*[role=menuitemcheckbox],*[role=menuitemradio],*[role=treeitem]'); } catch (e) {} }
                            if (lblCtrl) cb = lblCtrl;
                          } else if (cb && cb.closest) {
                            var near = cb.closest('input[type=checkbox],input[type=radio],*[role=checkbox],*[role=radio],*[role=switch],*[role=menuitemcheckbox],*[role=menuitemradio],*[role=treeitem]');
                            if (near) cb = near;
                          }
                          var postChecked;
                          if (cb && (cb.type === 'checkbox' || cb.type === 'radio')) postChecked = !!cb.checked;
                          else if (cb && cb.hasAttribute && cb.hasAttribute('aria-checked')) {
                            var _av = cb.getAttribute('aria-checked'); postChecked = (_av === 'true' || _av === 'mixed');
                          } else postChecked = null;
                          if (postChecked !== null && postChecked !== clickPick.checked) {
                            clickPick.checked = postChecked;            // 同时驱动 setCheckedTarget=checked（Java 侧 setCheckedTarget=checked）
                            if (typeof window.__sigKey === 'function') clickPick._sigKey = window.__sigKey(clickPick);
                            if (typeof window.__roleOnPick === 'function') window.__roleOnPick(JSON.stringify(clickPick));
                            try { console.log('__roleOnPick::' + JSON.stringify(clickPick)); } catch (_) {}
                          }
                        } catch (cbErr) {}
                      }
                    } catch (e) {}
                  }, 0);
                } catch (e) {}
                // 同步把最新 currentStep 落盘：若本次点击触发整页导航，onFrameNavigated 合并恢复时
                // localStorage 已含本次点击（元素因读 Java 内存 javaPickBySig 仍存在，故"元素有、step 也有"）。
                try { window.__persistNow(); } catch (e) {}
              };
              // 双击拾取（dblclick）：对齐 page.pause() 对 doubleClick 动作的录制。
              // dblclick 在两次 click 之后触发，两次 click 已把该元素记录为 click 动作；此处按同一元素
              // 去重复位到同一 pick，追加 dblclick 标记（以最近一次交互为准），生成 step 时输出 doubleClick()。
              // 因 __recordPick 内部已"首次即时回传"一份不含 dblclick 的 pick 到 Java 内存态，
              // 故设标记后须再次经 __roleOnPick 回传（按 _sigKey 覆盖），确保 stop 时以内存态生成能拿到该标记。
              window.__rolePickDblClick = function(event) {
                var t = event.target;
                if (t && t.closest && t.closest('#__rolePanel, #__roleCodeOverlay')) return;
                var pick = window.__recordPick(t, false);
                if (!pick) return;
                pick.dblclick = true;
                try {
                  if (typeof window.__sigKey === 'function') pick._sigKey = window.__sigKey(pick);
                  if (typeof window.__roleOnPick === 'function') window.__roleOnPick(JSON.stringify(pick));
                  try { console.log('__roleOnPick::' + JSON.stringify(pick)); } catch(_){}
                } catch (e) {}
                try { window.__persistNow(); } catch (e) {}
              };
              window.__rolePickKey = function(event) {
                if (event.key === 'Escape') { window.__pickDone = true; return; }
                // 对齐 page.pause() 的 press 录制：输入框聚焦态下按的"实质按键"（Enter/Tab/Escape 之外的
                // 非字符键，以及方向键/功能键）记到最近录入的输入框 pick 上，生成 step 输出 locator.press("Enter")。
                // 纯字符键（a/b/1 等）不记——已由 value 走 fill/type，无需 press。
                if (!window.__rolePickActive) return;
                var inp = window.__activeInputPick;
                if (!inp || !window.__lastPickEl || !isEditable(window.__lastPickEl)) return;
                var k = event.key;
                if (!k) return;
                // 字符键（单字符、可打印）→ 忽略；只收录命名按键与组合修饰键
                var named = /^(Enter|Tab|Escape|Backspace|Delete|ArrowUp|ArrowDown|ArrowLeft|ArrowRight|Home|End|PageUp|PageDown|Space|F\\d+|Shift|Control|Alt|Meta)$/.test(k);
                if (!named) return;
                // 组合键（如 Ctrl+C）只录修饰部分会在 press 里以 "Control+C" 表达；此处仅记录主键，
                // 生成端按需拼接。简单起见记录原始 key 串（已含 Shift+ 等，page.pause 即如此）。
                inp.pressKey = k;
                try { if (typeof window.__sigKey === 'function') inp._sigKey = window.__sigKey(inp); } catch (_) {}
                try { if (window.__renderPicks) window.__renderPicks(); } catch (_) {}
              };
              // 对齐 page.pause() 的键盘可达性：Tab 聚焦到元素后（focusin），在拾取/hover 模式下
              // 记录一次"键盘拾取"，使纯键盘可达、hover 不出现的元素也能被捕获（如 hover 才显形/被遮挡的控件）。
              window.__rolePickFocus = function(event) {
                if (!window.__rolePickActive) return;
                var el = event.target;
                if (!el || el === window || el === document) return;
                if (el.closest && el.closest('#__rolePanel, #__roleCodeOverlay')) return;
                // 【关键修复"点击操作被 focusin 覆盖成 hover"】
                // 鼠标点击元素会连带触发 focusin（聚焦）。原实现以 isHover=true 记录并覆盖，导致用户明明是
                // "点击"，生成代码却变成 locator.hover()（尤其 checkbox/button 被误标 hover）。
                // 修复：focusin 只用于捕获"纯键盘可达、hover/click 都难拾取"的新元素，且一律按「点击（false）」
                // 记录——聚焦的语义是接下来会交互（点击/按键），记成 hover 不符合动作意图。真正的悬停由
                // mouseenter 防抖路径（isHover=true）记录，与 focusin 互不冲突。
                // 同时：若元素已被鼠标 click/mouseenter 记录过，直接放行，避免 focusin 覆盖其 hover/click 类型。
                try {
                  var _fsig = (typeof window.__pickSig === 'function') ? window.__pickSig(el) : '';
                  if (_fsig && window.__sigToPick && window.__sigToPick[_fsig]) return; // 已记录过，勿覆盖
                } catch (_fe) {}
                try { window.__recordPick(el, false); } catch (e) {}
              };
              // 滚动时重定位 hover 高亮框（__hoverBox 为 position:fixed，否则滚动后高亮框会漂移残留）。
              window.__rolePickScroll = function() {
                try { if (window.__lastHoverTarget) __showHoverBox(window.__lastHoverTarget); } catch (e) {}
              };
              // ===== 拖拽手势录制（对齐 page.pause() 的 source.dragTo(target)）=====
              // mousedown 记录「源」元素与按下坐标；mousemove 累积位移；mouseup 时若源≠目标且位移超阈值，
              // 则把目标元素的定位签名挂在源 pick 上并触发记录（源 pick 进入拾取集，目标元素也一并 recordPick），
              // 生成端据 keyToField 反查目标字段名输出 srcField.dragTo(dstField)。
              window.__dragSrcEl = null; window.__dragStartX = 0; window.__dragStartY = 0;
              window.__dragMoved = 0; window.__dragDstKey = null;
              window.__rolePickDragDown = function(e) {
                if (!window.__rolePickActive) return;
                // resolveElement 若未定义（极少数注入片段不完整的 frame），退化为 composedPath()[0]，
                // 杜绝 ReferenceError 中断拖拽拾取。
                var el;
                try {
                  if (typeof resolveElement === 'function') { el = resolveElement(e.target); }
                  else { el = (e.composedPath && e.composedPath()[0]) || e.target; }
                } catch (_re) { el = e.target; }
                if (!el || el === window || el === document) { window.__dragSrcEl = null; return; }
                window.__dragSrcEl = (typeof resolveLabel === 'function') ? resolveLabel(el) : el;
                window.__dragStartX = e.clientX; window.__dragStartY = e.clientY;
                window.__dragMoved = 0; window.__dragDstKey = null;
              };
              window.__rolePickDragUp = function(e) {
                if (!window.__rolePickActive || !window.__dragSrcEl) return;
                var srcEl = window.__dragSrcEl;
                var dstEl = e.target;
                try {
                  if (typeof resolveElement === 'function') dstEl = resolveElement(e.target);
                  else if (e.composedPath && e.composedPath()[0]) dstEl = e.composedPath()[0];
                  if (typeof resolveLabel === 'function') dstEl = resolveLabel(dstEl);
                } catch (_de) {}
                window.__dragSrcEl = null;
                if (!dstEl || dstEl === srcEl) return; // 未跨元素，非拖拽
                if (window.__dragMoved < 12) return;  // 位移过小，视为普通点击
                try {
                  var dstPick = computePick(dstEl);
                  if (!dstPick || !dstPick.key) return;
                  window.__dragDstKey = dstPick.key;
                  // 记录「源」（带 dragDstKey 注入），再记录「目标」本身（若尚未拾取）。
                  try { window.__recordPick(srcEl, true); } catch (_) {}
                  window.__dragDstKey = null;
                  try { window.__recordPick(dstEl, true); } catch (_) {}
                } catch (_) {}
              };
              window.__rolePickDragMove = function(e) {
                if (!window.__dragSrcEl) return;
                window.__dragMoved += Math.abs(e.clientX - window.__dragStartX) + Math.abs(e.clientY - window.__dragStartY);
                window.__dragStartX = e.clientX; window.__dragStartY = e.clientY;
              };
              document.addEventListener('mousedown', window.__rolePickDragDown, true);
              document.addEventListener('mousemove', window.__rolePickDragMove, true);
              document.addEventListener('mouseup', window.__rolePickDragUp, true);
              document.addEventListener('click', window.__rolePickClick, true);
              document.addEventListener('dblclick', window.__rolePickDblClick, true);
              document.addEventListener('keydown', window.__rolePickKey, true);
              document.addEventListener('focusin', window.__rolePickFocus, true);
              document.addEventListener('scroll', window.__rolePickScroll, true);
              // ===== 诊断：额外鼠标事件监听（仅记录、不拦截、不影响拾取）=====
              // 排查"刷新后点击无反应 / 点击卡顿"：记录 mousedown/up/dblclick/contextmenu 是否真到达 document。
              // 双写：window.__roleMouseLog 环形缓冲（导航后可由 Java 经 page.evaluate 回读）
              // + console.log('[roleMouseDiag]...')（由 ctx.onConsoleMessage 实时转发到 Java 日志，前缀过滤不刷屏）。
              if (!window.__roleMouseDebugHooked) {
                window.__roleMouseDebugHooked = true;
                window.__roleMouseLog = [];
                function __mouseDiag(type) {
                  return function(ev) {
                    try {
                      var el = ev.target;
                      var tag = (el && el.tagName) ? el.tagName.toLowerCase() : '?';
                      var id = (el && el.id) ? '#' + el.id : '';
                      var cls = (el && el.className && typeof el.className === 'string')
                        ? '.' + el.className.trim().split(' ').filter(Boolean).slice(0,2).join('.') : '';
                      var entry = { type: type, el: tag + id + cls, active: !!window.__rolePickActive, ts: Date.now() };
                      window.__roleMouseLog.push(entry);
                      if (window.__roleMouseLog.length > 60) window.__roleMouseLog.shift();
                      try { console.log('[roleMouseDiag] ' + JSON.stringify(entry)); } catch(_){}
                    } catch(e) {}
                  };
                }
                document.addEventListener('mousedown', __mouseDiag('mousedown'), true);
                document.addEventListener('mouseup', __mouseDiag('mouseup'), true);
                document.addEventListener('dblclick', __mouseDiag('dblclick'), true);
                document.addEventListener('contextmenu', __mouseDiag('contextmenu'), true);
              }
            })();
            """;
    private static final String START_SCRIPT = concat(concat(START_SCRIPT_A, START_SCRIPT_B1), START_SCRIPT_B2);

    /** 运行时拼接，避免 javac 将 START_SCRIPT_A + START_SCRIPT_B 折叠为单一超长常量（越过 65535 字节上限）。 */
    private static String concat(String a, String b) {
        return a + b;
    }

    /** 关闭拾取模式：移除监听 + 收尾当前 step + 移除提示条 */
    private static final String STOP_SCRIPT = """
            (function() {
              // 区域扫描选区态的收尾：若处于选区态（window.__regionSelecting），先结束选区——
              // 否则选区监听（document 级 capture 的 click/keydown）残留会吞掉面板按钮点击、
              // 且 __regionSelecting 不清除会让 scan/region 按钮一直置灰、状态切不动。
              if (window.__regionSelecting) {
                try { if (window.__roleEndRegionSelect) window.__roleEndRegionSelect(); } catch (e) {}
                try { document.removeEventListener('mousemove', window.__roleRegionMove, true); } catch (e) {}
                try { document.removeEventListener('click', window.__roleRegionClick, true); } catch (e) {}
                try { document.removeEventListener('keydown', window.__roleRegionEsc, true); } catch (e) {}
                try { document.removeEventListener('keydown', window.__roleRegionEscB, false); } catch (e) {}
                window.__regionSelecting = false;
                window.__scanMode = null;   // 清除模式标识，回到"无模式"
                try { if (window.__roleRefreshToggle) window.__roleRefreshToggle(); } catch (e) {}
              }
              // 关键修复：无条件移除监听并置位（不再因 __rolePickActive 已为 false 而早退）。
              // 早退会在"Java 端 active[0] 与浏览器端 __rolePickActive 因竞态不一致"时，
              // 导致应停止的页面监听残留、状态错乱，进而出现"停止后再开始拾取不了"。
              // removeEventListener 对同一函数引用幂等，重复调用安全。
              document.removeEventListener('click', window.__rolePickClick, true);
              document.removeEventListener('mousemove', window.__rolePickMove, true);
              document.removeEventListener('keydown', window.__rolePickKey, true);
              document.removeEventListener('focusin', window.__rolePickFocus, true);
              document.removeEventListener('scroll', window.__rolePickScroll, true);
              window.__rolePickActive = false;
              // 停止时也清整页扫描态，避免异常路径下 scan/region 按钮卡死置灰。
              window.__pageScanning = false;
              try { if (window.__roleRefreshToggle) window.__roleRefreshToggle(); } catch (e) {}
              // 收尾当前 step：停止拾取即把"当前选中（已勾选）的候选"封装成 step 并入 __steps。
              // 面板勾选/实时点选都写入 window.__currentStep（选择集），停止时一次性打包为 step；
              // 整个选择（无论跨多少个页面）合并为【一个 step】——step 的唯一边界是"开始→停止"
              // （或一次「封装为步骤」），弹窗打开/关闭、页面跳转都只是同一 step 内的交互。
              // 优先用 __packageStep（与面板"封装为步骤"同一逻辑，保证顺序/去重一致）；兜底直接打包。
              if (window.__currentStep && window.__currentStep.length) {
                if (typeof window.__packageStep === 'function') {
                  window.__packageStep();
                } else {
                  if (!window.__steps) window.__steps = [];
                  window.__steps.push({pageClass:(window.__rolePageName||''), picks:window.__currentStep});
                }
              }
              window.__currentStep = null;
              // 收起实时悬停高亮框
              try { var __hb = document.getElementById('__roleHoverBox'); if (__hb) __hb.style.display = 'none'; } catch (e) {}
              // 清除区域选择遗留的页面高亮框（绿色已选/青色悬停），停止拾取时一并清掉，避免残留。
              try { if (typeof window.__clearRegionOutlines === 'function') window.__clearRegionOutlines(); } catch (e) {}
              // 清除所有 frame（含主文档 + 各层 iframe）内元素的拾取描边（outline）与聚焦边框，
              // 避免"取消拾取 / 停止拾取后，iframe 内元素的蓝色/黄色边框未消失"的残留。
              try {
                (function __stopAll(win) {
                  if (!win || !win.document) return;
                  var d = win.document;
                  // 【关键修复"停止拾取后 iframe 内仍可点击拾取"】
                  // 原 stop 只移除当前 frame（顶层）的 document 监听，iframe 内自己的 document 监听未移除，
                  // 停止拾取后 iframe 内仍能点击拾取、边框也仍会高亮。这里在遍历每个 frame 时，
                  // 一并移除该 frame 的拾取/扫描监听，并把 __rolePickActive 置 false，彻底关闭所有 frame 的拾取态。
                  var rl = window.__rolePickClick, rm = window.__rolePickMove, rk = window.__rolePickKey,
                      rf = window.__rolePickFocus, rs = window.__rolePickScroll,
                      rp = window.__rolePickPointer, rpo = window.__rolePickOver, rr = window.__rolePickRegionClick;
                  d.removeEventListener('click', rl, true);
                  d.removeEventListener('mousemove', rm, true);
                  d.removeEventListener('keydown', rk, true);
                  d.removeEventListener('focusin', rf, true);
                  d.removeEventListener('scroll', rs, true);
                  if (rp) d.removeEventListener('pointerdown', rp, true);
                  if (rpo) d.removeEventListener('mouseover', rpo, true);
                  if (rr) d.removeEventListener('click', rr, true);
                  try { if (window.__rolePickLeave) d.removeEventListener('mouseleave', window.__rolePickLeave, false); } catch (_) {}
                  try { win.__rolePickActive = false; } catch (_) {}
                  var nodes = d.querySelectorAll('[_rolepick_outline]');
                  for (var i = 0; i < nodes.length; i++) {
                    try { nodes[i].style.outline = ''; nodes[i].style.outlineOffset = ''; nodes[i].removeAttribute('_rolepick_outline'); } catch (_) {}
                  }
                  // 兜底：清掉仍带显式拾取色描边的元素（部分老路径未打标记属性）
                  var all = d.querySelectorAll('*');
                  for (var j = 0; j < all.length; j++) {
                    var s = all[j].style;
                    if (s && (s.outline.indexOf('ffeb3b') >= 0 || s.outline.indexOf('29b6f6') >= 0 || s.outline.indexOf('ff9800') >= 0)) {
                      s.outline = ''; s.outlineOffset = '';
                    }
                  }
                  // 隐藏当前 frame 的实时悬停高亮框（青色 #29b6f6 边框）
                  try { var hb = d.getElementById('__roleHoverBox'); if (hb) hb.style.display = 'none'; } catch (_) {}
                  var fr = win.frames || [];
                  for (var k = 0; k < fr.length; k++) { try { __stopAll(fr[k]); } catch (_) {} }
                })(window);
              } catch (e) {}
            })();
            """;

    /** 弹出可复制代码面板（参数 code 为生成的源码） */
    private static final String SHOW_PANEL_SCRIPT = """
            (function() {
              var code = window.__pickerCode || '';
              var old = document.getElementById('__roleCodeOverlay');
              if (old) old.remove();
              window.__codePanelClosed = false;

              var overlay = document.createElement('div');
              overlay.id = '__roleCodeOverlay';
              overlay.style.cssText = 'position:fixed;inset:0;z-index:2147483647;' +
                'background:rgba(0,0,0,.55);display:flex;align-items:center;justify-content:center;';

              var panel = document.createElement('div');
              panel.style.cssText = 'position:fixed;left:50%;top:50%;transform:translate(-50%,-50%);' +
                'width:min(860px,92vw);max-height:86vh;display:flex;flex-direction:column;' +
                'background:#1e1e1e;color:#e0e0e0;border-radius:8px;box-shadow:0 8px 30px rgba(0,0,0,.5);' +
                'font:13px/1.5 Consolas,Monaco,monospace;overflow:hidden;';

              var header = document.createElement('div');
              header.style.cssText = 'padding:10px 14px;background:#1e88e5;color:#fff;font-weight:bold;' +
                'display:flex;align-items:center;justify-content:space-between;cursor:move;';
              var title = document.createElement('span');
              title.textContent = '生成的 @RoleElement Page 代码';
              header.appendChild(title);
              var status = document.createElement('span');
              status.style.cssText = 'font-weight:normal;font-size:12px;opacity:.9;';
              header.appendChild(status);

              (function() {
                var dragging = false, sx = 0, sy = 0, ox = 0, oy = 0;
                header.addEventListener('mousedown', function(e) {
                  if (e.button !== 0) return;
                  dragging = true;
                  sx = e.clientX; sy = e.clientY;
                  ox = panel.offsetLeft; oy = panel.offsetTop;
                  e.preventDefault();
                });
                document.addEventListener('mousemove', function(e) {
                  if (!dragging) return;
                  var nl = ox + e.clientX - sx;
                  var nt = oy + e.clientY - sy;
                  // 视口边界约束：保证标题栏始终留在屏幕内，不会被顶边/侧边裁掉而抓不住
                  var w = panel.offsetWidth, h = panel.offsetHeight;
                  if (nl < 0) nl = 0;
                  if (nl + w > window.innerWidth) nl = window.innerWidth - w;
                  if (nt < 0) nt = 0;
                  if (h <= window.innerHeight && nt + h > window.innerHeight) nt = window.innerHeight - h;
                  panel.style.left = nl + 'px';
                  panel.style.top = nt + 'px';
                  panel.style.right = 'auto';
                  panel.style.bottom = 'auto';
                  panel.style.transform = 'none';
                });
                document.addEventListener('mouseup', function() { dragging = false; });
              })();

              var ta = document.createElement('textarea');
              ta.readOnly = true;
              ta.value = code;
              ta.style.cssText = 'flex:1;min-height:340px;margin:0;padding:12px 14px;border:0;resize:none;' +
                'background:#1e1e1e;color:#d4d4d4;font:13px/1.55 Consolas,Monaco,monospace;outline:none;white-space:pre;';

              var footer = document.createElement('div');
              footer.style.cssText = 'padding:10px 14px;background:#252526;display:flex;gap:10px;justify-content:flex-end;';

              function mkBtn(text, bg) {
                var b = document.createElement('button');
                b.textContent = text;
                b.style.cssText = 'padding:7px 18px;border:0;border-radius:5px;cursor:pointer;' +
                  'font:13px/1 sans-serif;color:#fff;background:' + bg + ';';
                return b;
              }
              var copyBtn = mkBtn('复制代码', '#43a047');
              var closeBtn = mkBtn('关闭', '#616161');

              copyBtn.onclick = function() {
                function ok() {
                  status.textContent = '已复制到剪贴板 ✔';
                  status.style.color = '#00e676';
                  status.style.fontWeight = 'bold';
                  status.style.fontSize = '14px';
                  status.style.background = 'rgba(0,230,118,.18)';
                  status.style.borderRadius = '3px';
                  status.style.padding = '1px 6px';
                  status.style.textShadow = '0 0 6px rgba(0,230,118,.6)';
                  copyBtn.textContent = '已复制';
                }
                try {
                  if (navigator.clipboard && navigator.clipboard.writeText) {
                    navigator.clipboard.writeText(code).then(ok, function() { fallback(); });
                  } else { fallback(); }
                } catch (e) { fallback(); }
                function fallback() {
                  ta.focus(); ta.select();
                  try { document.execCommand('copy'); ok(); }
                  catch (e2) { status.textContent = '复制失败，请手动选中复制'; }
                }
              };
              closeBtn.onclick = function() {
                window.__codePanelClosed = true;
                overlay.remove();
              };

              footer.appendChild(copyBtn);
              footer.appendChild(closeBtn);
              panel.appendChild(header);
              panel.appendChild(ta);
              panel.appendChild(footer);
              overlay.appendChild(panel);
              (document.body || document.documentElement).appendChild(overlay);
            })();
            """;

    /**
     * 注入式常驻面板（docked 在页面右侧，像 DevTools 那样占住窗口一侧、把页面内容挤到左边，
     * 不另开窗口、不盖内容）。由 {@link #openPanel} 通过 addInitScript 注入并随导航重建；
     * 命令通过 {@code window.__panelCmds} 入列，由 Java 主循环轮询消费。
     */
    private static final String PANEL_SCRIPT_A = """
    (function() {
      // 仅当 openPanel 运行期间（由 localStorage 开关标记）才注入面板；
      // 这样刷新/导航后 addInitScript 会自动重建面板，而正常访问不受影响。
      // 注意：外层必须用 (function(){...})() 而非 (() => {...})() —— 后者以 "(() =>" 开头，
      // 会被 Playwright 的 evaluate/addInitScript 误判成"箭头函数"并错误解析尾部的 ")();"，
      // 抛出 SyntaxError: Invalid or unexpected token。
      // 门禁：正常访问不打面板。openPanel/followPage 会显式置位 __rolePanelEnabled='1'；
      // 跨源新页面 localStorage 可能为空或不可写，故同时允许 window.__rolePanelForce 兜底
      // （由 followPage 注入），确保新页面无论如何都能重建面板。
      // 【关键修复"嵌套 iframe 元素序号 - / 不进 step"】
      // 门禁原来会让 iframe（frameOne）的 message 监听一并早退：frameTwo（grandchild）的 postMessage
      // 先发到 frameOne，但 frameOne 的 message 监听没注册 → 不向上转发到顶层 → 顶层 __currentStep
      // 无 frameTwo 元素 → 面板序号 '-'、停止时不入选 step（Java 内存态却因绑定桥直接回传而有值，
      // 表现为"能回传但不在面板/step"）。而 frameOne 元素能进 step，是因 frameOne→top 是直达、
      // 由顶层监听接收，不依赖 frameOne 自身的转发。
      // 修复：把"拾取聚合/转发的 message 监听"从面板门禁中解耦——无论是否渲染面板，iframe 都应
      // 无条件注册该监听，确保嵌套 iframe 的 pick 能逐层转发到顶层进入 __currentStep。面板 UI 门禁
      // 保持不变（见下方 __rolePanelUI 分支）。
      var __rolePanelUI = false;
      try { __rolePanelUI = (localStorage.getItem('__rolePanelEnabled') === '1' || !!window.__rolePanelForce); } catch (e) { __rolePanelUI = false; }
      // 顶层面板接收来自 iframe（含跨源）的拾取：iframe 内点击 push 进的是 iframe 自己的 window.__rolePicks，
      // 主框架可见面板读不到 → 表现为"Java 内存态增长、面板空白"。iframe 经 postMessage 把 pick 发给父窗口，
      // 此处接收并聚合进主框架 window.__rolePicks + 渲染。纯前端同步，无需 Java 主循环轮询。
      window.addEventListener('message', function(ev) {
        try {
          var d = ev.data;
          if (d && d.__roleScanRequest) {
            // 【关键修复"整页/区域扫描穿透 iframe"——跨源 iframe 自扫】
            // 父 frame 无法 JS 直调跨源 iframe 的 __roleScanPage，改用 postMessage 下发扫描请求；
            // 本 frame（可能为任意层 iframe）收到后在自己的上下文执行整页扫描（传 null=扫本 frame 整页），
            // 结果 push 进本 frame 的 __rolePicks 并经绑定桥/console 兜底回传 Java。与 Java 侧
            // 按 page.frames() 逐帧扫描殊途同归，但专为浏览器侧触发的区域扫描跨源穿透而设。
            try {
              if (typeof window.__roleScanPage === 'function' && window.self !== window.top) {
                window.__roleScanPage(null);
              }
            } catch (_rsErr) {}
            return;
          }
          if (!d || d.__rolePickMsg !== true || !d.pick) return;
          try {
            var __dbg = (d.pick && (d.pick.role || d.pick._sig || d.pick.name)) || '';
            var __dbgKey = d.pick && d.pick._sigKey;
            if (typeof console !== 'undefined' && console.log)
              console.log('[rolePick][msg-in] selfTop=' + (window.self === window.top)
                + ' srcTop=' + (ev.source && ev.source === window.self)
                + ' role=' + __dbg + ' sigKey=' + (__dbgKey || '') + ' pickActive=' + (window.__rolePickActive)
                + ' scanning=' + (window.__scanning) + ' href=' + (location && location.href));
          } catch (_dbgErr) {}
          // 安全加固（本地开发工具场景）：排除顶层自身的"自环"消息（理论上不会发生），
          // 其余带 __rolePickMsg 标记的 pick 一律接纳——包括 srcdoc/跨源子 frame。
          // 注：srcdoc iframe 在某些情况下 ev.source 为 null/非标准 Window，若强校验 ev.source.postMessage
          // 反而会误杀合法 iframe 拾取（实测 postMessage 已发出但被守卫丢弃）。消息真伪以 __rolePickMsg 标记为准。
          if (window.self === window.top && ev.source === window.self) {
            return;   // 顶层自身发给自己：拒绝（避免自环）
          }
          // 向上中继：非顶层 frame 收到子 frame 的 pick 后，继续向自己的父窗口转发，
          // 直到顶层（顶层才真正聚合进主框架 window.__rolePicks）。这样即使跨多级嵌套 iframe /
          // Playwright 下 window.top 直达投递异常的场景，pick 也能逐层可靠上送。
          if (window.self !== window.top) {
            try { window.parent.postMessage(d, '*'); } catch (e) {}
          }
          window.__rolePicks = window.__rolePicks || [];
          window.__rolePickSigs = window.__rolePickSigs || {};
          var p = d.pick;
          var key = p._sigKey || p._sig;
          if (key && window.__rolePickSigs[key]) return;
          if (key) window.__rolePickSigs[key] = true;
          window.__rolePicks.push(p);
          // 关键修复：iframe（含嵌套 frame）内的拾取元素经 postMessage 上送顶层后，必须回传 Java 权威内存态
          // （javaPickBySig），否则仅停留在浏览器侧 window.__rolePicks——顶层面板虽能渲染，但 stop 时按 Java
          // 内存态生成代码会漏掉这些 iframe 元素（表现为"页面元素清单不含 frame 内元素"）。
          // 与 __recordPick 内回传保持同一套（_sigKey 去重键 + __roleOnPick 回传 + console 兜底），且幂等。
          if (window.self === window.top) {
            try {
              if (typeof window.__sigKey === 'function') p._sigKey = window.__sigKey(p);
              if (typeof window.__roleOnPick === 'function') window.__roleOnPick(JSON.stringify(p));
            } catch (e) {}
            try { console.log('__roleOnPick::' + JSON.stringify(p)); } catch (_) {}
          }
          // 与主框架实时点选一致：拾取激活态下，iframe 内的点选也自动入选当前 step（选择集），
          // 用户无需再到面板重复勾选；整页扫描期间的 iframe 候选仍由用户勾选决定。
          // 【关键修复"checkbox 被默认勾选进选择集"】扫描批量回传上送的候选带 __isScan 标记：
          // 尽管此时 __scanning 已置 false，但 d.__isScan 为 true 表明这是扫描产生的候选，
          // 只聚合进 __rolePicks 供面板展示，绝不入选 __currentStep（否则 checkbox 等被默认勾选）。
          // 【关键修复"用户点击扫描候选不进 step"】与主框架 __recordPick 的 2169 行一致：
          // 点击（非扫描、非 __isScan 的 postMessage）即使元素已在 __rolePickSigs（扫描候选），
          // 只要不在当前 __currentStep 中就入选 step（用户主动点击 = 明确选择）；已入选的重复点击除外。
          if (window.__rolePickActive && window.__currentStep && !window.__scanning && !d.__isScan) {
            var __pkey = (typeof window.__sigKey === 'function') ? window.__sigKey(p) : (p._sigKey || p._sig || '');
            var __pinStep = false;
            if (__pkey) {
              for (var __qi = 0; __qi < window.__currentStep.length; __qi++) {
                try {
                  var __q = window.__currentStep[__qi];
                  if (__q && (__q._sigKey === __pkey
                      || (__q._sig && __pkey.indexOf(__q._sig) !== -1)
                      || (typeof window.__sigKey === 'function' && window.__sigKey(__q) === __pkey))) {
                    __pinStep = true; break;
                  }
                } catch (e) {}
              }
            }
            if (!__pinStep) window.__currentStep.push(p);
          }
          window.__renumberStep();
          if (window.__renderPicks) window.__renderPicks();
        } catch (e) {}
      });
      // 面板 UI 仅顶层文档渲染：iframe / 嵌套 frame 内不应再构建面板（避免面板被嵌入子 frame）。
      // 非顶层 frame 到此为止——上面注册的 message 监听器已能把子 frame 的 pick 逐层上送顶层聚合，
      // 但不再调用 build() 建面板，保证整页只有一个可见面板（顶层）。
      // 注意：__renumberStep 是 __recordPick 的核心依赖（选择集连续编号），与面板 DOM 无关，
      // 必须无条件定义（含 iframe 子文档），否则子 frame 内拾取调用 __renumberStep 报 "is not a function"。
      // 故在此 return 之前定义，与 build() 内的面板 UI 构建解耦。
      window.__renumberStep = function() {
        try {
          var cs = window.__currentStep || [];
          for (var i = 0; i < cs.length; i++) {
            if (cs[i]) cs[i].seq = i + 1;
          }
        } catch (e) {}
      };
      if (window.self !== window.top) return;
      // 顶层但面板门禁未开（正常访问，localStorage 开关未置位）→ 仅注册 message 监听用于嵌套 iframe
      // 拾取聚合/转发，不构建面板 UI。
      if (!__rolePanelUI) return;
      function build() {
      var old = document.getElementById('__rolePanel');
      if (old) old.remove();
      // 重建面板时清理上一次遗留的定时器，避免叠加
      if (window.__roleToggleTimer) { clearInterval(window.__roleToggleTimer); window.__roleToggleTimer = null; }
      window.__panelCmds = window.__panelCmds || [];
      window.__pickDone = false;

      function pushCmd(c) {
        // 优先用 exposeFunction 暴露的 Java 回调（事件驱动，无需 Java 轮询）；
        // 万一绑定尚未就绪则回退到 __panelCmds 队列，保证命令不丢。
        if (typeof window.__rolePickerCmd === 'function') {
          try { window.__rolePickerCmd(c); return; } catch (e) {}
        }
        window.__panelCmds = window.__panelCmds || [];
        window.__panelCmds.push(c);
      }

      // 内联 SVG 图标（24x24，currentColor 继承按钮文字色）
      function svg(inner) {
        return '<svg width="18" height="18" viewBox="0 0 24 24" fill="currentColor" xmlns="http://www.w3.org/2000/svg">' + inner + '</svg>';
      }
      var ICON = {
        start: svg('<path d="M8 5v14l11-7z"/>'),                                                // ▶ 开始
        stop:  svg('<path d="M6 6h12v12H6z"/>'),                                               // ⏹ 停止
        copy:  svg('<path d="M16 1H4a2 2 0 0 0-2 2v12h2V3h12V1zm3 4H8a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h11a2 2 0 0 0 2-2V7a2 2 0 0 0-2-2z"/>'), // 📋 复制
        abort: svg('<path d="M13 3h-2v10h2V3zm4.83 2.17-1.42 1.42A7 7 0 1 1 7.58 6.59L6.17 5.17a9 9 0 1 0 11.66 0z"/>'), // ⏻ 终止
        close: svg('<path d="M18.3 5.7 12 12l6.3 6.3-1.4 1.4L10.6 13.4 4.3 19.7 2.9 18.3 9.2 12 2.9 5.7 4.3 4.3l6.3 6.3 6.3-6.3z"/>'), // ✕ 关闭
        hover: svg('<path d="M7 2l12 7-5 1.4L13 18 7 2z"/>'),                                         // ⤢ 悬停拾取
        scan:  svg('<path d="M15.5 14h-.79l-.28-.27a6.5 6.5 0 1 0-.7.7l.27.28v.79l5 4.99L20.49 19l-4.99-5zm-6 0A4.5 4.5 0 1 1 14 9.5 4.5 4.5 0 0 1 9.5 14z"/>'), // 🔍 扫描整页
        region: svg('<path d="M3 3h6v2H5v4H3V3zm12 0h6v6h-2V5h-4V3zM3 15h2v4h4v2H3v-6zm16 0h2v6h-6v-2h4v-4z"/>') // ▢ 框选区域
      };
      // 统一的图标按钮：圆形/圆角方块、hover 提亮、带 title 作为无障碍提示
      function mkIconBtn(svgHtml, bg, title, onClick) {
        var b = document.createElement('button');
        b.type = 'button';
        b.title = title;
        b.innerHTML = svgHtml;
        b.style.cssText = 'width:34px;height:34px;padding:0;border:0;border-radius:8px;cursor:pointer;' +
          'display:inline-flex;align-items:center;justify-content:center;color:#fff;background:' + bg + ';' +
          'transition:filter .15s;';
        b.onmouseenter = function(){ b.style.filter = 'brightness(1.12)'; };
        b.onmouseleave = function(){ b.style.filter = 'none'; };
        b.onclick = onClick;
        return b;
      }

              // docked 模式：把页面内容挤到左侧，给面板留出 420px 右侧空间（像 DevTools，不盖内容）。
              document.documentElement.style.overflowX = 'hidden';
              if (document.body) document.body.style.marginRight = '420px';

              var panel = document.createElement('div');
              panel.id = '__rolePanel';
              panel.style.cssText = 'position:fixed;top:0;right:0;width:420px;height:100vh;' +
                'display:flex;flex-direction:column;pointer-events:auto;background:#1e1e1e;color:#e0e0e0;' +
                'border-left:1px solid #000;box-shadow:-8px 0 24px rgba(0,0,0,.35);' +
                'font:13px/1.5 Consolas,Monaco,monospace;overflow:hidden;z-index:2147483647;';

              // 左侧可拖拽改变宽度的握把（col-resize）：拖动时同步改面板宽度与页面右侧预留空间
              // 注意：grip 必须放在 panel 创建之后，否则 panel 此时为 undefined，调用 panel.appendChild 会崩溃。
              var grip = document.createElement('div');
              grip.title = '拖动调整面板宽度';
              grip.style.cssText = 'position:absolute;left:0;top:0;width:6px;height:100%;cursor:col-resize;'
                + 'background:rgba(255,255,255,.06);z-index:5;';
              grip.onmousedown = function(ev) {
                ev.preventDefault();
                var startX = ev.clientX, startW = panel.offsetWidth;
                function move(e) {
                  var w = startW + (startX - e.clientX);
                  if (w < 280) w = 280; if (w > 900) w = 900;
                  panel.style.width = w + 'px';
                  if (document.body) document.body.style.marginRight = w + 'px';
                }
                function up() { document.removeEventListener('mousemove', move); document.removeEventListener('mouseup', up); }
                document.addEventListener('mousemove', move);
                document.addEventListener('mouseup', up);
              };
              panel.appendChild(grip);

              var header = document.createElement('div');
              header.style.cssText = 'padding:10px 12px;background:#1e88e5;color:#fff;font-weight:bold;' +
                'display:flex;align-items:center;gap:10px;cursor:move;';
              var title = document.createElement('span');
              title.style.cssText = 'flex:1;min-width:0;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;';
              var nf = window.__nlsFiles || [];
              var pn = window.__rolePageName || '';
              title.textContent = 'RoleElement 拾取器'
                + (pn ? '  › ' + pn : '')
                + (nf.length ? '  (files=' + (nf.length === 1 ? nf[0] : nf[0] + ' (+' + (nf.length - 1) + ')') + ')' : '');
              var status = document.createElement('span');
              status.id = '__roleStatus';
              // 【修复状态文字颜色"深绿看不清"】
              // 面板 header 是蓝色（#1e88e5），title 用 color:#fff 显式声明以保对比度；状态条原本只设
              // opacity 与布局依赖继承，偶发受宿主页面/字体/颜色继承影响呈现深绿，与蓝色背景几乎不可读。
              // 修复：显式 color:#fff（白色）确保任何继承/上下文下都清晰显示在蓝色 header 上。
              status.style.cssText = 'font-weight:normal;font-size:12px;color:#fff;opacity:.95;' +
                'white-space:nowrap;overflow:hidden;text-overflow:ellipsis;max-width:190px;flex-shrink:0;';
              status.textContent = '就绪：点 ▶ 开始拾取并在页面点击元素';
              // 关闭面板：标题栏右侧 X 图标（对齐 page.pause() 的 inspector 关闭按钮）
              var closeBtn = mkIconBtn(ICON.close, 'transparent', '关闭面板', function() {
                if (window.__roleToggleTimer) { clearInterval(window.__roleToggleTimer); window.__roleToggleTimer = null; }
                // 关闭面板时还原页面布局（撤销 docked 给 body/html 预留的右侧空间）
                try { document.body.style.marginRight = ''; document.documentElement.style.overflowX = ''; } catch (e) {}
                pushCmd('done');
              });
              closeBtn.style.background = 'transparent';
              closeBtn.onmouseenter = function(){ closeBtn.style.background = 'rgba(255,255,255,.2)'; closeBtn.style.filter = 'none'; };
              closeBtn.onmouseleave = function(){ closeBtn.style.background = 'transparent'; closeBtn.style.filter = 'none'; };
              closeBtn.onmousedown = function(e){ e.stopPropagation(); };
              header.appendChild(title);
              header.appendChild(status);
              header.appendChild(closeBtn);

              var toolbar = document.createElement('div');
              toolbar.style.cssText = 'padding:10px 14px;background:#252526;display:flex;gap:8px;align-items:center;';
              // 开始/停止合并为同一个切换控件：空闲显示 ▶ 开始，拾取中显示 ⏹ 停止
              var toggleBtn = mkIconBtn(ICON.start, '#43a047', '开始拾取', function togglePick() {
                // 乐观即时反馈：点击后立刻让切换控件呈现"目标态"，无需等待 Java 往返 + START_SCRIPT 执行，
                // 消除"点了开始却要等往返才变成停止"的迟滞感（refreshToggle 会读取 __rolePickWanted）。
                // 关键修复：用"当前显示态"（真实激活态 ∪ 乐观意图）翻转来决定命令，而非直接读可能滞后的
                // window.__rolePickActive。否则快速连点 / Java 往返延迟期间 window.__rolePickActive 未及时更新，
                // 两次点击会读到相同旧值、发出相同命令（都 start 或都 stop）而非翻转，
                // 表现即"停止按钮有时点不动、停止后再点开始却拾取不了"。
                var willStart = !(window.__rolePickActive || window.__rolePickWanted);
                window.__rolePickWanted = willStart;
                pushCmd(willStart ? 'start' : 'stop');
              });
              // 注：悬停拾取模式已移除（按需求去除 hover 图标）。

              // ---- 顶部 Tab：页面元素 / 页面类 / 步骤代码 ----
              var tabBar = document.createElement('div');
              tabBar.style.cssText = 'display:flex;gap:0;background:#252526;border-bottom:1px solid #1b1b1b;';
              function mkTab(label, id, active) {
                var t = document.createElement('button');
                t.type = 'button';
                t.textContent = label;
                // 【优化当前 Tab 聚焦背景颜色】
                // 旧实现 active 背景 #1e1e1e（深灰）与非 active #2d2d2d（稍浅灰）差异极小，3 个 tab 谁聚焦难以一眼分辨。
                // 优化：聚焦 Tab 用蓝色（与 header #1e88e5 同色系 #1565c0）背景 + 白色加粗文字 + 3px 亮蓝下边框，
                // 与非聚焦（#2d2d2d 灰底 + 普通字重）形成强烈对比，聚焦一目了然。
                t.style.cssText = 'flex:1;padding:8px 0;border:0;cursor:pointer;font:13px/1.4 sans-serif;font-weight:' + (active ? 'bold' : 'normal') + ';' +
                  'color:#fff;background:' + (active ? '#1565c0' : '#2d2d2d') + ';' +
                  'border-bottom:' + (active ? '3px solid #42a5f5' : '3px solid transparent') + ';';
                t.onclick = function() { window.__roleActiveTab = id; showTab(); };
                return t;
              }
              var tabPage = mkTab('页面元素', 'page', true);
              var tabClass = mkTab('页面类', 'class', false);
              var tabStep = mkTab('步骤代码', 'step', false);
              tabBar.appendChild(tabPage);
              tabBar.appendChild(tabClass);
              tabBar.appendChild(tabStep);
              window.__roleActiveTab = window.__roleActiveTab || 'page';

              // 焦点感知复制：优先按当前真实 DOM 焦点（activeElement）判断用户"聚焦在哪一块"，
              // 再复制对应区块内容；若焦点不在任何代码区，则回退到当前激活的 Tab（点击切换的 Tab）。
              function __activeScope() {
                // 返回当前焦点所在区块：'class' / 'step' / 'page' / null（都不在，回退 tab）
                var a = document.activeElement;
                if (!a || !a.id) return null;
                if (a.id.indexOf('__roleCodeArea__') === 0) return 'class';
                if (a.id.indexOf('__roleCodeArea2__') === 0) return 'step';
                // 焦点落在某 Tab 内容容器或其子节点（textarea 之外）时，按容器归属判定
                if (classContent && classContent.contains(a)) return 'class';
                if (stepContent && stepContent.contains(a)) return 'step';
                if (pageContent && pageContent.contains(a)) return 'page';
                return null;
              }
              var copyBtn = mkIconBtn(ICON.copy, '#2e7d32', '复制代码（焦点在哪块就复制哪块）', function() {
                var ta = null, code = '';
                try {
                  // 1) 焦点优先：焦点落在某块代码区/容器，跟随焦点；否则回退到当前激活 Tab
                  var scope = __activeScope() || window.__roleActiveTab;
                  if (scope === 'class') {
                    var k = window.__roleClassSubTabBar_active;
                    ta = k ? document.getElementById('__roleCodeArea__' + k) : null;
                    if (!ta) ta = document.querySelector('#__roleClassAreas textarea');
                    code = ta ? ta.value : '';
                  } else if (scope === 'step') {
                    var k2 = window.__roleStepSubTabBar_active;
                    ta = k2 ? document.getElementById('__roleCodeArea2__' + k2) : null;
                    if (!ta) ta = document.querySelector('#__roleStepAreas textarea');
                    code = ta ? ta.value : '';
                  } else {
                    // "页面元素"Tab：复制当前子 Tab（页面类过滤）下的元素清单文本，
                    // 每行与列表展示一致（strategy/role/name/id/css/index/标记），便于外部粘贴核对。
                    var act = window.__roleActivePageClass;
                    var lines = [];
                    (window.__rolePicks || []).forEach(function(p) {
                      if (!p) return;
                      var pc = p._pageClass || (window.__rolePageName || '未知页');
                      if (pc !== act) return;
                      var s = (p.strategy || 'role');
                      if (p.role) s += ' role=' + p.role;
                      if (p.name) s += ' name="' + p.name + '"';
                      if (p.key) s += ' key=' + p.key;
                      if (p.id) s += ' id=' + p.id;
                      if (p.css) s += ' css=' + p.css;
                      if (p.index != null && p.index >= 0) s += ' #' + p.index;
                      if (p.popup) s += ' [popup]';
                      if (p.download) s += ' [download]';
                      if (p.hover) s += ' [hover]';
                      if (p.dblClick) s += ' [dbl]';
                      lines.push(pc + ' | ' + s);
                    });
                    code = lines.join('\\n');
                    if (!code) { status.textContent = '当前无可复制的页面元素'; return; }
                  }
                } catch (e) {}
                function ok() {
                  status.textContent = '已复制 ✔';
                  status.style.color = '#00e676';
                  status.style.fontWeight = 'bold';
                  status.style.fontSize = '14px';
                  status.style.background = 'rgba(0,230,118,.18)';
                  status.style.borderRadius = '3px';
                  status.style.padding = '1px 6px';
                  status.style.textShadow = '0 0 6px rgba(0,230,118,.6)';
                  copyBtn.title = '已复制';
                }
                try {
                  if (navigator.clipboard && navigator.clipboard.writeText) {
                    navigator.clipboard.writeText(code).then(ok, function() { fb(); });
                  } else { fb(); }
                } catch (e) { fb(); }
                function fb() {
                  // 兜底：有文本框则选中复制；"页面元素"Tab 无文本框时用临时 textarea 承载再 execCommand。
                  if (ta) { ta.focus(); ta.select(); try { document.execCommand('copy'); ok(); }
                  catch (e2) { status.textContent = '复制失败，请手动复制'; } return; }
                  try {
                    var tmp = document.createElement('textarea');
                    tmp.value = code;
                    tmp.style.cssText = 'position:fixed;left:-9999px;top:0;';
                    document.body.appendChild(tmp);
                    tmp.focus(); tmp.select();
                    document.execCommand('copy');
                    document.body.removeChild(tmp);
                    ok();
                  } catch (e3) { status.textContent = '复制失败，请手动复制'; }
                }
              });
              // 整页扫描：一次性收全当前页所有"带可访问名的语义角色元素"（heading/link/button/img/...），
              // 对齐 page.pause 的 role-centric 理念但更完整（点击录制只录点过的，扫描把整页语义角色全收）。
              // 走 Java 命令确保拾取库已注入后执行 window.__roleScanPage()，拾取结果与点击同一链路，随后点 ⏹ 停止生成。
              var scanBtn = mkIconBtn(ICON.scan, '#7e57c2', '扫描整页：一次性收全当前页所有带名称的语义角色元素（随后点停止生成代码）', function() {
                window.__pageScanning = true;   // 进入整页扫描态：立即置灰 scan/region，扫描完成自动恢复
                try { refreshToggle(); } catch (e) {}
                pushCmd('scan');
              });
              var regionBtn = mkIconBtn(ICON.region, '#0097a7', '区域扫描：点击按钮后，鼠标移入业务区域即聚焦，点击区域即扫描并展示该区域内元素；按 Esc 结束选区（扫描中「扫描整页」将置灰）', function() {
                window.__regionSelecting = true;   // 进入区域选择态：立即置灰 scan/region，防止冲突
                try { refreshToggle(); } catch (e) {}
                pushCmd('scanRegion');
              });
              var abortBtn = mkIconBtn(ICON.abort, '#e53935', '终止运行', function() { pushCmd('abort'); });
              toolbar.appendChild(toggleBtn);
              toolbar.appendChild(scanBtn);
              toolbar.appendChild(regionBtn);
              toolbar.appendChild(copyBtn);
              toolbar.appendChild(abortBtn);

              // 根据 window.__rolePickActive 实时同步切换控件的状态（图标/文案/颜色）
              // 仅在拾取状态变化时重写 DOM，避免每 300ms 定时器无谓重绘（企业级：减少无变化重排）。
              var __lastPicking = null;
              var __lastRegion = null;
              function refreshToggle() {
                // 目标态 = 真实激活态 ∪ 用户刚点击的乐观意图（__rolePickWanted），使按钮在 Java 尚未确认时
                // 就立即反映点击结果；真实态一旦与意图一致即清除意图，避免 stop 后按钮卡在错误态。
                var wanted = !!window.__rolePickWanted;
                var picking = !!window.__rolePickActive || wanted;
                if (picking !== __lastPicking) {
                  __lastPicking = picking;
                  toggleBtn.innerHTML = picking ? ICON.stop : ICON.start;
                  toggleBtn.title = picking ? '停止拾取' : '开始拾取';
                  toggleBtn.style.background = picking ? '#fb8c00' : '#43a047';
                  if (window.__rolePickWanted != null && window.__rolePickWanted === !!window.__rolePickActive) {
                    window.__rolePickWanted = null;
                  }
                }
                // 区域扫描 / 整页扫描进行中：置灰「扫描整页」与「区域扫描」按钮，避免冲突（扫描中不能再点 scan/区域）。
                var locking = !!window.__regionSelecting || !!window.__pageScanning;
                if (locking !== __lastRegion) {
                  __lastRegion = locking;
                  [scanBtn, regionBtn].forEach(function(b) {
                    b.disabled = locking;
                    b.style.opacity = locking ? '0.4' : '1';
                    b.style.pointerEvents = locking ? 'none' : 'auto';
                    b.style.cursor = locking ? 'not-allowed' : 'pointer';
                  });
                }
              }
              window.__roleRefreshToggle = refreshToggle;   // 暴露给拾取库作用域（如区域扫描 finish/stop 时刷新按钮态）
              refreshToggle();
              // 状态同步定时器：每 80ms 收敛到真实态，降低"开始"迟滞感。
              window.__roleToggleTimer = setInterval(refreshToggle, 80);

              // 页面类 / 步骤代码文本框按 pageClass 分栏动态创建（见 __fillCodeTabs / __renderCodeTabs），
              // 每个 pageClass 一个子 Tab 对应一个可复制的代码文本框，对齐"页面元素"Tab 的子 Tab 布局。

              // "页面元素" Tab 内容：页面类子 Tab 栏 + 候选项清单（带勾选框）+ 封装为步骤 按钮。
              var pageContent = document.createElement('div');
              pageContent.style.cssText = 'flex:1;display:flex;flex-direction:column;min-height:0;overflow:hidden;';
              var subTabBar = document.createElement('div');
              subTabBar.id = '__roleSubTabBar';
              subTabBar.style.cssText = 'display:flex;gap:0;overflow-x:auto;background:#1b1b1b;border-bottom:1px solid #111;flex:0 0 auto;';
              var listEl = document.createElement('div');
              listEl.id = '__rolePickList';
              listEl.style.cssText = 'flex:1;overflow:auto;padding:6px 8px;background:#161616;color:#bdbdbd;' +
                'font:12px/1.5 Consolas,Monaco,monospace;min-height:0;';
              // 注：「封装为步骤」按钮(pkgBtn) 不再独占一行(pkgRow)，而是合并进下方 selBar，
              // 与「全选」复选框显示在同一行（见 PANEL_SCRIPT_B 中 selBar 构建处）。
              var pkgBtn = document.createElement('button');
              pkgBtn.id = '__rolePkgBtn';   // 稳定 id：selBar 复用持久存在时，每次重渲染先移除旧按钮再追加，避免堆积
              pkgBtn.type = 'button';
              pkgBtn.textContent = '封装为步骤';
              pkgBtn.title = '把当前勾选的候选项（按点击/扫描顺序）封装为一个 step；未勾选的可持续勾选后再次封装';
              pkgBtn.style.cssText = 'padding:6px 14px;border:0;border-radius:6px;cursor:pointer;background:#43a047;color:#fff;font:12px/1.4 sans-serif;';
              pkgBtn.onclick = function() {
                // 浏览器侧 __packageStep 先把勾选集打包为 step（与停止时同一逻辑，顺序/去重一致）；
                // 随后推送 'package' 命令给 Java 立即生成步骤代码并切到「步骤代码」Tab 展示（不再需要等到点 ⏹）。
                var r = (typeof window.__packageStep === 'function') ? window.__packageStep() : {pages:[],start:-1,count:0};
                if (r && r.count > 0) {
                  window.__pendingJump = r;   // 记录"目标 step"，Java 生成代码后由 __afterFillJump 精准定位
                  status.textContent = '已封装 ' + r.count + ' 个 step（累计 ' + (window.__steps ? window.__steps.length : 0) + ' 个），正在生成步骤代码…';
                  try { pushCmd('package'); } catch (e) {}
                } else {
                  status.textContent = '请先勾选要封装的元素（扫描后请在列表勾选，页面点选会自动勾选）';
                }
              };
              pageContent.appendChild(subTabBar);
              pageContent.appendChild(listEl);
              """;
    private static final String PANEL_SCRIPT_B = """

              var classContent = document.createElement('div');
              classContent.style.cssText = 'flex:1;display:none;min-height:0;flex-direction:column;';
              var classSubBar = document.createElement('div');
              classSubBar.id = '__roleClassSubTabBar';
              classSubBar.style.cssText = 'display:flex;gap:0;overflow-x:auto;background:#1b1b1b;border-bottom:1px solid #111;flex:0 0 auto;';
              var classAreas = document.createElement('div');
              classAreas.id = '__roleClassAreas';
              classAreas.style.cssText = 'flex:1;min-height:0;display:flex;flex-direction:column;overflow:hidden;';
              classContent.appendChild(classSubBar);
              classContent.appendChild(classAreas);

              var stepContent = document.createElement('div');
              stepContent.style.cssText = 'flex:1;display:none;min-height:0;flex-direction:column;';
              var stepSubBar = document.createElement('div');
              stepSubBar.id = '__roleStepSubTabBar';
              stepSubBar.style.cssText = 'display:flex;gap:0;overflow-x:auto;background:#1b1b1b;border-bottom:1px solid #111;flex:0 0 auto;';
              var stepAreas = document.createElement('div');
              stepAreas.id = '__roleStepAreas';
              stepAreas.style.cssText = 'flex:1;min-height:0;display:flex;flex-direction:column;overflow:hidden;';
              stepContent.appendChild(stepSubBar);
              stepContent.appendChild(stepAreas);

              function showTab() {
                var t = window.__roleActiveTab;
                pageContent.style.display = (t === 'page') ? 'flex' : 'none';
                classContent.style.display = (t === 'class') ? 'flex' : 'none';
                stepContent.style.display = (t === 'step') ? 'flex' : 'none';
                // 【修复"Tab 聚焦背景/状态文字颜色不生效"】
                // 旧实现这里用硬编码 #1e1e1e/#2d2d2d 覆盖 mkTab 初始样式，导致聚焦 Tab 永远是深灰、
                // 与未聚焦差异极小；且每次切 Tab 都把状态文字强制设为绿色 #43a047，在蓝色 header 上几乎不可读
                // （用户反馈的"深绿色、看不清"正来源于此，而非最初 mkTab 的继承问题）。
                // 统一改为与 mkTab 一致：聚焦 Tab 蓝色背景 #1565c0 + 粗体 + 3px 亮蓝下边框；状态文字白色。
                tabPage.style.background = (t === 'page') ? '#1565c0' : '#2d2d2d';
                tabPage.style.fontWeight = (t === 'page') ? 'bold' : 'normal';
                tabPage.style.borderBottom = (t === 'page') ? '3px solid #42a5f5' : '3px solid transparent';
                tabClass.style.background = (t === 'class') ? '#1565c0' : '#2d2d2d';
                tabClass.style.fontWeight = (t === 'class') ? 'bold' : 'normal';
                tabClass.style.borderBottom = (t === 'class') ? '3px solid #42a5f5' : '3px solid transparent';
                tabStep.style.background = (t === 'step') ? '#1565c0' : '#2d2d2d';
                tabStep.style.fontWeight = (t === 'step') ? 'bold' : 'normal';
                tabStep.style.borderBottom = (t === 'step') ? '3px solid #42a5f5' : '3px solid transparent';
                // 切换 Tab 时复位「复制」按钮状态（避免上一轮"已复制"残留提示误导用户）。
                // 注意：ICON.copy 是内联 SVG 字符串，必须用 innerHTML 注入才能渲染成图标；
                // 若误用 textContent，SVG 标签会被当成纯文本显示，复制按钮变成一个 XML 字符串（图标"不显示/显示异常"）。
                if (copyBtn) { copyBtn.title = '复制'; copyBtn.innerHTML = ICON.copy; }
                if (status) { status.textContent = '就绪'; status.style.color = '#fff'; status.style.fontWeight = 'bold'; }
              }
              // 暴露给 Java 侧调用（扫描完成后自动切到"页面类"Tab，让用户即时看到生成的页面类代码）。
              window.__roleShowTab = showTab;

              // 按 pageClass 把页面类 / 步骤代码分栏渲染（对齐"页面元素"Tab 的子 Tab 布局）：
              // 每个 pageClass 一个子 Tab，点击切换显示对应代码文本框；支持按页对照查看与复制。
              window.__fillCodeTabs = function(obj) {
                if (!obj) return;
                var pageMap = obj.pageByPage || null;
                var stepMap = obj.stepByPage || null;
                if (!pageMap && obj.page != null) pageMap = { '__merged__': obj.page };
                if (!stepMap && obj.step != null) stepMap = { '__merged__': obj.step };
                pageMap = pageMap || {};
                stepMap = stepMap || {};
                __renderCodeTabs('__roleClassSubTabBar', '__roleClassAreas', pageMap, '__roleCodeArea');
                __renderCodeTabs('__roleStepSubTabBar', '__roleStepAreas', stepMap, '__roleCodeArea2');
                var st = document.getElementById('__roleStatus'); if (st) st.textContent = obj.msg || '';
                try { localStorage.setItem('__rolePickerCode',
                  JSON.stringify({ pageByPage: pageMap, stepByPage: stepMap, msg: obj.msg || '' })); } catch (e) {}
              };
              function __renderCodeTabs(barId, areasId, map, taPrefix) {
                var bar = document.getElementById(barId);
                var areas = document.getElementById(areasId);
                if (!bar || !areas) return;
                var keys = Object.keys(map);
                bar.innerHTML = '';
                areas.innerHTML = '';
                if (!keys.length) {
                  var empty = document.createElement('div');
                  empty.style.cssText = 'flex:1;padding:12px;color:#888;font:13px/1.5 sans-serif;';
                  empty.textContent = '（暂无生成）';
                  areas.appendChild(empty);
                  window[barId + '_active'] = null;
                  return;
                }
                var actKey = window[barId + '_active'];
                if (!actKey || keys.indexOf(actKey) < 0) actKey = keys[0];
                window[barId + '_active'] = actKey;
                function mkSub(label, key) {
                  var b = document.createElement('button');
                  b.type = 'button';
                  b.textContent = label;
                  b.style.cssText = 'flex:0 0 auto;padding:6px 12px;border:0;cursor:pointer;font:12px/1.4 sans-serif;' +
                    'color:#ccc;background:' + (actKey === key ? '#1e88e5' : '#2d2d2d') + ';' +
                    'border-bottom:2px solid ' + (actKey === key ? '#1e88e5' : 'transparent') + ';';
                  b.onclick = function() { window[barId + '_active'] = key; __renderCodeTabs(barId, areasId, map, taPrefix); };
                  return b;
                }
                for (var i = 0; i < keys.length; i++) bar.appendChild(mkSub(keys[i], keys[i]));
                for (var j = 0; j < keys.length; j++) {
                  var k = keys[j];
                  var ta = document.createElement('textarea');
                  ta.id = taPrefix + '__' + k;
                  ta.readOnly = true;
                  ta.value = map[k] || '';
                  ta.style.cssText = 'flex:1;min-height:0;margin:0;padding:12px 14px;border:0;resize:none;display:' +
                    (k === actKey ? 'flex' : 'none') + ';background:#1e1e1e;color:#d4d4d4;' +
                    'font:13px/1.55 Consolas,Monaco,monospace;outline:none;white-space:pre;';
                  areas.appendChild(ta);
                }
              }

              // 封装为步骤后精准跳转：由 Java 侧 fillCode 完成后调用。切到「步骤代码」Tab，
              // 激活目标 step 所属 pageClass 子 Tab，并选中高亮该 step 方法文本（滚动定位到目标 step），
              // 而非泛泛切到步骤 Tab 让用户自己找——对齐"封装即定位到刚生成的 step"。
              window.__afterFillJump = function() {
                try {
                  var pj = window.__pendingJump;
                  if (!pj) return;
                  window.__pendingJump = null;
                  var pc = (pj.pages && pj.pages[0]) || null;   // 目标 step 所属的 pageClass
                  // 1) 切到「步骤代码」Tab
                  window.__roleActiveTab = 'step';
                  if (window.__roleShowTab) window.__roleShowTab();
                  // 2) 用最新 localStorage（已含新 step）重渲染，并激活目标子 Tab
                  var code = null;
                  try { code = JSON.parse(localStorage.getItem('__rolePickerCode') || 'null'); } catch (e) {}
                  var stepMap = (code && code.stepByPage) || {};
                  if (pc) {
                    window.__roleStepSubTabBar_active = pc;   // 记忆目标子 Tab 为激活
                    if (window.__fillCodeTabs && code) {
                      window.__fillCodeTabs({ pageByPage: (code.pageByPage || {}), stepByPage: stepMap, msg: code.msg || '' });
                    }
                  }
                  // 3) 选中高亮目标 step 方法（取该视图中最后一个 step 方法，即刚封装的），并聚焦滚动定位
                  var ta = pc ? document.getElementById('__roleCodeArea2__' + pc) : document.getElementById('__roleCodeArea2');
                  if (!ta) ta = document.querySelector('#__roleStepAreas textarea');
                  if (ta) {
                    var txt = ta.value || '';
                    var re = /public void step\\d+\\(\\)/g;
                    var m, last = null;
                    while ((m = re.exec(txt)) != null) last = m;
                    if (last) {
                      var s0 = last.index;
                      re.lastIndex = last.index + 1;
                      var m2 = re.exec(txt);
                      // 终点：下一 step 方法起始，或类结尾最近的 '}'
                      var e0 = m2 ? m2.index : (txt.lastIndexOf('}') > s0 ? txt.lastIndexOf('}') : txt.length);
                      ta.focus();
                      if (ta.setSelectionRange) ta.setSelectionRange(s0, e0);
                    }
                  }
                } catch (e) {}
              };

              panel.appendChild(header);
              panel.appendChild(toolbar);
              panel.appendChild(tabBar);
              panel.appendChild(pageContent);
              panel.appendChild(classContent);
              panel.appendChild(stepContent);
              // 注：window.__renumberStep 已在 START 顶层（iframe 早退 return 之前）无条件定义，
              // 此处不再重复；保证顶层与 iframe 子文档的拾取依赖一致。

              // 候选清单渲染（节流到每帧一次）：按 pageClass 分组成子 Tab，每项带勾选框。
              // 后台标签/无头环境下 requestAnimationFrame 可能不触发，用 setTimeout 兜底保证一定会执行。
              window.__renderPicks = function() {
                if (window.__renderScheduled) return;
                window.__renderScheduled = true;
                var flush = function() { window.__renderScheduled = false; __renderPicksNow(); };
                if (window.requestAnimationFrame && !document.hidden) window.requestAnimationFrame(flush);
                else setTimeout(flush, 0);
              };
              // 把当前勾选（window.__currentStep 选择集）按 pageClass 分组、按整页拾取顺序封装为一个/多个 step。
              // 常见单页选择即封装为一个 step；跨页选择会按 pageClass 各自成 step（步骤顺序 = 各页选择出现的顺序）。
              window.__packageStep = function() {
                try {
                  var all = window.__rolePicks || [];
                  var selSet = {};
                  var _src = (window.__currentStep && window.__currentStep.length)
                      ? window.__currentStep
                      : all;   // 【关键修复"导航恢复/扫描后点封装没反应"】勾选集为空时兜底用全部已拾元素：
                               // ① 整页扫描出的候选默认不进 __currentStep（__isScan 守卫），用户未手动勾选即点"封装"应视为"封装全部"；
                               // ② 跨页导航 applyPickState 恢复的 currentStep=0，但 javaPickBySig/__rolePicks 仍有元素，
                               //    点封装按钮时若死守空勾选集会 return 0、不推送 package 命令、Java 侧永远不生成代码。
                  _src.forEach(function(p) {
                    // 【关键】用全局健壮键 __mergeKey（优先 _sigKey，否则实时 __sigKey 重算），
                    // 不再强依赖 p._sigKey||p._sig 已固化。部分链路（区域选择/导航恢复回灌）写入的
                    // pick 签名字段可能缺失，导致此处 selSet 为空 → 封装 0 条 step（表现为"选中也生成不了步骤"）。
                    var k = (typeof window.__mergeKey==='function') ? window.__mergeKey(p) : (p && (p._sigKey || p._sig));
                    if (k) selSet[k] = true;
                  });
                  if (!Object.keys(selSet).length) return 0;
                  window.__steps = window.__steps || [];
                  var startIndex = window.__steps.length;   // 本次新封装 step 的全局起始索引（供跳转到目标 step）
                  // 单步语义：一次「封装为步骤」或一次「开始→停止」拾取 = 一个 step，
                  // 即使选择跨多个页面也合并为【一个 StepRec】（每个 pick 自带 _pageClass，
                  // 生成器据此引用对应页变量），不再按 pageClass 拆成多个 step。
                  // 顺序沿用全局拾取序（window.__rolePicks），跨页操作天然按发生先后排布；
                  // 关闭当前页（_closeOp 标记）也作为 step 内的一笔操作内联，不另成 step。
                  var picks = [];
                  var owner = '';
                  // 【关键修复"页面元素没有按顺序封装步骤"】
                  // 旧实现遍历 all=window.__rolePicks（按「拾取发生先后」push 的全局候选序），再按 selSet 过滤。
                  // 但 iframe 内元素经 postMessage 异步上送、syncPanelToBrowser 回灌，push 进 __rolePicks 的
                  // 顺序可能与用户「点选/勾选」的先后不一致 → 封装出的 step 顺序错乱。
                  // 用户意图应以「勾选顺序」为准：__currentStep 是按用户点击/勾选先后维护的选择集，
                  // 故改为按 __currentStep 顺序遍历，每个勾选元素再从候选 all 中取完整 pick（含 framePath 等增强）。
                  var _selOrder = _src;   // 与上面 _src 一致：勾选集优先，为空时兜底全部已拾元素
                  var _byKey = {};
                  for (var _bi = 0; _bi < all.length; _bi++) {
                    var _bp = all[_bi] || {};
                    var _bk = (typeof window.__mergeKey==='function') ? window.__mergeKey(_bp) : (_bp._sigKey || _bp._sig);
                    if (_bk && !_byKey[_bk]) _byKey[_bk] = _bp;
                  }
                  var _pickedSeen = {};
                  for (var _si2 = 0; _si2 < _selOrder.length; _si2++) {
                    var _cp = _selOrder[_si2] || {};
                    var _ck2 = (typeof window.__mergeKey==='function') ? window.__mergeKey(_cp) : (_cp._sigKey || _cp._sig);
                    if (!_ck2 || _pickedSeen[_ck2]) continue;
                    _pickedSeen[_ck2] = true;
                    var _fp = _byKey[_ck2] || _cp;   // 优先候选里的完整 pick
                    if (!owner) owner = (_fp._pageClass) || (_cp._pageClass) || (window.__rolePageName || '');
                    picks.push(_fp);
                  }
                  if (!owner) owner = (window.__rolePageName || '');
                  // 【关键修复"步骤序号不连续 / iframe 元素序号错乱"】
                  // 旧逻辑直接把 picks 丢进 step，沿用各 pick 上残留的 seq：
                  //   · iframe 内拾取的元素从不进顶层 __currentStep（self===top 守卫，见上方 message 监听），
                  //     其 seq 恒为 undefined → 排序时被当作 0、挤到 step 最前，且生成器兜底 step.size()+1
                  //     会造成「同一个 step 内序号重复 / 不连续」，表现为"中间取消一项后编号没重排对"。
                  //   · 取消中间项再勾选其他项时，__currentStep 已重排，但 picks 是按 __rolePicks 全局序过滤，
                  //     与选中序不一致，残留 seq 仍会错位。
                  // 故在此按『过滤后的实际入选顺序』重新连续编号 seq=1..N，确保 step 内步骤序号恒连续、
                  // iframe 元素也获得正确序号、且"取消中间重排"语义成立。
                  for (var _pi = 0; _pi < picks.length; _pi++) {
                    if (picks[_pi]) picks[_pi].seq = _pi + 1;
                  }
                  window.__steps.push({ pageClass: owner, picks: picks });
                  window.__currentStep = [];   // 已封装，清空选择集
                  // 清除区域选择遗留的页面高亮框（绿色已选/青色悬停），否则不按 Esc 直接封装时绿框会残留。
                  try { if (typeof window.__clearRegionOutlines === 'function') window.__clearRegionOutlines(); } catch (e) {}
                  // 仅轻量刷新勾选态（复选框复位为未勾选），不重建整张候选列表——
                  // 扫描出海量子元素时全量重建会卡 UI，正是"封装 step 慢"的根因。
                  window.__applySelection();
                  // 返回结构化信息：本次封装涉及的 pageClass（按序）与全局起始索引，
                  // 供 pkgBtn 记录"目标 step"，Java 生成代码后由 window.__afterFillJump 精准定位。
                  return { pages: [owner], start: startIndex, count: 1 };
                } catch (e) { return 0; }
              };
              // 轻量刷新勾选态：仅更新已存在行的复选框与高亮，【不重建整个列表 DOM】。
              // 供「封装为步骤」等"仅选择集变化、候选集合不变"的场景调用，避免对扫描出的海量元素做 O(n) 全量重建（卡 UI 的根因）。
              window.__applySelection = function() {
                try {
                  var listEl = document.getElementById('__rolePickList');
                  if (!listEl) return;
                  var selSet = {};
                  (window.__currentStep || []).forEach(function(p) {
                    var k = (typeof window.__mergeKey==='function') ? window.__mergeKey(p) : (p && (p._sigKey || p._sig)); if (k) selSet[k] = true;
                  });
                  var rows = listEl.children;
                  for (var i = 0; i < rows.length; i++) {
                    var row = rows[i];
                    if (!row || !row.__cb) continue;
                    var sk = row.__sig;
                    var sel = !!(sk && selSet[sk]);
                    row.__cb.checked = sel;
                    row.style.background = sel ? 'rgba(30,136,229,.18)' : '';
                  }
                  // 【修复】同步"全选"复选框：selAllCb 是列表外的独立复选框、不在上面 children 遍历范围内。
                  // 其勾选状态必须由【真实选择集 window.__currentStep】唯一决定，否则会出现
                  // "元素行全未选中、但全选框仍勾选"的不一致（封装为步骤后切回页面元素 tab 尤为明显）。
                  // 双保险：① refreshSelInfo 统一刷新计数与全选框（依赖闭包 selAllCb）；
                  //        ② 直接用全局引用 window.__roleSelAllCb（指向真实 DOM checkbox）按真实选择集兜底复位，
                  //           不依赖闭包捕获，杜绝因闭包错位导致的复位失效。
                  try {
                    if (typeof refreshSelInfo === 'function') { try { refreshSelInfo(); } catch (e3) {} }
                    var _sa = window.__roleSelAllCb;
                    if (_sa) {
                      var _cset = window.__currentStep || [];
                      var _visN = (typeof curVisiblePicks === 'function') ? curVisiblePicks().length : 0;
                      _sa.checked = (_visN > 0 && _cset.length === _visN);
                    }
                  } catch (e2) {}
                } catch (e) {}
              }
              function __renderPicksNow() {
                try {
                  var subBar = document.getElementById('__roleSubTabBar');
                  var listEl = document.getElementById('__rolePickList');
                  if (!subBar || !listEl) return;  // 面板尚未挂载，等下次拾取/恢复时再渲染
                  // iframe（含嵌套 frame）内的拾取元素会经 postMessage 逐层上送顶层，
                  // 由顶层 message 监听（见 START_SCRIPT 末尾）push 进【顶层】window.__rolePicks 聚合。
                  // 因此面板只需渲染顶层 window.__rolePicks 即可，无需（也不能）跨域遍历 window.frames
                  // —— file:// 场景下 iframe origin 为 "null"，主框架访问子 frame 的 window.__rolePicks 会被
                  // 浏览器同源策略直接拦截（"Blocked a frame with origin null"），导致面板反而为空。
                  var picks = window.__rolePicks || [];
                  // 统计各 pageClass 候选数，用于子 Tab 命名与计数（命名以 page class）。
                  var counts = {};
                  for (var i = 0; i < picks.length; i++) {
                    var pc = (picks[i] && picks[i]._pageClass) || (window.__rolePageName || '未知页');
                    counts[pc] = (counts[pc] || 0) + 1;
                  }
                  var pageClasses = Object.keys(counts);
                  // 默认激活第一个 pageClass（不再提供「全部」汇总 tab，页面元素按页分组各自独立展示）。
                  var act = window.__roleActivePageClass;
                  if (!act || pageClasses.indexOf(act) < 0) act = pageClasses[0] || (window.__rolePageName || '未知页');
                  window.__roleActivePageClass = act;
                  // 渲染页面类子 Tab（命名以 page class，按页分组，无「全部」）
                  subBar.innerHTML = '';
                  function mkSub(label, key) {
                    var b = document.createElement('button');
                    b.type = 'button';
                    b.textContent = label + ((counts[key] != null) ? (' (' + counts[key] + ')') : '');
                    b.style.cssText = 'flex:0 0 auto;padding:6px 12px;border:0;cursor:pointer;font:12px/1.4 sans-serif;' +
                      'color:#ccc;background:' + (act === key ? '#1e88e5' : '#2d2d2d') + ';' +
                      'border-bottom:2px solid ' + (act === key ? '#1e88e5' : 'transparent') + ';';
                    b.onclick = function() { window.__roleActivePageClass = key; window.__renderPicks(); };
                    return b;
                  }
                  for (var c = 0; c < pageClasses.length; c++) subBar.appendChild(mkSub(pageClasses[c], pageClasses[c]));

                  // 渲染候选项（带勾选框）。勾选态 = 该 pick 的 sig 在选择集 window.__currentStep 中。
                  listEl.innerHTML = '';
                  if (!picks.length) {
                    listEl.textContent = '（暂无拾取：点 🔍 扫描整页，或在页面点击元素）';
                    // 【关键修复"删除全部元素后全选 checkbox 仍勾选且可用、已选计数陈旧"】
                    // 旧实现此处直接 return，跳过了下方 selBar 全选框与 refreshSelInfo 的更新——删除全部后
                    // 列表为空，refreshSelInfo 不再执行，全选框保持删除前的勾选/可用状态、计数仍显示旧的
                    // "已选 10/10"。修复：空列表时也复位全选框（禁用 + 取消勾选）并把计数归零，
                    // 使"删除全部 → 全选框 disabled、已选 0/0"与真实选择集一致。
                    var __selCb = window.__roleSelAllCb;
                    if (__selCb) { __selCb.disabled = true; __selCb.checked = false; }
                    var __selInfo = window.__roleSelInfo;
                    if (__selInfo) __selInfo.textContent = '已选 0 / 0';
                    return;
                  }
                  // 全选 / 全不选 工具栏：作用于"当前可见范围"（act 过滤集），与下方候选渲染用同一过滤规则。
                  // 注意：本栏在 __renderPicks 每次重渲染时都会被调用（手动拾取每个元素都会触发），
                  // 因此【只创建一次并复用】——否则每次重渲染都会在 pageContent 里堆积出新的全选条。
                  var selBar = window.__roleSelBar || null;
                  if (!selBar) {
                    selBar = document.createElement('div');
                    selBar.id = '__roleSelBar';
                    selBar.style.cssText = 'position:sticky;top:0;z-index:2;display:flex;gap:8px;align-items:center;' +
                      'padding:6px 4px;background:#1f1f1f;border-bottom:1px solid #333;color:#cfcfcf;font:12px/1.4 sans-serif;';
                    var selAll = document.createElement('label');
                    selAll.style.cssText = 'display:flex;gap:5px;align-items:center;cursor:pointer;user-select:none;';
                    var selAllCb = document.createElement('input');
                    selAllCb.type = 'checkbox';
                    selAllCb.style.cssText = 'width:14px;height:14px;cursor:pointer;';
                    var selAllTxt = document.createElement('span');
                    selAllTxt.textContent = '全选';
                    selAll.appendChild(selAllCb); selAll.appendChild(selAllTxt);
                    var selInfo = document.createElement('span');
                    selInfo.style.cssText = 'margin-left:auto;color:#9aa0a6;';
                    selBar.appendChild(selAll); selBar.appendChild(selInfo);
                    // 暴露全局引用，供 __applySelection 在"封装为步骤"等仅清空选择集的场景下同步复位"全选"复选框。
                    window.__roleSelAllCb = selAllCb;
                    window.__roleSelBar = selBar;
                    window.__roleSelInfo = selInfo;
                  } else {
                    // 复用已存在的工具栏：重新指向内部"全选"复选框与信息节点。
                    var selAllCb = selBar.querySelector('input[type=checkbox]');
                    var selInfo = window.__roleSelInfo;
                    window.__roleSelAllCb = selAllCb;
                  }
                  function curVisiblePicks() {
                    return picks.filter(function(p) {
                      if (!p) return false;
                      var pc = (p._pageClass) || (window.__rolePageName || '未知页');
                      return pc === act;
                    });
                  }
                  // 「封装为步骤」按钮与「全选」复选框合并到同一行（selInfo 的 margin-left:auto 已把计数+按钮推到右侧）
                  // selBar 持久复用：先移除上次渲染留下的同名按钮，再追加新按钮，避免每次重渲染堆积多个封装按钮。
                  var _oldPkg = document.getElementById('__rolePkgBtn');
                  if (_oldPkg) _oldPkg.remove();
                  selBar.appendChild(pkgBtn);
                  // 删除按钮（小垃圾桶图标）：位于「全部」工具栏这一行；无任何选中项时灰暗禁用，有选中时高亮可点。
                  var delBtn = document.createElement('button');
                  delBtn.type = 'button';
                  delBtn.id = '__roleDelBtn';   // 稳定 id：selBar 复用持久存在时，每次重渲染先移除旧按钮再追加，避免堆积
                  delBtn.title = '删除选中的全部元素';
                  // 图标放大到 18px 并改用「桶身描边 + 内部竖线」的高对比画法：
                  // 原先 15px 纯色实心块在深色工具栏上糊成一团、辨识度低，这里让桶盖/桶身/竖纹层次分明。
                  delBtn.innerHTML = '<svg viewBox="0 0 24 24" width="18" height="18" fill="none" ' +
                    'stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">' +
                    '<path d="M3 6h18"/>' +
                    '<path d="M8 6V4h8v2"/>' +
                    '<path d="M19 6l-1 14H6L5 6"/>' +
                    '<path d="M10 11v5M14 11v5"/>' +
                    '</svg>';
                  function syncDelBtn() {
                    var has = (window.__currentStep || []).length > 0;
                    if (has) {
                      // 可用态：亮红底 + 白图标 + 亮红描边 + 红色投影，在深色工具栏里一眼可见。
                      delBtn.disabled = false;
                      delBtn.style.cssText = 'flex:0 0 auto;display:flex;align-items:center;justify-content:center;' +
                        'width:32px;height:28px;padding:0;color:#fff;border:1px solid #ff6b60;border-radius:6px;' +
                        'cursor:pointer;background:#f4433a;opacity:1;box-shadow:0 0 0 2px rgba(244,67,58,.35);' +
                        'transition:filter .15s,box-shadow .15s;';
                    } else {
                      // 禁用态：不再用浅灰底（在深灰工具栏上几乎看不见），改为暗红描边的虚线轮廓 + 暗底，
                      // 既保持尺寸不跳、又让"垃圾桶"形状在禁用时也清晰可辨（淡红图标 + 红虚线边框）。
                      delBtn.disabled = true;
                      delBtn.style.cssText = 'flex:0 0 auto;display:flex;align-items:center;justify-content:center;' +
                        'width:32px;height:28px;padding:0;color:#f4877f;border:1px dashed rgba(244,67,58,.55);border-radius:6px;' +
                        'cursor:not-allowed;background:#2a2a2a;opacity:1;';
                    }
                  }
                  delBtn.onclick = function(e) {
                    if (e && e.stopPropagation) e.stopPropagation();
                    if (delBtn.disabled) return;
                    var cset = window.__currentStep || [];
                    if (!cset.length) return;
                    var dead = {};
                    // 同时收集 _sigKey 与 _sig 两种键：浏览器端去重 / 清理用（过滤 __rolePicks、__rolePickSigs）。
                    var deadKeys = [];
                    // 【关键修复"删除无效"】浏览器侧只上报 _sigKey/_sig 这两个字符串是不够的：
                    // Java 内存态 javaPickBySig 的 map key 由 pickDedupKey 决定——role/closeOp 策略用 _sigKey，
                    // 但「定位器唯一型策略」(id/css/i18n/text) 用的是 _sig（即 __pickSig 结果），
                    // 而这两种键的 *取值* 并不相同（_sigKey 是含_pageClass的 JSON 串、_sig 是 "id:xxx#0" 形式），
                    // 过去只按字符串判断，对定位器策略常因「上报键」与「内存态 key」对不上而删不掉，
                    // 主循环每 ~1s 又把 javaPickBySig 整体 merge 回浏览器，元素随即"复活"，表现为「删除没起作用」。
                    // 故此处额外上报**完整 pick 对象**（剥离 _el 等 DOM 引用，避免循环 JSON 失败），
                    // Java 侧 __roleOnDelete 用与入库时完全相同的 pickDedupKey 重新算 key 精确命中删除。
                    var delPicks = [];
                    // 【修复"扫描新增元素漏删"】cset 只是用户在面板里【勾选】的选择集，
                    // 而整页扫描新增的元素（如 StatusConfirmationLightIcon）会进入 __rolePicks、
                    // 被 Java 内存态收录，却可能尚未进入 __currentStep（用户全选后、删除前才扫描出来）。
                    // 这类"已存在但未被勾选"的同页元素，点删除时 cset.forEach 遍历不到 → 删不掉 → 残留。
                    // 故此处：先收集 cset 涉及的 pageClass 集合，再把 __rolePicks 中【同一 pageClass】
                    // 的全部元素并入删除集。仍严格按 pageClass 隔离，绝不会波及另一页的共用元素。
                    var csetPages = {};
                    // 【修复"点删除时只删当前页元素"】
                    // 旧逻辑把 cset（用户勾选集）涉及的【所有 pageClass】都纳入删除目标；若用户在其他页面也勾选/全选，
                    // csetPages 会包含多页 → 点一次删除把多页元素一并删掉。按需求：删除只作用于【当前激活页】
                    // （window.__roleActivePageClass，即面板顶部高亮的页面子 Tab），即便其它页也处于全选状态也不波及。
                    // 故此处只保留"勾选元素中、_pageClass === 当前激活页"的页，作为删除目标页集合。
                    var __actPage = window.__roleActivePageClass || window.__rolePageName || '';
                    cset.forEach(function(x) {
                      if (x) {
                        var p = (x._pageClass || window.__rolePageName || '');
                        if (p && p === __actPage) csetPages[p] = true;
                      }
                    });
                    var allCandidates = cset.slice();
                    // 收集顶层 + 所有 iframe（含嵌套）中的同页 pick。
                    // 实测：testid 等元素若位于 iframe 内，浏览器侧"合并 iframe 拾取到主框架"失败时，
                    // 它只留在 iframe 自己的 window.__rolePicks，不会进入顶层；而 Java 内存态(__roleOnPick)
                    // 是独立上送的，所以 Java 有、顶层浏览器没有 → 删除遍历不到 → 残留下来的正是这类元素。
                    // 故此处必须跨 frame 收集，否则同页 iframe 元素漏删。
                    function __collectFromWin(w) {
                      try {
                        var picks = (w && w.__rolePicks) ? w.__rolePicks : [];
                        var cur = (w && w.__currentStep) ? w.__currentStep : [];
                        [].concat(picks, cur).forEach(function(x) {
                          if (!x) return;
                          var p = (x._pageClass || (w && w.__rolePageName) || window.__rolePageName || '');
                          // 仅收集当前激活页（__actPage）的同页元素；即便其它页已全选，也不并入删除集。
                          if (p && p === __actPage && allCandidates.indexOf(x) < 0) allCandidates.push(x);
                        });
                      } catch (e2) { /* 跨 frame 访问可能因 detached 失败，忽略 */ }
                    }
                    __collectFromWin(window);
                    try {
                      var __fr = (window.frames && window.frames.length) ? window.frames : [];
                      for (var __fi = 0; __fi < __fr.length; __fi++) {
                        try { __collectFromWin(__fr[__fi]); } catch (e3) {}
                      }
                    } catch (e4) {}
                    // 会话级"已删屏蔽集"：即使 Java 删除因某种原因未命中，主循环 syncPanelToBrowser
                    // 把 javaPickBySig 合并回浏览器时也会跳过命中此集的元素，杜绝"删除后约 1s 复活"。
                    var delSigs = window.__deletedSigs = (window.__deletedSigs || {});
                    allCandidates.forEach(function(x) {
                      if (!x) return;
                      // 【关键】实时重算签名/去重键，而非依赖 pick 对象上碰巧缺失的字段。
                      // 部分链路（如区域选择、导航恢复回灌）写入 __currentStep 的 pick 可能没固化 _sig，
                      // 导致上报给 Java 的 _sig 为空、pickDedupKey 算不出 key → 定位器策略删不掉、随后复活。
                      // 用全量重算可保证 _sig/_sigKey 一定存在且与入库时完全一致。
                      // 删除键一律走 __mergeKey 口径（含 pageClass，如 "[\"role:link:Language:#0\",\"LogonPage\"]"），
                      // 绝不用裸 _sig（如 "role:link:Language:#0"）——否则 LoginPage 与 SetupSecondPwdPage 上
                      // 同名共用元素（Language、HSBC App tab、各页脚链接，_sig 完全相同）会共享同一裸键，
                      // 删一页即误删/屏蔽另一页。__mergeKey 已对全部策略（role/id/css/i18n/text）返回含 pc 的键，
                      // 故删除命中率不受影响；冗余的裸 _sig 兜底既不安全也无必要。
                      var rsig = (typeof window.__pickSig === 'function') ? window.__pickSig(x) : (x._sig || '');
                      var rkey = (typeof window.__mergeKey === 'function') ? window.__mergeKey(x)
                                : ((typeof window.__sigKey === 'function') ? window.__sigKey(x) : (x._sigKey || ''));
                      if (rkey) { dead[rkey] = true; deadKeys.push(rkey); delSigs[rkey] = true; }
                      // 仅抽取 Java 侧 collectDeleteKeys 所需的纯数据字段（不含 _el/DOM，JSON 安全）。
                      // 注意：collectDeleteKeys 只用 strategy/_sig/_sigKey/_pageClass/role/key/name/index，
                      // 故不再冗余携带 id/css 等未参与去重键计算的字段。
                      delPicks.push({
                        strategy: x.strategy,
                        _sig: rsig || x._sig,
                        _sigKey: rkey || x._sigKey,
                        _pageClass: x._pageClass,
                        role: x.role,
                        key: x.key,
                        name: x.name,
                        index: x.index,
                        // 【修复"删除所有元素后页面类残留"】collectDeleteKeys 对 id/css 型元素要用
                        // locatorKey(=strategy+selector) 命中 Java 权威内存态 key；此前 delPick 精简对象
                        // 缺 selector/resolvedKey，Java 侧算出的 locatorKey 为空 → 这类元素删除 miss。
                        // 这里补上 selector / resolvedKey / tag / text，保证删除侧与入库侧 key 计算一致。
                        selector: x.selector,
                        // 同步透传 css 字段：[name=]/[type=]/历史 css 候选的拾取对象里定位值存于 css 字段，
                        // collectDeleteKeys 的 locatorKey 主路径经 buildSelector 读 m.get("css") 计算权威内存态 key，
                        // 透传后可直接命中，无需单一依赖 _sig/_sigKey 兜底（与拾取侧完全对称）。
                        css: x.css,
                        resolvedKey: x.resolvedKey,
                        tag: x.tag,
                        text: x.text
                      });
                    });
                    // 从拾取数组移除所有选中项（按 sigKey/sig 精确匹配）
                    window.__rolePicks = (window.__rolePicks || []).filter(function(x) {
                      if (!x) return false;
                      return !(dead[x._sigKey] || dead[x._sig]);
                    });
                    // 同步清除去重登记表中对应的键，否则该元素被视为"已存在"，
                    // 之后重新点选同一元素时会因命中 __rolePickSigs 而被当作重复丢弃，再也拾不回来。
                    try {
                      var sigs = window.__rolePickSigs || {};
                      deadKeys.forEach(function(k) { if (k) delete sigs[k]; });
                      window.__rolePickSigs = sigs;
                    } catch (e2) {}
                    // 【关键】同步清理已封装的 step 中对该元素的引用。
                    // 页面类字段来自 picks，而 step 代码来自 window.__steps —— 两者是独立数据源。
                    // 只删 picks 的话，step 里仍留着这个"幽灵 pick"：生成时页面类已无对应字段，
                    // 代码生成侧靠 field==null 静默 continue 跳过，于是编译虽能通过，
                    // 但该动作会凭空消失（且弹窗元素还会连带丢掉 closeCurrentPage 闭环），排查极困难。
                    // 故在源头把它从每个 step 的 picks 里摘掉，并移除因此变空的 step，保持两侧一致。
                    try {
                      var _steps = window.__steps || [];
                      var _kept = [];
                      _steps.forEach(function(s) {
                        // 页面级操作条目（如 {op:'close'}）不含 picks，原样保留。
                        if (!s || typeof s !== 'object' || typeof s.op === 'string') { _kept.push(s); return; }
                        var ps = (s.picks || []).filter(function(p) {
                          if (!p) return false;
                          // 优先按 __mergeKey（去索引稳定键，与删除主逻辑 dead 集合一致）判定是否在删除集，
                          // 同时兜底兼容老格式含索引的 _sig/_sigKey，避免 i18n 等定位器策略因 #index 变化清不掉。
                          // 只按 __mergeKey（含 pageClass）判定是否在删除集，杜绝跨页同名裸 _sig 误删。
                          var mk = (typeof window.__mergeKey === 'function') ? window.__mergeKey(p) : null;
                          return !((mk && dead[mk]) || (p._sigKey && dead[p._sigKey]));
                        });
                        // picks 被删空的 step 整条丢弃，避免生成出一个没有任何语句的空 @Step 方法。
                        if (!ps.length) return;
                        s.picks = ps;
                        _kept.push(s);
                      });
                      window.__steps = _kept;
                    } catch (e6) {}
                    // 【关键】同步删除 Java 权威内存态：主循环每轮空闲都会把 javaPickBySig 整体 merge
                    // 回 window.__rolePicks，不通知 Java 的话被删元素约 1s 后就会"复活"（删除看似无效），
                    // 且代码生成读的正是 javaPickBySig。exposeBinding 与 console 兜底双通道上报（幂等）。
                    try {
                      // 上报完整 pick 对象（delPicks）而非仅 key 数组，供 Java 侧用 pickDedupKey 精确命中删除；
                      // 旧通道的 deadKeys 仅保留用于下方本地 __rolePicks / __rolePickSigs 的过滤。
                      var payload = JSON.stringify(delPicks);
                      if (typeof window.__roleOnDelete === 'function') window.__roleOnDelete(payload);
                      try { console.log('__roleOnDelete::' + payload); } catch (e4) {}
                    } catch (e3) {}
                    // 清空选择集并重新渲染（含子 Tab 计数同步）
                    window.__currentStep = [];
                    // 清除区域选择遗留的页面高亮框，删除元素后页面上的绿框/青框一并清掉。
                    try { if (typeof window.__clearRegionOutlines === 'function') window.__clearRegionOutlines(); } catch (e) {}
                    // 落盘最新态，避免整页跳转时被 localStorage 里的旧快照把已删元素恢复回来。
                    // 用 window.__persistPickState（面板脚本可见的公开 API）；__persistNow 是拾取脚本内的
                    // 局部函数，不在本脚本作用域内，直接调用取不到。
                    // 必须放在 __currentStep 清空【之后】，否则会把已删元素当作"选择集"一并写进快照。
                    try { if (typeof window.__persistPickState === 'function') window.__persistPickState(); } catch (e5) {}
                    window.__renderPicks();
                    // 【关键】「页面类」「步骤代码」两个 Tab 里的内容是生成时一次性写入 textarea 的
                    // 静态文本快照，不会跟随数据变化。仅删数据的话，已生成代码里该元素的字段声明与
                    // step 引用依然原样显示着（用户看到的就是"删了但代码没变"）。
                    // 故通知 Java 按最新状态重算并回填两个 Tab，使变量与引用一并消失。
                    try { pushCmd('refreshCode'); } catch (e7) {}
                  };
                  delBtn.onmouseenter = function() { if (!delBtn.disabled) delBtn.style.filter = 'brightness(1.12)'; };
                  delBtn.onmouseleave = function() { delBtn.style.filter = 'none'; };
                  // selBar 持久复用：先移除上次渲染留下的同名按钮，再追加新按钮，避免每次重渲染堆积多个删除按钮。
                  var _oldDel = document.getElementById('__roleDelBtn');
                  if (_oldDel) _oldDel.remove();
                  selBar.appendChild(delBtn);
                  // 把"全选"工具栏从候选列表内部移出，作为 subTabBar（按页分组的子 Tab 行）正下方
                  // 的兄弟元素，使"全选"这一行与子 Tab 行紧挨着。
                  try { pageContent.insertBefore(selBar, listEl); } catch (e) { listEl.appendChild(selBar); }
                  function refreshSelInfo() {
                    var vis = curVisiblePicks();
                    var sel = 0;
                    var cset = window.__currentStep || [];
                    function __mkey(q){ return (typeof window.__mergeKey==='function') ? window.__mergeKey(q) : (q && (q._sigKey || q._sig)); }
                    for (var vi = 0; vi < vis.length; vi++) {
                      var s = __mkey(vis[vi]);
                      if (s && cset.some(function(x){ return __mkey(x) === s; })) sel++;
                    }
                    selInfo.textContent = '已选 ' + sel + ' / ' + vis.length;
                    syncDelBtn();   // 选中数量变化后同步垃圾桶按钮高亮
                  }
                  var selSet = {};
                  (window.__currentStep || []).forEach(function(p) {
                    var k = (typeof window.__mergeKey==='function') ? window.__mergeKey(p) : (p && (p._sigKey || p._sig)); if (k) selSet[k] = true;
                  });
                  for (var i2 = 0; i2 < picks.length; i2++) {
                    var p = picks[i2] || {};
                    var pc = (p._pageClass) || (window.__rolePageName || '未知页');
                    if (pc !== act) continue;
                    var sk = (typeof window.__mergeKey==='function') ? window.__mergeKey(p) : (p._sigKey || p._sig);
                    var sel = !!(sk && selSet[sk]);
                    var row = document.createElement('label');
                    row.style.cssText = 'display:flex;gap:6px;align-items:flex-start;padding:3px 4px;cursor:pointer;' +
                      (sel ? 'background:rgba(30,136,229,.18);' : '') + 'border-bottom:1px solid #111;';
                    var cb = document.createElement('input');
                    cb.type = 'checkbox'; cb.checked = sel;
                    cb.style.cssText = 'margin-top:3px;flex:0 0 auto;';
                    // 关键修复：cb/row 均为 var 函数作用域的循环共享变量，若闭包直接引用外层 cb/row，
                    // 等 onchange 真正触发时它们已指向【最后一次】迭代创建的元素，导致读到的 checked 状态
                    // 错乱、勾选不生效（表现为"勾选不了"）。故用 IIFE 参数按迭代捕获当前 cb/row/pk，
                    // 并改为【直接更新该行高亮】，不再全量重建列表（消除闪烁与 O(n) 重建竞态）。
                    (function(pk, checkbox, rowEl) {
                      checkbox.onchange = function(e) {
                        // 阻断冒泡：勾选只做 O(1) 增量更新（仅该行高亮 + 选择集维护），
                        // 不冒泡到 document 的拾取监听链路，避免意外的 tab 切换/卡顿。
                        if (e && e.stopPropagation) e.stopPropagation();
                        window.__currentStep = window.__currentStep || [];
                        // 【关键】用全局健壮键，避免 pk 签名字段缺失时 s 为空、直接 return 导致勾选不生效
                        //（即"选中了却没进选择集、封装 step 时 SelSet 为空 → 生成 0 条"）。
                        var s = (typeof window.__mergeKey==='function') ? window.__mergeKey(pk) : (pk && (pk._sigKey || pk._sig));
                        if (!s) return;
                        var idx = -1;
                        for (var j = 0; j < window.__currentStep.length; j++) {
                          var e = window.__currentStep[j];
                          var ek = (typeof window.__mergeKey==='function') ? window.__mergeKey(e) : (e && (e._sigKey || e._sig));
                          if (ek === s) { idx = j; break; }
                        }
                        if (checkbox.checked) {
                          if (idx < 0) window.__currentStep.push(pk);
                          rowEl.style.background = 'rgba(30,136,229,.18)';
                        } else {
                          if (idx >= 0) window.__currentStep.splice(idx, 1);
                          rowEl.style.background = '';
                        }
                        // 勾选/取消后重排连续编号：取消中间项，后续项自动前移。
                        window.__renumberStep();
                        // 选中变化后同步「已选计数」与垃圾桶按钮高亮（轻量，仅刷新工具栏）
                        if (typeof refreshSelInfo === 'function') refreshSelInfo();
                        // 重排后刷新所有行的步骤序号显示（让"取消中间→后续前移"在面板里可见）。
                        // 用 __renderPicks 轻量重渲染候选列表即可，避免手工逐行改文本。
                        try { if (typeof window.__renderPicks === 'function') window.__renderPicks(); } catch (_) {}
                      };
                    })(p, cb, row);
                    var txt = document.createElement('span');
                    // 显示步骤序号（seq）：勾选入选的元素才有连续编号，未入选为『-』，
                    // 让用户在面板里直接看到"第几步"，取消中间项后编号实时前移。
                    // 【关键修复"嵌套 iframe 内元素序号显示 -"】
                    // 嵌套 iframe（grandchild）内的 pick 经 postMessage 逐层上送到顶层时，顶层
                    // __currentStep 持有的元素对象是顶层 message 监听 push 的拷贝（结构化克隆），
                    // 而 __rolePicks 里同时可能存在 syncPanelToBrowser 从 javaPickBySig 灌回的另一份
                    // 拷贝。两份拷贝的 _sigKey 在各自上下文的 __sigKey 算出来一致（含 frameTwo URL），
                    // 但若任一帧调用 __sigKey 时 _sigKey 已被固化（"已固化则直接返回"分支）而 iframe 内的
                    // 固化值用的是当时 frame 的 location 兜底，则可能在跨 frame 传递中产生 key 漂移。
                    // 这里主比较仍用 __mergeKey（_sigKey 优先）；若任一帧都没找到匹配，则退化到仅按
                    // 元素定位器签名 _sig 比较（不含页面类/URL），因同 DOM 元素的 _sig 跨 frame 一致，
                    // 必能命中对应入选记录，使嵌套 iframe 元素也能获得正确的步骤序号。
                    var _selMk = (function(){ try { return (typeof window.__mergeKey==='function') ? window.__mergeKey(p) : (p._sigKey || p._sig); } catch(_){ return null; } })();
                    var _selSig = p._sig || '';
                    var _seqNo = '-';
                    if (_selMk || _selSig) {
                      var _cset = window.__currentStep || [];
                      for (var _si = 0; _si < _cset.length; _si++) {
                        var _cur = _cset[_si];
                        var _ck = (function(){ try { return (typeof window.__mergeKey==='function') ? window.__mergeKey(_cur) : (_cur && (_cur._sigKey || _cur._sig)); } catch(_){ return ''; } })();
                        if (_ck && _ck === _selMk) { _seqNo = (_si + 1); break; }
                        // 退化匹配：嵌套 iframe 元素跨 frame 时 _sigKey 可能因 URL 兜底而不同，
                        // 但同 DOM 元素的 _sig（定位器签名）跨 frame 恒等，故以此作为兜底必命中。
                        if (_selSig && _cur && _cur._sig === _selSig) { _seqNo = (_si + 1); break; }
                      }
                    }
                    var s = (p.strategy || 'role');
                    if (p.role) s += ' role=' + p.role;
                    if (p.name) s += ' name="' + p.name + '"';
                    if (p.key) s += ' key=' + p.key;
                    if (p.id) s += ' id=' + p.id;
                    if (p.css) s += ' css=' + p.css;
                    if (p.index != null && p.index >= 0) s += ' #' + p.index;
                    if (p.popup) s += ' [popup]';
                    if (p.download) s += ' [download]';
                    if (p.hover) s += ' [hover]';
                    if (p.dblClick) s += ' [dbl]';
                    // 步骤序号前缀必须放在最后赋值，避免被上面的 txt.textContent = s 覆盖（序号丢失的根因）。
                    txt.textContent = '[' + _seqNo + '] ' + s;
                    txt.style.cssText = 'flex:1;white-space:pre-wrap;word-break:break-all;';
                    row.appendChild(cb); row.appendChild(txt);
                    // 记录复选框与签名引用，供 __applySelection 增量刷新（不重建列表）。
                    row.__cb = cb; row.__sig = sk;
                    listEl.appendChild(row);
                  }
                  // 绑定"全选"复选框：勾选即对当前可见范围全选，取消勾选即全不选；复选框状态与计数保持同步。
                  try {
                    function applySelectAll(on) {
                      var vis = curVisiblePicks();
                      if (on) {
                        window.__currentStep = window.__currentStep || [];
                        for (var vi = 0; vi < vis.length; vi++) {
                          var pk = vis[vi]; if (!pk) continue;
                          var s = (typeof window.__mergeKey==='function') ? window.__mergeKey(pk) : (pk._sigKey || pk._sig); if (!s) continue;
                          if (!window.__currentStep.some(function(x){ return ((typeof window.__mergeKey==='function')?window.__mergeKey(x):(x&&(x._sigKey||x._sig))) === s; })) window.__currentStep.push(pk);
                        }
                      } else {
                        var visSigs = {};
                        vis.forEach(function(pk) { if (pk) { var s = (typeof window.__mergeKey==='function')?window.__mergeKey(pk):(pk._sigKey||pk._sig); if (s) visSigs[s] = true; } });
                        window.__currentStep = (window.__currentStep || []).filter(function(x) { var sk=(typeof window.__mergeKey==='function')?window.__mergeKey(x):(x&&(x._sigKey||x._sig)); return !(sk && visSigs[sk]); });
                      }
                      // 全选/全不选后重排连续编号。
                      window.__renumberStep();
                    }
                    selAllCb.onclick = function(e) {
                      e && e.stopPropagation && e.stopPropagation();
                      applySelectAll(selAllCb.checked);
                      window.__renderPicks();
                    };
                    // 计数刷新时同步"全选"复选框状态：可见项全部选中 -> 勾选，否则不勾选。
                    var _refreshSelInfo = refreshSelInfo;
                    refreshSelInfo = function() {
                      var vis = curVisiblePicks();
                      var cset = window.__currentStep || [];
                      var sel = 0;
                      // 统一用 __mergeKey 计算勾选键（与 onchange / applySelectAll / __packageStep 完全一致）：
                      // 旧实现用 vis[vi]._sigKey || vis[vi]._sig，若面板 picks 对象未固化这些字段则 s 恒为空，
                      // 导致"已选计数"永远显示 0、全选框永远不勾选（用户误以为 i18n/无 _sigKey 元素没被选中）。
                      function __mkey2(q){ return (typeof window.__mergeKey==='function') ? window.__mergeKey(q) : (q && (q._sigKey || q._sig)); }
                      for (var vi = 0; vi < vis.length; vi++) {
                        var s = __mkey2(vis[vi]);
                        if (s && cset.some(function(x){ return __mkey2(x) === s; })) sel++;
                      }
                      selInfo.textContent = '已选 ' + sel + ' / ' + vis.length;
                      // 【关键修复"删除全部后全选 checkbox 未复位"】
                      // 无可见元素时（如删除全部选中项后 __rolePicks 被清空），全选框应【禁用且未勾选】，
                      // 而不是保持可勾选/勾选状态；否则用户看到的是"还能全选、已选未清空"的错觉。
                      // 有元素时恢复可用，并仅在"可见项全部已选"时勾选。
                      if (vis.length === 0) {
                        selAllCb.disabled = true;
                        selAllCb.checked = false;
                      } else {
                        selAllCb.disabled = false;
                        selAllCb.checked = (sel === vis.length);
                      }
                    };
                    refreshSelInfo();
                  } catch (e) {}
                } catch (e) {
                  try { var le = document.getElementById('__rolePickList'); if (le) le.textContent = '（列表渲染失败）'; } catch (_) {}
                }
              }
              window.__renderScheduled = false;
              window.__renderPicks();
              // 暴露幂等"确保面板存在"入口：供门控脚本在 SPA 路由切换（pushState/hashchange 等）自愈时调用——
              // 框架重渲染 document 子树可能把 docked 面板 DOM 冲掉，此时仅重建面板并立即重渲染已拾元素
              // （已拾元素存于 window.__rolePicks，不随 DOM 重建丢失）。build() 内部已对"已存在面板"做 remove 再重建，故幂等。
              window.__roleEnsurePanel = function() {
                try {
                  if (!document.getElementById('__rolePanel')) { build(); }
                  if (window.__renderPicks) window.__renderPicks();
                } catch (e) {}
              };

              showTab();
              (document.body || document.documentElement).appendChild(panel);
      }
      // Playwright addInitScript 在每个新文档解析早期执行，body 可能尚未就绪；
      // 若挂载点（body/documentElement）还未出现则短轮询重试，确保 appendChild 可靠。
      (function waitMount() {
        // build() 需要 document.body 才能安全设置 docked 布局（document.body.style）；
        // addInitScript/onPopup 注入时新文档可能尚未解析出 body，故必须等到 body 就绪再构建。
        if (!document.body) { setTimeout(waitMount, 10); return; }
        build();
      })();
    })();
    """;
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
        switch (cmd) {
            case "start":
                // 多实例：会话级"开始"作用于所有已打开页面，使各页面板同步显示 ⏹ 停止
                // （active[0] 是会话权威开关，followPage / onFrameNavigated 据此决定是否重启监听）。
                active[0] = true;
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
                        syncPanelToBrowser(page, null, javaPickBySig);
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
                            return new PickerResult(PickerAction.CONTINUE, codePage, null,
                                    "整页扫描完成，已生成页面类（" + snap.entries.size() + " 个字段，" + added
                                            + " 个新增），可继续勾选元素封装步骤，或点 ⏹ 停止生成步骤代码");
                        }
                    }
                } catch (Exception e) {
                    log.warn("[picker][scan] 扫描后即时生成页面类失败：{}", e.getMessage());
                }
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
                                f.evaluate("(function(){ try { return (typeof window.__roleScanPage==='function') ? window.__roleScanPage(null) : -1; } catch(e){ return -1; } })()");
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
                            return new PickerResult(PickerAction.CONTINUE, codePage, codeStep,
                                    "区域扫描完成，已生成页面类（" + snap.entries.size() + " 个字段），可继续点其他区域，或点 ⏹ 停止生成步骤代码");
                        }
                    }
                } catch (Exception e) {
                    log.warn("[picker][regionScanned] 生成页面类失败：{}", e.getMessage());
                }
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
                LinkedHashMap<String, String> codePage = buildPageClassCode(snap.entries, packageName, pageClassName, nlsFiles);
                LinkedHashMap<String, String> codeStep = buildStepCode(snap, packageName, stepClassName);
                // 注：切到步骤 Tab + 精准定位目标 step 由主循环 fillCode 后调用 window.__afterFillJump 统一处理
                // （该函数在浏览器侧读取 window.__pendingJump 记录的目标 step，避免此处提前切 tab 导致定位错位）。
                int stepCount = (snap.steps != null ? snap.steps.size() : 0) + (snap.ops != null ? snap.ops.size() : 0);
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
                if (!javaPickBySig.isEmpty()) {
                    snap = new PickSnapshot(snap.pageClass, new ArrayList<>(javaPickBySig.values()), snap.steps, snap.ops);
                }
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
                    if (p == page) snap = stopAndRead(p);
                    else stop(p);
                }
                if (snap == null) snap = readPickSnapshot(page);   // 兜底：命令页不在跟踪集合时
                // 状态外置（对齐 page.pause）：优先用 Java 侧内存态（javaPickBySig）作为已拾元素权威来源，
                // O(1) 取回、且对导航/关闭导致的浏览器端状态清空免疫；内存为空（回传桥未触发等异常）时
                // 退回浏览器读快照兜底。steps/ops 仍来自浏览器单次往返（stopAndRead 已合并），保证多页 step 序列正确。
                if (!javaPickBySig.isEmpty()) {
                    List<RoleEntry> memEntries = new ArrayList<>(javaPickBySig.values());
                    snap = new PickSnapshot(snap.pageClass, memEntries, snap.steps, snap.ops);
                }
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
                    totalSteps++;   // 关闭操作也计入 step 总数
                }
                if (entriesByPage.isEmpty()) {
                    return new PickerResult(PickerAction.CONTINUE, null, null, "未拾取到元素");
                }
                // 按 pageClass 分别生成页面类（含"仅 step/ops 无元素 pick"的页，也产出空字段类，保证步骤视图引用不悬空）
                LinkedHashMap<String, String> codePage = new LinkedHashMap<>();
                for (Map.Entry<String, List<RoleEntry>> e : entriesByPage.entrySet()) {
                    codePage.put(e.getKey(), RoleElementPageGenerator.generate(e.getValue(), packageName, e.getKey(), nlsFiles));
                }
                LinkedHashMap<String, String> codeStep = buildStepCode(snap, packageName, stepClassName);
                int matched = 0;
                for (RoleEntry e : allEntries) {
                    if (e.getResolvedKey() != null) matched++;
                }
                String nlsInfo = (nlsFiles != null && nlsFiles.length > 0)
                        ? "（nls=" + (nlsFiles.length == 1 ? nlsFiles[0] : nlsFiles.length + " 个文件")
                            + "，已反查 " + matched + " 个 key）" : "";
                return new PickerResult(PickerAction.CONTINUE, codePage, codeStep,
                        "已生成 " + entriesByPage.size() + " 个页面类 / " + allEntries.size()
                                + " 个页面字段 / " + totalSteps + " 个 step" + nlsInfo);
            }
            case "abort":
                active[0] = false;
                stop(page);
                return new PickerResult(PickerAction.ABORT, null, null, null);
            case "done":
            default:
                stop(page);
                return new PickerResult(PickerAction.DONE, null, null, null);
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
                try { log.warn("[picker] 合并 iframe 拾取到主框架失败（url={}）：{}", f.url(), fe.getMessage()); }
                catch (Exception ignore) {}
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
                       .append('|').append(e.getIndex());
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
                    + "     return o; }"
                    + "   window.__rolePicks = window.__rolePicks || [];"
                    + "   window.__rolePickSigs = window.__rolePickSigs || {};"
                    + "   arr.forEach(function(p){"
                    + "     var o = toPick(p);"
                    + "     o._sig = (typeof window.__pickSig==='function') ? (window.__pickSig(o)||'') : '';"
                    + "     var k = (typeof window.__sigKey==='function') ? window.__sigKey(o)"
                    + "            : ((o&&(o._sigKey||o._sig))||null);"
                    + "     if (k) o._sigKey = k;"
                    + "     var __del = window.__deletedSigs || {};"
                    // 仅按含 pageClass 的 k（=__sigKey）命中已删屏蔽集，裸 o._sig 跨页同名会误屏蔽另一页面共用元素。
                    + "     if (k && (__del[k] || del2.indexOf(k) >= 0)) return;"
                    + "     if (k && window.__rolePickSigs[k]) return;"
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
        Object sigKey = m.get("_sigKey");
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
        if (locatorIdentity) {
            String lk = RoleElementPageGenerator.locatorKey(e);
            if (lk != null && !lk.isEmpty()) return pc + "|" + lk;
        }
        // role/closeOp 分支：_sigKey 已内嵌 pageClass（JSON.stringify([_sig, pageClass])），
        // 与浏览器 __rolePicks 的 _sigKey 同构，删除/本地过滤均可精确命中，保持原行为。
        if (sigKey != null) return String.valueOf(sigKey);
        // 【方案 B 兜底】_sigKey 缺失时绝不能退化成裸 _sig——否则 LoginPage / SetupSecondPwdPage
        // 上同名共用元素（Language、HSBC App tab、各页脚链接，_sig 完全相同）会共享同一裸键，
        // 删一页即误删另一页。此处一律前缀 pageClass，确保即使缺 _sigKey 也维持按页隔离。
        if (sig != null) {
            String s = String.valueOf(sig);
            return pc.isEmpty() ? s : pc + "|" + s;
        }
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
            if (inEmpty && !exEmpty) {
                incoming.setFramePath(exFp);
            } else if (!inEmpty && !exEmpty && exFp.size() > inFp.size()) {
                // 旧条目链更深（更具体）→ 用旧链；同深时保留新条目（已是 incoming）
                incoming.setFramePath(exFp);
            }
            // 对话框标记：任一为 true 即视为触发（onDialog 回写 / 浏览器侧 hook 检测）
            if (!incoming.isDialog() && existing.isDialog()) {
                incoming.setDialog(true);
                if (existing.getDialogType() != null) incoming.setDialogType(existing.getDialogType());
                if (existing.getDialogAction() != null) incoming.setDialogAction(existing.getDialogAction());
            }
            // 弹窗标记：任一为 true 即视为触发（onPopup 回写）
            if (!incoming.isPopup() && existing.isPopup()) incoming.setPopup(true);
        }
        map.put(key, incoming);
        return incoming;
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
            roleEntry.setSeq(parseSeq(m.get("seq")));
            roleEntry.setPageInstanceId(parseInstanceId(m.get("_pageInstanceId")));
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
        entry.setSeq(parseSeq(m.get("seq")));
        entry.setPageInstanceId(parseInstanceId(m.get("_pageInstanceId")));
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
        final String stepClassName = pageClassName + "Steps";
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
                            syncPanelToBrowser(pg, null, javaPickBySig);
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
                PickerResult r = runPickerCommand(current[0], cmd, packageName, pageClassName, stepClassName, pageNames, snapshots, nlsFiles, active, javaPickBySig);
                if (r.action == PickerAction.ABORT) {
                    throw new PickerAbortedException("用户通过面板『终止运行』中止了后续代码执行");
                }
                if (r.action == PickerAction.DONE) {
                    break;
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
            // 生成 step 时包装为 switchToNewPage(() -> element.click())（对齐 page.pause() 的 codegen 输出）。
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
                                + "   arr.push(closeMarker); }"
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
                            // 立即刷新父页快照，确保随后父页导航重建时不会因覆盖而丢失该关闭操作。
                            try { snapshots.put(parent, readPickStateJson(parent)); } catch (Exception ignore) {}
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
                    if (closedCls != null && !closedCls.isEmpty() && navigatedPages.contains(closed)) {
                        appendCloseOpStep(closed, closedCls, snapshots);
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
                    if (st != null && !st.isEmpty()) {
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
                    try {
                        // 关键修复（跳转到新页面后元素成倍增加）：监听重挂已由 context 级门控注入脚本
                        // (gatedPickerInitScript) 在新文档早期原生完成（见本方法上方注释），导航数据恢复也已在
                        // 上方 applyPickState/合并 evaluate 中完成。此处【不再调用 start() 重注入整套库】——
                        // 否则一次导航会触发 onFrameNavigated 多次（main frame / iframe / about:blank 过渡 / 重试），
                        // 每次都 start() 一次：清空并重建 __rolePickSigs、异步 page.evaluate 重注入，与 idle 主循环的
                        // syncPanelToBrowser 合并 javaPickBySig 之间存在竞态，合并键未就绪时元素被重复 push，
                        // 形成"反复重注入 + 反复合并"的循环，导致已拾元素成倍累积。
                        // 这里仅做轻量激活保活：置位激活态并触发面板渲染，监听由门控脚本保证存活。
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
            if (sessionActive) start(newPage, nlsReverseJson);
            newPage.evaluate(PANEL_SCRIPT);
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
