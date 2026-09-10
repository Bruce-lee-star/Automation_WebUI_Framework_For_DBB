@concurrent-logon
Feature: Concurrent logon DBB - SSO 感知并发（框架自建并发，非 Serenity 并行）

  # 并发由框架层 ConcurrentScenarioExecutor（web 框架）在线程池真正并发驱动，业务层零并发代码。
  # Serenity 无 JVM 内并行能力（serenity.parallel.for.tests 为历史空操作）。
  # 相同 (env,username) 经框架 ConcurrencyGate 串行；不同身份并行。
  # 开启 -Dserenity.playwright.concurrent.partition.enabled=true 后，下方 4 个相同
  # (O63_SIT1, WP7UAT2_2) 任务被串行化（同一时刻仅一个登录），日志出现
  # "[concurrency-gate] identity ... serialized (blocked)"；闸门关闭时则退化为纯并行隔离验证。

  Scenario: 并发登录 6 个身份（3 不同 + 3 相同 O63）验证 SSO 分区互斥
    Given 准备并发批次
    When 并发执行以下:
      | env       | username     |
      | O63_SIT1  | WP7UAT2_2    |
      | O38_SIT2  | amhb2g0677_3 |
      | O88_SIT3  | amhb2g0680_2 |
      | O63_SIT1  | WP7UAT2_2    |
      | O63_SIT1  | WP7UAT2_2    |
      | O63_SIT1  | WP7UAT2_2    |
    Then 全部并发执行成功
