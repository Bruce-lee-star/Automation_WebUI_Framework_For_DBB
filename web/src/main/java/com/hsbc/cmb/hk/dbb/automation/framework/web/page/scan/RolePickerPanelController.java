package com.hsbc.cmb.hk.dbb.automation.framework.web.page.scan;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import com.hsbc.cmb.hk.dbb.automation.framework.web.page.scan.RoleElementPicker.CmdEvent;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.scan.RoleElementPicker.PickerResult;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.scan.RoleElementPicker.PickerAction;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.scan.RoleElementPicker.PickMode;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.scan.RoleElementPicker.PickerAbortedException;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.scan.RoleElementPicker.PickSnapshot;

import com.hsbc.cmb.hk.dbb.automation.framework.web.page.scan.RolePickerScripts;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.scan.RolePickerSessionState;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.scan.RolePickerClassNameResolver;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.scan.RolePickerNlsCache;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.scan.RolePickerCodeAssembler;

// 行为保持零回归的桥接：openPanel / registerPopupFollow 原样搬自 RoleElementPicker，
// 其依赖的私有静态助手已放宽到包内可见，此处显式静态导入以保留原调用形态。
import static com.hsbc.cmb.hk.dbb.automation.framework.web.page.scan.RoleElementPicker.setPickMode;
import static com.hsbc.cmb.hk.dbb.automation.framework.web.page.scan.RoleElementPicker.registerContextBridges;
import static com.hsbc.cmb.hk.dbb.automation.framework.web.page.scan.RoleElementPicker.registerContextInitScripts;
import static com.hsbc.cmb.hk.dbb.automation.framework.web.page.scan.RoleElementPicker.drainPanelCmds;
import static com.hsbc.cmb.hk.dbb.automation.framework.web.page.scan.RoleElementPicker.isCiRun;
import static com.hsbc.cmb.hk.dbb.automation.framework.web.page.scan.RoleElementPicker.runPickerCommand;
import static com.hsbc.cmb.hk.dbb.automation.framework.web.page.scan.RoleElementPicker.mergeFramePicksToMain;
import static com.hsbc.cmb.hk.dbb.automation.framework.web.page.scan.RoleElementPicker.syncPanelToBrowser;
import static com.hsbc.cmb.hk.dbb.automation.framework.web.page.scan.RoleElementPicker.ensurePickingActive;
import static com.hsbc.cmb.hk.dbb.automation.framework.web.page.scan.RolePickerPageTracker.reconcileTrackedPages;
import static com.hsbc.cmb.hk.dbb.automation.framework.web.page.scan.RolePickerPageTracker.followPage;
import static com.hsbc.cmb.hk.dbb.automation.framework.web.page.scan.RoleElementPicker.closePanel;
import static com.hsbc.cmb.hk.dbb.automation.framework.web.page.scan.RoleElementPicker.hasPicks;
import static com.hsbc.cmb.hk.dbb.automation.framework.web.page.scan.RoleElementPicker.isEmptyState;
import static com.hsbc.cmb.hk.dbb.automation.framework.web.page.scan.RoleElementPicker.setStatus;
import static com.hsbc.cmb.hk.dbb.automation.framework.web.page.scan.RoleElementPicker.fillCode;
import static com.hsbc.cmb.hk.dbb.automation.framework.web.page.scan.RoleElementPicker.readPickStateJson;
import static com.hsbc.cmb.hk.dbb.automation.framework.web.page.scan.RoleElementPicker.applyPickState;
import static com.hsbc.cmb.hk.dbb.automation.framework.web.page.scan.RoleElementPicker.safeOrigin;
import static com.hsbc.cmb.hk.dbb.automation.framework.web.page.scan.RoleElementPicker.start;
import static com.hsbc.cmb.hk.dbb.automation.framework.web.page.scan.RoleElementPicker.pickerEval;

/**
 * 面板会话控制器：承载「常驻控制面板」的开辟与主循环（含弹窗/关闭/导航/下载/上传/崩溃监听）。
 * 该逻辑原属 {@link RoleElementPicker} 的最大单块（openPanel + registerPopupFollow，约 676 行），
 * 按 T5-1「按 扫描/定位/缓存 拆子模块」拆出为独立簇类，公开行为（包括 PickerAbortedException 终止语义）不变。
 */
public final class RolePickerPanelController {

    private static final Logger log = LoggerFactory.getLogger(RolePickerPanelController.class);

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
        final String nlsReverseJson = RolePickerNlsCache.buildNlsReverseJson(Arrays.asList(nlsFiles));
        // 开启面板开关：刷新/导航后 context 级 addInitScript 会自动重建面板，避免"刷新后面板消失"。
        pickerEval(page, RolePickerScripts.ENABLE_PANEL_JS);
        // 清掉上一次会话可能残留的拾取落盘态与拾取开关（浏览器上下文虽每次重建，仍防御性清理），
        // 避免 onFrameNavigated 合并时把旧数据误并入本次会话、或门控注入脚本因残留开关误自启拾取。
        pickerEval(page, RolePickerScripts.CLEAR_PICKER_STATE_JS + RolePickerScripts.STOP_SESSION_ON_JS);
        // 把关联的 nls 文件路径暴露给面板（标题展示 files=...），并在导航重建后依然可用。
        pickerEval(page, RolePickerScripts.SET_NLS_FILES_JS, RolePickerScripts.args("files", nlsFiles));
        // context 级初始化脚本：①面板引导 + 面板重建（任意页面/导航自动执行）；
        // ②门控拾取脚本（会话开关打开时每个新文档自动注入 nls + 重挂拾取监听——
        //   "页面怎么变都能拾取"从此由浏览器原生保证，替代手动重挂/自愈兜底的主路径）。
        registerContextInitScripts(ctx, nlsReverseJson);
        // addInitScript 只对注册后的【新文档】生效：当前已加载文档立即补执行一次面板脚本。
        pickerEval(page, RolePickerScripts.PANEL_SCRIPT);
        final AtomicReference<Page> current = new AtomicReference<>(page);
        // 会话级"是否处于拾取中"状态：跨页面跟随 / 导航 / 关闭回退都以此为权威依据，
        // 驱动面板切换控件显示 ⏹ 停止（而不是每次都重置成 ▶ 开始）。
        final AtomicBoolean active = new AtomicBoolean(false);
        // throttle signature for auto-generating step while picking: recompute only when memory state changes.
        final String[] lastAutoGenSig = { "" };
        // rootClosed：连"根页面（最初打开面板的页面）"都关闭时置位，循环据此结束会话。
        final AtomicBoolean rootClosed = new AtomicBoolean(false);
        // pageNames：每个被跟踪页面 → 其 Page 类名。根页用传入的 pageClassName；
        // 新页面（弹窗/新标签页）由 URL 派生（见 pageClassNameFromUrl），保证"元素落到对应页代码"。
        final ConcurrentHashMap<Page, String> pageNames = new ConcurrentHashMap<>();
        pageNames.put(page, pageClassName);
        // snapshots：每个被跟踪页面 → 其拾取状态 JSON 快照（readPickStateJson 格式）。
        // 用于导航重建恢复，以及页面关闭后仍能把它拾取的元素生成到对应 Page 类。
        final ConcurrentHashMap<Page, String> snapshots = new ConcurrentHashMap<>();
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
        final LinkedHashMap<String, String> urlToClass = RolePickerClassNameResolver.snapshot();
        String rootNorm = RolePickerClassNameResolver.normalizeUrl(page.url());
        urlToClass.put(rootNorm, pageClassName);
        RolePickerClassNameResolver.put(rootNorm, pageClassName);
        // openedPages：会话期间新开出的页面（弹窗/新标签页），关闭面板时一并关闭。
        final CopyOnWriteArrayList<Page> openedPages = new CopyOnWriteArrayList<>();
        // 命令事件队列：面板按钮点击经 exposeFunction 异步投递到这里，主循环阻塞消费（事件驱动，无需忙轮询）。
        final BlockingQueue<CmdEvent> cmdQueue = new LinkedBlockingQueue<>();
        // 拾取状态权威副本（对齐 page.pause：状态外置到 Java 侧，浏览器只做轻量 UI）。
        // 每次点击经 exposeFunction(__roleOnPick) 把"单个"元素零往返、O(1) 回传进此 Map（key=拾取签名⊕pageClass，
        // value=RoleEntry），重复点击以最近一次交互为准整条替换、首次插入保序。stop 时优先用此内存态生成代码，
        // 不依赖浏览器全量读取、且对导航/关闭导致的浏览器端状态清空免疫（比 localStorage 更可靠）。
        final LinkedHashMap<String, RoleEntry> javaPickBySig = new LinkedHashMap<>();
        // closeSignal：关闭事件协调锁。页面关闭（onClose）回调异步执行会把 current.get() 回退到父页，
        // 主循环检测到 current.get() 已关闭后需等待该回退完成；用 wait/notify 精确等待（而非 Thread.sleep），
        // onClose 完成后立即 notify，主循环即时唤醒，不再空等固定时长，也避免忙睡引入的时序抖动。
        final Object closeSignal = new Object();
        // 记录"发生过整页跳转（URL 变化使 pageClass 改变）"的页面（Identity 比较），用于：
        // 同标签跳转到新页面后直接关闭时，补登记 closeCurrentPage 步骤（普通单页录制末尾不追加）。
        final java.util.Set<Page> navigatedPages = java.util.concurrent.ConcurrentHashMap.newKeySet();
        // 收敛本次拾取会话的可变工作集到 RolePickerContext，消除下游方法的 16 参数长签名（行为零变更）。
        final RolePickerContext pc = new RolePickerContext(current, rootClosed, active, nlsReverseJson, nlsFiles, packageName, pageClassName, stepClassName, pageNames, snapshots, urlToClass, openedPages, cmdQueue, navigatedPages, closeSignal, javaPickBySig);
        // 把根页类名暴露给面板标题展示（新页面在 followPage 里设置），并持久化以便整页重建后恢复。
        pickerEval(page, RolePickerScripts.SET_PAGE_NAME_AND_RESET_INSTANCE_JS,
                RolePickerScripts.args("pageName", pageClassName));
        // 命令桥+拾取桥+控制台桥：context 一次注册，所有当前与未来页面共享（替代逐页 exposeFunction）。
        registerContextBridges(ctx, cmdQueue, javaPickBySig);
        registerPopupFollow(pc, page, null);
        // 上下文级"任意新页面"监听：覆盖非 window.open 打开的新标签页（onPopup 仅捕获弹窗）。
        // 用 opener()==null 过滤掉弹窗（弹窗已由上面的 onPopup 跟随），避免重复跟随。
        ctx.onPage(p -> {
            if (p.opener() != null) return;
            log.info("[picker] 新页面打开（onPage）：{}", p.url());
            followPage(pc, current.get(), p);
        });

        log.info("[picker] 面板已打开（同窗口 docked 右侧，不另开窗口）：▶ 开始拾取 → 点击元素 → ⏹ 停止生成代码 → 📋 复制；✕ 关闭结束。");
        try {
            while (true) {
                // 当前跟随的页面（可能是弹窗）已关闭：先让 onClose 回调有机会把 current.get()
                // 回退到父页（重建面板、继续拾取），再判定是否真的结束会话。
                // 关键修复：绝不可因"current.get() 指向的弹窗关闭"就直接结束会话——
                // 否则会进入 finally 移除所有面板、却残留点击捕获监听，表现为
                // "面板消失却仍可静默拾取、不阻挡程序"（用户不期望的行为）。
                try {
                    if (current.get().isClosed()) {
                        // 等待 onClose 回调把 current.get() 回退到存活父页（wait/notify 精确唤醒，
                        // 超时仅作兜底，避免 Thread.sleep 固定空等与时序抖动）。
                        synchronized (closeSignal) {
                            try { closeSignal.wait(500); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                        }
                        if (!current.get().isClosed()) continue;   // onClose 已把 current.get() 回退到存活父页
                        if (rootClosed.get()) break;               // 根页关闭且已置位：结束
                        // onClose 未回退（异常/未触发）：在已跟踪页面里找一个存活页继续，
                        // 仅当"确实没有任何存活页面"时才结束会话。
                        Page alive = null;
                        for (Page pg : pageNames.keySet()) {
                            if (pg != null && !pg.isClosed()) { alive = pg; break; }
                        }
                        if (alive == null) break;
                        current.set(alive);
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
                    handleIdle(pc, lastAutoGenSig);
                    continue;
                }
                if (ev.page != null && ev.page.isClosed()) continue;   // 页面已关闭：丢弃其命令
                current.set(ev.page);
                String cmd = ev.cmd;
                // 每次开始拾取前，把浏览器真实打开的页面（context.pages()）与内存跟踪表对齐：
                // 任何在 stop→再 start 之间、或 onPage/onPopup/followPage 因异常漏登记的打开页面都会被补登，
                // 确保"停止后再点开始"不会遗漏某页（表现为点了开始却拾取不了）。
                if ("start".equals(cmd)) {
                    reconcileTrackedPages(pc, ev.page);
                }
                PickerResult r;
                try {
                    r = runPickerCommand(pc, current.get(), cmd);
                } catch (Exception cmdEx) {
                    // 命令消费期间（典型：stop 命令的 page.evaluate 撞上整页跳转/导航导致 execution context 销毁）
                    // 抛出的异常若直接冒泡会撕裂主循环、进入 finally 静默关面板，表现为"点了停止却卡住/没反应"。
                    // 此处捕获后降级为 CONTINUE：若该命令是 stop 则 active 已被复位（runPickerCommand 在 evaluate 前先置 active.get()=false），
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
                                    pickerEval(p, RolePickerScripts.INVOKE_AFTER_FILL_JUMP_JS);
                                } catch (Exception ignore) {}
                            }
                        }
                    }
                } else {
                    setStatus(current.get(), r.statusMsg);
                }
            }
        } finally {
            // 关闭开关并移除面板（撤销 docked 预留的右侧空间）：写墓碑 '0'（而非 remove），
            // 使 context 级引导注入脚本（无法撤销）在之后的导航中自行退出、不再重建面板。
            try { pickerEval(page, RolePickerScripts.DISABLE_PANEL_JS); } catch (Exception ignore) {}
            try { pickerEval(page, RolePickerScripts.REMOVE_PICK_STATE_JS); } catch (Exception ignore) {}
            // 多实例：可能有多个页面各自带面板（默认页 + 若干弹窗），逐一关闭，避免残留。
            closePanel(page);
            for (Page p : openedPages) {
                try { if (p != null && !p.isClosed()) closePanel(p); } catch (Exception ignore) {}
            }
            if (current.get() != page) closePanel(current.get());
            // 关闭面板时一并关闭会话期间新开出的页面（弹窗/新标签页），仅保留最初的根页面。
            for (Page p : openedPages) {
                try { if (p != null && !p.isClosed()) p.close(); } catch (Exception ignore) {}
            }
        }
    }

    /**
     * 注册"弹窗跟随"：当页面弹出新标签页（target=_blank）时，把原页面的拾取状态
     * （__rolePicks / __steps / __currentStep / __rolePickSigs）转移到新页面，
     * 并让 {@code current.get()} 指向新页面继续拾取。面板是注入式 docked（同窗口），
     * 故需在弹窗页也重建面板；新页面自身若再弹窗会递归注册，支持多级弹窗。
     */
    private static void handleIdle(RolePickerContext pc, String[] lastAutoGenSig) {
        ConcurrentHashMap<Page, String> pageNames = pc.pageNames;
        BlockingQueue<CmdEvent> cmdQueue = pc.cmdQueue;
        ConcurrentHashMap<Page, String> snapshots = pc.snapshots;
        LinkedHashMap<String, RoleEntry> javaPickBySig = pc.javaPickBySig;
        AtomicBoolean active = pc.active;
        String nlsReverseJson = pc.nlsReverseJson;
        String[] nlsFiles = pc.nlsFiles;
        String packageName = pc.packageName;
        String pageClassName = pc.pageClassName;
        String stepClassName = pc.stepClassName;
        // 抽干浏览器端兜底命令队列 window.__panelCmds：当 exposeFunction 绑定尚未就绪时，
        // 面板按钮 pushCmd 会把命令推入该队列（见 PANEL_SCRIPT）。若 Java 不消费，▶ 开始/停止等
        // 命令会静默丢失 → active.get() 永远 false、START_SCRIPT 永不注入、点击拾取不到。
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
        if (active.get()) {
            for (Page pg : pageNames.keySet()) {
                if (!pg.isClosed()) ensurePickingActive(pg, nlsReverseJson, nlsFiles);
            }
        }
        // auto-generate step while picking (no need to click stop): each change in javaPickBySig
        // rebuilds one step (start->stop = one step) + page classes and fills the panel silently.
        // page class is auto-derived from each pick's page url; alert/iframe/new-page handled by generator.
        // 读者侧加锁前快照：javaPickBySig 的写入方（__roleOnPick / console / mergeFramePicksToMain）均在
        // synchronized(javaPickBySig) 内结构修改，而本读端（面板主循环线程）此前未取锁即遍历/读 size，
        // 与派发线程的并发写入存在 ConcurrentModificationException / 撕裂读风险。此处加同一把锁取不可变快照，
        // 后续全部基于快照操作，既消除竞态又不把锁延伸到 page.evaluate 等阻塞调用。
        List<RoleEntry> pickSnap;
        synchronized (javaPickBySig) { pickSnap = new ArrayList<>(javaPickBySig.values()); }
        if (!pickSnap.isEmpty()) {
            StringBuilder sigBuilder = new StringBuilder();
            sigBuilder.append(pickSnap.size()).append('#');
            for (RoleEntry e : pickSnap) {
                sigBuilder.append(e.getSigKey()).append('|');
            }
            String newSig = sigBuilder.toString();
            if (!newSig.equals(lastAutoGenSig[0])) {
                lastAutoGenSig[0] = newSig;
                try {
                    PickSnapshot autoSnap = RolePickerCodeAssembler.snapWithAutoStep(
                            new PickSnapshot(pageClassName, new ArrayList<>(pickSnap),
                                    new ArrayList<>(), new ArrayList<>()));
                    LinkedHashMap<String, String> autoPage = RolePickerCodeAssembler.buildPageClassCode(autoSnap.entries, packageName, pageClassName, nlsFiles);
                    LinkedHashMap<String, String> autoStep = RolePickerCodeAssembler.buildStepCode(autoSnap, packageName, stepClassName);
                    if (!autoPage.isEmpty() || !autoStep.isEmpty()) {
                        for (Page pg : pageNames.keySet()) {
                            if (!pg.isClosed()) {
                                fillCode(pg, autoPage, autoStep, "(picking) auto-generated " + autoSnap.steps.size() + " step(s), " + autoSnap.entries.size() + " field(s)");
                                try { pickerEval(pg, RolePickerScripts.SET_AUTO_STEP_COUNT_JS,
                                    RolePickerScripts.args("n", autoSnap.steps.size())); } catch (Exception ignore) {}
                            }
                        }
                    }
                } catch (Exception autoEx) {
                    log.warn("[picker] auto-generate step failed: {}", autoEx.getMessage());
                }
            }
        }
    }

    static void registerPopupFollow(RolePickerContext ctx, Page page, Page parent) {
        AtomicReference<Page> current = ctx.current;
        AtomicBoolean rootClosed = ctx.rootClosed;
        AtomicBoolean active = ctx.active;
        String nlsReverseJson = ctx.nlsReverseJson;
        String[] nlsFiles = ctx.nlsFiles;
        String packageName = ctx.packageName;
        String pageClassName = ctx.pageClassName;
        String stepClassName = ctx.stepClassName;
        ConcurrentHashMap<Page, String> pageNames = ctx.pageNames;
        ConcurrentHashMap<Page, String> snapshots = ctx.snapshots;
        LinkedHashMap<String, String> urlToClass = ctx.urlToClass;
        CopyOnWriteArrayList<Page> openedPages = ctx.openedPages;
        BlockingQueue<CmdEvent> cmdQueue = ctx.cmdQueue;
        java.util.Set<Page> navigatedPages = ctx.navigatedPages;
        Object closeSignal = ctx.closeSignal;
        LinkedHashMap<String, RoleEntry> javaPickBySig = ctx.javaPickBySig;
        // 弹窗（window.open / target=_blank 等）跟随：复用 followPage 把 inspector 跟随到新页面。
        page.onPopup(popup -> {
            // 新页面弹出时，把"最近一次拾取的元素"标记为 popup（其触发动作会打开新页），
            // 生成 step 时包装为 waitForNewPage(() -> element.click())（对齐 page.pause() 的 codegen 输出）。
            try {
                String sig = pickerEval(page, RolePickerScripts.READ_LAST_PICK_SIG_JS).toString();
                if (sig != null && !sig.isEmpty()) {
                    RoleEntry e = javaPickBySig.get(sig);
                    if (e != null) { e.setPopup(true); log.info("[picker] onPopup 捕获新页面，回写最近拾取元素 popup 标记：{}", sig); }
                }
            } catch (Exception ex) { log.warn("[picker] onPopup 标记失败（已忽略）：{}", ex.getMessage()); }
            followPage(ctx, page, popup);
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
                String sig = pickerEval(page, RolePickerScripts.READ_LAST_PICK_SIG_JS).toString();
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
                // 后者才需要补登记 closeCurrentPage 步骤。RolePickerSessionState.consumeFrameworkClose 读取即清除标记（页面关后失效）。
                boolean frameworkClosed = RolePickerSessionState.consumeFrameworkClose(closed);
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
                    // 否则 onClose 中途异常会跳过 current.get()=parent，主循环将把"弹窗关闭"误判为会话结束，
                    // 进入 finally 删除所有面板却残留点击监听，表现为"面板消失却仍可静默拾取"（用户不期望）。
                    current.set(parent);
                    // 父页此前一直处于拾取态（active 未被触碰），监听器仍存活；幂等重启以兜底，
                    // 不会因重复 START 而重复挂监听（START_SCRIPT 内已做 __rolePickActive 早退）。
                    try { if (active.get()) pickerEval(parent, RolePickerScripts.START_SCRIPT); } catch (Exception ignore) {}
                    // 合并关闭页已抓元素到父页 + 把"关闭该页"内联为当前 step 的一个操作标记：
                    // 与"回退父页/面板存活"解耦，单独容错，避免一处瞬态异常中断整段。
                    try {
                        // 多页面：关闭弹窗时把该页已抓元素"按签名合并"回父页（而非整盘覆盖）。
                        // 只把关闭页中"父页还没有"的 pick 并入父页，父页自身数据永不被抹掉；
                        // 旧页 pick 自带 _pageClass，合并后仍正确归类到各自 Page 类。
                        String closedState = snapshots.get(closed);
                        if (closedState != null && hasPicks(closedState)) {
                            pickerEval(parent, RolePickerScripts.MERGE_CLOSED_PAGE_PICKS_JS, RolePickerScripts.args(
                                    "nlsFiles", nlsFiles, "nlsReverseJson", nlsReverseJson, "closedState", closedState));
                    }
                        // 关键修复：把关闭页"进行中 step"（__currentStep）合并回父页当前 step，
                        // 使弹窗内拾取的元素随同一 step 继续累积——step 的唯一边界是"开始→停止"，
                        // 弹窗打开/关闭都只是同一 step 内的交互，绝不该拆出额外 step。
                        // 同时把"关闭该页"登记为当前 step 内的一个操作标记（_closeOp），而非独立 step，
                        // 使代码生成器产出 closeCurrentPage() 内联在主流程中（用户明确要求"只有一个条件：开始-停止"）。
                        // 关闭标记按 _sig 去重（支持多次关闭同类弹窗），并随 __currentStep 在停止时被收尾进唯一一个 step。
                        if (closedCls != null && !closedCls.isEmpty()) {
                            pickerEval(parent, RolePickerScripts.MERGE_CLOSE_OP_STEP_JS, RolePickerScripts.args(
                                    "closedState", closedState, "closedCls", closedCls));
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
                        pickerEval(parent, RolePickerScripts.SET_PANEL_FORCE_JS);
                        boolean parentHasPanel = Boolean.TRUE.equals(pickerEval(parent, 
                                "!!(document.getElementById('__rolePanel') && window.__renderPicks)"));
                        if (parentHasPanel) pickerEval(parent, RolePickerScripts.RENDER_PICKS_JS);
                        else {
                            // 面板容器确已丢失：兜底挂载一次，挂载后立刻渲染合并回父页的拾取，
                            // 确保面板"挂载即可见、可继续拾取"，杜绝"面板不见却后台静默拾取"的半吊子状态。
                            pickerEval(parent, RolePickerScripts.PANEL_SCRIPT);
                            pickerEval(parent, RolePickerScripts.RENDER_PICKS_JS);
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
                        current.set(survivor);
                        boolean hasPanel = Boolean.TRUE.equals(pickerEval(survivor, RolePickerScripts.HAS_PANEL_JS));
                        if (!hasPanel) {
                            pickerEval(survivor, RolePickerScripts.SET_PANEL_FORCE_JS);
                            pickerEval(survivor, RolePickerScripts.PANEL_SCRIPT);
                        }
                        pickerEval(survivor, RolePickerScripts.RENDER_PICKS_JS);
                        setStatus(survivor, "[picker] 默认页面已关闭，已切换到存活页面继续拾取（面板保留）。");
                        log.info("[picker] 根页面已关闭，但仍有存活页面，切换到 {} 继续会话（面板保留）。",
                                survivor.url());
                    } else {
                        rootClosed.set(true);   // 连根页面都关了、且无其它存活页面：结束会话
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
                            RolePickerCodeAssembler.appendCloseOpStep(closed, closedCls, snapshots);
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
                pickerEval(page, RolePickerScripts.MARK_LAST_PICK_DOWNLOAD_JS);
            } catch (Exception ignore) { /* 页面已关闭等：忽略 */ }
        });
        // 文件选择框监听：出现上传文件选择框时，把该页面最近一次 pick 标记为 upload（对齐 setInputFiles 录制）。
        page.onFileChooser(fc -> {
            try {
                Page fp = fc.page();
                pickerEval(fp, RolePickerScripts.MARK_LAST_PICK_UPLOAD_JS);
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
                log.info("[picker][nav] 触发 onFrameNavigated：page={} active.get()={}", page.url(), active.get());
                // 仅当该页属于本次拾取会话（有快照）才处理；其它无关页面 snapshots 为 null 自然跳过。
                // current.get() 只决定"正在操作的页面"，不影响各页自身面板状态的恢复。
                String st = snapshots.get(page);
                String prevCls = pageNames.get(page);
                // 【修复"导航回来后页面类串味 / 扫描为 0"】
                // 旧实现 onFrameNavigated 仅在末尾激活块才按新 URL 重解析类名（且受后续 evaluate 异常影响可能跳过），
                // 导致：手动跳回 logon 后 pageNames/window.__rolePageName 仍停留在上一页 SetupSecondPwdPage，
                // 新拾取元素被打错页类、面板按激活页过滤后显示为 0。此处【提前、无条件】按最新 URL 重解析并刷新
                // 当前页类名（含浏览器侧 window.__rolePageName），且与后续数据恢复解耦——即便恢复逻辑抛异常也不影响
                // 类名正确性。这同时实现"导航后聚焦当前真实页面"的诉求。
                String resolvedCls = RolePickerClassNameResolver.resolvePageClassForUrl(page.url(), pageNames.values(), urlToClass);
                if (resolvedCls != null && !resolvedCls.equals(prevCls)) {
                    pageNames.put(page, resolvedCls);
                    prevCls = resolvedCls;
                }
                try {
                    pickerEval(page, RolePickerScripts.SET_PAGE_NAME_IF_CHANGED_JS,
                            RolePickerScripts.args("pageName", resolvedCls));
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
                boolean __navOriginChanged = !__navOrigin.isEmpty() && !__navOrigin.equals(RolePickerSessionState.LAST_PICK_ORIGIN.get(page));
                // 无论 window 是否随导航销毁，都确保"之前拾取的元素"不丢失：
                //  - 整页重建（livePicks=false）：用 applyPickState 从快照整体恢复（含 nls 反查表）；
                //  - window 仍在（livePicks=true）：把快照中"当前窗口缺少"的 pick/step 合并回来，
                //    避免某些导航把 window.__rolePicks 连带清空，导致"元素不见了"。
                try {
                boolean livePicks = Boolean.TRUE.equals(pickerEval(page, 
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
                    pickerEval(page, RolePickerScripts.MERGE_LOCALSTORAGE_PICKS_JS);
                } else {
                    pickerEval(page, RolePickerScripts.MERGE_SNAPSHOT_PICKS_JS,
                            RolePickerScripts.args("stateJson", st));
                }
                // 关键修复（跨页累积不丢失）：SPA / 同 window 跳转时 livePicks=true，上面 if 分支【不会】执行，
                // 因而从不把 Java 快照 st 中"当前窗口缺失"的元素合并回来。一旦此类导航把 window.__rolePicks
                // 部分清空（常见框架路由 / 同页整文档替换），之前页（如 Page1）已拾元素便凭空消失，
                // 表现为"跳转到另一页后之前页面的元素不在了"。此处对 livePicks=true 也补一次合并：
                // 仅把 st 里有、而当前 window.__rolePicks 没有的元素按签名去重补回（不整体覆盖，不影响导航后新拾元素）。
                if (livePicks && st != null && !st.isEmpty()) {
                    pickerEval(page, RolePickerScripts.MERGE_MISSING_PICKS_JS,
                            RolePickerScripts.args("stateJson", st));
                }
                // 导航后始终重渲染面板列表并滚动到底部，确保已恢复/合并的元素可见（修复"URL 变化后元素看不见"）；
                // 用 setTimeout 兜底等待 PANEL_SCRIPT 的 build() 完成（body 就绪才挂载面板），避免提前渲染找不到节点，
                // 同时恢复上次生成的代码（页面元素 / 步骤代码两个 Tab），刷新后不丢。
                pickerEval(page, RolePickerScripts.POST_NAV_COMPACT_AND_RENDER_JS);
                // 依据新 URL 解析本页类名：优先复用会话级 urlToClass 稳定映射（同一 URL 复用同一类名，
                // 避免"回到默认页 URL 又派生出 LogonPage 之类重复页类"）——仅当该 URL 从未见过时才派生新类名。
                String curCls = pageNames.get(page);
                String newCls = RolePickerClassNameResolver.resolvePageClassForUrl(page.url(), pageNames.values(), urlToClass);
                pickerEval(page, RolePickerScripts.SET_PAGE_NAME_JS, RolePickerScripts.args("pageName", newCls));
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
                // 触发条件不再单纯依赖 Java 侧 active.get()（可能与浏览器态不同步），而以浏览器侧会话开关为准，
                // 只要门控脚本此前读到过开关（localStorage/__rolePickSessionOn）就重激活，保证"刷新/跳转后必能拾取"。
                boolean sessionOn = active.get();
                // 【修复"停止不了"】用户已显式停止（stop 置位 __rolePickStopped）后，即便后续发生导航，
                // 也绝不再重激活拾取——否则 stop 后又被 onFrameNavigated 复活，表现为"点了停止还是停不掉"。
                boolean explicitStop = false;
                try {
                    explicitStop = Boolean.TRUE.equals(pickerEval(page, 
                            RolePickerScripts.IS_PICK_STOPPED_JS));
                } catch (Exception ignore) {}
                if (!explicitStop && !sessionOn) {
                    try {
                        sessionOn = Boolean.TRUE.equals(pickerEval(page, 
                                RolePickerScripts.IS_SESSION_ON_JS));
                    } catch (Exception ignore) {}
                }
                if (sessionOn && !explicitStop) {
                    log.info("[picker][nav] 会话拾取中：同步激活状态 @ {}", page.url());
                    // 跨域（或任何门控脚本因 localStorage 隔离未注入）导航后，新文档的 gatedPickerInitScript
                    // 因读不到 localStorage.__rolePickSessionOn 而提前 return，整套拾取库（__recordPick/点击监听）
                    // 从未注入 → 表现为"无蓝框、点击无反应"。Java 主循环权威开关 active.get() 仍为 true，故进入本分支，
                    // 但仅置 window.__rolePickActive 是"假激活"（库不存在）。必须直接 start() 真正重注入整套库。
                    // 同源导航：门控脚本已在新文档早期注入库，仅做轻量保活即可（避免反复重注入竞态放大，见下）。
                    // 同源 vs 跨域判断：门控脚本(gatedPickerInitScript)靠 localStorage.__rolePickSessionOn 在【每个新文档】
                    // 早期注入库——同源导航 localStorage 同域可读到开关 → 库必已注入，只需轻量保活(__rolePickActive=true)；
                    // 跨域导航 localStorage 因 origin 隔离读不到 → 门控 return 未注入 → 必须强制 start() 重注入整套库。
                    // 故以 origin 是否变化作为"是否需要强制重注入"的唯一判据，避免对同源导航（含 SPA hash 变化、整页跳转）
                    // 反复重注入造成"扫描了很多元素"的放大。SPA hash 变化(#/question1)不改变 origin → 视为同源，仅保活。
                    String curOrigin = safeOrigin(page.url());
                    boolean originChanged = !curOrigin.isEmpty() && !curOrigin.equals(RolePickerSessionState.LAST_PICK_ORIGIN);
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
                            pickerEval(page, RolePickerScripts.SET_PICK_ACTIVE_AND_RENDER_JS);
                        } catch (Exception ex) {
                            // 导航瞬间新文档执行上下文可能尚未就绪，page.evaluate 会抛"上下文已销毁"类异常；
                            // 此处等待 DOM 就绪后重试一次轻量激活保活（仍不重注入整套库）。
                            log.warn("[picker][nav] 激活保活首轮失败，等待页面就绪后重试 @ {} : {}", page.url(), ex.getMessage());
                            try { page.waitForLoadState(); } catch (Exception ignore2) {}
                            try { pickerEval(page, RolePickerScripts.SET_PICK_ACTIVE_AND_RENDER_JS); }
                            catch (Exception ex2) { log.warn("[picker][nav] 激活保活重试仍失败 @ {} : {}", page.url(), ex2.getMessage()); }
                        }
                    } else {
                        // ===== 跨域导航：门控脚本因 localStorage 隔离未注入 → 强制 start() 重注入整套库 =====
                        // 去抖：一次跨域导航会触发 onFrameNavigated 多次（about:blank 过渡/重定向/主框架/iframe），
                        // 每次都强制 start 会清空并重建 __rolePickSigs、重复渲染所有元素，表现为"扫描了很多元素"。
                        // 同一 page 在 RolePickerSessionState.FORCE_START_DEBOUNCE_MS 内只真正重注入一次。
                        long now = System.currentTimeMillis();
                        Long last = RolePickerSessionState.FORCE_START_TS.get(page);
                        if (last != null && (now - last) < RolePickerSessionState.FORCE_START_DEBOUNCE_MS) {
                            log.info("[picker][nav] 跨域重注入去抖（{}ms 内已注入，跳过）@ {}", (now - last), page.url());
                        } else {
                            RolePickerSessionState.FORCE_START_TS.put(page, now);
                            log.warn("[picker][nav] 检测到跨域导航库未注入，强制 start() 重注入 @ {} : origin={} -> {}",
                                    page.url(), RolePickerSessionState.LAST_PICK_ORIGIN, curOrigin);
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
                                pickerEval(page, RolePickerScripts.PANEL_FORCE_AND_ENABLE_JS);
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
                    String navDiag = pickerEval(page, RolePickerScripts.NAV_DIAG_JS).toString();
                    log.info("[picker][nav] 导航恢复/激活后运行时诊断 @ {} : {}", page.url(), navDiag);
                } catch (Exception diagEx) {
                    log.warn("[picker][nav] 读取诊断失败（页面可能已关闭）：{}", diagEx.getMessage());
                }
            } catch (Exception ignore) { /* 页面已关闭等：忽略 */ }
        });
        // 注：已按需求移除 onConsoleMessage / onRequestFailed 监听（避免刷屏、聚焦页面生命周期日志）。
        // 页面生命周期打印见上方 onPage（新页面打开）、onClose（页面关闭）与下方 onFrameNavigated（URL 变化）。
    }
}
