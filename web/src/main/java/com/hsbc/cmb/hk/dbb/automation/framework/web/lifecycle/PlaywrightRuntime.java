package com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.PlaywrightManager;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.state.PlaywrightRuntimeState;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.provider.DefaultRuntimeProvider;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.concurrent.ConcurrentContextExecutor;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.concurrent.ConcurrentContextOptions;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.concurrent.ContextTask;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.concurrent.ContextTaskResult;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.event.PageEventMonitor;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.config.PlaywrightConfigManager;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.config.ProxyConfigResolver;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.browser.BrowserRegistry;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.browser.BrowserRegistryImpl;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.browser.BrowserStartup;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.browser.BrowserStartupImpl;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.browser.BrowserRestart;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.browser.BrowserRestartImpl;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.browser.BrowserCleanup;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.browser.BrowserCleanupImpl;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.browser.BrowserCrashGuard;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.context.ContextRegistry;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.context.ContextRegistryImpl;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.context.CustomOptions;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.context.CustomOptionsManager;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.page.PageRegistry;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.page.PageRegistryImpl;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.scenario.ScenarioLifecycle;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.serenity.PlaywrightSerenityBridge;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.serenity.SerenityBusBridge;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.serenity.TestContextBridge;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.bootstrap.PlaywrightContextManager;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.bootstrap.PlaywrightInitializer;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.media.PlaywrightScreenshotManager;

import java.util.Objects;

/**
 * 实例门面（DI 二期 Phase 2 组合根 / composition root）。
 *
 * <p>持有 WEB-P1-1 拆分的 6 个角色协作者接口，默认实现为各自的 {@code *Impl.INSTANCE} 无状态单例。
 * {@link PlaywrightManager} 静态门面委托到本实例（兼容层），从而在<b>不触碰 53 个外部调用文件</b>的前提下
 * 完成面向对象化；后续 Phase 3 可经构造注入替换任一协作者实现（真运行时多态）。</p>
 *
 * <p><b>铁律（对齐 15 号设计文档 §6）：</b>本实例仅持有<b>无状态协作者</b>，所有 per-thread / 可变状态仍留在
 * {@link PlaywrightRuntimeState}。单例引用 {@code runtime} 是<b>受控测试性换实现 seam</b>（与 WEB-P0-2 的
 * {@code PlaywrightManager.setProvider} 同源），仅 {@link #setInstance} / {@link #resetInstance} 可改，非业务可变状态。</p>
 */
public final class PlaywrightRuntime {

    private static volatile PlaywrightRuntime runtime = new PlaywrightRuntime();

    /** Browser 注册与键/锁派发（默认 = {@link BrowserRegistryImpl#INSTANCE}）。 */
    public final BrowserRegistry browserRegistry;
    /** Context 注册（默认 = {@link ContextRegistryImpl#INSTANCE}）。 */
    public final ContextRegistry contextRegistry;
    /** Page 注册（默认 = {@link PageRegistryImpl#INSTANCE}）。 */
    public final PageRegistry pageRegistry;
    /** Browser 启动编排（默认 = {@link BrowserStartupImpl#INSTANCE}）。 */
    public final BrowserStartup browserStartup;
    /** Browser 重启与崩溃重建（默认 = {@link BrowserRestartImpl#INSTANCE}）。 */
    public final BrowserRestart browserRestart;
    /** Browser 清理与断开守卫（默认 = {@link BrowserCleanupImpl#INSTANCE}）。 */
    public final BrowserCleanup browserCleanup;
    /**
     * 生命周期可变状态根（默认 = {@link PlaywrightRuntimeState#INSTANCE}）。
     *
     * <p>doc16 Phase 2：状态根与其余 6 个协作者一样成为<b>可替换的角色</b>，
     * 协作者一律经 {@link LifecycleState} 的受控操作访问状态，不再直读容器字段。
     */
    public final LifecycleState state;

    /** 默认构造：各协作者 = 生产实现单例（{@code *Impl.INSTANCE}），状态根 = {@code PlaywrightRuntimeState.INSTANCE}。 */
    public PlaywrightRuntime() {
        this(BrowserRegistryImpl.INSTANCE,
                ContextRegistryImpl.INSTANCE,
                PageRegistryImpl.INSTANCE,
                BrowserStartupImpl.INSTANCE,
                BrowserRestartImpl.INSTANCE,
                BrowserCleanupImpl.INSTANCE,
                PlaywrightRuntimeState.INSTANCE);
    }

    /**
     * 注入构造（Phase 3 替换实现用）：任意协作者均可被其测试替身 / 替代实现替换。
     *
     * @throws NullPointerException 任一协作者为 {@code null}（含 {@code state}）
     */
    public PlaywrightRuntime(BrowserRegistry browserRegistry,
                             ContextRegistry contextRegistry,
                             PageRegistry pageRegistry,
                             BrowserStartup browserStartup,
                             BrowserRestart browserRestart,
                             BrowserCleanup browserCleanup,
                             LifecycleState state) {
        this.browserRegistry = Objects.requireNonNull(browserRegistry, "browserRegistry");
        this.contextRegistry = Objects.requireNonNull(contextRegistry, "contextRegistry");
        this.pageRegistry = Objects.requireNonNull(pageRegistry, "pageRegistry");
        this.browserStartup = Objects.requireNonNull(browserStartup, "browserStartup");
        this.browserRestart = Objects.requireNonNull(browserRestart, "browserRestart");
        this.browserCleanup = Objects.requireNonNull(browserCleanup, "browserCleanup");
        this.state = Objects.requireNonNull(state, "state");
    }

    /** 默认单例入口（生产运行时；Phase 3 可经 {@link #setInstance} 替换为替身组合根）。 */
    public static PlaywrightRuntime instance() {
        return runtime;
    }

    /**
     * 测试性换实现入口（Phase 3 真多态）：安装一个持有替身协作者的运行时，
     * 替换后所有经 {@link #instance()} 触达的协作行为随之改变。生产代码不应调用。
     *
     * @throws NullPointerException runtime 为 {@code null}
     */
    public static void setInstance(PlaywrightRuntime runtime) {
        PlaywrightRuntime.runtime = Objects.requireNonNull(runtime, "runtime");
    }

    /** 复位为默认生产运行时（{@code *Impl.INSTANCE} 组合根）。 */
    public static void resetInstance() {
        runtime = new PlaywrightRuntime();
    }
}
