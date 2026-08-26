package com.hsbc.cmb.hk.dbb.automation.framework.web.route.capture;

import com.microsoft.playwright.Page;
import com.microsoft.playwright.Request;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * 浏览器无关的请求生命周期关联器。
 * 只保存轻量身份和终态，不跨线程持有 Response/Route。
 */
public final class NetworkEventCorrelator {
    private static final int MAX_PENDING = 4096;

    private final Object lock = new Object();
    // 修复 P2-5：用访问顺序 LinkedHashMap 实现 LRU 驱逐（替代原 IdentityHashMap + findFirst 任意驱逐）。
    // Request 未重写 equals/hashCode，LinkedHashMap 退化为引用相等语义，与原 IdentityHashMap 行为一致。
    private final Map<Request, Entry> pending = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Request, Entry> eldest) {
            if (size() > MAX_PENDING) {
                evictedCount.incrementAndGet();
                return true;
            }
            return false;
        }
    };
    private final AtomicLong evictedCount = new AtomicLong();
    private final AtomicLong missingFinishCount = new AtomicLong();
    private volatile boolean accepting = true;

    public String onRequest(Page page, Request request, Consumer<String> publisher) {
        if (!accepting || request == null) return null;
        synchronized (lock) {
            if (!accepting) return null;
            Entry entry = pending.get(request);
            if (entry == null) {
                entry = new Entry(request, newId(), page);
                pending.put(request, entry); // LRU 驱逐由 removeEldestEntry 自动处理
            }
            if (publisher != null) publisher.accept(entry.requestId());
            return entry.requestId();
        }
    }

    public String idFor(Request request) {
        synchronized (lock) {
            Entry entry = pending.get(request);
            return entry == null ? null : entry.requestId();
        }
    }

    public String finish(Request request) {
        synchronized (lock) {
            Entry entry = pending.remove(request);
            if (entry == null) missingFinishCount.incrementAndGet();
            return entry == null ? null : entry.requestId();
        }
    }

    public int pendingCount() {
        synchronized (lock) {
            return pending.size();
        }
    }

    public long evictedCount() { return evictedCount.get(); }
    public long missingFinishCount() { return missingFinishCount.get(); }

    /** 启动新的采集会话；旧会话残留请求不会跨 session 关联。 */
    public void start() {
        synchronized (lock) {
            pending.clear();
            accepting = true;
        }
    }

    public void stop() {
        accepting = false;
        synchronized (lock) {
            pending.clear();
        }
    }

    private static String newId() {
        return "pw-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    private record Entry(Request request, String requestId, Page page) {
    }
}
