package com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.List;

/**
 * 桥接协作类（设计文档 9.10-③ / 9.3）：页面错误汇聚。
 *
 * <p>工作线程在任务结束时 draining 自身 {@code TestContext} 上累积的未捕获页面异常，随结构化
 * {@link ContextTaskResult} 回传<b>编排线程</b>统一汇聚标记（不在工作线程触碰 Serenity）。</p>
 */
final class TestContextBridge {

    private static final Logger LOGGER = LoggerFactory.getLogger(TestContextBridge.class);

    private TestContextBridge() {
    }

    /** 汇聚本工作线程累积的页面错误（已 drain）；异常安全，永不为 null。 */
    static List<String> drainPageErrors() {
        try {
            List<String> errors = PageEventMonitor.drainPendingPageErrors();
            return errors == null ? Collections.emptyList() : errors;
        } catch (Throwable t) {
            LOGGER.debug("[TestContextBridge] drainPageErrors skipped: {}", t.getMessage());
            return Collections.emptyList();
        }
    }
}
