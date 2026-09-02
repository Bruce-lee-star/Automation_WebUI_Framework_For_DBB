Feature: RoleElement 拾取器录制验证（端到端录制脚本）

  本 feature 不止于“回放录制产物”，而是真正启动框架拾取器（RoleElementPicker.start）
  对 scan-test-all.html 执行全部交互，再从录制结果生成代码，断言生成的脚本正确覆盖：
    click / doubleClick / hover / type / check / uncheck / selectOption
    / popup / download / dialog(alert|confirm|prompt)

  注意：拾取器是本地开发工具，CI 环境会跳过 start；本 feature 仅供本地手动运行验证录制能力。

  Background:
    Given 打开录制测试页并启动拾取器

  @scan-record
  Scenario: 端到端录制全部交互并校验生成代码
    When 在测试页上录制全部交互（点击/双击/悬停/输入/勾选/取消勾选/下拉/弹窗/下载/对话框）
    Then 生成的录制脚本应包含 'selectByVisibleText("人民币账户")'
    And 生成的录制脚本应包含 'uncheck()'
    And 生成的录制脚本应包含 'check()'
    And 生成的录制脚本应包含 'doubleClick()'
    And 生成的录制脚本应包含 'hover()'
    And 生成的录制脚本应包含 'type('
    And 生成的录制脚本应包含 'acceptAlert()'
    And 生成的录制脚本应包含 'dismissAlert()'
    And 生成的录制脚本应包含 'waitForDownload'
    And 生成的录制脚本应包含 'waitForNewPage'
