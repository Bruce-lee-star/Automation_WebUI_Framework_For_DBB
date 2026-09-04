@e2e-sandbox
Feature: E2E real browser sandbox
  自包含的 E2E 真实浏览器验证流程。
  页面由框架 setContent 注入本地静态 HTML，不依赖外网 / DBB 环境 / REST 接口，
  但执行的是真实 Chromium 浏览器，可作为后续 E2E 回归与并行隔离验证的稳定靶子。

  Background:
    Given the E2E sandbox page is open

  Scenario: Sign in successfully shows greeting
    When I sign in to the sandbox as "alice" with password "secret1"
    Then the sandbox message contains "Welcome, alice"

  Scenario: Missing credentials is rejected
    When I sign in to the sandbox as "" with password ""
    Then the sandbox message contains "ERROR"

  Scenario: Async panel appears after loading data
    When I load async data
    Then the sandbox async panel contains "Async data loaded"

  Scenario: Counter state stays isolated per scenario
    When I increment the sandbox counter 3 times
    Then the sandbox counter is "3"

  Scenario: Browser context is isolated to this thread
    Then the sandbox browser is isolated to this thread
