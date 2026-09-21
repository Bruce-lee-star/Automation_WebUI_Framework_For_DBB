package com.hsbc.cmb.hk.dbb.automation.framework.common.context;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 多语言（NLS）「当前语言」状态收口 —— 从 web 的 {@code NLSUtils} 下沉，解耦 {@code route → web} 依赖。
 *
 * <p><b>作用域（CORE-LANG 修复，2026-09-20）</b>：语言状态为<b>纯线程级（{@link ThreadLocal}）</b>，
 * 无进程级全局值。这样并行（多场景多线程）下，各场景/线程的语言状态天然隔离，互不串语言；
 * 同一线程内最新写入优先生效（与历史单线程行为一致）。{@code reset} 清当前线程。
 *
 * <p><b>为何不用进程级全局桥</b>：旧实现用 {@code globalLang + 单调序号} 让「异步回调线程设置的语言对主线程可见」，
 * 但该桥在并行下会被<b>跨场景</b>读取（场景 A 读到场景 B 的语言）→ 串语言。经核查，生产代码并无在异步回调线程
 * 调 {@code setLanguage} 的路径（{@code route} 仅调 {@link #reset()}）；而跨线程共享可变语言状态本身即与并行
 * 隔离目标冲突。故改为线程级隔离。同一线程串行多场景时，由每场景的业务步骤重新 {@link #setLanguage} 或
 * {@link #reset()} 保证不串（与旧实现一致）。
 *
 * @apiNote 框架内部使用；业务代码不应直接依赖。
 */
public final class LanguageState {

    private static final Logger log = LoggerFactory.getLogger(LanguageState.class);

    /** 当前语言 — 纯线程级，无进程级全局值（CORE-LANG 修复）。 */
    private static final ThreadLocal<String> LANG = new ThreadLocal<>();

    private LanguageState() {
    }

    /**
     * 语言切换时调用，告知框架当前显示的语言。
     *
     * @param lang 语言标识（取自对应 nls 文件第一层语言 Key，如 "en"、"zh"）；{@code null} 等价于清除
     */
    public static void setLanguage(String lang) {
        if (lang == null) {
            LANG.remove();
        } else {
            LANG.set(lang);
        }
        log.info("[NLS] language switched to: {}", lang);
    }

    /**
     * 取当前语言（仅读当前线程，无跨线程/跨用例泄漏）。
     *
     * @return 当前语言标识；未设置返回 {@code null}
     */
    public static String getLanguage() {
        return LANG.get();
    }

    /**
     * 清理语言状态，避免污染后续用例。仅清当前线程。
     */
    public static void reset() {
        LANG.remove();
    }
}
