@route-parallel-smoke
Feature: Route Demo 本地并行冒烟（CON-1 引擎级并行 GREEN 验证，绕过内网）

  # 目的：在本机用真实浏览器指向本地 route-demo-service 后端，证明 CON-1 引擎级并行
  #       （Cucumber fixed 策略）真正并发 GREEN，作为梯度压测的本地代理验证。
  # 前置：route-demo-service 已在 http://localhost:8888 启动
  #       （mvn -f route-demo-service/pom.xml spring-boot:run）。
  # 运行：mvn -o -pl test-automation -am verify -Pparallel ^
  #        -Dit.test=CucumberRouteDemoParallelSmokeRunnerIT ^
  #        -Dcucumber.filter.tags=@route-parallel-smoke ^
  #        -Dparallelism=8 -DmaxPoolSize=8 ^
  #        "-Dcve.gate.skip=true" "-Dcheckstyle.skip=true" "-Dspotbugs.skip=true"
  # 梯度：改 -Dparallelism=2/4/8 即可复现 2→4→8 梯度压测（与 nightly/CI 同旋钮）。
  # 隔离：每个 scenario 由 @AutoBrowser 托管独立 Context/Page；路由规则 per-Context（X-3 已修），
  #       后端为只读 GET / 拦截式 MOCK（不写后端），故并发 scenario 互不串扰。
  # 注意：本冒烟按需经 PlaywrightManager.getPage() 取页（与 logon 同路径），
  #       不复用 RouteDemoServiceSteps 的构造期缓存字段，避免引擎级并行下 Serenity 线程上下文未就绪导致 Failed to get page。

  @route-parallel-smoke
  Scenario: parallel demo monitor-1
    Given parallel smoke: monitor collects real response

  @route-parallel-smoke
  Scenario: parallel demo monitor-2
    Given parallel smoke: monitor collects real response

  @route-parallel-smoke
  Scenario: parallel demo monitor-3
    Given parallel smoke: monitor collects real response

  @route-parallel-smoke
  Scenario: parallel demo monitor-4
    Given parallel smoke: monitor collects real response

  @route-parallel-smoke
  Scenario: parallel demo mock-1
    Given parallel smoke: mock replaces whole response

  @route-parallel-smoke
  Scenario: parallel demo mock-2
    Given parallel smoke: mock replaces whole response

  @route-parallel-smoke
  Scenario: parallel demo mock-3
    Given parallel smoke: mock replaces whole response

  @route-parallel-smoke
  Scenario: parallel demo mock-4
    Given parallel smoke: mock replaces whole response
