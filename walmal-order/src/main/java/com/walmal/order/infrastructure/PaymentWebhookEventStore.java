package com.walmal.order.infrastructure;

import com.walmal.order.domain.WebhookEventStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.UUID;

/**
 * Persists {@code payment_webhook_events} reconciliation rows.
 *
 * <p>Plain {@link JdbcTemplate} rather than a Spring Data {@code JpaRepository}
 * — this table is a write-mostly log (same shape as {@code audit_log}, which
 * follows the identical pattern), not a domain aggregate the rest of the
 * order module needs to query through an entity.</p>
 */
@Repository
public class PaymentWebhookEventStore {

    private final JdbcTemplate jdbcTemplate;

    public PaymentWebhookEventStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Inserts a reconciliation row for {@code eventId} unless one already
     * exists. Idempotent by construction: {@code ON CONFLICT (event_id) DO
     * NOTHING} against the table's unique constraint, so Stripe's
     * at-least-once webhook delivery (retries on any non-2xx, and dashboard
     * "resend") can never create a second row for the same event — this is
     * a DB-level guarantee, not a check-then-insert race.
     *
     * @return {@code true} if a new row was inserted, {@code false} if
     *         {@code eventId} was already recorded
     */
    public boolean insertIfAbsent(String eventId, String eventType, String paymentIntentId,
                                   UUID orderId, WebhookEventStatus status) {
        // received_at is left to the column's DEFAULT now() rather than passed as a
        // parameter — a plain JdbcTemplate.update(Object...) can't infer a SQL type
        // for java.time.Instant (PSQLException: "Can't infer the SQL type"); letting
        // Postgres stamp it avoids needing an explicit java.sql.Timestamp conversion.
        int rowsInserted = jdbcTemplate.update(
                "INSERT INTO payment_webhook_events "
                + "(id, event_id, event_type, payment_intent_id, order_id, status) "
                + "VALUES (?, ?, ?, ?, ?, ?) "
                + "ON CONFLICT (event_id) DO NOTHING",
                UUID.randomUUID(), eventId, eventType, paymentIntentId, orderId, status.name());
        return rowsInserted > 0;
    }
}
