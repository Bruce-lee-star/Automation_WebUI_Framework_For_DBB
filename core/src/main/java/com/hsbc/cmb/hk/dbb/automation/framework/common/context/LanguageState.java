package com.hsbc.cmb.hk.dbb.automation.framework.common.context;

import com.hsbc.cmb.hk.dbb.automation.framework.core.context.ContextKey;
import com.hsbc.cmb.hk.dbb.automation.framework.core.context.TestContextHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 多语言（NLS）「当前语言」全局/线程级状态收口 —— 从 web 的 {@code NLSUtils} 下沉，
 * 用于解耦 {@code route → web} 依赖（route 的 {@code ApiCaptureContext.resetCurrent()} 需重置语言状态，
 * 不应牵动 web）。
 *
 * <p>本类<b>只</b>承载语言状态与 {@code reset}，不含 nls 文件加载/绑定（那些是 web/UI 可访问名解析范畴，
 * 仍留在 {@code framework.web.utils.NLSUtils}，后者经本类委托）。二者语义逐字一致，行为零变更。
 *
 * <p><b>双轨 + 全局单调序号</b>：原 {@code NLSUtils} 纯 {@code ThreadLocal} 会在 onResponse 回调线程设置后
 * 主线程读不到；改为「全局 + 线程级覆盖」双轨，并以全局单调序号取较新写入，既保留并发隔离，
 * 又保证跨线程最新设置不被陈旧线程副本遮蔽（详见原 {@code NLSUtils} 注释）。
 *
 * @apiNote 框架内部使用；业务代码不应直接依赖。
 */
public final class LanguageState {

    private static final Logger log = LoggerFactory.getLogger(LanguageState.class);

    /** 当前语言 — 进程级全局值（带写入序号）。 */
    private static final AtomicReference<LangValue> globalLang = new AtomicReference<>();

    /** 线程级语言覆盖（并发多场景隔离），走 {@link TestContextHolder}（per-thread 等价）。 */
    private static final ContextKey<LangValue> LANG_OVERRIDE_KEY =
            ContextKey.of("nls.langOverride", LangValue.class);

    /** 全局单调写入序号：判定「哪个写入更新」。 */
    private static final AtomicLong LANG_WRITE_SEQ = new AtomicLong();

    /** 带写入序号的语言值。 */
    private record LangValue(String lang, long seq) {}

    private LanguageState() {
    }

    /**
     * 语言切换时调用，告知框架当前显示的语言。
     *
     * @param lang 语言标识（取自对应 nls 文件第一层语言 Key，如 "en"、"zh"）；{@code null} 等价于清除
     */
    public static void setLanguage(String lang) {
        if (lang == null) {
            // 传入 null 等价于清除：同样写入「空值标记」使其它线程的副本失效
            TestContextHolder.get().remove(LANG_OVERRIDE_KEY);
            globalLang.set(clearedMarker());
        } else {
            //  同时写入全局值与当前线程副本，且两者共享同一个序号：
            //   单线程场景行为不变；跨线程场景（Monitor 回调线程设置、主线程读取）
            //   由 getLanguage() 的「序号取新」判定保证可见。
            LangValue v = new LangValue(lang, LANG_WRITE_SEQ.incrementAndGet());
            globalLang.set(v);
            TestContextHolder.get().set(LANG_OVERRIDE_KEY, v);
        }
        log.info("[NLS] language switched to: {}", lang);
    }

    /**
     * 取当前语言（取「写入更新的那个」，而非无条件优先线程副本，避免陈旧副本遮蔽跨线程新值）。
     *
     * @return 当前语言标识；未设置返回 {@code null}
     */
    public static String getLanguage() {
        LangValue override = TestContextHolder.get().get(LANG_OVERRIDE_KEY);
        LangValue global = globalLang.get();
        if (override == null) return global == null ? null : global.lang;
        if (global == null) return override.lang;
        return global.seq() >= override.seq() ? global.lang() : override.lang();
    }

    /**
     * 清理语言状态，避免污染后续用例。
     * <p>同时清线程副本与全局值（仅清线程副本不够：全局值会继续被其它线程读到）。
     * 全局侧写入「空值标记」并占用更新的序号，使任何线程残留的陈旧副本均因序号更旧而失效。
     */
    public static void reset() {
        TestContextHolder.get().remove(LANG_OVERRIDE_KEY);
        globalLang.set(clearedMarker());
    }

    /** 构造「较新序号的空值标记」：用于清除/重置场景，使其它线程的陈旧线程副本因序号更旧而被忽略。 */
    private static LangValue clearedMarker() {
        return new LangValue(null, LANG_WRITE_SEQ.incrementAndGet());
    }
}
