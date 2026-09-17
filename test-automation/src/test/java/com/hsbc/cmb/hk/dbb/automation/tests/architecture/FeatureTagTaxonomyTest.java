package com.hsbc.cmb.hk.dbb.automation.tests.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * B-7：Gherkin 标签体系门禁（三维标签 + 状态标签）。
 *
 * <p><b>为什么需要它</b>：修复前标签命名不成体系——{@code @test1}（无语义）、{@code @route-composite}
 * （与其它风格不一致）、文档里的 {@code @0test}（代码中不存在），既无法表达执行编排意图，也无从校验。
 * 本门禁把「标签体系」从文档约定升级为<b>可执行约束</b>：新增/改名标签若不在注册表内，或 feature
 * 缺少执行层/子系统维度，构建即失败。
 *
 * <p><b>三维标签体系</b>：
 * <table border="1">
 *   <caption>维度说明与编排示例</caption>
 *   <tr><th>维度</th><th>允许值</th><th>用途 / 编排示例</th></tr>
 *   <tr><td>执行层 tier</td><td>{@code @smoke} / {@code @regression}</td>
 *       <td>{@code -Dtags="@smoke and not @skip"}</td></tr>
 *   <tr><td>子系统 subsystem</td><td>{@code @web} / {@code @api} / {@code @route}</td>
 *       <td>{@code -Dtags="@api"}</td></tr>
 *   <tr><td>域/能力 domain</td><td>见 {@link #DOMAIN}</td>
 *       <td>{@code -Dtags="@regression and @route-coverage"}</td></tr>
 *   <tr><td>状态 status</td><td>{@code @skip} / {@code @flaky}</td>
 *       <td>默认 {@code not @skip}；{@code @flaky} 供重试/隔离编排</td></tr>
 * </table>
 *
 * <p><b>规则</b>：① 任何标签必须在注册表内；② 每个 feature 在 <b>feature 级</b>声明恰好 1 个执行层
 * 标签与 1 个子系统标签（scenario 级可自由叠加域标签，Cucumber 会继承 feature 级标签）。
 *
 * @see com.hsbc.cmb.hk.dbb.automation.tests.architecture.LayeringArchTest
 */
class FeatureTagTaxonomyTest {

    /** Gherkin 资源根目录（surefire 工作目录 = 模块根）。 */
    private static final Path FEATURES = Path.of("src/test/resources/features");

    /** 维度一：执行层（执行频率编排）。 */
    private static final Set<String> TIER = Set.of("smoke", "regression");

    /** 维度二：子系统（分层编排）。 */
    private static final Set<String> SUBSYSTEM = Set.of("web", "api", "route");

    /** 维度三：域/能力（业务能力编排；新增能力须在此登记）。 */
    private static final Set<String> DOMAIN = Set.of(
            "login", "baidu", "scan", "scan-record", "picker", "e2e-sandbox",
            "parallel-logon", "concurrent-logon",
            "route-composite", "route-coverage", "route-capability-stop", "route-parallel-smoke",
            "delay", "click", "hover", "type", "check", "select", "popup", "download", "dialog");

    /** 维度四：状态（默认排除 / 重试标记）。 */
    private static final Set<String> STATUS = Set.of("skip", "flaky");

    /** 全部合法标签。 */
    private static final Set<String> ALLOWED = Stream.of(TIER, SUBSYSTEM, DOMAIN, STATUS)
            .flatMap(Set::stream).collect(java.util.stream.Collectors.toUnmodifiableSet());

    /**
     * 「整行皆为标签」的行才视为标签行——排除注释行与被描述文本内出现的 {@code @Xxx}
     * （如 {@code Verify the @RoleElement annotated ...}、{@code # 运行：...-Dtags=@route}）。
     */
    private static final Pattern TAG_LINE = Pattern.compile("^\\s*(@[\\w-]+\\s*)+(#.*)?$");

    private static final Pattern TAG = Pattern.compile("@([\\w-]+)");

    private static final class Feature {
        private final String file;
        /** feature 级标签（{@code Feature:} 行之前）。 */
        private final List<String> featureTags = new ArrayList<>();
        /** 全部标签（含 scenario 级）。 */
        private final List<String> allTags = new ArrayList<>();

        private Feature(String file) {
            this.file = file;
        }
    }

    @Test
    void allTagsMustBeRegistered() throws IOException {
        List<String> violations = new ArrayList<>();
        for (Feature feature : parseAll()) {
            for (String tag : new LinkedHashSet<>(feature.allTags)) {
                if (!ALLOWED.contains(tag)) {
                    violations.add(feature.file + " -> 未注册标签 @" + tag + "（请在 FeatureTagTaxonomyTest 注册表登记后使用）");
                }
            }
        }
        assertTrue(violations.isEmpty(),
                "存在未注册的 Gherkin 标签（B-7 三维标签体系）：\n" + String.join("\n", violations));
    }

    @Test
    void everyFeatureDeclaresExactlyOneTierAndOneSubsystem() throws IOException {
        List<String> violations = new ArrayList<>();
        for (Feature feature : parseAll()) {
            List<String> tiers = distinct(feature.featureTags, TIER);
            List<String> subsystems = distinct(feature.featureTags, SUBSYSTEM);
            if (tiers.size() != 1) {
                violations.add(feature.file + " -> feature 级需恰好 1 个执行层标签（@smoke/@regression），实际=" + tiers);
            }
            if (subsystems.size() != 1) {
                violations.add(feature.file + " -> feature 级需恰好 1 个子系统标签（@web/@api/@route），实际=" + subsystems);
            }
        }
        assertTrue(violations.isEmpty(),
                "feature 级三维标签不完整（B-7 标签体系）：\n" + String.join("\n", violations));
    }

    private static List<String> distinct(List<String> tags, Set<String> dimension) {
        return tags.stream().filter(dimension::contains).distinct().toList();
    }

    /** 解析所有 feature 文件；目录缺失或扫不到文件一律**失败**（避免路径写错时门禁静默空转）。 */
    private static List<Feature> parseAll() throws IOException {
        if (!Files.isDirectory(FEATURES)) {
            throw new IllegalStateException("未找到 features 目录：" + FEATURES.toAbsolutePath()
                    + "（surefire 工作目录应为模块根）");
        }
        List<Feature> features = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(FEATURES)) {
            for (Path path : paths.filter(p -> p.toString().endsWith(".feature")).sorted().toList()) {
                Feature feature = new Feature(FEATURES.relativize(path).toString().replace('\\', '/'));
                boolean featureKeywordSeen = false;
                for (String raw : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                    String line = raw.strip();
                    if (line.startsWith("Feature:") || line.startsWith("功能:")) {
                        featureKeywordSeen = true;
                        continue;
                    }
                    if (!TAG_LINE.matcher(raw).matches()) {
                        continue;
                    }
                    Matcher matcher = TAG.matcher(line);
                    while (matcher.find()) {
                        String tag = matcher.group(1);
                        feature.allTags.add(tag);
                        if (!featureKeywordSeen) {
                            feature.featureTags.add(tag);
                        }
                    }
                }
                features.add(feature);
            }
        }
        if (features.isEmpty()) {
            throw new IllegalStateException("未扫描到任何 .feature 文件：" + FEATURES.toAbsolutePath());
        }
        return features;
    }
}
