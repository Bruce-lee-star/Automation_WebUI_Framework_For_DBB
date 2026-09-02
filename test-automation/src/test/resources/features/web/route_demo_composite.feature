@route @route-composite
Feature: Route 复合场景集成测试（四能力叠加 / 跨层优先级 / 清理隔离）

  # 前置：route-demo-service 已启动（端口 8888，context-path /demo）
  # 运行：mvn verify -Dtags=@route-composite      （仅复合场景）
  #      mvn verify -Dtags=@route                 （基础 + 复合，共 24 个 scenario 串跑）
  #
  # ⭐ 同时挂 @route：使 -Dtags=@route 能一次性串跑全部 route 场景，
  #   用于验证 scenario 间的规则 / 采集 / 后端数据清理是否彻底（跨用例零污染）。
  # 浏览器 / Context / Page 由框架 @AutoBrowser 自动托管；每个 Scenario 前后自动重置数据与清理规则。

  # ══════════ A. 单层复合：同 pattern 多能力叠加 ══════════

  Scenario: A1 MODIFY+MONITOR 叠加-两者都记录且 MONITOR 看到改写后的真实响应
    Given route composite: modify plus monitor both recorded

  Scenario: A2 MODIFY+DELAY 叠加-先延迟再改写转发
    Given route composite: modify plus delay both applied

  Scenario: A3 MODIFY+DELAY+MONITOR 三者叠加-产生三条并列记录
    Given route composite: modify delay monitor triple composite

  Scenario: A4 MOCK+DELAY 叠加-先延迟后短路-不发真实请求
    Given route composite: mock plus delay delayed then short circuit

  Scenario: A5 MOCK+MODIFY 叠加-MOCK 短路致 MODIFY 不执行
    Given route composite: mock plus modify mock short circuits modify

  # ══════════ B. 跨层复合：Page vs Context 优先级 ══════════

  Scenario: B1 Context MONITOR + Page MOCK-MOCK 终结-MONITOR 无记录
    Given route composite: context monitor plus page mock

  Scenario: B2 Context DELAY(1s) + Page DELAY(2s)-跨层取 max=2s
    Given route composite: context delay plus page delay takes max

  Scenario: B3 Context MODIFY(header) + Page MODIFY(body)-跨层 putAll 都生效
    Given route composite: context modify plus page modify merged

  Scenario: B4 Context MODIFY + Page MOCK-MOCK 终结跨层改写
    Given route composite: context modify plus page mock short circuits

  # ══════════ C. DSL 方法覆盖 ══════════

  Scenario: C1 MODIFY 全部请求改写方法（header/body 增删改）
    Given route composite: modify all request methods

  Scenario: C2 MOCK times(1) 一次性拦截-第二次走真实网络
    Given route composite: mock times one shot

  Scenario: C3 请求条件匹配 matchMethod + matchQuery 只命中目标请求
    Given route composite: request condition matching

  # ══════════ E. 各 Handler 落库契约（单一能力下逐条验证）══════════

  Scenario: E1 MOCK 落库-type=MOCK/fromMock=true/响应体为注入数据
    Given route composite: mock handler persists contract

  Scenario: E2 MOCK interceptResponse 落库-内容为改写后的响应
    Given route composite: mock intercept handler persists contract

  Scenario: E3 MODIFY 落库-type=MODIFY/响应为改写后请求的真实回应/带 modifyDetail
    Given route composite: modify handler persists contract

  Scenario: E4 MONITOR 落库-type=MONITOR/含完整真实响应
    Given route composite: monitor handler persists contract

  Scenario: E5 DELAY 落库为维度标记-可按类型查询且不污染通用查询
    Given route composite: delay handler persists contract

  # ══════════ F. Monitor 响应体主线程可读 ══════════

  Scenario: F1 Monitor 捕获响应体后主线程可读取业务字段
    Given route composite: monitor response body readable from main thread

  # ══════════ D. 清理与隔离 ══════════

  Scenario: D1 清理后规则失效-采集上下文重置-恢复真实后端
    Given route composite: cleanup resets route and capture state
