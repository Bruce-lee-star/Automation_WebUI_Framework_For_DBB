# DBBN-PATCH-02：拦截 pattern 只在"规则表真的变了"时重发

- **载体**：自建客户端 `com.microsoft.playwright:playwright:1.62.0-dbb2`
  （**2026-09-30 起为外部提供构件**：原 reactor 模块 `pw-playwright` 已从仓库删除，源码不在本仓库；
  构建前须已装到本地仓库。复现配方见
  [`playwright-java-1.62.0-dbb-patch-01.md §5`](./playwright-java-1.62.0-dbb-patch-01.md)）
- **上游版本**：playwright-java 1.62.0
- **补丁级别**：`Connection.DBBN_PATCH_LEVEL = 2`（1 = DBBN-PATCH-01，见同名 patch-01 文档）
- **落盘日期**：2026-09-29

## 1 现象（E2E 实测）

`test-automation` 真跑 `login_dbb.feature` 时，第三个 scenario（dbb-3）在**第一步注册路由**就卡满 30 秒并硬失败：

```
21:57:20.962 ERROR GuardedDriverCallImpl - [RouteV2] route-v2 driver thread POISONED & RESET
  after 'bind:notifications/streams' timed out 30000ms (reset#=2, epoch -> 3, ...)
21:57:20.968 INFO  - STEP ERROR: java.lang.IllegalStateException: [RouteV2] driver call
  'bind:notifications/streams' 超时 30000ms 未收到回包
```

`bind` 期间 context 尚空闲（页面还没导航），`case metrics` 显示 `dispatches=0` ⇒ 卡的不是业务，而是**共享协议连接**。

## 2 根因

`context.route(...)` / `unroute(...)` 最终都是客户端的一次同步协议调用
`sendMessage("setNetworkInterceptionPatterns", …, NO_TIMEOUT)`；而 playwright-java 的客户端**没有独立读线程** ——
谁发起调用，谁就负责把消息读干净（`Connection.sendMessage → ChannelOwner.runUntil → processOneMessage`）。
⇒ 同一连接上**任何时刻只能有一个调用在泵消息**，一次全量 pattern 下发被拖慢，其他调用就收不到 ack。

上游在**每一次命中 handler 之后**都重发全量 pattern：

```java
// 1.62.0 原样（BrowserContextImpl.handleRoute / PageImpl 的 "route" 事件分支）
Router.HandleResult handled = routes.handle(route);
if (handled != Router.HandleResult.NoMatchingHandler) {
  updateInterceptionPatterns();      // ⇐ 每次命中都全量重发
}
```

但 `Router.handle()` 内部**唯一**会改变 pattern 列表的动作，是"某条规则的 `times` 配额用尽 ⇒ 条目被移除"。
其余情况（Handled / PendingHandler / Fallback）pattern 列表**没有任何变化**，这次下发纯属多余。

在长活端点（`notifications/streams` 是 SSE，浏览器断流后会自动重连）上，每一次重连都是一次新请求 ⇒ 一次 route 事件
⇒ 一次全量 pattern 下发。重连风暴把单线程客户端排满 ⇒ 后续的 `bind`/`unroute` 界内收不到 ack ⇒ 30 秒超时。

## 3 改动

| 文件 | 改动 |
|---|---|
| `impl/Router.java` | 新增 `HandleOutcome`（`result` + `patternsChanged`）与 `handleOutcome(RouteImpl)`；原 `handle(...)` 保留为委托（**最小化补丁面，便于随上游升级 rebase**）。`patternsChanged` 仅在 `times` 用尽、`it.remove()` 发生时置真 |
| `impl/BrowserContextImpl.java` | `handleRoute(...)` 改为用 `HandleOutcome`，仅当 `patternsChanged` 时才 `updateInterceptionPatterns()` |
| `impl/PageImpl.java` | `"route"` 事件分支同样按 `outcome.patternsChanged` 判断 |
| `impl/Connection.java` | `DBBN_PATCH_LEVEL` 由 `1` 提到 `2`（编译期守卫：换成官方 jar 即编译失败） |

**语义等价性（补丁安全的依据）**：`routes` 的**所有**变更点都会显式重发 ——
`route(...)`、`unroute(...)`、`unrouteAll()` 各自都调 `updateInterceptionPatterns()`；
`Router.handle*` 内部的唯一变更是 `times` 用尽时的 `it.remove()`，该情形仍会重发。
⇒ 收窄只去掉"列表未变时的多余下发"，不改变任何可观察语义。

## 4 影响面

- **收益**：消除"命中即重发"的 O(请求数 × pattern 数) 协议流量；长活端点（SSE 重连）不再排满单线程客户端，
  直接缓解 `bind`/`unroute` 界内收不到 ack（框架侧 30s 超时）与随之而来的毒化/信道污染。
- **不改业务层**：`test-automation` 的规则注册（含 `notifications/streams`）保持原样。
- **Node 驱动**（`driver` / `driver-bundle`）保持与上游 1.62.0 **逐字节一致**，本补丁只动 Java 客户端。

## 5 守卫

`pw-route-v2/src/test/java/com/microsoft/playwright/impl/DbbPatchGuardTest.java`

- 编译期 + 运行期：断言 `Connection.DBBN_PATCH_LEVEL == 2`；换回官方 jar 时该测试**编译失败**，缺陷不会静默复现。
- 行为面：`Router.handleOutcome` 在"命中但列表未变"时 `patternsChanged == false`；`times` 用尽时 `== true`。

## 6 回滚

`git revert` 本补丁涉及的 4 个客户端文件，并把两处 pom 的 `1.62.0-dbb2` 改回 `1.62.0-dbb1`、`DBBN_PATCH_LEVEL` 改回 `1`。

## 7 2026-09-30 变更：载体模块已从框架仓库删除（上文"两处 pom"随之失效）

- 上文第 3 行的**载体**不再由框架仓库构建：模块 `pw-playwright`（含其在框架根 `pom.xml` 的 `<module>` 条目）**已删除**。
- `com.microsoft.playwright:playwright:1.62.0-dbb2` 改为**外部提供**：构建框架仓库/`test-automation` 之前，
  该构件必须已存在于本地仓库（或可从可达仓库解析），否则**依赖解析失败**（不是编译错，是找不到构件）。
  框架根 `pom.xml` 的 `<playwright.version>` 处已加同义注释。
- **复现方式**（不再有 `pw-playwright/src/main` 可覆盖）：以上游 `playwright-java` **1.62.0** 的 `playwright/src` 为基础，
  依次套用本目录 `playwright-java-1.62.0-dbb-patch-01.patch` 与本文件抽取的 `...-dbb-patch-02.patch`
  （后者覆盖 `impl/Router.java`、`impl/BrowserContextImpl.java`、`impl/PageImpl.java`；
  **另需把 `Connection.DBBN_PATCH_LEVEL` 置为 `2`** —— 该常量属 patch-01 引入、patch-02 提级），随后 `mvn install` 该坐标。
- 移除前的**完整源码留档**（未纳入 git，仓库外）：`D:\IdeaProject\_backup\pw-playwright-vendored-20260930\`。
- ~~未决：CI `e2e` job 需相应处理~~ → ✅ **已处理（2026-09-30）**：`.github/workflows/build.yml` 的
  `build` 与 `e2e` 两个 job 在**任何解析依赖的 mvn 之前**新增
  `Provision patched Playwright client` step，按序供给：
  ① runner `~/.m2` 已存在该 jar ⇒ 复用（`setup-java` 的 maven 缓存命中时零成本）；
  ② 否则克隆**独立 fork 仓库**并 `mvn -f pw-playwright-client/pom.xml install -DskipTests`；
  ③ 两者都没有 ⇒ **早失败**（`::error::` 指明缺哪个 variable），而不是让后续 step 报晦涩的 resolution error。

  **仓库侧需配置（代码改不了）**：repository variable `PW_PATCHED_CLIENT_REPOSITORY`（vendored fork 的
  `owner/repo`），可选 `PW_PATCHED_CLIENT_REF`（默认 `main`）与 secrets `PW_PATCHED_CLIENT_TOKEN`（私有仓库）。
  未配置且缓存未命中 ⇒ job 会**红**，这是刻意的（缺构件必然构建失败，早失败优于晚失败）。

  ⚠️ **该 fork 仓库尚未创建**：当前仅本机 `.m2` 与仓库外备份
  （`D:\IdeaProject\_backup\pw-playwright-vendored-20260930\`，含可 `mvn install` 的 POM）存在该构件的源码。
  要让 CI 真正跑通，需把这 128 个文件推成一个独立仓库（或发布到可达制品库），再填上面三个 variable。
