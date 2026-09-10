# 模块评审 06｜`framework-codegen`

> 定位：元素拾取器（RoleElementPicker）+ 页面对象/步骤代码生成器
> 规模：21 Java 文件 / ~4,000 行 / 自有单元测试 0 个
> 依赖：`framework-web`（单向），通过 SPI 被 web 反向发现
> **模块总评：2.4 / 5.0 —— 产品定位模糊，可靠性存疑**

---

## 一、模块结构

| 类别 | 类 | 说明 |
|---|---|---|
| **拾取器 UI** | `RolePickerPanelController` / `RolePickerPanelSync` / `RoleElementPicker` / `RolePickerBridge` | 浏览器内交互面板 |
| **脚本注入** | `RolePickerScriptInjector` / `RolePickerScripts`（含 15+ 段 JS） | 向页面注入 JS |
| **状态管理** | `RolePickerSessionState` / `RolePickerContext` / `RolePickerPageTracker` / `RolePickerFramePath` | 跨 frame/弹窗/导航状态 |
| **解析** | `RolePickerSnapshotParser` / `RolePickerPickParser` | 快照解析 |
| **代码生成** | `RoleElementPageGenerator` / `RoleElementStepGenerator` / `RolePickerCodeAssembler` / `RolePickerClassNameResolver` | 生成 Java 源码 |
| **命令引擎** | `RolePickerCommandEngine` | 命令分发 |
| **i18n 翻译** | `RolePickerNlsCache` + `web` 的 `NlsNameTranslator`（jieba + pinyin4j） | 中文→英文标识符 |
| **SPI** | `RoleCodegenBridge`（在 web）+ `RoleCodegenBridgeRegistry` | web ↔ codegen 桥接 |

---

## 二、八维度逐项分析

### D1 模块边界与依赖治理 —— 2.0 / 5 ❌

**优点 CG-1：SPI 解耦做得对**

```xml
<!-- codegen/pom.xml:15-18 -->
```
`framework-codegen` 通过 `java.util.ServiceLoader` 向 `framework-web` 注入 `RoleCodegenBridge`，**缺失时 web 行为不变**（有 `CodegenDecouplingArchTest` 保障）。这是全项目 SPI 使用的正面案例——**与 web 模块 `ListenerRegistry` 用 `Class.forName` 全量扫描形成鲜明对比**。

**问题 CG-2（P1/严重）：包名与模块名错配**

Maven 模块叫 `framework-codegen`，包名却是 `framework.web.page.scan`。后果：
- 从包路径看，这些类像是 web 模块的组成部分；
- ArchUnit 的 `web.page` 相关规则会**误伤** codegen 的类（例如 `pageMustNotDependOnRoute` 规则实际把 codegen 也纳入了 `web.page` 切片）；
- codegen 依赖 web，包名却在 web 之下——形成"子包反向依赖父包"的怪异结构。

**问题 CG-3（P1/中）：产品定位模糊——运行时工具还是构建期生成器？**

该模块同时承担两种性质完全不同的职责：

| 性质 | 表现 |
|---|---|
| **运行时 UI 工具** | 注入 JS 面板、依赖活的 `Playwright Page`、跨 frame 拾取、依赖浏览器状态 |
| **构建期代码生成** | 写盘生成 `XxxPage.java` / `XxxSteps.java` 源文件 |

这两者的**生命周期、失败模型、质量门禁完全不同**：
- 运行时工具可以"尽力而为"，失败只影响拾取体验；
- 代码生成器必须**确定性、幂等、可回滚**，失败会污染代码库。

当前二者混在同一模块、同一批类里，导致生成器**继承了运行时工具的不可靠性**（见 CG-5、CG-6）。

---

### D2 抽象设计与扩展性 —— 2.5 / 5 ❌

**问题 CG-4（P1/严重）：代码生成用字符串拼接，非模板引擎**

```java
// RoleElementPageGenerator.java:392-397
StringBuilder sb = new StringBuilder();
sb.append("package ...");
sb.append("public class ...");
...
```

而**同一个项目的 reporting 模块已经正确使用了 Freemarker 模板引擎**。同一仓库内，报告生成用模板引擎、代码生成用字符串拼接——**标准不一致**。

拼接式生成的固有缺陷：
- 无法做语法校验（生成的代码可能编译不过）；
- 模板与逻辑交织，修改困难；
- 无 escape 处理，字段名含特殊字符时产生非法 Java 代码。

**问题 CG-5（P1/严重）：覆盖写，无冲突检测**

```java
// RoleElementPageGenerator.java:463
Files.writeString(path, content);    // ← 直接覆盖
```

- 无"文件已存在且非本工具生成"的检测；
- 无 diff、无备份、无 dry-run；
- **用户在生成的 Page 类中手工补充的业务方法会被静默抹掉**。

当前仅靠"约定草稿需人工 review"规避——**靠流程约定而非技术约束**，迟早出事。

**问题 CG-6（中）：生成幂等性无保障**
无 Golden Test 验证"同一输入两次生成结果一致"。元素顺序、命名编号（见 CG-8）等不稳定因素会导致重复生成产生巨大 diff，污染代码评审。

---

### D3 并发与线程安全 —— 2.5 / 5 ⚠️

**问题 CG-7（中）：浏览器全局状态污染**

注入脚本重度依赖 `window.__rolePicks`、`__mergeKey`、`__rolePanelEnabled` 等**全局变量**（`RolePickerScripts.java` 多处）。后果：
- 与被测页面自身的全局变量**名冲突风险**；
- 跨 frame 读取依赖 `window.__rolePicks`（`READ_FRAME_PICKS_RAW_JS:452`），跨域 frame 受限；
- 并发拾取（多个 Context 同时打开面板）时状态可能互串。

**优点 CG-8：竞态意识存在**
注释中明确承认并处理了"4→5→6 元素重复累积"的竞态（去重键 `__mergeKey`、墓碑门控 `__rolePanelEnabled`）。**说明团队确实在这块踩过坑并做了处理**——只是处理方式是"打补丁"而非架构性解决。

---

### D4 生命周期与资源治理 —— 2.5 / 5

**问题 CG-9（中）：脚本注入生命周期无明确边界**
注入的 JS 在页面导航后会丢失，需要重新注入；面板状态与页面生命周期的同步靠 `RolePickerPageTracker` 维护，逻辑复杂且依赖时序。

**问题 CG-10（中）：生成产物无清理/回滚**
生成失败时可能留下半写文件；无"生成清单"记录本次生成了哪些文件，无法整体回滚。

---

### D5 配置与多环境 —— 2.0 / 5 ❌

**问题 CG-11（中）：i18n 翻译可靠性不足**

中文→英文标识符转换链路：`containsCjk` → `NlsNameTranslator.toIdentifier`（依赖 jieba-analysis + pinyin4j）。

风险点：
- **jieba 是简体中文离线词典**。本项目的业务背景是 **HSBC HK（中国香港）**应用，界面文案大概率包含**繁体中文与粤语用词**，简体分词器对繁体的切分准确率显著下降；
- pinyin4j 对多音字、繁体字的转换易出错；
- 多语言混排（中英夹杂）仅靠 `toIdentifier` 兜底；
- 命名冲突靠**数字后缀**消解（`candidate + (n++)`），产生 `button1`、`button2` 这类无语义标识符。

**对于金融级测试代码，元素命名是长期维护资产**——晦涩的自动命名会显著抬高后续维护成本。

---

### D6 错误处理与可观测性 —— 2.0 / 5 ❌

**问题 CG-12（P1/中）：JS 脚本大量静默吞异常**

`RolePickerScripts.java` 中大量 `catch(e){}`：
- `START_INJECT_JS:148-192`
- `SYNC_PANEL_TO_BROWSER_JS:789`

注入脚本运行在浏览器上下文中，异常无法传回 Java 侧，失败**完全不可见**。用户会遇到"点了没反应"却查不到任何日志。

**问题 CG-13（中）：无生成过程审计日志**
生成了哪些文件、基于哪些拾取、是否有覆盖，无结构化日志。企业级代码生成工具应能回答"这个文件是谁、什么时候、基于什么生成的"。

---

### D7 安全与合规 —— 2.5 / 5 ⚠️

**问题 CG-14（中）：JS 注入无白名单/来源校验**
向被测页面注入任意 JS，无 CSP 兼容性处理，无注入脚本完整性校验（SRI 概念在此不适用，但至少应有版本号与来源标记）。

**问题 CG-15（中）：跨源 frame 数据读取**
通过 Playwright 协议读取 frame 的 `window.__rolePicks`，绕过同源策略。虽在测试上下文中可接受，但**应有显式开关与审计**，避免被滥用于生产环境数据采集。

---

### D8 可测试性与质量门禁 —— 2.5 / 5 ❌

**问题 CG-16（P1/中）：零 Golden Test**

本模块的核心价值是"生成代码"，**但没有任何测试验证生成结果的正确性**。而同项目的 reporting 模块已经建立了成熟的 Golden Test 模式（逐字节比对 + 归一化 + 缺基线即失败）。

**这是最容易被修复的高价值缺口**——直接复用 reporting 的模式即可。

**问题 CG-17（中）：现有 5 个测试全在 test-automation**
`RolePickerClassNameResolverTest` / `RolePickerCodeAssemblerTest` / `RolePickerNlsCacheTest` / `RolePickerScriptInjectorTest` / `RolePickerScriptsTest` 均位于 test-automation 模块。

**问题 CG-18（中）：完全不在 Checkstyle 门禁范围**
根 POM 的 checkstyle `<includes>` 仅覆盖 `web/page/base/**` + 1 个类，**codegen 的 21 个类全部未被检查**。

---

## 三、问题清单

| ID | 级别 | 问题 | 证据 |
|---|---|---|---|
| CG-5 | **P0** | 代码生成覆盖写，无冲突检测，可静默抹掉用户手工代码 | `RoleElementPageGenerator.java:463` |
| CG-4 | **P1** | 用字符串拼接生成代码（同项目 reporting 已用 Freemarker） | `RoleElementPageGenerator.java:392-397` |
| CG-2 | **P1** | 包名 `web.page.scan` 与模块名 codegen 错配 | 包结构 |
| CG-3 | **P1** | 运行时工具与构建期生成器职责混同 | 模块定位 |
| CG-16 | **P1** | 零 Golden Test，生成结果正确性无保障 | `codegen/src/test` 为空 |
| CG-12 | **P1** | JS 注入大量静默吞异常，失败不可见 | `RolePickerScripts.java:148-192,789` |
| CG-11 | **P1** | i18n 翻译对繁体/粤语不可靠，命名冲突产生无语义标识符 | `NlsNameTranslator` + jieba |
| CG-6 | **P2** | 生成幂等性无保障 | 无 Golden Test |
| CG-7 | **P2** | 浏览器全局变量污染（`window.__rolePicks`） | `RolePickerScripts.java` |
| CG-14 | **P2** | JS 注入无白名单/版本标记 | — |
| CG-15 | **P2** | 跨源 frame 数据读取无开关/审计 | `READ_FRAME_PICKS_RAW_JS:452` |
| CG-13 | **P2** | 无生成过程审计日志 | — |
| CG-10 | **P2** | 生成失败无回滚 | — |
| CG-18 | **P2** | 21 个类全不在 Checkstyle 范围 | 根 POM `<includes>` |

---

## 四、整改任务列表（codegen 模块）

### P0 —— 阻断级

| 任务 ID | 任务 | 验收标准 | 工时 |
|---|---|---|---:|
| **CG-P0-1** | **生成前冲突检测 + 安全写**：① 检测目标文件是否已存在且不含本工具生成标记；② 存在用户手工修改时**拒绝覆盖**并输出 diff；③ 提供 `--force` / `--backup` 显式选项；④ 生成前写 `.bak` | ① 覆盖用户代码时必须显式确认；② 有 dry-run 模式；③ 生成失败不留半写文件（先写临时文件再原子移动） | 3d |

### P1 —— 重要

| 任务 ID | 任务 | 验收标准 | 工时 |
|---|---|---|---:|
| **CG-P1-1** | **改用 Freemarker 模板引擎**（复用 reporting 已引入的依赖），生成逻辑与模板分离 | ① 无 `StringBuilder` 拼接 Java 源码；② 模板独立文件；③ 生成行为与改造前**逐字节一致**（先用 Golden Test 锁定现状再迁移） | 5d |
| **CG-P1-2** | **建立 Golden Test 体系**（照搬 reporting 模式）：固定拾取输入 → 生成 → 逐字节比对 + 归一化 + **基线缺失即 fail**；覆盖 Page 生成 / Step 生成 / 命名解析 / 幂等性（两次生成结果一致） | ① `codegen/src/test` ≥ 8 个测试类；② 幂等性用例通过；③ 基线缺失时构建失败 | 4d |
| **CG-P1-3** | **包名与模块对齐**：`framework.web.page.scan` → `framework.codegen.*`；同步更新 SPI 配置与 ArchUnit 规则 | ① 包重命名完成；② `CodegenDecouplingArchTest` 通过；③ web 侧 SPI 引用更新 | 2d |
| **CG-P1-4** | **JS 异常可观测**：注入脚本统一包裹 `try/catch` → 将错误写入 `window.__rolePickerErrors` → Java 侧轮询/读取并输出 ERROR 日志 | ① 注入失败在 Java 日志可见；② 有 JS 错误时拾取流程明确告警 | 2d |
| **CG-P1-5** | **i18n 翻译增强**：① 补充繁体→简体预处理（OpenCC 或内置映射表）；② 维护业务术语白名单词典（优先于分词结果）；③ 命名冲突改用语义化后缀（如按 role 类型 + 就近文本）而非纯数字 | ① 繁体文案转换准确率人工抽样 ≥ 90%；② 术语白名单生效；③ 不再产生 `button1/button2` 式命名 | 5d |

### P2 —— 优化

| 任务 ID | 任务 | 验收标准 | 工时 |
|---|---|---|---:|
| **CG-P2-1** | 职责拆分：运行时拾取器（`codegen-picker`）与构建期生成器（`codegen-generator`）拆为两个模块或至少两个包，各自独立门禁 | 包/模块分离；生成器可脱离浏览器单测 | 5d |
| **CG-P2-2** | 全局变量收敛：注入脚本改用单一命名空间对象 `window.__rolePicker = {...}`，避免多个全局变量 | 全局变量数 = 1 | 2d |
| **CG-P2-3** | 生成审计日志：输出生成清单（文件、来源拾取、时间戳、是否覆盖），可选写入 manifest 文件 | 有 manifest 输出 | 2d |
| **CG-P2-4** | 注入安全加固：脚本带版本号标记；跨源 frame 读取增加显式开关（默认关闭）与审计日志 | 有开关；默认不跨源读取 | 2d |
| **CG-P2-5** | 纳入 Checkstyle 门禁范围 | 根 POM `<includes>` 覆盖 codegen | 0.5d |

---

## 五、给架构决策者的建议

`codegen` 是一个**"出发点是好的，但走偏了"**的模块。

它的初衷很务实：让测试人员通过点选页面元素自动生成 Page Object，降低自动化门槛。这个方向没错，很多企业都在做类似工具。问题出在**把"辅助工具"做成了"框架一等公民"**，却没有按一等公民的标准来建设它。

三个具体的错位：

1. **可靠性标准错位**——它生成的是要进代码库、要被人阅读维护的源码，却使用了"运行时工具"的容错标准（静默吞异常、覆盖写、无幂等保障）。

2. **技术选型错位**——同一个项目里，reporting 用 Freemarker 生成报告，codegen 用 `StringBuilder` 生成 Java 源码。**后者的影响远大于前者**（报告可以重跑，被覆盖的手写 Page 类找不回来），却用了更简陋的手段。

3. **测试投入错位**——codegen 是"输出物正确性"最需要保障的模块，却零 Golden Test；而 reporting 已经把这套模式跑通了。**这是现成的、几乎零学习成本的改进**。

**最关键的一条：CG-P0-1（覆盖写）必须最先做。**

这不是理论风险。实际场景是：测试同学用拾取器生成了 `LoginPage.java`，然后手工补充了几个业务方法；几周后有人重新拾取了一次，手工代码**无声消失**。这类事故一旦发生，团队对这个工具的信任就彻底瓦解了。

**建议把 codegen 的定位重新梳理一次**：它应该是"**脚手架工具**"（scaffolding，一次性生成草稿，之后由人接管），还是"**持续同步工具**"（与 UI 保持同步，可反复重新生成）？
- 若是前者 → 生成后应**自动打上"已交付给人工"标记**，拒绝再次覆盖；
- 若是后者 → 必须设计**用户代码保护区**（如 `// USER CODE BEGIN/END` 块），只更新受管区域。

当前实现两者都不是，处在最危险的地带。
