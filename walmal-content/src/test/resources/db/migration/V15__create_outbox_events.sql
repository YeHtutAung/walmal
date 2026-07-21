-- Transactional outbox for domain events (see docs/superpowers/specs/2026-07-09-transactional-outbox-design.md).
-- Rows are written in the business transaction and deleted by OutboxRelay after
-- successful publish. status: PENDING (awaiting delivery) | FAILED (60 attempts
-- exhausted; operator recovery: UPDATE outbox_events SET status='PENDING', attempts=0 WHERE status='FAILED').
CREATE TABLE outbox_events (
    id          UUID PRIMARY KEY,
    exchange    VARCHAR(100) NOT NULL,
    routing_key VARCHAR(100) NOT NULL,
    payload     TEXT NOT NULL,
    status      VARCHAR(20)  NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'FAILED')),
    attempts    INT          NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    last_error  TEXT,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_outbox_pending ON outbox_events (created_at) WHERE status = 'PENDING';
