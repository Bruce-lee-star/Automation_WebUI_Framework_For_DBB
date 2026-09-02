
Feature: RoleElement Login Page Object verification

  Verify the @RoleElement annotated LoginPage (tests.LoginPage) works end-to-end:
  fields are bound to PageElement at runtime and NLS keys resolve to accessible names,
  so type/click/check operate real controls on the logon page.

  Scenario: Log on using the RoleElement page object
    Given I logon DBB "O63_SIT1" environment as user "WP7UAT2_2" using the RoleElement page
