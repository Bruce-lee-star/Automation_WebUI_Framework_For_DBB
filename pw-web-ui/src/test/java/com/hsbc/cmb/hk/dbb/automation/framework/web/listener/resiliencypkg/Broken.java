package com.hsbc.cmb.hk.dbb.automation.framework.web.listener.resiliencypkg;

import com.hsbc.cmb.hk.dbb.automation.framework.web.listener.FrameworkListener;

/**
 * 源码化坏夹具（替代原无源码的 {@code Broken.class} 二进制产物）。
 *
 * <p>静态初始化必败，用于表征「单类加载失败仅跳过、不中止整个注册表」（W-16 根治）。
 * 当 {@link com.hsbc.cmb.hk.dbb.automation.framework.web.listener.ListenerRegistry#initialize(String)}
 * 经包扫描对该类执行 {@code Class.forName} 时抛出 {@link ExceptionInInitializerError}，
 * 被扫描循环的 {@code catch (Throwable)} 捕获并跳过（含 WARN 告警）——与 {@link BadLoadListener}
 * （编译正常、实例化阶段抛错）覆盖不同故障点，二者共同固化 W-16 韧性契约。
 */
public class Broken implements FrameworkListener {
    static {
        // 故意：静态初始化必败，用于表征「单类加载失败仅跳过、不中止整个注册表」（W-16 根治）。
        // 不能用裸 throw（编译器报“初始器无法正常完成”）；用恒真条件包一层使编译器认为可能不抛而可编译，
        // 运行期 Boolean.TRUE 必为 true，故 Class.forName 抛 ExceptionInInitializerError 被扫描循环 catch(Throwable) 跳过。
        if (Boolean.TRUE) {
            throw new RuntimeException("deliberate static-init failure for WEB-P1-3 resilience test");
        }
    }
}
