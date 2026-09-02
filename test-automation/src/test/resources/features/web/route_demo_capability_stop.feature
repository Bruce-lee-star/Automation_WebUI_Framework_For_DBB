@route-capability-stop
Feature: Route 按能力维度显式停止（monitor / modify / delay / mock / all）

  目标：验证 RouteDsl.stopMonitor / stopModify / stopDelay / stopMock / stopApi
        停止某能力只影响该能力，同 API 的其余能力不受影响；stopApi 停止全部能力（路由仍注册，走真实后端）。
  前置：route-demo-web 已在 http://localhost:8899 启动
        （mvn -f route-demo-web/pom.xml spring-boot:run）。
  运行：mvn verify -Dcucumber.filter.tags=@route-capability-stop

  # ───────────────── 单能力停止：其余能力不受影响 ─────────────────

  Scenario: stopMonitor 只停 monitor，modify 与 delay 仍生效
    Given route capability-stop: monitor 停止后 modify 与 delay 仍生效

  Scenario: stopModify 只停 modify，monitor 与 delay 仍生效
    Given route capability-stop: modify 停止后 monitor 与 delay 仍生效

  Scenario: stopDelay 只停 delay，monitor 与 modify 仍生效
    Given route capability-stop: delay 停止后 monitor 与 modify 仍生效

  # ───────────────── mock 停止：回退真实后端 ─────────────────

  Scenario: stopMock 使 mock 失效并回退真实后端
    Given route capability-stop: mock 停止后回退真实后端

  # ───────────────── 全部停止 ─────────────────

  Scenario: stopApi 停止该 API 的全部能力（passthrough 真实后端）
    Given route capability-stop: stopApi 停止全部能力
