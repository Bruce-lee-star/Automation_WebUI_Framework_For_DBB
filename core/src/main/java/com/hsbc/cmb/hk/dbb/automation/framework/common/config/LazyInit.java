package com.hsbc.cmb.hk.dbb.automation.framework.common.config;

/**
 * 惰性初始化守卫（评审 P2-2）：把「配置失败快」从 static 初始化块移到<b>首次使用</b>。
 *
 * <p><b>要解决的问题</b>：组件（如 {@code AsyncPool}）原在 {@code static {}} 块里读配置
 * （经 {@link ConfigSource} → {@code SecretValue} 解密失败即抛）。JVM 会把 static 块抛出的异常包装为
 * {@link ExceptionInInitializerError}，且该类在同一 JVM 内<b>永久不可用</b>（后续任何访问直接
 * {@code NoClassDefFoundError}）—— 错误形态难懂，且失去了失败后重试/降级的任何可能。</p>
 *
 * <p><b>本类语义</b>：</p>
 * <ul>
 *   <li>{@link #ensure()} 首次调用执行初始化；<b>成功则永不重复</b>（双重检查锁）；</li>
 *   <li>失败则缓存原因，后续调用抛出<b>清晰的 {@link IllegalStateException}</b>（带原始 cause），
 *       而不是 {@code ExceptionInInitializerError}；<b>不重试</b>（避免失败路径被高频调用放大噪声，
 *       失败原因通常是配置本身，不会自愈）；</li>
 *   <li>{@link Error}（如 OOM）原样抛出、不做包装；</li>
 *   <li>{@link #isInitialized()} 供「无可关闭资源」类方法短路（初始化失败时不留下半初始化状态，
 *       由使用方保证「先做可能失败的读取、再构造并发布资源」）。</li>
 * </ul>
 *
 * <p><b>线程安全</b>：初始化在实例锁内串行；成功发布借助 {@code volatile initialized} 的
 * happens-before 语义，使初始化期间写入的<b>普通字段</b>对其它线程可见（无需逐字段 volatile）。</p>
 */
public final class LazyInit {

    /** 初始化动作。 */
    @FunctionalInterface
    public interface Initializer {
        /** 执行初始化；抛出任意异常/错误即视为失败。 */
        void run() throws Throwable;
    }

    private final String component;
    private final Initializer initializer;
    private final Object lock = new Object();

    private volatile boolean initialized;
    private volatile Throwable failure;

    /**
     * @param component   组件名（用于错误消息定位，如 {@code "AsyncPool"}）
     * @param initializer 初始化动作
     */
    public LazyInit(String component, Initializer initializer) {
        this.component = component;
        this.initializer = initializer;
    }

    /**
     * 确保已初始化（幂等）。
     *
     * @throws IllegalStateException 初始化失败（含此前已失败）—— 消息带组件名，cause 为原始异常
     * @throws Error                 初始化抛出 {@link Error} 时原样抛出（不包装）
     */
    public void ensure() {
        if (initialized) {
            return;
        }
        synchronized (lock) {
            if (initialized) {
                return;
            }
            if (failure != null) {
                throw new IllegalStateException(component + " initialization previously failed (not retried): "
                        + failure.getMessage(), failure);
            }
            try {
                initializer.run();
                initialized = true;
            } catch (Error e) {
                // Error（如 OOM / 栈溢出）：原样抛出，不做包装（包装会掩盖致命错误的本性）
                failure = e;
                throw e;
            } catch (Throwable t) {
                failure = t;
                throw new IllegalStateException(component + " initialization failed: " + t.getMessage(), t);
            }
        }
    }

    /** 是否已<b>成功</b>完成初始化（初始化失败与从未初始化都返回 {@code false}）。 */
    public boolean isInitialized() {
        return initialized;
    }
}
