package com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.state;

import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.LifecycleState;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.Playwright;
import java.util.WeakHashMap;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Playwright 运行时可变状态的唯一受管容器（WEB-P1-1 Step 1），
 * 同时是 {@link LifecycleState} 角色接口的<b>生产实现</b>（doc16 Phase 2）。
 *
 * <p>原散落在 {@code PlaywrightManager} 的 6 处可变静态字段（Playwright/Browser 实例表、断开标记、
 * 关闭中标记、废弃 configId 集合、初始化幂等判据）全部收敛于此，使管理器自身不再持有可变静态字段，
 * 并为 Step 2~4 的行为分域（{@code BrowserRegistry} / {@code ContextRegistry} / {@code BrowserStartup}/{@code BrowserRestart}/{@code BrowserCleanup}）
 * 提供统一状态根。
 *
 * <p><b>并发语义（迁移前后完全等价）</b>：所有字段均为 {@code final} 引用 + 线程安全容器
 * （{@code ConcurrentHashMap} / {@code newKeySet()} / 弱引用同步 Set / {@code AtomicBoolean}）；
 * 实例表的 VALUE（Browser/Playwright）默认不跨线程共享（key 含 threadId），共享 Browser 模式
 * 下由 per-thread 的 {@code BrowserContext} 保证隔离——该不变式迁移前后不变。
 *
 * <p><b>Phase 2 变更</b>：容器字段由「包级可见」收为 {@code private}，对外只暴露
 * {@link LifecycleState} 定义的受控操作与<b>只读视图</b>，从而在不改变并发语义的前提下实现真封装
 * 与可替换（随 {@link PlaywrightRuntime#setInstance} 换实现）。
 *
 * @apiNote 框架内部状态容器：经 {@link LifecycleState} 接口供 {@code framework.web.lifecycle}
 *          包树协作者使用；业务代码不得访问。
 */
public final class PlaywrightRuntimeState implements LifecycleState {

    /** 进程内唯一状态根（饿汉单例，不可变引用）。 */
    public static final PlaywrightRuntimeState INSTANCE = new PlaywrightRuntimeState();

    /**
     * Playwright 实例表：key = {@code threadId:configId}（共享 Browser 模式为 {@code shared:configId}）。
     */
    private final ConcurrentMap<String, Playwright> playwrightInstances = new ConcurrentHashMap<>();

    /**
     * Browser 实例表：key 同上。
     *
     * <p>默认不变式：VALUE 绝不跨线程共享（key 含 threadId，每个 worker 线程独立实例）；
     * 共享 ConcurrentHashMap 仅作为跨线程安全的回收/清理容器（供 cleanupAll 统一关闭）。
     * 例外——共享 Browser 模式：key 退化为 {@code shared:configId}，所有线程有意复用同一 Browser，
     * 隔离性改由 per-thread 的 BrowserContext 保证。
     */
    private final ConcurrentMap<String, Browser> browserInstances = new ConcurrentHashMap<>();

    /**
     * 浏览器断开标记（{@code onDisconnected} 事件填充）：用于 {@code getPage()}/{@code getContext()}
     * 快速失败，避免浏览器进程崩溃/被杀后继续操作抛出晦涩的 Playwright 底层异常。
     */
    private final Set<Browser> disconnectedBrowsers = ConcurrentHashMap.newKeySet();

    /**
     * 框架主动关闭中的 Browser：各 close 路径经 {@code closeBrowserInstance()} 先登记再关闭，
     * 用于区分「主动关闭 → 预期断开」与「崩溃/被杀 → 意外断开」，避免收尾期把正常关闭记成 ERROR。
     *
     * <p>弱引用 Set：Browser 被 GC 后条目自动失效，不延长其生命周期、无引用泄漏；
     * 不主动移除条目，以覆盖 {@code onDisconnected} 异步回调晚于 {@code close()} 返回的情况。
     */
    private final Set<Browser> closingBrowsers =
            Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));

    /**
     * 已废弃的 configId 集合：浏览器类型切换时设置，其它线程进入 {@code getContext()}/{@code getPage()}
     * 检测到自己的 configId 已被废弃后强制重建，避免绑定到即将关闭的旧 Browser（竞态窗口修复 1.2）。
     * 使用并发 Set 支持多个并发废弃 ID，避免覆盖丢失（P2-18）。
     */
    private final Set<String> retiredConfigIds = ConcurrentHashMap.newKeySet();

    /** 初始化幂等判据（{@code static synchronized} 已提供类级互斥，此处补可见性判据，修复 H6/H8）。 */
    private final AtomicBoolean fullInit = new AtomicBoolean(false);

    private PlaywrightRuntimeState() {
    }

    // ==================== LifecycleState：Playwright 实例表 ====================

    @Override
    public Playwright getPlaywright(String key) {
        return playwrightInstances.get(key);
    }

    @Override
    public void putPlaywright(String key, Playwright playwright) {
        playwrightInstances.put(key, playwright);
    }

    @Override
    public Playwright removePlaywright(String key) {
        return playwrightInstances.remove(key);
    }

    @Override
    public boolean hasPlaywright(String key) {
        return playwrightInstances.containsKey(key);
    }

    @Override
    public Collection<Playwright> allPlaywrights() {
        return Collections.unmodifiableCollection(playwrightInstances.values());
    }

    @Override
    public Set<Map.Entry<String, Playwright>> playwrightEntries() {
        return Collections.unmodifiableMap(playwrightInstances).entrySet();
    }

    @Override
    public Set<String> playwrightKeys() {
        return Collections.unmodifiableSet(playwrightInstances.keySet());
    }

    @Override
    public void clearPlaywrights() {
        playwrightInstances.clear();
    }

    // ==================== LifecycleState：Browser 实例表 ====================

    @Override
    public Browser getBrowser(String key) {
        return browserInstances.get(key);
    }

    @Override
    public void putBrowser(String key, Browser browser) {
        browserInstances.put(key, browser);
    }

    @Override
    public Browser removeBrowser(String key) {
        return browserInstances.remove(key);
    }

    @Override
    public boolean containsBrowserInstance(Browser browser) {
        return browserInstances.containsValue(browser);
    }

    @Override
    public Collection<Browser> allBrowsers() {
        return Collections.unmodifiableCollection(browserInstances.values());
    }

    @Override
    public Set<Map.Entry<String, Browser>> browserEntries() {
        return Collections.unmodifiableMap(browserInstances).entrySet();
    }

    @Override
    public Set<String> browserKeys() {
        return Collections.unmodifiableSet(browserInstances.keySet());
    }

    @Override
    public void clearBrowsers() {
        browserInstances.clear();
    }

    // ==================== LifecycleState：断开 / 关闭中标记 ====================

    @Override
    public void markDisconnected(Browser browser) {
        disconnectedBrowsers.add(browser);
    }

    @Override
    public void clearDisconnected(Browser browser) {
        disconnectedBrowsers.remove(browser);
    }

    @Override
    public boolean isDisconnected(Browser browser) {
        return disconnectedBrowsers.contains(browser);
    }

    @Override
    public void markClosing(Browser browser) {
        closingBrowsers.add(browser);
    }

    @Override
    public boolean isClosing(Browser browser) {
        return closingBrowsers.contains(browser);
    }

    // ==================== LifecycleState：废弃 configId ====================

    @Override
    public void markRetired(String configId) {
        retiredConfigIds.add(configId);
    }

    @Override
    public void clearRetired(String configId) {
        retiredConfigIds.remove(configId);
    }

    @Override
    public boolean isRetired(String configId) {
        return retiredConfigIds.contains(configId);
    }

    @Override
    public void clearRetiredAll() {
        retiredConfigIds.clear();
    }

    // ==================== LifecycleState：初始化幂等判据 ====================

    @Override
    public boolean isFullInit() {
        return fullInit.get();
    }

    @Override
    public void markFullInit() {
        fullInit.set(true);
    }
}
