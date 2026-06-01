package com.hsbc.cmb.hk.dbb.automation.framework.web.page;

import com.hsbc.cmb.hk.dbb.automation.framework.web.exceptions.ElementOperationException;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.PlaywrightManager;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * 元素诊断信息收集器
 *
 * Enterprise-grade diagnostic information collector for element failures.
 * Collects detailed information to aid in debugging flaky tests.
 *
 * @since 1.0.0
 */
public class ElementDiagnosticsCollector {

    private static final Logger logger = LoggerFactory.getLogger(ElementDiagnosticsCollector.class);

    private final Locator locator;
    private final String selector;
    private final Page page;

    public ElementDiagnosticsCollector(Locator locator, String selector, Page page) {
        this.locator = locator;
        this.selector = selector;
        this.page = page;
    }

    /**
     * 收集完整的诊断信息（批量单次 JS 调用，4 次 IPC → 1 次 IPC）
     */
    public ElementOperationException.DiagnosticInfo collect() {
        ElementOperationException.DiagnosticInfo info = ElementOperationException.DiagnosticInfo.create();

        try {
            // 批量收集：一次 page.evaluate() 完成存在性/可见性/可编辑性/可交互性/数量/标签/属性检查
            boolean detailed = PlaywrightManager.config().isElementDetailedDiagnostics();
            String script = buildBatchDiagnosticScript(detailed);
            @SuppressWarnings("unchecked")
            Map<String, Object> result = (Map<String, Object>) page.evaluate(script, selector);

            info.existsInDom(getBoolean(result, "exists"))
               .isVisible(getBoolean(result, "visible"))
               .isEnabled(getBoolean(result, "enabled"))
               .isEditable(getBoolean(result, "editable"))
               .elementCount(getInt(result, "count"));

            if (detailed) {
                info.tagName(getString(result, "tagName"));
                @SuppressWarnings("unchecked")
                Map<String, String> attrs = (Map<String, String>) result.get("attributes");
                if (attrs != null) {
                    info.attributes(attrs);
                }
            }
        } catch (Exception e) {
            logger.warn("Failed to collect full diagnostic info for [{}]: {}",
                selector, e.getMessage());
            try {
                info.elementCount(getElementCount());
            } catch (Exception ignored) {
                info.elementCount(-1);
            }
        }

        return info;
    }

    /**
     * 构建批量诊断 JS 脚本——一次 DOM 查询返回所有所需字段
     */
    private static String buildBatchDiagnosticScript(boolean detailed) {
        String keysJson = "['id','class','name','type','disabled','readonly','data-testid']";
        return "(selector) => {\n" +
            "  const el = document.querySelector(selector);\n" +
            "  if (!el) return { exists: false, visible: false, enabled: false, editable: false, count: 0, tagName: 'unknown'" + (detailed ? ", attributes: {}" : "") + " };\n" +
            "  const all = document.querySelectorAll(selector);\n" +
            "  const cs = getComputedStyle(el);\n" +
            "  const result = {\n" +
            "    exists: true,\n" +
            "    visible: el.offsetParent !== null && cs.display !== 'none' && cs.visibility !== 'hidden' && parseFloat(cs.opacity) > 0,\n" +
            "    enabled: !el.disabled,\n" +
            "    editable: !el.readOnly && !el.disabled && (el.tagName === 'INPUT' || el.tagName === 'TEXTAREA' || el.isContentEditable),\n" +
            "    count: all.length\n" +
            "  };\n" +
            (detailed ?
            "  result.tagName = el.tagName;\n" +
            "  const attrs = {};\n" +
            "  const keys = " + keysJson + ";\n" +
            "  keys.forEach(function(k) { const v = el.getAttribute(k); if (v !== null) attrs[k] = v; });\n" +
            "  result.attributes = attrs;\n"
            : "") +
            "  return result;\n" +
            "}";
    }

    private static boolean getBoolean(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return v instanceof Boolean ? (Boolean) v : false;
    }

    private static int getInt(Map<String, Object> map, String key) {
        Object v = map.get(key);
        if (v instanceof Number) return ((Number) v).intValue();
        return 0;
    }

    private static String getString(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return v != null ? v.toString() : "unknown";
    }

    /**
     * 获取匹配元素数量
     */
    private int getElementCount() {
        try {
            return locator.count();
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * 获取当前页面标题
     */
    public String getPageTitle() {
        try {
            return page.title();
        } catch (Exception e) {
            return "unknown";
        }
    }

    /**
     * 获取当前页面 URL
     */
    public String getPageUrl() {
        try {
            return page.url();
        } catch (Exception e) {
            return "unknown";
        }
    }

    /**
     * 获取可见的遮挡元素信息
     */
    public String getObstructingElements() {
        try {
            // 使用 JS 检查是否有其他元素遮挡
            String script = """
                () => {
                    const el = document.querySelector('%s');
                    if (!el) return 'Element not found';

                    const rect = el.getBoundingClientRect();
                    const centerX = rect.left + rect.width / 2;
                    const centerY = rect.top + rect.height / 2;

                    const topEl = document.elementFromPoint(centerX, centerY);
                    if (topEl === el) return 'None';

                    return topEl ? `Element '${topEl.tagName}' is obstructing` : 'Unknown';
                }
                """.formatted(selector);

            Object result = page.evaluate(script);
            return result != null ? result.toString() : "Unable to check";
        } catch (Exception e) {
            return "Unable to check: " + e.getMessage();
        }
    }

    /**
     * 获取 DOM 结构上下文（父元素信息）
     */
    public String getDomContext() {
        try {
            String script = """
                () => {
                    const el = document.querySelector('%s');
                    if (!el) return 'Element not found';

                    const getPath = (elem) => {
                        const path = [];
                        while (elem && elem.parentElement) {
                            let selector = elem.tagName.toLowerCase();
                            if (elem.id) {
                                selector += `#${elem.id}`;
                                path.unshift(selector);
                                break;
                            }
                            if (elem.className && typeof elem.className === 'string') {
                                const classes = elem.className.trim().split(/\\s+/).slice(0, 2);
                                if (classes.length > 0 && classes[0]) {
                                    selector += '.' + classes.join('.');
                                }
                            }
                            path.unshift(selector);
                            elem = elem.parentElement;
                        }
                        return path.join(' > ');
                    };

                    return getPath(el);
                }
                """.formatted(selector);

            Object result = page.evaluate(script);
            return result != null ? result.toString() : "Unable to get context";
        } catch (Exception e) {
            return "Unable to get context: " + e.getMessage();
        }
    }

    /**
     * 生成失败截图
     */
    public String captureFailureScreenshot(String testName) {
        if (!PlaywrightManager.config().isElementScreenshotOnFailure()) {
            logger.debug("Screenshot capture is disabled");
            return null;
        }

        try {
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS"));
            String sanitizedSelector = selector.replaceAll("[^a-zA-Z0-9]", "_").substring(0, Math.min(selector.length(), 30));
            String fileName = String.format("FAILED_%s_%s_%s.png", testName, sanitizedSelector, timestamp);

            Path screenshotDir = Paths.get(PlaywrightManager.config().getElementScreenshotPath());
            File dir = screenshotDir.toFile();
            if (!dir.exists() && !dir.mkdirs()) {
                logger.warn("Failed to create screenshot directory: {}", screenshotDir);
            }

            Path screenshotPath = screenshotDir.resolve(fileName);
            page.screenshot(new Page.ScreenshotOptions()
                .setPath(screenshotPath)
                .setFullPage(false));

            logger.info("Failure screenshot saved: {}", screenshotPath);
            return screenshotPath.toString();
        } catch (Exception e) {
            logger.error("Failed to capture screenshot for [{}]: {}", selector, e.getMessage());
            return null;
        }
    }

    /**
     * 获取元素内部 HTML
     */
    public String getElementHtml() {
        try {
            return page.evaluate("() => document.querySelector('" + selector + "')?.outerHTML?.substring(0, 500) || 'N/A'").toString();
        } catch (Exception e) {
            return "Unable to get HTML";
        }
    }
}
