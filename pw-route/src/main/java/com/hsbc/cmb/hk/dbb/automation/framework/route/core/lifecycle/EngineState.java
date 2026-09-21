package com.hsbc.cmb.hk.dbb.automation.framework.route.core.lifecycle;

/**
 * Context 引擎生命周期状态（原内联于 {@code RouteEngine} 的 private 枚举，T2-4 拆分提取）。
 *
 * @apiNote framework-internal：框架内部类型，非公开 API。跨子包 public 可见性仅为分层迁移需要，外部不得依赖。
 */
public enum EngineState { RUNNING, CLOSING, CLOSED }
