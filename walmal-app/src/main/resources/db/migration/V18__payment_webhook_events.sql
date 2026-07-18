-- Reconciliation log for signature-verified Stripe webhook deliveries
-- (PaymentWebhookController, walmal-order). Not an authorization path —
-- order confirmation already happens synchronously at order-creation time
-- via PaymentGatewayService.verifyPayment. This table is an audit trail:
-- one row per handled event, flagging whether its payment intent matched a
-- known order (MATCHED) or not (UNMATCHED).
CREATE TABLE payment_webhook_events (
    id                 UUID PRIMARY KEY,
    event_id           TEXT NOT NULL UNIQUE,
    event_type         TEXT,
    payment_intent_id  TEXT,
    order_id           UUID NULL,
    status             TEXT,
    received_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);
