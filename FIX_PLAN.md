# 统一修复方案：Session / Context / Route V2 注册健壮性（单一事实来源）

> 建立：2026-09-28（v2，含时效声明与设计取舍补全）　维护约定：**本仓库所有修复/整改任务只写在这里**，
> 编号连续、标注状态，不再新增散落文档。
>
> 本文档合并并**删除**了以下 4 份旧文档（均为未入库文件，其仍有效内容已吸收进本文；细节取证见
> `test-automation/1.txt`、`Automation_WebUI_Framework_For_DBB/1.txt` 等实跑日志）：
> - `AUTOBROWSER_ROUTE_TEARDOWN_FIX_TASKS.md`（AutoBrowser tags / Route 跨场景收尾）
> - `SESSION_AUTOBROWSER_FIX_TASKS.md`（SessionManager / AutoBrowser）
> - `LOGIN_SESSION_FLOWCHART.md`（会话 / Context 生命周期流程图）
> - `docs/architecture-review/18_整改专项设计_HangWatchdog临时性与route2根因防护收口.md`
>
> **§2.0 时效声明必读**：历史文档中若干结论**已被后续改动取代**，直接照旧文执行会出错。

每条任务必须写清四件事：**动作 / 验收 / 回退开关 / 风险**。

---

## 1. 不可回退的不变式（语义契约）

### 1.1 会话 / Context
| 编号 | 不变式（**已按 2026-09-28 后的代码校准**） |
|---|---|
| I-1 | **同一 `sessionKey`、Context 承载登录态**时，上个用例**失败也保留 Context 与 Page 且不重建**（失败不污染会话：内存 Cookie 只活在 Context 内、`storageState` 仅成功路径落盘、sessionKey 绑定随 Context 关闭清除）；**无登录态绑定**的失败用例则**丢弃其 Page**（防"存活但已坏"跨用例传染）、保留 Context。实证：`1.txt` 打印 `Previous scenario FAILED — keeping Context and Page for same sessionKey` |
| I-2 | **跨 `sessionKey`**（同 feature 换用户）：**必须丢弃**旧 Context，防 Cookie/Storage 串用户 |
| I-3 | `scenario` 模式：**零复用**（本地会话文件只写不读），每用例全新 Context + 完整登录 |
| I-4 | 会话有效期（2026-09-29 收敛）：**只**看 `lastAccessTime`（`saveSession` 完整登录成功后写入、**复用不刷新**）+ `playwright.no.login.session.timeout.minutes`（默认 **5**）—— 超过即判过期、删 `.json`/`.meta`、下次完整登录 ⇒ 该阈值即会话的**绝对最大持续时间**；**不解析** storageState 的 `cookies[].expires`（旧实现按"最长 cookie TTL"判定，DBB 实测约 25 天 ⇒ 阈值形同失效；该解析已整体删除，勿再引入） |
| I-5 | 会话状态**包含 localStorage**（与 Cookie 同等地位）；承载登录态时用例收尾**不得清空** localStorage/sessionStorage |

### 1.2 路由 / 驱动
| 编号 | 不变式 |
|---|---|
| I-6 | 所有**同步协议调用**必须经 `GuardedDriverCall`（bind/unroute）或 `BoundedOps`（`route.fetch`）：daemon 执行 + 有界等待；**ArchUnit + 行为单测双重守护，禁止绕过** |
| I-7 | 规则生命周期跟随**目的**，而非用例：**达到目的即撤销（无论断言成功或失败）** |
| I-8 | 用例收尾"无任何活动"是**强要求**：驱动侧拦截已撤销 + 客户端规则/claim/pending/capture 清零 + 断言 drain；**是否干净必须可判定** |
| I-9 | **不可确证干净 ⇒ 重试/重同步自愈 + 响亮告警；绝不重建 Context**（2026-09-29 裁定）。理由：① 客户端每次下发都是**本地 Router 全量快照**且单连接 FIFO 有序 ⇒ 重发一次即可修复状态分叉；② 重建会破坏 I-1（同一 sessionKey 不重建）与免登录收益 —— **路由是辅助设施，其状态不确定性不得绑架会话/Context 生命周期**；③ 无法自愈时以 ERROR 告警 + 计数暴露（宁可可见的降级，也不要隐式重建） |

### 1.3 诊断设施的临时性
| 编号 | 不变式 |
|---|---|
| I-10 | HangWatchdog（web `static` + route2 `diag`）是**临时诊断设施**，不得接口化/注入；去除清单见 §7 |

---

## 2. 历史修复摘要

### 2.0 ⚠️ 时效声明：以下历史结论已被后续改动取代（勿按旧文执行）

| 历史结论（出处） | 现状（2026-09-28 后的真实语义） | 依据 |
|---|---|---|
| **A5（2026-09-26）：feature 模式下失败用例一律"丢弃 Page、保留 Context"** | **已取代**：`scenarioFailed && Context 承载登录态` ⇒ **保留 Context 与 Page**（不重建）；仅**无登录态绑定**的失败用例才丢 Page | `PlaywrightSerenityBridge.cleanupForScenario:620-637`；`PlaywrightManager.clearThreadResourcesOnCaseAbort:301-313`；`1.txt` 日志 |
| **S6（2026-09-26）：feature 快路径只需加"Context 存活"判断** | **已加强（C-4）**：还需 **Context 绑定与本次 sessionKey 一致**；且**两个 `restoreSession` 重载共用同一判定**（消除分叉） | `SessionStore.tryFeatureCacheHit` |
| **S3 / S11：feature 会话缓存复合键 / 仅 feature 标记缓存** | 语义不变，但**命中条件更严**；且 `scenario` 模式自 C-5 起**零复用**（文件只写不读），"有文件即复用"的旧预期不再成立 | `SessionStore.reusePersistedSession`、`logScenarioModeNoReuse` |
| **`LOGIN_SESSION_FLOWCHART.md` 图 1：命中 feature 缓存 → 免登录** | 命中需 **ctxAlive + 绑定一致**；否则回落文件恢复（会把 cookies **与 localStorage** 一起注入） | 同上 + `PlaywrightManager.applyStorageState*` |
| **A5：`SessionManager.isAnyFeatureSessionRestored()` 作为 Context 判据** | **已废弃**：改用 **Context ↔ sessionKey 绑定**（`ContextRegistryImpl.CURRENT_CONTEXT_SESSION_KEY`，随 Context 存活） | 上方 A5 行 |
| **S4："单 JVM ⇒ 多 JVM SSO 互踢不可能"** | 仍成立（`forkCount=1`）；但**跨进程/跨机器同 identity 登录**不受本框架保护（`ConcurrencyGate` 串行下为 no-op） | `serenity.playwright.concurrent.partition.enabled=auto` 警告日志 |
| **RC-3/A3：`BoundedUnrouteTask` 的 `unrouteAll()` 摘掉下个用例路由** | 属 **v1（pw-route）** 缺陷，已修；**V2 不存在该写法**，但 V2 有同类风险（teardown 与下个用例注册交叠）→ 由 T2/T2+ 处理 | `RuleRepository`（v1）vs `RouteRuntimeImpl`（v2） |

### 2.1 AutoBrowser tags / Route 跨场景收尾（2026-09-26，已闭环）
| 编号 | 缺陷（根因） | 处置 | 状态 |
|---|---|---|---|
| RC-1 | AutoBrowser 从 `StepEventBus.getEventBus()`（per-thread）读 tags，而真实 listener 在 per-feature sticky bus ⇒ 浏览器覆盖永久失效 | tags 改走 `getParallelEventBus()` + `isBaseStepListenerRegistered()` 就绪探测 | ✅ A1 |
| RC-2 | "空 listener 兜底"使 tags 静默为空（无 WARN） | 删除兜底：未就绪即返回空，下一 step 重试 | ✅ A2 |
| RC-3 | `RuleRepository.BoundedUnrouteTask` 在 daemon 里对本 feature **复用中的** Context 执行 `unrouteAll()` ⇒ 摘掉下个用例刚注册的路由 | 删除 `unrouteAll()` 兜底；改逐句柄 close 前复查 `hasRouteHandle` 的**零阻塞精判** | ✅ A3/A4 |
| A5 | feature 模式以 `sessionKey` 为唯一判据管理 Context | 绑定随 Context 存活；**其"失败丢 Page"部分见 §2.0 已取代** | ✅（部分取代） |
| A6 | `ApiCaptureManager` / `FileStoreMonitorCallback` 同源 per-thread bus 问题 | 改用并行 bus + 就绪守卫 | ✅ |
| A7 | 单测缺口 | `AutoBrowserProcessorTagBusTest`（2）、`RuleRepositoryUnrouteScopeTest`（1，含 `verify(never()).unrouteAll()`） | ✅ |

### 2.2 Session / AutoBrowser（2026-09-26，已闭环）
| 编号 | 内容 | 状态 |
|---|---|---|
| S1 | 复用 Context 前先 `getBrowser()` 强制浏览器类型一致（含 configId 空守卫修正） | ✅（离线回归 3 例） |
| S2 / S8 / S14 | `@AutoBrowser(enabled=false)` 门控；tags 迟到不终态化；反射单通道（净减 42 行） | ✅ |
| S3 / S11 | feature 缓存复合键 `featureId::sessionKey`；仅 feature 策略标记缓存 | ✅（命中条件见 §2.0） |
| S5 / S6 | 复用落盘补 `saveMeta`；feature 快路径加 Context 存活判断 | ✅（S6 已由 C-4 加强） |
| S9 | `resetAllForTest()` 统一重置入口 | ✅ |
| S16 / S17 | 会话目录绝对化（`-Dserenity.playwright.session.dir` 可覆盖）；meta 写失败升 ERROR | ✅ |
| S4 / S10 / S13 | 单 JVM ⇒ N/A；双 API 已合并；死代码删除 | ✅ |

---

## 3. 2026-09-28 批次（会话侧，已完成）

| 编号 | 内容 | 状态 |
|---|---|---|
| C-1 | `expires` 解析改为 Playwright **数字秒**（`-1` / 小数），删除"任何输入都解析成当前时刻"的 HTTP-date 分支 | ✅ 8 例回归单测 → **2026-09-29 被 S-1 取代（该解析整体删除）** |
| C-2 | 存量 meta 毒值自愈（`cookieExpiry ≈ lastAccessTime` ⇒ 丢弃重算），无需人工删历史会话文件 | ✅ **2026-09-29 被 S-1 取代（毒值字段不再读取）** |
| C-3 | 承载登录态时用例收尾**不再清空** localStorage/sessionStorage | ✅ |
| C-4 | 两个 `restoreSession` 重载统一 feature 命中判定（Context 存活 + 绑定一致） | ✅ |
| C-5 | `scenario` 模式**零复用** + 说明性日志 + 配置键文档更正 | ✅ |
| C-6 | 保存会话时打印 storageState 组成（cookies/origins/localStorageEntries） | ✅（实测 49 / 1 / 21） |
| C-7 | 路由侧 P1/P2（`MonitorSink` 能力+期望双闸门；content-type 诊断） | ✅ 早已实现且有测试 |

### 3.1 2026-09-29：会话有效期收敛为唯一判据（S-1）

| 编号 | 内容 | 状态 |
|---|---|---|
| S-1 | **删除** storageState `cookies[].expires` 解析：`parseCookieExpiryEpoch` / `parseExpiresToEpochMillis` / `isLegacySelfSignedExpiry` / `SessionMeta.cookieExpiryEpoch` / meta 键 `cookieExpiry`（写入时 `remove`，清除历史残留）全部移除；`isSessionExpired = now - lastAccessTime > playwright.no.login.session.timeout.minutes`；`lastAccessTime <= 0` ⇒ 判过期（fail-safe 重登） | ✅ |
| S-1a | **复用路径不再刷新** `lastAccessTime`（原 `reusePersistedSession` 里的 `META_CACHE.put` + `saveMeta` 已删）⇒ 阈值成为**绝对最大持续时间**，不会被复用无限延长 | ✅（`reusePersistedSession` 需真实 Playwright，无单测覆盖） |
| S-1b | 测试：删除 `SessionStoreExpiryParsingTest`（8 例，被测解析已不存在），新增 `SessionStoreSessionExpiryTest`（3 例：阈值边界 / 超龄必驱逐且 cookie 25 天 TTL 不得延寿 / 未超龄可复用） | ✅ `pw-web-ui` 381 例全绿（386 − 8 + 3） |
| S-1c | **副作用提示**：阈值默认 **5 分钟** ⇒ feature 模式下长 feature 中途会触发一次完整重登（计时按墙钟，从最近一次 `saveSession` 起算；业务侧每次真实切 profile 都会 `saveSession`）。需要更长请调大该配置键 | 暂定**保持默认 5 分钟**（2026-09-30 用户裁定：暂不改，后续有问题再解决；备选"空闲超时"语义的开关亦未做） |

---

## 4. 本轮根因：route 注册不可靠 → 病态 Context → 遗传

**事实（均带实证）**
1. `context.route()` / `unroute()` 最终都是客户端 `sendMessage("setNetworkInterceptionPatterns", …, NO_TIMEOUT)`
   （Playwright 1.62 `BrowserContextImpl:715-722`）——**客户端自身不设超时**；且客户端**没有调度线程**：
   `sendMessage → ChannelOwner.runUntil → processOneMessage` 由**调用线程自己泵消息**（`ChannelOwner:141-151`）。
2. 客户端 `Connection` 记账**非线程安全**：`private int lastId` + `++lastId`、`Map callbacks = new HashMap<>()`，
   全包 `synchronized` 命中 0 次（`Connection:64/66`）。框架存在多路并发调用（IO 池 `route.fetch`/`page.request`、
   guarded 每次新起 daemon 线程、`handleRoute` 嵌套重发）⇒ `1.txt` 出现 4 条 `Object doesn't exist: frame@…/response@…`。
3. **每次路由命中都会重发全量 pattern**（`BrowserContextImpl:728-732`），且本地 Router **先改后发**
   （`:552-555`）⇒ 一旦 ack 不到，客户端与驱动状态**分叉**（规则"看似注册、实则未生效"）。
4. 规则生命周期 = **用例级**（`PlaywrightManager.clearCurrentThreadRouteState` → `RouteLifecycleV2Impl.clearContext`
   ≡ `stopContextEngine` → `RouteEngine2.shutdown` → `runtime.close()` → 逐条 `unroute`），
   而 Context 生命周期 = **feature 级** ⇒ 每用例都在**同一个活跃 Context** 上做"全量撤销 + 全量重建"。
5. `1.txt` 实证：同一 `context @298156448` 跑 3 个 scenario；`unroute:profile/list` 3s 放弃；
   `bind:notifications/streams` 5.000s 无回包（第 3 个用例）；随后 `runtime degraded` 但 Context 仍被
   `kept for same sessionKey`，而 `RouteEngine2.isDegraded` **全仓 0 调用点**。

**结论**：病态 Context = 「context 级全量重装 + 每命中重发 + 长寿命跨用例复用 + 共享连接多线程」四者叠加下
某次回包超时的产物；并被「超时即失败 + degraded 无人消费 + 仍复用」**固化成遗传**。

### 4.1 master 分支为何不命中（2026-09-30 实跑对比，**重要**）

同样配置（`restart.browser.for.each=feature`）、同样 mock `notifications/streams`，master 从不出现本故障。
逐项对比（master = `...-master` 单模块版）得出**决定性差异在"收尾是否 unroute"**：

| # | 维度 | master | 当前（V2） |
|---|---|---|---|
| 1 | 规则注册表作用域 | **Context 级常驻**（`RouteRegistry.CONTEXT_PATTERNS`） | **用例级**（runtime 随用例 `shutdown`） |
| 2 | 收尾 unroute | **实际从不执行**：`RouteRegistry.clearContext` 仅当 `CONTEXT_PATTERNS.remove(new ContextKey(context))` 非 null 才 `unrouteAllForContext`；而该 WeakHashMap 键是即用即弃的 `ContextKey`（缺陷见 ID 70362232）⇒ `patterns` 恒 null ⇒ **跳过** | 每用例逐条 unroute（实测 `unroute:profile/list` 10s 未确证） |
| 3 | 用例级停用手段 | **纯内存**：`MonitorSession.stopped` 标记 + handler 内 `resume` 放行（源码注释明写"不调用 unroute，避免 Playwright 线程竞态"） | 协议调用（`retireByPurpose` / teardown flush / `retryUnroute`） |
| 4 | 每用例协议调用数 | **≈ N 次 bind + 0 次 unroute** | **N 次 unroute + N 次 bind** |
| 5 | 有界守卫 / 驱动线程 | 无 `GuardedDriverCall`、无专用驱动线程、无毒化重置（注册就在调用线程上泵消息） | 有（30s/10s + `route-v2-driver-N` + poison reset） |
| 6 | 相同项（非差异） | `restart=feature`、mock `notifications/streams`、SSE 重连风暴 —— **两边都有**，不是致病因子 | 同左 |

⇒ **master 实际跑的就是"档 B（T7）：规则随 Context 常驻、用例级纯内存停用、收尾零协议调用"**；
我们一直在"档 A（每用例 bind+unroute）"上打补丁。本故障的引信是 #2/#4：**每次失败都始于某条 unroute 未拿到回包
（两次实跑均是 dbb-1 的 `unroute:profile/list`），随后 dbb-3 的 bind 才卡 30s**。

**T8-5 在本轮未生效的实证**：用例收尾走的是 `PlaywrightManager.clearCurrentThreadRouteState()`
（`PlaywrightManager.java:243-252`）→ `RouteLifecycleRegistry.clearContext/stopContextEngine`，
**未传 `contextBeingClosed`** ⇒ 仍逐条 unroute；T8-5 只改了 `ContextRegistryImpl.cleanupContextScoped` 那条链。
（注意：feature 档 Context 不关时**不能**简单跳过 unroute，否则规则残留 ⇒ 见下条。）

**对齐 master 的正确动作**：推进 **T7（档 B）** —— 规则随 Context 常驻，用例级用 V2 已有的
`stop(capability, pattern)`（纯内存、零协议调用）停用，收尾**不发任何 unroute**，Context 关闭时由驱动原生释放。
短期替代：业务切 **`credential` 档**（每 scenario 新 Context + close 释放），与 master 的"随 close 释放"等价。

---

## 5. 任务列表（本轮，逐步修复）

### 5.0 详细设计与取舍（2026-09-28 结论，**先读这一节再动手**）

**① 目标模型：规则随"目的"生灭（取代"规则随用例生灭"）**
- 用户的裁定：**每一条规则达到目的就 unroute，无论断言成功或失败**。
  例：MONITOR 断言 status code —— 断言一旦定案（成功或失败）即撤销该规则。
- 收益：规则 **armed 窗口最短**；不再依赖"下个用例全量 bind"的隐性修复；失败面从"一次收尾失败 ⇒ 全部残留"
  收敛为"一次撤销失败 ⇒ 只留这一条，且当场可知"。

**② "兜底"不可取消，但**绝不重建 Context**（2026-09-29 裁定）**
- 现状的兜底是**隐式且概率性的**：客户端每次下发都是**本地 Router 全量快照**，所以下个用例的 `bind`
  会**覆盖**驱动侧 pattern 列表，顺带修复"两边不一致"；**但若该次 bind 也超时（dbb-3 正是），兜底即失效**。
- 正确做法不是"少调用"，而是：**让"是否干净"可判定** ——
  **确证成功 = 真干净**；**确证失败/不确定 = 立即重发一次重同步（`PatternBinder.retryUnroute()`）自愈**；
  仍不可确证 ⇒ **响亮 ERROR + 计数暴露**（可见的降级）。
- **不得重建 Context**：重建会破坏不变式 I-1（feature 模式下同一 sessionKey 不重建）并丢掉免登录收益；
  **路由是辅助设施，其状态不确定性不得绑架会话/Context 生命周期**。

**③ 两档实现（按约束强度，A → B）**
| 档 | 做法 | 前提 | 调用量 |
|---|---|---|---|
| **A（先做）** | 保留"逐条 unroute 我们拥有的 pattern"，但把**清理结论显式化**：`PatternBinder.close()` 回传"是否确证撤销"，`RouteRuntimeImpl.close()` 汇总 ⇒ `CLEAN / DEGRADED`；`DEGRADED` ⇒ 粘性标记 ⇒ **丢弃重建** | 无 | 不变（逐条） |
| **B（后续优化）** | 规则随 Context 常驻，用例级用 `stop(capability, pattern)`（**纯内存、零协议调用**）实现"无业务活动"；用例开始做一次**对账**（1 次全量下发 + ack 确证），失败即重建 | **route 所有权审计通过**：该 Context 的 context-level route 只有 `RouteDsl2` 注册 | O(1)/用例 |

**④ 关键实现约束（防新问题）**
1. **不得在 route handler 内同步 unroute**：handler 跑在泵线程（`ChannelOwner.runUntil`），而 `unroute`
   是同步协议调用（`BrowserContextImpl:715-722`），在 handler 内调用 = **嵌套下发**（客户端本身每命中
   已嵌套下发一次）。撤销必须**提交到安全线程**（T6 的专用驱动线程/IO 池），不阻塞 `resume/fulfill` 收尾。
2. **撤销必须确证**（回传 `CONFIRMED / UNCONFIRMED`），不能 fire-and-forget（现状 `close()` 仅 WARN）。
3. **先记录断言/settle 结论，再撤销**（否则丢失败证据）；撤销失败只影响"干净度"判定，不改断言结论。
4. **批量化下发**：客户端下发是全量快照 ⇒ 若能确认所有权，一次下发可覆盖一批撤销；否则逐条（仍优于现状，
   因为有确证 + 失败即重建）。
5. **"目的"内建于框架、按能力默认，不新增任何 DSL**（2026-09-29 裁定：业务侧零声明）：
   <table>
     <tr><td>MONITOR（有响应期望）</td><td>响应定案即撤（成功/失败都撤）</td></tr>
     <tr><td>MONITOR + autoStopOnMatch(minMatches=N)</td><td>达标（hits ≥ N）即撤；未达标不提前撤</td></tr>
     <tr><td>MONITOR（有窗口）窗口内未匹配/未定案</td><td>窗口到期即撤（monitorTimeoutMs；0=不设该上限）</td></tr>
     <tr><td>MOCK / MODIFY / DELAY + times(n)</td><td>n 次用尽即撤（Playwright 客户端自动注销）</td></tr>
     <tr><td>MOCK / MODIFY / DELAY（无 times，持续服务）</td><td><b>用例收尾兜底 flush</b>；<b>默认不得首撤</b>（否则放行真实响应/真实时序 ⇒ 行为漂移 = 非业务层失败）</td></tr>
     <tr><td>任一撤销未确证</td><td>重发全量快照重同步自愈；仍失败 ⇒ ERROR + 计数（不重建 Context）</td></tr>
   </table>
6. **竞态清单（2026-09-29 评审 + 修复状态）**：

   | # | 竞态 | 处置 | 状态 |
   |---|---|---|---|
   | **R-1** | 旧触发者（窗口到期任务 / 响应定案回调）撤掉"同 pattern 重注册后的**新规则**" | 撤销入口**令牌化**：`retireByPurpose(ApiSpec)` 用代际表 `generations.specFor(pattern)` 判定"当前规则实例"，不一致即拒绝 | ✅ 已修（含竞态守护单测） |
   | **R-2** | 多路触发重复撤销（sink / 窗口 / 收尾） | `binders` **两参原子移除** + `closeConfirmed()` CAS 幂等 | ✅ 已闭 |
   | **R-3** | 与在途交换冲突 | 触发点必须在"终结命令已发出 / 定论已记录"之后；MOCK/MODIFY/DELAY **默认不从命中路径触发** | ✅ 已闭 |
   | **R-4** | 撤销等待占住 IO 线程 ⇒ 挤压 body 断言（框架自身导致的观测缺失） | **独立单线程撤销执行器**（有界队列、拒绝即 fail-open、任务异常隔离；容量/超时为内部常量） | ✅ 已修 |

   **不新增任何配置**（2026-09-29 裁定）：能力默认表即为全部；目的驱动撤销（规则随目的生灭）为<b>不可关闭的默认行为</b>（无 kill-switch），仅保留既有 `-Droute.dev.*` 调试类开关。

**⑤ 三条根治方向（结构上不让它发生）**
| # | 方向 | 依据 | 备注 |
|---|---|---|---|
| R-1 | **规则生命周期与 Context 对齐**（档 B） | 每用例全量 unroute + bind 是唯一咽喉 | 需所有权审计；`clearContext` 语义要从"销毁"改为"停用能力位" |
| R-2 | **流式/高频端点不进 route**（SSE 用 `addInitScript` 顶掉 `EventSource`，或仅 `abort()`） | 每命中重发全量 pattern；对 SSE 做 mock 还要把流式 body 交给 handler（`route.fetch handle reclaimed` 即其代价） | 业务 + 框架 helper 各一半 |
| R-3 | **框架侧驱动调用单线程化 + 毒化重建** | 客户端本就要求外部串行；`Object doesn't exist` 是并发的直接产物 | 单点阻塞 ⇒ 必须配超时 + 重建 |

**⑥ 明确不做**
- 不为"少调用"取消重建能力（I-9）；
- 不在 T6 之前引入重试（残留泵线程会放大共享连接上的并发竞态）；
- 不把"阈值无限放大"当归档型修复（T3 只是把容忍度调到与环境相符）。

**⑦ 客观判定标准（根治是否成立）**
1. 每用例 `setNetworkInterceptionPatterns` 次数（≈ 命中数 + bind/unroute 数）从"数十次"降到 **个位数/context**（T1 报表）；
2. `Object doesn't exist` 计数 **归零**（T6 后）；
3. `runtime installed/closing` 次数 = **Context 数**（T7 后），不再是用例数；
4. 日志中 **不出现 `DEGRADED`**、不出现 `driver call … timed out`。

**⑧ 执行顺序**
`T2（档 A 止血：清理确证 + 粘性标记 + 丢弃重建）` → `T2+（目的驱动撤销）` → `T1（度量）` → `T5（文案/看门狗）`
→ `T6（单线程化 + 毒化重建）` → `T7/R-1（档 B，需所有权审计）`；R-2 可与 T6 并行（业务 + helper）。

### 5.1 任务状态表

| 编号 | 任务 | 状态 | 动作 | 验收 | 回退开关 | 风险 |
|---|---|---|---|---|---|---|
| **T3** | 有界阈值（硬编码 30s/10s） | ✅ 完成 2026-09-28（2026-09-29 依用户裁定移除系统属性覆盖） | bind/unroute 固定 **30s/10s** 有界等待（客户端 `sendMessage` 无超时，必由框架提供）；**不经系统属性覆盖**（2026-09-29 移除 `route.v2.bind.bound.ms`/`route.v2.unroute.bound.ms`，杜绝误配置取消有界保护） | `GuardedDriverCallBoundsTest` 2 例；既有契约测试绿 | 无（阈值硬编码） | 真卡死时等待变长（仍有界） |
| **T4** | 注册超时不再让业务失败（硬编码 degrade） | ✅ 完成 2026-09-28（2026-09-29 依用户裁定移除系统属性开关） | 注册超时固定 **degrade**（无 fail-fast 开关）：超时→惰性绑定 + ERROR（行为类能力额外 WARN）；**真异常绝不吞**；降级即置 runtime degraded | `PatternBinderRegistrationFailureTest` 4 例（含 runtime 级） | 无（硬编码 degrade） | 静默失效风险由双档告警抵消 |
| **T2** | ~~病态 Context 丢弃重建~~ → **改为：自愈 + 可见告警（绝不重建）** | ✅ 完成 2026-09-29 | ①②`close()` 回传**确证结论** + 收尾汇总；③ 未确证 ⇒ `retryUnroute()` **重发全量快照重同步自愈**；④ 仍不可确证 ⇒ ERROR + `unconfirmedRetirements` 计数 + `degraded`（**仅可观测**）；⑤ **绝不重建 Context**（裁定 2026-09-29：与 I-1「同一 sessionKey 不重建」一致 —— 路由是辅助设施，不得绑架会话生命周期） | `RouteRuleRetirementTest` 6/6（含"首次注销无回包 ⇒ 重同步自愈且不降级"、"窗口到期未匹配 ⇒ 清理结束"） | 无（目的驱动撤销为默认不可关行为） | 无（不再有重建副作用） |
| **T2+** | 目的驱动撤销（**规则随目的生灭**） | ✅ 第一/二步完成 2026-09-28 | **第一步（地基）**：`PatternBinder.closeConfirmed()`（撤销**可确证**）+ `RouteRuntime.retireByPurpose/isClean/unconfirmedRetirements` + 收尾兜底 flush **汇总确证结论**（未确证 ⇒ 降级）；撤销**绝不在事件线程同步执行**（提交独立撤销执行器）。**第二步（触发点）**：`MonitorSink` 在"**首个响应定案**"回调 `retireByPurpose` —— 不管断言成功/失败；这是<b>不可关闭的默认行为</b>（无 kill-switch） | `RouteRuleRetirementTest` 4/4 + `MonitorSinkPurposeRetirementTest` 4/5；全量构建绿 | 无（目的驱动撤销为默认不可关行为） | 默认把 armed 窗口从"整个用例"缩到"首个响应"⇒ 同 pattern 的**后续**响应不再被断言；需多次断言的场景用 `autoStopOnMatch(minMatches)` 控制（规则级 opt-out） |
| **T1** | 度量基线 | ✅ 完成 2026-09-29 | 每用例（= 每 runtime 生命周期，随 scenario 收尾重建自动重置）统计：下发次数（`RouteDispatcher.dispatchCount`，进入分发器即 +1）、路由命中数（`hitCount`，= 通过匹配条件且未停止）、每条规则 armed 时长（`register` 计时起点 → `retireByPurpose`/teardown 定稿于 `ruleArmedDurationsMs`）、**未确证撤销次数**（`unconfirmedRetirements`）；`close()` 收尾输出 `[RouteV2] case metrics @…` 一行（报表可见、真跑归档）；`RouteV2Metrics` 扩展上述字段 | `RouteV2MetricsTest` 3/3；全量构建绿（202 单测） | 无（仅日志/指标） | 无（armed 时长在纳秒级测试里会显示 0ms，真跑为真实值） |
| **T5** | 文案与看门狗去误报 | ✅ 完成 2026-09-29 | ① 文案：`GuardedDriverCallImpl` FAIL_FAST 超时由误导的 `driver unresponsive` 改为「未收到回包（驱动忙 / 客户端共享连接被并发占用）」并附 `DEBUG=pw:channel` 排查提示；② 看门狗去误报：route2 `HangWatchdog` 与 web `HangWatchdog` 的 `sample()` 由"每次采样即 WARN"改为"**同栈集合连续 N 次（默认 `*.hang.watchdog.frozen.samples=3`）采样不变才 WARN**"，且同一冻结剧集只报一次（避免刷屏）；硬超时/interrupt 语义不变 | `GuardedDriverCallMessageTest` 1/1；`RouteV2HangWatchdogTest`（frozen 三例新增）3/3；web `HangWatchdogFrozenTest` 3/3；route2 全量 206 单测绿 | 回退开关 `route.v2.hang.watchdog.frozen.samples` / `serenity.playwright.hang.watchdog.frozen.samples`（≤0 视为每次都报；`interval.ms=0` 整体关闭看门狗） | 仅诊断行为；route2 看门狗目标在 §7 整体删除（含 web） |
| **T6** | 驱动调用单线程化 + 毒化重置（**绝不波及 Context**） | ✅ 完成 2026-09-29 | 参照 `AsyncPool.MONITOR_CALLBACK_EXECUTOR` 形态：`GuardedDriverCallImpl` 内置**全局守护单线程执行器**（`volatile` + 工厂构造、`route-v2-driver-<epoch>` 守护线程），所有 guarded 同步驱动协议调用（bind/unroute）收敛到该线程**串行执行**（消除共享连接并发竞态、根治 `Object doesn't exist`）；**毒化重置**：某调用界内无回包（超时）→ 主线程按策略 fail-fast/降级返回，同时 `compareAndSet` 换新建执行器 + 旧 `shutdownNow()` 遗弃（`POISON_RESETS` 计数），**仅重建驱动线程，绝不关闭/重建 BrowserContext 与 Page**（Context 生命周期由 sessionKey 判据唯一掌管，见 I-9 / T2 裁定）；阈值 30s/10s 硬编码（与 T3 同值） | `GuardedDriverCallSingleThreadTest` 3/3（单线程串行收敛、并发不丢回包、毒化重置后新调用正常返回且 Context 未动）；全量构建绿 | **无（无条件默认行为；纯内部线程管理，成功路径不变）** | 单点阻塞 ⇒ 配套超时；毒化只作用于驱动线程，不向上蔓延到 Context；`route.fetch`/`page.request` 维持 `BoundedOps`/IO 池并发（串行化会回归并发请求吞吐，其 channel 竞态已由 T2 的 claim/`closeConfirmed` 收敛），不在 T6 单线程化范围内；驱动进程真死（非线程级停顿）不属 T6 范围 |
| **T7** | 档 B：规则随 Context 常驻 + 用例级 `stop` | ⏳ 待评估 | 见 §5.0 ③ 档 B | 每用例下发次数降至 O(1) | 同 T2 开关 | **前置：route 所有权审计**；未通过则维持档 A |

---

## 6. 运行诊断手册（取证，不靠猜）

| 目的 | 手段 |
|---|---|
| 看协议层每条消息收发（判定"驱动未回"还是"客户端丢回包"） | `DEBUG=pw:channel`（客户端 `Connection.isLogging` 判定）；驱动侧 `DEBUG=pw:browser*` |
| 抓卡死瞬间线程现场 | `-Droute.v2.hang.watchdog.interval.ms=1000`（必要时 `-Droute.v2.hang.watchdog.hard.timeout.ms=15000`） |
| 关键日志检索 | `driver call` / `DEGRADED` / `runtime degraded` / `Object doesn't exist` / `Cannot find command to respond` / `runtime installed` / `closing runtime` / `case aborted` |
| 会话侧判定 | `[Session] storageState captured …`、`Session cleared successfully`、`Session expired for: …`（verbose） |

**判定规则**：`setNetworkInterceptionPatterns` 的**回包出现过** ⇒ 客户端路径问题（记账/泵线程）；
**从未出现** ⇒ 驱动/浏览器侧未响应。

---

## 7. HangWatchdog 去除清单（原 18 号文档 §5 保留）

业务测试全量迁 route2 且稳跑 ≥2 周（零复现）后一并去除 web 与 route2 的临时诊断：

1. 删 `PlaywrightSerenityBridge.initializeForScenario` 中的 `HangWatchdog.onScenarioStart(...)` 调用；
2. 删 `PlaywrightSerenityBridge.cleanupForScenario` 中的 `HangWatchdog.onScenarioEnd()` 调用；
3. 删 `pw-web-ui` 的 `HangWatchdog.java`（含 `HangEvent` / `consumeHardHang`）；
4. 删 `pw-web-ui` 的 `HangWatchdogRenderTest.java`；
5. 清理测试中对 `consumeHardHang` 的断言（仅测试引用，无 prod 消费方）；
6. 删 `pw-route-v2` 的 `route.v2.diag.HangWatchdog.java`；
7. 删 `RouteRuntimeImpl` 构造末尾 `arm()` 与 `close()` 开头 `disarm()` 两处挂接；
8. 清理 `route.v2.hang.watchdog.*` 系统属性（如写入配置模板）。

**去除前提**（web 独立任务）：web 层自身卡死路径也需设界 —— SSO 登录（业务侧 `switchProfile` 实测阻塞 195s）、
业务锁 / Playwright 原生 poll（不可中断）、Serenity 自身。否则去除后回到"静默卡死无诊断"。

**route2 阻塞点收口现状（审计结论，去除后不受影响）**

| 调用 | 位置 | 收口方式 |
|---|---|---|
| `context.route()` | `PatternBinder.bind` | `GuardedDriverCall`（daemon + 有界，默认 30s） |
| `context.unroute()` | `PatternBinder.close` | `GuardedDriverCall`（WARN_AND_ABANDON，默认 10s） |
| `route.fetch()` | `RouteAction.fetch` | `BoundedOps` + IO 池（事件线程严禁），含 fetch 超时 |
| `context.onResponse` / `onClose` | `RouteRuntimeImpl` | 回调注册（非阻塞）；**刻意不订阅 `onResponse`**（`Object doesn't exist` 竞态源） |
| `ClaimRegistry.sweep` | daemon 巡检 | 超龄强制落定（fail-open） |

ArchUnit 硬约束（`RouteV2ArchitectureTest`）：除 `PatternBinder` / `GuardedDriverCall*` 外禁止直接调
`BrowserContext.route/unroute/Route.close`；除 `RouteAction`/`RouteIoExecutor`/`GuardedDriverCall*` 外禁止直接调 `Route.fetch`。

---

## 8. 回退开关总表

| 属性 | 默认 | 作用 |
|---|---|---|
| ~~`route.v2.degraded.discard`~~ | — | **已废除**（2026-09-29 裁定：degraded Context **绝不丢弃重建**，路由为辅助设施不得绑架会话生命周期）；代码与配置表均无此属性 |
| `route.v2.hang.watchdog.interval.ms` | `60000` | route2 看门狗采样间隔（0=关） |
| `route.v2.hang.watchdog.hard.timeout.ms` | `0` | route2 看门狗硬超时（0=禁用） |
| `serenity.playwright.hang.watchdog.interval.ms` | `60000` | web 版看门狗采样（0=关） |
| `serenity.playwright.reuse.context.within.feature` | 见配置 | feature 内无会话时是否仍复用 Context |
| `serenity.playwright.concurrent.partition.enabled` | `auto` | 同 sessionKey 并发闸门（串行下 no-op） |
| `playwright.no.login.session.timeout.minutes` | `5`（业务 20） | 会话兜底有效期（全部 cookie 无 TTL 时生效） |
| `playwright.no.login.session.include.indexed.db` | `false` | 会话是否含 IndexedDB（待实测确认必要性） |

---

## 9. 验收标准

**构建级**：`mvn -o clean install -Dspotbugs.skip=true -Dcve.gate.skip=true` → BUILD SUCCESS，各模块 0 失败、Checkstyle 0 违规。

**真跑级（`login_dbb.feature`）**：
1. 无 `Failed`；无 `DEGRADED` 日志；无 `driver call … timed out`；
2. `runtime installed/closing` 次数 = **Context 数**（T7 后），而非用例数；
3. `Object doesn't exist` 计数 **归零**（T6 后）；
4. 每用例下发次数/命中数/未确证次数可见（T1）；
5. 会话侧：不再出现"无故重登"；不出现登出 overlay 相关失败。

---

## 10. 凭证克隆档（`credential`）：Context 生命周期 × 会话复用 解耦（2026-09-30 立项）

> 本章是「feature 模式复用活 Context」路线的**替代方案**，目标是从结构上消灭 §4 的病态 Context 遗传。
> 立项依据：2026-09-30 真跑实证（dbb-1 收尾 `unroute:profile/list` 10s 未确证 → `degraded`；
> dbb-3 首个 `bind:notifications/streams` 卡满 30s 硬失败）—— **失败链全部落在"清理"路径上**。

### 10.1 不变式增补

| 编号 | 不变式 |
|---|---|
| **I-11** | 隔离靠**不共享**，不靠清理：`credential` 档每 scenario **独立 Context（用完即弃）**，登录态以**不可变 storageState 凭证**注入；不存在"跨 scenario 存活的可变对象" |
| **I-12** | 重启策略实际承载**三个正交维度**：① Context 生命周期（跨 scenario 复用 / 每 scenario 新建）② 会话复用方式（活 Context 延续 / 凭证注入 / 零复用）③ **Browser 生命周期**（跟随 Context 关闭 / 跨 scenario 保留）。三者不得再用同一个配置值隐式绑定；档位判定必须经**统一判据方法**（禁止散落字符串比较） |

### 10.2 三档矩阵

| 维度 | `feature`（现状） | `scenario`（现状） | `credential`（新增） |
|---|---|---|---|
| Context 生命周期 | 跨 scenario 复用 | 每 scenario 新建/关闭 | **每 scenario 新建/关闭** |
| **Browser 生命周期** | 保留（因 Context 不关） | **跟随 Context 关闭** | **必须保留**（否则退化为"每用例重启浏览器"） |
| 登录态来源 | 活 Context 延续 | 完整登录 | **storageState 凭证注入** |
| 登录成本 | 1 次/feature | N 次 | **1 次/凭证有效期** |
| scenario 隔离 | 靠清理（易污染） | 对象级 | **对象级** |
| route 绑定 | 每 scenario bind + unroute | 每 context bind，close 释放 | **每 context bind，close 释放** |

### 10.3 评审校正（原始提案的 5 处偏差，**动手前必读**）

| # | 提案原表述 | 校正（代码实证） |
|---|---|---|
| **C-1（阻断）** | 收尾判定改 `PlaywrightListener` / `ContextRegistryImpl` | **落点错误**：`PlaywrightListener:1130` 只打日志；真正执行关闭的是 `PlaywrightSerenityBridge:598`（`if ("scenario")` 正向判断，**else = feature 语义**）。`credential` 会静默落入 else ⇒ 不关 Context、走 `resetCustomContextOptionsForFeatureMode`、不 `resetFeatureSession`。同形态还有 `PlaywrightSerenityBridge:499`（`initializeForScenario`） |
| **C-2** | 只需改 5 处 | 实际 **8 处**字符串比较：`SessionStore:556/582/761/800/921`、`PlaywrightSerenityBridge:499/598`、`PlaywrightListener:1130`。且档位读取是裸 `getString`、**无枚举校验** ⇒ 拼错静默回落。必须收敛为统一判据方法 |
| **C-3** | credential 档跳过 `markFeatureSessionRestored` | **必须拆**：该方法（`SessionStore:393-399`）同时做 ① `FEATURE_SESSION_BY_KEY` L1 缓存登记（credential **不需要**）与 ② `bindCurrentContextSessionKey`（I-1/I-2/A5 判据，收尾 `PlaywrightSerenityBridge:619` `contextHoldsLogin` 读它 —— credential **必须保留**，否则收尾按"无登录态"处理，失败时丢 Page/关 Context 策略分叉） |
| **C-4** | 第 5 项 = "验证并确认无逐条 unroute" | **不是验证，是实现**：`RouteRuntimeImpl:463` 已有 `contextClosing` 短路，但它**只在 `context.onClose` 事件路径置位**（`closeAfterContextClosed():435`）；框架主动收尾是 `stopContextEngine`（→ `runtime.close()`）**先于**真实协议 close ⇒ `contextClosing=false` ⇒ **仍逐条 unroute，收益归零**。须显式传"正在关闭"意图 |
| **C-5** | 协议调用 N×2 → N×1 | **口径失真**：credential 档每 scenario 另增 `newContext` + `newPage` + `context.close()`（同为 `sendMessage("close")` NO_TIMEOUT 往返）。准确口径：**unroute 由 N 次降为 0 次**；总调用量大致持平。真实收益 = 消除"不可判定的未确证状态" + SSE 不再跨 scenario 存活 |

### 10.4 任务表（编号承接 T7）

| 编号 | 任务 | 状态 | 动作 | 验收 | 回退开关 | 风险 |
|---|---|---|---|---|---|---|
| **T8-0** | 判据收敛（地基，零行为变更） | ✅ 完成 2026-09-30 | 在 `PlaywrightConfigManager` 新增 `RESTART_STRATEGY_*` 三档常量 + 三个判据谓词（按 scenario/credential/feature 分流）；替换 `SessionStore:556`、`PlaywrightSerenityBridge:499/598/671`、`PlaywrightListener:1130` 五处散落比较（剩余 5 处为 feature-only 语义，分属 T8-2/T8-3）。**第三维度（Browser 生命周期）由业务配置 `=feature` 反推发现**：credential 复用 scenario 分支会连带关闭 Browser ⇒ 每用例重启浏览器，必须单独门控 | `mvn -o -pl pw-web-ui test` → **381 例 0 失败 0 错误 BUILD SUCCESS**（`scenario`/`feature` 行为逐字不变） | 无（纯重构；对两档存量行为零影响） | 无 |
| **T8-1** | 档位文档 + 未知值告警 | ✅ 完成 2026-09-30 | `ConfigKeys`（`WEB_SERENITY_PLAYWRIGHT_RESTART_BROWSER_FOR_EACH`）注释补第三档 `credential`；档位读取新增未知值**一次性** WARN（防拼写静默回落为 feature 语义） | `PlaywrightConfigManagerRestartStrategyTest` 5 例（含"拼错值不被任何谓词认领"的护栏） | 无（仅日志） | 无 |
| **T8-2** | `SessionStore` 放行 credential 复用文件 | ✅ 完成 2026-09-30 | `reusePersistedSession:559` 走统一判据（T8-0 已改）；两个 `restoreSession` 重载改**三档分流**（全经谓词，无裸字符串比较）：feature→`tryFeatureCacheHit`；credential→跳过 L1 缓存 + `discardContextIfBoundToOtherSessionKey` + 新增 `logCredentialReuse`（打印凭证年龄/剩余寿命）；scenario→`logScenarioModeNoReuse` | `SessionStoreCredentialModeTest` 3 例（免登录不登录 / 不登记 feature L1 缓存 / 无凭证回落登录）；`mvn -o -pl pw-web-ui test` → **389 例 0 失败**（381+5+3） | 切回 `scenario`/`feature` | 复用不刷新 `lastAccessTime`（S-1a）⇒ 到点必重登，已由 `logCredentialReuse` 的 INFO 可观测 |
| **T8-3** | 拆 `markFeatureSessionRestored` | ✅ 完成 2026-09-30 | 新增 credential 档识别判据；`reusePersistedSession` 与 `saveSession` 两处改为：credential → **只** `bindCurrentContextSessionKey`（标记"承载登录态"，供 I-5 localStorage 保留判定 + 异常收尾 `contextHoldsLogin` 使用）；feature → `markFeatureSessionRestored`（绑定 + L1 缓存）；scenario → 都不做。**必要性复核**：T8-0 把 credential 路由到 scenario-scoped 收尾后，`contextHoldsLogin` 仍被 `PlaywrightSerenityBridge:306`（localStorage 保留）与 `PlaywrightManager.clearThreadResourcesOnCaseAbort`（异常收尾）消费 ⇒ 不绑定会出现"注入了凭证却按无登录态处理"的分叉 | `SessionStoreCredentialModeTest` 4 例（新增"绑定 sessionKey 但不登记 L1 缓存"）；`mvn -o -pl pw-web-ui test` → **390 例 0 失败** | 切回 `scenario`/`feature` | 若误登记 L1 缓存 ⇒ 退化成复用活 Context（已由单测断言守住） |
| **T8-4** | 桥收尾/初始化走 scenario-scoped | 🔶 代码已随 T8-0 落地，待真跑验收 | `PlaywrightSerenityBridge:499/598` 走统一判据；Browser 关闭门控走统一判据（**已在 T8-0 落地**）；补 `PageObjectFactory.clearAll()/endRequestScope()`（否则 PageObject 持 Context 引用随 scenario 累积）；`PlaywrightListener:1130` 日志同步 | credential 档每 scenario 真关 Context **但 Browser 进程存活**（日志无 browser 重启）；headed 下窗口不堆积 | 同上 | 关闭时机变化 ⇒ 需 `CloseGuard` 兜底（已有） |
| **T8-5** | route 主动关闭短路 | ✅ 完成 2026-09-30 | 四层改动：① `EngineControl` 新增 **default** `stopContextEngine(ctx, contextBeingClosed)`（退化为原方法，pw-route 与所有测试替身零改动）；② `RouteRuntime` 新增 **default** `markContextClosing()`；③ `RouteRuntimeImpl.markContextClosing()` 置位（`closeAfterContextClosed()` 改为复用它）+ `RouteEngine2.shutdown(ctx, boolean)` + `RouteLifecycleV2Impl` 覆写；④ `ContextRegistryImpl.cleanupContextScoped` 传 `true`。**前提已验证**：该方法的三条调用路径（closeContext / scheduleContextRebuild / recreateContextIfCustomConfigNeeded）末尾<b>都</b>执行 `PlaywrightContextManager.closeContext` ⇒ 传 true 安全；已在注释中固化为不变式"不得新增跳过 close 的提前 return" | `RouteShutdownSkipsUnrouteTest` 2 例（传 true ⇒ 无任何 `unroute:` 调用；传 false ⇒ 照旧 unroute，供 feature 档"Context 仍存活"路径）；`pw-route-v2` **233 例**（231+2）0 失败；`pw-web-ui` 390 例 0 失败 | 撤掉 `true` 即恢复逐条 unroute | 若误用于"context 仍存活"路径 ⇒ 规则残留在驱动侧；已由不变式注释 + 反向用例守住 |
| **T8-6** | P2：探针 + 凭证代际 | ✅ 完成 2026-09-30 | ① 实现 `PROBE_ON_REUSE`（原为设计钩子）：新增凭证探针 —— **仅 credential 档**在注入后真导航 homeUrl，用**真实落地 URL** 判定（含 `/logon` ⇒ INVALID ⇒ 清凭证 + 本 scenario 单飞重登；导航异常/无 Page ⇒ **UNKNOWN，按有效处理**，避免网络抖动引发登录风暴）；② `SessionMeta` 增 `generation`，`saveSession` 时自增（复用不增），`saveMeta` 落盘 + `META_CACHE` 同步 | `SessionStoreCredentialModeTest` 7 例（新增：落地 logon ⇒ 重登一次；落地 homeUrl ⇒ 不重登；代际随完整登录 1→2）；`mvn -o -pl pw-web-ui test` → **393 例 0 失败** | 同上（其它档位探针直接返回 false，零副作用） | 探针误判 ⇒ 无谓重登。判定口径刻意保守（只有"确实被打回 /logon"才算失效），且 WARN/INFO 双留痕 |

### 10.5 验收（复用 §9 口径）

- **P0（T8-0~T8-4）**：`login_dbb.feature` 三 scenario 真跑 —— 每 scenario **免登录**、**无 `driver call … timed out`**、`dispatches>0/hits>0`、headed 下窗口不堆积。
- **P1（T8-5）**：收尾无 `unroute:` 协议调用、`unconfirmedRetirements=0`；`[DBBN-PATCH-01]` dropped 计数不随 scenario 数线性恶化。
- **P2（T8-6）**：人为置空凭证 ⇒ 仅该 scenario 重登。
- **回归**：`mvn -o clean install -Dspotbugs.skip=true -Dcve.gate.skip=true` 全绿；`scenario`/`feature` 两档存量行为零变化。

### 10.6 风险与回退

| 风险 | 处置 |
|---|---|
| ~~非 storageState 状态丢失（未落盘 profile 切换、页面内存态）~~ | ✅ **已确认解除（2026-09-30 业务确认 + 代码实证）**：`LoginSteps.switchProfile:245` 在切换 profile 后调用 `SessionManager.saveSession`。精确口径：保存发生在 `if (!readProfileText().contains(profile))`（`:234`）守卫内 —— **仅在真的发生切换时**保存；已切过则会话本就带该 profile。克隆场景下若凭证未带该 profile，新 Context 的 `readProfileText()` 必然不含 ⇒ 自动走到切换分支并保存，**自纠正**，无需业务改动 |
| `context.close()` 偶发挂起 ⇒ 孤儿 Context | `CloseGuard` 已有界；**必须补"放弃后登记 + 下轮回收"**（不能只记数） |
| ~~`ScenarioTraceRecorder` 行为变化（feature 档一个 trace 覆盖整 feature → credential 档每 scenario 一段）~~ | ✅ **已确认可接受（2026-09-30 业务拍板）**：credential 档 trace 粒度变为每 scenario 一段（与 scenario 档一致），业务不依赖"整 feature 单 trace" |
| 并行下同一凭证 N 份克隆同时在线 | 保留 `acquireSessionGate`（per-sessionKey 串行）；同 profile 并行在线属 P3 业务拍板项 |
| 回退 | `credential` 是**新增档**，默认值不变；改回 `scenario`/`feature` 即回退，存量用例零影响 |

---

## 11. 变更记录

| 日期 | 变更 |
|---|---|
| 2026-09-28 | 建立本文件；合并并删除 §0 所列 4 份旧文档；T3（阈值可配）、T4（注册失败策略）完成并验收 |
| 2026-09-28（v2） | ① 新增 **§2.0 时效声明**：修正"失败丢弃 Page"等 7 条已被取代的历史结论；② 修正 **I-1**（同 sessionKey 失败时 **Context+Page 均保留**）；③ 新增 **§5.0 详细设计与取舍**：目的驱动撤销模型、兜底不可取消（确证/重建）、档 A/档 B、三条根治方向（R-1~R-3）、明确不做、客观判定标准、执行顺序；④ 同步修正源码 javadoc：`PlaywrightSerenityBridge.cleanupForScenario`、`PlaywrightManager.cleanupForScenario`、`FrameworkCore.afterTest`（原描述与 2026-09-28 后行为矛盾） |
| 2026-09-29（v3） | ① **I-9 修订**：不可确证干净 **不再丢弃重建**，改为"重发全量快照重同步自愈（`PatternBinder.retryUnroute()`）+ 响亮 ERROR / 计数"（路由是辅助设施，不得绑架会话生命周期；与 I-1 一致）；② **T2 变更**：病态 Context 丢弃重建方案**取消**；③ **T2+ 完成两步**：`closeConfirmed/isClean/unconfirmedRetirements/retireByPurpose` + `MonitorSink` 首个响应定案即撤（默认 `monitor-always`）；④ **规则生命上限**：`monitorTimeoutMs` 到期仍未匹配/未定案 ⇒ 清理结束（不留悬挂规则）；⑤ **`autoStopOnMatch` 细化**：达到 `minMatches` 才撤，未达标由窗口到期/收尾 flush 清理 |
| 2026-09-30（v6） | **T8 全条落地**：T8-0~T8-3、T8-5、T8-6 完成并验收（pw-web-ui **393 例**、pw-route-v2 **233 例** 0 失败；全 reactor `mvn -o install -DskipTests` **BUILD SUCCESS**）；T8-4 代码随 T8-0 落地、待真跑验收。§10.6 两项业务侧风险**闭环**：① profile 状态丢失风险解除（`LoginSteps.switchProfile:245` 确已 `saveSession`，且"仅在真切换时保存"在克隆场景下自纠正）；② `ScenarioTraceRecorder` trace 粒度变化（整 feature → 每 scenario）经业务拍板可接受 |
| 2026-09-30（v5） | 新增 **§10 凭证克隆档（`credential`）**：立项依据为当日真跑实证（dbb-1 `unroute:profile/list` 10s 未确证 → dbb-3 `bind:notifications/streams` 30s 硬失败）；增补 **I-11**（隔离靠不共享，不靠清理）与 **I-12**（Context 生命周期 × 会话复用为正交维度、禁止散落字符串比较）；**§10.3 记录对原始提案的 5 处评审校正**（C-1 收尾判定落点应在 `PlaywrightSerenityBridge:598` 而非 `PlaywrightListener`；C-2 实为 8 处比较且档位读取无枚举校验；C-3 `markFeatureSessionRestored` 必须拆"绑定"与"L1 缓存"；C-4 route 短路是**实现**而非验证（`contextClosing` 仅在 onClose 事件路径置位）；C-5 "N×2→N×1"口径失真）；任务表 **T8-0~T8-6**（T8-0 判据收敛，零行为变更） |
| 2026-09-29（v4） | **S1/S2 竞态闭合（零新增配置）**：① **S1 令牌化撤销** `retireByPurpose(ApiSpec)` 用代际表 `generations.specFor(pattern)` 判定"当前规则实例"，旧触发者（窗口到期/响应定案）不得撤掉"同 pattern 重注册后的新规则"（含竞态守护单测）；② **R-2 多路触发** 借 `binders` 两参原子移除 + `closeConfirmed()` CAS 幂等；③ **S2 独立撤销执行器** `RouteRetireExecutor`（单线程、有界队列、拒绝即 fail-open、任务异常隔离），撤销不再与 body 断言/采集抢 IO 池（闭合 R-4，含 R-4 竞态守护单测 + 执行器单测 3/3）；④ 同步措辞：全文"丢弃重建"改为"重同步自愈 + 可见告警（绝不重建 Context）" |
