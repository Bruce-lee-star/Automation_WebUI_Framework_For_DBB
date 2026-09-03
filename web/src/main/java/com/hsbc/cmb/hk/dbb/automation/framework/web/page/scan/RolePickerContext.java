package com.hsbc.cmb.hk.dbb.automation.framework.web.page.scan;

import com.microsoft.playwright.Page;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.BlockingQueue;

/**
 * 单次拾取会话的可变工作集（T5-1 优化：消除 followPage / registerPopupFollow /
 * reconcileTrackedPages / runPickerCommand 等方法的 16 参数长签名）。
 *
 * <p>由 {@link RoleElementPicker#openPanel} 一次性构造，汇聚「被跟踪页面 ↔ 页类名」映射、快照、
 * 会话级 URL→类名映射、命令队列、拾取内存态，以及驱动多页面板/导航/关闭协调所需的共享可变状态。
 * 各方法经本对象读写同一份引用（current / rootClosed / active 以数组承载可变共享态），
 * 行为与原逐参透传完全等价、零变更。字段均为 final 引用，仅其内部状态可变。
 */
final class RolePickerContext {

    final Page[] current;
    final boolean[] rootClosed;
    final boolean[] active;
    final String nlsReverseJson;
    final String[] nlsFiles;
    final String packageName;
    final String pageClassName;
    final String stepClassName;
    final LinkedHashMap<Page, String> pageNames;
    final LinkedHashMap<Page, String> snapshots;
    final LinkedHashMap<String, String> urlToClass;
    final List<Page> openedPages;
    final BlockingQueue<RoleElementPicker.CmdEvent> cmdQueue;
    final Set<Page> navigatedPages;
    final Object closeSignal;
    final LinkedHashMap<String, RoleEntry> javaPickBySig;

    RolePickerContext(Page[] current, boolean[] rootClosed, boolean[] active,
                      String nlsReverseJson, String[] nlsFiles,
                      String packageName, String pageClassName, String stepClassName,
                      LinkedHashMap<Page, String> pageNames,
                      LinkedHashMap<Page, String> snapshots,
                      LinkedHashMap<String, String> urlToClass,
                      List<Page> openedPages,
                      BlockingQueue<RoleElementPicker.CmdEvent> cmdQueue,
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
