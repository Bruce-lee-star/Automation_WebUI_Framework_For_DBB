package com.hsbc.cmb.hk.dbb.automation.framework.web.route.core;

import java.util.Comparator;

/**
 * 路由处理类型枚举 —— 区分 Mock / 修改请求 / 高延迟 / 监控四种拦截能力。
 *
 * <p>⭐ 设计文档《企业级 API 拦截框架设计》<b>Phase 1</b>：
 * <b>优先级与 Terminal 语义收敛到本枚举，作为全框架唯一来源</b>。
 *
 * <p>背景：历史上优先级被重复定义在多处 ——
 * {@code RouteRegistry.priorityOf()}（MOCK=4 &gt; MODIFY=3 &gt; <b>DELAY=2 &gt; MONITOR=1</b>）
 * 与 {@code RouteEngine#dispatchRoute} 的 if-else 分支
 * （MOCK &gt; MODIFY &gt; <b>MONITOR &gt; DELAY</b>）。两处对
 * DELAY / MONITOR 的相对顺序<b>互相矛盾</b>，属典型一致性隐患；
 * 且因"能力位与类型解耦"的演进，{@code priorityOf()} 已部分失去意义。
 * 收敛后由本枚举单一定义，其余各处一律引用。
 *
 * <p><b>⭐ 两套顺序必须区分开</b>（历史混淆的根源，此处彻底拆开）：
 *
 * <table border="1" cellpadding="4">
 *   <caption>四类能力矩阵</caption>
 *   <tr><th>Type</th><th>选择优先级<br/>{@link #getPriority()}</th><th>执行时序<br/>{@link #getExecutionOrder()}</th>
 *       <th>Terminal</th><th>跨层合并规则（Page vs Context）</th></tr>
 *   <tr><td>MOCK</td><td>100（最先被选中）</td><td>3（最后执行，短路）</td><td>是</td><td>终结 + Page 胜出，不可被降级</td></tr>
 *   <tr><td>MODIFY</td><td>200</td><td>2（改请求后放行）</td><td>否</td><td>putAll 合并字段，同字段 Page 胜出</td></tr>
 *   <tr><td>DELAY</td><td>300</td><td>1（最先计时）</td><td>否</td><td>取 max(pageDelay, contextDelay)</td></tr>
 *   <tr><td>MONITOR</td><td>999（最后才轮到）</td><td>4（观察，与其它动作并存）</td><td>否</td><td>能力位 OR：任一层开启即生效</td></tr>
 * </table>
 *
 * <p><b>① 选择优先级（{@link #getPriority()}）</b>：数值越小越<b>先被选中</b>，
 * 决定一次请求由哪个 Handler 执行动作。MOCK 最小是因为它是唯一 terminal ——
 * 一旦命中立即短路，MODIFY / DELAY / MONITOR 都不再作为「动作」执行。
 *
 * <p><b>② 执行时序（{@link #getExecutionOrder()}）</b>：一次请求内部各动作的<b>实际发生顺序</b>。
 * 与选择优先级<b>方向相反</b>是正常的：DELAY 先计时 → MODIFY 改请求 → MOCK 短路。
 *
 * <p><b>MONITOR 的特殊性</b>：它既不是"最先选中"也不是"最先执行"，而是<b>与其它动作并存</b>的观察维度：
 * <ul>
 *   <li>叠加 MODIFY → 由 {@code ModifyHandler} 在拿到真实响应后调用 {@code assertAndRecord} 采集；</li>
 *   <li>叠加 DELAY → DELAY 先选中并放行，随后由 {@code MonitorHandler.handle(route, rule, delayMs)} 在事件线程采集延迟后的真实响应；</li>
 *   <li>MOCK 短路 → 不采集（MOCK 不产生真实网络响应，无响应可观察）。</li>
 * </ul>
 * 因此在 {@code ApiCaptureContext} 中，四种能力是<b>四个并列维度</b>而非互斥枚举：
 * 一次请求可同时产生 DELAY / MODIFY / MONITOR 三条记录。
 */
public enum RouteHandleType {

    /**
     * 直接 Mock 返回响应（短路 Short-circuit）。
     * 拦截请求，直接返回自定义响应。
     *
     * <p><b>Terminal</b>：执行后立即终止责任链，后续 MODIFY / DELAY 不再执行。
     */
    MOCK(100, 3, true),

    /**
     * 修改请求头/请求体（中间人 MITM）。
     * 拦截请求，修改后继续发送至真实网络；非 Terminal。
     */
    MODIFY(200, 2, false),

    /**
     * 高延迟模拟（Throttle）。
     * 拦截请求，等待指定毫秒后放行（模拟高延迟网络）；非 Terminal。
     */
    DELAY(300, 1, false),

    /**
     * 仅监控，不修改请求响应（观察者 Observer）。
     * 放行请求后读取真实响应做断言与落库。
     *
     * <p>⭐ <b>MONITOR 是观察维度，不是动作分支</b>：它选择优先级最低（999），
     * 但会<b>叠加</b>在被选中的动作之上一起生效 ——
     * <ul>
     *   <li>叠加 MODIFY：{@code ModifyHandler} 拿到真实响应后调 {@code assertAndRecord}；</li>
     *   <li>叠加 DELAY：DELAY 放行后由 {@code MonitorHandler.handle(route, rule, delayMs)} 在事件线程采集延迟后的真实响应；</li>
     *   <li>单独存在：{@code MonitorHandler} 用 {@code page.waitForResponse} 同步等待真实响应。</li>
     * </ul>
     * 唯一失效场景是 <b>MOCK 短路</b>：MOCK 不发真实请求，无响应可供观察。
     */
    MONITOR(999, 4, false);

    private final int priority;
    private final int executionOrder;
    private final boolean terminal;

    RouteHandleType(int priority, int executionOrder, boolean terminal) {
        this.priority = priority;
        this.executionOrder = executionOrder;
        this.terminal = terminal;
    }

    /**
     * ⭐ ① <b>选择优先级</b>：数值越小越<b>先被选中</b>执行动作
     * （MOCK=100 最先，MONITOR=999 最后）。
     *
     * <p>这是 {@code InterceptorChain} 的排序依据，决定一次请求由哪个 Handler 执行。
     * MOCK 最小是因为它是唯一 terminal —— 命中即短路。
     *
     * @return 选择优先级
     */
    public int getPriority() {
        return priority;
    }

    /**
     * ⭐ ② <b>执行时序</b>：一次请求内部各动作的<b>实际发生顺序</b>，数值越小越先发生。
     *
     * <pre>
     * DELAY(1)  → 先计时并等待
     * MODIFY(2) → 改请求后放行
     * MOCK(3)   → 短路 fulfill（terminal）
     * MONITOR(4)→ 观察（与上面并存，不单独占用时序）
     * </pre>
     *
     * <p>与 {@link #getPriority()} 方向相反是正常的：MOCK 最先被<b>选中</b>，但最后<b>发生</b>。
     *
     * @return 执行时序序号
     */
    public int getExecutionOrder() {
        return executionOrder;
    }

    /**
     * 是否为短路类型：执行后终止责任链，后续规则不再执行。
     *
     * @return 仅 MOCK 返回 true
     */
    public boolean isTerminal() {
        return terminal;
    }

    /**
     * 责任链排序比较器：按 {@link #getPriority()} 升序（数值小者<b>先被选中</b>）。
     *
     * @return 类型比较器
     */
    public static Comparator<RouteHandleType> byPriority() {
        return Comparator.comparingInt(RouteHandleType::getPriority);
    }

    /**
     * 执行时序比较器：按 {@link #getExecutionOrder()} 升序（数值小者<b>先发生</b>）。
     *
     * <p>即 {@code DELAY → MODIFY → MOCK → MONITOR}，与 {@link #byPriority()} 方向相反。
     *
     * @return 执行时序比较器
     */
    public static Comparator<RouteHandleType> byExecutionOrder() {
        return Comparator.comparingInt(RouteHandleType::getExecutionOrder);
    }
}
