package com.hsbc.cmb.hk.dbb.automation.framework.common.assertion;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 统一<b>软断言</b>门面（collect → scenario 末统一上报）—— D3-2。
 *
 * <p><b>与硬断言的差异</b>：{@link FrameworkAssertions} 首次失败即抛，后续校验不再执行，
 * 一次运行只能暴露一个问题；软断言把失败<b>收集</b>起来继续执行，在场景结束时
 * （由 Serenity 监听器调用 {@link #assertAll()}）一次性抛出聚合错误 ——
 * 一个用例能同时暴露全部不符项，减少"改一个跑一次"的返工。
 *
 * <p><b>线程模型（关键）</b>：失败收集器是 {@code ThreadLocal}，
 * 因此并行 / 并发 scenario 各自独立、互不污染。但也正因如此：
 * <ul>
 *   <li>线程池复用线程时必须清理，否则上一用例的失败会被算到下一用例头上；</li>
 *   <li>{@link #clearForCurrentThread()} 用 {@code ThreadLocal.remove()}（而非仅 clear 列表），
 *       彻底摘除条目，杜绝弱引用 / 列表残留。</li>
 * </ul>
 * 场景收尾由框架监听器统一调用 {@code clearForCurrentThread()}。
 *
 * <p>用法：
 * <pre>{@code
 * SoftAssertions.assertEquals(200, call.statusCode(), "status");
 * SoftAssertions.assertContains(body, "token", "has token");
 * // ...更多校验...
 * SoftAssertions.assertAll();   // 场景末：有失败则抛聚合错误
 * }</pre>
 *
 * <p>本类<b>不依赖任何测试引擎</b>（纯 JDK），Serenity 侧的上报由 web 监听器完成。
 */
public final class SoftAssertions {

    /** per-thread 失败收集器。 */
    private static final ThreadLocal<List<AssertionFailure>> FAILURES =
            ThreadLocal.withInitial(ArrayList::new);

    private SoftAssertions() {
        // 纯静态门面，禁止实例化
    }

    // ═══════════════════════════════════════════════════════════
    // 收集（不抛异常）
    // ═══════════════════════════════════════════════════════════

    /** 相等（null 安全）。 */
    public static void assertEquals(Object expected, Object actual, String message) {
        if (!Objects.equals(expected, actual)) {
            record(message, expected, actual);
        }
    }

    /** 非空。 */
    public static void assertNotNull(Object actual, String message) {
        if (actual == null) {
            record(message, "non-null", null);
        }
    }

    /** 条件为真。 */
    public static void assertTrue(boolean condition, String message) {
        if (!condition) {
            record(message, Boolean.TRUE, Boolean.FALSE);
        }
    }

    /** 字符串包含（null 安全）。 */
    public static void assertContains(String haystack, String needle, String message) {
        if (haystack == null || needle == null || !haystack.contains(needle)) {
            record(message, needle, haystack);
        }
    }

    /** 无条件记一条失败。 */
    public static void fail(String message) {
        record(message, null, null);
    }

    // ═══════════════════════════════════════════════════════════
    // 查询 / 上报
    // ═══════════════════════════════════════════════════════════

    /** 当前线程是否存在已收集的失败。 */
    public static boolean hasFailures() {
        return !FAILURES.get().isEmpty();
    }

    /** 当前线程已收集的失败（不可变副本）。 */
    public static List<AssertionFailure> failuresForCurrentThread() {
        return Collections.unmodifiableList(new ArrayList<>(FAILURES.get()));
    }

    /**
     * 把当前线程的失败渲染为可读报告（<b>不清空、不抛出</b>）。
     * <p>供 Serenity 报告与日志复用，保证"报告里看到的"与"抛出的"是同一份内容。
     */
    public static String renderFailures() {
        List<AssertionFailure> failures = failuresForCurrentThread();
        if (failures.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Soft assertion failures (").append(failures.size()).append(")");
        for (int i = 0; i < failures.size(); i++) {
            sb.append("\n  ").append(i + 1).append(") ").append(failures.get(i).describe());
        }
        return sb.toString();
    }

    /**
     * 场景末统一上报：有失败则<b>清空后抛聚合错误</b>，无失败则仅清空。
     *
     * <p>先取快照再清空，保证即使抛出后调用方未再清理，也不会把失败带到下一场景。
     */
    public static void assertAll() {
        List<AssertionFailure> snapshot = failuresForCurrentThread();
        clearForCurrentThread();
        if (snapshot.isEmpty()) {
            return;
        }
        throw new FrameworkAssertionError(render(snapshot));
    }

    /**
     * 清空当前线程的失败收集器。
     * <p>用 {@code remove()} 而非 {@code clear()}：彻底摘除 ThreadLocal 条目，
     * 避免线程池复用时残留空列表对象与跨用例串扰。
     */
    public static void clearForCurrentThread() {
        FAILURES.remove();
    }

    // ═══════════════════════════════════════════════════════════
    // 内部
    // ═══════════════════════════════════════════════════════════

    private static void record(String message, Object expected, Object actual) {
        FAILURES.get().add(new AssertionFailure(
                message == null || message.trim().isEmpty() ? "assertion failed" : message,
                expected, actual));
    }

    private static String render(List<AssertionFailure> failures) {
        StringBuilder sb = new StringBuilder();
        sb.append("Soft assertion failures (").append(failures.size()).append(")");
        for (int i = 0; i < failures.size(); i++) {
            sb.append("\n  ").append(i + 1).append(") ").append(failures.get(i).describe());
        }
        return sb.toString();
    }
}
