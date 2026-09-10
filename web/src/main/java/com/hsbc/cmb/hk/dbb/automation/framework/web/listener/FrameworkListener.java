package com.hsbc.cmb.hk.dbb.automation.framework.web.listener;

/**
 * 框架监听器 SPI 发现契约（对齐 codegen 模块 {@code RoleCodegenBridge} 的 {@code ServiceLoader} 做法）。
 *
 * <p>{@link ListenerRegistry#initialize(String)} 经 JDK {@link java.util.ServiceLoader} 惰性发现本接口的实现类，
 * 作为监听器发现的<b>主路径</b>；原 {@code Class.forName} 全量包扫描降级为<b>容错回退</b>。任一实现类
 * 加载 / 实例化失败仅跳过该类并记 WARN，<b>不影响其它监听器</b>（根治 W-16「单点失败全盘失败」）。</p>
 *
 * <p>监听器若需接入 Serenity 事件总线，仍自行经 {@code StepEventBus.registerListener} 注册
 * （Serenity 自带 {@code StepListener} SPI 负责接线）；本接口仅作为「可被框架发现」的 SPI 契约，
 * 与 Serenity 的注册机制互不耦合。</p>
 *
 * @apiNote 纯标记接口（同 {@code java.util.EventListener}）。仅 web 核心层与 SPI 声明方相关；
 *          实现类须提供 <b>public 无参构造器</b> 以满足 {@code ServiceLoader} 契约。
 */
public interface FrameworkListener {
}
