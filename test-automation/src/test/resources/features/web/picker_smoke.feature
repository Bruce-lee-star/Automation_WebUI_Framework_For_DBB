Feature: Role Element Picker - smoke of externalised evaluate scripts

  Guards the T5-1 step-2 extraction: the scripts moved out of RoleElementPicker
  into RolePickerScripts must still run in a real browser against the local
  scan-test-all.html, and must honour the "(a) => {...} + args()" contract that
  Playwright relies on to pass arguments. The runner only executes @route.

  @route @picker
  Scenario: externalised picker scripts evaluate without throwing
    Given the local scan test page is open
    When the panel bootstrap script is injected
    And the pick mode is set to "pick" through the externalised arg script
    Then the window pick mode equals "pick"
    And the pick state reader returns an object with keys "pageClass, picks, steps, ops"
