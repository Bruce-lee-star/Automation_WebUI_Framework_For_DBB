package com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.lock.LifecycleLockMediator;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.PlaywrightRuntime;
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

import com.hsbc.cmb.hk.dbb.automation.framework.web.cloud.BrowserStackManager;
import com.hsbc.cmb.hk.dbb.automation.framework.web.cloud.BrowserStrategy;
import com.hsbc.cmb.hk.dbb.automation.framework.web.cloud.CloudBrowserStrategy;
import com.hsbc.cmb.hk.dbb.automation.framework.web.cloud.LocalBrowserStrategy;
import com.hsbc.cmb.hk.dbb.automation.framework.web.config.AutoBrowserProcessor;
import com.hsbc.cmb.hk.dbb.automation.framework.web.config.WebFrameworkConfig;
import com.hsbc.cmb.hk.dbb.automation.framework.web.config.FrameworkConfigManager;
import com.hsbc.cmb.hk.dbb.automation.framework.web.core.FrameworkState;
import com.hsbc.cmb.hk.dbb.automation.framework.web.core.RuntimeProvider;
import com.hsbc.cmb.hk.dbb.automation.framework.web.exceptions.BrowserException;
import com.hsbc.cmb.hk.dbb.automation.framework.web.exceptions.InitializationException;
import com.hsbc.cmb.hk.dbb.automation.framework.common.route.RouteLifecycleRegistry;
import com.hsbc.cmb.hk.dbb.automation.framework.web.codegen.spi.RoleCodegenBridgeRegistry;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.base.BasePage;
import com.hsbc.cmb.hk.dbb.automation.framework.common.config.VerboseLogging;
import com.microsoft.playwright.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Dimension;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import com.hsbc.cmb.hk.dbb.automation.framework.core.context.ContextKey;
import com.hsbc.cmb.hk.dbb.automation.framework.core.context.TestContextHolder;

/**
 * 企业级 Playwright Manager —— 运行时对象的<b>稳定公开门面（Facade）</b>，
 * 统一管理 Playwright / Browser / Context / Page 的生命周期。
 *
 * <h2>设计立场（满足 Java 三大特性 + 企业级）</h2>
 * <ul>
 *   <li><b>封装</b>：可变状态收敛到 {@link PlaywrightRuntimeState}（唯一受管容器）；
 *       本类自身无可变静态字段。配置读取经 {@code config()} 暴露为<b>只读</b>视图；
 *       自定义选项经 {@code customOptions()} 暴露为<b>受生命周期治理的 per-thread 可变</b>自定义 Context 选项
 *       （意图内的变更 API：setter 落 per-thread {@code TestContextHolder} 并触发延迟 Context 重建），二者均不暴露框架可变状态根。</li>
 *   <li><b>组合优于继承</b>：本门面与所有协作者均为 {@code final}，**刻意不使用 class 继承**
 *       （避免脆弱基类、保护封装）；扩展通过「接口契约 + 组合」实现（如 {@code BrowserStrategy}）。</li>
 *   <li><b>多态</b>：发生在 seam 层——{@link PlaywrightRuntime#setInstance} 整体换实现、
 *       {@link #setProvider} 换运行时对象源；每个角色接口 ≥2 实现（生产 {@code XImpl} + 测试替身）。</li>
 * </ul>
 *
 * <h2>API 边界</h2>
 * <ul>
 *   <li><b>稳定公开契约</b>：{@link #getPlaywright}/{@link #getBrowser}/{@link #getContext}/{@link #getPage}、
 *       生命周期入口、{@link #config()}/{@link #customOptions()} —— 业务与框架代码均可依赖。</li>
 *   <li><b>框架内部 seam</b>（包级私有）：{@code getPageThreadLocal()}/{@code getFrameworkState()} 等仅供同包协作者。</li>
 *   <li>运行时对象获取一律经 {@code provider → PlaywrightRuntime 组合根}，组合根可整体替换（真多态）。</li>
 * </ul>
 *
 * 特性：支持 Serenity BDD 集成、线程安全（per-thread {@code ContextKey}）、灵活的浏览器生命周期管理、
 * 多级浏览器启动策略、避免静态初始化问题。
 */
public class PlaywrightManager {

    // ==================== 静态常量 ====================

    private static final Logger logger = LoggerFactory.getLogger(PlaywrightManager.class);

    // ==================== 非 ThreadLocal 静态变量 ====================
    // 【状态根已收口】原 STATE（PlaywrightRuntimeState 引用）已于 doc16 Phase 2 从本门面移除：
    // 全部可变状态由 PlaywrightRuntimeState 持有（WEB-P1-1 Step 1），并经 LifecycleState 角色接口
    // 对外提供受控操作；生命周期协作者统一经 PlaywrightRuntime.instance().state 访问，
    // 本门面不再持有任何状态根引用（公开面进一步收敛，状态根亦可随组合根整体替换）。
    //
    // 线程安全的实例回收容器（T3-2 企业级隔离）位于 PlaywrightRuntimeState：
    // ⚠️ 默认不变式：Map 的 VALUE（Browser/Playwright 实例）绝不跨线程共享——
    // 存储键为 "threadId:configId"（见 BrowserRegistry.keyFor），保证每个 worker 线程拥有独立实例。
    // 共享 ConcurrentHashMap 仅作为跨线程安全的回收/清理容器（供 cleanupAll 统一关闭），
    // 不再像旧实现那样按 configId 跨线程复用同一个 Browser（评审 P0：单点故障 + 全局串行化）。
    //
    //  例外——共享 Browser 模式（serenity.playwright.shared.browser.enabled=true）：
    // 此时 PlaywrightRuntime.instance().browserRegistry.keyFor() 返回 "shared:configId"，所有线程【有意】复用同一个 Browser 实例，
    // 隔离性改由 per-thread 的 BrowserContext 保证（Playwright 官方并发模型）。
    // 该模式下的配套约束见 restartBrowser()：重启降级为「仅重建本线程 Context」，绝不关闭共享 Browser。

    // 【锁已收口】doc16 Phase 3：三把锁（per-thread Browser 锁 / SHARED_BROWSER_LOCK / CONTEXT_LOCK / PAGE_LOCK）
    // 全部迁入 LifecycleLockMediator 并改为 private，本门面不再暴露任何锁对象。
    // 协作者一律经 LifecycleLockMediator.withXxxLock(...) 进入临界区，
    // 从而消除「外部按任意顺序 synchronized 同一锁 / 长期劫持锁对象」的风险（锁顺序由中介集中保证）。

    // 共享模式下 Browser/Playwright 实例的存储键前缀（去掉 threadId 维度，使所有线程命中同一实例）
    // 包级私有：供同包 BrowserRegistry 协作访问（WEB-P1-1 Step 3）。
    public static final String SHARED_KEY_PREFIX = "shared:";

    // 框架状态引用
    static final FrameworkState frameworkState = FrameworkState.getInstance();

    // ==================== per-thread 变量（ T3-1 收拢：原 3 个 static ThreadLocal 迁入 TestContext，
    //  均为默认 null 语义，迁移后等价；包级可见性保持不变，供同包 PlaywrightSerenityBridge 等访问） ====================

    // ---- 核心 Page/Context ----
    public static final ContextKey<BrowserContext> CONTEXT_KEY = ContextKey.of("playwrightManager.context", BrowserContext.class);
    public static final ContextKey<Page> PAGE_KEY = ContextKey.of("playwrightManager.page", Page.class);

    // ---- 配置标识 ----
    public static final ContextKey<String> CURRENT_CONFIG_ID_KEY = ContextKey.of("playwrightManager.currentConfigId", String.class);

    // ==================== 静态初始化块 ====================

    static {
        // 委托给 PlaywrightInitializer 处理初始化逻辑
        PlaywrightInitializer.initializePlaywrightPaths();
        PlaywrightInitializer.cleanupPlaywrightTempDirs();
        // 浏览器下载延迟到实际需要时，不在静态初始化阶段下载
    }

    // ==================== 初始化相关方法（委托给 PlaywrightInitializer） ====================

    // ==================== 生命周期管理方法 ====================

    public static synchronized void initialize() {
        PlaywrightRuntime.instance().browserStartup.initialize();
    }

    // ==================== 实例访问方法 ====================

    /**
     * 获取当前配置ID
     */
    public static String getCurrentConfigId() {
        if (TestContextHolder.get().get(CURRENT_CONFIG_ID_KEY) == null) {
            TestContextHolder.get().set(CURRENT_CONFIG_ID_KEY,PlaywrightRuntime.instance().browserStartup.generateConfigId());
            // 修复 4.1：懒初始化 configId 时同步标记 frameworkState 为已初始化，
            // 避免 getContext()/getPage() 因 frameworkState 未初始化而抛 IllegalStateException。
            frameworkState.markInitialized();
        }
        return TestContextHolder.get().get(CURRENT_CONFIG_ID_KEY);
    }

    public static String ensureConfigId() {
        return PlaywrightRuntime.instance().browserRegistry.ensureConfigId();
    }

    public static void setConfigId(String configId) {
        PlaywrightRuntime.instance().browserRegistry.setConfigId(configId);
    }

    public static String sharedConfigId() {
        return PlaywrightRuntime.instance().browserRegistry.sharedConfigId();
    }

    /**
     * 构造「线程隔离」存储键（T3-2 企业级隔离）。
     * <p>旧实现按 configId 在共享 Map 中跨线程复用同一 Browser 实例（评审 P0：单点故障 + 全局串行化）。
     * 现以 {@code threadId:configId} 为键，使每个 worker 线程拥有独立 Browser/Playwright 实例——
     * 并行场景下各 scenario 线程互不共享 Browser 对象，故障与 {@code restartBrowser} 作用域均收敛到本线程。
     * 共享 {@code ConcurrentHashMap} 仅作为线程安全的回收容器，KEY 保证 VALUE 永不跨线程共享。</p>
     *
     * @param configId 当前线程的浏览器配置标识
     * @return 线程隔离的存储键
     */
    /**
     * JVM 级<b>不可变</b>开关：是否启用「共享 Browser」模式（一个 Browser 实例 + 多 Context 并发）。
     *
     * <p><b>为何在类加载时解析一次、而不是每次现读配置：</b>{@link #PlaywrightRuntime.instance().browserRegistry.keyFor(String)} 决定了 Browser 实例
     * 在状态根（{@link PlaywrightRuntimeState}，经 {@link LifecycleState} 访问）Browser 实例表中的存储键。若该开关在 JVM 运行期间发生翻转，同一个 configId 会先后
     * 映射到不同的键，使已创建的 Browser 变成<b>无法回收的孤儿实例</b>（既不在当前键上，
     * 也只有 cleanupAll 能扫到）。因此并发隔离模型必须对 JVM 生命周期全局稳定。
     * <p>由 {@code serenity.playwright.shared.browser.enabled} 控制，默认 {@code false}
     * （保持 T3-2 每线程独立 Browser 的既有行为）。
     */
    public static final boolean SHARED_BROWSER_MODE = PlaywrightRuntime.instance().browserRegistry.resolveSharedBrowserMode();

    /**
     * 当前 JVM 是否启用「共享 Browser」模式。
     *
     * @return true 表示共享单个 Browser，各线程通过独立 BrowserContext 隔离
     * @apiNote <b>框架内部能力（生命周期决策用）</b>，业务 Page / 业务步骤请勿依赖：
     *          该取值决定并发隔离模型，业务侧依赖它会导致与框架生命周期耦合。
     */
    public static boolean isSharedBrowserMode() {
        return SHARED_BROWSER_MODE;
    }

    /**
     * 解析共享 Browser 开关的原始配置值（<b>纯函数</b>，便于单测覆盖各种输入）。
     *
     * <p>容错策略：{@code null} / 空白 → false（默认值）；无法识别的非法值 → false 并<b>告警</b>，
     * 避免静默降级后被误认为「已开启」而难以排查。</p>
     *
     * @param rawValue 原始配置值，可为 null
     * @return 是否启用共享 Browser 模式
     */
    public static boolean parseSharedBrowserMode(String rawValue) {
        if (rawValue == null || rawValue.isBlank()) {
            return false;
        }
        String normalized = rawValue.trim();
        if ("true".equalsIgnoreCase(normalized)) {
            return true;
        }
        if ("false".equalsIgnoreCase(normalized)) {
            return false;
        }
        logger.warn("[shared-browser] Unrecognized value '{}' for "
                        + "serenity.playwright.shared.browser.enabled (expected true/false); falling back to false",
                rawValue);
        return false;
    }

    /**
     * 获取 Playwright 实例
     */
    // ===================== WEB-P0-2 可测试性 DI seam =====================
    // 公开 getter 退化为纯委托门面，经 provider seam（DefaultRuntimeProvider → PlaywrightRuntime 组合根）获取运行时对象。
    // DefaultRuntimeProvider 默认实现委托 PlaywrightRuntime.instance() 的 6 角色协作者（WEB-P1-6 Phase 3），
    // 故组合根可整体替换（PlaywrightRuntime.setInstance）以达成真多态。
    // provider 字段 volatile + 原子换引用，满足可见性；默认 INSTANCE 不可变，生产零额外开销。
    private static volatile RuntimeProvider provider = DefaultRuntimeProvider.INSTANCE;

    public static void setProvider(RuntimeProvider p) {
        if (p == null) {
            throw new IllegalArgumentException("RuntimeProvider must not be null");
        }
        provider = p;
    }

    public static RuntimeProvider getProvider() {
        return provider;
    }

    public static void resetProvider() {
        provider = DefaultRuntimeProvider.INSTANCE;
    }

    public static Playwright getPlaywright() {
        return provider.getPlaywright();
    }

    public static Browser getBrowser() {
        return provider.getBrowser();
    }

    /**
     * 创建并获取 BrowserContext（线程安全）
     * <p>
     * 支持延迟重建机制：当检测到自定义配置时,自动重建Context
     */
    public static BrowserContext getContext() {
        return provider.getContext();
    }

    public static void discardCurrentContext() {
        PlaywrightRuntime.instance().contextRegistry.discardCurrentContext();
    }

    public static void setPage(Page page) {
        PlaywrightRuntime.instance().pageRegistry.setPage(page);
    }

    /**
     * 获取 Page（线程安全）
     * <p>
     * 支持延迟重建机制：在获取 Page 时检查是否需要重建 Context
     * 先调用 getContext() 确保重建检查被执行
     */
    public static Page getPage() {
        return provider.getPage();
    }

    // ==================== Context 和 Page 创建方法 ====================

    /**
     * 非阻塞语义的退避等待工具：基于 LockSupport.parkNanos 实现，不调用 Thread.sleep，
     * 也不依赖 ForkJoinPool。仅用于初始化路径中"等待浏览器启动重试"这类本身即阻塞 I/O 的场景。
     *
     * @param millis 等待毫秒数
     */
    // 包级私有：供同包 Browser*Impl 协作访问（WEB-P1-1 Step 2）。
    public static void parkMillis(long millis) {
        if (millis <= 0) {
            return;
        }
        // parkNanos 接受纳秒；直接阻塞当前线程（此处没有 Playwright 事件循环需要保护）
        LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(millis));
    }

    public static void createNewContextAndPage() {
        PlaywrightRuntime.instance().contextRegistry.createNewContextAndPage();
    }

    // ==================== 关闭和清理方法 ====================

    public static void closePage() {
        PlaywrightRuntime.instance().pageRegistry.closePage();
    }

    public static void closeContext() {
        PlaywrightRuntime.instance().contextRegistry.closeContext();
    }

    public static boolean hasContext() {
        return PlaywrightRuntime.instance().contextRegistry.hasContext();
    }

    public static void restartBrowser() {
        PlaywrightRuntime.instance().browserRestart.restartBrowser();
    }

    public static void cleanupAll() {
        PlaywrightRuntime.instance().browserCleanup.cleanupAll();
    }

    // ==================== Serenity BDD 集成方法（委托给 PlaywrightSerenityBridge） ====================

    public static void initializeForScenario() {
        PlaywrightSerenityBridge.initializeForScenario();
    }

    /**
     * 共享 Browser 崩溃后的进程级单飞重建（由 {@code BrowserCrashGuard} 调用）。
     * 委托 {@link BrowserRestart}；仅共享模式有效，非共享模式直接返回 true（详见 {@code BrowserRestart}）。
     *
     * @return true 表示可继续重跑（已重建或无需重建）
     */
    public static boolean rebuildSharedBrowserIfDisconnected() {
        return PlaywrightRuntime.instance().browserRestart.rebuildSharedBrowserIfDisconnected();
    }

    /**
     * 无条件重建共享 Browser（句柄损坏场景的强制恢复动作，由 {@code BrowserCrashGuard#recoverForced()} 调用）。
     * 委托 {@link BrowserRestart}；仅共享模式有效，非共享模式直接返回 true。
     *
     * @return 始终返回 true（表示已执行重建）
     */
    public static boolean rebuildSharedBrowser() {
        return PlaywrightRuntime.instance().browserRestart.rebuildSharedBrowser();
    }

    public static void cleanupForScenario() {
        ScenarioLifecycle.cleanupForScenario();
    }

    /**
     * Feature 级别的清理（委托给 ScenarioLifecycle → PlaywrightSerenityBridge）
     */
    public static void cleanupForFeature() {
        ScenarioLifecycle.cleanupForFeature();
    }

    // ==================== 截图方法（委托给 PlaywrightScreenshotManager） ====================

    public static String takeScreenshot(String title) {
        return PlaywrightScreenshotManager.takeScreenshot(title);
    }

    // ==================== 配置访问（通过 config() 代理到 PlaywrightConfigManager） ====================
    // 使用 PlaywrightManager.config().getXXX() 或 PlaywrightConfigManager.config().getXXX() 访问配置

    // ==================== 公共访问方法 ====================

    /**
     * 获取自定义选项管理器（提供 {@code PlaywrightManager.customOptions().getXXX()} 风格的 API）。
     *
     * <p><b>这是受支持的、稳定的公开门面方法</b>（业务与框架代码均可使用），用于设置/读取自定义浏览器选项
     * （storage state、proxy、locale 等）。它<b>不是封装缺口</b>，而是门面刻意提供的配置访问入口；
     * 同包内部协作者为减少一次门面跳转，可直接调用 {@link CustomOptionsManager#getInstance()}。</p>
     *
     * @apiNote <b>稳定的公开变更 API（per-thread）</b>：返回的 {@link CustomOptions} 抽象（实现为 {@link CustomOptionsManager}）的 setter 写入的是
     *          本线程 {@code TestContextHolder} 中的自定义 Context 选项，并触发延迟 Context 重建（下次 {@code getContext()} 生效），
     *          不会污染其它线程；典型用法为会话恢复（{@code SessionManager} 经此 {@code setStorageState/Path}）与场景级设备仿真
     *          （{@code setLocale/setViewportSize}）。请在导航前设置；业务应经此门面（返回抽象）而非直连具体类 {@link CustomOptionsManager}。
     *          内部生命周期控制 {@code removeAllThreadLocals()} 已为包级私有，不对业务暴露。
     */
    public static CustomOptions customOptions() {
        return CustomOptionsManager.getInstance();
    }

    /**
     * 获取配置管理器（提供 {@code PlaywrightManager.config().getXXX()} 风格的 API）。
     *
     * <p><b>这是受支持的、稳定的公开门面方法</b>（业务与框架代码均可使用）。它<b>不是封装缺口</b>，
     * 而是门面刻意收敛的「唯一配置读取入口」——所有 {@code serenity.playwright.*} 配置都经此暴露，
     * 业务侧应统一走 {@code PlaywrightManager.config().getXxx()}，不要直连 {@link PlaywrightConfigManager}。</p>
     *
     * @apiNote 稳定的公开契约；其返回对象是只读配置视图，不暴露可变状态。
     */
    public static PlaywrightConfigManager config() {
        return PlaywrightConfigManager.config();
    }

    // ==================== 包内访问器（框架内部 seam，非对外契约） ====================

    /**
     * 读取本线程绑定的 Page（per-thread 原始状态访问）。
     *
     * @apiNote <b>框架内部 seam</b>（仅同包协作者使用，如 {@code PlaywrightScreenshotManager}）；
     *          业务代码应使用 {@link #getPage()} 经 provider seam 获取，勿依赖此原始访问器。
     */
    public static Page getPageThreadLocal() {
        return TestContextHolder.get().get(PAGE_KEY);
    }

    /**
     * 读取框架状态引用（{@link FrameworkState}）。
     *
     * @apiNote <b>框架内部 seam</b>（仅同包协作者使用，如 {@code PlaywrightSerenityBridge}）；
     *          不属公开契约，业务不应依赖。
     */
    public static FrameworkState getFrameworkState() {
        return frameworkState;
    }

}