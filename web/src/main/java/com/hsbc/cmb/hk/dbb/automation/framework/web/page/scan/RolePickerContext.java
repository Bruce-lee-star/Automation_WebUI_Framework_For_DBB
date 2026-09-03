package com.hsbc.cmb.hk.dbb.automation.framework.web.page.scan;

import com.microsoft.playwright.Page;

import java.util.LinkedHashMap;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 单次拾取会话的可变工作集（T5-1 优化：消除 followPage / registerPopupFollow /
 * reconcileTrackedPages / runPickerCommand 等方法的 16 参数长签名）。
 *
 * <p>由 {@link RoleElementPicker#openPanel} 一次性构造，汇聚「被跟踪页面 ↔ 页类名」映射、快照、
 * 会话级 URL→类名映射、命令队列、拾取内存态，以及驱动多页面板/导航/关闭协调所需的共享可变状态。
 *
 * <p>线程安全说明（T5-1 扫描包并发加固）：本对象被两条线程共享——
 * ① 面板主循环线程（openPanel while-loop / handleIdle / runPickerCommand）；
 * ② Playwright 事件派发线程（onPage→followPage、onPopup / onClose / onFrameNavigated / onDialog…）。
 * 二者会并发读写以下可变共享态，故做如下加固（行为与原逐参透传完全等价、零变更）：
 * <ul>
 *   <li>pageNames / snapshots 改为 {@link ConcurrentHashMap}，openedPages 改为
 *       {@link CopyOnWriteArrayList}，navigatedPages 改为并发 Set：读端遍历不再抛
 *       ConcurrentModificationException，写端结构修改对各线程立即可见；</li>
 *   <li>current / active / rootClosed 由单元素数组改为 {@link AtomicReference} /
 *       {@link AtomicBoolean}：跨线程写入对他线程立即可见，且单元素读写原子（避免撕裂读）；</li>
 *   <li>javaPickBySig 仍由既有 synchronized(javaPickBySig) 守卫（见 RolePickerPanelController /
 *       RolePickerPanelSync），读端加锁前取快照，锁不延伸到 page.evaluate 等阻塞调用。</li>
 * </ul>
 * urlToClass 在构造后只读，经 final 引用安全发布，无需额外同步。
 */
final class RolePickerContext {

    final AtomicReference<Page> current;
    final AtomicBoolean rootClosed;
    final AtomicBoolean active;
    final String nlsReverseJson;
    final String[] nlsFiles;
    final String packageName;
    final String pageClassName;
    final String stepClassName;
    final ConcurrentHashMap<Page, String> pageNames;
    final ConcurrentHashMap<Page, String> snapshots;
    final LinkedHashMap<String, String> urlToClass;
    final CopyOnWriteArrayList<Page> openedPages;
    final BlockingQueue<RolePickerBridgeRegistry.CmdEvent> cmdQueue;
    final Set<Page> navigatedPages;
    final Object closeSignal;
    final LinkedHashMap<String, RoleEntry> javaPickBySig;

    RolePickerContext(AtomicReference<Page> current, AtomicBoolean rootClosed, AtomicBoolean active,
                      String nlsReverseJson, String[] nlsFiles,
                      String packageName, String pageClassName, String stepClassName,
                      ConcurrentHashMap<Page, String> pageNames,
                      ConcurrentHashMap<Page, String> snapshots,
                      LinkedHashMap<String, String> urlToClass,
                      CopyOnWriteArrayList<Page> openedPages,
                      BlockingQueue<RolePickerBridgeRegistry.CmdEvent> cmdQueue,
                      Set<Page> navigatedPages, Object closeSignal,
                      LinkedHashMap<String, RoleEntry> javaPickBySig) {
        this.current = current;
        this.rootClosed = rootClosed;
        this.active = active;
        this.nlsReverseJson = nlsReverseJson;
        this.nlsFiles = nlsFiles;
        this.packageName = packageName;
        this.pageClassName = pageClassName;
        this.stepClassName = stepClassName;
        this.pageNames = pageNames;
        this.snapshots = snapshots;
        this.urlToClass = urlToClass;
        this.openedPages = openedPages;
        this.cmdQueue = cmdQueue;
        this.navigatedPages = navigatedPages;
        this.closeSignal = closeSignal;
        this.javaPickBySig = javaPickBySig;
    }
}
