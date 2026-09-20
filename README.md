# Automation WebUI Framework for DBB

企业级 Web UI / API 自动化测试框架：**Playwright** 驱动浏览器、**Serenity BDD + Cucumber** 组织用例与报告，
多模块 Maven 工程，内置声明式网络拦截（monitor / mock / modify）、日志脱敏、并发执行与分层架构守卫。

> 本文件中的**技术栈版本**由 `tools/check-doc-drift.ps1`（CI：`.github/workflows/doc-drift-check.yml`）
> 与根 `pom.xml` 的 `<properties>` 逐项比对，**漂移即失败**。升级依赖后请同步本表，或执行
> `pwsh tools/check-doc-drift.ps1 --fix` 自动对齐。

## 技术栈

| 组件 | 版本 |
|---|---|
| JDK | 21 |
| 构建 | Maven（多模块） |
| Playwright for Java | 1.62.0 |
| Serenity BDD | 4.2.0 |
| Cucumber | 7.31.0 |
| JUnit 5 | Jupiter 5.11.4 / Platform 1.14.0 |
| Logback | 1.5.34 |
| Typesafe Config | 1.4.5 |
| Gson | 2.14.0 |
| JsonPath（Jayway） | 2.9.0 |
| Jackson | 2.18.3 |
| json-smart | 2.5.2 |
| FreeMarker | 2.3.33 |
| JaCoCo | 0.8.12 |
| Checkstyle（plugin） | 3.6.0 |
| SpotBugs | 4.9.8.5 |

## 模块

| 模块 | 说明 |
|---|---|
| `core` | 基础层：`framework.common`（配置 / 安全脱敏 / 异步池 / 关闭协调 / route SPI）+ 顶层工具与重试；**被所有模块依赖** |
| `reporting` | 报告层：`framework.common.reporting`（`SerenityReporter`）+ 顶层 `report`（汇总报告 `SummaryReportGenerator`、趋势、trace 索引、ZIP 打包） |
| `api` | API / HTTP 测试层：`framework.api`（连接池、幂等重试、JSONPath / JSON Schema 校验、出口脱敏） |
| `web` | Web / UI 层：`framework.web`（PageObject / RoleElement、并发 Context 执行器、浏览器生命周期、截图 / trace）；**不含 `route`** |
| `codegen` | 元素拾取与代码生成：RolePicker、NLS 名称翻译、`RoleElementPageGenerator` |
| `route` | 网络拦截层：`RouteDsl` 声明式 **monitor / mock / modify**，捕获落库（MySQL / H2）与落盘 |
| `test-automation` | 测试工程：features、step definitions、`CucumberTestRunnerIT`；聚合全部框架模块 |

依赖方向：`core` ← `reporting` / `api` ← `web`；`codegen` / `route` 独立模块；`test-automation` 聚合。
分层与依赖规则由 ArchUnit 守卫（`test-automation` 的 `ArchitectureTest` / `LayeringArchTest`）。

## 快速开始

前置：JDK 21、Maven 3.9+、Playwright 浏览器。

```bash
# 1) 安装 Playwright 浏览器（首次；Playwright Java CLI，识别 PLAYWRIGHT_BROWSERS_PATH）
mvn exec:java -Dexec.mainClass=com.microsoft.playwright.CLI -Dexec.args="install chromium"

# 2) 构建 + 全量校验（CI 形状：含 SpotBugs / Checkstyle / 汇总报告）
mvn -B verify -DskipITs

# 3) 仅跑测试工程（自动带上各框架模块）
mvn -pl test-automation -am test
```

### 常用开关

| 场景 | 用法 |
|---|---|
| 引擎级并行（默认串行） | `-Pparallel -Dparallelism=4 -DmaxPoolSize=8` |
| 环境 profile | `-Psit1` / `-Psit2` / `-Psit3`（注入 `env` / `env.code` / `base.url`） |
| 标签过滤 | `-Dtags="not @skip"`（默认即 `not @skip`，按需收窄为 `@smoke` / `@regression` 等） |
| 依赖漏洞门禁 | `-Psca`（OSV CVE 门禁所需） |

## 配置体系

取值优先级：**系统属性 > 环境变量 > `-P` profile 的 `environment-*.conf` > `serenity.conf` / `serenity.properties` > 代码默认值**。

配置键的**唯一事实来源**是 `core` 的
`com.hsbc.cmb.hk.dbb.automation.framework.common.config.ConfigKeys`（当前 **184** 个键）：
Web / API / Monitor 三侧配置类（`WebFrameworkConfig` / `ApiFrameworkConfig` / `MonitorConfig`）均为
**引用它的门面**，不再各自持有 key / 默认值字面量。新增配置键必须在此登记，并由三重守卫把关：

- **重复键**：类加载期 fail-fast（`ConfigKeys.validateUniqueKeys()`）；
- **默认值不漂移**：`ConfigKeysGoldenTest` 以迁移前基线逐键逐字比对；
- **门面不得私藏定义**：`ConfigUnificationTest#facadesMustDelegateToRegistry`。

## 安全基线

- **凭据一律环境变量注入**（如 `DBB_TEST_PASSWORD`、`DBB_OTP_URL_*`、`CONFIG_MASTER_KEY`），禁止入库；
- 日志出口强制脱敏（`SanitizingPrintStream` / `SanitizingThrowableConverter`）；**API 请求 / 响应日志默认关闭**；
- 截图默认「**仅失败步、非全页**」；报告推送前有凭据扫描门禁。

> 说明：框架自身不依赖 Spring；Spring Boot 仅用于 `route-demo-service` 演示后端。

## 文档

| 位置 | 内容 |
|---|---|
| `docs/architecture-review/01`~`13` | 总体分层与依赖治理、各模块评审、CI / 工程可维护性、问题修复任务清单 |
