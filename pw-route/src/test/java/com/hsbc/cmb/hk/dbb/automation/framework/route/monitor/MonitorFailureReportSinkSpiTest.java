package com.hsbc.cmb.hk.dbb.automation.framework.route.monitor;

import com.hsbc.cmb.hk.dbb.automation.framework.common.reporting.MonitorFailureReportSink;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SPI 注册守卫：{@code META-INF/services} 里登记的实现类必须真实存在且可加载。
 *
 * <p><b>背景（本次实测发现的既有缺陷）</b>：注册文件曾指向随 ENG-P0-2 迁移而失效的旧包名
 * （{@code …framework.route.monitor.MonitorFailureReportWriterSink}，而实现实际在
 * {@code …framework.route.monitor} 下）。{@code ServiceLoader} 遍历时抛
 * {@code ServiceConfigurationError: Provider … not found}——它是 {@link Error} 而非 {@link Exception}，
 * 不被报告生成器中的 {@code catch (Exception)} 兜住，直接中断 {@code mvn verify} 的汇总报告 exec，
 * 使汇总报告**从未真正产出**（且失败点在 verify 末段，容易被误认为环境问题）。
 *
 * <p>本测试把「注册文件 FQCN 与实现类一致」变成可执行约束：一旦迁移包名而忘记同步 SPI 文件，此处立即失败。
 */
class MonitorFailureReportSinkSpiTest {

    @Test
    void serviceLoaderDiscoversRouteSink() {
        List<MonitorFailureReportSink> sinks = new ArrayList<>();
        // 注意：若注册的 FQCN 不存在，遍历本身即抛 ServiceConfigurationError（测试直接失败，正是所需）
        for (MonitorFailureReportSink sink : ServiceLoader.load(MonitorFailureReportSink.class)) {
            sinks.add(sink);
        }

        assertTrue(
                sinks.stream().anyMatch(s -> MonitorFailureReportWriterSink.class.getName()
                        .equals(s.getClass().getName())),
                "SPI 未发现 route 侧实现，请核对 "
                        + "route/src/main/resources/META-INF/services/"
                        + MonitorFailureReportSink.class.getName()
                        + " 中的 FQCN；实际发现=" + sinks);
    }
}
