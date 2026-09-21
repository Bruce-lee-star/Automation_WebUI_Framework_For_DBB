package com.hsbc.cmb.hk.dbb.automation.framework.route.monitor;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 框架内部：API 监控数据持久化丢失汇总器（R-4 修复）。
 *
 * <p>写库失败 / 队列背压丢弃等「数据丢失」事件由 {@code ApiMonitoringRepository} 经
 * {@link #recordLoss(String, long)} 累计；在报告生成阶段由 {@link MonitorFailureReportWriter}
 * 以<b>红色提示</b>呈现于汇总报告尾部，使「监控数据丢失」对测试运行可见
 * （比保证不丢失更现实，详见架构评审 06 §5.3）。
 *
 * <p><b>线程安全</b>：{@link ConcurrentHashMap} + {@link AtomicLong}，支持并发测试场景。
 * <p><b>生命周期</b>：进程级单例；报告写出后由 {@code MonitorFailureReportWriterSink.clear()} 复位，
 * 避免跨套件（同 JVM 多 runner）累积。
 */
public final class MonitorDataLossReporter {

    private static final MonitorDataLossReporter INSTANCE = new MonitorDataLossReporter();

    private final Map<String, AtomicLong> lossByCategory = new ConcurrentHashMap<>();
    private final AtomicLong totalLoss = new AtomicLong(0);

    private MonitorDataLossReporter() {}

    public static MonitorDataLossReporter instance() {
        return INSTANCE;
    }

    /**
     * 累计某类别的数据丢失条数。
     *
     * @param category 丢失类别（如 {@code "route_monitor_record"}）
     * @param count    丢失条数（{@code <=0} 视为无操作，幂等安全）
     */
    public void recordLoss(String category, long count) {
        if (count <= 0) {
            return;
        }
        totalLoss.addAndGet(count);
        lossByCategory.computeIfAbsent(category, k -> new AtomicLong(0)).addAndGet(count);
    }

    /** 是否发生过数据丢失（供报告红色提示判定）。 */
    public boolean hasLoss() {
        return totalLoss.get() > 0;
    }

    /** 累计丢失总条数。 */
    public long totalLoss() {
        return totalLoss.get();
    }

    /** 按类别的丢失条数快照（供报告展示）。 */
    public Map<String, Long> lossByCategory() {
        Map<String, Long> snapshot = new LinkedHashMap<>();
        lossByCategory.forEach((k, v) -> snapshot.put(k, v.get()));
        return snapshot;
    }

    /** 复位（报告写出后调用，防止跨套件累积）。 */
    public void reset() {
        lossByCategory.clear();
        totalLoss.set(0);
    }
}
