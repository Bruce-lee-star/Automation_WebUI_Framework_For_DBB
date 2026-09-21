package com.hsbc.cmb.hk.dbb.automation.framework.api.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 框架级安全审计记录器（线程安全、快照不可变）。
 *
 * <p>集中记录安全相关事件，使「放宽安全基线」这类动作具备可审计、可观测的轨迹，
 * 而非仅散落一条 {@code logger.warn}（易被日志级别吞掉、无法被测试断言）。
 *
 * <p>当前覆盖的事件：
 * <ul>
 *   <li>{@code RELAXED_TLS} — 放宽 TLS 证书校验（{@code http.ssl.relax-validation=true}），
 *   使 API 流量暴露于中间人攻击风险。</li>
 * </ul>
 *
 * <p>约定：
 * <ul>
 *   <li>仅记录安全事件，不做流程控制（调用方自行决定是否 fail-fast）；</li>
 *   <li>{@link #recordedEvents()} 返回不可变快照，供测试与可观测断言；</li>
 *   <li>{@link #reset()} 仅供测试隔离使用，非业务 API。</li>
 * </ul>
 *
 * @apiNote 这是框架内部安全审计能力，业务代码不应依赖其存在性做断言或流程分支。
 */
public final class SecurityAudit {

    private static final Logger LOGGER = LoggerFactory.getLogger(SecurityAudit.class);

    private static final List<SecurityEvent> EVENTS = new CopyOnWriteArrayList<>();

    private SecurityAudit() {
    }

    /**
     * 记录一次「放宽 TLS 证书校验」安全事件。
     *
     * @param environment 当前运行环境名（可为 {@code null}，表示未显式指定）
     * @param configKey   触发放宽的配置键名
     */
    public static void recordRelaxedTls(String environment, String configKey) {
        String env = (environment == null || environment.isBlank()) ? "<unspecified>" : environment;
        String message = "TLS certificate validation RELAXED (config=" + configKey
                + ", environment=" + env + "). API traffic is exposed to MITM.";
        SecurityEvent event = new SecurityEvent(Instant.now(), "RELAXED_TLS", message);
        EVENTS.add(event);
        LOGGER.warn("[SECURITY AUDIT] {}", message);
    }

    /**
     * 判定给定环境名是否为生产（prod）环境（大小写不敏感）。
     *
     * <p>匹配 {@code prod} / {@code production} / {@code prd}；空或 null 视为非生产。
     *
     * @param environment 环境名（可空）
     * @return 是否为生产环境
     */
    public static boolean isProductionEnvironment(String environment) {
        if (environment == null || environment.isBlank()) {
            return false;
        }
        String normalized = environment.trim().toLowerCase(Locale.ROOT);
        return normalized.equals("prod") || normalized.equals("production") || normalized.equals("prd");
    }

    /**
     * 返回已记录安全事件的不可变快照（按记录顺序）。
     *
     * @return 不可变事件列表
     */
    public static List<SecurityEvent> recordedEvents() {
        return Collections.unmodifiableList(EVENTS);
    }

    /**
     * 已记录安全事件数量。
     *
     * @return 事件数
     */
    public static int count() {
        return EVENTS.size();
    }

    /**
     * 清空记录（仅供单元测试隔离使用，非业务 API）。
     */
    public static void reset() {
        EVENTS.clear();
    }

    /**
     * 不可变安全事件记录。
     *
     * @param timestamp 事件发生时间
     * @param type      事件类型（如 {@code RELAXED_TLS}）
     * @param message   人类可读描述
     */
    public record SecurityEvent(Instant timestamp, String type, String message) {
    }
}
