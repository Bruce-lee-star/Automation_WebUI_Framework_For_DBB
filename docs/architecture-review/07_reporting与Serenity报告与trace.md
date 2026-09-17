# 07 reporting 模块：Serenity 报告、Trace 与结果归因

> 评审范围：`reporting/src/main/java/**`、`reporting/src/main/resources/**`（8 ftlh）、`test-automation/pom.xml` 报告相关配置
> 评审基线：`e11a847`

---

## 一、模块职责

| 类 | 职责 |
|---|---|
| `SerenityReporter` | 向 Serenity 写入 API 操作记录；`ConcurrentLinkedQueue` 跨线程收集、主线程 flush；入队处脱敏 |
| `SerenityResultAdapter` | Serenity `TestOutcome` 与框架 `TestResult` 双向映射（纯函数） |
| `SummaryReportGenerator` | FreeMarker 二次汇总：HTML / CSV / ZIP，`main()` 由 Maven 调用 |
| `SummaryResultReporter` | 经 `ResultReporter` 端口采集，JVM 退出时写 `target/framework-summary.json|.txt` |

模板（8 个 `.ftlh` + 1 css）：`summary-report`（顶层页）、`alert-bar`、`coverage-section`、`error-type-pie-chart`、`failure-and-result-list`、`failure-overview`、`summary-section`、`view-full-report-button`。

---

## 二、现状评估

### 2.1 报告生成链路

```
failsafe 执行 IT
   └─ post-integration-test : serenity:aggregate     (test-automation/pom.xml:218-226)
        └─ verify : exec-maven-plugin → SummaryReportGenerator.main()  (:264-277)
```

用 **Maven 生命周期阶段**（`post-integration-test` → `verify`）保证"聚合完成后再汇总"，而不是依赖同阶段插件声明顺序。**这个决策是对的**——同阶段顺序在 Maven 里是易碎的隐式依赖。

### 2.2 二次汇总报告（正面）

`SummaryReportGenerator`（`:32,370,489,531,1885,1910`）读取 `TestOutcome`（`loadTestOutcomes:1421`），产出 `serenity-summary.html` + CSV + ZIP。模板拆分 8 个片段，含失败占比饼图、功能覆盖、失败清单。

`SummaryResultReporter`（`:48,103,140,149,152`）在 `ShutdownCoordinator` order 1000 时写 `target/framework-summary.json|.txt`，**含 Top10 最慢用例与失败清单**——这个 Top10 慢用例是实用功能，直接指向优化目标。

跨线程收集用 `ConcurrentLinkedQueue` + **入队处即脱敏**（`SensitiveDataSanitizer`），避免了"报告里出现明文"的常见问题。

### 2.3 缺口

**没有趋势/历史对比**。全量 grep `trend|previous|history` 无命中——每场报告是孤立的。对回归套件而言，"这个用例比上周慢了 3 倍""这个用例连续 5 次失败"这类信息才是决策依据，单场报告给不了。

`SerenityReporter.reportStep` 是**空实现**（`:94`）——说明设计上存在占位但未被填充的接缝。

**Trace 未挂进报告**（见 03 文档 W-3）：trace.zip 落在 `target/traces/` 且只用时间戳命名，报告里没有入口。

### 2.4 报告产物与外发

`serenity-report-push.yml` 把报告推送到外部仓库 `hsbc/dbb-serenity-reports` 的 gh-pages（`:37,:62-71`）。结合 03 文档的 W-2（截图无脱敏），**报告外发 = 敏感截图外发**。

---

## 三、优势

1. **用 Maven 阶段而非插件顺序保证时序**，规避了隐式依赖。
2. **跨线程收集 + 入队即脱敏**，报告侧无明文泄露。
3. **Top10 最慢用例**直击性能回归痛点。
4. **模板拆分为 8 个片段**，可维护性好于单文件巨模板。
5. **`SerenityResultAdapter` 是纯函数**，易测（reporting 模块有 3 个测试类，含 `SummaryReportGoldenTest` 黄金样本测试）。
6. 有 `ReportingRouteDecouplingArchTest` 守护模块解耦。

---

## 四、风险与问题

| 编号 | 级别 | 问题 | 证据 | 影响 |
|---|---|---|---|---|
| E-1 | **P0** | 报告（含未脱敏截图）被推送到**外部仓库** gh-pages | `serenity-report-push.yml:37,62-71` + 03-W-2 | 敏感数据外泄 |
| E-2 | **P1** | **无趋势/历史对比**，每场报告孤立 **已修复**：`RunSummary` + `TrendStore`（`<reportDir>/trend-history/*.json` 快照）；汇总报告 Full Test Results 增加「本次/上次耗时 + 变化率」列，近 5 场失败 ≥3 次打 `FLAKY`；无历史时不渲染（golden 不变）| grep `trend\|history` 无命中 | 无法识别性能退化与常失败用例 |
| E-3 | **P1** | **Trace 未挂进报告**，仅时间戳命名 **已修复**：trace 命名（scenarioId）与挂 Serenity 报告由 W-3 落地；本次补二次汇总报告 `SummaryReportGenerator` 失败清单 Trace 下载列（按归一化场景名前缀匹配 `traces/trace-*.zip`，仅存在匹配时渲染，不破坏 golden 基线）| 03-W-3 | 最强排障手段实际不可用 |
| E-4 | **P1** | 无失败自动分类归因（元素未找到 / 超时 / 断言 / 环境 / 崩溃） | `error-type-pie-chart.ftlh` 存在但分类来源不明 | 失败分析仍靠人眼 |
| E-5 | **P2** | `SerenityReporter.reportStep` 空实现 | `SerenityReporter.java:94` | 接缝占位未填充，易误导 |
| E-6 | **P2** | 汇总报告绑定 `verify`，而 CI 只跑 `mvn test` | 11 号文档 | 汇总报告在 CI 上从未生成 |
| E-7 | **P2** | ZIP 打包内容未说明是否含截图/trace | — | 产物大小与内容不可控 |

---

## 五、优化方案

### 5.1 报告外发前做脱敏门禁（P0）

在 `serenity-report-push.yml` 里加一道"推送前扫描"：

```yaml
- name: 报告脱敏自检
  run: |
    # 1) 扫描报告目录里是否残留疑似凭据/令牌
    if grep -rEl "(Bearer [A-Za-z0-9._-]{20,}|password\"?\s*[:=]\s*\"[^\"]{4,})" report-src/ ; then
      echo "::error::报告中发现疑似敏感信息，禁止推送"
      exit 1
    fi
    # 2) 截图目录必须存在脱敏标记（由框架写入 .sanitized 标记文件）
    test -f report-src/serenity-report/.sanitized || {
      echo "::error::截图未执行脱敏流程（缺少 .sanitized 标记）"; exit 1; }
```

框架侧在 `PlaywrightScreenshotManager` 完成脱敏后写 `.sanitized` 标记文件，让门禁有明确依据。

**更彻底的做法**：截图脱敏（03-5.2）落地后，报告默认就不含敏感信息，这道门禁只是兜底。

### 5.2 建立历史趋势（P1）

最小可用方案：**每场跑完落一个 JSON 快照，汇总时读取历史做对比**，不引入数据库。

```java
public final class TrendStore {
    private static final Path TREND_DIR = Path.of("target", "trend-history");

    public static void save(String buildId, RunSummary summary) {
        Files.writeString(TREND_DIR.resolve(buildId + ".json"),
                          GSON.toJson(summary));
    }

    /** 读取最近 N 场，用于对比 */
    public static List<RunSummary> recent(int n) {
        return Files.list(TREND_DIR)
            .sorted(Comparator.reverseOrder())
            .limit(n)
            .map(p -> GSON.fromJson(Files.readString(p), RunSummary.class))
            .toList();
    }
}

public record RunSummary(String buildId, Instant timestamp, int total, int passed,
                         int failed, Map<String, Long> scenarioDurationMs) {}
```

汇总报告里增加三列：**本次耗时 / 上次耗时 / 变化率**，并对"失败次数 ≥ 3 的用例"打 flaky 标记。CI 上把 `target/trend-history` 做成 cache 或 artifact 传递即可，成本极低。

### 5.3 把 Trace 挂进报告（P1）

与 03-5.3 联动：trace 文件名改用 `scenarioId`，并在 `SummaryReportGenerator` 的失败清单里加"Trace"下载列。

```java
// SummaryReportGenerator 中构造失败项时
FailureRow row = new FailureRow(
    outcome.getName(),
    outcome.getDuration(),
    traceFileFor(outcome).map(Path::toString).orElse(null),  // 新增
    screenshotFor(outcome).orElse(null)
);
```

模板 `failure-and-result-list.ftlh` 增加一列链接。

### 5.4 失败自动分类（P1）

用异常类型 + 消息签名做归因，直接复用 `BrowserCrashGuard` 已有的签名思路：

```java
public enum FailureCategory {
    ELEMENT_NOT_FOUND("元素未找到", "Timeout .* waiting for .* locator"),
    NAVIGATION_TIMEOUT("导航超时", "Timeout .* exceeded|net::ERR_"),
    ASSERTION("断言失败", "expected .* but was|AssertionError"),
    BROWSER_CRASH("浏览器崩溃", "Target crashed|Browser has been closed"),
    API_ERROR("接口异常", "status=\\d{3}"),
    ENVIRONMENT("环境问题", "ECONNREFUSED|UnknownHost|401|403"),
    UNKNOWN("未分类", "");

    public static FailureCategory of(Throwable t) { /* 按优先级匹配 */ }
}
```

在饼图模板里展示分类占比，并**把"环境问题"与"浏览器崩溃"自动标记为可重试**，配合 failsafe 的 `rerunFailingTestsCount` 使用。

### 5.5 填充或删除空接缝（P2）

`SerenityReporter.reportStep:94` 要么实现（写入 API 步骤到 Serenity），要么删除并加 `@Deprecated` 说明。空实现会让人误以为能力已存在。

---

## 六、结论

reporting 模块的**工程细节是扎实的**：用 Maven 阶段保证时序、入队即脱敏、模板拆分、纯函数适配器 + 黄金样本测试。Top10 慢用例说明设计者真的在用这份报告。

三个改进方向按价值排序：**E-1（外发脱敏门禁，合规底线）→ E-3 + E-4（trace 与失败归因，排障效率）→ E-2（趋势，长期决策）**。其中 E-2 的实现成本最低（一个 JSON 快照目录），但带来的收益（识别 flaky 与性能退化）会随套件规模增长而放大。
