# Agent 任务简报（Brief）模板 —— 你指挥 AI 的入口

> 你不需要学 Agent 内部机制。每次要它干活，就填一份 Brief（自然语言也行，结构化更稳）。
> Agent 收到后：加载宪章 → `requirement_classify` → 对 MODIFY/ENHANCE 先反查影响面并**等你确认** → 生成/改 → dry-run → 开草稿 PR。
> `intent_hint` 是你给 Agent 的倾向，Agent 仍会用 `requirement_classify` 实测校验，不一致以证据为准。

---

## 模板

```markdown
# 任务简报
req:          <Jira key / PRD 链接 / 自由文本需求描述>
intent_hint:  <NEW | MODIFY | ENHANCE | 让Agent判断>   # 选填，Agent 会 verify
scope:        <功能域 / 页面，如 login、transfer、statement>
env:          <SIT1 | SIT2 | SIT3>
locales:      <en-US, zh-HK>
mode:         <analyze-only | generate | generate+pr>
constraints:
  - 必须复用现有 <某 step / 某 page>
  - 只改 <某 feature / 某 scenario>，不要动 regression 套件
  - 其他约束…
expected_output: <feature 草稿 / 改后 diff / PR 链接>
```

---

## 示例 1：改现有（MODIFY）

```markdown
# 任务简报
req: REQ-778 登录后新增安全问题验证步骤
intent_hint: MODIFY
scope: login
env: SIT1
locales: en-US, zh-HK
mode: generate+pr
constraints:
  - 复用现有 "logon DBB" step，不要重写登录流程
  - 仅改 login_dbb.feature，不要新建文件
  - 两语言都要覆盖
expected_output: 改后 login_dbb.feature 的 diff + 草稿 PR
```
→ Agent 会：`requirement_classify` 证实 MODIFY → `impact_analyze` 锁定 `login_dbb.feature` + 相关 Page → 暂停**请你确认影响面** → 原地改 → 两语言 dry-run → 草稿 PR。

## 示例 2：增强（ENHANCE）

```markdown
# 任务简报
req: REQ-801 转账流程新增 HKD 以外币种
intent_hint: ENHANCE
scope: transfer
env: SIT2
locales: en-US, zh-HK
mode: generate
constraints:
  - 现有转账 case 必须保持绿色，不要改它们
  - 仅追加新币种相关 scenario
expected_output: 新增的 transfer 币种 scenario 草稿
```
→ Agent 会：确认 ENHANCE → 旧 case 不动 → 追加 scenario → dry-run → 因 mode=generate 只给你草稿，不开 PR。

## 示例 3：先只分析影响面（不改任何东西）

```markdown
# 任务简报
req: REQ-812 个人资料页改版
intent_hint: 让Agent判断
scope: profile
env: SIT1
locales: en-US, zh-HK
mode: analyze-only
expected_output: 影响面报告（命中哪些 NLS key / Page / Step / Feature）
```
→ Agent 会：只跑 `requirement_classify` + `impact_analyze`，产出影响面清单，**不写任何文件**，等你决定下一步。

---

## 你如何"强制/绕过"规则
- 想临时破例（如某定位器实在只能用 XPath）：在 constraints 里写 `override: <规则名> <原因>`，Agent 会记录 `run_id` + 原因 + 需你审批，且仍走 dry-run 与 PR 门。
- 想看 Agent 为什么这么做：任何产物都带 `run_id`，可回溯到本次宪章版本与每一步证据。
