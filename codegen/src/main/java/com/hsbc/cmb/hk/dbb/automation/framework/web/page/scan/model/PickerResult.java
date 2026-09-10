package com.hsbc.cmb.hk.dbb.automation.framework.web.page.scan.model;

import java.util.LinkedHashMap;

/**
 * 拾取命令循环的统一返回：动作 + 按页生成的代码 + 状态文案。
 *
 * @apiNote 框架内部共享数据契约（picker 与 generator 跨包共用）。业务代码不应依赖。
 *          字段为不可变公开快照，仅供读取；构造仅由拾取器命令引擎完成。
 */
public final class PickerResult {
    public final PickerAction action;
    public final LinkedHashMap<String, String> pageClassByPage;   // 页面类：pageClass → 源码
    public final LinkedHashMap<String, String> stepByPage;         // 步骤代码：pageClass → 源码视图
    public final String statusMsg;

    public PickerResult(PickerAction action, LinkedHashMap<String, String> pageClassByPage,
                        LinkedHashMap<String, String> stepByPage, String statusMsg) {
        this.action = action;
        this.pageClassByPage = pageClassByPage;
        this.stepByPage = stepByPage;
        this.statusMsg = statusMsg;
    }
}
