package com.hsbc.cmb.hk.dbb.automation.framework.route.util;

import com.microsoft.playwright.Page;
import com.microsoft.playwright.PlaywrightException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *  Playwright 连接层韧性工具。
 *
 * <p><b>背景</b>：{@code page.onResponse} 等全局监听器在极端并发 / 压测下，会令 Playwright 在事件分发层
 * （{@code BrowserContextImpl.handleEvent}）解析已被浏览器侧 GC 的 {@code response@} 对象，抛
 * {@code PlaywrightException: Object doesn't exist}。该异常发生在调用监听 lambda <b>之前</b>，lambda 内
 * {@code try/catch} 无法拦截，且会沿连接等待回灌、污染<b>同一连接上任何在途的 {@code page.evaluate}</b>。
 * 这类错误属<b>瞬时 churn 产物</b>（连接本身未断开），重试即可恢复，而非真正的故障。
 *
 * <p><b>职责</b>：对 {@code Page#evaluate} 等可能因上述 churn 瞬时失败的连接操作做界内重试，
 * 仅捕获含 {@code "Object doesn't exist"} 的 {@code PlaywrightException}（瞬时、可恢复），其余异常原样抛出，
 * 避免掩盖真实故障。作为防御纵深，与「高 churn 场景关闭被动捕获」互补。
 *
 * @apiNote framework-internal：框架内部工具，非公开 API。
 * <p><b>线程模型（刻意同步，不进 {@code AsyncPool}）</b>：本方法在<b>调用方线程</b>上同步执行
 * {@code page.evaluate} 并重试。Playwright 的 {@code Page} 须由<b>同一线程串行</b>驱动，若改投框架
 * {@code AsyncPool} 等线程池，会破坏其连接亲和模型、引入跨线程竞态；且重试本身即「重跑整段脚本」，
 * 本就不该在后台线程静默放大负载。重试间<b>不使用 {@code Thread.sleep}</b>（企业级约束：禁止阻塞线程），
 * 靠「立即重跑」重新错位 GC 时序即可恢复。
 */
public final class PlaywrightSafeOps {

    private static final Logger LOGGER = LoggerFactory.getLogger(PlaywrightSafeOps.class);

    /** 最大重试次数（含首次共 3 次）。
     *  <p>克制上限：被保护的 {@code page.evaluate} 多为重负载压测脚本（百并发 fetch），重试会重跑整段脚本、
     *  放大连接 churn 与负载；过高重试次数反而可能把浏览器连接拖死（{@code Cannot find command to respond}）。
     *  3 次用于容忍高负载下连续两次仍撞上 churn 的情况（实测 2 次偶发不足），仍属克制。 */
    private static final int MAX_ATTEMPTS = 3;

    private PlaywrightSafeOps() {
    }

    /**
     * 对 {@code page.evaluate(expression, arg)} 做连接层韧性包装。
     *
     * <p>仅当抛出含 {@code "Object doesn't exist"} 的 {@code PlaywrightException}（瞬时 churn）时重试，
     * 其它异常原样上抛。返回值与 {@code Page#evaluate} 一致。
     *
     * @param page       目标页面
     * @param expression 求值脚本
     * @param arg        脚本入参（可为 null）
     * @return 脚本求值结果
     */
    public static Object safeEvaluate(Page page, String expression, Object arg) {
        PlaywrightException last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                return page.evaluate(expression, arg);
            } catch (PlaywrightException e) {
                if (isObjectGone(e)) {
                    last = e;
                    LOGGER.debug("[PlaywrightSafeOps] safeEvaluate attempt {}/{} hit transient object-gone churn, retrying",
                            attempt, MAX_ATTEMPTS);
                    //  无退避：瞬时 churn 为 GC 时序竞争，立即重跑即重新错位时序、通常下一次成功；
                    //  绝不使用 Thread.sleep（企业级约束：禁止阻塞线程），重试本身即提供时序偏移。
                    continue;
                }
                throw e;
            }
        }
        throw last;
    }

    /** 判定是否为「对象已失效」类瞬时错误（连接 churn 产物，可重试恢复）。 */
    private static boolean isObjectGone(PlaywrightException e) {
        String msg = e.getMessage();
        if (msg == null) {
            return false;
        }
        // 同一类瞬时 churn 的不同措辞：对象/父子关联在浏览器侧被 GC 后，Playwright 事件分发层解析失效引用时
        // 会抛 "Object doesn't exist: response@…" 或 "Cannot find parent object request@… to create route@…"。
        return msg.contains("Object doesn't exist") || msg.contains("Cannot find parent object");
    }
}
