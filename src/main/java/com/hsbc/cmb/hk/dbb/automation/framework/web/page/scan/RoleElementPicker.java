package com.hsbc.cmb.hk.dbb.automation.framework.web.page.scan;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.hsbc.cmb.hk.dbb.automation.framework.web.utils.NLSUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
 * <p>与整页 {@code dumpAccessibilityRoles} 不同，本类只采集“用户主动点击”的元素，
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
     * 上下文级“当前会话”桥（对齐 {@code page.pause()} 的 DebugController/Recorder 解耦）：
     * 命令桥/拾取桥不再逐页 {@code exposeFunction}，而是对 {@link BrowserContext} 一次性
     * {@code exposeBinding}——context 下所有当前与未来页面（弹窗/新标签页）、每次导航后的新文档
     * 都自动持有绑定，由 {@code BindingCallback.Source#page()} 天然区分“哪个页面发起”。
     * 同名绑定不可重复注册（重复会抛 {@code PlaywrightException}），故每个 context 仅注册一次；
     * 二次打开（同一 context 再次 {@code openPanel}）只更新下方 Map 指向的“当前会话”队列/状态，
     * 回调动态读取，避免命令/拾取被投递到已失效的旧会话队列。
     */
    private static final Map<BrowserContext, BlockingQueue<CmdEvent>> CTX_CMD_QUEUES = new ConcurrentHashMap<>();
    private static final Map<BrowserContext, LinkedHashMap<String, RoleEntry>> CTX_PICK_STATES = new ConcurrentHashMap<>();
    private static final Set<BrowserContext> CTX_BRIDGED = Collections.newSetFromMap(new ConcurrentHashMap<>());
    // 上下文级初始化脚本守卫：面板脚本每 context 仅注册一次；拾取脚本按 nls 内容变化才追加注册
    // （addInitScript 无法撤销，重复注册会累积执行；同 nls 幂等跳过，不同 nls 追加后“后注册者后执行”覆盖生效）。
    private static final Set<BrowserContext> CTX_PANEL_SCRIPTED = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private static final Map<BrowserContext, String> CTX_PICKER_NLS = new ConcurrentHashMap<>();



    /**
     * 面板命令事件：由页面内 {@code window.__rolePickerCmd(c)}（经 {@link Page#exposeFunction} 暴露的
     * Java 回调）投递，携带“哪个页面发的命令”。主循环从阻塞队列取出后据此驱动，避免忙轮询所有页面。
     */
    private static final class CmdEvent {
        final Page page;
        final String cmd;
        CmdEvent(Page page, String cmd) { this.page = page; this.cmd = cmd; }
    }

    /**
     * 把“面板命令桥 + 点击拾取桥 + 控制台兜底桥”一次性注册到 {@link BrowserContext}
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
                // 去重键与浏览器端保持一致但更精确：定位器唯一型策略（id/css/i18n/text/...）按 locator 签名（_sig）
                // 去重，避免同一元素在“主页↔弹窗”间被重复收录；角色/closeOp 仍按 [sig, pageClass|URL]（_sigKey）区分。
                // 重复点击以最近一次交互为准整条替换（RoleEntry 不可变，更新须替换）；首次插入保序。
                String key = pickDedupKey(m, e);
                synchronized (map) { map.put(key, e); log.info("[picker] __roleOnPick 回传写入内存态：key={} pageClass={}（当前内存态大小={}）", key, (e.getPageClass() == null ? "" : e.getPageClass()), map.size()); }
            } catch (Exception ex) {
                log.warn("[picker] __roleOnPick 回传解析失败：{}", ex.getMessage());
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
                    String key = pickDedupKey(m, e);
                    synchronized (map) {
                        map.put(key, e);
                        log.info("[picker] __roleOnPick(console) 回传写入内存态：key={} pageClass={}（当前内存态大小={}）", key, (e.getPageClass() == null ? "" : e.getPageClass()), map.size());
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
            + " try{var n=localStorage.getItem('__rolePageName'); if(n) window.__rolePageName=n;}catch(e){} })();";

    /**
     * 门控式拾取初始化脚本（对齐 {@code page.pause()} 的 Recorder：拾取脚本经 context 注入脚本
     * 在【每个新文档】自动重跑，跨导航/弹窗/新标签页由浏览器原生保证监听重挂，无需 Java 端手动跟踪）。
     * 门控：仅当“会话拾取开关”（localStorage __rolePickSessionOn，由 start/stop 置位/清除）打开时
     * 才注入 nls 反向表并挂载 START_SCRIPT；未拾取时新文档零侵入。
     */
    private static String gatedPickerInitScript(String nlsReverseJson) {
        return "(function(){"
                // 诊断回写：浏览器 console 已被吞（无 onConsoleMessage 监听），故把门控执行结果写入
                // window.__gateInit，供 Java 在 onFrameNavigated / start 后回读，定位“刷新后拾取不了”。
                + " var __gi = { ts: Date.now(), url: location.href, origin: location.origin };"
                + " var on=false;"
                + " try{ on = localStorage.getItem('__rolePickSessionOn')==='1'; }catch(e){ __gi.lsErr = String(e); }"
                + " try{ if(!on) on = !!window.__rolePickSessionOn; }catch(e){}"
                + " __gi.switchOn = on;"
                // 无条件定义“开启监听”入口（即便本次文档早期会话开关尚未置位）：仅定义、不调用，
                // 所有调用处（__roleReenable / __roleSpaHeal / start）均带会话开关自检，不会误开启拾取。
                // 提前定义可保证“文档早期会话未开、之后才点开始”的场景下，刷新/跳转/SPA 自愈仍能复用同一入口。
                + " window.__roleGatedStart = function(){ " + START_SCRIPT + " };"
                // ===== 同页 URL 变更（SPA 路由切换）自愈：修复“同页 url 变化后已拾元素消失 / 拾取不了” =====
                // 无条件注册（即便本次文档早期会话未开启）：函数体自带会话开关自检，会话未开时整体 no-op，安全。
                // pushState/replaceState/popstate/hashchange 不会触发 load/pageshow/onFrameNavigated，
                // 框架重渲染 document 子树往往静默移除 document 级点击监听、甚至把 docked 面板 DOM（#__rolePanel）冲掉，
                // 表现为“同页路由切换后，之前抓取的元素看不见、点了也没反应”。
                // 故在此类事件上二次自检：开关仍在则重挂监听；面板 DOM 若被冲掉则重建；并立即重渲染已拾元素。
                // 已拾元素存于 window.__rolePicks（按本标签页累积的展示数组，不随 DOM 重建丢失），重渲染即可恢复显示。
                + " function __roleSpaHeal(){ try {"
                + "   var on2=false; try{ on2 = localStorage.getItem('__rolePickSessionOn')==='1'; }catch(e){}"
                + "   try{ if(!on2) on2 = !!window.__rolePickSessionOn; }catch(e){}"
                + "   if (on2 && typeof window.__roleGatedStart === 'function') { try{ window.__roleGatedStart(); }catch(e){} }"
                + "   // 面板 DOM 被框架重渲染冲掉：用 PANEL_SCRIPT 暴露的 __roleEnsurePanel 幂等重建（存在则跳过）。"
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
                // 关键加固：nls 反查表字面量若因任何原因非法，绝不能连累后面的 START_SCRIPT 注入
                // （否则整个门控 IIFE 抛错、监听永不挂载，表现为“刷新/跳转后点了没反应”）。
                + " var __o; try { __o = " + (nlsReverseJson == null ? "{}" : nlsReverseJson) + "; } catch(e){ __o = {}; __gi.nlsErr = String(e); }"
                + " window.__nlsReverse = (__o && __o.exact) ? __o.exact : (__o && __o.templates ? {} : (__o || {}));"
                + " window.__nlsTemplates = (__o && __o.templates) ? __o.templates : [];"
                // 记忆体开关兜底：即便跨源/localStorage 不可用，浏览器侧也持有本会话开启态，
                // 供 onFrameNavigated 的会话开关自检（读 window.__rolePickSessionOn）与 load/pageshow 自检使用。
                + " try{ window.__rolePickSessionOn = true; }catch(e){}"
                + " window.__roleGatedStart();"
                // ===== 浏览器侧自愈（核心修复“刷新/跳转后拾取不了”）=====
                // 仅靠文档早期 addInitScript 不可靠：现代 SPA/微前端框架可能在初始化阶段重建 document 子树、
                // 或浏览器以 bfcache 前进/后退/刷新恢复旧文档（addInitScript 不重跑）、或导航瞬间执行上下文竞态，
                // 都可能让文档早期的监听没“粘住”。故在 load 与 pageshow 两个全文档就绪时机二次自检：
                //   开关仍在 且（点击监听缺失 或 未激活）→ 重新执行 START_SCRIPT（幂等：已激活则早退仅保活）。
                // 该机制完全不依赖 Java 侧 onFrameNavigated / ensurePickingActive 的时序，从根上保证“页面怎么变都能拾取”。
                + " function __roleReenable(){ try {"
                + "   var on2=false; try{ on2 = localStorage.getItem('__rolePickSessionOn')==='1'; }catch(e){}"
                + "   try{ if(!on2) on2 = !!window.__rolePickSessionOn; }catch(e){}"
                + "   if (!on2) return;"
                + "   try{ window.__rolePickSessionOn = true; }catch(e){}"
                + "   // 始终重挂：addEventListener 对同函数引用幂等（重复挂无害），但能覆盖“框架重建 document"
                + "   // 子树导致原监听被静默移除、而 window.__rolePickClick 函数引用仍在”的卡死态——仅判函数是否存在会漏判。"
                + "   if (typeof window.__roleGatedStart === 'function') window.__roleGatedStart();"
                + "   // 重新合并 pagehide/beforeunload 落盘到 localStorage 的最新拾取态，并重渲染面板："
                + "   // onFrameNavigated 的合并可能早于旧页 pagehide 落盘（竞态），导致“跳转到新页后面板空白 / 缺最后点击”。"
                + "   // 此处 load/pageshow（旧页 pagehide 已稳定发生）再次合并，补齐默认页已拾元素，修复面板空白。"
                + "   try {"
                + "     var raw = localStorage.getItem('__rolePickState'); if (raw) {"
                + "       var s = JSON.parse(raw);"
                + "       window.__rolePicks = window.__rolePicks || [];"
                + "       window.__rolePickSigs = window.__rolePickSigs || {};"
                + "       (s.picks||[]).forEach(function(p){"
                + "         var k = JSON.stringify([(p&&p._sig)||'', (p&&p._pageClass)||'']);"
                + "         if (k && window.__rolePickSigs[k]) return;"
                + "         if (k) window.__rolePickSigs[k] = true; window.__rolePicks.push(p); });"
                + "       window.__currentStep = window.__currentStep || [];"
                + "       var __cs = {}; window.__currentStep.forEach(function(p){ var k=JSON.stringify([(p&&p._sig)||'',(p&&p._pageClass)||'']); if(k) __cs[k]=true; });"
                + "       (s.currentStep||[]).forEach(function(p){ var k=JSON.stringify([(p&&p._sig)||'',(p&&p._pageClass)||'']); if(k&&__cs[k])return; if(k)__cs[k]=true; window.__currentStep.push(p); });"
                + "     }"
                + "   } catch(e){}"
                + "   try { if (window.__renderPicks) window.__renderPicks(); } catch(e){}"
                + " } catch(e){} }"
                + " window.addEventListener('load', __roleReenable);"
                + " window.addEventListener('pageshow', __roleReenable);"
                + " __gi.injected = true;"
                + " __gi.activeAfter = !!window.__rolePickActive;"
                + " __gi.hasClick = typeof window.__rolePickClick === 'function';"
                + " __gi.hasMove = typeof window.__rolePickMove === 'function';"
                + " __gi.hasKey = typeof window.__rolePickKey === 'function';"
                + " window.__gateInit = __gi;"
                + "})();";
    }

    /**
     * 把“面板重建 + 门控拾取”初始化脚本一次性注册到 {@link BrowserContext}：
     * context 下每个页面、每次导航、每个新文档都会自动执行（Playwright 原生保证），
     * 从根上替代“onFrameNavigated 手动重挂 / 逐页 addInitScript / 自愈循环”三套兜底机器。
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
     * 暂存进 window.__panelCmds（见 PANEL_SCRIPT）。若不消费，这些命令会静默丢失，导致“点了开始却
     * 拾取不了”。此处由 Java 主循环周期性抽干，保证命令零丢失。与 exposeFunction 投递幂等、不会重复。
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

    /** 反序列化任意拾取态 JSON 的精确泛型类型，避免 {@code fromJson(x, Map.class)} 的未检查转换 */
    private static final java.lang.reflect.Type MAP_STRING_OBJECT_TYPE =
            new TypeToken<java.util.Map<String, Object>>() {}.getType();

    /** 开启拾取模式：注入监听 + 顶部提示条 */
    private static final String START_SCRIPT_A = """
            (function() {
              // 保活（幂等）：任何 start 都确保 document 级点击/键盘/悬浮监听已挂载。
              // 导航（尤其某些 SPA/微前端框架在路由切换时重建 document 子树）可能移除这些监听，
              // 而 window.__rolePickActive 仍为 true；若直接早退会导致“导航到新页面后点了没反应、拾取不到”。
              // 故先重挂（addEventListener 对同一函数引用幂等），再判断是否已在拾取中。
              if (window.__rolePickClick) document.addEventListener('click', window.__rolePickClick, true);
              if (window.__rolePickDblClick) document.addEventListener('dblclick', window.__rolePickDblClick, true);
              if (window.__rolePickMove) document.addEventListener('mousemove', window.__rolePickMove, true);
              if (window.__rolePickKey) document.addEventListener('keydown', window.__rolePickKey, true);
              if (window.__rolePickActive) return;   // 已在拾取中：仅保活监听，不重置状态/不重复定义库
              window.__rolePickActive = true;
              window.__rolePicks = window.__rolePicks || [];
              window.__steps = window.__steps || [];
              // 保留“进行中的 step”：跨页面切换（弹窗打开/关闭）会先 applyPickState 把父页/弹窗的
              // __currentStep 搬过来，再执行本 START 重启监听。若这里硬置为 []，会把刚搬来的当前 step
              // 整个抹掉——这正是“元素定位在但步骤没了”的根因。用 || [] 续接：
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
              // page.pause()/Inspector 的“拾取元素”正是用这套 W3C ARIA + accname 算法计算 role 与 name，
              // 因此直接移植，保证 picker 结果与 Inspector 完全一致（不再依赖浏览器 computedRole/computedName）。
              // ============================================================================
              // 企业级优化：roleUtils 移植 + 各拾取/去重/落盘辅助函数定义是“静态库”，
              // 同一 window 内只需解析/编译一次（首次 start）。后续 start（stop→再 start、
              // 或多页跟随）用一次性守卫跳过这近千行的重解析/重编译，点击“开始”的端到端延迟显著下降；
              // 所有对外入口（window.__recordPick / __computePick / __pickSig / __sigKey /
              // __persistPickState 等）都挂在 window 上会持续存活，跳过定义后仍可被点击 handler 正常调用。
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
                if (!explicitRole) return getImplicitAriaRole(element);
                if ((explicitRole === 'none' || explicitRole === 'presentation') && !hasGlobalAriaAttribute(element)) return getImplicitAriaRole(element);
                return explicitRole;
              }
              function getRole(el) { return getAriaRole(el) || 'generic'; }

              // 把 <label> 解析为其关联的表单控件：点击复选框/单选/文本框的“标签文字”时，
              // 对齐 page.pause()/Inspector——直接拾取它所指代的控件（role + name=标签文本），
              // 而非无语义的 label 文本节点。
              //   - HTMLLabelElement.control 覆盖“包裹式”关联（含 display:none 的隐藏控件）；
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
                  // 否则“ (opens in a new window)”等提示会被算进 name，与可见文本不一致。
                  tokens.push(options.includeAdvisory ? getPseudoContent(getPseudo(element, '::before')) : '');
                  // aria-describedby 指向的是“描述”而非“名称”，其引用的后代不应计入可访问名，
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

              // 无语义角色：这类元素通常不是用户想捕获的“控件”，应向上回溯
              var NON_ROLE = { generic:1, none:1, presentation:1 };
              // 可作为点击目标的“交互角色”：仅这些角色才用 role+name 定位（对齐 pause 优先级）；
              // region/navigation/list 等容器角色不算，避免点文本时误抓到外层容器。
              var INTERACTIVE_ROLES = { button:1, link:1, checkbox:1, radio:1, tab:1,
                menuitem:1, menuitemcheckbox:1, menuitemradio:1, option:1, 'switch':1,
                textbox:1, combobox:1, listbox:1, searchbox:1, slider:1, spinbutton:1, treeitem:1 };

              // 判断 id 是否“稳定”（可作为持久选择器）：排除自动生成/含长数字/非法字符的 id
              function isStableId(id) {
                if (!id) return false;
                if (id.length > 40) return false;
                if (/\\d{4,}/.test(id)) return false;               // 连续 4+ 数字，疑似动态
                return /^[A-Za-z][\\w-]*$/.test(id);                 // 首字母 + 单词字符/连字符
              }
              function ownVisibleText(el) {
                return (el.textContent || '').replace(/\\s+/g, ' ').trim();
              }
              // 是否“可输入”元素（对齐 page.pause() 的 fill 录制范围）：文本框/多行/数字/搜索/
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
                var parts = [], node = el, depth = 0;
                while (node && node.nodeType === 1 && depth++ < 5) {
                  var idv = node.getAttribute && node.getAttribute('id');
                  if (isStableId(idv)) { parts.unshift('#' + idv); break; }
                  var sel = node.tagName.toLowerCase();
                  var parent = node.parentElement;
                  if (parent) {
                    var same = [];
                    for (var c = parent.firstElementChild; c; c = c.nextElementSibling) {
                      if (c.tagName === node.tagName) same.push(c);
                    }
                    if (same.length > 1) sel += ':nth-of-type(' + (same.indexOf(node) + 1) + ')';
                  }
                  parts.unshift(sel);
                  node = parent;
                }
                return parts.join(' > ');
              }

              // ============================================================================
              // 定位策略链（忠实对齐 page.pause() 的 selectorGenerator 打分序，分低者优先）：
              //   testId(1) < placeholder(100) < label(120) < role+name(140)
              //     < altText(160) < text(180) < title(200) < css #id(500) < css 兜底
              // 算法分两步（与 recorder 一致）：
              //   ① 重定位：label → 关联控件；再向上回溯（≤5 层）找交互角色祖先，
              //      点按钮内文字/图标时抓按钮本身；
              //   ② 在重定位后的目标元素上按打分序依次尝试候选：
              //      1. data-testid 族        → getByTestId
              //      2. INPUT/TEXTAREA 的 placeholder → getByPlaceholder（pause 仅对这两类标签）
              //      3. 表单控件原生关联 label → getByLabel（先于 role+name，打分 120 < 140）
              //         注：仅“直接点击控件”时生效；若点是 <label> 本身（被 resolveLabel 重定位成控件），
              //         则跳过此步回退到 ④ role+name，避免 label 与 input 产出同一种 label 策略。
              //      4. 交互角色 + 可访问名    → getByRole（生成 @RoleElement，保留 NLS 多语言）
              //      4.5 非交互元素的 data-i18n 多语言 key → @Element("[data-i18n=...]")（CSS 属性选择器，本项目约定，仅在上方
              //          role+name 未命中时生效：交互控件已走 role，这里专补 generic span/div 等稳定定位）
              //      5. IMG/AREA 的 alt        → getByAltText（pause 中先于 text，160 < 180）
              //      6. 可见文本（≤80 字符，pause 截断阈值）→ getByText
              //      7. title                  → getByTitle
              //      8. 稳定 id                → #id（500 分，必须排在语义候选之后）
              //      9. 兜底                   → css 路径（needsReview）
              // 仅返回“原始片段”（strategy/role/name/attr/value/id/css），
            """;
    private static final String START_SCRIPT_B = """
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
                  var labels = el.labels;
                  if (labels && labels.length) {
                    var s = normName(labels[0].textContent || '');
                    if (s) return s.slice(0, 80);
                  }
                } catch (e) {}
                return '';
              }
              // 计算 pick 定位器在整页上匹配的元素集合（文档顺序），用于判定“一组元素”并给出序号。
              // 与生成端的定位策略对齐：role→role+name（exact=!cleaned）、text→最内层文本相等、
              // 属性类→属性值相等、label→关联控件。id/css 视为唯一（返回空）。
              function __normSafe(s) {
                try { return normName(s || ''); } catch (e) { return (s || '').replace(/\\s+/g, ' ').trim(); }
              }
              function __matchingElements(pick) {
                if (!pick) return [];
                var all = document.querySelectorAll('*'), out = [], i, x;
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
                  var nodes = document.querySelectorAll('[' + attr + ']');
                  for (i = 0; i < nodes.length; i++) {
                    if ((nodes[i].getAttribute(attr) || '').trim() === want) out.push(nodes[i]);
                  }
                  return out;
                }
                if (pick.strategy === 'label') {
                  var t3 = __normSafe(pick.name);
                  var forms = document.querySelectorAll('input,textarea,select');
                  for (i = 0; i < forms.length; i++) {
                    var lbls = forms[i].labels;
                    if (lbls && lbls.length && __normSafe(lbls[0].textContent).indexOf(t3) !== -1) out.push(forms[i]);
                  }
                  return out;
                }
                return out;   // id / css 视为唯一
              }
              // 若该定位器在页面上匹配多个元素，则给 pick 附上序号（index/count），
              // 生成 step 时输出 .nth(index)，对齐 page.pause() 的 first()/nth() 消歧。
              function __attachIndex(pick, el) {
                try {
                  var ms = __matchingElements(pick);
                  if (ms.length > 1) {
                    var idx = ms.indexOf(el);
                    if (idx >= 0) { pick.count = ms.length; pick.index = idx; }
                  }
                } catch (e) {}
                return pick;
              }
              window.__computePick = function(t) {
                // ① 重定位（对齐 recorder retarget）：label → 控件；向上找交互角色祖先
                // originalIsLabel 标记“用户点的是 <label> 本身”而非直接点控件——
                // 点击 label 时走 getByLabel 策略（定位到其关联控件），点击控件本身时跳过本分支，
                // 改为 role+name/placeholder 等控件-centric 定位，避免“点输入框却生成 label 策略”的错位。
                var originalIsLabel = !!(t && t.tagName === 'LABEL');
                var el = resolveLabel(t);
                var cur = t, guard = 0;
                while (cur && guard++ < 5) {
                  var node = resolveLabel(cur);
                  if (INTERACTIVE_ROLES[(getRole(node) || '').toLowerCase()]) { el = node; break; }
                  cur = cur.parentElement;
                }
                window.__lastPickEl = el;
                var tag = (el.tagName || '').toLowerCase();
                function done(o) {
                  o.tag = tag;
                  o.text = ownVisibleText(el).slice(0, 120);
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
                // ② 按 pause 打分序出候选
                // 1. testId（打分 1，最优）
                var testAttrs = ['data-testid','data-test-id','data-test','data-qa'];
                for (var i = 0; i < testAttrs.length; i++) {
                  var tv = el.getAttribute(testAttrs[i]);
                  if (tv && tv.trim()) return done({ strategy:'testid', attr:testAttrs[i], value:tv.trim(), name:tv.trim() });
                }
                // 2. placeholder（100；pause 仅对 INPUT/TEXTAREA）
                if (tag === 'input' || tag === 'textarea') {
                  var ph = el.getAttribute('placeholder');
                  if (ph && ph.trim()) return done({ strategy:'placeholder', attr:'placeholder', value:ph.trim(), name:ph.trim() });
                }
                // 3. 原生关联 label → getByLabel（120，先于 role+name）
                //    仅当用户“直接点击 <label> 本身”时生效（originalIsLabel）；
                //    此时 getByLabel 会定位到该 label 关联的控件，符合“点标签=操作控件”的直觉。
                //    若点击的是表单控件本身，则跳过本分支，走下方 role+name（输入控件-centric 定位），
                //    避免“点了输入框却生成 label 策略”的错位。
                if (originalIsLabel) {
                  var lbl = labelTextOf(el);
                  if (lbl) return done({ strategy:'label', name:lbl });
                }
                // 4. 语义角色 + 可访问名（140）—— 覆盖所有"有意义的" ARIA 角色（button/link/textbox/
                //    heading/img/listitem/region 等），不再限于可点击控件，对齐 page.pause 的 getByRole
                //    对全部角色生效；仅 generic/none/presentation 这类无语义角色走下方 text/data-i18n 兜底。
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
                  // 有角色无名称（pause 打 510 分，比 #id 还差）：继续走下方候选
                }
                // 4.5 data-i18n 多语言 key（本项目约定）：走到这里说明元素无语义角色（generic 的 span/div 等，
                //     有语义角色的元素已在上方 step 4 走 role+key）。对无角色的纯文本/容器元素，data-i18n 的
                //     属性值即多语言 key——语言无关、比可见文本稳定，生成时转为 @Element("[data-i18n=\"key\"]")
                //     （CSS 属性选择器），无需在 @RoleElement 设专门字段。
                var i18n = el.getAttribute('data-i18n');
                if (i18n && i18n.trim()) return done({ strategy:'i18n', value:i18n.trim(), name:i18n.trim() });
                // 5. alt（160；pause 中先于 text）
                if (tag === 'img' || tag === 'area') {
                  var alt = el.getAttribute('alt');
                  if (alt && alt.trim()) return done({ strategy:'altText', attr:'alt', value:alt.trim(), name:alt.trim() });
                }
                // 6. 可见文本（180；pause 截断阈值 80 字符）
                var ot = ownVisibleText(el);
                if (ot && ot.length <= 80) return done({ strategy:'text', name:ot, exact:true });
                // 7. title（200）
                var title = el.getAttribute('title');
                if (title && title.trim()) return done({ strategy:'title', attr:'title', value:title.trim(), name:title.trim() });
                // 8. 稳定 id（500）
                var id = el.getAttribute('id');
                if (isStableId(id)) return done({ strategy:'id', id:id });
                // 9. css 兜底
                return done({ strategy:'css', css: cssPathOf(el), needsReview:true });
              };

              // 定位器签名（与 Java 端 RoleElementPageGenerator.locatorKey 规则一致）：
              // role 按 role+name/key；id/css 按选择器；其余语义策略按 strategy+name。
              // 同一签名重复点击不入列、计数不增，避免生成端才去重导致“点了但计数不变”的困惑。
              window.__pickSig = function(pick) {
                if (!pick) return '';
                var base;
                if (pick.strategy === 'role') {
                  base = 'role:' + (pick.role || '') + ':' + (pick.key || pick.name || '');
                } else if (pick.strategy === 'i18n') base = 'i18n:' + (pick.name || '');
                else if (pick.strategy === 'id') base = 'id:' + (pick.id || '').replace(/^#/, '');
                else if (pick.strategy === 'css') base = 'css:' + (pick.css || '');
                else base = pick.strategy + ':' + (pick.name || '');
                // 一组同定位器元素（如多条同名链接）按序号区分签名，使 nth(0)/nth(1) 均可独立拾取，
                // 不被互相去重（页面字段仍按不含序号的 locatorKey 归一为同一个 PageElement）。
                if (pick.index != null && pick.index >= 0) base += '#' + pick.index;
                return base;
              };
              // 去重复合键：签名 + 所属 Page 类。关键修复：不同页面上 role/name 完全相同的“共用元素”
              // （如各页都有的 Close / Next 按钮、页眉页脚链接）原本只按签名去重，会被误判为重复而被丢弃
              // （表现：跳到新页面，有些元素没抓到 / 关弹窗后弹窗元素丢失）。把 _pageClass 纳入去重键后，
              // 同一页内仍按签名去重（同一元素重复点只保留一份），但跨页同名元素各自独立保留。
              window.__sigKey = function(pick) {
                var pageKey = (pick && pick._pageClass) || '';
                // 页面类（_pageClass）就是页面身份，稳定可靠：有它时去重键只用 [pickSig, pageClass]，
                // 不再附加 URL。此前无条件把 location.href 拼进键，导致“URL change 后再回到本页”时
                // 因 query/hash 抖动使同一元素的键改变、去重失配 → role 元素整组重复收录（本次 bug 根因）。
                // 仅当 _pageClass 尚未派生（整页跳转瞬间为空）才用规范化路径（origin+pathname，去掉 query/hash）
                // 兜底区分跨页同名元素，避免旧页签名误判；query/hash 抖动被归一，不再重复。
                if (!pageKey) {
                  try { if (location) pageKey = (location.origin || '') + (location.pathname || ''); } catch (e) {}
                }
                return JSON.stringify([window.__pickSig(pick) || '', pageKey]);
              };
              // 落盘最新拾取态到 localStorage：用于“点击会触发整页跳转的元素”场景——
              // 跳转瞬间 onFrameNavigated 恢复用的 Java 快照由主循环每 ~1s 刷新，可能来不及包含刚刚这次点击，
              // 导致刚点的元素在 window 重建后被旧快照覆盖丢失（表现：点了“返回登录”按钮却没被拾取上）。
              // pagehide/beforeunload 时把最新态写入 localStorage（同域整页跳转间 localStorage 保留），
              // 新窗口 onFrameNavigated 再据此合并补回最新点击的元素。
              // 即时落盘（供 pagehide/beforeunload 调用，不被节流，确保整页跳转瞬间 localStorage 是最新态）。
              function __persistNow() {
                try {
                  localStorage.setItem('__rolePickState', JSON.stringify({
                    picks: window.__rolePicks || [],
                    steps: window.__steps || [],
                    currentStep: window.__currentStep || [],
                    sigs: window.__rolePickSigs || {}
                  }));
                } catch (e) {}
                window.__lastPersist = Date.now();
              }
              // 企业级优化：每次点击都全量序列化 localStorage（随拾取增多 O(n²)）是拾取卡顿来源之一。
              // 改为节流——相邻高频拾取最多每 ~120ms 落盘一次；但 pagehide/beforeunload 走 __persistNow 即时落盘，
              // 保证“点击触发整页跳转”前最后一击一定已写入 localStorage（onFrameNavigated 合并恢复依赖它，不丢元素）。
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
              // 去重键改为“签名+页面类”（__sigKey），跨页同名元素不再互相误删。
              window.__rolePickerLib = true;
              }
              window.__rolePickSigs = {};
              window.__sigToPick = {};
              (window.__rolePicks || []).forEach(function(p) {
                var k = window.__sigKey(p);
                if (k) window.__rolePickSigs[k] = true;
                var s = window.__pickSig(p);
                if (s) window.__sigToPick[s] = p;
              });
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
                  }
                }, true);
              }
              // 实时悬停高亮 + 悬停拾取（hover）模式：鼠标移到元素上即时描边，开启“悬停拾取”后
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
                var t = target;
                if (!t) return null;
                var pick = window.__computePick(t);
                pick._pageClass = window.__rolePageName || '';
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
                var sig = window.__pickSig(pick);
                var key = window.__sigKey(pick);
                var dup = key && window.__rolePickSigs[key];
                if (!dup) {
                  if (key) window.__rolePickSigs[key] = true;
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
                // 选择模型（替代原“所有拾取自动并入 currentStep = 一个 step”）：
                //   · 整页扫描期间（window.__scanning 为 true）的元素只是“候选”，不直接入选当前 step，
                //     由用户在面板上勾选后再「封装为步骤」；
                //   · 页面上的实时点选（非扫描、非重复）视为用户主动拾取，自动入选当前 step（选择集）；
                //   · 重复拾取（dup）不重复入选，避免 step 内出现重复元素。
                if (window.__currentStep && !window.__scanning && !dup && !__rapid) window.__currentStep.push(pick);
                // 状态外置（对齐 page.pause）：去掉“每次点击全量序列化 localStorage”这一 O(n) 瓶颈，
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
                  setTimeout(function() { t.style.outline = prev; }, 400);
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
                  // （按 sig 去重，与 exposeFunction 调用幂等）。同时便于排查“二次拾取没反应”。
                  try { console.log('__roleOnPick::' + JSON.stringify(pick)); } catch(_){}
                  // 跨 frame 同步：点击若发生在 iframe 内，顶层主框架可见面板读的是主框架 window.__rolePicks，
                  // 而本 frame 把 pick push 进了自己的 window.__rolePicks（主框架读不到），表现为“内存态增长、面板空白”。
                  // 故把 pick postMessage 给父窗口，由顶层面板监听聚合进其 window.__rolePicks 并渲染。
                  // 纯前端同步，不依赖 Java 主循环轮询（postMessage 不受同源限制，跨源 iframe 同样生效）。
                  if (window.self !== window.top) {
                    try { window.parent.postMessage({ __rolePickMsg: true, pick: pick }, '*'); } catch (e) {}
                  }
                }
                return pick;
              };
              // 整页 role 树扫描（对齐 page.pause 的 role-centric 理念，但更完整）：
              // 遍历整页所有元素，凡“有语义角色（非 generic/none/presentation）且有可访问名”的可见元素，
              // 一律经 __recordPick 记录为 pick——复用点击拾取的全套链路（去重 / 面板渲染 / __roleOnPick 回传 Java）。
              // 与点击录制“录到什么才有什么”互补：扫描把整页所有语义角色元素一次性收全，用户随后停止即生成。
              window.__roleScanPage = function() {
                if (typeof window.__recordPick !== 'function') return -1;
                function __visForScan(el) {
                  try {
                    if (!el || !el.getBoundingClientRect) return false;
                    var cs = window.getComputedStyle(el);
                    if (!cs || cs.display === 'none' || cs.visibility === 'hidden' || parseFloat(cs.opacity || '1') === 0) return false;
                    var r = el.getBoundingClientRect();
                    return (r.width > 0 && r.height > 0);
                  } catch (e) { return true; }
                }
                var prevActive = window.__rolePickActive;
                window.__rolePickActive = true;   // 扫描期间强制激活，使 __recordPick 记录（结束后还原）
                window.__scanning = true;         // 抑制 __recordPick 的逐元素 UI 反馈/Java 回传，结束统一处理
                var added = 0;
                try {
                  var els = document.querySelectorAll('*');
                  for (var i = 0; i < els.length; i++) {
                    var el = els[i];
                    if (el.closest && el.closest('#__rolePanel, #__roleCodeOverlay, #__roleHoverBox')) continue;
                    // 1) 先按语义角色过滤：最便宜的判定，可剔除绝大多数 generic div/span，
                    //    避免后续对它们做昂贵的可见性（getComputedStyle）/可访问名计算。
                    var role = (getRole(el) || '').toLowerCase();
                    if (!role || NON_ROLE[role]) continue;
                    // 2) 再按可访问名过滤：只需“有名字”，用 clean 名称一次计算即可（省去 getNameInfo 的二次计算）。
                    var name = (typeof getElementAccessibleName === 'function') ? getElementAccessibleName(el, false) : null;
                    if (!name) continue;
                    // 3) 最后才做昂贵的可见性判定（getComputedStyle + getBoundingClientRect）。
                    //    绝大多数元素已在 1)/2) 被过滤，getComputedStyle 调用量从「全体元素」降至「语义角色+带名元素」。
                    if (!__visForScan(el)) continue;
                    var before = window.__rolePicks ? window.__rolePicks.length : 0;
                    var pk = window.__recordPick(el, false);   // 内部核心去重仍执行；UI/回传副作用因 __scanning 被跳过
                    var after = window.__rolePicks ? window.__rolePicks.length : 0;
                    if (pk && after > before) added++;
                  }
                } catch (e) {
                  try { console.error('[roleScan] ' + (e && e.message)); } catch (_) {}
                }
                window.__scanning = false;
                window.__rolePickActive = prevActive;
                // 一次性渲染 + 批量回传 Java：替代逐元素的 O(N²) 渲染与 N 次回传 / 控制台事件洪流。
                try { if (window.__renderPicks) window.__renderPicks(); } catch (e) {}
                if (window.__rolePicks) {
                  for (var k = 0; k < window.__rolePicks.length; k++) {
                    var p = window.__rolePicks[k];
                    try {
                      if (typeof window.__sigKey === 'function') p._sigKey = window.__sigKey(p);
                      if (typeof window.__roleOnPick === 'function') window.__roleOnPick(JSON.stringify(p));
                    } catch (e) {}
                  }
                }
                var statusEl = document.getElementById('__roleStatus');
                if (statusEl) {
                  statusEl.textContent = 'RoleElement Picker：整页扫描完成，已拾取 ' + (window.__rolePicks ? window.__rolePicks.length : 0) + ' 个语义角色元素，按 ESC 结束';
                }
                return added;
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
              document.addEventListener('mousemove', window.__rolePickMove, true);
              window.__rolePickClick = function(event) {
                // 诊断：记录最近一次点击到达 handler 的时间与当时激活态（供 Java 侧回读，确认点击是否被监听捕获）。
                window.__lastClickTs = Date.now();
                window.__lastClickActive = !!window.__rolePickActive;
                var t = event.target;
                if (t && t.closest && t.closest('#__rolePanel, #__roleCodeOverlay')) {
                  return;
                }
                var pick = window.__recordPick(t, false);
                if (!pick) return;
                // 对齐 page.pause()：拾取模式下【点击穿透】——元素的真实事件与默认行为照常触发
                // （button 的 onclick、链接跳转、表单提交都会真实发生），仅在此同步记录/定位该元素，
                // 不再用 preventDefault 吞掉元素的真实交互。曾经为“留在当前页连续拾取”而阻止 a[href]/form 的默认导航，
                // 但那样会吞掉点击（如点按钮却没触发其事件/业务），与 page.pause() 的“点击即真实交互 + 定位”不一致。
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
                    // 标记“该点击会弹出新页面”：生成 step 时包装为
                    // page.waitForPopup(() -> element.click())（对齐 page.pause() 的 codegen 输出）。
                    pick.popup = true;
                  }
                  // 同标签页普通链接：放行真实导航（不再 preventDefault）
                }
                // 按钮/表单提交：放行真实提交（点击按钮应触发其事件与业务），不再 preventDefault。
                // 同步把最新 currentStep 落盘：若本次点击触发整页导航，onFrameNavigated 合并恢复时
                // localStorage 已含本次点击（元素因读 Java 内存 javaPickBySig 仍存在，故“元素有、step 也有”）。
                try { window.__persistNow(); } catch (e) {}
              };
              // 双击拾取（dblclick）：对齐 page.pause() 对 doubleClick 动作的录制。
              // dblclick 在两次 click 之后触发，两次 click 已把该元素记录为 click 动作；此处按同一元素
              // 去重复位到同一 pick，追加 dblclick 标记（以最近一次交互为准），生成 step 时输出 doubleClick()。
              // 因 __recordPick 内部已“首次即时回传”一份不含 dblclick 的 pick 到 Java 内存态，
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
                if (event.key === 'Escape') { window.__pickDone = true; }
              };
              document.addEventListener('click', window.__rolePickClick, true);
              document.addEventListener('dblclick', window.__rolePickDblClick, true);
              document.addEventListener('keydown', window.__rolePickKey, true);
              // ===== 诊断：额外鼠标事件监听（仅记录、不拦截、不影响拾取）=====
              // 排查“刷新后点击无反应 / 点击卡顿”：记录 mousedown/up/dblclick/contextmenu 是否真到达 document。
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
    private static final String START_SCRIPT = concat(START_SCRIPT_A, START_SCRIPT_B);

    /** 运行时拼接，避免 javac 将 START_SCRIPT_A + START_SCRIPT_B 折叠为单一超长常量（越过 65535 字节上限）。 */
    private static String concat(String a, String b) {
        return a + b;
    }

    /** 关闭拾取模式：移除监听 + 收尾当前 step + 移除提示条 */
    private static final String STOP_SCRIPT = """
            (function() {
              // 关键修复：无条件移除监听并置位（不再因 __rolePickActive 已为 false 而早退）。
              // 早退会在“Java 端 active[0] 与浏览器端 __rolePickActive 因竞态不一致”时，
              // 导致应停止的页面监听残留、状态错乱，进而出现“停止后再开始拾取不了”。
              // removeEventListener 对同一函数引用幂等，重复调用安全。
              document.removeEventListener('click', window.__rolePickClick, true);
              document.removeEventListener('mousemove', window.__rolePickMove, true);
              document.removeEventListener('keydown', window.__rolePickKey, true);
              window.__rolePickActive = false;
              // 收尾当前 step：停止拾取即把“当前选中（已勾选）的候选”封装成 step 并入 __steps。
              // 面板勾选/实时点选都写入 window.__currentStep（选择集），停止时一次性打包为 step；
              // 整个选择（无论跨多少个页面）合并为【一个 step】——step 的唯一边界是“开始→停止”
              // （或一次「封装为步骤」），弹窗打开/关闭、页面跳转都只是同一 step 内的交互。
              // 优先用 __packageStep（与面板“封装为步骤”同一逻辑，保证顺序/去重一致）；兜底直接打包。
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
                function ok() { status.textContent = '已复制到剪贴板 ✔'; copyBtn.textContent = '已复制'; }
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
    private static final String PANEL_SCRIPT = """
    (function() {
      // 仅当 openPanel 运行期间（由 localStorage 开关标记）才注入面板；
      // 这样刷新/导航后 addInitScript 会自动重建面板，而正常访问不受影响。
      // 注意：外层必须用 (function(){...})() 而非 (() => {...})() —— 后者以 "(() =>" 开头，
      // 会被 Playwright 的 evaluate/addInitScript 误判成"箭头函数"并错误解析尾部的 ")();"，
      // 抛出 SyntaxError: Invalid or unexpected token。
      // 门禁：正常访问不打面板。openPanel/followPage 会显式置位 __rolePanelEnabled='1'；
      // 跨源新页面 localStorage 可能为空或不可写，故同时允许 window.__rolePanelForce 兜底
      // （由 followPage 注入），确保新页面无论如何都能重建面板。
      try { if (localStorage.getItem('__rolePanelEnabled') !== '1' && !window.__rolePanelForce) return; } catch (e) { return; }
      // 顶层面板接收来自 iframe（含跨源）的拾取：iframe 内点击 push 进的是 iframe 自己的 window.__rolePicks，
      // 主框架可见面板读不到 → 表现为“Java 内存态增长、面板空白”。iframe 经 postMessage 把 pick 发给父窗口，
      // 此处（顶层面板脚本）接收并聚合进主框架 window.__rolePicks + 渲染。纯前端同步，无需 Java 主循环轮询。
      window.addEventListener('message', function(ev) {
        try {
          var d = ev.data;
          if (!d || d.__rolePickMsg !== true || !d.pick) return;
          window.__rolePicks = window.__rolePicks || [];
          window.__rolePickSigs = window.__rolePickSigs || {};
          var p = d.pick;
          var key = p._sigKey || p._sig;
          if (key && window.__rolePickSigs[key]) return;
          if (key) window.__rolePickSigs[key] = true;
          window.__rolePicks.push(p);
          // 与主框架实时点选一致：拾取激活态下，iframe 内的点选也自动入选当前 step（选择集），
          // 用户无需再到面板重复勾选；整页扫描期间的 iframe 候选仍由用户勾选决定。
          if (window.__rolePickActive && window.__currentStep && !window.__scanning) window.__currentStep.push(p);
          if (window.__renderPicks) window.__renderPicks();
        } catch (e) {}
      });
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
        scan:  svg('<path d="M15.5 14h-.79l-.28-.27a6.5 6.5 0 1 0-.7.7l.27.28v.79l5 4.99L20.49 19l-4.99-5zm-6 0A4.5 4.5 0 1 1 14 9.5 4.5 4.5 0 0 1 9.5 14z"/>') // 🔍 扫描整页
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
              status.style.cssText = 'font-weight:normal;font-size:12px;opacity:.95;' +
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
                // 乐观即时反馈：点击后立刻让切换控件呈现“目标态”，无需等待 Java 往返 + START_SCRIPT 执行，
                // 消除“点了开始却要等往返才变成停止”的迟滞感（refreshToggle 会读取 __rolePickWanted）。
                // 关键修复：用“当前显示态”（真实激活态 ∪ 乐观意图）翻转来决定命令，而非直接读可能滞后的
                // window.__rolePickActive。否则快速连点 / Java 往返延迟期间 window.__rolePickActive 未及时更新，
                // 两次点击会读到相同旧值、发出相同命令（都 start 或都 stop）而非翻转，
                // 表现即“停止按钮有时点不动、停止后再点开始却拾取不了”。
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
                t.style.cssText = 'flex:1;padding:8px 0;border:0;cursor:pointer;font:13px/1.4 sans-serif;' +
                  'color:#fff;background:' + (active ? '#1e1e1e' : '#2d2d2d') + ';' +
                  'border-bottom:' + (active ? '2px solid #1e88e5' : '2px solid transparent') + ';';
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

              var copyBtn = mkIconBtn(ICON.copy, '#1976d2', '复制代码', function() {
                var ta = null, code = '';
                try {
                  if (window.__roleActiveTab === 'class') {
                    var k = window.__roleClassSubTabBar_active;
                    ta = k ? document.getElementById('__roleCodeArea__' + k) : null;
                    if (!ta) ta = document.querySelector('#__roleClassAreas textarea');
                    code = ta ? ta.value : '';
                  } else if (window.__roleActiveTab === 'step') {
                    var k2 = window.__roleStepSubTabBar_active;
                    ta = k2 ? document.getElementById('__roleCodeArea2__' + k2) : null;
                    if (!ta) ta = document.querySelector('#__roleStepAreas textarea');
                    code = ta ? ta.value : '';
                  } else {
                    // “页面元素”Tab：复制当前子 Tab（页面类过滤）下的元素清单文本，
                    // 每行与列表展示一致（strategy/role/name/id/css/index/标记），便于外部粘贴核对。
                    var act = window.__roleActivePageClass || '全部';
                    var lines = [];
                    (window.__rolePicks || []).forEach(function(p) {
                      if (!p) return;
                      var pc = p._pageClass || (window.__rolePageName || '未知页');
                      if (act !== '全部' && pc !== act) return;
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
                function ok() { status.textContent = '已复制 ✔'; copyBtn.title = '已复制'; }
                try {
                  if (navigator.clipboard && navigator.clipboard.writeText) {
                    navigator.clipboard.writeText(code).then(ok, function() { fb(); });
                  } else { fb(); }
                } catch (e) { fb(); }
                function fb() {
                  // 兜底：有文本框则选中复制；“页面元素”Tab 无文本框时用临时 textarea 承载再 execCommand。
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
              // 整页扫描：一次性收全当前页所有“带可访问名的语义角色元素”（heading/link/button/img/...），
              // 对齐 page.pause 的 role-centric 理念但更完整（点击录制只录点过的，扫描把整页语义角色全收）。
              // 走 Java 命令确保拾取库已注入后执行 window.__roleScanPage()，拾取结果与点击同一链路，随后点 ⏹ 停止生成。
              var scanBtn = mkIconBtn(ICON.scan, '#7e57c2', '扫描整页：一次性收全当前页所有带名称的语义角色元素（随后点停止生成代码）', function() {
                pushCmd('scan');
              });
              var abortBtn = mkIconBtn(ICON.abort, '#e53935', '终止运行', function() { pushCmd('abort'); });
              toolbar.appendChild(toggleBtn);
              toolbar.appendChild(scanBtn);
              toolbar.appendChild(copyBtn);
              toolbar.appendChild(abortBtn);

              // 根据 window.__rolePickActive 实时同步切换控件的状态（图标/文案/颜色）
              // 仅在拾取状态变化时重写 DOM，避免每 300ms 定时器无谓重绘（企业级：减少无变化重排）。
              var __lastPicking = null;
              function refreshToggle() {
                // 目标态 = 真实激活态 ∪ 用户刚点击的乐观意图（__rolePickWanted），使按钮在 Java 尚未确认时
                // 就立即反映点击结果；真实态一旦与意图一致即清除意图，避免 stop 后按钮卡在错误态。
                var wanted = !!window.__rolePickWanted;
                var picking = !!window.__rolePickActive || wanted;
                if (picking === __lastPicking) return;
                __lastPicking = picking;
                toggleBtn.innerHTML = picking ? ICON.stop : ICON.start;
                toggleBtn.title = picking ? '停止拾取' : '开始拾取';
                toggleBtn.style.background = picking ? '#fb8c00' : '#43a047';
                if (window.__rolePickWanted != null && window.__rolePickWanted === !!window.__rolePickActive) {
                  window.__rolePickWanted = null;
                }
              }
              refreshToggle();
              // 状态同步定时器：每 80ms 收敛到真实态，降低“开始”迟滞感。
              window.__roleToggleTimer = setInterval(refreshToggle, 80);

              // 页面类 / 步骤代码文本框按 pageClass 分栏动态创建（见 __fillCodeTabs / __renderCodeTabs），
              // 每个 pageClass 一个子 Tab 对应一个可复制的代码文本框，对齐“页面元素”Tab 的子 Tab 布局。

              // “页面元素” Tab 内容：页面类子 Tab 栏 + 候选项清单（带勾选框）+ 封装为步骤 按钮。
              var pageContent = document.createElement('div');
              pageContent.style.cssText = 'flex:1;display:flex;flex-direction:column;min-height:0;overflow:hidden;';
              var subTabBar = document.createElement('div');
              subTabBar.id = '__roleSubTabBar';
              subTabBar.style.cssText = 'display:flex;gap:0;overflow-x:auto;background:#1b1b1b;border-bottom:1px solid #111;flex:0 0 auto;';
              var listEl = document.createElement('div');
              listEl.id = '__rolePickList';
              listEl.style.cssText = 'flex:1;overflow:auto;padding:6px 8px;background:#161616;color:#bdbdbd;' +
                'font:12px/1.5 Consolas,Monaco,monospace;min-height:0;';
              var pkgRow = document.createElement('div');
              pkgRow.style.cssText = 'padding:8px 10px;background:#252526;border-bottom:1px solid #1b1b1b;display:flex;justify-content:flex-end;flex:0 0 auto;';
              var pkgBtn = document.createElement('button');
              pkgBtn.type = 'button';
              pkgBtn.textContent = '封装为步骤';
              pkgBtn.title = '把当前勾选的候选项（按点击/扫描顺序）封装为一个 step；未勾选的可持续勾选后再次封装';
              pkgBtn.style.cssText = 'padding:6px 14px;border:0;border-radius:6px;cursor:pointer;background:#43a047;color:#fff;font:12px/1.4 sans-serif;';
              pkgBtn.onclick = function() {
                // 浏览器侧 __packageStep 先把勾选集打包为 step（与停止时同一逻辑，顺序/去重一致）；
                // 随后推送 'package' 命令给 Java 立即生成步骤代码并切到「步骤代码」Tab 展示（不再需要等到点 ⏹）。
                var r = (typeof window.__packageStep === 'function') ? window.__packageStep() : {pages:[],start:-1,count:0};
                if (r && r.count > 0) {
                  window.__pendingJump = r;   // 记录“目标 step”，Java 生成代码后由 __afterFillJump 精准定位
                  status.textContent = '已封装 ' + r.count + ' 个 step（累计 ' + (window.__steps ? window.__steps.length : 0) + ' 个），正在生成步骤代码…';
                  try { pushCmd('package'); } catch (e) {}
                } else {
                  status.textContent = '请先勾选要封装的元素（扫描后请在列表勾选，页面点选会自动勾选）';
                }
              };
              pkgRow.appendChild(pkgBtn);
              pageContent.appendChild(subTabBar);
              pageContent.appendChild(pkgRow);
              pageContent.appendChild(listEl);

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
                tabPage.style.background = (t === 'page') ? '#1e1e1e' : '#2d2d2d';
                tabPage.style.borderBottom = (t === 'page') ? '2px solid #1e88e5' : '2px solid transparent';
                tabClass.style.background = (t === 'class') ? '#1e1e1e' : '#2d2d2d';
                tabClass.style.borderBottom = (t === 'class') ? '2px solid #1e88e5' : '2px solid transparent';
                tabStep.style.background = (t === 'step') ? '#1e1e1e' : '#2d2d2d';
                tabStep.style.borderBottom = (t === 'step') ? '2px solid transparent' : '2px solid #1e88e5';
              }
              // 暴露给 Java 侧调用（扫描完成后自动切到“页面类”Tab，让用户即时看到生成的页面类代码）。
              window.__roleShowTab = showTab;

              // 按 pageClass 把页面类 / 步骤代码分栏渲染（对齐“页面元素”Tab 的子 Tab 布局）：
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
              // 而非泛泛切到步骤 Tab 让用户自己找——对齐“封装即定位到刚生成的 step”。
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
                  (window.__currentStep || []).forEach(function(p) {
                    var k = p && (p._sigKey || p._sig); if (k) selSet[k] = true;
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
                  for (var i = 0; i < all.length; i++) {
                    var p = all[i] || {};
                    var k = p._sigKey || p._sig;
                    if (!k || !selSet[k]) continue;
                    if (!owner) owner = (p._pageClass) || (window.__rolePageName || '');
                    picks.push(p);
                  }
                  if (!owner) owner = (window.__rolePageName || '');
                  window.__steps.push({ pageClass: owner, picks: picks });
                  window.__currentStep = [];   // 已封装，清空选择集
                  // 仅轻量刷新勾选态（复选框复位为未勾选），不重建整张候选列表——
                  // 扫描出海量子元素时全量重建会卡 UI，正是“封装 step 慢”的根因。
                  window.__applySelection();
                  // 返回结构化信息：本次封装涉及的 pageClass（按序）与全局起始索引，
                  // 供 pkgBtn 记录“目标 step”，Java 生成代码后由 window.__afterFillJump 精准定位。
                  return { pages: [owner], start: startIndex, count: 1 };
                } catch (e) { return 0; }
              };
              // 轻量刷新勾选态：仅更新已存在行的复选框与高亮，【不重建整个列表 DOM】。
              // 供「封装为步骤」等“仅选择集变化、候选集合不变”的场景调用，避免对扫描出的海量元素做 O(n) 全量重建（卡 UI 的根因）。
              window.__applySelection = function() {
                try {
                  var listEl = document.getElementById('__rolePickList');
                  if (!listEl) return;
                  var selSet = {};
                  (window.__currentStep || []).forEach(function(p) {
                    var k = p && (p._sigKey || p._sig); if (k) selSet[k] = true;
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
                } catch (e) {}
              }
              function __renderPicksNow() {
                try {
                  var subBar = document.getElementById('__roleSubTabBar');
                  var listEl = document.getElementById('__rolePickList');
                  if (!subBar || !listEl) return;  // 面板尚未挂载，等下次拾取/恢复时再渲染
                  var picks = window.__rolePicks || [];
                  // 统计各 pageClass 候选数，用于子 Tab 命名与计数（命名以 page class）。
                  var counts = {};
                  for (var i = 0; i < picks.length; i++) {
                    var pc = (picks[i] && picks[i]._pageClass) || (window.__rolePageName || '未知页');
                    counts[pc] = (counts[pc] || 0) + 1;
                  }
                  var pageClasses = Object.keys(counts);
                  var act = window.__roleActivePageClass || '全部';
                  if (act !== '全部' && pageClasses.indexOf(act) < 0) act = '全部';
                  window.__roleActivePageClass = act;
                  // 渲染页面类子 Tab（命名以 page class）
                  subBar.innerHTML = '';
                  function mkSub(label, key) {
                    var b = document.createElement('button');
                    b.type = 'button';
                    b.textContent = label + ((key !== '全部' && counts[key] != null) ? (' (' + counts[key] + ')') : '');
                    b.style.cssText = 'flex:0 0 auto;padding:6px 12px;border:0;cursor:pointer;font:12px/1.4 sans-serif;' +
                      'color:#ccc;background:' + (act === key ? '#1e88e5' : '#2d2d2d') + ';' +
                      'border-bottom:2px solid ' + (act === key ? '#1e88e5' : 'transparent') + ';';
                    b.onclick = function() { window.__roleActivePageClass = key; window.__renderPicks(); };
                    return b;
                  }
                  subBar.appendChild(mkSub('全部', '全部'));
                  for (var c = 0; c < pageClasses.length; c++) subBar.appendChild(mkSub(pageClasses[c], pageClasses[c]));

                  // 渲染候选项（带勾选框）。勾选态 = 该 pick 的 sig 在选择集 window.__currentStep 中。
                  listEl.innerHTML = '';
                  if (!picks.length) { listEl.textContent = '（暂无拾取：点 🔍 扫描整页，或在页面点击元素）'; return; }
                  var selSet = {};
                  (window.__currentStep || []).forEach(function(p) {
                    var k = p && (p._sigKey || p._sig); if (k) selSet[k] = true;
                  });
                  for (var i2 = 0; i2 < picks.length; i2++) {
                    var p = picks[i2] || {};
                    var pc = (p._pageClass) || (window.__rolePageName || '未知页');
                    if (act !== '全部' && pc !== act) continue;
                    var sk = p._sigKey || p._sig;
                    var sel = !!(sk && selSet[sk]);
                    var row = document.createElement('label');
                    row.style.cssText = 'display:flex;gap:6px;align-items:flex-start;padding:3px 4px;cursor:pointer;' +
                      (sel ? 'background:rgba(30,136,229,.18);' : '') + 'border-bottom:1px solid #111;';
                    var cb = document.createElement('input');
                    cb.type = 'checkbox'; cb.checked = sel;
                    cb.style.cssText = 'margin-top:3px;flex:0 0 auto;';
                    // 关键修复：cb/row 均为 var 函数作用域的循环共享变量，若闭包直接引用外层 cb/row，
                    // 等 onchange 真正触发时它们已指向【最后一次】迭代创建的元素，导致读到的 checked 状态
                    // 错乱、勾选不生效（表现为“勾选不了”）。故用 IIFE 参数按迭代捕获当前 cb/row/pk，
                    // 并改为【直接更新该行高亮】，不再全量重建列表（消除闪烁与 O(n) 重建竞态）。
                    (function(pk, checkbox, rowEl) {
                      checkbox.onchange = function(e) {
                        // 阻断冒泡：勾选只做 O(1) 增量更新（仅该行高亮 + 选择集维护），
                        // 不冒泡到 document 的拾取监听链路，避免意外的 tab 切换/卡顿。
                        if (e && e.stopPropagation) e.stopPropagation();
                        window.__currentStep = window.__currentStep || [];
                        var s = pk && (pk._sigKey || pk._sig);
                        if (!s) return;
                        var idx = -1;
                        for (var j = 0; j < window.__currentStep.length; j++) {
                          var e = window.__currentStep[j];
                          if (e && (e._sigKey || e._sig) === s) { idx = j; break; }
                        }
                        if (checkbox.checked) {
                          if (idx < 0) window.__currentStep.push(pk);
                          rowEl.style.background = 'rgba(30,136,229,.18)';
                        } else {
                          if (idx >= 0) window.__currentStep.splice(idx, 1);
                          rowEl.style.background = '';
                        }
                      };
                    })(p, cb, row);
                    var txt = document.createElement('span');
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
                    txt.textContent = s;
                    txt.style.cssText = 'flex:1;white-space:pre-wrap;word-break:break-all;';
                    row.appendChild(cb); row.appendChild(txt);
                    // 记录复选框与签名引用，供 __applySelection 增量刷新（不重建列表）。
                    row.__cb = cb; row.__sig = sk;
                    listEl.appendChild(row);
                  }
                } catch (e) {
                  try { var le = document.getElementById('__rolePickList'); if (le) le.textContent = '（列表渲染失败）'; } catch (_) {}
                }
              }
              window.__renderScheduled = false;
              window.__renderPicks();
              // 暴露幂等“确保面板存在”入口：供门控脚本在 SPA 路由切换（pushState/hashchange 等）自愈时调用——
              // 框架重渲染 document 子树可能把 docked 面板 DOM 冲掉，此时仅重建面板并立即重渲染已拾元素
              // （已拾元素存于 window.__rolePicks，不随 DOM 重建丢失）。build() 内部已对“已存在面板”做 remove 再重建，故幂等。
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
                // 多实例：会话级“开始”作用于所有已打开页面，使各页面板同步显示 ⏹ 停止
                // （active[0] 是会话权威开关，followPage / onFrameNavigated 据此决定是否重启监听）。
                active[0] = true;
                // 反向查表只构建一次（避免对每个被跟踪页面重复读 nls 文件），减少点击“开始”的延迟。
                String startNls = buildNlsReverseJson(Arrays.asList(nlsFiles));
                for (Page p : pageNames.keySet()) {
                    if (!p.isClosed()) { log.info("[picker][start] 对页面 {} 调用 start", p.url()); start(p, startNls); }
                }
                return new PickerResult(PickerAction.CONTINUE, null, null,
                        "RoleElement Picker：点击元素拾取 role/name，按 ESC 结束");
            case "scan": {
                // 整页 role 树扫描：先复用 start 注入拾取库（定义 __roleScanPage/__recordPick），
                // 再对命令来源页运行 window.__roleScanPage()——把整页所有“带可访问名的语义角色元素”
                // 经 __recordPick 记录，与点击拾取同一链路（去重 / 面板渲染 / __roleOnPick 回传 javaPickBySig）。
                // 用户随后点 ⏹ 停止即从 javaPickBySig 生成代码（无需为扫描单独实现生成逻辑）。
                active[0] = true;
                String scanNls = buildNlsReverseJson(Arrays.asList(nlsFiles));
                if (!page.isClosed()) start(page, scanNls);
                int added = -1;
                try {
                    Object r = page.evaluate(
                            "(function(){ try { return (typeof window.__roleScanPage==='function') ? window.__roleScanPage() : -1; } catch(e){ return -1; } })()");
                    if (r instanceof Number) added = ((Number) r).intValue();
                } catch (Exception e) {
                    log.warn("[picker][scan] 整页扫描执行失败：{}", e.getMessage());
                }
                log.info("[picker][scan] 整页扫描完成：新增 {} 个语义角色元素", added);
                // 扫描完成后【立即生成页面类代码】，无需等到点 ⏹ 停止：直接同步读取浏览器侧
                // window.__rolePicks（readPickSnapshot 走 page.evaluate，比依赖异步的 __roleOnPick 回传更可靠），
                // 按 pageClass 分组生成页面类并填入“页面类”Tab，同时自动切到该 Tab 让用户即时看到。
                // 此后用户仍可继续勾选元素「封装为步骤」、或点 ⏹ 停止重新生成（含步骤代码）。
                try {
                    PickSnapshot snap = readPickSnapshot(page);
                    if (snap != null && !snap.entries.isEmpty()) {
                        LinkedHashMap<String, String> codePage = buildPageClassCode(snap.entries, packageName, pageClassName, nlsFiles);
                        if (codePage != null && !codePage.isEmpty()) {
                            try {
                                page.evaluate("try{window.__roleActiveTab='class';if(window.__roleShowTab)window.__roleShowTab();}catch(e){}");
                            } catch (Exception ignore) {}
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
            case "stop": {
                active[0] = false;
                log.info("[picker][stop] 收到停止命令，对 {} 个被跟踪页面执行停止", pageNames.size());
                // 多实例：停止作用于所有已打开页面，使各页面板同步回 ▶ 开始
                // （否则某页仍显示停止却已失活，造成“点了没反应”的错觉）。
                // 企业级优化：命令来源页用 stopAndRead 把“去激活 + 收尾当前 step + 读回全部拾取态”
                // 合并为 1 次 Java↔浏览器往返；其余被跟踪页仅去激活（stop），避免重复生成代码与多余读快照。
                // （原实现对命令页额外发一次收尾 evaluate + 一次 readPickSnapshot，与 stop() 共 3 次串行往返，
                //  现已合并进 stopAndRead，点击“停止”的端到端延迟显著下降。）
                PickSnapshot snap = null;
                // 折叠已关闭页（如“跳转到新页面后直接关闭”的根页）缓存的 step/op：停止生成时这些页被跳过，
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
                // 关键修复（修复“开始→停止→再开始→停止，第一次的步骤代码丢失”）：
                // 整页跳转时 onFrameNavigated→applyPickState 会用 snapshots 里的 Java 恢复态【整体覆盖】
                // window.__steps（见 applyPickState：window.__steps = s.steps || []）。而快照此前只在“空闲刷新”时更新，
                // 且 run1 拾取期间 __steps 为空、空闲刷新把恢复态停在“空 steps”；若 run2 中发生整页跳转，
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
                // 按 pageClass 分别生成页面类（含“仅 step/ops 无元素 pick”的页，也产出空字段类，保证步骤视图引用不悬空）
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
     * @param nlsReverseJson    nls 反向查表 JSON；为 null/空/“{}” 时退化为不反查（回退 slug）
     */
    public static void start(Page page, String nlsReverseJson) {
        // CI 环境：拾取模式是本地开发工具，自动化测试里不应开启并等待人工拾取，直接跳过。
        if (isCiRun()) {
            log.info("[picker] 检测到 CI 运行环境，跳过拾取模式（start）。");
            return;
        }
        // 兼容两种格式：新格式 {exact, templates} 拆开注入；旧格式（纯精确表）整体作为 exact。
        // 企业级优化：把“会话开关置位 + nls 反向表注入 + START_SCRIPT 开启监听”合并进同一次 page.evaluate，
        // 点击“开始拾取”只付出 1 次 Java↔浏览器往返（原来 2 次串行），按钮即时响应。
        // 会话开关 __rolePickSessionOn（对齐 page.pause 的 mode 下推）：context 级门控注入脚本
        // （gatedPickerInitScript）据此在【每个新文档】自动重挂拾取监听——导航/弹窗/SPA 整文档替换后
        // 拾取存活由浏览器原生保证，无需 Java 端手动重挂。
        page.evaluate(
                "try{localStorage.setItem('__rolePickSessionOn','1');}catch(e){}"
                + " try{window.__rolePickSessionOn=true;}catch(e){}"
                // 重置面板切换控件的“乐观意图”位：避免上一轮 stop/start 残留的 __rolePickWanted 让
                // 按钮的 willStart 计算发出错误命令（关键修复“停止后再点开始却拾取不了”的边界之一）。
                + " try{ window.__rolePickWanted = null; }catch(e){}"
                // 关键修复（停止→再开始拾取不了）：同文档“二次开始”若沿用“已注入”状态（window.__rolePickerLib 已为 true），
                // START_SCRIPT 会跳过整套库定义、仅依赖顶部 addEventListener 保活；一旦该保活因竞态未真正生效
                // （典型如 stop 已 removeEventListener 移除旧函数引用、而重激活路径没把它加回），表现即
                // “停止后再点开始却拾取不了 / 跳转到新页面拾取不到”。这里主动移除旧监听并把 __rolePickerLib 复位为 false，
                // 强制 __roleGatedStart 重新走“完整库定义 + 重新 addEventListener”分支——与刷新/跳转全新文档的
                // 已知可用路径完全一致，从根消除“二次开始”与“刷新”的行为差异。
                // window.__rolePicks 仍用 || [] 保留，__rolePickSigs/__sigToPick 随后从既有 picks 重建，已拾元素与去重都不丢。
                + " try{ if(window.__rolePickClick) document.removeEventListener('click', window.__rolePickClick, true); }catch(e){}"
                + " try{ if(window.__rolePickMove) document.removeEventListener('mousemove', window.__rolePickMove, true); }catch(e){}"
                + " try{ if(window.__rolePickKey) document.removeEventListener('keydown', window.__rolePickKey, true); }catch(e){}"
                + " try{ window.__rolePickerLib = false; }catch(e){}"
                + " var __o = " + (nlsReverseJson == null ? "{}" : nlsReverseJson) + ";"
                + " window.__nlsReverse = (__o && __o.exact) ? __o.exact : (__o && __o.templates ? {} : (__o || {}));"
                + " window.__nlsTemplates = (__o && __o.templates) ? __o.templates : [];"
                // 优先复用门控脚本封装的 window.__roleGatedStart（含 load/pageshow 自愈入口），保证 Java 侧
                // 触发的重注入与浏览器原生重注入走同一份逻辑、nls 反查表一致；不可用时退化直接执行 START_SCRIPT。
                // 由于上面已把 __rolePickerLib 复位为 false，此处的 __roleGatedStart / START_SCRIPT 都会重新定义整套库并重新挂监听。
                + " if (typeof window.__roleGatedStart === 'function') { window.__roleGatedStart(); } else { " + START_SCRIPT + " }");
        log.info("[picker] 拾取模式已开启：在浏览器点击元素即可拾取，按 ESC 结束。");
        // 诊断：start() 注入后确认监听真正挂载（排查“点击没反应”究竟是注入失败还是被后续覆盖）。
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
     * 把 nls 文件构建成“规范化后的文本值 → key”的反向查表 JSON（覆盖所有语言），
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
        page.evaluate("try{localStorage.removeItem('__rolePickSessionOn');}catch(e){}"
                + " try{window.__rolePickSessionOn=false;}catch(e){}"
                + STOP_SCRIPT);
    }

    /**
     * 读取当前已拾取的元素列表（不阻塞、不生成）。
     * 可在 {@link #start} / {@link #stop} 之间多次调用，实时查看进度。
     */
    @SuppressWarnings("unchecked")
    public static List<RoleEntry> getEntries(Page page) {
        List<RoleEntry> result = new ArrayList<>();
        Object raw = page.evaluate("Array.from(window.__rolePicks || [])");
        if (raw instanceof List) {
            for (Object o : (List<Object>) raw) {
                if (o instanceof Map) {
                    RoleEntry e = parsePick((Map<Object, Object>) o);
                    if (e != null) result.add(e);
                }
            }
        }
        return result;
    }

    /**
     * 把 Java 权威拾取内存态（javaPickBySig）同步到浏览器面板展示数组 window.__rolePicks 并触发渲染。
     * 修复“Java 内存态增长、浏览器实时面板空白”：浏览器侧 window.__rolePicks 因去重 / iframe / 导航时序
     * 未被可靠填充，导致点击时面板列表看不到已拾元素。此处以 Java 侧为准，保证面板实时反映已拾内容。
     * 仅用于面板展示；代码生成仍走 javaPickBySig（见 runPickerCommand），不受影响。
     */
    /**
     * 把 Java 权威拾取内存态（javaPickBySig）按目标页 pageClass 过滤后同步到该页浏览器面板展示数组
     * window.__rolePicks 并触发渲染。
     * 修复“当前跟随页(current[0])不是用户正在点击的页时，那个页面的面板空白、看不到已拾元素”：
     * 改为对每个被跟踪页面分别同步（调用处遍历 pageNames），使任一页面的面板都能实时反映 Java 侧已拾内容。
     * 仅用于面板展示；代码生成仍走 javaPickBySig（见 runPickerCommand），不受影响。
     * 注意：不再清空 window.__rolePickSigs，避免干扰浏览器端真实点击的去重计数。
     */
    private static void syncPanelToBrowser(Page page, String pageClass, LinkedHashMap<String, RoleEntry> state) {
        if (page == null || page.isClosed() || state == null) return;
        try {
            // 仅取归属该页的拾取（pageClass 为空的元素兜底同步到所有页，避免漏显示）。
            List<RoleEntry> filtered = new ArrayList<>();
            for (RoleEntry e : state.values()) {
                String pc = e.getPageClass();
                if (pc == null || pc.isEmpty() || pc.equals(pageClass)) filtered.add(e);
            }
            String json = GSON.toJson(filtered);
            page.evaluate("(function(){"
                    + " try {"
                    + "   var arr = " + json + ";"
                    + "   // 把 Java RoleEntry 的字段名映射回浏览器 pick 的字段名（selector→css/id、resolvedKey→key、pageClass→_pageClass），"
                    + "   // 使下面合并去重键与浏览器侧 window.__rolePickSigs 完全一致：同一元素点击后“浏览器 push”与“Java 回传再合并”"
                    + "   // 用同一键去重，避免每条点击都在面板里重复出现。\n"
                    + "   function toPick(p){ if(!p) return p; var o={};"
                    + "     o.strategy=p.strategy; o.role=p.role; o.name=p.name;"
                    + "     o.key=(p.resolvedKey!=null)?p.resolvedKey:undefined;"
                    + "     // id 策略的 selector 在 Java 侧以 CSS 选择器形式存储（如 \"#logoHeader\"），\n"
                    + "     // 需剥掉前导 '#' 还原成裸 id（\"logoHeader\"），否则与浏览器直播点击产生的\n"
                    + "     // id=logoHeader 签名（id:logoHeader vs id:#logoHeader）不一致、无法去重，\n"
                    + "     // 面板里会多出一整条 \"id=#logoHeader\" 冗余副本。\n"
                    + "     o.id=(p.strategy==='id' && p.selector)? String(p.selector).replace(/^#/, '') : undefined;"
                    + "     o.css=(p.strategy==='css')?p.selector:undefined;"
                    + "     o.index=p.index; o._pageClass=p.pageClass; return o; }"
                    + "   // 改为【合并】而非【整体覆盖】 window.__rolePicks：window.__rolePicks 是浏览器侧按本标签页累积的"
                    + "   // 展示数组（点击/iframe 回传/导航恢复都会 push），整体覆盖会把它清空，再用 Java 按当前 pageClass"
                    + "   // 过滤的子集填充——一旦同页 URL 变化触发 onFrameNavigated 重派生 pageClass（或 SPA 路由切换），"
                    + "   // 旧 URL 拾取的元素（_pageClass=旧类）就被新类过滤甩掉，表现为“同页 url 变化后已拾元素消失”。"
                    + "   // 合并后用签名去重，保证“已拾的元素始终可见”，不随 pageClass 变化被清零。\n"
                    + "   window.__rolePicks = window.__rolePicks || [];"
                    + "   window.__rolePickSigs = window.__rolePickSigs || {};"
                    + "   arr.forEach(function(p){"
                    + "     var o = toPick(p);"
                    // 关键修复：push 的必须是转换后的浏览器格式 o（含 id/css/key/_pageClass），
                    // 而非原始 Java 格式 p（字段是 selector/pageClass/resolvedKey，无 id/_pageClass）。
                    // 之前 push(p) 导致：面板读不到 p.id → 出现无值的 “id” 行；p 缺 _sig/_pageClass →
                    // 破坏后续所有基于签名/页面类的去重与归类（表现为元素重复 / 空 id）。
                    + "     o._sig = (typeof window.__pickSig==='function') ? (window.__pickSig(o)||'') : '';"
                    + "     var k = (typeof window.__sigKey==='function') ? window.__sigKey(o)"
                    + "            : ((o&&(o._sigKey||o._sig))||null);"
                    + "     if (k && window.__rolePickSigs[k]) return;"
                    + "     if (k) window.__rolePickSigs[k]=true;"
                    + "     window.__rolePicks.push(o); });"
                    + "   if (window.__renderPicks) window.__renderPicks();"
                    + " } catch(e){} })();");
        } catch (Exception ignore) {}
    }

    /**
     * 自愈式保活：会话处于拾取中时，确保某页的点击捕获监听确实已挂载。
     * 任意页面变化（整文档替换、frame 内部跳转、onFrameNavigated 未覆盖到的边界情形）导致监听被静默丢弃后，
     * 主循环空闲期据此重挂 START_SCRIPT，保证“页面如何变化都能继续拾取”（用户核心需求）。
     * 重挂同时补注入 nls 反向查表，使导航后拾取仍能把 a11y name 反查为 key。
     * 幂等：监听已存活则零成本返回，不会重复挂载（START_SCRIPT 内部 __rolePickActive 早退 + 此处先探测）。
     */
    private static void ensurePickingActive(Page page, String nlsReverseJson, String[] nlsFiles) {
        if (page == null || page.isClosed()) return;
        try {
            // 会话处于拾取中（调用方已用 active[0] 守卫）：无条件重挂（幂等）监听，
            // 确保“停止后再开始 / 跳转到新页面 / 框架静默移除 document 级监听”等任何情形下点击必能拾取。
            // 关键修复：移除了原先的“if (active && hasClick) return”早退。
            // 该早退在“window.__rolePickActive 仍为 true 但 click 监听已被框架/导航静默移除”的竞态下会跳过重挂，
            // 表现即“停止后再点开始却拾取不了 / 跳转到新页面拾取不到”——因为激活态显示 true、函数引用还在（hasClick 为真），
            // 于是误判“无需重挂”，而真实监听早已不工作。START_SCRIPT 对同函数引用 addEventListener 幂等、
            // 不重复定义库，按 1s 节奏重挂安全无副作用，故此处改为“会话开则必重挂”。
            page.evaluate("try{localStorage.setItem('__rolePickSessionOn','1');}catch(e){}"
                    + " try{window.__rolePickSessionOn=true;}catch(e){}"
                    + " var __o = " + (nlsReverseJson == null ? "{}" : nlsReverseJson) + ";"
                    + " window.__nlsReverse = (__o && __o.exact) ? __o.exact : (__o && __o.templates ? {} : (__o || {}));"
                    + " window.__nlsTemplates = (__o && __o.templates) ? __o.templates : [];"
                    + " if (typeof window.__roleGatedStart === 'function') { window.__roleGatedStart(); } else { " + START_SCRIPT + " }");
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
     *  与 {@link #STOP_SCRIPT} 拼接即可在“停止”时一次性完成去激活 + 收尾 + 读回，避免多次往返。 */
    private static final String PICK_STATE_READER_JS = "(function(){"
            + " function norm(s){ var t=(s&&typeof s==='object')?s:null;"
            + "   var pc=(t&&typeof t.pageClass==='string')?t.pageClass:'';"
            + "   var ps=(t&&t.picks)?t.picks:(Array.isArray(s)?s:[]);"
            + "   return {pageClass:pc, picks:ps}; }"
            + " return {"
            + "   pageClass: (window.__rolePageName||''),"
            + "   picks: Array.from(window.__rolePicks||[]),"
            + "   steps: Array.from(window.__steps||[]).filter(function(s){"
            + "     return !(s&&typeof s==='object'&&typeof s.op==='string'); }).map(norm),"
            + "   ops: Array.from(window.__steps||[]).filter(function(s){"
            + "     return (s&&typeof s==='object'&&typeof s.op==='string'); })"
            + "     .map(function(s){ return {pageClass:(s.pageClass||''), op:s.op}; })"
            + " };"
            + "})()";

    /** 单次 {@code page.evaluate} 即取回「已拾元素 + step 序列 + 页面级操作 + 当前页类名」，
     *  把停止命令原本分散的多次浏览器往返合并为一次，降低点击“停止”的响应延迟。 */
    @SuppressWarnings("unchecked")
    private static PickSnapshot readPickSnapshot(Page page) {
        return parsePickSnapshot(page.evaluate(PICK_STATE_READER_JS));
    }

    /** “停止”命令专用：单次 {@code page.evaluate} 同时完成「去激活 + 收尾当前 step + 读回全部拾取态」，
     *  把原本 stop()（1 次）+ 收尾 evaluate（1 次）+ readPickSnapshot（1 次）三次往返合并为 1 次，
     *  点击“停止”即时得到快照并转交代码生成，不再串行等待多次 Java↔浏览器往返（企业级：减少关键路径往返）。 */
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
            if (p instanceof List) for (Object o : (List<Object>) p)
                if (o instanceof Map) { RoleEntry e = parsePick((Map<Object, Object>) o); if (e != null) entries.add(e); }
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
        // 过滤掉“页面级操作”step（含 op 字段，如关闭页面），它们由 getPageOpsWithPage 单独处理，
        // 否则会被当成“空 pick 的 step”生成无意义方法。
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
                Object ps = m.get("picks");
                if (ps instanceof List) {
                    for (Object item : (List<Object>) ps) {
                        if (item instanceof Map) {
                            RoleEntry e = parsePick((Map<Object, Object>) item);
                            if (e != null) picks.add(e);
                        }
                    }
                }
                result.add(new StepRec(pc, picks));
            }
        }
        return result;
    }

    /**
     * 定位器唯一型策略：同一 locator 跨页面指向同一元素，回传去重时按 locator 签名（_sig）而非 [sig, pageClass|URL]，
     * 避免“回到主页 / 关弹窗”时同一元素被重复收录（如 id=logoHeader 在主页与弹窗各存一份、主页元素多出一份）。
     * 角色策略（role+name）与 closeOp 仍按页面作用域（_sigKey）区分，保留跨页同名元素各自独立。
     */
    private static final java.util.Set<String> LOCATOR_IDENTITY_STRATEGIES = new java.util.HashSet<>(java.util.Arrays.asList(
            "id", "css", "i18n", "text", "title", "placeholder", "label", "testid", "altText"));

    /** 计算拾取回传的权威去重键：定位器唯一型策略用语 locator 签名（_sig），其余（role/closeOp）用页面作用域键（_sigKey）。 */
    private static String pickDedupKey(Map<Object, Object> m, RoleEntry e) {
        if (m == null) return "";
        Object sig = m.get("_sig");
        Object sigKey = m.get("_sigKey");
        String strategy = (e != null) ? e.getStrategy() : null;
        boolean locatorIdentity = strategy != null && LOCATOR_IDENTITY_STRATEGIES.contains(strategy);
        if (locatorIdentity && sig != null) return String.valueOf(sig);
        if (sigKey != null) return String.valueOf(sigKey);
        return sig != null ? String.valueOf(sig) : "";
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
        int index = parseIndex(m.get("index"));
        int level = parseLevel(m.get("level"));
        if ("role".equals(strategy)) {
            if (role == null && !closeOp) return null;   // 角色策略但无角色：跳过（关闭操作标记除外）
            String resolvedKey = asString(m.get("key"));
            if (resolvedKey != null && resolvedKey.isBlank()) resolvedKey = null;
            boolean cleaned = Boolean.parseBoolean(asString(m.get("cleaned")));
            String value = asString(m.get("value"));
            if (value != null && value.isBlank()) value = null;
            return new RoleEntry(role, name, tag, text, "role", null, resolvedKey, cleaned, value, popup, index, download, asString(m.get("_pageClass")), hover, closeOp, level, dblClick);
        }
        String selector = buildSelector(strategy, m);
        if (selector == null || selector.isBlank()) return null;
        String resolvedKey = asString(m.get("key"));
        if (resolvedKey != null && resolvedKey.isBlank()) resolvedKey = null;
        boolean cleaned = Boolean.parseBoolean(asString(m.get("cleaned")));
        String value = asString(m.get("value"));
        if (value != null && value.isBlank()) value = null;
        return new RoleEntry(role, name, tag, text, strategy, selector, resolvedKey, cleaned, value, popup, index, download, asString(m.get("_pageClass")), hover, closeOp, level, dblClick);
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
     *   <li>▶/⏹ 切换控件：空闲时显示“开始拾取”（▶，绿），点后进入点选模式并在页面点击目标元素；
     *       拾取中自动变为“停止拾取”（⏹，橙），再点即退出点选并按已点元素生成 {@code @RoleElement} 代码填入面板</li>
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
        // 开启面板开关：刷新/导航后 context 级 addInitScript 会自动重建面板，避免“刷新后面板消失”。
        page.evaluate("try{localStorage.setItem('__rolePanelEnabled','1')}catch(e){}");
        // 清掉上一次会话可能残留的拾取落盘态与拾取开关（浏览器上下文虽每次重建，仍防御性清理），
        // 避免 onFrameNavigated 合并时把旧数据误并入本次会话、或门控注入脚本因残留开关误自启拾取。
        page.evaluate("try{localStorage.removeItem('__rolePickState')}catch(e){} try{localStorage.removeItem('__rolePickerCode')}catch(e){}"
                + " try{localStorage.removeItem('__rolePickSessionOn')}catch(e){} try{window.__rolePickSessionOn=false;}catch(e){}");
        // 把关联的 nls 文件路径暴露给面板（标题展示 files=...），并在导航重建后依然可用。
        page.evaluate("window.__nlsFiles = " + GSON.toJson(nlsFiles) + ";");
        // context 级初始化脚本：①面板引导 + 面板重建（任意页面/导航自动执行）；
        // ②门控拾取脚本（会话开关打开时每个新文档自动注入 nls + 重挂拾取监听——
        //   “页面怎么变都能拾取”从此由浏览器原生保证，替代手动重挂/自愈兜底的主路径）。
        registerContextInitScripts(ctx, nlsReverseJson);
        // addInitScript 只对注册后的【新文档】生效：当前已加载文档立即补执行一次面板脚本。
        page.evaluate(PANEL_SCRIPT);
        final Page[] current = { page };
        // 会话级“是否处于拾取中”状态：跨页面跟随 / 导航 / 关闭回退都以此为权威依据，
        // 驱动面板切换控件显示 ⏹ 停止（而不是每次都重置成 ▶ 开始）。
        final boolean[] active = { false };
        // rootClosed：连“根页面（最初打开面板的页面）”都关闭时置位，循环据此结束会话。
        final boolean[] rootClosed = { false };
        // pageNames：每个被跟踪页面 → 其 Page 类名。根页用传入的 pageClassName；
        // 新页面（弹窗/新标签页）由 URL 派生（见 pageClassNameFromUrl），保证“元素落到对应页代码”。
        final LinkedHashMap<Page, String> pageNames = new LinkedHashMap<>();
        pageNames.put(page, pageClassName);
        // snapshots：每个被跟踪页面 → 其拾取状态 JSON 快照（readPickStateJson 格式）。
        // 用于导航重建恢复，以及页面关闭后仍能把它拾取的元素生成到对应 Page 类。
        final LinkedHashMap<Page, String> snapshots = new LinkedHashMap<>();
        // urlToClass：会话级“URL → Page 类名”稳定映射。同一 URL 在会话内首次访问时派生类名并记住，
        // 之后再回到该 URL（即使离开又返回）直接复用原类名，而不是重新派生一个重复类
        // （例如默认页 LoginPage → 跳到第二密页 → 回到登录 URL，若每次都重派生会多出 LogonPage，
        // 这正是“回到默认页面，根据 url 又创建了新的 page”的根因）。预置根页 URL → 传入的 pageClassName，
        // 保证回到默认页 URL 时复用原类名。键为“去 query/hash”的归一化 URL。
        // urlToClass 初始化为全局持久映射的当前内容（跨会话复用），再补登记根页 URL→传入类名。
        // 关键修复（修复“同一 URL 来回跳转却生成 XxxPage / XxxPage2 两个类”）：旧实现 urlToClass 是每次
        // pick 会话的局部变量，跨“停止→再开始”或多次运行会被重建，导致同 URL 在新会话重新派生类名；
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
        // 每次点击经 exposeFunction(__roleOnPick) 把“单个”元素零往返、O(1) 回传进此 Map（key=拾取签名⊕pageClass，
        // value=RoleEntry），重复点击以最近一次交互为准整条替换、首次插入保序。stop 时优先用此内存态生成代码，
        // 不依赖浏览器全量读取、且对导航/关闭导致的浏览器端状态清空免疫（比 localStorage 更可靠）。
        final LinkedHashMap<String, RoleEntry> javaPickBySig = new LinkedHashMap<>();
        // closeSignal：关闭事件协调锁。页面关闭（onClose）回调异步执行会把 current[0] 回退到父页，
        // 主循环检测到 current[0] 已关闭后需等待该回退完成；用 wait/notify 精确等待（而非 Thread.sleep），
        // onClose 完成后立即 notify，主循环即时唤醒，不再空等固定时长，也避免忙睡引入的时序抖动。
        final Object closeSignal = new Object();
        // 记录“发生过整页跳转（URL 变化使 pageClass 改变）”的页面（Identity 比较），用于：
        // 同标签跳转到新页面后直接关闭时，补登记 closeCurrentPage 步骤（普通单页录制末尾不追加）。
        final java.util.Set<Page> navigatedPages =
                java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<Page, Boolean>());
        // 把根页类名暴露给面板标题展示（新页面在 followPage 里设置），并持久化以便整页重建后恢复。
        page.evaluate("window.__rolePageName = " + GSON.toJson(pageClassName) + ";"
                + " try{localStorage.setItem('__rolePageName', " + GSON.toJson(pageClassName) + ");}catch(e){}");
        // 命令桥+拾取桥+控制台桥：context 一次注册，所有当前与未来页面共享（替代逐页 exposeFunction）。
        registerContextBridges(ctx, cmdQueue, javaPickBySig);
        registerPopupFollow(page, null, current, rootClosed, nlsReverseJson, nlsFiles, packageName, pageClassName, stepClassName, active, pageNames, snapshots, urlToClass, openedPages, cmdQueue, navigatedPages, closeSignal, javaPickBySig);
        // 上下文级“任意新页面”监听：覆盖非 window.open 打开的新标签页（onPopup 仅捕获弹窗）。
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
                // 关键修复：绝不可因“current[0] 指向的弹窗关闭”就直接结束会话——
                // 否则会进入 finally 移除所有面板、却残留点击捕获监听，表现为
                // “面板消失却仍可静默拾取、不阻挡程序”（用户不期望的行为）。
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
                        // 仅当“确实没有任何存活页面”时才结束会话。
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
                // 故点击“开始/停止”等按钮近乎零延迟，无需为命令轮询额外消耗 Java↔浏览器 evaluate。
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
                    // 空闲（1s 内无命令）：周期性缓存“所有被跟踪页面”的拾取快照，供导航重建/关闭后恢复。
                    // 关键优化：快照刷新不再放在【每个命令迭代】里——否则每次点按钮都要先对“每个被跟踪页面”
                    // 各做一次 page.evaluate 读快照（N 页 = N 次往返），造成“点按钮要好久才有反应”。
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
                    // 可能未可靠填充，导致“内存态增长、面板空白”。每轮空闲用 javaPickBySig 同步【所有】被跟踪页面
                    // 的面板并渲染（按各页 pageClass 过滤只显示该页拾取），保证用户在任一页面点击时面板都实时反映
                    // 已拾元素（生成链路仍走 javaPickBySig，不受影响）。
                    try {
                        for (Page pg : pageNames.keySet()) {
                            if (!pg.isClosed()) syncPanelToBrowser(pg, pageNames.get(pg), javaPickBySig);
                        }
                    } catch (Exception ignore) {}
                    // 自愈式保活：会话处于拾取中时，校验每个被跟踪页的点击捕获监听是否仍存活，
                    // 丢失则立即重挂 START_SCRIPT（含 nls）——覆盖“页面变化（跳转/URL change/SPA 整文档替换/
                    // frame 内部跳转）后监听被静默丢弃”的所有边界，保证任何时刻都能继续拾取。
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
                // 确保“停止后再点开始”不会遗漏某页（表现为点了开始却拾取不了）。
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
                    // 多实例：把“合并后的全部页面代码”填充到每一个被跟踪页面的面板，
                    // 避免只在当前页（如新页）显示、而默认页面板留空（之前“默认页没生成代码”的根因）。
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
     * 注册“弹窗跟随”：当页面弹出新标签页（target=_blank）时，把原页面的拾取状态
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
        page.onPopup(popup -> followPage(page, popup, current, rootClosed, active, nlsReverseJson, nlsFiles, packageName, pageClassName, stepClassName, pageNames, snapshots, urlToClass, openedPages, cmdQueue, navigatedPages, closeSignal, javaPickBySig));
        // 当前页面关闭监听：若关闭的是弹窗，把 inspector 回退到父页（默认页面）并重建面板，
        // 使单一 inspector 回到原页面继续拾取（对齐 page.pause()：关掉弹出的标签页后 inspector 不消失）。
        // 多页面模型下每个页面保留各自独立的拾取（不合并），故此处仅重建父页面板、不回写子页数据。
        page.onClose(closed -> {
            try {
                // 关闭瞬间尝试抓一份最终快照：页面可能已不可 evaluate，此时 readPickStateJson 返回空集
                // （{picks:[],...}，见 2491-2496，不抛异常）。关键修复：绝不能拿空集覆盖主循环此前缓存的快照
                // （那才含新页已拾取的元素）——否则合并时会用空 picks 把新页元素“合并没了”。
                // 仅当活读成功且确实含拾取时才覆盖缓存；活读为空则保留主循环缓存（更可靠）。
                try {
                    String live = readPickStateJson(closed);
                    if (live != null && hasPicks(live)) snapshots.put(closed, live);
                } catch (Exception ignore) {}
                String closedCls = pageNames.get(closed);   // 提升至 if/else 之前，使根页（else）分支也能引用
                if (parent != null && !parent.isClosed()) {
                    // 先切回父页并保留其面板：即使后续合并/渲染操作抛异常，也不影响“回退父页 + 面板存活”，
                    // 否则 onClose 中途异常会跳过 current[0]=parent，主循环将把“弹窗关闭”误判为会话结束，
                    // 进入 finally 删除所有面板却残留点击监听，表现为“面板消失却仍可静默拾取”（用户不期望）。
                    current[0] = parent;
                    // 父页此前一直处于拾取态（active 未被触碰），监听器仍存活；幂等重启以兜底，
                    // 不会因重复 START 而重复挂监听（START_SCRIPT 内已做 __rolePickActive 早退）。
                    try { if (active[0]) parent.evaluate(START_SCRIPT); } catch (Exception ignore) {}
                    // 合并关闭页已抓元素到父页 + 把“关闭该页”内联为当前 step 的一个操作标记：
                    // 与“回退父页/面板存活”解耦，单独容错，避免一处瞬态异常中断整段。
                    try {
                        // 多页面：关闭弹窗时把该页已抓元素“按签名合并”回父页（而非整盘覆盖）。
                        // 只把关闭页中“父页还没有”的 pick 并入父页，父页自身数据永不被抹掉；
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
                                + " var s = " + closedState + ";"
                                // 定位器唯一型策略（id/css/i18n/text/...）按 locator 签名（_sig）全局去重：
                                // 弹窗打开时被 followPage 复制进来的“主页元素”与主页已有元素 locator 相同，
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
                                + "   var k = JSON.stringify([sig, (p&&p._pageClass)||'']);"
                                + "   if (k && window.__rolePickSigs[k]) return;"
                                + "   if (k) window.__rolePickSigs[k] = true;"
                                + "   window.__rolePicks.push(p); });"
                                + "})()");
                        }
                        // 关键修复：把关闭页“进行中 step”（__currentStep）合并回父页当前 step，
                        // 使弹窗内拾取的元素随同一 step 继续累积——step 的唯一边界是“开始→停止”，
                        // 弹窗打开/关闭都只是同一 step 内的交互，绝不该拆出额外 step。
                        // 同时把“关闭该页”登记为当前 step 内的一个操作标记（_closeOp），而非独立 step，
                        // 使代码生成器产出 closeCurrentPage() 内联在主流程中（用户明确要求“只有一个条件：开始-停止”）。
                        // 关闭标记按 _sig 去重（支持多次关闭同类弹窗），并随 __currentStep 在停止时被收尾进唯一一个 step。
                        if (closedCls != null && !closedCls.isEmpty()) {
                            parent.evaluate(
                                "(function(){"
                                // 关键修复：合并弹窗 currentStep 时【绝不可】用父页全局 __rolePickSigs 去重——
                                // 否则弹窗打开时被 followPage 搬运进弹窗 currentStep 的“默认页元素”（如 A）会因其 sig
                                // 已存在于默认页 __rolePickSigs 而被误删，导致该元素从当前 step 消失
                                //（表现为“关弹窗 / url 变化后元素找不到”）。此处仅对“弹窗 currentStep 自身”
                                // 去重（避免弹窗内重复拾取同一元素），被搬运来的默认页元素必须原样保留。
                                + " var s = " + (closedState == null ? "{}" : closedState) + ";"
                                + " var closeMarker = {_closeOp:true, _pageClass:" + GSON.toJson(closedCls)
                                + "   , _sig:'__close_' + ((window.__roleCloseSeq=(window.__roleCloseSeq||0)+1)), tag:'close'};"
                                + " function mergeInto(arr){ if(!arr) return;"
                                + "   var seen = {};"
                                + "   (s.currentStep||[]).forEach(function(p){"
                                + "     var k = JSON.stringify([(p&&p._sig)||'', (p&&p._pageClass)||'']);"
                                + "     if (k && seen[k]) return;"
                                + "     if (k) seen[k] = true;"
                                + "     arr.push(p); });"
                                + "   arr.push(closeMarker); }"
                                // 仍在进行中（未停止）：并入当前 step（__currentStep 是数组）。
                                + " if (Array.isArray(window.__currentStep)) { mergeInto(window.__currentStep); }"
                                // 已停止：把弹窗的 currentStep 与关闭标记并入“最后一个已生成 step”的 picks，
                                // 不新建 step（保持“开始-停止”才是唯一 step 边界）。
                                + " else { window.__steps = window.__steps || [];"
                                + "   var last = window.__steps[window.__steps.length-1];"
                                + "   if (!last) { last = {pageClass:" + GSON.toJson(closedCls) + ", picks:[]}; window.__steps.push(last); }"
                                + "   if (!last.picks) last.picks = [];"
                                + "   mergeInto(last.picks); }"
                                // 同步登记一条“页面级关闭操作”（op='close'，pageClass=被关弹窗页），
                                // 使 window.__steps 中除 _closeOp 标记外还保有可被解析为 PageOp 的条目。
                                // 这一步关键：代码生成器的 inferPopupTargetVar 据此推断“弹窗目标页对象”
                                // （如 privacyAndSecurityPage），从而把新页面绑到目标页对象而非打开页（修复
                                // “弹窗关闭落在 loginPage 而非 privacyAndSecurityPage”）。_closeOp 仅用于 step 内联渲染，
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
                            // 确保面板“挂载即可见、可继续拾取”，杜绝“面板不见却后台静默拾取”的半吊子状态。
                            parent.evaluate(PANEL_SCRIPT);
                            parent.evaluate("if(window.__renderPicks) window.__renderPicks();");
                        }
                        setStatus(parent, "[picker] 已返回默认页面，可继续点选或停止生成代码。");
                    } catch (Exception ignore) { /* 面板渲染失败：忽略，主循环会继续在存活页面试图恢复 */ }
                    log.info("[picker] 页面已关闭（onClose）：{} （页面类：{}），默认页面板保留并合并各页拾取。",
                            closed.url(), pageNames.get(closed));
                } else {
                    // 根页面（默认页）被关闭：若仍有其它被跟踪页面（弹窗）存活，则不结束会话，
                    // 切换到最后一个存活页面并保留其面板——避免“关掉默认页后面板全消失、程序却静默继续”的半吊子状态。
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
                    // 同标签整页跳转到新页面后“直接关闭”该根页：原逻辑只在“有存活父页”的弹窗分支
                    // 登记 _closeOp（→ closeCurrentPage），根页关闭走本 else 分支从不登记；且停止生成时
                    // 已关闭页被跳过，导致关闭步骤丢失。故在此把“关闭当前页”补登记为该页缓存快照里的一条
                    // step（含 _closeOp 标记），停止时由 runPickerCommand 的 stop 分支折叠回最终快照 → 生成
                    // closeCurrentPage()。仅对“发生过整页跳转”的页生效，普通单页录制末尾不会无谓追加。
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
        // 关键点（修复“同页 URL 变化后元素仍归到第一个 URL 页类 / 重建清空已有元素”）：
        //   1) SPA/框架内路由：window 未销毁，已抓元素仍驻留内存 → 绝不用快照整体覆盖，否则会清掉
        //      快照之后新拾的元素（“重建清空已有元素”的根因）；仅当整页重建（window.__rolePicks 随文档
        //      销毁变空）时，才从快照恢复并刷新面板列表。
        //   2) URL 变化即视为“新页面边界”：依据新 URL 重派生本页类名（window.__rolePageName），
        //      使导航后新拾取的元素落到新页类；导航前已拾元素自带 _pageClass（旧页类）保持不变。
        //      派生时排除本页当前类名自身，避免 pageClassNameFromUrl 去重把同名类误加成 “Xxx2”。
        page.onFrameNavigated(frame -> {
            try {
                if (frame != page.mainFrame()) return;   // 仅关注主框架，忽略 iframe
                log.info("[picker][nav] 触发 onFrameNavigated：page={} active[0]={}", page.url(), active[0]);
                // 仅当该页属于本次拾取会话（有快照）才处理；其它无关页面 snapshots 为 null 自然跳过。
                // current[0] 只决定“正在操作的页面”，不影响各页自身面板状态的恢复。
                String st = snapshots.get(page);
                String prevCls = pageNames.get(page);
                // URL 变化即视为“页面边界”：打印日志，便于排查录制定位与元素丢失。
                log.info("[picker] 页面 URL 变化（onFrameNavigated）：{} （页面类：{}）", page.url(), prevCls);
                // 无论 window 是否随导航销毁，都确保“之前拾取的元素”不丢失：
                //  - 整页重建（livePicks=false）：用 applyPickState 从快照整体恢复（含 nls 反查表）；
                //  - window 仍在（livePicks=true）：把快照中“当前窗口缺少”的 pick/step 合并回来，
                //    避免某些导航把 window.__rolePicks 连带清空，导致“元素不见了”。
                try {
                boolean livePicks = Boolean.TRUE.equals(page.evaluate(
                        "!!(window.__rolePicks && window.__rolePicks.length)"));
                if (!livePicks) {
                    // 仅当 Java 快照非空才整体恢复（含 nls 反查表）；为空不再提前 return，
                    // 改由下方 localStorage 兜底——修复“刷新前未来得及空闲刷新 / 首屏”导致 st 为空、
                    // 早期 return 把 localStorage 兜底也跳过、整页刷新后元素与步骤全丢的问题。
                    if (st != null && !st.isEmpty()) {
                        applyPickState(page, st, nlsReverseJson, nlsFiles);
                    }
                    // 关键修复：整页跳转（window 重建）后，上面 applyPickState 用的是主循环每 ~1s 刷新的
                    // Java 快照 st，可能【来不及包含跳转前最后点击的元素】（例如刚点的“返回登录”按钮），
                    // 于是该元素被旧快照整体覆盖而丢失。此处把浏览器在 pagehide 时落盘到 localStorage 的
                    // 最新拾取态（含跳转前那次点击）合并回来，以“页面级复合键”去重，补回 st 缺失的最新点击元素。
                    // 仅同域整页跳转 localStorage 才保留，跨域（如弹窗 PDF）自然为空、不影响。
                    page.evaluate("(function(){"
                            + " try {"
                            + "   var raw = localStorage.getItem('__rolePickState'); if(!raw) return;"
                            + "   var s = JSON.parse(raw);"
                            + "   window.__rolePicks = window.__rolePicks || [];"
                            + "   window.__rolePickSigs = window.__rolePickSigs || {};"
                            // 定位器唯一型策略（id/css/i18n/text/...）按 locator 签名（_sig）全局去重，
                            // 避免“跳转再返回”合并时同一元素（如 id=logoHeader）被追加副本；
                            // role/closeOp 仍按 [sig, pageClass|URL] 区分（与 close-merge、Java 权威态一致）。
                            + "   var __LOCID={id:1,css:1,i18n:1,text:1,title:1,placeholder:1,label:1,testid:1,altText:1};"
                            + "   var __loc = {};"
                            + "   window.__rolePicks.forEach(function(p){ if(p&&__LOCID[p.strategy]){ var ls=p._sig||''; if(ls) __loc[ls]=true; } });"
                            + "   (s.picks||[]).forEach(function(p){"
                            + "     var sig=(p&&p._sig)||'';"
                            + "     var li=(p&&__LOCID[p.strategy]);"
                            + "     if (li && sig && __loc[sig]) return;"
                            + "     if (li && sig) __loc[sig]=true;"
                            + "     var k = JSON.stringify([sig, (p&&p._pageClass)||'']);"
                            + "     if (k && window.__rolePickSigs[k]) return;"
                            + "     if (k) window.__rolePickSigs[k] = true;"
                            + "     window.__rolePicks.push(p); });"
                            + "   // 关键修复（修复“URL 变化后元素定位到了、但 step 没记录”）：当前进行中 step（__currentStep）\n"
                            + "   // 必须按【自身】去重合并 localStorage 中最新 currentStep，而非与 __rolePicks 的签名表去重——\n"
                            + "   // 否则因每个 currentStep 元素同时也是 pick，会被上面 picks 循环写入的签名表整批去重掉，\n"
                            + "   // 导致 currentStep 恢复完全失效、只能依赖可能已过期的 Java 快照（st.currentStep），\n"
                            + "   // 从而丢失跳转前最后点击（如“返回登录”）所属的那一步。改为仅与【当前 __currentStep 已有项】去重，\n"
                            + "   // 把快照遗漏的最新 currentStep 元素补回（与 picks 去重互不干扰，避免后续重复 finalize）。\n"
                            + "   window.__currentStep = window.__currentStep || [];"
                            + "   var __cs = {};"
                            + "   window.__currentStep.forEach(function(p){ var k=JSON.stringify([(p&&p._sig)||'',(p&&p._pageClass)||'']); if(k) __cs[k]=true; });"
                            + "   (s.currentStep||[]).forEach(function(p){"
                            + "     var k = JSON.stringify([(p&&p._sig)||'', (p&&p._pageClass)||'']);"
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
                            + "   var k = JSON.stringify([sig, (p&&p._pageClass)||'']);"
                            + "   if (k && window.__rolePickSigs[k]) return;"
                            + "   if (k) window.__rolePickSigs[k] = true;"
                            + "   window.__rolePicks.push(p); });"
                            + " window.__steps = window.__steps || [];"
                            + " (s.steps||[]).forEach(function(st2){"
                            + "   var dup = window.__steps.some(function(ex){ return JSON.stringify(ex)===JSON.stringify(st2); });"
                            + "   if(!dup) window.__steps.push(st2); });"
                            // 关键修复：URL 变化若把“进行中 step”（__currentStep）一并清空、但 window 未销毁
                            // （livePicks 为真），从快照补回，避免当前 step 元素在导航后“凭空消失”。
                            // 仅当仍处于拾取中、当前 __currentStep 已丢失、且快照确有内容时才补，
                            // 防止 stop 后再导航被误恢复出游离 step。
                            + " if (window.__rolePickActive && !Array.isArray(window.__currentStep)"
                            + "     && (s.currentStep||[]).length) {"
                            + "   window.__currentStep = s.currentStep; }"
                            + "})()");
                }
                // 导航后始终重渲染面板列表并滚动到底部，确保已恢复/合并的元素可见（修复“URL 变化后元素看不见”）；
                // 用 setTimeout 兜底等待 PANEL_SCRIPT 的 build() 完成（body 就绪才挂载面板），避免提前渲染找不到节点，
                // 同时恢复上次生成的代码（页面元素 / 步骤代码两个 Tab），刷新后不丢。
                page.evaluate("(function(){ setTimeout(function(){"
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
                // 避免“回到默认页 URL 又派生出 LogonPage 之类重复页类”）——仅当该 URL 从未见过时才派生新类名。
                String curCls = pageNames.get(page);
                String newCls = resolvePageClassForUrl(page.url(), pageNames.values(), urlToClass);
                page.evaluate("window.__rolePageName = " + GSON.toJson(newCls) + ";"
                        + " try{localStorage.setItem('__rolePageName', " + GSON.toJson(newCls) + ");}catch(e){}");
                if (!newCls.equals(curCls)) { pageNames.put(page, newCls); navigatedPages.add(page); }
                } catch (Exception restoreEx) {
                    // 数据恢复（applyPickState / 合并 / 渲染 / 类名解析）任一 evaluate 因导航瞬间页面不稳抛异常，
                    // 必须吞掉且【不能影响下方 start() 重激活】——否则会出现“刷新/导航后点了没反应、拾取不了”
                    // （applyPickState 把 __rolePickActive 置 false 后激活被跳过，监听永久失效）。
                    log.warn("[picker][nav] 数据恢复异常（不阻断拾取激活）：{}", restoreEx.getMessage());
                }
                // 整页导航的监听重挂已由 context 门控注入脚本在新文档早期原生完成（gatedPickerInitScript）；
                // 但 applyPickState 恢复数据时会把 __rolePickActive 置 false。若会话仍处于拾取中，
                // 经 start() 幂等恢复激活位（监听已在则早退仅保活），并置位会话开关——
                // 覆盖跨源导航后 localStorage 开关丢失的边界，使该页后续导航恢复浏览器原生保活。
                // 触发条件不再单纯依赖 Java 侧 active[0]（可能与浏览器态不同步），而以浏览器侧会话开关为准，
                // 只要门控脚本此前读到过开关（localStorage/__rolePickSessionOn）就重激活，保证“刷新/跳转后必能拾取”。
                boolean sessionOn = active[0];
                if (!sessionOn) {
                    try {
                        sessionOn = Boolean.TRUE.equals(page.evaluate(
                                "try { return localStorage.getItem('__rolePickSessionOn')==='1' || !!window.__rolePickSessionOn; } catch(e){ return !!window.__rolePickSessionOn; }"));
                    } catch (Exception ignore) {}
                }
                if (sessionOn) {
                    log.info("[picker][nav] 会话拾取中：同步激活状态 @ {}", page.url());
                    try {
                        start(page, nlsReverseJson);
                    } catch (Exception ex) {
                        // 导航瞬间新文档执行上下文可能尚未就绪，page.evaluate 会抛“上下文已销毁”类异常；
                        // 原实现未捕获会被外层 catch(Exception ignore) 静默吞掉 → 监听永久失效、刷新/跳转后点了没反应。
                        // 此处等待 DOM 就绪后重试一次，作为保底。
                        log.warn("[picker][nav] 激活注入首轮失败，等待页面就绪后重试 @ {} : {}", page.url(), ex.getMessage());
                        try { page.waitForLoadState(); } catch (Exception ignore2) {}
                        try { start(page, nlsReverseJson); }
                        catch (Exception ex2) { log.warn("[picker][nav] 激活注入重试仍失败 @ {} : {}", page.url(), ex2.getMessage()); }
                    }
                } else {
                    log.info("[picker][nav] 未处于拾取会话（active=false 且浏览器侧未开启），跳过激活 @ {}", page.url());
                }
                // ===== 诊断：刷新/导航后真实运行时状态（定位“拾取不了”根因）=====
                // 浏览器 console 已被吞，所有关键信息只能经 page.evaluate 回读。一次性汇总：
                //   lsSwitch   —— 新文档 localStorage 里的会话开关（跨源导航会读不到，暴露 origin 隔离问题）
                //   winSwitch  —— window.__rolePickSessionOn 是否被置位
                //   active     —— __rolePickActive（最终是否处于拾取态）
                //   hasClick/hasMove/hasRecord —— 三大监听/入口函数是否真的被定义（判断 START_SCRIPT 是否注入成功）
                //   gateInit   —— 门控注入脚本本次执行结果（是否读到开关、是否注入），直接显示是“门控没生效”还是“激活被覆盖”
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
     * 元素按各页 window.__rolePageName 打 _pageClass 标签，生成时据此分组，实现“打开新页显示之前抓的元素”。
     */
    /**
     * 开始拾取前，将浏览器当前真实打开的所有页面（context.pages()）与内存跟踪表 pageNames 对齐。
     * 兜底：任何在 stop→再 start 之间、或 onPage/onPopup/followPage 因异常而未登记进 pageNames 的打开页面，
     * 都会被补做最小初始化并纳入跟踪，使该页面在随后的 start 遍历中可被激活拾取，
     * 彻底消除“停止后再点开始，某个已打开页面点了开始却拾取不了”的问题。
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
            }
        }
    }

    /**
     * 对漏登记页面补做“可被拾取”的最小初始化（不搬运 opener 的当前 step，start 自身会续接/重置）。
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
            // 关键修复：打开新页面【不再】把默认页当前步收尾成一个 step。step 的唯一边界是“开始→停止”，
            // 弹窗打开/关闭都只是同一 step 内的交互，绝不该切分出额外 step（用户明确要求“只有一个条件：开始-停止”）。
            // 因此此处只把 opener 的“进行中 step”（__currentStep，已带各元素原 _pageClass）整体搬运到新页继续累积，
            // 并把 opener 的 __currentStep 清空（转移而非复制），避免关闭弹窗合并回来时出现重复元素。
            // 旧页 pick 已带原 _pageClass，新页面板会显示之前抓的元素；新页拾取的元素再打上 cls。
            // 当前页始终持有全部页面的 pick 并集，故代码生成可在单一窗口按各元素自身 _pageClass 归类。
            applyPickState(newPage, readPickStateJson(opener), nlsReverseJson, nlsFiles);
            if (opener != null && !opener.isClosed()) {
                opener.evaluate("try{ window.__currentStep = []; }catch(e){}");
            }
            newPage.evaluate("window.__rolePageName = " + GSON.toJson(cls) + ";"
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
            // 新页面若再弹窗/再开页，继续跟随；把“是否处于拾取态”传下去，供其 onClose 回退父页时恢复。
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
     * 跨多次 pick 运行持久化的“URL → Page 类名”稳定映射。
     * 关键修复（修复“同一 URL 来回跳转却生成 XxxPage / XxxPage2 两个类”）：
     * 旧实现 urlToClass 是每次 pick 会话的局部变量，跨“停止→再开始”或多次运行会被重建，导致同 URL
     * 在新会话重新派生类名；若既有类名因 pageNames 残留被计入去重，就派生出 XxxPage2。提升为全局持久
     * 映射后，同一 URL 首次派生即记住，之后任何会话/导航都复用，永不再派生重复类。
     */
    private static final java.util.Map<String, String> GLOBAL_URL_TO_CLASS = new java.util.concurrent.ConcurrentHashMap<>();

    /** 归一化 URL：去 query/hash，并去除末尾斜杠，作为 urlToClass 的稳定键。
     *  去除末尾斜杠可让肉眼“相同”但末尾斜杠有差异的 URL（如 /help 与 /help/）映射到同一页类，
     *  避免它们被误判为两个页面而派生出 XxxPage2。 */
    private static String normalizeUrl(String url) {
        String raw = url == null ? "" : url.trim();
        int q = raw.indexOf('?'); if (q >= 0) raw = raw.substring(0, q);
        int h = raw.indexOf('#'); if (h >= 0) raw = raw.substring(0, h);
        while (raw.length() > 1 && raw.endsWith("/")) raw = raw.substring(0, raw.length() - 1);
        return raw;
    }

    /**
     * 解析某 URL 对应的 Page 类名：优先复用会话级 urlToClass 稳定映射（同一 URL 全程复用同一类名，
     * 避免“离开默认页又回到默认页 URL 时被派生成 LogonPage 等重复类”）。
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

    /** 把任意片段清洗为合法 Java 类名的“主体”（首字母大写；- _ . 空格 / 作单词边界；其余字符丢弃）。 */
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
     *  故对 evaluate 整体容错，避免 TargetClosedError 冒泡污染测试 step（之前“关闭新页面后 STEP ERROR”的根因）。 */
    private static void closePanel(Page page) {
        try {
            // 移除常驻面板的同时，移除点击/悬停/按键捕获监听并复位 active 标记，
            // 否则面板删了、监听器残留，会出现“面板消失却仍可静默拾取、不阻挡程序”（用户不期望）的半吊子状态。
            page.evaluate("(function(){"
                    + " try{ if(window.__rolePickClick) document.removeEventListener('click', window.__rolePickClick, true); }catch(e){}"
                    + " try{ if(window.__rolePickMove) document.removeEventListener('mousemove', window.__rolePickMove, true); }catch(e){}"
                    + " try{ if(window.__rolePickKey) document.removeEventListener('keydown', window.__rolePickKey, true); }catch(e){}"
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
     * 反序列化成 Java 对象再 GSON 重序列化——后者在遇到某些返回形态时会抛“无法序列化”异常，
     * 被 catch 成空串，进而把弹窗/父页的既有拾取整体清空（这是之前“回到原页全没了”的根因）。
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

    /** 状态 JSON 是否为“全空”（picks / steps / currentStep 均为空），用于快照更新时识别导航空窗期。 */
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
                + " var s = " + stateJson + ";"
                + " window.__rolePicks = s.picks || [];"
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
        // 企业级优化：把“写入消息对象”与“更新 DOM”合并进同一次 page.evaluate，
        // 点击“停止”后只需 1 次往返即可把分页代码渲染进面板对应 Tab（原来 2 次串行往返）。
        page.evaluate("(function(){"
                + " window.__fillCodeTabs({"
                + " pageByPage:" + GSON.toJson(pageClassByPage == null ? new LinkedHashMap<String, String>() : pageClassByPage)
                + ", stepByPage:" + GSON.toJson(stepByPage == null ? new LinkedHashMap<String, String>() : stepByPage)
                + ", msg:" + GSON.toJson(msg == null ? "" : msg) + "});"
                + "})()");
    }

    /**
     * 由一组已拾取元素按所属页面类分组生成页面类源码（与 stop 命令的生成逻辑一致）。
     * 返回 pageClass → 该页完整页面类源码 的 map，供面板“页面类”Tab 按页分栏展示（对齐“页面元素”Tab）。
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
     * 返回 pageClass → 该页“完整可编译的 Step 类视图”源码 的 map，供面板“步骤代码”Tab 按页分栏展示
     * （对齐“页面元素”Tab）。多页时主页视图含跨页 step、弹窗页视图含其 close 操作。
     *
     * @return pageClass → 步骤类视图源码（LinkedHashMap 保序，无 step/操作时返回空 map）
     */
    private static LinkedHashMap<String, String> buildStepCode(PickSnapshot snap, String packageName, String stepClassName) {
        LinkedHashMap<String, String> out = new LinkedHashMap<>();
        if (snap == null) return out;
        String curClass = (snap.pageClass == null) ? "" : snap.pageClass;
        LinkedHashMap<String, List<RoleEntry>> entriesByPage = new LinkedHashMap<>();
        for (RoleEntry e : snap.entries) {
            String pc = (e.getPageClass() == null || e.getPageClass().isEmpty()) ? curClass : e.getPageClass();
            entriesByPage.computeIfAbsent(pc, k -> new ArrayList<>()).add(e);
        }
        LinkedHashMap<String, List<List<RoleEntry>>> stepsByPage = new LinkedHashMap<>();
        if (snap.steps != null) for (StepRec st : snap.steps) {
            String pc = (st.pageClass == null || st.pageClass.isEmpty()) ? curClass : st.pageClass;
            stepsByPage.computeIfAbsent(pc, k -> new ArrayList<>()).add(st.picks);
            entriesByPage.computeIfAbsent(pc, k -> new ArrayList<>());
        }
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

    /** 关闭步骤序号器：保证每次“关闭当前页”标记签名唯一、可去重。 */
    private static final java.util.concurrent.atomic.AtomicInteger CLOSE_SEQ =
            new java.util.concurrent.atomic.AtomicInteger();

    /**
     * 把“关闭当前页”补登记为一条 step（含 _closeOp 标记的 pick），追加进该页缓存快照（snapshots），
     * 供停止生成时（已关闭页被跳过）折叠回最终快照，从而生成 closeCurrentPage() 步骤。
     * 仅用于“同标签整页跳转到新页面后直接关闭”的根页场景（普通弹窗关闭已由 onClose 的父页分支处理）。
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