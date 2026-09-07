package com.hsbc.cmb.hk.dbb.automation.framework.web.page.scan;

import com.google.gson.Gson;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Frame;
import com.microsoft.playwright.Page;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.BlockingQueue;

/**
 * 上下文级「浏览器桥 / 面板命令抽干」注册中心（T5-1 拆分）：
 * 把 {@link RoleElementPicker} 中"向 Playwright {@link BrowserContext} 一次性注册命令桥 /
 * 拾取桥 / 删除桥 / 控制台兜底桥 / 动态 iframe 监听，以及面板命令队列抽干"等
 * 【扫描/捕获桥】职责收敛到本类，使 {@link RoleElementPicker} 退化为拾取编排门面。
 *
 * <p>行为与原实现逐字等价：仅物理搬移，逻辑 / 日志标签 / 去重键口径完全不变。
 * 桥方法经 {@link RoleElementPicker#pickerEval} 与浏览器交互（同包静态调用）。
 */
final class RolePickerBridgeRegistry {

    static final Logger log = LoggerFactory.getLogger(RolePickerBridgeRegistry.class);

    /** 反序列化任意拾取态 JSON 的 Gson 实例（桥方法统一用其解析回传）。 */
    static final Gson GSON = new Gson();

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
     * 抽干浏览器端兜底命令队列 window.__panelCmds 并入 Java 命令队列 cmdQueue。
     * 当 exposeFunction 绑定（window.__rolePickerCmd）尚未就绪时，面板按钮的 pushCmd 会把命令
     * 暂存进 window.__panelCmds（见 PANEL_SCRIPT）。若不消费，这些命令会静默丢失，导致"点了开始却
     * 拾取不了"。此处由 Java 主循环周期性抽干，保证命令零丢失。与 exposeFunction 投递幂等、不会重复。
     */
    static void drainPanelCmds(Page page, BlockingQueue<CmdEvent> cmdQueue) {
        if (page == null || page.isClosed() || cmdQueue == null) return;
        try {
            Object raw = RoleElementPicker.pickerEval(page, RolePickerScripts.DRAIN_PANEL_CMDS_JS);
            if (raw instanceof List) {
                for (Object o : (List<?>) raw) {
                    String c = o == null ? null : o.toString();
                    cmdQueue.offer(new CmdEvent(page, c));
                }
            }
        } catch (Exception ignore) {}
    }
}
