package com.hsbc.cmb.hk.dbb.automation.framework.route.binding;

import com.hsbc.cmb.hk.dbb.automation.framework.route.RouteRuntime;
import com.hsbc.cmb.hk.dbb.automation.framework.route.dsl.ApiSpec;
import com.hsbc.cmb.hk.dbb.automation.framework.route.dsl.RouteCapability;
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
 * <p>并发安全：{@code closed} 原子标志保证注销恰好执行一次；句柄在构造期写入（final），读取无需额外同步。
 *
 * <p><b>协议调用有界等待（2026-09-28 收口，2026-09-29 探针复核）</b>：{@code context.route()} /
 * {@code nativeHandle.close()}（= {@code context.unroute}）最终都是 Playwright 客户端的
 * {@code sendMessage("setNetworkInterceptionPatterns", …, NO_TIMEOUT)}（{@code BrowserContextImpl:745-747}，
 * <b>客户端自身不设超时</b>，且由调用线程自己泵消息），驱动不响应时会无限阻塞调用线程。
 * 故 bind/unroute 统一收口到 {@link GuardedDriverCall} 原语（daemon 线程 + 有界等待，固定 30s/10s）。</p>
 *
 * <p><b>2026-09-29 探针结论（更正旧表述）</b>：该调用<b>并非</b>长时间挂起 —— 界值之后约 3~5 秒即结束，
 * 以 {@code Object doesn't exist: worker@/frame@}（或看门狗中断产物 {@code Failed to read message}）抛错。
 * 根因是客户端 {@code Connection.dispatch} 未按消息隔离异常：一条"引用已释放对象"的事件会把当时正在
 * 等待回执的调用一起带崩。该缺陷已由框架自建客户端 <b>DBBN-PATCH-01</b> 修复
 * （见 {@code docs/patches/playwright-java-1.62.0-dbb-patch-01.md}）。因此<b>不再需要对瞬时错误做重试或
 * 异常嗅探</b>；看门狗保留为"驱动真卡死"的兜底。</p>
 *
 * <p><b>注册失败处理（2026-09-29 修订：按规则能力决定，行为类 fail-closed）</b>：
 * <ol>
 *   <li><b>行为类能力</b>（{@code MOCK} / {@code MODIFY_REQUEST} / {@code DELAY}）→ {@code FAIL_FAST}：
 *       注册失败即<b>响亮失败</b>。规则没生效却继续跑，断言口径会被静默改变，不可接受；</li>
 *   <li><b>观测类能力</b>（{@code MONITOR}）→ {@code WARN_AND_ABANDON}：允许降级
 *       （返回 {@link #isDegraded()} 的惰性绑定 + ERROR 告警），因其失败不影响用例正确性。</li>
 * </ol>
 * 降级绑定持有 {@link #NOOP_HANDLE 空句柄}，故 {@link #close()} 行为与正常路径一致（仍走 SPI 与幂等注销）。
 * 残留驱动层 handler 会随 context 关闭被 Playwright 自动释放，绝不阻塞调用线程或清理主链。</p>
 */

public final class PatternBinder implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(PatternBinder.class);

    /** 降级绑定的空句柄：保证 {@link #close()} 与正常路径同构（仍经 SPI、幂等、无副作用）。 */
    private static final AutoCloseable NOOP_HANDLE = () -> { };

    private final BrowserContext context;
    private final String pattern;
    private final RouteRuntime runtime;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final boolean degraded;
    private final AutoCloseable nativeHandle;

    private PatternBinder(BrowserContext context, String pattern, RouteRuntime runtime,
                          AutoCloseable nativeHandle, boolean degraded) {
        this.context = context;
        this.pattern = pattern;
        this.runtime = runtime;
        this.nativeHandle = nativeHandle;
        this.degraded = degraded;
    }

    /**
     * 创建并注册绑定。
     *
     * @param context 目标 BrowserContext
     * @param spec    规则（pattern 取自 spec）
     * @param runtime 所属运行时（事件转发目标）
     */
    public static PatternBinder bind(BrowserContext context, ApiSpec spec, RouteRuntime runtime) {
        // Wildcard normalisation of the pattern; spec.pattern() keeps the business-level endpoint
        // (response pairing matches on ApiSpec, not on the URL).
        String glob = RoutePatterns.normalize(spec.pattern());
        long bindBoundMs = GuardedDriverCall.bindBoundMs();
        // Behaviour-affecting rules (MOCK / MODIFY_REQUEST / DELAY) are FAIL-CLOSED: registering them is
        // meaningless unless it really took effect, and running the case without them would silently change
        // its assertion baseline. MONITOR only observes, so it may degrade.
        // NOTE (2026-09-29): the previous "record the action error to tell a genuine failure apart from a
        // timeout" trick is gone -- it could not be made reliable (the guard interrupts the abandoned call,
        // whose interrupt fallout then looked like a genuine error) and it re-introduced exception sniffing.
        boolean failClosed = spec.capability() != RouteCapability.MONITOR;
        AutoCloseable handle = GuardedDriverCallRegistry.instance().guarded(
                "bind:" + spec.pattern(), bindBoundMs,
                failClosed ? GuardedDriverCall.OnTimeout.FAIL_FAST
                        : GuardedDriverCall.OnTimeout.WARN_AND_ABANDON,
                () -> context.route(glob, route -> onRoute(route, runtime, spec.pattern()),
                        spec.times() == null ? null
                                : new BrowserContext.RouteOptions().setTimes(spec.times())));
        if (handle != null) {
            return new PatternBinder(context, spec.pattern(), runtime, handle, false);
        }
        if (failClosed) {
            // A conforming FAIL_FAST guard throws instead of returning null; never degrade silently here.
            throw new IllegalStateException("[Route] route registration failed for pattern="
                    + spec.pattern() + " capability=" + spec.capability());
        }
        reportDegradedRegistration(spec, bindBoundMs);
        return new PatternBinder(context, spec.pattern(), runtime, NOOP_HANDLE, true);
    }

    /** 注册超时降级告警：行为影响面 + 恢复严格语义的方法，缺一不可（避免"静默失效"）。 */
    private static void reportDegradedRegistration(ApiSpec spec, long bindBoundMs) {
        LOGGER.error("[Route] route registration DEGRADED for pattern='{}' capability={} — no reply within {}ms; "
                        + "this rule is INERT for this case (its mock/monitor will NOT apply).",
                spec.pattern(), spec.capability(), bindBoundMs);
        if (spec.capability() != RouteCapability.MONITOR) {
            LOGGER.warn("[Route] DEGRADED rule '{}' is BEHAVIOUR-AFFECTING (capability={}) — the case will run "
                            + "WITHOUT this mock/modify/delay, so its behaviour may differ from the intended scenario.",
                    spec.pattern(), spec.capability());
        }
    }

    private static void onRoute(Route route, RouteRuntime runtime, String pattern) {
        // 事件线程入口：全程无阻塞（终结命令走 sendMessageAsync；fetch/重装走 IO 池）
        runtime.dispatch(route, pattern);
    }

    public String pattern() {
        return pattern;
    }

    /** 是否因注册超时被降级：驱动层未生效（规则仍在内存中发布，但不参与任何 mock/monitor 行为）。 */
    public boolean isDegraded() {
        return degraded;
    }

    /**
     * 注销绑定（不关心结论）。幂等、线程安全；语义与 {@link #closeConfirmed()} 完全一致，
     * 仅丢弃确证结论。降级绑定持有空句柄，故本方法与正常路径同构。
     */
    @Override
    public void close() {
        closeConfirmed();
    }

    /**
     * 注销绑定并返回<b>是否确证</b>（2026-09-28，T2+：撤销必须可判定）。
     *
     * <p><b>为什么必须回传结论</b>：{@code unroute} 与 {@code route} 同为客户端的
     * {@code setNetworkInterceptionPatterns}（{@code NO_TIMEOUT} + 调用线程自己泵消息，
     * 见 {@code BrowserContextImpl:715-722} / {@code ChannelOwner.runUntil}）。现状用
     * {@link GuardedDriverCall.OnTimeout#WARN_AND_ABANDON} 只 WARN、不置位 ⇒
     * 「用例收尾是否真的恢复如初」从未被判定。本方法把结论显式化：收到 ack ⇒ 客户端与驱动一致
     * （下发是全量快照、单连接 FIFO 有序）；界内无回包/异常 ⇒ <b>状态不可确证</b>，
     * 调用方须按不变式 I-9 丢弃 Context 重建。</p>
     *
     * <p><b>幂等</b>：重复调用返回 {@code true}（已撤销过）。注意<b>不重试</b>：一旦某次撤销未确证，
     * 本实例不再尝试（{@code closed} 已置位），未确证状态由调用方（{@code RouteRuntimeImpl}）记录并
     * 升级为 degraded，交由"丢弃重建"兜底。</p>
     *
     * @return {@code true}=已确证撤销（含幂等重复调用与降级空句柄）；{@code false}=界内无回包，不可确证
     */
    public boolean closeConfirmed() {
        if (!closed.compareAndSet(false, true)) {
            return true;
        }
        Object ack = GuardedDriverCallRegistry.instance().guarded("unroute:" + pattern,
                GuardedDriverCall.unrouteBoundMs(), GuardedDriverCall.OnTimeout.WARN_AND_ABANDON, () -> {
                    nativeHandle.close();
                    return Boolean.TRUE;
                });
        return Boolean.TRUE.equals(ack);
    }

    /**
     * 重发一次撤销（<b>自愈 / 重同步</b>，2026-09-29）。
     *
     * <p><b>为什么重发就能自愈</b>：客户端每次 {@code route()} / {@code unroute()} 下发的都是
     * <b>本地 Router 的全量快照</b>（{@code BrowserContextImpl.updateInterceptionPatterns()}），
     * 且单连接 FIFO 有序 ⇒ 只要<b>任意一次</b>下发拿到 ack，驱动侧拦截列表就重新与客户端一致。
     * 因此"未确证"的撤销只需<b>重发一次</b>即可修复状态分叉，<b>无需重建 Context</b>
     * （重建会破坏 feature/session 不变式 I-1：同一 sessionKey 不重建）。</p>
     *
     * <p>幂等：{@code unroute} 一个已被移除的 pattern 在客户端是本地空操作，但仍会触发全量快照下发
     * —— 这正是所需的"重同步"；{@code nativeHandle.close()} 亦幂等。</p>
     *
     * @return {@code true}=本次重发已确证（状态已重同步）；{@code false}=仍未收到 ack
     */
    public boolean retryUnroute() {
        Object ack = GuardedDriverCallRegistry.instance().guarded("unroute-retry:" + pattern,
                GuardedDriverCall.unrouteBoundMs(), GuardedDriverCall.OnTimeout.WARN_AND_ABANDON, () -> {
                    nativeHandle.close();
                    return Boolean.TRUE;
                });
        return Boolean.TRUE.equals(ack);
    }

    @Override
    public String toString() {
        return "PatternBinder{pattern='" + pattern + "', closed=" + closed.get()
                + ", degraded=" + degraded + '}';
    }
}
