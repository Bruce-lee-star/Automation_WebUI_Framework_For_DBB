package framework.route.core.engine;

import framework.webbridge.RouteRuntimeBridge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ServiceLoader;
import java.util.concurrent.atomic.AtomicReference;

/**
 * {@link RouteRuntimeBridge} 的持有者：SPI 惰性解析 + 测试可注入（{@link #set}）。
 *
 * <p>双通道获取顺序：① 测试注入（{@link #set}，便于单测/stub）；② SPI 自动发现
 * （首个 {@code META-INF/services/framework.webbridge.RouteRuntimeBridge} 实现）。
 * 两者皆空时返回 {@code null}，调用方回退到 {@code route.fetch()}。</p>
 */
public final class RouteRuntimeBridgeHolder {

    private static final Logger LOGGER = LoggerFactory.getLogger(RouteRuntimeBridgeHolder.class);
    private static final AtomicReference<RouteRuntimeBridge> INJECTED = new AtomicReference<>();
    private static volatile RouteRuntimeBridge spiInstance;
    private static volatile boolean spiSearched;

    private RouteRuntimeBridgeHolder() {
    }

    /** 测试注入点；传 {@code null} 复位为 SPI 解析。 */
    public static void set(RouteRuntimeBridge bridge) {
        INJECTED.set(bridge);
    }

    /** 返回当前可用的桥接实现，无则返回 {@code null}（调用方回退到 route.fetch()）。 */
    public static RouteRuntimeBridge get() {
        RouteRuntimeBridge injected = INJECTED.get();
        if (injected != null) {
            return injected;
        }
        if (!spiSearched) {
            synchronized (RouteRuntimeBridgeHolder.class) {
                if (!spiSearched) {
                    spiInstance = loadViaSpi();
                    spiSearched = true;
                }
            }
        }
        return spiInstance;
    }

    private static RouteRuntimeBridge loadViaSpi() {
        try {
            for (RouteRuntimeBridge b : ServiceLoader.load(RouteRuntimeBridge.class)) {
                return b; // 取首个实现
            }
        } catch (Throwable t) {
            LOGGER.debug("[RouteRuntimeBridge] SPI 解析无实现（web/测试宿主未提供时属正常）: {}", t.toString());
        }
        return null;
    }
}
