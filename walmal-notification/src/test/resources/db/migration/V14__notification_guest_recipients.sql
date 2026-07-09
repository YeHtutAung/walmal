-- Guest notifications: recipient identified by email, not user id
ALTER TABLE notification_log
    ALTER COLUMN recipient_id DROP NOT NULL,
    ADD COLUMN recipient_email VARCHAR(320),
    ADD CONSTRAINT chk_notification_recipient
        CHECK (recipient_id IS NOT NULL OR recipient_email IS NOT NULL);

-- Guest orders must be fulfillable: fulfillment has no user account
ALTER TABLE warehouse_fulfillments
    ALTER COLUMN user_id DROP NOT NULL;
