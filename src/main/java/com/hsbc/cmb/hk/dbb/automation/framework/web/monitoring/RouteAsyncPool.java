package com.hsbc.cmb.hk.dbb.automation.framework.web.monitoring;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Playwright 路由异步任务池
 * 守护线程+弹性线程+队列限流，无阻塞、无内存泄漏
 *
 * <p>使用示例：
 * <pre>{@code
 * RouteAsyncPool.runAsync(() -> {
 *     // 执行异步任务，如日志记录、JSON 解析等
 *     System.out.println("Async task executed");
 * });
 *
 * // 项目结束时关闭
 * RouteAsyncPool.close();
 * }</pre>
 */
public class RouteAsyncPool {

    private static final ExecutorService POOL;

    static {
        POOL = new ThreadPoolExecutor(
                2,      // corePoolSize
                6,      // maxPoolSize
                30L,    // keepAliveTime
                TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(200),  // 队列容量限流
                runnable -> {
                    Thread thread = new Thread(runnable);
                    thread.setName("pw-route-async");
                    thread.setDaemon(true);
                    thread.setPriority(Thread.NORM_PRIORITY - 1);
                    return thread;
                },
                new ThreadPoolExecutor.DiscardPolicy()  // 满队列时静默丢弃
        );
    }

    /**
     * 异步执行任务
     * @param task 要执行的任务
     */
    public static void runAsync(Runnable task) {
        if (task == null) return;
        try {
            POOL.execute(task);
        } catch (Exception e) {
            // 静默丢弃，不影响主流程
        }
    }

    /**
     * 关闭线程池
     * 项目结束统一关闭
     */
    public static void close() {
        if (!POOL.isShutdown()) {
            POOL.shutdownNow();
        }
    }
}
