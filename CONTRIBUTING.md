# 贡献指南（CONTRIBUTING）

> 本文件随 T4-4（文档防漂移）补充，说明架构守护规则与「版本单一事实来源」约定，避免文档与代码再次漂移。

## 1. 如何构建与测试

```bash
# 全量测试（行为零回归护盾）
mvn -o -pl test-automation -am test

# 仅生成汇总报告（方案 A，绑定 verify 阶段）
mvn -o -pl test-automation -am verify

# 文档防漂移校验（见第 3 节）
bash tools/check-doc-drift.sh          # 检查
bash tools/check-doc-drift.sh --fix    # 依据 pom 重写 README 版本
```

## 2. 架构守护（Architecture Guardians）

质量门禁在 `verify` 阶段强制生效（详见 `architecture/ARCHITECTURE_REMEDIATION_PLAN.md` 的 T1 系列）：

| 守护 | 机制 | 约束 |
|------|------|------|
| 模块解耦 | ArchUnit（`framework-web` 等模块测试） | page 包不得反向依赖 route；common 不得越层依赖 web / api；顶层切片无环 |
| 报告解耦 | `ReportingRouteDecouplingArchTest` | reporting 模块不依赖 route（可独立运行） |
| 代码生成解耦 | `CodegenDecouplingArchTest` | web 运行时对 codegen 零编译期依赖（SPI 注册表桥接） |
| 风格 | Checkstyle | 文件长度 / 空 catch / 未用 import（作用域限定重构包） |
| 缺陷 | SpotBugs | `verify` 硬门禁；存量告警按 class+pattern 模块级冻结，新代码零容忍 |
| 覆盖率 | JaCoCo | prepare-agent + report 已接线；check 门禁随单测补全启用 |
| 安全 | OWASP / OSV-Scanner | `cve.gate.skip` 可临时跳过，默认 false |

**新增公共 API 的铁律**：框架内部能力用包级私有 + `@apiNote` 约束，防止业务 Page 误用；公开 API 变更须保持签名兼容；线程安全 / 并发可见性 / 语义化异常为默认要求。

## 3. 版本单一事实来源（防漂移）

- **所有对外宣称的技术栈版本都来自根 `pom.xml` 的 `<properties>`**（如 `<logback.version>`、`<cucumber.version>`）。README 的技术栈表只是这些属性的「投影」，不是另一份真相。
- 升级某个依赖版本时：**只改 `pom.xml` 的对应 `<properties>`**（必要时同步 `<dependencyManagement>` 钉版本），然后运行：

  ```bash
  bash tools/check-doc-drift.sh --fix   # 自动把新版本写回 README
  git add README.md pom.xml             # 必须一并提交，否则 CI 失败
  ```

- CI 工作流 `.github/workflows/doc-drift-check.yml` 会在每次触碰 `pom.xml` / `README.md` 的 push / PR 上运行 `tools/check-doc-drift.sh`；**若 README 版本与 pom 不一致，流水线直接失败**（验收标准：CI 有版本一致性校验；README 表述与实际一致）。
- 不要在 README 里手写版本号作为「第二真相」——它会被 CI 抓出漂移。

## 4. 依赖事实澄清

- **框架本身不依赖 Spring**。Spring Boot 仅用于 `route-demo-service` / `route-demo-web` 两个演示模块（Spring Boot 2.7.18）。请勿把 Spring 重新列为框架核心依赖。
- Cucumber 由 Serenity BDD 传递引入，已在根 pom 钉为显式属性 `<cucumber.version>`（当前 7.31.0），既保证可重现，也供 doc-drift 校验比对。
