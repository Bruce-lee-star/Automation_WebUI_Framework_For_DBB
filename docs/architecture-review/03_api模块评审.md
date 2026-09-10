# 模块评审 03｜`framework-api`

> 定位：HTTP 接口测试封装（基于 Serenity RestAssured）
> 规模：30 Java 文件 / ~3,000 行 / 自有单元测试 0 个
> 依赖：`framework-core`、`framework-reporting`
> **模块总评：2.8 / 5.0（抽象合格，并发与配置是短板）**

---

## 一、模块结构盘点

| 包 | 类 | 职责 | 评价 |
|---|---|---|---|
| `client` | `ApiJob`(贫血数据类) / `AbstractApiJobHelper` / `ApiJobPayloadLoader` / `ApiJobRequestReader` / `ApiJobRequestWriter` | 任务模型与 IO | 中等 |
| `client.rest` | `AbstractRestJob`(模板方法) / `RestJobProvider` | REST 抽象 | **良好** |
| `client.rest.impl` | `RestGetJob` / `RestPostJob` / `RestPutJob` / `RestDeleteJob` / `RestPatchJob` | 5 个动词实现 | 良好（各 ~1 行差异） |
| `core.endpoint` | `EndpointConfig` / `EndpointProvider` | 端点配置 | 中等 |
| `core.entity` | `Entity` / `EntityBuilder` | 请求实体构建 | 中等 |
| `core.services` | `TestServices` | 服务门面（per-thread） | 中等 |
| `core.step` | `BaseStep` / `BaseStepFactory` | 步骤基类 | 中等 |
| `config` | `FrameworkConfig` / `ConfigProvider` | 配置 | **问题集中** |
| `utility` | `ApiLogSanitizer` / `JsonUtils` / `FileReader` / `EnvironmentUtils` / `Constants` | 工具 | 良好 |
| `assembler` | `HeadersAssemblers` | 请求头装配 | 中等 |
| `domain.enums` | `APIResources` / `ConfigKeys` / `HttpStatus` | 枚举 | 中等 |

---

## 二、八维度逐项分析

### D1 模块边界与依赖治理 —— 3.0 / 5 ✅

**优点 A-1：依赖方向干净，且有门禁保障**
`api → core`，`api → reporting`，不依赖 web、不依赖 route。ArchUnit 的 `apiMustNotDependOnWeb` 规则（`ArchitectureTest.java:52-59`）显式固化该约束。

**问题 A-2（中）：死代码未清理**

| 位置 | 内容 |
|---|---|
| `FrameworkConfig.java:274,313,328` | 3 个 `@Deprecated` 的 Selenium 遗留配置项，**无调用点** |
| `RestJobProvider.java:178-190` | `setEndPoint` / `getEndPoint` `@Deprecated`，**无调用点** |

这些是"从 Selenium 迁移到 Playwright/RestAssured"过程中的残留，属于**迁移未收尾**的信号。

---

### D2 抽象设计与扩展性 —— 3.0 / 5 ⚠️

**优点 A-3：模板方法模式运用正确 —— REST 动词实现高度收敛**

```java
// AbstractRestJob.java:115
public <T> T execute(Entity entity, Function<Response, T> handler) { ... }
// RestGetJob.java:22 —— 仅一行差异
```
`AbstractRestJob` 把 5 个 HTTP 动词的公共流程（鉴权装配、日志、脱敏、异常处理、响应解析）上提，每个子类只保留一行差异。**消除了 5 份近乎全等的拷贝**，这是本模块最干净的设计。

**优点 A-4：`EntityBuilder` 已修复全局污染**
`EntityBuilder.build(name, env)` 改为显式传入 env 参数，不再依赖 `System.setProperty`（`EntityBuilder.java:78-87`）。说明团队已识别并处理过全局状态问题。

**问题 A-5（中）：`ApiJob` 是贫血数据类，抽象层次不完整**

`ApiJob.java:9-38` 仅含字段与 getter/setter，无任何行为。而 `AbstractApiJobHelper` 承担全部操作——**数据与行为分离但分离得不够彻底**，形成"贫血模型 + 上帝 Helper"的经典反模式。

**问题 A-6（中）：继承链过深且方向混乱**

```
BaseStep → RestJobProvider → AbstractApiJobHelper → ApiJob
```
`BaseStep.java:31` 显示 4 层继承，且 `ApiJob`（数据类）位于继承链**末端**——数据类被当作基类继承，语义倒置。业务方要扩展能力时，不清楚该覆写哪一层。

**问题 A-7（中）：强耦合 Serenity，无法独立使用**

```java
// AbstractRestJob.java:33 静态块
SerenityRest.setDefaultConfig(...);
// :129
SerenityRest.given();
```
整个模块绑死在 Serenity RestAssured 上。若某项目只想用 `framework-api` 做纯接口测试（不引入 Serenity 报告体系），**做不到**。对比 core 模块至少尝试了 SPI 抽象，本模块无解耦尝试。

---

### D3 并发与线程安全 —— 2.0 / 5 ❌ **本模块最严重问题**

**问题 A-8（P0/严重）：全局配置跨 Scenario 串扰**

```java
// ConfigProvider.java:61,213
private static volatile Config config;      // ← 全局可变
public static synchronized Config config(Entity entity) {
    ...
    config = loadFor(entity);                // ← 写入全局
    return config;
}
```

- `synchronized` 只保证了"写入互斥"，**不保证"读取时配置未被他人覆盖"**；
- `TestServices` 虽是 per-thread（`TestServices.java:42-49`），但其 `baseStep()` → `ConfigProvider.config(entity)` 会覆盖全局配置；
- **类注释（56-59 行）自认**："并行 scenario 会互相覆盖配置"。

**后果**：并发执行 API 用例时，线程 A 读取到的可能是线程 B 刚写入的 entity 配置（base-uri、超时、鉴权）。表现为**间歇性、不可复现、与测试数据无关**的诡异失败。

这是测试框架最危险的失效模式——一旦团队开始怀疑"框架是不是会串数据"，自动化测试的**可信度就崩塌了**，其破坏力远超功能缺陷。

**佐证**：`test-automation` 中存在 `TestServicesConcurrencyTest` 与 `ApiTestContextConcurrencyTest`，说明团队已察觉并尝试验证，但**根本问题（全局 volatile 字段）未修复**。

---

### D4 生命周期与资源治理 —— 3.0 / 5

**问题 A-9（中）：无连接池管理语义**
依赖 Serenity RestAssured 的默认 HTTP 连接管理，未显式配置连接池（最大连接数、每路由连接数、空闲回收）。并发场景下可能遭遇连接耗尽或 TIME_WAIT 堆积。

**问题 A-10（轻）：`TestServices` per-thread 但无显式清理契约**
`TestServices.java:42-49` 使用 ThreadLocal，但没有与 `TestContextHolder.resetForCurrentThread()` 对齐的清理钩子。线程池复用场景下存在残留风险。

---

### D5 配置与多环境 —— 2.0 / 5 ❌

**问题 A-11（严重）：配置体系与 web/core 完全割裂**

| 维度 | api 模块 | web / core 模块 |
|---|---|---|
| 引擎 | Typesafe Config（HOCON） | Serenity `SystemEnvironmentVariables` |
| 文件 | `application.conf` | `serenity.conf` / `serenity.properties` |
| 入口 | `ConfigProvider` + `FrameworkConfig`(class) | `FrameworkConfig`(enum, 128 项) |
| 解密 | 调用 `ConfigSource.decrypt()` 复用 | `ConfigSource.resolve()` 内置 |
| 环境变量 | Typesafe 自解析 | `ConfigSource.toEnvKey()` 自定义映射 |

**同一框架内两套配置引擎、两套文件、两套入口**。使用者必须知道"我要配的东西属于哪个域"，否则配了不生效。

**问题 A-12（中）：`ConfigKeys` 魔法字符串治理方式脆弱**

```java
// ConfigKeys.java:5-135 —— 每个常量重写 toString 返回配置 key
```
依赖 `toString()` 返回业务 key 是脆弱设计：调试器显示、日志打印、字符串拼接时行为不一致；且编译器无法校验 key 是否真实存在。

**问题 A-13（中）：历史配置 key 兼容逻辑堆积**
`FrameworkConfig.java:70-79, 207` 大量 `hasPath` + 兼容历史 key（`http.socket.timeout.value`、`api.base-uri.default` 等）。兼容层无废弃计划，会持续膨胀。

---

### D6 错误处理与可观测性 —— 3.0 / 5

**优点 A-14：`ApiLogSanitizer` 复用 core 脱敏能力**
日志输出前统一脱敏，与 core 的 `SensitiveDataSanitizer` 保持同一套识别规则，**未另起炉灶**。

**问题 A-15（中）：无请求/响应全链路追踪 ID**
每个请求无唯一 correlationId，并发执行时日志**无法按请求聚合**——这在排查 A-8 类串扰问题时是致命的。

**问题 A-16（中）：无结构化指标**
无请求耗时分布、成功率、重试次数等指标暴露。企业级接口测试框架应能回答"本轮接口平均耗时/ P95 / 失败率"。

---

### D7 安全与合规 —— 3.5 / 5 ✅

**优点 A-17：`ApiLogSanitizer` + `ConfigSource.decrypt()` 双保险**
敏感头/体在日志脱敏，密文配置在读取时透明解密，解密失败保留原串不中断加载（fail-safe 而非 fail-open——因为保留的是密文而非明文）。

**优点 A-18：零硬编码凭据**
全模块 grep 无命中。

---

### D8 可测试性与质量门禁 —— 2.5 / 5 ⚠️

**问题 A-19（严重）：`api/src/test` 为空**
30 个生产类零单元测试，与 core、web 同样的问题。

**问题 A-20（中）：静态门面导致测试需依赖真实配置**
`FrameworkConfig` 全静态 getter + `ConfigProvider` 全局状态，测试每个用例都要处理全局状态污染。

---

## 三、问题清单

| ID | 级别 | 问题 | 证据 |
|---|---|---|---|
| A-8 | **P0** | `ConfigProvider` 全局 volatile 配置，并发 Scenario 互相覆盖 | `ConfigProvider.java:56-59,61,213` |
| A-11 | **P1** | 配置体系与 web/core 割裂（Typesafe vs Serenity） | `ConfigProvider` vs `web/FrameworkConfig` |
| A-7 | **P1** | 强耦合 Serenity RestAssured，无法独立使用 | `AbstractRestJob.java:33,129` |
| A-19 | **P1** | `api/src/test` 为空 | 目录实证 |
| A-5 | **P2** | `ApiJob` 贫血模型 + 上帝 Helper | `ApiJob.java:9-38` |
| A-6 | **P2** | 4 层继承链且数据类位于末端 | `BaseStep.java:31` |
| A-2 | **P2** | 死代码未清理（Selenium 遗留配置项、Deprecated 端点方法） | `FrameworkConfig.java:274,313,328`；`RestJobProvider.java:178-190` |
| A-12 | **P2** | `ConfigKeys` 依赖 `toString()` 返回 key，脆弱 | `ConfigKeys.java:5-135` |
| A-13 | **P2** | 历史 key 兼容层无废弃计划 | `FrameworkConfig.java:70-79,207` |
| A-9 | **P2** | 无 HTTP 连接池显式管理 | 无相关配置 |
| A-15 | **P2** | 无请求 correlationId，并发日志无法聚合 | 无 |
| A-16 | **P2** | 无接口耗时/成功率指标 | 无 |

---

## 四、整改任务列表（api 模块）

### P0 —— 阻断级

| 任务 ID | 任务 | 验收标准 | 工时 |
|---|---|---|---:|
| **API-P0-1** | **消除全局配置串扰**：`ConfigProvider` 的全局 `volatile Config` 改为 per-thread 存储（复用 core 的 `TestContext` + `ContextKey<Config>`），或显式传参；`TestServices.baseStep()` 不再依赖全局态 | ① 无静态可变 `Config` 字段；② 新增并发单测：N 线程使用不同 entity 并发执行，断言各线程读到的 base-uri/超时互不影响；③ `TestServicesConcurrencyTest` 扩展为高压力用例并通过 | 3d |

### P1 —— 重要

| 任务 ID | 任务 | 验收标准 | 工时 |
|---|---|---|---:|
| **API-P1-1** | **配置体系统一**：`ConfigProvider` 改为委托 core 的 `ConfigSource`（保留 HOCON 作为配置源之一，但读取路径统一）；`FrameworkConfig`(api) 改名为 `ApiFrameworkConfig` | ① 配置读取统一走 `ConfigSource`；② 无同名类冲突；③ 现有 `application.conf` 配置项全部仍生效 | 4d |
| **API-P1-2** | **解耦 Serenity**：抽出 `HttpClient` 接口（发送/鉴权/日志/脱敏），Serenity RestAssured 作为默认实现；静态块 `SerenityRest.setDefaultConfig` 改为首次使用时惰性初始化 | ① `framework-api` 可在无 `serenity-rest-assured` 依赖下编译（提供 JDK HttpClient 备选实现）；② 现有行为不变 | 5d |
| **API-P1-3** | **补充 api 单元测试**：优先覆盖 `EntityBuilder`（多环境、模板变量）、`JsonUtils`、`ApiLogSanitizer`（脱敏矩阵）、`EndpointProvider`、5 个 `Rest*Job` 的模板流程；目标行覆盖 ≥ 50% | ① `api/src/test` ≥ 10 个测试类；② JaCoCo 行覆盖 ≥ 50% | 5d |
| **API-P1-4** | **引入 correlationId**：每个请求生成唯一 ID，贯穿日志、断言失败信息、报告；MDC 注入便于日志聚合 | ① 单条请求的所有日志含同一 ID；② 并发日志可按 ID 过滤 | 2d |

### P2 —— 优化

| 任务 ID | 任务 | 验收标准 | 工时 |
|---|---|---|---:|
| **API-P2-1** | 清理死代码：移除 3 个 Selenium 遗留配置项与 Deprecated 端点方法 | 无 @Deprecated 无调用点代码 | 1d |
| **API-P2-2** | 重构 `ApiJob`：合并贫血模型与 Helper，或明确拆为 `ApiRequest`(数据) + `ApiExecutor`(行为)，缩短继承链至 ≤ 2 层 | 继承深度 ≤ 2；无贫血基类 | 4d |
| **API-P2-3** | `ConfigKeys` 改为携带真实 key 字段的枚举，废弃 `toString()` 承载语义 | 编译期可校验 key 存在 | 1d |
| **API-P2-4** | 历史配置 key 兼容层加废弃标记与移除时间表（随版本发布 notes） | 有明确的移除计划 | 0.5d |
| **API-P2-5** | HTTP 连接池显式可配（最大连接、每路由、空闲回收），纳入 `ApiFrameworkConfig` | 配置项生效；压测无连接耗尽 | 2d |
| **API-P2-6** | 接口指标采集：耗时 P50/P95、成功率、重试次数，输出到报告与日志汇总 | 报告含接口性能汇总表 | 3d |

---

## 五、给架构决策者的建议

`api` 模块的处境比较特殊：它是**全项目设计得最"中规中矩"的模块**（模板方法正确、脱敏复用、无硬编码），却同时背着一个 **P0 级并发缺陷**。

值得注意的是，`ConfigProvider.java` 的注释里**清清楚楚写着**"并行 scenario 会互相覆盖配置"——团队知道这个问题，测试也写了（`TestServicesConcurrencyTest`），但**根因没修**。

这暴露了一个流程问题：**当"已知缺陷"以注释而非工单的形式存在时，它就永远不会被修复**。注释不是跟踪系统。

建议在整改中同步建立一条纪律：**代码注释中禁止出现"已知问题/待修复/注意这里会…"而未关联跟踪项**。这类注释应被静态检查或 code review 拦截，强制转为任务。
