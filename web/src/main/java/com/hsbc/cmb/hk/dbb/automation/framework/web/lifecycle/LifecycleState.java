package com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.Playwright;

import java.util.Collection;
import java.util.Map;
import java.util.Set;

/**
 * 生命周期<b>可变状态根</b>的角色接口（doc16 Phase 2：状态根接口化）。
 *
 * <p>本接口把 {@link PlaywrightRuntimeState} 原先「包级可见、被直接读写的容器字段」收敛为
 * <b>意图明确的受控操作</b>。这样做的收益：
 * <ul>
 *   <li><b>真封装</b>：状态容器的引用不再外泄，不变式（如「谁来标记关闭中」「何时清空废弃 ID」）
 *       可以在实现内强制，而不是靠调用方自律。</li>
 *   <li><b>多态</b>：状态根成为组合根 {@link PlaywrightRuntime} 的一个角色，可随
 *       {@link PlaywrightRuntime#setInstance} 与其余 6 个协作者一起被替换
 *       （满足「每接口 ≥2 实现：生产实现 + 测试替身」）。</li>
 *   <li><b>跨子包可达</b>：子包协作者不再依赖「包级私有字段」这种在同包拆分下失效的可见性，
 *       而是依赖稳定的接口契约。</li>
 * </ul>
 *
 * <p><b>并发语义</b>：所有实现必须是线程安全的，且与直接操作并发容器等价——
 * 尤其「遍历中移除」仍须安全（实现基于 {@code ConcurrentHashMap}，迭代期间删除不会
 * 抛 {@code ConcurrentModificationException}）。</p>
 *
 * <p><b>遍历视图语义</b>：{@link #playwrightEntries()} / {@link #browserEntries()} /
 * {@link #allPlaywrights()} / {@link #allBrowsers()} / {@link #playwrightKeys()} / {@link #browserKeys()}
 * 均返回<b>实时只读视图</b>——反映后续变更，但不可经其修改状态（修改必须走本接口的写方法）。
 * 因此「遍历 + 条件 + 经接口删除」的既有清理模式语义不变。</p>
 *
 * @apiNote <b>框架内部能力</b>：仅供 {@code framework.web.lifecycle} 包树的生命周期协作者使用；
 *          业务代码不得访问（由 {@code ArchitectureTest} 的 L7 规则构建期守护）。
 */
public interface LifecycleState {

    // ==================== Playwright 实例表 ====================
    // key = threadId:configId（共享 Browser 模式为 shared:configId）

    /** 取指定 key 的 Playwright 实例，不存在则返回 {@code null}。 */
    Playwright getPlaywright(String key);

    /** 登记（或覆盖）指定 key 的 Playwright 实例。 */
    void putPlaywright(String key, Playwright playwright);

    /**
     * 移除指定 key 的 Playwright 实例。
     *
     * @return 被移除的实例；该 key 不存在时返回 {@code null}（与 {@code ConcurrentMap#remove} 语义一致）
     */
    Playwright removePlaywright(String key);

    /** 判断指定 key 是否已有 Playwright 实例。 */
    boolean hasPlaywright(String key);

    /** 全部 Playwright 实例的只读实时视图（用于统一关闭等清理路径）。 */
    Collection<Playwright> allPlaywrights();

    /** 全部 {@code key → Playwright} 条目的只读实时视图（用于「按 key 前缀过滤」的清理路径）。 */
    Set<Map.Entry<String, Playwright>> playwrightEntries();

    /** 全部 Playwright 实例的 key 只读实时视图。 */
    Set<String> playwrightKeys();

    /** 清空 Playwright 实例表（不关闭实例，关闭动作由调用方先行执行）。 */
    void clearPlaywrights();

    // ==================== Browser 实例表 ====================

    /** 取指定 key 的 Browser 实例，不存在则返回 {@code null}。 */
    Browser getBrowser(String key);

    /** 登记（或覆盖）指定 key 的 Browser 实例。 */
    void putBrowser(String key, Browser browser);

    /**
     * 移除指定 key 的 Browser 实例。
     *
     * @return 被移除的实例；该 key 不存在时返回 {@code null}（与 {@code ConcurrentMap#remove} 语义一致）
     */
    Browser removeBrowser(String key);

    /**
     * 判断给定 Browser <b>实例</b>是否仍登记在表中（按 value 匹配，非 key）。
     * 用于区分「框架已注销的陈旧实例」与「仍在册的活跃实例」。
     */
    boolean containsBrowserInstance(Browser browser);

    /** 全部 Browser 实例的只读实时视图（用于统一关闭等清理路径）。 */
    Collection<Browser> allBrowsers();

    /** 全部 {@code key → Browser} 条目的只读实时视图（用于「按 key 前缀过滤」的清理路径）。 */
    Set<Map.Entry<String, Browser>> browserEntries();

    /** 全部 Browser 实例的 key 只读实时视图。 */
    Set<String> browserKeys();

    /** 清空 Browser 实例表（不关闭实例，关闭动作由调用方先行执行）。 */
    void clearBrowsers();

    // ==================== 断开 / 关闭中标记 ====================

    /** 标记 Browser 已断开（{@code onDisconnected} 事件），使其后的取用快速失败。 */
    void markDisconnected(Browser browser);

    /** 清除断开标记（重建成功后调用）。 */
    void clearDisconnected(Browser browser);

    /** 判断 Browser 是否已断开。 */
    boolean isDisconnected(Browser browser);

    /**
     * 登记「框架正在主动关闭」的 Browser。
     * 用于把「主动关闭引发的 onDisconnected」降级为预期事件，避免收尾期误记 ERROR。
     */
    void markClosing(Browser browser);

    /** 判断 Browser 是否处于框架主动关闭流程中。 */
    boolean isClosing(Browser browser);

    // ==================== 废弃 configId ====================

    /** 标记 configId 已废弃（浏览器类型切换时），促使其它线程重建而非复用旧 Browser。 */
    void markRetired(String configId);

    /** 清除指定 configId 的废弃标记。 */
    void clearRetired(String configId);

    /** 判断 configId 是否已废弃。 */
    boolean isRetired(String configId);

    /** 清空全部废弃 configId 标记（全量重建后调用）。 */
    void clearRetiredAll();

    // ==================== 初始化幂等判据 ====================

    /** 是否已完成全量初始化。 */
    boolean isFullInit();

    /** 标记全量初始化完成（幂等判据）。 */
    void markFullInit();
}
