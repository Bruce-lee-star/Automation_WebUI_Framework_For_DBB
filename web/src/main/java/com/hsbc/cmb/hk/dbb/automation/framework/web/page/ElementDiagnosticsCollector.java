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
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
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
    //  修复 B-3：原 newCachedThreadPool 无界，失败路径高并发（成百上千元素诊断）会无限新建线程、
    //   耗尽系统线程/内存。改为固定大小（失败诊断本就属低频兜底，8 线程足够）+ 调用者运行拒绝策略
    //   （诊断任务在调用线程同步执行，保证失败信息不丢，同时避免队列积压导致 OOM）。
    private static final int DIAGNOSTIC_MAX_THREADS = 8;
    private static final ExecutorService DIAGNOSTIC_EXECUTOR =
            new ThreadPoolExecutor(DIAGNOSTIC_MAX_THREADS, DIAGNOSTIC_MAX_THREADS, 0L, TimeUnit.MILLISECONDS,
                    new LinkedBlockingQueue<>(DIAGNOSTIC_MAX_THREADS * 2),
                    new ThreadFactory() {
                        private final AtomicInteger counter = new AtomicInteger(0);
                        @Override
                        public Thread newThread(Runnable r) {
                            Thread t = new Thread(r, "diagnostic-" + counter.incrementAndGet());
                            t.setDaemon(true);
                            t.setUncaughtExceptionHandler((th, ex) ->
                                logger.warn("[diagnostic] Uncaught in thread {}: {}", th.getName(), ex.getMessage()));
                            return t;
                        }
                    },
                    new ThreadPoolExecutor.CallerRunsPolicy());

    static {
        com.hsbc.cmb.hk.dbb.automation.framework.common.ShutdownCoordinator.register(
                com.hsbc.cmb.hk.dbb.automation.framework.common.ShutdownCoordinator.ORDER_DIAGNOSTICS,
                "diagnostics", () -> {
                    DIAGNOSTIC_EXECUTOR.shutdown();
                    try {
                        if (!DIAGNOSTIC_EXECUTOR.awaitTermination(3, TimeUnit.SECONDS)) {
                            DIAGNOSTIC_EXECUTOR.shutdownNow();
                        }
                    } catch (InterruptedException e) {
                        DIAGNOSTIC_EXECUTOR.shutdownNow();
                        Thread.currentThread().interrupt();
                    }
                });
    }


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
     * CompletableFuture 内部已设置 exceptionally() 处理异常，调用方无需等待。
     */
    public CompletableFuture<ElementOperationException.DiagnosticInfo> collectAsync() {
        return CompletableFuture.supplyAsync(this::collect, DIAGNOSTIC_EXECUTOR)
                .whenComplete((info, ex) -> {
                    if (ex != null) {
                        logger.debug("[diagnostic] Async collect failed for [{}]: {}", selector, ex.getMessage());
                    }
                });
    }

    /**
     * 收集完整的诊断信息（批量单次 JS 调用，4 次 IPC → 1 次 IPC）
     */
    public ElementOperationException.DiagnosticInfo collect() {
        ElementOperationException.DiagnosticInfo info = ElementOperationException.DiagnosticInfo.create();

        try {
            // 批量收集：一次 evaluate() 完成存在性/可见性/可编辑性/数量/标签/属性检查
            boolean detailed = PlaywrightManager.config().isElementDetailedDiagnostics();
            Map<String, Object> result;

            // 使用 locator.evaluate() 而非 page.evaluate(selector)，以正确处理
            // CSS / XPath / role / text 等所有 Playwright 支持的定位语义（P2-19）。
            // ⚠ 但 locator 语义下传入脚本的是【元素】而非选择器字符串，必须配套使用
            //   buildElementDiagnosticScript（见其 Javadoc），否则 querySelector 会把元素当选择器而抛 SyntaxError。
            if (locator != null) {
                result = castToMap(locator.evaluate(buildElementDiagnosticScript(detailed), null));
            } else {
                if (selector == null || selector.isBlank()) {
                    throw new IllegalArgumentException(
                            "Cannot collect element diagnostics: locator is null and selector is blank");
                }
                result = castToMap(evaluateInContext(buildSelectorDiagnosticScript(detailed), selector));
            }

            info.existsInDom(getBoolean(result, "exists"))
               .isVisible(getBoolean(result, "visible"))
               .isEnabled(getBoolean(result, "enabled"))
               .isEditable(getBoolean(result, "editable"))
               // locator 语义无法由单元素推导匹配数，改用 locator.count()；selector 语义由脚本统计
               .elementCount(locator != null ? getElementCount() : getInt(result, "count"));

            if (detailed) {
                info.tagName(getString(result, "tagName"));
                Map<String, String> attrs = castToStringMap(result == null ? null : result.get("attributes"));
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
     * 构建【以元素为入参】的诊断脚本，配合 {@code locator.evaluate()} 使用。
     *
     * <p>⚠ <b>为什么必须与 {@link #buildSelectorDiagnosticScript(boolean)} 分开：</b>
     * Playwright 的 {@code Locator.evaluate(script, arg)} 会把匹配到的<b>元素</b>作为第一个实参传入脚本；
     * 若沿用 {@code document.querySelector(s)} 的写法，实参会变成元素对象本身，从而抛出
     * {@code SyntaxError: '[object HTMLInputElement]' is not a valid selector}。
     * 该错误只在元素定位失败时暴露，导致「最需要诊断信息的时刻，采集器必然失效」。</p>
     *
     * <p>locator 语义下无法由单个元素推导匹配总数，count 改由 {@link #getElementCount()} 提供。</p>
     */
    private static String buildElementDiagnosticScript(boolean detailed) {
        StringBuilder sb = new StringBuilder(512);
        sb.append("(e) => {")
          .append("if(!e) return {exists:false,visible:false,enabled:false,editable:false};")
          .append("const cs=getComputedStyle(e);")
          .append("const r={")
          .append("exists:true,")
          .append("visible:e.offsetParent!==null&&cs.display!=='none'&&cs.visibility!=='hidden'&&parseFloat(cs.opacity)>0,")
          .append("enabled:!e.disabled,")
          .append("editable:!e.disabled&&!e.readOnly");
        if (detailed) {
            sb.append(DIAGNOSTIC_DETAIL_FIELDS);
        }
        sb.append("};return r;}");
        return sb.toString();
    }

    /**
     * 构建【以选择器字符串为入参】的诊断脚本，配合 {@code page/frame.evaluate()} 使用。
     * 仅适用于 CSS 选择器；非 CSS 描述符请走 {@link #buildElementDiagnosticScript(boolean)}。
     */
    private static String buildSelectorDiagnosticScript(boolean detailed) {
        StringBuilder sb = new StringBuilder(512);
        sb.append("(s) => {")
          .append("const e=document.querySelector(s);")
          .append("if(!e) return {exists:false,visible:false,enabled:false,editable:false,count:0};")
          .append("const cs=getComputedStyle(e);")
          .append("const r={")
          .append("exists:true,")
          .append("visible:e.offsetParent!==null&&cs.display!=='none'&&cs.visibility!=='hidden'&&parseFloat(cs.opacity)>0,")
          .append("enabled:!e.disabled,")
          .append("editable:!e.disabled&&!e.readOnly,")
          .append("count:document.querySelectorAll(s).length");
        if (detailed) {
            sb.append(DIAGNOSTIC_DETAIL_FIELDS);
        }
        sb.append("};return r;}");
        return sb.toString();
    }

    /**
     * 详细模式下追加的字段：tagName 与元素属性集合。
     * <p>⚠ 旧实现只追加了 {@code tagName}，却从未产出 {@code attributes}，
     * 而调用方一直在读取 {@code attributes} → 该诊断项恒为 null（静默失真）。</p>
     */
    private static final String DIAGNOSTIC_DETAIL_FIELDS =
            ",tagName:e.tagName"
          + ",attributes:Object.fromEntries(Array.from(e.attributes||[]).map(a=>[a.name,a.value]))";

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castToMap(Object value) {
        return (value instanceof Map) ? (Map<String, Object>) value : null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> castToStringMap(Object value) {
        return (value instanceof Map) ? (Map<String, String>) value : null;
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
     * CompletableFuture 内部已设置 exceptionally() 处理异常，调用方无需等待。
     */
    public CompletableFuture<String> captureFailureScreenshotAsync(String testName) {
        return CompletableFuture.supplyAsync(() -> captureFailureScreenshot(testName), DIAGNOSTIC_EXECUTOR)
                .whenComplete((path, ex) -> {
                    if (ex != null) {
                        logger.debug("[diagnostic] Async screenshot failed for [{}]: {}", selector, ex.getMessage());
                    }
                });
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
