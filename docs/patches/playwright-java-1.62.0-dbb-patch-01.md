# DBBN-PATCH-01 — Playwright Java 1.62.0 客户端补丁（`Connection.dispatch` 单条消息异常隔离）

| 项 | 内容 |
|---|---|
| 补丁编号 | `DBBN-PATCH-01` |
| 状态 | ✅ 已落地（2026-09-29） |
| 上游基线 | `microsoft/playwright-java` **1.62.0**（`playwright` 客户端模块） |
| 本地产物 | `com.microsoft.playwright:playwright:1.62.0-dbb1` |
| 修改文件 | `src/main/java/com/microsoft/playwright/impl/Connection.java`（唯一） |
| diff | [`playwright-java-1.62.0-dbb-patch-01.patch`](./playwright-java-1.62.0-dbb-patch-01.patch)（4 个 hunk） |
| 集成位置 | ~~框架 reactor 模块 `pw-playwright`（`<module>pw-playwright</module>`，排在 `pw-core` 之前）~~ **2026-09-30：该模块已从框架仓库删除，构件改为外部提供 —— 见 patch-02 文档第 7 节** |
| 许可证 | Apache-2.0（允许修改再分发；本模块 POM 已标注 "modified"） |

---

## 1. 症状（真跑实证）

`test-automation` 真跑 `login_dbb.feature` 时，**每个用例收尾的 `unroute` / 下一个用例开局 的 `bind`** 会随机失败：

```
[RouteV2] route registration failed for 'notifications/streams'
Caused by: com.microsoft.playwright.PlaywrightException: Failed to read message
Caused by: java.lang.InterruptedException  ...
```

并且 T6 的有界守护把它**误报成"驱动无响应超时"**（`driver call ... timed out 30000ms`），把真正的异常藏了起来——因此 `Object doesn't exist` 的统计长期为 **0（假象）**。

**探针取证（临时补丁：超时不中断、只记录真实完成耗时）**还原了真相：

```
[PROBE] driver call [unroute:profile/list] exceeded 10000ms -- detached ...
[PROBE] driver call [unroute:profile/list] ended exceptionally +2713ms after bound:
        PlaywrightException: Object doesn't exist: worker@36d2ddd9...
[PROBE] driver call [bind:notifications/streams] exceeded 30000ms -- detached ...
[PROBE] driver call [bind:notifications/streams] ended exceptionally +4963ms after bound:
        PlaywrightException: Object doesn't exist: frame@f620a4b8...
```

结论：**不是"挂起几分钟"，也不是"时延超界值"**，而是调用在界值后 3–5 秒**被一条无关的悬空事件炸掉**。

## 2. 根因（源码级，1.62.0 与 1.63 相同）

Playwright Java 客户端**没有调度线程**：谁调用 API，谁就在 `ChannelOwner.runUntil` 里泵消息。

```java
// ChannelOwner.runUntil
while (!waitable.isDone()) {
  connection.processOneMessage();     // ← 这里抛异常，整个等待就被带崩
}
```

而 `Connection.dispatch` **没有按消息做异常隔离**：

```java
object.handleEvent(message.method, message.params);   // 无 try/catch
```

当驱动把**已被 `__dispose__` 的对象**（`worker@`/`frame@`，多为导航/关闭期间的时序竞态）作为事件发来时，`getExistingObject` 抛 `Object doesn't exist`，异常直接从 `dispatch` 冒出 `runUntil`，**把当时正在等待回执的那个调用（`setNetworkInterceptionPatterns`）一起中止**。

上游自己也承认这个竞态——它只在 `pageError` 分支做了 try/catch 容错，其余分支不设防（1.63 依旧如此）。**这是 Playwright 客户端缺陷，框架改不了它的类，所以必须自己打补丁。**

## 3. 补丁内容（4 处，均为"记日志 + 继续"，不再抛）

| # | 位置 | 原行为 | 补丁后 |
|---|---|---|---|
| 1 | 类头 | — | 新增 `DBBN_PATCH_LEVEL`（编译期守卫）+ `DBBN_DROPPED` 计数 + `dbbnDrop(...)` |
| 2 | `dispatch`：`message.id != 0` 且 `callback == null` | `throw "Cannot find command to respond"` | `dbbnDrop("unknown-callback", ...)` + `return` |
| 3 | `dispatch`：`objects.get(message.guid) == null` | `throw "Cannot find object to call ..."` | `dbbnDrop("unknown-object", ...)` + `return` |
| 4 | `dispatch`：`object.handleEvent(...)` | 直接调用（异常外溢） | `try/catch (PlaywrightException)` → `dbbnDrop("stale-event", ...)` |

**为什么这样就根治**：泵循环不再被无关消息打断 ⇒ 我们等待的回执**一定会被处理到** ⇒ `setNetworkInterceptionPatterns` 正常完成。此后 `bind`/`unroute` **不需要重试、不需要按文案/类型分类、更不需要降级**。

## 4. 可观测性（不要静默）

补丁**不吞**，而是"降级为可观测"：

- 每次丢弃都会向 `stderr` 打一行 `[DBBN-PATCH-01] dropped protocol message: reason=..., method=..., guid=..., id=..., error=...`；
- 累计计数：`Connection.dbbnDroppedMessageCount()`。

→ 真跑时 `grep "[DBBN-PATCH-01]"` 即可知道**这条竞态本来会命中多少次**，是修复效果的直接证据，也是未来回归的金丝雀。

## 5. 集成方式

> **2026-09-30 现状（以下原始描述已失效，勿照做）**：reactor 模块 `pw-playwright`（及其在框架根 `pom.xml`
> 的 `<module>` 条目）**已从仓库删除**，源码不在本仓库。`com.microsoft.playwright:playwright` 现为
> **外部提供构件**（当前版本 `1.62.0-dbb2` = patch-01 + patch-02），构建前必须已装到本地仓库，
> 否则**依赖解析失败**。框架根 `pom.xml` 的 `<playwright.version>` 处已注明复现配方。
> 移除前的完整源码留档（仓库外、未纳入 git）：`D:\IdeaProject\_backup\pw-playwright-vendored-20260930\`。
> 完整变更说明见 [patch-02 文档第 7 节](./playwright-java-1.62.0-dbb-patch-02.md)。

**重建该构件时仍需遵守的原始约束**（在仓库外以独立 POM 构建，`mvn install` 该坐标即可）：

- 源码取自上游 `playwright-java` 的 `playwright/src/main`，**独立 POM、无父**，避免继承框架的门禁/插件配置；
- 版本形如 `1.62.0-dbb2`（**故意与上游区分**，防止与官方包混淆）；框架根 `pom.xml` 用
  `<playwright.version>` 指向该版本（现为 `1.62.0-dbb2`）；
- **Node 驱动不动**：仍依赖官方 `com.microsoft.playwright:driver:1.62.0` / `driver-bundle:1.62.0`（客户端与驱动版本必须一致，驱动无此缺陷）；
- 离线构建适配：`maven-compiler-plugin` 用框架已缓存的 **3.13.0**；`maven-jar-plugin` 不钉版本（用默认绑定）；补 `junit-jupiter-api:5.11.4`(provided) —— `com.microsoft.playwright.junit` 包需要它；
- 依赖：`gson:2.14.0`、`opentest4j:1.3.0`（见移除前 POM）；POM 的 `<licenses>` 须标注 modified。

## 6. 守卫（防止升级时静默丢失）

1. **编译期**：`public static final int DBBN_PATCH_LEVEL = 1;` —— 换回官方 jar 时，`pw-route-v2` 的守门测试**编译失败**（响亮告警）；
2. **运行期**：`pw-route-v2/src/test/java/com/microsoft/playwright/impl/DbbPatchGuardTest.java` —— 同包内直接构造 `Connection`，喂一条"引用未注册对象的事件"，断言**不抛**且计数 +1；
3. **真跑期**：`[DBBN-PATCH-01]` 日志行。

## 7. 验证

| 项 | 结果 |
|---|---|
| `mvn -o -pl pw-playwright install` | **BUILD SUCCESS**，`playwright-1.62.0-dbb1.jar` 入库 |
| 补丁入包（`javap`） | `DBBN_PATCH_LEVEL` / `DBBN_DROPPED` / `dbbnDroppedMessageCount()` / `dbbnDrop(...)` 均在 |
| 全量 `mvn -o clean install -Dspotbugs.skip=true -Dcve.gate.skip=true` | **BUILD SUCCESS**，9 模块，补丁模块排首位 |
| `pw-route-v2` | **204 例，0 失败 0 错误** |
| Checkstyle | 未跳过即通过 |

## 8. 升级流程（每次升 Playwright 必做）

1. ~~下载目标版本的 `playwright/src/main`，覆盖 `pw-playwright/src/main`；~~ **（2026-09-30：`pw-playwright` 模块已删除 ⇒ 改为在仓库外重建该模块、或直接以补丁文件套用到上游源码后 `mvn install` 该坐标；见 patch-02 文档第 7 节）**
2. 重新应用 [`playwright-java-1.62.0-dbb-patch-01.patch`](./playwright-java-1.62.0-dbb-patch-01.patch)（如上游有改动需人工核对上下文）；
3. 更新模块 POM 版本（如 `1.63.0-dbb1`）与根 POM 的 `driver`/`driver-bundle`/`playwright.version`；
4. 先查上游是否已修（把 `object.handleEvent(...)` 包进 try/catch）——**若已修则撤回本补丁与模块**；
5. 跑守门测试 + 全量安装 + 真跑（`grep "[DBBN-PATCH-01]"` 应为 0 或极低）。

## 9. 合规提醒

- Apache-2.0 允许修改再分发，但**必须保留 LICENSE/NOTICE 并标注已修改**（模块 POM 的 `<licenses>` 已注明，建议后续在模块内补 `NOTICE`）；
- 本模块是**内部构建**，`osv-scanner` CVE 门禁**扫不到**它 → 需在 SCA 基线与升级流程中显式登记"能力等同上游 1.62.0 + DBBN-PATCH-01"。

## 10. 上游跟进

建议按本文（§1 症状 + §2 根因 + §3 补丁）向 `microsoft/playwright-java` 提 issue/PR：**请求把 `object.handleEvent(...)` 与未知 guid 分支做异常隔离**（它已在 `pageError` 分支采用同一手法，接受度高）。上游合并后即可删除本模块。
