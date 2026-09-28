package com.hsbc.cmb.hk.dbb.automation.framework.route.v2.binding;

import com.hsbc.cmb.hk.dbb.automation.framework.route.v2.RouteRuntime;
import com.hsbc.cmb.hk.dbb.automation.framework.route.v2.dsl.ApiSpec;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Route;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Pattern 绑定 —— 每个 pattern 对应一个 Playwright 原生 {@code context.route()} 注册。
 *
 * <p>职责：
 * <ul>
 *   <li>把规则注册到驱动（对应 Playwright 客户端 {@code Router.RouteInfo}，即"驱动层 1 个 handler"）；</li>
 *   <li>事件到达时仅转发给 {@code RouteDispatcher}（裁决在事件线程外分层进行，本类不做任何阻塞操作）；</li>
 *   <li>持有原生 {@code AutoCloseable} 句柄，{@link #close()} 精确注销，幂等且线程安全。</li>
 * </ul>
 *
 * <p>并发安全：{@code bound} 原子标志保证 {@code route()} 恰好执行一次；
 * 注销句柄写入一次、读取多次（{@code volatile} 语义由 final 字段保证——句柄在构造期写入）。
 *
 * <p><b>协议调用有界等待（2026-09-27 实测根因修复，2026-09-28 复盘收口）</b>：{@code nativeHandle.close()}
 * （= Playwright {@code context.unroute}）与 {@code context.route()}（= Playwright
 * {@code context.route → updateInterceptionPatterns}）都是<b>同步协议调用</b>——jstack 实测在 Node
 * 驱动不响应时（{@code ... → updateInterceptionPatterns → sendMessage → PipeTransport.poll}）会无限阻塞：
 * unroute 无响应曾导致 scenario/套件收尾挂死（E2E 卡 11 分钟）；route 注册无响应曾导致首个 scenario
 * 在 step 内永久卡死（E2E 卡 14 分钟，见 test-automation 1.txt）。bind/unroute 现已统一收口到
 * {@link GuardedDriverCall} 原语（daemon 线程执行 + 有界等待）：unroute 用
 * {@link GuardedDriverCall.OnTimeout#WARN_AND_ABANDON}（超时/异常仅 WARN，不阻塞清理链）；route 注册用
 * {@link GuardedDriverCall.OnTimeout#FAIL_FAST}（超时/异常抛异常让 step 快速失败）。残留驱动层 handler 会随
 * context 关闭被 Playwright 自动释放，绝不阻塞调用线程或清理主链。</p>
 */
public final class PatternBinder implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(PatternBinder.class);

    private final BrowserContext context;
    private final String pattern;
    private final RouteRuntime runtime;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AutoCloseable nativeHandle;

    private PatternBinder(BrowserContext context, String pattern, RouteRuntime runtime, AutoCloseable nativeHandle) {
        this.context = context;
        this.pattern = pattern;
        this.runtime = runtime;
        this.nativeHandle = nativeHandle;
    }

    /**
     * 创建并注册绑定。
     *
     * @param context 目标 BrowserContext
     * @param spec    规则（pattern 取自 spec）
     * @param runtime 所属运行时（事件转发目标）
     */
    public static PatternBinder bind(BrowserContext context, ApiSpec spec, RouteRuntime runtime) {
        // 注册前做前后缀通配归一化（**/profile/list**）：相对端点直接传 Playwright glob 会按完整
        // URL 精确匹配而永不命中（E2E 实测根因）。spec.pattern() 保持业务层原始端点不变——
        // 响应侧配对已改为按 ApiSpec 精确匹配（route 通道轮询 existingResponse），
        // 不再依赖 URL 匹配，故 pattern 的原始值只用于记录/诊断。
        String glob = RoutePatterns.normalize(spec.pattern());
        // context.route() 是同步协议调用（→ updateInterceptionPatterns → 驱动回包），驱动不响应时会
        // 无限阻塞调用线程（E2E 实测卡 14 分钟，见 test-automation 1.txt）。统一收口到 GuardedDriverCall：
        // daemon 线程执行 + 有界等待，FAIL_FAST 让 step 快速失败而非死等；残留 daemon 线程在驱动恢复或
        // context 关闭时结束，驱动层 handler 随 context 关闭释放。
        AutoCloseable handle = GuardedDriverCallRegistry.instance().guarded(
                "bind:" + spec.pattern(), GuardedDriverCall.BIND_BOUND_MS, GuardedDriverCall.OnTimeout.FAIL_FAST,
                () -> context.route(glob, route -> onRoute(route, runtime, spec.pattern()),
                        spec.times() == null ? null
                                : new BrowserContext.RouteOptions().setTimes(spec.times())));
        return new PatternBinder(context, spec.pattern(), runtime, handle);
    }

    private static void onRoute(Route route, RouteRuntime runtime, String pattern) {
        // 事件线程入口：全程无阻塞（终结命令走 sendMessageAsync；fetch/重装走 IO 池）
        runtime.dispatch(route, pattern);
    }

    public String pattern() {
        return pattern;
    }

    /**
     * 注销绑定。幂等、线程安全；有界等待（{@link GuardedDriverCall#UNROUTE_BOUND_MS}），超时/异常仅 WARN
     * 放弃，不阻塞清理链（语义由 {@link GuardedDriverCall.OnTimeout#WARN_AND_ABANDON} 保证）。
     */
    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            GuardedDriverCallRegistry.instance().guarded("unroute:" + pattern, GuardedDriverCall.UNROUTE_BOUND_MS,
                    GuardedDriverCall.OnTimeout.WARN_AND_ABANDON, () -> {
                        nativeHandle.close();
                        return null;
                    });
        }
    }

    @Override
    public String toString() {
        return "PatternBinder{pattern='" + pattern + "', closed=" + closed.get() + '}';
    }
}
