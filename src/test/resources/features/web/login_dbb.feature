
Feature: testing logon - 1
  @test
  Scenario: testing logon to dbb-1
    Given logon DBB "O63_SIT1" environment as user "WP7UAT2_2"
#    When switch profile to "HKHBAP001008101838PU0000" and close reminder


  Scenario: testing logon to dbb-2
    Given logon DBB "O63_SIT1" environment as user "WP7UAT2_2"

  Scenario: testing logon to dbb-3
    Given logon DBB "O63_SIT1" environment as user "WP7UAT2_2"
    When switch profile to "HKHBAP005400049838PU0000" and close reminder
