-- V8: Notification module tables
-- Owned exclusively by walmal-notification.
-- No FK constraints to other modules' tables (bounded context rule).

CREATE TABLE notification_log (
    id              UUID        NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    recipient_id    UUID        NOT NULL,           -- auth_users.id (no FK — cross-module boundary)
    type            VARCHAR(20) NOT NULL CHECK (type IN ('EMAIL', 'IN_APP')),
    status          VARCHAR(20) NOT NULL CHECK (status IN ('PENDING', 'SENT', 'FAILED')),
    subject         VARCHAR(500) NOT NULL,
    body            TEXT        NOT NULL,
    error_message   TEXT,
    trigger_event   VARCHAR(100) NOT NULL,          -- routing key that triggered this notification
    reference_id    UUID,                           -- order/variant/etc. for quick lookup (no FK)
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_notification_log_recipient ON notification_log (recipient_id);
CREATE INDEX idx_notification_log_status    ON notification_log (status);
CREATE INDEX idx_notification_log_reference ON notification_log (reference_id) WHERE reference_id IS NOT NULL;

-- notification_templates: message templates keyed by event + type
CREATE TABLE notification_templates (
    id           UUID        NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    event_key    VARCHAR(100) NOT NULL,  -- routing key (e.g. 'order.confirmed')
    type         VARCHAR(20) NOT NULL CHECK (type IN ('EMAIL', 'IN_APP')),
    subject      VARCHAR(500) NOT NULL,
    body_template TEXT       NOT NULL,   -- Mustache/plain-text template; {{variable}} placeholders
    is_active    BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_notification_template_event_type UNIQUE (event_key, type)
);

-- Seed default templates
INSERT INTO notification_templates (event_key, type, subject, body_template) VALUES
('order.confirmed',    'EMAIL',  'Your order has been confirmed',
 'Hi {{username}}, your order {{orderId}} has been confirmed and is being processed.'),
('order.confirmed',    'IN_APP', 'Order confirmed',
 'Your order {{orderId}} has been confirmed.'),
('order.cancelled',    'EMAIL',  'Your order has been cancelled',
 'Hi {{username}}, your order {{orderId}} has been cancelled. {{cancellationNote}}'),
('order.cancelled',    'IN_APP', 'Order cancelled',
 'Your order {{orderId}} has been cancelled. {{cancellationNote}}'),
('warehouse.fulfillment.shipped', 'EMAIL',  'Your order has been shipped',
 'Hi {{username}}, your order {{orderId}} has been shipped via {{carrier}}. Tracking: {{trackingNumber}}'),
('warehouse.fulfillment.shipped', 'IN_APP', 'Order shipped',
 'Your order {{orderId}} has been shipped. Tracking: {{trackingNumber}}'),
('inventory.stock.low', 'IN_APP', 'Low stock alert',
 'Variant {{variantId}} at location {{locationId}} is below threshold: {{availableQuantity}} remaining (threshold: {{threshold}}).'),
('auth.user.registered', 'EMAIL', 'Welcome to Walmal',
 'Hi {{username}}, welcome to Walmal! Your account has been created successfully.'),
('pos.sync.conflict.resolved', 'EMAIL', 'Your order was affected by a POS sync conflict',
 'Hi {{username}}, your order {{orderId}} was affected by an in-store sale conflict and has been cancelled. We apologize for the inconvenience.');
