package com.hsbc.cmb.hk.dbb.automation.retry.listener;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import com.hsbc.cmb.hk.dbb.automation.retry.configuration.RerunConfiguration;

public class RetryHealthChecker {
    private static final Logger logger = LoggerFactory.getLogger(RetryHealthChecker.class);

    private static final long MAX_ROUND_DURATION_MS = 30 * 60 * 1000;
    private static final long MAX_PROCESS_IDLE_MS = 5 * 60 * 1000;
    private static final int MAX_CONSECUTIVE_FAILURES = 3;

    private final ConcurrentMap<String, HealthIndicator> indicators = new ConcurrentHashMap<>();
    private final AtomicBoolean isHealthy = new AtomicBoolean(true);
    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);
    private final AtomicLong lastActivityTime = new AtomicLong(System.currentTimeMillis());

    private LocalDateTime sessionStartTime;
    private HealthStatus lastKnownStatus = HealthStatus.HEALTHY;

    public enum HealthStatus {
        HEALTHY("HEALTHY"),
        DEGRADED("DEGRADED"),
        UNHEALTHY("UNHEALTHY"),
        CRITICAL("CRITICAL");

        private final String displayName;

        HealthStatus(String displayName) {
            this.displayName = displayName;
        }

        public String getDisplayName() {
            return displayName;
        }
    }

    public void startSession() {
        sessionStartTime = LocalDateTime.now();
        isHealthy.set(true);
        consecutiveFailures.set(0);
        lastActivityTime.set(System.currentTimeMillis());
        lastKnownStatus = HealthStatus.HEALTHY;

        indicators.clear();

        indicators.put("process", new HealthIndicator("Process Status", true));
        indicators.put("memory", new HealthIndicator("Memory Status", true));
        indicators.put("disk", new HealthIndicator("Disk Space", true));
        indicators.put("configuration", new HealthIndicator("Configuration Status", true));
        indicators.put("connectivity", new HealthIndicator("Connectivity Status", true));

        logger.info("Health check monitoring started");
    }

    public void endSession() {
        logger.info("Health check monitoring ended");
    }

    public void recordActivity() {
        lastActivityTime.set(System.currentTimeMillis());
    }

    public void recordRoundStart(int round) {
        recordActivity();
        indicators.get("process").setHealthy(true);
        indicators.get("process").setDetails(String.format("第 %d 轮进行中", round));
        logger.debug("Health check round {} started", round);
    }

    public void recordRoundEnd(int round, boolean success) {
        recordActivity();

        if (success) {
            consecutiveFailures.set(0);
            indicators.get("process").setHealthy(true);
            indicators.get("process").setDetails(String.format("第 %d 轮成功完成", round));
        } else {
            int failures = consecutiveFailures.incrementAndGet();
            indicators.get("process").setHealthy(failures < MAX_CONSECUTIVE_FAILURES);
            indicators.get("process").setDetails(String.format("第 %d 轮失败，连续失败: %d", round, failures));
        }

        updateOverallHealth();
        logger.info("Health check round {} ended - success: {}, status: {}",
            round, success, lastKnownStatus);
    }

    public void checkProcessHealth(Process process) {
        HealthIndicator indicator = indicators.get("process");

        if (process == null) {
            indicator.setHealthy(false);
            indicator.setDetails("Process does not exist");
        } else if (process.isAlive()) {
            long idleTime = System.currentTimeMillis() - lastActivityTime.get();
            if (idleTime > MAX_PROCESS_IDLE_MS) {
                indicator.setHealthy(false);
                indicator.setDetails(String.format("进程空闲时间过长: %d 秒", idleTime / 1000));
            } else {
                indicator.setHealthy(true);
                indicator.setDetails("进程运行正常");
            }
        } else {
            indicator.setHealthy(true);
            indicator.setDetails("Process completed");
        }

        updateOverallHealth();
    }

    public void checkMemoryHealth(Runtime runtime) {
        HealthIndicator indicator = indicators.get("memory");

        long maxMemory = runtime.maxMemory();
        long totalMemory = runtime.totalMemory();
        long freeMemory = runtime.freeMemory();
        long usedMemory = totalMemory - freeMemory;

        double usagePercent = (usedMemory * 100.0) / maxMemory;

        boolean isHealthyMem = usagePercent < 90.0;
        indicator.setHealthy(isHealthyMem);
        indicator.setDetails(String.format("Used %.1f%% (%.1fGB/%.1fGB)",
            usagePercent,
            usedMemory / (1024.0 * 1024.0 * 1024.0),
            maxMemory / (1024.0 * 1024.0 * 1024.0)));

        updateOverallHealth();
    }

    public void checkDiskHealth(String path, long minFreeSpaceBytes) {
        HealthIndicator indicator = indicators.get("disk");

        try {
            java.io.File file = new java.io.File(path);
            long freeSpace = file.getFreeSpace();

            boolean hasEnoughSpace = freeSpace > minFreeSpaceBytes;
            indicator.setHealthy(hasEnoughSpace);
            indicator.setDetails(String.format("Available %.2fGB (threshold: %.2fGB)",
                freeSpace / (1024.0 * 1024.0 * 1024.0),
                minFreeSpaceBytes / (1024.0 * 1024.0 * 1024.0)));
        } catch (Exception e) {
            indicator.setHealthy(false);
            indicator.setDetails("无法检查磁盘空间: " + e.getMessage());
        }

        updateOverallHealth();
    }

    public void checkConfigurationHealth(RerunConfiguration configuration) {
        HealthIndicator indicator = indicators.get("configuration");

        try {
            boolean isValid = configuration != null &&
                configuration.getGluePackage() != null &&
                !configuration.getGluePackage().isEmpty() &&
                configuration.getJavaBinary() != null &&
                !configuration.getJavaBinary().isEmpty();

            indicator.setHealthy(isValid);
            indicator.setDetails(isValid ? "配置有效" : "配置存在缺失");
        } catch (Exception e) {
            indicator.setHealthy(false);
            indicator.setDetails("配置检查失败: " + e.getMessage());
        }

        updateOverallHealth();
    }

    public void recordConnectivityStatus(boolean available, String message) {
        HealthIndicator indicator = indicators.get("connectivity");
        indicator.setHealthy(available);
        indicator.setDetails(message != null ? message : (available ? "连接正常" : "连接失败"));
        updateOverallHealth();
    }

    public void recordFailure(String component, String message) {
        HealthIndicator indicator = indicators.get(component);
        if (indicator != null) {
            indicator.setHealthy(false);
            indicator.setDetails(message);
        }
        updateOverallHealth();
        logger.warn("Health check {} failure: {}", component, message);
    }

    public void recordRecovery(String component, String message) {
        HealthIndicator indicator = indicators.get(component);
        if (indicator != null) {
            indicator.setHealthy(true);
            indicator.setDetails(message);
        }
        updateOverallHealth();
        logger.info("Health check {} recovered: {}", component, message);
    }

    private void updateOverallHealth() {
        long unhealthyCount = indicators.values().stream()
            .filter(i -> !i.isHealthy())
            .count();

        if (unhealthyCount == 0) {
            lastKnownStatus = HealthStatus.HEALTHY;
            isHealthy.set(true);
        } else if (unhealthyCount == 1) {
            lastKnownStatus = HealthStatus.DEGRADED;
            isHealthy.set(true);
        } else if (unhealthyCount < indicators.size() / 2) {
            lastKnownStatus = HealthStatus.UNHEALTHY;
            isHealthy.set(false);
        } else {
            lastKnownStatus = HealthStatus.CRITICAL;
            isHealthy.set(false);
        }
    }

    public HealthStatus getCurrentStatus() {
        return lastKnownStatus;
    }

    public boolean isHealthy() {
        return isHealthy.get();
    }

    public Map<String, HealthIndicator> getIndicators() {
        return Collections.unmodifiableMap(indicators);
    }

    public HealthCheckReport generateReport() {
        List<String> healthyComponents = new ArrayList<>();
        List<String> unhealthyComponents = new ArrayList<>();

        for (Map.Entry<String, HealthIndicator> entry : indicators.entrySet()) {
            if (entry.getValue().isHealthy()) {
                healthyComponents.add(entry.getKey());
            } else {
                unhealthyComponents.add(entry.getKey() + ": " + entry.getValue().getDetails());
            }
        }

        return new HealthCheckReport(
            lastKnownStatus,
            isHealthy.get(),
            healthyComponents.size(),
            unhealthyComponents.size(),
            healthyComponents,
            unhealthyComponents,
            getSessionDuration(),
            consecutiveFailures.get()
        );
    }

    public String getFormattedHealthStatus() {
        HealthCheckReport report = generateReport();

        StringBuilder sb = new StringBuilder();
        sb.append("\n========================================\n");
        sb.append("         健康检查状态\n");
        sb.append("========================================\n");
        sb.append(String.format("总体状态:      %s\n", report.getStatus().getDisplayName()));
        sb.append(String.format("健康组件:      %d/%d\n", report.getHealthyCount(), indicators.size()));
        sb.append(String.format("连续失败:      %d\n", report.getConsecutiveFailures()));
        sb.append(String.format("会话时长:      %s\n", formatDuration(report.getSessionDurationMs())));

        if (!report.getUnhealthyDetails().isEmpty()) {
            sb.append("\n异常组件:\n");
            for (String component : report.getUnhealthyDetails()) {
                sb.append(String.format("  - %s\n", component));
            }
        }

        sb.append("========================================\n");

        return sb.toString();
    }

    private long getSessionDuration() {
        if (sessionStartTime == null) return 0;
        return Duration.between(sessionStartTime, LocalDateTime.now()).toMillis();
    }

    private String formatDuration(long ms) {
        Duration duration = Duration.ofMillis(ms);
        long seconds = duration.getSeconds();
        long minutes = seconds / 60;
        long hours = minutes / 60;
        minutes = minutes % 60;

        if (hours > 0) {
            return String.format("%d hours %d minutes", hours, minutes);
        } else if (minutes > 0) {
            return String.format("%d minutes %d seconds", minutes, seconds % 60);
        }
        return String.format("%d seconds", seconds);
    }

    public static class HealthIndicator {
        private final String name;
        private volatile boolean healthy = true;
        private volatile String details = "Normal";

        public HealthIndicator(String name, boolean healthy) {
            this.name = name;
            this.healthy = healthy;
        }

        public String getName() {
            return name;
        }

        public boolean isHealthy() {
            return healthy;
        }

        public void setHealthy(boolean healthy) {
            this.healthy = healthy;
        }

        public String getDetails() {
            return details;
        }

        public void setDetails(String details) {
            this.details = details;
        }

        @Override
        public String toString() {
            return String.format("[%s] %s - %s",
                healthy ? "" : "", name, details);
        }
    }

    public static class HealthCheckReport {
        private final HealthStatus status;
        private final boolean healthy;
        private final int healthyCount;
        private final int unhealthyCount;
        private final List<String> healthyComponents;
        private final List<String> unhealthyDetails;
        private final long sessionDurationMs;
        private final int consecutiveFailures;

        public HealthCheckReport(HealthStatus status, boolean healthy,
                                int healthyCount, int unhealthyCount,
                                List<String> healthyComponents,
                                List<String> unhealthyDetails,
                                long sessionDurationMs, int consecutiveFailures) {
            this.status = status;
            this.healthy = healthy;
            this.healthyCount = healthyCount;
            this.unhealthyCount = unhealthyCount;
            this.healthyComponents = healthyComponents;
            this.unhealthyDetails = unhealthyDetails;
            this.sessionDurationMs = sessionDurationMs;
            this.consecutiveFailures = consecutiveFailures;
        }

        public HealthStatus getStatus() {
            return status;
        }

        public boolean isHealthy() {
            return healthy;
        }

        public int getHealthyCount() {
            return healthyCount;
        }

        public int getUnhealthyCount() {
            return unhealthyCount;
        }

        public List<String> getHealthyComponents() {
            return healthyComponents;
        }

        public List<String> getUnhealthyDetails() {
            return unhealthyDetails;
        }

        public long getSessionDurationMs() {
            return sessionDurationMs;
        }

        public int getConsecutiveFailures() {
            return consecutiveFailures;
        }
    }
}
