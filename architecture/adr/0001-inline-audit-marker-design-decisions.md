# ADR-0001　内联审计标记（`⭐` / `修复 Pn-xx`）所记录的设计决策索引

> 状态：2026-09-07 建立　类型：索引 / 收敛
> 背景：T4-1 审计标记迁出。历史上代码内用 `⭐` + `修复 Pn-xx` 标记记录"已修复 / 已拆分 / 设计决策"。2026-09-07 全量测绘发现 **`⭐` 约 602 处 + `修复 P` 约 14 处（跨 79 文件）**，远超 2026-09-04 文档记载的"⭐ ~15 文件 / 修复 Pn-xx 34 文件"——因近期多次模块拆分/下沉重构重新写回了标记。本次清理：① 纯状态/迁移标记直接删除（所指改动已在代码中体现）；② 解释"为什么"（bug 根因 / 并发陷阱 / workaround / 脱敏理由 / 性能取舍）的注释保留并去掉前缀，改写为普通注释；③ 非显而易见的设计决策收敛到本文档与既有架构文档；④ 未发现显性 TODO/FIXME（计数 0）。

## 一、非显而易见设计决策（原 ⭐ 标记，现权威来源）

| 决策 | 原标记示例 | 权威记录位置 |
|---|---|---|
| 合规审计刷库隔离模型：专属单线程刷库执行器、O(1) 队列计数、事务包裹单批原子提交 | `审计 P0-0` / `审计 P0-2` / `审计 P0-3` | `route/.../persistence/ApiMonitoringRepository.java`（注释保留，去前缀） |
| 统一绑定模型：`page` 规则升级为 `context` 级绑定，由 Playwright 自动覆盖同 context 全部页面 | `统一绑定模型` | `route/.../dsl/RouteDsl.java` + `Element.MD` |
| 路由处理失败必须 `throw` 传播（防挂起）：handler 业务路径统一重抛 / `FrameworkResponseException` | `失败必须 throw` | `route/.../handler/*` + `ARCHITECTURE_REMEDIATION_PLAN.md` §T2-6 |
| `RouteHandleType` 两套顺序区分：枚举 `priority`（默认顺序）vs 注册顺序，不可混用 | `RouteHandleType 两套顺序` | `route/.../core/RouteHandleType.java` |
| `CONTEXT_LOCK` / `PAGE_LOCK` 仅保护共享子系统（`RouteRegistry` / `RoleElementPicker` / `PlaywrightContextManager`）清理，创建路径靠 per-thread 隔离 | 并发锁语义 | `web/.../lifecycle/PlaywrightManager.java` + `ARCHITECTURE_REMEDIATION_PLAN.md` §T3-2 |
| 登录单飞 + SSO 会话复用：`LoginGuard` 按 `sessionKey` 单飞，`SessionManager` Guava 缓存单飞读盘 | 登录单飞 | `web/.../session/SessionManager.java` + `ARCHITECTURE_REMEDIATION_PLAN.md` §T3-3 |
| 能力刻意不实现 / 无调用点即不修（如 `FrameworkState` 某些能力） | `当前无调用点所以不修` | `web/.../core/FrameworkState.java`（注释保留，去前缀） |
| 监控为不可覆盖基线：无论是否叠加 Modify/Delay，监控始终生效 | `监控是不可被覆盖的基线` | `route/.../handler/MonitorHandler.java` |
| SSO 感知并发（按身份分区互斥）设计 | `附录 A` | `CONCURRENT_CONTEXT_EXECUTOR_DESIGN.md` 附录 A |

## 二、规范（T4-1 落地）

- **commit message 记录"为什么"**；**代码注释只记录"是什么"与"为什么这样写会产生 bug"**。
- 禁止再用 `⭐` / `修复 Pn-xx` / `Phase N` 横幅式状态标记。
- 新设计决策写入 `architecture/adr/NNNN-*.md` 或对应架构文档，不在代码内联状态标记。
- 验收：`grep -c "⭐" src/main/java` = 0；`grep -c "修复 P" src/main/java` = 0。
