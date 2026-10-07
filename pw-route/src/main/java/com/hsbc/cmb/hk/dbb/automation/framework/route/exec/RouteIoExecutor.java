package com.hsbc.cmb.hk.dbb.automation.framework.route.exec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.hsbc.cmb.hk.dbb.automation.framework.route.RouteConfig;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * IO 执行器 —— 事件线程与阻塞操作的唯一隔离带。
 *
 * <p>职责：承载一切不允许出现在 Playwright 事件线程上的操作（fetch、sleep、观测消费等）。
 * <ul>
 *   <li><b>自适应线程数</b>：入参 {@code coreThreads} 是<b>基线</b>并发；队列一旦出现积压即抬升
 *       （上限见 {@link #MAX_THREADS}），连续空闲任务后回落到基线 —— 无需任何配置项，见
 *       {@link #adaptBeforeSubmit()} 的说明；</li>
 *   <li>有界队列：队满拒绝而非无界堆积（拒绝即 fail-open）；</li>
 *   <li>线程命名带 context 标识（可观测）；</li>
 *   <li>内部周期巡检：强制落定超龄挂起 claim（防悬挂）；</li>
 *   <li>{@link #close()} 优雅停机：拒绝新任务 + 有界等待在途任务。</li>
 * </ul>
 *
 * <p>并发安全：{@link ThreadPoolExecutor} 自带队列/工作线程同步；计数器均为原子类型。
 */
public final class RouteIoExecutor implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(RouteIoExecutor.class);

    /**
     * 自适应并发的<b>上限</b>（内部常量，刻意不做成配置项）。
     *
     * <p>取值理由：本池绝大多数任务是**阻塞型 IO**（fetch 真实网络、读响应体、JSON 解析），
     * 其并发上限应与同类阻塞操作的台账一致 —— {@code RouteConfig.DEFAULT_OPS_MAX_CONCURRENT}
     * （同步 fetch 的信号量上限）。刻意不暴露配置：多一个旋钮就多一种"现场配错"的方式，
     * 而基线（{@code ioThreads}）已经能表达"小机器/大机器"的差异。</p>
     */
    private static final int MAX_THREADS = RouteConfig.DEFAULT_OPS_MAX_CONCURRENT;

    /**
     * 连续多少个"完成时队列为空"的任务后回落到基线。
     *
     * <p>用任务数而非时间做回落下限：时间阈值在测试里不可控（只能靠 sleep ✗），
     * 而任务计数既确定又无时钟依赖；64 次足以跨越一次登录期的短促突发，
     * 不会在突发抖动中反复涨落。</p>
     */
    private static final int SHRINK_AFTER_IDLE_TASKS = 64;

    private final ThreadPoolExecutor pool;
    private final AtomicLong rejectedCount = new AtomicLong(0);
    private final AtomicInteger taskSeq = new AtomicInteger(0);
    /** 基线并发（构造入参；自适应只会高于它，绝不会低于它）。 */
    private final int baselineThreads;
    /** 自适应扩容次数（可观测）。 */
    private final AtomicLong grownCount = new AtomicLong(0);
    /** 自适应回落次数（可观测）。 */
    private final AtomicLong shrunkCount = new AtomicLong(0);
    /** 自适应达到过的最大 core 线程数（可观测）。 */
    private final AtomicInteger maxCoreObserved = new AtomicInteger(0);
    /** 连续"完成时无积压"的任务计数（用于回落判定）。 */
    private final AtomicInteger idleStreak = new AtomicInteger(0);

    public RouteIoExecutor(String contextTag, int coreThreads, int queueCapacity) {
        ThreadFactory factory = new ThreadFactory() {
            private final AtomicInteger seq = new AtomicInteger(0);

            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "route-v2-io-" + contextTag + "-" + seq.incrementAndGet());
                t.setDaemon(true);
                return t;
            }
        };
        this.baselineThreads = Math.max(1, coreThreads);
        // maximum 必须 ≥ core，否则 setCorePoolSize 扩容会抛 IllegalArgumentException；
        // 同时保证"配置的基线高于内部上限"时以基线为准（不退化成比用户要求更低）。
        int ceiling = Math.max(baselineThreads, MAX_THREADS);
        this.pool = new ThreadPoolExecutor(baselineThreads, ceiling,
                0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(queueCapacity),
                factory, new ThreadPoolExecutor.AbortPolicy());
        this.maxCoreObserved.set(baselineThreads);
    }

    /**
     * 提交 IO 任务。
     *
     * @return true=已提交；false=队列满/已关闭（调用方必须 fail-open）
     */
    public boolean trySubmit(String taskName, Runnable task) {
        if (pool.isShutdown()) {
            rejectedCount.incrementAndGet();
            return false;
        }
        try {
            adaptBeforeSubmit();
            pool.execute(() -> {
                try {
                    task.run();
                } catch (Throwable t) {
                    LOGGER.warn("[Route] IO task '{}' failed: {}", taskName, t.toString());
                } finally {
                    onTaskFinished();
                }
            });
            return true;
        } catch (RejectedExecutionException e) {
            rejectedCount.incrementAndGet();
            LOGGER.warn("[Route] IO queue full, rejecting task '{}' (caller must fail-open)", taskName);
            return false;
        }
    }

    /**
     * 提交前的自适应扩容：队列有积压就抬 core（每次 +1，封顶），无积压时不做任何事。
     *
     * <p><b>为什么必须主动抬 core，而不是靠 {@code maximumPoolSize}</b>：本池的队列是<b>有界</b>的
     * （队满即拒绝，而不是无界堆积），而 {@link ThreadPoolExecutor} 只在<b>队列已满</b>时才把线程数
     * 扩到 core 以上 —— 也就是说"等队满再扩容"那一刻，任务已经被 {@code AbortPolicy} 拒绝了。
     * 因此这里在提交侧观察队列积压，主动 {@code setCorePoolSize} 提前扩容。</p>
     *
     * <p>扩容是"每次提交最多 +1"，因此突发事件下只会稳步抬升（由上限兜底），不会一次开出一堆线程；
     * 回落见 {@link #onTaskFinished()}。</p>
     */
    private void adaptBeforeSubmit() {
        if (!pool.getQueue().isEmpty() && pool.getCorePoolSize() < pool.getMaximumPoolSize()) {
            int now = pool.getCorePoolSize() + 1;
            pool.setCorePoolSize(now);
            grownCount.incrementAndGet();
            if (now > maxCoreObserved.get()) {
                maxCoreObserved.set(now);
            }
            LOGGER.debug("[Route] IO pool had backlog → core threads raised to {}", now);
        }
    }

    /**
     * 任务完成后的自适应回落：连续多次"完成时队列为空"即回到基线并发。
     *
     * <p>为什么要回落：突发（如登录期十来个请求同时需要读体/替换）过后若把抬升后的并发长期留着，
     * 就等于把上限当默认值用 —— 基线配置失去意义，且并发度长期超标。用任务计数而非时间做滞回，
     * 既确定（可在单测里稳定复现）又不依赖时钟。</p>
     */
    private void onTaskFinished() {
        if (!pool.getQueue().isEmpty()) {
            idleStreak.set(0); // 仍有积压：不回落
            return;
        }
        if (pool.getCorePoolSize() <= baselineThreads) {
            return; // 已在基线
        }
        if (idleStreak.incrementAndGet() >= SHRINK_AFTER_IDLE_TASKS) {
            pool.setCorePoolSize(baselineThreads);
            idleStreak.set(0);
            shrunkCount.incrementAndGet();
            LOGGER.debug("[Route] IO pool idle for {} tasks → core threads back to baseline {}",
                    SHRINK_AFTER_IDLE_TASKS, baselineThreads);
        }
    }

    /** 被拒绝的任务数。 */
    public long rejectedCount() {
        return rejectedCount.get();
    }

    /** 当前排队/执行任务数。 */
    public int activeCount() {
        return pool.getActiveCount() + pool.getQueue().size();
    }

    /** 当前实际线程数（含空闲线程）。 */
    public int poolSize() {
        return pool.getPoolSize();
    }

    /** 当前 core 线程数 = 当前目标并发上限（自适应后的值）。 */
    public int coreThreads() {
        return pool.getCorePoolSize();
    }

    /** 基线线程数（构造入参；回落的目标）。 */
    public int baselineThreads() {
        return baselineThreads;
    }

    /** 自适应扩容累计次数（&gt; 0 说明曾出现队列积压）。 */
    public long grownCount() {
        return grownCount.get();
    }

    /** 自适应回落累计次数。 */
    public long shrunkCount() {
        return shrunkCount.get();
    }

    /** 自适应达到过的最大 core 线程数（容量评估用）。 */
    public int maxCoreObserved() {
        return maxCoreObserved.get();
    }

    /**
     * 立即停机（不等待在途任务）：**仅用于 context 已销毁的收尾路径**，避免在 Playwright 消息泵线程上
     * 长时间阻塞（context 销毁时驱动侧 handler 由 Playwright 一并释放，无需等待本池收尾）。
     */
    public void closeNoWait() {
        pool.shutdown();
    }

    /** 优雅停机：拒绝新任务，有界等待在途任务完成。 */
    @Override
    public void close() {
        pool.shutdown();
        try {
            if (!pool.awaitTermination(10, TimeUnit.SECONDS)) {
                pool.shutdownNow();
                LOGGER.warn("[Route] IO executor did not terminate in 10s, forcing shutdown");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            pool.shutdownNow();
        }
    }

    /** 供调度任务使用的序号（指标可观测性）。 */
    int nextTaskSeq() {
        return taskSeq.incrementAndGet();
    }
}
