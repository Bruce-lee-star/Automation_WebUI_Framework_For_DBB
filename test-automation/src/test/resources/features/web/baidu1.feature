@regression @web @baidu
Feature: Baidu search - 1 (single scenario, for comparison)

  @baidu
  Scenario: baidu - search selenium only
    When open the baidu site
    And search "selenium" keywords
