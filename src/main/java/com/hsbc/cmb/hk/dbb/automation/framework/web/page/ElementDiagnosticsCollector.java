package com.hsbc.cmb.hk.dbb.automation.framework.web.page;


import com.hsbc.cmb.hk.dbb.automation.framework.web.exceptions.ElementOperationException;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.PlaywrightManager;
import com.microsoft.playwright.Frame;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 元素诊断信息收集器。
 *
 * <h3>Enterprise-grade diagnostic collector for element failures.</h3>
 * 支持同步和异步两种收集模式：
 * <ul>
 *   <li>{@link #collect()} — 同步收集（阻塞当前测试线程）</li>
 *   <li>{@link #collectAsync()} — 异步收集（使用共享线程池，不阻塞测试线程）</li>
 * </ul>
 * 截图 I/O 始终异步执行，避免高并发场景下失败路径雪崩。
 *
 * @since 1.0.0
 */
public class ElementDiagnosticsCollector {

    private static final Logger logger = LoggerFactory.getLogger(ElementDiagnosticsCollector.class);

    /** 共享诊断线程池——daemon 线程，JVM 退出时自动回收 */
    private static final ExecutorService DIAGNOSTIC_EXECUTOR = Executors.newCachedThreadPool(new ThreadFactory() {
        private final AtomicInteger counter = new AtomicInteger(0);
        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "diagnostic-" + counter.incrementAndGet());
            t.setDaemon(true);
            return t;
        }
    });


    private final Locator locator;
    private final String selector;
    private final Page page;
    /** 当前 iframe 上下文（null 表示在主页面 DOM 中操作） */
    private final Frame currentFrame;

    public ElementDiagnosticsCollector(Locator locator, String selector, Page page, Frame currentFrame) {
        this.locator = locator;
        this.selector = selector;
        this.page = page;
        this.currentFrame = currentFrame;
    }

    /**
     * 在正确的 DOM 上下文中执行 JS 评估：优先使用 iframe Frame，否则使用主页面 Page。
     */
    private Object evaluateInContext(String script, Object arg) {
        if (currentFrame != null) {
            return currentFrame.evaluate(script, arg);
        }
        return page.evaluate(script, arg);
    }

    /**
     * 异步收集完整的诊断信息（不阻塞当前测试线程）。
     * 适用于只需要日志记录、不需要阻塞等待的诊断场景。
     */
    public CompletableFuture<ElementOperationException.DiagnosticInfo> collectAsync() {
        return CompletableFuture.supplyAsync(this::collect, DIAGNOSTIC_EXECUTOR);
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
            Map<String, Object> result = (Map<String, Object>) evaluateInContext(script, selector);

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
     * 构建批量诊断 JS 脚本——一次 DOM 查询返回所有所需字段。
     * 精简为基础检查：exists / visible / enabled / count，复杂诊断移到单独工具类。
     */
    private static String buildBatchDiagnosticScript(boolean detailed) {
        StringBuilder sb = new StringBuilder(512);
        sb.append("(s) => {")
          .append("const e=document.querySelector(s);")
          .append("if(!e) return {exists:false,visible:false,enabled:false,count:0};")
          .append("const cs=getComputedStyle(e);")
          .append("const r={")
          .append("exists:true,")
          .append("visible:e.offsetParent!==null&&cs.display!=='none'&&cs.visibility!=='hidden'&&parseFloat(cs.opacity)>0,")
          .append("enabled:!e.disabled,")
          .append("count:document.querySelectorAll(s).length");
        if (detailed) {
            sb.append(",tagName:e.tagName");
        }
        sb.append("};return r;}");
        return sb.toString();
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
     * 获取可见的遮挡元素信息（selector 作为参数传递，避免字符串注入风险）
     */
    public String getObstructingElements() {
        try {
            String script = "(s) => {"
                + "const e=document.querySelector(s);"
                + "if(!e) return 'Element not found';"
                + "const r=e.getBoundingClientRect();"
                + "const top=document.elementFromPoint(r.left+r.width/2,r.top+r.height/2);"
                + "if(top===e) return 'None';"
                + "return top?'Obstructed by '+top.tagName:'Unknown';"
                + "}";
            Object result = evaluateInContext(script, selector);
            return result != null ? result.toString() : "Unable to check";
        } catch (Exception e) {
            return "Unable to check: " + e.getMessage();
        }
    }

    /**
     * 获取 DOM 结构上下文（简化为 tagName + id/class 的一行摘要）
     */
    public String getDomContext() {
        try {
            String script = "(s) => {"
                + "const e=document.querySelector(s);"
                + "if(!e) return 'N/A';"
                + "let p=e.tagName.toLowerCase();"
                + "if(e.id) p+='#'+e.id;"
                + "else if(e.className&&typeof e.className==='string'){"
                + "  const c=e.className.trim().split(/\\s+/).slice(0,2).join('.');"
                + "  if(c) p+='.'+c;"
                + "}"
                + "return p;"
                + "}";
            Object result = evaluateInContext(script, selector);
            return result != null ? result.toString() : "N/A";
        } catch (Exception e) {
            return "N/A";
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
     * 异步捕获失败截图（不阻塞测试线程）。
     * I/O 操作使用共享诊断线程池执行，避免高并发场景下失败路径雪崩。
     */
    public CompletableFuture<String> captureFailureScreenshotAsync(String testName) {
        return CompletableFuture.supplyAsync(() -> captureFailureScreenshot(testName), DIAGNOSTIC_EXECUTOR);
    }

    /**
     * 获取元素内部 HTML（selector 作为参数传递，安全）
     */
    public String getElementHtml() {
        try {
            String script = "(s) => {"
                + "const e=document.querySelector(s);"
                + "return e&&e.outerHTML?e.outerHTML.substring(0,500):'N/A';"
                + "}";
            Object evaluateResult = evaluateInContext(script, selector);
            return evaluateResult != null ? evaluateResult.toString() : "N/A";
        } catch (Exception e) {
            return "Unable to get HTML";
        }
    }
}
