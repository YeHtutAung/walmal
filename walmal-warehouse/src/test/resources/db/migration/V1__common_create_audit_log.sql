CREATE TABLE audit_log (
    id          UUID PRIMARY KEY,
    table_name  VARCHAR(255) NOT NULL,
    record_id   UUID NOT NULL,
    action      VARCHAR(50) NOT NULL,
    old_value   JSONB,
    new_value   JSONB,
    performed_by VARCHAR(255) NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_audit_log_created_at ON audit_log (created_at);
CREATE INDEX idx_audit_log_table_record ON audit_log (table_name, record_id);
