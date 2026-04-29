package com.hsbc.cmb.hk.dbb.automation.framework.web.page;

import com.hsbc.cmb.hk.dbb.automation.framework.web.exception.ElementOperationException;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.PlaywrightManager;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
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
     * 收集完整的诊断信息
     */
    public ElementOperationException.DiagnosticInfo collect() {
        ElementOperationException.DiagnosticInfo info = ElementOperationException.DiagnosticInfo.create();

        try {
            info.existsInDom(checkExistsInDom())
               .isVisible(checkIsVisible())
               .isEnabled(checkIsEnabled())
               .isEditable(checkIsEditable())
               .elementCount(getElementCount());

            // 收集额外属性（可选，根据配置决定）
            if (PlaywrightManager.config().isElementDetailedDiagnostics()) {
                info.tagName(getTagName());
                info.attributes(getElementAttributes());
            }
        } catch (Exception e) {
            logger.warn("Failed to collect full diagnostic info for [{}]: {}",
                selector, e.getMessage());
            // 尽可能多地收集信息，即使部分失败
            try {
                info.elementCount(getElementCount());
            } catch (Exception ignored) {
                info.elementCount(-1);
            }
        }

        return info;
    }

    /**
     * 检查元素是否存在于 DOM 中
     */
    private boolean checkExistsInDom() {
        try {
            return locator.count() > 0;
        } catch (Exception e) {
            logger.debug("Element [{}] not found in DOM: {}", selector, e.getMessage());
            return false;
        }
    }

    /**
     * 检查元素是否可见
     */
    private boolean checkIsVisible() {
        try {
            return locator.isVisible();
        } catch (Exception e) {
            logger.debug("Element [{}] not visible: {}", selector, e.getMessage());
            return false;
        }
    }

    /**
     * 检查元素是否可用
     */
    private boolean checkIsEnabled() {
        try {
            return locator.isEnabled();
        } catch (Exception e) {
            logger.debug("Element [{}] not enabled: {}", selector, e.getMessage());
            return false;
        }
    }

    /**
     * 检查元素是否可编辑
     */
    private boolean checkIsEditable() {
        try {
            return locator.isEditable();
        } catch (Exception e) {
            logger.debug("Element [{}] not editable: {}", selector, e.getMessage());
            return false;
        }
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
     * 获取元素标签名
     */
    private String getTagName() {
        try {
            return locator.evaluate("el => el.tagName").toString();
        } catch (Exception e) {
            return "unknown";
        }
    }

    /**
     * 获取元素关键属性
     */
    private Map<String, String> getElementAttributes() {
        Map<String, String> attrs = new HashMap<>();
        String[] keys = {"id", "class", "name", "type", "disabled", "readonly", "data-testid"};

        for (String key : keys) {
            try {
                String value = locator.getAttribute(key);
                if (value != null) {
                    attrs.put(key, value);
                }
            } catch (Exception e) {
                // 属性不存在，忽略
            }
        }

        return attrs;
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
            if (!screenshotDir.toFile().exists()) {
                screenshotDir.toFile().mkdirs();
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
