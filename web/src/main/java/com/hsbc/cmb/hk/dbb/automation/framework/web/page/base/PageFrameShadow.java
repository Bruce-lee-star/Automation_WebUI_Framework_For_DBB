package com.hsbc.cmb.hk.dbb.automation.framework.web.page.base;

import com.microsoft.playwright.Frame;
import com.microsoft.playwright.Page;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * 框架/Shadow 切换子模块（T5-5 拆分）。
 * <p>原 {@link BasePage} 的 frame / shadow 上下文切换方法体下沉至此。
 * <p>本类与 BasePage 同包（非 delegate 子包），以便直接调用其包级私有上下文 seam
 * （{@code activateFrame} / {@code deactivateFrame} / {@code pushShadow} / {@code popShadow} /
 * {@code clearShadows} / {@code getShadowDepth} / {@code peekShadow}）；业务 Page 因处于不同包，
 * 编译期即无法访问这些 seam，杜绝误用。其余逻辑仅依赖 {@link BasePage} 公开 API
 * （{@code getPage} / {@code getCurrentFrame} / {@code getConfig} / {@code getFrame} 等），行为零回归；
 * 公开 API 不变（新增 seam 均为 additive）。
 */
public final class PageFrameShadow {

    // 路由回 BasePage 的日志类别，保证生产日志溯源与原实现一致（委派类跨包无法直访 BasePage.logger）。
    private static final Logger log = LoggerFactory.getLogger(BasePage.class);

    private PageFrameShadow() {
        // 纯静态工具类，禁止实例化
    }

    /**
     * 入参校验：委派方法均为公开静态 API，必须防止业务方误传 null 而抛裸 NPE。
     * 抛 {@link IllegalArgumentException} 以给出语义化错误，便于生产溯源与快速定位。
     */
    private static void requireNonNullPage(BasePage bp) {
        if (bp == null) {
            throw new IllegalArgumentException(
                    "BasePage instance must not be null when performing frame/shadow operations");
        }
    }

    public static Frame getFrame(BasePage bp, String name) {
        requireNonNullPage(bp);
        return bp.getPage().frame(name);
    }

    public static Frame switchToFrame(BasePage bp, String nameOrSelector) {
        requireNonNullPage(bp);
        if (nameOrSelector == null || nameOrSelector.isBlank()) {
            throw new IllegalArgumentException("switchToFrame: nameOrSelector must not be blank");
        }
        Page page = bp.getPage();
        // 策略 1：按 Playwright 原生 frame(name) 查找（匹配 name/id 属性）
        Frame frame = page.frame(nameOrSelector);
        if (frame == null) {
            // 策略 2：回退为 CSS 选择器
            try {
                com.microsoft.playwright.ElementHandle iframeEl = page.locator(nameOrSelector).elementHandle();
                frame = iframeEl.contentFrame();
            } catch (Exception e) {
                log.error("Failed to switch to iframe by selector '{}': {}", nameOrSelector, e.getMessage());
            }
        }
        if (frame == null) {
            throw new RuntimeException("Frame not found: '" + nameOrSelector
                    + "'. Tried as name/id and CSS selector. Available frames: " + page.frames().size());
        }
        bp.activateFrame(frame);
        log.info("Switched to iframe: '{}'", nameOrSelector);
        return frame;
    }

    public static void switchToShadow(BasePage bp, String hostSelector) {
        requireNonNullPage(bp);
        if (hostSelector == null || hostSelector.isBlank()) {
            throw new RuntimeException("switchToShadow: hostSelector 不能为空");
        }
        bp.pushShadow(hostSelector.trim());
        log.info("Switched into shadowRoot of '{}' (depth={})", hostSelector, bp.getShadowDepth());
    }

    public static String switchToDefaultShadow(BasePage bp) {
        requireNonNullPage(bp);
        String popped = bp.popShadow();
        if (popped != null) {
            log.info("Exited one shadowRoot (depth now {})", bp.getShadowDepth());
        }
        return popped;
    }

    public static void switchToDefaultShadowAll(BasePage bp) {
        requireNonNullPage(bp);
        bp.clearShadows();
        log.info("Exited all shadowRoots");
    }

    public static Frame switchToFrameAndWait(BasePage bp, Runnable trigger, String nameOrSelector, int timeoutSecs) {
        requireNonNullPage(bp);
        if (nameOrSelector == null || nameOrSelector.isBlank()) {
            throw new IllegalArgumentException("switchToFrameAndWait: nameOrSelector must not be blank");
        }
        Page page = bp.getPage();
        int timeoutMs = (timeoutSecs > 0) ? timeoutSecs * 1000
                : (int) bp.getConfig().getNavigationTimeout();
        // 若 iframe 已挂载（静态场景），直接切，无需走事件监听
        Frame existing = matchFrame(bp, nameOrSelector);
        if (existing != null) {
            bp.activateFrame(existing);
            log.info("Switched to iframe (already attached): '{}'", nameOrSelector);
            return existing;
        }
        // 纯事件驱动：注册 onFrameAttached 监听，命中即放行（用 latch 等待，无轮询）
        // 注意：监听器运行在 Playwright 内部事件线程；同一 Page 切勿在多个线程中并发调用本方法，
        // 否则多个监听器会各自捕获 frame 并重复激活。
        final AtomicReference<Frame> matched = new AtomicReference<>();
        final CountDownLatch latch = new CountDownLatch(1);
        Consumer<Frame> listener = f -> {
            // 仅首个命中的 frame 触发：frameMatches 先判定，compareAndSet 兜底只捕获一次；
            // AtomicReference 提供监听线程与调用线程间的可靠内存可见性，不依赖 latch 顺序。
            if (frameMatches(f, nameOrSelector) && matched.compareAndSet(null, f)) {
                latch.countDown();
            }
        };
        page.onFrameAttached(listener);
        try {
            if (trigger != null) trigger.run();   // 执行触发动作，期间监听捕获目标 frame
            boolean got = false;
            try { got = latch.await(timeoutMs, TimeUnit.MILLISECONDS); }
            catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
            if (!got || matched.get() == null) {
                throw new RuntimeException("Timed out (" + (timeoutMs / 1000) + "s) waiting for iframe to attach: '"
                        + nameOrSelector + "'. Available frames: " + page.frames().size());
            }
        } finally {
            try { page.offFrameAttached(listener); } catch (Exception ignore) {}
        }
        bp.activateFrame(matched.get());
        log.info("Switched to iframe (waited & ready via onFrameAttached): '{}'", nameOrSelector);
        return matched.get();
    }

    public static Frame switchToFrameAndWait(BasePage bp, Runnable trigger, String nameOrSelector) {
        return switchToFrameAndWait(bp, trigger, nameOrSelector, 0);
    }

    public static Frame switchToFrameAndWait(BasePage bp, String nameOrSelector, int timeoutSecs) {
        return switchToFrameAndWait(bp, (Runnable) null, nameOrSelector, timeoutSecs);
    }

    public static Frame switchToFrameAndWait(BasePage bp, String nameOrSelector) {
        return switchToFrameAndWait(bp, (Runnable) null, nameOrSelector, 0);
    }

    private static Frame matchFrame(BasePage bp, String nameOrSelector) {
        requireNonNullPage(bp);
        Page page = bp.getPage();
        Frame f = page.frame(nameOrSelector);
        if (f != null) return f;
        for (Frame fr : page.frames()) {
            if (frameMatches(fr, nameOrSelector)) return fr;
        }
        return null;
    }

    private static boolean frameMatches(Frame f, String nameOrSelector) {
        if (f == null || nameOrSelector == null) return false;
        if (nameOrSelector.equals(f.name())) return true;          // name/id 精确匹配
        String url = f.url();
        return url != null && url.contains(nameOrSelector);          // url 片段兜底
    }

    public static Frame switchToFrame(BasePage bp, int index) {
        requireNonNullPage(bp);
        List<Frame> frames = bp.getPage().frames();
        if (index < 0 || index >= frames.size()) {
            throw new IndexOutOfBoundsException("Invalid frame index: " + index + " (total: " + frames.size() + ")");
        }
        Frame selectedFrame = frames.get(index);
        bp.activateFrame(selectedFrame);
        log.info("Switched to iframe by index: {} (total: {})", index, frames.size());
        return selectedFrame;
    }

    public static void switchToDefaultContent(BasePage bp) {
        requireNonNullPage(bp);
        bp.deactivateFrame();
    }

    public static List<Frame> getAllFrames(BasePage bp) {
        return bp.getPage().frames();
    }

    public static void executeInFrame(BasePage bp, String frameName, Consumer<Frame> action) {
        requireNonNullPage(bp);
        Frame frame = bp.getFrame(frameName);
        if (frame == null) throw new RuntimeException("Frame not found: " + frameName);
        action.accept(frame);
    }
}
