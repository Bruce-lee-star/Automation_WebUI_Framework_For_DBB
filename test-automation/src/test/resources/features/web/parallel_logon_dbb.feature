Feature: Parallel logon DBB - T3-2 每线程独立浏览器隔离验证

  # 参照 login_dbb.feature：每个 scenario 复用既有的 "logon DBB <env> environment as user <user>" 步骤，
  # 通过 Serenity 场景级并行（serenity.parallel.for.tests=4）在 4 个线程并发登录，
  # 验证 T3-2「每个 worker 线程持有独立 Browser 实例、互不共享、restartBrowser 不误杀并发场景」。

  @parallel-logon
  Scenario: parallel logon dbb on thread-1
    Given logon DBB "O63_SIT1" environment as user "WP7UAT2_2"
    Then the browser is isolated to this thread only

  @parallel-logon
  Scenario: parallel logon dbb on thread-2
    Given logon DBB "O63_SIT1" environment as user "WP7UAT2_2"
    Then the browser is isolated to this thread only

  @parallel-logon
  Scenario: parallel logon dbb on thread-3
    Given logon DBB "O63_SIT1" environment as user "WP7UAT2_2"
    Then the browser is isolated to this thread only

  @parallel-logon
  Scenario: parallel logon dbb on thread-4
    Given logon DBB "O63_SIT1" environment as user "WP7UAT2_2"
    Then the browser is isolated to this thread only
