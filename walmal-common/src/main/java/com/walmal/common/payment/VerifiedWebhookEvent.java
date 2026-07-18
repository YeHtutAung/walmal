package com.walmal.common.payment;

/**
 * Gateway-agnostic result of a verified payment-gateway webhook event — exactly
 * the fields the order module needs to reconcile it, with no gateway-specific
 * SDK type (e.g. Stripe's {@code Event}) crossing the module boundary.
 *
 * <p>{@code paymentIntentId} is extracted by the implementation from the
 * event's raw JSON payload ({@code data.object.id}), never via a typed/
 * polymorphic deserializer (Stripe's {@code getDataObjectDeserializer()}
 * silently returns an empty Optional on any API-version mismatch between the
 * event and the pinned SDK version — a real risk with dashboard-resent events
 * and any hand-crafted payload). It may be {@code null} for event types whose
 * data object carries no {@code id}, or where the field is absent.</p>
 *
 * @param eventId         gateway's unique event id — the idempotency key for
 *                        deduplicating retried deliveries
 * @param eventType       gateway's event type string (e.g.
 *                        {@code payment_intent.succeeded})
 * @param paymentIntentId the payment intent id the event concerns, or null
 */
public record VerifiedWebhookEvent(String eventId, String eventType, String paymentIntentId) {}
