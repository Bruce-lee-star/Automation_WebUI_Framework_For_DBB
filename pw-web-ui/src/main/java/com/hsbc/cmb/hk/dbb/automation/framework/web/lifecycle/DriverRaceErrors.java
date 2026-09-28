package com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle;

import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.TimeoutError;

/**
 * Playwright Java「驱动侧竞态」错误分类器 —— 框架自愈策略的<b>唯一判据来源</b>。
 *
 * <h2>为什么需要分类，而不是"重试一切"</h2>
 * Playwright Java 的 {@code Connection} 是<b>一条共享连接</b>：消息在
 * {@code Connection.processOneMessage} 内被分发，而分发线程就是"此刻恰好在等这条连接结果的任意调用线程"。
 * 因此驱动内部的解析失败会<b>以该调用为宿主抛出</b>，与调用语义无关 —— 表现为"导航失败""evaluate 失败"，
 * 实则是别的对象出问题。
 *
 * <p>典型案例（本仓库实测，playwright 1.62.0）：
 * <pre>
 * PageImpl.navigate → Connection.sendMessage → ChannelOwner.runUntil
 *   → Connection.processOneMessage → Connection.dispatch
 *     → BrowserContextImpl.handleEvent(:853)            // response 分支
 *       → Connection.getExistingObject(:210)            // 句柄已被服务端回收
 *         → PlaywrightException: Object doesn't exist: response@125692…
 * </pre>
 * 浏览器侧其实<b>已完成</b>导航并被 302 重定向到 {@code /logon}（实测日志：报错后 20ms 出现
 * {@code [nav] main frame navigated -> …/home}、415ms 后 {@code -> …/logon}）。若不在最外层收口，
 * 该异常会被业务误判为"会话失效" → 删会话缓存 → 每轮完整重登（实测正是如此）。
 *
 * <p>而该类异常<b>无法</b>在业务回调内拦截：驱动在解析事件对象时就抛出（早于调用业务 lambda，
 * 见 {@code ApiCaptureLifecycle} 的既有说明），故只能由<b>最外层调用</b>识别并自愈。
 *
 * <h2>分类判据（必须"窄且稳定"）</h2>
 * 只承认<b>驱动自己产出</b>的两段稳定文案，其余一律 {@link Kind#NONE}：
 * <ol>
 *   <li>{@link Kind#OBJECT_LIFECYCLE_RACE}：{@code Object doesn't exist: <type>@<guid>}
 *       —— 唯一产出点是 {@code Connection.getExistingObject}（对象句柄生命周期竞态）。</li>
 *   <li>{@link Kind#INTERRUPTED_BY_ANOTHER_NAVIGATION}：{@code … is interrupted by another navigation …}
 *       —— 唯一产出点是驱动导航逻辑（上一次导航尚未收尾，含服务端 302 重定向链）。</li>
 * </ol>
 *
 * <p><b>明确排除</b>：{@link TimeoutError}（超时）即使文案命中标记也判为 {@link Kind#NONE} ——
 * 超时是<b>语义失败</b>，重试只会掩盖问题并翻倍耗时。此规则在<b>整条 cause 链</b>上生效。
 *
 * <h2>使用契约</h2>
 * 调用方（{@code PageNavigation}）必须满足：<b>至多重试一次</b>、重试前先让状态收敛、自愈成功记 WARN/INFO
 * （异常率可观测）、自愈失败仍抛原语义异常且原异常经 {@code addSuppressed} 保留（<b>绝不掩盖失败</b>）。
 *
 * @apiNote framework-internal：仅供框架内部"最外层调用收口"使用；业务代码不得依赖。
 */
public final class DriverRaceErrors {

    /** 驱动侧竞态类别。 */
    public enum Kind {
        /** 非驱动竞态（语义失败 / 超时 / 业务异常）—— <b>绝不自愈</b>。 */
        NONE,
        /** 上一次导航（典型：服务端 302 重定向链）尚未收尾，本次导航被驱动判定为"被打断"。 */
        INTERRUPTED_BY_ANOTHER_NAVIGATION,
        /** 连接上的对象句柄已被服务端回收，驱动解析事件对象时抛 {@code Object doesn't exist}。 */
        OBJECT_LIFECYCLE_RACE
    }

    /** {@code Connection.getExistingObject} 的唯一文案（对象句柄生命周期竞态）。 */
    private static final String MARKER_OBJECT_GONE = "Object doesn't exist";

    /** 驱动导航逻辑的唯一文案（导航被另一次导航打断）。 */
    private static final String MARKER_INTERRUPTED = "interrupted by another navigation";

    /** cause 链遍历深度上限（防御异常链自引用/超长链）。 */
    private static final int MAX_CAUSE_DEPTH = 8;

    private DriverRaceErrors() {
    }

    /**
     * 分类给定异常（含 cause 链）。
     *
     * @param error 待分类异常（可为 {@code null}）
     * @return 竞态类别；非驱动竞态/超时/空一律 {@link Kind#NONE}
     */
    public static Kind classify(Throwable error) {
        if (error == null) {
            return Kind.NONE;
        }
        Kind found = Kind.NONE;
        Throwable current = error;
        for (int depth = 0; current != null && depth < MAX_CAUSE_DEPTH; depth++) {
            //  超时优先否决：整条链上任一环节为 TimeoutError，即视为语义失败（不可自愈）。
            if (current instanceof TimeoutError) {
                return Kind.NONE;
            }
            Kind kind = classifyMessage(current);
            if (kind == Kind.OBJECT_LIFECYCLE_RACE) {
                //  对象生命周期竞态是最明确的驱动内部错误：一旦命中即可定论，无需继续遍历。
                return kind;
            }
            if (kind != Kind.NONE && found == Kind.NONE) {
                found = kind;
            }
            current = current.getCause();
        }
        return found;
    }

    /**
     * 是否为"可自愈的驱动竞态"。
     *
     * @param error 待判定异常（可为 {@code null}）
     * @return true 表示可在最外层调用处做有界自愈
     */
    public static boolean isSelfHealable(Throwable error) {
        return classify(error) != Kind.NONE;
    }

    /** 单条异常自身的文案分类（仅认 {@link PlaywrightException} 家族，避免误伤业务异常）。 */
    private static Kind classifyMessage(Throwable error) {
        if (!(error instanceof PlaywrightException)) {
            return Kind.NONE;
        }
        String message = error.getMessage();
        if (message == null) {
            return Kind.NONE;
        }
        if (message.contains(MARKER_OBJECT_GONE)) {
            return Kind.OBJECT_LIFECYCLE_RACE;
        }
        if (message.contains(MARKER_INTERRUPTED)) {
            return Kind.INTERRUPTED_BY_ANOTHER_NAVIGATION;
        }
        return Kind.NONE;
    }
}
