Feature: RoleElement 拾取器全场景录制回归验证

  本 feature 用「录制生成的 PageObject + Steps 等价物」驱动 scan-test-all.html，
  验证框架对以下全部信号的录制脚本可正确运行：
    click / dblclick / hover / type / check / uncheck / selectOption / popup / download / dialog

  Background:
    Given 打开录制测试页 "file:///d:/IdeaProject/Automation_WebUI_Framework_For_DBB/src/test/resources/scan-test-all.html"

  @scan @click
  Scenario: 单击与双击
    When 点击 提交按钮
    And 点击 取消按钮
    And 点击 返回首页链接
    And 双击 编辑按钮

  @scan @hover
  Scenario: 悬停
    When 悬停到目标
    Then 悬停结果应为 "已悬停（hover 触发）"

  @scan @type
  Scenario: 文本输入
    When 在姓名框输入 "张三"
    Then 姓名框值应为 "张三"
    And 在密码框输入
    And 在搜索框输入 "转账"
    And 在年龄框输入 "30"

  @scan @check
  Scenario: 复选框勾选与取消勾选
    When 勾选 同意条款
    And 取消勾选 订阅新闻
    And 选择 男

  @scan @select
  Scenario: 下拉选择（selectOption）
    When 选择账户类型 "人民币账户"
    Then 账户类型应选中 "CNY"
    And 选择城市 "上海"

  @scan @popup
  Scenario: 弹出新页面
    When 点击 打开新窗口

  @scan @download
  Scenario: 下载
    When 点击 下载示例文件

  @scan @dialog
  Scenario: 原生对话框（alert / confirm / prompt）
    When 触发 Alert
    And 触发 Confirm
    And 触发 Prompt
