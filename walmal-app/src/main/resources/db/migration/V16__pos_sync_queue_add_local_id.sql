-- V16: add a first-class idempotency key to pos_sync_queue.
--
-- The device-generated localId was previously only buried inside the sale_data
-- JSONB blob, so the offline-sync path had no way to detect a resubmitted batch
-- and would reprocess every payload — double-decrementing stock and potentially
-- resolving the same conflict twice (ADR-6: "a sync operation should be
-- maximally idempotent"). This promotes localId to its own column and enforces
-- at-most-once processing per (terminal, localId) at the DB layer.
--
-- Table ownership: pos_sync_queue is owned exclusively by walmal-pos (see V6).

ALTER TABLE pos_sync_queue
    ADD COLUMN local_id UUID;

-- Backfill existing rows from the JSONB payload, but only where the stored
-- value is a well-formed UUID — older/hand-written rows may hold non-UUID
-- localId values (e.g. test fixtures), which stay NULL rather than fail the cast.
UPDATE pos_sync_queue
SET local_id = (sale_data->>'localId')::uuid
WHERE sale_data->>'localId' ~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$';

-- At most one PROCESSED row per (terminal, localId). A localId may appear on
-- many PENDING/FAILED rows (each resubmission attempt persists a queue row for
-- operator visibility), but can be PROCESSED only once — this is the DB-level
-- idempotency guarantee backing the application-level duplicate check.
-- NULL local_id (legacy/malformed rows) is excluded and never conflicts.
CREATE UNIQUE INDEX ux_pos_sync_processed_local_id
    ON pos_sync_queue (terminal_id, local_id)
    WHERE status = 'PROCESSED' AND local_id IS NOT NULL;
