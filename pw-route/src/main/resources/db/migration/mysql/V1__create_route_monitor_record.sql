-- ROUTE-P1-N2：route_monitor_record 建表（MySQL）。
-- 由 ApiMonitoringRepository.buildDdl 的原内嵌 DDL 外置而来，逐字一致；保留 IF NOT EXISTS 使
-- Flyway baselineOnMigrate 跳过执行时也幂等安全。后续 schema 演进追加 V2__*.sql，不再改动本文件。

CREATE TABLE IF NOT EXISTS route_monitor_record (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    endpoint     VARCHAR(500)  NOT NULL        COMMENT 'api() 配置的 urlPattern',
    request_url  VARCHAR(2000)                 COMMENT '实际请求完整 URL',
    method       VARCHAR(10)   NOT NULL        COMMENT 'HTTP 方法',
    status_code  INT           NOT NULL        COMMENT 'HTTP 状态码',
    req_headers  TEXT                          COMMENT '请求头 (JSON)',
    res_headers  TEXT                          COMMENT '响应头 (JSON)',
    res_body     MEDIUMTEXT                    COMMENT '响应体',
    body_length  INT                           COMMENT '响应体长度 (bytes)',
    captured_at  DATETIME(3)   NOT NULL        COMMENT '捕获时间戳',
    test_run_id  VARCHAR(100)                  COMMENT '测试运行 ID',
    assertion_ok BOOLEAN                       COMMENT '断言是否通过',
    created_at   DATETIME      DEFAULT NOW()   COMMENT '记录创建时间',
    INDEX idx_endpoint   (endpoint),
    INDEX idx_captured   (captured_at),
    INDEX idx_test_run   (test_run_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
