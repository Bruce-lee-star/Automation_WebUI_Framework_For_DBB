package com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.concurrent;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.concurrent.ContextTaskResult;

import java.util.concurrent.Callable;

/**
 * 立线程并发上下文任务：在独 / 独立 BrowserContext 中执行的一段工作流。
 *
 * <p>业务实现 {@link #call()} 内部照常使用 {@code BasePage} / {@code PlaywrightManager}，框架保证
 * 每个任务在独立线程上获得独立 {@code BrowserContext}（共享 Browser 模式下由 per-thread Context 隔离）。
 * 任务抛出的异常被捕获进 {@link ContextTaskResult}，不会污染线程池。</p>
 *
 * <p>⚠️ 不要在任务内直接调用 {@code StepEventBus.getEventBus().testFailed(...)} 一类 Serenity 报告 API：
 * 工作线程拿到的是无监听器的全新事件总线（设计文档第九节 G1）。失败应通过返回值 / 异常经
 * {@link ContextTaskResult#valueOrThrow()} 在<b>编排线程</b>回放，由既有 Serenity 通道统一标记。</p>
 *
 * @apiNote <b>框架内部能力</b>：仅供 {@code framework.web.lifecycle} 包树协作者与并发桥接使用；业务代码不得直接依赖。
 */
@FunctionalInterface
public interface ContextTask<T> {

    /** 任务唯一名（日志 / 结果追踪）。默认取类名；lambda 建议用 {@link #of(String, Callable)} 显式命名。 */
    default String name() {
        return getClass().getSimpleName();
    }

    /** 任务逻辑。返回结果或抛异常（被收口进 {@link ContextTaskResult}）。 */
    T call() throws Exception;

    /** 以 lambda 形式创建命名任务。 */
    static <T> ContextTask<T> of(String name, Callable<T> callable) {
        return new ContextTask<T>() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public T call() throws Exception {
                return callable.call();
            }
        };
    }
}
