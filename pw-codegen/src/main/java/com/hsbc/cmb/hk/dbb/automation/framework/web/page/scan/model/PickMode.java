package com.hsbc.cmb.hk.dbb.automation.framework.web.page.scan.model;

/**
 * 拾取模式枚举：标识拾取器当前所处的交互态（空闲 / 手动拾取）。
 *
 * <p>注：整页扫描与区域扫描已从框架移除（见 panel-core-a.js 工具栏注释），故枚举不再有 SCAN_* 取值。
 *
 * @apiNote 框架内部共享数据契约（运行时拾取器 picker 与构建期生成器 generator 跨包共用）。
 *          业务代码不应依赖本枚举；其取值与浏览器侧字符串的映射由
 *          {@code com.hsbc.cmb.hk.dbb.automation.framework.web.page.scan.RolePickerConstants.MODE_*} 维护。
 */
public enum PickMode {
    IDLE, MANUAL
}
