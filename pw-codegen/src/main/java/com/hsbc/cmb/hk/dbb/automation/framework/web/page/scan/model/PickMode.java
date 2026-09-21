package com.hsbc.cmb.hk.dbb.automation.framework.web.page.scan.model;

/**
 * 拾取模式枚举：标识拾取器当前所处的交互态（空闲 / 手动 / 整页扫描 / 区域扫描）。
 *
 * @apiNote 框架内部共享数据契约（运行时拾取器 picker 与构建期生成器 generator 跨包共用）。
 *          业务代码不应依赖本枚举；其取值与浏览器侧字符串的映射由
 *          {@code com.hsbc.cmb.hk.dbb.automation.framework.web.page.scan.RolePickerConstants.MODE_*} 维护。
 */
public enum PickMode {
    IDLE, MANUAL, SCAN_PAGE, SCAN_REGION
}
