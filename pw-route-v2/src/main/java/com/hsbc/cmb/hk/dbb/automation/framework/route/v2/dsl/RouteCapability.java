package com.hsbc.cmb.hk.dbb.automation.framework.route.v2.dsl;

import java.util.Comparator;

/**
 * 路由能力（对一次被拦截请求采取的行为）。
 *
 * <p>枚举是规则语义的单一事实来源：新模块只有四种能力，每种能力的执行路径
 * 在 {@code RouteDispatcher} 中对应一条确定的无竞态执行链。
 *
 * <p><b>优先级模型（参照 V1 {@code RouteHandleType}，单规则多能力位收敛于此）</b>：
 * 同 endpoint 可同时叠加 MONITOR / MOCK / MODIFY_REQUEST / DELAY 四种能力，
 * 一次请求在<b>单 handler 内</b>按统一时序编排执行，而非注册多条独立 handler。
 * 本枚举为优先级裁决的<b>唯一来源</b>，其余各处一律引用，消除历史上优先级重复定义
 * （MOCK&gt;MODIFY&gt;DELAY&gt;MONITOR 与 MOCK&gt;MODIFY&gt;MONITOR&gt;DELAY 互相矛盾）的隐患。
 *
 * <p><b>两套顺序必须区分开</b>：
 * <ul>
 *   <li>{@link #selectionPriority()}（选择优先级）：数值越小越<b>先被选中</b>执行动作。
 *       MOCK 最小，因为它是唯一 terminal —— 命中即短路，MODIFY / MONITOR / DELAY 不再作为「动作」执行；
 *       与 {@link #executionOrder()} 方向相反是正常的。</li>
 *   <li>{@link #executionOrder()}（执行时序）：一次请求内部各能力的<b>实际发生顺序</b>，
 *       恒为 {@code DELAY(1) → MODIFY(2) → MOCK(3) → MONITOR(4)}：
 *       先计时延迟 → 改请求后放行 → MOCK 短路 fulfill（否则 resume 真实网络）→ MONITOR 观察（与上面并存）。</li>
 * </ul>
 */
public enum RouteCapability {

    /**
     * 观测：记录请求快照并放行（fail-open，绝不影响业务请求）。
     *
     * <p><b>MONITOR 是观察维度，不是动作分支</b>：它选择优先级位于 MOCK/MODIFY 之后、DELAY 之前
     * （selectionPriority=300），但会<b>叠加</b>在被选中的动作之上一起生效（与 DELAY/MODIFY 并存，
     * 对真实响应断言；唯一失效场景是 MOCK 短路——MOCK 不发真实请求，无响应可观察）。
     */
    MONITOR(300, 4, false),

    /** 拦截并伪造响应：静态伪造（直接 fulfill）或拦截真实响应（IO 线程 fetch 后 fulfill）。唯一 terminal。 */
    MOCK(100, 3, true),

    /** 修改请求后放行：增删改请求头 / 方法 / 体（经 resume 的 overrides 语义）。非 terminal。 */
    MODIFY_REQUEST(200, 2, false),

    /** 延迟放行：拦截请求，到点后 resume（fail-open，超时后立即放行）。非 terminal。 */
    DELAY(400, 1, false);

    /**
     * ① 选择优先级：数值越小越先被<b>选中</b>执行动作
     * （MOCK=100 最先；MONITOR=300 在 MODIFY 之后、DELAY 之前；DELAY=400 最后）。
     */
    private final int selectionPriority;

    /**
     * ② 执行时序：一次请求内部各能力的实际发生顺序，数值越小越先发生
     * （DELAY=1 → MODIFY=2 → MOCK=3 → MONITOR=4）。
     */
    private final int executionOrder;

    /** 是否为短路类型：执行后终止责任链，后续能力不再执行。仅 MOCK 为 true。 */
    private final boolean terminal;

    RouteCapability(int selectionPriority, int executionOrder, boolean terminal) {
        this.selectionPriority = selectionPriority;
        this.executionOrder = executionOrder;
        this.terminal = terminal;
    }

    /** ① 选择优先级：数值越小越先被选中执行动作。 */
    public int selectionPriority() {
        return selectionPriority;
    }

    /** ② 执行时序：数值越小越先发生（DELAY→MODIFY→MOCK→MONITOR）。 */
    public int executionOrder() {
        return executionOrder;
    }

    /** 是否为短路类型（执行后终止链）。仅 MOCK 返回 true。 */
    public boolean isTerminal() {
        return terminal;
    }

    /** 责任链排序比较器：按 {@link #selectionPriority()} 升序（数值小者先被选中）。 */
    public static Comparator<RouteCapability> bySelectionPriority() {
        return Comparator.comparingInt(RouteCapability::selectionPriority);
    }

    /** 执行时序比较器：按 {@link #executionOrder()} 升序（数值小者先发生）。 */
    public static Comparator<RouteCapability> byExecutionOrder() {
        return Comparator.comparingInt(RouteCapability::executionOrder);
    }
}
