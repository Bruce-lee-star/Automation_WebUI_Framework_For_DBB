-- ROUTE-P1-N2（扩展至 SQL Server）：route_monitor_record 建表。
-- 由 ApiMonitoringRepository 原内嵌 DDL 按 SQL Server 语义对齐外置而来，落库字段与三方言版本保持一致。
-- 保留 baselineOnMigrate 跳过执行语义；SQL Server 不支持 CREATE TABLE IF NOT EXISTS，
-- 旧库迁移完全依赖 Flyway 基线（schema 非空即跳过），故不写 IF NOT EXISTS。

CREATE TABLE route_monitor_record (
    id           BIGINT IDENTITY(1,1) PRIMARY KEY,
    endpoint     VARCHAR(500) NOT NULL,
    request_url  VARCHAR(2000),
    method       VARCHAR(10) NOT NULL,
    status_code  INT NOT NULL,
    req_headers  VARCHAR(MAX),
    res_headers  VARCHAR(MAX),
    res_body     VARCHAR(MAX),
    body_length  INT,
    captured_at  DATETIME2(3) NOT NULL,
    test_run_id  VARCHAR(100),
    assertion_ok BIT,
    created_at   DATETIME2 DEFAULT SYSUTCDATETIME()
);

CREATE INDEX idx_endpoint ON route_monitor_record(endpoint);
CREATE INDEX idx_captured ON route_monitor_record(captured_at);
CREATE INDEX idx_test_run ON route_monitor_record(test_run_id);
