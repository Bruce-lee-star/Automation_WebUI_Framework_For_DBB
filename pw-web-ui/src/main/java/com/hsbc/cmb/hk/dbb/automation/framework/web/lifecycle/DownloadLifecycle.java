package com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle;

import com.microsoft.playwright.BrowserContext;

import java.nio.file.Path;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 下载生命周期登记（CT2-15）：① <b>Context 关闭状态</b>；② <b>按下载目录的在途保存数</b>。
 *
 * <h4>要解决的问题</h4>
 * <p>{@code download.saveAs(...)} 被卸载到 {@code AsyncPool} 异步执行（必须在 Playwright 连接读线程之外，
 * 否则自死锁 —— 见 {@code PlaywrightContextManager#saveDownloadAsync}）。于是存在两条与本类相关的竞态：</p>
 * <ol>
 *   <li><b>与 Context 关闭竞态</b>：{@code onDownload} 派发后、保存执行前，Context 可能已进入
 *       {@code close()} 流程 —— Playwright 会取消未完成下载，{@code saveAs} 随之失败
 *       （文案随版本变化：{@code TargetClosedError}、{@code Cannot find object to call close} …）。
 *       该失败本身无害，但若记 ERROR 会污染收尾期日志、掩盖真实告警。</li>
 *   <li><b>与收尾删目录竞态</b>：场景收尾的 {@code cleanupTempDownloads()} 会<b>直接删除</b>下载目录，
 *       可能删掉尚未保存完的文件，并使 {@code DownloadRegistry} 中登记的路径指向不存在的文件
 *       （表现为「下载记录在、文件没了」；更糟的是在途写入以 0 字节 / 半截文件告终）。</li>
 * </ol>
 *
 * <h4>① Context 关闭状态：让①「不发生」，而不是「事后按文案分类」</h4>
 * <p>{@code PlaywrightContextManager#closeContext} 是框架内 Context 销毁的<b>唯一收口</b>
 * （场景收尾、自定义配置重建、孤儿回收、套件级 {@code cleanupAll} 全部经它），故在其入口登记
 * {@link #markContextClosing(BrowserContext)} 即可全覆盖，无需逐路径打补丁。
 * 此后任何下载事件/保存任务都会先查 {@link #isContextClosing(BrowserContext)}：</p>
 * <ul>
 *   <li><b>预防</b>：不再为已进入关闭流程的 Context 发起 {@code saveAs} —— 该保存注定被取消，
 *       不发起就不会失败，也就没有「需要分类的异常」；</li>
 *   <li><b>判定</b>：对已越过该判定的极小残留窗口（检查通过后 close 才开始的在途保存），
 *       其失败依据<b>框架已知状态</b>而非异常文案来判定为预期噪音 —— 文案随 Playwright 版本变化，
 *       按文案分类必然要不断追补且会漏判（漏判即把预期噪音记成 ERROR，或反向把真实失败降级）。</li>
 * </ul>
 *
 * <h4>② 在途保存计数：按下载目录，供收尾做非阻塞判定</h4>
 * <p>收尾删除目录前查 {@link #pendingCount(Path)}：{@code > 0} 即<b>放弃本轮删除</b>（不等待），
 * 既不影响在途写入，也不把异步保存的耗时转嫁给 scenario 收尾关键路径
 * （{@code cleanupForScenario} 位于每个用例的关键路径上，阻塞等待会逐用例累加）。</p>
 *
 * <p><b>为什么按目录而非线程归属</b>：下载目录由 {@code downloadDirectoryForCurrentThread()} 决定，
 * 而<b>执行收尾的线程未必等于注册 {@code onDownload} 的线程</b>（如 {@code ConcurrentContextExecutor}
 * 把 context 建在 worker 线程上）。按目录键后，「写入者」与「判定者」的归属与线程拓扑解耦。</p>
 *
 * <p><b>线程安全</b>：均为并发容器；计数归零、Context 关闭即移除键，避免长期占用。
 * Context 键采用<b>身份语义</b>（Playwright 的 {@code BrowserContext} 实现不覆写
 * {@code equals/hashCode}）。</p>
 *
 * @apiNote framework-internal：框架内部状态，业务代码不得依赖。
 */
public final class DownloadLifecycle {

    /**
     * 已进入关闭流程的 Context（身份语义）。
     *
     * <p><b>为什么用弱引用键</b>：标记必须「活到再也不可能有人保存为止」。若在
     * {@code closeContext} 返回时主动清除，则「close 已返回、在途 {@code saveAs} 才失败」这段窗口
     * 会查不到标记 → 被误判为真实失败 —— 正是要消除的收尾噪音。弱引用键让条目生命周期自然对齐
     * Context 对象本身：只要仍有在途保存持有该 Context 引用，标记必然可见；对象不可达后条目自动回收，
     * 既不需要（也不应该）手工选择清除时机，且不泄漏。</p>
     */
    private static final Set<BrowserContext> CLOSING_CONTEXTS =
            Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));

    /** 下载目录（归一化绝对路径）→ 在途保存数。 */
    private static final ConcurrentHashMap<Path, AtomicInteger> PENDING_BY_DIR = new ConcurrentHashMap<>();

    private DownloadLifecycle() {
    }

    // ==================== ① Context 关闭状态 ====================

    /**
     * 登记「该 Context 已进入关闭流程」（由 {@code PlaywrightContextManager#closeContext} 在
     * <b>任何清理动作之前</b>调用）。
     *
     * @param context 目标上下文（{@code null} 安全：忽略）
     */
    public static void markContextClosing(BrowserContext context) {
        if (context != null) {
            CLOSING_CONTEXTS.add(context);
        }
    }

    /**
     * 该 Context 是否已进入关闭流程。
     *
     * @param context 目标上下文（{@code null} 返回 {@code false}，按「未关闭」处理）
     * @return true 表示其下载必然被取消，不应再发起保存、其保存失败属预期噪音
     */
    public static boolean isContextClosing(BrowserContext context) {
        return context != null && CLOSING_CONTEXTS.contains(context);
    }

    // ==================== ② 在途保存（按下载目录） ====================

    /**
     * 归一化目录键（绝对路径 + {@code normalize}）：避免 {@code ./x}、{@code x}、{@code x/} 等
     * 写法被当作不同目录，导致「写入者登记了 A、判定者查的是 B」而漏判。
     */
    private static Path dirKey(Path downloadDir) {
        return downloadDir == null ? null : downloadDir.toAbsolutePath().normalize();
    }

    /**
     * 登记一次「即将开始保存」。
     *
     * <p>由保存任务体<b>最开始</b>调用（而非提交前）：{@code AsyncPool} 在池关闭 / 饱和时会
     * <b>丢弃</b>任务且不抛异常，若在提交前登记，被丢弃的任务会让计数<b>永久挂账</b>；
     * 放在任务体内则「无执行 ⇒ 无计数」，且恰好覆盖「文件正在写入」这段真正危险的窗口。</p>
     *
     * @param downloadDir 下载目录（{@code null} 安全：忽略）
     */
    public static void begin(Path downloadDir) {
        Path key = dirKey(downloadDir);
        if (key == null) {
            return;
        }
        PENDING_BY_DIR.computeIfAbsent(key, ignored -> new AtomicInteger()).incrementAndGet();
    }

    /**
     * 注销一次保存（成功 / 失败 / 被超时取消均须调用，保证计数必然配对归零）。
     *
     * @param downloadDir 下载目录（{@code null} 安全；未登记过时幂等无副作用）
     */
    public static void end(Path downloadDir) {
        Path key = dirKey(downloadDir);
        if (key == null) {
            return;
        }
        //  B-05 修复：原实现 get → decrementAndGet → remove 非原子，并发 begin 可在 remove 前插入，
        //    使计数被误删（pendingCount 回落 0 → 场景收尾 cleanupTempDownloads 误删在途目录）。
        //    改用 computeIfPresent：在 CHM 分段锁内原子递减，归零才返回 null 移除，
        //    与 begin 的 computeIfAbsent 同键互斥，彻底消除该竞态窗口。
        PENDING_BY_DIR.computeIfPresent(key, (ignored, counter) -> {
            int remaining = counter.decrementAndGet();
            return remaining <= 0 ? null : counter;
        });
    }

    /**
     * 该目录当前在途保存数（可观测；也是收尾侧「能否安全删除该目录」的唯一判据）。
     *
     * @param downloadDir 下载目录（{@code null} 返回 0）
     * @return 在途保存数；{@code 0} 表示可安全删除
     */
    public static int pendingCount(Path downloadDir) {
        Path key = dirKey(downloadDir);
        if (key == null) {
            return 0;
        }
        AtomicInteger counter = PENDING_BY_DIR.get(key);
        return counter == null ? 0 : Math.max(0, counter.get());
    }

    /** 清空全部登记（JVM 收尾 / 测试重置用）。 */
    public static void clearAll() {
        CLOSING_CONTEXTS.clear();
        PENDING_BY_DIR.clear();
    }
}
