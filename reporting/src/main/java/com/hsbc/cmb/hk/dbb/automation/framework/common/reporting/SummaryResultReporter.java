package com.hsbc.cmb.hk.dbb.automation.framework.common.reporting;

import com.hsbc.cmb.hk.dbb.automation.framework.common.config.FrameworkFlags;
import com.hsbc.cmb.hk.dbb.automation.framework.common.result.ResultReporter;
import com.hsbc.cmb.hk.dbb.automation.framework.common.result.ResultReporters;
import com.hsbc.cmb.hk.dbb.automation.framework.common.result.StepResult;
import com.hsbc.cmb.hk.dbb.automation.framework.common.result.TestResult;
import com.hsbc.cmb.hk.dbb.automation.framework.core.lifecycle.ShutdownCoordinator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * 基于 D4-2 结果端口的<b>汇总报告生成器</b>。
 *
 * <p>它自己不产生结果，只<b>消费</b> {@link ResultReporter} 端口广播出来的框架模型
 * （{@link StepResult} / {@link TestResult}），因此与任何报告引擎无关 ——
 * 这正是 D4-2「换报告引擎不需重写」的直接证明：换引擎只需替换/新增一个
 * {@code ResultReporter} 实现，本类与框架核心都不用动。
 *
 * <p><b>产出</b>（默认 {@code target/framework-summary.{json,txt}}）：
 * 场景/步骤计数、按结果分布、总耗时、最慢场景 Top N、失败清单。
 * 文本版便于人看，JSON 版便于 CI 采集。
 *
 * <p><b>落盘时机</b>：登记在 {@link ShutdownCoordinator}（order 1000，晚于所有业务关闭任务），
 * JVM 退出时统一写出；也可调用 {@link #writeNow()} 即时生成。
 *
 * <p><b>开关</b>：{@code framework.summary.report.enabled}（默认 true）；
 * 目录由 {@code framework.summary.report.dir} 指定，默认 {@code target}。
 *
 * <p>线程安全：结果可能来自多个执行线程，采集用无锁队列。
 */
public final class SummaryResultReporter implements ResultReporter {

    private static final Logger logger = LoggerFactory.getLogger(SummaryResultReporter.class);

    /** 开关（默认开启）。 */
    public static final String ENABLED_KEY = "framework.summary.report.enabled";

    /** 输出目录配置键（默认 {@code target}）。 */
    public static final String DIR_KEY = "framework.summary.report.dir";

    private static final String DEFAULT_DIR = "target";
    private static final int TOP_SLOWEST = 10;
    private static final int MAX_LISTED_FAILURES = 100;
    private static final DateTimeFormatter TS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 场景记录（不可变小载体）。 */
    private static final class ScenarioRecord {
        final String name;
        final TestResult result;
        final long durationMs;

        ScenarioRecord(String name, TestResult result, long durationMs) {
            this.name = name == null ? "(unknown)" : name;
            this.result = result == null ? TestResult.UNKNOWN : result;
            this.durationMs = durationMs;
        }
    }

    private static final SummaryResultReporter INSTANCE = new SummaryResultReporter();

    /** 幂等装载保护（install() 可能被多处触发）。 */
    private static final java.util.concurrent.atomic.AtomicBoolean INSTALLED =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    private final Queue<ScenarioRecord> scenarios = new ConcurrentLinkedQueue<>();
    private final Queue<StepResult> steps = new ConcurrentLinkedQueue<>();

    private SummaryResultReporter() {
    }

    /**
     * 装载本上报器（幂等）：注册到 {@link ResultReporters} 并登记 JVM 退出落盘任务。
     *
     * <p><b>为什么需要显式装载</b>：本类没有任何被动引用点，若依赖自身静态块则永远不会被类加载、
     * 注册也就不会生效（曾导致报告不产出）。又因 core 不能反向依赖 reporting
     * （会形成 core↔reporting 循环、破坏 G1），无法由 core 侧的 {@code ResultReporters} 直接引用，
     * 故由<b>同模块</b>已被加载的 {@link SerenityReporter} 负责装载。
     */
    public static void install() {
        if (!INSTALLED.compareAndSet(false, true)) {
            return;
        }
        ResultReporters.register(INSTANCE);
        //  order 1000：晚于 ORDER_TEST_CONTEXT(950) 等所有业务关闭任务，确保采集完整
        ShutdownCoordinator.register(1000, "framework-summary-report",
                SummaryResultReporter::writeNow);
    }

    /** 当前采集到的场景数（测试/诊断用）。 */
    public static int scenarioCount() {
        return INSTANCE.scenarios.size();
    }

    /** 当前采集到的步骤数（测试/诊断用）。 */
    public static int stepCount() {
        return INSTANCE.steps.size();
    }

    /** 清空采集数据（测试用）。 */
    public static void reset() {
        INSTANCE.scenarios.clear();
        INSTANCE.steps.clear();
    }

    @Override
    public void reportStep(StepResult step) {
        if (step != null) {
            steps.add(step);
        }
    }

    @Override
    public void reportScenario(String scenarioName, TestResult result, long durationMs) {
        scenarios.add(new ScenarioRecord(scenarioName, result, durationMs));
    }

    /**
     * 立即生成汇总报告（幂等；未开启或无数据时不产出文件）。
     *
     * @return 写出的 JSON 文件路径；未写出时返回 {@code null}
     */
    public static Path writeNow() {
        if (!FrameworkFlags.isEnabled(ENABLED_KEY, true)) {
            return null;
        }
        try {
            Path dir = Paths.get(FrameworkFlags.resolve(DIR_KEY, DEFAULT_DIR));
            Files.createDirectories(dir);

            String json = INSTANCE.renderJson();
            Path jsonFile = dir.resolve("framework-summary.json");
            Files.write(jsonFile, json.getBytes(StandardCharsets.UTF_8));

            Path txtFile = dir.resolve("framework-summary.txt");
            Files.write(txtFile, INSTANCE.renderText().getBytes(StandardCharsets.UTF_8));

            logger.info("[SummaryReport] wrote {} and {}", jsonFile, txtFile);
            return jsonFile;
        } catch (IOException e) {
            logger.warn("[SummaryReport] failed to write summary report: {}", e.toString());
            return null;
        }
    }

    // ═══════════════════════════════════════════════════════════
    // 渲染
    // ═══════════════════════════════════════════════════════════

    private String renderJson() {
        List<ScenarioRecord> sc = new ArrayList<>(scenarios);
        List<StepResult> st = new ArrayList<>(steps);

        StringBuilder sb = new StringBuilder(1024);
        sb.append("{\n");
        sb.append("  \"generatedAt\": \"").append(TS.format(LocalDateTime.now())).append("\",\n");

        sb.append("  \"scenarios\": {\n");
        sb.append("    \"total\": ").append(sc.size()).append(",\n");
        appendDistribution(sb, countScenarios(sc), "    ");
        sb.append("    \"totalDurationMs\": ").append(sc.stream().mapToLong(s -> s.durationMs).sum()).append("\n");
        sb.append("  },\n");

        sb.append("  \"steps\": {\n");
        sb.append("    \"total\": ").append(st.size()).append(",\n");
        appendDistribution(sb, countSteps(st), "    ");
        sb.append("    \"totalDurationMs\": ").append(st.stream().mapToLong(StepResult::getDurationMs).sum()).append("\n");
        sb.append("  },\n");

        sb.append("  \"slowestScenarios\": [\n");
        List<ScenarioRecord> slowest = new ArrayList<>(sc);
        slowest.sort(Comparator.comparingLong((ScenarioRecord s) -> s.durationMs).reversed());
        for (int i = 0; i < Math.min(TOP_SLOWEST, slowest.size()); i++) {
            ScenarioRecord s = slowest.get(i);
            sb.append("    {\"name\": ").append(quote(s.name))
                    .append(", \"result\": ").append(quote(s.result.name()))
                    .append(", \"durationMs\": ").append(s.durationMs).append("}")
                    .append(i < Math.min(TOP_SLOWEST, slowest.size()) - 1 ? "," : "")
                    .append('\n');
        }
        sb.append("  ],\n");

        sb.append("  \"failures\": [\n");
        List<ScenarioRecord> failures = new ArrayList<>();
        for (ScenarioRecord s : sc) {
            if (s.result.isFailure()) {
                failures.add(s);
            }
        }
        int listed = Math.min(MAX_LISTED_FAILURES, failures.size());
        for (int i = 0; i < listed; i++) {
            ScenarioRecord s = failures.get(i);
            sb.append("    {\"name\": ").append(quote(s.name))
                    .append(", \"result\": ").append(quote(s.result.name()))
                    .append("}").append(i < listed - 1 ? "," : "").append('\n');
        }
        sb.append("  ]\n");
        sb.append("}\n");
        return sb.toString();
    }

    private String renderText() {
        List<ScenarioRecord> sc = new ArrayList<>(scenarios);
        List<StepResult> st = new ArrayList<>(steps);

        StringBuilder sb = new StringBuilder(512);
        sb.append("========== Framework Test Summary (D4-2 result port) ==========\n");
        sb.append("generatedAt: ").append(TS.format(LocalDateTime.now())).append('\n');
        sb.append("scenarios   : ").append(sc.size()).append(' ').append(countScenarios(sc)).append('\n');
        sb.append("steps       : ").append(st.size()).append(' ').append(countSteps(st)).append('\n');
        sb.append("scenarioTime: ")
                .append(sc.stream().mapToLong(s -> s.durationMs).sum()).append(" ms\n");
        sb.append("stepTime    : ")
                .append(st.stream().mapToLong(StepResult::getDurationMs).sum()).append(" ms\n");

        List<ScenarioRecord> slowest = new ArrayList<>(sc);
        slowest.sort(Comparator.comparingLong((ScenarioRecord s) -> s.durationMs).reversed());
        sb.append("\n-- slowest scenarios (top ").append(Math.min(TOP_SLOWEST, slowest.size())).append(") --\n");
        for (int i = 0; i < Math.min(TOP_SLOWEST, slowest.size()); i++) {
            ScenarioRecord s = slowest.get(i);
            sb.append(String.format("  %6d ms  %-8s  %s%n", s.durationMs, s.result, s.name));
        }

        List<ScenarioRecord> failures = new ArrayList<>();
        for (ScenarioRecord s : sc) {
            if (s.result.isFailure()) {
                failures.add(s);
            }
        }
        sb.append("\n-- failures (").append(failures.size()).append(") --\n");
        for (int i = 0; i < Math.min(MAX_LISTED_FAILURES, failures.size()); i++) {
            ScenarioRecord s = failures.get(i);
            sb.append("  ").append(s.result).append("  ").append(s.name).append('\n');
        }
        if (failures.isEmpty()) {
            sb.append("  (none)\n");
        }
        return sb.toString();
    }

    private static Map<String, Integer> countScenarios(List<ScenarioRecord> list) {
        Map<String, Integer> counts = new TreeMap<>();
        for (ScenarioRecord s : list) {
            counts.merge(s.result.name(), 1, Integer::sum);
        }
        return counts;
    }

    private static Map<String, Integer> countSteps(List<StepResult> list) {
        Map<String, Integer> counts = new TreeMap<>();
        for (StepResult s : list) {
            String key = s.getResult() == null ? TestResult.UNKNOWN.name() : s.getResult().name();
            counts.merge(key, 1, Integer::sum);
        }
        return counts;
    }

    private static void appendDistribution(StringBuilder sb, Map<String, Integer> counts, String indent) {
        sb.append(indent).append("\"byResult\": {");
        List<String> keys = new ArrayList<>(counts.keySet());
        Collections.sort(keys);
        for (int i = 0; i < keys.size(); i++) {
            sb.append(quote(keys.get(i))).append(": ").append(counts.get(keys.get(i)));
            if (i < keys.size() - 1) {
                sb.append(", ");
            }
        }
        sb.append("},\n");
    }

    private static String quote(String s) {
        if (s == null) {
            return "\"\"";
        }
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r") + "\"";
    }
}
