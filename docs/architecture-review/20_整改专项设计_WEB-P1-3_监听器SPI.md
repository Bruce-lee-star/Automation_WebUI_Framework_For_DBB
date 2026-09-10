# 整改专项设计 · WEB-P1-3 监听器注册改 SPI

> 状态：**已完成** —— 2026-09-10，含设计 + 实现 + 表征测试（ListenerRegistryResilienceTest 2 例全绿），全 web 套件 18 例 0 失败/0 错误/0 跳过 BUILD SUCCESS，无生产行为回归。
> 关联：评审 `02_web模块评审.md` §B（W-16 监听器发现单点失败全盘失败）、§C（WEB-P1-3 监听器注册改 SPI）；`12_代码质量巡检.md` W-10（ListenerRegistry.initialized 非 volatile）。

## 1. 背景与问题（W-16）

`ListenerRegistry` 原通过全量 `Class.forName` 包扫描发现监听器（`scanPackage` → `scanDirectory`/`scanJar` 递归），整段扫描被 `initialize()` 的 `try/catch` 包裹，**任一 `.class` 不可加载（损坏/缺依赖/链接错）或任一监听器实例化失败，都会抛 `InitializationException` 中止整个 `FrameworkCore.initialize()`** —— 即「单点失败全盘失败」。

`ListenerRegistry` 的 `getRegisteredListeners()` 全仓无消费方，监听器实际由 **Serenity 自带 `StepListener` SPI** 注册接线；`ListenerRegistry` 仅做扫描登记（其 list 未接线）。因此本专项聚焦**根治健壮性缺陷**并**引入 SPI 主发现机制（对齐 codegen 模块）**，不改变任何 Serenity 监听器接线语义（零回归风险）。

## 2. 目标

| 验收项 | 内容 | 达成 |
| --- | --- | --- |
| ① | 单个坏类不影响其它监听器 | ✅ 逐类 `try/catch` + 跳过 |
| ② | 有失败告警日志 | ✅ `logger.warn("Skipping unloadable class ...", ...)` |
| ③ | 对齐 codegen 模块的 SPI 做法 | ✅ `FrameworkListener` 接口 + `ServiceLoader` 主发现 + `META-INF/services` |

## 3. 方案

### 3.1 SPI 契约（新增 `FrameworkListener` 标记接口）
`web/src/main/java/.../web/listener/FrameworkListener.java`：纯标记接口（同 `java.util.EventListener`），实现类须提供 **public 无参构造器**以满足 `ServiceLoader` 契约。`@apiNote` 明确：监听器若需接入 Serenity 事件总线仍自行经 `StepEventBus.registerListener` 注册，本接口仅作为「可被框架发现」的 SPI 契约，与 Serenity 注册机制互不耦合。

### 3.2 主发现路径改为 ServiceLoader（对齐 codegen `RoleCodegenBridge`）
`ListenerRegistry.initialize(basePackage)` 重构为：
1. **先** `discoverViaServiceLoader()`：经 `ServiceLoader.load(FrameworkListener.class)` 惰性发现；逐 provider `try/catch`，单实现类加载/实例化失败仅跳过并记 WARN，不影响其它（根治 W-16）。
2. **再** 容错包扫描回退：`scanDirectory`/`scanJar`/`scanDirectoryRecursive`/`scanJarRecursive` 的 `Class.forName` 全部包 `try/catch`，单类不可加载仅跳过并记 WARN。
3. 扫描循环对 `FrameworkListener` 实现类直接 `continue`（SPI 已发现，避免重复登记）；非 SPI 的注解/接口监听器按原 `isListenerClass` 判定，注册失败逐类 `try/catch` 跳过。
4. **移除全盘 abort**：仅基础包解析等致命配置错误才整体抛 `InitializationException`；单类失败不再中止注册表初始化。
5. `initialized` 字段由 `boolean` 改为 `volatile`（W-10 线程安全加固）。

### 3.3 生产声明
`web/src/main/resources/META-INF/services/...FrameworkListener` 声明 `ThucydidesStepsListenerAdapter`；后者 `implements StepListener, FrameworkListener`。**行为等价**：原扫描已对 `isListenerClass` 命中的 `ThucydidesStepsListenerAdapter` 调用 `registerListener(clazz)` 构造并登记；现改由 SPI 主路径构造登记，零接线语义变更（自注册在 `registerWithStepEventBus()`，非构造器内，无双重注册风险）。

## 4. 测试（WEB-P1-3 表征，web/src/test）

`ListenerRegistryResilienceTest`（2 例，同包 `web.listener`，可访问包级 `initialize/cleanup/isInitialized/getRegisteredListeners`）：
- `spiDiscoveryRegistersGoodListener_andSkipsBadClass_withoutAborting`：扫描测试包 `resiliencypkg`（含正常 `GoodSpiListener` + 构造器必败的 `BadLoadListener` + 故意损坏的 `Broken.class`），断言 `initialize` 不中止、SPI 成功登记 `GoodSpiListener`、`BadLoadListener` 被跳过。
- `cleanupResetsRegistryState`：复位后 `isInitialized()==false` 且列表清空。
- 测试资源 `META-INF/services/...FrameworkListener` 声明 `GoodSpiListener`/`BadLoadListener`，与生产的 `ThucydidesStepsListenerAdapter` 合并被发现（ServiceLoader 多文件合并）。
- 故意损坏的 `Broken.class` 覆盖「扫描路径 `Class.forName` 失败」分支（日志实证 `Skipping unloadable class ...Broken ... ClassFormatError`）；已在父 POM JaCoCo `excludes` 中排除该夹具以消除良性 instrumentation 噪声。

## 5. 验证

```
mvn -o -pl web -am test
→ Tests run: 18, Failures: 0, Errors: 0, Skipped: 0  (WEB 模块)
→ 上游 core/reporting/api 全绿
→ BUILD SUCCESS
```
新增 `ListenerRegistryResilienceTest` 2 例 + 既有 web 测试无回归（确认 `ListenerRegistry`/`ThucydidesStepsListenerAdapter` 改动未影响 Serenity 接线）。

## 6. 结论与后续

W-16 健壮性缺陷已根治（单类失败仅跳过 + 告警，不再全盘失败），SPI 主发现机制对齐 codegen 落地。因 `ListenerRegistry` 的 list 当前未接线（Serenity 自有 SPI 负责接线），本专项**不改变任何运行时监听器注册语义**，属纯健壮性 + 机制对齐整改，无行为回归。后续若有新框架监听器需经本 SPI 发现，声明于 `META-INF/services/...FrameworkListener` 即可（前向兼容路径已就绪）。

> 注：WEB-P1-5（web 单测补充）同期启动——`web/pom` 已补 `mockito-core` 测试依赖；新增种子测试 `ConcurrencyGateTest`(4) + `NLSUtilsTest`(3)（均无浏览器），后续将持续扩充至 ≥25 个测试类 / 行覆盖 ≥25% 目标。
