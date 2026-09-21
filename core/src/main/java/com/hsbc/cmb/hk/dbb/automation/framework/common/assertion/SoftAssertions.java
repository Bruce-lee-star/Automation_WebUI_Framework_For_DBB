package com.hsbc.cmb.hk.dbb.automation.framework.common.assertion;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

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
 * <p><b>跨线程传播（评审 F-13 / P1-5）</b>：纯 {@code ThreadLocal} 会让<b>异步任务</b>里记录的失败
 * 落在工作线程自己的收集器上 —— 无人调用其 {@code assertAll()}，失败<b>永不上报</b>（静默假绿）。
 * 现在收集器可<b>整体交接</b>：异步投递点（{@code AsyncPool}）在提交线程侧
 * {@link #captureCollector()}，工作线程侧 {@link #bindCollector(Collector)} /
 * {@link #unbindCollector(Collector)}，使工作线程写入的失败与父线程<b>共用同一份收集器</b>，
 * 从而随父线程的场景末 {@code assertAll()} 一并判红（与 C-5 的 MDC 传播同一套写法）。
 *
 * <p><b>不得静默的守卫</b>：场景收尾会 {@code close()} 收集器；此后再有记录落入该收集器
 * （典型：异步任务晚于 {@code assertAll()} 才返回），会打 WARN 明示"该失败不会被上报"，
 * 而不是无声丢弃。
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

    private static final Logger LOGGER = LoggerFactory.getLogger(SoftAssertions.class);

    /** per-thread 失败收集器。 */
    private static final ThreadLocal<Collector> FAILURES = ThreadLocal.withInitial(Collector::new);

    private SoftAssertions() {
        // 纯静态门面，禁止实例化
    }

    /**
     * 失败收集器：可被<b>父线程与其异步任务共享</b>（F-13），故写入必须并发安全。
     *
     * <p>线程安全模型：{@link CopyOnWriteArrayList} —— 写入（记录失败）罕见、读取（场景末聚合 / 渲染）
     * 可能与其他工作线程的写入并发；父线程与 N 个工作线程同时写入也不丢条目。</p>
     *
     * <p><b>生命周期</b>：由「拥有它的线程」在场景收尾 {@link #clearForCurrentThread()} 时 {@code close()}
     * 并摘除 ThreadLocal；工作线程只 bind/unbind，<b>绝不</b> close（否则会误关父线程的收集器）。</p>
     *
     * @apiNote framework-internal：跨线程交接入口仅供异步投递点（{@code AsyncPool}）使用，
     *          业务步骤不应直接操作。
     */
    public static final class Collector {

        private final List<AssertionFailure> failures = new CopyOnWriteArrayList<>();

        /** 是否已随场景收尾「关闭」：关闭后落入的记录无法再被上报，必须显式告警（不得静默假绿）。 */
        private volatile boolean closed;

        private void add(AssertionFailure failure) {
            if (closed) {
                LOGGER.warn("[SoftAssertions] soft assertion recorded AFTER scenario end — this failure will NOT be "
                        + "reported (典型的异步任务晚于 assertAll() 返回). failure: {}", failure.describe());
            }
            failures.add(failure);
        }

        private boolean isEmpty() {
            return failures.isEmpty();
        }

        private List<AssertionFailure> snapshot() {
            return List.copyOf(failures);
        }

        private void close() {
            closed = true;
        }
    }

    // ═══════════════════════════════════════════════════════════
    // 跨线程交接（F-13：供 AsyncPool 等异步投递点使用）
    // ═══════════════════════════════════════════════════════════

    /**
     * 取出当前线程的收集器，交给工作线程共享（在<b>提交线程</b>侧调用）。
     *
     * @return 当前线程的收集器（不存在则创建）；<b>不可为 null</b>，工作线程据此 bind 后可写入父线程收集器
     * @apiNote framework-internal：仅供异步投递点使用。
     */
    public static Collector captureCollector() {
        return FAILURES.get();
    }

    /**
     * 在工作线程绑定父线程的收集器（在<b>工作线程</b>侧调用），使本线程记录的失败对父线程可见。
     *
     * <p>必须在任务 {@code finally} 中调用 {@link #unbindCollector(Collector)} 复位：线程池会复用线程，
     * 残留绑定会让<b>下一个用例</b>的任务把失败写进上一个用例的收集器（跨用例串扰）。</p>
     *
     * @param collector {@link #captureCollector()} 的返回值（不得为 null）
     * @return 绑定前的收集器（供 {@link #unbindCollector(Collector)} 恢复；无绑定则为 null）
     * @apiNote framework-internal：仅供异步投递点使用。
     */
    public static Collector bindCollector(Collector collector) {
        Objects.requireNonNull(collector, "collector must not be null (use captureCollector() on the submitting thread)");
        Collector previous = FAILURES.get();
        FAILURES.set(collector);
        return previous;
    }

    /**
     * 复位工作线程的收集器绑定（在任务 {@code finally} 中调用）。
     *
     * @param previous {@link #bindCollector(Collector)} 的返回值；null 表示原本无绑定（摘除 ThreadLocal 条目）
     * @apiNote framework-internal：仅供异步投递点使用。
     */
    public static void unbindCollector(Collector previous) {
        if (previous == null) {
            FAILURES.remove();
        } else {
            FAILURES.set(previous);
        }
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
        return Collections.unmodifiableList(new ArrayList<>(FAILURES.get().snapshot()));
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
        return render(failures);
    }

    /**
     * 场景末统一上报：有失败则<b>清空后抛聚合错误</b>，无失败则仅清空。
     *
     * <p>先取快照再清空，保证即使抛出后调用方未再清理，也不会把失败带到下一场景。
     * 清空会同时 {@code close()} 收集器：此后任何（逾期）异步任务再记录失败都会打 WARN，
     * 明示"未被上报"而非静默丢弃（F-13 守卫）。</p>
     */
    public static void assertAll() {
        List<AssertionFailure> snapshot = FAILURES.get().snapshot();
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
     * <p>同时把被摘除的收集器标记为「已关闭」，使逾期写入可见（见 {@link #assertAll()}）。
     */
    public static void clearForCurrentThread() {
        FAILURES.get().close();
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
