package com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle;

import com.microsoft.playwright.BrowserContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * 下载登记簿（framework-internal）：记录每个 {@link BrowserContext} 最近保存的下载文件，
 * 供业务层经 {@link PlaywrightManager#getLastDownloadPath()} /
 * {@link PlaywrightManager#getLastDownloadFileName()} / {@link PlaywrightManager#getDownloadPaths()} 查询。
 *
 * <p><b>线程安全：</b>以 {@code BrowserContext} 为键，各上下文独立维护一个 FIFO 队列（最近下载追加至队尾）。
 * 跨线程可见性由 {@link ConcurrentHashMap} / {@link ConcurrentLinkedDeque} 的 happens-before 语义保证——
 * 登记发生在 Playwright 下载事件回调线程，查询发生在业务（场景）线程，二者经本登记簿安全传递，无额外锁。</p>
 *
 * <p><b>生命周期：</b>随 {@code BrowserContext} 隔离；{@link #clear(BrowserContext)} 在 context 关闭时由
 * {@code PlaywrightContextManager.closeContext} 调用，避免陈旧上下文的下载记录无限堆积。</p>
 *
 * @apiNote 业务代码请勿直接使用本类，统一经 {@link PlaywrightManager} 的下载查询方法；本类仅承载框架内部登记状态。
 */
public final class DownloadRegistry {

    private static final Logger logger = LoggerFactory.getLogger(DownloadRegistry.class);
    private static final DownloadRegistry INSTANCE = new DownloadRegistry();

    private final Map<BrowserContext, Deque<Path>> byContext = new ConcurrentHashMap<>();

    private DownloadRegistry() {
    }

    /**
     * 获取单例（饿汉、无状态协作对象）。
     */
    public static DownloadRegistry instance() {
        return INSTANCE;
    }

    /**
     * 登记一次已落盘的下载（由 {@code PlaywrightContextManager.registerDownloadHandler}
     * 在 {@code download.saveAs} 成功后调用）。
     *
     * @param context   触发下载的浏览器上下文（null 安全：忽略）
     * @param savedPath 已保存文件的绝对路径（null 安全：忽略）
     */
    public void record(BrowserContext context, Path savedPath) {
        if (context == null || savedPath == null) {
            return;
        }
        byContext.computeIfAbsent(context, k -> new ConcurrentLinkedDeque<>()).addLast(savedPath);
        if (logger.isDebugEnabled()) {
            logger.debug("[Download] Recorded saved file for context {}: {}",
                    System.identityHashCode(context), savedPath);
        }
    }

    /**
     * 取指定上下文最近一次下载的路径；无记录返回 {@code null}。
     */
    public Path last(BrowserContext context) {
        Deque<Path> deque = context == null ? null : byContext.get(context);
        return (deque == null || deque.isEmpty()) ? null : deque.peekLast();
    }

    /**
     * 取指定上下文全部下载路径（按时间升序）；无记录返回空列表（非 {@code null}）。
     */
    public List<Path> all(BrowserContext context) {
        Deque<Path> deque = context == null ? null : byContext.get(context);
        if (deque == null || deque.isEmpty()) {
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(new ArrayList<>(deque));
    }

    /**
     * 清除指定上下文的全部下载记录（context 关闭时调用）。
     */
    public void clear(BrowserContext context) {
        if (context != null) {
            byContext.remove(context);
        }
    }
}
