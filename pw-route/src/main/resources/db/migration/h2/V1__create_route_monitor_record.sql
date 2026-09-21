-- ROUTE-P1-N2：route_monitor_record 建表（H2，测试/内存库用）。
-- 由 ApiMonitoringRepository.buildDdl 的原内嵌 DDL 外置而来，逐字一致；保留 IF NOT EXISTS 使
-- Flyway baselineOnMigrate 跳过执行时也幂等安全。后续 schema 演进追加 V2__*.sql，不再改动本文件。

CREATE TABLE IF NOT EXISTS route_monitor_record (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    endpoint     VARCHAR(500)  NOT NULL,
    request_url  VARCHAR(2000),
    method       VARCHAR(10)   NOT NULL,
    status_code  INT           NOT NULL,
    req_headers  VARCHAR(20000),
    res_headers  VARCHAR(20000),
    res_body     VARCHAR(200000),
    body_length  INT,
    captured_at  TIMESTAMP(3)  NOT NULL,
    test_run_id  VARCHAR(100),
    assertion_ok BOOLEAN,
    created_at   TIMESTAMP DEFAULT NOW()
);
