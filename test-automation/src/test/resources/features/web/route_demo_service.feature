@route
Feature: Route Demo Service 集成测试（结合框架 @AutoBrowser 浏览器实例化）

  # 前置：route-demo-service 已启动（端口 8888，context-path /demo）
  # 运行：mvn verify -Dcucumber.filter.tags=@route
  # 浏览器 / Context / Page 由框架 @AutoBrowser 自动托管，业务零侵入（参照 login_dbb.feature）

  Scenario: MONITOR 采集真实响应
    Given route demo: monitor collects real response

  Scenario: MOCK 整体替换响应
    Given route demo: mock replaces whole response

  Scenario: MOCK 拦截真实响应并改写字段
    Given route demo: mock intercept real response then replace field

  Scenario: 需求3 条件修改-按角色数组逐元素
    Given route demo: conditional modify array element by role

  Scenario: 需求3 条件修改-数值大于等于
    Given route demo: conditional modify numeric greater than

  Scenario: 条件修改 NOT_EQUALS
    Given route demo: conditional modify not equals

  Scenario: 条件修改 GT 严格大于含边界
    Given route demo: conditional modify greater than

  Scenario: 条件修改 LT 严格小于含边界
    Given route demo: conditional modify less than

  Scenario: 条件修改 LTE 含等于边界
    Given route demo: conditional modify less than or equal

  Scenario: 条件修改 CONTAINS 子串匹配
    Given route demo: conditional modify contains

  Scenario: 条件修改 NOT_CONTAINS 子串不匹配
    Given route demo: conditional modify not contains

  Scenario: 条件修改 REGEX 正则匹配
    Given route demo: conditional modify regex

  Scenario: 条件修改 EXISTS 字段存在性
    Given route demo: conditional modify exists

  Scenario: 条件修改 NOT_EXISTS 字段缺失性
    Given route demo: conditional modify not exists

  Scenario: MODIFY 改写请求体后转发
    Given route demo: modify request body forwarded to server

    @test
  Scenario: DELAY 高延迟生效
    Given route demo: delay applies latency

  Scenario: 需求2 delay+monitor monitor 仍能采集
    Given route demo: delay plus monitor still captures

  Scenario: 同 API 先 modify 后 monitor 两能力共存
    Given route demo: modify then monitor same api both active

  Scenario: 同 API 先 monitor 后 modify 两能力共存
    Given route demo: monitor then modify same api both active

  Scenario: 优先级 mock+monitor monitor 看到 mock 后响应
    Given route demo: priority mock and monitor sees mocked response

  Scenario: 资源清理-单 context clear 后恢复真实后端
    Given route demo: single context rule disabled after clear

  Scenario: 资源清理-多 context 隔离互不泄漏
    Given route demo: multi context isolation
