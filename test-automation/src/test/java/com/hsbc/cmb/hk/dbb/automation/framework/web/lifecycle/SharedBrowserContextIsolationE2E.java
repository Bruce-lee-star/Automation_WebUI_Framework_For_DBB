package com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle;

import com.hsbc.cmb.hk.dbb.automation.framework.web.concurrent.ConcurrentScenarioExecutor;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.Cookie;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 实浏览器 E2E：验证「共享 Browser 模式（单 Browser + 多 Context）」下的<b>上下文隔离</b>不变式。
 *
 * <p><b>为什么需要它：</b> {@link PlaywrightManagerSharedBrowserTest} 已从白盒角度证明共享模式下
 * 实例键跨线程一致、互斥锁为进程级；{@link PlaywrightManagerConcurrencyTest#perThreadPageIsolation()}
 * 已证明 manager 级 per-thread 状态隔离。但二者都<b>不启动真实浏览器</b>，无法证明「共享同一个
 * {@code Browser} 实例的两个 {@code BrowserContext} 在 cookie / storage 维度确实彼此独立」——
 * 这正是共享 Browser 模式能在并发 scenario 间安全复用的根基。本 E2E 补齐这条端到端证据链。</p>
 *
 * <p><b>运行约束（企业级）：</b>本测试<b>仅在共享 Browser 模式被显式开启时</b>执行
 * （{@code serenity.playwright.shared.browser.enabled=true}）。普通 {@code mvn test} 默认该开关为
 * {@code false}，{@link #setUpSharedBrowser()} 经 {@link Assume#assumeTrue} 优雅跳过，<b>零侵入</b>
 * 默认并发模型（每线程独立 Browser）。CI 通过激活 {@code shared-browser-e2e} profile 在独立 fork 中
 * 以该开关运行本类（需具备浏览器运行环境）。</p>
 *
 * <p><b>不变式：</b>① 两线程拿到不同 {@code BrowserContext} 实例；② 二者位于同一个共享
 * {@code Browser} 实例；③ 仅在 ctx1 写入的探测 Cookie 对 ctx2 不可见（反之亦然）。</p>
 */
public class SharedBrowserContextIsolationE2E {

    private static final String ORIGIN = "https://example.com/";
    private static final String COOKIE_NAME = "dbb_iso_probe";
    private static final String COOKIE_VALUE_CTX1 = "ctx1-only";
    private static final String COOKIE_VALUE_CTX2 = "ctx2-only";

    @BeforeAll
    public static void setUpSharedBrowser() {
        // 自跳：默认每线程独立 Browser 模型下不跑真实浏览器验证，避免污染默认并发语义。
        // 并发执行器已在 prepareSharedBrowser() 内按 Playwright 官方推荐主动启用共享模式，
        // 故本 E2E 不再依赖全局 serenity.playwright.shared.browser.enabled，仅由专属开关激活。
        Assumptions.assumeTrue(
                Boolean.parseBoolean(System.getProperty("dbb.e2e.shared.browser", "false")),
                "Shared browser E2E is opt-in: pass -Ddbb.e2e.shared.browser=true "
                        + "(or activate profile 'shared-browser-e2e') to run it; "
                        + "shared mode is enabled automatically by prepareSharedBrowser().");

        // 编排线程预热：全局初始化 + 按 Playwright 推荐启用共享 Browser + 设共享 configId + 预热（单 Browser 多 Context）。
        ConcurrentScenarioExecutor.prepareSharedBrowser();
    }

    @Test
    public void sharedBrowserYieldsDistinctIsolatedContexts() throws Exception {
        // 两个并发虚拟用户线程各自取 Page：共享 Browser，但 Context 按线程隔离。
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Page> f1 = pool.submit(acquirePageOnSharedBrowser());
            Future<Page> f2 = pool.submit(acquirePageOnSharedBrowser());
            Page page1 = f1.get(60, TimeUnit.SECONDS);
            Page page2 = f2.get(60, TimeUnit.SECONDS);

            BrowserContext ctx1 = page1.context();
            BrowserContext ctx2 = page2.context();

            // 不变式 ①：两线程拿到的是不同 BrowserContext 实例。
            assertNotSame( ctx1,  ctx2, "共享 Browser 模式下各线程必须持有独立的 BrowserContext");

            // 不变式 ②：二者位于同一个共享 Browser 实例（单 Browser + 多 Context 模型）。
            Browser browser1 = ctx1.browser();
            Browser browser2 = ctx2.browser();
            assertSame( browser1,  browser2, "两个 Context 必须位于同一个共享 Browser 实例上");
            assertTrue( browser1.isConnected(), "共享 Browser 必须处于连接状态");

            // 不变式 ③：Cookie 隔离 —— 仅在 ctx1 写入探测 Cookie。
            ctx1.addCookies(Collections.singletonList(
                    new Cookie(COOKIE_NAME, COOKIE_VALUE_CTX1).setUrl(ORIGIN)));
            List<Cookie> cookies1 = ctx1.cookies(ORIGIN);
            List<Cookie> cookies2 = ctx2.cookies(ORIGIN);

            boolean ctx1HasProbe = cookies1.stream()
                    .anyMatch(c -> COOKIE_NAME.equals(c.name) && COOKIE_VALUE_CTX1.equals(c.value));
            boolean ctx2HasProbe = cookies2.stream()
                    .anyMatch(c -> COOKIE_NAME.equals(c.name) && COOKIE_VALUE_CTX1.equals(c.value));

            assertTrue( ctx1HasProbe, "ctx1 应能看到自己写入的 Cookie");
            assertFalse( ctx2HasProbe, "ctx2 必须隔离 ctx1 的 Cookie（共享 Browser 模式核心不变式）");

            // 不变式 ④：反向确认 —— ctx2 写入不影响 ctx1。
            ctx2.addCookies(Collections.singletonList(
                    new Cookie(COOKIE_NAME, COOKIE_VALUE_CTX2).setUrl(ORIGIN)));
            List<Cookie> cookies1Again = ctx1.cookies(ORIGIN);
            boolean ctx1SeesCtx2 = cookies1Again.stream()
                    .anyMatch(c -> COOKIE_NAME.equals(c.name) && COOKIE_VALUE_CTX2.equals(c.value));
            assertFalse( ctx1SeesCtx2, "ctx1 必须隔离 ctx2 的 Cookie");
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * 在调用线程设共享 configId 并取 Page（模拟并发虚拟用户复用同一 Browser）。
     * 每个 worker 必须各自设相同的共享 configId，才能经 {@code keyFor("shared:<configId>")}
     * 命中同一个 Browser 实例。
     */
    private static Callable<Page> acquirePageOnSharedBrowser() {
        return () -> {
            PlaywrightManager.setConfigId(PlaywrightManager.sharedConfigId());
            return PlaywrightManager.getPage();
        };
    }

    @AfterAll
    public static void tearDownSharedBrowser() {
        if (!PlaywrightManager.isSharedBrowserMode()) {
            return;
        }
        // 收尾共享 Browser（专用 fork，不影响其它用例）。收尾失败不应掩盖测试结论。
        try {
            PlaywrightManager.cleanupAll();
        } catch (Throwable t) {
            System.err.println("[SharedBrowserContextIsolationE2E] cleanup error: " + t);
        }
    }
}
