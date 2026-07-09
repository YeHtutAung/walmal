package com.walmal.infrastructure.messaging;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * JDBC access to the {@code outbox_events} table (V15).
 *
 * <p>Deliberately not a JPA entity: the outbox is infrastructure plumbing, and
 * plain SQL keeps {@code FOR UPDATE SKIP LOCKED} and the partial index on
 * PENDING rows explicit.</p>
 */
@Repository
public class OutboxRepository {

    private final JdbcTemplate jdbc;

    public OutboxRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Inserts a PENDING row. Default REQUIRED propagation: joins the caller's
     * transaction when one is active (rollback removes the row — preserving the
     * "no publish on rollback" guarantee), otherwise runs in its own.
     */
    @Transactional
    public void insert(UUID id, String exchange, String routingKey, String payload) {
        jdbc.update(
                "INSERT INTO outbox_events (id, exchange, routing_key, payload) VALUES (?, ?, ?, ?)",
                id, exchange, routingKey, payload);
    }

    /**
     * Locks and returns the oldest PENDING rows. Must be called inside an active
     * transaction (the relay tick) for {@code FOR UPDATE SKIP LOCKED} to hold.
     */
    public List<OutboxEventRow> lockPendingBatch(int limit) {
        return jdbc.query(
                "SELECT id, exchange, routing_key, payload, attempts FROM outbox_events " +
                "WHERE status = 'PENDING' ORDER BY created_at LIMIT ? FOR UPDATE SKIP LOCKED",
                (rs, i) -> new OutboxEventRow(
                        rs.getObject("id", UUID.class),
                        rs.getString("exchange"),
                        rs.getString("routing_key"),
                        rs.getString("payload"),
                        rs.getInt("attempts")),
                limit);
    }

    public void delete(UUID id) {
        jdbc.update("DELETE FROM outbox_events WHERE id = ?", id);
    }

    /**
     * Records a failed send attempt. When {@code exhausted} is true the row is
     * parked as FAILED and no longer selected by {@link #lockPendingBatch}.
     */
    public void recordFailure(UUID id, int attempts, String lastError, boolean exhausted) {
        jdbc.update(
                "UPDATE outbox_events SET attempts = ?, last_error = ?, status = ? WHERE id = ?",
                attempts, lastError, exhausted ? "FAILED" : "PENDING", id);
    }
}
