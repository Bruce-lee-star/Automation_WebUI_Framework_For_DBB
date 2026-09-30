package com.hsbc.cmb.hk.dbb.automation.framework.route.claim;

import com.hsbc.cmb.hk.dbb.automation.framework.route.dispatch.RouteAction;
import com.microsoft.playwright.Route;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 索赔注册表 —— 每 context 一份，管理全部在途 Route 的 claim 生命周期。
 *
 * <p>并发安全设计：
 * <ul>
 *   <li>{@code ConcurrentHashMap<Route, RouteClaim>}：{@link #tryClaim} 用
 *       {@code putIfAbsent} 保证「同一 Route 对象至多一个 claim」（防重放 / 防并发处理）；</li>
 *   <li>termination 时 {@code remove(route, claim)}（CAS 语义，避免误删他人新 claim）；</li>
 *   <li>挂起额度由 {@link PendingGuard} 统一记账；终结结果携带 {@code wasIoAwait} 标志，
 *       由状态机保证额度恰好释放一次。</li>
 * </ul>
 *
 * <p>巡检（{@link #sweep}）：由 IO 执行器的周期任务调用。对超龄 IO_AWAIT claim
 * 强制 fallback（fail-open），防止 IO 任务丢失/异常导致请求永久悬挂。
 */
public final class ClaimRegistry {

    private static final Logger LOGGER = LoggerFactory.getLogger(ClaimRegistry.class);

    private final ConcurrentMap<Route, RouteClaim> claims = new ConcurrentHashMap<>();
    private final PendingGuard pendingGuard;
    private final long ioAwaitTimeoutNanos;

    public ClaimRegistry(PendingGuard pendingGuard, Duration ioAwaitTimeout) {
        this.pendingGuard = pendingGuard;
        this.ioAwaitTimeoutNanos = ioAwaitTimeout.toNanos();
    }

    /**
     * 尝试为 Route 建立唯一 claim。
     *
     * @return 新 claim；若该 Route 已存在 claim 则返回 null（调用方必须 fail-open）
     */
    public RouteClaim tryClaim(Route route) {
        RouteClaim candidate = new RouteClaim(route);
        RouteClaim existing = claims.putIfAbsent(route, candidate);
        return existing == null ? candidate : null;
    }

    /**
     * 终结标记（HANDLED 或 RELEASED）。
     *
     * @return true 表示本次调用真正完成了终结（此前处于 NEW 或 IO_AWAIT）
     */
    public boolean markTerminal(RouteClaim claim, boolean handled) {
        RouteClaim.TerminalResult result = claim.tryTerminal(handled);
        if (!result.granted()) {
            return false;
        }
        if (result.wasIoAwait()) {
            pendingGuard.release();
        }
        claims.remove(claim.route(), claim);
        return true;
    }

    /** 在途 claim 数。 */
    public int size() {
        return claims.size();
    }

    /** 当前挂起（IO_AWAIT）数。 */
    public int pendingCount() {
        return pendingGuard.pendingCount();
    }

    /**
     * 巡检：强制落定超龄 IO_AWAIT claim（fail-open，不悬挂请求）。
     *
     * <p>仅调度线程调用；内部用 {@code tryTerminal} 抢占，不会与 IO 线程并发终结同一 Route。
     */
    public void sweep() {
        long now = System.nanoTime();
        for (RouteClaim claim : claims.values()) {
            if (!claim.isIoAwait()) {
                continue;
            }
            if (now - claim.createdAtNanos() <= ioAwaitTimeoutNanos) {
                continue;
            }
            if (markTerminal(claim, false)) {
                LOGGER.warn("[Route] sweep: force fallback for stale IO_AWAIT claim, url='{}'",
                        claim.route().request().url());
                try {
                    RouteAction.fallback(claim.route());
                } catch (Exception e) {
                    LOGGER.warn("[Route] sweep: fallback failed for url='{}': {}",
                            claim.route().request().url(), e.toString());
                }
            }
        }
    }

    /** 运行时关闭：全部在途 claim 强制 fallback 落定（防悬挂泄漏）。 */
    public void closeAll() {
        for (RouteClaim claim : claims.values()) {
            if (markTerminal(claim, false)) {
                try {
                    RouteAction.fallback(claim.route());
                } catch (Exception ignored) {
                    LOGGER.trace("[Route] closeAll fallback ignored for url='{}': {}",
                            claim.route().request().url(), ignored.toString());
                }
            }
        }
    }
}
