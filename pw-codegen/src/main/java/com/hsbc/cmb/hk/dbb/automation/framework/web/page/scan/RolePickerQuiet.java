package com.hsbc.cmb.hk.dbb.automation.framework.web.page.scan;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicLong;

/**
 * codegen 面板「最佳努力」异常吞噬的<b>统一收口</b>（N-17，doc 21 LOW）。
 *
 * <p><b>要防的缺陷</b>：本模块主源码原有 <b>64 处</b>空 {@code catch (…) {}}。其中多数吞噬是<b>有意</b>的
 * —— 面板脚本注入 / 命令桥注册 / 状态读取在「页面已关闭」「脚本尚未注入」「导航竞态」等情况下失败属常态，
 * 不该打断整个拾取会话。但写成空块后是<b>零信号</b>：一旦遇到的是真实故障
 * （例如命令桥注册失败 → 用户看到"面板点了没反应"），既无日志也无计数，只能靠人肉复现排查。</p>
 *
 * <p>本类<b>不改变原语义</b>（仍然吞掉、仍然不打断会话），只补可观测性：</p>
 * <ul>
 *   <li>日志固定 <b>DEBUG</b>：默认级别下不制造噪音，排查时开 DEBUG 即有完整堆栈；</li>
 *   <li>累计<b>计数</b>：{@link #getSwallowedCount()} 可在套件末尾 / 排查时回答"到底吞了多少"，
 *       把"静默吞吐"变成可断言事实。</li>
 * </ul>
 *
 * @apiNote 框架内部能力（包级私有）：仅供 pw-codegen 内部"最佳努力"路径使用。
 */
final class RolePickerQuiet {

    private static final Logger log = LoggerFactory.getLogger(RolePickerQuiet.class);
    private static final AtomicLong SWALLOWED = new AtomicLong();

    private RolePickerQuiet() {
    }

    /**
     * 记录一次被有意吞噬的异常（保持「不打断会话」语义）。
     *
     * @param where 发生位置的可定位标识（建议 {@code 类名#方法名}，便于定位真凶）
     * @param t     被吞噬的异常（可为 {@code null}）
     */
    static void ignore(String where, Throwable t) {
        long cumulative = SWALLOWED.incrementAndGet();
        if (t == null) {
            log.debug("[picker][quiet] swallowed null throwable at {} (cumulative: {})", where, cumulative);
        } else {
            log.debug("[picker][quiet] swallowed exception at {} (cumulative: {}): {}",
                    where, cumulative, t.toString(), t);
        }
    }

    /** 被吞噬异常的累计次数（{@code 0} = 本次运行未发生任何吞噬）。 */
    static long getSwallowedCount() {
        return SWALLOWED.get();
    }

    /** 复位计数（供用例隔离）。 */
    static void reset() {
        SWALLOWED.set(0);
    }
}
