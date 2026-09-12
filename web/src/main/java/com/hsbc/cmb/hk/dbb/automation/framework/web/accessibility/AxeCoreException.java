package com.hsbc.cmb.hk.dbb.automation.framework.web.accessibility;

/**
 * axe-core 扫描相关语义化运行时异常。
 * <p>
 * 用于替代裸 {@link RuntimeException}/{@link NullPointerException}，明确区分「资源缺失」「注入失败」
 * 「脚本执行失败」等场景，便于上层（{@link AxeCoreScanner}）与业务定位根因。
 */
public class AxeCoreException extends RuntimeException {

    public AxeCoreException(String message) {
        super(message);
    }

    public AxeCoreException(String message, Throwable cause) {
        super(message, cause);
    }
}
