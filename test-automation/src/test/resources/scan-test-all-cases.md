# RoleElement Picker 全场景录制测试 —— 用例与预期结果

> 测试页：`src/test/resources/scan-test-all.html`
> 操作顺序：启动拾取 → 按下面「操作」列逐一操作 → 停止拾取 → 生成代码 → 对照「预期生成代码」。

## 用例总表

| # | 场景 | 元素 | 操作 | 信号 | 预期生成代码（草稿） |
|---|------|------|------|------|----------------------|
| 1.1 | 单击 button | 提交 | 单击 | click | `page.btnSubmit.click();` |
| 1.2 | 单击 button 次色 | 取消 | 单击 | click | `page.btnCancel.click();` |
| 1.3 | 单击 link | 返回首页 | 单击 | click | `page.linkHome.click();` |
| 2.1 | 双击 | 编辑 | 双击 | dblclick | `page.btnEdit.dblclick();` |
| 3.1 | 悬停 | 悬停目标 | 悬停≥0.45s | hover | `page.hoverTarget.hover();` |
| 4.1 | 文本输入 | 姓名 | 输入"张三" | type | `page.txtName.type("张三");` |
| 4.2 | 密码输入 | 密码 | 输入"******" | type | `page.txtPwd.type("******");` |
| 4.3 | 搜索框 | 搜索 | 输入"转账" | type | `page.txtSearch.type("转账");` |
| 4.4 | 数字框 | 年龄 | 输入"30" | type | `page.numAge.type("30");` |
| 5.1 | 复选框(未勾) | 同意条款 | 勾选 | check | `page.chkAgree.check();` |
| 5.2 | 复选框(已勾) | 订阅新闻 | 取消勾选 | uncheck | `page.chkNews.uncheck();` |
| 5.3 | 单选 | 男 | 选择 | check | `page.rdMale.check();` |
| 6.1 | 下拉单选 | 账户类型 | 选"人民币账户" | selectOption | `page.selAccount.selectByVisibleText("人民币账户");` |
| 6.2 | 下拉多选 | 城市 | 选"上海" | selectOption | `page.selCity.selectByVisibleText("上海");` |
| 7.1 | 新窗口 | 打开新窗口 | 单击(开新页) | popup | `var popup = page.waitForPopup(() -> page.lnkPopup.click());` |
| 8.1 | 下载 | 下载示例文件 | 单击 | download | `var download = page.waitForDownload(() -> page.lnkDownload.click());` |
| 9.1 | 提示框 | 触发 Alert | 单击 | dialog(alert) | `page.acceptAlert(); // 处理原生对话框(alert)` 前置 |
| 9.2 | 确认框 | 触发 Confirm | 单击 | dialog(confirm) | `page.dismissAlert(); // 处理原生对话框(confirm)` 前置 |
| 9.3 | 输入框 | 触发 Prompt | 单击 | dialog(prompt) | `page.dismissAlert(); // 处理原生对话框(prompt)` 前置 |

## 验证要点

1. **selectOption 必须带选中项**：combobox/listbox 不再生成裸 `click()`，而是 `selectByVisibleText("选项文本")`。
2. **uncheck 必须区分**：「订阅新闻」默认已勾选，录制取消勾选时应生成 `uncheck()`，而非 `check()`。
3. **check 默认态**：「同意条款」若录制时是勾选动作 → `check()`；「订阅新闻」录制取消勾选 → `uncheck()`。
4. **dialog 必须前置**：alert/confirm/prompt 的 `acceptAlert()/dismissAlert()` 必须生成在触发点击**之前**（对齐 page.pause() 的 `onceDialog` 预插入）。
5. **popup / download 必须包裹**：用 `waitForPopup` / `waitForDownload` 包裹触发动作。
6. **hover 单步**：悬停生成 `hover()`，不应重复触发 click。
7. **dblclick 单步**：双击生成 `dblclick()`，不应拆成两次 click。

## 人工核对步骤

1. 打开 `scan-test-all.html`，启动 `page.pause()` 拾取。
2. 按上表 1.1 → 9.3 顺序操作（注意 5.2 要先让「订阅新闻」处于已勾选，再取消勾选）。
3. 停止拾取，生成 PageObject + Steps。
4. 检查生成的 `.java` 是否满足上表「预期生成代码」与「验证要点」。
5. 标注不通过项，反馈给生成器调整。
